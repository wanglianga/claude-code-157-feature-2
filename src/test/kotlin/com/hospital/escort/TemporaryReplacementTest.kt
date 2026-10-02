package com.hospital.escort

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 陪护人临时替换(原陪护人夜间发烧等紧急场景):
 * 身份/健康承诺/患者关系核验 -> 旧陪护离院(短时无人陪护空窗) -> 护士站巡查/看护提醒 ->
 * 新陪护到院现场身份复核 -> 交接完成(新证签发、提醒转交家属并保留交接时间)。
 */
class TemporaryReplacementTest : AbstractApiTest() {

    private fun tempRequestBody(
        oldPermitNo: String,
        name: String = "周母",
        idNo: String = "IDTEMPNEW01",
        phone: String = "13900003333",
        relation: String = "配偶",
        needNightCare: Boolean = false
    ) = mapOf(
        "oldPermitNo" to oldPermitNo,
        "reason" to "ESCORT_SICK",
        "detail" to "原陪护人夜间发烧38.8度, 家属申请由亲属接替",
        "escortName" to name, "escortIdNo" to idNo, "escortPhone" to phone,
        "relationToPatient" to relation, "healthCommitment" to true,
        "screeningType" to "NAT",
        "screeningValidUntil" to Instant.now().plus(2, ChronoUnit.DAYS).toString(),
        "periodEnd" to Instant.now().plus(7, ChronoUnit.DAYS).toString(),
        "needNightCare" to needNightCare,
        "expectedArriveAt" to Instant.now().plus(2, ChronoUnit.HOURS).toString()
    )

    /** 走完整临时替换流程到"旧陪护离院", 返回(caseNo, 旧证号, replacementId) */
    private fun prepareGap(): Triple<String, String, Long> {
        val (caseNo, oldPermitNo, _) = applyAndApprove(applyRequest(
            patientNo = "IP-TEMP-01", escortName = "周敏", escortIdNo = "IDTEMPOLD", escortPhone = "13811111111"))

        val created = post("/api/temporary-replacements", tempRequestBody(oldPermitNo))
        assertEquals(200, created.statusCode.value(), created.body.toString())
        val rid = created.body!!["replacementId"].asLong()
        assertEquals("APPROVAL_PENDING", created.body!!["status"].asText())

        // 未核验关系/健康不能批准
        val bad = post("/api/temporary-replacements/$rid/approval", mapOf(
            "decidedBy" to "NURSE01", "approved" to true))
        assertEquals(400, bad.statusCode.value())

        val approved = post("/api/temporary-replacements/$rid/approval", mapOf(
            "decidedBy" to "NURSE01", "approved" to true,
            "relationVerified" to true, "healthVerified" to true,
            "note" to "身份证与本人一致, 确认为患者配偶, 筛查在有效期"))
        assertEquals(200, approved.statusCode.value(), approved.body.toString())
        assertEquals("APPROVED", approved.body!!["status"].asText())

        val leave = post("/api/temporary-replacements/$rid/old-escort-leave", mapOf(
            "operator" to "NURSE01", "note" to "周敏发烧由家属陪同离院"))
        assertEquals(200, leave.statusCode.value(), leave.body.toString())
        assertEquals("OLD_LEFT", leave.body!!["status"].asText())
        return Triple(caseNo, oldPermitNo, rid)
    }

