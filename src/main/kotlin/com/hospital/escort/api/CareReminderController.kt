package com.hospital.escort.api

import com.hospital.escort.service.CareReminderService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 看护提醒（进食/翻身/夜间观察）。
 * 临时替换空窗期由护士站创建并承担；新陪护到位后提醒自动转为"家属已接手"并保留交接时间。
 */
@RestController
@RequestMapping("/api/care-reminders")
class CareReminderController(
    private val careReminderService: CareReminderService
) {

    /** 护士站临时创建看护提醒（可关联某次临时替换，或按 caseNo 创建常规提醒） */
    @PostMapping
    fun create(@Valid @RequestBody req: CreateCareReminderRequest): Map<String, Any?> {
        val r = careReminderService.createReminder(req)
        return mapOf(
            "reminderId" to r.id, "type" to r.type.name, "content" to r.content,
            "remindAt" to r.remindAt, "status" to r.status.name, "replacementId" to r.replacementId
        )
    }

    /** 完成提醒：空窗期为护士完成，家属接手后为家属完成 */
    @PostMapping("/{id}/complete")
    fun complete(@PathVariable id: Long, @Valid @RequestBody req: CompleteCareReminderRequest): Map<String, Any?> {
        val r = careReminderService.completeReminder(id, req)
        return mapOf(
            "reminderId" to r.id, "status" to r.status.name,
            "nurseDoneAt" to r.nurseDoneAt, "familyDoneAt" to r.familyDoneAt,
            "takenOverAt" to r.takenOverAt, "takenOverBy" to r.takenOverBy
        )
    }

    /** 取消提醒 */
    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: Long, @Valid @RequestBody req: CancelCareReminderRequest): Map<String, Any?> {
        val r = careReminderService.cancelReminder(id, req)
        return mapOf("reminderId" to r.id, "status" to r.status.name)
    }
}
