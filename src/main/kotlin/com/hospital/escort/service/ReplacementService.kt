package com.hospital.escort.service

import com.hospital.escort.api.ApplyRequest
import com.hospital.escort.api.ReplacementArrivalRequest
import com.hospital.escort.api.ReplacementCancelRequest
import com.hospital.escort.api.ReplacementRequest
import com.hospital.escort.api.TemporaryReplacementRequest
import com.hospital.escort.common.badRequest
import com.hospital.escort.common.conflict
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.ApplicationStatus
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.EscortApplicationRepository
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.ReplacementRecord
import com.hospital.escort.domain.ReplacementRecordRepository
import com.hospital.escort.domain.ReplacementStatus
import com.hospital.escort.domain.SyncAction
import com.hospital.escort.domain.WardPolicyRepository
import com.hospital.escort.sync.DownstreamSyncService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 陪护人替换: 临时生病、亲属争抢、排班调整等场景。
 * 旧证作废(REPLACED) -> 新申请审批 -> 新证签发, 全程在同一条陪护记录上。
 *
 * 临时替换(temporary, 如原陪护夜间发烧):
 *   家属申请 -> 旧陪护离院即停用旧证、患者进入"短时无人陪护"(护士站可见并安排巡查)
 *   -> 新陪护到院, 护士站核验[身份/健康承诺/与患者关系] -> 签发新证、到位交接(记录交接时间与无人陪护时长)
 *   -> 无人陪护间隙的看护提醒自动转为"家属已接手"。
 */
