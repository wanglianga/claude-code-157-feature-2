package com.hospital.escort.service

import com.hospital.escort.api.ApplicationDto
import com.hospital.escort.api.CareReminderDto
import com.hospital.escort.api.CaseArchiveDto
import com.hospital.escort.api.EventDto
import com.hospital.escort.api.PatrolDto
import com.hospital.escort.api.PermitDto
import com.hospital.escort.api.ReplacementDto
import com.hospital.escort.api.ScanRecordDto
import com.hospital.escort.api.UnattendedPatientDto
import com.hospital.escort.api.ViolationDto
import com.hospital.escort.api.WardNoticeDto
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.CareReminderRepository
import com.hospital.escort.domain.CareReminderStatus
import com.hospital.escort.domain.CaseEventRepository
import com.hospital.escort.domain.EscortApplicationRepository
import com.hospital.escort.domain.EscortCaseRepository
import com.hospital.escort.domain.EscortPermitRepository
import com.hospital.escort.domain.PatrolRecordRepository
import com.hospital.escort.domain.PatrolStatus
import com.hospital.escort.domain.PermitSyncRepository
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.ReplacementMode
import com.hospital.escort.domain.ReplacementRecordRepository
import com.hospital.escort.domain.ReplacementStatus
import com.hospital.escort.domain.ScanRecordRepository
import com.hospital.escort.domain.SyncStatus
import com.hospital.escort.domain.ViolationRecordRepository
import com.hospital.escort.domain.WardNoticeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 单条陪护记录归档查询: 申请、审批、证件、进出、替换(含临时交接时间轴)、
 * 空窗巡查、看护提醒、违规、事件流全部在同一视图。
 */
