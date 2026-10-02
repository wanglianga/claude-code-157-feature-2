package com.hospital.escort.service

import com.hospital.escort.api.CancelCareReminderRequest
import com.hospital.escort.api.CompleteCareReminderRequest
import com.hospital.escort.api.CreateCareReminderRequest
import com.hospital.escort.common.badRequest
import com.hospital.escort.common.conflict
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.CareReminder
import com.hospital.escort.domain.CareReminderRepository
import com.hospital.escort.domain.CareReminderStatus
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.EscortPermit
import com.hospital.escort.domain.EscortCaseRepository
import com.hospital.escort.domain.ReplacementMode
import com.hospital.escort.domain.ReplacementRecordRepository
import com.hospital.escort.domain.ReplacementStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 看护提醒（进食/翻身/夜间观察等）。
 * 临时替换空窗期由护士站临时创建并承担(PENDING_NURSE)；新陪护到位复核通过后，
 * 未完成的提醒整批转交家属(FAMILY_TAKEN_OVER)并保留交接时间，家属完成后置 FAMILY_DONE。
 */
@Service
class CareReminderService(
    private val reminderRepository: CareReminderRepository,
    private val replacementRepository: ReplacementRecordRepository,
    private val caseRepository: EscortCaseRepository,
    private val caseService: EscortCaseService
) {

    @Transactional
    fun createReminder(req: CreateCareReminderRequest): CareReminder {
        if (req.replacementId == null && req.caseNo == null) {
            badRequest("必须指定临时替换(replacementId)或陪护记录(caseNo)")
        }
        var caseId = 0L
        var wardCode = ""
        var replacementId: Long? = null

        if (req.replacementId != null) {
            val record = replacementRepository.findById(req.replacementId).orElse(null)
                ?: notFound("临时替换记录不存在: ${req.replacementId}")
            if (record.mode != ReplacementMode.TEMPORARY) notFound("记录 ${req.replacementId} 不是临时替换")
            if (record.status == ReplacementStatus.COMPLETED || record.status == ReplacementStatus.CANCELLED) {
                conflict("临时替换已${record.status.name}, 不能再创建看护提醒")
            }
            caseId = record.caseId
            replacementId = record.id
            val escortCase = caseService.getById(caseId)
            wardCode = escortCase.wardCode
        } else {
            val escortCase = caseRepository.findByCaseNo(req.caseNo!!) ?: notFound("陪护记录不存在: ${req.caseNo}")
            caseId = escortCase.id!!
            wardCode = escortCase.wardCode
        }

        val remindAt = req.remindAt!!
        val reminder = reminderRepository.save(
            CareReminder(
                caseId = caseId,
                replacementId = replacementId,
                wardCode = wardCode,
                type = req.type!!,
                content = req.content,
                remindAt = remindAt,
                status = CareReminderStatus.PENDING_NURSE,
                createdBy = req.createdBy
            )
        )
        caseService.recordEvent(
            caseId, CaseEventType.CARE_REMINDER_CREATED, req.createdBy,
            "护士站创建看护提醒[${reminder.type.name}]: ${reminder.content}",
            "计划提醒时间 $remindAt" + (replacementId?.let { " (临时替换 #$it 空窗期)" } ?: ""),
            "CARE_REMINDER", reminder.id
        )
        caseService.notifyWard(
            wardCode, caseId, null, "CARE_REMINDER_CREATED",
            "看护提醒[${typeLabel(req.type!!)}] 计划于 $remindAt: ${reminder.content}"
        )
        return reminder
    }

    /** 护士/家属完成提醒: 空窗期护士完成 -> NURSE_DONE; 家属接手后完成 -> FAMILY_DONE */
    @Transactional
    fun completeReminder(reminderId: Long, req: CompleteCareReminderRequest): CareReminder {
        val reminder = reminderRepository.findById(reminderId).orElse(null)
            ?: notFound("看护提醒不存在: $reminderId")
        val now = Instant.now()
        when (reminder.status) {
            CareReminderStatus.PENDING_NURSE -> {
                reminder.status = CareReminderStatus.NURSE_DONE
                reminder.nurseDoneAt = now
                reminder.nurseDoneBy = req.operator
            }
            CareReminderStatus.FAMILY_TAKEN_OVER -> {
                reminder.status = CareReminderStatus.FAMILY_DONE
                reminder.familyDoneAt = now
                reminder.familyDoneBy = req.operator
            }
            else -> conflict("看护提醒当前状态(${reminder.status})不允许完成")
        }
        val saved = reminderRepository.save(reminder)
        caseService.recordEvent(
            reminder.caseId, CaseEventType.CARE_REMINDER_DONE, req.operator,
            "看护提醒[${reminder.type.name}]已完成: ${reminder.content}",
            req.note ?: "完成人=${req.operator}, 状态=${saved.status.name}",
            "CARE_REMINDER", reminder.id
        )
        return saved
    }

    @Transactional
    fun cancelReminder(reminderId: Long, req: CancelCareReminderRequest): CareReminder {
        val reminder = reminderRepository.findById(reminderId).orElse(null)
            ?: notFound("看护提醒不存在: $reminderId")
        if (reminder.status == CareReminderStatus.NURSE_DONE ||
            reminder.status == CareReminderStatus.FAMILY_DONE
        ) {
            conflict("看护提醒已完成, 不能取消")
        }
        if (reminder.status == CareReminderStatus.CANCELLED) conflict("看护提醒已取消")
        reminder.status = CareReminderStatus.CANCELLED
        reminder.cancelledAt = Instant.now()
        reminder.cancelledBy = req.operator
        val saved = reminderRepository.save(reminder)
        caseService.recordEvent(
            reminder.caseId, CaseEventType.CARE_REMINDER_CANCELLED, req.operator,
            "看护提醒[${reminder.type.name}]已取消: ${reminder.content}", null,
            "CARE_REMINDER", reminder.id
        )
        return saved
    }

    /**
     * 新陪护到位、交接完成时调用: 空窗期创建且仍待护士处理的提醒整批转交家属，
     * 状态转 FAMILY_TAKEN_OVER 并保留交接时间。已由护士完成的提醒保持 NURSE_DONE 不动。
     * @return 实际转交的提醒数
     */
    @Transactional
    fun handOverToFamily(replacementId: Long, newPermit: EscortPermit, newEscortName: String): Int {
        val pending = reminderRepository.findByReplacementIdAndStatusIn(
            replacementId, listOf(CareReminderStatus.PENDING_NURSE)
        )
        val now = Instant.now()
        pending.forEach { r ->
            r.status = CareReminderStatus.FAMILY_TAKEN_OVER
            r.takenOverAt = now
            r.takenOverBy = newEscortName
            r.permitId = newPermit.id
            r.takenOverPermitId = newPermit.id
            reminderRepository.save(r)
            caseService.recordEvent(
                r.caseId, CaseEventType.CARE_REMINDER_TAKEN_OVER, newEscortName,
                "看护提醒[${r.type.name}]转交家属接手: ${r.content}",
                "交接时间 $now, 接手人=$newEscortName, 陪护证=${newPermit.permitNo}",
                "CARE_REMINDER", r.id
            )
        }
        if (pending.isNotEmpty()) {
            caseService.notifyWard(
                newPermit.wardCode, newPermit.caseId, newPermit.id, "CARE_REMINDER_TAKEN_OVER",
                "$newEscortName 已到位接手 ${pending.size} 条看护提醒(交接时间 $now)"
            )
        }
        return pending.size
    }

    @Transactional(readOnly = true)
    fun listByWard(wardCode: String, activeOnly: Boolean): List<CareReminder> {
        val all = reminderRepository.findByWardCodeAndStatusInOrderByRemindAtAsc(
            wardCode,
            if (activeOnly) listOf(CareReminderStatus.PENDING_NURSE, CareReminderStatus.FAMILY_TAKEN_OVER)
            else CareReminderStatus.entries
        )
        return all
    }

    private fun typeLabel(type: com.hospital.escort.domain.CareReminderType) = when (type) {
        com.hospital.escort.domain.CareReminderType.FEEDING -> "进食"
        com.hospital.escort.domain.CareReminderType.TURNING -> "翻身"
        com.hospital.escort.domain.CareReminderType.NIGHT_OBSERVATION -> "夜间观察"
        com.hospital.escort.domain.CareReminderType.OTHER -> "其他照护"
    }
}
