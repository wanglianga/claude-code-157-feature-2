package com.hospital.escort

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 陪护人临时替换(原陪护夜间发烧, 家属申请另一位亲属接替):
 *  1. 旧陪护离院即停用旧证, 患者进入短时无人陪护, 护士站可见并安排巡查;
 *  2. 护士站核验新陪护身份/健康承诺/与患者关系, 到位交接并记录交接时间;
 *  3. 无人陪护间隙可临时创建进食/翻身/夜间观察提醒, 新陪护到位后转为家属已接手并保留交接时间。
 */
class TemporaryReplacementTest : AbstractApiTest() {

    private fun tempRequestBody(
        oldPermitNo: String,
        name: String,
        idNo: String,
        relation: String = "妹妹",
        phone: String = "13900000088",
        healthCommitment: Boolean = true,
        oldLeftAt: Instant? = Instant.now().minus(30, ChronoUnit.MINUTES),
        extra: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = mapOf(
        "oldPermitNo" to oldPermitNo,
        "reason" to "ESCORT_SICK",
        "detail" to "原陪护夜间发烧39度, 家属申请亲属接替",
        "oldLeftAt" to oldLeftAt?.toString(),
        "escortName" to name,
        "escortIdNo" to idNo,
        "escortPhone" to phone,
        "relationToPatient" to relation,
        "healthCommitment" to healthCommitment,
        "screeningType" to "NAT",
        "screeningValidUntil" to Instant.now().plus(2, ChronoUnit.DAYS).toString(),
        "periodEnd" to Instant.now().plus(7, ChronoUnit.DAYS).toString()
    ) + extra

    @Test
    fun `临时替换全流程旧证停用无人陪护护士巡查提醒到位核验后交接`() {
        val (caseNo, oldPermitNo, _) = applyAndApprove(applyRequest(
            patientNo = "IP-TEMP-01", escortName = "周敏", escortIdNo = "IDTEMPOLD", escortPhone = "13800000001"))

        // 1. 家属申请临时替换: 原陪护已离院
        val leftAt = Instant.now().minus(35, ChronoUnit.MINUTES)
        val apply = post("/api/replacements/temporary", tempRequestBody(
            oldPermitNo, "周兰", "IDTEMPNEW", relation = "妹妹", oldLeftAt = leftAt))
        assertEquals(200, apply.statusCode.value(), apply.body.toString())
        val replacementId = apply.body!!["replacementId"].asLong()
        assertEquals("PENDING", apply.body!!["status"].asText())
        assertTrue(apply.body!!["temporary"].asBoolean())
        assertEquals(oldPermitNo, apply.body!!["oldPermitNo"].asText())

        // 旧证即时停用, 门禁扫码拒绝
        val token = get("/api/permits/$oldPermitNo").body!!["qrToken"].asText()
        val denied = post("/api/access/scan", mapOf(
            "token" to token, "direction" to "IN", "gateLocation" to "W3A-东门闸机",
            "scannedAt" to Instant.now()))
        assertEquals("DENIED", denied.body!!["result"].asText())

        // 患者进入短时无人陪护: 归档与护士站巡查视图均可见
        var archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("UNATTENDED", archive["careStatus"].asText())
        assertTrue(archive["currentEscortName"].isNull)
        assertEquals("REPLACED", archive["permits"][0]["status"].asText())

        val unattended = get("/api/wards/W3A/unattended-patients").body!!
        val row = unattended.toList().first { it["caseNo"].asText() == caseNo }
        assertEquals("周敏", row["previousEscortName"].asText())
        assertEquals("周兰", row["newEscortName"].asText())
        assertEquals("妹妹", row["relationToPatient"].asText())
        assertTrue(row["unattendedMinutes"].asLong() >= 34L, row.toString())
        assertTrue(get("/api/wards/W3A/dashboard").body!!["unattendedPatientCount"].asLong() >= 1L)

        // 病区收到无人陪护通知
        val notices = get("/api/wards/W3A/notices").body!!
        assertTrue(notices.any { it["type"].asText() == "TEMP_REPLACEMENT_UNATTENDED" }, "应通知护士站安排巡查")

        // 2. 无人陪护间隙护士站临时创建三类看护提醒
        val feeding = post("/api/care-reminders", mapOf(
            "caseNo" to caseNo, "type" to "FEEDING", "note" to "22:00 协助进食温水和流食",
            "createdBy" to "NURSE01"))
        assertEquals(200, feeding.statusCode.value(), feeding.body.toString())
        val feedingId = feeding.body!!["id"].asLong()
        post("/api/care-reminders", mapOf(
            "caseNo" to caseNo, "type" to "TURNING", "note" to "每2小时翻身防压疮", "createdBy" to "NURSE01"))
        post("/api/care-reminders", mapOf(
            "caseNo" to caseNo, "type" to "NIGHT_WATCH", "note" to "夜间每小时观察生命体征", "createdBy" to "NURSE02"))
        assertTrue(get("/api/wards/W3A/care-reminders?status=NURSE_PENDING").body!!.size() >= 3)
        assertEquals(3L, get("/api/wards/W3A/unattended-patients").body!!
            .toList().first { it["caseNo"].asText() == caseNo }["pendingReminderCount"].asLong())

        // 护士先执行一次进食照护(新陪护仍未到)
        val done = post("/api/care-reminders/$feedingId/nurse-done",
            mapOf("operator" to "NURSE01", "note" to "已协助进水200ml"))
        assertEquals("NURSE_DONE", done.body!!["status"].asText())
        assertEquals("NURSE01", done.body!!["nurseDoneBy"].asText())

        // 3. 新陪护到院: 核验不通过必须拒绝, 患者维持无人陪护
        val noIdentity = post("/api/replacements/$replacementId/arrival", mapOf("operator" to "NURSE01"))
        assertEquals(400, noIdentity.statusCode.value())
        assertTrue(noIdentity.body!!["message"].asText().contains("身份"))
        val badHealth = post("/api/replacements/$replacementId/arrival", mapOf(
            "operator" to "NURSE01", "identityVerified" to true, "healthCommitmentAccepted" to false))
        assertEquals(400, badHealth.statusCode.value())
        assertTrue(badHealth.body!!["message"].asText().contains("健康承诺"))
        val badRelation = post("/api/replacements/$replacementId/arrival", mapOf(
            "operator" to "NURSE01", "identityVerified" to true,
            "healthCommitmentAccepted" to true, "relationVerified" to false))
        assertEquals(400, badRelation.statusCode.value())
        assertTrue(badRelation.body!!["message"].asText().contains("亲属关系"))
        assertEquals("UNATTENDED", get("/api/cases/$caseNo/archive").body!!["careStatus"].asText())

        // 三项核验全部通过 -> 到位交接, 签发新证(时间戳经数据库存储截断到微秒)
        val arrivedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val arrival = post("/api/replacements/$replacementId/arrival", mapOf(
            "operator" to "NURSE01", "arrivedAt" to arrivedAt.toString(),
            "identityVerified" to true, "healthCommitmentAccepted" to true, "relationVerified" to true,
            "note" to "人证一致, 无发热症状, 确系患者妹妹"))
        assertEquals(200, arrival.statusCode.value(), arrival.body.toString())
        assertEquals("APPROVED", arrival.body!!["status"].asText())
        assertTrue(arrival.body!!["identityVerified"].asBoolean())
        assertTrue(arrival.body!!["relationVerified"].asBoolean())
        assertEquals("周兰", arrival.body!!["newEscortName"].asText())
        assertNotNull(arrival.body!!["newPermitNo"])
        assertTrue(arrival.body!!["gapMinutes"].asLong() >= 34L)

        // 无人陪护间隙结束, 新陪护在岗
        archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("WITH_ESCORT", archive["careStatus"].asText())
        assertEquals("周兰", archive["currentEscortName"].asText())
        assertEquals("ACTIVE", archive["permits"][1]["status"].asText())
        val replacement = archive["replacements"][0]
        assertTrue(replacement["temporary"].asBoolean())
        assertTrue(replacement["identityVerified"].asBoolean())
        assertTrue(replacement["relationVerified"].asBoolean())
        assertNotNull(replacement["oldLeftAt"].asText())
        assertEquals(arrivedAt.toString(), replacement["newArrivedAt"].asText())

        // 全部看护提醒转为家属已接手, 且保留交接时间; 护士已执行的那条同时保留执行痕迹
        val reminders = archive["reminders"]
        assertEquals(3, reminders.size())
        reminders.forEach {
            assertEquals("FAMILY_TAKEN_OVER", it["status"].asText())
            assertEquals("周兰", it["takenOverBy"].asText())
            assertEquals(arrivedAt.toString(), it["takenOverAt"].asText())
        }
        val feedingReminder = reminders.toList().first { it["type"].asText() == "FEEDING" }
        assertEquals("NURSE01", feedingReminder["nurseDoneBy"].asText())
        assertNotNull(feedingReminder["nurseDoneAt"].asText())

        // 事件流完整留痕
        val eventTypes = archive["events"].map { it["eventType"].asText() }
        assertTrue(eventTypes.contains("TEMP_REPLACEMENT_OLD_LEFT"))
        assertTrue(eventTypes.contains("TEMP_REPLACEMENT_VERIFIED"))
        assertTrue(eventTypes.contains("TEMP_REPLACEMENT_ARRIVED"))
        assertTrue(eventTypes.contains("CARE_REMINDER_CREATED"))
        assertTrue(eventTypes.contains("CARE_REMINDER_NURSE_DONE"))
        assertTrue(eventTypes.contains("CARE_REMINDER_TAKEN_OVER"))
        // 无人陪护患者列表清空
        assertTrue(get("/api/wards/W3A/unattended-patients").body!!.none { it["caseNo"].asText() == caseNo })
    }

    @Test
    fun `新陪护未确认健康承诺或关系为空申请直接被拒`() {
        val (_, oldPermitNo, _) = applyAndApprove(applyRequest(patientNo = "IP-TEMP-02"))

        val noHealth = post("/api/replacements/temporary",
            tempRequestBody(oldPermitNo, "吴姨", "IDT0002", healthCommitment = false))
        assertEquals(400, noHealth.statusCode.value())
        assertTrue(noHealth.body!!["message"].asText().contains("健康承诺"))

        val noRelation = post("/api/replacements/temporary",
            tempRequestBody(oldPermitNo, "吴姨", "IDT0002", relation = ""))
        assertEquals(400, noRelation.statusCode.value())
        assertTrue(noRelation.body!!["message"].asText().contains("关系"))
    }

    @Test
    fun `临时替换取消后患者仍无人陪护重新登记亲属接替并完成交接`() {
        val (caseNo, oldPermitNo, _) = applyAndApprove(applyRequest(
            patientNo = "IP-TEMP-03", escortName = "郑华", escortIdNo = "IDT3OLD"))

        val first = post("/api/replacements/temporary", tempRequestBody(
            oldPermitNo, "郑华弟弟", "IDT003A", relation = "弟弟"))
        assertEquals(200, first.statusCode.value())
        val firstId = first.body!!["replacementId"].asLong()

        // 间隙中先建一条夜间观察提醒
        post("/api/care-reminders", mapOf(
            "caseNo" to caseNo, "type" to "NIGHT_WATCH", "note" to "夜间观察", "createdBy" to "NURSE02"))

        // 第一位亲属无法赶到, 取消; 患者维持无人陪护, 护士站继续巡查
        val cancel = post("/api/replacements/$firstId/cancel",
            mapOf("operator" to "NURSE02", "reason" to "车票改签, 今晚无法到院"))
        assertEquals(200, cancel.statusCode.value())
        assertEquals("CANCELED", cancel.body!!["status"].asText())
        var archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("UNATTENDED", archive["careStatus"].asText())
        assertTrue(get("/api/wards/W3A/unattended-patients").body!!.any { it["caseNo"].asText() == caseNo })

        // 家属重新登记另一位亲属接替(旧证已 REPLACED 但患者仍无人陪护, 沿用原间隙)
        val second = post("/api/replacements/temporary", tempRequestBody(
            oldPermitNo, "郑华姐姐", "IDT003B", relation = "姐姐", phone = "13700000077", oldLeftAt = null))
        assertEquals(200, second.statusCode.value(), second.body.toString())
        val secondId = second.body!!["replacementId"].asLong()
        assertEquals("PENDING", second.body!!["status"].asText())

        // 新亲属到位核验交接
        val arrival = post("/api/replacements/$secondId/arrival", mapOf(
            "operator" to "NURSE02", "identityVerified" to true,
            "healthCommitmentAccepted" to true, "relationVerified" to true))
        assertEquals(200, arrival.statusCode.value(), arrival.body.toString())
        archive = get("/api/cases/$caseNo/archive").body!!
        assertEquals("WITH_ESCORT", archive["careStatus"].asText())
        assertEquals("郑华姐姐", archive["currentEscortName"].asText())
        // 取消前遗留的提醒在新陪护到位后也转为家属接手
        assertTrue(archive["reminders"].any {
            it["status"].asText() == "FAMILY_TAKEN_OVER" && it["takenOverBy"].asText() == "郑华姐姐"
        })
    }
}