    @Test
    fun `临时替换全流程_核验_空窗_巡查_提醒转交_交接时间留痕`() {
        val (caseNo, oldPermitNo, rid) = prepareGap()

        // 旧证冻结(门禁停权), 患者当前无陪护人
        assertEquals("FROZEN", get("/api/permits/$oldPermitNo").body!!["status"].asText())
        val archiveInGap = get("/api/cases/$caseNo/archive").body!!
        assertTrue(archiveInGap["currentEscortName"].isNull)

        // 护士站看到患者处于短时无人陪护状态
        val unattended = get("/api/wards/W3A/unattended").body!!
        assertEquals(1, unattended.size())
        val u = unattended[0]
        assertEquals(caseNo, u["caseNo"].asText())
        assertEquals("周敏", u["oldEscortName"].asText())
        assertEquals("周母", u["newEscortName"].asText())
        assertTrue(u["unattendedMinutes"].asLong() >= 0)

        // 安排并完成一次空窗巡查
        val patrol = post("/api/temporary-replacements/$rid/patrols", mapOf(
            "scheduledBy" to "NURSE02",
            "scheduledAt" to Instant.now().plus(30, ChronoUnit.MINUTES).toString()))
        assertEquals(200, patrol.statusCode.value(), patrol.body.toString())
        val patrolId = patrol.body!!["patrolId"].asLong()

        val patrolDone = post("/api/temporary-replacements/$rid/patrols/$patrolId/complete", mapOf(
            "patrolledBy" to "NURSE02", "result" to "患者卧床, 生命体征平稳, 已协助饮水"))
        assertEquals(200, patrolDone.statusCode.value())
        assertEquals("DONE", patrolDone.body!!["status"].asText())

        // 空窗期护士站临时创建看护提醒: 进食/翻身/夜间观察
        fun createReminder(type: String, content: String, plusMin: Long): Long {
            val r = post("/api/care-reminders", mapOf(
                "replacementId" to rid, "type" to type, "content" to content,
                "remindAt" to Instant.now().plus(plusMin, ChronoUnit.MINUTES).toString(),
                "createdBy" to "NURSE02"))
            assertEquals(200, r.statusCode.value(), r.body.toString())
            return r.body!!["reminderId"].asLong()
        }
        val feedingId = createReminder("FEEDING", "06:00 协助进流食", 200)
        val turningId = createReminder("TURNING", "每2小时翻身防压疮", 60)
        val nightId = createReminder("NIGHT_OBSERVATION", "凌晨监测体温(原陪护发烧需警惕传染)", 120)

        // 进食由护士在空窗期完成
        val feedingDone = post("/api/care-reminders/$feedingId/complete",
            mapOf("operator" to "NURSE02", "note" to "已协助进食小米粥"))
        assertEquals("NURSE_DONE", feedingDone.body!!["status"].asText())

        // 看板: 1 个无人陪护患者, 2 条待护士处理提醒
        val dashboard = get("/api/wards/W3A/dashboard").body!!
        assertEquals(1, dashboard["unattendedPatientCount"].asInt())
        assertEquals(2, dashboard["pendingCareReminderCount"].asInt())

        // 新陪护提前到院: 未到离院阶段不能重复登记(此处正常登记到院)
        val arrive = post("/api/temporary-replacements/$rid/new-escort-arrive", mapOf("operator" to "NURSE02"))
        assertEquals(200, arrive.statusCode.value(), arrive.body.toString())
        assertEquals("NEW_ARRIVED", arrive.body!!["status"].asText())
        assertNotNull(arrive.body!!["newEscortArrivedAt"])

        // 空窗结束
        assertEquals(0, get("/api/wards/W3A/unattended").body!!.size())

        // 未现场身份复核不能完成交接
        val noIdentity = post("/api/temporary-replacements/$rid/complete",
            mapOf("completedBy" to "NURSE02"))
        assertEquals(400, noIdentity.statusCode.value())

        // 现场复核身份证一致, 完成交接
        val completed = post("/api/temporary-replacements/$rid/complete", mapOf(
            "completedBy" to "NURSE02", "identityVerified" to true,
            "note" to "人证一致, 已告知照护注意事项"))
        assertEquals(200, completed.statusCode.value(), completed.body.toString())
        assertEquals("COMPLETED", completed.body!!["status"].asText())
        assertNotNull(completed.body!!["handoverCompletedAt"])

        // 旧证 REPLACED, 新证 ACTIVE, 当前照护人=周母
        assertEquals("REPLACED", get("/api/permits/$oldPermitNo").body!!["status"].asText())
        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("周母", archive["currentEscortName"].asText())
        val newPermit = archive["permits"][1]
        assertEquals("ACTIVE", newPermit["status"].asText())
        val newPermitNo = newPermit["permitNo"].asText()
        assertTrue(newPermitNo.startsWith("EP-"))

        // 交接时间轴完整留痕
        val rep = archive["replacements"][0]
        assertEquals("TEMPORARY", rep["mode"].asText())
        assertEquals("COMPLETED", rep["status"].asText())
        assertTrue(rep["relationVerified"].asBoolean())
        assertTrue(rep["identityVerified"].asBoolean())
        assertTrue(rep["healthVerified"].asBoolean())
        assertNotNull(rep["oldEscortLeftAt"])
        assertNotNull(rep["newEscortArrivedAt"])
        assertNotNull(rep["handoverCompletedAt"])

        // 巡查归档到同一记录
        val patrols = archive["patrols"]
        assertEquals(1, patrols.size())
        assertEquals("DONE", patrols[0]["status"].asText())

        // 看护提醒: 护士完成的仍为 NURSE_DONE; 其余两条转交家属并保留交接时间
        val reminders = archive["careReminders"]
        assertEquals(3, reminders.size())
        val byType = reminders.associateBy { it["type"].asText() }
        assertEquals("NURSE_DONE", byType["FEEDING"]!!["status"].asText())
        assertEquals("FAMILY_TAKEN_OVER", byType["TURNING"]!!["status"].asText())
        assertEquals("FAMILY_TAKEN_OVER", byType["NIGHT_OBSERVATION"]!!["status"].asText())
        assertEquals("周母", byType["TURNING"]!!["takenOverBy"].asText())
        assertNotNull(byType["TURNING"]!!["takenOverAt"])
        assertEquals(newPermit["permitNo"].asText(), byType["TURNING"]!!["permitNo"].asText())

        // 家属完成翻身提醒 -> FAMILY_DONE
        val familyDone = post("/api/care-reminders/$turningId/complete",
            mapOf("operator" to "周母"))
        assertEquals("FAMILY_DONE", familyDone.body!!["status"].asText())

        // 病区通知含交接完成与当前照护人
        val notices = get("/api/wards/W3A/notices").body!!
        assertTrue(notices.any { it["type"].asText() == "TEMP_HANDOVER_COMPLETED" &&
                it["message"].asText().contains("周母") }, "应通知病区现由周母照护")
        assertTrue(notices.any { it["type"].asText() == "UNATTENDED_START" })

        // 事件流覆盖临时替换各环节
        val eventTypes = archive.let {
            get("/api/cases/$caseNo/archive").body!!["events"].map { e -> e["eventType"].asText() }
        }
        listOf(
            "TEMP_REPLACEMENT_REQUESTED", "TEMP_REPLACEMENT_APPROVED", "OLD_ESCORT_LEFT",
            "NEW_ESCORT_ARRIVED", "TEMP_HANDOVER_COMPLETED", "PATROL_SCHEDULED", "PATROL_DONE",
            "CARE_REMINDER_CREATED", "CARE_REMINDER_TAKEN_OVER"
        ).forEach { assertTrue(eventTypes.contains(it), "事件流缺少 $it") }
    }

