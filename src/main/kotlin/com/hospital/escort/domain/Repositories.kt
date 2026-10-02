package com.hospital.escort.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface WardPolicyRepository : JpaRepository<WardPolicy, String>

interface EscortCaseRepository : JpaRepository<EscortCase, Long> {
    fun findByCaseNo(caseNo: String): EscortCase?

    /**
     * 患者当前仍在院(ACTIVE)的陪护记录。患者转科/重新入院时通过 caseNo 区分。
     */
    fun findFirstByPatientInpatientNoAndStatusOrderByCreatedAtDesc(
        patientInpatientNo: String, status: CaseStatus
    ): EscortCase?

    fun findByWardCodeAndStatus(wardCode: String, status: CaseStatus): List<EscortCase>
}

interface EscortApplicationRepository : JpaRepository<EscortApplication, Long> {
    fun findByApplicationNo(applicationNo: String): EscortApplication?
    fun findByCaseIdOrderBySubmittedAtAsc(caseId: Long): List<EscortApplication>
    fun countByWardCodeAndStatus(wardCode: String, status: ApplicationStatus): Long

    @Query("select count(a) from EscortApplication a where a.wardCode = :ward and a.status = :status and a.submittedAt >= :from")
    fun countByWardStatusSince(@Param("ward") ward: String, @Param("status") status: ApplicationStatus, @Param("from") from: Instant): Long
}

interface EscortPermitRepository : JpaRepository<EscortPermit, Long> {
    fun findByPermitNo(permitNo: String): EscortPermit?
    fun findByQrToken(qrToken: String): EscortPermit?
    fun findByCaseIdOrderByIssuedAtAsc(caseId: Long): List<EscortPermit>
    fun findByWardCodeAndStatus(wardCode: String, status: PermitStatus): List<EscortPermit>
    fun findByStatusAndValidUntilBefore(status: PermitStatus, now: Instant): List<EscortPermit>
    fun countByWardCodeAndStatus(wardCode: String, status: PermitStatus): Long

    /** 同一陪护人身份证在多个 ACTIVE 陪护证 -> 多证共用排查 */
    fun findByEscortIdNoAndStatus(escortIdNo: String, status: PermitStatus): List<EscortPermit>

    @Query("select count(p) from EscortPermit p where p.wardCode = :ward and p.status = :status and p.issuedAt >= :from")
    fun countByWardStatusSince(@Param("ward") ward: String, @Param("status") status: PermitStatus, @Param("from") from: Instant): Long

    @Query("select count(distinct p.escortIdNo) from EscortPermit p where p.wardCode = :ward and p.status = 'ACTIVE'")
    fun countDistinctActiveEscorts(@Param("ward") ward: String): Long
}

interface PermitSyncRepository : JpaRepository<PermitSync, Long> {
    fun findByPermitId(permitId: Long): List<PermitSync>
    fun findByStatus(status: SyncStatus): List<PermitSync>
    fun countByPermitIdAndStatus(permitId: Long, status: SyncStatus): Long
}

interface ReplacementRecordRepository : JpaRepository<ReplacementRecord, Long> {
    fun findByCaseIdOrderByRequestedAtAsc(caseId: Long): List<ReplacementRecord>
    fun findByModeAndStatus(mode: ReplacementMode, status: ReplacementStatus): List<ReplacementRecord>

    /** 某患者进行中(未完成/未取消)的临时替换, 防止重复发起 */
    fun findByCaseIdAndModeAndStatusIn(
        caseId: Long, mode: ReplacementMode, statuses: Collection<ReplacementStatus>
    ): List<ReplacementRecord>
}

interface PatrolRecordRepository : JpaRepository<PatrolRecord, Long> {
    fun findByReplacementIdOrderByScheduledAtAsc(replacementId: Long): List<PatrolRecord>
    fun findByCaseIdOrderByScheduledAtAsc(caseId: Long): List<PatrolRecord>
}

interface CareReminderRepository : JpaRepository<CareReminder, Long> {
    fun findByCaseIdOrderByRemindAtAsc(caseId: Long): List<CareReminder>
    fun findByReplacementIdOrderByRemindAtAsc(replacementId: Long): List<CareReminder>
    fun findByWardCodeAndStatusInOrderByRemindAtAsc(
        wardCode: String, statuses: Collection<CareReminderStatus>
    ): List<CareReminder>
    fun countByWardCodeAndStatus(wardCode: String, status: CareReminderStatus): Long
    fun findByReplacementIdAndStatusIn(
        replacementId: Long, statuses: Collection<CareReminderStatus>
    ): List<CareReminder>
}

interface ScanRecordRepository : JpaRepository<ScanRecord, Long> {
    fun findByCaseIdOrderByScannedAtAsc(caseId: Long): List<ScanRecord>
    fun findByWardCodeAndScannedAtBetween(wardCode: String, from: Instant, to: Instant): List<ScanRecord>

    /** 多证共用: 同一会话标识(扫码人身份证)在时间窗内扫了多个不同陪护证 */
    @Query("""select count(distinct s.permitNo) from ScanRecord s
             where s.scannedIdNo = :idNo and s.scannedAt between :from and :to and s.permitNo is not null""")
    fun countDistinctPermitsByIdNoInWindow(
        @Param("idNo") idNo: String, @Param("from") from: Instant, @Param("to") to: Instant
    ): Long

    /** 该陪护人上一次扫码方向, 用于判断本次扫码与证件持证人是否一致 */
    fun findFirstByScannedIdNoOrderByScannedAtDesc(idNo: String): ScanRecord?
}

interface ViolationRecordRepository : JpaRepository<ViolationRecord, Long> {
    fun findByCaseIdOrderByCreatedAtAsc(caseId: Long): List<ViolationRecord>
    fun findByWardCodeAndCreatedAtBetween(wardCode: String, from: Instant, to: Instant): List<ViolationRecord>
    fun countByWardCodeAndType(wardCode: String, type: ViolationType): Long
    fun findByStatus(status: ViolationStatus): List<ViolationRecord>
}

interface CaseEventRepository : JpaRepository<CaseEvent, Long> {
    fun findByCaseIdOrderByCreatedAtAsc(caseId: Long): List<CaseEvent>
}

interface WardNoticeRepository : JpaRepository<WardNotice, Long> {
    fun findByWardCodeAndReadFalseOrderByCreatedAtDesc(wardCode: String): List<WardNotice>
    fun findByWardCodeOrderByCreatedAtDesc(wardCode: String): List<WardNotice>
}
