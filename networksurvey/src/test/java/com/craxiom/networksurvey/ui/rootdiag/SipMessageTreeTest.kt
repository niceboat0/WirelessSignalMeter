package com.craxiom.networksurvey.ui.rootdiag

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SipMessageTreeTest {
    @Test
    fun treeShowsMethodCseqSdpAndCallIdHashOnly() {
        val tree = sipMessageTree(JSONObject()
            .put("src", "0x156E")
            .put("dir", "UL")
            .put("method", "INVITE")
            .put("cseq", "INVITE")
            .put("cseq_num", 4)
            .put("call_id", "a1b2c3d4")
            .put("content_type", "application/sdp")
            .put("max_forwards", 70)
            .put("codec", "AMR-WB/16000")
            .put("video", 1)
            .put("sdp", 1)
            .put("access", "3GPP-NR-FDD"))
        assertTrue(tree.contains("Method: INVITE"))
        assertTrue(tree.contains("Sequence: 4"))
        assertTrue(tree.contains("Call-ID hash: a1b2c3d4"))
        assertTrue(tree.contains("Content-Type: application/sdp"))
        assertTrue(tree.contains("Max-Forwards: 70"))
        assertTrue(tree.contains("audio: AMR-WB/16000"))
        assertTrue(tree.contains("video: present"))
        assertTrue(tree.contains("Access-Network: 3GPP-NR-FDD"))
        assertFalse(tree.contains("sip:"))
    }

    @Test
    fun treeDropsUrisAndLongDigitRuns() {
        val tree = sipMessageTree(JSONObject()
            .put("type", "sip")
            .put("status", 183)
            .put("reason", "see sip:user@example")
            .put("reason_hdr", "cause=41")
            .put("call_id", "not-a-hash-and-has-sip:user")
            .put("codec", "1234567890")
            .put("access", "tel:12345"))
        assertTrue(tree.contains("Code: 183"))
        assertTrue(tree.contains("Reason: cause=41"))
        assertFalse(tree.contains("sip:"))
        assertFalse(tree.contains("tel:"))
        assertFalse(tree.contains("@"))
        assertFalse(tree.contains("1234567890"))
        assertFalse(tree.contains("Call-ID"))
    }
}
