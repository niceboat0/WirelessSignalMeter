#include <stddef.h>
#include <stdint.h>
#include <stdarg.h>
#include "../../main/cpp/diag_queue.c"

int snprintf(char *, size_t, const char *, ...);
int _vsnprintf(char *, size_t, const char *, va_list);
#define vsnprintf _vsnprintf
#define CHECK(condition) do { if (!(condition)) return __LINE__; } while (0)
int _fltused;

static int64_t clock_ms;
static int64_t pkt_now_ms(void) { return clock_ms; }
static int emitted;
static void emit_locked(const char *line) { (void)line; emitted++; }
static void layout_seen(uint16_t code, uint32_t version, const uint8_t *data, int size) {
    (void)code; (void)version; (void)data; (void)size;
}
static void layout_variant(uint16_t code, uint32_t version, int size, int records) {
    (void)code; (void)version; (void)size; (void)records;
}
static void unknown_layout(uint16_t code, uint32_t version, const uint8_t *data, int size, const char *why) {
    (void)code; (void)version; (void)data; (void)size; (void)why;
}

#include "diag_epoch_util_under_test.inc"

static struct { int pci; long arfcn; } g_nr;
#include "diag_epoch_pdsch_under_test.inc"

/* The worker's reset operates on these other windows only through memset/timers. */
static uint64_t g_nr_layer[12], g_dl[8], g_ul[8], g_us[8];
static int64_t g_csf_t, g_dl_t, g_dl_last, g_ul_t, g_us_t;
static int g_csf_n, g_csf_cqi_sum, g_csf_reports, g_csf_skipped, g_csf_cc;
static int g_csf_cqi_min, g_csf_cqi_max, g_csf_rank[5];
static int g_dl_bad, g_dl_other_rnti, g_us_bad;
static struct diag_queue g_dq, g_dq_lo;
static int g_stop, g_mu, g_dq_mu, g_dq_cv;
static unsigned parsed;
static uint16_t parsed_codes[64];
static int parsed_pci[64];
static uint64_t parsed_time[64];

static void pthread_mutex_lock(int *lock) { (void)lock; }
static void pthread_mutex_unlock(int *lock) { (void)lock; }
static void pthread_cond_wait(int *condition, int *lock) {
    (void)condition; (void)lock; g_stop = 1;
}

static void handle_record(const uint8_t *record, int size) {
    uint16_t code = rd16(record + 2);
    clock_ms = (int64_t)rd64(record + 4);
    if (code == 0xB823) {
        g_nr.pci = rd16(record + 12);
        g_nr.arfcn = rd32(record + 14);
    } else if (code == 0xB888) {
        parse_nr_b888(record + 12, size - 12);
    }
    if (parsed < 64) {
        parsed_codes[parsed] = code;
        parsed_pci[parsed] = g_nr.pci;
        parsed_time[parsed] = (uint64_t)clock_ms;
    }
    parsed++;
}

#include "diag_epoch_worker_under_test.inc"

static void put_le(uint8_t *data, uint64_t value, int bytes) {
    for (int i = 0; i < bytes; i++) data[i] = (uint8_t)(value >> (8 * i));
}

static int enqueue(uint16_t code, uint64_t time, int pci, long arfcn) {
    uint8_t record[18] = {0};
    put_le(record, sizeof(record), 2);
    put_le(record + 2, code, 2);
    put_le(record + 4, time, 8);
    put_le(record + 12, (uint64_t)pci, 2);
    put_le(record + 14, (uint64_t)arfcn, 4);
    return diag_q_push(diag_code_priority(code) == DIAG_PRIO_HIGH ? &g_dq : &g_dq_lo,
                       record, sizeof(record));
}

static int enqueue_pdsch(uint64_t time, int scale) {
    uint8_t record[12 + 20 + 72] = {0};
    put_le(record, sizeof(record), 2);
    put_le(record + 2, 0xB888, 2);
    put_le(record + 4, time, 8);
    uint8_t *body = record + 12;
    put_le(body, 0x30001, 4);
    body[7] = 1;
    uint8_t *row = body + 20;
    put_le(row, 0x10000, 4);
    put_le(row + 4, (uint64_t)(1000 * scale), 4);
    put_le(row + 8, (uint64_t)(100 * scale), 4);
    put_le(row + 12, (uint64_t)(90 * scale), 4);
    put_le(row + 16, (uint64_t)(10 * scale), 4);
    put_le(row + 20, (uint64_t)(5 * scale), 4);
    put_le(row + 32, (uint64_t)(1000 * scale), 8);
    put_le(row + 40, (uint64_t)(200 * scale), 8);
    put_le(row + 48, (uint64_t)(1200 * scale), 8);
    return diag_q_push(&g_dq_lo, record, sizeof(record));
}

