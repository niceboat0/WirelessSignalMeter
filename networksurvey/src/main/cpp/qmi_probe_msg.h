/* QMI over QRTR message builders (pure, no I/O: host-testable).
 *
 * QMI datagrams on QRTR carry no QMUX wrapper:
 *   u8 type | u16 txn | u16 msg_id | u16 length | TLVs...
 * Request type 0, response type 2. TLVs are  tag u8 | length u16 | value.
 * The NAS service is 0x03. These helpers build the two no-argument GETs the
 * probe uses to confirm the channel; the transport itself lives in
 * qmi_probe.c. Written from the protocol, not from any GPL implementation.
 */
#ifndef QMI_PROBE_MSG_H
#define QMI_PROBE_MSG_H

#include <stddef.h>
#include <stdint.h>

enum {
    QMI_TYPE_REQ = 0,
    QMI_TYPE_RESP = 2,
    QMI_HEADER = 7,
    QMI_NAS_SERVICE = 0x03,
    QMI_NAS_GET_SERVING_SYSTEM = 0x0024,
    QMI_NAS_GET_RF_BAND_INFO = 0x0031,
};

static void qp_put16(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

static uint16_t qp_read16(const uint8_t *p) {
    return (uint16_t)(p[0] | (p[1] << 8));
}

/* Opens the 7-byte QMI request header (type 0, txn, message, zero length)
 * and returns the next free offset. Caller appends TLVs then qp_finish. */
static size_t qp_req_hdr(uint8_t *out, uint32_t txn, uint32_t msg_id) {
    out[0] = QMI_TYPE_REQ;
    qp_put16(out + 1, txn);
    qp_put16(out + 3, msg_id);
    qp_put16(out + 5, 0);
    return QMI_HEADER;
}

/* Back-patches the TLV length field and returns the final message size. */
static size_t qp_finish(uint8_t *out, size_t at) {
    qp_put16(out + 5, (uint32_t)(at - QMI_HEADER));
    return at;
}

static size_t qp_nas_get_serving_system(uint8_t *out, uint32_t txn) {
    return qp_finish(out, qp_req_hdr(out, txn, QMI_NAS_GET_SERVING_SYSTEM));
}

static size_t qp_nas_get_rf_band_info(uint8_t *out, uint32_t txn) {
    return qp_finish(out, qp_req_hdr(out, txn, QMI_NAS_GET_RF_BAND_INFO));
}

/* True when a datagram is the QMI response matching the given txn/message. */
static int qp_is_response(const uint8_t *p, int n, uint16_t txn, uint16_t msg) {
    return n >= QMI_HEADER && p[0] == QMI_TYPE_RESP &&
        qp_read16(p + 1) == txn && qp_read16(p + 3) == msg;
}

#endif