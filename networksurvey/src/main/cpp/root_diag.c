/*
 * Qualcomm DCI collector for WirelessSignalMeter.
 * Adapted from niceboat0/diag-research diagcap/native/diagcap.c at dd7d898b.
 * The Android app owns the process and reads decoded records from stdout.
 */
#define _GNU_SOURCE
#include <stdio.h>
#include <math.h>
#include <stdlib.h>
#include <stdint.h>
#include <stdatomic.h>
#include <stdarg.h>
#include <string.h>
#include <strings.h>
#include <ctype.h>
#include <errno.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <signal.h>
#include <time.h>
#include <dlfcn.h>
#include <poll.h>
#include <pthread.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <sys/types.h>
#ifdef __ANDROID__
#include <android/log.h>
#endif

/* ------------------------------------------------------------------ */
/* small utils                                                         */
/* ------------------------------------------------------------------ */
static void elog(const char *fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm; localtime_r(&ts.tv_sec, &tm);
    fprintf(stderr, "wireless-root-diag: %02d:%02d:%02d.%03ld %s\n", tm.tm_hour, tm.tm_min, tm.tm_sec,
            ts.tv_nsec / 1000000, buf);
    fflush(stderr);
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "diagcap", "%s", buf);
#endif
}

static int64_t now_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
/* 2.3: live DCI delivers log packets in bursts (run9: 0-480 ms, median ~180 ms behind the modem timestamp), so events
   parsed from a packet used the late arrival time while logcat SIP lines are near-instant. Live packet events now use
   modem_ts - clock_offset, where clock_offset = running max(modem_ts - arrival) (= the true offset seen at the lowest latency;
   run9: modem clock 521 ms ahead of the AP). Never later than arrival; reset on a clock jump. */
static int g_in_rec, g_clk_have; static int64_t g_clk_off, g_rec_modem_ms;
static int64_t pkt_now_ms(void) {
    int64_t now = now_ms();
    if (g_in_rec && g_clk_have && g_rec_modem_ms) { int64_t t = g_rec_modem_ms - g_clk_off; return t < now ? t : now; }
    return now;
}
static void clk_feed(int64_t modem_epoch_ms) {
    int64_t off = modem_epoch_ms - now_ms();
    g_rec_modem_ms = 0;
    if (off > 86400000LL || off < -86400000LL) return;
    if (!g_clk_have || off > g_clk_off || off < g_clk_off - 3000) { g_clk_off = off; g_clk_have = 1; }
    g_rec_modem_ms = modem_epoch_ms;
}
static int64_t mono_ms(void) {
    struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}
static inline uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static inline uint32_t rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
static inline uint64_t rd64(const uint8_t *p) { return (uint64_t)rd32(p) | ((uint64_t)rd32(p + 4) << 32); }
/* little-endian bit field: bits [start, start+n) of the LE integer formed by buf */
static uint32_t lebits(const uint8_t *buf, int start, int n) {
    uint32_t v = 0;
    for (int i = 0; i < n; i++) {
        int b = start + i;
        if ((buf[b >> 3] >> (b & 7)) & 1) v |= (1u << i);
    }
    return v;
}
/* MSB-first bit reader for UPER message type */
static int msbbits(const uint8_t *buf, int len, int start, int n) {
    int v = 0;
    for (int i = 0; i < n; i++) {
        int b = start + i;
        if ((b >> 3) >= len) return -1;
        v = (v << 1) | ((buf[b >> 3] >> (7 - (b & 7))) & 1);
    }
    return v;
}

/* ------------------------------------------------------------------ */
/* configuration                                                       */
/* ------------------------------------------------------------------ */
struct code_info { uint16_t code; const char *name; };
/* First 15 codes match the DiagCap mask verified on SM-S9370. */
#define N_LIVE_BASE 15
static const struct code_info k_default_codes[] = {
    { 0xB0C0, "LTE RRC OTA" },
    { 0xB0C1, "LTE RRC MIB" },
    { 0xB0C2, "LTE RRC Serving Cell Info" },
    { 0xB0EC, "LTE NAS EMM Plain OTA Incoming" },
    { 0xB0ED, "LTE NAS EMM Plain OTA Outgoing" },
    { 0xB17F, "LTE ML1 Serving Cell Meas and Eval" },
    { 0xB193, "LTE ML1 Serving Cell Meas Response" },
    { 0xB195, "LTE ML1 Connected Neighbor Meas" },
    { 0xB80A, "NR NAS 5GMM Plain OTA Incoming" },
    { 0xB80B, "NR NAS 5GMM Plain OTA Outgoing" },
    { 0xB821, "NR RRC OTA" },
    { 0xB822, "NR RRC MIB Info" },
    { 0xB823, "NR RRC Serving Cell Info" },
    { 0xB825, "NR RRC Configuration Info" },
    { 0xB97F, "NR ML1 Meas Database Update" },
    /* Extended live logs. */
    { 0xB0E2, "LTE NAS ESM Plain OTA Incoming" },
    { 0xB0E3, "LTE NAS ESM Plain OTA Outgoing" },
    { 0xB179, "LTE ML1 Intra-frequency Neighbor Meas" },
    { 0xB180, "LTE ML1 Neighbor Measurements" },
    { 0xB192, "LTE ML1 Neighbor Cell Meas" },
    { 0xB139, "LTE ML1 PUSCH Tx Report" },
    { 0xB13C, "LTE ML1 PUCCH Tx Report" },
    { 0xB14D, "LTE PHY PUCCH CSI Feedback" },
    { 0xB14E, "LTE PHY PUSCH CSI Feedback" },
    { 0xB16D, "LTE ML1 GM Tx Report" },
    { 0xB16E, "LTE ML1 PUSCH Power Control" },
    { 0xB16F, "LTE ML1 PUCCH Power Control" },
    { 0xB173, "LTE ML1 PDSCH Stat Indication" },
    /* 1.10: independent LTE rank / TX-RX antennas and per-slot carrier aggregation */
    { 0xB126, "LTE ML1 PDSCH Demapper Config" },
    { 0xB184, "LTE ML1 CA Metrics" },
    { 0xB087, "LTE RLC DL Statistics" },
    { 0xB097, "LTE RLC UL Statistics" },
    { 0xB0A4, "LTE PDCP DL Statistics" },
    { 0xB0B4, "LTE PDCP UL Statistics" },
    { 0xB063, "LTE MAC DL Transport Block" },
    { 0xB064, "LTE MAC UL Transport Block" },
    { 0xB800, "NR NAS 5GSM Plain OTA Incoming" },
    { 0xB801, "NR NAS 5GSM Plain OTA Outgoing" },
    /* 1.3: NR SINR / PDSCH log codes */
    { 0xB975, "NR ML1 Serving Cell Beam Mgmt" },
    { 0xB8D8, "NR LL1 LOG Serving SNR" },
    { 0xB8DD, "NR LL1 FW Serving FTL" },
    { 0xB96A, "NR ML1 Searcher FW Cell Meas Confirm" },
    { 0xB888, "NR MAC PDSCH Stats" },
    { 0xB881, "NR MAC UL TB Stats" },
    { 0xB842, "NR PDCP DL RB Stats" },
    { 0xB860, "NR PDCP UL Stats" },
    { 0xB84D, "NR RLC DL Stats" },
    { 0xB868, "NR RLC UL Stats" },
    { 0xB8A7, "NR LL1 FW CSF Report" },
    /* 1.6: MCS / layers / RB (PDSCH status) and UL power control; raw-saved + layout_seen until pinned on real packets */
    { 0xB887, "NR MAC PDSCH Status" },
    { 0xB884, "NR MAC UL Physical Channel Power Control" },
    /* 1.9: UL schedule (throughput / BLER / MCS / RB / layers), RACH (raw), IMS / VoLTE / VoNR */
    { 0xB883, "NR MAC UL Physical Channel Schedule Report" },
    { 0xB889, "NR MAC RACH Trigger" },
    { 0xB88A, "NR MAC RACH Attempt" },
    { 0x156E, "IMS SIP Message" },
    { 0x1830, "IMS VoLTE Session Setup" },
    { 0x1831, "IMS VoLTE Session End" },
    { 0x1832, "IMS Registration" },
    { 0x1568, "IMS RTP SN and Payload" },
    { 0x1569, "IMS RTP Packet Loss" },
    { 0x156A, "IMS RTCP" },
    { 0x17F2, "IMS Voice Call Statistics" },
};
#define N_DEFAULT ((int)(sizeof(k_default_codes) / sizeof(k_default_codes[0])))
#define MAX_CODES 128

/* Extra baseband measurements, enabled only during QMDL recording. */
static uint16_t k_qmdl_codes[] = {
    0xB116,
    0xB16C,          /* LTE DCI */
    0xB880, 0xB885, 0xB886,  /* NR MAC TB reports and DCI */
    0xB950, 0xB951,          /* NR ML1 DL common/dedicated configuration */
};
#define N_QMDL_CODES ((int)(sizeof(k_qmdl_codes) / sizeof(k_qmdl_codes[0])))

/* Optional live subscription; independent of the QMDL recording mask. */
#define NR_CA_STATE_CODE 0xB9A7
static uint16_t g_nr_ca_state_code = NR_CA_STATE_CODE;
static int g_nr_ca_subscribe_attempted, g_nr_ca_subscribe_rc;

/* LL1 neighbor tracking/CER and NR measurement candidates. Kept out of the required mask. */
static uint16_t k_neighbor_probe_codes[] = { 0xB119, 0xB123, 0xB96D, 0xB96E, 0xB96F, 0xB992 };
#define N_NEIGHBOR_PROBES ((int)(sizeof(k_neighbor_probe_codes) / sizeof(k_neighbor_probe_codes[0])))

static uint16_t g_codes[N_DEFAULT];
static const int g_proc = 0; /* DIAG_PROC_MSM */

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static volatile sig_atomic_t g_stop;

static void on_sig(int signo) {
    (void)signo;
    g_stop = 1;
}

#include "diag_queue.c"
#include "ims_logcat.c"

/* Two decode queues split by log-code priority. High-rate periodic statistics
   go to the low queue and absorb drops first. Per-TB/power/CQI samples are not
   replayed, so their drops still mean missing coverage. RRC/NAS/measurement DB/
   serving) goes to the high queue so those records are never displaced by a
   counter flood. Both queues share g_dq_mu/g_dq_cv; the worker drains high
   before low. */
static struct diag_queue g_dq;
static struct diag_queue g_dq_lo;
static pthread_mutex_t g_dq_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_dq_cv = PTHREAD_COND_INITIALIZER;
static pthread_t g_dq_th;
static int g_dq_started;

static pid_t g_lc_pid = -1;
static pthread_t g_lc_th;
static int g_lc_started, g_lc_fmt, g_lc_alive;
static unsigned long g_lc_read, g_lc_lines, g_lc_sip, g_lc_rcs;
static int64_t g_lc_start;
static int g_diag_ims_pkts;
static void handle_record(const uint8_t *rec, int len);

static void emit_locked(const char *json) {
    if (!g_stop && fprintf(stdout, "%s\n", json) < 0) g_stop = 1;
}

struct code_stat { uint16_t code; unsigned long count; unsigned long bytes; uint32_t warned_vers[8]; int nwarned; };
static struct code_stat g_stats[MAX_CODES];
static int g_nstats;
static unsigned long g_total_pkts;
static atomic_ulong g_dci_callbacks, g_dci_bad_frames;
static int64_t g_last_pkt_mono;

struct neighbor_stat {
    uint16_t code;
    int probe, serving_reference, subscription_attempted, subscription_rc, last_len;
    int64_t first_seen, last_seen, last_sample;
    unsigned long received, layout_errors, decoded_blocks, cells, rsrp, rsrq, sinr, invalid_sinr;
    unsigned long samples;
};
static struct neighbor_stat g_neighbor_stats[] = {
    { .code = 0xB179 }, { .code = 0xB180 }, { .code = 0xB192 }, { .code = 0xB195 },
    { .code = 0xB96A }, { .code = 0xB97F },
    { .code = 0xB193, .serving_reference = 2 }, { .code = 0xB8D8, .serving_reference = 1 },
    { .code = 0xB975, .serving_reference = 1 }, { .code = 0xB8DD, .serving_reference = 1 },
    { .code = 0xB119, .probe = 1 }, { .code = 0xB123, .probe = 1 },
    { .code = 0xB96D, .probe = 1 }, { .code = 0xB96E, .probe = 1 }, { .code = 0xB96F, .probe = 1 },
    { .code = 0xB992, .probe = 1 },
};
static struct neighbor_stat *neighbor_stat_for(uint16_t code) {
    for (unsigned i = 0; i < sizeof(g_neighbor_stats) / sizeof(g_neighbor_stats[0]); i++) {
        if (g_neighbor_stats[i].code == code) return &g_neighbor_stats[i];
    }
    return NULL;
}

static void neighbor_decoded(uint16_t code, int cells, int rsrp, int rsrq, int sinr, int invalid_sinr) {
    struct neighbor_stat *s = neighbor_stat_for(code);
    if (!s) return;
    s->decoded_blocks++;
    s->cells += cells;
    s->rsrp += rsrp;
    s->rsrq += rsrq;
    s->sinr += sinr;
    s->invalid_sinr += invalid_sinr;
}

static struct code_stat *stat_for(uint16_t code) {
    for (int i = 0; i < g_nstats; i++) if (g_stats[i].code == code) return &g_stats[i];
    if (g_nstats < MAX_CODES) { g_stats[g_nstats].code = code; return &g_stats[g_nstats++]; }
    return NULL;
}

#define NA_I (-99999)
#define NA_F (-9999.0)
struct serving {
    char rat[4];      /* "LTE" / "NR" */
    int pci; long arfcn; int band; double freq;
    double rsrp, rsrq, rssi, sinr;
    long tac; unsigned long long cid; int mcc, mnc, mnc_digits;
    double bw_mhz;
    int64_t t_meas, t_cell;
    int64_t t_seen;
    char src[16];
    int64_t t_sinr; char sinr_src[16]; int sinr_prio;
    /* Same B193 v66 sample as sinr when sinr_agg is max_rx. */
    int sinr_rx_mask;
    double sinr_rx[4];
    char sinr_agg[12];
};
static struct serving g_lte = {
    .rat = "LTE", .pci = NA_I, .arfcn = -1, .rsrp = NA_F, .rsrq = NA_F, .rssi = NA_F, .sinr = NA_F,
    .tac = -1, .mcc = -1, .mnc = -1, .bw_mhz = -1,
};
static struct serving g_nr = {
    .rat = "NR", .pci = NA_I, .arfcn = -1, .rsrp = NA_F, .rsrq = NA_F, .rssi = NA_F, .sinr = NA_F,
    .tac = -1, .mcc = -1, .mnc = -1, .bw_mhz = -1,
};

/* JSON helpers */
/* Keep the maximum collector JSON line within the Android reader's 8 KiB limit. */
struct jb { char b[8192]; int n; };
static void jb_init(struct jb *j, const char *type) {
    long long t = (long long)pkt_now_ms();
    j->n = snprintf(j->b, sizeof(j->b), "{\"t\":%lld,\"type\":\"%s\"", t, type);
}
static void jb_add(struct jb *j, const char *fmt, ...) {
    if (j->n >= (int)sizeof(j->b) - 2) return;
    va_list ap; va_start(ap, fmt);
    int w = vsnprintf(j->b + j->n, sizeof(j->b) - (size_t)j->n, fmt, ap);
    va_end(ap);
    if (w > 0) j->n += w;
    if (j->n > (int)sizeof(j->b) - 2) j->n = (int)sizeof(j->b) - 2;
}
static void jb_f(struct jb *j, const char *k, double v) {
    if (v <= NA_F + 1) return;
    jb_add(j, ",\"%s\":%.2f", k, v);
}
static void jb_i(struct jb *j, const char *k, long long v) {
    if (v == NA_I) return;
    jb_add(j, ",\"%s\":%lld", k, v);
}
static void jb_emit(struct jb *j) { jb_add(j, "}"); emit_locked(j->b); }

#define QMDL_LIMIT (128LL * 1024 * 1024)
static int g_qmdl_fd = -1, g_qmdl_error, g_qmdl_limit;
static long long g_qmdl_bytes, g_qmdl_packets;
static uint8_t g_qmdl_buffer[256 * 1024];
static size_t g_qmdl_pending_bytes;
static unsigned g_qmdl_pending_packets;

static void qmdl_status_locked(const char *state) {
    struct jb j; jb_init(&j, "qmdl");
    jb_add(&j, ",\"state\":\"%s\",\"bytes\":%lld,\"packets\":%lld,\"rc\":%d",
           state, g_qmdl_bytes, g_qmdl_packets, g_qmdl_error);
    jb_emit(&j);
}

static void qmdl_escape(uint8_t *out, size_t *size, uint8_t byte) {
    if (byte == 0x7d || byte == 0x7e) {
        out[(*size)++] = 0x7d;
        byte ^= 0x20;
    }
    out[(*size)++] = byte;
}

/* DIAG_LOG_F with CRC-16/X-25 and Qualcomm's HDLC escaping. */
static size_t qmdl_frame(const uint8_t *rec, uint16_t len, uint8_t *out) {
    const uint8_t header[] = { 0x10, 0, (uint8_t)len, (uint8_t)(len >> 8) };
    uint16_t crc = 0xffff;
    size_t size = 0;
    for (size_t i = 0; i < sizeof(header) + len; i++) {
        uint8_t byte = i < sizeof(header) ? header[i] : rec[i - sizeof(header)];
        crc ^= byte;
        for (int bit = 0; bit < 8; bit++) crc = (uint16_t)((crc >> 1) ^ ((crc & 1) ? 0x8408 : 0));
        qmdl_escape(out, &size, byte);
    }
    crc = (uint16_t)~crc;
    qmdl_escape(out, &size, (uint8_t)crc);
    qmdl_escape(out, &size, (uint8_t)(crc >> 8));
    out[size++] = 0x7e;
    return size;
}

static void qmdl_flush(int fd) {
    for (size_t written = 0; written < g_qmdl_pending_bytes;) {
        ssize_t n = write(fd, g_qmdl_buffer + written, g_qmdl_pending_bytes - written);
        if (n > 0) { written += (size_t)n; continue; }
        if (n < 0 && errno == EINTR) continue;
        g_qmdl_error = n < 0 ? errno : EIO;
        g_qmdl_bytes -= (long long)g_qmdl_pending_bytes;
        g_qmdl_packets -= g_qmdl_pending_packets;
        if (written) (void)ftruncate(fd, (off_t)g_qmdl_bytes);
        break;
    }
    g_qmdl_pending_bytes = 0;
    g_qmdl_pending_packets = 0;
}

static void qmdl_write_locked(const uint8_t *rec, uint16_t len) {
    if (g_qmdl_fd < 0 || g_qmdl_limit || g_qmdl_error) return;
    size_t max_size = 2 * ((size_t)len + 6) + 1;
    if (max_size > sizeof(g_qmdl_buffer) - g_qmdl_pending_bytes) {
        qmdl_flush(g_qmdl_fd);
        if (g_qmdl_error) return;
    }
    size_t size = qmdl_frame(rec, len, g_qmdl_buffer + g_qmdl_pending_bytes);
    if (g_qmdl_bytes + (long long)size > QMDL_LIMIT) { g_qmdl_limit = 1; return; }
    g_qmdl_pending_bytes += size;
    g_qmdl_pending_packets++;
    g_qmdl_bytes += (long long)size;
    g_qmdl_packets++;
}

/* Log a representative failure per layout and retain the count of all failures. */
static void unknown_layout(uint16_t code, uint32_t ver, const uint8_t *body, int blen, const char *why) {
    struct neighbor_stat *neighbor = neighbor_stat_for(code);
    if (neighbor) neighbor->layout_errors++;
    struct code_stat *s = stat_for(code);
    if (s) {
        for (int i = 0; i < s->nwarned; i++) if (s->warned_vers[i] == ver) return;
        if (s->nwarned < 8) s->warned_vers[s->nwarned++] = ver;
    }
    /* The first 16 body bytes are enough to settle layout questions offline
       (count-byte position, header stride, version field) without exporting
       the whole payload; full frames are already preserved by QMDL capture.
       Layout failures are deduplicated per (code, ver), so this stays a
       handful of one-line records per session. */
    char head[16 * 3 + 1] = {0};
    int hn = (blen > 16) ? 16 : blen;
    if (hn < 0 || !body) hn = 0;
    static const char hex[] = "0123456789abcdef";
    for (int i = 0; i < hn; i++) {
        head[i * 3] = hex[body[i] >> 4];
        head[i * 3 + 1] = hex[body[i] & 0xf];
        head[i * 3 + 2] = (i + 1 < hn) ? ' ' : '\0';
    }
    struct jb j; jb_init(&j, "unknown_layout");
    jb_add(&j, ",\"code\":\"0x%04X\",\"ver\":\"0x%x\",\"len\":%d,\"why\":\"%s\",\"head\":\"%s\"",
           code, ver, blen, why, head);
    jb_emit(&j);
}

/* Record the version and size without exporting payload bytes. */
static void layout_seen(uint16_t code, uint32_t ver, const uint8_t *body, int blen) {
    static struct { uint16_t code; uint32_t ver; } seen[64];
    static int nseen;
    for (int i = 0; i < nseen; i++) if (seen[i].code == code && seen[i].ver == ver) return;
    if (nseen < 64) { seen[nseen].code = code; seen[nseen].ver = ver; nseen++; } else return;
    struct jb j; jb_init(&j, "layout_seen");
    jb_add(&j, ",\"code\":\"0x%04X\",\"ver\":\"0x%x\",\"len\":%d", code, ver, blen);
    (void)body;
    jb_emit(&j);
}

/* Report accepted fixed-capacity table variants once per code/version/length. */
static void layout_variant(uint16_t code, uint32_t ver, int blen, int records) {
    static struct { uint16_t code; uint32_t ver; int blen; } seen[16];
    static int nseen;
    for (int i = 0; i < nseen; i++)
        if (seen[i].code == code && seen[i].ver == ver && seen[i].blen == blen) return;
    if (nseen == 16) return;
    seen[nseen].code = code;
    seen[nseen].ver = ver;
    seen[nseen++].blen = blen;
    struct jb j; jb_init(&j, "layout_variant");
    jb_add(&j, ",\"code\":\"0x%04X\",\"ver\":\"0x%x\",\"len\":%d,\"records\":%d",
           code, ver, blen, records);
    jb_emit(&j);
}

/* The app retains a bounded sample window and exports it only when requested. */
static void emit_neighbor_sample(struct neighbor_stat *stat, const uint8_t *body, int len, int64_t now) {
    if (len <= 0 || (stat->samples && now - stat->last_sample < 2000)) return;
    enum { MAX_NEIGHBOR_SAMPLE = 3072 };
    static const char digits[] = "0123456789abcdef";
    char hex[2 * MAX_NEIGHBOR_SAMPLE + 1];
    int saved = len < MAX_NEIGHBOR_SAMPLE ? len : MAX_NEIGHBOR_SAMPLE;
    for (int i = 0; i < saved; i++) {
        hex[2 * i] = digits[body[i] >> 4];
        hex[2 * i + 1] = digits[body[i] & 15];
    }
    hex[2 * saved] = 0;
    stat->last_sample = now;
    stat->samples++;
    struct jb j; jb_init(&j, "neighbor_probe_sample");
    jb_add(&j, ",\"code\":\"0x%04X\",\"body_length\":%d,\"saved_length\":%d,\"body_hex\":\"%s\"",
           (unsigned)stat->code, len, saved, hex);
    jb_emit(&j);
}

/* --- band helpers --- */
static int lte_band(long e) {
    static const struct { long lo, hi; int b; } t[] = {
        {0,599,1},{600,1199,2},{1200,1949,3},{1950,2399,4},{2400,2649,5},{2750,3449,7},{3450,3799,8},
        {6150,6449,20},{9210,9659,28},{36000,36199,33},{36200,36349,34},{37750,38249,38},{38250,38649,39},
        {38650,39649,40},{39650,41589,41},{41590,43589,42},{43590,45589,43},{46790,54539,46}};
    for (unsigned i = 0; i < sizeof(t) / sizeof(t[0]); i++) if (e >= t[i].lo && e <= t[i].hi) return t[i].b;
    return 0;
}
static double nr_freq(long n) {
    if (n < 0) return 0;
    if (n < 600000) return n * 0.005;
    if (n < 2016667) return 3000.0 + (n - 600000) * 0.015;
    return 24250.08 + (n - 2016667) * 0.06;
}
#include "nr_band_guess.h"
static double q7(uint32_t v) {
    if (v == 0) return NA_F;
    int integer = (int)((v >> 7) & 0xff);
    double r = (double)(integer - 256) + (v & 0x7f) * 0.0078125;
    return r;
}

/* serving cell change tracking */
static void serving_update(struct serving *s, int pci, long arfcn, const char *src) {
    if (pci < 0 || pci > 1007) return;
    if (pci == 0 && arfcn == 0) return; /* 1.5: transitional RRC packets with no cell (pci 0 / arfcn 0) */
    if (s->pci != NA_I && (s->pci != pci || (arfcn >= 0 && s->arfcn >= 0 && s->arfcn != arfcn))) {
        struct jb j; jb_init(&j, "cell_change");
        jb_add(&j, ",\"rat\":\"%s\",\"from_pci\":%d,\"from_arfcn\":%ld,\"to_pci\":%d,\"to_arfcn\":%ld,\"src\":\"%s\"",
               s->rat, s->pci, s->arfcn, pci, arfcn >= 0 ? arfcn : s->arfcn, src);
        jb_emit(&j);
        s->rsrp = s->rsrq = s->rssi = s->sinr = NA_F;
        s->t_meas = s->t_sinr = s->t_cell = 0;
        s->sinr_prio = 0;
        s->sinr_rx_mask = 0;
        s->src[0] = s->sinr_src[0] = s->sinr_agg[0] = 0;
        s->band = 0;
        s->bw_mhz = s->mcc = s->mnc = -1;
        s->mnc_digits = 0;
        s->tac = -1;
        s->cid = 0;
    }
    s->pci = pci;
    s->t_seen = mono_ms();
    if (arfcn >= 0) {
        s->arfcn = arfcn;
        if (s->rat[0] == 'N') { s->freq = nr_freq(arfcn); if (!s->band) s->band = nr_band_guess(arfcn); }
        else if (!s->band) s->band = lte_band(arfcn);
    }
}

/* --- UPER message type names --- */
enum ch { CH_UNK = 0, CH_BCCH_BCH, CH_BCCH_DL_SCH, CH_MCCH, CH_PCCH, CH_DL_CCCH, CH_DL_DCCH,
          CH_UL_CCCH, CH_UL_CCCH1, CH_UL_DCCH, CH_RECONF, CH_RECONF_COMPLETE };
static const char *ch_name(int c) {
    static const char *n[] = { "?", "BCCH-BCH", "BCCH-DL-SCH", "MCCH", "PCCH", "DL-CCCH", "DL-DCCH",
                               "UL-CCCH", "UL-CCCH1", "UL-DCCH", "RRCReconfiguration(container)",
                               "RRCReconfigurationComplete(container)" };
    return (c >= 0 && c <= CH_RECONF_COMPLETE) ? n[c] : "?";
}
static const char *lte_dl_dcch[16] = { "csfbParametersResponseCDMA2000", "dlInformationTransfer",
    "handoverFromEUTRAPreparationRequest", "mobilityFromEUTRACommand", "rrcConnectionReconfiguration",
    "rrcConnectionRelease", "securityModeCommand", "ueCapabilityEnquiry", "counterCheck",
    "ueInformationRequest-r9", "loggedMeasurementConfiguration-r10", "rnReconfiguration-r10",
    "rrcConnectionResume-r13", "dlDedicatedMessageSegment-r16", "spare2", "spare1" };
static const char *lte_ul_dcch[16] = { "csfbParametersRequestCDMA2000", "measurementReport",
    "rrcConnectionReconfigurationComplete", "rrcConnectionReestablishmentComplete",
    "rrcConnectionSetupComplete", "securityModeComplete", "securityModeFailure", "ueCapabilityInformation",
    "ulHandoverPreparationTransfer", "ulInformationTransfer", "counterCheckResponse",
    "ueInformationResponse-r9", "proximityIndication-r9", "rnReconfigurationComplete-r10",
    "mbmsCountingResponse-r10", "interFreqRSTDMeasurementIndication-r10" };
static const char *lte_dl_ccch[4] = { "rrcConnectionReestablishment", "rrcConnectionReestablishmentReject",
    "rrcConnectionReject", "rrcConnectionSetup" };
static const char *nr_dl_dcch[16] = { "rrcReconfiguration", "rrcResume", "rrcRelease", "rrcReestablishment",
    "securityModeCommand", "dlInformationTransfer", "ueCapabilityEnquiry", "counterCheck",
    "mobilityFromNRCommand", "dlDedicatedMessageSegment-r16", "ueInformationRequest-r16",
    "dlInformationTransferMRDC-r16", "loggedMeasurementConfiguration-r16", "spare3", "spare2", "spare1" };
static const char *nr_ul_dcch[16] = { "measurementReport", "rrcReconfigurationComplete", "rrcSetupComplete",
    "rrcReestablishmentComplete", "rrcResumeComplete", "securityModeComplete", "securityModeFailure",
    "ulInformationTransfer", "locationMeasurementIndication", "ueCapabilityInformation",
    "counterCheckResponse", "ueAssistanceInformation", "failureInformation", "ulInformationTransferMRDC",
    "scgFailureInformation", "scgFailureInformationEUTRA" };
static const char *nr_dl_ccch[4] = { "rrcReject", "rrcSetup", "spare2", "spare1" };
static const char *nr_ul_ccch[4] = { "rrcSetupRequest", "rrcResumeRequest", "rrcReestablishmentRequest",
    "rrcSystemInfoRequest" };

static const char *msg_type(int nr, int ch, const uint8_t *m, int len) {
    if (len <= 0) return "";
    if (ch == CH_BCCH_BCH) return nr ? "MIB" : "MasterInformationBlock";
    if (ch == CH_MCCH) return "MCCH";
    if (ch == CH_RECONF) return "RRCReconfiguration";
    if (ch == CH_RECONF_COMPLETE) return "RRCReconfigurationComplete";
    int ext = msbbits(m, len, 0, 1);
    if (ext != 0) {
        /* UL-DCCH messageClassExtension -> c2 (16 alternatives, NR Rel-16/17 and LTE Rel-11+) */
        if (ch == CH_UL_DCCH && msbbits(m, len, 1, 1) == 0) {
            static const char *nr_c2[10] = {
                "ulDedicatedMessageSegment-r16", "dedicatedSIBRequest-r16", "mcgFailureInformation-r16",
                "ueInformationResponse-r16", "sidelinkUEInformationNR-r16", "ulInformationTransferIRAT-r16",
                "iabOtherInformation-r16", "mbsInterestIndication-r17", "uePositioningAssistanceInfo-r17",
                "measurementReportAppLayer-r17"
            };
            static const char *lte_c2[12] = { "ueAssistanceInformation-r11", "inDeviceCoexIndication-r11",
                "mbmsInterestIndication-r11", "scgFailureInformation-r12", "sidelinkUEInformation-r12",
                "wlanConnectionStatusReport-r13", "rrcConnectionResumeComplete-r13", "ulInformationTransferMRDC-r15",
                "scgFailureInformationNR-r15", "measReportAppLayer-r15", "failureInformation-r15",
                "ulInformationTransferIRAT-r16" };
            int v2 = msbbits(m, len, 2, 4);
            if (nr && v2 >= 0 && v2 < 10) return nr_c2[v2];
            if (!nr && v2 >= 0 && v2 < 12) return lte_c2[v2];
        }
        return "messageClassExtension";
    }
    int v;
    switch (ch) {
    case CH_BCCH_DL_SCH: v = msbbits(m, len, 1, 1);
        return v == 0 ? "SystemInformation" : (nr ? "SIB1" : "SystemInformationBlockType1");
    case CH_PCCH: return nr && msbbits(m, len, 1, 1) != 0 ? "" : "Paging";
    case CH_DL_CCCH: v = msbbits(m, len, 1, 2); if (v < 0) return "";
        return nr ? nr_dl_ccch[v] : lte_dl_ccch[v];
    case CH_DL_DCCH: v = msbbits(m, len, 1, 4); if (v < 0) return "";
        return nr ? nr_dl_dcch[v] : lte_dl_dcch[v];
    case CH_UL_CCCH:
        if (nr) { v = msbbits(m, len, 1, 2); return v < 0 ? "" : nr_ul_ccch[v]; }
        v = msbbits(m, len, 1, 1); return v == 0 ? "rrcConnectionReestablishmentRequest" : "rrcConnectionRequest";
    case CH_UL_CCCH1: v = msbbits(m, len, 1, 2); return v == 0 ? "rrcResumeRequest1" : "spare";
    case CH_UL_DCCH: v = msbbits(m, len, 1, 4); if (v < 0) return "";
        return nr ? nr_ul_dcch[v] : lte_ul_dcch[v];
    default: return "";
    }
}

/* The masked field tree is for the signaling page. Compact fields remain the signal page's input. */
#include "sigdec.c"

static char g_signal_tree[262144];
static char g_signal_json[2 * sizeof(g_signal_tree) + 8192];

static void signal_json_string(int *offset, const char *value) {
    int p = *offset;
    g_signal_json[p++] = '"';
    for (; *value && p < (int)sizeof(g_signal_json) - 12; value++) {
        unsigned char c = (unsigned char)*value;
        if (c == '"' || c == '\\') {
            g_signal_json[p++] = '\\';
            g_signal_json[p++] = (char)c;
        } else if (c == '\n') {
            g_signal_json[p++] = '\\';
            g_signal_json[p++] = 'n';
        } else if (c >= 0x20) {
            g_signal_json[p++] = (char)c;
        }
    }
    g_signal_json[p++] = '"';
    *offset = p;
}

static void emit_signaling(struct jb *j, const struct sd_out *out, int incomplete,
                           const char *name, const char *nested_nas) {
    int n = snprintf(g_signal_json, sizeof(g_signal_json), "%s,\"decode_incomplete\":%s",
                     j->b, incomplete ? "true" : "false");
    if (name && name[0]) {
        n += snprintf(g_signal_json + n, sizeof(g_signal_json) - (size_t)n, ",\"display_msg\":");
        signal_json_string(&n, name);
    }
    if (nested_nas && nested_nas[0]) {
        n += snprintf(g_signal_json + n, sizeof(g_signal_json) - (size_t)n, ",\"nested_nas\":");
        signal_json_string(&n, nested_nas);
    }
    if (out->n) {
        n += snprintf(g_signal_json + n, sizeof(g_signal_json) - (size_t)n, ",\"decoded_tree\":");
        signal_json_string(&n, out->b);
    }
    g_signal_json[n++] = '}';
    g_signal_json[n] = 0;
    emit_locked(g_signal_json);
}

static const char *signaling_root(int nr, int ch) {
    switch (ch) {
    case CH_BCCH_BCH: return "BCCH-BCH-Message";
    case CH_BCCH_DL_SCH: return "BCCH-DL-SCH-Message";
    case CH_MCCH: return nr ? NULL : "MCCH-Message";
    case CH_PCCH: return "PCCH-Message";
    case CH_DL_CCCH: return "DL-CCCH-Message";
    case CH_DL_DCCH: return "DL-DCCH-Message";
    case CH_UL_CCCH: return "UL-CCCH-Message";
    case CH_UL_CCCH1: return nr ? "UL-CCCH1-Message" : NULL;
    case CH_UL_DCCH: return "UL-DCCH-Message";
    case CH_RECONF: return nr ? "RRCReconfiguration" : NULL;
    case CH_RECONF_COMPLETE: return nr ? "RRCReconfigurationComplete" : NULL;
    default: return NULL;
    }
}

/* Measurement trigger thresholds as reported ranges (TS 36.133 / 38.133):
   LTE RSRP 0..97 -> -140..-43 dBm, NR RSRP 0..126 -> -156..-31 dBm; RSRQ and
   NR SINR in 0.5 dB steps (LTE RSRQ 0..34 from -19.5 dB, NR RSRQ 0..126 from
   -43 dB, NR SINR 0..126 from -23 dB). 127 in NR means infinity. */
static double meas_threshold_db(int nr, int quantity, int raw) {
    switch (quantity) {
    case SD_Q_RSRP:
        if (nr) return raw >= 127 ? NA_F : -156.0 + raw;
        return raw > 97 ? NA_F : -140.0 + raw;
    case SD_Q_RSRQ:
        if (nr) return raw >= 127 ? NA_F : -43.0 + 0.5 * raw;
        return raw > 34 ? NA_F : -19.5 + 0.5 * raw;
    case SD_Q_SINR:
        return raw >= 127 ? NA_F : -23.0 + 0.5 * raw;
    default:
        return NA_F;
    }
}

static const char *meas_quantity_name(int quantity) {
    switch (quantity) {
    case SD_Q_RSRP: return "RSRP";
    case SD_Q_RSRQ: return "RSRQ";
    case SD_Q_SINR: return "SINR";
    default: return "";
    }
}

/* The measId linking to a report config; the network normally writes the
   measIdToAddModList after the report list, so this is resolved at emit time. */
static int meas_link_for_report(const struct sd_meas_config *m, int report_id, int *object_id) {
    for (int id = 1; id <= SD_MEAS_LIMIT; id++) {
        if (!(m->ids_present & (UINT64_C(1) << (id - 1)))) continue;
        if (m->links[id].report_id != report_id) continue;
        *object_id = m->links[id].object_id;
        return id;
    }
    return 0;
}

static void add_meas_config(struct jb *j, int nr, const struct sd_meas_config *m) {
    if (!(m->removed_objects | m->removed_ids | m->removed_reports | m->objects_present | m->ids_present |
          m->reports_present)) return;
    jb_add(j, ",\"meas_config\":{");
    int first = 1;
    const uint64_t removed[] = { m->removed_objects, m->removed_ids, m->removed_reports };
    const char *const keys[] = { "remove_objects", "remove_ids", "remove_reports" };
    for (int k = 0; k < 3; k++) {
        if (!removed[k]) continue;
        jb_add(j, "%s\"%s\":[", first ? "" : ",", keys[k]);
        int head = 1;
        for (int id = 1; id <= SD_MEAS_LIMIT; id++) {
            if (!(removed[k] & (UINT64_C(1) << (id - 1)))) continue;
            jb_add(j, "%s%d", head ? "" : ",", id);
            head = 0;
        }
        jb_add(j, "]");
        first = 0;
    }
    if (m->objects_present) {
        jb_add(j, "%s\"objects\":[", first ? "" : ",");
        int head = 1;
        for (int id = 1; id <= SD_MEAS_LIMIT; id++) {
            if (!(m->objects_present & (UINT64_C(1) << (id - 1)))) continue;
            const struct sd_meas_object *o = &m->objects[id];
            jb_add(j, "%s{\"id\":%d", head ? "" : ",", id);
            if (o->rat) jb_add(j, ",\"rat\":\"%s\"", o->rat == 1 ? "NR" : "LTE");
            if (o->ssb >= 0) jb_add(j, ",\"ssb_arfcn\":%d", o->ssb);
            if (o->csi >= 0) jb_add(j, ",\"csi_arfcn\":%d", o->csi);
            if (o->earfcn >= 0) jb_add(j, ",\"earfcn\":%d", o->earfcn);
            jb_add(j, "}");
            head = 0;
        }
        jb_add(j, "]");
        first = 0;
    }
    if (m->ids_present) {
        jb_add(j, "%s\"links\":[", first ? "" : ",");
        int head = 1;
        for (int id = 1; id <= SD_MEAS_LIMIT; id++) {
            if (!(m->ids_present & (UINT64_C(1) << (id - 1)))) continue;
            const struct sd_meas_link *link = &m->links[id];
            jb_add(j, "%s{\"id\":%d,\"object_id\":%d,\"report_id\":%d}",
                   head ? "" : ",", id, link->object_id, link->report_id);
            head = 0;
        }
        jb_add(j, "]");
        first = 0;
    }
    if (m->reports_present) {
        jb_add(j, "%s\"reports\":[", first ? "" : ",");
        int head = 1;
        for (int id = 1; id <= SD_MEAS_LIMIT; id++) {
            if (!(m->reports_present & (UINT64_C(1) << (id - 1)))) continue;
            const struct sd_meas_report *r = &m->reports[id];
            jb_add(j, "%s{\"id\":%d", head ? "" : ",", id);
            head = 0;
            if (r->event[0]) jb_add(j, ",\"event\":\"%s\"", r->event);
            if (r->quantity != SD_Q_NONE)
                jb_add(j, ",\"quantity\":\"%s\"", meas_quantity_name(r->quantity));
            if (r->has_threshold) {
                jb_add(j, ",\"threshold_raw\":%d", r->threshold);
                double db = meas_threshold_db(nr, r->quantity, r->threshold);
                if (db != NA_F) {
                    if (r->quantity == SD_Q_RSRP) jb_f(j, "threshold_dbm", db);
                    else jb_f(j, "threshold_db", db);
                }
            }
            if (r->has_hysteresis) jb_i(j, "hysteresis_db", r->hysteresis);
            if (r->has_ttt) jb_i(j, "ttt_ms", r->ttt_ms);
            if (r->has_offset) jb_i(j, "offset_db", r->offset_db);
            jb_add(j, "}");
        }
        jb_add(j, "]");
    }
    jb_add(j, "}");
}

/* Fingerprint of one MeasConfig: an unchanged configuration re-sent by a later
   reconfiguration (handover, periodic RRC) is not repeated as a new event. */
static uint64_t meas_config_fingerprint(int nr, const struct sd_meas_config *m) {
    uint64_t h = 1469598103934665603ULL ^ (uint64_t)(nr + 1);
    for (int id = 1; id <= SD_MEAS_LIMIT; id++) {
        if (!(m->reports_present & (UINT64_C(1) << (id - 1)))) continue;
        const struct sd_meas_report *r = &m->reports[id];
        uint64_t v = (uint64_t)id * 131 + (uint64_t)(r->quantity + 1);
        for (const char *p = r->event; *p; p++) v = v * 31 + (unsigned char)*p;
        v = v * 31 + (uint64_t)r->threshold + (r->has_threshold ? 199 : 0);
        v = v * 31 + (uint64_t)r->hysteresis + (r->has_hysteresis ? 199 : 0);
        v = v * 31 + (uint64_t)r->ttt_ms + (r->has_ttt ? 199 : 0);
        v = v * 31 + (uint64_t)(r->offset_db + 40) + (r->has_offset ? 199 : 0);
        h ^= v;
        h *= 1099511628211ULL;
    }
    return h;
}

/* Emit one event per report config so the signaling event feed shows what the
   network asked the UE to measure (A1-A6 / B1-B2 thresholds, hysteresis, TTT
   and the A3/A6 offset). */
static void emit_meas_config_events(int nr, const struct sd_meas_config *m) {
    static uint64_t last_fingerprint;
    static int have_fingerprint;
    if (!m->reports_present) return;
    uint64_t fp = meas_config_fingerprint(nr, m);
    if (have_fingerprint && fp == last_fingerprint) return;
    last_fingerprint = fp;
    have_fingerprint = 1;
    const struct serving *s = nr ? &g_nr : &g_lte;
    for (int id = 1; id <= SD_MEAS_LIMIT; id++) {
        if (!(m->reports_present & (UINT64_C(1) << (id - 1)))) continue;
        const struct sd_meas_report *r = &m->reports[id];
        int object_id = 0;
        int meas_id = meas_link_for_report(m, id, &object_id);
        const char *event = r->event;
        const char *short_event = !strncmp(event, "event", 5) ? event + 5 : event;
        struct jb j;
        jb_init(&j, "event");
        jb_add(&j, ",\"rat\":\"%s\",\"event\":\"meas_config\",\"msg\":\"MeasConfig\""
                    ",\"display_msg\":\"MeasConfig %s\"", nr ? "NR" : "LTE",
               short_event[0] ? short_event : "?");
        if (meas_id) jb_i(&j, "meas_id", meas_id);
        jb_i(&j, "report_id", id);
        if (object_id) jb_i(&j, "object_id", object_id);
        if (r->event[0]) jb_add(&j, ",\"meas_event\":\"%s\"", short_event);
        if (r->quantity != SD_Q_NONE)
            jb_add(&j, ",\"quantity\":\"%s\"", meas_quantity_name(r->quantity));
        if (r->has_threshold) {
            jb_i(&j, "threshold_raw", r->threshold);
            double db = meas_threshold_db(nr, r->quantity, r->threshold);
            if (db != NA_F) {
                if (r->quantity == SD_Q_RSRP) jb_f(&j, "threshold_dbm", db);
                else jb_f(&j, "threshold_db", db);
            }
        }
        if (r->has_hysteresis) jb_i(&j, "hysteresis_db", r->hysteresis);
        if (r->has_ttt) jb_i(&j, "ttt_ms", r->ttt_ms);
        if (r->has_offset) jb_i(&j, "offset_db", r->offset_db);
        if (s->pci >= 0) jb_i(&j, "pci", s->pci);
        if (s->arfcn >= 0) jb_i(&j, "arfcn", s->arfcn);
        if (object_id > 0 && object_id <= SD_MEAS_LIMIT &&
            (m->objects_present & (UINT64_C(1) << (object_id - 1)))) {
            const struct sd_meas_object *o = &m->objects[object_id];
            if (o->rat == 2 && o->earfcn >= 0) jb_i(&j, "object_earfcn", o->earfcn);
            else if (o->rat == 1 && o->ssb >= 0) jb_i(&j, "object_arfcn", o->ssb);
        }
        jb_emit(&j);
    }
}

/* Broadcast reselection parameters: emit one event per changed set of
   SIB3/5 (LTE) or SIB2/4/5 (NR) parameters, with the serving cell context. */
static void emit_reselection_event(int nr, const struct sd_sib_info *sib, int pci) {
    static uint64_t last_fingerprint;
    static int have_fingerprint;
    const struct sd_reselection *rs = &sib->resel;
    uint64_t fp = 1469598103934665603ULL ^ (uint64_t)(nr + 1) ^ ((uint64_t)sib->mask << 5);
    fp = fp * 31 + (uint64_t)rs->q_hyst + (rs->has_q_hyst ? 199 : 0);
    fp = fp * 31 + (uint64_t)rs->priority + (rs->has_priority ? 199 : 0);
    fp = fp * 31 + (uint64_t)rs->t_reselect + (rs->has_t_reselect ? 199 : 0);
    fp = fp * 31 + (uint64_t)rs->s_intra_search + (rs->has_s_intra ? 199 : 0);
    fp = fp * 31 + (uint64_t)rs->thresh_serving_low + (rs->has_thresh_serving_low ? 199 : 0);
    fp = fp * 31 + (uint64_t)rs->thresh_x_high + (rs->has_thresh_x_high ? 199 : 0);
    fp = fp * 31 + (uint64_t)rs->thresh_x_low + (rs->has_thresh_x_low ? 199 : 0);
    if (have_fingerprint && fp == last_fingerprint) return;
    last_fingerprint = fp;
    have_fingerprint = 1;
    const struct serving *s = nr ? &g_nr : &g_lte;
    struct jb j;
    jb_init(&j, "event");
    jb_add(&j, ",\"rat\":\"%s\",\"event\":\"reselection\",\"msg\":\"SystemInformation\""
                ",\"display_msg\":\"SIB reselection\"", nr ? "NR" : "LTE");
    jb_i(&j, "sib_mask", (int)sib->mask);
    if (rs->has_q_hyst) jb_i(&j, "q_hyst_db", rs->q_hyst);
    if (rs->has_priority) jb_i(&j, "priority", rs->priority);
    if (rs->has_t_reselect) jb_i(&j, "t_reselect_s", rs->t_reselect);
    if (rs->has_s_intra) jb_i(&j, "s_intra_search_db", rs->s_intra_search);
    if (rs->has_thresh_serving_low) jb_i(&j, "thresh_serving_low_db", rs->thresh_serving_low);
    if (rs->has_thresh_x_high) jb_i(&j, "thresh_x_high_db", rs->thresh_x_high);
    if (rs->has_thresh_x_low) jb_i(&j, "thresh_x_low_db", rs->thresh_x_low);
    if (s->pci >= 0) jb_i(&j, "pci", s->pci);
    if (s->arfcn >= 0) jb_i(&j, "arfcn", s->arfcn);
    jb_emit(&j);
}

static void add_measurement_report(struct jb *j, int nr, int serving_pci, const uint8_t *b, int n,
                                   int lte_pcell_sinr);

static void emit_rrc_signaling(struct jb *j, int nr, int ch, int pci, const uint8_t *pdu, int len, int can_decode) {
    const char *root = can_decode ? signaling_root(nr, ch) : NULL;
    struct sd_summary summary = {0};
    struct sd_out out = { g_signal_tree, 0, sizeof(g_signal_tree), 0 };
    int incomplete = !root || sd_decode(nr ? "NR" : "LTE", root, pdu, len, 0, &out, 0, &summary);
    if (root && ch == CH_UL_DCCH && summary.message && !strcmp(summary.message, "measurementReport"))
        add_measurement_report(j, nr, pci, pdu, len, incomplete ? -1 : summary.lte_pcell_sinr_plus_one - 1);
    if (out.trunc) {
        out.trunc = 0;
        sd_put(&out, "\n<内容过长，已截断>\n");
    }
    if (!incomplete && !nr && summary.lte_tdd_assignment_plus_one && summary.lte_tdd_special_plus_one) {
        jb_add(j, ",\"lte_tdd_config\":{\"assignment\":%d,\"special\":%d}",
               summary.lte_tdd_assignment_plus_one - 1, summary.lte_tdd_special_plus_one - 1);
    }
    if (!incomplete && summary.sib1_present) {
        static const char *const fields[SD_SIB1_FIELDS] = {
            "q_rx_lev_min_dbm", "offset_to_pointa", "ul_pointa", "prach_cfg_index",
            "dl_bwp_riv", "prach_root", "grid_offset", "grid_rbs", "grid_scs_khz",
            "ssb_period_ms", "ssb_power_dbm", "tdd_scs_khz", "ul_bwp_riv", "dl_cp_type", "ul_cp_type"
        };
        jb_add(j, ",\"sib1_config\":{");
        int first = 1;
        for (int i = 0; i < SD_SIB1_FIELDS; i++) {
            if (!(summary.sib1_present & (1u << i))) continue;
            if (i >= SD_GRID_OFFSET && i <= SD_GRID_SCS && summary.sib1_dl_grids != 1) continue;
            int value = summary.sib1[i];
            if (i == SD_Q_RX_LEV_MIN) value *= 2; /* TS 38.331 Q-RxLevMin */
            jb_add(j, "%s\"%s\":%d", first ? "" : ",", fields[i], value);
            first = 0;
        }
        for (int i = 0; i < 2; i++) {
            const struct sd_tdd_pattern *pattern = &summary.tdd[i];
            if (!pattern->period_us) continue;
            jb_add(j, "%s\"tdd_pattern%d\":{\"period_us\":%d,\"dl_slots\":%d,\"dl_symbols\":%d,"
                   "\"ul_slots\":%d,\"ul_symbols\":%d}", first ? "" : ",", i + 1, pattern->period_us,
                   pattern->dl_slots, pattern->dl_symbols, pattern->ul_slots, pattern->ul_symbols);
            first = 0;
        }
        jb_add(j, "}");
    }
    /* Broadcast reselection parameters from SIB3/SIB5 (LTE) or SIB2/4/5 (NR). */
    if (!incomplete && summary.sib.mask) {
        const struct sd_reselection *rs = &summary.sib.resel;
        if (rs->has_q_hyst || rs->has_priority || rs->has_t_reselect || rs->has_s_intra ||
            rs->has_thresh_serving_low || rs->has_thresh_x_high || rs->has_thresh_x_low) {
            jb_add(j, ",\"reselection_config\":{\"sib_mask\":%d", (int)summary.sib.mask);
            if (rs->has_q_hyst) jb_i(j, "q_hyst_db", rs->q_hyst);
            if (rs->has_priority) jb_i(j, "priority", rs->priority);
            if (rs->has_t_reselect) jb_i(j, "t_reselect_s", rs->t_reselect);
            if (rs->has_s_intra) jb_i(j, "s_intra_search_db", rs->s_intra_search);
            if (rs->has_thresh_serving_low) jb_i(j, "thresh_serving_low_db", rs->thresh_serving_low);
            if (rs->has_thresh_x_high) jb_i(j, "thresh_x_high_db", rs->thresh_x_high);
            if (rs->has_thresh_x_low) jb_i(j, "thresh_x_low_db", rs->thresh_x_low);
            jb_add(j, "}");
            emit_reselection_event(nr, &summary.sib, pci);
        }
    }
    if (!incomplete && summary.access_present) {
        static const char *const fields[SD_ACCESS_FIELDS] = {
            "p_max_dbm", "q_rx_lev_min_dbm", "prach_cfg_index", "prach_root",
            "preamble_target_dbm", "preamble_ramp_db", "preamble_max_tx", "rar_window_sf", "preamble_count", "ul_cp_type"
        };
        jb_add(j, ",\"access_config\":{");
        const char *sep = "";
        for (int i = 0; i < SD_ACCESS_FIELDS; i++) {
            if (!(summary.access_present & (1u << i))) continue;
            jb_add(j, "%s\"%s\":%d", sep, fields[i], summary.access[i]);
            sep = ",";
        }
        jb_add(j, "}");
    }
    if (!incomplete && nr && (summary.nr_frequency_present & ((1u << SD_NR_PCI) | (1u << SD_NR_SSB))) ==
        ((1u << SD_NR_PCI) | (1u << SD_NR_SSB)) &&
        (summary.nr_frequency_present & ((1u << SD_NR_DL_A) | (1u << SD_NR_UL_A)))) {
        jb_add(j, ",\"frequency_config\":{\"pci\":%d,\"arfcn\":%d",
               summary.nr_frequency[SD_NR_PCI], summary.nr_frequency[SD_NR_SSB]);
        if (summary.nr_frequency_present & (1u << SD_NR_DL_A))
            jb_add(j, ",\"dl_pointa\":%d", summary.nr_frequency[SD_NR_DL_A]);
        if (summary.nr_frequency_present & (1u << SD_NR_UL_A))
            jb_add(j, ",\"ul_pointa\":%d", summary.nr_frequency[SD_NR_UL_A]);
        jb_add(j, "}");
    }
    /* A length-delimited unknown extension after a complete MeasConfig does not erase it. */
    if (summary.meas_complete) {
        add_meas_config(j, nr, &summary.meas);
        emit_meas_config_events(nr, &summary.meas);
    }
    if (summary.plmn_count && !summary.plmn_invalid) {
        jb_add(j, ",\"broadcast_plmns\":[");
        for (int i = 0; i < summary.plmn_count; i++) {
            jb_add(j, "%s\"%03d%0*d\"", i ? "," : "", summary.plmns[i].mcc,
                   summary.plmns[i].mnc_digits, summary.plmns[i].mnc);
        }
        jb_add(j, "]");
    }
    if (summary.selected_plmn) jb_i(j, "selected_plmn_index", summary.selected_plmn);
    if (!incomplete) {
        if (summary.lte_pcell_sinr_plus_one)
            jb_i(j, "pcell_sinr_index", summary.lte_pcell_sinr_plus_one - 1);
        if (summary.suspended) jb_add(j, ",\"rrc_suspended\":true");
    }
    emit_signaling(j, &out, incomplete, summary.message, summary.nas);
}

enum { RRC_SEGMENT_LAST = 7 };
struct signal_segments {
    uint8_t bytes[65536];
    int len, next, ch, pci, rbid;
    long arfcn;
    int64_t updated;
};
static struct signal_segments g_signal_segments[2][2];

static int join_signal_segments(int nr, int seg, int ch, int pci, long arfcn, int rbid,
                                const uint8_t **packet, int *len) {
    if (!seg) return 1;
    if (seg < 1 || seg > RRC_SEGMENT_LAST || ch == CH_UNK) return -1;
    int ul = ch == CH_UL_CCCH || ch == CH_UL_CCCH1 || ch == CH_UL_DCCH || ch == CH_RECONF_COMPLETE;
    struct signal_segments *s = &g_signal_segments[nr][ul];
    int64_t now = mono_ms();
    if (seg == 1) {
        s->len = 0;
        s->next = 1;
        s->ch = ch; s->pci = pci; s->arfcn = arfcn; s->rbid = rbid;
    } else if (!s->next || s->ch != ch || s->pci != pci || s->arfcn != arfcn || s->rbid != rbid ||
               now - s->updated > 3000 || (seg != RRC_SEGMENT_LAST && seg != s->next)) {
        s->next = 0;
        return -1;
    }
    if (*len <= 0 || *len > (int)sizeof(s->bytes) - s->len) {
        s->next = 0;
        return -1;
    }
    memcpy(s->bytes + s->len, *packet, (size_t)*len);
    s->len += *len;
    s->updated = now;
    if (seg < RRC_SEGMENT_LAST) {
        s->next = seg + 1;
        return 0;
    }
    s->next = 0;
    *packet = s->bytes;
    *len = s->len;
    return 1;
}

static void ims_rrc_hook(int nr, const char *msg);  /* 1.9: EPS fallback detection (IMS section) */
/* 2.1: establishmentCause of the last RRC setup request (MO/MT hint for calls without SIP) */
static const char *k_nr_cause[16] = { "emergency", "highPriorityAccess", "mt-Access", "mo-Signalling", "mo-Data", "mo-VoiceCall",
    "mo-VideoCall", "mo-SMS", "mps-PriorityAccess", "mcs-PriorityAccess", "spare6", "spare5", "spare4", "spare3", "spare2", "spare1" };
static const char *k_lte_cause[8] = { "emergency", "highPriorityAccess", "mt-Access", "mo-Signalling", "mo-Data",
    "delayTolerantAccess", "mo-VoiceCall", "spare1" };
static char g_setup_cause[24]; static int64_t g_setup_cause_t;
static void rrc_events(const char *rat, const char *msg) {
    const char *ev = NULL;
    if (!strcmp(msg, "rrcReconfiguration") || !strcmp(msg, "rrcConnectionReconfiguration")) ev = "rrc_reconfig";
    else if (!strcmp(msg, "RRCReconfiguration")) ev = "scg_config";  /* NR container inside LTE (EN-DC) */
    else if (!strcmp(msg, "rrcSetup") || !strcmp(msg, "rrcConnectionSetup")) ev = "rrc_setup";
    else if (!strcmp(msg, "rrcRelease") || !strcmp(msg, "rrcConnectionRelease")) ev = "rrc_release";
    else if (!strcmp(msg, "rrcReestablishmentRequest") || !strcmp(msg, "rrcConnectionReestablishmentRequest")) ev = "rrc_reestablish";
    else if (!strcmp(msg, "mobilityFromEUTRACommand") || !strcmp(msg, "mobilityFromNRCommand")) ev = "inter_rat_mobility";
    else if (!strcmp(msg, "scgFailureInformation") || !strcmp(msg, "scgFailureInformationEUTRA")) ev = "scg_failure";
    else if (!strcmp(msg, "rrcReject") || !strcmp(msg, "rrcConnectionReject")) ev = "rrc_reject";
    if (!ev) return;
    struct jb j; jb_init(&j, "event");
    jb_add(&j, ",\"rat\":\"%s\",\"event\":\"%s\",\"msg\":\"%s\"", rat, ev, msg);
    jb_emit(&j);
}

/* Decode the base PCCH Paging list (TS 36.331 / 38.331, unaligned PER).
   Only emit identifier types and access/domain flags. Skip the identity values. */
static void add_paging_details(struct jb *j, int nr, const uint8_t *b, int n) {
    int bit = nr ? 2 : 1; /* c1; NR also encodes the paging/spare1 choice */
    int option_bits = nr ? 3 : 4;
    int options = msbbits(b, n, bit, option_bits);
    if (options < 0) return;
    bit += option_bits;

    int count = 0;
    if (options & (1 << (option_bits - 1))) {
        int count_bits = nr ? 5 : 4;
        int encoded_count = msbbits(b, n, bit, count_bits);
        if (encoded_count < 0) return;
        count = encoded_count + 1;
        bit += count_bits;
    }

    int identities[32], access[32];
    for (int i = 0; i < count; i++) {
        if (msbbits(b, n, bit++, 1) != 0) return; /* PagingRecord extension */
        int non3gpp = nr ? msbbits(b, n, bit++, 1) : 0;
        if (msbbits(b, n, bit++, 1) != 0) return; /* PagingUE-Identity extension */
        int identity = msbbits(b, n, bit++, 1);
        if (identity < 0) return;
        int identity_bits = nr ? (identity ? 40 : 48) : 40;
        if (!nr && identity) {
            int digits = msbbits(b, n, bit, 4);
            if (digits < 0) return;
            bit += 4;
            identity_bits = (digits + 6) * 4;
        }
        if (identity_bits > n * 8 - bit) return;
        bit += identity_bits;
        access[i] = nr ? non3gpp : msbbits(b, n, bit++, 1);
        if (access[i] < 0) return;
        identities[i] = identity;
    }

    jb_add(j, ",\"paging_records\":[");
    for (int i = 0; i < count; i++) {
        const char *identity = nr ? (identities[i] ? "fullI-RNTI" : "ng-5G-S-TMSI")
                                  : (identities[i] ? "imsi" : "s-TMSI");
        jb_add(j, "%s{\"ue_identity_type\":\"%s\"", i ? "," : "", identity);
        if (nr && access[i]) jb_add(j, ",\"accessType\":\"non3GPP\"");
        if (!nr) jb_add(j, ",\"cn-Domain\":\"%s\"", access[i] ? "cs" : "ps");
        jb_add(j, "}");
    }
    jb_add(j, "]");
    if (!nr && (options & 4)) jb_add(j, ",\"systemInfoModification\":true");
    if (!nr && (options & 2)) jb_add(j, ",\"etws-Indication\":true");
    if (options & 1) jb_add(j, ",\"paging_extensions\":true");
}

/* Bounded UPER reader for RRC measurement reports (TS 38.331 / 36.331). */
struct per_reader { const uint8_t *buf; int len, bit, error, limit; };
static int per_get(struct per_reader *r, int width) {
    if (r->error || width > r->limit - r->bit) { r->error = 1; return 0; }
    int value = msbbits(r->buf, r->len, r->bit, width);
    if (value < 0) { r->error = 1; return 0; }
    r->bit += width;
    return value;
}
static int per_skip(struct per_reader *r, int width) {
    if (r->error || width < 0 || width > r->limit - r->bit) { r->error = 1; return 0; }
    r->bit += width;
    return 1;
}
/* Small extension indexes and unfragmented length determinants. */
static int per_small(struct per_reader *r) {
    if (per_get(r, 1)) { r->error = 1; return 0; }
    return per_get(r, 6);
}
static int per_octets(struct per_reader *r) {
    int first = per_get(r, 8);
    if (first < 128) return first;
    if (first < 192) return ((first & 63) << 8) | per_get(r, 8);
    r->error = 1;
    return 0;
}
static void per_extensions(struct per_reader *r) {
    int count = 1 + per_small(r), present[64];
    for (int i = 0; i < count; i++) present[i] = per_get(r, 1);
    for (int i = 0; i < count && !r->error; i++) {
        if (present[i]) per_skip(r, 8 * per_octets(r));
    }
}
struct report_quantity { int rp, rq, snr; };
enum { REPORT_BEAM_OUTPUT_LIMIT = 12 };
struct report_beam { int index, has_values; struct report_quantity values; };
struct report_cell {
    int pci, serving_index, best;
    struct report_quantity ssb, csi;
    int beam_count[2];
    int best_beam_sinr[2];
    struct report_beam beams[2][REPORT_BEAM_OUTPUT_LIMIT];
};
static const struct report_quantity report_empty = { -1, -1, -1 };
static struct report_quantity per_quantities(struct per_reader *r, int eutra) {
    int flags = per_get(r, 3);
    struct report_quantity q = report_empty;
    if (flags & 4) q.rp = per_get(r, 7);
    if (flags & 2) q.rq = per_get(r, eutra ? 6 : 7);
    if (flags & 1) q.snr = per_get(r, 7);
    if (eutra && (q.rp > 97 || q.rq > 34)) r->error = 1;
    return q;
}
static struct report_cell per_nr_cell(struct per_reader *r) {
    struct report_cell c = {
        .pci = -1, .serving_index = -1, .ssb = report_empty, .csi = report_empty,
        .best_beam_sinr = { -1, -1 },
    };
    int extension = per_get(r, 1), has_pci = per_get(r, 1);
    if (has_pci) c.pci = per_get(r, 10);
    if (c.pci > 1007) r->error = 1;
    int has_indexes = per_get(r, 1), cell_flags = per_get(r, 2);
    if (cell_flags & 2) c.ssb = per_quantities(r, 0);
    if (cell_flags & 1) c.csi = per_quantities(r, 0);
    if (has_indexes) {
        int flags = per_get(r, 2);
        for (int list = 0; list < 2; list++) {
            if (!(flags & (2 >> list))) continue;
            int count = 1 + per_get(r, 6);
            for (int i = 0; i < count && !r->error; i++) {
                struct report_beam beam = { .has_values = per_get(r, 1) };
                beam.index = per_get(r, list == 0 ? 6 : 7);
                if (list == 1 && beam.index >= 96) r->error = 1;
                if (beam.has_values) beam.values = per_quantities(r, 0);
                if (!r->error) {
                    if (beam.has_values && beam.values.snr > c.best_beam_sinr[list])
                        c.best_beam_sinr[list] = beam.values.snr;
                    if (i < REPORT_BEAM_OUTPUT_LIMIT) c.beams[list][i] = beam;
                    c.beam_count[list]++;
                }
            }
        }
    }
    if (extension) per_extensions(r);
    return c;
}

enum lte_meas_extension { LTE_NEIGHBOR_SINR, LTE_MEAS_ID };
static void per_lte_meas_extensions(struct per_reader *r, enum lte_meas_extension field, int *value) {
    int count = 1 + per_small(r), present[64];
    for (int i = 0; i < count; i++) present[i] = per_get(r, 1);
    for (int i = 0; i < count && !r->error; i++) {
        if (!present[i]) continue;
        int length = per_octets(r);
        if (r->error || length * 8 > r->limit - r->bit) { r->error = 1; return; }
        if (i == 2) {
            /* Both fields are first in extension group 2 of their respective sequences. */
            struct per_reader group = *r;
            group.limit = group.bit + 8 * length;
            int present = per_get(&group, 1);
            per_skip(&group, field == LTE_NEIGHBOR_SINR ? 1 : 2);
            if (present) *value = field == LTE_NEIGHBOR_SINR ? per_get(&group, 7) : 33 + per_get(&group, 5);
            if (group.error) { r->error = 1; return; }
        }
        per_skip(r, 8 * length);
    }
}

/* TS 36.331 PLMN-Identity and MeasResultEUTRA.cgi-Info. Only consume the bits here. */
static void per_skip_lte_plmn(struct per_reader *r) {
    if (per_get(r, 1)) per_skip(r, 12); /* MCC: three constrained decimal digits */
    int mnc_digits = 2 + per_get(r, 1);
    per_skip(r, 4 * mnc_digits);
}

static struct report_cell per_eutra_cell(struct per_reader *r, int in_nr) {
    struct report_cell c = { .serving_index = -1, .ssb = report_empty, .csi = report_empty };
    int extension = in_nr ? per_get(r, 1) : 0;
    int has_cgi = per_get(r, 1);
    /* TS 38.331 uses the 10-bit PhysCellId here, reserving 504..1007 for E-UTRA.
       TS 36.331 uses the 9-bit LTE PhysCellId. */
    c.pci = per_get(r, in_nr ? 10 : 9);
    if (c.pci > 503) r->error = 1;
    if (in_nr) {
        c.ssb = per_quantities(r, 1);
        if (has_cgi) r->error = 1; /* TS 38.331 has a different CGI extension structure. */
    } else {
        if (has_cgi) {
            int has_plmn_list = per_get(r, 1);
            per_skip_lte_plmn(r);
            per_skip(r, 28 + 16); /* CellIdentity and TAC */
            if (has_plmn_list) {
                int count = 1 + per_get(r, 3);
                if (count > 5) r->error = 1;
                for (int i = 0; i < count && !r->error; i++) per_skip_lte_plmn(r);
            }
        }
        int quantity_extension = per_get(r, 1), flags = per_get(r, 2);
        if (flags & 2) c.ssb.rp = per_get(r, 7);
        if (flags & 1) c.ssb.rq = per_get(r, 6);
        if (c.ssb.rp > 97 || c.ssb.rq > 34) r->error = 1;
        if (quantity_extension) per_lte_meas_extensions(r, LTE_NEIGHBOR_SINR, &c.ssb.snr);
    }
    if (extension) per_extensions(r);
    return c;
}

static void add_report_quantities(struct jb *j, struct report_quantity q) {
    int leading = 0;
    if (q.rp >= 0) { jb_add(j, "\"rsrp_index\":%d", q.rp); leading = 1; }
    if (q.rq >= 0) { jb_add(j, "%s\"rsrq_index\":%d", leading ? "," : "", q.rq); leading = 1; }
    if (q.snr >= 0) jb_add(j, "%s\"sinr_index\":%d", leading ? "," : "", q.snr);
}
static void add_report_beams(struct jb *j, const struct report_cell *cell, int *budget, int *limited) {
    if (!cell->beam_count[0] && !cell->beam_count[1]) return;
    static const char *lists[] = {"resultsSSB-Indexes", "resultsCSI-RS-Indexes"};
    static const char *indexes[] = {"ssb-Index", "csi-RS-Index"};
    static const char *results[] = {"ssb-Results", "csi-RS-Results"};
    int started = 0;
    for (int kind = 0; kind < 2; kind++) {
        int count = cell->beam_count[kind];
        int shown = count < *budget ? count : *budget;
        if (shown < count) *limited = 1;
        if (!shown) continue;
        jb_add(j, "%s\"%s\":[", started ? "," : ",\"rsIndexResults\":{", lists[kind]);
        started = 1;
        for (int i = 0; i < shown; i++) {
            const struct report_beam *beam = &cell->beams[kind][i];
            jb_add(j, "%s{\"%s\":%d", i ? "," : "", indexes[kind], beam->index);
            if (beam->has_values) {
                jb_add(j, ",\"%s\":{", results[kind]);
                add_report_quantities(j, beam->values);
                jb_add(j, "}");
            }
            jb_add(j, "}");
        }
        jb_add(j, "]");
        *budget -= shown;
    }
    if (started) jb_add(j, "}");
}
static void add_report_cell(struct jb *j, const struct report_cell *cell, int nr, int serving,
                            int *beam_budget, int *limited) {
    jb_add(j, "{\"rat\":\"%s\"", nr ? "NR" : "LTE");
    if (cell->serving_index >= 0) jb_add(j, ",\"servCellId\":%d", cell->serving_index);
    if (cell->pci >= 0) jb_add(j, ",\"physCellId\":%d", cell->pci);
    if (cell->best) jb_add(j, ",\"bestNeighbour\":true");
    if (nr) {
        int has_ssb = cell->ssb.rp >= 0 || cell->ssb.rq >= 0 || cell->ssb.snr >= 0;
        int has_csi = cell->csi.rp >= 0 || cell->csi.rq >= 0 || cell->csi.snr >= 0;
        jb_add(j, ",\"cellResults\":{");
        if (has_ssb) {
            jb_add(j, "\"resultsSSB-Cell\":{");
            add_report_quantities(j, cell->ssb);
            jb_add(j, "}");
        }
        if (has_csi) {
            jb_add(j, "%s\"resultsCSI-RS-Cell\":{", has_ssb ? "," : "");
            add_report_quantities(j, cell->csi);
            jb_add(j, "}");
        }
    } else {
        jb_add(j, ",\"measResult\":{");
        add_report_quantities(j, cell->ssb);
    }
    jb_add(j, "}");
    if (nr) {
        if (!serving) {
            // Keep a neighbor's beam SINR available even when the detail view caps the beam list.
            if (cell->ssb.snr < 0 && cell->best_beam_sinr[0] >= 0)
                jb_add(j, ",\"ssb_beam_sinr_index\":%d", cell->best_beam_sinr[0]);
            if (cell->csi.snr < 0 && cell->best_beam_sinr[1] >= 0)
                jb_add(j, ",\"csi_beam_sinr_index\":%d", cell->best_beam_sinr[1]);
        }
        add_report_beams(j, cell, beam_budget, limited);
    }
    jb_add(j, "}");
}

static void add_measurement_report(struct jb *j, int nr, int serving_pci, const uint8_t *b, int n,
                                   int lte_pcell_sinr) {
    struct per_reader r = { b, n, 5, 0, 8 * n }; /* UL-DCCH c1 and message choice */
    int critical = per_get(&r, nr ? 1 : 4); /* LTE criticalExtensions (1) and c1 choice (3). */
    if (r.error || critical) {
        jb_add(j, ",\"mr_status\":\"%s\",\"mr_bit\":%d", r.error ? "truncated" : "critical_extension", r.bit);
        return;
    }
    per_skip(&r, nr ? 2 : 1); /* optional IEs following measResults */
    int extension = per_get(&r, 1), has_neighbors = per_get(&r, 1);
    int meas_id = 1 + per_get(&r, nr ? 6 : 5);

    struct report_cell serving[32], best[32], neighbors[8];
    int nr_neighbors = nr, serving_count = 1, neighbor_count = 0, best_count = 0, partial = extension;
    if (nr) {
        serving_count = 1 + per_get(&r, 5);
        for (int i = 0; i < serving_count && !r.error; i++) {
            int ext = per_get(&r, 1), has_best = per_get(&r, 1), index = per_get(&r, 5);
            serving[i] = per_nr_cell(&r);
            serving[i].serving_index = index;
            if (has_best) {
                best[best_count] = per_nr_cell(&r);
                best[best_count].serving_index = index;
                best[best_count++].best = 1;
            }
            if (ext) per_extensions(&r);
        }
    } else {
        serving[0] = (struct report_cell){ .pci = serving_pci, .serving_index = -1, .ssb = report_empty, .csi = report_empty };
        serving[0].ssb.rp = per_get(&r, 7);
        serving[0].ssb.rq = per_get(&r, 6);
        serving[0].ssb.snr = lte_pcell_sinr;
        if (serving[0].ssb.rp > 97 || serving[0].ssb.rq > 34) r.error = 1;
    }
    if (r.error) {
        jb_add(j, ",\"mr_status\":\"serving_decode_failed\",\"mr_bit\":%d", r.bit);
        return;
    }

    int can_read_extensions = 1;
    if (has_neighbors) {
        int supported = 0;
        if (!per_get(&r, 1)) {
            /* NR has one root alternative; LTE E-UTRA is the first of four. */
            supported = nr || per_get(&r, 2) == 0;
        } else if (nr && per_small(&r) == 0 && !r.error) {
            int length = per_octets(&r);
            int end = r.bit + 8 * length;
            if (end > r.limit || !length) r.error = 1;
            else r.limit = end;
            nr_neighbors = 0;
            supported = 1;
        }
        if (supported && !r.error) {
            int count = 1 + per_get(&r, 3);
            for (int i = 0; i < count && !r.error; i++) {
                neighbors[i] = nr_neighbors ? per_nr_cell(&r) : per_eutra_cell(&r, nr);
                if (!r.error) neighbor_count++;
            }
        } else { partial = 1; can_read_extensions = 0; }
    }
    if (!nr && extension && !r.error && can_read_extensions) {
        per_lte_meas_extensions(&r, LTE_MEAS_ID, &meas_id);
    }
    jb_add(j, ",\"measResults\":{\"measId\":%d,\"%s\":[", meas_id,
           nr ? "measResultServingMOList" : "measResultPCell");
    /* Bound the diagnostic JSON independently of the protocol's cell limits. */
    int displayed_serving = serving_count > 8 ? 8 : serving_count;
    int beam_budget = REPORT_BEAM_OUTPUT_LIMIT, limited = 0;
    for (int i = 0; i < displayed_serving; i++) {
        if (i) jb_add(j, ",");
        add_report_cell(j, &serving[i], nr, 1, &beam_budget, &limited);
    }
    jb_add(j, "]");
    if (neighbor_count) {
        jb_add(j, ",\"%s\":[", nr_neighbors ? "measResultListNR" : "measResultListEUTRA");
        for (int i = 0; i < neighbor_count; i++) {
            if (i) jb_add(j, ",");
            add_report_cell(j, &neighbors[i], nr_neighbors, 0, &beam_budget, &limited);
        }
        jb_add(j, "]");
    }
    if (best_count) {
        int selected = 0;
        for (int i = 0; i < best_count; i++) {
            if (best[i].serving_index == 0) { selected = i; break; }
        }
        jb_add(j, ",\"measResultBestNeighCell\":");
        add_report_cell(j, &best[selected], 1, 0, &beam_budget, &limited);
    }
    if (displayed_serving != serving_count || best_count > 1) limited = 1;
    if (r.error || partial || limited) jb_add(j, ",\"partial\":true");
    jb_add(j, "}");
    jb_add(j, ",\"mr_status\":\"%s\",\"mr_bit\":%d",
           r.error ? "neighbor_decode_failed" : partial ? "unsupported_extension" :
           limited ? "display_limit" : "decoded", r.bit);
}

/* Bounded sample of the measurement payload only, used by the local diagnostic export. */
static void emit_mr_sample(const char *rat, uint32_t version, uint32_t release, int seg,
                           const uint8_t *b, int n) {
    char hex[513];
    static const char digits[] = "0123456789abcdef";
    int count = n < 256 ? n : 256;
    for (int i = 0; i < count; i++) {
        hex[i * 2] = digits[b[i] >> 4];
        hex[i * 2 + 1] = digits[b[i] & 15];
    }
    hex[count * 2] = 0;
    struct jb sample; jb_init(&sample, "mr_sample");
    jb_add(&sample, ",\"rat\":\"%s\",\"ver\":%u,\"release\":%u,\"seg\":%d,\"len\":%d,\"uper_hex\":\"%s\"",
           rat, version, release, seg, n, hex);
    jb_emit(&sample);
}

static void add_nr_mib(struct jb *j, const uint8_t *b, int n) {
    struct per_reader r = { b, n, 0, 0, 8 * n };
    if (per_get(&r, 1)) return; /* BCCH-BCH messageClassExtension */
    int sfn = per_get(&r, 6), scs = per_get(&r, 1), offset = per_get(&r, 4);
    int dmrs = per_get(&r, 1), coreset = per_get(&r, 4), search = per_get(&r, 4);
    int barred = per_get(&r, 1), reselection = per_get(&r, 1);
    per_skip(&r, 1); /* spare */
    if (r.error) return;
    jb_add(j, ",\"mib\":{\"sfn_msb6\":%d,\"scs_common\":\"%s\",\"ssb_subcarrier_offset\":%d,\"dmrs_type_a\":%d,"
              "\"control_resource_set_zero\":%d,\"search_space_zero\":%d,\"cell_barred\":\"%s\",\"intra_freq_reselection\":\"%s\"}",
           sfn, scs ? "30 / 120 kHz" : "15 / 60 kHz", offset, dmrs + 2, coreset, search,
           barred ? "notBarred" : "barred", reselection ? "notAllowed" : "allowed");
}

static void add_nas_detail(struct jb *j, int nr, int pd, const uint8_t *p, int n);

static void add_nas_container(struct jb *j, int nr, struct per_reader *r) {
    int n = per_octets(r);
    if (r->error || n <= 0 || n > (r->limit - r->bit) / 8) return;
    jb_add(j, ",\"dedicatedNAS\":{\"len\":%d", n);
    uint8_t nas[512];
    int available = n < (int)sizeof(nas) ? n : (int)sizeof(nas);
    for (int i = 0; i < available; i++) nas[i] = per_get(r, 8);
    int pd = nr ? nas[0] : nas[0] & 15;
    int mm = pd == (nr ? 0x7e : 7);
    int sm = pd == (nr ? 0x2e : 2);
    int security = mm ? (nr && available >= 2 ? nas[1] & 15 : !nr ? nas[0] >> 4 : -1) : 0;
    if (mm || sm) jb_add(j, ",\"protocol\":\"%s\"", nr ? (mm ? "5GMM" : "5GSM") : (mm ? "EMM" : "ESM"));
    if (mm && security > 0) jb_add(j, ",\"nas_security_header\":%d", security);
    int offset = mm ? (nr ? 2 : 1) : (nr ? 3 : 2);
    if ((mm || sm) && security == 0 && offset < available) {
        jb_add(j, ",\"nas_message_type\":\"%s 0x%02X\"",
               nr ? (mm ? "5GMM" : "5GSM") : (mm ? "EMM" : "ESM"), nas[offset]);
        add_nas_detail(j, nr, pd, nas, available);
    }
    jb_add(j, "}");
}

static void add_rrc_nas_transfer(struct jb *j, int nr, int ch, const char *message, const uint8_t *b, int n) {
    int downlink = ch == CH_DL_DCCH;
    if (!downlink && ch != CH_UL_DCCH) return;
    if (strcmp(message, downlink ? "dlInformationTransfer" : "ulInformationTransfer")) return;
    struct per_reader r = { b, n, 5, 0, 8 * n };
    int tx = downlink ? per_get(&r, 2) : -1;
    int future = per_get(&r, 1);
    if (r.error) return;
    if (tx >= 0) jb_add(j, ",\"rrc_transaction_id\":%d", tx);
    if (future) {
        jb_add(j, ",\"criticalExtensions\":\"future\"");
        return;
    }
    if (nr) {
        int flags = per_get(&r, 3);
        if (r.error) return;
        jb_add(j, ",\"criticalExtensions\":\"%s\"", message);
        if (flags & 4) add_nas_container(j, 1, &r);
    } else {
        int version = per_get(&r, 2);
        if (r.error || version != 0) return;
        per_skip(&r, 1); /* nonCriticalExtension */
        int kind = per_get(&r, 2);
        if (r.error) return;
        jb_add(j, ",\"criticalExtensions\":\"%s-r8\"", message);
        if (kind == 0) add_nas_container(j, 0, &r);
    }
}

static void add_nr_system_information(struct jb *j, const uint8_t *b, int n) {
    struct per_reader r = { b, n, 2, 0, 8 * n };
    /* Root SystemInformation has two optional IEs, then the SIB list. */
    if (per_get(&r, 1)) return;
    per_skip(&r, 2);
    int count = 1 + per_get(&r, 5), extension = per_get(&r, 1);
    int index = extension ? per_small(&r) + 10 : per_get(&r, 3) + 2;
    if (!r.error) jb_add(j, ",\"sib_count\":%d,\"first_sib\":%d", count, index);
}

static void add_nr_capability_information(struct jb *j, struct per_reader *r) {
    if (!(per_get(r, 3) & 4)) return;
    int count = per_get(r, 4);
    const char *rat[8];
    int bytes[8];
    static const char *root_rat[] = {"NR", "E-UTRA-NR", "E-UTRA", "UTRA-FDD"};
    if (count > 8) return;
    for (int i = 0; i < count && !r->error; i++) {
        int ext = per_get(r, 1);
        if (ext) { (void)per_small(r); rat[i] = "extension"; }
        else rat[i] = root_rat[per_get(r, 2)];
        bytes[i] = per_octets(r);
        per_skip(r, 8 * bytes[i]);
    }
    if (r->error) return;
    jb_add(j, ",\"ue_capability_containers\":[");
    for (int i = 0; i < count; i++) {
        jb_add(j, "%s{\"rat\":\"%s\",\"len\":%d}", i ? "," : "", rat[i], bytes[i]);
    }
    jb_add(j, "]");
}

/* Decode compact message-specific fields without retaining payloads or keys. */
static void add_nr_rrc_controls(struct jb *j, const char *message, int ch, const uint8_t *b, int n) {
    int choice_bits = ch == CH_DL_CCCH ? 3 : ch == CH_UL_DCCH && n > 0 && (b[0] & 0x80) ? 6 : 5;
    struct per_reader r = { b, n, choice_bits, 0, 8 * n };
    if (!strcmp(message, "rrcReject")) {
        int future = per_get(&r, 1);
        int flags = future ? 0 : per_get(&r, 3);
        int wait = flags & 4 ? 1 + per_get(&r, 4) : 0;
        if (r.error) return;
        jb_add(j, ",\"criticalExtensions\":\"%s\"", future ? "future" : "rrcReject");
        if (wait) jb_add(j, ",\"wait_time_s\":%d", wait);
        return;
    }
    const int information_request = !strcmp(message, "ueInformationRequest-r16");
    const int information_response = !strcmp(message, "ueInformationResponse-r16");
    const int capability = !strcmp(message, "ueCapabilityInformation");
    if (!information_request && !information_response && !capability && strcmp(message, "ueCapabilityEnquiry") &&
        strcmp(message, "rrcRelease") && strcmp(message, "rrcSetup") &&
        strcmp(message, "rrcResume") && strcmp(message, "rrcReconfiguration") &&
        strcmp(message, "rrcSetupComplete") && strcmp(message, "rrcReconfigurationComplete") &&
        strcmp(message, "securityModeCommand") && strcmp(message, "securityModeComplete") &&
        strcmp(message, "securityModeFailure")) return;
    int transaction = per_get(&r, 2), future = per_get(&r, 1);
    if (r.error) return;
    jb_add(j, ",\"rrc_transaction_id\":%d,\"criticalExtensions\":\"%s\"",
           transaction, future ? "future" : message);
    if (future) return;
    if (information_request || information_response) {
        static const char *request_fields[] = {"idleModeMeasurementReq-r16", "logMeasReportReq-r16",
            "connEstFailReportReq-r16", "ra-ReportReq-r16", "rlf-ReportReq-r16", "mobilityHistoryReportReq-r16"};
        static const char *response_fields[] = {"measResultIdleEUTRA-r16", "measResultIdleNR-r16", "logMeasReport-r16",
            "connEstFailReport-r16", "ra-ReportList-r16", "rlf-Report-r16", "mobilityHistoryReport-r16"};
        int count = information_request ? 6 : 7;
        int flags = per_get(&r, count + 2);
        if (r.error) return;
        jb_add(j, ",\"%s\":[", information_request ? "requested_reports" : "included_reports");
        const char *separator = "";
        for (int i = 0; i < count; i++) {
            if (flags & (1 << (count + 1 - i))) {
                jb_add(j, "%s\"%s\"", separator, information_request ? request_fields[i] : response_fields[i]);
                separator = ",";
            }
        }
        jb_add(j, "]");
    } else if (capability) {
        add_nr_capability_information(j, &r);
    } else if (!strcmp(message, "rrcSetupComplete")) {
        int flags = per_get(&r, 6), plmn = 1 + per_get(&r, 4);
        if (!r.error && plmn <= 12) {
            jb_add(j, ",\"selectedPLMN-Identity\":%d", plmn);
            if (!(flags & 0x28)) {
                if (flags & 0x10) per_skip(&r, 1); /* guami-Type, before dedicated NAS */
                add_nas_container(j, 1, &r);
            }
        }
    } else if (!strcmp(message, "rrcRelease")) {
        int flags = per_get(&r, 6);
        if (r.error) return;
        jb_add(j, ",\"redirected_carrier_present\":%s,\"suspend_config_present\":%s",
               flags & 32 ? "true" : "false", flags & 8 ? "true" : "false");
    } else if (!strcmp(message, "securityModeCommand")) {
        per_skip(&r, 4); /* two optional IEs, two SEQUENCE extension bits (separated by no root fields) */
        int integrity = per_get(&r, 1);
        if (per_get(&r, 1)) return; /* unsupported algorithm extension */
        int cipher = per_get(&r, 3);
        if (r.error || cipher > 3) return;
        jb_add(j, ",\"cipher_algorithm\":\"nea%d\"", cipher);
        if (integrity && !per_get(&r, 1)) {
            int algorithm = per_get(&r, 3);
            if (!r.error && algorithm <= 3) jb_add(j, ",\"integrity_algorithm\":\"nia%d\"", algorithm);
        }
    }
}

/* --- 0xB0C0 LTE RRC OTA --- */
static int lte_ch(int ver, int pdu) {
    static const int A[] = { 0, CH_BCCH_BCH, CH_BCCH_DL_SCH, CH_MCCH, CH_PCCH, CH_DL_CCCH, CH_DL_DCCH, CH_UL_CCCH, CH_UL_DCCH };
    static const int C[] = { 0, CH_BCCH_BCH, CH_BCCH_DL_SCH, 0, CH_MCCH, CH_PCCH, CH_DL_CCCH, CH_DL_DCCH, CH_UL_CCCH, CH_UL_DCCH };
    static const int D[] = { 0, CH_BCCH_BCH, 0, CH_BCCH_DL_SCH, 0, 0, CH_MCCH, CH_PCCH, CH_DL_CCCH, CH_DL_DCCH, CH_UL_CCCH, CH_UL_DCCH };
    switch (ver) {
    case 2: case 3: case 4: case 6: case 7: case 8: case 0x0d: case 0x16:
        return (pdu >= 1 && pdu <= 8) ? A[pdu] : CH_UNK;
    case 9: case 0x0c:
        return (pdu >= 8 && pdu <= 15) ? A[pdu - 7] : CH_UNK;
    case 0x0e: case 0x0f: case 0x10: case 0x14: case 0x18: case 0x19:
        return (pdu >= 1 && pdu <= 9) ? C[pdu] : CH_UNK;
    default: /* 0x13,0x1a,0x1b,0x1d,0x1e,0x1f and newer (guess) */
        return (pdu >= 1 && pdu <= 11) ? D[pdu] : CH_UNK;
    }
}
static void parse_lte_rrc(const uint8_t *b, int n) {
    if (n < 13) return;
    int ver = b[0];
    int pci, pdu, mlen, off, seg = 0; long earfcn;
    if (ver >= 30) { if (n < 24) return; pci = rd16(b + 6); earfcn = rd32(b + 8); pdu = b[14]; mlen = rd16(b + 19); seg = b[23]; off = 24; }
    else if (ver >= 25) { if (n < 21) return; pci = rd16(b + 6); earfcn = rd32(b + 8); pdu = b[14]; mlen = rd16(b + 19); off = 21; }
    else if (ver >= 8) { if (n < 19) return; pci = rd16(b + 4); earfcn = rd32(b + 6); pdu = b[12]; mlen = rd16(b + 17); off = 19; }
    else if (ver >= 5) { if (n < 17) return; pci = rd16(b + 4); earfcn = rd16(b + 6); pdu = b[10]; mlen = rd16(b + 15); off = 17; }
    else { pci = rd16(b + 4); earfcn = rd16(b + 6); pdu = b[10]; mlen = rd16(b + 11); off = 13; }
    int avail = n - off;
    int ch = lte_ch(ver, pdu);
    int guess = (ver > 0x1f);
    if (mlen != avail || pci > 503 || earfcn > 262143) {
        unknown_layout(0xB0C0, (uint32_t)ver, b, n, "len/pci mismatch");
        return;
    }
    const char *mt = (seg <= 1) ? msg_type(0, ch, b + off, avail) : "(segment)";
    struct jb j; jb_init(&j, "rrc");
    jb_add(&j, ",\"src\":\"0xB0C0\",\"rat\":\"LTE\",\"ch\":\"%s\",\"msg\":\"%s\",\"pci\":%d,\"arfcn\":%ld,\"len\":%d,\"ver\":%d",
           ch_name(ch), mt, pci, earfcn, mlen, ver);
    int rbid_offset = ver >= 25 ? 5 : 3;
    jb_add(&j, ",\"rbid\":%u,\"pdu\":%d", b[rbid_offset], pdu);
    if (b[1] && (b[2] >> 4) <= 9 && (b[2] & 15) <= 9)
        jb_add(&j, ",\"rrc_release\":\"%u.%u.%u\"", b[1], b[2] >> 4, b[2] & 15);
    unsigned frame = rd16(b + (ver >= 25 ? 12 : ver >= 8 ? 10 : 8));
    jb_add(&j, ",\"sfn\":%u,\"subframe\":%u", frame >> 4, frame & 15);
    if (seg) jb_add(&j, ",\"seg\":%d", seg);
    if (guess) jb_add(&j, ",\"guess\":1");
    if (!guess && !seg && ch == CH_PCCH && !strcmp(mt, "Paging")) add_paging_details(&j, 0, b + off, avail);
    if (!guess && !seg) add_rrc_nas_transfer(&j, 0, ch, mt, b + off, avail);
    if (ch == CH_UL_CCCH && !strcmp(mt, "rrcConnectionRequest")) {   /* 1+1+1 choice bits, ue-Identity 1+40, cause 3 bits */
        int c = msbbits(b + off, avail, 44, 3);
        if (c >= 0) { jb_add(&j, ",\"cause\":\"%s\"", k_lte_cause[c]); snprintf(g_setup_cause, sizeof(g_setup_cause), "%s", k_lte_cause[c]); g_setup_cause_t = mono_ms(); }
    }
    const uint8_t *packet = b + off;
    int packet_len = avail;
    int joined = guess ? -1 : join_signal_segments(0, seg, ch, pci, earfcn, b[rbid_offset], &packet, &packet_len);
    if (joined) {
        if (seg && joined > 0) jb_add(&j, ",\"reassembled_len\":%d", packet_len);
        emit_rrc_signaling(&j, 0, ch, pci, packet, packet_len, joined > 0);
    }
    if (!guess && !seg && ch == CH_UL_DCCH && !strcmp(mt, "measurementReport"))
        emit_mr_sample("LTE", ver, 0, seg, b + off, avail);
    if (!guess && ch != CH_UNK) {
        serving_update(&g_lte, pci, earfcn, "B0C0");
        if (!seg && mt[0]) rrc_events("LTE", mt);
        if (!seg) ims_rrc_hook(0, mt);
    }
}

/* --- 0xB821 NR RRC OTA --- */
static int nr_ch(uint32_t ver, int pdu) {
    static const int OLD[] = { 0, CH_BCCH_BCH, CH_BCCH_DL_SCH, CH_DL_CCCH, CH_DL_DCCH, CH_PCCH, CH_UL_CCCH, CH_UL_CCCH1, CH_UL_DCCH, CH_RECONF, CH_RECONF_COMPLETE };
    static const int NEW[] = { 0, CH_BCCH_BCH, CH_BCCH_DL_SCH, CH_DL_CCCH, CH_DL_DCCH, CH_MCCH, CH_PCCH, CH_UL_CCCH, CH_UL_CCCH1, CH_UL_DCCH };
    if (ver == 0x09 || ver == 0x0c || ver == 0x0e || ver == 0x11 || ver == 0x13 || ver == 0x19)
        return (pdu >= 1 && pdu <= 10) ? OLD[pdu] : CH_UNK;
    if (pdu >= 1 && pdu <= 9) return NEW[pdu];
    if (ver == 0x14 || ver == 0x17 || ver == 0x18) {
        if (pdu == 10) return CH_RECONF;
        if (pdu == 11) return CH_RECONF_COMPLETE;
        return CH_UNK;
    }
    if (ver == 0x1a) { if (pdu == 11) return CH_RECONF; if (pdu == 12) return CH_RECONF_COMPLETE; return CH_UNK; }
    /* 0x1c and newer */
    if (pdu == 10) return CH_UL_DCCH;
    if (pdu == 11) return CH_RECONF;
    if (pdu == 12) return CH_RECONF_COMPLETE;
    return CH_UNK;
}
/* NR CGI (60 bits): bits 36..59 = PLMN (3GPP BCD, 3 bytes), bits 0..35 = NCI */
static int ncgi_decode(uint64_t ncgi, int *mcc, int *mnc, int *mnc_digits, uint64_t *nci) {
    if (!ncgi || (ncgi >> 60)) return 0;
    uint32_t p = (uint32_t)((ncgi >> 36) & 0xffffff);
    int b1 = (p >> 16) & 0xff, b2 = (p >> 8) & 0xff, b3 = p & 0xff;
    int m1 = b1 & 0xf, m2 = b1 >> 4, m3 = b2 & 0xf, n3 = b2 >> 4, n1 = b3 & 0xf, n2 = b3 >> 4;
    if (m1 > 9 || m2 > 9 || m3 > 9 || n1 > 9 || n2 > 9 || (n3 > 9 && n3 != 0xf)) return 0;
    *mcc = m1 * 100 + m2 * 10 + m3;
    if (n3 == 0xf) { *mnc = n1 * 10 + n2; *mnc_digits = 2; }
    else { *mnc = n1 * 100 + n2 * 10 + n3; *mnc_digits = 3; }
    *nci = ncgi & ((1ULL << 36) - 1);
    return 1;
}
static void parse_nr_rrc(const uint8_t *b, int n) {
    if (n < 23) return;
    uint32_t ver = rd32(b);
    int pci = rd16(b + 7), pdu, mlen, off, seg = 0; long arfcn;
    uint64_t ncgi = 0;
    int guess = 0;
    if (ver >= 0x11) ncgi = rd64(b + 9);
    if (ver == 0x09) { if (n < 24) return; arfcn = rd32(b + 9); pdu = b[17]; mlen = rd16(b + 22); off = 24; }
    else if (ver == 0x0c || ver == 0x0e) { arfcn = rd32(b + 9); pdu = b[16]; mlen = rd16(b + 21); off = 23; }
    else if (ver == 0x11) { if (n < 31) return; arfcn = rd32(b + 17); pdu = b[24]; mlen = rd16(b + 29); off = 31; }
    else if (ver == 0x13 || ver == 0x14 || ver == 0x19) { if (n < 32) return; arfcn = rd32(b + 17); pdu = b[24]; mlen = rd16(b + 29); off = 32; }
    else { /* 0x17 0x18 0x1a 0x1c, newer = guess with same layout */
        if (n < 35) return;
        if (!(ver == 0x17 || ver == 0x18 || ver == 0x1a || ver == 0x1c)) guess = 1;
        arfcn = rd32(b + 17); pdu = b[24]; mlen = rd16(b + 29); seg = b[34]; off = 35;
    }
    int avail = n - off;
    if (mlen != avail || pci > 1007 || arfcn > 3279165) {
        unknown_layout(0xB821, ver, b, n, "len/pci mismatch");
        return;
    }
    int ch = nr_ch(ver, pdu);
    /* unknown PDU ids (seen on SM8750 v26: 19-22 right after rrcRelease, likely stored SIB/config dumps): no msg decode */
    const char *mt = ch == CH_UNK ? "" : (seg <= 1) ? msg_type(1, ch, b + off, avail) : "(segment)";
    char chbuf[16];
    const char *chs = ch_name(ch);
    if (ch == CH_UNK) { snprintf(chbuf, sizeof(chbuf), "pdu%d", pdu); chs = chbuf; }
    struct jb j; jb_init(&j, "rrc");
    jb_add(&j, ",\"src\":\"0xB821\",\"rat\":\"NR\",\"ch\":\"%s\",\"msg\":\"%s\",\"pci\":%d,\"arfcn\":%ld,\"len\":%d,\"ver\":%u",
           chs, mt, pci, arfcn, mlen, ver);
    if (!guess) {
        const uint8_t *frame = b + (ver < 0x11 ? 13 : 21);
        uint32_t packed_frame = ver == 0x09 ? rd32(frame)
            : (uint32_t)frame[0] | ((uint32_t)frame[1] << 8) | ((uint32_t)frame[2] << 16);
        jb_add(&j, ",\"sfn_subframe\":%u", packed_frame);
    }
    if (!guess && b[4] && (b[5] >> 4) <= 9 && (b[5] & 15) <= 9)
        jb_add(&j, ",\"rrc_release\":\"%u.%u.%u\"", b[4], b[5] >> 4, b[5] & 15);
    if (!guess && !seg && ch == CH_PCCH && !strcmp(mt, "Paging")) add_paging_details(&j, 1, b + off, avail);
    int mcc, mnc, mncd; uint64_t nci; int have_cgi = ncgi_decode(ncgi, &mcc, &mnc, &mncd, &nci);
    if (have_cgi) jb_add(&j, ",\"mcc\":%d,\"mnc\":%d,\"mnc_digits\":%d,\"nci\":%llu,\"ncgi\":%llu", mcc, mnc, mncd,
                         (unsigned long long)nci, (unsigned long long)ncgi);
    if (seg) jb_add(&j, ",\"seg\":%d", seg);
    if (guess) jb_add(&j, ",\"guess\":1");
    jb_add(&j, ",\"rbid\":%u,\"pdu\":%d", b[6], pdu);
    if (!guess && !seg && (ch == CH_DL_CCCH || ch == CH_DL_DCCH || ch == CH_UL_DCCH))
        add_nr_rrc_controls(&j, mt, ch, b + off, avail);
    if (!guess && !seg && ch == CH_BCCH_BCH) add_nr_mib(&j, b + off, avail);
    if (!guess && !seg && ch == CH_BCCH_DL_SCH && !strcmp(mt, "SystemInformation"))
        add_nr_system_information(&j, b + off, avail);
    if (!guess && !seg) add_rrc_nas_transfer(&j, 1, ch, mt, b + off, avail);
    if (ch == CH_UL_CCCH && !strcmp(mt, "rrcSetupRequest")) {   /* 1+2 choice bits, ue-Identity 1+39, cause 4 bits */
        int c = msbbits(b + off, avail, 43, 4);
        if (c >= 0) { jb_add(&j, ",\"cause\":\"%s\"", k_nr_cause[c]); snprintf(g_setup_cause, sizeof(g_setup_cause), "%s", k_nr_cause[c]); g_setup_cause_t = mono_ms(); }
    }
    const uint8_t *packet = b + off;
    int packet_len = avail;
    int joined = guess ? -1 : join_signal_segments(1, seg, ch, pci, arfcn, b[6], &packet, &packet_len);
    if (joined) {
        if (seg && joined > 0) jb_add(&j, ",\"reassembled_len\":%d", packet_len);
        emit_rrc_signaling(&j, 1, ch, pci, packet, packet_len, joined > 0);
    }
    if (!guess && !seg && ch == CH_UL_DCCH && !strcmp(mt, "measurementReport"))
        emit_mr_sample("NR", ver, (b[4] << 8) | b[5], seg, b + off, avail);
    if (!guess && ch != CH_UNK) {
        serving_update(&g_nr, pci, arfcn, "B821");
        if (have_cgi && ch != CH_RECONF && ch != CH_RECONF_COMPLETE) {
            g_nr.mcc = mcc; g_nr.mnc = mnc; g_nr.mnc_digits = mncd; g_nr.cid = nci; g_nr.t_cell = mono_ms();
        }
        if (!seg && mt[0]) rrc_events("NR", mt);
        if (!seg) ims_rrc_hook(1, mt);
    }
}

/* --- 0xB0C2 LTE RRC serving cell info --- */
/* Bandwidth RB count (6/15/25/50/75/100) or 0xB184 0-5 code: MHz, else NA. */
static double lte_bw_mhz(int raw) {
    switch (raw) {
    case 6: case 0: return 1.4;
    case 15: case 1: return 3;
    case 25: case 2: return 5;
    case 50: case 3: return 10;
    case 75: case 4: return 15;
    case 100: case 5: return 20;
    default: return NA_F;
    }
}

/* B0C1 is modem-decoded broadcast data, independent of the RRC OTA MIB. */
static void parse_lte_mib(const uint8_t *b, int n) {
    int ver = n ? b[0] : 0;
    int channel_bytes = ver == 1 ? 2 : 4;
    int size = ver == 1 ? 9 : ver == 2 ? 11 : ver == 3 ? 12 : 0;
    if (!size || (n != size && n != size + 1)) {
        unknown_layout(0xB0C1, ver, b, n, "version/length"); return;
    }
    int pci = rd16(b + 1);
    long earfcn = channel_bytes == 2 ? rd16(b + 3) : rd32(b + 3);
    int antennas = b[5 + channel_bytes], prbs = b[6 + channel_bytes];
    /* Preserve the older layout with a byte between SFN and the antenna count. */
    if (n == size + 1 && lte_bw_mhz(prbs) == NA_F) {
        antennas = b[6 + channel_bytes];
        prbs = b[7 + channel_bytes];
    }
    if (pci > 503 || earfcn > 262143 || lte_bw_mhz(prbs) == NA_F) {
        unknown_layout(0xB0C1, ver, b, n, "sanity"); return;
    }
    struct jb j; jb_init(&j, "lte_mib");
    jb_add(&j, ",\"rat\":\"LTE\",\"src\":\"0xB0C1\",\"pci\":%d,\"earfcn\":%ld,\"sfn\":%u,\"tx_antennas\":%u,\"dl_bw_prb\":%d,\"ver\":%d",
           pci, earfcn, rd16(b + 3 + channel_bytes), antennas, prbs, ver);
    jb_f(&j, "bw_mhz", lte_bw_mhz(prbs));
    jb_emit(&j);
}

static void parse_lte_cellinfo(const uint8_t *b, int n) {
    int ver = n ? b[0] : 0, o;
    long dl, ul; int dlbw, ulbw; unsigned long cid; int tac, band, mcc, mncd, mnc, pci;
    if (ver == 2 && n >= 25) { pci = rd16(b + 1); dl = rd16(b + 3); ul = rd16(b + 5); o = 7; }
    else if (ver == 3 && n >= 29) { pci = rd16(b + 1); dl = rd32(b + 3); ul = rd32(b + 7); o = 11; }
    else { unknown_layout(0xB0C2, (uint32_t)ver, b, n, "version"); return; }
    dlbw = b[o]; ulbw = b[o + 1]; cid = rd32(b + o + 2); tac = rd16(b + o + 6); band = (int)rd32(b + o + 8);
    mcc = rd16(b + o + 12); mncd = b[o + 14]; mnc = rd16(b + o + 15);
    if (pci > 503 || dl > 262143) { unknown_layout(0xB0C2, ver, b, n, "sanity"); return; }
    struct jb j; jb_init(&j, "lte_cell");
    jb_add(&j, ",\"pci\":%d,\"earfcn\":%ld,\"band\":%d,"
               "\"tac\":%d,\"cid\":%lu,\"mcc\":%d,\"mnc\":%d,\"mnc_digits\":%d",
           pci, dl, band, tac, cid, mcc, mnc, mncd);
    if (ul >= 0 && ul <= 262143) jb_i(&j, "ul_earfcn", ul);
    double dl_mhz = lte_bw_mhz(dlbw);
    if (dl_mhz != NA_F) jb_i(&j, "dl_bw_prb", dlbw);
    if (lte_bw_mhz(ulbw) != NA_F) jb_i(&j, "ul_bw_prb", ulbw);
    jb_f(&j, "bw_mhz", dl_mhz);
    jb_emit(&j);
    serving_update(&g_lte, pci, dl, "B0C2");
    g_lte.band = band; g_lte.tac = tac; g_lte.cid = cid; g_lte.mcc = mcc; g_lte.mnc = mnc;
    g_lte.mnc_digits = mncd;
    if (dl_mhz != NA_F) g_lte.bw_mhz = dl_mhz;
    g_lte.t_cell = mono_ms();
}

/* --- 0xB823 NR RRC serving cell info --- */
static void parse_nr_cellinfo(const uint8_t *b, int n) {
    if (n < 4) return;
    int rel_min = rd16(b), rel_maj = rd16(b + 2);
    int pci, dlbw, ulbw, mcc, mncd, mnc, band; long dl, ul, tac; unsigned long long cid;
    if (rel_maj == 0 && rel_min == 4 && n >= 38) {
        pci = rd16(b + 4); dl = rd32(b + 6); ul = rd32(b + 10); dlbw = rd16(b + 14); ulbw = rd16(b + 16);
        cid = rd64(b + 18); mcc = rd16(b + 26); mncd = b[28]; mnc = rd16(b + 29); tac = rd32(b + 32); band = rd16(b + 36);
    } else if (rel_maj == 3 && (rel_min == 0 || rel_min == 2 || rel_min == 3 || rel_min == 4)) {
        int o = rel_min == 0 ? 4 : rel_min == 4 ? 8 : 7;
        if (n < o + 42) { unknown_layout(0xB823, ((uint32_t)rel_maj << 16 | (uint32_t)rel_min), b, n, "short"); return; }
        pci = rd16(b + o); dl = rd32(b + o + 10); ul = rd32(b + o + 14); dlbw = rd16(b + o + 18); ulbw = rd16(b + o + 20);
        cid = rd64(b + o + 22); mcc = rd16(b + o + 30); mncd = b[o + 32]; mnc = rd16(b + o + 33);
        tac = rd32(b + o + 36); band = rd16(b + o + 40);
    } else { unknown_layout(0xB823, ((uint32_t)rel_maj << 16 | (uint32_t)rel_min), b, n, "version"); return; }
    if (pci > 1007 || dl > 3279165) { unknown_layout(0xB823, ((uint32_t)rel_maj << 16 | (uint32_t)rel_min), b, n, "sanity"); return; }
    struct jb j; jb_init(&j, "nr_cell");
    jb_add(&j, ",\"pci\":%d,\"arfcn\":%ld,\"ul_arfcn\":%ld,\"freq_mhz\":%.2f,\"band\":%d,\"dl_bw_mhz\":%d,\"ul_bw_mhz\":%d,"
               "\"tac\":%ld,\"cid\":%llu,\"mcc\":%d,\"mnc\":%d,\"mnc_digits\":%d,\"ver\":\"%d.%d\"",
           pci, dl, ul, nr_freq(dl), band, dlbw, ulbw, tac, cid, mcc, mnc, mncd, rel_maj, rel_min);
    jb_emit(&j);
    serving_update(&g_nr, pci, dl, "B823");
    g_nr.band = band; g_nr.tac = tac; g_nr.cid = cid; g_nr.mcc = mcc; g_nr.mnc = mnc; g_nr.bw_mhz = dlbw;
    g_nr.mnc_digits = mncd;
    g_nr.t_cell = mono_ms();
}

/* --- 0xB17F LTE ML1 serving cell meas & eval --- */
static void parse_lte_b17f(const uint8_t *b, int n) {
    int ver = n ? b[0] : 0; long earfcn; int pcip; uint32_t mr, rq, rs;
    if (ver == 4 && n >= 32) { earfcn = rd16(b + 4); pcip = rd16(b + 6); mr = rd32(b + 8); rq = rd32(b + 16); rs = rd32(b + 20); }
    else if (ver == 5 && n >= 36) { earfcn = rd32(b + 4); pcip = rd16(b + 8); mr = rd32(b + 12); rq = rd32(b + 20); rs = rd32(b + 24); }
    else { unknown_layout(0xB17F, (uint32_t)ver, b, n, "version"); return; }
    int pci = pcip & 0x1ff;
    if (pci > 503 || earfcn > 262143) { unknown_layout(0xB17F, ver, b, n, "sanity"); return; }
    double rsrp = -180.0 + (mr & 0xfff) * 0.0625;
    double rsrq = -30.0 + (rq & 0x3ff) * 0.0625;
    double rssi = -110.0 + ((rs >> 10) & 0x7ff) * 0.0625;
    struct jb j; jb_init(&j, "lte_meas");
    jb_add(&j, ",\"src\":\"B17F\",\"pci\":%d,\"earfcn\":%ld", pci, earfcn);
    jb_f(&j, "rsrp", rsrp); jb_f(&j, "rsrq", rsrq); jb_f(&j, "rssi", rssi);
    jb_emit(&j);
    serving_update(&g_lte, pci, earfcn, "B17F");
    g_lte.rsrp = rsrp; g_lte.rsrq = rsrq; g_lte.rssi = rssi; g_lte.t_meas = mono_ms();
    snprintf(g_lte.src, sizeof(g_lte.src), "B17F");
}

#include "lte_b193_sinr.h"
/* --- 0xB193 LTE ML1 serving cell meas response (subpacket 0x19) --- */
/* B193 subpacket 0x19 v66 (SM8750). Field replay and qcom-band-menu B193_LAYOUTS:
   subpkt body: earfcn u32 @0, cell count in byte 4 low 3 bits, then cells of 144 bytes from byte 8.
   cell: RX mask at byte 0, PCI at bit 64, CC at bit 76, serving flag at bit 79,
         u32 @44 RSRP bits0-11 / filtered RSRP bits12-23 (x/16-180 dBm)  -> matches LTE measurementReport PCell RSRP
         u32 @56 RSRQ bits0-9 / filtered RSRQ bits20-31 (x/16-30 dB)     -> matches MR RSRQ and B17F
         u32 @68 RSSI bits0-10 (x/16-110 dBm)                              -> matches B17F RSSI
   RX SINR: 10-bit codes at bits 832/842/864/874, expressed as code/10 - 20 dB. */
/* 2.3: in RRC connected B193 arrives ~75x/s (run9: 12,825 packets in 170 s, 5.5 MB/h of JSON). The serving cell
   state still updates on every packet; the lte_meas JSON line is limited to one per cell every 500 ms with the mean
   RSRP/RSRQ/RSSI/SINR over the window. The raw QMDL keeps every packet. */
struct lm_acc { int used, pci, cc, n, sinr_count; long earfcn; int64_t t_emit; double rsrp, rsrq, rssi, sinr; };
static struct lm_acc g_lm[16];
static struct lm_acc *lm_get(int pci, long earfcn, int cc) {
    struct lm_acc *o = NULL;
    for (int i = 0; i < 16; i++) if (g_lm[i].used && g_lm[i].pci == pci && g_lm[i].earfcn == earfcn && g_lm[i].cc == cc) return &g_lm[i];
    for (int i = 0; i < 16; i++) if (!g_lm[i].used) { o = &g_lm[i]; break; }
    if (!o) { o = &g_lm[0]; for (int i = 1; i < 16; i++) if (g_lm[i].t_emit < o->t_emit) o = &g_lm[i]; }
    memset(o, 0, sizeof(*o)); o->used = 1; o->pci = pci; o->earfcn = earfcn; o->cc = cc; o->t_emit = -1000000;
    return o;
}
static unsigned g_b193_report;
static void parse_lte_b193_v66(const uint8_t *sb, int sbl, long earfcn, unsigned report) {
    int ncells = sb[4] & 7;
    if (ncells == 0) return;
    if (sbl < 8 + ncells * 144) { unknown_layout(0xB193, 66, sb, sbl, "cell length"); return; }
    layout_seen(0xB193, 66, sb, sbl);
    int neighbors = 0, neighbor_sinr = 0, invalid_sinr = 0;
    for (int c = 0; c < ncells; c++) {
        const uint8_t *cb = sb + 8 + c * 144;
        uint16_t v0 = rd16(cb + 8);
        int pci = v0 & 0x1ff, cc = (v0 >> 12) & 7, is_serving = v0 >> 15;
        uint32_t wr = rd32(cb + 44), wq = rd32(cb + 56), ws = rd32(cb + 68);
        double rsrp = -180.0 + (wr & 0xfff) / 16.0, rsrp_f = -180.0 + ((wr >> 12) & 0xfff) / 16.0;
        double rsrq = -30.0 + (wq & 0x3ff) / 16.0, rsrq_f = -30.0 + ((wq >> 20) & 0xfff) / 16.0;
        double rssi = -110.0 + (ws & 0x7ff) / 16.0;
        if (pci > 503 || rsrp < -144 || rsrp > -40) continue;
        struct lte_b193_sinr sn;
        lte_b193_v66_sinr(cb, &sn);
        double sinr = sn.combined;
        int rx_mask = cb[0] & 15;
        if (!is_serving) {
            neighbors++;
            if (sinr != NA_F) neighbor_sinr++;
            else invalid_sinr++;
        }
        struct lm_acc *a = lm_get(pci, earfcn, cc);
        a->n++; a->rsrp += rsrp; a->rsrq += rsrq; a->rssi += rssi;
        if (sinr != NA_F) { a->sinr += sinr; a->sinr_count++; }
        int64_t tm = mono_ms();
        if (tm - a->t_emit >= 500) {
            struct jb j; jb_init(&j, "lte_meas");
            jb_add(&j, ",\"src\":\"B193\",\"pci\":%d,\"earfcn\":%ld,\"cc\":%d,\"idx\":%d,\"is_serving\":%d", pci, earfcn, cc, c, is_serving);
            jb_add(&j, ",\"ca_report\":%u", report);
            jb_f(&j, "rsrp", a->rsrp / a->n); jb_f(&j, "rsrp_filt", rsrp_f); jb_f(&j, "rsrq", a->rsrq / a->n); jb_f(&j, "rsrq_filt", rsrq_f); jb_f(&j, "rssi", a->rssi / a->n);
            if (a->sinr_count) jb_f(&j, "sinr", a->sinr / a->sinr_count);
            if (is_serving) {
                /* RSRP/RSRQ chain layout is known for Rx0/Rx1 only.
                   SINR is every chain in this packet's RX mask, same sample as sinr. */
                static const int rp_bit[] = { 202, 236 }, rq_bit[] = { 384, 404 };
                static const char *const rp_name[] = { "rsrp_rx0", "rsrp_rx1" };
                static const char *const rq_name[] = { "rsrq_rx0", "rsrq_rx1" };
                for (int rx = 0; rx < 2; rx++) {
                    if (!(rx_mask & (1 << rx))) continue;
                    double rp = lebits(cb, rp_bit[rx], 12) / 16.0 - 180.0;
                    unsigned raw_rq = lebits(cb, rq_bit[rx], 10);
                    double rq = raw_rq / 16.0 - 30.0;
                    if (rp >= -160 && rp <= -20) jb_f(&j, rp_name[rx], rp);
                    if (raw_rq && rq <= 5) jb_f(&j, rq_name[rx], rq);
                }
                if (sn.combined > NA_F + 1) jb_f(&j, "sinr_max", sn.combined);
                for (int rx = 0; rx < 4; rx++) {
                    if (!(sn.valid_mask & (1 << rx))) continue;
                    char key[16];
                    snprintf(key, sizeof(key), "sinr_rx%d", rx);
                    jb_f(&j, key, sn.rx[rx]);
                }
            }
            jb_add(&j, ",\"n\":%d,\"subver\":66", a->n);
            jb_emit(&j);
            a->n = a->sinr_count = 0; a->rsrp = a->rsrq = a->rssi = a->sinr = 0; a->t_emit = tm;
        }
        if (is_serving && cc == 0) {
            serving_update(&g_lte, pci, earfcn, "B193");
            g_lte.rsrp = rsrp; g_lte.rsrq = rsrq; g_lte.rssi = rssi;
            g_lte.t_meas = tm; snprintf(g_lte.src, sizeof(g_lte.src), "B193");
            if (sinr != NA_F) {
                g_lte.sinr = sinr; g_lte.t_sinr = tm;
                g_lte.sinr_rx_mask = sn.valid_mask;
                for (int rx = 0; rx < 4; rx++) g_lte.sinr_rx[rx] = sn.rx[rx];
                snprintf(g_lte.sinr_src, sizeof(g_lte.sinr_src), "B193");
                snprintf(g_lte.sinr_agg, sizeof(g_lte.sinr_agg), "max_rx");
            }
        }
    }
    neighbor_decoded(0xB193, neighbors, neighbors, neighbors, neighbor_sinr, invalid_sinr);
}
static void parse_lte_b193(const uint8_t *b, int n) {
    if (n < 8 || b[0] != 1) { unknown_layout(0xB193, n ? b[0] : 0, b, n, "version"); return; }
    unsigned report = ++g_b193_report;
    int nsub = b[1], pos = 4;
    for (int s = 0; s < nsub && pos + 4 <= n; s++) {
        int id = b[pos], sv = b[pos + 1], size = rd16(b + pos + 2);
        if (size < 4 || size > n - pos) break;
        const uint8_t *sb = b + pos + 4;
        int sbl = size - 4;
        pos += size;
        if (id != 0x19 || sbl < 8) continue;
        long earfcn = rd32(sb); int ncells = rd16(sb + 4);
        if (earfcn < 0 || earfcn > 262143) continue;
        int cell0 = 0, csz = 0, snr_off = 0;
        if (sv == 36) { cell0 = 8; csz = 128; snr_off = 80; }
        else if (sv == 48 || sv == 50) { cell0 = 12; csz = 140; snr_off = 92; }
        else if (sv == 66) { parse_lte_b193_v66(sb, sbl, earfcn, report); continue; }
        if (!csz) {
            unknown_layout(0xB193, (uint32_t)sv, b, n, "subpkt version");
            continue;
        }
        for (int c = 0; c < ncells && c < 8; c++) {
            if (cell0 + (c + 1) * csz > sbl) break;
            const uint8_t *cb = sb + cell0 + c * csz;
            uint16_t v0 = rd16(cb);
            int pci = v0 & 0x1ff, scell_idx = (v0 >> 9) & 7, is_scell = (v0 >> 12) & 1;
            if (pci > 503) continue;
            const uint8_t *rb = cb + 16;
            double rsrp = -180.0 + lebits(rb, 108, 12) * 0.0625 + 40.0;
            double rsrq = -30.0 + lebits(rb, 224, 10) * 0.0625;
            double rssi = -110.0 + lebits(rb, 320, 11) * 0.0625;
            double snr0 = lebits(cb + snr_off, 0, 9) * 0.1 - 20.0;
            double snr1 = lebits(cb + snr_off, 9, 9) * 0.1 - 20.0;
            struct jb j; jb_init(&j, "lte_meas");
            jb_add(&j, ",\"src\":\"B193\",\"pci\":%d,\"earfcn\":%ld,\"cc\":%d,\"is_scell\":%d", pci, earfcn, scell_idx, is_scell);
            jb_f(&j, "rsrp", rsrp); jb_f(&j, "rsrq", rsrq); jb_f(&j, "rssi", rssi);
            jb_f(&j, "sinr", snr0); jb_f(&j, "sinr_rx0", snr0); jb_f(&j, "sinr_rx1", snr1);
            jb_add(&j, ",\"subver\":%d", sv);
            jb_emit(&j);
            if (c == 0 && scell_idx == 0) {
                serving_update(&g_lte, pci, earfcn, "B193");
                g_lte.rsrp = rsrp; g_lte.rsrq = rsrq; g_lte.rssi = rssi; g_lte.sinr = snr0;
                g_lte.t_meas = g_lte.t_sinr = mono_ms();
                g_lte.sinr_rx_mask = 0;
                g_lte.sinr_agg[0] = 0;
                snprintf(g_lte.src, sizeof(g_lte.src), "B193");
                snprintf(g_lte.sinr_src, sizeof(g_lte.sinr_src), "B193");
            }
        }
    }
}

enum lte_neighbor_layout { LTE_B180, LTE_B192_FLAT, LTE_NEIGHBOR_IDENTITY, LTE_NEIGHBOR_RESPONSE };
enum { LTE_NEIGHBOR_CELL_LIMIT = 128 };

/* B179 carries calibrated filtered neighbor readings. Detected-only cells at the end carry timing, not RSRP/RSRQ. */
static void parse_lte_b179(const uint8_t *b, int n) {
    int version = n ? b[0] : 0;
    int narrow = version == 3;
    int header = narrow ? 22 : 28;
    if (n < header || (version != 3 && version != 4 && version != 56)) {
        unknown_layout(0xB179, version, b, n, "version or length");
        return;
    }
    uint32_t earfcn = narrow ? rd16(b + 8) : rd32(b + 8);
    int serving_pci = rd16(b + (narrow ? 10 : 12));
    int count = b[narrow ? 20 : 24], detected = b[narrow ? 21 : 25];
    if (earfcn > 262143 || serving_pci > 503 || count > LTE_NEIGHBOR_CELL_LIMIT ||
        n != header + 12 * count + 16 * detected) {
        unknown_layout(0xB179, version, b, n, "carrier or cell count");
        return;
    }
    layout_seen(0xB179, version, b, n);
    struct jb j; jb_init(&j, "lte_meas");
    jb_add(&j, ",\"src\":\"B179\",\"earfcn\":%u,\"subver\":%d,\"cells\":[", earfcn, version);
    int emitted = 0, power_count = 0, quality_count = 0;
    for (int i = 0; i < count; i++) {
        const uint8_t *cell = b + header + 12 * i;
        int pci = rd16(cell);
        if (pci > 503 || pci == serving_pci) continue;
        double rsrp = rd16(cell + 2) * 0.0625 - 180.0;
        double rsrq = rd16(cell + 6) * 0.0625 - 30.0;
        jb_add(&j, "%s{\"pci\":%d", emitted++ ? "," : "", pci);
        if (rsrp >= -160.0 && rsrp <= -20.0) { jb_f(&j, "rsrp", rsrp); power_count++; }
        if (rsrq >= -30.0 && rsrq <= 5.0) { jb_f(&j, "rsrq", rsrq); quality_count++; }
        jb_add(&j, "}");
    }
    jb_add(&j, "]");
    jb_emit(&j);
    neighbor_decoded(0xB179, emitted, power_count, quality_count, 0, 0);
}

static int b180_populated(const uint8_t *cell) {
    return rd32(cell) | rd32(cell + 4) | rd32(cell + 8) | rd32(cell + 12) | rd32(cell + 16) | rd32(cell + 20);
}

static void emit_lte_neighbors(uint16_t code, uint32_t earfcn, int version, const uint8_t *cells,
                               int count, int stride, enum lte_neighbor_layout layout) {
    struct jb j; jb_init(&j, "lte_meas");
    jb_add(&j, ",\"src\":\"%04X\",\"earfcn\":%u,\"subver\":%d,\"cells\":[", (unsigned)code, earfcn, version);
    int emitted = 0, power_count = 0, quality_count = 0;
    for (int i = 0; i < count; i++) {
        const uint8_t *cell = cells + i * stride;
        uint32_t word = rd32(cell);
        int pci = layout == LTE_B192_FLAT ? word >> 23 : word & 511;
        if (pci > 503) continue;
        if (layout == LTE_B180 && !b180_populated(cell)) continue;
        jb_add(&j, "%s{\"pci\":%d", emitted++ ? "," : "", pci);
        if (layout != LTE_NEIGHBOR_IDENTITY) {
            unsigned rp, rq;
            if (layout == LTE_NEIGHBOR_RESPONSE) {
                rp = rd16(cell + 12) & 4095;
                rq = (rd32(cell + 20) >> 10) & 1023;
            } else {
                rp = (layout == LTE_B180 ? word >> 20 : word) & 4095;
                rq = (rd32(cell + 8) >> 12) & 1023;
            }
            double rsrp = rp * 0.0625 - 180.0;
            double rsrq = rq * 0.0625 - 30.0;
            if (rsrp >= -160 && rsrp <= -20) { jb_f(&j, "rsrp", rsrp); power_count++; }
            if (rq && rsrq <= 5) { jb_f(&j, "rsrq", rsrq); quality_count++; }
        }
        jb_add(&j, "}");
    }
    if (emitted) { jb_add(&j, "]"); jb_emit(&j); }
    neighbor_decoded(code, emitted, power_count, quality_count, 0, 0);
}

/* LTE Neighbor Measurements. The version 5 stride and carrier match modem diagnostic prints. */
static void parse_lte_b180(const uint8_t *b, int n) {
    int version = n ? b[0] : 0;
    int header = version == 5 ? 12 : 8, stride = 32;
    if (n < header || (version != 4 && version != 5) || (version == 4 && b[1] > 1)) {
        unknown_layout(0xB180, version, b, n, "version or length");
        return;
    }
    int bytes = n - header, padded_layout = version == 4 && b[1] == 0;
    if (padded_layout && bytes && bytes % 28 == 0) {
        if (bytes % 32 == 0) { unknown_layout(0xB180, version, b, n, "ambiguous cell stride"); return; }
        stride = 28;
    }
    uint32_t earfcn = version == 5 ? rd32(b + 4) : rd16(b + 4);
    int count = bytes / stride;
    if (bytes % stride != 0 || earfcn > 262143 || count > LTE_NEIGHBOR_CELL_LIMIT) {
        unknown_layout(0xB180, version, b, n, "carrier or length");
        return;
    }
    int expected_count = count;
    if (padded_layout && stride == 32) {
        expected_count = 0;
        for (int i = 0; i < count; i++) expected_count += b180_populated(b + header + i * stride) != 0;
    }
    if (stride == 32 && b[version == 5 ? 8 : 6] >> 6 != expected_count % 4) {
        unknown_layout(0xB180, version, b, n, "cell count");
        return;
    }
    layout_seen(0xB180, version, b, n);
    emit_lte_neighbors(0xB180, earfcn, version, b + header, count, stride, LTE_B180);
}

/* B192 has both a flat LTE neighbor measurement and a subpacket-based ML1 response. */
static void parse_lte_neighbor_response(uint16_t code, const uint8_t *b, int n) {
    int connected = code == 0xB195;
    if (!connected && n >= 1 && (b[0] == 4 || b[0] == 5)) {
        int wide = b[0] == 5;
        int header = wide ? 12 : 8;
        if (n < header) return;
        uint32_t earfcn = wide ? rd32(b + 4) : rd16(b + 4);
        uint32_t count = (wide ? rd32(b + 8) : rd16(b + 6)) >> 6;
        int available = n - header;
        if (earfcn > 262143 || count > LTE_NEIGHBOR_CELL_LIMIT || count > (uint32_t)(available + 4) / 32) {
            unknown_layout(code, b[0], b, n, "carrier or cell count");
            return;
        }
        layout_seen(code, b[0], b, n);
        emit_lte_neighbors(code, earfcn, b[0], b + header, (int)count, 32, LTE_B192_FLAT);
        return;
    }
    if (n < 4 || b[0] != 1) { unknown_layout(code, n ? b[0] : 0, b, n, "version"); return; }
    int pos = 4;
    for (int i = 0; i < b[1] && pos + 4 <= n; i++) {
        int id = b[pos], version = b[pos + 1], size = rd16(b + pos + 2);
        if (size < 4 || size > n - pos) return;
        const uint8_t *sub = b + pos + 4;
        pos += size;
        if (id != (connected ? 31 : 27)) continue;
        int legacy = connected && (version == 3 || version == 4);
        int narrow = version == (connected ? 3 : 2);
        int supported = connected ? legacy || version == 40 || version == 56 || version == 57
                                  : narrow || version == 4 || version == 56;
        if (!supported) {
            unknown_layout(code, (uint32_t)version, sub, size - 4, "response subpacket version");
            continue;
        }
        int header = narrow ? 4 : 8;
        if (size - 4 < header) continue;
        uint32_t earfcn = narrow ? rd16(sub) : rd32(sub) & 0x3FFFF;
        int count = rd16(sub + (narrow ? 2 : 4));
        count &= connected ? 127 : 63;
        const int cell_size = 52;
        if (count > LTE_NEIGHBOR_CELL_LIMIT || count > (size - 4 - header) / cell_size) {
            unknown_layout(code, (uint32_t)version, sub, size - 4, "response cell count");
            continue;
        }
        layout_seen(code, version, sub, size - 4);
        /* The B192 v56 and B195 v57 field capture retains the 52-byte
         * per-cell response layout used by the older measurement subpackets. */
        int measured = !connected || legacy || version == 57;
        emit_lte_neighbors(code, earfcn, version, sub + header, count, cell_size,
                           measured ? LTE_NEIGHBOR_RESPONSE : LTE_NEIGHBOR_IDENTITY);
    }
}

/* --- 0xB96A NR ML1 Searcher FW Cell Meas Confirm --- */
struct nr_meas_window { uint32_t arfcn; int cells_off, count; };
enum { NR_MEAS_WINDOW_LIMIT = 5 * 7 };

static int b96a_raster_windows(const uint8_t *b, int n, uint32_t version, int cell_size,
                               struct nr_meas_window *windows) {
    int off = 24, window_count = 0;
    if (n < off) return -1;
    int max_rasters = version == 0x20006 ? 5 : 4;
    int max_cells = version == 0x20006 ? 12 : 48;
    int window_count_shift = version == 0x2000F ? 55 : 54;
    int rasters = b[16] & 7;
    if (rasters > max_rasters) return -1;
    for (int raster = 0; raster < rasters; raster++) {
        if (n - off < 24) return -1;
        int count = (rd64(b + off + 16) >> window_count_shift) & 7;
        off += 24;
        for (int window = 0; window < count; window++) {
            if (n - off < 8) return -1;
            uint32_t channel = rd32(b + off);
            int cells = (rd16(b + off + 4) >> 4) & 0x3F;
            off += 8;
            if ((cells && (!channel || channel > 3279165)) || cells > max_cells || cells > (n - off) / cell_size) return -1;
            windows[window_count++] = (struct nr_meas_window){ channel, off, cells };
            off += cells * cell_size;
        }
    }
    return off == n ? window_count : -1;
}

/* In flat 2.14/2.15 packets, the header identifies the measured PCI. */
static int b96a_flat_windows(const uint8_t *b, int n, struct nr_meas_window *windows) {
    int off = 48, count = 0;
    if (n < off) return -1;
    while (off < n) {
        if (n - off < 8 || count == NR_MEAS_WINDOW_LIMIT) return -1;
        uint32_t channel = rd32(b + off);
        int cells = (b[off + 4] >> 4) & 0xF;
        off += 8;
        if ((cells && (!channel || channel > 3279165)) || cells > (n - off) / 36) return -1;
        windows[count++] = (struct nr_meas_window){ channel, off, cells };
        off += cells * 36;
    }
    return count;
}

static void parse_nr_b96a(const uint8_t *b, int n) {
    if (n < 4) { unknown_layout(0xB96A, 0, b, n, "short header"); return; }
    uint32_t version = rd32(b);
    layout_seen(0xB96A, version, b, n);
    int cell_size = 32, window_count = -1, packet_pci = -1;
    struct nr_meas_window windows[NR_MEAS_WINDOW_LIMIT];
    switch (version) {
    case 0x20006: case 0x20008: case 0x2000A: case 0x2000D: case 0x2000E: case 0x2000F: break;
    default: unknown_layout(0xB96A, version, b, n, "version"); return;
    }
    if (version != 0x2000E) {
        if (version == 0x2000F) cell_size = 36;
        window_count = b96a_raster_windows(b, n, version, cell_size, windows);
        if (version == 0x2000D && window_count < 0) {
            cell_size = 36;
            window_count = b96a_raster_windows(b, n, version, cell_size, windows);
        }
    }
    if (window_count < 0 && (version == 0x2000E || version == 0x2000F)) {
        cell_size = 36;
        window_count = b96a_flat_windows(b, n, windows);
        if (window_count >= 0) {
            packet_pci = (rd16(b + 17) >> 5) & 0x3FF;
            if (packet_pci > 1007) window_count = -1;
        }
    }
    if (window_count < 0) {
        unknown_layout(0xB96A, version, b, n, "raster/window lengths or identity");
        return;
    }

    int candidates = 0, cells_with_sinr = 0, invalid_sinr = 0;
    for (int w = 0; w < window_count; w++) {
        const struct nr_meas_window *window = &windows[w];
        candidates += window->count;
        struct jb j; jb_init(&j, "nr_neighbor_sinr");
        jb_add(&j, ",\"rat\":\"NR\",\"src\":\"B96A\",\"ver\":\"%u.%u\",\"arfcn\":%u,\"cells\":[",
               version >> 16, version & 0xFFFF, window->arfcn);
        int emitted = 0;
        for (int i = 0; i < window->count; i++) {
            const uint8_t *cell = b + window->cells_off + i * cell_size;
            uint32_t identity = rd32(cell);
            int pci = packet_pci >= 0 ? packet_pci : (int)((identity >> 16) & 0x3FF);
            if (pci > 1007) continue;
            int rx0 = (int16_t)rd16(cell + 12), rx1 = (int16_t)rd16(cell + 14);
            if (rx0 < -23 * 128 || rx0 > 40 * 128) rx0 = INT16_MIN;
            if (rx1 < -23 * 128 || rx1 > 40 * 128) rx1 = INT16_MIN;
            int snr = rx0 > rx1 ? rx0 : rx1;
            if (snr == INT16_MIN) { invalid_sinr++; continue; }
            jb_add(&j, "%s{\"pci\":%d,\"sinr\":%.3f", emitted++ ? "," : "", pci, snr / 128.0);
            if (packet_pci < 0) jb_i(&j, "ssb", identity >> 26);
            jb_add(&j, "}");
        }
        if (emitted) { jb_add(&j, "]"); jb_emit(&j); }
        cells_with_sinr += emitted;
    }
    neighbor_decoded(0xB96A, candidates, 0, 0, cells_with_sinr, invalid_sinr);
}

/* --- 0xB97F NR ML1 measurement database update --- */
static int b97f_cell_count(const uint8_t *layer, int count_at, int use_index) {
    int count = layer[count_at];
    if (use_index && (count == 0 || count == 0xFF)) {
        int fallback = layer[count_at == 4 ? 5 : 8];
        if (fallback != 0xFF) return fallback;
    }
    return count == 0xFF ? 0 : count;
}

static int b97f_layout_fits(const uint8_t *b, int n, int off, int nlayers,
                            int lsz, int bsz, int count_at, int use_index) {
    for (int layer = 0; layer < nlayers; layer++) {
        if (n - off < lsz) return 0;
        const uint8_t *lb = b + off;
        uint32_t arfcn = rd32(lb);
        int serving_pci = rd16(lb + 6), cells = b97f_cell_count(lb, count_at, use_index);
        if (!arfcn || arfcn > 3279165 || (serving_pci > 1007 && serving_pci != 0xFFFF) || cells > 64) return 0;
        off += lsz;
        for (int cell = 0; cell < cells; cell++) {
            if (n - off < 16 || rd16(b + off) > 1007) return 0;
            unsigned beams = b[off + 4];
            if (beams > 64 || beams > (unsigned)(n - off - 16) / bsz) return 0;
            off += 16 + beams * bsz;
        }
    }
    if (n - off > 3) return 0;
    while (off < n) if (b[off++] != 0) return 0;
    return 1;
}

static int b97f_neighbor_beam(const uint8_t *beams, unsigned count, int stride) {
    if (count == 1) return rd16(beams);
    int best = -1, tied = 0;
    double best_rsrp = -156.0;
    for (unsigned i = 0; i < count; i++) {
        const uint8_t *beam = beams + i * stride;
        int ssb = rd16(beam);
        double rsrp = q7(rd32(beam + stride - 16));
        if (ssb >= 64 || rsrp <= -156.0 || rsrp > -31.0) continue;
        if (rsrp > best_rsrp) {
            best = ssb;
            best_rsrp = rsrp;
            tied = 0;
        } else if (rsrp == best_rsrp && ssb != best) {
            tied = 1;
        }
    }
    return tied ? -1 : best;
}

static void parse_nr_b97f(const uint8_t *b, int n) {
    if (n < 20) { unknown_layout(0xB97F, 0, b, n, "short header"); return; }
    int rel_min = rd16(b), rel_maj = rd16(b + 2);
    uint32_t version = rd32(b);
    int nlayers, off, lsz, bsz, guess = 0;
    if (rel_maj == 2 && rel_min == 7) { nlayers = b[4]; off = 16; lsz = 32; bsz = 44; }
    else if (rel_maj == 2 && (rel_min == 9 || rel_min == 10)) { nlayers = b[8]; off = 20; lsz = 32; bsz = rel_min == 10 ? 84 : 44; }
    else if (rel_maj == 3) { nlayers = b[8]; off = 20; lsz = 40; bsz = 84; guess = rel_min != 0; }
    else { unknown_layout(0xB97F, ((uint32_t)rel_maj << 16 | (uint32_t)rel_min), b, n, "version"); return; }
    if (nlayers <= 0 || nlayers > 16) { unknown_layout(0xB97F, ((uint32_t)rel_maj << 16 | (uint32_t)rel_min), b, n, "num_layers"); return; }
    int count_at = rel_maj == 2 && rel_min == 7 ? 4 : 5, use_index = 0;
    int matched = 0;
    for (; use_index <= 1 && !matched; use_index++) {
        if (b97f_layout_fits(b, n, off, nlayers, lsz, bsz, count_at, use_index)) {
            matched = 1;
        } else if (rel_maj == 2 && count_at == 5 &&
                   b97f_layout_fits(b, n, off, nlayers, lsz, bsz, 4, use_index)) {
            /* Some 2.9/2.10 modems retain the 2.7 carrier field order. */
            count_at = 4;
            matched = 1;
        }
    }
    if (!matched) { unknown_layout(0xB97F, version, b, n, "carrier/cell/beam layout"); return; }
    use_index--;
    layout_seen(0xB97F, version, b, n);
    for (int L = 0; L < nlayers; L++) {
        const uint8_t *lb = b + off;
        long arfcn = rd32(lb);
        int ncells = b97f_cell_count(lb, count_at, use_index);
        int spci = rd16(lb + 6), sssb = lb[count_at == 4 ? 8 : 9];
        double layer_rsrp = q7(rd32(lb + 12));
        double rx0 = NA_F, rx1 = NA_F;
        off += lsz;
        int nonserv = (spci == 0xFFFF);
        struct jb j; jb_init(&j, "nr_meas");
        if (nonserv) jb_add(&j, ",\"src\":\"B97F\",\"layer\":%d,\"arfcn\":%ld,\"freq_mhz\":%.2f,\"serving\":0", L, arfcn, nr_freq(arfcn));
        else {
            jb_add(&j, ",\"src\":\"B97F\",\"layer\":%d,\"arfcn\":%ld,\"freq_mhz\":%.2f,\"pci\":%d", L, arfcn, nr_freq(arfcn), spci);
            if (sssb < 64) jb_i(&j, "ssb", sssb);
        }
        double srsrp = NA_F, srsrq = NA_F;
        jb_add(&j, ",\"cells\":[");
        int first = 1, neighbors = 0, power_count = 0, quality_count = 0;
        for (int c = 0; c < ncells; c++) {
            const uint8_t *cb = b + off;
            int pci = rd16(cb);
            unsigned nb = cb[4];
            double rp = q7(rd32(cb + 8)), rq = q7(rd32(cb + 12));
            if (rp == -156.0) rp = NA_F;
            if (rq == -43.0) rq = NA_F;
            if (pci == spci && sssb < 64) {
                /* The serving beam contains RX0/1. The layer header contains other RX chains. */
                for (uint32_t i = 0; i < nb; i++) {
                    const uint8_t *beam = cb + 16 + i * bsz;
                    if (rd16(beam) != sssb) continue;
                    rx0 = q7(rd32(beam + 20));
                    rx1 = q7(rd32(beam + 24));
                    break;
                }
            }
            off += 16 + nb * bsz;
            if (pci == spci && srsrp <= NA_F + 1) { srsrp = rp; srsrq = rq; }
            if (pci != spci && rp == NA_F && rq == NA_F) continue;
            if (pci != spci) { neighbors++; power_count += rp != NA_F; quality_count += rq != NA_F; }
            jb_add(&j, "%s{\"pci\":%d", first ? "" : ",", pci);
            jb_f(&j, "rsrp", rp); jb_f(&j, "rsrq", rq);
            if (pci != spci && !guess && nb) {
                int ssb = b97f_neighbor_beam(cb + 16, nb, bsz);
                if (ssb >= 0 && ssb < 64) jb_i(&j, "ssb", ssb);
            }
            jb_add(&j, "}");
            first = 0;
        }
        jb_add(&j, "]");
        if (!nonserv) {
            if (!guess && rx0 > -156 && rx0 < -20) jb_f(&j, "rsrp_rx0", rx0);
            if (!guess && rx1 > -156 && rx1 < -20) jb_f(&j, "rsrp_rx1", rx1);
            jb_f(&j, "rsrp", srsrp > NA_F + 1 ? srsrp : layer_rsrp);
            jb_f(&j, "rsrq", srsrq);
        }
        jb_add(&j, ",\"ver\":\"%d.%d\"", rel_maj, rel_min);
        if (guess) jb_add(&j, ",\"guess\":1");
        jb_emit(&j);
        if (!guess && !nonserv && (L == 0 || g_nr.arfcn == arfcn)) {
            serving_update(&g_nr, spci, arfcn, "B97F");
            g_nr.rsrp = srsrp > NA_F + 1 ? srsrp : layer_rsrp; g_nr.rsrq = srsrq;
            g_nr.t_meas = mono_ms(); snprintf(g_nr.src, sizeof(g_nr.src), "B97F");
        }
        if (!guess) neighbor_decoded(0xB97F, neighbors, power_count, quality_count, 0, 0);
    }
}

/* --- 0xB822 NR MIB (only PCI/ARFCN for state) --- */
static void parse_nr_mib(const uint8_t *b, int n) {
    if (n < 14) return;
    int rel_min = rd16(b), rel_maj = rd16(b + 2);
    (void)rel_min;
    /* SCAT: v0.x/v2.x '<HI' pci + nrarfcn at 4; v3.x? keep conservative */
    int pci = rd16(b + 4); long arfcn = rd32(b + 6);
    if (rel_maj > 3 || pci > 1007 || arfcn <= 0 || arfcn > 3279165) { unknown_layout(0xB822, ((uint32_t)rel_maj << 16 | (uint32_t)rel_min), b, n, "sanity"); return; }
    struct jb j; jb_init(&j, "nr_mib");
    jb_add(&j, ",\"pci\":%d,\"arfcn\":%ld,\"ver\":\"%d.%d\"", pci, arfcn, rel_maj, rel_min);
    jb_emit(&j);
}

/* ------------------------------------------------------------------ */
/* 1.3 NR SINR / PDSCH parsers.                                         */
/* Layout sources (see static2/static_logcode_analysis.md):             */
/*  - MobileInsight dm_collector_c (B975 v2.1, B8DD v7, B888 v2.2)       */
/* No device sample yet -> every value here is tagged "tentative":1 and  */
/* cross-checked (PCI == serving PCI, RSRP close to B97F) before use.    */
/* ------------------------------------------------------------------ */
static inline int32_t rds32(const uint8_t *p) { return (int32_t)rd32(p); }

/* Linear SNR: convert the maximum valid Q10 sample (6..0x9C4000) to dB. */
static double snr_q10_max(const uint8_t *p, int n, int stride, int *nvalid, int *nbad) {
    int32_t best = 0; *nvalid = 0; *nbad = 0;
    for (int i = 0; i < n; i++) {
        int32_t v = rds32(p + i * stride);
        if (v == 0) continue;
        if (v >= 6 && v <= 0x9C4000) { (*nvalid)++; if (v > best) best = v; }
        else (*nbad)++;
    }
    if (best <= 0) return NA_F;
    return 10.0 * log10((double)best / 1024.0);
}

static void nr_set_sinr(double v, const char *src, int prio, int pci) {
    if (v < -23.0 || v > 40.0) return;
    if (pci >= 0 && g_nr.pci != NA_I && pci != g_nr.pci) return;
    int64_t now = mono_ms();
    /* Prefer B975 (3), then B8D8 (2), then B8DD (1). Lower priority can replace a sample after 3 s. */
    if (g_nr.t_sinr && prio < g_nr.sinr_prio && now - g_nr.t_sinr < 3000) return;
    g_nr.sinr = v; g_nr.t_sinr = now; g_nr.sinr_prio = prio;
    snprintf(g_nr.sinr_src, sizeof(g_nr.sinr_src), "%s", src);
}

static int64_t g_snr_line_t[8];
static int snr_throttle(int idx) {
    int64_t now = mono_ms();
    if (g_snr_line_t[idx] && now - g_snr_line_t[idx] < 1000) return 0;
    g_snr_line_t[idx] = now; return 1;
}

/* --- 0xB975 NR ML1 Serving Cell Beam Management --- */
/* 1.4: version-dispatched layouts (body offsets incl. the 4-byte version word).
   MobileInsight v2.1 documents RSRP at 12 and RSRQ at 16, without an SNR field.
   1.2        : pci@4(10b) rsrp@20 rsrq@24 snr@32 (Q16 linear)
   1.3/1.4/1.5: pci@4(10b) rsrp@12 rsrq@16 snr@24 (Q16 linear)
   2.1        : pci@4(10b) rsrp@12 rsrq@16 snr@24 (s32 /128 dB)
   other 2.x  : try the 2.1 layout, tagged tentative
   other      : old heuristic (both Q16 layouts), tagged tentative                                 */
static void parse_nr_b975(const uint8_t *b, int n) {
    if (n < 40) return;
    int rel_min = rd16(b), rel_maj = rd16(b + 2);
    uint32_t ver = ((uint32_t)rel_maj << 16 | (uint32_t)rel_min);
    layout_seen(0xB975, ver, b, n);
    static const int L[3][4] = { { 12, 16, 24, 0 }, { 20, 24, 32, 0 }, { 12, 16, 24, 1 } };  /* rsrp, rsrq, snr, snr_is_db128 */
    int cand[2], nc = 0, tentative = 0;
    if (rel_maj == 1 && rel_min >= 3 && rel_min <= 5) cand[nc++] = 0;
    else if (rel_maj == 1 && rel_min == 2) cand[nc++] = 1;
    else if (rel_maj == 2 && rel_min == 1) cand[nc++] = 2;
    else if (rel_maj >= 2) { cand[nc++] = 2; tentative = 1; }
    else { cand[nc++] = 0; cand[nc++] = 1; tentative = 1; }
    for (int c = 0; c < nc; c++) {
        int k = cand[c];
        if (n < L[k][2] + 4) continue;
        int pci = rd16(b + 4) & 0x3ff;
        double rsrp = rds32(b + L[k][0]) / 128.0, rsrq = rds32(b + L[k][1]) / 128.0;
        if (rsrp < -156 || rsrp > -31 || rsrq < -43 || rsrq > 20) continue;
        if (g_nr.pci != NA_I && pci != g_nr.pci) continue;
        if (tentative && g_nr.rsrp > NA_F + 1 && fabs(rsrp - g_nr.rsrp) > 12) continue;
        double snr;
        if (L[k][3]) { int32_t sv = rds32(b + L[k][2]); snr = (sv == 0 || sv == (int32_t)0x80000000) ? NA_F : sv / 128.0; }
        else { uint32_t sl = rd32(b + L[k][2]); snr = sl ? 10.0 * log10(sl / 65536.0) : NA_F; }
        if (snr > NA_F + 1 && (snr < -23 || snr > 40)) { if (tentative) continue; snr = NA_F; }
        if (snr_throttle(0)) {
            struct jb j; jb_init(&j, "nr_snr");
            jb_add(&j, ",\"src\":\"B975\",\"pci\":%d,\"layout\":\"%s\",\"ver\":\"%d.%d\",\"tentative\":%d", pci,
                   k == 0 ? "v1.3-1.5" : k == 1 ? "v1.2" : "v2.1", rel_maj, rel_min, tentative);
            jb_f(&j, "rsrp", rsrp); jb_f(&j, "rsrq", rsrq); jb_f(&j, "sinr", snr);
            jb_emit(&j);
        }
        if (!tentative && snr > NA_F + 1) nr_set_sinr(snr, "B975", 3, pci);
        return;
    }
}

static void emit_nr_snr(const char *source, uint32_t version, int pci, long arfcn, int carrier,
                        int trs, const uint8_t *samples, int stride, int throttle) {
    int valid, invalid;
    double snr = snr_q10_max(samples, 4, stride, &valid, &invalid);
    if (!valid || snr < -23.0 || snr > 40.0) return;
    if (carrier != 0 || pci != g_nr.pci || arfcn != g_nr.arfcn || pci < 0 || arfcn <= 0) return;
    if (snr_throttle(throttle)) {
        struct jb j;
        jb_init(&j, "nr_snr");
        jb_add(&j, ",\"rat\":\"NR\",\"src\":\"%s\",\"pci\":%d,\"arfcn\":%ld,\"rs\":\"%s\",\"carrier\":%d,\"ver\":\"%u.%u\",\"tentative\":0",
               source, pci, arfcn, trs ? "TRS" : "SSB", carrier, version >> 16, version & 0xffff);
        jb_f(&j, "sinr", snr);
        for (int i = 0; i < 4; i++) {
            int32_t sample = rds32(samples + i * stride);
            if (sample >= 6 && sample < 0x9C4000)
                jb_add(&j, ",\"sinr_rx%d\":%.2f", i, 10.0 * log10(sample / 1024.0));
        }
        jb_emit(&j);
    }
    if (!trs) nr_set_sinr(snr, source, !strcmp(source, "B8D8") ? 2 : 1, pci);
}

/* --- 0xB8D8 NR LL1 LOG Serving SNR --- */
static void parse_nr_b8d8(const uint8_t *b, int n) {
    if (n < 4) return;
    uint32_t version = rd32(b);
    layout_seen(0xB8D8, version, b, n);
    int sample_at, trs = 0;
    switch (version) {
    case 0x20000: case 0x30000: sample_at = 8; break;
    case 0x20001: case 0x20002: case 0x20003: case 0x30001: case 0x30002:
        sample_at = 12;
        break;
    default: unknown_layout(0xB8D8, version, b, n, "serving SNR version"); return;
    }
    if (n < sample_at + 16) { unknown_layout(0xB8D8, version, b, n, "serving SNR length"); return; }
    /* 1.5: layout checked against NR measurementReport SINR in run3.
       u32@4: bits0-15 slot counter, bits16-25 PCI, bits26-31 carrier index (0 = PCC)
       u32@8 bit0: 0 = SSB (-> serving "SSB SINR"), 1 = TRS/CSI-RS
       snr[4] s32@12, Q10 linear, valid 6..0x9C4000; SINR = 10log10(max/1024), [-23,40]
       run3: 33 MR samples, SSB max-rx vs MR serving SINR bias +1.1 dB, median abs diff 2.4 dB */
    uint32_t h = rd32(b + 4);
    int pci = (int)((h >> 16) & 0x3ff), carrier = (int)((h >> 26) & 0x3f);
    if (sample_at == 12) trs = b[8] & 1;
    if (carrier != 0) {
        /* 3.2 SCC frames carry CC and PCI but no ARFCN. Join to the live config in the app. */
        if (version != 0x30002 || n != 28 || carrier >= 16 || pci > 1007 || trs) return;
        int valid, invalid;
        double snr = snr_q10_max(b + 12, 4, 4, &valid, &invalid);
        if (!valid || snr < -23.0 || snr > 40.0) return;
        static int64_t last[16];
        int64_t now = mono_ms();
        if (last[carrier] && now - last[carrier] < 500) return;
        last[carrier] = now;
        struct jb j; jb_init(&j, "nr_snr");
        jb_add(&j, ",\"rat\":\"NR\",\"src\":\"B8D8\",\"ver\":\"3.2\",\"rs\":\"SSB\","
                   "\"pci\":%d,\"carrier\":%d,\"tentative\":0", pci, carrier);
        jb_f(&j, "sinr", snr);
        jb_emit(&j);
        return;
    }
    emit_nr_snr("B8D8", version, pci, g_nr.arfcn, carrier, trs, b + sample_at, 4, trs ? 4 : 1);
}

/* --- 0xB8DD NR LL1 FW Serving FTL --- */
/* 1.4: version 7 (u32 == 7), using bit positions also documented in MobileInsight v7:
   bitstream @4: skip14, cell id 10b (bits 14..23), rs type 1b (bit 24, 0=SSB 1=TRS), skip7, carrier 8b (bits 32..39),
   skip16, skip8, 10x50b + 12b reserved -> snr[4] u32 @76, Q10 linear, SINR = 10*log10(max/1024).
   Other versions: try offsets 68, 80 (stride 8), and 76. Emit SINR only if exactly one candidate passes. */
static void parse_nr_b8dd(const uint8_t *b, int n) {
    if (n < 24) return;
    int rel_min = rd16(b), rel_maj = rd16(b + 2);
    uint32_t ver = rd32(b);
    layout_seen(0xB8DD, ver, b, n);
    /* Exact 3.11 layout: serving carrier slot, TRS flag, four Q10 chains
       12 bytes apart. This version carries a slot index instead of PCI. */
    if (ver == 0x3000b) {
        if (n != 644) { unknown_layout(0xB8DD, ver, b, n, "serving FTL length"); return; }
        int carrier = b[8] & 7, trs = b[4] & 1;
        emit_nr_snr("B8DD", ver, g_nr.pci, g_nr.arfcn, carrier, trs, b + 80, 12, trs ? 5 : 2);
        return;
    }
    uint64_t h = rd64(b + 4);
    int trs = (int)((h >> 24) & 1);
    int pci = (int)((h >> 14) & 0x3ff);
    if (ver == 7) {
        if (n != 544) { unknown_layout(0xB8DD, ver, b, n, "serving FTL length"); return; }
        emit_nr_snr("B8DD", ver, pci, rd32(b + 92), 0, trs, b + 76, 4, trs ? 5 : 2);
        return;
    }
    int carrier = (int)((h >> 32) & 0x3f);
    static const int C[3][2] = { { 68, 4 }, { 80, 8 }, { 76, 4 } };
    int ok = -1, nok = 0; double snr = NA_F, cv[3] = { NA_F, NA_F, NA_F };
    for (int k = 0; k < 3; k++) {
        if (n < C[k][0] + 3 * C[k][1] + 4) continue;
        int nv, nb; double v = snr_q10_max(b + C[k][0], 4, C[k][1], &nv, &nb);
        if (!nb && nv) { if (ok < 0) { ok = k; snr = v; } cv[k] = v; nok++; }
    }
    if (ok < 0) return;
    if (snr_throttle(2)) {
        struct jb j; jb_init(&j, "nr_snr");
        jb_add(&j, ",\"src\":\"B8DD\",\"pci\":%d,\"rs\":\"%s\",\"carrier\":%d,\"ver\":\"%d.%d\",\"tentative\":1",
               pci, trs ? "TRS" : "SSB", carrier, rel_maj, rel_min);
        if (nok == 1) jb_f(&j, "sinr", snr);
        jb_f(&j, "cand68", cv[0]); jb_f(&j, "cand80s8", cv[1]); jb_f(&j, "cand76", cv[2]);
        jb_emit(&j);
    }
}

/* --- 0xB888 NR MAC PDSCH Stats (MobileInsight v2.2 layout) --- */
/* B888 NR5G ML1 PDSCH status. Counters are cumulative per carrier; 1.5 emits ~1 s deltas (BLER, Mbps).
   v2.2 (MobileInsight): header 28 bytes, nrec = byte 15.  v3.1 (run3, SM8750): header 20 bytes, nrec = byte 7.
   record (72 B): carrier word, slots, decodes, crc_pass, crc_fail, retx, ack_as_nack, harq_fail;
                  v3.1's carrier word has flags above the low byte (e.g. 0x00010000 for CC 0);
                  u64 pass_bytes @32, fail_bytes @40, tb_bytes @48 (= pass+fail), pad @56, retx_bytes @64 */
struct pdsch_acc { int valid; uint32_t slots, dec, pass, fail, retx; uint64_t pbytes, tb_bytes, padding; };
static struct pdsch_acc g_pd_prev[8], g_pd_base[8];
static int64_t g_pd_t;
static int g_pd_pci = -1;
static long g_pd_arfcn = -1;
static uint32_t g_pd_version;
static void parse_nr_b888(const uint8_t *b, int n) {
    if (n < 28) return;
    int rel_min = rd16(b), rel_maj = rd16(b + 2);
    layout_seen(0xB888, ((uint32_t)rel_maj << 16 | (uint32_t)rel_min), b, n);
    int hdr, nrec;
    if (rel_maj == 2 && rel_min == 2) { hdr = 28; nrec = b[15]; }
    else if (rel_maj == 3 && rel_min == 0) { hdr = 20; nrec = b[8] >> 4; }
    else if (rel_maj == 3 && rel_min == 1) { hdr = 20; nrec = b[7]; }
    else { unknown_layout(0xB888, ((uint32_t)rel_maj << 16 | (uint32_t)rel_min), b, n, "version"); return; }
    uint32_t version = rd32(b);
    int nrec_used = nrec;
    if (nrec <= 0 || nrec > 8 || hdr + nrec * 72 != n) {
        /* A fixed-capacity table can contain more rows than its active count.
         * Keep rejecting invalid counts, partial rows and truncated tables. */
        int derived = n > hdr ? (n - hdr) / 72 : 0;
        int usable = n > hdr && (n - hdr) % 72 == 0 && derived >= 1 && derived <= 8;
        if (usable && nrec <= 8 && derived > nrec) {
            nrec_used = derived;
        } else {
            unknown_layout(0xB888, version, b, n, "PDSCH counters length");
            return;
        }
    }
    unsigned seen = 0;
    for (int r = 0; r < nrec_used; r++) {
        const uint8_t *p = b + hdr + r * 72;
        uint32_t cc = version == 0x30001 ? p[0] : rd32(p);
        if (cc > 7 || (seen & (1u << cc))) {
            unknown_layout(0xB888, version, b, n, "PDSCH counters carrier");
            return;
        }
        uint64_t pass = rd64(p + 32), fail = rd64(p + 40), total = rd64(p + 48);
        if (version == 0x30001 && (pass > total || fail != total - pass)) {
            unknown_layout(0xB888, version, b, n, "PDSCH counters total");
            return;
        }
        seen |= 1u << cc;
    }
    int64_t now = pkt_now_ms();
    if (!g_pd_t || now < g_pd_t || now - g_pd_t > 6000 || g_pd_pci != g_nr.pci ||
        g_pd_arfcn != g_nr.arfcn || g_pd_version != version) {
        memset(g_pd_prev, 0, sizeof(g_pd_prev));
        memset(g_pd_base, 0, sizeof(g_pd_base));
        g_pd_pci = g_nr.pci;
        g_pd_arfcn = g_nr.arfcn;
        g_pd_version = version;
        g_pd_t = now;
    }
    if (nrec_used != nrec) layout_variant(0xB888, version, n, nrec_used);
    for (int cc = 0; cc < 8; cc++) {
        if (!(seen & (1u << cc))) g_pd_prev[cc].valid = g_pd_base[cc].valid = 0;
    }
    for (int r = 0; r < nrec_used; r++) {
        const uint8_t *p = b + hdr + r * 72;
        uint32_t cc = version == 0x30001 ? p[0] : rd32(p);
        struct pdsch_acc a = { 1, rd32(p + 4), rd32(p + 8), rd32(p + 12), rd32(p + 16), rd32(p + 20),
            rd64(p + 32), rd64(p + 48), rd64(p + 56) };
        struct pdsch_acc *pv = &g_pd_prev[cc];
        if (pv->valid && (a.dec < pv->dec || a.retx < pv->retx ||
            a.pass < pv->pass || a.fail < pv->fail || a.pbytes < pv->pbytes ||
            a.tb_bytes < pv->tb_bytes || a.padding < pv->padding)) {
            g_pd_base[cc].valid = 0;
        }
        if (!g_pd_base[cc].valid) g_pd_base[cc] = a;
        *pv = a;
    }
    int64_t dt = now - g_pd_t;
    if (dt < 1000) return;
    struct jb j; jb_init(&j, "nr_pdsch");
    jb_add(&j, ",\"src\":\"B888\",\"rat\":\"NR\",\"ver\":\"%d.%d\",\"dt_ms\":%lld", rel_maj, rel_min, (long long)dt);
    if (g_pd_pci >= 0 && g_pd_arfcn > 0) {
        jb_i(&j, "pci", g_pd_pci);
        jb_i(&j, "arfcn", g_pd_arfcn);
    }
    jb_add(&j, ",\"recs\":[");
    int first = 1;
    for (int cc = 0; cc < 8; cc++) {
        struct pdsch_acc *pv = &g_pd_prev[cc], *bs = &g_pd_base[cc];
        if (!pv->valid || !bs->valid) continue;
        uint32_t dp = pv->pass - bs->pass, df = pv->fail - bs->fail, dd = pv->dec - bs->dec, dr = pv->retx - bs->retx, ds = pv->slots - bs->slots;
        uint64_t db = pv->pbytes - bs->pbytes;
        jb_add(&j, "%s{\"cc\":%d,\"slots\":%u,\"decodes\":%u,\"crc_pass\":%u,\"crc_fail\":%u,\"retx\":%u,\"pass_bytes\":%llu",
               first ? "" : ",", cc, ds, dd, dp, df, dr, (unsigned long long)db);
        jb_f(&j, "bler_pct", (dp + df) ? 100.0 * df / (dp + df) : NA_F);
        if (dd && dr <= dd) jb_f(&j, "retx_pct", 100.0 * dr / dd);
        jb_f(&j, "mbps", db * 0.008 / dt);
        uint64_t padding = pv->padding - bs->padding;
        if (padding <= db) jb_f(&j, "mac_mbps", (db - padding) * 0.008 / dt);
        uint64_t tb_delta = pv->tb_bytes - bs->tb_bytes;
        if (tb_delta >= db) jb_f(&j, "phy_tb_mbps", tb_delta * 0.008 / dt);
        jb_add(&j, "}");
        first = 0;
        *bs = *pv;
    }
    jb_add(&j, "]");
    g_pd_t = now;
    if (!first) jb_emit(&j);
}

/* LTE scheduling layouts checked against qcom-band-menu qbm_layouts.h. The
   X80 capture also verifies DL v50, UL v162 and PUSCH power v51. */
struct lte_schedule_carrier {
    unsigned tb, fail, rb_sum, layers, grants, mcs_sum, mcs_count, power_count, retransmissions;
    unsigned modulation[6];
    int power_sum;
    uint64_t bytes, crc_pass_bytes;
};

enum lte_schedule_kind { LTE_DL, LTE_UL, LTE_POWER, LTE_UL_TX, LTE_PUCCH_POWER, LTE_PUCCH_TX, LTE_SCHEDULE_KINDS };

struct lte_schedule_window {
    int64_t start, last;
    int pci, channel, version;
    struct lte_schedule_carrier carrier[8];
};
static struct lte_schedule_window g_lte_schedule[LTE_SCHEDULE_KINDS];

static void lte_schedule_emit(struct lte_schedule_window *window, enum lte_schedule_kind kind, int64_t now) {
    static const char *const types[] = {
        "lte_dl_sched", "lte_ul_sched", "lte_ul_pwr", "lte_ul_tx", "lte_pucch_power", "lte_pucch_tx"
    };
    static const char *const sources[] = { "B173", "B139", "B16E", "B16D", "B16F", "B13C" };
    static const char *const modulation[] = { "BPSK", "QPSK", "16QAM", "64QAM", "256QAM", "1024QAM" };
    int64_t dt = now - window->start;
    struct jb j;
    jb_init(&j, types[kind]);
    jb_add(&j, ",\"rat\":\"LTE\",\"src\":\"%s\",\"ver\":%d,\"dt_ms\":%lld",
           sources[kind], window->version, (long long)dt);
    if (window->pci >= 0 && window->channel >= 0) {
        jb_i(&j, "pci", window->pci);
        jb_i(&j, "earfcn", window->channel);
    }
    jb_add(&j, ",\"cc\":[");
    int first = 1;
    for (int cc = 0; cc < 8; cc++) {
        const struct lte_schedule_carrier *a = &window->carrier[cc];
        if (!a->tb && !a->power_count) continue;
        jb_add(&j, "%s{\"cc\":%d", first ? "" : ",", cc);
        first = 0;
        if (kind == LTE_POWER) {
            jb_f(&j, "pusch_dbm", (double)a->power_sum / a->power_count);
        } else if (kind == LTE_PUCCH_POWER || kind == LTE_PUCCH_TX) {
            jb_f(&j, "pucch_dbm", (double)a->power_sum / a->power_count);
        } else if (kind == LTE_UL_TX) {
            if (a->power_count) jb_f(&j, "pucch_dbm", (double)a->power_sum / a->power_count);
            if (a->mcs_count) jb_f(&j, "mcs_avg", (double)a->mcs_sum / a->mcs_count);
            if (a->tb) jb_f(&j, "retx_pct", 100.0 * a->retransmissions / a->tb);
        } else {
            jb_i(&j, "tb", a->tb);
            jb_f(&j, "rb_avg", (double)a->rb_sum / a->tb);
            jb_f(&j, "phy_tb_mbps", a->bytes * 0.008 / dt);
            if (kind == LTE_DL) {
                jb_f(&j, "bler_pct", 100.0 * a->fail / a->tb);
                jb_f(&j, "mac_crc_tb_mbps", a->crc_pass_bytes * 0.008 / dt);
                if (a->mcs_count) jb_f(&j, "mcs_avg", (double)a->mcs_sum / a->mcs_count);
                if (a->grants) jb_f(&j, "layers_avg", (double)a->layers / a->grants);
            } else if (window->version == 162) {
                jb_f(&j, "retx_pct", 100.0 * a->retransmissions / a->tb);
                if (a->power_count) jb_f(&j, "pusch_dbm", (double)a->power_sum / a->power_count);
            }
            int mode = 0;
            for (int i = 1; i < 6; i++) if (a->modulation[i] > a->modulation[mode]) mode = i;
            if (a->modulation[mode]) {
                jb_add(&j, ",\"mod\":\"%s\",\"mod_hist\":[%u,%u,%u,%u,%u,%u]", modulation[mode],
                       a->modulation[1], a->modulation[2], a->modulation[3], a->modulation[4],
                       a->modulation[5], a->modulation[0]);
            }
        }
        jb_add(&j, "}");
    }
    jb_add(&j, "]");
    if (!first) jb_emit(&j);
}

/* --- 0xB126 LTE PDSCH demapper config and 0xB184 LTE CA metrics ---
   Per-version bit positions are protocol facts cross-checked against the
   table published by qcom-band-menu (GPL-3.0). The tables, decoders and
   emitters below were written from those facts; no GPL code was copied.
   Payload-relative positions are in bits; field positions marked "rec" are
   relative to the start of one record. */

struct lte_b126_layout {
    uint8_t ver;
    int carrier_at, carrier_bits;   /* carrier slot; absent: slot 0 */
    int count_at, count_bits;       /* record count; absent: 1 */
    int rec_start, rec_bits;        /* payload bits before the first record / record size */
    int rnti_at, rnti_bits, tx_at, tx_bits, rx_at, rx_bits;   /* rec */
    int rank_at, rank_bits, scheme_at, scheme_bits;           /* rec */
};

static const struct lte_b126_layout k_lte_b126_layouts[] = {
    {   1, 0, 0, 0, 0, 0, 416, 48, 4, 56, 2, 58, 1, 62, 2, 59, 3 },
    {   2, 0, 0, 0, 0, 0, 448, 48, 4, 56, 2, 58, 1, 62, 2, 59, 3 },
    {  22, 0, 0, 0, 0, 0, 416, 48, 4, 56, 2, 58, 1, 62, 2, 59, 3 },
    {  23, 0, 0, 0, 0, 0, 416, 48, 4, 56, 2, 58, 1, 62, 2, 328, 4 },
    { 103, 0, 0, 0, 0, 0, 448, 48, 4, 56, 2, 58, 2, 62, 2, 328, 4 },
    { 122, 0, 0, 0, 0, 0, 480, 48, 4, 54, 2, 56, 2, 60, 2, 326, 4 },
    { 123, 0, 0, 0, 0, 0, 480, 48, 4, 54, 2, 56, 2, 60, 2, 326, 4 },
    { 124, 0, 0, 0, 0, 0, 480, 48, 4, 54, 2, 56, 2, 60, 2, 326, 4 },
    { 144, 8, 4, 12, 8, 64, 448, 23, 4, 27, 2, 29, 2, 50, 2, 60, 4 },
    { 161, 8, 4, 12, 8, 64, 416, 23, 4, 27, 2, 29, 2, 50, 2, 60, 4 },
    { 162, 8, 4, 12, 8, 64, 352, 14, 4, 18, 2, 20, 2, 32, 2, 42, 4 },
    { 163, 8, 4, 12, 8, 64, 384, 14, 4, 18, 2, 20, 2, 32, 2, 42, 4 },
    { 164, 8, 4, 12, 8, 64, 416, 347, 4, 14, 2, 16, 2, 32, 2, 41, 4 },
    { 165, 8, 4, 12, 8, 64, 448, 347, 4, 14, 2, 16, 2, 32, 2, 41, 4 },
};

/* Antenna codes as reported by the demapper: 0 = unknown for TX. */
static const int k_lte_tx_antennas[4] = { 1, 2, 4, 0 };
static const int k_lte_rx_antennas[4] = { 1, 2, 3, 4 };

struct lte_b126_sample { int carrier, rnti, tx_code, rx_code, rank, scheme; };

static int lte_b126_decode(const uint8_t *b, int n, struct lte_b126_sample *out, int max,
                           uint8_t *ver_out) {
    if (n < 1) return -1;
    const struct lte_b126_layout *l = NULL;
    for (size_t i = 0; i < sizeof(k_lte_b126_layouts) / sizeof(k_lte_b126_layouts[0]); i++)
        if (k_lte_b126_layouts[i].ver == b[0]) { l = &k_lte_b126_layouts[i]; break; }
    if (!l) return -1;
    *ver_out = l->ver;
    if ((l->carrier_bits && l->carrier_at + l->carrier_bits > n * 8) ||
        (l->count_bits && l->count_at + l->count_bits > n * 8)) return -1;
    int carrier = l->carrier_bits ? (int)lebits(b, l->carrier_at, l->carrier_bits) : 0;
    int count = l->count_bits ? (int)lebits(b, l->count_at, l->count_bits) : 1;
    int size = l->rec_bits / 8, pos = l->rec_start / 8;
    if (count <= 0 || size <= 0 || pos + size > n) return 0;
    int done = 0;
    for (int r = 0; r < count && done < max && pos + size <= n; r++, pos += size) {
        const uint8_t *rec = b + pos;
        struct lte_b126_sample *s = &out[done++];
        s->carrier = carrier;
        s->rnti = (int)lebits(rec, l->rnti_at, l->rnti_bits);
        s->tx_code = (int)lebits(rec, l->tx_at, l->tx_bits);
        s->rx_code = (int)lebits(rec, l->rx_at, l->rx_bits);
        s->rank = (int)lebits(rec, l->rank_at, l->rank_bits);
        s->scheme = (int)lebits(rec, l->scheme_at, l->scheme_bits);
    }
    return done;
}

struct lte_b184_layout {
    uint8_t ver;
    int count_at, count_bits;               /* payload: SCell count */
    int sc_start, sc_bits, pcell_at;        /* payload bits */
    int state_add;                          /* legacy 0/1 enum -> modern 1/2 */
    int p_earfcn_at, p_earfcn_bits, p_pci_at, p_pci_bits;
    int p_band_at, p_band_bits, p_bw_at, p_bw_bits;
    int s_earfcn_at, s_earfcn_bits, s_pci_at, s_pci_bits;
    int s_band_at, s_band_bits, s_bw_at, s_bw_bits;
    int s_state_at, s_state_bits, s_slot_at, s_slot_bits;
};

static const struct lte_b184_layout k_lte_b184_layouts[] = {
    {  1, 16, 8,  128,  64,  64, 1,
       0, 32, 32, 16, 48, 8,  0, 0,
       0, 32, 32, 16, 48, 8,  0, 0, 56, 8,  0, 0 },
    { 24, 16, 8,  160,  96,  64, 1,
       0, 32, 32, 16, 48, 8, 56, 8,
       0, 32, 32, 16, 48, 8, 56, 8, 64, 8,  0, 0 },
    { 25, 16, 8,  160,  96,  64, 0,
       0, 32, 32, 16, 48, 8, 56, 8,
       0, 32, 32, 16, 48, 8, 56, 8, 64, 8,  0, 0 },
    { 40, 16, 8,  160,  96,  64, 0,
       0, 32, 32, 16, 48, 8, 56, 8,
       0, 32, 32, 16, 48, 8, 56, 8, 64, 8, 88, 8 },
    { 48, 16, 8,  160,  96,  64, 0,
       0, 32, 32, 16, 48, 8, 56, 8,
       0, 32, 32, 16, 48, 8, 56, 8, 64, 8, 88, 8 },
    { 49, 16, 8,  160,  96,  64, 0,
       0, 32, 32, 16, 48, 8, 56, 8,
       0, 32, 32, 16, 48, 8, 56, 8, 64, 8, 88, 8 },
    { 50, 16, 8, 2624, 672,  64, 0,
       0, 32, 32, 16, 48, 8, 56, 8,
       0, 32, 32, 16, 48, 8, 56, 8, 64, 8, 88, 8 },
    { 51, 16, 8, 2624, 672,  64, 0,
       0, 32, 32, 16, 48, 8, 56, 8,
       0, 32, 32, 16, 48, 8, 56, 8, 64, 8, 88, 8 },
};

/* Normalized downlink carrier state: 0 absent, 1 deactivated, 2 activated. */
struct lte_ca_cell { int slot; long earfcn; int pci, band, bw_raw, state; };

static int lte_b184_decode(const uint8_t *b, int n, struct lte_ca_cell *out, int max,
                           uint8_t *ver_out) {
    if (n < 2) return -1;
    const struct lte_b184_layout *l = NULL;
    for (size_t i = 0; i < sizeof(k_lte_b184_layouts) / sizeof(k_lte_b184_layouts[0]); i++)
        if (k_lte_b184_layouts[i].ver == b[0]) { l = &k_lte_b184_layouts[i]; break; }
    if (!l) return -1;
    *ver_out = l->ver;
    int sc_bytes = l->sc_bits / 8;
    if (l->count_at + l->count_bits > n * 8) return -1;
    int count = (int)lebits(b, l->count_at, l->count_bits);
    if (count < 0 || count > 32 || sc_bytes <= 0) return -1;
    int pc = l->pcell_at / 8;
    if (pc + 8 > n || max < 1) return -1;
    const uint8_t *p = b + pc;
    int done = 0;
    out[done].slot = 0;
    out[done].earfcn = l->p_earfcn_bits ? (long)lebits(p, l->p_earfcn_at, l->p_earfcn_bits) : -1;
    out[done].pci = l->p_pci_bits ? (int)lebits(p, l->p_pci_at, l->p_pci_bits) : -1;
    out[done].band = l->p_band_bits ? (int)lebits(p, l->p_band_at, l->p_band_bits) : -1;
    out[done].bw_raw = l->p_bw_bits ? (int)lebits(p, l->p_bw_at, l->p_bw_bits) : -1;
    out[done].state = 2;                    /* the PCell is active while listed */
    done++;
    int start = l->sc_start / 8;
    if (start + count * sc_bytes > n) return -1;
    for (int i = 0; i < count && done < max; i++) {
        const uint8_t *c = b + start + i * sc_bytes;
        /* Some versions name the SCell's own carrier slot: during a swap a
           lone SCell can be listed first with slot 2. */
        int slot = l->s_slot_bits ? (int)lebits(c, l->s_slot_at, l->s_slot_bits) : i + 1;
        if (slot < 1 || slot >= 8) slot = i + 1;
        if (slot >= 8) continue;
        int duplicate = 0;
        for (int k = 0; k < done; k++) if (out[k].slot == slot) duplicate = 1;
        if (duplicate) { slot = i + 1; if (slot >= 8) continue; }
        struct lte_ca_cell *e = &out[done++];
        e->slot = slot;
        e->earfcn = l->s_earfcn_bits ? (long)lebits(c, l->s_earfcn_at, l->s_earfcn_bits) : -1;
        e->pci = l->s_pci_bits ? (int)lebits(c, l->s_pci_at, l->s_pci_bits) : -1;
        e->band = l->s_band_bits ? (int)lebits(c, l->s_band_at, l->s_band_bits) : -1;
        e->bw_raw = l->s_bw_bits ? (int)lebits(c, l->s_bw_at, l->s_bw_bits) : -1;
        int st = l->s_state_bits ? (int)lebits(c, l->s_state_at, l->s_state_bits) + l->state_add : -1;
        e->state = (st >= 0 && st <= 2) ? st : -1;
    }
    return done;
}

/* 0xB184 bandwidth reuses lte_bw_mhz: an RB count or a 0-5 index. */

/* Rank / antenna window: one second per report, like the scheduling windows. */
struct lte_rank_acc {
    unsigned n, rank_sum, rank_hist[4], scheme_hist[16];
    int tx, rx;
};

struct lte_rank_window {
    int64_t start, last;
    int version, pci, channel;
    struct lte_rank_acc cc[8];
};

static struct lte_rank_window g_lte_rank;

static void lte_rank_emit(struct lte_rank_window *w, int64_t now) {
    struct jb j;
    jb_init(&j, "lte_rank");
    jb_add(&j, ",\"rat\":\"LTE\",\"src\":\"B126\",\"ver\":%d,\"dt_ms\":%lld",
           w->version, (long long)(now - w->start));
    if (w->pci >= 0 && w->channel >= 0) {
        jb_i(&j, "pci", w->pci);
        jb_i(&j, "earfcn", w->channel);
    }
    jb_add(&j, ",\"cc\":[");
    int first = 1;
    for (int cc = 0; cc < 8; cc++) {
        const struct lte_rank_acc *a = &w->cc[cc];
        if (!a->n) continue;
        jb_add(&j, "%s{\"cc\":%d,\"n\":%u", first ? "" : ",", cc, a->n);
        first = 0;
        /* Rank 0 means "not reported"; it is counted in the histogram only. */
        unsigned reported = a->rank_hist[1] + a->rank_hist[2] + a->rank_hist[3];
        if (reported) jb_f(&j, "rank_avg", (double)a->rank_sum / reported);
        jb_add(&j, ",\"rank_hist\":[%u,%u,%u,%u]", a->rank_hist[0], a->rank_hist[1],
               a->rank_hist[2], a->rank_hist[3]);
        int mode = 0;
        for (int i = 1; i < 16; i++) if (a->scheme_hist[i] > a->scheme_hist[mode]) mode = i;
        if (a->scheme_hist[mode]) jb_i(&j, "scheme", mode);
        if (a->tx) jb_i(&j, "tx", a->tx);
        if (a->rx) jb_i(&j, "rx", a->rx);
        jb_add(&j, "}");
    }
    jb_add(&j, "]");
    if (!first) jb_emit(&j);
}

static void parse_lte_b126(const uint8_t *b, int n) {
    struct lte_b126_sample samples[8];
    uint8_t version = 0;
    int count = lte_b126_decode(b, n, samples, 8, &version);
    if (count < 0) { unknown_layout(0xB126, n ? b[0] : 0, b, n, "LTE demapper version"); return; }
    layout_seen(0xB126, version, b, n);
    if (!count) return;
    int64_t now = pkt_now_ms();
    struct lte_rank_window *w = &g_lte_rank;
    int reset = !w->start || now < w->last || now - w->last > 6000 ||
        w->version != version || w->pci != g_lte.pci || w->channel != g_lte.arfcn;
    if (reset || now - w->start >= 1000) {
        if (!reset) lte_rank_emit(w, now);
        memset(w, 0, sizeof(*w));
        w->start = now;
        w->version = version;
        w->pci = g_lte.pci;
        w->channel = g_lte.arfcn;
    }
    w->last = now;
    for (int i = 0; i < count; i++) {
        const struct lte_b126_sample *s = &samples[i];
        if (s->rnti > 1) continue;              /* C-RNTI and SPS C-RNTI only */
        struct lte_rank_acc *a = &w->cc[s->carrier & 7];
        a->n++;
        if (s->rank > 0 && s->rank < 4) {
            a->rank_sum += (unsigned)s->rank;
        }
        a->rank_hist[s->rank & 3]++;
        if (s->scheme < 16) a->scheme_hist[s->scheme]++;
        if (s->tx_code < 4 && k_lte_tx_antennas[s->tx_code])
            a->tx = k_lte_tx_antennas[s->tx_code];
        if (s->rx_code < 4) a->rx = k_lte_rx_antennas[s->rx_code];
    }
}

static unsigned long long g_lte_ca_sig;
static int g_lte_ca_have_sig;
static int64_t g_lte_ca_emit_ms;

/* 0xB184 can be sent every slot: emit on any change, otherwise once a second. */
static void parse_lte_b184(const uint8_t *b, int n) {
    struct lte_ca_cell cells[33];
    uint8_t version = 0;
    int count = lte_b184_decode(b, n, cells, 33, &version);
    if (count < 0) { unknown_layout(0xB184, n ? b[0] : 0, b, n, "LTE CA metrics version"); return; }
    layout_seen(0xB184, version, b, n);
    if (!count) return;
    unsigned long long sig = 1469598103934665603ULL;
    for (int i = 0; i < count; i++) {
        const struct lte_ca_cell *c = &cells[i];
        unsigned long long v = (unsigned long long)(c->slot & 7) |
            ((unsigned long long)(c->earfcn < 0 ? 0xffffffffu : (unsigned)c->earfcn) << 3) ^
            ((unsigned long long)(c->pci + 1) << 35) ^
            ((unsigned long long)(c->band + 1) << 45) ^
            ((unsigned long long)(c->bw_raw + 1) << 54) ^
            ((unsigned long long)(c->state + 1) << 60);
        sig ^= v;
        sig *= 1099511628211ULL;
    }
    int64_t now = pkt_now_ms();
    if (g_lte_ca_have_sig && sig == g_lte_ca_sig &&
        now >= g_lte_ca_emit_ms && now - g_lte_ca_emit_ms < 1000) return;
    g_lte_ca_sig = sig;
    g_lte_ca_have_sig = 1;
    g_lte_ca_emit_ms = now;
    struct jb j;
    jb_init(&j, "lte_ca");
    /* The PCell (always cells[0]) also names the serving cell so consumers can
       drop a list captured before the last handover. */
    jb_add(&j, ",\"rat\":\"LTE\",\"src\":\"B184\",\"ver\":%d", version);
    if (cells[0].pci >= 0) jb_i(&j, "pci", cells[0].pci);
    if (cells[0].earfcn >= 0) jb_i(&j, "earfcn", cells[0].earfcn);
    jb_add(&j, ",\"cells\":[");
    for (int i = 0; i < count; i++) {
        const struct lte_ca_cell *c = &cells[i];
        jb_add(&j, "%s{\"slot\":%d,\"role\":\"%s\"", i ? "," : "", c->slot,
               c->slot ? "scell" : "pcell");
        if (c->earfcn >= 0) jb_i(&j, "earfcn", c->earfcn);
        if (c->pci >= 0) jb_i(&j, "pci", c->pci);
        if (c->band > 0) jb_i(&j, "band", c->band);
        double bw = lte_bw_mhz(c->bw_raw);
        if (bw > 0) jb_f(&j, "bw_mhz", bw);
        if (c->state >= 0) jb_i(&j, "state", c->state);
        jb_add(&j, "}");
    }
    jb_add(&j, "]");
    jb_emit(&j);
}

static struct lte_schedule_carrier *lte_schedule_window(enum lte_schedule_kind kind, int version) {
    struct lte_schedule_window *window = &g_lte_schedule[kind];
    int64_t now = pkt_now_ms();
    int reset = !window->start || now < window->last || now - window->last > 6000 ||
        window->version != version || window->pci != g_lte.pci || window->channel != g_lte.arfcn;
    if (reset || now - window->start >= 1000) {
        if (!reset) lte_schedule_emit(window, kind, now);
        memset(window, 0, sizeof(*window));
        window->start = now;
        window->version = version;
        window->pci = g_lte.pci;
        window->channel = g_lte.arfcn;
    }
    window->last = now;
    return window->carrier;
}

static void parse_lte_b173(const uint8_t *b, int n) {
    if (n < 4) return;
    int version = b[0], count = b[1];
    layout_seen(0xB173, version, b, n);
    int size, tb_at = 12, tb_size = 12, size_at = 4, size_bits = 16, mod_at = 8;
    switch (version) {
    case 5: case 16: case 17: case 18: case 19:
        size = version == 17 ? 32 : version >= 18 ? 36 : 20;
        tb_at = version >= 18 ? 8 : 6;
        tb_size = version >= 17 ? 12 : 6;
        size_at = 2; size_bits = 14; mod_at = 0;
        break;
    case 6: case 24: case 25: case 32: case 33: case 34: case 35:
        size = version == 35 ? 32 : 24;
        tb_at = version == 35 ? 12 : 6;
        tb_size = 8; size_at = 2; size_bits = version == 6 ? 14 : 16; mod_at = 6;
        break;
    case 36: case 37: case 38: case 39: case 40: case 48: case 49: case 50:
        size = 40;
        break;
    default: unknown_layout(0xB173, version, b, n, "LTE DL schedule version"); return;
    }
    if (4 + count * size != n) { unknown_layout(0xB173, version, b, n, "LTE DL schedule length"); return; }
    int layer_at = version == 50 ? 2 : (version == 18 || version == 19) ? 5 : 3;
    struct lte_schedule_carrier *carrier = lte_schedule_window(LTE_DL, version);
    for (int i = 0; i < count; i++) {
        const uint8_t *record = b + 4 + i * size;
        int layers = record[layer_at], blocks = record[layer_at + 1];
        if (blocks > 2) continue;
        struct lte_schedule_carrier *a = &carrier[record[layer_at + 2] & 7];
        int scheduled = 0;
        for (int t = 0; t < blocks; t++) {
            const uint8_t *tb = record + tb_at + t * tb_size;
            if ((tb[1] & 15) > 1) continue;  /* C-RNTI and SPS C-RNTI */
            unsigned bytes = lebits(tb, size_at * 8, size_bits);
            int mcs = tb[size_at + 2], rb = tb[size_at + 3];
            if (!bytes || rb < 1 || rb > 110) continue;
            a->tb++;
            a->bytes += bytes;
            if (tb[0] & 0x80) a->crc_pass_bytes += bytes;
            else a->fail++;
            a->rb_sum += rb;
            int modulation = mod_at ? tb[mod_at] : 0;
            if (modulation >= 2 && modulation <= 10 && !(modulation & 1)) a->modulation[modulation / 2]++;
            if (mcs < 29 && !(mcs == 28 && modulation == 2)) {
                a->mcs_sum += mcs;
                a->mcs_count++;
            }
            scheduled = 1;
        }
        if (scheduled && layers >= 1 && layers <= 8) {
            a->layers += layers;
            a->grants++;
        }
    }
}

/* Periodic wideband CQI (PUCCH) and aperiodic wideband CQI (PUSCH).
   Layouts follow MobileInsight's LTE PHY CSI feedback dissectors. */
static void parse_lte_csf(uint16_t code, const uint8_t *b, int n) {
    if (n < 1) return;
    int version = b[0];
    layout_seen(code, version, b, n);
    int size = 0, cc_bit, cqi_bit, mode_bit, rank = 0;
    if (code == 0xB14D && version == 164) {
        /* Types 2 and 4 carry wideband CQI feedback. The v164
           samples have an extra low bit before this CQI field. */
        size = 36;
        if (n == size) {
            int type = lebits(b, 34, 4);
            if (type != 2 && type != 4) return;
        }
        cc_bit = 22;
        cqi_bit = 52;
        mode_bit = 64;
    } else if (code == 0xB14D) {
        switch (version) {
        case 22: size = 8; break;
        case 24: case 43: case 101: case 102: case 103: size = 12; break;
        case 142: size = 16; break;
        }
        if (size && n == size) {
            int type = version == 142 ? lebits(b, 34, 4) :
                version == 22 ? lebits(b, 27, 2) : lebits(b, 25, 4);
            if (type != (version == 22 ? 1 : 2)) return;
        }
        cc_bit = version == 142 ? 22 : 54;
        cqi_bit = version == 142 ? 51 : 42;
        mode_bit = version == 142 ? 64 : 58;
    } else if (version == 164) {
        /* v163 carrier, rank and CW0 prefix, validated by v164 samples. */
        size = 60;
        cc_bit = 22;
        cqi_bit = 47;
        mode_bit = 72;
        if (n == size) {
            if (lebits(b, 32, 3) > 4) return;
            rank = lebits(b, 36, 2) + 1;
        }
    } else {
        switch (version) {
        case 22: case 23: case 42: case 101: case 102: size = 36; break;
        case 103: size = 37; break;
        case 142: size = 40; break;
        }
        cc_bit = version == 142 ? 22 : (size - 1) * 8;
        cqi_bit = version == 142 ? 47 : 37;
        mode_bit = version == 142 ? 72 : 60;
        if (size && n == size) {
            if (version == 103) rank = lebits(b, 26, 2) + 1;
            if (version == 142) rank = lebits(b, 36, 2) + 1;
        }
    }
    if (!size || n != size) { unknown_layout(code, version, b, n, "LTE CSI feedback layout"); return; }
    if (lebits(b, 8, 4) > 9 || g_lte.pci < 0 || g_lte.arfcn < 0 ||
        !g_lte.t_seen || mono_ms() - g_lte.t_seen > 3000) return;
    int cc = lebits(b, cc_bit, 4), cqi = lebits(b, cqi_bit, 4);
    static int64_t last[2][16];
    static int last_pci = -1;
    static long last_arfcn = -1;
    if (last_pci != g_lte.pci || last_arfcn != g_lte.arfcn) {
        memset(last, 0, sizeof(last));
        last_pci = g_lte.pci;
        last_arfcn = g_lte.arfcn;
    }
    int64_t now = mono_ms();
    int64_t *last_report = &last[code == 0xB14E][cc];
    if (*last_report && now - *last_report < 500) return;
    *last_report = now;
    struct jb j; jb_init(&j, "lte_csf");
    jb_add(&j, ",\"rat\":\"LTE\",\"src\":\"0x%04X\",\"ver\":%d,\"pci\":%d,\"earfcn\":%ld,\"cc\":%d,\"cqi\":%d",
           code, version, g_lte.pci, g_lte.arfcn, cc, cqi);
    if (rank) jb_i(&j, "rank", rank);
    static const char *const modes[] = {
        NULL, "Single port", "Tx diversity", "Open-loop SM", "Closed-loop SM", "MU-MIMO",
        "Closed-loop Rank 1", "Single port (port 5)"
    };
    unsigned mode = lebits(b, mode_bit, 4);
    if (mode > 0 && mode < sizeof(modes) / sizeof(modes[0])) jb_add(&j, ",\"tx_mode\":\"%s\"", modes[mode]);
    jb_emit(&j);
}

static void parse_lte_b139(const uint8_t *b, int n) {
    if (n < 8) return;
    int version = b[0], count = lebits(b, 17, 5);
    layout_seen(0xB139, version, b, n);
    int size, rb_bit = 47, mod_bit = 160, mod_width = 2, drop_bit = 240;
    switch (version) {
    case 101: case 103: case 121: case 122: size = 68; rb_bit = 46; break;
    case 102: case 123: size = 68; break;
    case 124: size = 84; break;
    case 125: size = 80; rb_bit = 46; break;
    case 141: case 142: size = 84; mod_bit = 162; mod_width = 3; drop_bit = 241; break;
    case 143: case 144: case 145: case 161: case 162: case 163:
        size = 100; mod_bit = 290; mod_width = 3; drop_bit = 369; break;
    default: unknown_layout(0xB139, version, b, n, "LTE UL schedule version"); return;
    }
    if (8 + count * size != n) { unknown_layout(0xB139, version, b, n, "LTE UL schedule length"); return; }
    struct lte_schedule_carrier *carrier = lte_schedule_window(LTE_UL, version);
    for (int i = 0; i < count; i++) {
        const uint8_t *record = b + 8 + i * size;
        unsigned bytes = rd16(record + 8), rb = lebits(record, rb_bit, 7);
        if (!bytes || rb < 1 || rb > 110 || lebits(record, drop_bit, 1)) continue;
        struct lte_schedule_carrier *a = &carrier[record[2] & 3];
        a->tb++;
        a->bytes += bytes;
        a->rb_sum += rb;
        unsigned modulation = lebits(record, mod_bit, mod_width);
        if (modulation < 6) a->modulation[modulation]++;
        if (version == 162) {
            a->retransmissions += lebits(record, 23, 5) != 0; /* ReTxIndex */
            int power = (int)lebits(record, 370, 7);
            if (power & 64) power -= 128;
            if (power >= -50 && power <= 35) {
                a->power_count++;
                a->power_sum += power;
            }
        }
    }
}

static void parse_lte_b16e(const uint8_t *b, int n) {
    if (n < 4) return;
    int version = b[0], count = b[3], size = version == 51 ? 16 : version == 48 || version == 50 ? 12 : 0;
    layout_seen(0xB16E, version, b, n);
    if (!size || 4 + count * size != n) { unknown_layout(0xB16E, version, b, n, "LTE PUSCH power layout"); return; }
    struct lte_schedule_carrier *carrier = lte_schedule_window(LTE_POWER, version);
    for (int i = 0; i < count; i++) {
        const uint8_t *record = b + 4 + i * size;
        int power = (int8_t)lebits(record, 79, 8);
        if (power < -50 || power > 35) continue;
        struct lte_schedule_carrier *a = &carrier[record[0] & 7];
        a->power_count++;
        a->power_sum += power;
    }
}

/* Newer GM Tx reports append a 12-byte carrier aggregation block to some
   records. Validate the complete walk before updating any carrier. */
static void parse_lte_b16d(const uint8_t *b, int n) {
    if (n < 4) return;
    int version = b[0];
    layout_seen(0xB16D, version, b, n);
    int base = version == 49 ? 36 : version == 51 ? 44 : version == 52 ? 48 : 0;
    if (!base) { unknown_layout(0xB16D, version, b, n, "LTE GM Tx version"); return; }
    int pos = 4;
    for (int i = 0; i < b[3]; i++) {
        if (pos + base > n) return;
        int ca = lebits(b + pos, 57, 1);
        if (version == 51 && ca) { unknown_layout(0xB16D, version, b, n, "LTE GM Tx v51 carrier extension"); return; }
        pos += base + 12 * ca;
    }
    if (pos != n) { unknown_layout(0xB16D, version, b, n, "LTE GM Tx length"); return; }
    struct lte_schedule_carrier *carrier = lte_schedule_window(LTE_UL_TX, version);
    pos = 4;
    for (int i = 0; i < b[3]; i++) {
        const uint8_t *r = b + pos;
        int ca = lebits(r, 57, 1);
        pos += base + 12 * ca;
        struct lte_schedule_carrier *a = &carrier[lebits(r, 33, 3)];
        if (lebits(r, 32, 1)) {
            int mcs = lebits(r, 142 + 96 * ca, 5);
            a->tb++;
            a->retransmissions += lebits(r, 119 + 96 * ca, 5) != 0;
            if (mcs <= 28) { a->mcs_count++; a->mcs_sum += mcs; }
        } else if (version != 51) {
            int power = (int8_t)lebits(r, 86 + 96 * ca, 8);
            if (power >= -50 && power <= 35) { a->power_count++; a->power_sum += power; }
        }
    }
}

static void parse_lte_pucch_power(uint16_t code, const uint8_t *b, int n) {
    if (n < 4) return;
    int version = b[0], header, size, count, power_at;
    enum lte_schedule_kind kind;
    if (code == 0xB16F && (version == 48 || version == 49)) {
        /* v48 power control: 12-byte entries. v49's actual power
           agrees with all time-aligned B13C v162 PUCCH transmissions. */
        header = 4; size = 12; count = b[3]; power_at = 10;
        kind = LTE_PUCCH_POWER;
    } else if (code == 0xB13C && version == 162 && n >= 8) {
        header = 8; size = 68; count = lebits(b, 17, 5); power_at = 21;
        kind = LTE_PUCCH_TX;
    } else {
        unknown_layout(code, version, b, n, "LTE PUCCH power version");
        return;
    }
    layout_seen(code, version, b, n);
    if (header + count * size != n) {
        unknown_layout(code, version, b, n, "LTE PUCCH power length");
        return;
    }
    if (g_lte.pci < 0 || g_lte.arfcn < 0 || !g_lte.t_seen || mono_ms() - g_lte.t_seen > 3000) return;
    /* No verified secondary PUCCH identity in these layouts. */
    struct lte_schedule_carrier *a = lte_schedule_window(kind, version);
    for (int i = 0; i < count; i++) {
        const uint8_t *record = b + header + size * i;
        int power = (int8_t)record[power_at];
        if (power < -50 || power > 35) continue;
        a->power_count++;
        a->power_sum += power;
    }
}

/* NR layer totals from CellNinjia's versioned statistics and MobileInsight.
   https://github.com/NEMO-LAB-repo/CellNinjia_Asn1_Library/tree/main/asn1_group
   https://github.com/mobile-insight/mobileinsight-core/tree/master/dm_collector_c */
struct nr_layer_layout {
    uint16_t code;
    uint32_t version;
    const char *layer, *direction;
    int header, count_offset, count_mask, stride, bytes_offset, bytes_width;
};
static const struct nr_layer_layout NR_LAYER_LAYOUTS[] = {
    { 0xB842, 5,       "PDCP", "DL", 18,  4,   0, 116, 20, 4 },
    { 0xB842, 6,       "PDCP", "DL", 18,  4,   0, 122, 18, 4 },
    { 0xB860, 9,       "PDCP", "UL", 76,  5,  31, 128, 40, 4 },
    { 0xB860, 0x3000c, "PDCP", "UL", 76,  5,  31, 216, 104, 4 },
    { 0xB84D, 0x30000, "RLC",  "DL", 68,  4, 255, 100, 94, 4 },
    { 0xB84D, 0x30005, "RLC",  "DL", 564, 4, 255, 108, 102, 4 },
    { 0xB84D, 4,       "RLC",  "DL", 84,  4, 255, 100, 94, 4 },
    { 0xB868, 4,       "RLC",  "UL", 36,  5, 255, 104, 56, 4 },
    { 0xB868, 0x30006, "RLC",  "UL", 36,  5, 255, 160, 56, 4 },
    { 0xB881, 0x30001, "MAC",  "UL", 12,  7, 255,  80,  0, 4 },
    { 0xB881, 0x20000, "MAC",  "UL", 20, 15, 255,  92,  0, 8 },
    { 0xB881, 0x20001, "MAC",  "UL", 20, 15, 255,  96,  0, 8 },
};
enum { LAYER_MAX_RECORDS = 64 };
struct layer_snapshot {
    int64_t time;
    int count;
    uint64_t bytes[LAYER_MAX_RECORDS];
    uint16_t bearer[LAYER_MAX_RECORDS];
};
static struct layer_snapshot g_nr_layer[sizeof(NR_LAYER_LAYOUTS) / sizeof(NR_LAYER_LAYOUTS[0])];

static void layer_rate(struct layer_snapshot *previous, const struct layer_snapshot *next,
                       const char *rat, const char *layer, const char *direction, uint16_t code) {
    int64_t dt = next->time - previous->time;
    if (dt == 0 && next->count == previous->count) return;
    int comparable = previous->time && dt > 0 && dt <= 20000 && next->count == previous->count && next->count > 0;
    uint64_t change = 0;
    for (int r = 0; comparable && r < next->count; r++) {
        if (next->bearer[r] != previous->bearer[r] || next->bytes[r] < previous->bytes[r]) {
            comparable = 0;
            break;
        }
        uint64_t bytes = next->bytes[r] - previous->bytes[r];
        if (bytes > (uint64_t)dt * 2500000 - change) comparable = 0;
        else change += bytes;
    }
    *previous = *next;
    if (!comparable) return;
    struct jb j;
    jb_init(&j, "layer_throughput");
    jb_add(&j, ",\"rat\":\"%s\",\"layer\":\"%s\",\"dir\":\"%s\",\"src\":\"0x%04X\",\"dt_ms\":%lld",
           rat, layer, direction, code, (long long)dt);
    jb_f(&j, "mbps", change * 0.008 / dt);
    jb_emit(&j);
}

static void parse_nr_layer_stats(uint16_t code, const uint8_t *b, int n) {
    if (n < 4) return;
    uint32_t version = rd32(b);
    for (size_t i = 0; i < sizeof(NR_LAYER_LAYOUTS) / sizeof(NR_LAYER_LAYOUTS[0]); i++) {
        const struct nr_layer_layout *l = &NR_LAYER_LAYOUTS[i];
        if (l->code != code || l->version != version) continue;
        if (n < l->header) break;
        uint32_t count = l->count_mask ? b[l->count_offset] & l->count_mask : rd32(b + l->count_offset);
        if (count > LAYER_MAX_RECORDS || n != l->header + (int)count * l->stride) break;
        layout_seen(code, version, b, n);
        struct layer_snapshot next = { .time = g_rec_modem_ms ? g_rec_modem_ms : pkt_now_ms(), .count = (int)count };
        for (uint32_t r = 0; r < count; r++) {
            const uint8_t *record = b + l->header + r * l->stride;
            next.bytes[r] = l->bytes_width == 8 ? rd64(record + l->bytes_offset) : rd32(record + l->bytes_offset);
            next.bearer[r] = code == 0xB881 ? (uint8_t)r : record[0];
        }
        layer_rate(&g_nr_layer[i], &next, "NR", l->layer, l->direction, code);
        return;
    }
    unknown_layout(code, version, b, n, "layer byte counter layout");
}

/* Flat LTE statistics layouts verified against the X80 QMDL. DL PDCP uses
   received data PDU bytes and DL RLC uses its trailing received-byte
   counter. UL uses PDCP transmitted data and RLC new data PDU bytes. */
static const struct {
    uint16_t code;
    uint8_t version, header, count_offset, count_mask;
    uint16_t stride;
    uint8_t byte_offset, width, sub_id, sub_version, bearer_offset;
    const char *layer, *direction;
} LTE_LAYER_LAYOUTS[] = {
    { 0xB087, 50, 72, 4, 0xff, 100, 94, 4,   0,  0, 0, "RLC", "DL" },
    { 0xB097, 57, 12, 5, 0x1f,  64,  8, 4,   0,  0, 0, "RLC", "UL" },
    { 0xB0A4, 52, 18, 4, 0xff, 122, 18, 4,   0,  0, 0, "PDCP", "DL" },
    { 0xB0B4, 59, 12, 5, 0x1f, 108, 16, 8,   0,  0, 4, "PDCP", "UL" },
    /* Legacy subpacket layouts from MobileInsight's log_packet definitions. */
    { 0xB087,  1, 17, 8, 0xff, 182, 10, 4,  66,  2, 0, "RLC", "DL" },
    { 0xB087,  1, 17, 8, 0xff, 262, 10, 8,  66,  3, 0, "RLC", "DL" },
    { 0xB097,  1, 13, 8, 0xff, 126, 10, 4,  71,  1, 0, "RLC", "UL" },
    { 0xB0A4,  1, 21, 8, 0xff, 167, 15, 4, 196,  2, 0, "PDCP", "DL" },
    { 0xB0A4,  1, 33, 8, 0xff, 175, 15, 4, 196, 41, 0, "PDCP", "DL" },
    { 0xB0B4,  1, 13, 8, 0xff, 111, 15, 4, 197,  1, 0, "PDCP", "UL" },
    { 0xB0B4,  1, 13, 8, 0xff, 141, 21, 4, 197,  2, 0, "PDCP", "UL" },
    { 0xB0B4,  1, 13, 8, 0xff, 253, 25, 4, 197,  3, 0, "PDCP", "UL" },
    { 0xB0B4,  1, 13, 8, 0xff, 293, 25, 8, 197, 26, 0, "PDCP", "UL" },
};
static struct layer_snapshot g_lte_layer[sizeof(LTE_LAYER_LAYOUTS) / sizeof(LTE_LAYER_LAYOUTS[0])];

static void parse_lte_layer_stats(uint16_t code, const uint8_t *b, int n) {
    if (n < 6) return;
    int version = b[0];
    layout_seen(code, version, b, n);
    for (size_t i = 0; i < sizeof(LTE_LAYER_LAYOUTS) / sizeof(LTE_LAYER_LAYOUTS[0]); i++) {
        if (code != LTE_LAYER_LAYOUTS[i].code || version != LTE_LAYER_LAYOUTS[i].version) continue;
        if (LTE_LAYER_LAYOUTS[i].sub_id && (n < 9 || b[1] != 1 || b[4] != LTE_LAYER_LAYOUTS[i].sub_id ||
            b[5] != LTE_LAYER_LAYOUTS[i].sub_version || rd16(b + 6) != n - 4)) continue;
        int count = b[LTE_LAYER_LAYOUTS[i].count_offset] & LTE_LAYER_LAYOUTS[i].count_mask;
        int stride = LTE_LAYER_LAYOUTS[i].stride;
        int header = LTE_LAYER_LAYOUTS[i].header;
        if (count > LAYER_MAX_RECORDS || n != header + count * stride) break;
        struct layer_snapshot next = { .time = g_rec_modem_ms ? g_rec_modem_ms : pkt_now_ms(), .count = count };
        for (int r = 0; r < count; r++) {
            const uint8_t *record = b + header + r * stride;
            const uint8_t *counter = record + LTE_LAYER_LAYOUTS[i].byte_offset;
            next.bearer[r] = rd16(record + LTE_LAYER_LAYOUTS[i].bearer_offset);
            next.bytes[r] = LTE_LAYER_LAYOUTS[i].width == 8 ? rd64(counter) : rd32(counter);
        }
        layer_rate(&g_lte_layer[i], &next, "LTE", LTE_LAYER_LAYOUTS[i].layer, LTE_LAYER_LAYOUTS[i].direction, code);
        return;
    }
    unknown_layout(code, version, b, n, "LTE layer byte counter layout");
}

/* B063 v50 uses a flat list of TBs followed by each TB's SDU descriptors. */
static int lte_mac_dl_v50_bytes(const uint8_t *b, int n, uint64_t *bytes) {
    if (n < 8) return 0;
    int count = rd16(b + 4), pos = 8;
    if (count > (n - pos) / 16) return 0;
    for (int i = 0; i < count; i++) {
        if (n - pos < 16) return 0;
        const uint8_t *tb = b + pos;
        uint32_t size = rd32(tb), padding = rd32(tb + 4);
        if (padding > size) return 0;
        if (((rd32(tb + 8) >> 15) & 3) == 0) *bytes += size - padding;
        int sdus = tb[13];
        pos += 16;
        for (int s = 0; s < sdus; s++) {
            if (n - pos < 12) return 0;
            const uint8_t *sdu = b + pos;
            pos += 12;
            if (sdu[0] & 1) continue; /* fixed-size MAC control element */
            int nlo = rd16(sdu + 9);
            if (nlo > (n - pos) / 4) return 0;
            pos += 4 * nlo;
            if (sdu[8]) {
                int more;
                do {
                    if (n - pos < 4) return 0;
                    more = b[pos] & 1;
                    pos += 4;
                } while (more);
            }
        }
    }
    return pos == n;
}

/* The legacy v7 UL report omits the Sub ID and aligns to 4 bytes. */
struct lte_mac_control { int phr, bsr[4]; unsigned bsr_mask; };

/* LTE UL-SCH headers are followed by fixed control elements before SDUs. */
static void lte_mac_ul_control(const uint8_t *b, int n, struct lte_mac_control *out) {
    uint8_t headers[32];
    int count = 0, pos = 0;
    for (;;) {
        if (pos >= n || count >= (int)sizeof(headers)) return;
        uint8_t header = b[pos++];
        if (header & 0xc0) return; /* Only the ordinary one-byte LCID header. */
        unsigned lcid = header & 31;
        int more = header & 32;
        if (lcid >= 26 && lcid <= 30) headers[count++] = (uint8_t)lcid;
        else if (lcid <= 10) {
            headers[count++] = (uint8_t)lcid;
            if (more) {
                if (pos >= n) return;
                pos += (b[pos] & 0x80) ? 2 : 1;
            }
        } else if (lcid != 31) return;
        if (!more) break;
    }
    for (int i = 0; i < count && headers[i] >= 26; i++) {
        static const int ce_bytes[] = { 1, 2, 1, 1, 3 };
        unsigned lcid = headers[i];
        int size = ce_bytes[lcid - 26];
        if (size > n - pos) return;
        const uint8_t *ce = b + pos;
        pos += size;
        if (lcid == 26 && !(ce[0] & 0xc0)) out->phr = ce[0];
        if (lcid == 28 || lcid == 29) {
            int lcg = ce[0] >> 6;
            out->bsr[lcg] = ce[0] & 63;
            out->bsr_mask = 1u << lcg;
        } else if (lcid == 30) {
            uint32_t packed = ((uint32_t)ce[0] << 16) | ((uint32_t)ce[1] << 8) | ce[2];
            for (int g = 0; g < 4; g++) out->bsr[g] = (packed >> (18 - 6 * g)) & 63;
            out->bsr_mask = 15;
        }
    }
}

static int lte_mac_legacy_bytes(uint16_t code, const uint8_t *b, int n, uint64_t *bytes,
                               struct lte_mac_control *control) {
    int ul = code == 0xB064;
    int version = b[0], sub_version = b[5];
    int v7 = ul && sub_version == 7;
    int header = ul ? (sub_version == 2 ? 14 : 12 + v7) : (sub_version == 4 ? 14 : 12);
    if (version != 1 || b[1] != 1 || b[4] != (ul ? 8 : 7) || rd16(b + 6) != n - 4 ||
        (ul ? sub_version != 1 && sub_version != 2 && !v7 : sub_version != 2 && sub_version != 4)) {
        return 0;
    }
    int pos = 9;
    int rnti_at = ul ? header - 11 : header - 10;
    int tbs_at = header - (ul ? 8 : 6);
    for (int i = 0; i < b[8]; i++) {
        if (pos + header > n) return 0;
        const uint8_t *r = b + pos;
        unsigned size = rd16(r + tbs_at), padding = rd16(r + tbs_at + 3);
        if (padding > size) return 0;
        int logged = r[header - 1];
        if (logged > n - pos - header) return 0;
        if ((r[rnti_at] & 0xf) <= 1) {
            *bytes += size - padding;
            if (v7 && control) lte_mac_ul_control(r + header, logged, control);
        }
        pos += header + logged;
    }
    if (pos > n || n - pos > (v7 ? 3 : 0)) return 0;
    for (; pos < n; pos++) if (b[pos]) return 0;
    return 1;
}

static void emit_lte_mac_control(const struct lte_mac_control *control, int64_t now) {
    static int pci = -1;
    static long earfcn = -1;
    static int64_t last[2];
    if (g_lte.pci < 0 || g_lte.arfcn < 0) return;
    if (g_lte.pci != pci || g_lte.arfcn != earfcn) {
        pci = g_lte.pci;
        earfcn = g_lte.arfcn;
        last[0] = last[1] = 0;
    }
    for (int kind = 0; kind < 2; kind++) {
        if (kind == 0 ? control->phr < 0 : !control->bsr_mask) continue;
        if (last[kind] && now >= last[kind] && now - last[kind] < 200) continue;
        struct jb j;
        jb_init(&j, kind ? "lte_bsr" : "lte_phr");
        jb_add(&j, ",\"rat\":\"LTE\",\"src\":\"B064\",\"pci\":%d,\"arfcn\":%ld", pci, earfcn);
        if (!kind) jb_i(&j, "ph_index", control->phr);
        else for (int g = 0; g < 4; g++) {
            static const char *const groups[] = { "lcg0", "lcg1", "lcg2", "lcg3" };
            if (control->bsr_mask & (1u << g)) jb_i(&j, groups[g], control->bsr[g]);
        }
        jb_emit(&j);
        last[kind] = now;
    }
}

static void parse_lte_mac_tb(uint16_t code, const uint8_t *b, int n) {
    if (n < 8) return;
    int ul = code == 0xB064;
    int version = b[0] == 1 ? 0x100 | b[5] : b[0];
    int v50 = !ul && rd32(b) == 50;
    uint64_t bytes = 0;
    struct lte_mac_control control = { .phr = -1 };
    layout_seen(code, version, b, n);
    if (!(v50 ? lte_mac_dl_v50_bytes(b, n, &bytes) : n >= 9 && lte_mac_legacy_bytes(code, b, n, &bytes, &control))) {
        unknown_layout(code, version, b, n, "LTE MAC transport block layout");
        return;
    }

    static struct {
        int64_t start, last;
        uint64_t bytes;
        int version, pci;
        long earfcn;
    } windows[2];
    int64_t now = pkt_now_ms();
    if (control.phr >= 0 || control.bsr_mask) emit_lte_mac_control(&control, now);
    if (!windows[ul].start || now < windows[ul].last || now - windows[ul].last > 6000 ||
        windows[ul].version != version || windows[ul].pci != g_lte.pci || windows[ul].earfcn != g_lte.arfcn) {
        windows[ul].start = now;
        windows[ul].bytes = 0;
        windows[ul].version = version;
        windows[ul].pci = g_lte.pci;
        windows[ul].earfcn = g_lte.arfcn;
    }
    windows[ul].last = now;
    int64_t dt = now - windows[ul].start;
    if (dt >= 1000) {
        struct jb j;
        jb_init(&j, "layer_throughput");
        jb_add(&j, ",\"rat\":\"LTE\",\"layer\":\"MAC\",\"dir\":\"%s\",\"src\":\"0x%04X\",\"dt_ms\":%lld",
               ul ? "UL" : "DL", code, (long long)dt);
        if (g_lte.pci >= 0 && g_lte.arfcn >= 0) {
            jb_i(&j, "pci", g_lte.pci);
            jb_i(&j, "earfcn", g_lte.arfcn);
        }
        jb_f(&j, "mbps", windows[ul].bytes * 0.008 / dt);
        jb_emit(&j);
        windows[ul].start = now;
        windows[ul].bytes = 0;
    }
    windows[ul].bytes += bytes;
}

/* ===== 1.7: B8A7 / B825 / B887 / B884 from static analysis (see DiagCap_B887_B884_static_analysis.md) =====
   Layout facts (bit offsets, little-endian bit fields, relative to payload / record / item start, payload includes
   the 4-byte version word) were cross-checked. QBM refers to qcom-band-menu qbm_layouts.h
   (GPLv3; used as a reference for layout facts only, code here is our own).
   "tentative":0 only where two independent sources agree. */

static int pos_fits(int off, int bits, int nbytes) { return off + bits <= nbytes * 8; }

/* --- 0xB8A7 NR5G ML1 CSF report (RI, CRI, CQI, carrier ID; v3.5 matched run3/run4 samples) --- */
struct b8a7_l { uint32_t ver; int rc_off, rec_start, reps_at, rep_bytes; };
static const struct b8a7_l B8A7_L[] = {
    { 0x20000, 120, 16, 8, 40 }, { 0x20001, 120, 16, 8, 60 }, { 0x20002, 120, 16, 12, 60 }, { 0x20003, 120, 16, 12, 60 },
    { 0x30000, 56, 8, 8, 60 }, { 0x30001, 56, 8, 12, 0 }, { 0x30002, 56, 8, 12, 0 }, { 0x30003, 56, 8, 12, 0 },
    { 0x30004, 56, 8, 12, 60 }, { 0x30005, 56, 8, 16, 60 }, { 0x30006, 56, 8, 16, 60 }, { 0x30007, 56, 8, 16, 60 },
};
static int64_t g_csf_t; static int g_csf_n, g_csf_cqi_sum, g_csf_cqi_min = 99, g_csf_cqi_max = -1, g_csf_rank[5], g_csf_cc, g_csf_reports, g_csf_skipped;
static void parse_nr_b8a7(const uint8_t *b, int n) {
    if (n < 4) return;
    int rel_min = rd16(b), rel_maj = rd16(b + 2);
    uint32_t ver = ((uint32_t)rel_maj << 16 | (uint32_t)rel_min);
    layout_seen(0xB8A7, ver, b, n);
    const struct b8a7_l *L = NULL;
    for (size_t i = 0; i < sizeof(B8A7_L) / sizeof(B8A7_L[0]); i++) if (B8A7_L[i].ver == ver) L = &B8A7_L[i];
    if (!L) { unknown_layout(0xB8A7, ver, b, n, "version"); return; }
    if (!pos_fits(L->rc_off, 8, n)) return;
    int recs = (int)lebits(b, L->rc_off, 8), pos = L->rec_start;
    if (L->rep_bytes) {           /* walk everything first: the packet must be consumed exactly */
        int p = pos;
        for (int r = 0; r < recs; r++) {
            if (p + L->reps_at > n) return;
            uint32_t k = rd32(b + p + 4); p += L->reps_at;
            if (k > (uint32_t)((n - p) / L->rep_bytes)) return;
            p += (int)k * L->rep_bytes;
        }
        if (p != n) { unknown_layout(0xB8A7, ver, b, n, "length"); return; }
    } else if (recs > 1) recs = 1; /* variable-size reports (3.1-3.3): first report only */
    for (int r = 0; r < recs; r++) {
        if (pos + L->reps_at > n) return;
        uint32_t k = rd32(b + pos + 4); pos += L->reps_at;
        if (!L->rep_bytes && k > 1) k = 1;
        for (uint32_t i = 0; i < k; i++, pos += L->rep_bytes) {
            if (pos + 10 > n) return;
            const uint8_t *rp = b + pos;
            g_csf_reports++;
            if (lebits(rp, 16, 8) & 32) { g_csf_skipped++; continue; }   /* L1-RSRP-only report: no RI/CQI */
            int cc = (int)lebits(rp, 4, 4), ri = (int)lebits(rp, 67, 2), cqi = (int)lebits(rp, 69, 4);
            if (cqi == 0 || cc > 7) continue;
            g_csf_n++; g_csf_cqi_sum += cqi; if (cqi < g_csf_cqi_min) g_csf_cqi_min = cqi; if (cqi > g_csf_cqi_max) g_csf_cqi_max = cqi;
            g_csf_rank[ri + 1]++; g_csf_cc = cc;
        }
    }
    int64_t now = mono_ms();
    if (!g_csf_t) g_csf_t = now;
    if (now - g_csf_t < 1000 || !g_csf_n) return;
    int rk = 1; for (int i = 2; i <= 4; i++) if (g_csf_rank[i] > g_csf_rank[rk]) rk = i;
    struct jb j; jb_init(&j, "nr_csf");
    jb_add(&j, ",\"src\":\"B8A7\",\"ver\":\"%d.%d\",\"tentative\":%d,\"cc\":%d,\"n\":%d,\"rank\":%d,\"cqi_min\":%d,\"cqi_max\":%d",
           rel_maj, rel_min, (ver == 0x30005) ? 0 : 1, g_csf_cc, g_csf_n, rk, g_csf_cqi_min, g_csf_cqi_max);
    jb_f(&j, "cqi_avg", (double)g_csf_cqi_sum / g_csf_n);
    jb_add(&j, ",\"rank_hist\":[%d,%d,%d,%d],\"reports\":%d,\"rsrp_only\":%d", g_csf_rank[1], g_csf_rank[2], g_csf_rank[3], g_csf_rank[4],
           g_csf_reports, g_csf_skipped);
    jb_emit(&j);
    g_csf_t = now; g_csf_n = g_csf_cqi_sum = g_csf_reports = g_csf_skipped = 0; g_csf_cqi_min = 99; g_csf_cqi_max = -1; memset(g_csf_rank, 0, sizeof(g_csf_rank));
}

/* --- 0xB825 NR5G RRC Configuration Info: serving cell list (QBM table 3.0-3.10; run3/run4 v3.8 hex matches) --- */
struct b825_l { uint32_t ver; int arrays_at, skip0, skip1, cnt_off, cell_bytes, has_scs, bw35; };
static const struct b825_l B825_L[] = {
    { 0x30000, 63, 464, 472, 480, 22, 0, 0 },
    { 0x30001, 71, 528, 536, 544, 23, 1, 0 }, { 0x30002, 71, 528, 536, 544, 23, 1, 0 }, { 0x30003, 71, 528, 536, 544, 23, 1, 1 },
    { 0x30004, 72, 536, 544, 552, 23, 1, 1 }, { 0x30005, 72, 536, 544, 552, 25, 1, 1 }, { 0x30006, 72, 536, 544, 552, 25, 1, 1 },
    { 0x30008, 72, 536, 544, 552, 26, 1, 1 }, { 0x30009, 72, 536, 544, 552, 26, 1, 1 }, { 0x3000a, 72, 536, 544, 552, 29, 1, 1 },
};
enum { B825_MAX_CELLS = 16, B825_KEY_BYTES = 128 + B825_MAX_CELLS * 128 };
static char g_b825_last[B825_KEY_BYTES];
static int64_t g_b825_emitted;
static void parse_nr_b825(const uint8_t *b, int n) {
    if (n < 4) return;
    int rel_min = rd16(b), rel_maj = rd16(b + 2);
    uint32_t ver = ((uint32_t)rel_maj << 16 | (uint32_t)rel_min);
    layout_seen(0xB825, ver, b, n);
    const struct b825_l *L = NULL;
    for (size_t i = 0; i < sizeof(B825_L) / sizeof(B825_L[0]); i++) if (B825_L[i].ver == ver) L = &B825_L[i];
    if (!L) { unknown_layout(0xB825, ver, b, n, "version"); return; }
    if (L->arrays_at > n || !pos_fits(L->cnt_off, 8, n)) return;
    int pos = L->arrays_at;
    int s0 = (int)lebits(b, L->skip0, 8), s1 = (int)lebits(b, L->skip1, 8);
    if (s0 > 64 || s1 > 64) return;
    pos += 4 * (s0 + s1);
    int nc = (int)lebits(b, L->cnt_off, 8);
    if (nc > B825_MAX_CELLS || pos + nc * L->cell_bytes > n) return;
    static const int bw_a[] = { 5, 10, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90, 100, 200, 400 };              /* 3.0-3.2 */
    static const int bw_b[] = { 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 60, 70, 80, 90, 100, 200, 400 };      /* 3.3+   */
    int state = b[12];
    struct jb j; jb_init(&j, "nr_cfg");
    const int key_at = j.n;
    jb_add(&j, ",\"src\":\"B825\",\"ver\":\"%d.%d\",\"tentative\":%d,\"state\":\"%s\",\"ncells\":%d",
           rel_maj, rel_min, ver == 0x30008 ? 0 : 1, state == 2 ? "connected" : state == 4 ? "idle" : "?", nc);
    if (nc > 0) jb_add(&j, ",\"pci\":%u,\"ssb_arfcn\":%lu", (unsigned)rd16(b + pos + 1), (unsigned long)rd32(b + pos + 11));
    if (ver == 0x30008 && nc > 0) jb_i(&j, "nrb", b[18]);
    jb_add(&j, ",\"cells\":[");
    for (int i = 0; i < nc; i++) {
        const uint8_t *c = b + pos + i * L->cell_bytes;
        int cc = c[0], pci = rd16(c + 1), band = rd16(c + 15), bwc = c[18], scs = L->has_scs ? c[20] : -1, ml = L->has_scs ? c[21] : c[20];
        long dl = rd32(c + 3), ssb = rd32(c + 11);
        int bw = L->bw35 ? (bwc < 17 ? bw_b[bwc] : 0) : (bwc < 15 ? bw_a[bwc] : 0);
        jb_add(&j, "%s{\"cc\":%d,\"pci\":%d,\"band\":%d,\"dl_arfcn\":%ld,\"ssb_arfcn\":%ld,\"bw_mhz\":%d,\"scs_khz\":%d,\"max_layers\":%d}",
               i ? "," : "", cc, pci, band, dl, ssb, bw, (scs >= 0 && scs < 5) ? 15 << scs : 0, ml);
    }
    jb_add(&j, "]");
    const char *key = j.b + key_at;
    if (j.n - key_at >= (int)sizeof(g_b825_last)) return;
    int64_t now = mono_ms();
    if (!strcmp(key, g_b825_last) && now - g_b825_emitted < 10000) return;
    g_b825_emitted = now;
    snprintf(g_b825_last, sizeof(g_b825_last), "%s", key);
    jb_emit(&j);
}

/* --- 0xB9A7 NR5G CA state: fixed-size, versioned carrier table --- */
static void parse_nr_b9a7(const uint8_t *b, int n) {
    enum { HEADER = 24, STRIDE = 24, MAX_CC = 16 };
    static const struct { uint32_t version; int count_at; } layouts[] = {
        { 0x20003, 5 }, { 0x20004, 9 }, { 0x30000, 9 }, { 0x30002, 9 },
    };
    uint32_t ver = n >= 4 ? rd32(b) : 0;
    int count_at = -1;
    for (size_t i = 0; i < sizeof(layouts) / sizeof(layouts[0]); i++) {
        if (layouts[i].version == ver) { count_at = layouts[i].count_at; break; }
    }
    if (n < HEADER || count_at < 0) {
        unknown_layout(NR_CA_STATE_CODE, ver, b, n, n < HEADER ? "header" : "version");
        return;
    }
    int count = b[count_at];
    int count_used = count;
    if (count > MAX_CC || n != HEADER + count * STRIDE) {
        /* Only infer additional complete rows for an in-range active count.
         * A shorter table remains truncation even if the count is oversized. */
        int derived = n > HEADER ? (n - HEADER) / STRIDE : 0;
        int usable = n > HEADER && (n - HEADER) % STRIDE == 0 && derived >= 1 && derived <= MAX_CC;
        if (usable && count <= MAX_CC && derived > count) {
            count_used = derived;
        } else {
            unknown_layout(NR_CA_STATE_CODE, ver, b, n, "count/length");
            return;
        }
    }
    uint32_t seen = 0;
    for (int i = 0; i < count_used; i++) {
        const uint8_t *c = b + HEADER + i * STRIDE;
        unsigned cc = c[0], dl = rd16(c + 4), ul = rd16(c + 16);
        unsigned pci = rd16(c + 2);
        uint32_t ssb = rd32(c + 8);
        unsigned band = (unsigned)rd16(c + 12) + 1;
        int sul = band == 80 || band == 81 || band == 82 || band == 83 || band == 84 ||
                  band == 86 || band == 89 || band == 95 || band == 97 || band == 98 || band == 99;
        /* PCC and SUL can omit the DL identity; their placeholder fields are not joined. */
        int needs_identity = cc != 0 && !sul && (dl == 1 || dl == 2 || ul == 1 || ul == 2);
        if (cc >= MAX_CC || (seen & (1U << cc)) || dl > 3 || ul > 3 ||
            (needs_identity && (pci > 1007 || ssb == 0 || ssb > 3279165))) {
            unknown_layout(NR_CA_STATE_CODE, ver, b, n, "carrier/state/identity");
            return;
        }
        seen |= 1U << cc;
    }
    if (count_used != count) layout_variant(NR_CA_STATE_CODE, ver, n, count_used);
    layout_seen(NR_CA_STATE_CODE, ver, b, n);
    struct jb j; jb_init(&j, "nr_ca_state");
    jb_add(&j, ",\"rat\":\"NR\",\"src\":\"B9A7\",\"ver\":\"%u.%u\",\"tentative\":0,\"cc\":[",
           ver >> 16, ver & 0xffff);
    for (int i = 0; i < count_used; i++) {
        const uint8_t *c = b + HEADER + i * STRIDE;
        jb_add(&j, "%s{\"cc\":%u,\"pci\":%u,\"ssb_arfcn\":%lu,\"band\":%u,\"dl\":%u,\"ul\":%u}",
               i ? "," : "", (unsigned)c[0], (unsigned)rd16(c + 2), (unsigned long)rd32(c + 8),
               (unsigned)rd16(c + 12) + 1, (unsigned)rd16(c + 4), (unsigned)rd16(c + 16));
    }
    jb_add(&j, "]");
    jb_emit(&j);
}

/* --- 0xB887 NR5G MAC PDSCH Status: one item per TB ---
   Common item fields:
     pci{32,10} arfcn{42,22} tb_size{69,18} bytes  scs_mu{87,3}  mcs{90,5}  rbs{96,9}  rnti{114,4} (0 C-RNTI, 3 CS-RNTI)
     layers{125,2}+1  crc{128,1} (1 pass)  new_tx{129,1}
   Per version: record count / start, item size, scell, bw code, modulation, rx count, FR2 flag. */
struct b887_l { uint32_t ver; int rc_off, rec_start, item_bytes, scell_off, bw_off, mod_off, rx_off, rx_bits, bt_off, two_src; };
static const struct b887_l B887_L[] = {
    { 0x20001, 120, 16, 20, 4, 25, -1, -1, 0, 29, 0 }, { 0x20002, 120, 16, 20, 4, 25, -1, -1, 0, 29, 0 },
    { 0x20003, 120, 16, 20, 4, 25, 146, 150, 2, 29, 0 },
    { 0x20004, 120, 16, 20, 4, 25, 146, 150, 2, 29, 1 }, { 0x20005, 120, 16, 20, 4, 25, 146, 150, 2, 29, 1 },
    { 0x30000, 56, 8, 20, 4, 25, 146, 150, 2, 29, 0 },
    { 0x30002, 56, 8, 20, 4, 25, 146, 150, 2, 29, 1 }, { 0x30003, 56, 8, 20, 4, 25, 146, 150, 2, 29, 1 },
    { 0x30004, 56, 8, 20, 4, 25, 146, 149, 3, 29, 0 },
    { 0x30005, 56, 8, 24, 4, 25, 146, 149, 3, 29, 0 },
    { 0x30006, 56, 8, 24, 4, 25, 146, 149, 3, 29, 1 }, { 0x30007, 56, 8, 24, 4, 25, 146, 149, 3, 29, 1 },
    { 0x30008, 56, 8, 24, 4, 25, 146, 149, 3, 29, 0 },   /* QBM: "decoded with 3.6's layout" */
    { 0x3000a, 56, 8, 28, 4, 25, 146, 149, 3, 29, 1 },
    { 0x3000d, 56, 8, 36, 1, 22, 147, 150, 3, -1, 1 }, { 0x3000e, 56, 8, 36, 1, 22, 147, 150, 3, -1, 0 },
};
struct dl_acc { int used, pci, bw, scs, bt; long arfcn; uint32_t tb, fail, mcs_n, mcs_sum, mcs_max, rb_sum, lay_sum, newtx;
                uint64_t pass_bytes, all_bytes; uint32_t mcs_hist[32], mod[5], rx[4], m64, m256, mchk; };
static struct dl_acc g_dl[8];
static int64_t g_dl_t, g_dl_last;
static int g_dl_pci = -1;
static long g_dl_arfcn = -1;
static uint32_t g_dl_ver;
static int g_dl_bad, g_dl_other_rnti;
static int mod_for_mcs(int mcs, int t256) {   /* 38.214 Table 5.1.3.1-1 (64QAM) / -2 (256QAM); -1 = reserved */
    if (t256) { if (mcs <= 4) return 0; if (mcs <= 10) return 1; if (mcs <= 19) return 2; if (mcs <= 27) return 3; return -1; }
    if (mcs <= 9) return 0;
    if (mcs <= 16) return 1;
    if (mcs <= 28) return 2;
    return -1;
}
static void dl_flush(int64_t now) {
    int64_t dt = now - g_dl_t;
    struct jb j; jb_init(&j, "nr_dl_sched");
    const struct b887_l *L = NULL;
    for (size_t i = 0; i < sizeof(B887_L) / sizeof(B887_L[0]); i++) if (B887_L[i].ver == g_dl_ver) L = &B887_L[i];
    jb_add(&j, ",\"src\":\"B887\",\"ver\":\"%u.%u\",\"tentative\":%d,\"dt_ms\":%lld,\"bad_pkts\":%d,\"other_rnti_tb\":%d",
           g_dl_ver >> 16, g_dl_ver & 0xffff, (L && L->two_src) ? 0 : 1, (long long)dt, g_dl_bad, g_dl_other_rnti);
    if (g_dl_pci >= 0 && g_dl_arfcn > 0) {
        jb_i(&j, "pci", g_dl_pci);
        jb_i(&j, "arfcn", g_dl_arfcn);
    }
    jb_add(&j, ",\"cc\":[");
    static const int bwt[13] = { 5, 10, 15, 20, 25, 30, 40, 50, 60, 70, 80, 90, 100 };
    int first = 1;
    for (int c = 0; c < 8; c++) {
        struct dl_acc *a = &g_dl[c];
        if (!a->used || !a->tb) continue;
        int mode = 0; for (int m = 1; m < 32; m++) if (a->mcs_hist[m] > a->mcs_hist[mode]) mode = m;
        int bw = a->bt ? (a->bw == 1 ? 100 : 0) : (a->bw < 13 ? bwt[a->bw] : 0);
        jb_add(&j, "%s{\"cc\":%d,\"pci\":%d,\"arfcn\":%ld,\"bw_mhz\":%d,\"scs_khz\":%d,\"tb\":%u,\"crc_fail\":%u",
               first ? "" : ",", c, a->pci, a->arfcn, bw, a->scs < 4 ? 15 << a->scs : 0, a->tb, a->fail);
        jb_f(&j, "bler_pct", 100.0 * a->fail / a->tb);
        jb_f(&j, "mcs_avg", a->mcs_n ? (double)a->mcs_sum / a->mcs_n : NA_F);
        jb_add(&j, ",\"mcs_max\":%u,\"mcs_mode\":%d", a->mcs_max, mode);
        if (L && L->mod_off >= 0) {
            static const char *const names[] = { "QPSK", "16QAM", "64QAM", "256QAM", "1024QAM" };
            int modulation = 0;
            for (int m = 1; m < 5; m++) if (a->mod[m] > a->mod[modulation]) modulation = m;
            if (a->mod[modulation]) jb_add(&j, ",\"mod\":\"%s\"", names[modulation]);
            jb_add(&j, ",\"mod_hist\":[%u,%u,%u,%u,%u]", a->mod[0], a->mod[1], a->mod[2], a->mod[3], a->mod[4]);
        }
        if (L && L->rx_off >= 0) jb_add(&j, ",\"rx_hist\":[%u,%u,%u,%u]", a->rx[0], a->rx[1], a->rx[2], a->rx[3]);
        jb_f(&j, "rb_avg", (double)a->rb_sum / a->tb);
        jb_f(&j, "layers_avg", (double)a->lay_sum / a->tb);
        jb_add(&j, ",\"newtx\":%u,\"pass_bytes\":%llu", a->newtx, (unsigned long long)a->pass_bytes);
        double passed_mbps = dt > 0 ? a->pass_bytes * 0.008 / dt : NA_F;
        jb_f(&j, "phy_mbps", passed_mbps);
        if (!c || (g_dl_pci >= 0 && g_dl_arfcn > 0)) jb_f(&j, "mac_crc_tb_mbps", passed_mbps);
        jb_f(&j, "phy_tb_mbps", dt > 0 ? a->all_bytes * 0.008 / dt : NA_F);
        if (a->mchk) jb_add(&j, ",\"mod_mcs_check\":{\"n\":%u,\"t64\":%u,\"t256\":%u}", a->mchk, a->m64, a->m256);
        jb_add(&j, "}");
        first = 0;
    }
    jb_add(&j, "]");
    if (!first) jb_emit(&j);
    memset(g_dl, 0, sizeof(g_dl)); g_dl_bad = 0; g_dl_other_rnti = 0; g_dl_t = now;
}
static void parse_nr_b887(const uint8_t *b, int n) {
    if (n < 4) return;
    uint32_t ver = (uint32_t)(rd16(b + 2) << 16 | rd16(b));
    layout_seen(0xB887, ver, b, n);
    const struct b887_l *L = NULL;
    for (size_t i = 0; i < sizeof(B887_L) / sizeof(B887_L[0]); i++) if (B887_L[i].ver == ver) L = &B887_L[i];
    if (!L) { unknown_layout(0xB887, ver, b, n, "version"); return; }
    if (!pos_fits(L->rc_off, 8, n)) return;
    int recs = (int)lebits(b, L->rc_off, 8);
    /* walk first: record header 8 B, item count = record byte 4, items fixed size; must end exactly at n */
    int p = L->rec_start;
    for (int r = 0; r < recs; r++) {
        if (p + 8 > n) { g_dl_bad++; return; }
        int k = b[p + 4]; p += 8 + k * L->item_bytes;
        if (k > 32 || p > n) { g_dl_bad++; return; }
    }
    if (p != n) { g_dl_bad++; unknown_layout(0xB887, ver, b, n, "length"); return; }
    int64_t now = pkt_now_ms();
    if (!g_dl_t || now < g_dl_last || now - g_dl_last > 6000 || g_dl_ver != ver ||
        g_dl_pci != g_nr.pci || g_dl_arfcn != g_nr.arfcn) {
        memset(g_dl, 0, sizeof(g_dl));
        g_dl_bad = g_dl_other_rnti = 0;
        g_dl_t = now;
        g_dl_pci = g_nr.pci;
        g_dl_arfcn = g_nr.arfcn;
    } else if (now - g_dl_t >= 1000) {
        dl_flush(now);
    }
    g_dl_last = now;
    g_dl_ver = ver;
    p = L->rec_start;
    for (int r = 0; r < recs; r++) {
        int k = b[p + 4]; p += 8;
        for (int i = 0; i < k; i++, p += L->item_bytes) {
            const uint8_t *it = b + p;
            int rnti = (int)lebits(it, 114, 4);
            if (rnti != 0 && rnti != 3) { g_dl_other_rnti++; continue; }
            int cc = (int)lebits(it, L->scell_off, 4);
            if (cc > 7) continue;
            int pci = (int)lebits(it, 32, 10);
            long arfcn = (long)lebits(it, 42, 22);
            if (cc == 0 && g_dl_pci >= 0 && g_dl_arfcn > 0 &&
                (pci != g_dl_pci || arfcn != g_dl_arfcn)) continue;
            struct dl_acc *a = &g_dl[cc];
            if (a->used && (a->pci != pci || a->arfcn != arfcn)) memset(a, 0, sizeof(*a));
            a->used = 1;
            a->pci = pci; a->arfcn = arfcn;
            a->bw = (int)lebits(it, L->bw_off, 4); a->scs = (int)lebits(it, 87, 3);
            a->bt = L->bt_off >= 0 ? (int)lebits(it, L->bt_off, 1) : 0;
            uint32_t tbs = lebits(it, 69, 18);
            int mcs = (int)lebits(it, 90, 5), crc = (int)lebits(it, 128, 1), newtx = (int)lebits(it, 129, 1);
            a->tb++; a->all_bytes += tbs;
            if (crc) a->pass_bytes += tbs; else a->fail++;
            if (newtx) a->newtx++;
            if (!(!newtx && mcs >= 28)) { a->mcs_n++; a->mcs_sum += mcs; a->mcs_hist[mcs]++; if ((uint32_t)mcs > a->mcs_max) a->mcs_max = mcs; }
            a->rb_sum += lebits(it, 96, 9);
            a->lay_sum += lebits(it, 125, 2) + 1;
            if (L->mod_off >= 0) {
                int m = (int)lebits(it, L->mod_off, 3);
                if (m < 5) a->mod[m]++;
                if (newtx && mcs < 28 && m < 5) { a->mchk++; if (mod_for_mcs(mcs, 0) == m) a->m64++; if (mod_for_mcs(mcs, 1) == m) a->m256++; }
            }
            if (L->rx_off >= 0) { int x = (int)lebits(it, L->rx_off, L->rx_bits); if (x < 4) a->rx[x]++; }
        }
    }
}

/* --- 0xB884 NR5G MAC UL Physical Channel Power Control (QBM 3.3/3.5) ---
   header 8 B (records = byte 7); record 12 B (carriers = record byte 4); carrier 4 B (channels = byte 0);
   channel 16 B: carrier bits0-3, type bits4-7 (1..5; QBM: 4 = PUSCH, 5 = PUCCH), power s12 @8, max s12 @44 (0.1 dB);
   transmitted power = min(power, max).
   1.8: s12 @20 (0.1 dB) = DL path loss, averaged over PUSCH (type 4) blocks;
   run5: PL + serving RSRP stays near-constant (see run5 analysis). Single source -> reported, flagged in the md. */
struct ul_acc { int used; uint32_t n[6], capped[6]; double sum[6], mn[6], mx[6], pmax_sum[6], pl_sum[6]; };
static struct ul_acc g_ul[8];
static int64_t g_ul_t; static uint32_t g_ul_ver;
static int g_ul_pci = -1;
static long g_ul_arfcn = -1;
static int s12(uint32_t v) { return v >= 2048 ? (int)v - 4096 : (int)v; }
static void ul_flush(int64_t now) {
    struct jb j; jb_init(&j, "nr_ul_pwr");
    jb_add(&j, ",\"src\":\"B884\",\"ver\":\"%u.%u\",\"tentative\":%d,\"dt_ms\":%lld", g_ul_ver >> 16, g_ul_ver & 0xffff,
           (g_ul_ver == 0x30003 || g_ul_ver == 0x30005) ? 0 : 1, (long long)(now - g_ul_t));
    if (g_ul_pci >= 0 && g_ul_arfcn > 0) {
        jb_i(&j, "pci", g_ul_pci);
        jb_i(&j, "arfcn", g_ul_arfcn);
    }
    jb_add(&j, ",\"cc\":[");
    int first = 1;
    for (int c = 0; c < 8; c++) {
        struct ul_acc *a = &g_ul[c];
        if (!a->used) continue;
        jb_add(&j, "%s{\"cc\":%d", first ? "" : ",", c);
        static const char *nm[6] = { "t0", "t1", "t2", "t3", "pusch", "pucch" };
        for (int t = 1; t < 6; t++) if (a->n[t]) {
            jb_add(&j, ",\"%s_n\":%u", nm[t], a->n[t]);
            char k[24]; snprintf(k, sizeof(k), "%s_dbm", nm[t]); jb_f(&j, k, a->sum[t] / a->n[t]);
            snprintf(k, sizeof(k), "%s_min", nm[t]); jb_f(&j, k, a->mn[t]);
            snprintf(k, sizeof(k), "%s_max", nm[t]); jb_f(&j, k, a->mx[t]);
            snprintf(k, sizeof(k), "%s_pcmax", nm[t]); jb_f(&j, k, a->pmax_sum[t] / a->n[t]);
            snprintf(k, sizeof(k), "%s_capped_pct", nm[t]); jb_f(&j, k, 100.0 * a->capped[t] / a->n[t]);
            if (t == 4) jb_f(&j, "pathloss_db", a->pl_sum[t] / a->n[t]);
            if (t == 5) jb_f(&j, "pucch_pathloss_db", a->pl_sum[t] / a->n[t]);
        }
        jb_add(&j, "}");
        first = 0;
    }
    jb_add(&j, "]");
    if (!first) jb_emit(&j);
    memset(g_ul, 0, sizeof(g_ul)); g_ul_t = now;
}
static void parse_nr_b884(const uint8_t *b, int n) {
    if (n < 8) return;
    uint32_t ver = (uint32_t)(rd16(b + 2) << 16 | rd16(b));
    layout_seen(0xB884, ver, b, n);
    if (ver < 0x30003 || ver > 0x30005) { unknown_layout(0xB884, ver, b, n, "version"); return; }
    /* walk first */
    int p = 8;
    for (int r = 0; r < b[7]; r++) {
        if (p + 12 > n) return;
        int nc = b[p + 4]; p += 12;
        for (int c = 0; c < nc; c++) {
            if (p + 4 > n) return;
            int nch = b[p]; p += 4;
            for (int k = 0; k < nch; k++, p += 16) {
                if (p + 16 > n) return;
                int t = b[p] >> 4;
                if (t < 1 || t > 5) { unknown_layout(0xB884, ver, b, n, "chan_type"); return; }
            }
        }
    }
    if (p != n) { unknown_layout(0xB884, ver, b, n, "length"); return; }
    int64_t now = pkt_now_ms();
    if (!g_ul_t || now < g_ul_t || now - g_ul_t > 6000 ||
        g_ul_ver != ver || g_ul_pci != g_nr.pci || g_ul_arfcn != g_nr.arfcn) {
        memset(g_ul, 0, sizeof(g_ul));
        g_ul_t = now;
        g_ul_pci = g_nr.pci; g_ul_arfcn = g_nr.arfcn;
    } else if (now - g_ul_t >= 1000) {
        ul_flush(now);
    }
    g_ul_ver = ver;
    p = 8;
    for (int r = 0; r < b[7]; r++) {
        int nc = b[p + 4]; p += 12;
        for (int c = 0; c < nc; c++) {
            int nch = b[p]; p += 4;
            for (int k = 0; k < nch; k++, p += 16) {
                const uint8_t *q = b + p;
                int cc = q[0] & 15, t = q[0] >> 4;
                if (cc > 7) continue;
                int pw = s12(lebits(q, 8, 12)), mx = s12(lebits(q, 44, 12));
                double v = (pw < mx ? pw : mx) / 10.0;
                struct ul_acc *a = &g_ul[cc];
                if (!a->n[t]) { a->mn[t] = v; a->mx[t] = v; }
                a->used = 1; a->n[t]++; a->sum[t] += v; a->pmax_sum[t] += mx / 10.0; if (pw > mx) a->capped[t]++;
                a->pl_sum[t] += s12(lebits(q, 20, 12)) / 10.0;   /* 1.8: DL path loss, s12 @20 in 0.1 dB */
                if (v < a->mn[t]) a->mn[t] = v;
                if (v > a->mx[t]) a->mx[t] = v;
            }
        }
    }
}

/* --- 0xB883 NR5G MAC UL Physical Channel Schedule Report (1.9) ---
   Layout facts: qcom-band-menu qbm_layouts.h (2.11, 3.15-3.27; own code here), MobileInsight (2.8/2.11 field list),
   Payload: records -> carriers -> carrier header + channel blocks selected by a mask
   (1 PUSCH fixed, 2 PUCCH count-prefixed, 4 SRS count-prefixed, 8 PRACH fixed, 16 = unknown -> reject).
   The whole packet is walked first and only used when the walk ends exactly at the packet end.
   UL BLER = HARQ-retransmitted PUSCH bytes / all PUSCH bytes (QBM: matches NSG's MAC UL BLER within 0.3 points). */
struct bp { uint16_t off; uint8_t bits; };
struct b883_l { uint32_t ver; struct bp rc; uint16_t rs; struct bp cc; uint16_t ca, hb; struct bp car, mask;
                uint16_t pusch, pucch_b, pucch_i, srs_b, srs_i, prach;
                struct bp tb, mcs, tbl, txm, rbs, mod, p0, p1; uint8_t m28; struct bp txt; uint8_t retx, guess; };
#define B883_3X(v, srsi, tbl, mask, txtw, g) { v, { 56, 8 }, 64, { 26, 6 }, 32, 160, { 0, 4 }, { mask, 5 }, 288, 32, 128, 32, srsi, 64, \
    { 64, 18 }, { 16, 5 }, { tbl, 3 }, { 21, 2 }, { 32, 9 }, { 189, 3 }, { 101, 4 }, { 105, 4 }, 0x09, { 4, txtw }, 3, g }
static const struct b883_l B883_L[] = {
    { 0x2000b, { 120, 8 }, 128, { 32, 8 }, 64, 32, { 0, 3 }, { 7, 5 }, 320, 32, 128, 32, 128, 64,
      { 43, 18 }, { 13, 5 }, { 18, 3 }, { 64, 2 }, { 32, 9 }, { 237, 3 }, { 220, 4 }, { 224, 4 }, 0x11, { 72, 2 }, 1, 0 },
    B883_3X(0x3000f, 128, 61, 8, 5, 0),     /* 3.15 */
    B883_3X(0x30010, 128, 61, 8, 5, 1),     /* 3.16: tentative, using the identical 3.15 and 3.17 layouts */
    B883_3X(0x30011, 128, 61, 8, 5, 0),     /* 3.17 */
    B883_3X(0x30012, 128, 61, 8, 5, 1),     /* 3.18 guess */
    B883_3X(0x30013, 128, 61, 8, 6, 1),     /* 3.19 guess */
    B883_3X(0x30014, 128, 61, 8, 6, 0),     /* 3.20 */
    B883_3X(0x30015, 128, 61, 8, 6, 0),     /* 3.21 */
    B883_3X(0x30016, 256, 61, 8, 6, 0),     /* 3.22 */
    B883_3X(0x30017, 256, 61, 8, 6, 0),     /* 3.23 */
    B883_3X(0x30018, 256, 58, 8, 6, 0),     /* 3.24 */
    B883_3X(0x30019, 256, 61, 8, 6, 0),     /* 3.25 */
    B883_3X(0x3001a, 256, 58, 11, 6, 0),    /* 3.26 */
    B883_3X(0x3001b, 256, 58, 11, 6, 0),    /* 3.27 */
};
static inline uint32_t bpv(const uint8_t *p, struct bp f) { return f.bits ? lebits(p, f.off, f.bits) : 0; }
struct us_acc {
    int used, txm;
    uint32_t tb, mcs_n, pucch, srs, prach, mod[6], rank_hist[3], unknown_tx;
    uint64_t bytes, retx, new_bytes, rb_sum, lay_sum, mcs_sum;
};
static struct us_acc g_us[8];
static int64_t g_us_t; static uint32_t g_us_ver; static int g_us_bad;
static int g_us_pci = -1;
static long g_us_arfcn = -1;
static void us_flush(int64_t now) {
    int64_t dt = now - g_us_t;
    const struct b883_l *L = NULL;
    for (size_t i = 0; i < sizeof(B883_L) / sizeof(B883_L[0]); i++) if (B883_L[i].ver == g_us_ver) L = &B883_L[i];
    static const char *modn[6] = { "BPSK", "QPSK", "16QAM", "64QAM", "256QAM", "1024QAM" };
    static const char *txmn[3] = { "SISO", "SMSL", "SMDL" };
    struct jb j; jb_init(&j, "nr_ul_sched");
    jb_add(&j, ",\"src\":\"B883\",\"ver\":\"%u.%u\",\"tentative\":%d,\"dt_ms\":%lld,\"bad_pkts\":%d",
           g_us_ver >> 16, g_us_ver & 0xffff, g_us_ver != 0x2000b && g_us_ver != 0x3001a,
           (long long)dt, g_us_bad);
    if (L && L->guess) jb_add(&j, ",\"guess\":1");
    if (g_us_pci >= 0 && g_us_arfcn > 0) {
        jb_i(&j, "pci", g_us_pci);
        jb_i(&j, "arfcn", g_us_arfcn);
    }
    uint64_t tot = 0; for (int c = 0; c < 8; c++) tot += g_us[c].bytes;
    jb_f(&j, "ul_mbps", dt > 0 ? tot * 8.0 / dt / 1000.0 : NA_F);
    unsigned blocks = 0, unknown_tx = 0;
    uint64_t new_bytes = 0;
    for (int c = 0; c < 8; c++) {
        blocks += g_us[c].tb;
        unknown_tx += g_us[c].unknown_tx;
        new_bytes += g_us[c].new_bytes;
    }
    /* First transmissions of scheduled MAC TBs include MAC overhead and padding.
       Keep this separate from the PHY rate, which includes HARQ retransmissions. */
    if (L && !L->guess && L->ver >= 0x30000 && blocks && !unknown_tx && dt > 0)
        jb_f(&j, "mac_new_tb_mbps", new_bytes * 0.008 / dt);
    jb_add(&j, ",\"cc\":[");
    int first = 1;
    for (int c = 0; c < 8; c++) {
        struct us_acc *a = &g_us[c];
        if (!a->used) continue;
        jb_add(&j, "%s{\"cc\":%d,\"tb\":%u,\"bytes\":%llu,\"retx_bytes\":%llu", first ? "" : ",", c, a->tb,
               (unsigned long long)a->bytes, (unsigned long long)a->retx);
        jb_f(&j, "ul_mbps", dt > 0 ? a->bytes * 8.0 / dt / 1000.0 : NA_F);
        if (a->bytes) jb_f(&j, "bler_pct", 100.0 * a->retx / a->bytes);
        if (a->mcs_n) jb_f(&j, "mcs_avg", (double)a->mcs_sum / a->mcs_n);
        if (a->tb) {
            jb_f(&j, "rb_avg", (double)a->rb_sum / a->tb);
            uint32_t ranked = a->rank_hist[1] + a->rank_hist[2];
            if (ranked) {
                jb_f(&j, "layers_avg", (double)a->lay_sum / ranked);
                if (L && !L->guess) jb_i(&j, "rank", a->rank_hist[2] > a->rank_hist[1] ? 2 : 1);
            }
            int m = 0; for (int k = 1; k < 6; k++) if (a->mod[k] > a->mod[m]) m = k;
            jb_add(&j, ",\"mod\":\"%s\",\"mod_hist\":[%u,%u,%u,%u,%u,%u]", modn[m], a->mod[0], a->mod[1], a->mod[2], a->mod[3], a->mod[4], a->mod[5]);
        }
        if (a->txm >= 1 && a->txm <= 3) jb_add(&j, ",\"tx_mode\":\"%s\"", txmn[a->txm - 1]);
        jb_add(&j, ",\"pucch_n\":%u,\"srs_n\":%u,\"prach_n\":%u}", a->pucch, a->srs, a->prach);
        first = 0;
    }
    jb_add(&j, "]");
    if (!first) jb_emit(&j);
    memset(g_us, 0, sizeof(g_us)); g_us_bad = 0; g_us_t = now;
}
/* length of a count-prefixed block (count = first byte), -1 if it does not fit */
static int b883_counted(const uint8_t *p, int avail, int base_bits, int item_bits) {
    int base = base_bits / 8, item = item_bits / 8;
    if (base > avail || avail <= 0 || !item) return -1;
    int k = p[0];
    if (k > (avail - base) / item) return -1;
    return base + k * item;
}
/* walk = 0: validate only; walk = 1: accumulate */
static int b883_walk(const struct b883_l *L, const uint8_t *b, int n, int acc) {
    if (L->rc.off + L->rc.bits > n * 8) return -1;
    int recs = (int)bpv(b, L->rc), p = L->rs / 8;
    for (int r = 0; r < recs; r++) {
        int rec = p;
        if (rec > n || (L->cc.off + L->cc.bits) > (n - rec) * 8) return -1;
        int nc = (int)bpv(b + rec, L->cc);
        p = rec + L->ca / 8;
        for (int c = 0; c < nc; c++) {
            int hb = L->hb / 8;
            if (hb > n - p) return -1;
            int car = (int)bpv(b + p, L->car), mask = (int)bpv(b + p, L->mask);
            p += hb;
            if (mask & 16) return -1;
            if (mask & 1) {
                int sz = L->pusch / 8;
                if (sz > n - p) return -1;
                if (acc && car < 8 && bpv(b + p, L->tb)) {
                    const uint8_t *q = b + p;
                    struct us_acc *a = &g_us[car];
                    uint32_t tbs = bpv(q, L->tb);
                    int mcs = (int)bpv(q, L->mcs), tbl = (int)bpv(q, L->tbl), mod = (int)bpv(q, L->mod), txm = (int)bpv(q, L->txm);
                    a->used = 1; a->tb++; a->bytes += tbs;
                    int tx_type = (int)bpv(q, L->txt);
                    if (tx_type == L->retx) a->retx += tbs;
                    else if (tx_type == 0) a->new_bytes += tbs;
                    else a->unknown_tx++;
                    a->rb_sum += bpv(q, L->rbs);
                    int rank = (bpv(q, L->p0) != 15) + (bpv(q, L->p1) != 15);
                    a->rank_hist[rank]++;
                    a->lay_sum += rank;
                    if (mcs <= (((L->m28 >> tbl) & 1) ? 28 : 27)) { a->mcs_sum += mcs; a->mcs_n++; }
                    if (mod < 6) a->mod[mod]++;
                    if (txm < 3) a->txm = txm + 1;
                }
                p += sz;
            }
            if (mask & 2) { int sz = b883_counted(b + p, n - p, L->pucch_b, L->pucch_i); if (sz < 0) return -1; if (acc && car < 8) { g_us[car].used = 1; g_us[car].pucch++; } p += sz; }
            if (mask & 4) { int sz = b883_counted(b + p, n - p, L->srs_b, L->srs_i); if (sz < 0) return -1; if (acc && car < 8) { g_us[car].used = 1; g_us[car].srs++; } p += sz; }
            if (mask & 8) { int sz = L->prach / 8; if (sz > n - p) return -1; if (acc && car < 8) { g_us[car].used = 1; g_us[car].prach++; } p += sz; }
        }
    }
    return p;
}
static void parse_nr_b883(const uint8_t *b, int n) {
    if (n < 8) return;
    uint32_t ver = (uint32_t)(rd16(b + 2) << 16 | rd16(b));
    layout_seen(0xB883, ver, b, n);
    const struct b883_l *L = NULL;
    for (size_t i = 0; i < sizeof(B883_L) / sizeof(B883_L[0]); i++) if (B883_L[i].ver == ver) L = &B883_L[i];
    if (!L) { unknown_layout(0xB883, ver, b, n, "version"); return; }
    if (b883_walk(L, b, n, 0) != n) { g_us_bad++; unknown_layout(0xB883, ver, b, n, "length"); return; }
    int64_t now = pkt_now_ms();
    if (!g_us_t || now < g_us_t || now - g_us_t > 6000 || g_us_ver != ver ||
        g_us_pci != g_nr.pci || g_us_arfcn != g_nr.arfcn) {
        memset(g_us, 0, sizeof(g_us));
        g_us_t = now;
        g_us_pci = g_nr.pci;
        g_us_arfcn = g_nr.arfcn;
    } else if (now - g_us_t >= 1000) us_flush(now);
    g_us_ver = ver;
    b883_walk(L, b, n, 1);
}

/* ------------------------------------------------------------------ */
/* IMS (1.9): SIP messages 0x156E, session setup 0x1830, registration 0x1832; RTP/RTCP/voice stats raw only.
   0x156E v1 header (SCAT diag1xlogparser, 16 B):
     u8 ver, u8 dir (0 DL / 1 UL), u8 has_sdp, u8 call_id_len, u16 len, u16 sip_len, u16 msg_type, u16 status, u32 ?
     then call_id[call_id_len] (NUL included), then the SIP text [sip_len] (NUL included).
   Privacy: JSON never carries URIs, numbers, IMSI/IMPU/IMPI or IP addresses; Call-ID only as a 32-bit hash.
   The raw qmdl keeps the full messages (needed for offline decoding). */
static uint32_t fnv1a(const char *p, int n) { uint32_t h = 2166136261u; for (int i = 0; i < n; i++) { h ^= (uint8_t)p[i]; h *= 16777619u; } return h; }
static int is_hexc(int c) { return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'); }
/* copy a short free-text field with identifiers removed and JSON-unsafe chars replaced */
static void redact(const char *in, int n, char *out, int cap) {
    int o = 0;
    for (int i = 0; i < n && o < cap - 8; ) {
        const char *s = in + i; int rem = n - i;
        if ((rem > 4 && !strncasecmp(s, "sip:", 4)) || (rem > 5 && !strncasecmp(s, "sips:", 5)) ||
            (rem > 4 && !strncasecmp(s, "tel:", 4)) || (rem > 4 && !strncasecmp(s, "urn:", 4))) {
            o += snprintf(out + o, cap - o, "<uri>");
            while (i < n && !strchr(" >;,\"\r\n", in[i])) i++;
            continue;
        }
        if (is_hexc((uint8_t)s[0]) || s[0] == '+' || s[0] == '[') {
            int k = 0, dig = 0, col = 0, dot = 0, alpha = 0;
            while (k < rem && (is_hexc((uint8_t)s[k]) || strchr(":.+-[]", s[k]))) {
                if (s[k] >= '0' && s[k] <= '9') dig++; else if (s[k] == ':') col++; else if (s[k] == '.') dot++; else if (is_hexc((uint8_t)s[k])) alpha++;
                k++;
            }
            int word_before = i > 0 && (isalpha((uint8_t)in[i - 1]));
            int word_after = k < rem && isalpha((uint8_t)s[k]);
            if (col >= 2 && dig + alpha >= 4) { o += snprintf(out + o, cap - o, "<ip>"); i += k; continue; }
            if (dot == 3 && dig >= 4 && !alpha) { o += snprintf(out + o, cap - o, "<ip>"); i += k; continue; }
            if (dig >= 5 && !word_before) { o += snprintf(out + o, cap - o, "<num>"); i += k; continue; }
            if (alpha && (word_before || word_after)) { /* ordinary word: copy the run */ }
            for (int t = 0; t < k && o < cap - 8; t++) out[o++] = s[t];
            i += k;
            continue;
        }
        char c = s[0];
        if (c == '@') { /* user@host: drop the host too (may be an IP or a personal domain) */
            o += snprintf(out + o, cap - o, "@<host>");
            i++; while (i < n && !strchr(" >;,\"\r\n", in[i])) i++;
            continue;
        }
        out[o++] = (c == '"' || c == '\\' || (uint8_t)c < 0x20 || (uint8_t)c >= 0x7f) ? ' ' : c;
        i++;
    }
    out[o] = 0;
}
/* header value (first occurrence, case-insensitive name incl. compact form); returns length, -1 if absent */
static int sip_hdr(const char *t, int n, const char *name, const char *compact, const char **val) {
    int ln = (int)strlen(name), lc = compact ? (int)strlen(compact) : 0;
    for (int i = 0; i < n; ) {
        int e = i; while (e < n && t[e] != '\n') e++;
        int le = e; if (le > i && t[le - 1] == '\r') le--;
        if (le == i) break;   /* end of headers */
        const char *l = t + i; int L = le - i, nl = -1;
        if (L > ln && !strncasecmp(l, name, ln) && (l[ln] == ':' || l[ln] == ' ' || l[ln] == '\t')) nl = ln;
        else if (lc && L > lc && !strncasecmp(l, compact, lc) && (l[lc] == ':' || l[lc] == ' ')) nl = lc;
        if (nl >= 0) {
            int k = nl; while (k < L && (l[k] == ' ' || l[k] == '\t')) k++;
            if (k < L && l[k] == ':') k++;
            while (k < L && (l[k] == ' ' || l[k] == '\t')) k++;
            *val = l + k; return L - k;
        }
        i = e + 1;
    }
    return -1;
}
/* SDP: first audio m-line payload type -> rtpmap name/rate; video present */
static void sdp_codec(const char *t, int n, char *codec, int cap, int *video) {
    codec[0] = 0; *video = 0;
    const char *body = NULL;
    for (int i = 0; i + 3 < n; i++) if (t[i] == '\n' && (t[i + 1] == '\n' || (t[i + 1] == '\r' && t[i + 2] == '\n'))) { body = t + i + 1; break; }
    if (!body) return;
    int bn = (int)(t + n - body), pt = -1;
    for (int i = 0; i < bn; ) {
        int e = i; while (e < bn && body[e] != '\n') e++;
        const char *l = body + i; int L = e - i; if (L && l[L - 1] == '\r') L--;
        if (L > 8 && !strncmp(l, "m=video ", 8)) *video = 1;
        if (pt < 0 && L > 8 && !strncmp(l, "m=audio ", 8)) {
            int sp = 0, k = 8; for (; k < L && sp < 2; k++) if (l[k] == ' ') sp++;
            if (sp == 2) pt = atoi(l + k);
        }
        if (pt >= 0 && !codec[0] && L > 9 && !strncmp(l, "a=rtpmap:", 9) && atoi(l + 9) == pt) {
            const char *s = memchr(l, ' ', L);
            if (s) {   /* codec name/rate/channels: printable token chars only (no identifiers in an rtpmap) */
                int m = 0; for (const char *q = s + 1; q < l + L && m < cap - 1; q++) { char ch = *q; if (isalnum((uint8_t)ch) || ch == '/' || ch == '-' || ch == '.' || ch == '_') codec[m++] = ch; else break; }
                codec[m] = 0;
            }
        }
        i = e + 1;
    }
}
/* EPS fallback / RAT context from the RRC parsers */
static int64_t g_t_nr_rrc, g_t_lte_rrc, g_t_nr_leave; static char g_nr_leave_msg[32];
struct ims_call { int used, mo, state, efb_watch, efb, sa, video; uint32_t h; int64_t t0, t_alert, t_conn, t_last; char codec[32], pani[32]; };
static struct ims_call g_calls[4];
static char g_ims_reg[16] = "unknown"; static int g_reg_inferred;   /* 2.3 */
static int g_ims_reg_expires = -1;
/* 2.0: QoS-based voice bearer state (NAS section below) */
static int64_t g_voice_t0; static int g_voice_on, g_voice_5qi; static char g_voice_rat[8]; static int g_ims_pdn;
/* 2.1: unified call record (Samsung AP logcat SIP + NAS voice bearer + RRC cause) */
struct ucall {
    int active, ended, dir, have_sip, bye_tx, final_tx, final_code, cancel, sip_cause, err, crbt_video, video_lost, b_qi, n, ims_reason;
    int64_t t_start, t_inv, t_100, t_183, t_180, t_200, t_ack, t_bye, t_end, b_up, b_down, v_up, v_down, t_nr_leave;
    char dir_src[8], b_rat[12], codec[16], reason[32], rrc_cause[24];
};
static struct ucall g_uc; static int g_uc_n;
static int g_efb_return_pending; static int64_t g_t_lte_rel_ev, g_t_call_end_ev, g_t_ims_pdu_ev;   /* 2.2 */
static int64_t ev_ms(void) { return pkt_now_ms(); }
static int on_nr_sa(void);
static void ims_state_emit(const char *why) {
    struct ims_call *c = NULL;
    for (int i = 0; i < 4; i++) if (g_calls[i].used && g_calls[i].state < 4 && (!c || g_calls[i].t_last > c->t_last)) c = &g_calls[i];
    static const char *stn[5] = { "idle", "calling", "alerting", "connected", "ended" };
    struct jb j; jb_init(&j, "ims_state");
    jb_add(&j, ",\"why\":\"%s\",\"reg\":\"%s\",\"ims_pdn\":%d", why, g_ims_reg, g_ims_pdn);
    if (g_reg_inferred && !strcmp(g_ims_reg, "registered")) jb_add(&j, ",\"reg_inferred\":1");
    if (g_voice_on) jb_add(&j, ",\"voice_bearer\":\"%s\",\"voice_qi\":%d", g_voice_rat, g_voice_5qi);
    if (g_ims_reg_expires >= 0) jb_add(&j, ",\"reg_expires\":%d", g_ims_reg_expires);
    if (!c && g_uc.active && g_uc.have_sip && !g_uc.ended) {
        const char *st = g_uc.t_200 ? "connected" : g_uc.t_180 ? "alerting" : "calling";
        jb_add(&j, ",\"call\":\"%s\",\"call_src\":\"logcat\",\"dir\":\"%s\",\"rat\":\"%s\"", st,
               g_uc.dir == 1 ? "MO" : g_uc.dir == 2 ? "MT" : "?", g_uc.b_rat[0] ? g_uc.b_rat : on_nr_sa() ? "NR" : "LTE");
        if (g_uc.codec[0]) jb_add(&j, ",\"codec\":\"%s\"", g_uc.codec);
        if (g_voice_5qi == 2 || g_uc.v_up > g_uc.v_down) jb_add(&j, ",\"video_flow\":1");
        if (g_uc.crbt_video) jb_add(&j, ",\"crbt_video\":1");
    } else if (c) {
        jb_add(&j, ",\"call\":\"%s\",\"dir\":\"%s\",\"call_id\":\"%08x\"", stn[c->state], c->mo ? "MO" : "MT", c->h);
        if (c->codec[0]) jb_add(&j, ",\"codec\":\"%s\"", c->codec);
        if (c->pani[0]) jb_add(&j, ",\"access\":\"%s\"", c->pani);
        jb_add(&j, ",\"rat\":\"%s\"", c->efb ? "LTE(EPSFB)" : c->sa ? "NR" : "LTE");
        if (c->video) jb_add(&j, ",\"video\":1");
    } else if (g_uc.active && g_uc.ended) jb_add(&j, ",\"call\":\"idle\",\"ending\":1");
    else if (g_voice_on) jb_add(&j, ",\"call\":\"connected\",\"call_src\":\"qos\",\"rat\":\"%s\"%s", g_voice_rat, g_voice_5qi == 2 ? ",\"video\":1" : "");
    else jb_add(&j, ",\"call\":\"idle\"");
    jb_emit(&j);
}
static void ims_event(struct ims_call *c, const char *ev, const char *extra) {
    struct jb j; jb_init(&j, "event");
    jb_add(&j, ",\"rat\":\"IMS\",\"event\":\"%s\",\"msg\":\"%s\"", ev, extra ? extra : "");
    if (c) {
        jb_add(&j, ",\"call_id\":\"%08x\",\"dir\":\"%s\"", c->h, c->mo ? "MO" : "MT");
        if (c->codec[0]) jb_add(&j, ",\"codec\":\"%s\"", c->codec);
    }
    jb_add(&j, ",\"tentative\":1");
    jb_emit(&j);
}
static struct ims_call *call_find(uint32_t h, int create) {
    struct ims_call *free_c = NULL, *old = NULL;
    for (int i = 0; i < 4; i++) {
        if (g_calls[i].used && g_calls[i].h == h) return &g_calls[i];
        if (!g_calls[i].used && !free_c) free_c = &g_calls[i];
        if (!old || g_calls[i].t_last < old->t_last) old = &g_calls[i];
    }
    if (!create) return NULL;
    struct ims_call *c = free_c ? free_c : old;
    memset(c, 0, sizeof(*c)); c->used = 1; c->h = h;
    return c;
}
static int on_nr_sa(void) { return g_t_nr_rrc && g_t_nr_rrc >= g_t_lte_rrc; }
/* called from the RRC parsers (msg may be "") */
static void ims_rrc_hook(int nr, const char *msg) {
    int64_t now = mono_ms();
    if (nr && !strcmp(msg, "mobilityFromNRCommand") && g_uc.active && !g_uc.ended && !g_uc.t_nr_leave) g_uc.t_nr_leave = ev_ms();
    if (!nr && (!strcmp(msg, "rrcConnectionRelease") || !strcmp(msg, "mobilityFromEUTRACommand"))) g_t_lte_rel_ev = ev_ms();
    if (nr && g_efb_return_pending && !(g_uc.active && !g_uc.ended)) {   /* 2.2: back on NR after an EPS fallback call */
        int64_t t = ev_ms();
        g_efb_return_pending = 0;
        struct jb j; jb_init(&j, "event");
        jb_add(&j, ",\"rat\":\"IMS\",\"event\":\"eps_fb_return\",\"msg\":\"%s\"", msg);
        if (g_t_lte_rel_ev && t - g_t_lte_rel_ev < 15000 && g_t_lte_rel_ev >= g_t_call_end_ev - 60000) jb_add(&j, ",\"lte_release_to_nr_ms\":%lld", (long long)(t - g_t_lte_rel_ev));
        if (g_t_call_end_ev) jb_add(&j, ",\"call_end_to_nr_ms\":%lld", (long long)(t - g_t_call_end_ev));
        jb_emit(&j);
    }
    if (nr) {
        g_t_nr_rrc = now;
        if (!strcmp(msg, "mobilityFromNRCommand") || !strcmp(msg, "rrcRelease")) { g_t_nr_leave = now; snprintf(g_nr_leave_msg, sizeof(g_nr_leave_msg), "%s", msg); }
        return;
    }
    g_t_lte_rrc = now;
    for (int i = 0; i < 4; i++) {
        struct ims_call *c = &g_calls[i];
        if (!c->used || !c->efb_watch) continue;
        c->efb_watch = 0;
        if (now - c->t0 > 20000 || c->state >= 4) continue;
        c->efb = 1;
        const char *trig = (g_t_nr_leave && g_t_nr_leave >= c->t0 && now - g_t_nr_leave <= 5000) ? g_nr_leave_msg : "lte_rrc";
        struct jb j; jb_init(&j, "event");
        jb_add(&j, ",\"rat\":\"IMS\",\"event\":\"eps_fallback\",\"msg\":\"%s\",\"call_id\":\"%08x\",\"dir\":\"%s\",\"delay_ms\":%lld,\"call_state\":%d,\"tentative\":1",
               trig, c->h, c->mo ? "MO" : "MT", (long long)(now - c->t0), c->state);
        jb_emit(&j);
        ims_state_emit("eps_fallback");
    }
}
static void uc_sip(int64_t t, int tx, const char *method, int code, const char *cseq_m, long cseq);
static void parse_ims_sip(const uint8_t *b, int n) {
    if (n < 16) return;
    layout_seen(0x156E, b[0], b, n < 16 ? n : 16);   /* header only: never dump SIP text into JSON */
    if (b[0] != 1) { unknown_layout(0x156E, b[0], b, 4, "version"); return; }
    int dir = b[1], has_sdp = b[2], cil = b[3], sl = rd16(b + 6), mtype = rd16(b + 8), status = rd16(b + 10);
    if (16 + cil + sl > n || dir > 1) { unknown_layout(0x156E, b[0], b, 4, "length"); return; }
    const char *cid = (const char *)b + 16; int cidn = cil; while (cidn > 0 && !cid[cidn - 1]) cidn--;
    if (sl > 16384) return;
    char text[16385];
    memcpy(text, b + 16 + cil, (size_t)sl);
    text[sl] = 0;
    const char *t = text;
    int tn = sl;
    while (tn > 0 && !t[tn - 1]) tn--;
    uint32_t h = fnv1a(cid, cidn);
    int e = 0; while (e < tn && t[e] != '\r' && t[e] != '\n') e++;
    char method[24] = "", reason[64] = "", cseqm[24] = "", rsn[96] = "", codec[32] = "", pani[32] = "";
    int code = 0, video = 0, expires = -1;
    if (e > 8 && !strncmp(t, "SIP/2.0 ", 8)) {
        code = atoi(t + 8);
        int k = 8; while (k < e && t[k] != ' ') k++;
        if (k < e) redact(t + k + 1, e - k - 1 > 40 ? 40 : e - k - 1, reason, sizeof(reason));
    } else {
        int k = 0; while (k < e && k < 20 && t[k] >= 'A' && t[k] <= 'Z') { method[k] = t[k]; k++; }
        method[k] = 0;
    }
    const char *v; int vl;
    long cseqn = -1;
    if ((vl = sip_hdr(t, tn, "CSeq", NULL, &v)) > 0) {
        cseqn = strtol(v, NULL, 10);
        int k = 0; while (k < vl && v[k] != ' ') k++;
        while (k < vl && v[k] == ' ') k++;
        int m = 0; while (k < vl && m < 20 && v[k] >= 'A' && v[k] <= 'Z') cseqm[m++] = v[k++];
        cseqm[m] = 0;
    }
    if ((vl = sip_hdr(t, tn, "Reason", NULL, &v)) > 0) redact(v, vl > 80 ? 80 : vl, rsn, sizeof(rsn));
    if ((vl = sip_hdr(t, tn, "P-Access-Network-Info", NULL, &v)) > 0) {
        int m = 0; while (m < vl && m < 30 && v[m] != ';' && v[m] != ' ' && v[m] != ',') m++;
        redact(v, m, pani, sizeof(pani));
    }
    if ((vl = sip_hdr(t, tn, "Expires", NULL, &v)) > 0) expires = atoi(v);
    else if ((vl = sip_hdr(t, tn, "Contact", "m", &v)) > 0) {
        for (int k = 0; k + 8 < vl; k++) if (!strncasecmp(v + k, "expires=", 8)) { expires = atoi(v + k + 8); break; }
    }
    if (has_sdp || strstr(t, "application/sdp")) sdp_codec(t, tn, codec, sizeof(codec), &video);
    struct jb j; jb_init(&j, "sip");
    jb_add(&j, ",\"src\":\"0x156E\",\"dir\":\"%s\",\"call_id\":\"%08x\",\"len\":%d,\"msg_type\":%d", dir ? "UL" : "DL", h, tn, mtype);
    if (code) jb_add(&j, ",\"status\":%d,\"reason\":\"%s\"", code, reason); else jb_add(&j, ",\"method\":\"%s\"", method);
    if (status && !code) jb_add(&j, ",\"hdr_status\":%d", status);
    if (cseqm[0]) jb_add(&j, ",\"cseq\":\"%s\"", cseqm);
    if (has_sdp) jb_add(&j, ",\"sdp\":1");
    if (codec[0]) jb_add(&j, ",\"codec\":\"%s\"", codec);
    if (video) jb_add(&j, ",\"video\":1");
    if (pani[0]) jb_add(&j, ",\"access\":\"%s\"", pani);
    if (expires >= 0) jb_add(&j, ",\"expires\":%d", expires);
    if (rsn[0]) jb_add(&j, ",\"reason_hdr\":\"%s\"", rsn);
    jb_emit(&j);
    uc_sip(ev_ms(), dir == 1, method, code, cseqm, cseqn);
    g_diag_ims_pkts++;

    int64_t now = mono_ms();
    /* registration */
    static int reg_req_expires = -1;
    if (!code && !strcmp(method, "REGISTER") && dir == 1) reg_req_expires = expires;
    if (code && !strcmp(cseqm, "REGISTER")) {
        char ex[48];
        if (code >= 200 && code < 300) {
            int exp = expires >= 0 ? expires : reg_req_expires;
            snprintf(g_ims_reg, sizeof(g_ims_reg), "%s", exp == 0 ? "deregistered" : "registered"); g_reg_inferred = 0;
            g_ims_reg_expires = exp;
            snprintf(ex, sizeof(ex), "%d expires=%d", code, exp);
            ims_event(NULL, exp == 0 ? "ims_deregister" : "ims_register", ex);
            ims_state_emit("register");
        } else if (code >= 300 && code != 401 && code != 407) {
            snprintf(g_ims_reg, sizeof(g_ims_reg), "failed"); g_reg_inferred = 0;
            snprintf(ex, sizeof(ex), "%d %s", code, reason);
            ims_event(NULL, "ims_register_fail", ex);
            ims_state_emit("register");
        }
    }
    /* calls */
    struct ims_call *c = NULL;
    if (!code && !strcmp(method, "INVITE")) {
        c = call_find(h, 0);
        if (!c || c->state >= 4) {
            c = call_find(h, 1);
            c->mo = dir == 1; c->state = 1; c->t0 = now; c->sa = on_nr_sa(); c->efb_watch = c->sa;
            if (pani[0]) snprintf(c->pani, sizeof(c->pani), "%s", pani);
            if (codec[0]) snprintf(c->codec, sizeof(c->codec), "%s", codec);
            c->video = video; c->t_last = now;
            char ex[64]; snprintf(ex, sizeof(ex), "%s%s", c->sa ? "on NR" : "on LTE", c->video ? " video" : "");
            ims_event(c, "call_start", ex);
            ims_state_emit("call_start");
            return;
        }
    }
    c = call_find(h, 0);
    if (!c || c->state >= 4) return;
    c->t_last = now;
    if (codec[0]) snprintf(c->codec, sizeof(c->codec), "%s", codec);
    if (pani[0] && !c->pani[0]) snprintf(c->pani, sizeof(c->pani), "%s", pani);
    char ex[160];
    if (code && !strcmp(cseqm, "INVITE")) {
        if (code >= 180 && code < 190 && c->state < 2) {
            c->state = 2; c->t_alert = now;
            snprintf(ex, sizeof(ex), "%d setup_ms=%lld", code, (long long)(now - c->t0));
            ims_event(c, "call_alerting", ex);
            ims_state_emit("alerting");
        } else if (code >= 200 && code < 300 && c->state < 3) {
            c->state = 3; c->t_conn = now; c->efb_watch = 0;
            snprintf(ex, sizeof(ex), "%d setup_ms=%lld rat=%s", code, (long long)(now - c->t0), c->efb ? "LTE(EPSFB)" : c->sa ? "NR" : "LTE");
            ims_event(c, "call_connected", ex);
            ims_state_emit("connected");
        } else if (code >= 300 && code != 401 && code != 407 && c->state < 3) {
            c->state = 4;
            snprintf(ex, sizeof(ex), "%d %s%s%s after_ms=%lld%s", code, reason, rsn[0] ? " | " : "", rsn, (long long)(now - c->t0), c->efb_watch ? " efb_pending" : "");
            ims_event(c, code == 487 ? "call_cancelled" : "call_fail", ex);
            ims_state_emit("fail");
        }
    } else if (!code && !strcmp(method, "CANCEL")) {
        c->state = 4;
        snprintf(ex, sizeof(ex), "by=%s after_ms=%lld", dir ? "local" : "remote", (long long)(now - c->t0));
        ims_event(c, "call_cancelled", ex);
        ims_state_emit("cancel");
    } else if (!code && !strcmp(method, "BYE")) {
        int conn = c->state == 3;
        c->state = 4;
        int abn = rsn[0] && (strcasestr(rsn, "RTP") || strcasestr(rsn, "timeout") || strcasestr(rsn, "cause=503") || strcasestr(rsn, "cause=408") ||
                             strcasestr(rsn, "cause=41") || strcasestr(rsn, "cause=38") || strcasestr(rsn, "cause=47"));
        snprintf(ex, sizeof(ex), "by=%s dur_s=%lld%s%s%s", dir ? "local" : "remote", conn ? (long long)((now - c->t_conn) / 1000) : 0LL,
                 rsn[0] ? " reason=" : "", rsn, abn ? " abnormal" : "");
        ims_event(c, abn ? "call_drop" : "call_end", ex);
        ims_state_emit("end");
    }
}
/* length-prefixed (u8) string inside an IMS log; returns new pos or -1 */
static int ims_lpstr(const uint8_t *b, int n, int p, const char **s, int *sl) {
    if (p >= n) return -1;
    int l = b[p]; if (p + 1 + l > n) return -1;
    *s = (const char *)b + p + 1; *sl = l; while (*sl > 0 && !(*s)[*sl - 1]) (*sl)--;
    return p + 1 + l;
}
/* 0x1830 IMS VoLTE Session Setup v2 (SCAT only): dialed(lp) dir(u8) call_id(lp) call_type(u32) orig(lp) term(lp) result(u16) delay(u32) u8.
   Only non-identifying fields are emitted. */
static void parse_ims_1830(const uint8_t *b, int n) {
    if (n < 2) return;
    layout_seen(0x1830, b[0], b, 2);
    if (b[0] != 2) { unknown_layout(0x1830, b[0], b, 2, "version"); return; }
    const char *s, *cid; int sl, cl, p = 1;
    if ((p = ims_lpstr(b, n, p, &s, &sl)) < 0 || p + 1 > n) goto bad;
    int dir = b[p++];
    if ((p = ims_lpstr(b, n, p, &cid, &cl)) < 0 || p + 4 > n) goto bad;
    uint32_t ctype = rd32(b + p); p += 4;
    if ((p = ims_lpstr(b, n, p, &s, &sl)) < 0) goto bad;
    if ((p = ims_lpstr(b, n, p, &s, &sl)) < 0 || p + 7 > n) goto bad;
    {
        struct jb j; jb_init(&j, "ims_session");
        jb_add(&j, ",\"src\":\"0x1830\",\"dir\":%d,\"call_id\":\"%08x\",\"call_type\":%u,\"result\":%u,\"setup_delay\":%u,\"tentative\":1",
               dir, fnv1a(cid, cl), ctype, rd16(b + p), rd32(b + p + 2));
        jb_emit(&j);
    }
    return;
bad:
    unknown_layout(0x1830, b[0], b, 2, "length");
}
/* 0x1832 IMS Registration v1 (SCAT only): reg_type(u8) call_id(lp) request_uri(lp) to(lp) result(u16) */
static void parse_ims_1832(const uint8_t *b, int n) {
    if (n < 3) return;
    layout_seen(0x1832, b[0], b, 2);
    if (b[0] != 1) { unknown_layout(0x1832, b[0], b, 2, "version"); return; }
    const char *s, *cid; int sl, cl, p = 2;
    if ((p = ims_lpstr(b, n, p, &cid, &cl)) < 0 || (p = ims_lpstr(b, n, p, &s, &sl)) < 0 ||
        (p = ims_lpstr(b, n, p, &s, &sl)) < 0 || p + 2 > n) { unknown_layout(0x1832, b[0], b, 2, "length"); return; }
    struct jb j; jb_init(&j, "ims_reg_log");
    jb_add(&j, ",\"src\":\"0x1832\",\"reg_type\":%d,\"call_id\":\"%08x\",\"result\":%u,\"tentative\":1", b[1], fnv1a(cid, cl), rd16(b + p));
    jb_emit(&j);
}
/* RTP / RTCP / voice-stats logs: layouts multi-version and not cross-checked -> raw only; per-call log counts */
static uint32_t g_ims_media[4]; static int64_t g_ims_media_t;
static void ims_media_count(int k, uint16_t code, const uint8_t *b, int n) {
    if (n >= 1) layout_seen(code, b[0], b, n < 8 ? n : 8);   /* 8-byte head only: payload may contain addresses */
    g_ims_media[k]++;
    int64_t now = mono_ms();
    if (!g_ims_media_t) g_ims_media_t = now;
    if (now - g_ims_media_t >= 5000) {
        struct jb j; jb_init(&j, "ims_media");
        jb_add(&j, ",\"dt_ms\":%lld,\"rtp_logs\":%u,\"rtp_loss_logs\":%u,\"rtcp_logs\":%u,\"voice_stats_logs\":%u,\"tentative\":1",
               (long long)(now - g_ims_media_t), g_ims_media[0], g_ims_media[1], g_ims_media[2], g_ims_media[3]);
        jb_emit(&j);
        memset(g_ims_media, 0, sizeof(g_ims_media)); g_ims_media_t = now;
    }
}

/* ------------------------------------------------------------------ */
/* 2.0: voice-bearer based call detection from plain NAS (works without any modem IMS/SIP log).
   Samsung's IMS stack runs on the AP, so 0x156E & co. never arrive on this phone (run6: 0 packets);
   the network still has to add a GBR QoS flow (5QI 1 voice / 2 video) to the IMS PDU session for every VoNR call
   (LTE: dedicated EPS bearer QCI 1 on the IMS APN), and that is visible in the plain NAS logs:
     NR 0xB80A/0xB80B: u32 ver, 3 B std ver, then the NAS PDU (SCAT); 5GSM inside UL/DL NAS TRANSPORT (24.501).
     LTE 0xB0E2/0xB0E3: u8 ver, 3 B std ver, then the ESM PDU (SCAT); 24.301.
   run6 (real VoNR call): PSI 2 DNN "IMS" accept, then PDU SESSION MODIFICATION COMMAND creating QFI 2 5QI 1
   (GFBR/MFBR 52 kbps), deleted 17.6 s later. Only DNN/APN, PSI/EBI, QFI, 5QI/QCI and bit rates are emitted. */
struct psi_st {
    int used, ims, pdu_type, ssc_mode, sst, sd_present;
    uint32_t sd;
    char dnn[24];
    int qfi5qi[64];
};
static struct psi_st g_psi[16];
struct ebi_st { int used, ims, qci, linked; };
static struct ebi_st g_ebi[16];
static void update_ims_bearer(const char *why) {
    int inferred = 0, established = 0;
    for (int i = 0; i < 16; i++) {
        if (g_psi[i].used) {
            established |= g_psi[i].ims == 1;
            inferred |= g_psi[i].ims == 2;
        }
        if (g_ebi[i].used && !g_ebi[i].linked) {
            established |= g_ebi[i].ims == 1;
            inferred |= g_ebi[i].ims == 2;
        }
    }
    int state = established ? 1 : inferred ? 2 : 0;
    if (state != g_ims_pdn) {
        g_ims_pdn = state;
        ims_state_emit(why);
    }
}
static void nas_dnn(const uint8_t *v, int l, char *out, int cap) {
    int o = 0;
    for (int p = 0; p < l && o < cap - 1; ) {
        int k = v[p++]; if (o && o < cap - 1) out[o++] = '.';
        for (int i = 0; i < k && p < l && o < cap - 1; i++, p++) { char c = (char)v[p]; out[o++] = (isalnum((uint8_t)c) || c == '-' || c == '_') ? c : '?'; }
    }
    out[o] = 0;
}
static int is_ims_name(const char *s) { return !strncasecmp(s, "ims", 3) && (s[3] == 0 || s[3] == '.'); }
static void uc_emit(const char *why) {
    struct ucall *u = &g_uc;
    if (!u->active) return;
    struct jb j; jb_init(&j, "ims_call");
    jb_add(&j, ",\"n\":%d,\"dir\":\"%s\",\"dir_src\":\"%s\",\"src\":\"%s\",\"t_start\":%lld", u->n,
           u->dir == 1 ? "MO" : u->dir == 2 ? "MT" : "unknown", u->dir_src[0] ? u->dir_src : "none",
           u->have_sip && u->b_up ? "sip+nas" : u->have_sip ? "sip" : "nas", (long long)u->t_start);
    if (u->b_rat[0]) jb_add(&j, ",\"rat\":\"%s\",\"qi\":%d", u->b_rat, u->b_qi);
    if (u->codec[0]) jb_add(&j, ",\"codec\":\"%s\"", u->codec);
    if (u->t_inv) {
        if (u->t_100) jb_add(&j, ",\"inv_100_ms\":%lld", (long long)(u->t_100 - u->t_inv));
        if (u->t_183) jb_add(&j, ",\"inv_183_ms\":%lld", (long long)(u->t_183 - u->t_inv));
        if (u->t_180) jb_add(&j, ",\"inv_180_ms\":%lld", (long long)(u->t_180 - u->t_inv));
        if (u->t_200) jb_add(&j, ",\"inv_200_ms\":%lld", (long long)(u->t_200 - u->t_inv));
        if (u->b_up) jb_add(&j, ",\"inv_bearer_ms\":%lld", (long long)(u->b_up - u->t_inv));
    }
    int64_t tend = u->t_bye ? u->t_bye : u->t_end ? u->t_end : u->b_down;
    if (u->t_200 && tend > u->t_200) jb_add(&j, ",\"talk_s\":%.1f", (tend - u->t_200) / 1000.0);
    if (u->b_up && u->b_down > u->b_up) jb_add(&j, ",\"bearer_s\":%.1f", (u->b_down - u->b_up) / 1000.0);
    if (u->v_up) jb_add(&j, ",\"video_flow_s\":%.1f", ((u->v_down > u->v_up ? u->v_down : ev_ms()) - u->v_up) / 1000.0);
    const char *end = u->cancel ? "cancel" : u->final_code >= 300 ? "rejected" : u->t_bye ? (u->bye_tx ? "local_bye" : "remote_bye")
                    : u->b_down ? "bearer_down" : why;
    jb_add(&j, ",\"end\":\"%s\"", end);
    /* 2.3: who ended it (UE sent the final >=300 / CANCEL / BYE = local) and how long it rang without an answer */
    if (u->cancel || u->t_bye) jb_add(&j, ",\"end_by\":\"%s\"", u->bye_tx ? "local" : "remote");
    else if (u->final_code >= 300) jb_add(&j, ",\"end_by\":\"%s\"", u->final_tx ? "local" : "remote");
    if (u->t_180 && !u->t_200 && u->t_end > u->t_180) jb_add(&j, ",\"ring_s\":%.1f", (u->t_end - u->t_180) / 1000.0);
    if (u->final_code) jb_add(&j, ",\"final_code\":%d", u->final_code);
    if (u->sip_cause) jb_add(&j, ",\"reason_cause\":%d", u->sip_cause);
    if (u->reason[0]) jb_add(&j, ",\"reason\":\"%s\"", u->reason);
    if (u->err) jb_add(&j, ",\"err\":%d", u->err);
    if (u->crbt_video) jb_add(&j, ",\"crbt_video\":1");
    if (u->video_lost) jb_add(&j, ",\"video_bearer_lost\":1");
    if (u->rrc_cause[0]) jb_add(&j, ",\"rrc_cause\":\"%s\"", u->rrc_cause);
    if (u->ims_reason) jb_add(&j, ",\"ims_reason\":%d", u->ims_reason);
    if (u->t_nr_leave && u->t_inv) jb_add(&j, ",\"inv_to_epsfb_ms\":%lld", (long long)(u->t_nr_leave - u->t_inv));
    if (!u->have_sip) jb_add(&j, ",\"tentative\":1");
    if (!strncmp(u->b_rat, "LTE(EPS", 7)) g_efb_return_pending = 1;
    g_t_call_end_ev = u->t_bye ? u->t_bye : u->b_down ? u->b_down : ev_ms();
    jb_emit(&j);
    u->active = 0;
    ims_state_emit("call_record");
}
static void uc_start(int64_t t, int dir, const char *src) {
    if (g_uc.active) uc_emit("superseded");
    memset(&g_uc, 0, sizeof(g_uc));
    g_uc.active = 1; g_uc.n = ++g_uc_n; g_uc.t_start = t; g_uc.dir = dir;
    snprintf(g_uc.dir_src, sizeof(g_uc.dir_src), "%s", dir ? src : "");
    if (g_setup_cause_t && mono_ms() - g_setup_cause_t < 5000) snprintf(g_uc.rrc_cause, sizeof(g_uc.rrc_cause), "%s", g_setup_cause);
}
static void uc_tick(int64_t t) {
    if (!g_uc.active) return;
    if (g_uc.ended && t - g_uc.t_end > 5000) uc_emit("timeout");
    else if (!g_uc.ended && g_uc.have_sip && !g_uc.b_up && !g_uc.t_200 && t - g_uc.t_inv > 180000) uc_emit("timeout");
}
static void uc_dialog_end(int64_t t) {
    g_uc.ended = 1; g_uc.t_end = t;
    if (!g_uc.b_up || g_uc.b_down) uc_emit("sip_end");   /* else wait for the bearer release (or 5 s) */
    else ims_state_emit("call_end");
}
/* NAS voice bearer up/down (called from voice_update) */
static void uc_bearer(int on, const char *rat, int q) {
    int64_t t = ev_ms(); uc_tick(t);
    if (on) {
        if (!g_uc.active || (g_uc.ended && g_uc.b_down)) {
            int dir = 0;
            if (g_setup_cause_t && mono_ms() - g_setup_cause_t < 5000) dir = !strcmp(g_setup_cause, "mt-Access") ? 2 : strstr(g_setup_cause, "mo-Voice") || strstr(g_setup_cause, "mo-Video") ? 1 : 0;
            uc_start(t, dir, "rrc");
        }
        if (!g_uc.b_up) { g_uc.b_up = t; g_uc.b_qi = q; snprintf(g_uc.b_rat, sizeof(g_uc.b_rat), "%s", rat); }
    } else if (g_uc.active) {
        g_uc.b_down = t;
        if (!g_uc.have_sip || g_uc.ended) uc_emit("bearer_down");
    }
}
static void uc_video(int on) {
    int64_t t = ev_ms();
    if (!g_uc.active) return;
    if (on && !(g_uc.v_up > g_uc.v_down)) { g_uc.v_up = t; g_uc.v_down = 0; }
    else if (!on && g_uc.v_up > g_uc.v_down) g_uc.v_down = t;
}
/* 2.2: IMS registration flow (MMTel profile only; RCS ignored) */
static struct { int64_t t_req, t_401, t_200, t_air_off; int done, dereg_cseq, lc_imsregi; double kpi_sip, kpi_total, kpi_pdn; } g_reg = { .dereg_cseq = -1 };
static void reg_set(const char *st, const char *why) {
    if (strcmp(g_ims_reg, st) || g_reg_inferred) { snprintf(g_ims_reg, sizeof(g_ims_reg), "%s", st); g_reg_inferred = 0; ims_state_emit(why); }
}
static void reg_record(const char *result, int reason) {
    struct jb j; jb_init(&j, "ims_reg");
    jb_add(&j, ",\"result\":\"%s\",\"src\":\"logcat\"", result);
    if (reason >= 0) jb_add(&j, ",\"reason\":%d", reason);
    if (!strcmp(result, "registered")) {
        if (g_reg.t_req && g_reg.t_200 >= g_reg.t_req) jb_add(&j, ",\"register_to_200_ms\":%lld", (long long)(g_reg.t_200 - g_reg.t_req));
        if (g_reg.t_req && g_reg.t_401 >= g_reg.t_req) jb_add(&j, ",\"register_to_401_ms\":%lld", (long long)(g_reg.t_401 - g_reg.t_req));
        if (g_reg.t_air_off && g_reg.t_200 > g_reg.t_air_off) jb_add(&j, ",\"airplane_off_to_200_ms\":%lld", (long long)(g_reg.t_200 - g_reg.t_air_off));
        if (g_t_ims_pdu_ev && g_reg.t_req && g_reg.t_req - g_t_ims_pdu_ev > -5000 && g_reg.t_req - g_t_ims_pdu_ev < 60000)
            jb_add(&j, ",\"ims_pdu_to_register_ms\":%lld", (long long)(g_reg.t_req - g_t_ims_pdu_ev));
        if (g_reg.kpi_sip > 0) jb_add(&j, ",\"kpi_sip_reg_s\":%.3f", g_reg.kpi_sip);
        if (g_reg.kpi_total > 0) jb_add(&j, ",\"kpi_total_s\":%.3f", g_reg.kpi_total);
        if (g_reg.kpi_pdn > 0) jb_add(&j, ",\"kpi_pdn_setup_s\":%.3f", g_reg.kpi_pdn);
        g_reg.t_air_off = 0; g_reg.kpi_sip = g_reg.kpi_total = 0;
    }
    jb_emit(&j);
}
/* SIP message seen in the AP log. tx = sent by the UE. */
static void uc_sip(int64_t t, int tx, const char *method, int code, const char *cseq_m, long cseq) {
    uc_tick(t);
    if (method[0]) {
        if (!strcmp(method, "REGISTER")) {
            if (tx && (g_reg.done || !g_reg.t_req)) { g_reg.t_req = t; g_reg.t_401 = g_reg.t_200 = 0; g_reg.done = 0; }
            return;
        }
        if (!strcmp(method, "INVITE")) {
            if (g_uc.active && !g_uc.have_sip && !g_uc.ended && t - g_uc.t_start < 5000) {   /* NAS saw the bearer first */
                g_uc.dir = tx ? 1 : 2; snprintf(g_uc.dir_src, sizeof(g_uc.dir_src), "sip");
            } else if (g_uc.active && g_uc.have_sip && !g_uc.ended) return;                    /* re-INVITE */
            else uc_start(t, tx ? 1 : 2, "sip");
            g_uc.have_sip = 1; g_uc.t_inv = t;
            if (g_uc.t_start > t) g_uc.t_start = t;
            /* 2.3: an MMTel INVITE (sent or received) needs a registered IMS; capture started while registered (run9) */
            if (!strcmp(g_ims_reg, "unknown")) { snprintf(g_ims_reg, sizeof(g_ims_reg), "registered"); g_reg_inferred = 1; }
            ims_state_emit("sip");
        } else if (!g_uc.active || !g_uc.have_sip) return;
        else if (!strcmp(method, "ACK")) { if (!g_uc.t_ack) g_uc.t_ack = t; }
        else if (!strcmp(method, "BYE")) { if (!g_uc.t_bye) { g_uc.t_bye = t; g_uc.bye_tx = tx; } }
        else if (!strcmp(method, "CANCEL")) { g_uc.cancel = 1; g_uc.bye_tx = tx; if (!g_uc.t_bye) g_uc.t_bye = t; }
        return;
    }
    if (!strcmp(cseq_m, "REGISTER")) {
        if (code == 401 || code == 407) { if (!g_reg.t_401) g_reg.t_401 = t; return; }
        if (code == 200) {
            if (cseq == g_reg.dereg_cseq) { reg_set("deregistered", "deregister"); g_reg.done = 1; return; }
            g_reg.t_200 = t; g_reg.done = 1;
            reg_set("registered", "register");
            if (!g_reg.lc_imsregi) reg_record("registered", -1);
        } else if (code >= 300) { g_reg.done = 1; reg_set("failed", "register"); struct jb j; jb_init(&j, "ims_reg"); jb_add(&j, ",\"result\":\"failed\",\"code\":%d,\"src\":\"logcat\"", code); jb_emit(&j); }
        return;
    }
    if (!g_uc.active || !g_uc.have_sip || g_uc.ended) return;
    if (!strcmp(cseq_m, "INVITE")) {
        if (code == 100 && !g_uc.t_100) g_uc.t_100 = t;
        else if (code == 183 && !g_uc.t_183) g_uc.t_183 = t;
        else if (code == 180 && !g_uc.t_180) { g_uc.t_180 = t; ims_state_emit("alerting"); }
        else if (code == 200 && !g_uc.t_200) { g_uc.t_200 = t; ims_state_emit("connected"); }
        else if (code >= 300 && !g_uc.t_200) { g_uc.final_code = code; g_uc.final_tx = tx; uc_dialog_end(t); }
    } else if ((!strcmp(cseq_m, "BYE") || !strcmp(cseq_m, "CANCEL")) && code >= 200) uc_dialog_end(t);
}

static void emit_ims_provider(const char *name, const char *status, const char *available, const char *detail) {
    struct jb j; jb_init(&j, "ims_provider");
    jb_add(&j, ",\"name\":\"%s\",\"status\":\"%s\"", name, status);
    if (available) jb_add(&j, ",\"available\":%s", available);
    if (detail && detail[0]) jb_add(&j, ",\"detail\":\"%s\"", detail);
    jb_emit(&j);
}

static void apply_lc_event(const struct lc_event *e) {
    if (!e) return;
    switch (e->kind) {
    case LC_SIP_SAMSUNG: {
        struct jb j; jb_init(&j, "sip");
        jb_add(&j, ",\"src\":\"logcat\",\"fmt\":\"samsung\",\"dir\":\"%s\"", e->tx ? "UL" : "DL");
        if (e->code) jb_add(&j, ",\"status\":%d,\"reason\":\"%s\"", e->code, e->reason);
        else jb_add(&j, ",\"method\":\"%s\"", e->method);
        if (e->cseq >= 0) jb_add(&j, ",\"cseq\":\"%ld %s\"", e->cseq, e->cseq_m);
        if (g_uc.active && g_uc.have_sip && !g_uc.ended) jb_add(&j, ",\"call_n\":%d", g_uc.n);
        jb_emit(&j);
        uc_sip(e->t_ms, e->tx == 1, e->method, e->code, e->cseq_m, e->cseq);
        g_lc_sip++;
        break;
    }
    case LC_SIP_GENERIC: {
        struct jb j; jb_init(&j, "sip");
        jb_add(&j, ",\"src\":\"logcat\",\"tentative\":1");
        if (e->code) jb_add(&j, ",\"status\":%d", e->code); else jb_add(&j, ",\"method\":\"%s\"", e->method);
        jb_emit(&j);
        g_lc_sip++;
        break;
    }
    case LC_RCS_SKIP:
        g_lc_rcs++;
        break;
    case LC_CODEC:
        if (e->codec[0] && g_uc.active && strcmp(e->codec, g_uc.codec)) {
            snprintf(g_uc.codec, sizeof(g_uc.codec), "%s", e->codec);
            ims_state_emit("codec");
        }
        break;
    case LC_CALL_STATE:
        if (!g_uc.active) break;
        if (e->reason[0]) snprintf(g_uc.reason, sizeof(g_uc.reason), "%s", e->reason);
        if (e->err) g_uc.err = e->err;
        if (e->crbt_video) g_uc.crbt_video = 1;
        if (!g_uc.dir && e->dir) {
            g_uc.dir = e->dir;
            snprintf(g_uc.dir_src, sizeof(g_uc.dir_src), "csm");
        }
        break;
    case LC_IMS_CAUSE:
        if (g_uc.active) g_uc.sip_cause = e->sip_cause;
        break;
    case LC_BEARER_LOST_QCI2:
        if (g_uc.active) g_uc.video_lost = 1;
        break;
    case LC_DEREG_CSEQ:
        g_reg.dereg_cseq = e->dereg_cseq;
        break;
    case LC_IMS_REASON:
        if (g_uc.active) g_uc.ims_reason = e->ims_reason;
        break;
    case LC_KPI_SIP: g_reg.kpi_sip = e->kpi; break;
    case LC_KPI_TOTAL: g_reg.kpi_total = e->kpi; break;
    case LC_KPI_PDN: g_reg.kpi_pdn = e->kpi; break;
    case LC_IMSREGI_REGISTERED:
        g_reg.lc_imsregi = 1;
        reg_set("registered", "register_ap");
        reg_record("registered", -1);
        break;
    case LC_IMSREGI_DEREGISTERED:
        g_reg.lc_imsregi = 1;
        reg_set("deregistered", "deregister_ap");
        reg_record("deregistered", e->reg_reason);
        break;
    case LC_AIRPLANE: {
        if (!e->airplane_on) g_reg.t_air_off = e->t_ms;
        struct jb j; jb_init(&j, "event");
        jb_add(&j, ",\"rat\":\"IMS\",\"event\":\"%s\",\"msg\":\"\"", e->airplane_on ? "airplane_on" : "airplane_off");
        jb_emit(&j);
        break;
    }
    case LC_REGISTER_AP:
        if (strcmp(g_ims_reg, "registered") || g_reg_inferred) {
            snprintf(g_ims_reg, sizeof(g_ims_reg), "registered");
            g_reg_inferred = 0;
            ims_state_emit("register_ap");
        }
        break;
    case LC_DEREGISTER_AP:
        if (strcmp(g_ims_reg, "deregistered")) {
            snprintf(g_ims_reg, sizeof(g_ims_reg), "deregistered");
            g_reg_inferred = 0;
            ims_state_emit("register_ap");
        }
        break;
    default:
        break;
    }
}

static void lc_wait_ms(int ms) {
    int64_t end = now_ms() + ms;
    while (!g_stop) {
        int64_t left = end - now_ms();
        if (left <= 0) return;
        if (left > 200) left = 200;
        struct timespec ts = { 0, (long)left * 1000000L };
        nanosleep(&ts, NULL);
    }
}

static void *logcat_thread(void *arg) {
    (void)arg;
    int backoff = 2000;
    long fd_limit = sysconf(_SC_OPEN_MAX);
    while (!g_stop) {
        int pfd[2];
        if (pipe2(pfd, O_CLOEXEC) != 0) {
            pthread_mutex_lock(&g_mu);
            emit_ims_provider("samsung_logcat", "unavailable", "false", "pipe_failed");
            pthread_mutex_unlock(&g_mu);
            lc_wait_ms(backoff);
            if (backoff < 30000) backoff *= 2;
            continue;
        }
        pid_t pid = fork();
        if (pid == 0) {
            if (dup2(pfd[1], STDOUT_FILENO) < 0) _exit(127);
            int dn = open("/dev/null", O_RDWR);
            if (dn < 0 || dup2(dn, STDIN_FILENO) < 0 || dup2(dn, STDERR_FILENO) < 0) _exit(127);
            // Vendor libdiag may open its router connection without CLOEXEC.
#ifdef __NR_close_range
            if (syscall(__NR_close_range, 3U, ~0U, 0U) != 0)
#endif
                for (long fd = 3; fd < (fd_limit > 0 ? fd_limit : 1024); fd++) close((int)fd);
            char *argv[] = { "logcat", "-b", "radio", "-b", "main", "-v", "epoch", "-T", "1", NULL };
            execv("/system/bin/logcat", argv);
            _exit(127);
        }
        close(pfd[1]);
        if (pid < 0) {
            close(pfd[0]);
            pthread_mutex_lock(&g_mu);
            emit_ims_provider("samsung_logcat", "unavailable", "false", "fork_failed");
            pthread_mutex_unlock(&g_mu);
            lc_wait_ms(backoff);
            if (backoff < 30000) backoff *= 2;
            continue;
        }
        g_lc_pid = pid;
        g_lc_alive = 1;
        FILE *in = fdopen(pfd[0], "r");
        if (!in) {
            close(pfd[0]);
            kill(pid, SIGTERM);
            waitpid(pid, NULL, 0);
            g_lc_pid = -1;
            g_lc_alive = 0;
            lc_wait_ms(backoff);
            if (backoff < 30000) backoff *= 2;
            continue;
        }
        elog("IMS logcat reader started (pid %d); raw lines are not stored", (int)pid);
        pthread_mutex_lock(&g_mu);
        emit_ims_provider("samsung_logcat", "starting", NULL, "");
        if (!g_lc_fmt && !g_reg.lc_imsregi) g_lc_start = now_ms();
        pthread_mutex_unlock(&g_mu);
        char line[4096], red[4200];
        while (!g_stop && fgets(line, sizeof(line), in)) {
            g_lc_read++;
            if (!lc_keep_line(line) || lc_is_noisy(line)) continue;
            g_lc_lines++;
            lc_redact_line(line, red, sizeof(red));
            struct lc_event ev;
            int fmt = g_lc_fmt;
            if (!lc_parse_line(red, fmt, &fmt, &ev)) continue;
            pthread_mutex_lock(&g_mu);
            if (fmt) g_lc_fmt = 1;
            apply_lc_event(&ev);
            pthread_mutex_unlock(&g_mu);
        }
        fclose(in);
        waitpid(pid, NULL, 0);
        if (g_lc_pid == pid) g_lc_pid = -1;
        g_lc_alive = 0;
        elog("IMS logcat ended: read %lu, kept %lu, sip %lu, rcs skipped %lu, fmt %s",
             g_lc_read, g_lc_lines, g_lc_sip, g_lc_rcs, g_lc_fmt ? "samsung" : "none");
        if (g_stop) break;
        pthread_mutex_lock(&g_mu);
        emit_ims_provider("samsung_logcat", "reconnecting", NULL, "logcat_exited");
        pthread_mutex_unlock(&g_mu);
        lc_wait_ms(backoff);
        if (backoff < 30000) backoff *= 2;
    }
    return NULL;
}

static void logcat_start(void) {
    if (g_lc_started) return;
    g_lc_start = now_ms();
    if (pthread_create(&g_lc_th, NULL, logcat_thread, NULL) == 0) g_lc_started = 1;
    else {
        emit_ims_provider("samsung_logcat", "unavailable", "false", "thread_failed");
    }
}

static void logcat_stop(void) {
    if (g_lc_pid > 0) {
        kill(g_lc_pid, SIGTERM);
        waitpid(g_lc_pid, NULL, 0);
        g_lc_pid = -1;
    }
    if (g_lc_started) {
        pthread_join(g_lc_th, NULL);
        g_lc_started = 0;
    }
}

static void nr_statistics_reset(void) {
    memset(g_pd_prev, 0, sizeof(g_pd_prev));
    memset(g_pd_base, 0, sizeof(g_pd_base));
    g_pd_t = 0;
    memset(g_nr_layer, 0, sizeof(g_nr_layer));
    g_csf_t = 0;
    g_csf_n = g_csf_cqi_sum = g_csf_reports = g_csf_skipped = g_csf_cc = 0;
    g_csf_cqi_min = 99;
    g_csf_cqi_max = -1;
    memset(g_csf_rank, 0, sizeof(g_csf_rank));
    g_dl_t = g_dl_last = g_ul_t = g_us_t = 0;
    memset(g_dl, 0, sizeof(g_dl));
    memset(g_ul, 0, sizeof(g_ul));
    memset(g_us, 0, sizeof(g_us));
    g_dl_bad = g_dl_other_rnti = g_us_bad = 0;
}

static void *decode_worker(void *arg) {
    (void)arg;
    uint8_t tmp[DIAG_Q_SLOT];
    struct diag_epoch_barrier nr_epoch = {0};
    for (;;) {
        pthread_mutex_lock(&g_dq_mu);
        while (!g_stop && diag_q_empty(&g_dq) && diag_q_empty(&g_dq_lo))
            pthread_cond_wait(&g_dq_cv, &g_dq_mu);
        if (diag_q_empty(&g_dq) && diag_q_empty(&g_dq_lo)) {
            pthread_mutex_unlock(&g_dq_mu);
            break;
        }
        struct diag_q_item it;
        int high = !diag_q_empty(&g_dq);
        if (high) diag_q_pop(&g_dq, &it);
        else diag_q_pop(&g_dq_lo, &it);
        uint16_t n = it.len;
        memcpy(tmp, it.data, n);
        uint64_t timestamp = n >= 12 ? rd64(tmp + 4) : 0;
        if (!high && !diag_epoch_allows(&nr_epoch, timestamp)) {
            diag_q_drop_epoch(&g_dq_lo, n);
            pthread_mutex_unlock(&g_dq_mu);
            continue;
        }
        pthread_mutex_unlock(&g_dq_mu);
        pthread_mutex_lock(&g_mu);
        int previous_pci = g_nr.pci;
        long previous_arfcn = g_nr.arfcn;
        handle_record(tmp, n);
        if (high && (previous_pci != g_nr.pci || previous_arfcn != g_nr.arfcn)) {
            diag_epoch_mark(&nr_epoch, timestamp);
            nr_statistics_reset();
        } else if (high && nr_epoch.active && !nr_epoch.timestamp && timestamp) {
            /* An untimed transition stays closed until a timed high-priority
               record provides a conservative boundary for later statistics. */
            diag_epoch_mark(&nr_epoch, timestamp);
        }
        pthread_mutex_unlock(&g_mu);
    }
    return NULL;
}

static void voice_update(const char *rat, int on, int q, const char *why) {
    char ex[96];
    int64_t now = mono_ms();
    if (on && !g_voice_on) {
        g_voice_on = 1; g_voice_t0 = now; g_voice_5qi = q; snprintf(g_voice_rat, sizeof(g_voice_rat), "%s", rat);
        snprintf(ex, sizeof(ex), "%s %s=%d %s", rat, strcmp(rat, "NR") ? "QCI" : "5QI", q, why);
        ims_event(NULL, q == 2 ? "video_bearer_up" : "voice_bearer_up", ex);
        uc_bearer(1, rat, q);
        /* EPS fallback without SIP: LTE voice bearer shortly after the UE left NR (tentative) */
        if (!strcmp(rat, "LTE") && g_t_nr_rrc && now - g_t_nr_rrc <= 20000 && g_t_lte_rrc >= g_t_nr_rrc) {
            int sip_efb = 0; for (int i = 0; i < 4; i++) if (g_calls[i].used && g_calls[i].efb && now - g_calls[i].t0 < 30000) sip_efb = 1;
            if (!sip_efb) {
                struct jb j; jb_init(&j, "event");
                jb_add(&j, ",\"rat\":\"IMS\",\"event\":\"eps_fallback\",\"msg\":\"qos\",\"src\":\"qos\",\"nr_to_bearer_ms\":%lld,\"tentative\":1", (long long)(now - g_t_nr_rrc));
                jb_emit(&j);
                snprintf(g_voice_rat, sizeof(g_voice_rat), "LTE*");
                snprintf(g_uc.b_rat, sizeof(g_uc.b_rat), "LTE(EPSFB)");
            }
        }
        ims_state_emit("voice_bearer");
    } else if (!on && g_voice_on) {
        g_voice_on = 0;
        snprintf(ex, sizeof(ex), "%s dur_s=%.1f %s", g_voice_rat, (now - g_voice_t0) / 1000.0, why);
        ims_event(NULL, "voice_bearer_down", ex);
        uc_bearer(0, g_voice_rat, 0);
        ims_state_emit("voice_bearer");
    }
}
/* 3GPP TS 24.501 §9.11.4.14. The same two-octet value is used in each direction. */
static double ambr_mbps(const uint8_t *field) {
    static const double units[] = {
        0, .001, .004, .016, .064, .256, 1, 4, 16, 64, 256,
        1000, 4000, 16000, 64000, 256000, 1000000, 4000000, 16000000,
        64000000, 256000000, 1000000000, 4000000000., 16000000000.,
        64000000000., 256000000000.,
    };
    unsigned int unit = field[0];
    if (!unit || unit >= sizeof(units) / sizeof(units[0])) return NA_F;
    return units[unit] * ((field[1] << 8) | field[2]);
}

static void emit_nas_release(int nr, int id) {
    struct jb j; jb_init(&j, "nas_release");
    jb_add(&j, ",\"rat\":\"%s\",\"%s\":%d", nr ? "NR" : "LTE", nr ? "psi" : "ebi", id);
    jb_emit(&j);
}
static void nr_5gsm(const uint8_t *sm, int n, int dl) {
    if (n < 4 || sm[0] != 0x2e) return;
    int psi = sm[1] & 15, mt = sm[3], p;
    if (!psi) return;
    struct psi_st *s = &g_psi[psi];
    double dl_ambr = NA_F, ul_ambr = NA_F;
    if (mt == 0xc2) {   /* PDU SESSION ESTABLISHMENT ACCEPT: sel type/SSC (1), QoS rules LV-E, AMBR LV */
        p = 5; if (p + 2 > n) return;
        p += 2 + (sm[p] << 8 | sm[p + 1]); if (p >= n) return;
        int ambr_len = sm[p++];
        if (ambr_len > n - p) return;
        if (ambr_len == 6) {
            dl_ambr = ambr_mbps(sm + p);
            ul_ambr = ambr_mbps(sm + p + 3);
        }
        p += ambr_len;
        memset(s, 0, sizeof(*s)); s->used = 1;
        s->pdu_type = sm[4] & 7;
        s->ssc_mode = (sm[4] >> 4) & 7;
    } else if (mt == 0xcb) p = 4;            /* MODIFICATION COMMAND */
    else if (mt == 0xd3 || mt == 0xd4) {    /* RELEASE COMMAND / COMPLETE */
        int had = 0; for (int q = 0; q < 64; q++) if (s->qfi5qi[q] == 1 || s->qfi5qi[q] == 2) had = 1;
        if (s->ims && mt == 0xd3) ims_event(NULL, "ims_pdu_session_release", "");
        emit_nas_release(1, psi);
        memset(s, 0, sizeof(*s));
        update_ims_bearer("ims_pdu");
        if (had) voice_update("NR", 0, 0, "pdu_release");
        return;
    } else return;
    int voice_add = 0, voice_del = 0, vq = 0;
    int config_changed = mt == 0xc2;
    int qos_changed = mt == 0xc2;
    const struct nas_ie *ies = mt == 0xc2 ? k5s_estacc : k5s_modcmd;
    while (p < n) {
        int iei = sm[p], l, header;
        if (iei >= 0x80) { p++; continue; }
        const struct nas_ie *ie = ies;
        while (ie->name && ie->iei != iei) ie++;
        int fmt = ie->name ? ie->fmt : ((iei & 0xf0) == 0x70 ? 5 : 4);
        if (fmt == 3) { header = 1; l = ie->len - 1; }
        else if (fmt == 4 && n - p >= 2) { header = 2; l = sm[p + 1]; }
        else if (fmt == 5 && n - p >= 3) { header = 3; l = sm[p + 1] << 8 | sm[p + 2]; }
        else break;
        if (l > n - p - header) break;
        const uint8_t *v = sm + p + header;
        p += header + l;
        if (mt == 0xc2 && iei == 0x25) {
            nas_dnn(v, l, s->dnn, sizeof(s->dnn));
            s->ims = is_ims_name(s->dnn);
        }
        if (mt == 0xc2 && iei == 0x22 && (l == 1 || l == 2 || l == 4 || l == 5 || l == 8)) {
            s->sst = v[0];
            if (l >= 4) {
                s->sd = ((uint32_t)v[1] << 16) | ((uint32_t)v[2] << 8) | v[3];
                s->sd_present = s->sd != 0xffffff;
            }
        }
        if (mt == 0xcb && iei == 0x2a && l == 6) {
            dl_ambr = ambr_mbps(v);
            ul_ambr = ambr_mbps(v + 3);
            config_changed = 1;
        }
        if (iei == 0x79) {  /* authorized QoS flow descriptions */
            for (int q = 0; q + 3 <= l; ) {
                int qfi = v[q] & 0x3f, op = v[q + 1] >> 5, np = v[q + 2] & 0x3f, fiveqi = -1; q += 3;
                int k;
                for (k = 0; k < np && q + 2 <= l; k++) {
                    int id = v[q], pl = v[q + 1];
                    q += 2;
                    if (pl > l - q) break;
                    if (id == 1 && pl == 1) fiveqi = v[q];
                    q += pl;
                }
                if (k != np) break;
                if (!qfi) continue;
                if ((op == 1 || op == 3) && fiveqi > 0) { s->qfi5qi[qfi] = fiveqi; qos_changed = 1; }
                if ((op == 1 || op == 3) && (s->qfi5qi[qfi] == 1 || s->qfi5qi[qfi] == 2)) { voice_add = 1; vq = s->qfi5qi[qfi]; }
                if (op == 2) { if (s->qfi5qi[qfi] == 1 || s->qfi5qi[qfi] == 2) voice_del = 1; s->qfi5qi[qfi] = 0; qos_changed = 1; }
            }
        }
    }
    if (config_changed) {
        struct jb j; jb_init(&j, "nas_pdu");
        jb_add(&j, ",\"rat\":\"NR\",\"psi\":%d,\"dnn\":\"%s\",\"ims\":%d%s",
               psi, s->dnn, s->ims, mt == 0xcb ? ",\"modified\":true" : "");
        if (s->pdu_type >= 1 && s->pdu_type <= 5) jb_i(&j, "pdu_type", s->pdu_type);
        if (s->ssc_mode >= 1 && s->ssc_mode <= 3) jb_i(&j, "ssc_mode", s->ssc_mode);
        if (s->sst) jb_i(&j, "sst", s->sst);
        if (s->sd_present) jb_add(&j, ",\"sd\":\"%06X\"", s->sd);
        jb_f(&j, "ambr_dl_mbps", dl_ambr);
        jb_f(&j, "ambr_ul_mbps", ul_ambr);
        jb_emit(&j);
        if (mt == 0xc2) {
            if (s->ims) { g_t_ims_pdu_ev = ev_ms(); char ex[48]; snprintf(ex, sizeof(ex), "PSI %d DNN %s", psi, s->dnn); ims_event(NULL, "ims_pdu_session_up", ex); }
            update_ims_bearer("ims_pdu");
        }
    }
    if (qos_changed) {
        if (voice_add && !s->ims && !s->dnn[0] && vq == 1) {
            s->used = 1;
            s->ims = 2;
            update_ims_bearer("ims_pdu_inferred");
        }
        struct jb j; jb_init(&j, "nas_qos");
        jb_add(&j, ",\"rat\":\"NR\",\"psi\":%d,\"ims\":%d,\"voice_add\":%d,\"voice_del\":%d,\"flows\":[", psi, s->ims, voice_add, voice_del);
        int f = 0; for (int q = 0; q < 64; q++) if (s->qfi5qi[q]) { jb_add(&j, "%s{\"qfi\":%d,\"5qi\":%d}", f ? "," : "", q, s->qfi5qi[q]); f = 1; }
        jb_add(&j, "]"); jb_emit(&j);
        if (voice_add) voice_update("NR", 1, vq, s->ims == 1 ? "ims_pdu" : s->ims == 2 ? "ims_pdu_inferred" : "pdu");
        else if (voice_del) {
            int still = 0; for (int q = 0; q < 64; q++) if (s->qfi5qi[q] == 1 || s->qfi5qi[q] == 2) still = 1;
            if (!still) voice_update("NR", 0, 0, "qos_flow_deleted");
        }
        int vid = 0; for (int q = 0; q < 64; q++) if (s->qfi5qi[q] == 2) vid = 1;
        uc_video(vid);
    }
    (void)dl;
}
static void parse_nr_nas_plain(const uint8_t *b, int n, int dl) {
    if (n < 10 || rd32(b) != 1) return;
    const uint8_t *m = b + 7; int ml = n - 7;
    if (m[0] == 0x2e) { nr_5gsm(m, ml, dl); return; }
    if (m[0] != 0x7e || m[1] != 0x00) return;            /* plain 5GMM only */
    if ((m[2] == 0x67 || m[2] == 0x68) && ml >= 6 && (m[3] & 15) == 1) {   /* NAS transport, container = N1 SM */
        int l = m[4] << 8 | m[5];
        if (6 + l <= ml) nr_5gsm(m + 6, l, dl);
    }
}
/* LTE ESM: activate default (0xC1) / dedicated (0xC5) bearer request, deactivate request (0xCD) */
static void parse_lte_esm_plain(const uint8_t *b, int n, int dl) {
    if (n < 4 + 3) return;
    const uint8_t *m = b + 4; int ml = n - 4;
    if ((m[0] & 15) != 2) return;
    int ebi = m[0] >> 4, mt = m[2];
    struct ebi_st *e = &g_ebi[ebi];
    if (mt == 0xc1 && dl && ml >= 5) {          /* EPS QoS LV at 3, APN LV next */
        int ql = m[3], qci = ql ? m[4] : 0, p = 4 + ql;
        if (p >= ml) return;
        char apn[101] = ""; int al = m[p]; if (p + 1 + al <= ml) nas_dnn(m + p + 1, al, apn, sizeof(apn));
        memset(e, 0, sizeof(*e)); e->used = 1; e->qci = qci; e->ims = is_ims_name(apn);
        struct jb j; jb_init(&j, "nas_pdu");
        jb_add(&j, ",\"rat\":\"LTE\",\"ebi\":%d,\"apn\":\"%s\",\"qci\":%d,\"ims\":%d", ebi, apn, qci, e->ims);
        jb_emit(&j);
        if (e->ims) ims_event(NULL, "ims_pdn_up", "LTE");
        update_ims_bearer("ims_pdu");
    } else if (mt == 0xc5 && dl && ml >= 5) {   /* linked EBI in byte 3 low nibble, EPS QoS LV at 4 */
        int linked = m[3] & 15, ql = m[4], qci = ql ? (ml > 5 ? m[5] : 0) : 0;
        memset(e, 0, sizeof(*e)); e->used = 1; e->qci = qci; e->linked = linked; e->ims = g_ebi[linked].ims;
        /* 2.2: after N1 interworking (EPS fallback) the IMS default bearer was never activated on LTE: infer it from QCI 1 */
        if (!e->ims && qci == 1 && !g_ebi[linked].used && linked) {
            g_ebi[linked].used = 1; g_ebi[linked].ims = 2; e->ims = 2;
            update_ims_bearer("ims_pdu_inferred");
        } else if (!e->ims && g_ebi[linked].ims) e->ims = g_ebi[linked].ims;
        struct jb j; jb_init(&j, "nas_qos");
        jb_add(&j, ",\"rat\":\"LTE\",\"ebi\":%d,\"linked_ebi\":%d,\"qci\":%d,\"ims\":%d", ebi, linked, qci, e->ims);
        jb_emit(&j);
        if (qci == 1) voice_update("LTE", 1, 1, "dedicated_bearer");
        else if (qci == 2) { if (!g_voice_on) voice_update("LTE", 1, 2, "dedicated_bearer"); uc_video(1); }
    } else if (mt == 0xcd && dl) {
        int q = e->used ? e->qci : 0;
        emit_nas_release(0, ebi);
        memset(e, 0, sizeof(*e));
        update_ims_bearer("ims_pdu");
        int still1 = 0, still2 = 0;
        for (int i = 0; i < 16; i++) if (g_ebi[i].used) { if (g_ebi[i].qci == 1) still1 = 1; if (g_ebi[i].qci == 2) still2 = 1; }
        if (q == 2) uc_video(still2);
        /* 2.2: a QCI 2 (video / video ringback) bearer going away must not end the voice call */
        if ((q == 1 && !still1) || (q == 2 && !still1 && !still2)) voice_update("LTE", 0, 0, "bearer_deactivated");
    }
}

/* Plain NAS mandatory fields. Mobile identities and security material stay out of the view. */
static void add_nas_detail(struct jb *j, int nr, int pd, const uint8_t *p, int n) {
    if (nr && pd == 0x2e && n >= 4) {
        jb_add(j, ",\"psi\":%d,\"pti\":%d", p[1], p[2]);
        if ((p[3] == 0xc3 || p[3] == 0xcd || p[3] == 0xd3) && n >= 5)
            jb_add(j, ",\"cause\":%d", p[4]);
    } else if (nr && pd == 0x7e && n >= 4) {
        switch (p[2]) {
        case 0x41:
            jb_add(j, ",\"registration_type\":%d,\"follow_on_request\":%s,\"ngksi\":%d",
                   p[3] & 7, p[3] & 8 ? "true" : "false", (p[3] >> 4) & 7);
            break;
        case 0x42:
            if (p[3] == 1 && n >= 5) jb_add(j, ",\"registration_result\":%d", p[4] & 7);
            break;
        case 0x44: case 0x4d: case 0x59: case 0x5f: case 0x64:
            jb_add(j, ",\"cause\":%d", p[3]);
            break;
        case 0x67: case 0x68:
            if (n >= 6) {
                int kind = p[3] & 15, len = p[4] << 8 | p[5];
                jb_add(j, ",\"payload_container_type\":%d,\"payload_length\":%d", kind, len);
                if (kind == 1 && len >= 4 && len <= n - 6 && p[6] == 0x2e) {
                    jb_add(j, ",\"n1_sm\":{\"message_type\":%d", p[9]);
                    add_nas_detail(j, 1, 0x2e, p + 6, len);
                    jb_add(j, "}");
                }
            }
            break;
        }
    } else if (!nr && pd == 2 && n >= 3) {
        jb_add(j, ",\"ebi\":%d,\"pti\":%d", p[0] >> 4, p[1]);
        if (p[2] == 0xc1 && n >= 5 && p[3] && p[3] + 4 < n) {
            int offset = 4 + p[3], len = p[offset];
            char apn[24] = "";
            if (len < n - offset) nas_dnn(p + offset + 1, len, apn, sizeof(apn));
            jb_add(j, ",\"qci\":%u,\"apn\":\"%s\"", p[4], apn);
        }
        if (p[2] == 0xc5 && n >= 6 && p[4] && p[4] <= n - 5)
            jb_add(j, ",\"linked_ebi\":%d,\"qci\":%u", p[3] & 15, p[5]);
        if (p[2] == 0xcd && n >= 4) jb_add(j, ",\"cause\":%u", p[3]);
    } else if (!nr && pd == 7 && n >= 3) {
        if (p[1] == 0x41) jb_add(j, ",\"attach_type\":%d,\"ksi\":%d", p[2] & 7, (p[2] >> 4) & 7);
        else if (p[1] == 0x42) jb_add(j, ",\"attach_result\":%d", p[2] & 7);
        else if (p[1] == 0x44 || p[1] == 0x4b || p[1] == 0x4e) jb_add(j, ",\"cause\":%u", p[2]);
    }
}
static void emit_nas_mobility(int nr, int ul, int pd, const uint8_t *p, int n) {
    if (pd != (nr ? 0x7e : 7) || n < (nr ? 3 : 2)) return;
    int mt = p[nr ? 2 : 1];
    const char *state = NULL;
    if (mt == 0x42 && !ul) {
        /* TS 24.501: the mandatory LV's value distinguishes 3GPP from non-3GPP access. */
        if (nr ? n >= 5 && p[3] == 1 && ((p[4] & 7) == 1 || (p[4] & 7) == 3) : n >= 3)
            state = "Registered";
    }
    /* An NR deregistration accept carries no access type. Track the corresponding
       request on the cellular path instead (TS 24.501, de-registration type IE). */
    if (nr ? n >= 4 && (mt == (ul ? 0x45 : 0x47)) && ((p[3] & 3) == 1 || (p[3] & 3) == 3)
           : mt == 0x46) state = "Deregistered";
    if (!state) return;
    struct jb j; jb_init(&j, "nas_mm");
    jb_add(&j, ",\"rat\":\"%s\",\"state\":\"%s\"", nr ? "NR" : "LTE", state);
    add_nas_detail(&j, nr, pd, p, n);
    jb_emit(&j);
}

static void emit_nas(uint16_t code, const uint8_t *body, int len) {
    int nr = code == 0xB800 || code == 0xB801 || code == 0xB80A || code == 0xB80B;
    int ul = code == 0xB801 || code == 0xB80B || code == 0xB0E3 || code == 0xB0ED;
    int offset = nr ? 7 : 4;
    if (len <= offset) return;
    if (nr && rd32(body) != 1) { unknown_layout(code, rd32(body), body, len, "version"); return; }
    const uint8_t *pdu = body + offset;
    int remaining = len - offset;
    int type_offset;
    int pd = nr ? pdu[0] : (pdu[0] & 15);
    int plain;
    if (nr) {
        if (pd != 0x7E && pd != 0x2E) return;
        type_offset = pd == 0x7E ? 2 : 3;
        plain = pd != 0x7E || (remaining >= 2 && (pdu[1] & 15) == 0);
    } else {
        if (pd != 7 && pd != 2) return;
        type_offset = pd == 7 ? 1 : 2;
        plain = pd != 7 || (pdu[0] >> 4) == 0;
    }
    struct jb j; jb_init(&j, "nas");
    jb_add(&j, ",\"rat\":\"%s\",\"dir\":\"%s\",\"src\":\"0x%04X\",\"len\":%d,\"ver\":%u,\"std_version\":\"%u.%u.%u\"",
           nr ? "NR" : "LTE", ul ? "UL" : "DL", code, remaining,
           body[0], body[offset - 3], body[offset - 2], body[offset - 1]);
    if (plain && type_offset < remaining) {
        jb_add(&j, ",\"msg\":\"%s 0x%02X\"",
               nr ? (pd == 0x7E ? "5GMM" : "5GSM") : (pd == 7 ? "EMM" : "ESM"), pdu[type_offset]);
        add_nas_detail(&j, nr, pd, pdu, remaining);
    }
    char name[96];
    nas_msg_name(pdu, remaining, !ul, name, sizeof(name));
    struct sd_out out = { g_signal_tree, 0, sizeof(g_signal_tree), 0 };
    int incomplete = nas_render(&out, pdu, remaining, !ul, 0, 0) != 0 || out.trunc;
    emit_signaling(&j, &out, incomplete, name, NULL);
    if (plain && type_offset < remaining) emit_nas_mobility(nr, ul, pd, pdu, remaining);
}

/* B88A's TA is meaningful only when Msg2 (RAR) was captured. Layouts from
 * qcom-band-menu, with the 3.18 layout also checked against our QMDL. */
static int nr_rach_ta(const uint8_t *body, int len) {
    static const struct {
        uint32_t version;
        int length, message_mask, ta_offset;
    } layouts[] = {
        { 0x20007, 108, 23, 64 },
        { 0x30008, 160, 16, 104 },
        { 0x3000a, 180, 19, 120 },
        { 0x3000e, 196, 19, 140 },
        { 0x30012, 232, 19, 176 },
        { 0x30013, 240, 19, 184 },
    };
    if (len < 4) return -1;
    uint32_t version = rd32(body);
    for (size_t i = 0; i < sizeof(layouts) / sizeof(layouts[0]); i++) {
        if (layouts[i].version != version) continue;
        if (len != layouts[i].length || !(body[layouts[i].message_mask] & 2)) break;
        int ta = rd16(body + layouts[i].ta_offset);
        return ta <= 3846 ? ta : -1;
    }
    return -1;
}

static void handle_record(const uint8_t *rec, int len) {
    /* log record: len(2) code(2) ts(8) body */
    if (len < 12) return;
    uint16_t code = rd16(rec + 2);
    const uint8_t *body = rec + 12;
    int blen = len - 12;
    struct code_stat *s = stat_for(code);
    if (s) { s->count++; s->bytes += (unsigned long)len; }
    g_total_pkts++;
    uint64_t qts = rd64(rec + 4);
    clk_feed((int64_t)((qts >> 16) * 1.25 + (qts & 0xffff) / 40960.0 * 1.25) + 315964800000LL);
    g_in_rec = 1;
    g_last_pkt_mono = mono_ms();
    struct neighbor_stat *neighbor = neighbor_stat_for(code);
    if (neighbor) {
        if (!neighbor->received) neighbor->first_seen = g_last_pkt_mono;
        neighbor->received++;
        neighbor->last_seen = g_last_pkt_mono;
        neighbor->last_len = blen;
        if (neighbor->probe || code == 0xB96A) emit_neighbor_sample(neighbor, body, blen, g_last_pkt_mono);
    }
    switch (code) {
    case 0xB0C0: parse_lte_rrc(body, blen); break;
    case 0xB0C1: parse_lte_mib(body, blen); break;
    case 0xB0C2: parse_lte_cellinfo(body, blen); break;
    case 0xB17F: parse_lte_b17f(body, blen); break;
    case 0xB179: parse_lte_b179(body, blen); break;
    case 0xB180: parse_lte_b180(body, blen); break;
    case 0xB193: parse_lte_b193(body, blen); break;
    case 0xB192: case 0xB195: parse_lte_neighbor_response(code, body, blen); break;
    case 0xB139: parse_lte_b139(body, blen); break;
    case 0xB14D: case 0xB14E: parse_lte_csf(code, body, blen); break;
    case 0xB13C: case 0xB16F: parse_lte_pucch_power(code, body, blen); break;
    case 0xB16D: parse_lte_b16d(body, blen); break;
    case 0xB16E: parse_lte_b16e(body, blen); break;
    case 0xB173: parse_lte_b173(body, blen); break;
    case 0xB126: parse_lte_b126(body, blen); break;
    case 0xB184: parse_lte_b184(body, blen); break;
    case 0xB087: case 0xB097: case 0xB0A4: case 0xB0B4:
        parse_lte_layer_stats(code, body, blen); break;
    case 0xB063: case 0xB064: parse_lte_mac_tb(code, body, blen); break;
    case 0xB821: parse_nr_rrc(body, blen); break;
    case 0xB822: parse_nr_mib(body, blen); break;
    case 0xB823: parse_nr_cellinfo(body, blen); break;
    case 0xB97F: parse_nr_b97f(body, blen); break;
    case 0xB975: parse_nr_b975(body, blen); break;
    case 0xB8D8: parse_nr_b8d8(body, blen); break;
    case 0xB8DD: parse_nr_b8dd(body, blen); break;
    case 0xB888: parse_nr_b888(body, blen); break;
    case 0xB842: case 0xB860: case 0xB84D: case 0xB868: case 0xB881:
        parse_nr_layer_stats(code, body, blen); break;
    case 0xB8A7: parse_nr_b8a7(body, blen); break;
    case 0xB825: parse_nr_b825(body, blen); break;
    case NR_CA_STATE_CODE: parse_nr_b9a7(body, blen); break;
    case 0xB887: parse_nr_b887(body, blen); break;
    case 0xB884: parse_nr_b884(body, blen); break;
    case 0xB883: parse_nr_b883(body, blen); break;
    case 0x156E: parse_ims_sip(body, blen); break;
    case 0xB800: case 0xB80A: emit_nas(code, body, blen); parse_nr_nas_plain(body, blen, 1); break;
    case 0xB801: case 0xB80B: emit_nas(code, body, blen); parse_nr_nas_plain(body, blen, 0); break;
    case 0xB0E2: emit_nas(code, body, blen); parse_lte_esm_plain(body, blen, 1); break;
    case 0xB0E3: emit_nas(code, body, blen); parse_lte_esm_plain(body, blen, 0); break;
    case 0xB0EC: case 0xB0ED: emit_nas(code, body, blen); break;
    case 0x1830: parse_ims_1830(body, blen); break;
    case 0x1832: parse_ims_1832(body, blen); break;
    case 0x1568: ims_media_count(0, code, body, blen); break;
    case 0x1569: ims_media_count(1, code, body, blen); break;
    case 0x156A: ims_media_count(2, code, body, blen); break;
    case 0x17F2: ims_media_count(3, code, body, blen); break;
    case 0x1831: if (blen >= 1) layout_seen(code, body[0], body, blen < 2 ? blen : 2); break;
    case 0xB889: case 0xB88A: {
        if (blen < 4) break;
        struct jb j; jb_init(&j, "nr_rach");
        jb_add(&j, ",\"src\":\"0x%04X\",\"msg\":\"%s\",\"ver\":\"%u.%u\",\"len\":%d",
               code, code == 0xB889 ? "RACH trigger" : "RACH attempt", rd16(body + 2), rd16(body), blen);
        if (code == 0xB88A) {
            layout_seen(code, rd32(body), body, blen);
            int ta = nr_rach_ta(body, blen);
            if (ta >= 0) jb_i(&j, "ta", ta);
            if (ta >= 0 && g_nr.pci >= 0 && g_nr.arfcn > 0 &&
                g_nr.t_seen && mono_ms() - g_nr.t_seen <= 3000) {
                jb_i(&j, "pci", g_nr.pci);
                jb_i(&j, "arfcn", g_nr.arfcn);
            }
        }
        jb_emit(&j);
        break;
    }
    case 0xB96A: parse_nr_b96a(body, blen); break;
    case 0xB119: case 0xB123:
        if (blen >= 1) layout_seen(code, body[0], body, blen);
        break;
    case 0xB96D: case 0xB96E: case 0xB96F: case 0xB992:
        if (blen >= 4) layout_seen(code, rd32(body), body, blen);
        break;
    default: break; /* saved raw only; counted in status */
    }
    g_in_rec = 0;
}

/* DCI log callback (libdiag read thread). One or more log records per call. */
static void on_log(unsigned char *ptr, int len) {
    atomic_fetch_add_explicit(&g_dci_callbacks, 1, memory_order_relaxed);
    if (!ptr || len < 12) {
        atomic_fetch_add_explicit(&g_dci_bad_frames, 1, memory_order_relaxed);
        return;
    }
    int pos = 0;
    while (len - pos >= 12) {
        int rl = rd16(ptr + pos);
        if (rl < 12 || rl > len - pos) break;
        pthread_mutex_lock(&g_mu);
        qmdl_write_locked(ptr + pos, (uint16_t)rl);
        pthread_mutex_unlock(&g_mu);
        if (!g_dq_started || rl > DIAG_Q_SLOT) {
            pthread_mutex_lock(&g_mu);
            handle_record(ptr + pos, rl);
            pthread_mutex_unlock(&g_mu);
            if (rl > DIAG_Q_SLOT) {
                pthread_mutex_lock(&g_dq_mu);
                g_dq.stats.oversized++;
                pthread_mutex_unlock(&g_dq_mu);
            }
        } else {
            /* log record = len(2) code(2) ts(8) body; route by code priority. */
            struct diag_queue *q = diag_code_priority(rd16(ptr + pos + 2)) == DIAG_PRIO_LOW ? &g_dq_lo : &g_dq;
            pthread_mutex_lock(&g_dq_mu);
            diag_q_push(q, ptr + pos, (uint16_t)rl);
            pthread_cond_signal(&g_dq_cv);
            pthread_mutex_unlock(&g_dq_mu);
        }
        pos += rl;
    }
    if (pos != len) atomic_fetch_add_explicit(&g_dci_bad_frames, 1, memory_order_relaxed);
}
static void on_event(unsigned char *data, int len) {
    (void)data;
    (void)len;
}

static void emit_serving_locked(const struct serving *s) {
    if (s->pci == NA_I) return;
    const int64_t now = mono_ms();
    if (!s->t_seen || now - s->t_seen > 20000) return;
    struct jb j; jb_init(&j, "serving");
    jb_add(&j, ",\"rat\":\"%s\",\"pci\":%d,\"seen_age_ms\":%lld", s->rat, s->pci,
           (long long)(now - s->t_seen));
    if (s->arfcn >= 0) jb_add(&j, ",\"arfcn\":%ld", s->arfcn);
    if (s->band > 0 && s->t_cell) jb_add(&j, ",\"band\":%d", s->band);
    if (s->freq > 0) jb_f(&j, "freq_mhz", s->freq);
    if (s->bw_mhz > 0) jb_f(&j, "bw_mhz", s->bw_mhz);
    if (s->t_meas && now - s->t_meas <= 15000) {
        jb_f(&j, "rsrp", s->rsrp); jb_f(&j, "rsrq", s->rsrq); jb_f(&j, "rssi", s->rssi);
        if (s->src[0]) jb_add(&j, ",\"meas_src\":\"%s\"", s->src);
    }
    if (s->t_meas && now - (s->t_sinr ? s->t_sinr : s->t_meas) <= 15000) {
        jb_f(&j, "sinr", s->sinr);
        if (s->sinr_src[0] && s->sinr > NA_F + 1) jb_add(&j, ",\"sinr_src\":\"%s\"", s->sinr_src);
        if (s->sinr > NA_F + 1 && s->sinr_agg[0]) jb_add(&j, ",\"sinr_agg\":\"%s\"", s->sinr_agg);
        if (s->sinr > NA_F + 1) {
            for (int rx = 0; rx < 4; rx++) {
                if (!(s->sinr_rx_mask & (1 << rx))) continue;
                char key[16];
                snprintf(key, sizeof(key), "sinr_rx%d", rx);
                jb_f(&j, key, s->sinr_rx[rx]);
            }
        }
    }
    if (s->tac >= 0) jb_add(&j, ",\"tac\":%ld", s->tac);
    if (s->cid) jb_add(&j, ",\"cid\":%llu", s->cid);
    if (s->mcc >= 0 && s->mnc >= 0) jb_add(&j, ",\"mcc\":%d,\"mnc\":%d,\"mnc_digits\":%d", s->mcc, s->mnc, s->mnc_digits);
    jb_emit(&j);
}

static int64_t g_start_mono;

struct dci_mask_result { int count, batch_rc, accepted; };
static struct dci_mask_result g_base_mask, g_details_mask;
static int g_dci_init_rc = -1, g_dci_init_errno;
static int g_dci_client_rc = -1, g_dci_support_rc = -1, g_dci_stream_rc = -1, g_dci_stream_per_client;
static int g_live_details_before_first_packet;

static void emit_status_locked(const char *state, int rc) {
    struct jb j; jb_init(&j, "status");
    int64_t now = mono_ms();
    jb_add(&j, ",\"state\":\"%s\",\"rc\":%d,\"uptime_s\":%lld,\"pkts\":%lu",
           state, rc, (long long)((mono_ms() - g_start_mono) / 1000), g_total_pkts);
    jb_add(&j, ",\"dci_callbacks\":%lu,\"dci_bad_frames\":%lu",
           atomic_load_explicit(&g_dci_callbacks, memory_order_relaxed),
           atomic_load_explicit(&g_dci_bad_frames, memory_order_relaxed));
    if (g_dci_init_rc >= 0) jb_i(&j, "dci_init_rc", g_dci_init_rc);
    if (g_dci_init_errno) jb_i(&j, "dci_init_errno", g_dci_init_errno);
    if (g_dci_client_rc >= 0) jb_i(&j, "dci_client_rc", g_dci_client_rc);
    if (g_dci_support_rc >= 0) jb_i(&j, "dci_support_rc", g_dci_support_rc);
    if (g_dci_stream_rc >= 0) {
        jb_i(&j, "dci_stream_rc", g_dci_stream_rc);
        jb_add(&j, ",\"dci_stream_per_client\":%s", g_dci_stream_per_client ? "true" : "false");
    }
    if (g_base_mask.count)
        jb_add(&j, ",\"dci_base_mask\":{\"requested\":%d,\"batch_rc\":%d,\"accepted\":%d}",
               g_base_mask.count, g_base_mask.batch_rc, g_base_mask.accepted);
    if (g_details_mask.count)
        jb_add(&j, ",\"dci_details_mask\":{\"requested\":%d,\"batch_rc\":%d,\"accepted\":%d,\"before_first_packet\":%s}",
               g_details_mask.count, g_details_mask.batch_rc, g_details_mask.accepted,
               g_live_details_before_first_packet ? "true" : "false");
    jb_add(&j, ",\"decode_queued\":%u,\"decode_dropped\":%lu,\"decode_high_water\":%u,\"decode_oversized\":%lu",
           diag_q_count(&g_dq) + diag_q_count(&g_dq_lo),
           g_dq.stats.dropped + g_dq_lo.stats.dropped,
           g_dq.stats.high_water > g_dq_lo.stats.high_water ? g_dq.stats.high_water : g_dq_lo.stats.high_water,
           g_dq.stats.oversized);
    jb_add(&j, ",\"decode_queued_hi\":%u,\"decode_queued_lo\":%u,\"decode_dropped_hi\":%lu,\"decode_dropped_lo\":%lu",
           diag_q_count(&g_dq), diag_q_count(&g_dq_lo),
           g_dq.stats.dropped, g_dq_lo.stats.dropped);
    jb_add(&j, ",\"decode_epoch_dropped\":%lu", g_dq_lo.stats.epoch_dropped);
    jb_add(&j, ",\"logcat_alive\":%d,\"logcat_fmt\":%s,\"logcat_sip\":%lu,\"logcat_rcs_skipped\":%lu,\"diag_ims_pkts\":%d",
           g_lc_alive, g_lc_fmt ? "true" : "false", g_lc_sip, g_lc_rcs, g_diag_ims_pkts);
    if (g_last_pkt_mono) jb_add(&j, ",\"last_pkt_age_s\":%lld",
                                (long long)((mono_ms() - g_last_pkt_mono) / 1000));
    if (g_nr_ca_subscribe_attempted) jb_i(&j, "b9a7_subscribe_rc", g_nr_ca_subscribe_rc);
    jb_add(&j, ",\"log_counts\":{");
    const char *separator = "";
    for (int i = 0; i < g_nstats; i++) {
        const struct code_stat *stat = &g_stats[i];
        if (stat->count == 0) continue;
        jb_add(&j, "%s\"0x%04X\":%lu", separator, (unsigned)stat->code, stat->count);
        separator = ",";
    }
    jb_add(&j, "}");
    jb_add(&j, ",\"neighbor_diagnostics_rev\":2,\"qmdl_active\":%s,\"neighbor_codes\":{",
           g_qmdl_fd >= 0 ? "true" : "false");
    for (unsigned i = 0; i < sizeof(g_neighbor_stats) / sizeof(g_neighbor_stats[0]); i++) {
        const struct neighbor_stat *s = &g_neighbor_stats[i];
        jb_add(&j, "%s\"0x%04X\":{\"mode\":\"live\",\"role\":\"%s\",\"received\":%lu,\"layout_errors\":%lu,"
                   "\"decoded_blocks\":%lu,\"cells\":%lu,\"rsrp\":%lu,\"rsrq\":%lu,\"sinr\":%lu,\"invalid_sinr\":%lu,\"samples\":%lu",
               i ? "," : "", (unsigned)s->code, s->probe ? "probe" : s->serving_reference == 2 ? "serving_and_neighbor" :
               s->serving_reference ? "serving" : "neighbor", s->received,
               s->layout_errors, s->decoded_blocks, s->cells, s->rsrp, s->rsrq, s->sinr, s->invalid_sinr, s->samples);
        if (s->subscription_attempted) jb_add(&j, ",\"subscribe_rc\":%d", s->subscription_rc);
        if (s->received) jb_add(&j, ",\"first_uptime_ms\":%lld,\"last_age_ms\":%lld,\"last_length\":%d",
                               (long long)(s->first_seen - g_start_mono), (long long)(now - s->last_seen), s->last_len);
        jb_add(&j, "}");
    }
    jb_add(&j, "}");
    jb_emit(&j);
}

/* ------------------------------------------------------------------ */
/* libdiag DCI                                                          */
/* ------------------------------------------------------------------ */
typedef uint8_t boolean;
typedef boolean (*fn_init)(uint8_t *);
typedef boolean (*fn_deinit)(void);
typedef int (*fn_reg_client)(int *, uint16_t *, int, void *);
typedef int (*fn_rel_client)(int *);
typedef int (*fn_reg_stream)(void (*)(unsigned char *, int), void (*)(unsigned char *, int));
typedef int (*fn_reg_stream_proc)(int, void (*)(unsigned char *, int), void (*)(unsigned char *, int));
typedef int (*fn_log_cfg)(int, int, uint16_t *, int);
typedef int (*fn_support)(int, uint16_t *);
#define DCI_OK 1001

static void *g_lib;
static fn_init p_init; static fn_deinit p_deinit; static fn_reg_client p_reg; static fn_rel_client p_rel;
static fn_reg_stream p_stream; static fn_reg_stream_proc p_stream_proc; static fn_log_cfg p_logcfg; static fn_support p_support;
static int g_client = -1, g_inited, g_enabled_count, g_neighbor_probes_enabled;

static void record_subscriptions(const uint16_t *codes, int count, int result) {
    pthread_mutex_lock(&g_mu);
    for (int i = 0; i < count; i++) {
        struct neighbor_stat *s = neighbor_stat_for(codes[i]);
        if (s) { s->subscription_attempted = 1; s->subscription_rc = result; }
    }
    pthread_mutex_unlock(&g_mu);
}

static int dci_enable_logs(uint16_t *codes, int count, struct dci_mask_result *status) {
    int batch_rc = p_logcfg(g_client, 1, codes, count);
    int accepted = batch_rc == DCI_OK ? count : 0;
    record_subscriptions(codes, count, batch_rc);
    if (batch_rc != DCI_OK) {
        for (int i = 0; i < count && !g_stop; i++) {
            int rc = count == 1 ? batch_rc : p_logcfg(g_client, 1, &codes[i], 1);
            record_subscriptions(&codes[i], 1, rc);
            if (rc == DCI_OK) accepted++;
            else elog("code 0x%04X rejected (%d)", codes[i], rc);
        }
    }
    if (status) {
        pthread_mutex_lock(&g_mu);
        *status = (struct dci_mask_result){ count, batch_rc, accepted };
        pthread_mutex_unlock(&g_mu);
    }
    elog("diag_log_stream_config(ENABLE, %d codes) -> %d; accepted %d", count, batch_rc, accepted);
    return accepted;
}

static int dci_start(void) {
    const char *paths[] = { "/vendor/lib64/libdiag.so", "libdiag.so", "/system/vendor/lib64/libdiag.so", NULL };
    for (int i = 0; paths[i] && !g_lib; i++) {
        g_lib = dlopen(paths[i], RTLD_NOW | RTLD_GLOBAL);
        if (g_lib) elog("dlopen %s ok", paths[i]);
        else elog("dlopen %s: %s", paths[i], dlerror());
    }
    if (!g_lib) return 2;
    p_init = (fn_init)dlsym(g_lib, "Diag_LSM_Init");
    p_deinit = (fn_deinit)dlsym(g_lib, "Diag_LSM_DeInit");
    p_reg = (fn_reg_client)dlsym(g_lib, "diag_register_dci_client");
    p_rel = (fn_rel_client)dlsym(g_lib, "diag_release_dci_client");
    p_stream = (fn_reg_stream)dlsym(g_lib, "diag_register_dci_stream");
    p_stream_proc = (fn_reg_stream_proc)dlsym(g_lib, "diag_register_dci_stream_proc");
    p_logcfg = (fn_log_cfg)dlsym(g_lib, "diag_log_stream_config");
    p_support = (fn_support)dlsym(g_lib, "diag_get_dci_support_list_proc");
    if (!p_init || !p_deinit || !p_reg || !p_rel || !p_logcfg || (!p_stream && !p_stream_proc)) { elog("required libdiag symbols missing"); return 3; }
    errno = 0;
    g_dci_init_rc = p_init(NULL);
    if (!g_dci_init_rc) {
        g_dci_init_errno = errno;
        elog("Diag_LSM_Init failed errno=%d %s", g_dci_init_errno, strerror(g_dci_init_errno));
        return 4;
    }
    g_inited = 1;
    elog("Diag_LSM_Init ok");
    int sigtype = SIGCONT; uint16_t list = 0xFFFF; int cid = -1;
    int r = g_dci_client_rc = p_reg(&cid, &list, g_proc, &sigtype);
    elog("diag_register_dci_client -> %d client=%d", r, cid);
    if (r != DCI_OK) return 5;
    g_client = cid;
    if (p_support) {
        uint16_t support = 0;
        int rc = g_dci_support_rc = p_support(g_proc, &support);
        if (rc == DCI_OK) elog("dci support list -> %d mask=0x%04x", rc, support);
        else elog("dci support list unavailable -> %d", rc);
    }
    g_dci_stream_per_client = p_stream_proc != NULL;
    r = g_dci_stream_rc = p_stream_proc ? p_stream_proc(cid, on_log, on_event) : p_stream(on_log, on_event);
    elog("register dci stream -> %d", r);
    if (r != DCI_OK) return 6;
    g_enabled_count = N_LIVE_BASE;
    return dci_enable_logs(g_codes, N_LIVE_BASE, &g_base_mask) ? 0 : 6;
}

static void dci_enable_optional_live(void) {
    g_neighbor_probes_enabled = 1;
    dci_enable_logs(k_neighbor_probe_codes, N_NEIGHBOR_PROBES, NULL);
    if (g_stop) return;
    int ca_rc = p_logcfg(g_client, 1, &g_nr_ca_state_code, 1);
    pthread_mutex_lock(&g_mu);
    g_nr_ca_subscribe_rc = ca_rc;
    g_nr_ca_subscribe_attempted = 1;
    pthread_mutex_unlock(&g_mu);
    elog("optional live B9A7 subscription -> %d", ca_rc);
}

enum live_mask_action { LIVE_MASK_NONE = 0, LIVE_MASK_ADD_DETAILS = 1, LIVE_MASK_ADD_OPTIONAL = 2 };

static unsigned live_mask_actions(int64_t elapsed_ms, unsigned long packets, unsigned requested) {
    unsigned ready = packets ? LIVE_MASK_ADD_DETAILS | LIVE_MASK_ADD_OPTIONAL :
            elapsed_ms >= 10000 ? LIVE_MASK_ADD_DETAILS : LIVE_MASK_NONE;
    return ready & ~requested;
}

static void dci_enable_live_details(void) {
    g_enabled_count = N_DEFAULT;
    dci_enable_logs(g_codes + N_LIVE_BASE, N_DEFAULT - N_LIVE_BASE, &g_details_mask);
}

static void dci_stop(void) {
    if (g_nr_ca_subscribe_attempted && g_nr_ca_subscribe_rc == DCI_OK && p_logcfg && g_client >= 0)
        p_logcfg(g_client, 0, &g_nr_ca_state_code, 1);
    if (g_neighbor_probes_enabled && p_logcfg && g_client >= 0)
        p_logcfg(g_client, 0, k_neighbor_probe_codes, N_NEIGHBOR_PROBES);
    if (g_enabled_count && p_logcfg && g_client >= 0) {
        int r = p_logcfg(g_client, 0, g_codes, g_enabled_count);
        elog("diag_log_stream_config(DISABLE) -> %d", r);
        g_enabled_count = 0;
    }
    if (g_client >= 0 && p_rel) { int id = g_client; int r = p_rel(&id); elog("diag_release_dci_client -> %d", r); g_client = -1; }
    if (g_inited && p_deinit) { boolean ok = p_deinit(); elog("Diag_LSM_DeInit -> %d", (int)ok); g_inited = 0; }
}

/* A stalled optional vendor subscription must not block file writes or stop ACKs. */
static pthread_mutex_t g_qmdl_mask_mu = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_qmdl_mask_cond = PTHREAD_COND_INITIALIZER;
static pthread_t g_qmdl_mask_thread;
static int g_qmdl_mask_started, g_qmdl_mask_wanted, g_qmdl_mask_exit;
static unsigned g_pending_live_mask;

static void *qmdl_mask_worker(void *unused) {
    (void)unused;
    int enabled = 0;
    pthread_mutex_lock(&g_qmdl_mask_mu);
    while (!g_qmdl_mask_exit) {
        while (!g_qmdl_mask_exit && enabled == g_qmdl_mask_wanted && g_pending_live_mask == LIVE_MASK_NONE)
            pthread_cond_wait(&g_qmdl_mask_cond, &g_qmdl_mask_mu);
        if (g_qmdl_mask_exit) break;
        if (enabled == g_qmdl_mask_wanted) {
            unsigned actions = g_pending_live_mask;
            g_pending_live_mask = LIVE_MASK_NONE;
            pthread_mutex_unlock(&g_qmdl_mask_mu);
            if ((actions & LIVE_MASK_ADD_DETAILS) && !g_stop) dci_enable_live_details();
            if ((actions & LIVE_MASK_ADD_OPTIONAL) && !g_stop) dci_enable_optional_live();
            pthread_mutex_lock(&g_qmdl_mask_mu);
            continue;
        }
        enabled = g_qmdl_mask_wanted;
        pthread_mutex_unlock(&g_qmdl_mask_mu);
        int result = p_logcfg(g_client, enabled, k_qmdl_codes, N_QMDL_CODES);
        if (enabled && result != DCI_OK) {
            for (int i = 0; i < N_QMDL_CODES; i++) {
                pthread_mutex_lock(&g_qmdl_mask_mu);
                int wanted = g_qmdl_mask_wanted && !g_qmdl_mask_exit;
                pthread_mutex_unlock(&g_qmdl_mask_mu);
                if (!wanted) break;
                int rc = p_logcfg(g_client, 1, &k_qmdl_codes[i], 1);
                if (rc != DCI_OK) elog("QMDL code 0x%04X rejected (%d)", k_qmdl_codes[i], rc);
            }
        }
        pthread_mutex_lock(&g_qmdl_mask_mu);
    }
    pthread_mutex_unlock(&g_qmdl_mask_mu);
    return NULL;
}

static int request_mask_work(int qmdl_enabled, unsigned live_actions) {
    if (!g_qmdl_mask_started) {
        int rc = pthread_create(&g_qmdl_mask_thread, NULL, qmdl_mask_worker, NULL);
        if (rc != 0) {
            elog("QMDL optional mask worker unavailable (%d)", rc);
            return 0;
        }
        g_qmdl_mask_started = 1;
    }
    pthread_mutex_lock(&g_qmdl_mask_mu);
    if (live_actions != LIVE_MASK_NONE) g_pending_live_mask |= live_actions;
    else g_qmdl_mask_wanted = qmdl_enabled;
    pthread_cond_signal(&g_qmdl_mask_cond);
    pthread_mutex_unlock(&g_qmdl_mask_mu);
    return 1;
}

static void qmdl_request_mask(int enabled) {
    request_mask_work(enabled, LIVE_MASK_NONE);
}

static void qmdl_stop_mask_worker(void) {
    if (!g_qmdl_mask_started) return;
    pthread_mutex_lock(&g_qmdl_mask_mu);
    g_qmdl_mask_exit = 1;
    pthread_cond_signal(&g_qmdl_mask_cond);
    pthread_mutex_unlock(&g_qmdl_mask_mu);
    pthread_join(g_qmdl_mask_thread, NULL);
    /* Client release removes any masks still enabled. */
}

static void qmdl_start(const char *path) {
    if (!path || g_qmdl_fd >= 0) return;
    pthread_mutex_lock(&g_mu);
    g_qmdl_packets = g_qmdl_bytes = 0;
    g_qmdl_pending_bytes = g_qmdl_pending_packets = 0;
    g_qmdl_limit = g_qmdl_error = 0;
    int fd = open(path, O_WRONLY | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    struct stat info;
    if (fd < 0 || fstat(fd, &info) != 0) g_qmdl_error = errno;
    else if (!S_ISREG(info.st_mode) || info.st_size != 0) g_qmdl_error = EINVAL;
    if (g_qmdl_error && fd >= 0) close(fd);
    g_qmdl_fd = g_qmdl_error ? -1 : fd;
    qmdl_status_locked(g_qmdl_error ? "error" : "recording");
    pthread_mutex_unlock(&g_mu);
    if (g_qmdl_fd >= 0) qmdl_request_mask(1);
}

static void qmdl_stop(int requested) {
    pthread_mutex_lock(&g_mu);
    int fd = g_qmdl_fd;
    g_qmdl_fd = -1;
    pthread_mutex_unlock(&g_mu);
    if (fd < 0 && !requested) return;
    if (fd >= 0) {
        qmdl_flush(fd);
        if (close(fd) != 0 && !g_qmdl_error) g_qmdl_error = errno;
    }
    pthread_mutex_lock(&g_mu);
    qmdl_status_locked(g_qmdl_error ? "error" : g_qmdl_limit ? "limit" : "stopped");
    pthread_mutex_unlock(&g_mu);
    if (fd >= 0 && requested) qmdl_request_mask(0);
}

int main(int argc, char **argv) {
    const char *qmdl_path = argc == 2 ? argv[1] : NULL;
    setvbuf(stdout, NULL, _IOLBF, 0);
    signal(SIGPIPE, SIG_IGN);
    struct sigaction stop = { .sa_handler = on_sig };
    sigemptyset(&stop.sa_mask);
    sigaction(SIGTERM, &stop, NULL);
    sigaction(SIGINT, &stop, NULL);
    sigaction(SIGHUP, &stop, NULL);
    signal(SIGALRM, SIG_DFL);

    g_start_mono = mono_ms();
    if (geteuid() != 0) {
        emit_status_locked("denied", 0);
        return 1;
    }

    for (int i = 0; i < N_DEFAULT; i++) g_codes[i] = k_default_codes[i].code;
    emit_status_locked("authorized", 0);
    emit_ims_provider("mediatek", "unsupported", "false", "no_backend");
    diag_q_init(&g_dq);
    diag_q_init(&g_dq_lo);
    if (pthread_create(&g_dq_th, NULL, decode_worker, NULL) == 0) g_dq_started = 1;
    alarm(12);
    int rc = dci_start();
    alarm(0);
    pthread_mutex_lock(&g_mu);
    emit_status_locked(rc == 0 ? "running" : "error", rc);
    if (rc == 0) emit_ims_provider("qualcomm_diag", "subscribed", NULL, "");
    pthread_mutex_unlock(&g_mu);
    if (rc == 0 && !g_stop) logcat_start();
    int64_t connected_at = mono_ms(), last_snapshot = connected_at;
    unsigned live_requested = LIVE_MASK_NONE;
    while (!g_stop && rc == 0) {
        struct pollfd input = { .fd = STDIN_FILENO, .events = POLLIN };
        int ready = poll(&input, 1, 250);
        if (ready > 0 && (input.revents & POLLIN)) {
            char cmd[16];
            ssize_t n = read(STDIN_FILENO, cmd, sizeof(cmd));
            if (n <= 0) break;
            for (ssize_t i = 0; i < n; i++) {
                if (cmd[i] == 'Q') qmdl_start(qmdl_path);
                else if (cmd[i] == 'S') qmdl_stop(1);
            }
        } else if (ready > 0 && input.revents != 0) break;
        if (ready < 0 && errno != EINTR) break;
        int64_t now = mono_ms();
        if (now - last_snapshot < 1000) continue;
        pthread_mutex_lock(&g_mu);
        if (g_qmdl_fd >= 0 && !g_qmdl_error) qmdl_flush(g_qmdl_fd);
        int finish_qmdl = g_qmdl_fd >= 0 && (g_qmdl_limit || g_qmdl_error);
        unsigned actions = live_mask_actions(now - connected_at, g_total_pkts, live_requested);
        if (actions & LIVE_MASK_ADD_DETAILS) g_live_details_before_first_packet = g_total_pkts == 0;
        if (g_qmdl_fd >= 0 && !finish_qmdl) qmdl_status_locked("recording");
        emit_serving_locked(&g_lte);
        emit_serving_locked(&g_nr);
        emit_status_locked("running", 0);
        uc_tick(ev_ms());
        if (g_lc_start && now_ms() - g_lc_start >= 20000 && !g_lc_fmt && !g_reg.lc_imsregi) {
            emit_ims_provider("samsung_logcat", "unavailable", "false", "no_samsung_ims_tags");
            g_lc_start = 0;
        } else if (g_lc_fmt || g_reg.lc_imsregi) {
            emit_ims_provider("samsung_logcat", "available", "true", g_lc_fmt ? "sipmsg" : "imsregi");
        }
        if (g_diag_ims_pkts) emit_ims_provider("qualcomm_diag", "available", "true", "0x156E");
        pthread_mutex_unlock(&g_mu);
        if (finish_qmdl) qmdl_stop(1);
        if (actions != LIVE_MASK_NONE && request_mask_work(0, actions)) live_requested |= actions;
        last_snapshot = now;
    }

    // If vendor cleanup blocks, the process still exits promptly.
    g_stop = 1;
    alarm(3);
    qmdl_stop(0);
    pthread_mutex_lock(&g_dq_mu);
    pthread_cond_signal(&g_dq_cv);
    pthread_mutex_unlock(&g_dq_mu);
    if (g_dq_started) pthread_join(g_dq_th, NULL);
    logcat_stop();
    qmdl_stop_mask_worker();
    dci_stop();
    if (g_lib) dlclose(g_lib);
    return rc == 0 ? 0 : 10 + rc;
}
