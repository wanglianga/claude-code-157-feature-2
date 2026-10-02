package com.hospital.escort.api

import com.hospital.escort.domain.ReminderStatus
import com.hospital.escort.service.CareReminderService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 临时看护提醒: 临时替换(患者短时无人陪护)期间, 护士站为患者临时创建
 * 进食(FEEDING)/翻身(TURNING)/夜间观察(NIGHT_WATCH)提醒并安排巡查;
 * 新陪护到位交接后, 提醒自动转为"家属已接手"并保留交接时间。
 */
@RestController
@RequestMapping("/api/care-reminders")
class CareReminderController(
    private val careReminderService: CareReminderService
) {

    /** 护士站为无人陪护间隙中的患者临时创建看护提醒 */
    @PostMapping
    fun create(@Valid @RequestBody req: CareReminderRequest): CareReminderDto {
        val saved = careReminderService.createReminder(req)
        return careReminderService.getDto(saved.id!!)
    }

    /** 护士已执行一次巡查照护 */
    @PostMapping("/{id}/nurse-done")
    fun nurseDone(@PathVariable id: Long, @Valid @RequestBody req: CareReminderActionRequest): CareReminderDto {
        careReminderService.markNurseDone(id, req)
        return careReminderService.getDto(id)
    }

    /** 按陪护记录查询提醒(归档视图) */
    @GetMapping("/by-case/{caseNo}")
    fun byCase(@PathVariable caseNo: String): List<CareReminderDto> =
        careReminderService.listByCase(caseNo)

    /** 护士站按病区查询看护提醒(可按状态过滤: NURSE_PENDING/NURSE_DONE/FAMILY_TAKEN_OVER) */
    @GetMapping
    fun list(
        @RequestParam wardCode: String,
        @RequestParam(required = false) status: ReminderStatus?
    ): List<CareReminderDto> = careReminderService.listWardReminders(wardCode, status)
}
