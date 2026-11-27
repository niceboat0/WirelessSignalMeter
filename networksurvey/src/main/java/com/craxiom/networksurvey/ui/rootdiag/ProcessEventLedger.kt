package com.craxiom.networksurvey.ui.rootdiag

import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-event ledger over messages this app already decodes.
 *
 * A signaling message stays a signaling message. This ledger only appends a
 * procedure outcome when the sequence supports it. In particular, one
 * RRC reconfiguration complete is not handover success, a TAU/Registration
 * accept is not proof the UE sent Complete, and a PRACH timing advance is
 * Msg2, not contention resolution. Windows that expire produce no failure.
 *
 * No phone numbers, IMSIs, or SIP URIs are copied into events.
 */
internal class ProcessEventLedger {
    private var nextProc = 1
    private val handover = linkedMapOf<String, Handover>()
    private val access = linkedMapOf<String, Access>()
    private val nas = linkedMapOf<String, NasProc>()
    private var prach: Prach? = null
    private val calls = linkedMapOf<String, CallProc>()
    private var imsReg: ImsReg? = null
    private var lastNasKey = ""
    private var lastNasAt = Long.MIN_VALUE
    private var lastSipKey = ""
    private var lastSipAt = Long.MIN_VALUE

    fun reset() {
        nextProc = 1
        handover.clear()
        access.clear()
        nas.clear()
        prach = null
        calls.clear()
        imsReg = null
        lastNasKey = ""
        lastNasAt = Long.MIN_VALUE
        lastSipKey = ""
        lastSipAt = Long.MIN_VALUE
    }

    fun accept(data: JSONObject, messageId: Long, timeMs: Long): List<ProcessEvent> {
        if (data.optInt("guess") == 1) return emptyList()
        expire(timeMs)
        val out = mutableListOf<ProcessEvent>()
        when (data.optString("type")) {
            "rrc" -> {
                out += handover(data, messageId, timeMs)
                out += access(data, messageId, timeMs)
                nestedNas(data)?.let { out += nas(it, messageId, timeMs) }
            }
            "nas" -> out += nas(data, messageId, timeMs)
            "nr_rach" -> out += prach(data, messageId, timeMs)
            "cell_change" -> out += cellChange(data, messageId, timeMs)
            "sip" -> out += sip(data, messageId, timeMs)
        }
        return out
    }

    private fun expire(now: Long) {
        handover.entries.removeAll { (_, proc) -> !proc.done && now - proc.openedAt > HO_WINDOW }
        access.entries.removeAll { (_, proc) -> now - proc.openedAt > ACCESS_WINDOW }
        nas.entries.removeAll { (_, proc) -> !proc.followComplete && now - proc.openedAt > NAS_WINDOW }
        nas.entries.removeAll { (_, proc) -> proc.followComplete && now - proc.openedAt > NAS_WINDOW }
        if (prach != null && now - prach!!.openedAt > PRACH_WINDOW) prach = null
        calls.entries.removeAll { (_, proc) -> proc.ended || (!proc.established && now - proc.openedAt > CALL_SETUP_WINDOW) }
        if (imsReg != null && !imsReg!!.done && now - imsReg!!.openedAt > NAS_WINDOW) imsReg = null
    }

