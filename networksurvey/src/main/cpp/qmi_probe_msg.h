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
    QMI_NAS_SET_SSP = 0x0033,          /* Set System Selection Preference */
    QMI_NAS_GET_SSP = 0x0034,          /* Get System Selection Preference */
    QMI_NAS_LTE_LOCK_SET = 0x00D8,
    QMI_NAS_LTE_LOCK_GET = 0x00D7,
    QMI_NAS_NR_LOCK_SET = 0x010E,
    QMI_NAS_NR_LOCK_GET = 0x010F,
    QMI_DMS_SERVICE = 0x02,
    QMI_DMS_GET_BANDS = 0x0045,
};

/* TLV ids are per NAS message, not global. */
enum {
    /* 0x0033/0x0034 (system selection preference). */
    QP_TLV_SSP_MODE = 0x11,            /* u16 mode_pref: 4 GSM, 8 WCDMA, 16 LTE, 64 NR */
    QP_TLV_SSP_LTE_BANDS = 0x15,       /* u64 LTE band mask */
    QP_TLV_SSP_DURATION = 0x17,        /* u8, 1 = permanent */
    QP_TLV_SSP_USAGE = 0x21,           /* u32 usage setting, 1 = voice centric */
    QP_TLV_SSP_EXT_LTE_GET = 0x23,     /* 32-byte extended LTE band mask on GET */
    QP_TLV_SSP_EXT_LTE_SET = 0x24,     /* 32-byte extended LTE band mask on SET */
    QP_TLV_SSP_NR_BANDS = 0x2B,        /* SET: 64B combined NR mask; GET: 4B NR mode */
    QP_TLV_SSP_NR_SA_GET = 0x2C,       /* 64-byte NR-SA band mask on GET */
    QP_TLV_SSP_NR_NSA_GET = 0x2D,      /* 64-byte NR-NSA band mask on GET */
    QP_TLV_SSP_NR_MODE = 0x2E,         /* u32 NR mode on SET: 0 both, 1 nsa, 2 sa */
    QP_TLV_SSP_NR_SA_SET = 0x2F,       /* 64-byte NR-SA band mask on SET */
    QP_TLV_SSP_NR_NSA_SET = 0x30,      /* 64-byte NR-NSA band mask on SET */
    /* 0x00D8 (LTE cell lock set). */
    QP_TLV_LTE_LIST = 0x01,            /* count:u8 + count*[pci:u16, earfcn:u32] */
    QP_TLV_LTE_APPLY = 0x10,           /* apply:u8 = 1 (also the 0x00D7 GET list id) */
    /* 0x010E (NR cell lock set). */
    QP_TLV_NR_TYPE = 0x01,             /* u32: 0 PCI, 1 ARFCN, 2 none, 3 multi-PCI, 4 gNB */
    QP_TLV_NR_PCI = 0x10,              /* pci:u16, scs:u32, arfcn:u32, band_mask:64B (74B) */
    QP_TLV_NR_ARFCN = 0x11,            /* count:u8(=1), arfcn:u32, scs:u32 (9B) */
};

static inline void qp_put16(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
}

static inline uint16_t qp_read16(const uint8_t *p) {
    return (uint16_t)(p[0] | (p[1] << 8));
}

/* Opens the 7-byte QMI request header (type 0, txn, message, zero length)
 * and returns the next free offset. Caller appends TLVs then qp_finish. */
static inline size_t qp_req_hdr(uint8_t *out, uint32_t txn, uint32_t msg_id) {
    out[0] = QMI_TYPE_REQ;
    qp_put16(out + 1, txn);
    qp_put16(out + 3, msg_id);
    qp_put16(out + 5, 0);
    return QMI_HEADER;
}

/* Back-patches the TLV length field and returns the final message size. */
static inline size_t qp_finish(uint8_t *out, size_t at) {
    qp_put16(out + 5, (uint32_t)(at - QMI_HEADER));
    return at;
}

static inline size_t qp_nas_get_serving_system(uint8_t *out, uint32_t txn) {
    return qp_finish(out, qp_req_hdr(out, txn, QMI_NAS_GET_SERVING_SYSTEM));
}