    @Test
    fun `申请核验_健康承诺缺失或关系为空被拒`() {
        val (_, oldPermitNo, _) = applyAndApprove(applyRequest(
            patientNo = "IP-TEMP-02", escortName = "吴霞", escortIdNo = "IDTEMP02"))

        val noHealth = post("/api/temporary-replacements",
            tempRequestBody(oldPermitNo, name = "吴父", idNo = "IDT02NEW") + ("healthCommitment" to false))
        assertEquals(400, noHealth.statusCode.value())

        val noRelation = post("/api/temporary-replacements",
            tempRequestBody(oldPermitNo, name = "吴父", idNo = "IDT02NEW") + ("relationToPatient" to ""))
        assertEquals(400, noRelation.statusCode.value())

        // 新陪护不能与原陪护同一人
        val same = post("/api/temporary-replacements",
            tempRequestBody(oldPermitNo, name = "吴霞", idNo = "IDTEMP02"))
        assertEquals(400, same.statusCode.value())
    }

    @Test
    fun `夜间陪护替换需专项审批且新证夜间陪护生效`() {
        val (caseNo, oldPermitNo, _) = applyAndApprove(applyRequest(
            patientNo = "IP-TEMP-03", escortName = "郑红", escortIdNo = "IDTEMP03", needNightCare = true),
            approveNightCare = true)

        val created = post("/api/temporary-replacements",
            tempRequestBody(oldPermitNo, name = "郑妹", idNo = "IDT03NEW", relation = "妹妹", needNightCare = true))
        val rid = created.body!!["replacementId"].asLong()

        val noNight = post("/api/temporary-replacements/$rid/approval", mapOf(
            "decidedBy" to "NURSE-HEAD", "approved" to true,
            "relationVerified" to true, "healthVerified" to true, "approveNightCare" to false))
        assertEquals(400, noNight.statusCode.value())

        val approved = post("/api/temporary-replacements/$rid/approval", mapOf(
            "decidedBy" to "NURSE-HEAD", "approved" to true,
            "relationVerified" to true, "healthVerified" to true, "approveNightCare" to true))
        assertEquals(200, approved.statusCode.value())
        assertTrue(approved.body!!["nightCareApproved"].asBoolean())

        post("/api/temporary-replacements/$rid/old-escort-leave", mapOf("operator" to "NURSE01"))
        post("/api/temporary-replacements/$rid/new-escort-arrive", mapOf("operator" to "NURSE01"))
        val completed = post("/api/temporary-replacements/$rid/complete",
            mapOf("completedBy" to "NURSE-HEAD", "identityVerified" to true))
        assertEquals(200, completed.statusCode.value())

        val newPermit = get("/api/cases/$caseNo/archive").body!!["permits"][1]
        assertTrue(newPermit["nightCareAllowed"].asBoolean())
    }