    private fun handover(data: JSONObject, messageId: Long, timeMs: Long): List<ProcessEvent> {
        val msg = data.optString("msg")
        val rat = data.optString("rat")
        val dir = direction(data)
        val out = mutableListOf<ProcessEvent>()
        if (dir == "DL" && msg in MOBILITY_COMMANDS) {
            val source = if (msg == "mobilityFromNRCommand") "NR" else "LTE"
            val proc = openHandover("inter:$source", source, "inter", data, messageId, timeMs)
            out.addIfPresent(
                proc.emit(
                    "handover_request", "request", "mobility_command",
                    "mobility_command_is_not_handover_success", listOf(messageId), rat.ifBlank { source },
                ),
            )
            return out
        }
        if (dir == "DL" && msg in RECONFIG && mobility(data)) {
            val proc = openHandover("intra:$rat", rat, "intra", data, messageId, timeMs)
            val evidence = when {
                data.optBoolean("reconfiguration_with_sync") -> "reconfiguration_with_sync"
                data.optBoolean("mobility_control_info") -> "mobility_control_info"
                else -> "mobility_command"
            }
            out.addIfPresent(
                proc.emit(
                    "handover_request", "request", evidence, "not_a_success_rate", listOf(messageId), rat,
                ),
            )
            return out
        }
        if (dir == "UL" && msg in RECONFIG_COMPLETE) {
            val proc = handover["intra:$rat"] ?: return out
            if (proc.done || proc.kind != "intra") return out
            proc.completeId = messageId
            val ids = listOf(proc.requestId, messageId)
            out.addIfPresent(
                proc.emit(
                    "handover_in_progress", "in_progress", "rrc_reconfiguration_complete",
                    "rrc_complete_is_not_handover_success", ids, rat,
                ),
            )
            if (proc.cellConfirmed) out.addIfPresent(proc.succeed(ids + listOfNotNull(proc.cellId), rat))
            return out
        }
        if (dir == "UL" && msg in REESTABLISH_REQUEST) {
            val cause = data.optString("reestablishment_cause")
            for (key in listOf("intra:$rat", "inter:$rat")) {
                val proc = handover[key] ?: continue
                if (proc.done) continue
                val evidence = if (cause == "handoverFailure") "handoverFailure" else "reestablishment_while_handover_open"
                val claim = if (cause == "handoverFailure") "reestablishment_handover_failure"
                else "reestablishment_while_handover_open"
                out.addIfPresent(
                    proc.emit("handover_failure", "failure", evidence, claim, listOf(proc.requestId, messageId), rat),
                )
                proc.done = true
            }
            return out
        }
        val inter = handover.values.firstOrNull { it.kind == "inter" && !it.done && it.targetRat == rat }
        if (inter != null) {
            if (dir == "DL" && msg in ACCESS_SETUP) {
                out.addIfPresent(
                    inter.emit(
                        "handover_in_progress", "in_progress", "target_rrc_setup",
                        "target_setup_may_be_redirect", listOf(inter.requestId, messageId), inter.rat,
                    ),
                )
            } else if (dir == "UL" && msg in ACCESS_COMPLETE) {
                inter.done = true
                out.addIfPresent(
                    inter.emit(
                        "handover_success", "success", "target_rrc_setup_complete",
                        "target_setup_may_be_redirect", listOf(inter.requestId, messageId), inter.rat,
                    ),
                )
            }
        }
        return out
    }

    private fun openHandover(
        key: String, rat: String, kind: String, data: JSONObject, messageId: Long, timeMs: Long,
    ): Handover {
        handover.remove(key)
        val target = data.optInt("target_pci", -1)
        val proc = Handover(
            id = nextProc++, rat = rat, kind = kind, openedAt = timeMs, requestId = messageId,
            sourcePci = data.optInt("pci", -1), targetPci = target,
            targetRat = if (kind == "inter") if (rat == "NR") "LTE" else "NR" else rat,
        )
        handover[key] = proc
        return proc
    }

    private fun cellChange(data: JSONObject, messageId: Long, timeMs: Long): List<ProcessEvent> {
        val rat = data.optString("rat")
        val proc = handover["intra:$rat"] ?: return emptyList()
        if (proc.done || proc.kind != "intra" || timeMs - proc.openedAt > HO_WINDOW) return emptyList()
        val toPci = data.optInt("to_pci", -1)
        val fromPci = data.optInt("from_pci", -1)
        if (toPci < 0) return emptyList()
        if (proc.targetPci >= 0 && toPci != proc.targetPci) return emptyList()
        val source = if (proc.sourcePci >= 0) proc.sourcePci else fromPci
        if (source >= 0 && toPci == source) return emptyList()
        proc.cellConfirmed = true
        proc.cellId = messageId
        if (proc.completeId == null) return emptyList()
        return listOfNotNull(proc.succeed(listOf(proc.requestId, proc.completeId!!, messageId), rat))
    }

    private fun Handover.succeed(ids: List<Long>, rat: String): ProcessEvent? {
        done = true
        return emit(
            "handover_success", "success", "command_complete_and_serving_cell",
            "handover_success_is_one_procedure", ids, rat,
        )
    }