@Service
class ArchiveQueryService(
    private val caseRepository: EscortCaseRepository,
    private val applicationRepository: EscortApplicationRepository,
    private val permitRepository: EscortPermitRepository,
    private val scanRepository: ScanRecordRepository,
    private val replacementRepository: ReplacementRecordRepository,
    private val patrolRepository: PatrolRecordRepository,
    private val careReminderRepository: CareReminderRepository,
    private val violationRepository: ViolationRecordRepository,
    private val eventRepository: CaseEventRepository,
    private val syncRepository: PermitSyncRepository,
    private val noticeRepository: WardNoticeRepository
) {

    @Transactional(readOnly = true)
    fun getCaseArchive(caseNo: String): CaseArchiveDto {
        val escortCase = caseRepository.findByCaseNo(caseNo) ?: notFound("陪护记录不存在: $caseNo")
        val caseId = escortCase.id!!

        val applications = applicationRepository.findByCaseIdOrderBySubmittedAtAsc(caseId)
        val applicationById = applications.associateBy { it.id }
        val permits = permitRepository.findByCaseIdOrderByIssuedAtAsc(caseId)
        val permitNoById = permits.associate { it.id to it.permitNo }

        return CaseArchiveDto(
            caseNo = escortCase.caseNo,
            patientInpatientNo = escortCase.patientInpatientNo,
            wardCode = escortCase.wardCode,
            status = escortCase.status.name,
            currentEscortName = escortCase.currentEscortName,
            currentEscortPhone = escortCase.currentEscortPhone,
            applications = applications.map { it.toDto(escortCase.caseNo) },
            permits = permits.map { p ->
                val synced = syncRepository.findByPermitId(p.id!!)
                    .filter { it.status == SyncStatus.SYNCED }
                    .map { it.target.name }
                p.toDto(escortCase.caseNo, synced)
            },
            scans = scanRepository.findByCaseIdOrderByScannedAtAsc(caseId).map { it.toDto() },
            replacements = replacementRepository.findByCaseIdOrderByRequestedAtAsc(caseId).map { r ->
                val newApp = applicationById[r.newApplicationId]
                val oldApp = r.oldPermitId?.let { oldId ->
                    permits.firstOrNull { it.id == oldId }?.applicationId
                        ?.let { applicationById[it] }
                }
                ReplacementDto(
                    id = r.id!!, reason = r.reason.name, detail = r.detail, status = r.status.name,
                    mode = r.mode.name,
                    oldPermitNo = r.oldPermitId?.let { permitNoById[it] },
                    newPermitNo = r.newPermitId?.let { permitNoById[it] },
                    oldEscortName = oldApp?.escortName,
                    newEscortName = newApp?.escortName,
                    relationToPatient = newApp?.relationToPatient,
                    identityVerified = r.identityVerified,
                    healthVerified = r.healthVerified,
                    relationVerified = r.relationVerified,
                    nightCareApproved = r.nightCareApproved,
                    requestedAt = r.requestedAt, decidedAt = r.decidedAt, decidedBy = r.decidedBy,
                    oldEscortLeftAt = r.oldEscortLeftAt,
                    newEscortArrivedAt = r.newEscortArrivedAt,
                    handoverCompletedAt = r.handoverCompletedAt
                )
            },
            patrols = patrolRepository.findByCaseIdOrderByScheduledAtAsc(caseId).map {
                PatrolDto(
                    id = it.id!!, caseNo = escortCase.caseNo, replacementId = it.replacementId,
                    wardCode = it.wardCode, bedNo = it.bedNo, scheduledAt = it.scheduledAt,
                    scheduledBy = it.scheduledBy, status = it.status.name, result = it.result,
                    patrolledAt = it.patrolledAt, patrolledBy = it.patrolledBy
                )
            },
            careReminders = careReminderRepository.findByCaseIdOrderByRemindAtAsc(caseId).map {
                toCareReminderDto(it, escortCase.caseNo, permitNoById[it.permitId])
            },
            violations = violationRepository.findByCaseIdOrderByCreatedAtAsc(caseId).map { it.toDto() },
            events = eventRepository.findByCaseIdOrderByCreatedAtAsc(caseId).map {
                EventDto(it.eventType.name, it.actor, it.summary, it.detail, it.refType, it.refId, it.createdAt)
            },
            createdAt = escortCase.createdAt,
            closedAt = escortCase.closedAt
        )
    }

    /** 空窗期患者列表: 旧陪护已离院(OLD_LEFT)、新陪护未到，附带巡查/提醒安排 */
    @Transactional(readOnly = true)
    fun listUnattendedPatients(wardCode: String): List<UnattendedPatientDto> {
        val now = Instant.now()
        return replacementRepository.findByModeAndStatus(ReplacementMode.TEMPORARY, ReplacementStatus.OLD_LEFT)
            .mapNotNull { record ->
                val escortCase = caseRepository.findById(record.caseId).orElse(null) ?: return@mapNotNull null
                if (escortCase.wardCode != wardCode) return@mapNotNull null
                val newApp = applicationRepository.findById(record.newApplicationId).orElse(null)
                val oldPermit = record.oldPermitId?.let { permitRepository.findById(it).orElse(null) }
                val patrols = patrolRepository.findByReplacementIdOrderByScheduledAtAsc(record.id!!)
                val pendingReminders = careReminderRepository.findByReplacementIdAndStatusIn(
                    record.id!!, listOf(CareReminderStatus.PENDING_NURSE)
                )
                UnattendedPatientDto(
                    caseNo = escortCase.caseNo,
                    patientInpatientNo = escortCase.patientInpatientNo,
                    wardCode = escortCase.wardCode,
                    bedNo = escortCase.bedNo,
                    replacementId = record.id!!,
                    oldEscortName = oldPermit?.escortName,
                    newEscortName = newApp?.escortName,
                    oldEscortLeftAt = record.oldEscortLeftAt!!,
                    unattendedMinutes = Duration.between(record.oldEscortLeftAt, now).toMinutes(),
                    scheduledPatrols = patrols.count { it.status == PatrolStatus.SCHEDULED }.toLong(),
                    pendingCareReminders = pendingReminders.size.toLong()
                )
            }
    }

    @Transactional(readOnly = true)
    fun listWardNotices(wardCode: String, unreadOnly: Boolean): List<WardNoticeDto> {
        val list = if (unreadOnly) noticeRepository.findByWardCodeAndReadFalseOrderByCreatedAtDesc(wardCode)
        else noticeRepository.findByWardCodeOrderByCreatedAtDesc(wardCode)
        return list.map { WardNoticeDto(it.id!!, it.type, it.message, it.read, it.createdAt) }
    }

    @Transactional(readOnly = true)
    fun getPermitDetail(permitNo: String): PermitDto {
        val permit = permitRepository.findByPermitNo(permitNo) ?: notFound("陪护证不存在: $permitNo")
        val escortCase = caseRepository.findById(permit.caseId).orElseThrow()
        val synced = syncRepository.findByPermitId(permit.id!!)
            .filter { it.status == SyncStatus.SYNCED }.map { it.target.name }
        return permit.toDto(escortCase.caseNo, synced)
    }

    @Transactional(readOnly = true)
    fun toCareReminderDto(reminder: com.hospital.escort.domain.CareReminder): CareReminderDto {
        val escortCase = caseRepository.findById(reminder.caseId).orElse(null)
        val permitNo = reminder.permitId?.let { id -> permitRepository.findById(id).orElse(null)?.permitNo }
        return toCareReminderDto(reminder, escortCase?.caseNo, permitNo)
    }

    fun toCareReminderDto(
        reminder: com.hospital.escort.domain.CareReminder,
        caseNo: String?,
        permitNo: String?
    ) = CareReminderDto(
        id = reminder.id!!, caseNo = caseNo, replacementId = reminder.replacementId,
        permitNo = permitNo, wardCode = reminder.wardCode, type = reminder.type.name,
        content = reminder.content, remindAt = reminder.remindAt, status = reminder.status.name,
        createdBy = reminder.createdBy, createdAt = reminder.createdAt,
        takenOverAt = reminder.takenOverAt, takenOverBy = reminder.takenOverBy,
        nurseDoneAt = reminder.nurseDoneAt, nurseDoneBy = reminder.nurseDoneBy,
        familyDoneAt = reminder.familyDoneAt, familyDoneBy = reminder.familyDoneBy
    )
}

