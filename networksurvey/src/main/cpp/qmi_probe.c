/* qmi-probe: confirm whether the QMI NAS service is reachable over QRTR on
 * this rooted device, before any lock command is written.
 *
 * Steps, printed one per line so adb/su output is easy to read:
 *   STEP socket=ok
 *   STEP lookup=ok (node 0 port N)
 *   STEP connect=ok
 *   QMI NAS_GET_SERVING_SYSTEM ... ok reply=17 bytes
 *   QMI NAS_GET_RF_BAND_INFO ... ok reply=...
 *   VERDICT ready|reachable|fail
 *
 * The transport is a plain AF_QIPCRTR datagram socket: send a QRTR
 * NEW_SERVER_LOOKUP to the control port, then exchange QMI datagrams on the
 * service socket (no QMUX, each socket is its own client). Requires root.
 *
 * Written from the QRTR/QMI protocol; no third-party code is copied.
 */
#define _GNU_SOURCE
#include "qmi_probe_msg.h"
#include <errno.h>
#include <fcntl.h>
#include <linux/qrtr.h>
#include <poll.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#define LOOKUP_TIMEOUT_MS 1500
#define EXCHANGE_TIMEOUT_MS 3000
/* An extra byte lets length validation reject oversized truncated datagrams. */
#define QMI_MAX (QMI_HEADER + UINT16_MAX + 1)

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ((int64_t)ts.tv_sec * 1000) + (ts.tv_nsec / 1000000);
}

/* 1 readable, 0 deadline passed, -errno on poll failure. */
static int wait_readable(int fd, int64_t deadline) {
    for (;;) {
        int64_t left = deadline - now_ms();
        if (left <= 0) return 0;
        struct pollfd p = {.fd = fd, .events = POLLIN};
        int ready = poll(&p, 1, left > INT32_MAX ? INT32_MAX : (int)left);
        if (ready > 0) return 1;
        if (ready < 0 && errno != EINTR) return -errno;
    }
}

/* Ask the QRTR name service for a server endpoint. instance 0 is a kernel
 * wildcard. Returns 0 and fills server on success. */
static int qrtr_lookup(uint32_t service, uint32_t instance, struct sockaddr_qrtr *server) {
    int fd = socket(AF_QIPCRTR, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return errno;

    struct sockaddr_qrtr ctrl = {.sq_family = AF_QIPCRTR, .sq_node = 1, .sq_port = QRTR_PORT_CTRL};
    struct sockaddr_qrtr self;
    socklen_t self_len = sizeof self;
    if (getsockname(fd, (struct sockaddr *)&self, &self_len) == 0 && self.sq_node) ctrl.sq_node = self.sq_node;

    struct qrtr_ctrl_pkt req;
    memset(&req, 0, sizeof req);
    req.cmd = QRTR_TYPE_NEW_LOOKUP;
    req.server.service = service;
    req.server.instance = instance;
    if (sendto(fd, &req, sizeof req, 0, (struct sockaddr *)&ctrl, sizeof ctrl) < 0) {
        int e = errno;
        close(fd);
        return e;
    }

    int rc = 0, found = 0;
    int64_t deadline = now_ms() + LOOKUP_TIMEOUT_MS;
    for (;;) {
        int ready = wait_readable(fd, deadline);
        if (ready <= 0) break;
        struct qrtr_ctrl_pkt pkt;
        ssize_t n = recv(fd, &pkt, sizeof pkt, 0);
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            rc = errno;
            break;
        }
        if ((size_t)n < sizeof pkt || pkt.cmd != QRTR_TYPE_NEW_SERVER) continue;
        /* All-zero NEW_SERVER marks the end of a lookup result. */
        if (!pkt.server.service && !pkt.server.instance && !pkt.server.node && !pkt.server.port) break;
        if (pkt.server.service == service && pkt.server.port) {
            *server = (struct sockaddr_qrtr){.sq_family = AF_QIPCRTR, .sq_node = pkt.server.node, .sq_port = pkt.server.port};
            found = 1;
            break;
        }
    }
    close(fd);
    if (found) return 0;
    return rc ? rc : ENXIO;
}

