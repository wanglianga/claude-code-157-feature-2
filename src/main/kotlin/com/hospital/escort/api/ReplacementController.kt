package com.hospital.escort.api

import com.hospital.escort.domain.EscortApplicationRepository
import com.hospital.escort.domain.EscortPermitRepository
import com.hospital.escort.domain.ReplacementRecord
import com.hospital.escort.service.ReplacementService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 陪护人替换:
 *  - 常规替换 POST /api/replacements(新陪护已在场, 审批即发证)
 *  - 临时替换 POST /api/replacements/temporary(旧陪护突发疾病已离院, 患者短时无人陪护)
 *    -> POST /api/replacements/{id}/arrival 新陪护到院, 护士站核验身份/健康承诺/亲属关系后到位交接发证
 *    -> POST /api/replacements/{id}/cancel 新陪护无法接替时取消(护士站继续巡查)
 */
@RestController
@RequestMapping("/api/replacements")
class ReplacementController(
    private val replacementService: ReplacementService,
    private val replacementRepository: com.hospital.escort.domain.ReplacementRecordRepository,
    private val applicationRepository: EscortApplicationRepository,
    private val permitRepository: EscortPermitRepository
) {

    @PostMapping
    fun request(@Valid @RequestBody req: ReplacementRequest): Map<String, Any?> {
        val record = replacementService.requestReplacement(req)
        return detail(record)
    }

    @PostMapping("/{id}/decision")
    fun decide(@PathVariable id: Long, @Valid @RequestBody req: ReplacementDecisionRequest): Map<String, Any?> {
        val record = replacementService.decideReplacement(id, req.decidedBy, req.approved == true, req.note)
        return detail(record)
    }

    /** 临时替换: 原陪护已离院, 登记待接替亲属, 患者进入短时无人陪护状态 */
    @PostMapping("/temporary")
    fun temporary(@Valid @RequestBody req: TemporaryReplacementRequest): Map<String, Any?> {
        val record = replacementService.requestTemporaryReplacement(req)
        return detail(record)
    }

    /** 新陪护到院到位交接: 核验身份/健康承诺/亲属关系, 通过后签发新证并记录交接时间 */
    @PostMapping("/{id}/arrival")
    fun arrival(@PathVariable id: Long, @Valid @RequestBody req: ReplacementArrivalRequest): Map<String, Any?> {
        val record = replacementService.newEscortArrived(id, req)
        return detail(record)
    }

    /** 取消临时替换(新陪护无法到院), 患者维持无人陪护由护士站继续巡查 */
    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: Long, @Valid @RequestBody req: ReplacementCancelRequest): Map<String, Any?> {
        val record = replacementService.cancelTemporaryReplacement(id, req)
        return detail(record)
    }

    private fun detail(record: ReplacementRecord): Map<String, Any?> {
        val newApp = applicationRepository.findById(record.newApplicationId).orElse(null)
        val oldPermit = record.oldPermitId?.let { permitRepository.findById(it).orElse(null) }
        val newPermit = record.newPermitId?.let { permitRepository.findById(it).orElse(null) }
        return mapOf(
            "replacementId" to record.id,
            "status" to record.status.name,
            "temporary" to record.temporary,
            "reason" to record.reason.name,
            "oldPermitNo" to oldPermit?.permitNo,
            "oldEscortName" to oldPermit?.escortName,
            "newApplicationNo" to newApp?.applicationNo,
            "newPermitNo" to newPermit?.permitNo,
            "newEscortName" to newApp?.escortName,
            "relationToPatient" to newApp?.relationToPatient,
            "identityVerified" to record.identityVerified,
            "identityVerifiedBy" to record.identityVerifiedBy,
            "healthPromiseAccepted" to record.healthPromiseAccepted,
            "relationVerified" to record.relationVerified,
            "oldLeftAt" to record.oldLeftAt,
            "newArrivedAt" to record.newArrivedAt,
            "gapMinutes" to record.gapMinutes
        )
    }
}
