package com.hospital.escort.api

import com.hospital.escort.service.TemporaryReplacementService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 陪护人临时替换（原陪护人夜间发烧等紧急场景）。
 * 流程: 家属申请 -> 护士站核验身份/健康承诺/患者关系 -> 旧陪护离院(空窗) ->
 * 新陪护到院 -> 现场身份复核完成交接(新证签发、看护提醒转交家属)。
 */
@RestController
@RequestMapping("/api/temporary-replacements")
class TemporaryReplacementController(
    private val service: TemporaryReplacementService
) {

    /** 家属申请临时替换（登记新陪护身份、健康承诺、与患者关系） */
    @PostMapping
    fun request(@Valid @RequestBody req: TemporaryReplacementRequest): Map<String, Any?> {
        val r = service.requestTemporary(req)
        return mapOf(
            "replacementId" to r.id,
            "status" to r.status.name,
            "mode" to r.mode.name,
            "newApplicationId" to r.newApplicationId
        )
    }

    /** 护士站核验审批（身份信息/健康承诺/与患者关系/夜间专项） */
    @PostMapping("/{id}/approval")
    fun approve(@PathVariable id: Long, @Valid @RequestBody req: TemporaryReplacementApprovalRequest): Map<String, Any?> {
        val r = service.approve(id, req)
        return mapOf(
            "replacementId" to r.id, "status" to r.status.name,
            "relationVerified" to r.relationVerified, "healthVerified" to r.healthVerified,
            "nightCareApproved" to r.nightCareApproved, "decidedBy" to r.decidedBy
        )
    }

    /** 旧陪护离院登记：患者进入短时无人陪护空窗，旧证冻结 */
    @PostMapping("/{id}/old-escort-leave")
    fun oldLeave(@PathVariable id: Long, @Valid @RequestBody req: OldEscortLeaveRequest): Map<String, Any?> {
        val r = service.oldEscortLeave(id, req)
        return mapOf(
            "replacementId" to r.id, "status" to r.status.name,
            "oldEscortLeftAt" to r.oldEscortLeftAt
        )
    }

    /** 新陪护到院登记（空窗结束） */
    @PostMapping("/{id}/new-escort-arrive")
    fun newArrive(@PathVariable id: Long, @Valid @RequestBody req: NewEscortArriveRequest): Map<String, Any?> {
        val r = service.newEscortArrive(id, req)
        return mapOf(
            "replacementId" to r.id, "status" to r.status.name,
            "newEscortArrivedAt" to r.newEscortArrivedAt
        )
    }

    /** 现场身份复核通过，完成交接（旧证作废、新证签发、看护提醒转交家属） */
    @PostMapping("/{id}/complete")
    fun complete(@PathVariable id: Long, @Valid @RequestBody req: CompleteHandoverRequest): Map<String, Any?> {
        val r = service.completeHandover(id, req)
        return mapOf(
            "replacementId" to r.id, "status" to r.status.name,
            "newPermitId" to r.newPermitId, "identityVerified" to r.identityVerified,
            "handoverCompletedAt" to r.handoverCompletedAt
        )
    }

    /** 取消临时替换（旧陪护退烧返回等） */
    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: Long, @Valid @RequestBody req: CancelTemporaryReplacementRequest): Map<String, Any?> {
        val r = service.cancel(id, req)
        return mapOf("replacementId" to r.id, "status" to r.status.name)
    }

    /** 空窗期安排巡查 */
    @PostMapping("/{id}/patrols")
    fun schedulePatrol(@PathVariable id: Long, @Valid @RequestBody req: SchedulePatrolRequest): Map<String, Any?> {
        val p = service.schedulePatrol(id, req)
        return mapOf(
            "patrolId" to p.id, "status" to p.status.name,
            "scheduledAt" to p.scheduledAt, "scheduledBy" to p.scheduledBy
        )
    }

    /** 完成巡查 */
    @PostMapping("/{id}/patrols/{patrolId}/complete")
    fun completePatrol(
        @PathVariable id: Long,
        @PathVariable patrolId: Long,
        @Valid @RequestBody req: CompletePatrolRequest
    ): Map<String, Any?> {
        val p = service.completePatrol(patrolId, req)
        return mapOf(
            "patrolId" to p.id, "status" to p.status.name,
            "patrolledAt" to p.patrolledAt, "patrolledBy" to p.patrolledBy, "result" to p.result
        )
    }

    /** 查看某次临时替换的巡查安排 */
    @GetMapping("/{id}/patrols")
    fun patrols(@PathVariable id: Long): List<Map<String, Any?>> =
        service.listPatrols(id).map {
            mapOf(
                "patrolId" to it.id, "caseId" to it.caseId, "wardCode" to it.wardCode,
                "bedNo" to it.bedNo, "scheduledAt" to it.scheduledAt, "scheduledBy" to it.scheduledBy,
                "status" to it.status.name, "result" to it.result,
                "patrolledAt" to it.patrolledAt, "patrolledBy" to it.patrolledBy
            )
        }
}
