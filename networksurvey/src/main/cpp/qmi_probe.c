/* qmi-probe: confirm whether the QMI NAS service is reachable over QRTR on
 * this rooted device, before any lock command is written.
 *
 * Steps, printed one per line so adb/su output is easy to read:
 *   STEP socket=ok
 *   STEP lookup=ok (node 0 port N)
 *   STEP connect=ok
 *   QMI NAS_GET_SERVING_SYSTEM ... ok reply=17 bytes 01 0a 00 00 ...
 *   QMI NAS_GET_RF_BAND_INFO ... ok reply=...
 *   VERDICT ready|fail
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
#define QMI_MAX 65536

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
    uint16_t txn = qp_read16(buf + 1);
    uint16_t msg = qp_read16(buf + 3);
    if (len < QMI_HEADER || len >= QMI_MAX) return EINVAL;
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

static void dump_hex(const uint8_t *p, int n) {
    int show = n > 40 ? 40 : n;
    for (int i = 0; i < show; i++) printf("%02x ", p[i]);
    if (n > show) printf("...");
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    if (geteuid() != 0) {
        fprintf(stderr, "qmi-probe requires root.\n");
        return 1;
    }
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
    int failed = 0;
    for (int i = 0; i < (int)(sizeof tests / sizeof tests[0]); i++) {
        uint8_t buf[QMI_MAX];
        int n = tests[i].msg == QMI_NAS_GET_SERVING_SYSTEM ?
            (int)qp_nas_get_serving_system(buf, (uint32_t)(i + 1)) :
            (int)qp_nas_get_rf_band_info(buf, (uint32_t)(i + 1));
        int reply = 0;
        int e = qmi_exchange(sd, buf, n, &reply);
        printf("QMI %-26s %s", tests[i].name, e ? strerror(e) : "ok");
if (!e) {
            printf(" reply=%d bytes ", reply);
            dump_hex(buf, reply);
        }
        printf("\n");
        if (e) failed = 1;
    }

    close(sd);
    printf("VERDICT %s\n", failed ? "FAIL" : "ready");
    return failed ? 5 : 0;
}