package com.hospital.escort.service

import com.hospital.escort.api.ApplyRequest
import com.hospital.escort.api.CancelTemporaryReplacementRequest
import com.hospital.escort.api.CompleteHandoverRequest
import com.hospital.escort.api.CompletePatrolRequest
import com.hospital.escort.api.NewEscortArriveRequest
import com.hospital.escort.api.OldEscortLeaveRequest
import com.hospital.escort.api.SchedulePatrolRequest
import com.hospital.escort.api.TemporaryReplacementApprovalRequest
import com.hospital.escort.api.TemporaryReplacementRequest
import com.hospital.escort.common.badRequest
import com.hospital.escort.common.conflict
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.ApplicationStatus
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.EscortApplicationRepository
import com.hospital.escort.domain.PatrolRecord
import com.hospital.escort.domain.PatrolRecordRepository
import com.hospital.escort.domain.PatrolStatus
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.ReplacementMode
import com.hospital.escort.domain.ReplacementRecord
import com.hospital.escort.domain.ReplacementRecordRepository
import com.hospital.escort.domain.ReplacementStatus
import com.hospital.escort.domain.ScreeningType
import com.hospital.escort.domain.SyncAction
import com.hospital.escort.domain.WardPolicyRepository
import com.hospital.escort.sync.DownstreamSyncService
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 陪护人临时替换（原陪护人夜间发烧等紧急场景）的两阶段交接。
 *
 * 状态机（mode=TEMPORARY）:
 *  家属申请 -> APPROVAL_PENDING
 *  护士站核验身份信息/健康承诺/与患者关系后批准 -> APPROVED（旧陪护仍在照护, 旧证仍有效）
 *  旧陪护离院 -> OLD_LEFT（旧证冻结、门禁停权, 患者进入"短时无人陪护"空窗, 护士站可安排巡查/看护提醒）
 *  新陪护到院 -> NEW_ARRIVED（记录到院时间）
 *  现场身份复核通过 -> COMPLETED（旧证 REPLACED, 新证签发, 看护提醒转交家属, 记录交接完成时间）
 *  任一未完成阶段均可取消（旧陪护退烧返回等）。
 */