    private fun access(data: JSONObject, messageId: Long, timeMs: Long): List<ProcessEvent> {
        val msg = data.optString("msg")
        val rat = data.optString("rat")
        val dir = direction(data)
        val proc = access[rat]
        if (dir == "UL" && msg in ACCESS_REQUEST) {
            val opened = Access(nextProc++, rat, timeMs, messageId, "request")
            access[rat] = opened
            return listOfNotNull(opened.event("access_request", "request", "rrc_request", "not_a_success_rate", listOf(messageId)))
        }
        if (dir == "DL" && msg in ACCESS_SETUP) {
            val opened = proc ?: Access(nextProc++, rat, timeMs, messageId, "setup").also { access[rat] = it }
            if (opened.stage == "complete" || opened.stage == "failed") return emptyList()
            opened.stage = "setup"
            opened.setupId = messageId
            val claim = if (proc == null) "result_without_request" else "setup_is_not_access_success"
            val ids = listOfNotNull(if (proc != null) opened.requestId else null, messageId)
            return listOfNotNull(opened.event("access_in_progress", "in_progress", "rrc_setup", claim, ids))
        }
        if (dir == "UL" && msg in ACCESS_COMPLETE) {
            if (proc == null) {
                val opened = Access(nextProc++, rat, timeMs, messageId, "complete")
                return listOfNotNull(
                    opened.event(
                        "access_success", "success", "rrc_setup_complete", "setup_not_in_trace", listOf(messageId),
                    ),
                )
            }
            if (proc.stage == "complete" || proc.stage == "failed") return emptyList()
            proc.stage = "complete"
            val ids = listOfNotNull(proc.requestId, proc.setupId, messageId).distinct()
            val claim = if (proc.setupId == null) "setup_not_in_trace" else "not_a_success_rate"
            access.remove(rat)
            return listOfNotNull(proc.event("access_success", "success", "rrc_setup_complete", claim, ids))
        }
        if (dir == "DL" && msg in ACCESS_REJECT) {
            val ids = listOfNotNull(proc?.requestId, messageId).distinct()
            val claim = if (proc == null) "result_without_request" else "not_a_success_rate"
            access.remove(rat)
            val opened = proc ?: Access(nextProc++, rat, timeMs, messageId, "failed")
            opened.stage = "failed"
            return listOfNotNull(opened.event("access_failure", "failure", "rrc_reject", claim, ids))
        }
        return emptyList()
    }

    private fun nestedNas(data: JSONObject): JSONObject? {
        val nas = data.optJSONObject("dedicatedNAS") ?: return null
        if (nas.optInt("nas_security_header", 0) != 0) return null
        val type = nas.optString("nas_message_type")
        if (type.isBlank()) return null
        val copy = JSONObject().put("type", "nas").put("rat", data.optString("rat")).put("dir", direction(data)).put("msg", type)
        if (nas.has("cause")) copy.put("cause", nas.opt("cause"))
        if (nas.has("registration_type")) copy.put("registration_type", nas.opt("registration_type"))
        if (nas.has("attach_type")) copy.put("attach_type", nas.opt("attach_type"))
        return copy
    }

    private fun nas(data: JSONObject, messageId: Long, timeMs: Long): List<ProcessEvent> {
        val parsed = nasType(data.optString("msg")) ?: return emptyList()
        val (pd, mt) = parsed
        val rat = data.optString("rat").ifBlank { if (pd == "5GMM") "NR" else "LTE" }
        val dir = data.optString("dir").ifBlank { expectedNasDir(pd, mt) }
        val dedupe = "$rat|$pd|0x${mt.toString(16)}|$dir"
        if (dedupe == lastNasKey && timeMs - lastNasAt in 0..NAS_DEDUPE_MS) return emptyList()
        lastNasKey = dedupe
        lastNasAt = timeMs
        val cause = if (data.has("cause")) data.optInt("cause") else null
        return when {
            pd == "EMM" && mt == 0x41 && dir == "UL" ->
                nasRequest("attach", rat, timeMs, messageId, "attach_request", "attach_request", cause, data)
            pd == "EMM" && mt == 0x42 && dir == "DL" ->
                nasStep("attach", rat, "accepted", timeMs, messageId, "attach_in_progress", "in_progress", "attach_accept", "attach_accept_is_not_attach_success", cause)
            pd == "EMM" && mt == 0x43 && dir == "UL" -> nasAttachComplete(rat, messageId)
            pd == "EMM" && mt == 0x44 && dir == "DL" -> nasFailure("attach", rat, messageId, "attach_failure", "attach_reject", cause)
            pd == "EMM" && mt == 0x48 && dir == "UL" ->
                nasRequest("tau", rat, timeMs, messageId, "tau_request", "tau_request", cause, data)
            pd == "EMM" && mt == 0x49 && dir == "DL" -> nasTauAccept(rat, timeMs, messageId, cause)
            pd == "EMM" && mt == 0x4A && dir == "UL" -> nasFollowComplete("tau", rat, messageId, "tau_complete", "tau_complete")
            pd == "EMM" && mt == 0x4B && dir == "DL" -> nasFailure("tau", rat, messageId, "tau_failure", "tau_reject", cause)
            pd == "5GMM" && mt == 0x41 && dir == "UL" ->
                nasRequest("registration", rat, timeMs, messageId, "registration_request", "registration_request", cause, data)
            pd == "5GMM" && mt == 0x42 && dir == "DL" -> nasRegistrationAccept(rat, timeMs, messageId, cause)
            pd == "5GMM" && mt == 0x43 && dir == "UL" ->
                nasFollowComplete("registration", rat, messageId, "registration_complete", "registration_complete")
            pd == "5GMM" && mt == 0x44 && dir == "DL" ->
                nasFailure("registration", rat, messageId, "registration_failure", "registration_reject", cause)
            else -> emptyList()
        }
    }

