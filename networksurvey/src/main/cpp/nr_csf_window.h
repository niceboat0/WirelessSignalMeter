#ifndef NR_CSF_WINDOW_H
#define NR_CSF_WINDOW_H
/*
 * 0xB8A7 NR CSF (RI / CQI) one-second window.
 * Each component carrier keeps its own sum, rank histogram and report counts.
 * A window that contains several carriers emits one result per carrier; the
 * average is never labeled with whichever carrier happened to report last.
 * CQI 0 is out of range and is not averaged. An L1-RSRP-only report has no RI/CQI.
 */
#include <stdint.h>
#include <string.h>

#define NR_CSF_CCS 8

enum {
    NR_CSF_OK = 0,
    NR_CSF_SHORT = 1,
    NR_CSF_VERSION = 2,
    NR_CSF_LENGTH = 3,
    NR_CSF_INCOMPLETE = 4,
};

struct nr_csf_packet {
    uint32_t ver;
    int rel_maj, rel_min;
};

struct nr_csf_result {
    int cc, n, rank, cqi_min, cqi_max, reports, rsrp_only;
    int rank_hist[4];
    double cqi_avg;
};

struct nr_csf_cc {
    int n, cqi_sum, cqi_min, cqi_max, reports, rsrp_only;
    int rank[5];
};

struct nr_csf_window {
    int64_t t0;
    struct nr_csf_cc cc[NR_CSF_CCS];
};

struct nr_csf_layout {
    uint32_t ver;
    int rc_off, rec_start, reps_at, rep_bytes;
};

/* Payload includes the 4-byte version word. Offsets match the checked B8A7 table. */
static const struct nr_csf_layout NR_CSF_LAYOUTS[] = {
    { 0x20000, 120, 16, 8, 40 }, { 0x20001, 120, 16, 8, 60 }, { 0x20002, 120, 16, 12, 60 }, { 0x20003, 120, 16, 12, 60 },
    { 0x30000, 56, 8, 8, 60 }, { 0x30001, 56, 8, 12, 0 }, { 0x30002, 56, 8, 12, 0 }, { 0x30003, 56, 8, 12, 0 },
    { 0x30004, 56, 8, 12, 60 }, { 0x30005, 56, 8, 16, 60 }, { 0x30006, 56, 8, 16, 60 }, { 0x30007, 56, 8, 16, 60 },
};

static inline uint32_t nr_csf_u32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static inline uint32_t nr_csf_bits(const uint8_t *buf, int start, int n) {
    uint32_t v = 0;
    for (int i = 0; i < n; i++) {
        int b = start + i;
        if ((buf[b >> 3] >> (b & 7)) & 1) v |= (1u << i);
    }
    return v;
}

static inline int nr_csf_fits(int off, int bits, int nbytes) { return off + bits <= nbytes * 8; }

static inline void nr_csf_reset(struct nr_csf_window *w) { memset(w, 0, sizeof(*w)); }

static inline void nr_csf_add(struct nr_csf_window *w, int cc, int rsrp_only, int ri, int cqi) {
    if (cc < 0 || cc >= NR_CSF_CCS) return;
    struct nr_csf_cc *a = &w->cc[cc];
    a->reports++;
    if (rsrp_only) { a->rsrp_only++; return; }
    if (cqi == 0) return;
    if (!a->n || cqi < a->cqi_min) a->cqi_min = cqi;
    if (cqi > a->cqi_max) a->cqi_max = cqi;
    a->n++;
    a->cqi_sum += cqi;
    if (ri >= 0 && ri <= 3) a->rank[ri + 1]++;
}

static inline int nr_csf_any(const struct nr_csf_window *w) {
    for (int cc = 0; cc < NR_CSF_CCS; cc++) if (w->cc[cc].n) return 1;
    return 0;
}

/* Opens the window on the first accepted packet. Due once a full second has
   passed and at least one carrier has a CQI. An empty second does not close it. */