fun com.hospital.escort.domain.EscortApplication.toDto(caseNo: String) = ApplicationDto(
    applicationNo = applicationNo, caseNo = caseNo, applyType = applyType,
    patientInpatientNo = patientInpatientNo, wardCode = wardCode,
    escortName = escortName, escortIdNo = escortIdNo, escortPhone = escortPhone,
    relationToPatient = relationToPatient, healthCommitment = healthCommitment,
    screeningType = screeningType, screeningValidUntil = screeningValidUntil,
    periodStart = periodStart, periodEnd = periodEnd, needNightCare = needNightCare,
    status = status.name, submittedAt = submittedAt, decidedAt = decidedAt,
    decidedBy = decidedBy, decisionNote = decisionNote
)

fun com.hospital.escort.domain.EscortPermit.toDto(caseNo: String, syncedTargets: List<String>) = PermitDto(
    permitNo = permitNo, caseNo = caseNo, wardCode = wardCode, qrToken = qrToken,
    escortName = escortName, escortIdNo = escortIdNo, escortPhone = escortPhone,
    validFrom = validFrom, validUntil = validUntil, nightCareAllowed = nightCareAllowed,
    status = status, lockdownFrozen = lockdownFrozen, exitRestricted = exitRestricted,
    exitRestrictReason = exitRestrictReason, issuedAt = issuedAt, returnedAt = returnedAt,
    syncedTargets = syncedTargets
)

fun com.hospital.escort.domain.ScanRecord.toDto() = ScanRecordDto(
    id = id!!, permitNo = permitNo, direction = direction.name, gateLocation = gateLocation,
    scannedAt = scannedAt, scannedIdNo = scannedIdNo, result = result.name,
    outOfHours = outOfHours, escortChanged = escortChanged, multiPermitShared = multiPermitShared,
    wardLockdown = wardLockdown, denyReason = denyReason
)

fun com.hospital.escort.domain.ViolationRecord.toDto() = ViolationDto(
    id = id!!, type = type.name, detail = detail, reportedBy = reportedBy, status = status.name,
    createdAt = createdAt, resolvedAt = resolvedAt, resolutionNote = resolutionNote
)
