package com.hospital.escort.domain

enum class CaseStatus { ACTIVE, CLOSED }

/** 患者当前陪护状态: WITH_ESCORT=有陪护人在岗, UNATTENDED=临时替换间隙短时无人陪护 */
enum class CareStatus { WITH_ESCORT, UNATTENDED }

enum class ApplicationType { INITIAL, REPLACEMENT }

enum class ApplicationStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }

/** 核酸或传染病筛查要求: NAT=核酸检测, ANTIGEN=抗原快筛, NONE=病区不要求 */
enum class ScreeningType { NAT, ANTIGEN, NONE }

enum class PermitStatus { ACTIVE, FROZEN, EXPIRED, RETURNED, REVOKED, REPLACED }

enum class SyncTarget { GATE, WARD_SCAN, SECURITY_PATROL }

enum class SyncAction { ISSUE, REVOKE, FREEZE, UNFREEZE, UPDATE }

enum class SyncStatus { PENDING, SYNCED, FAILED }

/** 替换原因: ESCORT_SICK=陪护人临时生病, FAMILY_DISPUTE=亲属争抢陪护资格, SCHEDULE=排班调整, OTHER=其他 */
enum class ReplacementReason { ESCORT_SICK, FAMILY_DISPUTE, SCHEDULE, OTHER }

enum class ReplacementStatus { PENDING, APPROVED, REJECTED, CANCELED }

/** 临时看护提醒类型: FEEDING=进食, TURNING=翻身, NIGHT_WATCH=夜间观察/巡查 */
enum class ReminderType { FEEDING, TURNING, NIGHT_WATCH }

/** NURSE_PENDING=待护士巡查执行, NURSE_DONE=护士已执行, FAMILY_TAKEN_OVER=新陪护到位后家属接手 */
enum class ReminderStatus { NURSE_PENDING, NURSE_DONE, FAMILY_TAKEN_OVER }

enum class ScanDirection { IN, OUT }

enum class ScanResult { ALLOWED, DENIED }

enum class ViolationType {
    ESCORT_UNREGISTERED_CHANGE,  // 未登记更换陪护人(人证不符)
    MULTI_PERMIT_SHARED,         // 多证共用
    NIGHT_ABNORMAL,              // 夜间异常出入
    EXIT_BREACH,                 // 违反护士外出限制
    EXPIRED_NOT_RETURNED,        // 陪护证过期未退
    SECURITY_IMPERSONATION,      // 安保发现冒用
    LOCKDOWN_BREACH              // 封闭管理期间违规
}

enum class ViolationStatus { OPEN, CONFIRMED, RESOLVED, DISMISSED }

enum class CaseEventType {
    APPLICATION_SUBMITTED,
    APPLICATION_APPROVED,
    APPLICATION_REJECTED,
    PERMIT_ISSUED,
    PERMIT_FROZEN,
    PERMIT_UNFROZEN,
    PERMIT_EXPIRED,
    PERMIT_RETURNED,
    PERMIT_REVOKED,
    PERMIT_REPLACED,
    REPLACEMENT_REQUESTED,
    REPLACEMENT_APPROVED,
    REPLACEMENT_REJECTED,
    SCAN_IN,
    SCAN_OUT,
    VIOLATION_REPORTED,
    VIOLATION_RESOLVED,
    PATIENT_TRANSFERRED,
    WARD_LOCKDOWN,
    WARD_LOCKDOWN_LIFTED,
    EXIT_RESTRICTED,
    EXIT_RESTRICTION_LIFTED,
    DEPOSIT_DISPUTE_RAISED,
    DEPOSIT_DISPUTE_RESOLVED,
    CAREGIVER_CHANGED,
    TEMP_REPLACEMENT_OLD_LEFT,
    TEMP_REPLACEMENT_VERIFIED,
    TEMP_REPLACEMENT_ARRIVED,
    TEMP_REPLACEMENT_CANCELED,
    CARE_REMINDER_CREATED,
    CARE_REMINDER_NURSE_DONE,
    CARE_REMINDER_TAKEN_OVER,
    CASE_CLOSED
}
