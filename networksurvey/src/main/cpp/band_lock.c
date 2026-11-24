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

static int fail(const char *message) {
    fprintf(stderr, "%s\n", message);
    return 1;
}

static long long monotonic_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int receive(uint8_t *data, int size, int timeout) {
    struct pollfd p = { .fd = router, .events = POLLIN };
    int result;
    do { result = poll(&p, 1, timeout); } while (result < 0 && errno == EINTR);
    if (result <= 0 || !(p.revents & POLLIN)) return -1;
    return (int)recv(router, data, (size_t)size, 0);
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
    while (monotonic_ms() < deadline) {
        int size = receive(response, sizeof(response), 300);
        if (size >= 8 && lb_read32(response) == 0x20000) {
            client_id = lb_read32(response + 4);
            return client_id && client_id != UINT32_MAX;
        }
    }
    return 0;
}

static int connect_router(void) {
    for (unsigned t = 0; t < sizeof(lb_transports) / sizeof(lb_transports[0]); t++) {
        const struct lb_transport *tr = &lb_transports[t];
        int fd = socket(AF_UNIX,
            (tr->seqpacket ? SOCK_SEQPACKET : SOCK_STREAM) | SOCK_CLOEXEC, 0);
        if (fd < 0) continue;
        struct sockaddr_un address;
        memset(&address, 0, sizeof(address));
        address.sun_family = AF_UNIX;
        int namelen = tr->zero_name ?
            lb_zero_abstract_name((uint8_t *)address.sun_path) :
            lb_short_abstract_name((uint8_t *)address.sun_path, "diag");
        if (connect(fd, (struct sockaddr *)&address,
                (socklen_t)(offsetof(struct sockaddr_un, sun_path) + namelen)) == 0) {
            router = fd;
            if (register_client()) {
                printf("CHANNEL %s\n", tr->label);
                return 0;
            }
            router = -1;
        }
        close(fd);
    }
    return fail("This device does not expose a supported diag channel "
        "(tried samsung-diag-router, diag-stream, diag-seqpacket).");
}

static int send_frame(const uint8_t *payload, int size, uint32_t *tx) {
    uint8_t frame[512];
    if (size < 4 || size > (int)sizeof(frame) - 12) return 1;
    *tx = transaction++;
    lb_u32(frame, 0x40); lb_u32(frame + 4, *tx); lb_u32(frame + 8, client_id);
    memcpy(frame + 12, payload, (size_t)size);
    return write(router, frame, (size_t)size + 12) != size + 12;
}

