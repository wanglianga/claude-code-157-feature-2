package com.hospital.escort.service

import com.hospital.escort.api.CareReminderActionRequest
import com.hospital.escort.api.CareReminderDto
import com.hospital.escort.api.CareReminderRequest
import com.hospital.escort.api.UnattendedPatientDto
import com.hospital.escort.common.conflict
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.CareReminder
import com.hospital.escort.domain.CareReminderRepository
import com.hospital.escort.domain.CareStatus
import com.hospital.escort.domain.CaseEventType
import com.hospital.escort.domain.EscortApplicationRepository
import com.hospital.escort.domain.EscortCaseRepository
import com.hospital.escort.domain.EscortPermitRepository
import com.hospital.escort.domain.ReminderStatus
import com.hospital.escort.domain.ReminderType
import com.hospital.escort.domain.ReplacementRecordRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 临时看护提醒: 陪护人临时替换、患者处于"短时无人陪护"间隙时,
 * 护士站可为患者临时创建进食(FEEDING)/翻身(TURNING)/夜间观察(NIGHT_WATCH)提醒并安排巡查。
 * 新陪护到位交接后, 尚未完成的提醒自动转为"家属已接手"(保留交接时间)。
 */
@Service
class CareReminderService(
    private val reminderRepository: CareReminderRepository,
    private val caseRepository: EscortCaseRepository,
    private val replacementRepository: ReplacementRecordRepository,
    private val applicationRepository: EscortApplicationRepository,
    private val permitRepository: EscortPermitRepository,
    private val caseService: EscortCaseService
) {

    @Transactional
    fun createReminder(req: CareReminderRequest): CareReminder {
        val escortCase = caseRepository.findByCaseNo(req.caseNo) ?: notFound("陪护记录不存在: ${req.caseNo}")
        if (escortCase.careStatus != CareStatus.UNATTENDED) {
            conflict("患者[${escortCase.patientInpatientNo}]当前有陪护人在岗, 无需创建临时看护提醒")
        }
        val type = req.type!!
        val reminder = reminderRepository.save(
            CareReminder(
                caseId = escortCase.id!!,
                wardCode = escortCase.wardCode,
                replacementId = escortCase.pendingReplacementId,
                type = type,
                note = req.note,
                dueAt = req.dueAt ?: Instant.now(),
                status = ReminderStatus.NURSE_PENDING,
                createdBy = req.createdBy
            )
        )
        caseService.recordEvent(
            escortCase.id!!, CaseEventType.CARE_REMINDER_CREATED, req.createdBy,
            "护士站临时创建看护提醒[${type.cnName}]: ${req.note ?: ""}",
            "无人陪护间隙, 计划 ${reminder.dueAt} 巡查, 提醒#${reminder.id}",
            "CARE_REMINDER", reminder.id
        )
        caseService.notifyWard(
            escortCase.wardCode, escortCase.id, null, "CARE_REMINDER_PENDING",
            "患者[${escortCase.patientInpatientNo}]无人陪护, 需${type.cnName}巡查: ${req.note ?: ""} (护士 ${req.createdBy} 创建)"
        )
        return reminder
    }

    /** 护士已执行一次巡查照护(新陪护可能仍未到位, 提醒保持跟进, 到位后仍会转家属接手) */
    @Transactional
    fun markNurseDone(reminderId: Long, req: CareReminderActionRequest): CareReminder {
        val reminder = getById(reminderId)
        if (reminder.status == ReminderStatus.FAMILY_TAKEN_OVER) {
            conflict("提醒已由家属接手, 无需护士执行")
        }
        val actedAt = req.actedAt ?: Instant.now()
        reminder.status = ReminderStatus.NURSE_DONE
        reminder.nurseDoneAt = actedAt
        reminder.nurseDoneBy = req.operator
        if (req.note != null) reminder.note = (reminder.note?.let { it + " | " } ?: "") + "护士执行: ${req.note}"
        val saved = reminderRepository.save(reminder)
        caseService.recordEvent(
            reminder.caseId, CaseEventType.CARE_REMINDER_NURSE_DONE, req.operator,
            "护士已执行看护提醒[${reminder.type.cnName}]", req.note, "CARE_REMINDER", reminder.id
        )
        return saved
    }

    /**
     * 新陪护到位交接: 该临时替换下所有尚未被家属接手的提醒(含护士已执行的)
     * 自动转为 FAMILY_TAKEN_OVER, 并保留交接时间与接手人。
     */
    @Transactional
    fun takeoverOnArrival(replacementId: Long, arrivedAt: Instant, newEscortName: String, operator: String): Int {
        val caseId = replacementRepository.findById(replacementId).orElse(null)?.caseId
        val open = reminderRepository.findByReplacementIdAndStatusNot(replacementId, ReminderStatus.FAMILY_TAKEN_OVER) +
                (caseId?.let {
                    reminderRepository.findByCaseIdAndReplacementIdNullAndStatusNot(it, ReminderStatus.FAMILY_TAKEN_OVER)
                } ?: emptyList())
        open.distinctBy { it.id }.forEach {
            it.status = ReminderStatus.FAMILY_TAKEN_OVER
            it.takenOverAt = arrivedAt
            it.takenOverBy = newEscortName
            reminderRepository.save(it)
            caseService.recordEvent(
                it.caseId, CaseEventType.CARE_REMINDER_TAKEN_OVER, newEscortName,
                "看护提醒[${it.type.cnName}]随新陪护到位转为家属已接手",
                "交接时间=$arrivedAt, 交接护士=$operator, 接手人=$newEscortName",
                "CARE_REMINDER", it.id
            )
        }
        if (open.isNotEmpty()) {
            val wardCode = open.first().wardCode
            val caseId = open.first().caseId
            caseService.notifyWard(
                wardCode, caseId, null, "CARE_REMINDER_TAKEN_OVER",
                "新陪护 $newEscortName 已到位, ${open.size} 条看护提醒(进食/翻身/夜间观察)转为家属已接手, 交接时间 $arrivedAt"
            )
        }
        return open.size
    }

    @Transactional(readOnly = true)
    fun listByCase(caseNo: String): List<CareReminderDto> {
        val escortCase = caseRepository.findByCaseNo(caseNo) ?: notFound("陪护记录不存在: $caseNo")
        return reminderRepository.findByCaseIdOrderByCreatedAtAsc(escortCase.id!!).map { it.toDto(escortCase.caseNo) }
    }

    @Transactional(readOnly = true)
    fun listWardReminders(wardCode: String, status: ReminderStatus?): List<CareReminderDto> {
        val list = if (status != null)
            reminderRepository.findByWardCodeAndStatusOrderByDueAtAsc(wardCode, status)
        else reminderRepository.findByWardCodeOrderByCreatedAtDesc(wardCode)
        val caseNoById = caseRepository.findAllById(list.map { it.caseId }.distinct()).associate { it.id to it.caseNo }
        return list.map { it.toDto(caseNoById[it.caseId] ?: "") }
    }

    /** 护士站巡查视图: 某病区当前处于"短时无人陪护"的患者 */
    @Transactional(readOnly = true)
    fun listUnattendedPatients(wardCode: String): List<UnattendedPatientDto> {
        val now = Instant.now()
        return caseRepository.findByWardCodeAndCareStatus(wardCode, CareStatus.UNATTENDED).map { escortCase ->
            val record = escortCase.pendingReplacementId?.let {
                replacementRepository.findById(it).orElse(null)
            }
            val oldPermit = record?.oldPermitId?.let { permitRepository.findById(it).orElse(null) }
            val newApp = record?.newApplicationId?.let { applicationRepository.findById(it).orElse(null) }
            val pendingCount = reminderRepository.findByCaseIdOrderByCreatedAtAsc(escortCase.id!!)
                .count { it.status == ReminderStatus.NURSE_PENDING }.toLong()
            UnattendedPatientDto(
                caseNo = escortCase.caseNo,
                patientInpatientNo = escortCase.patientInpatientNo,
                wardCode = escortCase.wardCode,
                bedNo = escortCase.bedNo,
                previousEscortName = oldPermit?.escortName,
                previousEscortPhone = oldPermit?.escortPhone,
                replacementId = record?.id,
                newEscortName = newApp?.escortName,
                newEscortPhone = newApp?.escortPhone,
                relationToPatient = newApp?.relationToPatient,
                unattendedSince = escortCase.unattendedSince ?: now,
                unattendedMinutes = Duration.between(escortCase.unattendedSince ?: now, now).toMinutes(),
                pendingReminderCount = pendingCount
            )
        }
    }

    @Transactional(readOnly = true)
    fun countWardPendingReminders(wardCode: String): Long =
        reminderRepository.findByWardCodeAndStatusOrderByDueAtAsc(wardCode, ReminderStatus.NURSE_PENDING).size.toLong()

    @Transactional(readOnly = true)
    fun getById(reminderId: Long): CareReminder =
        reminderRepository.findById(reminderId).orElse(null) ?: notFound("看护提醒不存在: $reminderId")

    @Transactional(readOnly = true)
    fun getDto(reminderId: Long): CareReminderDto {
        val reminder = getById(reminderId)
        val caseNo = caseRepository.findById(reminder.caseId).orElseThrow().caseNo
        return reminder.toDto(caseNo)
    }

    /**
     * 临时替换被取消: 间隙内已创建的看护提醒脱离该替换记录保留(置 replacement_id 为空),
     * 患者仍由护士站巡查, 后续重新安排亲属接替时这些提醒可继续跟进/接手。
     */
    @Transactional
    fun detachReminders(replacementId: Long) {
        reminderRepository.findByReplacementIdAndStatusNot(replacementId, ReminderStatus.FAMILY_TAKEN_OVER)
            .forEach {
                it.replacementId = null
                reminderRepository.save(it)
            }
    }

    private fun CareReminder.toDto(caseNo: String) = CareReminderDto(
        id = id!!, caseNo = caseNo, wardCode = wardCode, replacementId = replacementId,
        type = type.name, note = note, dueAt = dueAt, status = status.name,
        createdBy = createdBy, createdAt = createdAt,
        nurseDoneAt = nurseDoneAt, nurseDoneBy = nurseDoneBy,
        takenOverAt = takenOverAt, takenOverBy = takenOverBy
    )
}

/** 看护提醒类型的中文名, 用于护士站通知与事件流 */
val ReminderType.cnName: String
    get() = when (this) {
        ReminderType.FEEDING -> "进食"
        ReminderType.TURNING -> "翻身"
        ReminderType.NIGHT_WATCH -> "夜间观察"
    }