static inline size_t qp_nas_get_rf_band_info(uint8_t *out, uint32_t txn) {
    return qp_finish(out, qp_req_hdr(out, txn, QMI_NAS_GET_RF_BAND_INFO));
}

/* DMS hardware band capability: the modem's own supported-band report. */
static inline size_t qp_dms_get_bands(uint8_t *out, uint32_t txn) {
    return qp_finish(out, qp_req_hdr(out, txn, QMI_DMS_GET_BANDS));
}

/* DMS GET_BANDS TLV ids. 0x01 legacy (GSM/WCDMA) u64, 0x10 LTE u64,
 * 0x12 LTE band list, 0x13 NR band list (count:u16 then band:u16 each). */
enum {
    QP_DMS_TLV_LEGACY = 0x01,
    QP_DMS_TLV_LTE = 0x10,
    QP_DMS_TLV_LTE_LIST = 0x12,
    QP_DMS_TLV_NR_LIST = 0x13,
};

/* Bit band-1 of a list mask, matching the NV/EFS route's band numbering. */
static inline void qp_mask_from_band_list(const uint8_t *value, uint16_t len, uint8_t *mask, int bytes) {
    if (len < 2) return;
    int count = (int)qp_read16(value);
    for (int i = 0; i < count && 2 + 2 * i + 1 < len; i++) {
        int band = (int)qp_read16(value + 2 + 2 * i);
        if (band >= 1 && band <= bytes * 8) mask[(band - 1) / 8] |= (uint8_t)(1 << ((band - 1) % 8));
    }
}

/* Format a bit band-1 mask as comma-separated band numbers ("1,3,41") or
 * "none" when empty. Truncates with "..." if cap is tight; always NUL-ended. */
static inline size_t qp_format_band_list(const uint8_t *mask, int bytes, char *out, size_t cap) {
    if (!cap) return 0;
    size_t at = 0;
    for (int i = 0; i < bytes; i++) {
        uint8_t v = mask[i];
        if (!v) continue;
        for (int b = 0; b < 8; b++) {
            if (!(v & (1u << b))) continue;
            int band = i * 8 + b + 1;
            char digits[12];
            int nd = 0;
            for (int x = band; x > 0; x /= 10) digits[nd++] = (char)('0' + (x % 10));
            size_t need = (size_t)(at > 0 ? 1 : 0) + (size_t)nd;
            if (at + need + 1 > cap) {
                /* Overwrite the tail with "..." when the full list does not fit. */
                size_t keep = cap > 4 ? cap - 4 : 0;
                if (at < keep) keep = at;
                const char *dots = "...";
                for (size_t k = 0; k < 3 && keep + 1 < cap; k++) out[keep++] = dots[k];
                out[keep] = '\0';
                return keep;
            }
            if (at > 0) out[at++] = ',';
            while (nd > 0) out[at++] = digits[--nd];
        }
    }
    if (at == 0) {
        const char *none = "none";
        while (none[at] && at + 1 < cap) { out[at] = none[at]; at++; }
    }
    out[at] = '\0';
    return at;
}

/* ---- lock commands ---------------------------------------------------- */

static inline void qp_put32(uint8_t *p, uint32_t v) {
    p[0] = (uint8_t)v;
    p[1] = (uint8_t)(v >> 8);
    p[2] = (uint8_t)(v >> 16);
    p[3] = (uint8_t)(v >> 24);
}

static inline uint32_t qp_read32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

/* tag:u8 | length:u16 | value */
static inline size_t qp_tlv(uint8_t *out, size_t at, uint8_t tag, const uint8_t *value, uint16_t len) {
    out[at] = tag;
    qp_put16(out + at + 1, len);
    for (uint16_t i = 0; i < len; i++) out[at + 3 + i] = value[i];
    return at + 3 + len;
}

/* System selection preference: LTE band mask (8 bytes, little endian),
 * optional 64-byte NR mask written under nr_tag (0x2B combined, 0x2F SA,
 * 0x30 NSA). Always carries duration so the modem keeps the preference. */