static int exchange(const uint8_t *payload, int size, int kind, uint32_t tag,
        int allow_eexist, int allow_enoent) {
    uint32_t tx;
    if (send_frame(payload, size, &tx)) return fail("DIAG command write failed.");
    uint8_t response[8192];
    long long deadline = monotonic_ms() + 3500;
    uint32_t cmd = lb_read32(payload);
    while (monotonic_ms() < deadline) {
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
    return fail("No recognized modem acknowledgement. The operation may be partial; refresh before retrying.");
}

/* Read up to `bytes` of an item into mask, clamped to the caller's buffer
 * capacity (the probe reads 164-byte cell-lock lists; a caller passing a
 * smaller buffer must fail closed, never overrun the stack). */
static int read_mask_to(const char *path, int bytes, uint8_t *mask,
        int mask_cap, int timeout_ms) {
    uint8_t payload[160], response[8192];
    if (bytes < 1 || bytes > mask_cap) return 1;
    lb_u32(payload, 0x0027134b); lb_u32(payload + 4, (uint32_t)bytes);
    lb_u32(payload + 8, (uint32_t)strlen(path) + 1);
    payload[12] = 0xff; payload[13] = 0x03;
    int size = lb_copy_path(payload, 14, path);
    uint32_t tx;
    if (send_frame(payload, size, &tx)) return 1;
    long long deadline = monotonic_ms() + timeout_ms;
    while (monotonic_ms() < deadline) {
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

/* General EFS item read: OPEN -> READ -> CLOSE. Returns 0 with *size set, 1
 * when the item cannot be opened or read, 2 when the modem reports an errno.
 * READ carries an explicit byte count, so a short read is what reveals the
 * item's real length (cell_lock_list 164 vs 244, pci_lock_info 12 vs 28). */
static int efs_read(const char *path, uint8_t *out, int cap, int *size) {
    uint8_t payload[200], response[8192];
    uint32_t tx, fd = 0;
    int n = lb_efs_open(payload, path);
    if (n >= (int)sizeof(payload) || send_frame(payload, n, &tx)) return 1;
    int opened = 0;
    long long deadline = monotonic_ms() + 3000;
    while (monotonic_ms() < deadline) {
        int length = receive(response, sizeof(response), 300);
        if (length <= 0) continue;
        int at = lb_find_reply(response, length, 0x0002134b);
        if (at < 0 || length < at + 8) continue;
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
        while (monotonic_ms() < deadline) {
            int length = receive(response, sizeof(response), 300);
            if (length <= 0) continue;
            int at = lb_find_reply(response, length, 0x0004134b);
            if (at < 0 || length < at + 16) continue;
            uint32_t err = lb_read32(response + at + 12);
            uint32_t count = lb_read32(response + at + 8);
            if (err) { got = -2; break; }
            int avail = length - at - 16;
            if (count > (uint32_t)avail) count = (uint32_t)avail;
            if (count > (uint32_t)(cap - total)) count = (uint32_t)(cap - total);
            for (uint32_t i = 0; i < count; i++) out[total + i] = response[at + 16 + i];
            got = (int)count;
            break;
        }
        if (got < 0) { rc = (got == -2) ? 2 : 1; break; }
        total += got;
        if (got < want) break;          /* short read: end of item */
    }
    lb_efs_close(payload, fd);
    send_frame(payload, 8, &tx);        /* best effort, reply not needed */
    *size = total;
    return rc ? rc : (total > 0 ? 0 : 1);
}

/* EFS2 STAT. Returns 0 with size/mode filled, 2 when the modem reports an
 * errno (item absent, or the subsystem refuses STAT), 1 when no reply came. */
static int efs_stat_size(const char *path, int *size, uint32_t *mode) {
    uint8_t payload[200], response[8192];
    int n = lb_efs_stat(payload, path);
    uint32_t tx;
    if (n >= (int)sizeof(payload) || send_frame(payload, n, &tx)) return 1;
    long long deadline = monotonic_ms() + 2500;
    while (monotonic_ms() < deadline) {
        int length = receive(response, sizeof(response), 300);
        if (length <= 0) continue;
        int at = lb_find_reply(response, length, 0x000f134b);
        if (at < 0 || length < at + 12) continue;
        if (lb_read32(response + at)) return 2;              /* errno */
        *mode = lb_read32(response + at + 4);
        *size = (int)lb_read32(response + at + 8);
        return 0;
    }
    return 1;
}

/* Fixed-item probe. STAT answers mode and size in a single frame, so the
 * common case transfers no data at all; when STAT is refused or unavailable
 * the OPEN/READ/CLOSE path runs as a fallback and dumps the bytes. */
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
    printf("RESULT %s size=%d\n", label, size);
    printf("HEX %s ", label);
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

static int reboot_modem(void) {
    const uint8_t frame[4] = {0x4b, 0x25, 0x03, 0x00};
    uint32_t tx;
    sleep(2);
    if (send_frame(frame, sizeof(frame), &tx)) return fail("Files were updated, but the modem restart command failed.");
    puts("RESULT restart_sent");
    puts("Modem restart requested. Wait for registration, then refresh to check the serving cell.");
    return 0;
}

/* LTE band lock: 1..32 rides the instant CM frame (the only route verified
 * live to move the serving bands on the reference device); 33..64 keeps the
 * EFS+restart route applied at modem boot. */
static int lte_band_command(const char *bands, int restore) {
    uint8_t mask[8] = {0};
    if (restore) {
        if (restore_mask("lte-original.bin", 8, mask) && read_mask(LB_LTE_BANDS, 8, mask))
            return fail("No original LTE band mask is available. A modem restart clears the instant lock.");
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
                puts("Original LTE band mask backed up.");
            }
        }
    }
    for (int i = 4; i < 8; i++) if (mask[i]) {
        /* above band 32 the CM frame cannot carry it */
        if (put_file(LB_MMODE, LB_LTE_BANDS, LB_LTE_BANDS, mask, 8, 0x7f, 0x7f, 1)) return 1;
        return reboot_modem();
    }
    uint8_t frame[36];
    int size = lb_cm_band(frame, lb_read32(mask));
    if (exchange(frame, size, LB_ACK_LOOSE, 0, 0, 0)) return 1;
    puts("RESULT accepted");
    puts("Instant LTE band preference sent; verify the serving cell without restarting the modem.");
    return 0;
}

/* NR unlock semantics follow the Dingli ROOT(QUALCOMM) plugin: there is no
 * SET_UNLOCKBAND - a band mask is "unlocked" by rewriting the FULL supported
 * set (see diag-research reports/v98_dmg6_official_sequences.md §7). The
 * modem applies nr_band_pref at baseband boot, so one full-set write + SSR
 * restores every factory band (incl. n28) instead of trusting a possibly
 * stale/narrow backup. */
#define NR_FULL_SET "1,3,28,41,77,78,79"

static int mask_bits(const uint8_t *m, int bytes) {
    int count = 0;
    for (int i = 0; i < bytes; i++) {
        uint8_t b = m[i];
        while (b) { count += b & 1; b >>= 1; }
    }
    return count;
}

static int nr_full_bits(void) {
    uint8_t mask[64];
    return lb_bands(NR_FULL_SET, 512, mask, 64) ? mask_bits(mask, 64) : 0;
}

/* nr_band_pref exists on the reference device but only answers GET after a
 * first PUT materializes it: the first lock records a "no baseline" marker and
 * restoring removes the marker, so the modem rebuilds its default set.
 * NSAccess devices (T-Phone lock library) keep a separate nr_nsa_band_pref;
 * when readable, both items are written with the same mask so NSA camping
 * follows. */
static int nr_marker_exists(void) {
    int fd = openat(state_dir, "nr-no-original", O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return 0;
    close(fd);
    return 1;
}

static int nr_nsa_present(void) {
    uint8_t mask[64];
    return read_mask_to(LB_NR_NSA_BANDS, 64, mask, sizeof(mask), 1500) == 0;
}

/* NR-NSA restore readiness: either a backup exists, or the item is absent on
 * this device (nothing to restore), so restore can proceed either way. */
static int nsa_backup_possible(void) {
    uint8_t mask[64];
    if (restore_mask("nr-nsa-original.bin", 64, mask) == 0) return 1;
    return !nr_nsa_present();
}

static int nr_band_command(const char *bands, int restore) {
    uint8_t mask[64] = {0};
    if (restore) {
        /* Dingli NR-unlock semantics: there is no SET_UNLOCKBAND - a band mask
         * is unlocked by rewriting the FULL supported set again. The modem
         * applies nr_band_pref at baseband boot, so one write + SSR returns
         * every factory band (incl. n28). Never restore a possibly-dirty
         * backup, never delete the item: a full-set rewrite is authoritative. */
        if (!lb_bands(NR_FULL_SET, 512, mask, 64))
            return fail("Invalid default NR band set.");
        if (nr_nsa_present()) {
            if (put_file(LB_MMODE, LB_NR_NSA_BANDS, LB_MMODE, mask, 64, 0x7a, 0x7a, 0)) return 1;
        }
        if (put_file(LB_MMODE, LB_NR_BANDS, LB_MMODE, mask, 64, 0x7a, 0x7a, 0)) return 1;
        return reboot_modem();
    }
    if (!lb_bands(bands, 512, mask, 64)) return fail("Invalid band list.");
    /* Dirty-backup detection: surface a previous backup that is narrower than
     * the full set (e.g. an earlier n1+n78 lock). Unlock now rewrites the full
     * set, so that narrow backup must not be mistaken for a restore baseline. */
    {
        uint8_t prior[64];
        if (restore_mask("nr-original.bin", 64, prior) == 0) {
            int prev = mask_bits(prior, 64);
            if (prev < nr_full_bits())
                printf("Note: the previous NR backup is a %d-band mask; unlock restores the FULL set (n1,n3,n28,n41,n77,n78,n79).\n", prev);
        }
    }
    int backup = backup_mask(LB_NR_BANDS, "nr-original.bin", 64);
    if (backup == 1) return 1;
    if (backup == 2) {
        puts("Note: the original NR mask is not readable (lock item not materialised yet). Restoring will remove the lock file instead.");
        int fd = openat(state_dir, "nr-no-original",
            O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
        if (fd < 0 && errno != EEXIST)
            return fail("Cannot record the missing baseline; no band changes were made.");
        if (fd >= 0) {
            int ok = fsync(fd) == 0;
            close(fd);
            if (!ok) {
                unlinkat(state_dir, "nr-no-original", 0);
                return fail("Cannot persist the missing baseline; no band changes were made.");
            }
        }
        if (fsync(state_dir)) return fail("Cannot sync the missing baseline; no band changes were made.");
    }
    if (nr_nsa_present()) {
        /* The NSA item answered a read seconds ago, so its original value DOES
         * exist. Failing to capture it now (transient read or local write
         * error) would leave the lock with no way back -> refuse to lock
         * rather than overwrite an ensemble we cannot restore. */
        int nsa_backup = backup_mask(LB_NR_NSA_BANDS, "nr-nsa-original.bin", 64);
        if (nsa_backup == 1) return 1;   /* local persistence error already reported */
        if (nsa_backup == 2)
            return fail("Cannot capture the original NR-NSA mask. No band changes were made.");
        if (put_file(LB_MMODE, LB_NR_NSA_BANDS, LB_MMODE, mask, 64, 0x7a, 0x7a, 0)) return 1;
    }
    if (put_file(LB_MMODE, LB_NR_BANDS, LB_MMODE, mask, 64, 0x7a, 0x7a, 0)) return 1;
    return reboot_modem();
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
    while (monotonic_ms() < deadline) {
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
    int any = 0;
    while (monotonic_ms() < deadline) {
        struct pollfd p = { .fd = fd, .events = POLLIN };
        int r;
        do { r = poll(&p, 1, 200); } while (r < 0 && errno == EINTR);
        if (r > 0 && (p.revents & POLLIN)) {
            int nr = (int)read(fd, buf, sizeof(buf));
            if (nr > 0) { fwrite(buf, 1, (size_t)nr, stdout); any = 1; }
        } else if (r == 0 && any) {
            break;
        }
    }
    close(fd);
    if (!any) return fail("AT response not found on this port.");
    return 0;
}

static int execute(int argc, char **argv) {
    const char *cmd = argv[0];
    if (!strcmp(cmd, "probe") && argc <= 3) {
        uint8_t mask[64];
        puts("RESULT ready");
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
        printf("BACKUP lte %d\n", !restore_mask("lte-original.bin", 8, mask) ||
            !read_mask(LB_LTE_BANDS, 8, mask));
        printf("BACKUP nr %d\n", !restore_mask("nr-original.bin", 64, mask) || nr_marker_exists());
        printf("BACKUP nr_nsa %d\n", !nsa_backup_possible());
        printf("FULLSET nr %d\n", nr_full_bits());
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
        return reboot_modem();
    }
    uint8_t data[244] = {0};
    const char *file = NULL, *sync_path = nr ? LB_NR_DIR "/" : LB_LTE_DIR;
    int size = 0;
    uint8_t m0 = 0x7f, m1 = 0x7f;
    uint32_t frequency, pci, band, scs;
    if (!strcmp(operation, "freq") && (nr ? (argc == 3 || argc == 4) : argc == 2)) {
        if (!lb_number(argv[1], nr ? 3279165 : 262143, &frequency)) return fail("Invalid frequency.");
        if (nr) {
            int enc = scs_encoding(argc == 4 ? argv[3] : NULL);
            uint16_t scs_value;
            if (enc < 0) return fail("SCS encoding must be index, pow2 or enum.");
            if (!frequency || !lb_number(argv[2], 240, &scs) || lb_scs_enc(scs, enc, &scs_value) < 0)
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
        /* Item size is model-coupled: 164B = 20 slots, 244B = 30 slots, same
         * NV item (cell_lock_list). Pioneer exposes them as two separate
         * commands; here the 3rd argument pins it explicitly and the default
         * keeps the historical auto rule. */
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
        int enc = scs_encoding(argc == 6 ? argv[5] : NULL);
        uint16_t scs_value;
        if (enc < 0) return fail("SCS encoding must be index, pow2 or enum.");
        if (!lb_number(argv[1], 512, &band) || !band ||
            !lb_number(argv[2], 3279165, &frequency) || !frequency ||
            !lb_number(argv[4], 240, &scs) || lb_scs_enc(scs, enc, &scs_value) < 0)
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
    return reboot_modem();
}

int main(int argc, char **argv) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    signal(SIGPIPE, SIG_IGN);
    alarm(100);
    if (getuid() != 0) return fail("Root authorization is required.");
    if (argc < 4 || strcmp(argv[1], "--state-dir")) return fail("Missing private state directory.");
    state_dir = open(argv[2], O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (state_dir < 0) return fail("Cannot open the private backup directory.");
    int lock = openat(state_dir, "operation.lock", O_RDWR | O_CREAT | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (lock < 0 || flock(lock, LOCK_EX | LOCK_NB)) return fail("Another lock operation is in progress.");
    if (connect_router()) return 2;
    int result = execute(argc - 3, argv + 3);
    close(router); close(lock); close(state_dir);
    return result;
}
