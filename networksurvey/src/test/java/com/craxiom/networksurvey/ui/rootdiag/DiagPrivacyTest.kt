package com.craxiom.networksurvey.ui.rootdiag

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagPrivacyTest {
    @Test
    fun diagnosticRedactionRemovesPrivateSignalingTreesAndPagingIdentityRecursively() {
        val input = JSONObject(
            """
            {
              "type": "rrc",
              "decoded_tree": "masked",
              "decoded_tree_private": "IMSI 001010123456789",
              "paging_records": [
                {
                  "ue_identity_type": "imsi",
                  "ue_identity_value": "001010123456789"
                }
              ],
              "rat": "LTE"
            }
            """.trimIndent(),
        )

        val redacted = redactDiagnostic(input)

        assertEquals("LTE", redacted.getString("rat"))
        assertFalse(redacted.has("decoded_tree"))
        assertFalse(redacted.has("decoded_tree_private"))
        val paging = redacted.getJSONArray("paging_records").getJSONObject(0)
        assertEquals("imsi", paging.getString("ue_identity_type"))
        assertFalse(paging.has("ue_identity_value"))
    }
}
