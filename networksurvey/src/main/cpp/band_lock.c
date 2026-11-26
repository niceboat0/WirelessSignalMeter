#define _GNU_SOURCE
#include "band_lock_protocol.h"
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

static uint32_t client_id, transaction = 0x80;
static int router = -1, state_dir = -1;
static int have_device;
static char device_model[256], device_soc[256], device_fingerprint[256];
static char connected_label[64];

static int write_backup_origin(void) {
    int n, ok, fd;
    char buf[1024];
    if (!have_device) return 0;
    fd = openat(state_dir, "backup-origin", O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return errno == EEXIST ? 0 : 1;
    n = snprintf(buf, sizeof(buf), "model=%s\nsoc=%s\nfingerprint=%s\n",
        device_model, device_soc, device_fingerprint);
    ok = n > 0 && n < (int)sizeof(buf) && write(fd, buf, (size_t)n) == n && fsync(fd) == 0;
    close(fd);
    if (!ok) {
        unlinkat(state_dir, "backup-origin", 0);
        return 1;
    }
    return fsync(state_dir) == 0 ? 0 : 1;
}

static int origin_line(const char *text, const char *key, char *dst, size_t cap) {
    size_t k = strlen(key);
    const char *p = text;
    while (*p) {
        const char *end = strchr(p, '\n');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        if (len > k && !strncmp(p, key, k) && p[k] == '=') {
            size_t v = len - k - 1;
            if (!v || v >= cap) return 1;
            memcpy(dst, p + k + 1, v);
            dst[v] = 0;
            if (dst[v - 1] == '\r') dst[v - 1] = 0;
            return 0;
        }
        p = end ? end + 1 : p + len;
    }
    return 1;
}

static int backup_origin_conflict(void) {
    char buf[1024], model[256], soc[256], fingerprint[256];
    ssize_t n;
    int fd;
    if (!have_device) return 0;
    fd = openat(state_dir, "backup-origin", O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return 0;
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return 1;
    buf[n] = 0;
    if (origin_line(buf, "model", model, sizeof(model)) ||
        origin_line(buf, "soc", soc, sizeof(soc)) ||
        origin_line(buf, "fingerprint", fingerprint, sizeof(fingerprint)))
        return 1;
    return strcmp(model, device_model) || strcmp(soc, device_soc) ||
        strcmp(fingerprint, device_fingerprint);
}

static int load_session_identity(void) {
    char buf[1024];
    ssize_t n;
    int fd = openat(state_dir, "session-identity", O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return 0;
    n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    unlinkat(state_dir, "session-identity", 0);
    if (n <= 0) return 1;
    buf[n] = 0;
    if (origin_line(buf, "model", device_model, sizeof(device_model)) ||
        origin_line(buf, "soc", device_soc, sizeof(device_soc)) ||
        origin_line(buf, "fingerprint", device_fingerprint, sizeof(device_fingerprint)))
        return 1;
    have_device = 1;
    return 0;
}

static int fail(const char *message) {
    fprintf(stderr, "%s\n", message);
    return 1;
}

static long long monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void disconnect_router(const char *reason) {
    if (router < 0) return;
    if (client_id) printf("LOCK_DIAG disconnect=%s channel=%s\n", reason, connected_label);
    close(router);
    router = -1;
    client_id = 0;
}

static int receive(uint8_t *data, int size, int timeout) {
    struct pollfd p = { .fd = router, .events = POLLIN | POLLRDHUP };
    int result;
    do { result = poll(&p, 1, timeout); } while (result < 0 && errno == EINTR);
    if (result > 0 && (p.revents & POLLIN)) {
        int length = (int)recv(router, data, (size_t)size, 0);
        if (length > 0) return length;
        if (length < 0 && (errno == EINTR || errno == EAGAIN) &&
            !(p.revents & (POLLHUP | POLLERR | POLLNVAL | POLLRDHUP))) return -1;
        disconnect_router(length == 0 ? "eof" : "read_failed");
    } else if (result < 0 || (result > 0 && (p.revents & (POLLHUP | POLLERR | POLLNVAL | POLLRDHUP)))) {
        disconnect_router("poll_failed");
    }
    return -1;
}

/* Transport candidates, tried in order. The Samsung diag-router binds an
 * abstract name built from 108 zero bytes (verified live on SM-S9370);
 * generic Qualcomm devices usually expose the diagservices-style abstract
 * name "diag". The registration handshake is the oracle: a candidate counts
 * only when it returns a valid client id. */
struct lb_transport {
    const char *label;
    int zero_name;
    int seqpacket;
};
static const struct lb_transport lb_transports[] = {
    { "samsung-diag-router", 1, 1 },
    { "diag-stream", 0, 0 },
    { "diag-seqpacket", 0, 1 },
};

static int register_client(void) {
    uint8_t reg[24] = {0}, response[8192];
    lb_u32(reg, 0x17);
    lb_u32(reg + 4, (uint32_t)getpid());
    lb_u32(reg + 8, UINT32_MAX);
    reg[12] = reg[13] = 0xff;
    lb_u32(reg + 14, 18);
    if (write(router, reg, sizeof(reg)) != sizeof(reg)) return 0;
    long long deadline = monotonic_ms() + 1500;
    while (router >= 0 && monotonic_ms() < deadline) {
        int size = receive(response, sizeof(response), 300);
        if (size >= 8 && lb_read32(response) == 0x20000) {
            uint32_t id = lb_read32(response + 4);
            if (!id || id == UINT32_MAX) return 0;
            client_id = id;
            return 1;
        }
    }
    return 0;
}

static int connect_candidate(const struct lb_transport *tr) {
    int fd = socket(AF_UNIX,
        (tr->seqpacket ? SOCK_SEQPACKET : SOCK_STREAM) | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
    if (fd < 0) return 1;
    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    int namelen = tr->zero_name ?
        lb_zero_abstract_name((uint8_t *)address.sun_path) :
        lb_short_abstract_name((uint8_t *)address.sun_path, "diag");
    int rc = connect(fd, (struct sockaddr *)&address,
        (socklen_t)(offsetof(struct sockaddr_un, sun_path) + namelen));
    if (rc < 0 && errno == EINPROGRESS) {
        struct pollfd p = { .fd = fd, .events = POLLOUT };
        int error;
        socklen_t len = sizeof(error);
        if (poll(&p, 1, 1000) > 0 &&
            getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &len) == 0 && error == 0) rc = 0;
    }
    if (rc == 0) {
        router = fd;
        if (register_client()) {
            snprintf(connected_label, sizeof(connected_label), "%s", tr->label);
            printf("LOCK_DIAG registered channel=%s\n", connected_label);
            return 0;
        }
        printf("LOCK_DIAG registration_failed channel=%s\n", tr->label);
        disconnect_router("registration_failed");
    } else {
        close(fd);
    }
    return 1;
}

static int connect_router(const char *preferred) {
    const struct lb_transport *first = NULL;
    for (unsigned t = 0; preferred && t < sizeof(lb_transports) / sizeof(lb_transports[0]); t++) {
        if (!strcmp(preferred, lb_transports[t].label)) {
            first = &lb_transports[t];
            if (connect_candidate(first) == 0) return 0;
            printf("LOCK_DIAG preferred_unavailable channel=%s\n", first->label);
            break;
        }
    }
    for (unsigned t = 0; t < sizeof(lb_transports) / sizeof(lb_transports[0]); t++) {
        if (&lb_transports[t] != first && connect_candidate(&lb_transports[t]) == 0) return 0;
    }
    return fail("This device does not expose a supported diag channel "
        "(tried samsung-diag-router, diag-stream, diag-seqpacket).");
}

static void check_router_closed(void) {
    if (router >= 0) {
        struct pollfd p = { .fd = router, .events = POLLRDHUP };
        if (poll(&p, 1, 0) > 0 && (p.revents & (POLLHUP | POLLERR | POLLNVAL | POLLRDHUP)))
            disconnect_router("idle_socket_closed");
    }
}

/* Check idle connections before dispatching any part of a new command. */
static int ensure_router(const char *preferred) {
    check_router_closed();
    if (router >= 0) return 0;
    return connect_router(connected_label[0] ? connected_label : preferred);
}

static int send_frame(const uint8_t *payload, int size, uint32_t *tx) {
    uint8_t frame[512];
    if (size < 4 || size > (int)sizeof(frame) - 12) return 1;
    if (router < 0) { errno = ENOTCONN; return 1; }
    *tx = transaction++;
    lb_u32(frame, 0x40); lb_u32(frame + 4, *tx); lb_u32(frame + 8, client_id);
    memcpy(frame + 12, payload, (size_t)size);
    if (write(router, frame, (size_t)size + 12) == size + 12) return 0;
    int error = errno;
    disconnect_router("write_failed");
    errno = error;
    return 1;
}

static int exchange(const uint8_t *payload, int size, int kind, uint32_t tag,
        int allow_eexist, int allow_enoent) {
    uint32_t tx;
    if (send_frame(payload, size, &tx)) return fail("DIAG command write failed.");
    uint8_t response[8192];
    long long deadline = monotonic_ms() + 3500;
    uint32_t cmd = lb_read32(payload);
    while (router >= 0 && monotonic_ms() < deadline) {
        int length = receive(response, sizeof(response), 350);
        if (length < 0) continue;
        int at = lb_find_reply(response, length, cmd);
        if (at < 0) continue;
        uint32_t status = 0;
        int verdict = lb_ack(response, length, at, kind, tag, allow_eexist, allow_enoent, &status);
        if (verdict < 0) continue;   /* a different reply shape; keep waiting */
        printf("ACK command=%08x kind=%d status=%u tail=%d\n", cmd, kind, status, length - at);
        if (verdict == 0) return 0;
        return fail("The modem rejected the command; see its status above.");
    }
    disconnect_router("ack_timeout");
    return fail("No recognized modem acknowledgement. The operation may be partial; refresh before retrying.");
}

/* EFS2 subsystem probe. STATFS("/") is read-only and its reply echoes the
 * exact 4-byte opcode, so it tells us whether a subsystem answers without
 * touching any lock state. Most modems answer on 0x13; some only on 0x3E
 * (catxeon/qcom-efs-browser probes the same pair on its daemon). 0x13 is
 * tried first so a normal device keeps the behaviour it already had. */
static int efs_probe_subsys(uint8_t subsys) {
    uint8_t frame[160], response[8192];
    uint8_t saved = lb_efs_subsys;
    lb_efs_subsys = subsys;
    int size = lb_efs_statfs(frame, "/");
    uint32_t tx;
    int answered = 0;
    if (!send_frame(frame, size, &tx)) {
        long long deadline = monotonic_ms() + 1200;
        while (router >= 0 && monotonic_ms() < deadline) {
            int length = receive(response, sizeof(response), 250);
            if (length <= 0) continue;
            if (lb_find_reply(response, length, lb_read32(frame)) >= 0) {
                answered = 1;
                break;
            }
        }
    }
    if (!answered) lb_efs_subsys = saved;
    return answered;
}

/* Runs once per process, before the first lock item is touched. A device that
 * answers on neither subsystem keeps the default 0x13 so the failure surfaces
 * on the real command with its usual message. */
static void ensure_efs_subsys(void) {
    static int resolved;
    if (resolved) return;
    resolved = 1;
    if (efs_probe_subsys(LB_EFS_SUBSYS_STD)) {
        printf("EFS subsys=0x%02x (probe)\n", lb_efs_subsys);
        return;
    }
    if (efs_probe_subsys(LB_EFS_SUBSYS_ALT)) {
        printf("EFS subsys=0x%02x (probe: 0x%02x silent)\n", lb_efs_subsys, LB_EFS_SUBSYS_STD);
        return;
    }
    printf("EFS subsys=0x%02x (probe unanswered; default)\n", lb_efs_subsys);
}

/* Read up to `bytes` of an item into mask, clamped to the caller's buffer
 * capacity (the probe reads 164-byte cell-lock lists; a caller passing a
 * smaller buffer must fail closed, never overrun the stack). */
static int read_mask_to(const char *path, int bytes, uint8_t *mask,
        int mask_cap, int timeout_ms) {
    uint8_t payload[160], response[8192];
    if (bytes < 1 || bytes > mask_cap) return 1;
    lb_u32(payload, lb_efs_cmd(0x27)); lb_u32(payload + 4, (uint32_t)bytes);
    lb_u32(payload + 8, (uint32_t)strlen(path) + 1);
    payload[12] = 0xff; payload[13] = 0x03;
    int size = lb_copy_path(payload, 14, path);
    uint32_t tx;
    if (send_frame(payload, size, &tx)) return 1;
    long long deadline = monotonic_ms() + timeout_ms;
    while (router >= 0 && monotonic_ms() < deadline) {
        int length = receive(response, sizeof(response), 300);
        if (length > 0 && lb_get_data(response, length, tx, bytes, mask)) {
            for (int i = 0; i < bytes; i++) if (mask[i]) return 0;
            return 1;
        }
    }
    return 1;
}

static int read_mask(const char *path, int bytes, uint8_t *mask) {
    /* every mask caller uses a 64-byte buffer */
    return read_mask_to(path, bytes, mask, 64, 3000);
}

/* Read only the two fixed cell-lock items supported by the probe commands. */
static int efs_read(const char *path, uint8_t *out, int cap, int *size) {
    uint8_t payload[200], response[8192];
    uint32_t tx, fd = 0;
    int n = lb_efs_open(payload, path);
    if (n >= (int)sizeof(payload) || send_frame(payload, n, &tx)) return 1;
    int opened = 0;
    long long deadline = monotonic_ms() + 3000;
    while (router >= 0 && monotonic_ms() < deadline) {
        int length = receive(response, sizeof(response), 300);
        if (length <= 0) continue;
        int at = lb_find_reply(response, length, lb_efs_cmd(0x02));
        if (at < 0 || length != at + 8) continue;
        fd = lb_read32(response + at);
        if (lb_read32(response + at + 4)) return 2;
        opened = 1;
        break;
    }
    if (!opened) return 1;
    int total = 0, rc = 0;
    while (total < cap) {
        int want = cap - total;
        if (want > 1024) want = 1024;
        n = lb_efs_read(payload, fd, (uint32_t)want, (uint32_t)total);
        if (send_frame(payload, n, &tx)) { rc = 1; break; }
        int got = -1;
        deadline = monotonic_ms() + 3000;
        while (router >= 0 && monotonic_ms() < deadline) {
            int length = receive(response, sizeof(response), 300);
            if (length <= 0) continue;
            int at = lb_find_reply(response, length, lb_efs_cmd(0x04));
            if (at < 0 || length < at + 16 || lb_read32(response + at) != fd ||
                lb_read32(response + at + 4) != (uint32_t)total) continue;
            uint32_t err = lb_read32(response + at + 12);
            uint32_t count = lb_read32(response + at + 8);
            if (err) { got = -2; break; }
            if (count > (uint32_t)want || count > (uint32_t)(length - at - 16)) continue;
            for (uint32_t i = 0; i < count; i++) out[total + i] = response[at + 16 + i];
            got = (int)count;
            break;
        }
        if (got < 0) { rc = (got == -2) ? 2 : 1; break; }
        total += got;
        if (got < want) break;
    }
    lb_efs_close(payload, fd);
    send_frame(payload, 8, &tx);
    *size = total;
    return rc ? rc : (total > 0 && total < cap ? 0 : 1);
}

static int efs_stat_size(const char *path, int *size, uint32_t *mode) {
    uint8_t payload[200], response[8192];
    int n = lb_efs_stat(payload, path);
    uint32_t tx;
    if (n >= (int)sizeof(payload) || send_frame(payload, n, &tx)) return 1;
    long long deadline = monotonic_ms() + 2500;
    while (router >= 0 && monotonic_ms() < deadline) {
        int length = receive(response, sizeof(response), 300);
        if (length <= 0) continue;
        int at = lb_find_reply(response, length, lb_efs_cmd(0x0f));
        if (at < 0 || length != at + 28) continue;
        if (lb_read32(response + at)) return 2;
        *mode = lb_read32(response + at + 4);
        *size = (int)lb_read32(response + at + 8);
        return 0;
    }
    return 1;
}

static int item_probe(const char *label, const char *path, int *size_out) {
    uint32_t mode = 0;
    int size = 0;
    if (efs_stat_size(path, &size, &mode) == 0) {
        printf("RESULT %s size=%d\n", label, size);
        printf("MODE %s 0%o item=%d\n", label, (unsigned)mode,
            (mode & 0170000u) == 0160000u);
        *size_out = size;
        return 0;
    }
    static uint8_t buf[1024];
    int rc = efs_read(path, buf, (int)sizeof(buf), &size);
    if (rc) {
        printf("RESULT %s read_failed rc=%d\n", label, rc);
        return 1;
    }
    printf("RESULT %s size=%d\nHEX %s ", label, size, label);
    for (int i = 0; i < size; i++) printf("%02x", buf[i]);
    printf("\n");
    *size_out = size;
    return 0;
}

static int restore_mask(const char *name, int bytes, uint8_t *mask) {
    int fd = openat(state_dir, name, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return 1;
    struct stat st;
    int ok = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size == bytes &&
        read(fd, mask, (size_t)bytes) == bytes;
    close(fd);
    if (ok) for (int i = 0; i < bytes; i++) if (mask[i]) return 0;
    return 1;
}

/* Only an unreadable modem baseline returns 2; local persistence errors return 1. */
static int backup_mask(const char *path, const char *name, int bytes) {
    uint8_t original[64];
    if (backup_origin_conflict())
        return fail("The band backup does not match this device. No band changes were made.");
    if (!restore_mask(name, bytes, original)) return 0;
    if (read_mask(path, bytes, original)) return 2;
    int fd = openat(state_dir, name, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) return fail("Cannot create the original band backup; no band changes were made.");
    int ok = write(fd, original, (size_t)bytes) == bytes && fsync(fd) == 0;
    close(fd);
    if (!ok) {
        unlinkat(state_dir, name, 0);
        return fail("Cannot persist the original band backup; no band changes were made.");
    }
    if (fsync(state_dir)) return fail("Cannot sync the backup directory; no band changes were made.");
    if (write_backup_origin()) {
        unlinkat(state_dir, name, 0);
        return fail("Cannot persist the band backup identity; no band changes were made.");
    }
    puts("Original band mask backed up.");
    return 0;
}

static int put_file(const char *dir, const char *file, const char *sync_path,
        const uint8_t *data, int bytes, uint8_t mode_a, uint8_t mode_b, int lte_band) {
    uint8_t payload[400];
    int size = lb_mkdir(payload, dir, mode_a, mode_b);
    if (exchange(payload, size, LB_ACK_STATUS, 0, 1, 0)) return 1;  /* EEXIST fine */
    size = lb_put(payload, file, data, bytes, lte_band);
    if (exchange(payload, size, LB_ACK_PUT, lte_band ? 0xff03u : 0x2613u, 0, 0)) return 1;
    size = lb_sync(payload, sync_path);
    return exchange(payload, size, LB_ACK_LOOSE, 0, 0, 0);
}

static int remove_file(const char *path, const char *dir) {
    uint8_t payload[160];
    int size = lb_unlink(payload, path);
    if (exchange(payload, size, LB_ACK_STATUS, 0, 0, 1)) return 1;  /* ENOENT fine */
    size = lb_sync(payload, dir);
    return exchange(payload, size, LB_ACK_LOOSE, 0, 0, 0);
}

static void observe_modem_restart(void) {
    uint8_t response[8192];
    long long start = monotonic_ms();
    int echo = 0;
    for (;;) {
        long long elapsed = monotonic_ms() - start;
        if (elapsed >= 2000) break;
        struct pollfd event = { .fd = router, .events = POLLIN };
        int result = poll(&event, 1, (int)(2000 - elapsed));
        if (result < 0 && errno == EINTR) continue;
        if (result == 0) break;
        if (result < 0) {
            printf("SSR observe=poll_failed errno=%d\n", errno);
            return;
        }
        if (event.revents & (POLLERR | POLLHUP | POLLNVAL)) {
            printf("SSR observe=router_event flags=0x%x elapsed_ms=%lld\n",
                (unsigned)event.revents, monotonic_ms() - start);
            return;
        }
        if (event.revents & POLLIN) {
            ssize_t bytes = recv(router, response, sizeof(response), MSG_DONTWAIT);
            if (bytes < 0 && (errno == EAGAIN || errno == EINTR)) continue;
            if (bytes <= 0) {
                printf("SSR observe=%s errno=%d elapsed_ms=%lld\n",
                    bytes == 0 ? "router_closed" : "recv_failed",
                    bytes == 0 ? 0 : errno, monotonic_ms() - start);
                return;
            }
            if (!echo && lb_find_reply(response, (int)bytes, 0x0003254b) >= 0) {
                echo = 1;
                printf("SSR observe=command_echo elapsed_ms=%lld\n", monotonic_ms() - start);
            }
        }
    }
    printf("SSR observe=window_elapsed echo=%d; modem_restart_unconfirmed\n", echo);
}

static int reboot_modem(int observe) {
    const uint8_t frame[4] = {0x4b, 0x25, 0x03, 0x00};
    uint32_t tx;
    sleep(2);
    if (send_frame(frame, sizeof(frame), &tx)) {
        if (observe) printf("SSR send=failed errno=%d\n", errno);
        return fail("Files were updated, but the modem restart command failed.");
    }
    if (observe) {
        printf("SSR send=sent tx=%u\n", tx);
        observe_modem_restart();
    }
    disconnect_router("modem_restart_requested");
    puts("RESULT restart_sent");
    puts("Modem restart requested. Wait for registration, then refresh to check the serving cell.");
    return 0;
}

/* LTE band lock: 1..32 rides the instant CM frame (the only route verified
 * live to move the serving bands on the reference device); 33..64 keeps the
 * EFS+restart route applied at modem boot. */
static int lte_band_command(const char *bands, int restore) {
    uint8_t mask[8] = {0};
    if (backup_origin_conflict())
        return fail("The band backup does not match this device. No band changes were made.");
    if (restore) {
        if (restore_mask("lte-original.bin", 8, mask))
            return fail("No usable original LTE band backup. Restore was not applied.");
    } else {
        if (!lb_bands(bands, 64, mask, 8)) return fail("Invalid band list.");
        uint8_t probe[8];
        if (restore_mask("lte-original.bin", 8, probe)) {
            /* First lock on this install: keep a copy of the stored item for restore. */
            uint8_t original[8];
            if (read_mask(LB_LTE_BANDS, 8, original)) {
                for (int i = 4; i < 8; i++) if (mask[i])
                    return fail("Cannot read the original LTE mask. No persistent band changes were made.");
                puts("Note: the stored LTE band item is not readable; the CM lock works without it. Restore then needs a modem restart.");
            } else {
                int fd = openat(state_dir, "lte-original.bin",
                    O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
                if (fd < 0) return fail("Cannot create the original LTE backup. No band changes were made.");
                int ok = write(fd, original, (size_t)8) == 8 && fsync(fd) == 0;
                close(fd);
                if (!ok) {
                    unlinkat(state_dir, "lte-original.bin", 0);
                    return fail("Cannot persist the original LTE backup. No band changes were made.");
                }
                if (fsync(state_dir)) return fail("Cannot sync the LTE backup directory. No band changes were made.");
                if (write_backup_origin()) {
                    unlinkat(state_dir, "lte-original.bin", 0);
                    return fail("Cannot persist the LTE backup identity. No band changes were made.");
                }
                puts("Original LTE band mask backed up.");
            }
        }
    }
    for (int i = 4; i < 8; i++) if (mask[i]) {
        /* above band 32 the CM frame cannot carry it */
        if (put_file(LB_MMODE, LB_LTE_BANDS, LB_LTE_BANDS, mask, 8, 0x7f, 0x7f, 1)) return 1;
        return reboot_modem(0);
    }
    uint8_t frame[36];
    int size = lb_cm_band(frame, lb_read32(mask));
    if (exchange(frame, size, LB_ACK_LOOSE, 0, 0, 0)) return 1;
    puts("RESULT accepted");
    puts("Instant LTE band preference sent; verify the serving cell without restarting the modem.");
    return 0;
}

/* Treat errors other than absence as an unusable backup, not permission to
 * overwrite it or synthesize defaults. */
static int backup_may_exist(const char *name) {
    struct stat info;
    return fstatat(state_dir, name, &info, AT_SYMLINK_NOFOLLOW) == 0 || errno != ENOENT;
}

static int nr_nsa_present(void) {
    uint8_t mask[64];
    return read_mask_to(LB_NR_NSA_BANDS, 64, mask, sizeof(mask), 1500) == 0;
}

static int verify_nr_mask(const char *label, const char *path, const uint8_t *expected) {
    uint8_t mask[64];
    if (read_mask(path, sizeof(mask), mask)) {
        printf("NR_BAND readback=%s status=unreadable\n", label);
        return fail("NR band mask readback failed; restart was not requested.");
    }
    if (memcmp(mask, expected, sizeof(mask))) {
        printf("NR_BAND readback=%s status=mismatch\n", label);
        return fail("NR band mask differs from the requested value; restart was not requested.");
    }
    printf("NR_BAND readback=%s status=match\n", label);
    return 0;
}

static int nr_band_command(const char *bands, int restore) {
    uint8_t mask[64] = {0};
    uint8_t nsa_mask[64];
    puts(restore ? "NR_BAND operation=restore" : "NR_BAND operation=lock");
    if (backup_origin_conflict())
        return fail("The band backup does not match this device. No band changes were made.");
    if (backup_may_exist("nr-no-original")) {
        uint8_t unverified[64];
        int primary_file = backup_may_exist("nr-original.bin");
        int primary_readable = restore_mask("nr-original.bin", sizeof(unverified), unverified) == 0;
        printf("NR_BAND backup=legacy_unknown_baseline primary_file=%d primary_readable=%d\n",
            primary_file, primary_readable);
        if (!restore) {
            puts("RESULT baseline_unverified");
            return fail("The previous NR lock has no verified baseline; re-locking would make it irreversible. "
                        "Run restore first to unlock, then lock again.");
        }
        /* Legacy lock: no verified original mask exists, so we cannot write a band
         * back. Restoring therefore unlocks to the full range by removing the NV
         * band items, then clears the local state so a later lock starts from a
         * clean, verified baseline. */
        puts("NR_BAND restore=unlock_no_baseline");
        if (remove_file(LB_NR_BANDS, LB_MMODE)) return 1;
        if (nr_nsa_present()) remove_file(LB_NR_NSA_BANDS, LB_MMODE);
        unlinkat(state_dir, "nr-original.bin", 0);
        unlinkat(state_dir, "nr-nsa-original.bin", 0);
        unlinkat(state_dir, "nr-no-original", 0);
        puts("NR_BAND all_masks_cleared=1");
        return reboot_modem(1);
    }
    int nsa;
    if (restore) {
        if (restore_mask("nr-original.bin", sizeof(mask), mask)) {
            puts("NR_BAND backup=primary_unavailable");
            return fail("No usable original NR band backup. No band changes were made.");
        }
        nsa = restore_mask("nr-nsa-original.bin", sizeof(nsa_mask), nsa_mask) == 0;
        if (!nsa && backup_may_exist("nr-nsa-original.bin")) {
            puts("NR_BAND backup=nsa_unusable");
            return fail("The original NR-NSA backup is unreadable. No band changes were made.");
        }
        printf("NR_BAND backup=primary_valid nsa_saved=%d\n", nsa);
    } else {
        if (!lb_bands(bands, 512, mask, sizeof(mask))) return fail("Invalid band list.");
        int backup = backup_mask(LB_NR_BANDS, "nr-original.bin", sizeof(mask));
        if (backup) {
            printf("NR_BAND backup=primary_failed rc=%d\n", backup);
            return backup == 1 ? 1 :
                fail("Cannot read the original NR mask. No band changes were made.");
        }
        nsa = nr_nsa_present();
        if (nsa) {
            backup = backup_mask(LB_NR_NSA_BANDS, "nr-nsa-original.bin", sizeof(nsa_mask));
            if (backup) {
                printf("NR_BAND backup=nsa_failed rc=%d\n", backup);
                return backup == 1 ? 1 :
                    fail("Cannot capture the original NR-NSA mask. No band changes were made.");
            }
            memcpy(nsa_mask, mask, sizeof(nsa_mask));
        }
        printf("NR_BAND backup=primary_valid nsa_readable=%d\n", nsa);
    }
    if (nsa) {
        puts("NR_BAND write=nsa");
        if (put_file(LB_MMODE, LB_NR_NSA_BANDS, LB_MMODE, nsa_mask, sizeof(nsa_mask), 0x7a, 0x7a, 0) ||
            verify_nr_mask("nsa", LB_NR_NSA_BANDS, nsa_mask)) return 1;
    }
    puts("NR_BAND write=primary");
    if (put_file(LB_MMODE, LB_NR_BANDS, LB_MMODE, mask, sizeof(mask), 0x7a, 0x7a, 0) ||
        verify_nr_mask("primary", LB_NR_BANDS, mask)) return 1;
    puts("NR_BAND all_masks_verified=1");
    return reboot_modem(1);
}

static int cell_list(const char *text, uint8_t *data) {
    char list[512];
    if (strlen(text) >= sizeof(list)) return 0;
    strcpy(list, text);
    int count = 0;
    char *save;
    for (char *pair = strtok_r(list, ",", &save); pair; pair = strtok_r(NULL, ",", &save)) {
        char *colon = strchr(pair, ':');
        uint32_t channel, pci;
        if (count == 30 || !colon) return 0;
        *colon++ = 0;
        if (!lb_number(pair, 262143, &channel) || !lb_number(colon, 503, &pci)) return 0;
        lb_u32(data + 4 + count * 8, pci); lb_u32(data + 8 + count * 8, channel);
        count++;
    }
    lb_u32(data, (uint32_t)count);
    return count;
}

static int scs_encoding(const char *name) {
    if (!name || !strcmp(name, "index")) return LB_SCS_INDEX;
    if (!strcmp(name, "pow2")) return LB_SCS_POW2;
    if (!strcmp(name, "enum") || !strcmp(name, "enum1")) return LB_SCS_ENUM1;
    return -1;
}

/* Classic-NV dial tone: send NV read item 10 and see whether the modem
 * answers with a full-size reply (v95 on-device capture). Cheap capability
 * signal for devices whose EFS layout differs. */
static int nv_dial_tone(void) {
    uint8_t payload[132], response[8192];
    int size = lb_nv_read(payload, 10);
    uint32_t tx;
    if (send_frame(payload, size, &tx)) return 0;
    long long deadline = monotonic_ms() + 1500;
    while (router >= 0 && monotonic_ms() < deadline) {
        int length = receive(response, sizeof(response), 300);
        if (length >= 100) return 1;
    }
    return 0;
}

/* item probes ask for the full on-disk size (e.g. the 164-byte cell-lock
 * list); the buffer must cover the largest item we probe. */
#define LB_PROBE_BUF 256

static void print_cap(const char *key, const char *path, int bytes) {
    uint8_t buf[LB_PROBE_BUF];
    int ok = read_mask_to(path, bytes, buf, sizeof(buf), 1500) == 0;
    printf("CAP %s %d\n", key, ok);
}

/* AT passthrough over a modem AT port (e.g. /dev/smd0). The AT command family
 * is per-vendor (Huawei ^NRFREQLOCK, Qualcomm AT+QNWPREFCFG, ...), so the
 * tool stays generic: the caller supplies the node and the raw command. */
static int at_command(const char *node, const char *cmd) {
    int fd = open(node, O_RDWR | O_NOCTTY | O_NONBLOCK);
    if (fd < 0) return fail("AT port open failed - pass a valid node (e.g. /dev/smd0).");
    uint8_t buf[512];
    int drained = 0;
    while (drained < 64 && read(fd, buf, sizeof(buf)) > 0) drained++;
    char line[512];
    int n = snprintf(line, sizeof(line), "%s\r", cmd);
    if (write(fd, line, (size_t)n) != n) { close(fd); return fail("AT write failed."); }
    printf("[AT] -> %s\n", cmd);
    long long deadline = monotonic_ms() + 2500;
    int any = 0, at_line_start = 1;
    while (monotonic_ms() < deadline) {
        struct pollfd p = { .fd = fd, .events = POLLIN };
        int r;
        do { r = poll(&p, 1, 200); } while (r < 0 && errno == EINTR);
        if (r > 0 && (p.revents & POLLIN)) {
            int nr = (int)read(fd, buf, sizeof(buf));
            if (nr > 0) {
                for (int i = 0; i < nr; i++) {
                    if (at_line_start) fputs("[AT] <- ", stdout);
                    putchar(buf[i]);
                    at_line_start = buf[i] == '\n';
                }
                any = 1;
            }
        } else if (r == 0 && any) {
            break;
        }
    }
    close(fd);
    if (!any) return fail("AT response not found on this port.");
    if (!at_line_start) putchar('\n');
    return 0;
}

static int execute(int argc, char **argv) {
    const char *cmd = argv[0];
    /* Every command that touches a lock item speaks EFS2; pick the subsystem
     * once per process before anything is written. */
    ensure_efs_subsys();
    if (!strcmp(cmd, "probe") && argc <= 3) {
        uint8_t mask[64];
        if (argc >= 2) printf("DEVICE model %s\n", argv[1]);
        if (argc >= 3) printf("DEVICE soc %s\n", argv[2]);
        print_cap("lte_bandpref", LB_LTE_BANDS, 8);
        print_cap("nr_band_pref", LB_NR_BANDS, 64);
        print_cap("nr_nsa_band_pref", LB_NR_NSA_BANDS, 64);
        print_cap("cell_lock_list", LB_LTE_CELL, 164);
        print_cap("cell_restrict", LB_LTE_FREQ, 36);
        print_cap("nr_earfcn_lock", LB_NR_FREQ, 8);
        print_cap("nr_pci_lock_info", LB_NR_CELL, 12);
        printf("CAP nv %d\n", nv_dial_tone());
        check_router_closed();
        if (router < 0) return fail("DIAG connection was lost during the probe.");
        printf("BACKUP lte %d\n", !backup_origin_conflict() &&
            !restore_mask("lte-original.bin", 8, mask));
        printf("BACKUP nr %d\n", !backup_may_exist("nr-no-original") &&
            !restore_mask("nr-original.bin", 64, mask));
        printf("BACKUP nr_nsa %d\n", !restore_mask("nr-nsa-original.bin", 64, mask));
        puts("RESULT ready");
        return 0;
    }
    if (!strcmp(cmd, "lte-cell-probe") && argc == 1) {
        int size;
        if (item_probe("cell_lock_list", LB_LTE_CELL, &size)) return 1;
        if (size == 164) puts("LAYOUT cell_lock_list 164B 20slots");
        else if (size == 244) puts("LAYOUT cell_lock_list 244B 30slots");
        else printf("LAYOUT cell_lock_list unexpected-%d\n", size);
        return 0;
    }
    if (!strcmp(cmd, "nr-cell-probe") && argc == 1) {
        int size;
        if (item_probe("pci_lock_info", LB_NR_CELL, &size)) return 1;
        if (size == 12) puts("LAYOUT pci_lock_info 12B single-pci");
        else if (size == 28) puts("LAYOUT pci_lock_info 28B multi-pci");
        else if (size == 896) puts("LAYOUT pci_lock_info 896B 32records");
        else printf("LAYOUT pci_lock_info unexpected-%d\n", size);
        return 0;
    }
    if ((!strcmp(cmd, "lte-band") || !strcmp(cmd, "nr-band")) && argc == 2)
        return cmd[0] == 'n' ? nr_band_command(argv[1], 0) : lte_band_command(argv[1], 0);
    if ((!strcmp(cmd, "lte-band-restore") || !strcmp(cmd, "nr-band-restore")) && argc == 1)
        return cmd[0] == 'n' ? nr_band_command(NULL, 1) : lte_band_command(NULL, 1);
    if (!strcmp(cmd, "mode") && argc == 2) {
        uint32_t mode = !strcmp(argv[1], "auto") ? 2 : !strcmp(argv[1], "lte") ? 38 :
            !strcmp(argv[1], "nr") ? 100 : 0;
        if (!mode) return fail("Invalid network mode.");
        uint8_t frame[32] = {0};
        lb_u32(frame, 0x00020f4b); lb_u32(frame + 8, mode);
        lb_u32(frame + 12, 3); lb_u32(frame + 16, 0x80000000);
        lb_u32(frame + 20, 0x100); lb_u32(frame + 24, 2); lb_u32(frame + 28, 4);
        if (exchange(frame, sizeof(frame), LB_ACK_LOOSE, 0, 0, 0)) return 1;
        puts("RESULT accepted");
        return 0;
    }
    if (!strcmp(cmd, "at") && argc >= 3) {
        /* at <node> <command...>: the node is argv[1]; the remaining arguments
         * are joined with spaces into one AT command string. */
        char line[480] = {0};
        int at = 0;
        for (int i = 2; i < argc; i++) {
            if (i > 2 && at < 479) line[at++] = ' ';
            size_t len = strlen(argv[i]);
            if (at + (int)len >= (int)sizeof(line)) return fail("AT command too long.");
            memcpy(line + at, argv[i], len);
            at += (int)len;
        }
        line[at] = 0;
        return at_command(argv[1], line);
    }
    int nr = !strncmp(cmd, "nr-", 3), lte = !strncmp(cmd, "lte-", 4);
    if (!nr && !lte) return fail("Unknown command.");
    const char *operation = strchr(cmd, '-');
    if (!operation) return 1;
    operation++;
    const char *dir = nr ? LB_NR_DIR : LB_LTE_DIR;
    if (argc == 1 && (!strcmp(operation, "freq-clear") || !strcmp(operation, "cell-clear"))) {
        int frequency = !strcmp(operation, "freq-clear");
        const char *path = nr ? (frequency ? LB_NR_FREQ : LB_NR_CELL) :
            (frequency ? LB_LTE_FREQ : LB_LTE_CELL);
        if (remove_file(path, dir)) return 1;
        if (lte && !frequency && remove_file(LB_LTE_CSP "/pci_lock", LB_LTE_CSP)) return 1;
        return reboot_modem(0);
    }
    uint8_t data[244] = {0};
    const char *file = NULL, *sync_path = nr ? LB_NR_DIR "/" : LB_LTE_DIR;
    int size = 0;
    uint8_t m0 = 0x7f, m1 = 0x7f;
    uint32_t frequency, pci, band, scs;
    if (!strcmp(operation, "freq") && (nr ? (argc == 3 || argc == 4) : argc == 2)) {
        if (!lb_number(argv[1], nr ? 3279165 : 262143, &frequency)) return fail("Invalid frequency.");
        if (nr) {
            int encoding = scs_encoding(argc == 4 ? argv[3] : NULL);
            uint16_t scs_value;
            if (encoding < 0) return fail("SCS encoding must be index, pow2 or enum.");
            if (!frequency || !lb_number(argv[2], 240, &scs) || lb_scs_enc(scs, encoding, &scs_value) < 0)
                return fail("Invalid NR SCS.");
            lb_u32(data, frequency); lb_u32(data + 4, scs_value);
            file = LB_NR_FREQ; size = 8; m0 = 0xff; m1 = 0x1f;
        } else {
            lb_u32(data + 28, frequency); lb_u32(data + 32, frequency);
            file = LB_LTE_FREQ; size = 36; m1 = 0x6f; sync_path = LB_LTE_DIR "/";
        }
    } else if (lte && !strcmp(operation, "cell") && (argc == 2 || argc == 3)) {
        int count = cell_list(argv[1], data);
        if (!count) return fail("Invalid LTE cell list.");
        int slots = 0;
        if (argc == 3) {
            if (!strcmp(argv[2], "164")) slots = 20;
            else if (!strcmp(argv[2], "244")) slots = 30;
            else return fail("Cell list size must be 164 or 244.");
        }
        if (!slots) slots = count > 20 ? 30 : 20;
        if (count > slots) return fail("Cell list exceeds the selected item size.");
        file = LB_LTE_CELL; size = slots * 8 + 4; m0 = 0xff; m1 = 0x1f;
    } else if (nr && (!strcmp(operation, "cell") || !strcmp(operation, "cell-multi")) &&
            (argc == 5 || argc == 6)) {
        int encoding = scs_encoding(argc == 6 ? argv[5] : NULL);
        uint16_t scs_value;
        if (encoding < 0) return fail("SCS encoding must be index, pow2 or enum.");
        if (!lb_number(argv[1], 512, &band) || !band ||
            !lb_number(argv[2], 3279165, &frequency) || !frequency ||
            !lb_number(argv[4], 240, &scs) || lb_scs_enc(scs, encoding, &scs_value) < 0)
            return fail("Invalid NR cell parameters.");
        if (!strcmp(operation, "cell")) {
            if (!lb_number(argv[3], 1007, &pci)) return fail("Invalid NR cell parameters.");
            lb_u32(data, pci); lb_u32(data + 4, frequency);
            data[8] = (uint8_t)scs_value; data[9] = (uint8_t)(scs_value >> 8);
            data[10] = (uint8_t)(band - 1); data[11] = (uint8_t)((band - 1) >> 8);
            file = LB_NR_CELL; size = 12;
        } else {
            if (!lb_nr_cell_record(data, argv[3], frequency, scs_value, band))
                return fail("Invalid NR PCI list (1..8 PCIs).");
            file = LB_NR_CELL; size = 28; m0 = 0xff; m1 = 0x1f;
        }
    } else return fail("Unknown command or argument count.");
    if (put_file(dir, file, sync_path, data, size, m0, m1, 0)) return 1;
    return reboot_modem(0);
}

static void trim_line(char *text) {
    size_t n = strlen(text);
    while (n && (text[n - 1] == '\n' || text[n - 1] == '\r')) text[--n] = 0;
}

static int read_line(char *buf, int cap) {
    if (!fgets(buf, cap, stdin)) return -1;
    trim_line(buf);
    return 0;
}

static int channel_name_ok(const char *name) {
    if (!name || !name[0]) return 0;
    for (const char *p = name; *p; p++) {
        if (!((*p >= 'a' && *p <= 'z') || (*p >= '0' && *p <= '9') || *p == '-')) return 0;
        if ((size_t)(p - name) >= 63) return 0;
    }
    return 1;
}

/* One router client for every command on stdin. The collector's libdiag
 * session is a different client and is not borrowed here. */
static int run_session(const char *preferred) {
    alarm(100);
    if (ensure_router(preferred)) return 2;
    alarm(0);
    puts("SESSION connected");
    fflush(stdout);
    for (;;) {
        char header[64];
        if (read_line(header, sizeof(header))) break;
        if (!strcmp(header, "session-close")) break;
        int count = 0;
        if (strncmp(header, "CMD ", 4) != 0) {
            puts("SESSION exit 1");
            fflush(stdout);
            continue;
        }
        count = atoi(header + 4);
        if (count < 1 || count > 12) {
            puts("SESSION exit 1");
            fflush(stdout);
            continue;
        }
        char storage[12][256];
        char *args[12];
        int bad = 0;
        for (int i = 0; i < count; i++) {
            if (read_line(storage[i], (int)sizeof(storage[i]))) return 0;
            if (!storage[i][0] || strlen(storage[i]) > 240) bad = 1;
            args[i] = storage[i];
        }
        alarm(100);
        int rc = 1;
        if (bad) rc = fail("Invalid lock command.");
        else if (ensure_router(preferred)) rc = 2;
        else {
            printf("CHANNEL %s\n", connected_label);
            rc = execute(count, args);
            /* Only a connection failure before dispatch is safe to retry. */
            if (rc == 2) rc = 1;
        }
        alarm(0);
        printf("SESSION exit %d\n", rc);
        fflush(stdout);
    }
    return 0;
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    signal(SIGPIPE, SIG_IGN);
    if (getuid() != 0) return fail("Root authorization is required.");
    if (argc < 4 || strcmp(argv[1], "--state-dir")) return fail("Missing private state directory.");
    state_dir = open(argv[2], O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (state_dir < 0) return fail("Cannot open the private backup directory.");
    if (load_session_identity()) return fail("Invalid device identity.");
    int lock = openat(state_dir, "operation.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB)) return fail("Another lock operation is in progress.");

    int result;
    if (!strcmp(argv[3], "--session")) {
        const char *preferred = NULL;
        int end = 4;
        if (argc >= 6 && !strcmp(argv[4], "--channel")) {
            if (!channel_name_ok(argv[5])) return fail("Invalid DIAG channel.");
            preferred = argv[5];
            end = 6;
        }
        if (argc != end) return fail("Session mode takes commands on stdin.");
        result = run_session(preferred);
    } else {
        alarm(100);
        if (ensure_router(NULL)) return 2;
        printf("CHANNEL %s\n", connected_label);
        result = execute(argc - 3, argv + 3);
    }
    if (router >= 0) close(router);
    close(lock);
    close(state_dir);
    return result;
}