    @Test
    fun `空窗期取消替换_旧陪护返岗旧证恢复巡查取消`() {
        val (caseNo, permitNo, rid) = run {
            val (c, p, _) = applyAndApprove(applyRequest(
                patientNo = "IP-TEMP-04", escortName = "冯兰", escortIdNo = "IDTEMP04"))
            val cr = post("/api/temporary-replacements", tempRequestBody(p, name = "冯兄", idNo = "IDT04NEW", relation = "哥哥"))
            val id = cr.body!!["replacementId"].asLong()
            post("/api/temporary-replacements/$id/approval", mapOf(
                "decidedBy" to "NURSE01", "relationVerified" to true, "healthVerified" to true))
            post("/api/temporary-replacements/$id/old-escort-leave", mapOf("operator" to "NURSE01"))
            Triple(c, p, id)
        }

        val patrol = post("/api/temporary-replacements/$rid/patrols", mapOf(
            "scheduledBy" to "NURSE02", "scheduledAt" to Instant.now().plus(30, ChronoUnit.MINUTES).toString()))
        val pid = patrol.body!!["patrolId"].asLong()

        val cancel = post("/api/temporary-replacements/$rid/cancel", mapOf(
            "operator" to "NURSE01", "reason" to "原陪护冯兰退烧至37度, 可继续照护"))
        assertEquals(200, cancel.statusCode.value(), cancel.body.toString())
        assertEquals("CANCELLED", cancel.body!!["status"].asText())

        // 旧证恢复 ACTIVE, 当前照护人恢复为冯兰
        assertEquals("ACTIVE", get("/api/permits/$permitNo").body!!["status"].asText())
        val archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("冯兰", archive["currentEscortName"].asText())
        // 巡查安排被取消
        val patrols = get("/api/temporary-replacements/$rid/patrols").body!!
        assertEquals("CANCELLED", patrols.first { it["patrolId"].asLong() == pid }["status"].asText())
        // 患者不再出现在空窗列表
        assertFalse(get("/api/wards/W3A/unattended").body!!.any { it["caseNo"].asText() == caseNo })
    }

    @Test
    fun `状态机非法跳转被拒且非空窗不能安排巡查`() {
        val (_, oldPermitNo, rid) = run {
            val (c, p, _) = applyAndApprove(applyRequest(
                patientNo = "IP-TEMP-05", escortName = "蒋芳", escortIdNo = "IDTEMP05"))
            val cr = post("/api/temporary-replacements", tempRequestBody(p, name = "蒋姐", idNo = "IDT05NEW"))
            val id = cr.body!!["replacementId"].asLong()
            post("/api/temporary-replacements/$id/approval", mapOf(
                "decidedBy" to "NURSE01", "relationVerified" to true, "healthVerified" to true))
            Triple(c, p, id)
        }
        // APPROVED 阶段尚未离院: 不能登记新陪护到院
        val arriveEarly = post("/api/temporary-replacements/$rid/new-escort-arrive", mapOf("operator" to "NURSE01"))
        assertEquals(409, arriveEarly.statusCode.value())
        // 非空窗不能安排巡查
        val patrolEarly = post("/api/temporary-replacements/$rid/patrols", mapOf(
            "scheduledBy" to "NURSE01", "scheduledAt" to Instant.now().plus(10, ChronoUnit.MINUTES).toString()))
        assertEquals(409, patrolEarly.statusCode.value())
        // 交接完成后不能再创建提醒
        post("/api/temporary-replacements/$rid/old-escort-leave", mapOf("operator" to "NURSE01"))
        post("/api/temporary-replacements/$rid/new-escort-arrive", mapOf("operator" to "NURSE01"))
        post("/api/temporary-replacements/$rid/complete",
            mapOf("completedBy" to "NURSE01", "identityVerified" to true))
        val reminderAfter = post("/api/care-reminders", mapOf(
            "replacementId" to rid, "type" to "FEEDING", "content" to "迟到的提醒",
            "remindAt" to Instant.now().plus(1, ChronoUnit.HOURS).toString(), "createdBy" to "NURSE01"))
        assertEquals(409, reminderAfter.statusCode.value())
    }
}