static void reset(void) {
    diag_q_init(&g_dq);
    diag_q_init(&g_dq_lo);
    g_nr.pci = 10;
    g_nr.arfcn = 627264;
    nr_statistics_reset();
    g_stop = 1;
    emitted = 0;
    parsed = 0;
}

__declspec(dllexport) int test_diag_epoch_worker(void) {
    reset();
    CHECK(enqueue(0xB884, 100, 0, 0));
    CHECK(enqueue(0xB823, 200, 20, 635000));
    CHECK(enqueue(0xB884, 201, 0, 0));
    decode_worker(NULL);
    CHECK(parsed == 2 && parsed_codes[0] == 0xB823 && parsed_codes[1] == 0xB884);
    CHECK(parsed_pci[1] == 20 && parsed_time[1] == 201);
    CHECK(g_dq_lo.stats.epoch_dropped == 1 && g_dq_lo.stats.dropped == 1);

    reset();
    CHECK(enqueue(0xB884, 100, 0, 0));
    CHECK(enqueue(0xB823, 200, 10, 627264));
    decode_worker(NULL);
    CHECK(parsed == 2 && parsed_time[1] == 100 && g_dq_lo.stats.epoch_dropped == 0);

    reset();
    CHECK(enqueue(0xB884, 0, 0, 0));
    CHECK(enqueue(0xB884, 200, 0, 0));
    CHECK(enqueue(0xB823, 200, 20, 635000));
    CHECK(enqueue(0xB884, 201, 0, 0));
    decode_worker(NULL);
    CHECK(parsed == 2 && parsed_time[1] == 201 && g_dq_lo.stats.epoch_dropped == 2);

    reset();
    CHECK(enqueue(0xB823, 0, 20, 635000));
    CHECK(enqueue(0xB884, 300, 0, 0));
    decode_worker(NULL);
    CHECK(parsed == 1 && g_dq_lo.stats.epoch_dropped == 1);
    reset();
    CHECK(enqueue(0xB823, 0, 20, 635000));
    CHECK(enqueue(0xB823, 400, 20, 635000));
    CHECK(enqueue(0xB884, 399, 0, 0));
    CHECK(enqueue(0xB884, 401, 0, 0));
    decode_worker(NULL);
    CHECK(parsed == 3 && parsed_time[2] == 401 && g_dq_lo.stats.epoch_dropped == 1);
    /* A new collector worker must not inherit the preceding timestamp boundary. */
    CHECK(enqueue(0xB884, 1, 0, 0));
    decode_worker(NULL);
    CHECK(parsed == 4 && parsed_time[3] == 1);

    reset();
    for (int i = 0; i < DIAG_Q_SLOTS; i++) CHECK(enqueue(0xB884, (uint64_t)(100 + i), 0, 0));
    CHECK(!enqueue(0xB884, 132, 0, 0));
    struct diag_q_item item;
    for (int i = 0; i < 16; i++) CHECK(diag_q_pop(&g_dq_lo, &item));
    for (int i = 0; i < 16; i++) CHECK(enqueue(0xB884, (uint64_t)(201 + i), 0, 0));
    CHECK(enqueue(0xB823, 200, 20, 635000));
    decode_worker(NULL);
    CHECK(parsed == 17 && parsed_codes[0] == 0xB823 && parsed_time[1] == 201 && parsed_time[16] == 216);
    CHECK(g_dq_lo.stats.epoch_dropped == 16 && g_dq_lo.stats.dropped == 17);
    CHECK(g_dq_lo.stats.dropped_bytes == 17 * 18 && diag_q_empty(&g_dq_lo));

    /* A -> B -> A must not reuse A's pre-handover cumulative baseline. */
    reset();
    CHECK(enqueue_pdsch(1000, 1));
    decode_worker(NULL);
    CHECK(g_pd_t == 1000 && g_pd_base[0].pbytes == 1000 && emitted == 0);
    CHECK(enqueue(0xB823, 1100, 20, 635000));
    CHECK(enqueue(0xB823, 1200, 10, 627264));
    CHECK(enqueue_pdsch(2000, 3));
    decode_worker(NULL);
    CHECK(g_pd_t == 2000 && g_pd_base[0].pbytes == 3000 && emitted == 0);
    CHECK(enqueue_pdsch(3000, 4));
    decode_worker(NULL);
    CHECK(emitted == 1 && g_pd_base[0].pbytes == 4000);
    return 0;
}