@Service
class ReplacementService(
    private val replacementRepository: ReplacementRecordRepository,
    private val applicationRepository: EscortApplicationRepository,
    private val applicationService: ApplicationService,
    private val permitService: PermitService,
    private val caseService: EscortCaseService,
    private val syncService: DownstreamSyncService,
    private val wardPolicyRepository: WardPolicyRepository,
    private val reminderService: CareReminderService
) {

    // ---------------- 常规替换(新陪护已在场, 申请-审批-发证一次完成或分步审批) ----------------

    @Transactional
    fun requestReplacement(req: ReplacementRequest): ReplacementRecord {
        val oldPermit = permitService.getByPermitNo(req.oldPermitNo)
        if (oldPermit.status != PermitStatus.ACTIVE && oldPermit.status != PermitStatus.FROZEN) {
            conflict("原陪护证状态(${oldPermit.status})不允许替换")
        }
        val escortCase = caseService.getById(oldPermit.caseId)
        val policy = wardPolicyRepository.findById(escortCase.wardCode).orElse(null)
            ?: notFound("病区不存在: ${escortCase.wardCode}")
        if (policy.lockdown) conflict("病区[${escortCase.wardCode}]封闭管理中, 暂停陪护替换申请")
        if (req.escortIdNo.isBlank() || req.escortName.isBlank() || req.escortPhone.isBlank()) {
            badRequest("新陪护人姓名/身份证/联系方式不能为空")
        }
        if (!req.healthCommitment) badRequest("新陪护人必须确认健康承诺")

        val newApplication = applicationService.applyReplacementDirect(buildApplyRequest(req, escortCase, oldPermit), oldPermit.applicationId)

        val record = replacementRepository.save(
            ReplacementRecord(
                caseId = escortCase.id!!,
                oldPermitId = oldPermit.id,
                newApplicationId = newApplication.id!!,
                reason = req.reason,
                detail = req.detail,
                status = ReplacementStatus.PENDING,
                healthPromiseAccepted = true
            )
        )
        caseService.recordEvent(
            escortCase.id!!, CaseEventType.REPLACEMENT_REQUESTED, req.escortName,
            "申请替换陪护人: ${oldPermit.escortName} -> ${req.escortName} (${req.reason.name})",
            req.detail, "REPLACEMENT", record.id
        )
        caseService.notifyWard(escortCase.wardCode, escortCase.id, oldPermit.id, "REPLACEMENT_PENDING",
            "患者[${escortCase.patientInpatientNo}]申请替换陪护: ${oldPermit.escortName} -> ${req.escortName}, 待审批")

        if (req.autoApprove) {
            return decideReplacement(record.id!!, req.decidedBy ?: "nurse-station", approve = true, note = "护士站即时审批")
        }
        return record
    }

    @Transactional
    fun decideReplacement(replacementId: Long, decidedBy: String, approve: Boolean, note: String?): ReplacementRecord {
        val record = getRecord(replacementId)
        if (record.temporary) conflict("临时替换请使用到位交接接口(/api/replacements/{id}/arrival)完成核验发证")
        if (record.status != ReplacementStatus.PENDING) conflict("替换申请已处理: ${record.status}")

        val newApplication = applicationRepository.findById(record.newApplicationId).orElseThrow()
        val oldPermit = record.oldPermitId?.let { permitService.getById(it) }
        val escortCase = caseService.getById(record.caseId)

        if (!approve) {
            return rejectReplacement(record, newApplication, escortCase, decidedBy, note)
        }

        newApplication.status = ApplicationStatus.APPROVED
        newApplication.decidedAt = Instant.now()
        newApplication.decidedBy = decidedBy
        newApplication.decisionNote = note
        applicationRepository.save(newApplication)

        if (oldPermit != null && oldPermit.status != PermitStatus.RETURNED && oldPermit.status != PermitStatus.REVOKED) {
            deactivateOldPermit(oldPermit, escortCase, decidedBy)
        }

        val newPermit = permitService.issuePermit(newApplication, escortCase, decidedBy, nightCareApproved = true)

        record.status = ReplacementStatus.APPROVED
        record.newPermitId = newPermit.id
        record.decidedAt = Instant.now()
        record.decidedBy = decidedBy
        val saved = replacementRepository.save(record)

        caseService.recordEvent(record.caseId, CaseEventType.REPLACEMENT_APPROVED, decidedBy,
            "替换完成: ${oldPermit?.escortName ?: "?"} -> ${newPermit.escortName}, 新证 ${newPermit.permitNo}",
            note, "REPLACEMENT", record.id)
        caseService.notifyWard(escortCase.wardCode, record.caseId, newPermit.id, "REPLACEMENT_APPROVED",
            "患者[${escortCase.patientInpatientNo}]陪护人已替换: 现由 ${newPermit.escortName} 照护(证号 ${newPermit.permitNo})")
        return saved
    }

    // ---------------- 临时替换(夜间发烧等紧急接替, 存在无人陪护间隙) ----------------

    /**
     * 第1步: 家属申请临时替换。原陪护已离院(或即刻离院):
     * 旧证即时停用(门禁同步失效), 患者进入短时无人陪护状态, 护士站可见并安排巡查。
     * 新陪护的身份/健康承诺/亲属关系待其到院后在到位交接时当面核验。
     */
    @Transactional
    fun requestTemporaryReplacement(req: TemporaryReplacementRequest): ReplacementRecord {
        val oldPermit = permitService.getByPermitNo(req.oldPermitNo)
        val escortCase = caseService.getById(oldPermit.caseId)
        if (escortCase.pendingReplacementId != null) {
            conflict("患者[${escortCase.patientInpatientNo}]已在临时替换交接中(记录#${escortCase.pendingReplacementId}), 请先完成到位交接或取消")
        }
        val policy = wardPolicyRepository.findById(escortCase.wardCode).orElse(null)
            ?: notFound("病区不存在: ${escortCase.wardCode}")
        if (policy.lockdown) conflict("病区[${escortCase.wardCode}]封闭管理中, 暂停陪护替换申请")

        if (req.escortName.isBlank() || req.escortIdNo.isBlank() || req.escortPhone.isBlank()) {
            badRequest("新陪护人姓名/身份证/联系方式不能为空")
        }
        if (req.relationToPatient.isBlank()) badRequest("新陪护人与患者关系不能为空(需亲属关系核验)")
        if (req.healthCommitment != true) badRequest("新陪护人必须确认健康承诺(无发热/传染病症状)")

        val now = Instant.now()
        val leftAt = req.oldLeftAt ?: Instant.now()
        if (leftAt.isAfter(now.plusSeconds(60))) badRequest("旧陪护离院时间不能晚于当前时间")

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
            periodStart = req.periodStart ?: leftAt,
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
                status = ReplacementStatus.PENDING,
                temporary = true,
                healthPromiseAccepted = true,
                oldLeftAt = leftAt,
                identityVerified = req.identityVerified,
                identityVerifiedBy = if (req.identityVerified) req.verifiedBy else null,
                identityVerifiedAt = if (req.identityVerified) leftAt else null,
                relationVerified = req.relationVerified
            )
        )

        if (oldPermit.status == PermitStatus.ACTIVE || oldPermit.status == PermitStatus.FROZEN) {
            // 正常情形: 旧陪护本次离院, 旧证即时停用并同步门禁, 患者进入无人陪护间隙
            deactivateOldPermit(oldPermit, escortCase, req.escortName,
                reasonDetail = "临时替换: ${req.reason.name}${req.detail?.let { " - $it" } ?: ""}")
            caseService.recordEvent(
                escortCase.id!!, CaseEventType.REPLACEMENT_REQUESTED, req.escortName,
                "申请临时替换陪护人: ${oldPermit.escortName} -> ${req.escortName}(${req.relationToPatient}), 原因 ${req.reason.name}",
                "原陪护 ${oldPermit.escortName} 已于 $leftAt 离院, 新陪护未到院; ${req.detail ?: ""}",
                "REPLACEMENT", record.id
            )
            caseService.markUnattended(escortCase, record.id!!, leftAt, oldPermit.escortName, req.escortName, req.detail)
        } else if (oldPermit.status == PermitStatus.REPLACED &&
            escortCase.careStatus == com.hospital.escort.domain.CareStatus.UNATTENDED
        ) {
            // 上次临时替换取消、患者仍无人陪护: 家属重新登记另一位亲属接替, 沿用原无人陪护间隙
            escortCase.pendingReplacementId = record.id
            caseService.saveCase(escortCase)
            caseService.recordEvent(
                escortCase.id!!, CaseEventType.REPLACEMENT_REQUESTED, req.escortName,
                "无人陪护期间重新登记接替亲属: ${req.escortName}(${req.relationToPatient}), 原因 ${req.reason.name}",
                "沿用原离院时间 ${escortCase.unattendedSince ?: leftAt}; ${req.detail ?: ""}",
                "REPLACEMENT", record.id
            )
            caseService.notifyWard(
                escortCase.wardCode, escortCase.id, oldPermit.id, "REPLACEMENT_PENDING",
                "患者[${escortCase.patientInpatientNo}]家属重新登记接替陪护 ${req.escortName}(${req.relationToPatient}), 待到院核验交接"
            )
        } else {
            conflict("原陪护证状态(${oldPermit.status})不允许临时替换")
        }
        return record
    }

    /**
     * 第2步: 新陪护到院到位交接。护士站当面核验:
     *  1) 身份(证件与本人一致) 2) 健康承诺(无发热/传染病症状) 3) 与患者亲属关系属实。
     * 三项全部通过后: 审批新申请 -> 签发新证 -> 结束无人陪护间隙、回填交接时间与无人陪护时长,
     * 间隙内的看护提醒自动转为"家属已接手"。任一核验不通过则拒绝(400), 患者维持无人陪护等待处理。
     */
    @Transactional
    fun newEscortArrived(replacementId: Long, req: ReplacementArrivalRequest): ReplacementRecord {
        val record = getRecord(replacementId)
        if (!record.temporary) conflict("该替换不是临时替换, 请使用常规审批接口")
        if (record.status != ReplacementStatus.PENDING) conflict("临时替换已处理: ${record.status}")

        val escortCase = caseService.getById(record.caseId)
        if (escortCase.careStatus != com.hospital.escort.domain.CareStatus.UNATTENDED) {
            conflict("患者当前不处于无人陪护状态, 无法到位交接")
        }
        val newApplication = applicationRepository.findById(record.newApplicationId).orElseThrow()

        val arrivedAt = req.arrivedAt ?: Instant.now()
        val leftAt = record.oldLeftAt ?: arrivedAt
        if (arrivedAt.isBefore(leftAt)) badRequest("到位交接时间不能早于旧陪护离院时间($leftAt)")

        // 三项核验(请求参数优先, 未显式给定时沿用申请阶段的预核验结果)
        val identityOk = req.identityVerified ?: record.identityVerified
        val relationOk = req.relationVerified ?: record.relationVerified
        val healthOk = req.healthCommitmentAccepted ?: record.healthPromiseAccepted
        if (!identityOk) badRequest("核验未通过: 新陪护人身份证件与本人不一致, 不能接替陪护")
        if (!healthOk) badRequest("核验未通过: 新陪护人未确认健康承诺或存在发热/传染病症状, 不能接替陪护")
        if (!relationOk) badRequest("核验未通过: 无法确认新陪护人与患者的亲属关系, 不能接替陪护")

        // 记录核验结果与到位交接时间
        record.identityVerified = true
        record.identityVerifiedBy = req.operator
        record.identityVerifiedAt = arrivedAt
        record.healthPromiseAccepted = true
        record.relationVerified = true
        record.newArrivedAt = arrivedAt
        record.gapMinutes = Duration.between(leftAt, arrivedAt).toMinutes().toInt().coerceAtLeast(0)

        // 审批新申请 + 签发新证(更新当前照护人快照)
        newApplication.status = ApplicationStatus.APPROVED
        newApplication.decidedAt = arrivedAt
        newApplication.decidedBy = req.operator
        newApplication.decisionNote = req.note ?: "临时替换到位交接, 身份/健康承诺/亲属关系核验通过"
        applicationRepository.save(newApplication)

        val newPermit = permitService.issuePermit(newApplication, escortCase, req.operator, nightCareApproved = true)

        record.status = ReplacementStatus.APPROVED
        record.newPermitId = newPermit.id
        record.decidedAt = arrivedAt
        record.decidedBy = req.operator
        replacementRepository.save(record)

        caseService.recordEvent(
            record.caseId, CaseEventType.TEMP_REPLACEMENT_VERIFIED, req.operator,
            "护士站核验新陪护 ${newPermit.escortName}: 身份/健康承诺/与患者关系(${newApplication.relationToPatient})均通过, 签发新证 ${newPermit.permitNo}",
            "到位时间=$arrivedAt; 无人陪护 ${record.gapMinutes} 分钟; ${req.note ?: ""}",
            "REPLACEMENT", record.id
        )

        // 无人陪护间隙的看护提醒自动转为家属已接手(保留交接时间)
        val takenOver = reminderService.takeoverOnArrival(record.id!!, arrivedAt, newPermit.escortName, req.operator)

        // 结束无人陪护间隙
        caseService.finishUnattendedGap(
            escortCase, record.id!!, req.operator, newPermit.escortName,
            arrivedAt, record.gapMinutes!!, takenOver
        )
        caseService.notifyWard(
            escortCase.wardCode, record.caseId, newPermit.id, "REPLACEMENT_APPROVED",
            "患者[${escortCase.patientInpatientNo}]临时替换到位: 现由 ${newPermit.escortName} 照护(证号 ${newPermit.permitNo}), " +
                    "交接时间 $arrivedAt, 无人陪护 ${record.gapMinutes} 分钟"
        )
        return record
    }

    /**
     * 取消临时替换(新陪护无法到院/核验不合格放弃等)。
     * 新申请作废; 旧陪护既已离院, 患者仍维持无人陪护状态由护士站继续巡查, 可重新登记其他亲属接替。
     */
    @Transactional
    fun cancelTemporaryReplacement(replacementId: Long, req: ReplacementCancelRequest): ReplacementRecord {
        val record = getRecord(replacementId)
        if (!record.temporary) conflict("仅临时替换可以取消, 常规替换请使用审批驳回")
        if (record.status != ReplacementStatus.PENDING) conflict("临时替换已处理: ${record.status}")

        val newApplication = applicationRepository.findById(record.newApplicationId).orElseThrow()
        newApplication.status = ApplicationStatus.WITHDRAWN
        newApplication.decidedAt = Instant.now()
        newApplication.decidedBy = req.operator
        newApplication.decisionNote = "临时替换取消: ${req.reason ?: ""}"
        applicationRepository.save(newApplication)

        record.status = ReplacementStatus.CANCELED
        record.canceledAt = Instant.now()
        record.canceledBy = req.operator
        record.cancelReason = req.reason
        replacementRepository.save(record)

        val escortCase = caseService.getById(record.caseId)
        // 间隙内已创建的看护提醒脱离本次替换保留, 便于后续重新安排接替时继续跟进/接手
        reminderService.detachReminders(record.id!!)
        escortCase.pendingReplacementId = null
        caseService.saveCase(escortCase)

        caseService.recordEvent(record.caseId, CaseEventType.TEMP_REPLACEMENT_CANCELED, req.operator,
            "临时替换取消: ${newApplication.escortName} 无法接替", req.reason, "REPLACEMENT", record.id)
        caseService.notifyWard(escortCase.wardCode, record.caseId, null, "TEMP_REPLACEMENT_CANCELED",
            "患者[${escortCase.patientInpatientNo}]临时替换已取消(${newApplication.escortName}: ${req.reason ?: "无"}), " +
                    "当前仍无人陪护, 请护士站继续巡查并协调家属重新安排接替")
        return record
    }

    // ---------------- 内部辅助 ----------------

    @Transactional(readOnly = true)
    fun getRecord(replacementId: Long): ReplacementRecord =
        replacementRepository.findById(replacementId).orElse(null)
            ?: notFound("替换记录不存在: $replacementId")

    private fun buildApplyRequest(req: ReplacementRequest, escortCase: com.hospital.escort.domain.EscortCase, oldPermit: com.hospital.escort.domain.EscortPermit) =
        ApplyRequest(
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
            periodStart = req.periodStart ?: Instant.now(),
            periodEnd = req.periodEnd ?: oldPermit.validUntil,
            needNightCare = req.needNightCare
        )

    private fun rejectReplacement(
        record: ReplacementRecord,
        newApplication: com.hospital.escort.domain.EscortApplication,
        escortCase: com.hospital.escort.domain.EscortCase,
        decidedBy: String,
        note: String?
    ): ReplacementRecord {
        record.status = ReplacementStatus.REJECTED
        record.decidedAt = Instant.now()
        record.decidedBy = decidedBy
        newApplication.status = ApplicationStatus.REJECTED
        newApplication.decidedAt = Instant.now()
        newApplication.decidedBy = decidedBy
        newApplication.decisionNote = note
        applicationRepository.save(newApplication)
        val saved = replacementRepository.save(record)
        caseService.recordEvent(record.caseId, CaseEventType.REPLACEMENT_REJECTED, decidedBy,
            "替换申请被驳回", note, "REPLACEMENT", record.id)
        caseService.notifyWard(escortCase.wardCode, record.caseId, null, "REPLACEMENT_REJECTED",
            "患者[${escortCase.patientInpatientNo}]替换陪护被驳回: ${note ?: ""}")
        return saved
    }

    /** 旧证作废(REPLACED) + 事件留痕 + 同步门禁/扫码点/安保即时失效 */
    private fun deactivateOldPermit(
        oldPermit: com.hospital.escort.domain.EscortPermit,
        escortCase: com.hospital.escort.domain.EscortCase,
        actor: String,
        reasonDetail: String? = null
    ) {
        oldPermit.status = PermitStatus.REPLACED
        permitService.saveDirect(oldPermit)
        caseService.recordEvent(
            escortCase.id!!, CaseEventType.PERMIT_REPLACED, actor,
            "旧陪护证 ${oldPermit.permitNo}(${oldPermit.escortName}) 因替换作废",
            reasonDetail, "PERMIT", oldPermit.id
        )
        syncService.syncPermit(oldPermit, escortCase.caseNo, SyncAction.REVOKE)
    }
}
