package com.craxiom.networksurvey.ui.rootdiag

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagPrivacyTest {
    @Test
    fun nrMeasurementReportUsesIndexThenConvertedValue() {
        val quantities = JSONObject()
            .put("rsrp_index", 59)
            .put("rsrq_index", 64)
            .put("sinr_index", 63)

        assertEquals("-97 dBm", quantities.reportedQuantity("rsrp_index", "NR", singleReading = true))
        assertEquals("-10.5 dB", quantities.reportedQuantity("rsrq_index", "NR", singleReading = true))
        assertEquals("9 dB", quantities.reportedQuantity("sinr_index", "NR", singleReading = true))
    }

    @Test
    fun privateValuesWrapAfterSixteenDigitsInTheDetailView() {
        assertEquals("0010101234567890\n12345", wrapPrivateValue("001010123456789012345"))
        assertEquals("0x0011223344556677\n8899", wrapPrivateValue("0x00112233445566778899"))
        assertEquals("IMSI: 0010101234567890\n  12345", wrapPrivateValues("IMSI: 001010123456789012345"))
        assertEquals("From: sip:alice@exampl\n  e.invalid", wrapPrivateValues("From: sip:alice@example.invalid"))
        assertTrue(wrapPrivateValues("short 001010123456789").contains("001010123456789"))
    }

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