static inline int nr_csf_due(struct nr_csf_window *w, int64_t now) {
    if (!w->t0) { w->t0 = now; return 0; }
    if (now < w->t0 || now - w->t0 < 1000) return 0;
    return nr_csf_any(w);
}

static inline int nr_csf_flush(struct nr_csf_window *w, struct nr_csf_result *out, int max, int64_t now) {
    int count = 0;
    for (int cc = 0; cc < NR_CSF_CCS && count < max; cc++) {
        struct nr_csf_cc *a = &w->cc[cc];
        if (!a->n) continue;
        struct nr_csf_result *r = &out[count++];
        r->cc = cc;
        r->n = a->n;
        r->cqi_min = a->cqi_min;
        r->cqi_max = a->cqi_max;
        r->cqi_avg = (double)a->cqi_sum / (double)a->n;
        r->reports = a->reports;
        r->rsrp_only = a->rsrp_only;
        int mode = 1;
        for (int rank = 2; rank <= 4; rank++) if (a->rank[rank] > a->rank[mode]) mode = rank;
        r->rank = mode;
        for (int i = 0; i < 4; i++) r->rank_hist[i] = a->rank[i + 1];
    }
    nr_csf_reset(w);
    w->t0 = now;
    return count;
}

/* Validate the whole packet before adding a sample. A rejected frame leaves the window unchanged. */
static inline int nr_csf_feed(struct nr_csf_window *w, const uint8_t *b, int n, struct nr_csf_packet *info) {
    if (n < 4) return NR_CSF_SHORT;
    int rel_min = b[0] | (b[1] << 8);
    int rel_maj = b[2] | (b[3] << 8);
    uint32_t ver = ((uint32_t)rel_maj << 16) | (uint32_t)rel_min;
    if (info) {
        info->ver = ver;
        info->rel_maj = rel_maj;
        info->rel_min = rel_min;
    }
    const struct nr_csf_layout *L = NULL;
    for (size_t i = 0; i < sizeof(NR_CSF_LAYOUTS) / sizeof(NR_CSF_LAYOUTS[0]); i++)
        if (NR_CSF_LAYOUTS[i].ver == ver) L = &NR_CSF_LAYOUTS[i];
    if (!L) return NR_CSF_VERSION;
    if (!nr_csf_fits(L->rc_off, 8, n)) return NR_CSF_INCOMPLETE;
    int recs = (int)nr_csf_bits(b, L->rc_off, 8), pos = L->rec_start;
    if (L->rep_bytes) {
        int p = pos;
        for (int r = 0; r < recs; r++) {
            if (p + L->reps_at > n) return NR_CSF_INCOMPLETE;
            uint32_t k = nr_csf_u32(b + p + 4);
            p += L->reps_at;
            if (k > (uint32_t)((n - p) / L->rep_bytes)) return NR_CSF_INCOMPLETE;
            p += (int)k * L->rep_bytes;
        }
        if (p != n) return NR_CSF_LENGTH;
    } else if (recs > 1) recs = 1;
    struct nr_csf_window added = *w;
    for (int r = 0; r < recs; r++) {
        if (pos + L->reps_at > n) return NR_CSF_INCOMPLETE;
        uint32_t k = nr_csf_u32(b + pos + 4);
        pos += L->reps_at;
        if (!L->rep_bytes && k > 1) k = 1;
        for (uint32_t i = 0; i < k; i++, pos += L->rep_bytes) {
            if (pos + 10 > n) return NR_CSF_INCOMPLETE;
            const uint8_t *rp = b + pos;
            int rsrp_only = (nr_csf_bits(rp, 16, 8) & 32) != 0;
            int cc = (int)nr_csf_bits(rp, 4, 4);
            int ri = (int)nr_csf_bits(rp, 67, 2);
            int cqi = (int)nr_csf_bits(rp, 69, 4);
            nr_csf_add(&added, cc, rsrp_only, ri, cqi);
        }
    }
    *w = added;
    return NR_CSF_OK;
}

#endif
