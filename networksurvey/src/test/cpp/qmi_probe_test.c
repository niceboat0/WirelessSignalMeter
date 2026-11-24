#include "../../main/cpp/qmi_probe_msg.h"

#define CHECK(condition) do { if (!(condition)) return __LINE__; } while (0)

/* Freestanding host test for message builders, matching, and result parsing. */
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

    uint16_t error = 0xffff;
    uint8_t response[] = {
        QMI_TYPE_RESP, 5, 0, 0x24, 0, 11, 0,
        0x10, 1, 0, 0x42, /* Another TLV may precede the result. */
        0x02, 4, 0, 0, 0, 0, 0,
    };
    CHECK(qp_read_result(response, sizeof response, &error) == QP_SUCCESS);
    CHECK(error == 0);
    response[14] = 1;
    response[16] = 0x34;
    response[17] = 0x12;
    CHECK(qp_read_result(response, sizeof response, &error) == QP_REJECTED);
    CHECK(error == 0x1234);
    CHECK(qp_read_result(response, sizeof response - 1, &error) == QP_MALFORMED);
    response[12] = 5;
    CHECK(qp_read_result(response, sizeof response, &error) == QP_MALFORMED);
    response[12] = 4;
    response[11] = 0x03;
    CHECK(qp_read_result(response, sizeof response, &error) == QP_MALFORMED);

    uint8_t duplicate[] = {
        QMI_TYPE_RESP, 5, 0, 0x24, 0, 14, 0,
        0x02, 4, 0, 0, 0, 0, 0,
        0x02, 4, 0, 1, 0, 1, 0,
    };
    CHECK(qp_read_result(duplicate, sizeof duplicate, &error) == QP_MALFORMED);
    return 0;
}
