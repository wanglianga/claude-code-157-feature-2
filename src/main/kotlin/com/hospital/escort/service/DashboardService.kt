package com.hospital.escort.service

import com.hospital.escort.api.ViolationDto
import com.hospital.escort.api.WardDashboardDto
import com.hospital.escort.common.ApiException
import com.hospital.escort.domain.ApplicationStatus
import com.hospital.escort.domain.CareReminderRepository
import com.hospital.escort.domain.CareStatus
import com.hospital.escort.domain.CaseStatus
import com.hospital.escort.domain.EscortApplicationRepository
import com.hospital.escort.domain.EscortCaseRepository
import com.hospital.escort.domain.EscortPermitRepository
import com.hospital.escort.domain.PermitStatus
import com.hospital.escort.domain.PermitSyncRepository
import com.hospital.escort.domain.ReminderStatus
import com.hospital.escort.domain.ScanDirection
import com.hospital.escort.domain.ScanRecordRepository
import com.hospital.escort.domain.ViolationRecordRepository
import com.hospital.escort.domain.ViolationStatus
import com.hospital.escort.domain.ViolationType
import com.hospital.escort.domain.WardPolicyRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 管理者看板: 病区陪护人数、夜间出入异常、违规频次、退证效率。
 */
@Service
class DashboardService(
    private val wardPolicyRepository: WardPolicyRepository,
    private val caseRepository: EscortCaseRepository,
    private val permitRepository: EscortPermitRepository,
    private val scanRepository: ScanRecordRepository,
    private val violationRepository: ViolationRecordRepository,
    private val applicationRepository: EscortApplicationRepository,
    @Suppress("unused") private val syncRepository: PermitSyncRepository,
    private val reminderRepository: CareReminderRepository,
    @Value("\${escort.zone-id}") zoneIdValue: String
) {
    private val zoneId: ZoneId = ZoneId.of(zoneIdValue)

    @Transactional(readOnly = true)
    fun wardDashboard(wardCode: String, days: Long = 7): WardDashboardDto {
        val policy = wardPolicyRepository.findById(wardCode).orElse(null)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "病区不存在: $wardCode")
        val now = Instant.now()
        val windowStart = now.minus(Duration.ofDays(days))
        val todayStart = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant()

        val activePermits = permitRepository.findByWardCodeAndStatus(wardCode, PermitStatus.ACTIVE)
        val activeCases = caseRepository.findByWardCodeAndStatus(wardCode, CaseStatus.ACTIVE)
        val activePatients = activeCases.size.toLong()
        val unattendedPatients = activeCases.count { it.careStatus == CareStatus.UNATTENDED }.toLong()
        val pendingReminders = reminderRepository
            .findByWardCodeAndStatusOrderByDueAtAsc(wardCode, ReminderStatus.NURSE_PENDING).size.toLong()

        // 当前在病区内的陪护人数: 每张有效证最后一次扫码方向为 IN(无记录视为在病区)
        var insideCount = 0L
        activePermits.forEach { p ->
            val last = scanRepository.findByCaseIdOrderByScannedAtAsc(p.caseId)
                .lastOrNull { it.permitId == p.id }
            if (last == null || last.direction == ScanDirection.IN) insideCount++
        }

        val recentScans = scanRepository.findByWardCodeAndScannedAtBetween(wardCode, windowStart, now)
        val todayScans = scanRepository.findByWardCodeAndScannedAtBetween(wardCode, todayStart, now)
        val todayIn = todayScans.count { it.direction == ScanDirection.IN }.toLong()
        val todayOut = todayScans.count { it.direction == ScanDirection.OUT }.toLong()
        val nightAbnormal = recentScans.count { it.outOfHours }.toLong()
        val outOfHoursToday = todayScans.count { it.outOfHours }.toLong()

        val violations = violationRepository.findByWardCodeAndCreatedAtBetween(wardCode, windowStart, now)
        val byType = violations.groupingBy { it.type.name }.eachCount().mapValues { it.value.toLong() }
        val expiredNotReturned = violationRepository.countByWardCodeAndType(wardCode, ViolationType.EXPIRED_NOT_RETURNED)
        val pendingApplications = applicationRepository.countByWardCodeAndStatus(wardCode, ApplicationStatus.PENDING)

        // 退证效率: 窗口内退证证件从签发到退证的平均小时数
        val returnedPermits = permitRepository.findAll().filter {
            it.wardCode == wardCode && it.status == PermitStatus.RETURNED && it.returnedAt != null &&
                    it.returnedAt!!.isAfter(windowStart)
        }
        val avgReturnHours = if (returnedPermits.isEmpty()) null
        else returnedPermits.sumOf { Duration.between(it.issuedAt, it.returnedAt).toMinutes() }
            .toDouble() / returnedPermits.size / 60.0

        val openViolations = violations.filter { it.status == ViolationStatus.OPEN }.map {
            ViolationDto(
                id = it.id!!, type = it.type.name, detail = it.detail, reportedBy = it.reportedBy,
                status = it.status.name, createdAt = it.createdAt, resolvedAt = it.resolvedAt,
                resolutionNote = it.resolutionNote
            )
        }

        return WardDashboardDto(
            wardCode = wardCode, lockdown = policy.lockdown,
            activeEscortCount = insideCount, activePatientCount = activePatients,
            unattendedPatientCount = unattendedPatients, pendingReminderCount = pendingReminders,
            todayEntryCount = todayIn, todayExitCount = todayOut,
            nightAbnormalCount = nightAbnormal, outOfHoursCount = outOfHoursToday,
            violationCount = violations.size.toLong(), violationsByType = byType,
            expiredNotReturnedCount = expiredNotReturned, pendingApplications = pendingApplications,
            avgReturnHours = avgReturnHours?.let { Math.round(it * 100) / 100.0 },
            openViolations = openViolations
        )
    }
}