    private fun nasRequest(
        family: String, rat: String, timeMs: Long, messageId: Long, title: String, evidence: String,
        cause: Int?, data: JSONObject,
    ): List<ProcessEvent> {
        val proc = NasProc(nextProc++, family, rat, timeMs, messageId)
        if (data.has("registration_type")) proc.registrationType = data.optInt("registration_type")
        nas[nasKey(family, rat)] = proc
        return listOfNotNull(proc.event(title, "request", evidence, "not_a_success_rate", listOf(messageId), cause))
    }

    private fun nasStep(
        family: String, rat: String, stage: String, timeMs: Long, messageId: Long, title: String,
        phase: String, evidence: String, claim: String, cause: Int?,
    ): List<ProcessEvent> {
        val proc = nas[nasKey(family, rat)]
        if (proc == null || proc.done) {
            val opened = NasProc(nextProc++, family, rat, timeMs, messageId)
            opened.stage = stage
            return listOfNotNull(opened.event(title, phase, evidence, "result_without_request", listOf(messageId), cause))
        }
        proc.stage = stage
        return listOfNotNull(proc.event(title, phase, evidence, claim, listOf(proc.requestId, messageId), cause))
    }

    private fun nasAttachComplete(rat: String, messageId: Long): List<ProcessEvent> {
        val proc = nas[nasKey("attach", rat)] ?: return emptyList()
        if (proc.stage != "accepted" || proc.done) return emptyList()
        proc.done = true
        nas.remove(nasKey("attach", rat))
        return listOfNotNull(
            proc.event(
                "attach_success", "success", "attach_complete", "not_a_success_rate",
                listOf(proc.requestId, messageId), null,
            ),
        )
    }

    private fun nasTauAccept(rat: String, timeMs: Long, messageId: Long, cause: Int?): List<ProcessEvent> {
        val proc = nas[nasKey("tau", rat)]
        if (proc != null && proc.followComplete) return emptyList()
        if (proc == null || proc.done) {
            val opened = NasProc(nextProc++, "tau", rat, timeMs, messageId)
            opened.followComplete = true
            opened.done = true
            nas[nasKey("tau", rat)] = opened
            return listOfNotNull(
                opened.event("tau_success", "success", "tau_accept", "result_without_request", listOf(messageId), cause),
            )
        }
        proc.followComplete = true
        proc.done = true
        proc.openedAt = timeMs
        return listOfNotNull(
            proc.event(
                "tau_success", "success", "tau_accept", "tau_complete_not_required",
                listOf(proc.requestId, messageId), cause,
            ),
        )
    }

    private fun nasRegistrationAccept(rat: String, timeMs: Long, messageId: Long, cause: Int?): List<ProcessEvent> {
        val proc = nas[nasKey("registration", rat)]
        if (proc != null && proc.followComplete) return emptyList()
        if (proc == null || proc.done) {
            val opened = NasProc(nextProc++, "registration", rat, timeMs, messageId)
            opened.followComplete = true
            opened.done = true
            nas[nasKey("registration", rat)] = opened
            return listOfNotNull(
                opened.event(
                    "registration_accept", "success", "registration_accept", "result_without_request",
                    listOf(messageId), cause,
                ),
            )
        }
        proc.followComplete = true
        proc.done = true
        proc.openedAt = timeMs
        return listOfNotNull(
            proc.event(
                "registration_accept", "success", "registration_accept",
                "registration_accept_is_not_ue_complete", listOf(proc.requestId, messageId), cause,
            ),
        )
    }