/* Send the request held in buf and read until its response arrives. */
static int qmi_exchange(int fd, uint8_t *buf, int len, int *reply_len) {
    if (len < QMI_HEADER || len >= QMI_MAX) return EINVAL;
    uint16_t txn = qp_read16(buf + 1);
    uint16_t msg = qp_read16(buf + 3);
    if (send(fd, buf, (size_t)len, 0) != (ssize_t)len) return errno ? errno : EIO;

    int64_t deadline = now_ms() + EXCHANGE_TIMEOUT_MS;
    for (;;) {
        int ready = wait_readable(fd, deadline);
        if (ready == 0) return ETIMEDOUT;
        if (ready < 0) return -ready;
        ssize_t n = recv(fd, buf, QMI_MAX, 0);
        if (n < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            return errno;
        }
        /* Indications and late replies to earlier requests share the socket:
         * only a response to this exact (txn, msg) counts. */
        if (qp_is_response(buf, (int)n, txn, msg)) {
            *reply_len = (int)n;
            return 0;
        }
    }
}

/* ---- lock commands (bands / cell lock / mode) ------------------------- */

static int is_lock_command(const char *cmd) {
    static const char *const names[] = {
        "bands-set", "bands-get", "bands-hw", "bands-restore",
        "celllte-set", "celllte-get", "celllte-clear",
        "cellnr-set", "cellnr-get", "cellnr-clear", "mode",
    };
    for (size_t i = 0; i < sizeof names / sizeof names[0]; i++)
        if (!strcmp(cmd, names[i])) return 1;
    return 0;
}

/* "<hex>" -> nbytes little-endian; returns 0 on a bad digit or overflow. */
static int parse_hex_le(const char *text, uint8_t *out, int nbytes) {
    if (text[0] == '0' && (text[1] == 'x' || text[1] == 'X')) text += 2;
    size_t len = strlen(text);
    if (!len || len > (size_t)nbytes * 2) return 0;
    for (int i = 0; i < nbytes; i++) out[i] = 0;
    for (size_t i = 0; i < len; i++) {
        char ch = text[len - 1 - i];
        int v = ch >= '0' && ch <= '9' ? ch - '0'
            : ch >= 'a' && ch <= 'f' ? ch - 'a' + 10
            : ch >= 'A' && ch <= 'F' ? ch - 'A' + 10 : -1;
        if (v < 0) return 0;
        out[i / 2] |= (uint8_t)(v << ((i & 1) * 4));
    }
    return 1;
}

/* QMI scs is an index: 0=15k, 1=30k, 2=60k, 3=120k, 4=240k. */
static int parse_scs(const char *text) {
    long v = strtol(text, NULL, 10);
    if (v >= 0 && v <= 4 && text[1] == 0) return (int)v;
    switch (v) { case 15: return 0; case 30: return 1; case 60: return 2; case 120: return 3; case 240: return 4; }
    return -1;
}

static uint64_t mask64(const uint8_t m[8]) {
    uint64_t v = 0;
    for (int i = 7; i >= 0; i--) v = (v << 8) | m[i];
    return v;
}

/* One NAS exchange: returns 0 and fills reply_len, or an errno value. */
static int nas_exchange(int fd, uint8_t *buf, int len, int *reply_len) {
    if (len <= 0) return EINVAL;
    return qmi_exchange(fd, buf, len, reply_len);
}

static const char *verdict_of(enum qp_result r, uint16_t error) {
    (void)error;
    return r == QP_SUCCESS ? "ok" : r == QP_REJECTED ? "rejected" : "malformed";
}

/* mode_pref bitmask: 4 GSM, 8 WCDMA, 16 LTE, 64 NR; 0xFF = everything. */
static int mode_pref_text(const char *text) {
    if (!strcmp(text, "auto") || !strcmp(text, "all")) return 0xFF;
    if (!strcmp(text, "lte")) return 16;
    if (!strcmp(text, "nr") || !strcmp(text, "nr5g")) return 64;
    if (!strcmp(text, "gsm")) return 4;
    if (!strcmp(text, "wcdma") || !strcmp(text, "umts")) return 8;
    return -1;
}

