package com.craxiom.networksurvey.ui.rootdiag

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProcessEventLedgerTest {
    private val ledger = ProcessEventLedger()
    private var time = 1_000L
    private var nextId = 1L

    @Test
    fun reconfigurationCompleteAloneIsNotHandoverSuccess() {
        val events = feed(rrc("rrcReconfigurationComplete", "UL-DCCH"))
        assertTrue(events.none { it.family == "handover" })
    }

    @Test
    fun reconfigurationWithoutMobilityIsNotAHandoverRequest() {
        feed(rrc("rrcReconfiguration", "DL-DCCH"))
        val events = feed(rrc("rrcReconfigurationComplete", "UL-DCCH"))
        assertTrue(events.none { it.family == "handover" })
    }

    @Test
    fun handoverCompleteWithoutTargetCellStaysInProgress() {
        feed(rrc("rrcReconfiguration", "DL-DCCH") { put("reconfiguration_with_sync", true); put("pci", 10) })
        val events = feed(rrc("rrcReconfigurationComplete", "UL-DCCH"))
        val handover = events.filter { it.family == "handover" }
        assertEquals(listOf("in_progress"), handover.map { it.phase })
        assertEquals("rrc_complete_is_not_handover_success", handover.single().nonClaim)
        assertEquals(2, handover.single().messageIds.size)
    }

    @Test
    fun handoverSucceedsOnlyAfterCommandCompleteAndNewCell() {
        val request = feed(rrc("rrcReconfiguration", "DL-DCCH") {
            put("reconfiguration_with_sync", true)
            put("pci", 10)
            put("target_pci", 22)
        })
        assertEquals("request", request.single { it.family == "handover" }.phase)
        val progress = feed(rrc("rrcReconfigurationComplete", "UL-DCCH"))
        assertEquals("in_progress", progress.single { it.family == "handover" }.phase)
        val sameCell = feed(cell("NR", 10, 22))
        assertTrue(sameCell.none { it.family == "handover" && it.phase == "success" })
        val success = feed(cell("NR", 10, 22, from = 10))
        assertTrue(success.none { it.phase == "success" })
        val moved = feed(cell("NR", 22, 100, from = 10))
        val done = moved.single { it.family == "handover" }
        assertEquals("success", done.phase)
        assertEquals("handover_success_is_one_procedure", done.nonClaim)
        assertTrue(done.messageIds.size >= 3)
    }

    @Test
    fun cellChangeBeforeCompleteDoesNotSucceedUntilComplete() {
        feed(rrc("rrcConnectionReconfiguration", "DL-DCCH", "LTE") { put("mobility_control_info", true); put("pci", 4) })
        assertTrue(feed(cell("LTE", 9, 300, from = 4)).none { it.phase == "success" })
        val events = feed(rrc("rrcConnectionReconfigurationComplete", "UL-DCCH", "LTE"))
        assertEquals(listOf("in_progress", "success"), events.filter { it.family == "handover" }.map { it.phase })
    }

    @Test
    fun reestablishmentWhileHandoverIsOpenIsFailure() {
        feed(rrc("rrcReconfiguration", "DL-DCCH") { put("mobility_command", true); put("pci", 1) })
        val events = feed(rrc("rrcReestablishmentRequest", "UL-CCCH") { put("reestablishment_cause", "handoverFailure") })
        val failure = events.single { it.family == "handover" }
        assertEquals("failure", failure.phase)
        assertEquals("handoverFailure", failure.evidence)
    }

    @Test
    fun reestablishmentWithoutHandoverIsNotADropOrHandover() {
        val events = feed(rrc("rrcReestablishmentRequest", "UL-CCCH"))
        assertTrue(events.none { it.family == "handover" || it.titleKey == "call_drop" })
    }

    @Test
    fun interRatMobilityNeedsTargetSetupComplete() {
        val request = feed(rrc("mobilityFromNRCommand", "DL-DCCH"))
        assertEquals("mobility_command_is_not_handover_success", request.single().nonClaim)
        assertEquals("request", request.single().phase)
        feed(rrc("rrcConnectionSetup", "DL-CCCH", "LTE"))
        val success = feed(rrc("rrcConnectionSetupComplete", "UL-DCCH", "LTE")).filter { it.family == "handover" }
        assertEquals("success", success.single().phase)
        assertEquals("target_setup_may_be_redirect", success.single().nonClaim)
    }

    @Test
    fun expiredHandoverCompleteIsNotAttached() {
        feed(rrc("rrcReconfiguration", "DL-DCCH") { put("reconfiguration_with_sync", true) })
        time += 11_000
        assertTrue(feed(rrc("rrcReconfigurationComplete", "UL-DCCH")).none { it.family == "handover" })
    }

    @Test
    fun attachAcceptIsNotSuccessUntilComplete() {
        feed(nas("LTE", "UL", "EMM 0x41"))
        val accept = feed(nas("LTE", "DL", "EMM 0x42"))
        assertEquals("in_progress", accept.single().phase)
        assertEquals("attach_accept_is_not_attach_success", accept.single().nonClaim)
        val complete = feed(nas("LTE", "UL", "EMM 0x43"))
        assertEquals("success", complete.single().phase)
        assertEquals("attach_complete", complete.single().evidence)
    }

    @Test
    fun attachCompleteWithoutAcceptIsNotSuccess() {
        feed(nas("LTE", "UL", "EMM 0x41"))
        assertTrue(feed(nas("LTE", "UL", "EMM 0x43")).isEmpty())
    }

    @Test
    fun attachRejectIsFailure() {
        feed(nas("LTE", "UL", "EMM 0x41"))
        val reject = feed(nas("LTE", "DL", "EMM 0x44") { put("cause", 15) })
        assertEquals("failure", reject.single().phase)
        assertEquals(15, reject.single().cause)
    }

    @Test
    fun tauAcceptIsSuccessAndMissingCompleteIsNotFailure() {
        feed(nas("LTE", "UL", "EMM 0x48"))
        val accept = feed(nas("LTE", "DL", "EMM 0x49"))
        assertEquals("success", accept.single().phase)
        assertEquals("tau_complete_not_required", accept.single().nonClaim)
        val confirmed = feed(nas("LTE", "UL", "EMM 0x4A"))
        assertEquals("tau_complete", confirmed.single().titleKey)
        assertEquals("success", confirmed.single().phase)
    }

    @Test
    fun tauRejectIsFailure() {
        feed(nas("LTE", "UL", "EMM 0x48"))
        assertEquals("failure", feed(nas("LTE", "DL", "EMM 0x4B")).single().phase)
    }

    @Test
    fun registrationAcceptIsNotUeComplete() {
        val request = feed(nas("NR", "UL", "5GMM 0x41") { put("registration_type", 1) })
        assertEquals(1, request.single().registrationType)
        val accept = feed(nas("NR", "DL", "5GMM 0x42"))
        assertEquals("success", accept.single().phase)
        assertEquals("registration_accept_is_not_ue_complete", accept.single().nonClaim)
        val complete = feed(nas("NR", "UL", "5GMM 0x43"))
        assertEquals("registration_complete", complete.single().titleKey)
        assertEquals(request.single().procId, complete.single().procId)
    }

    @Test
    fun registrationRejectIsFailure() {
        feed(nas("NR", "UL", "5GMM 0x41"))
        val reject = feed(nas("NR", "DL", "5GMM 0x44") { put("cause", 11) })
        assertEquals("registration_failure", reject.single().titleKey)
        assertEquals(11, reject.single().cause)
    }

    @Test
    fun nestedNasRegistrationIsLinkedToTheRrcMessage() {
        val events = feed(rrc("ulInformationTransfer", "UL-DCCH") {
            put("dedicatedNAS", JSONObject().put("nas_message_type", "5GMM 0x41").put("registration_type", 2))
        })
        val registration = events.single { it.family == "registration" }
        assertEquals("request", registration.phase)
        assertEquals(listOf(1L), registration.messageIds)
    }

    @Test
    fun duplicateNasWithinTheWindowIsOneProcedure() {
        feed(nas("NR", "UL", "5GMM 0x41"))
        assertTrue(feed(nas("NR", "UL", "5GMM 0x41")).isEmpty())
    }

    @Test
    fun prachMsg2IsNotContentionResolutionAndMissingTaIsNotFailure() {
        feed(rach("0xB889", "RACH trigger"))
        val pending = feed(rach("0xB88A", "RACH attempt"))
        assertEquals("in_progress", pending.single().phase)
        assertEquals("missing_ta_is_not_prach_failure", pending.single().nonClaim)
        val success = feed(rach("0xB88A", "RACH attempt") { put("ta", 20) })
        assertEquals("success", success.single().phase)
        assertEquals("msg2_is_not_contention_resolution", success.single().nonClaim)
        assertTrue(success.none { it.phase == "failure" })
    }

    @Test
    fun rrcSetupIsNotAccessSuccessUntilComplete() {
        feed(rrc("rrcSetupRequest", "UL-CCCH") { put("cause", "mo-Signalling") })
        val setup = feed(rrc("rrcSetup", "DL-CCCH"))
        assertEquals("in_progress", setup.single { it.family == "rrc_access" }.phase)
        assertEquals("setup_is_not_access_success", setup.single { it.family == "rrc_access" }.nonClaim)
        val done = feed(rrc("rrcSetupComplete", "UL-DCCH"))
        assertEquals("success", done.single { it.family == "rrc_access" }.phase)
    }

    @Test
    fun rrcRejectIsAccessFailure() {
        feed(rrc("rrcConnectionRequest", "UL-CCCH", "LTE"))
        val reject = feed(rrc("rrcConnectionReject", "DL-CCCH", "LTE"))
        assertEquals("failure", reject.single { it.family == "rrc_access" }.phase)
    }

    @Test
    fun sipCallReachesEstablishedThenReleaseAndAbnormalByeIsADrop() {
        val attempt = feed(sip(method = "INVITE", dir = "UL", callId = "abc"))
        assertEquals("call_attempt", attempt.single { it.family == "call" }.titleKey)
        assertEquals("MO", attempt.single { it.family == "call" }.detail)
        feed(sip(status = 100, cseq = "INVITE", dir = "DL", callId = "abc"))
        val alerting = feed(sip(status = 180, cseq = "INVITE", dir = "DL", callId = "abc"))
        assertEquals("call_alerting", alerting.single { it.family == "call" }.titleKey)
        val established = feed(sip(status = 200, cseq = "INVITE", dir = "DL", callId = "abc"))
        assertEquals("success", established.single { it.family == "call" }.phase)
        val release = feed(sip(method = "BYE", dir = "DL", callId = "abc"))
        assertEquals("release", release.single { it.family == "call" }.phase)
        feed(sip(method = "INVITE", dir = "DL", callId = "def"))
        feed(sip(status = 200, cseq = "INVITE", dir = "UL", callId = "def"))
        val drop = feed(sip(method = "BYE", dir = "UL", callId = "def") { put("reason_hdr", "RTP timeout") })
        assertEquals("call_drop", drop.single { it.family == "call" }.titleKey)
        assertEquals("bye_abnormal_is_heuristic", drop.single().nonClaim)
        assertTrue(drop.single().detail != "RTP timeout")
    }

    @Test
    fun sipRejectAndCancelFailTheCallAndReinviteDoesNotStartAnother() {
        feed(sip(method = "INVITE", dir = "UL", callId = "abc"))
        val rejected = feed(sip(status = 486, cseq = "INVITE", dir = "DL", callId = "abc"))
        assertEquals("call_rejected", rejected.single { it.family == "call" }.titleKey)
        feed(sip(method = "INVITE", dir = "UL", callId = "abc"))
        feed(sip(status = 200, cseq = "INVITE", dir = "DL", callId = "abc"))
        assertTrue(feed(sip(method = "INVITE", dir = "UL", callId = "abc")).none { it.family == "call" })
        val second = ProcessEventLedger()
        fun once(data: JSONObject) = second.accept(data, nextId++, time.also { time += 50 })
        once(sip(method = "INVITE", dir = "UL", callId = "z"))
        val cancel = once(sip(method = "CANCEL", dir = "UL", callId = "z"))
        assertEquals("call_cancelled", cancel.single { it.family == "call" }.titleKey)
    }

    @Test
    fun imsRegisterChallengeThenSuccess() {
        val request = feed(sip(method = "REGISTER", dir = "UL") { put("expires", 600000) })
        assertEquals("ims_register_request", request.single().titleKey)
        val challenge = feed(sip(status = 401, cseq = "REGISTER", dir = "DL"))
        assertEquals("in_progress", challenge.single().phase)
        val ok = feed(sip(status = 200, cseq = "2 REGISTER", dir = "DL"))
        assertEquals("ims_register_success", ok.single().titleKey)
    }

    @Test
    fun eventJsonLinksMessageIdsWithoutFreeText() {
        val event = feed(rrc("rrcSetupRequest", "UL-CCCH")).single()
        val json = event.toJson()
        assertEquals("process_event", json.getString("type"))
        assertEquals(event.messageIds.single(), json.getJSONArray("message_ids").getLong(0))
        assertTrue(!json.toString().contains("sip:"))
    }

    private fun feed(data: JSONObject): List<ProcessEvent> {
        val events = ledger.accept(data, nextId++, time)
        time += 100
        return events
    }

    private fun rrc(msg: String, channel: String, rat: String = "NR", extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("type", "rrc").put("msg", msg).put("ch", channel).put("rat", rat).apply(extra)

    private fun cell(rat: String, toPci: Int, toArfcn: Int, from: Int = toPci) =
        JSONObject().put("type", "cell_change").put("rat", rat).put("from_pci", from).put("to_pci", toPci)
            .put("from_arfcn", 100).put("to_arfcn", toArfcn)

    private fun nas(rat: String, dir: String, msg: String, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("type", "nas").put("rat", rat).put("dir", dir).put("msg", msg).apply(extra)

    private fun rach(src: String, msg: String, extra: JSONObject.() -> Unit = {}) =
        JSONObject().put("type", "nr_rach").put("src", src).put("msg", msg).put("rat", "NR").apply(extra)

    private fun sip(
        method: String = "", status: Int = 0, cseq: String = "", dir: String = "UL", callId: String = "",
        extra: JSONObject.() -> Unit = {},
    ) = JSONObject().put("type", "sip").put("dir", dir).put("src", "0x156E").apply {
        if (method.isNotBlank()) put("method", method)
        if (status > 0) put("status", status)
        if (cseq.isNotBlank()) put("cseq", cseq)
        if (callId.isNotBlank()) put("call_id", callId)
    }.apply(extra)
}