    private fun nasFollowComplete(family: String, rat: String, messageId: Long, title: String, evidence: String): List<ProcessEvent> {
        val proc = nas[nasKey(family, rat)] ?: return emptyList()
        if (!proc.followComplete) return emptyList()
        nas.remove(nasKey(family, rat))
        return listOfNotNull(proc.event(title, "success", evidence, "not_a_success_rate", listOf(proc.requestId, messageId), null))
    }

    private fun nasFailure(family: String, rat: String, messageId: Long, title: String, evidence: String, cause: Int?): List<ProcessEvent> {
        val proc = nas[nasKey(family, rat)]
        if (proc != null && proc.done && proc.followComplete) return emptyList()
        val ids = listOfNotNull(proc?.requestId, messageId).distinct()
        val claim = if (proc == null || proc.done) "result_without_request" else "not_a_success_rate"
        nas.remove(nasKey(family, rat))
        val opened = if (proc != null && !proc.done) proc else NasProc(nextProc++, family, rat, 0, messageId)
        opened.done = true
        return listOfNotNull(opened.event(title, "failure", evidence, claim, ids, cause))
    }

    private fun prach(data: JSONObject, messageId: Long, timeMs: Long): List<ProcessEvent> {
        val src = data.optString("src")
        val trigger = src.equals("0xB889", true) || data.optString("msg") == "RACH trigger"
        val attempt = src.equals("0xB88A", true) || data.optString("msg") == "RACH attempt"
        if (trigger) {
            val proc = Prach(nextProc++, timeMs, messageId)
            prach = proc
            return listOfNotNull(proc.event("prach_request", "request", "rach_trigger", "not_a_success_rate", listOf(messageId)))
        }
        if (!attempt) return emptyList()
        val open = prach?.takeIf { timeMs - it.openedAt <= PRACH_WINDOW }
        val ids = listOfNotNull(open?.requestId, messageId).distinct()
        val proc = open ?: Prach(nextProc++, timeMs, messageId)
        prach = null
        return if (data.has("ta")) {
            listOf(proc.event("prach_success", "success", "msg2_ta", "msg2_is_not_contention_resolution", ids))
        } else {
            listOf(proc.event("prach_in_progress", "in_progress", "attempt_without_msg2", "missing_ta_is_not_prach_failure", ids))
        }
    }

    private fun sip(data: JSONObject, messageId: Long, timeMs: Long): List<ProcessEvent> {
        val method = data.optString("method")
        val status = if (data.has("status")) data.optInt("status") else 0
        val cseq = cseqMethod(data)
        val key = data.optString("call_id").ifBlank { "logcat" }
        val fingerprint = "$key|$method|$status|$cseq"
        if (fingerprint == lastSipKey && timeMs - lastSipAt in 0..NAS_DEDUPE_MS) return emptyList()
        lastSipKey = fingerprint
        lastSipAt = timeMs
        val out = mutableListOf<ProcessEvent>()
        if (method == "REGISTER" || cseq == "REGISTER") out += imsRegister(data, messageId, timeMs, method, status)
        if (method == "INVITE" || cseq == "INVITE" || method == "CANCEL" || method == "BYE" || cseq == "BYE" || cseq == "CANCEL") {
            out += call(data, messageId, timeMs, key, method, status, cseq)
        }
        return out
    }

    private fun imsRegister(data: JSONObject, messageId: Long, timeMs: Long, method: String, status: Int): List<ProcessEvent> {
        if (method == "REGISTER") {
            val dereg = data.optInt("expires", -1) == 0
            val proc = ImsReg(nextProc++, timeMs, messageId, dereg)
            imsReg = proc
            val title = if (dereg) "ims_deregister_request" else "ims_register_request"
            return listOfNotNull(proc.event(title, "request", "register", "not_a_success_rate", listOf(messageId)))
        }
        val proc = imsReg ?: return emptyList()
        if (proc.done) return emptyList()
        if (status == 401 || status == 407) {
            return listOfNotNull(
                proc.event(
                    "ims_register_challenge", "in_progress", "register_challenge", "not_a_success_rate",
                    listOf(proc.requestId, messageId),
                ),
            )
        }
        if (status in 200..299) {
            proc.done = true
            val title = if (proc.dereg) "ims_deregister_success" else "ims_register_success"
            return listOfNotNull(proc.event(title, "success", "register_2xx", "not_a_success_rate", listOf(proc.requestId, messageId)))
        }
        if (status >= 300) {
            proc.done = true
            return listOfNotNull(
                proc.event(
                    "ims_register_failure", "failure", "register_$status", "not_a_success_rate",
                    listOf(proc.requestId, messageId),
                ),
            )
        }
        return emptyList()
    }

