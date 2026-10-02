package com.hospital.escort.domain

enum class CaseStatus { ACTIVE, CLOSED }

enum class ApplicationType { INITIAL, REPLACEMENT }

enum class ApplicationStatus { PENDING, APPROVED, REJECTED, WITHDRAWN }

/** 核酸或传染病筛查要求: NAT=核酸检测, ANTIGEN=抗原快筛, NONE=病区不要求 */
enum class ScreeningType { NAT, ANTIGEN, NONE }

enum class PermitStatus { ACTIVE, FROZEN, EXPIRED, RETURNED, REVOKED, REPLACED }

enum class SyncTarget { GATE, WARD_SCAN, SECURITY_PATROL }

enum class SyncAction { ISSUE, REVOKE, FREEZE, UNFREEZE, UPDATE }

enum class SyncStatus { PENDING, SYNCED, FAILED }

/** 替换模式: REGULAR=常规替换(旧证与新证同批交接) / TEMPORARY=临时替换(夜间发烧等紧急场景, 允许短时无人陪护空窗) */
enum class ReplacementMode { REGULAR, TEMPORARY }

/** 替换原因: ESCORT_SICK=陪护人临时生病, FAMILY_DISPUTE=亲属争抢陪护资格, SCHEDULE=排班调整, OTHER=其他 */
enum class ReplacementReason { ESCORT_SICK, FAMILY_DISPUTE, SCHEDULE, OTHER }

/**
 * 替换状态:
 *  - REGULAR 模式: PENDING -> APPROVED / REJECTED
 *  - TEMPORARY 模式: APPROVAL_PENDING(待护士核验) -> OLD_LEFT(旧陪护离院, 患者空窗) ->
 *    NEW_ARRIVED(新陪护到位待身份复核) -> COMPLETED(复核通过, 新证签发) / CANCELLED(交接取消)
 */
enum class ReplacementStatus {
    PENDING, APPROVED, REJECTED,
    APPROVAL_PENDING, OLD_LEFT, NEW_ARRIVED, COMPLETED, CANCELLED
}

/** 空窗期巡查状态 */
enum class PatrolStatus { SCHEDULED, DONE, CANCELLED }

/** 看护提醒类型: 进食 / 翻身 / 夜间观察 / 其他 */
enum class CareReminderType { FEEDING, TURNING, NIGHT_OBSERVATION, OTHER }

/**
 * 看护提醒状态:
 * PENDING_NURSE=待护士处理(空窗期由护士站承担) / NURSE_DONE=护士已完成 /
 * FAMILY_TAKEN_OVER=新陪护到位, 家属已接手(保留交接时间) / FAMILY_DONE=家属已完成 / CANCELLED=取消
 */
enum class CareReminderStatus { PENDING_NURSE, NURSE_DONE, FAMILY_TAKEN_OVER, FAMILY_DONE, CANCELLED }

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
    TEMP_REPLACEMENT_REQUESTED,    // 家属申请临时替换
    TEMP_REPLACEMENT_APPROVED,     // 护士站核验身份/健康/关系后批准
    OLD_ESCORT_LEFT,               // 旧陪护离院, 患者进入短时无人陪护空窗
    NEW_ESCORT_ARRIVED,            // 新陪护到院, 等待身份复核
    TEMP_HANDOVER_COMPLETED,       // 身份复核通过, 新证签发, 交接完成
    TEMP_REPLACEMENT_CANCELLED,    // 临时交接取消
    PATROL_SCHEDULED,              // 护士站安排空窗期巡查
    PATROL_DONE,                   // 巡查完成
    CARE_REMINDER_CREATED,         // 护士站创建看护提醒(进食/翻身/夜间观察)
    CARE_REMINDER_TAKEN_OVER,      // 提醒转交家属(新陪护已接手)
    CARE_REMINDER_DONE,            // 看护提醒完成(护士/家属)
    CARE_REMINDER_CANCELLED,       // 看护提醒取消
    CASE_CLOSED
}