static inline size_t qp_nas_ssp_set_nr_tag(uint8_t *out, uint32_t txn,
        const uint8_t lte_mask[8], const uint8_t *nr_mask, uint8_t nr_tag, int mode_pref) {
    size_t at = qp_req_hdr(out, txn, QMI_NAS_SET_SSP);
    uint8_t one = 1;
    at = qp_tlv(out, at, QP_TLV_SSP_DURATION, &one, 1);
    if (mode_pref >= 0) {
        uint8_t mode[2];
        qp_put16(mode, (uint32_t)mode_pref);
        at = qp_tlv(out, at, QP_TLV_SSP_MODE, mode, 2);
    }
    if (lte_mask) at = qp_tlv(out, at, QP_TLV_SSP_LTE_BANDS, lte_mask, 8);
    if (nr_mask) at = qp_tlv(out, at, nr_tag, nr_mask, 64);
    return qp_finish(out, at);
}

static inline size_t qp_nas_ssp_set(uint8_t *out, uint32_t txn,
        const uint8_t lte_mask[8], const uint8_t *nr_mask, int mode_pref) {
    return qp_nas_ssp_set_nr_tag(out, txn, lte_mask, nr_mask, QP_TLV_SSP_NR_BANDS, mode_pref);
}

/* Extended LTE band mask (0x24, 32 bytes for >B64). Never sent together
 * with 0x15 because Qualcomm modems reject requests carrying both. */
static inline size_t qp_nas_ssp_set_ext_lte(uint8_t *out, uint32_t txn,
        const uint8_t lte_ext_mask[32], const uint8_t *nr_mask, int mode_pref) {
    size_t at = qp_req_hdr(out, txn, QMI_NAS_SET_SSP);
    uint8_t one = 1;
    at = qp_tlv(out, at, QP_TLV_SSP_DURATION, &one, 1);
    if (mode_pref >= 0) {
        uint8_t mode[2];
        qp_put16(mode, (uint32_t)mode_pref);
        at = qp_tlv(out, at, QP_TLV_SSP_MODE, mode, 2);
    }
    if (lte_ext_mask) at = qp_tlv(out, at, QP_TLV_SSP_EXT_LTE_SET, lte_ext_mask, 32);
    if (nr_mask) at = qp_tlv(out, at, QP_TLV_SSP_NR_BANDS, nr_mask, 64);
    return qp_finish(out, at);
}

static inline size_t qp_nas_ssp_get(uint8_t *out, uint32_t txn) {
    return qp_finish(out, qp_req_hdr(out, txn, QMI_NAS_GET_SSP));
}

/* LTE cell lock: count entries of [pci:u16, earfcn:u32], then apply=1.
 * count 0 clears. The GET reply reports earfcn as u16 (x16 truncation). */
static inline size_t qp_nas_lte_lock_set(uint8_t *out, uint32_t txn,
        const uint16_t *pci, const uint32_t *earfcn, int count) {
    if (count < 0 || count > 64) return 0;
    size_t at = qp_req_hdr(out, txn, QMI_NAS_LTE_LOCK_SET);
    uint8_t list[1 + 64 * 6];
    list[0] = (uint8_t)count;
    for (int i = 0; i < count; i++) {
        qp_put16(list + 1 + i * 6, pci[i]);
        qp_put32(list + 3 + i * 6, earfcn[i]);
    }
    at = qp_tlv(out, at, QP_TLV_LTE_LIST, list, (uint16_t)(1 + count * 6));
    uint8_t apply = 1;
    at = qp_tlv(out, at, QP_TLV_LTE_APPLY, &apply, 1);
    return qp_finish(out, at);
}

static inline size_t qp_nas_lte_lock_get(uint8_t *out, uint32_t txn) {
    return qp_finish(out, qp_req_hdr(out, txn, QMI_NAS_LTE_LOCK_GET));
}

/* NR cell lock. type 0 = PCI (74-byte TLV), 1 = ARFCN (9 bytes),
 * 2 = clear. band_mask is 64 bytes; scs is the QMI index (0..4). */