@Service
class TemporaryReplacementService(
    private val replacementRepository: ReplacementRecordRepository,
    private val applicationRepository: EscortApplicationRepository,
    private val applicationService: ApplicationService,
    private val permitService: PermitService,
    private val caseService: EscortCaseService,
    private val patrolRepository: PatrolRecordRepository,
    private val wardPolicyRepository: WardPolicyRepository,
    private val syncService: DownstreamSyncService,
    @Lazy private val careReminderService: CareReminderService
) {

    /** 1. 家属申请临时替换: 登记新陪护人身份、健康承诺、与患者关系, 待护士站核验 */
    @Transactional
    fun requestTemporary(req: TemporaryReplacementRequest): ReplacementRecord {
        val oldPermit = permitService.getByPermitNo(req.oldPermitNo)
        if (oldPermit.status != PermitStatus.ACTIVE && oldPermit.status != PermitStatus.FROZEN) {
            conflict("原陪护证状态(${oldPermit.status})不允许临时替换")
        }
        val escortCase = caseService.getById(oldPermit.caseId)
        val policy = wardPolicyRepository.findById(escortCase.wardCode).orElse(null)
            ?: notFound("病区不存在: ${escortCase.wardCode}")
        if (policy.lockdown) conflict("病区[${escortCase.wardCode}]封闭管理中, 暂停陪护替换申请")

        val active = replacementRepository.findByCaseIdAndModeAndStatusIn(
            escortCase.id!!, ReplacementMode.TEMPORARY, INCOMPLETE_STATUSES
        )
        if (active.isNotEmpty()) conflict("该患者已有进行中的临时替换(#${active.first().id}), 请先完成或取消")

        if (req.healthCommitment != true) badRequest("新陪护人必须确认健康承诺")
        if (req.escortIdNo == oldPermit.escortIdNo) badRequest("新陪护人不能与原陪护人为同一人")

        val now = Instant.now()
        val applyReq = ApplyRequest(
            patientInpatientNo = escortCase.patientInpatientNo,
            wardCode = escortCase.wardCode,
            bedNo = escortCase.bedNo,
            escortName = req.escortName,
            escortIdNo = req.escortIdNo,
            escortPhone = req.escortPhone,
            relationToPatient = req.relationToPatient,
            healthCommitment = true,
            screeningType = req.screeningType,
            screeningValidUntil = req.screeningValidUntil,
            periodStart = now,
            periodEnd = req.periodEnd ?: oldPermit.validUntil,
            needNightCare = req.needNightCare
        )
        val newApplication = applicationService.applyReplacementDirect(applyReq, oldPermit.applicationId)

        val record = replacementRepository.save(
            ReplacementRecord(
                caseId = escortCase.id!!,
                oldPermitId = oldPermit.id,
                newApplicationId = newApplication.id!!,
                reason = req.reason,
                detail = req.detail,
                status = ReplacementStatus.APPROVAL_PENDING,
                mode = ReplacementMode.TEMPORARY
            )
        )
        caseService.recordEvent(
            escortCase.id!!, CaseEventType.TEMP_REPLACEMENT_REQUESTED, req.escortName,
            "申请临时替换陪护人(紧急): ${oldPermit.escortName} -> ${req.escortName}, " +
                    "关系=${req.relationToPatient} (${req.reason.name})",
            req.detail + req.expectedArriveAt?.let { " | 新陪护预计到院 $it" },
            "REPLACEMENT", record.id
        )
        caseService.notifyWard(
            escortCase.wardCode, escortCase.id, oldPermit.id, "TEMP_REPLACEMENT_PENDING",
            "紧急陪护替换申请: 患者[${escortCase.patientInpatientNo}]原陪护 ${oldPermit.escortName} " +
                    "因${req.reason.name}不能继续, 申请 ${req.escortName}(关系:${req.relationToPatient})接替, 待护士站核验" +
                    req.expectedArriveAt?.let { ", 预计到院 $it" }
        )
        return record
    }

    /** 2. 护士站核验: 新陪护身份信息、健康承诺/筛查、与患者关系; 夜间陪护专项审批 */
    @Transactional
    fun approve(replacementId: Long, req: TemporaryReplacementApprovalRequest): ReplacementRecord {
        val record = getRecord(replacementId)
        if (record.status != ReplacementStatus.APPROVAL_PENDING) conflict("临时替换当前状态(${record.status})不允许审批")
        val application = applicationRepository.findById(record.newApplicationId).orElseThrow()
        val escortCase = caseService.getById(record.caseId)

        if (!req.approved) {
            record.status = ReplacementStatus.REJECTED
            record.decidedAt = Instant.now()
            record.decidedBy = req.decidedBy
            application.status = ApplicationStatus.REJECTED
            application.decidedAt = Instant.now()
            application.decidedBy = req.decidedBy
            application.decisionNote = req.note
            applicationRepository.save(application)
            val saved = replacementRepository.save(record)
            caseService.recordEvent(record.caseId, CaseEventType.REPLACEMENT_REJECTED, req.decidedBy,
                "临时替换申请被驳回", req.note, "REPLACEMENT", record.id)
            caseService.notifyWard(escortCase.wardCode, record.caseId, null, "TEMP_REPLACEMENT_REJECTED",
                "患者[${escortCase.patientInpatientNo}]临时陪护替换被驳回: ${req.note ?: ""}")
            return saved
        }

        if (!req.relationVerified) badRequest("请核验并确认新陪护人与患者的关系(relationVerified=true)")
        if (!req.healthVerified) badRequest("请核验并确认新陪护人健康承诺/筛查合格(healthVerified=true)")
        val policy = wardPolicyRepository.findById(escortCase.wardCode).orElse(null)
            ?: notFound("病区不存在: ${escortCase.wardCode}")
        if (policy.lockdown) conflict("病区封闭管理中, 不能批准陪护替换")
        if (application.screeningType != ScreeningType.NONE &&
            application.screeningValidUntil != null &&
            !application.screeningValidUntil!!.isAfter(Instant.now())
        ) {
            conflict("新陪护人筛查结果已过期, 不能批准")
        }
        if (application.needNightCare && policy.nightEscortRequiresApproval && !req.approveNightCare) {
            badRequest("该临时替换包含夜间陪护, 需夜间陪护专项审批(approveNightCare=true)")
        }

        application.status = ApplicationStatus.APPROVED
        application.decidedAt = Instant.now()
        application.decidedBy = req.decidedBy
        application.decisionNote = req.note
        applicationRepository.save(application)

        record.status = ReplacementStatus.APPROVED
        record.relationVerified = true
        record.healthVerified = true
        record.nightCareApproved = application.needNightCare && req.approveNightCare
        record.decidedAt = Instant.now()
        record.decidedBy = req.decidedBy
        val saved = replacementRepository.save(record)

        caseService.recordEvent(
            record.caseId, CaseEventType.TEMP_REPLACEMENT_APPROVED, req.decidedBy,
            "临时替换已核验批准: 新陪护 ${application.escortName}(关系:${application.relationToPatient}), " +
                    "身份信息/健康承诺/患者关系核验通过, 等待旧陪护离院",
            req.note, "REPLACEMENT", record.id
        )
        caseService.notifyWard(
            escortCase.wardCode, record.caseId, record.oldPermitId, "TEMP_REPLACEMENT_APPROVED",
            "患者[${escortCase.patientInpatientNo}]临时替换已批准: ${application.escortName}(关系:${application.relationToPatient})" +
                    "已通过护士站核验, 旧陪护离院时请登记交接"
        )
        return saved
    }

    /** 3. 旧陪护离院: 旧证冻结(门禁停权), 患者进入短时无人陪护空窗 */
    @Transactional
    fun oldEscortLeave(replacementId: Long, req: OldEscortLeaveRequest): ReplacementRecord {
        val record = getRecord(replacementId)
        if (record.status != ReplacementStatus.APPROVED) {
            conflict("仅已核验批准(APPROVED)的临时替换可登记旧陪护离院, 当前: ${record.status}")
        }
        val oldPermit = record.oldPermitId?.let { permitService.getById(it) }
        val escortCase = caseService.getById(record.caseId)
        val leftAt = req.leftAt ?: Instant.now()

        if (oldPermit != null && oldPermit.status == PermitStatus.ACTIVE) {
            // 冻结而非直接 REPLACED: 交接取消(旧陪护退烧返回)时尚可恢复
            oldPermit.status = PermitStatus.FROZEN
            oldPermit.freezeReason = "临时替换: 旧陪护离院, 新陪护尚未到院"
            oldPermit.frozenAt = leftAt
            oldPermit.frozenBy = req.operator
            oldPermit.lockdownFrozen = false
            permitService.saveDirect(oldPermit)
            syncService.syncPermit(oldPermit, escortCase.caseNo, SyncAction.FREEZE)
        }

        record.status = ReplacementStatus.OLD_LEFT
        record.oldEscortLeftAt = leftAt
        replacementRepository.save(record)

        caseService.recordEvent(
            record.caseId, CaseEventType.OLD_ESCORT_LEFT, req.operator,
            "旧陪护 ${oldPermit?.escortName ?: "?"} 已于 $leftAt 离院, 患者进入短时无人陪护状态, 等待新陪护到院",
            req.note, "REPLACEMENT", record.id
        )
        // 患者当前无登记照护人, 护士站据此安排巡查
        caseService.updateCurrentEscort(
            escortCase, null, null, null, null, req.operator,
            "临时替换空窗: 旧陪护 ${oldPermit?.escortName} 已离院, 新陪护未到"
        )
        caseService.notifyWard(
            escortCase.wardCode, record.caseId, null, "UNATTENDED_START",
            "患者[${escortCase.patientInpatientNo}]床位${escortCase.bedNo ?: ""}自 $leftAt 起短时无人陪护, " +
                    "请护士站安排巡查并关注进食/翻身/夜间观察等照护需求"
        )
        return record
    }

    /** 4. 新陪护到院: 记录到院时间(空窗结束), 等待现场身份复核 */
    @Transactional
    fun newEscortArrive(replacementId: Long, req: NewEscortArriveRequest): ReplacementRecord {
        val record = getRecord(replacementId)
        if (record.status != ReplacementStatus.OLD_LEFT) {
            conflict("仅旧陪护已离院(OLD_LEFT)的临时替换可登记新陪护到院, 当前: ${record.status}")
        }
        val escortCase = caseService.getById(record.caseId)
        val application = applicationRepository.findById(record.newApplicationId).orElseThrow()
        val arrivedAt = req.arrivedAt ?: Instant.now()

        record.status = ReplacementStatus.NEW_ARRIVED
        record.newEscortArrivedAt = arrivedAt
        val saved = replacementRepository.save(record)

        caseService.recordEvent(
            record.caseId, CaseEventType.NEW_ESCORT_ARRIVED, req.operator,
            "新陪护 ${application.escortName} 已于 $arrivedAt 到院, 等待护士站现场身份复核",
            "空窗时长(分钟)=${java.time.Duration.between(record.oldEscortLeftAt, arrivedAt).toMinutes()}",
            "REPLACEMENT", record.id
        )
        caseService.notifyWard(
            escortCase.wardCode, record.caseId, null, "NEW_ESCORT_ARRIVED",
            "患者[${escortCase.patientInpatientNo}]的接替家属 ${application.escortName} 已到院, 请现场核验身份证后完成交接"
        )
        return saved
    }

    /** 5. 现场身份复核通过: 旧证 REPLACED, 新证签发, 看护提醒转交家属, 记录交接完成时间 */
    @Transactional
    fun completeHandover(replacementId: Long, req: CompleteHandoverRequest): ReplacementRecord {
        val record = getRecord(replacementId)
        if (record.status != ReplacementStatus.NEW_ARRIVED) {
            conflict("仅新陪护已到院(NEW_ARRIVED)的临时替换可完成交接, 当前: ${record.status}")
        }
        if (!req.identityVerified) {
            badRequest("请现场核验到场者身份证与申请的新陪护人一致(identityVerified=true)")
        }
        val application = applicationRepository.findById(record.newApplicationId).orElseThrow()
        val oldPermit = record.oldPermitId?.let { permitService.getById(it) }
        val escortCase = caseService.getById(record.caseId)
        val completedAt = req.completedAt ?: Instant.now()

        // 旧证(空窗期冻结) -> REPLACED, 门禁即时失效
        if (oldPermit != null && oldPermit.status != PermitStatus.RETURNED &&
            oldPermit.status != PermitStatus.REVOKED && oldPermit.status != PermitStatus.REPLACED
        ) {
            oldPermit.status = PermitStatus.REPLACED
            permitService.saveDirect(oldPermit)
            caseService.recordEvent(record.caseId, CaseEventType.PERMIT_REPLACED, req.completedBy,
                "旧陪护证 ${oldPermit.permitNo} 因临时替换交接完成作废", null, "PERMIT", oldPermit.id)
            syncService.syncPermit(oldPermit, escortCase.caseNo, SyncAction.REVOKE)
        }

        // 新证签发: issuePermit 会更新"当前照护人"快照并同步 GATE/WARD_SCAN/SECURITY_PATROL
        val newPermit = permitService.issuePermit(
            application, escortCase, req.completedBy, nightCareApproved = record.nightCareApproved
        )

        record.status = ReplacementStatus.COMPLETED
        record.newPermitId = newPermit.id
        record.identityVerified = true
        record.handoverCompletedAt = completedAt
        record.completedBy = req.completedBy
        val saved = replacementRepository.save(record)

        caseService.recordEvent(
            record.caseId, CaseEventType.TEMP_HANDOVER_COMPLETED, req.completedBy,
            "临时替换交接完成: ${oldPermit?.escortName ?: "?"} -> ${newPermit.escortName}, 新证 ${newPermit.permitNo}",
            "旧陪护离院=${record.oldEscortLeftAt}, 新陪护到院=${record.newEscortArrivedAt}, " +
                    "交接完成=$completedAt; 身份/健康/关系核验通过" +
                    (req.note?.let { "; $it" } ?: ""),
            "REPLACEMENT", record.id
        )

        // 空窗期待处理的看护提醒整批转交家属, 保留交接时间
        val handedOver = careReminderService.handOverToFamily(record.id!!, newPermit, newPermit.escortName)

        caseService.notifyWard(
            escortCase.wardCode, record.caseId, newPermit.id, "TEMP_HANDOVER_COMPLETED",
            "患者[${escortCase.patientInpatientNo}]交接完成: 现由 ${newPermit.escortName} 照护(证号 ${newPermit.permitNo}), " +
                    "交接时间 $completedAt" + (if (handedOver > 0) ", $handedOver 条看护提醒已转交家属" else "")
        )
        return saved
    }

    /** 取消临时替换: 旧陪护退烧返回/新陪护核验失败等; 清理空窗期巡查安排 */
    @Transactional
    fun cancel(replacementId: Long, req: CancelTemporaryReplacementRequest): ReplacementRecord {
        val record = getRecord(replacementId)
        if (record.status == ReplacementStatus.COMPLETED || record.status == ReplacementStatus.CANCELLED ||
            record.status == ReplacementStatus.REJECTED
        ) {
            conflict("临时替换当前状态(${record.status})不允许取消")
        }
        val escortCase = caseService.getById(record.caseId)
        val application = applicationRepository.findById(record.newApplicationId).orElseThrow()
        val oldPermit = record.oldPermitId?.let { permitService.getById(it) }

        // 未完成的巡查安排全部取消
        patrolRepository.findByReplacementIdOrderByScheduledAtAsc(replacementId)
            .filter { it.status == PatrolStatus.SCHEDULED }
            .forEach {
                it.status = PatrolStatus.CANCELLED
                patrolRepository.save(it)
            }

        when (record.status) {
            ReplacementStatus.OLD_LEFT -> {
                // 旧陪护退烧返回: 恢复旧证, 照护人快照恢复
                if (oldPermit != null && oldPermit.status == PermitStatus.FROZEN) {
                    oldPermit.status = PermitStatus.ACTIVE
                    oldPermit.freezeReason = null
                    oldPermit.frozenAt = null
                    oldPermit.frozenBy = null
                    permitService.saveDirect(oldPermit)
                    syncService.syncPermit(oldPermit, escortCase.caseNo, SyncAction.UNFREEZE)
                    caseService.updateCurrentEscort(
                        escortCase, oldPermit.id, oldPermit.escortName, oldPermit.escortIdNo, oldPermit.escortPhone,
                        req.operator, "临时替换取消, 旧陪护 ${oldPermit.escortName} 返岗"
                    )
                }
            }
            ReplacementStatus.NEW_ARRIVED -> {
                // 新陪护到院但交接未成(如身份核验失败): 患者仍无人陪护, 提醒护士站另行安排
                caseService.notifyWard(
                    escortCase.wardCode, record.caseId, null, "UNATTENDED_START",
                    "患者[${escortCase.patientInpatientNo}]临时替换取消但新陪护未完成交接, 患者仍无人陪护, 请立即安排"
                )
            }
            else -> {
                // APPROVAL_PENDING / APPROVED: 旧陪护本就在岗, 旧证不受影响
                if (escortCase.currentPermitId == null && oldPermit != null) {
                    caseService.updateCurrentEscort(
                        escortCase, oldPermit.id, oldPermit.escortName, oldPermit.escortIdNo, oldPermit.escortPhone,
                        req.operator, "临时替换取消"
                    )
                }
            }
        }

        application.status = ApplicationStatus.WITHDRAWN
        application.decidedAt = Instant.now()
        application.decidedBy = req.operator
        application.decisionNote = "临时替换取消: ${req.reason}"
        applicationRepository.save(application)

        record.status = ReplacementStatus.CANCELLED
        record.cancelledAt = Instant.now()
        record.cancelledBy = req.operator
        record.cancelReason = req.reason
        val saved = replacementRepository.save(record)

        caseService.recordEvent(record.caseId, CaseEventType.TEMP_REPLACEMENT_CANCELLED, req.operator,
            "临时替换已取消: ${req.reason}", null, "REPLACEMENT", record.id)
        caseService.notifyWard(escortCase.wardCode, record.caseId, record.oldPermitId,
            "TEMP_REPLACEMENT_CANCELLED",
            "患者[${escortCase.patientInpatientNo}]临时陪护替换已取消: ${req.reason}")
        return saved
    }

    // ---- 空窗期巡查 ----

    @Transactional
    fun schedulePatrol(replacementId: Long, req: SchedulePatrolRequest): PatrolRecord {
        val record = getRecord(replacementId)
        if (record.status != ReplacementStatus.OLD_LEFT) {
            conflict("患者当前不处于无人陪护空窗(${record.status}), 无需安排空窗巡查")
        }
        val escortCase = caseService.getById(record.caseId)
        val patrol = patrolRepository.save(
            PatrolRecord(
                caseId = record.caseId,
                replacementId = replacementId,
                wardCode = escortCase.wardCode,
                bedNo = escortCase.bedNo,
                scheduledAt = req.scheduledAt!!,
                scheduledBy = req.scheduledBy
            )
        )
        caseService.recordEvent(
            record.caseId, CaseEventType.PATROL_SCHEDULED, req.scheduledBy,
            "护士站安排空窗期巡查: ${req.scheduledAt}", null, "PATROL", patrol.id
        )
        caseService.notifyWard(
            escortCase.wardCode, record.caseId, null, "PATROL_SCHEDULED",
            "已安排 ${req.scheduledBy} 于 ${req.scheduledAt} 巡查患者[${escortCase.patientInpatientNo}]床位${escortCase.bedNo ?: ""}"
        )
        return patrol
    }

    @Transactional
    fun completePatrol(patrolId: Long, req: CompletePatrolRequest): PatrolRecord {
        val patrol = patrolRepository.findById(patrolId).orElse(null) ?: notFound("巡查记录不存在: $patrolId")
        if (patrol.status != PatrolStatus.SCHEDULED) conflict("巡查已处理: ${patrol.status}")
        patrol.status = PatrolStatus.DONE
        patrol.patrolledAt = req.patrolledAt ?: Instant.now()
        patrol.patrolledBy = req.patrolledBy
        patrol.result = req.result
        val saved = patrolRepository.save(patrol)
        caseService.recordEvent(
            patrol.caseId, CaseEventType.PATROL_DONE, req.patrolledBy,
            "空窗期巡查完成: ${req.result ?: "患者情况正常"}", null, "PATROL", patrol.id
        )
        return saved
    }

    // ---- 查询 ----

    @Transactional(readOnly = true)
    fun getRecord(replacementId: Long): ReplacementRecord =
        replacementRepository.findById(replacementId).orElse(null)
            ?: notFound("临时替换记录不存在: $replacementId")

    @Transactional(readOnly = true)
    fun listPatrols(replacementId: Long): List<PatrolRecord> =
        patrolRepository.findByReplacementIdOrderByScheduledAtAsc(replacementId)

    companion object {
        val INCOMPLETE_STATUSES = listOf(
            ReplacementStatus.APPROVAL_PENDING,
            ReplacementStatus.APPROVED,
            ReplacementStatus.OLD_LEFT,
            ReplacementStatus.NEW_ARRIVED
        )
    }
}
