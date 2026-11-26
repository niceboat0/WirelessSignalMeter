/*
 * Lock command templates adapted from niceboat0/diag-research, lockband v104.
 * See docs/LOCKBAND.md for the source revision and device limitations.
 */
#ifndef WIRELESS_BAND_LOCK_PROTOCOL_H
#define WIRELESS_BAND_LOCK_PROTOCOL_H

#include <stdint.h>
#include <stddef.h>

#define LB_MMODE "/nv/item_files/modem/mmode"
#define LB_LTE_BANDS LB_MMODE "/lte_bandpref"
#define LB_NR_BANDS LB_MMODE "/nr_band_pref"
/* NSA devices keep a SEPARATE NR band preference item (seen in the ZTE
 * T-Phone lock library); when it exists both items must carry the mask. */
#define LB_NR_NSA_BANDS LB_MMODE "/nr_nsa_band_pref"
#define LB_LTE_DIR "/nv/item_files/modem/lte/rrc/efs"
#define LB_LTE_FREQ LB_LTE_DIR "/cell_restrict_opt_params"
#define LB_LTE_CELL LB_LTE_DIR "/cell_lock_list"
#define LB_LTE_CSP "/nv/item_files/modem/lte/rrc/csp"
#define LB_NR_DIR "/nv/item_files/modem/nr5g/RRC"
#define LB_NR_FREQ LB_NR_DIR "/earfcn_lock"
#define LB_NR_CELL LB_NR_DIR "/pci_lock_info"

static void lb_u32(uint8_t *p, uint32_t v) {
    for (int i = 0; i < 4; i++) p[i] = (uint8_t)(v >> (8 * i));
}

static uint32_t lb_read32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
        ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

/* EFS2 lives on DIAG subsystem 0x13 on most Qualcomm modems, but some devices
 * only answer on the alternate subsystem 0x3E (cross-checked against
 * catxeon/qcom-efs-browser's efs2.c, which probes both). band_lock probes once
 * per process and every EFS2 frame below follows lb_efs_subsys, so a device
 * that only speaks 0x3E works without any other change. The wire opcode is
 * 0x004B (DIAG_SUBSYS_CMD_F) | subsys << 8 | subcommand << 16. */
static uint8_t lb_efs_subsys = 0x13;
#define LB_EFS_SUBSYS_STD 0x13
#define LB_EFS_SUBSYS_ALT 0x3E

static uint32_t lb_efs_cmd(uint32_t subcommand) {
    return 0x004bu | ((uint32_t)lb_efs_subsys << 8) | (subcommand << 16);
}

static int lb_copy_path(uint8_t *out, int at, const char *path) {
    do { out[at++] = (uint8_t)*path; } while (*path++);
    return at;
}

/* Decimal arguments are validated again in the native boundary. */
static int lb_number(const char *s, uint32_t max, uint32_t *out) {
    uint32_t value = 0;
    if (!s || !*s) return 0;
    for (; *s; s++) {
        if (*s < '0' || *s > '9') return 0;
        unsigned digit = (unsigned)(*s - '0');
        if (digit > max || value > (max - digit) / 10) return 0;
        value = value * 10 + digit;
    }
    *out = value;
    return 1;
}

static int lb_bands(const char *s, int max, uint8_t *mask, int bytes) {
    for (int i = 0; i < bytes; i++) mask[i] = 0;
    if (!s || !*s) return 0;
    while (*s) {
        char word[4];
        int count = 0;
        while (*s && *s != ',') {
            if (count == 3) return 0;
            word[count++] = *s++;
        }
        word[count] = 0;
        uint32_t band;
        if (!lb_number(word, (uint32_t)max, &band) || band == 0 || band > (unsigned)bytes * 8) return 0;
        mask[(band - 1) / 8] |= (uint8_t)(1u << ((band - 1) % 8));
        if (*s == ',' && !*++s) return 0;
    }
    return 1;
}

/* 28-byte multi-PCI NR record: one ARFCN with up to eight PCI values. */
static int lb_nr_cell_record(uint8_t *out, const char *pci_list, uint32_t arfcn,
        uint16_t scs, uint32_t band) {
    for (int i = 0; i < 28; i++) out[i] = 0;
    if (!pci_list || !*pci_list || band == 0 || band > 1024) return 0;
    int count = 0;
    while (*pci_list) {
        char word[5];
        int n = 0;
        while (*pci_list && *pci_list != ',') {
            if (n == 4) return 0;
            word[n++] = *pci_list++;
        }
        word[n] = 0;
        uint32_t pci;
        if (!lb_number(word, 1007, &pci) || count == 8) return 0;
        out[2 + count * 2] = (uint8_t)pci;
        out[3 + count * 2] = (uint8_t)(pci >> 8);
        count++;
        if (*pci_list == ',' && !*++pci_list) return 0;
    }
    if (!count) return 0;
    out[0] = (uint8_t)count;
    lb_u32(out + 20, arfcn);
    out[24] = (uint8_t)scs;
    out[25] = (uint8_t)(scs >> 8);
    out[26] = (uint8_t)(band - 1);
    out[27] = (uint8_t)((band - 1) >> 8);
    return 28;
}