static inline size_t qp_nas_nr_lock_set_pci(uint8_t *out, uint32_t txn,
        uint16_t pci, uint32_t scs, uint32_t arfcn, const uint8_t band_mask[64]) {
    size_t at = qp_req_hdr(out, txn, QMI_NAS_NR_LOCK_SET);
    uint8_t type[4] = {0, 0, 0, 0};
    at = qp_tlv(out, at, QP_TLV_NR_TYPE, type, 4);
    uint8_t value[74];
    qp_put16(value, pci);
    qp_put32(value + 2, scs);
    qp_put32(value + 6, arfcn);
    for (int i = 0; i < 64; i++) value[10 + i] = band_mask[i];
    at = qp_tlv(out, at, QP_TLV_NR_PCI, value, 74);
    return qp_finish(out, at);
}

static inline size_t qp_nas_nr_lock_set_arfcn(uint8_t *out, uint32_t txn, uint32_t arfcn, uint32_t scs) {
    size_t at = qp_req_hdr(out, txn, QMI_NAS_NR_LOCK_SET);
    uint8_t type[4] = {1, 0, 0, 0};
    at = qp_tlv(out, at, QP_TLV_NR_TYPE, type, 4);
    uint8_t value[9];
    value[0] = 1;
    qp_put32(value + 1, arfcn);
    qp_put32(value + 5, scs);
    at = qp_tlv(out, at, QP_TLV_NR_ARFCN, value, 9);
    return qp_finish(out, at);
}

static inline size_t qp_nas_nr_lock_clear(uint8_t *out, uint32_t txn) {
    size_t at = qp_req_hdr(out, txn, QMI_NAS_NR_LOCK_SET);
    uint8_t type[4] = {2, 0, 0, 0};
    at = qp_tlv(out, at, QP_TLV_NR_TYPE, type, 4);
    return qp_finish(out, at);
}

static inline size_t qp_nas_nr_lock_get(uint8_t *out, uint32_t txn) {
    return qp_finish(out, qp_req_hdr(out, txn, QMI_NAS_NR_LOCK_GET));
}

/* Locate one TLV in a well-formed response body. Returns 1 when found. */
static inline int qp_find_tlv(const uint8_t *p, size_t n, uint8_t tag,
        const uint8_t **value, uint16_t *length) {
    if (n < QMI_HEADER || qp_read16(p + 5) != n - QMI_HEADER) return 0;
    for (size_t at = QMI_HEADER; at < n;) {
        if (n - at < 3) return 0;
        uint8_t t = p[at];
        uint16_t len = qp_read16(p + at + 1);
        at += 3;
        if (len > n - at) return 0;
        if (t == tag) {
            if (value) *value = p + at;
            if (length) *length = len;
            return 1;
        }
        at += len;
    }
    return 0;
}

/* True when a datagram is the QMI response matching the given txn/message. */
static inline int qp_is_response(const uint8_t *p, int n, uint16_t txn, uint16_t msg) {
    return n >= QMI_HEADER && p[0] == QMI_TYPE_RESP &&
        qp_read16(p + 1) == txn && qp_read16(p + 3) == msg;
}

enum qp_result {
    QP_MALFORMED,
    QP_REJECTED,
    QP_SUCCESS,
};

/* The common result TLV carries two little-endian u16 values: result and error. */
static inline enum qp_result qp_read_result(const uint8_t *p, size_t n,
        uint16_t *error) {
    if (n < QMI_HEADER || qp_read16(p + 5) != n - QMI_HEADER)
        return QP_MALFORMED;

    int found = 0;
    uint16_t status = 0, code = 0;
    for (size_t at = QMI_HEADER; at < n;) {
        if (n - at < 3) return QP_MALFORMED;
        uint8_t tag = p[at];
        size_t length = qp_read16(p + at + 1);
        at += 3;
        if (length > n - at) return QP_MALFORMED;
        if (tag == 0x02) {
            if (found || length != 4) return QP_MALFORMED;
            found = 1;
            status = qp_read16(p + at);
            code = qp_read16(p + at + 2);
        }
        at += length;
    }
    if (!found || status > 1 || (status == 0 && code != 0))
        return QP_MALFORMED;
    *error = code;
    return status == 0 ? QP_SUCCESS : QP_REJECTED;
}

#endif
