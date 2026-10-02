package com.hospital.escort.service

import com.hospital.escort.common.notFound
import com.hospital.escort.domain.CareStatus
import com.hospital.escort.domain.CaseEvent
import com.hospital.escort.domain.CaseEventRepository
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.CaseStatus
import com.hospital.escort.domain.EscortCase
import com.hospital.escort.domain.EscortCaseRepository
import com.hospital.escort.domain.WardNotice
import com.hospital.escort.domain.WardNoticeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 陪护记录(escort_case)是聚合根: 同一患者本次住院的
 * 申请/审批/发证/进出/替换/违规/退证全部归档在同一条记录上。
 */
@Service
class EscortCaseService(
    private val caseRepository: EscortCaseRepository,
    private val eventRepository: CaseEventRepository,
    private val noticeRepository: WardNoticeRepository
) {

    /** 找到患者当前在院的陪护记录, 没有则新建 */
    @Transactional
    fun getOrCreateActiveCase(patientInpatientNo: String, wardCode: String, bedNo: String?): EscortCase {
        val existing = caseRepository.findFirstByPatientInpatientNoAndStatusOrderByCreatedAtDesc(
            patientInpatientNo, CaseStatus.ACTIVE
        )
        if (existing != null) {
            if (existing.wardCode != wardCode) {
                existing.wardCode = wardCode
                if (bedNo != null) existing.bedNo = bedNo
                return caseRepository.save(existing)
            }
            if (bedNo != null && existing.bedNo != bedNo) {
                existing.bedNo = bedNo
                return caseRepository.save(existing)
            }
            return existing
        }
        val created = EscortCase(
            caseNo = "EC-" + UUID.randomUUID().toString().substring(0, 8).uppercase(),
            patientInpatientNo = patientInpatientNo,
            wardCode = wardCode,
            bedNo = bedNo,
            status = CaseStatus.ACTIVE
        )
        return caseRepository.save(created)
    }

    @Transactional(readOnly = true)
    fun getByCaseNo(caseNo: String): EscortCase =
        caseRepository.findByCaseNo(caseNo) ?: notFound("陪护记录不存在: $caseNo")

    @Transactional(readOnly = true)
    fun getById(caseId: Long): EscortCase =
        caseRepository.findById(caseId).orElse(null) ?: notFound("陪护记录不存在: id=$caseId")

    @Transactional(readOnly = true)
    fun getCaseNoById(caseId: Long): String = getById(caseId).caseNo

    @Transactional
    fun saveCase(escortCase: EscortCase): EscortCase = caseRepository.save(escortCase)

    @Transactional
    fun recordEvent(
        caseId: Long,
        type: CaseEventType,
        actor: String?,
        summary: String,
        detail: String? = null,
        refType: String? = null,
        refId: Long? = null
    ): CaseEvent = eventRepository.save(
        CaseEvent(
            caseId = caseId,
            eventType = type,
            actor = actor,
            summary = summary,
            detail = detail,
            refType = refType,
            refId = refId
        )
    )

    /** 给病区护士站推送通知: 让病区随时知道患者当下由谁照护 */
    @Transactional
    fun notifyWard(wardCode: String, caseId: Long?, permitId: Long?, type: String, message: String) {
        noticeRepository.save(
            WardNotice(wardCode = wardCode, caseId = caseId, permitId = permitId, type = type, message = message)
        )
    }

    /** 更新"当前照护人"快照并通知病区 */
    @Transactional
    fun updateCurrentEscort(
        escortCase: EscortCase,
        permitId: Long?,
        escortName: String?,
        escortIdNo: String?,
        escortPhone: String?,
        actor: String?,
        reason: String
    ) {
        escortCase.currentPermitId = permitId
        escortCase.currentEscortName = escortName
        escortCase.currentEscortIdNo = escortIdNo
        escortCase.currentEscortPhone = escortPhone
        caseRepository.save(escortCase)
        val who = escortName ?: "无(待安排)"
        recordEvent(
            escortCase.id!!, CaseEventType.CAREGIVER_CHANGED, actor,
            "当前照护人变更: $who", "原因: $reason"
        )
        notifyWard(
            escortCase.wardCode, escortCase.id, permitId, "CAREGIVER_CHANGED",
            "患者[${escortCase.patientInpatientNo}]当前照护人: $who ($reason)"
        )
    }

    @Transactional
    fun closeCase(escortCase: EscortCase, actor: String?, reason: String) {
        escortCase.status = CaseStatus.CLOSED
        escortCase.closedAt = Instant.now()
        caseRepository.save(escortCase)
        recordEvent(escortCase.id!!, CaseEventType.CASE_CLOSED, actor, "陪护记录关闭", reason)
        notifyWard(escortCase.wardCode, escortCase.id, null, "CASE_CLOSED",
            "患者[${escortCase.patientInpatientNo}]陪护记录已关闭: $reason")
    }

    /**
     * 临时替换: 旧陪护离院、新陪护未到, 患者进入"短时无人陪护"状态。
     * 清空当前照护人快照(护士站看板可见), 记录离院时间并通知病区安排巡查。
     */
    @Transactional
    fun markUnattended(
        escortCase: EscortCase,
        replacementId: Long,
        leftAt: Instant,
        oldEscortName: String,
        actor: String?,
        reasonDetail: String?
    ) {
        escortCase.careStatus = CareStatus.UNATTENDED
        escortCase.unattendedSince = leftAt
        escortCase.pendingReplacementId = replacementId
        escortCase.currentPermitId = null
        escortCase.currentEscortName = null
        escortCase.currentEscortIdNo = null
        escortCase.currentEscortPhone = null
        caseRepository.save(escortCase)
        recordEvent(
            escortCase.id!!, CaseEventType.TEMP_REPLACEMENT_OLD_LEFT, actor,
            "原陪护 $oldEscortName 已离院, 患者进入短时无人陪护状态, 等待新陪护到院交接",
            "旧陪护离院时间=$leftAt${reasonDetail?.let { "; $it" } ?: ""}",
            "REPLACEMENT", replacementId
        )
        notifyWard(
            escortCase.wardCode, escortCase.id, null, "TEMP_REPLACEMENT_UNATTENDED",
            "患者[${escortCase.patientInpatientNo}]原陪护 $oldEscortName 已于 $leftAt 离院, 新陪护尚未到院, " +
                    "当前短时无人陪护, 请护士站安排巡查(进食/翻身/夜间观察)"
        )
    }

    /**
     * 新陪护到位交接完成: 结束无人陪护间隙, 恢复"有陪护"状态。
     * 当前照护人快照已由发证流程更新, 这里补充交接事件与病区通知(含交接时间与无人陪护时长)。
     */
    @Transactional
    fun finishUnattendedGap(
        escortCase: EscortCase,
        replacementId: Long,
        operator: String,
        newEscortName: String,
        arrivedAt: Instant,
        gapMinutes: Int,
        reminderTakenOver: Int
    ) {
        escortCase.careStatus = CareStatus.WITH_ESCORT
        escortCase.unattendedSince = null
        escortCase.pendingReplacementId = null
        caseRepository.save(escortCase)
        recordEvent(
            escortCase.id!!, CaseEventType.TEMP_REPLACEMENT_ARRIVED, operator,
            "新陪护 $newEscortName 已到院完成交接, 无人陪护间隙结束(共 $gapMinutes 分钟)",
            "交接时间=$arrivedAt; 身份/健康承诺/亲属关系已核验; $reminderTakenOver 条看护提醒转为家属已接手",
            "REPLACEMENT", replacementId
        )
        notifyWard(
            escortCase.wardCode, escortCase.id, escortCase.currentPermitId, "TEMP_REPLACEMENT_ARRIVED",
            "患者[${escortCase.patientInpatientNo}]新陪护 $newEscortName 已于 $arrivedAt 到位交接, " +
                    "无人陪护 $gapMinutes 分钟, 现由 $newEscortName 照护; $reminderTakenOver 条看护提醒已转家属接手"
        )
    }
}
