#include "../../main/cpp/qmi_probe_msg.h"

#define CHECK(condition) do { if (!(condition)) return __LINE__; } while (0)

/* Freestanding host test: no QRTR, no modem; covers the message builders and
 * the response matcher only. */
__declspec(dllexport) int test_qmi_probe(void) {
    uint8_t buf[64];

    /* NAS_GET_SERVING_SYSTEM (0x0024): 7-byte header, empty TLV length. */
    size_t n = qp_nas_get_serving_system(buf, 5);
    CHECK(n == QMI_HEADER);
    CHECK(buf[0] == 0 && buf[1] == 5 && buf[2] == 0);
    CHECK(buf[3] == 0x24 && buf[4] == 0);
    CHECK(buf[5] == 0 && buf[6] == 0);          /* tlv_len = 0 */

    n = qp_nas_get_rf_band_info(buf, 0x0123);
    CHECK(n == QMI_HEADER);
    CHECK(buf[0] == 0 && buf[1] == 0x23 && buf[2] == 0x01);
    CHECK(buf[3] == 0x31 && buf[4] == 0);
    CHECK(buf[5] == 0 && buf[6] == 0);

/* Response matcher: type 2, correct txn and message. */
    buf[0] = QMI_TYPE_RESP; buf[1] = 5; buf[2] = 0; buf[3] = 0x24; buf[4] = 0;
    CHECK(qp_is_response(buf, 7, 5, 0x0024));
    CHECK(!qp_is_response(buf, 7, 6, 0x0024));   /* wrong txn */
    CHECK(!qp_is_response(buf, 7, 5, 0x0031));   /* wrong message */
    CHECK(!qp_is_response(buf, 6, 5, 0x0024));   /* too short */
    buf[0] = QMI_TYPE_REQ;
    CHECK(!qp_is_response(buf, 7, 5, 0x0024));   /* wrong type */
    return 0;
}