/* Open one QRTR service endpoint. Returns 0 or a small error code. */
static int open_service(uint32_t service, int *fd, struct sockaddr_qrtr *server) {
    int s = socket(AF_QIPCRTR, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (s < 0) return -1;
    int e = qrtr_lookup(service, 1, server);
    if (e) e = qrtr_lookup(service, 0, server);
    if (e) { close(s); return -2; }
    if (connect(s, (struct sockaddr *)server, sizeof *server) < 0) { close(s); return -3; }
    *fd = s;
    return 0;
}

/* Print decoded system selection preference TLVs (shared by bands-get and
 * post-SET readback so logs always show readable band lists and NR domains). */
static void print_ssp_state(const char *tag, const uint8_t *buf, size_t len) {
    const uint8_t *v = NULL;
    uint16_t tlen = 0;
    char bands[1024];
    if (qp_find_tlv(buf, len, QP_TLV_SSP_MODE, &v, &tlen) && tlen >= 2)
        printf("QMI %s mode_pref=0x%04x\n", tag, (unsigned)qp_read16(v));
    if (qp_find_tlv(buf, len, QP_TLV_SSP_LTE_BANDS, &v, &tlen) && tlen == 8) {
        qp_format_band_list(v, 8, bands, sizeof bands);
        printf("QMI %s lte=0x%016llx\n", tag, (unsigned long long)mask64(v));
        printf("QMI %s lte_bands=%s\n", tag, bands);
    }
    if (qp_find_tlv(buf, len, QP_TLV_SSP_EXT_LTE_GET, &v, &tlen) && tlen == 32) {
        qp_format_band_list(v, 32, bands, sizeof bands);
        printf("QMI %s lte_ext_bands=%s\n", tag, bands);
    }
    if (qp_find_tlv(buf, len, QP_TLV_SSP_NR_BANDS, &v, &tlen)) {
        if (tlen == 4) {
            const char *nm = v[0] == 0 ? "both" : v[0] == 1 ? "nsa" : v[0] == 2 ? "sa" : "other";
            printf("QMI %s nr_mode=%s(%u)\n", tag, nm, (unsigned)v[0]);
        } else if (tlen == 64) {
            qp_format_band_list(v, 64, bands, sizeof bands);
            printf("QMI %s nr=", tag);
            for (int i = 63; i >= 0; i--) printf("%02x", v[i]);
            printf("\n");
            printf("QMI %s nr_bands=%s\n", tag, bands);
        }
    }
    if (qp_find_tlv(buf, len, QP_TLV_SSP_NR_SA_GET, &v, &tlen) && tlen == 64) {
        qp_format_band_list(v, 64, bands, sizeof bands);
        printf("QMI %s nr_sa_bands=%s\n", tag, bands);
    }
    if (qp_find_tlv(buf, len, QP_TLV_SSP_NR_NSA_GET, &v, &tlen) && tlen == 64) {
        qp_format_band_list(v, 64, bands, sizeof bands);
        printf("QMI %s nr_nsa_bands=%s\n", tag, bands);
    }
}

/* Best-effort independent NR-SA (0x2F) / NR-NSA (0x30) SSP writes. Some
 * modems read only 0x2B; others evaluate 0x2F/0x30 and reject or accept them
 * separately from 0x2B. Returns the number of domains that succeeded. */
static int apply_nr_domains(int sd, uint32_t txn_base, const char *cmd, const uint8_t nr_mask[64],
        enum qp_result *last_fail_result, uint16_t *last_fail_error) {
    static const struct { const char *name; uint8_t tag; } domains[] = {
        { "nr_sa", QP_TLV_SSP_NR_SA_SET },
        { "nr_nsa", QP_TLV_SSP_NR_NSA_SET },
    };
    static uint8_t msg[QMI_MAX];
    int ok = 0;
    for (int i = 0; i < 2; i++) {
        int qn = (int)qp_nas_ssp_set_nr_tag(msg, txn_base + (uint32_t)i, NULL, nr_mask, domains[i].tag, -1);
        int qreply = 0;
        int te = nas_exchange(sd, msg, qn, &qreply);
        if (te) {
            printf("QMI %s %s=transport_error:%s\n", cmd, domains[i].name, strerror(te));
            continue;
        }
        uint16_t err = 0;
        enum qp_result r = qp_read_result(msg, (size_t)qreply, &err);
        if (r == QP_SUCCESS) {
            printf("QMI %s %s=ok\n", cmd, domains[i].name);
            ok++;
        } else {
            if (r == QP_REJECTED) printf("QMI %s %s=rejected(qmi_error=%u)\n", cmd, domains[i].name, (unsigned)err);
            else printf("QMI %s %s=malformed\n", cmd, domains[i].name);
            if (last_fail_result) *last_fail_result = r;
            if (last_fail_error) *last_fail_error = err;
        }
    }
    return ok;
}

static int apply_nr_combined(int sd, uint32_t txn, const char *cmd, const uint8_t nr_mask[64],
        enum qp_result *out_result, uint16_t *out_error) {
    static uint8_t msg[QMI_MAX];
    int qn = (int)qp_nas_ssp_set(msg, txn, NULL, nr_mask, -1);
    int qreply = 0;
    int te = nas_exchange(sd, msg, qn, &qreply);
    if (te) {
        printf("QMI %s nr_combined=transport_error:%s\n", cmd, strerror(te));
        if (out_result) *out_result = QP_MALFORMED;
        if (out_error) *out_error = 0;
        return 0;
    }
    uint16_t err = 0;
    enum qp_result r = qp_read_result(msg, (size_t)qreply, &err);
    if (r == QP_SUCCESS) {
        printf("QMI %s nr_combined=ok\n", cmd);
        return 1;
    }
    if (r == QP_REJECTED) printf("QMI %s nr_combined=rejected(qmi_error=%u)\n", cmd, (unsigned)err);
    else printf("QMI %s nr_combined=malformed\n", cmd);
    if (out_result) *out_result = r;
    if (out_error) *out_error = err;
    return 0;
}

/* Read back 0x0034 immediately after a band write so logs verify what the
 * modem actually stored instead of guessing from a bare VERDICT ok. */
static void readback_ssp(int sd, uint32_t txn) {
    static uint8_t msg[QMI_MAX];
    int qn = (int)qp_nas_ssp_get(msg, txn);
    int qreply = 0;
    int te = nas_exchange(sd, msg, qn, &qreply);
    if (te) {
        printf("QMI readback fail=transport:%s\n", strerror(te));
        return;
    }
    uint16_t err = 0;
    enum qp_result r = qp_read_result(msg, (size_t)qreply, &err);
    if (r != QP_SUCCESS) {
        if (r == QP_REJECTED) printf("QMI readback fail=rejected qmi_error=%u\n", (unsigned)err);
        else printf("QMI readback fail=malformed\n");
        return;
    }
    print_ssp_state("readback", msg, (size_t)qreply);
}

static int run_lock_command(int argc, char **argv) {
    const char *cmd = argv[1];
    const int need_dms = !strcmp(cmd, "bands-hw") || !strcmp(cmd, "bands-restore");
    const int need_nas = strcmp(cmd, "bands-hw") != 0;
    int sd = -1, dms = -1;
    struct sockaddr_qrtr server, dms_server;
    if (need_dms && open_service(QMI_DMS_SERVICE, &dms, &dms_server)) {
        printf("QMI %s fail=dms_lookup\n", cmd);
        return 3;
    }
    if (need_nas && open_service(QMI_NAS_SERVICE, &sd, &server)) {
        printf("QMI %s fail=lookup\n", cmd);
        if (dms >= 0) close(dms);
        return 3;
    }

    uint8_t buf[QMI_MAX];
    int n = 0, reply = 0;
    uint8_t lte_mask[8] = {0}, lte_ext_mask[32] = {0}, nr_mask[64] = {0};
    int use_lte = 0, use_lte_ext = 0, use_nr = 0;

    /* Hardware capability (DMS): the modem's own supported bands. Needed for
       bands-hw and for bands-restore, whose masks must stay inside it: an
       all-ones restore is rejected by the modem (qmi_error=3 INTERNAL). */
    if (need_dms) {
        /* nas_exchange receives into the same buffer it sends from, so it
           must be QMI_MAX bytes (using a 16B req buffer overflows the stack). */
        static uint8_t dms_buf[QMI_MAX];
        int dms_len = 0;
        int qn = (int)qp_dms_get_bands(dms_buf, 1);
        int transport = nas_exchange(dms, dms_buf, qn, &dms_len);
        if (transport) {
            printf("QMI %s fail=hw_capability transport=%s\n", cmd, strerror(transport));
            printf("VERDICT fail\n");
            if (sd >= 0) close(sd);
            close(dms);
            return 9;
        }
        uint16_t qerr = 0;
        enum qp_result dms_res = qp_read_result(dms_buf, (size_t)dms_len, &qerr);
        if (dms_res != QP_SUCCESS) {
            if (dms_res == QP_REJECTED)
                printf("QMI %s fail=hw_capability qmi_error=%u\n", cmd, (unsigned)qerr);
            else
                printf("QMI %s fail=hw_capability malformed reply=%d\n", cmd, dms_len);
            printf("VERDICT %s\n", dms_res == QP_REJECTED ? "rejected" : "fail");
            if (sd >= 0) close(sd);
            close(dms);
            return 9;
        }
        const uint8_t *v = NULL;
        uint16_t len = 0;
        if (qp_find_tlv(dms_buf, (size_t)dms_len, QP_DMS_TLV_LTE, &v, &len) && len >= 8) {
            for (int i = 0; i < 8; i++) {
                lte_mask[i] = v[i];
                lte_ext_mask[i] = v[i];
            }
        }
        if (qp_find_tlv(dms_buf, (size_t)dms_len, QP_DMS_TLV_LTE_LIST, &v, &len)) {
            qp_mask_from_band_list(v, len, lte_ext_mask, 32);
            for (int i = 0; i < 8; i++) lte_mask[i] |= lte_ext_mask[i];
        }
        for (int i = 8; i < 32; i++) if (lte_ext_mask[i]) { use_lte_ext = 1; break; }
        if (qp_find_tlv(dms_buf, (size_t)dms_len, QP_DMS_TLV_NR_LIST, &v, &len)) {
            qp_mask_from_band_list(v, len, nr_mask, 64);
            for (int i = 0; i < 64; i++) if (nr_mask[i]) use_nr = 1;
        }
        char lte_csv[256], nr_csv[1024];
        qp_format_band_list(use_lte_ext ? lte_ext_mask : lte_mask, use_lte_ext ? 32 : 8, lte_csv, sizeof lte_csv);
        qp_format_band_list(nr_mask, 64, nr_csv, sizeof nr_csv);
        if (!strcmp(cmd, "bands-hw")) {
            printf("QMI hw lte=0x%016llx\n", (unsigned long long)mask64(lte_mask));
            printf("QMI hw lte_bands=%s\n", lte_csv);
            printf("QMI hw nr=");
            for (int i = 63; i >= 0; i--) printf("%02x", nr_mask[i]);
            printf("\n");
            printf("QMI hw nr_bands=%s\n", nr_csv);
            printf("VERDICT ok\n");
            close(dms);
            return 0;
        }
        /* bands-restore continues to the NAS write below. */
        printf("QMI bands-restore hw_lte=0x%016llx lte_bands=%s nr_bands=%s\n",
            (unsigned long long)mask64(lte_mask), lte_csv, nr_csv);
    }

    if (!strcmp(cmd, "bands-restore")) {
        if (!mask64(lte_mask) && !use_lte_ext) {
            printf("QMI bands-restore fail=hw_capability empty_lte\n");
            printf("VERDICT fail\n");
            if (sd >= 0) close(sd);
            close(dms);
            return 9;
        }
        use_lte = 1;
        /* Restore LTE first (no NR TLV here), then NR combined/SA/NSA separately
         * so a firmware rejection on one NR domain never blocks LTE or the other
         * NR domains. Never send 0x15 and 0x24 together. */
        n = use_lte_ext
            ? (int)qp_nas_ssp_set_ext_lte(buf, 2, lte_ext_mask, NULL, -1)
            : (int)qp_nas_ssp_set(buf, 2, lte_mask, NULL, -1);
    } else if (!strcmp(cmd, "bands-set")) {
        if (argc < 3) {
            printf("QMI bands-set fail=usage\n"); close(sd); return 5;
        }
        if (strcmp(argv[2], "-") != 0 && strcmp(argv[2], "keep") != 0) {
            if (!parse_hex_le(argv[2], lte_mask, 8)) {
                printf("QMI bands-set fail=usage\n"); close(sd); return 5;
            }
            use_lte = 1;
        }
        int argi = 3, mode = -1;
        if (argi < argc && strcmp(argv[argi], "--mode")) {
            if (!parse_hex_le(argv[argi], nr_mask, 64)) {
                printf("QMI bands-set fail=usage\n"); close(sd); return 5;
            }
            use_nr = 1;
            argi++;
        }
        if (argi < argc) {
            if (!strcmp(argv[argi], "--mode") && argi + 1 < argc) {
                mode = mode_pref_text(argv[argi + 1]);
                if (mode < 0) { printf("QMI bands-set fail=usage\n"); close(sd); return 5; }
            } else {
                printf("QMI bands-set fail=usage\n"); close(sd); return 5;
            }
        }
        if (!use_lte && !use_nr && mode < 0) {
            printf("QMI bands-set fail=usage\n"); close(sd); return 5;
        }
        char lte_csv[256], nr_csv[1024];
        if (use_lte) qp_format_band_list(lte_mask, 8, lte_csv, sizeof lte_csv);
        else snprintf(lte_csv, sizeof lte_csv, "keep");
        if (use_nr) qp_format_band_list(nr_mask, 64, nr_csv, sizeof nr_csv);
        else snprintf(nr_csv, sizeof nr_csv, "keep");
        printf("QMI bands-set req lte_bands=%s nr_bands=%s mode=%d\n", lte_csv, nr_csv, mode);
        n = (int)qp_nas_ssp_set(buf, 1, use_lte ? lte_mask : NULL, use_nr ? nr_mask : NULL, mode);
    } else if (!strcmp(cmd, "mode")) {
        if (argc < 3) { printf("QMI mode fail=usage\n"); close(sd); return 5; }
        int mode = mode_pref_text(argv[2]);
        if (mode < 0) { printf("QMI mode fail=usage\n"); close(sd); return 5; }
        n = (int)qp_nas_ssp_set(buf, 1, NULL, NULL, mode);
    } else if (!strcmp(cmd, "bands-get")) {
        n = (int)qp_nas_ssp_get(buf, 1);
    } else if (!strcmp(cmd, "celllte-set")) {
        uint16_t pci[64];
        uint32_t earfcn[64];
        int count = 0;
        if (argc < 3) { printf("QMI celllte-set fail=usage\n"); close(sd); return 5; }
        char *list = argv[2];
        for (char *tok = strtok(list, ","); tok; tok = strtok(NULL, ",")) {
            if (count >= 64) break;
            char *colon = strchr(tok, ':');
            if (!colon) { printf("QMI celllte-set fail=usage\n"); close(sd); return 5; }
            *colon = 0;
            pci[count] = (uint16_t)strtoul(tok, NULL, 0);
            earfcn[count] = (uint32_t)strtoul(colon + 1, NULL, 0);
            count++;
        }
        n = (int)qp_nas_lte_lock_set(buf, 1, pci, earfcn, count);
        if (!n) { printf("QMI celllte-set fail=usage\n"); close(sd); return 5; }
    } else if (!strcmp(cmd, "celllte-clear")) {
        n = (int)qp_nas_lte_lock_set(buf, 1, NULL, NULL, 0);
    } else if (!strcmp(cmd, "celllte-get")) {
        n = (int)qp_nas_lte_lock_get(buf, 1);
    } else if (!strcmp(cmd, "cellnr-set")) {
        int scs;
        if (argc >= 4 && !strcmp(argv[2], "arfcn")) {
            if (argc < 5 || (scs = parse_scs(argv[4])) < 0) {
                printf("QMI cellnr-set fail=usage\n"); close(sd); return 5;
            }
            n = (int)qp_nas_nr_lock_set_arfcn(buf, 1, (uint32_t)strtoul(argv[3], NULL, 0), (uint32_t)scs);
        } else {
            if (argc < 5 || (scs = parse_scs(argv[4])) < 0) {
                printf("QMI cellnr-set fail=usage\n"); close(sd); return 5;
            }
            memset(nr_mask, 0, sizeof nr_mask);
            if (argc >= 6 && !parse_hex_le(argv[5], nr_mask, 64)) {
                printf("QMI cellnr-set fail=usage\n"); close(sd); return 5;
            }
            n = (int)qp_nas_nr_lock_set_pci(buf, 1, (uint16_t)strtoul(argv[2], NULL, 0),
                (uint32_t)scs, (uint32_t)strtoul(argv[3], NULL, 0), nr_mask);
        }
    } else if (!strcmp(cmd, "cellnr-clear")) {
        n = (int)qp_nas_nr_lock_clear(buf, 1);
    } else {
        n = (int)qp_nas_nr_lock_get(buf, 1);
    }

    int transport_error = nas_exchange(sd, buf, n, &reply);
    if (transport_error) {
        printf("QMI %s transport_error=%s\n", cmd, strerror(transport_error));
        close(sd);
        if (dms >= 0) close(dms);
        return 6;
    }
    uint16_t error = 0;
    enum qp_result result = qp_read_result(buf, (size_t)reply, &error);
    int is_query = strstr(cmd, "-get") != NULL;
    if (is_query && result == QP_SUCCESS) {
        const uint8_t *v = NULL;
        uint16_t len = 0;
        if (!strcmp(cmd, "bands-get")) {
            print_ssp_state("bands", buf, (size_t)reply);
        } else if (!strcmp(cmd, "celllte-get")) {
            if (qp_find_tlv(buf, (size_t)reply, QP_TLV_LTE_APPLY, &v, &len) && len >= 1) {
                int count = v[0];
                printf("QMI celllte count=%d", count);
                for (int i = 0; i < count && (size_t)(1 + i * 4) + 4 <= len; i++) {
                    uint16_t pci = qp_read16(v + 1 + i * 4);
                    uint16_t earfcn = qp_read16(v + 3 + i * 4);
                    printf(" %u:%u", (unsigned)pci, (unsigned)earfcn);
                }
                printf("\n");
            }
        } else {
            if (qp_find_tlv(buf, (size_t)reply, QP_TLV_NR_TYPE, &v, &len) && len == 4)
                printf("QMI cellnr type=%u\n", (unsigned)qp_read32(v));
            if (qp_find_tlv(buf, (size_t)reply, QP_TLV_NR_PCI, &v, &len) && len >= 10)
                printf("QMI cellnr pci=%u arfcn=%u scs_index=%u\n",
                    (unsigned)qp_read16(v), (unsigned)qp_read32(v + 6), (unsigned)qp_read32(v + 2));
        }
    }
    if (result == QP_SUCCESS && !strcmp(cmd, "bands-set")) {
        if (use_nr) apply_nr_domains(sd, 10, cmd, nr_mask, NULL, NULL);
        readback_ssp(sd, 20);
    } else if (result == QP_SUCCESS && !strcmp(cmd, "bands-restore")) {
        printf("QMI bands-restore lte=ok\n");
        if (use_nr) {
            enum qp_result nr_res = QP_MALFORMED;
            uint16_t nr_err = 0;
            int nr_ok = apply_nr_combined(sd, 3, cmd, nr_mask, &nr_res, &nr_err);
            nr_ok += apply_nr_domains(sd, 10, cmd, nr_mask, &nr_res, &nr_err);
            if (nr_ok == 0) {
                result = nr_res;
                error = nr_err;
            }
        }
        readback_ssp(sd, 20);
    }
    printf("VERDICT %s%s\n", verdict_of(result, error),
        result == QP_REJECTED ? "" : "");
    if (result == QP_REJECTED) printf("QMI %s qmi_error=%u\n", cmd, (unsigned)error);
    close(sd);
    if (dms >= 0) close(dms);
    return result == QP_SUCCESS ? 0 : result == QP_REJECTED ? 7 : 8;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (geteuid() != 0) {
        fprintf(stderr, "qmi-probe requires root.\n");
        return 1;
    }
    if (argc >= 2 && is_lock_command(argv[1])) return run_lock_command(argc, argv);
    uint32_t service = argc >= 2 ? (uint32_t)strtoul(argv[1], NULL, 0) : QMI_NAS_SERVICE;
    printf("QMI probe: service 0x%x over QRTR\n", service);

    int sd = socket(AF_QIPCRTR, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    printf("STEP socket=%s\n", sd < 0 ? strerror(errno) : "ok");
    if (sd < 0) return 2;

    struct sockaddr_qrtr server;
    int e = qrtr_lookup(service, 1, &server);
    if (e) e = qrtr_lookup(service, 0, &server);
    printf("STEP lookup=%s%s\n", e ? strerror(e) : "ok",
        e ? "" : " (node 1 is implied; resolved)");
    if (e) {
        close(sd);
        return 3;
    }

    if (connect(sd, (struct sockaddr *)&server, sizeof server) < 0) {
        printf("STEP connect=%s\n", strerror(errno));
        close(sd);
        return 4;
    }
    printf("STEP connect=ok\n");

    struct { const char *name; uint32_t msg; } tests[] = {
        { "NAS_GET_SERVING_SYSTEM", QMI_NAS_GET_SERVING_SYSTEM },
        { "NAS_GET_RF_BAND_INFO", QMI_NAS_GET_RF_BAND_INFO },
    };
    int succeeded = 0, reachable = 0;
    for (int i = 0; i < (int)(sizeof tests / sizeof tests[0]); i++) {
        uint8_t buf[QMI_MAX];
        int n = tests[i].msg == QMI_NAS_GET_SERVING_SYSTEM ?
            (int)qp_nas_get_serving_system(buf, (uint32_t)(i + 1)) :
            (int)qp_nas_get_rf_band_info(buf, (uint32_t)(i + 1));
        int reply = 0;
        int transport_error = qmi_exchange(sd, buf, n, &reply);
        printf("QMI %-26s ", tests[i].name);
        if (transport_error) {
            printf("transport_error=%s\n", strerror(transport_error));
            continue;
        }
        uint16_t error;
        switch (qp_read_result(buf, (size_t)reply, &error)) {
        case QP_SUCCESS:
            reachable = 1;
            succeeded++;
            printf("ok reply=%d bytes\n", reply);
            break;
        case QP_REJECTED:
            reachable = 1;
            printf("rejected qmi_error=%u\n", (unsigned)error);
            break;
        case QP_MALFORMED:
            printf("malformed_response reply=%d bytes\n", reply);
            break;
        }
    }

    close(sd);
    int ready = succeeded == (int)(sizeof tests / sizeof tests[0]);
    printf("VERDICT %s\n", ready ? "ready" : reachable ? "reachable" : "fail");
    return ready ? 0 : reachable ? 6 : 5;
}
