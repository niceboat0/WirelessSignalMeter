/* Host tests for the QMI NAS lock builders (pure frame construction).
   Frames are compared byte for byte against the protocol tables in
   reports/qmi-instant-lock-and-nv-spc-2026-11-26.md. */
#include <stddef.h>
#include <stdint.h>

/* Freestanding host target: zero initialisers need memset. It must be a
   global symbol so the compiler-generated calls resolve. */
void *memset(void *p, int c, size_t n) {
    unsigned char *q = (unsigned char *)p;
    for (size_t i = 0; i < n; i++) q[i] = (unsigned char)c;
    return p;
}

#include "qmi_probe_msg.h"

int _fltused = 0;

#define CHECK(x) do { if (!(x)) return __LINE__; } while (0)

static uint8_t out[4096];

__declspec(dllexport) int test_qmi_lock(void) {
    /* LTE cell lock set: count 1, pci 322 (0x0142), earfcn 300, apply 1. */
    uint16_t pci = 322;
    uint32_t earfcn = 300;
    size_t n = qp_nas_lte_lock_set(out, 7, &pci, &earfcn, 1);
    CHECK(n == 7 + (3 + 7) + (3 + 1));
    CHECK(out[0] == QMI_TYPE_REQ);
    CHECK(qp_read16(out + 1) == 7);
    CHECK(qp_read16(out + 3) == QMI_NAS_LTE_LOCK_SET);
    CHECK(qp_read16(out + 5) == n - QMI_HEADER);
    CHECK(out[7] == QP_TLV_LTE_LIST && qp_read16(out + 8) == 7);   /* tag|len|count|pci|earfcn */
    CHECK(out[10] == 1);                                           /* count */
    CHECK(out[11] == 0x42 && out[12] == 0x01);                     /* pci LE */
    CHECK(out[13] == 0x2C && out[14] == 0x01 && out[15] == 0 && out[16] == 0); /* earfcn LE */
    CHECK(out[17] == QP_TLV_LTE_APPLY && qp_read16(out + 18) == 1 && out[20] == 1);

    /* LTE clear: count 0 still carries the list TLV and apply. */
    n = qp_nas_lte_lock_set(out, 8, NULL, NULL, 0);
    CHECK(n == 7 + (3 + 1) + (3 + 1));
    CHECK(out[7] == QP_TLV_LTE_LIST && qp_read16(out + 8) == 1 && out[10] == 0);

    /* NR PCI lock: type 0, then the 74-byte PCI value. */
    uint8_t bands[64] = {0};
    bands[0] = 0x20;                                               /* n78 bit 7 -> byte 0 */
    n = qp_nas_nr_lock_set_pci(out, 9, 100, 1, 627264, bands);
    CHECK(n == 7 + (3 + 4) + (3 + 74));
    CHECK(qp_read16(out + 3) == QMI_NAS_NR_LOCK_SET);
    CHECK(out[7] == QP_TLV_NR_TYPE && qp_read16(out + 8) == 4 && qp_read32(out + 10) == 0);
    CHECK(out[14] == QP_TLV_NR_PCI && qp_read16(out + 15) == 74);
    CHECK(qp_read16(out + 17) == 100);                             /* pci */
    CHECK(qp_read32(out + 19) == 1);                               /* scs index */
    CHECK(qp_read32(out + 23) == 627264);                          /* arfcn */
    CHECK(out[27] == 0x20);                                        /* band mask */

    /* NR ARFCN-only lock: type 1, 9-byte value. */
    n = qp_nas_nr_lock_set_arfcn(out, 10, 627264, 1);
    CHECK(n == 7 + (3 + 4) + (3 + 9));
    CHECK(qp_read32(out + 10) == 1);
    CHECK(out[14] == QP_TLV_NR_ARFCN && qp_read16(out + 15) == 9);
    CHECK(out[17] == 1);                                           /* count */
    CHECK(qp_read32(out + 18) == 627264);
    CHECK(qp_read32(out + 22) == 1);

    /* NR clear: type 2 only. */
    n = qp_nas_nr_lock_clear(out, 11);
    CHECK(n == 7 + (3 + 4));
    CHECK(qp_read32(out + 10) == 2);

    /* System selection preference: duration, mode, LTE mask, NR mask. */
    uint8_t lte[8] = {0};
    lte[0] = 0x04;                                                 /* B3 */
    n = qp_nas_ssp_set(out, 12, lte, bands, 16);
    CHECK(qp_read16(out + 3) == QMI_NAS_SET_SSP);
    size_t at = 7;
    CHECK(out[at] == QP_TLV_SSP_DURATION && qp_read16(out + at + 1) == 1 && out[at + 3] == 1);
    at += 4;
    CHECK(out[at] == QP_TLV_SSP_MODE && qp_read16(out + at + 1) == 2 && qp_read16(out + at + 3) == 16);
    at += 5;
    CHECK(out[at] == QP_TLV_SSP_LTE_BANDS && qp_read16(out + at + 1) == 8 && out[at + 3] == 0x04);
    at += 11;
    CHECK(out[at] == QP_TLV_SSP_NR_BANDS && qp_read16(out + at + 1) == 64 && out[at + 3] == 0x20);
    at += 67;
    CHECK(at == n);
    CHECK(qp_read16(out + 5) == n - QMI_HEADER);

    /* GET requests are header-only. */
    n = qp_nas_ssp_get(out, 13);
    CHECK(n == QMI_HEADER && qp_read16(out + 3) == QMI_NAS_GET_SSP);
    n = qp_nas_lte_lock_get(out, 14);
    CHECK(n == QMI_HEADER && qp_read16(out + 3) == QMI_NAS_LTE_LOCK_GET);
    n = qp_nas_nr_lock_get(out, 15);
    CHECK(n == QMI_HEADER && qp_read16(out + 3) == QMI_NAS_NR_LOCK_GET);

    /* DMS hardware capability GET is header-only. */
    n = qp_dms_get_bands(out, 17);
    CHECK(n == QMI_HEADER && qp_read16(out + 3) == QMI_DMS_GET_BANDS);

    /* Band list -> bit band-1 masks (LTE 8 bytes, NR 64 bytes). */
    uint8_t list[6];                     /* count 2, bands 3 and 78 (LE u16) */
    list[0] = 2; list[1] = 0;
    list[2] = 3; list[3] = 0;
    list[4] = 78; list[5] = 0;
    uint8_t lte_m[8] = {0}, nr_m[64] = {0};
    qp_mask_from_band_list(list, 6, lte_m, 8);
    CHECK(lte_m[0] == 0x04);                                           /* band 3 -> bit 2 */
    qp_mask_from_band_list(list, 6, nr_m, 64);
    CHECK(nr_m[9] == 0x20);                                            /* band 78 -> bit 77 = byte 9, bit 5 */
    uint8_t edge[4];                     /* band 512 lands on the last bit */
    edge[0] = 1; edge[1] = 0; edge[2] = 0x00; edge[3] = 0x02;
    uint8_t nr_edge[64] = {0};
    qp_mask_from_band_list(edge, 4, nr_edge, 64);
    CHECK(nr_edge[63] == 0x80);

    /* qp_find_tlv on a synthetic response: result TLV then an LTE mask. */
    uint8_t resp[64];
    size_t r = qp_req_hdr(resp, 16, QMI_NAS_GET_SSP);
    resp[0] = QMI_TYPE_RESP;
    uint8_t res[4] = {0, 0, 0, 0};
    r = qp_tlv(resp, r, 0x02, res, 4);
    uint8_t mask[8] = {0};
    mask[0] = 0x88;
    r = qp_tlv(resp, r, QP_TLV_SSP_LTE_BANDS, mask, 8);
    qp_put16(resp + 5, (uint32_t)(r - QMI_HEADER));
    const uint8_t *v = NULL;
    uint16_t len = 0;
    CHECK(qp_find_tlv(resp, r, QP_TLV_SSP_LTE_BANDS, &v, &len) == 1);
    CHECK(len == 8 && v[0] == 0x88);
    uint16_t error = 0;
    CHECK(qp_read_result(resp, r, &error) == QP_SUCCESS);
    CHECK(qp_find_tlv(resp, r, 0x33, &v, &len) == 0);
    return 0;
}