    private fun call(
        data: JSONObject, messageId: Long, timeMs: Long, key: String, method: String, status: Int, cseq: String,
    ): List<ProcessEvent> {
        val dir = if (data.optString("dir") == "UL") "MO" else if (data.optString("dir") == "DL") "MT" else ""
        if (method == "INVITE") {
            val current = calls[key]
            if (current != null && !current.ended && (current.established || timeMs - current.openedAt < 2_000)) return emptyList()
            calls.remove(key)
            while (calls.size >= 8) calls.remove(calls.keys.first())
            val proc = CallProc(
                nextProc++, key, timeMs, messageId,
                dir.ifBlank { if (data.optString("dir") == "UL") "MO" else "MT" },
            )
            calls[key] = proc
            return listOfNotNull(proc.event("call_attempt", "request", "invite", "not_a_success_rate", listOf(messageId)))
        }
        val proc = calls[key] ?: return emptyList()
        if (proc.ended) return emptyList()
        if (status in 100..183 && cseq == "INVITE" && !proc.established) {
            val alerting = status == 180 || status == 181
            val title = if (alerting) "call_alerting" else "call_progress"
            val evidence = "sip_$status"
            return listOfNotNull(proc.event(title, "in_progress", evidence, "not_a_success_rate", listOf(proc.requestId, messageId)))
        }
        if (status in 200..299 && cseq == "INVITE" && !proc.established) {
            proc.established = true
            return listOfNotNull(
                proc.event("call_established", "success", "invite_2xx", "not_a_success_rate", listOf(proc.requestId, messageId)),
            )
        }
        if (status >= 300 && status != 401 && status != 407 && cseq == "INVITE" && !proc.established) {
            proc.ended = true
            val title = if (status == 487) "call_cancelled" else "call_rejected"
            calls.remove(key)
            return listOfNotNull(proc.event(title, "failure", "sip_$status", "not_a_success_rate", listOf(proc.requestId, messageId)))
        }
        if (method == "CANCEL" && !proc.established) {
            proc.ended = true
            calls.remove(key)
            return listOfNotNull(proc.event("call_cancelled", "failure", "cancel", "not_a_success_rate", listOf(proc.requestId, messageId)))
        }
        if ((method == "BYE" || cseq == "BYE") && method != "CANCEL") {
            if (!proc.established) return emptyList()
            proc.ended = true
            calls.remove(key)
            val reason = data.optString("reason_hdr")
            return if (abnormalBye(reason)) {
                listOfNotNull(
                    proc.event(
                        "call_drop", "failure", "bye_abnormal", "bye_abnormal_is_heuristic",
                        listOf(proc.requestId, messageId),
                    ),
                )
            } else {
                listOfNotNull(proc.event("call_release", "release", "bye", "not_a_success_rate", listOf(proc.requestId, messageId)))
            }
        }
        return emptyList()
    }

    private fun abnormalBye(reason: String): Boolean {
        val text = reason.lowercase()
        return "rtp" in text || "timeout" in text || "cause=503" in text || "cause=408" in text ||
            "cause=41" in text || "cause=38" in text || "cause=47" in text
    }

    private class Handover(
        val id: Int,
        val rat: String,
        val kind: String,
        var openedAt: Long,
        val requestId: Long,
        val sourcePci: Int,
        val targetPci: Int,
        val targetRat: String,
        var completeId: Long? = null,
        var cellConfirmed: Boolean = false,
        var cellId: Long? = null,
        var done: Boolean = false,
        val seen: MutableSet<String> = mutableSetOf(),
    ) {
        fun emit(
            title: String, phase: String, evidence: String, claim: String, ids: List<Long>, eventRat: String,
        ): ProcessEvent? {
            if (!seen.add(title)) return null
            return makeEvent(id, "handover", phase, eventRat, evidence, title, claim, ids.distinct())
        }
    }

