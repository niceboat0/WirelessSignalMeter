package com.craxiom.networksurvey.ui.rootdiag

import org.json.JSONObject

/**
 * Redacted IMS SIP detail tree. Only short tokens already kept by the decoder
 * are shown. URIs, addresses and long digit runs are not copied into the tree.
 */
internal fun sipMessageTree(data: JSONObject): String = buildString {
    appendLine("SIP")
    fun line(indent: String, label: String, value: String?) {
        val safe = value?.let { safeSipToken(it) } ?: return
        appendLine("$indent$label: $safe")
    }
    line("  ", "Log", data.optString("src").ifBlank { null })
    line("  ", "Direction", data.optString("dir").ifBlank { null })
    val method = data.optString("method")
    val status = if (data.has("status")) data.optInt("status") else 0
    if (method.isNotBlank()) {
        appendLine("  Request")
        line("    ", "Method", method)
    } else if (status > 0) {
        appendLine("  Status")
        appendLine("    Code: $status")
        line("    ", "Reason", data.optString("reason").ifBlank { null })
    }
    val callId = data.optString("call_id")
    if (callId.matches(Regex("[0-9a-fA-F]{1,16}"))) appendLine("  Call-ID hash: $callId")
    val cseqMethod = data.optString("cseq").let { raw ->
        val parts = raw.split(' ').filter { it.isNotBlank() }
        when {
            parts.size >= 2 && parts[0].all { it.isDigit() } -> parts.last()
            else -> raw
        }
    }
    val cseqNum = when {
        data.has("cseq_num") -> data.optLong("cseq_num")
        else -> data.optString("cseq").substringBefore(' ').toLongOrNull()
    }
    if (cseqNum != null && cseqNum >= 0 || cseqMethod.isNotBlank()) {
        appendLine("  CSeq")
        if (cseqNum != null && cseqNum >= 0) appendLine("    Sequence: $cseqNum")
        line("    ", "Method", cseqMethod.ifBlank { null })
    }
    if (data.has("max_forwards")) appendLine("  Max-Forwards: ${data.optInt("max_forwards")}")
    line("  ", "Content-Type", data.optString("content_type").ifBlank { null })
    if (data.has("expires")) appendLine("  Expires: ${data.optInt("expires")}")
    line("  ", "Reason", data.optString("reason_hdr").ifBlank { null })
    line("  ", "Access-Network", data.optString("access").ifBlank { null })
    if (data.optInt("sdp") == 1 || data.optString("codec").isNotBlank() || data.optInt("video") == 1) {
        appendLine("  SDP")
        line("    ", "audio", data.optString("codec").ifBlank { null })
        if (data.optInt("video") == 1) appendLine("    video: present")
    }
    if (data.has("len")) appendLine("  Length: ${data.optInt("len")}")
}.trimEnd()

internal fun safeSipToken(value: String): String? {
    val text = value.trim()
    if (text.isEmpty() || text.length > 80) return null
    val lower = text.lowercase()
    if ("sip:" in lower || "sips:" in lower || "tel:" in lower || "urn:" in lower || '@' in text) return null
    if (Regex("\\d{6,}").containsMatchIn(text)) return null
    if (text.any { it < ' ' || it == '"' || it == '\\' }) return null
    return text
}