static int lb_scs(uint32_t khz) {
    switch (khz) {
        case 15: return 0;
        case 30: return 1;
        case 60: return 2;
        case 120: return 3;
        default: return -1;
    }
}

enum { LB_SCS_INDEX = 0, LB_SCS_POW2 = 1, LB_SCS_ENUM1 = 2 };

static int lb_scs_enc(uint32_t khz, int encoding, uint16_t *out) {
    if (encoding == LB_SCS_INDEX) {
        int index = lb_scs(khz);
        if (index < 0) return -1;
        *out = (uint16_t)index;
        return 0;
    }
    int index;
    switch (khz) {
        case 15: index = 0; break;
        case 30: index = 1; break;
        case 60: index = 2; break;
        case 120: index = 3; break;
        case 240: index = 4; break;
        default: return -1;
    }
    if (encoding == LB_SCS_POW2) *out = (uint16_t)(1u << index);
    else if (encoding == LB_SCS_ENUM1) *out = (uint16_t)(index + 1);
    else return -1;
    return 0;
}

static int lb_mkdir(uint8_t *out, const char *path, uint8_t a, uint8_t b) {
    lb_u32(out, lb_efs_cmd(0x09));
    out[4] = a; out[5] = b;
    return lb_copy_path(out, 6, path);
}

static int lb_put(uint8_t *out, const char *path, const uint8_t *data, int size, int lte_band) {
    lb_u32(out, lb_efs_cmd(0x26));
    lb_u32(out + 4, (uint32_t)size);
    int length = 0;
    while (path[length]) length++;
    lb_u32(out + 8, lte_band ? (uint32_t)length + 1 : 0x241);
    out[12] = lte_band ? 0xff : 0x26;
    out[13] = lte_band ? 0x03 : 0x13;
    for (int i = 0; i < size; i++) out[14 + i] = data[i];
    return lb_copy_path(out, 14 + size, path);
}

static int lb_sync(uint8_t *out, const char *path) {
    lb_u32(out, lb_efs_cmd(0x30));
    out[4] = 0x1f; out[5] = 0x01;
    return lb_copy_path(out, 6, path);
}

/* EFS2 OPEN / READ / CLOSE for fixed, read-only cell-lock probes. */
static int lb_efs_open(uint8_t *out, const char *path) {
    lb_u32(out, lb_efs_cmd(0x02));
    lb_u32(out + 4, 0);
    lb_u32(out + 8, 0);
    return lb_copy_path(out, 12, path);
}

static int lb_efs_read(uint8_t *out, uint32_t fd, uint32_t bytes, uint32_t offset) {
    lb_u32(out, lb_efs_cmd(0x04));
    lb_u32(out + 4, fd);
    lb_u32(out + 8, bytes);
    lb_u32(out + 12, offset);
    return 16;
}

static int lb_efs_close(uint8_t *out, uint32_t fd) {
    lb_u32(out, lb_efs_cmd(0x03));
    lb_u32(out + 4, fd);
    return 8;
}

static int lb_efs_stat(uint8_t *out, const char *path) {
    lb_u32(out, lb_efs_cmd(0x0f));
    return lb_copy_path(out, 4, path);
}

/* EFS2 STATFS (subcommand 19) on the root directory: a read-only round trip
 * verified on-device (the reply carries the echoed 4-byte opcode). Used to
 * pick the EFS2 subsystem without touching any lock state. */
static int lb_efs_statfs(uint8_t *out, const char *path) {
    lb_u32(out, lb_efs_cmd(0x13));
    return lb_copy_path(out, 4, path);
}

/* Classic NV read request: [opcode 0x26][item u16 LE][129 B slot].
 * Used as the transport dial tone for non-Samsung Qualcomm devices (the
 * diag-research v95 on-device capture: item 10 answers a full 158 B reply). */
static int lb_nv_read(uint8_t *out, uint32_t item) {
    out[0] = 0x26;
    out[1] = (uint8_t)(item & 0xff);
    out[2] = (uint8_t)((item >> 8) & 0xff);
    for (int i = 0; i < 129; i++) out[3 + i] = 0;
    return 132;
}