    private class Access(
        val id: Int,
        val rat: String,
        var openedAt: Long,
        val requestId: Long,
        var stage: String,
        var setupId: Long? = null,
        val seen: MutableSet<String> = mutableSetOf(),
    ) {
        fun event(title: String, phase: String, evidence: String, claim: String, ids: List<Long>): ProcessEvent? {
            if (!seen.add(title)) return null
            return makeEvent(id, "rrc_access", phase, rat, evidence, title, claim, ids.distinct())
        }
    }

    private class NasProc(
        val id: Int,
        val family: String,
        val rat: String,
        var openedAt: Long,
        val requestId: Long,
        var stage: String = "request",
        var done: Boolean = false,
        var followComplete: Boolean = false,
        var registrationType: Int? = null,
        val seen: MutableSet<String> = mutableSetOf(),
    ) {
        fun event(title: String, phase: String, evidence: String, claim: String, ids: List<Long>, cause: Int?): ProcessEvent? {
            if (!seen.add(title)) return null
            return makeEvent(id, family, phase, rat, evidence, title, claim, ids.distinct(), cause, registrationType)
        }
    }

    private class Prach(val id: Int, val openedAt: Long, val requestId: Long) {
        fun event(title: String, phase: String, evidence: String, claim: String, ids: List<Long>): ProcessEvent =
            makeEvent(id, "prach", phase, "NR", evidence, title, claim, ids.distinct())
    }

    private class CallProc(
        val id: Int,
        val key: String,
        var openedAt: Long,
        val requestId: Long,
        val dir: String,
        var established: Boolean = false,
        var ended: Boolean = false,
        val seen: MutableSet<String> = mutableSetOf(),
    ) {
        fun event(title: String, phase: String, evidence: String, claim: String, ids: List<Long>): ProcessEvent? {
            if (!seen.add(title)) return null
            return makeEvent(id, "call", phase, "IMS", evidence, title, claim, ids.distinct(), detail = dir)
        }
    }

    private class ImsReg(
        val id: Int,
        var openedAt: Long,
        val requestId: Long,
        val dereg: Boolean,
        var done: Boolean = false,
        val seen: MutableSet<String> = mutableSetOf(),
    ) {
        fun event(title: String, phase: String, evidence: String, claim: String, ids: List<Long>): ProcessEvent? {
            if (!seen.add(title)) return null
            return makeEvent(id, "ims_register", phase, "IMS", evidence, title, claim, ids.distinct())
        }
    }

    private fun MutableList<ProcessEvent>.addIfPresent(event: ProcessEvent?) {
        if (event != null) add(event)
    }

