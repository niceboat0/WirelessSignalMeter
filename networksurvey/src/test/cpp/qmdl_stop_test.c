#include <stddef.h>
#include <stdint.h>
#include "../../main/cpp/diag_queue.h"

enum { LOCK = 1, UNLOCK, WRITE, CLOSE, ACK, MASK, OPEN };
static unsigned trace;
static int g_mu, errno, open_error, write_mode, write_calls;
static uint8_t file_bytes[64];
static size_t file_size;
typedef long long ssize_t;
typedef long long off_t;
struct stat { int st_mode; off_t st_size; };
#define S_ISREG(mode) ((mode) == 1)
#define O_WRONLY 1
#define O_CLOEXEC 2
#define O_NOFOLLOW 4
#define O_NONBLOCK 8
#define EINVAL 22
#define EIO 5
#define EINTR 4

static void step(unsigned event) { trace = (trace << 3) | event; }
static void pthread_mutex_lock(int *ignored) { step(LOCK); }
static void pthread_mutex_unlock(int *ignored) { step(UNLOCK); }
static int open(const char *path, int flags) {
    step(OPEN);
    if (open_error) { errno = open_error; return -1; }
    return 5;
}
static int fstat(int fd, struct stat *info) {
    info->st_mode = 1; info->st_size = file_size; return 0;
}
static ssize_t write(int fd, const uint8_t *data, size_t size) {
    step(WRITE);
    write_calls++;
    if (write_mode && write_calls == 1) { errno = EINTR; return -1; }
    if (write_mode && size > 4) size = 4;
    if (write_mode == 2 && write_calls == 3) { errno = 28; return -1; }
    if (size > sizeof(file_bytes) - file_size) return 0;
    for (size_t i = 0; i < size; i++) file_bytes[file_size++] = data[i];
    return (ssize_t)size;
}
static int ftruncate(int fd, off_t size) { file_size = (size_t)size; return 0; }
static int close(int fd) { step(CLOSE); return 0; }
static void qmdl_status_locked(const char *state) { step(ACK); }
static void qmdl_request_mask(int enabled) { step(MASK); }

#include "qmdl_stop_under_test.inc"

static struct diag_queue g_dq, g_dq_lo;
static int g_dq_mu, g_dq_cv, decoded, queued, queued_high, queued_low, g_dq_started = 1;
/* The freestanding Windows harness has no system headers for stdatomic.h. */
typedef _Atomic(unsigned long) atomic_ulong;
#define memory_order_relaxed __ATOMIC_RELAXED
#define atomic_fetch_add_explicit __c11_atomic_fetch_add
static atomic_ulong g_dci_callbacks, g_dci_bad_frames;
static uint16_t seen_codes[3];
static uint16_t rd16(const uint8_t *buf) { return buf[0] | ((uint16_t)buf[1] << 8); }
static void handle_record(const uint8_t *data, int size) { decoded++; }
int diag_q_push(struct diag_queue *queue, const uint8_t *data, uint16_t size) {
    if (queued < 3) seen_codes[queued] = rd16(data + 2);
    queued++;
    if (queue == &g_dq) queued_high++;
    if (queue == &g_dq_lo) queued_low++;
    return 1;
}
static void pthread_cond_signal(int *ignored) {}

#include "diag_priority_under_test.inc"
#include "diag_live_under_test.inc"

#define DCI_OK 1001
static int g_client = 7, g_stop, cfg_calls, cfg_reject_batch;
static int p_logcfg(int client, int enabled, uint16_t *codes, int count) {
    cfg_calls++;
    if (client != 7 || !enabled || !count) return 1008;
    return cfg_reject_batch && (count > 1 || codes[0] == 0xB195) ? 1008 : DCI_OK;
}
static void record_subscriptions(const uint16_t *codes, int count, int result) {}
static void elog(const char *fmt, ...) {}

#include "diag_live_mask_under_test.inc"

__declspec(dllexport) int test_diag_live_without_recording(void) {
    static uint8_t packets[36] = {
        12, 0, 0xc0, 0xb0, 0, 0, 0, 0, 0, 0, 0, 0,
        12, 0, 0x0a, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0,
        12, 0, 0x93, 0xb1, 0, 0, 0, 0, 0, 0, 0, 0,
    };
    if (g_qmdl_fd != -1) return __LINE__;
    on_log(packets, sizeof(packets));
    if (queued != 3 || decoded != 0) return __LINE__;
    if (seen_codes[0] != 0xb0c0 || seen_codes[1] != 0xb80a || seen_codes[2] != 0xb193) return __LINE__;
    if (g_qmdl_fd != -1 || file_size || write_calls || g_qmdl_packets || g_qmdl_pending_bytes) return __LINE__;
    on_log(packets, 11);
    on_log(packets, 13);
    if (g_dci_callbacks != 3 || g_dci_bad_frames != 2 || queued != 4) return __LINE__;
    g_dq_started = 0;
    on_log(packets, sizeof(packets));
    if (decoded != 3 || queued != 4 || g_dci_callbacks != 4 || g_dci_bad_frames != 2) return __LINE__;

    trace = 0;
    return 0;
}

