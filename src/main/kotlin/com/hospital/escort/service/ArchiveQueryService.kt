package com.hospital.escort.service

import com.hospital.escort.api.ApplicationDto
import com.hospital.escort.api.CareReminderDto
import com.hospital.escort.api.CaseArchiveDto
import com.hospital.escort.api.EventDto
import com.hospital.escort.api.PermitDto
import com.hospital.escort.api.ReplacementDto
import com.hospital.escort.api.ScanRecordDto
import com.hospital.escort.api.ViolationDto
import com.hospital.escort.api.WardNoticeDto
import com.hospital.escort.common.notFound
import com.hospital.escort.domain.ApplicationStatus
import com.hospital.escort.domain.CareReminderRepository
import com.hospital.escort.domain.CaseEventRepository
import com.hospital.escort.domain.EscortApplicationRepository
import com.hospital.escort.domain.EscortCaseRepository
import com.hospital.escort.domain.EscortPermitRepository
import com.hospital.escort.domain.PermitSyncRepository
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.ReplacementRecordRepository
import com.hospital.escort.domain.ScanRecordRepository
import com.hospital.escort.domain.SyncStatus
import com.hospital.escort.domain.ViolationRecordRepository
import com.hospital.escort.domain.WardNoticeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 单条陪护记录归档查询: 申请、审批、证件、进出、替换、违规、事件流全部在同一视图。
 */
@Service
class ArchiveQueryService(
    private val caseRepository: EscortCaseRepository,
    private val applicationRepository: EscortApplicationRepository,
    private val permitRepository: EscortPermitRepository,
    private val scanRepository: ScanRecordRepository,
    private val replacementRepository: ReplacementRecordRepository,
    private val violationRepository: ViolationRecordRepository,
    private val eventRepository: CaseEventRepository,
    private val syncRepository: PermitSyncRepository,
    private val noticeRepository: WardNoticeRepository,
    private val reminderRepository: CareReminderRepository
) {

    @Transactional(readOnly = true)
    fun getCaseArchive(caseNo: String): CaseArchiveDto {
        val escortCase = caseRepository.findByCaseNo(caseNo) ?: notFound("陪护记录不存在: $caseNo")
        val caseId = escortCase.id!!

        val applications = applicationRepository.findByCaseIdOrderBySubmittedAtAsc(caseId)
        val permits = permitRepository.findByCaseIdOrderByIssuedAtAsc(caseId)
        val permitNoById = permits.associate { it.id to it.permitNo }

        return CaseArchiveDto(
            caseNo = escortCase.caseNo,
            patientInpatientNo = escortCase.patientInpatientNo,
            wardCode = escortCase.wardCode,
            status = escortCase.status.name,
            careStatus = escortCase.careStatus.name,
            unattendedSince = escortCase.unattendedSince,
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
                val newApp = applications.firstOrNull { it.id == r.newApplicationId }
                ReplacementDto(
                    id = r.id!!, reason = r.reason.name, detail = r.detail, status = r.status.name,
                    temporary = r.temporary,
                    oldPermitNo = r.oldPermitId?.let { permitNoById[it] },
                    newPermitNo = r.newPermitId?.let { permitNoById[it] },
                    newEscortName = newApp?.escortName,
                    relationToPatient = newApp?.relationToPatient,
                    identityVerified = r.identityVerified,
                    healthPromiseAccepted = r.healthPromiseAccepted,
                    relationVerified = r.relationVerified,
                    oldLeftAt = r.oldLeftAt, newArrivedAt = r.newArrivedAt, gapMinutes = r.gapMinutes,
                    requestedAt = r.requestedAt, decidedAt = r.decidedAt, decidedBy = r.decidedBy
                )
            },
            violations = violationRepository.findByCaseIdOrderByCreatedAtAsc(caseId).map { it.toDto() },
            reminders = reminderRepository.findByCaseIdOrderByCreatedAtAsc(caseId)
                .map { it.toDto(escortCase.caseNo) },
            events = eventRepository.findByCaseIdOrderByCreatedAtAsc(caseId).map {
                EventDto(it.eventType.name, it.actor, it.summary, it.detail, it.refType, it.refId, it.createdAt)
            },
            createdAt = escortCase.createdAt,
            closedAt = escortCase.closedAt
        )
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

fun com.hospital.escort.domain.CareReminder.toDto(caseNo: String) = CareReminderDto(
    id = id!!, caseNo = caseNo, wardCode = wardCode, replacementId = replacementId,
    type = type.name, note = note, dueAt = dueAt, status = status.name,
    createdBy = createdBy, createdAt = createdAt,
    nurseDoneAt = nurseDoneAt, nurseDoneBy = nurseDoneBy,
    takenOverAt = takenOverAt, takenOverBy = takenOverBy
)