    private companion object {
        const val HO_WINDOW = 10_000L
        const val ACCESS_WINDOW = 15_000L
        const val NAS_WINDOW = 60_000L
        const val PRACH_WINDOW = 3_000L
        const val CALL_SETUP_WINDOW = 180_000L
        const val NAS_DEDUPE_MS = 800L
        val MOBILITY_COMMANDS = setOf("mobilityFromNRCommand", "mobilityFromEUTRACommand")
        val RECONFIG = setOf("rrcReconfiguration", "rrcConnectionReconfiguration")
        val RECONFIG_COMPLETE = setOf("rrcReconfigurationComplete", "rrcConnectionReconfigurationComplete")
        val REESTABLISH_REQUEST = setOf("rrcReestablishmentRequest", "rrcConnectionReestablishmentRequest")
        val ACCESS_REQUEST = setOf("rrcSetupRequest", "rrcConnectionRequest", "rrcResumeRequest", "rrcResumeRequest1")
        val ACCESS_SETUP = setOf("rrcSetup", "rrcConnectionSetup", "rrcResume", "rrcConnectionResume")
        val ACCESS_COMPLETE = setOf(
            "rrcSetupComplete", "rrcConnectionSetupComplete", "rrcResumeComplete", "rrcConnectionResumeComplete",
        )
        val ACCESS_REJECT = setOf("rrcReject", "rrcConnectionReject")
        val TITLES = mapOf(
            "handover_request" to "切换请求",
            "handover_in_progress" to "切换进行中",
            "handover_success" to "切换成功",
            "handover_failure" to "切换失败",
            "prach_request" to "PRACH 请求",
            "prach_in_progress" to "PRACH 进行中",
            "prach_success" to "PRACH 成功（Msg2）",
            "access_request" to "RRC 接入请求",
            "access_in_progress" to "RRC 接入进行中",
            "access_success" to "RRC 接入成功",
            "access_failure" to "RRC 接入失败",
            "attach_request" to "Attach 请求",
            "attach_in_progress" to "Attach 进行中",
            "attach_success" to "Attach 成功",
            "attach_failure" to "Attach 失败",
            "tau_request" to "TAU 请求",
            "tau_success" to "TAU 成功",
            "tau_complete" to "TAU 完成",
            "tau_failure" to "TAU 失败",
            "registration_request" to "5GS 注册请求",
            "registration_accept" to "5GS 注册接受",
            "registration_complete" to "5GS 注册完成",
            "registration_failure" to "5GS 注册失败",
            "call_attempt" to "呼叫尝试",
            "call_progress" to "呼叫进展",
            "call_alerting" to "呼叫振铃",
            "call_established" to "呼叫建立",
            "call_rejected" to "呼叫被拒绝",
            "call_cancelled" to "呼叫取消",
            "call_release" to "呼叫释放",
            "call_drop" to "掉话",
            "ims_register_request" to "IMS 注册请求",
            "ims_register_challenge" to "IMS 注册鉴权",
            "ims_register_success" to "IMS 注册成功",
            "ims_register_failure" to "IMS 注册失败",
            "ims_deregister_request" to "IMS 注销请求",
            "ims_deregister_success" to "IMS 注销成功",
        )

        fun nasKey(family: String, rat: String) = "$family:$rat"

        fun mobility(data: JSONObject) =
            data.optBoolean("mobility_command") || data.optBoolean("reconfiguration_with_sync") ||
                data.optBoolean("mobility_control_info")

        fun direction(data: JSONObject): String {
            val dir = data.optString("dir")
            if (dir == "UL" || dir == "DL") return dir
            val channel = data.optString("ch")
            return when {
                channel == "RRCReconfiguration(container)" -> "DL"
                channel.startsWith("UL") || channel.contains("Complete") -> "UL"
                channel.startsWith("DL") || channel.startsWith("BCCH") || channel.startsWith("PCCH") -> "DL"
                else -> ""
            }
        }

        fun nasType(msg: String): Pair<String, Int>? {
            val match = Regex("(5GMM|EMM) 0x([0-9A-Fa-f]{2})").find(msg) ?: return null
            return match.groupValues[1] to match.groupValues[2].toInt(16)
        }

        fun expectedNasDir(pd: String, mt: Int): String = when (pd) {
            "5GMM" -> when (mt) {
                0x41, 0x43 -> "UL"
                0x42, 0x44 -> "DL"
                else -> ""
            }
            else -> when (mt) {
                0x41, 0x43, 0x48, 0x4A -> "UL"
                0x42, 0x44, 0x49, 0x4B -> "DL"
                else -> ""
            }
        }

        fun cseqMethod(data: JSONObject): String {
            val raw = data.optString("cseq")
            val parts = raw.split(' ').filter { it.isNotBlank() }
            return when {
                parts.size >= 2 && parts[0].all { it.isDigit() } -> parts.last()
                else -> raw
            }
        }

        fun makeEvent(
            id: Int, family: String, phase: String, rat: String, evidence: String, title: String,
            claim: String, ids: List<Long>, cause: Int? = null, registrationType: Int? = null, detail: String = "",
        ) = ProcessEvent(
            family = family,
            phase = phase,
            procId = id,
            rat = rat,
            evidence = evidence,
            titleKey = title,
            titleZh = TITLES.getValue(title),
            nonClaim = claim,
            messageIds = ids,
            detail = detail,
            cause = cause,
            registrationType = registrationType,
        )
    }
}

internal data class ProcessEvent(
    val family: String,
    val phase: String,
    val procId: Int,
    val rat: String,
    val evidence: String,
    val titleKey: String,
    val titleZh: String,
    val nonClaim: String,
    val messageIds: List<Long>,
    val detail: String = "",
    val cause: Int? = null,
    val registrationType: Int? = null,
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
            .put("type", "process_event")
            .put("family", family)
            .put("phase", phase)
            .put("proc_id", procId)
            .put("rat", rat)
            .put("evidence", evidence)
            .put("title_key", titleKey)
            .put("title_zh", titleZh)
            .put("display_msg", titleZh)
            .put("non_claim", nonClaim)
            .put("message_ids", JSONArray(messageIds))
        if (detail.isNotBlank()) json.put("detail", detail)
        if (cause != null) json.put("cause", cause)
        if (registrationType != null) json.put("registration_type", registrationType)
        return json
    }
}
