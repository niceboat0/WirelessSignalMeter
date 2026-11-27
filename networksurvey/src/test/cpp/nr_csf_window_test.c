#include "../../main/cpp/nr_csf_window.h"
#include <stdio.h>
#include <string.h>

#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); return 1; } } while (0)

static int near(double v, double expect) {
    double d = v - expect;
    if (d < 0) d = -d;
    return d < 0.001;
}

static void put_bits(uint8_t *p, int off, int bits, uint32_t v) {
    for (int i = 0; i < bits; i++) {
        int b = off + i;
        if ((v >> i) & 1) p[b >> 3] = (uint8_t)(p[b >> 3] | (1u << (b & 7)));
    }
}

struct report { int cc, ri, cqi, rsrp_only; };

/* v3.5: record count at bit 56, 16-byte record header, 60-byte reports. */
static int build_v35(uint8_t *buf, int cap, const struct report *s, int ns) {
    int need = 24 + ns * 60;
    if (need > cap) return -1;
    memset(buf, 0, (size_t)need);
    buf[0] = 5;
    buf[2] = 3;
    put_bits(buf, 56, 8, 1);
    buf[12] = (uint8_t)ns;
    for (int i = 0; i < ns; i++) {
        uint8_t *rp = buf + 24 + i * 60;
        put_bits(rp, 4, 4, (uint32_t)s[i].cc);
        put_bits(rp, 67, 2, (uint32_t)s[i].ri);
        put_bits(rp, 69, 4, (uint32_t)s[i].cqi);
        if (s[i].rsrp_only) put_bits(rp, 21, 1, 1);
    }
    return need;
}

static const struct nr_csf_result *find_cc(const struct nr_csf_result *out, int n, int cc) {
    for (int i = 0; i < n; i++) if (out[i].cc == cc) return &out[i];
    return NULL;
}

static int test_carriers_stay_separate(void) {
    struct nr_csf_window w;
    struct nr_csf_packet info;
    struct nr_csf_result out[NR_CSF_CCS];
    uint8_t frame[2048];
    /* 12 and 12 reports. Mixed they would average 10.875 and, on a tie, rank 1,
       then that mix would be labeled with whichever carrier was last. */
    struct report mixed[24];
    for (int i = 0; i < 12; i++) {
        mixed[i] = (struct report){ 0, 0, (i < 8) ? 9 : 11, 0 };
        mixed[12 + i] = (struct report){ 1, 3, (i < 11) ? 12 : 13, 0 };
    }
    int n = build_v35(frame, sizeof frame, mixed, 24);
    CHECK(n > 0);
    nr_csf_reset(&w);
    CHECK(nr_csf_feed(&w, frame, n, &info) == NR_CSF_OK);
    CHECK(info.ver == 0x30005 && info.rel_maj == 3 && info.rel_min == 5);
    CHECK(!nr_csf_due(&w, 5000));
    CHECK(!nr_csf_due(&w, 5999));
    CHECK(nr_csf_due(&w, 6000));
    int got = nr_csf_flush(&w, out, NR_CSF_CCS, 6000);
    CHECK(got == 2);
    const struct nr_csf_result *cc0 = find_cc(out, got, 0);
    const struct nr_csf_result *cc1 = find_cc(out, got, 1);
    CHECK(cc0 && cc1);
    CHECK(cc0->n == 12 && near(cc0->cqi_avg, 116.0 / 12.0) && cc0->rank == 1);
    CHECK(cc0->cqi_min == 9 && cc0->cqi_max == 11 && cc0->rank_hist[0] == 12);
    CHECK(cc1->n == 12 && near(cc1->cqi_avg, 145.0 / 12.0) && cc1->rank == 4);
    CHECK(cc1->rank_hist[3] == 12 && cc1->reports == 12 && cc1->rsrp_only == 0);
    CHECK(!near(cc0->cqi_avg, 261.0 / 24.0) && !near(cc1->cqi_avg, 261.0 / 24.0));
    CHECK(cc1->rank != 1);
    return 0;
}

static int test_single_carrier_and_next_window(void) {
    struct nr_csf_window w;
    struct nr_csf_result out[NR_CSF_CCS];
    uint8_t frame[512];
    struct report one[3] = { { 0, 1, 10, 0 }, { 0, 1, 10, 0 }, { 0, 2, 13, 0 } };
    int n = build_v35(frame, sizeof frame, one, 3);
    nr_csf_reset(&w);
    CHECK(nr_csf_feed(&w, frame, n, NULL) == NR_CSF_OK);
    CHECK(!nr_csf_due(&w, 100));
    CHECK(nr_csf_due(&w, 1100));
    int got = nr_csf_flush(&w, out, NR_CSF_CCS, 1100);
    CHECK(got == 1 && out[0].cc == 0 && out[0].n == 3);
    CHECK(near(out[0].cqi_avg, 11.0) && out[0].rank == 2 && out[0].rank_hist[1] == 2 && out[0].rank_hist[2] == 1);

    struct report next[1] = { { 1, 0, 8, 0 } };
    n = build_v35(frame, sizeof frame, next, 1);
    CHECK(nr_csf_feed(&w, frame, n, NULL) == NR_CSF_OK);
    CHECK(!nr_csf_due(&w, 2099));
    CHECK(nr_csf_due(&w, 2100));
    got = nr_csf_flush(&w, out, NR_CSF_CCS, 2100);
    CHECK(got == 1 && out[0].cc == 1 && out[0].n == 1 && out[0].cqi_avg == 8);
    return 0;
}

static int test_skipped_reports_and_rejection(void) {
    struct nr_csf_window w;
    struct nr_csf_result out[NR_CSF_CCS];
    uint8_t frame[1024];
    struct report rows[4] = {
        { 0, 1, 10, 0 },
        { 0, 1, 0, 0 },
        { 0, 1, 12, 1 },
        { 9, 1, 15, 0 },
    };
    int n = build_v35(frame, sizeof frame, rows, 4);
    nr_csf_reset(&w);
    CHECK(nr_csf_feed(&w, frame, n, NULL) == NR_CSF_OK);
    CHECK(nr_csf_due(&w, 1000) == 0);
    CHECK(nr_csf_due(&w, 2000));
    int got = nr_csf_flush(&w, out, NR_CSF_CCS, 2000);
    CHECK(got == 1 && out[0].cc == 0 && out[0].n == 1 && out[0].cqi_avg == 10);
    CHECK(out[0].reports == 3 && out[0].rsrp_only == 1);

    uint8_t badver[8] = { 9, 0, 9, 0, 0, 0, 0, 0 };
    struct nr_csf_packet info;
    CHECK(nr_csf_feed(&w, badver, sizeof badver, &info) == NR_CSF_VERSION);
    CHECK(info.ver == 0x90009);
    CHECK(!nr_csf_any(&w));

    n = build_v35(frame, sizeof frame, rows, 1);
    frame[n] = 1;
    CHECK(nr_csf_feed(&w, frame, n + 1, NULL) == NR_CSF_LENGTH);
    CHECK(!nr_csf_any(&w));
    CHECK(nr_csf_feed(&w, frame, 2, NULL) == NR_CSF_SHORT);
    return 0;
}

int main(void) {
    int result = test_carriers_stay_separate();
    if (result) return result;
    result = test_single_carrier_and_next_window();
    if (result) return result;
    result = test_skipped_reports_and_rejection();
    if (result) return result;
    printf("nr_csf_window_test ok\n");
    return 0;
}