__declspec(dllexport) int test_diag_live_masks(void) {
    uint16_t base[] = {
        0xB0C0, 0xB0C1, 0xB0C2, 0xB0EC, 0xB0ED, 0xB17F, 0xB193, 0xB195,
        0xB80A, 0xB80B, 0xB821, 0xB822, 0xB823, 0xB825, 0xB97F
    };
    if (N_LIVE_BASE != sizeof(base) / sizeof(base[0]) || N_DEFAULT <= N_LIVE_BASE) return __LINE__;
    for (int i = 0; i < N_DEFAULT; i++) {
        if (i < N_LIVE_BASE && k_default_codes[i].code != base[i]) return __LINE__;
        for (int j = 0; j < i; j++) if (k_default_codes[i].code == k_default_codes[j].code) return __LINE__;
        for (int j = 0; j < N_QMDL_CODES; j++) if (k_default_codes[i].code == k_qmdl_codes[j]) return __LINE__;
    }
    const unsigned all = LIVE_MASK_ADD_DETAILS | LIVE_MASK_ADD_OPTIONAL;
    if (live_mask_actions(9999, 0, 0) != LIVE_MASK_NONE ||
        live_mask_actions(10000, 0, 0) != LIVE_MASK_ADD_DETAILS ||
        live_mask_actions(15000, 0, LIVE_MASK_ADD_DETAILS) != LIVE_MASK_NONE ||
        live_mask_actions(200, 1, 0) != all ||
        live_mask_actions(15000, 1, LIVE_MASK_ADD_DETAILS) != LIVE_MASK_ADD_OPTIONAL ||
        live_mask_actions(15000, 1, all) != LIVE_MASK_NONE) return __LINE__;

    struct dci_mask_result status;
    if (dci_enable_logs(base, N_LIVE_BASE, &status) != N_LIVE_BASE ||
        status.count != N_LIVE_BASE || status.batch_rc != DCI_OK || status.accepted != N_LIVE_BASE ||
        cfg_calls != 1) return __LINE__;
    cfg_reject_batch = 1;
    if (dci_enable_logs(base, N_LIVE_BASE, &status) != N_LIVE_BASE - 1 ||
        status.count != N_LIVE_BASE || status.batch_rc != 1008 || status.accepted != N_LIVE_BASE - 1 ||
        cfg_calls != N_LIVE_BASE + 2) return __LINE__;
    trace = 0;
    return 0;
}

__declspec(dllexport) int test_diag_live_priority(void) {
    static uint8_t packets[36] = {
        12, 0, 0x88, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0,
        12, 0, 0x0a, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0,
        12, 0, 0x7f, 0xb9, 0, 0, 0, 0, 0, 0, 0, 0,
    };
    int previous_high = queued_high, previous_low = queued_low, previous_decoded = decoded;
    g_dq_started = 1;
    on_log(packets, sizeof(packets));
    if (queued_high != previous_high + 2 || queued_low != previous_low + 1 ||
        decoded != previous_decoded) return __LINE__;
    if (g_qmdl_fd != -1 || file_size || write_calls || g_qmdl_packets || g_qmdl_pending_bytes) return __LINE__;
    g_dq_started = 0;
    trace = 0;
    return 0;
}

__declspec(dllexport) int test_qmdl_stop(void) {
    static const uint8_t record[] = { 12, 0, 0x7d, 0x7e, 0, 1, 2, 3, 4, 5, 6, 7 };
    static const uint8_t expected_frame[] = {
        0x10, 0, 12, 0, 12, 0, 0x7d, 0x5d, 0x7d, 0x5e, 0, 1, 2, 3, 4, 5, 6, 7, 5, 0x13, 0x7e
    };
    qmdl_start("active.qmdl");
    if (trace != ((LOCK << 12) | (OPEN << 9) | (ACK << 6) | (UNLOCK << 3) | MASK) || g_qmdl_fd != 5)
        return __LINE__;
    qmdl_write_locked(record, sizeof(record));
    if (g_qmdl_packets != 1 || file_size != 0) return __LINE__;
    trace = 0;
    qmdl_stop(1);
    const unsigned expected = (LOCK << 21) | (UNLOCK << 18) | (WRITE << 15) | (CLOSE << 12) |
                              (LOCK << 9) | (ACK << 6) | (UNLOCK << 3) | MASK;
    if (trace != expected || g_qmdl_fd != -1 || g_qmdl_packets != 1 || file_size != sizeof(expected_frame) ||
        g_qmdl_bytes != sizeof(expected_frame)) return __LINE__;
    for (size_t i = 0; i < file_size; i++) if (file_bytes[i] != expected_frame[i]) return __LINE__;
    trace = 0;
    qmdl_stop(1);
    if (trace != ((LOCK << 12) | (UNLOCK << 9) | (LOCK << 6) | (ACK << 3) | UNLOCK))
        return __LINE__;
    file_size = 0;
    qmdl_start("active.qmdl");
    write_mode = 1;
    write_calls = 0;
    qmdl_write_locked(record, sizeof(record));
    qmdl_stop(0);
    if (g_qmdl_error || g_qmdl_packets != 1 || file_size != sizeof(expected_frame) || write_calls <= 2)
        return __LINE__;

    file_size = 0;
    write_mode = 0;
    qmdl_start("active.qmdl");
    qmdl_write_locked(record, sizeof(record));
    qmdl_flush(g_qmdl_fd);
    qmdl_write_locked(record, sizeof(record));
    write_mode = 2;
    write_calls = 0;
    qmdl_stop(1);
    if (g_qmdl_error != 28 || g_qmdl_packets != 1 || g_qmdl_bytes != sizeof(expected_frame) ||
        file_size != sizeof(expected_frame)) return __LINE__;
    trace = 0;
    open_error = 13;
    qmdl_start("active.qmdl");
    if (trace != ((LOCK << 9) | (OPEN << 6) | (ACK << 3) | UNLOCK) || g_qmdl_fd != -1 || g_qmdl_error != 13)
        return __LINE__;
    open_error = 0;
    trace = 0;
    qmdl_start("active.qmdl");
    if (g_qmdl_error != EINVAL || g_qmdl_fd != -1 || file_size != sizeof(expected_frame)) return __LINE__;
    return 0;
}