/* Abstract-socket name helpers. The Samsung diag-router binds an abstract
 * name built from 108 zero bytes (verified live on SM-S9370); the AOSP
 * diagservices style is the short abstract name "diag". */
#define LB_ABSTRACT_MAX 108
static int lb_zero_abstract_name(uint8_t *sun_path) {
    for (int i = 0; i < LB_ABSTRACT_MAX; i++) sun_path[i] = 0;
    return LB_ABSTRACT_MAX;
}

static int lb_short_abstract_name(uint8_t *sun_path, const char *name) {
    sun_path[0] = 0;
    int i = 0;
    while (name[i] && i < LB_ABSTRACT_MAX - 1) { sun_path[1 + i] = (uint8_t)name[i]; i++; }
    return i + 1;
}

static int lb_unlink(uint8_t *out, const char *path) {
    lb_u32(out, lb_efs_cmd(0x08));
    return lb_copy_path(out, 4, path);
}

/* Reply matching follows the on-device captures from diag-research:
 * the field right before the echoed subsystem command is NOT a plain copy of
 * our transaction id (frames 1-2 happen to line up, later frames carry the
 * router-side sequence, e.g. 0x96 on frame 3), so matching keys on the echoed
 * command itself. PUT replies carry a 2-byte tag echo plus a u32 status,
 * MKDIR/UNLINK a bare u32, SYNC and CM replies echo free-form payloads. */
enum { LB_ACK_STATUS = 0, LB_ACK_PUT = 1, LB_ACK_LOOSE = 2 };

static int lb_find_reply(const uint8_t *b, int size, uint32_t cmd) {
    if (size < 20 || lb_read32(b) != 0x40) return -1;
    for (int at = 12; at + 4 <= size; at++)
        if (lb_read32(b + at) == cmd) return at + 4;
    return -1;
}

/* Map an ACK payload tail to 0 = accepted, 1 = rejected, -1 = not this shape.
 * expected_tag is the PUT request tag as it appears on the wire (bytes 26 13
 * read MSB-first = 0x2613; lte_bandpref uses ff 03 = 0xff03).
 * status_out receives the parsed status word when the shape is recognized. */
static int lb_ack(const uint8_t *b, int size, int at, int kind, uint32_t expected_tag,
        int allow_eexist, int allow_enoent, uint32_t *status_out) {
    int remaining = size - at;
    if (kind == LB_ACK_PUT) {
        if (remaining < 6) return -1;
        if (expected_tag != 0 && (uint32_t)((b[at] << 8) | b[at + 1]) != expected_tag) return -1;
        uint32_t status = lb_read32(b + at + 2);
        if (status_out) *status_out = status;
        return status == 0 ? 0 : 1;
    }
    if (kind == LB_ACK_STATUS) {
        if (remaining != 4) return -1;
        uint32_t status = lb_read32(b + at);
        if (status_out) *status_out = status;
        if (status == 0 || (allow_eexist && (status == 6 || status == 17)) ||
            (allow_enoent && status == 2)) return 0;
        return 1;
    }
    /* LOOSE: the echoed command is the acknowledgement (SYNC token replies and
     * CM configuration echoes do not carry a u32 errno field). */
    return 0;
}

/* CM instant band-preference frame 4b 0f 28 00, the ONLY route verified live
 * to move an LTE band-1..32 selection on the reference SM-S9370. */
static int lb_cm_band(uint8_t *out, uint32_t mask32) {
    lb_u32(out, 0x00280f4b);
    lb_u32(out + 4, 0);
    lb_u32(out + 8, 0x10);
    lb_u32(out + 12, 3);
    lb_u32(out + 16, 0x40000000);
    lb_u32(out + 20, mask32);
    lb_u32(out + 24, 0x100);
    lb_u32(out + 28, 2);
    lb_u32(out + 32, 4);
    return 36;
}

/* GET data extraction as exercised on-device since v95: find the ff03 marker
 * inside a reply frame and read `expected` bytes after it. A leading '/' after
 * the marker marks the echoed request (the path follows ff03 there), not data. */
static int lb_get_data(const uint8_t *b, int size, uint32_t tx, int expected, uint8_t *out) {
    (void)tx;
    if (size < 24 || lb_read32(b) != 0x40) return 0;
    for (int at = 12; at + 2 + expected <= size; at++) {
        if (b[at] != 0xff || b[at + 1] != 0x03) continue;
        if (b[at + 2] == '/') continue;
        for (int i = 0; i < expected; i++) out[i] = b[at + 2 + i];
        return 1;
    }
    return 0;
}

#endif
