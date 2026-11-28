/* 2.4: signaling page decoder (UPER, X.691 unaligned) driven by tables generated from pycrate's
   NR RRC (38.331 Rel-18) and LTE RRC (36.331) ASN.1 (tools/gen_rrc_schema.py -> rrc_schema.h).
   Renders an indented field tree; identifiers (TMSI/IMSI/I-RNTI/random UE id/location/NAS containers ...)
   are masked in display mode. Canonical mode (no conversion, no masking) exists only for host-side
   comparison against pycrate. */
/* Adapted from niceboat0/diag-research diagcap/native at a338d57c. */
#include "rrc_schema.h"

enum sd_sib1_field {
    SD_Q_RX_LEV_MIN, SD_POINT_A_OFFSET, SD_UL_POINT_A, SD_PRACH_CONFIG,
    SD_DL_BWP_RIV, SD_PRACH_ROOT, SD_GRID_OFFSET, SD_GRID_RBS, SD_GRID_SCS,
    SD_SSB_PERIOD_MS, SD_SSB_POWER_DBM, SD_TDD_SCS, SD_UL_BWP_RIV,
    SD_DL_CP_TYPE, SD_UL_CP_TYPE, SD_SIB1_FIELDS
};
struct sd_tdd_pattern {
    int period_us, dl_slots, dl_symbols, ul_slots, ul_symbols;
};
enum sd_access_field {
    SD_ACCESS_P_MAX, SD_ACCESS_Q_RX_MIN, SD_ACCESS_PRACH_INDEX, SD_ACCESS_ROOT,
    SD_ACCESS_TARGET_DBM, SD_ACCESS_RAMP_DB, SD_ACCESS_MAX_TX, SD_ACCESS_RAR_WINDOW,
    SD_ACCESS_PREAMBLES, SD_ACCESS_UL_CP_TYPE, SD_ACCESS_FIELDS
};
enum sd_nr_frequency_field { SD_NR_PCI, SD_NR_SSB, SD_NR_DL_A, SD_NR_UL_A, SD_NR_FREQ_FIELDS };
enum { SD_MEAS_LIMIT = 64, SD_PLMN_LIMIT = 12 };
enum sd_meas_list { SD_MEAS_NONE, SD_REMOVE_OBJECT, SD_ADD_OBJECT, SD_REMOVE_REPORT, SD_ADD_REPORT, SD_REMOVE_ID, SD_ADD_ID };
enum sd_meas_quantity { SD_Q_NONE, SD_Q_RSRP, SD_Q_RSRQ, SD_Q_SINR };
struct sd_meas_object { int rat, ssb, csi, earfcn; };
struct sd_meas_link { int object_id, report_id; };
/* One reportConfigToAddModList entry: the event and its trigger parameters. */
struct sd_meas_report {
    char event[20];          /* eventA1..eventB2, eventC1/C2, periodical, "" */
    int quantity;            /* SD_Q_* */
    int threshold, has_threshold;
    int hysteresis, has_hysteresis;
    int ttt_ms, has_ttt;
    int offset_db, has_offset;   /* a3-Offset / a6-Offset */
};
struct sd_meas_config {
    uint64_t removed_objects, removed_ids, removed_reports, objects_present, ids_present, reports_present;
    struct sd_meas_object objects[SD_MEAS_LIMIT + 1];
    struct sd_meas_link links[SD_MEAS_LIMIT + 1];
    struct sd_meas_report reports[SD_MEAS_LIMIT + 1];
};
/* Broadcast reselection parameters (SIB3/SIB5 for LTE, SIB2/SIB4/SIB5 for NR). */
struct sd_reselection {
    int q_hyst, has_q_hyst;                  /* dB (ENUMERATED name) */
    int s_intra_search, has_s_intra;         /* dB (range x2) */
    int thresh_serving_low, has_thresh_serving_low;   /* dB (x2) */
    int thresh_x_high, has_thresh_x_high;    /* dB (x2) */
    int thresh_x_low, has_thresh_x_low;      /* dB (x2) */
    int t_reselect, has_t_reselect;          /* seconds */
    int priority, has_priority;              /* 0..7 */
};
struct sd_sib_info {
    char name[8];       /* last SIB subtree entered ("sib3"/"sib5") */
    unsigned mask;      /* bit n set for sibN seen */
    struct sd_reselection resel;
};
struct sd_summary {
    const char *message;
    char nas[96];
    unsigned sib1_present;
    int sib1[SD_SIB1_FIELDS];
    int sib1_dl_grids;
    struct sd_tdd_pattern tdd[2];
    int lte_tdd_assignment_plus_one, lte_tdd_special_plus_one;
    unsigned access_present;
    int access[SD_ACCESS_FIELDS];
    unsigned nr_frequency_present;
    int nr_frequency[SD_NR_FREQ_FIELDS];
    struct sd_meas_config meas;
    int meas_complete;
    struct sd_sib_info sib;
    int lte_pcell_sinr_plus_one;
    int suspended;
    struct { int mcc, mnc, mnc_digits; } plmns[SD_PLMN_LIMIT];
    int plmn_count, plmn_invalid, selected_plmn;
    /* 1: reconfigurationWithSync, 2: mobilityControlInfo. A complete message alone never sets these. */
    unsigned mobility_flags;
    int target_pci_plus_one;
    int reest_handover_failure;
};

static void sd_sib1_value(struct sd_summary *summary, const char *label, long long value) {
    static const char *const fields[SD_SIB1_FIELDS] = {
        [SD_Q_RX_LEV_MIN] = "q-RxLevMin",
        [SD_POINT_A_OFFSET] = "offsetToPointA",
        [SD_PRACH_CONFIG] = "prach-ConfigurationIndex",
        [SD_SSB_POWER_DBM] = "ss-PBCH-BlockPower",
    };
    for (size_t i = 0; i < sizeof(fields) / sizeof(fields[0]); i++) {
        unsigned bit = 1u << i;
        if (fields[i] && !(summary->sib1_present & bit) && strcmp(label, fields[i]) == 0) {
            summary->sib1_present |= bit;
            summary->sib1[i] = (int)value;
            return;
        }
    }
}

static void sd_set_access(struct sd_summary *summary, enum sd_access_field field, int value) {
    if (summary->access_present & (1u << field)) return;
    summary->access_present |= 1u << field;
    summary->access[field] = value;
}

struct sd_br { const uint8_t *p; long nbits, pos; int err; };
struct sd_out { char *b; int n, cap, trunc; };
struct sd {
    struct sd_out *o;
    int canon;          /* 1: canonical host-test output */
    int show_private;
    int nr;             /* RAT of the schema in use */
    int rat_type;       /* last decoded rat-Type enum index, -1 none */
    char rat_name[16];
    int depth_max;
    int dl, naslist;    /* direction (for NAS rendering), inside dedicatedNAS-MessageList */
    long nodes;         /* safety budget */
    struct sd_summary *summary;
    int nested_error;
    int in_meas_config, meas_parent_depth, meas_object_depth, meas_item;
    int in_sib;      /* inside a sibN subtree of SystemInformation */
    int lte_pcell_depth;
    int in_sib1_plmns;
    int in_initial_bwp;
    int in_prach_root;
    int in_sib1_dl, in_sib1_ul, in_sib1_grid;
    int in_tdd_pattern;
    int in_lte_sib2;
    int in_lte_tdd;
    int in_spcell_common, nr_frequency_direction;
    enum sd_meas_list meas_list;
    struct sd_meas_object meas_object;
    struct sd_meas_link meas_link;
    struct sd_meas_report meas_report;
    int meas_report_id;
};

static enum sd_meas_list sd_meas_list(const char *label) {
    if (!strcmp(label, "measObjectToRemoveList") || !strcmp(label, "measObjectToRemoveListExt-r13")) return SD_REMOVE_OBJECT;
    if (!strcmp(label, "measObjectToAddModList") || !strcmp(label, "measObjectToAddModListExt-r13")) return SD_ADD_OBJECT;
    if (!strcmp(label, "reportConfigToRemoveList")) return SD_REMOVE_REPORT;
    if (!strcmp(label, "reportConfigToAddModList")) return SD_ADD_REPORT;
    if (!strcmp(label, "measIdToRemoveList") || !strcmp(label, "measIdToRemoveListExt-r12")) return SD_REMOVE_ID;
    if (!strcmp(label, "measIdToAddModList") || !strcmp(label, "measIdToAddModListExt-r12")) return SD_ADD_ID;
    return SD_MEAS_NONE;
}

/* ENUMERATED member names ("dB2", "ms320", "dB-30") -> integer; -1 when the
   name does not carry the expected prefix. timeToTrigger/hysteresis/offset
   values are definitionally the numbers in these names. */
static int sd_enum_num(const char *nm, const char *prefix) {
    size_t pl = strlen(prefix);
    if (strncmp(nm, prefix, pl)) return -1;
    const char *p = nm + pl;
    int sign = 1, v = 0, digits = 0;
    if (*p == '-') { sign = -1; p++; }
    for (; *p >= '0' && *p <= '9'; p++) { v = v * 10 + (*p - '0'); digits++; }
    return digits ? sign * v : -1;
}


static void sd_meas_integer(struct sd *d, const char *label, int depth, long long value) {
    struct sd_meas_config *m = &d->summary->meas;
    if (d->meas_list == SD_ADD_OBJECT && depth == d->meas_object_depth + 1) {
        struct sd_meas_object *o = &d->meas_object;
        if (o->rat == 1 && !strcmp(label, "ssbFrequency")) o->ssb = (int)value;
        else if (o->rat == 1 && !strcmp(label, "refFreqCSI-RS")) o->csi = (int)value;
        else if (o->rat == 1 && !strcmp(label, "carrierFreq-r15")) o->ssb = (int)value;
        else if (o->rat == 2 && (!strcmp(label, "carrierFreq") ||
            !strcmp(label, "carrierFreq-v9e0") || !strcmp(label, "carrierFreq-r13"))) o->earfcn = (int)value;
        return;
    }
    /* reportConfigToAddModList: the config id, then the threshold area value
       (RSRP/RSRQ/SINR range; 0 is a valid threshold, unlike list ids). */
    if (d->meas_list == SD_ADD_REPORT) {
        struct sd_meas_report *rp = &d->meas_report;
        if (!strcmp(label, "reportConfigId") || !strcmp(label, "reportConfigId-r13")) {
            if (depth <= d->meas_parent_depth + 3 && value >= 1 && value <= SD_MEAS_LIMIT)
                d->meas_report_id = (int)value;
        } else if (!strncmp(label, "threshold-", 10) && value >= 0 && value <= 255) {
            rp->threshold = (int)value;
            rp->has_threshold = 1;
        }
        return;
    }
    if (depth > d->meas_parent_depth + 2 || value < 1 || value > SD_MEAS_LIMIT) return;
    uint64_t bit = UINT64_C(1) << (value - 1);
    switch (d->meas_list) {
    case SD_REMOVE_OBJECT: m->removed_objects |= bit; break;
    case SD_REMOVE_REPORT: m->removed_reports |= bit; break;
    case SD_REMOVE_ID: m->removed_ids |= bit; break;
    case SD_ADD_OBJECT:
        if (!strcmp(label, "measObjectId") || !strcmp(label, "measObjectId-r13")) d->meas_item = (int)value;
        break;
    case SD_ADD_ID:
        if (!strcmp(label, "measId") || !strcmp(label, "measId-v1250")) d->meas_item = (int)value;
        else if (!strcmp(label, "measObjectId") || !strcmp(label, "measObjectId-r12")) d->meas_link.object_id = (int)value;
        else if (!strcmp(label, "reportConfigId") || !strcmp(label, "reportConfigId-r12")) d->meas_link.report_id = (int)value;
        break;
    default: break;
    }
}

static uint64_t sd_get(struct sd_br *r, int n) {
    if (n <= 0) return 0;
    if (n > 64 || r->pos + n > r->nbits) { r->err = 1; r->pos = r->nbits; return 0; }
    uint64_t v = 0;
    for (int i = 0; i < n; i++, r->pos++) v = (v << 1) | ((r->p[r->pos >> 3] >> (7 - (r->pos & 7))) & 1);
    return v;
}

static int sd_decimal_digits(struct sd_br *r, int digits) {
    int value = 0;
    for (int i = 0; i < digits; i++) {
        int digit = (int)sd_get(r, 4);
        if (digit > 9) { r->err = 1; return -1; }
        value = 10 * value + digit;
    }
    return value;
}

/* PLMN-Identity has an optional MCC followed by a two- or three-digit MNC. */
static void sd_record_plmn(struct sd_summary *summary, struct sd_br r) {
    int index = summary->plmn_count;
    if (index >= SD_PLMN_LIMIT) { summary->plmn_invalid = 1; return; }
    int has_mcc = (int)sd_get(&r, 1);
    int mcc = has_mcc ? sd_decimal_digits(&r, 3) : index ? summary->plmns[index - 1].mcc : -1;
    int mnc_digits = 2 + (int)sd_get(&r, 1);
    int mnc = sd_decimal_digits(&r, mnc_digits);
    if (r.err || mcc < 0 || mnc < 0) { summary->plmn_invalid = 1; return; }
    summary->plmns[index].mcc = mcc;
    summary->plmns[index].mnc = mnc;
    summary->plmns[index].mnc_digits = mnc_digits;
    summary->plmn_count++;
}
static int sd_bits_for(uint64_t range) {   /* bits to encode 0..range-1 */
    if (!range) return 0;
    int b = 0; uint64_t x = range - 1;
    while (x) { b++; x >>= 1; }
    return b;
}
static long sd_len(struct sd_br *r) {       /* unconstrained length determinant (no fragmentation) */
    long v = (long)sd_get(r, 8);
    if (!(v & 0x80)) return v;
    if ((v & 0xc0) == 0x80) return ((v & 0x3f) << 8) | (long)sd_get(r, 8);
    r->err = 1; return 0;
}
static long sd_nsnn(struct sd_br *r) {      /* normally small non-negative whole number */
    if (!sd_get(r, 1)) return (long)sd_get(r, 6);
    long l = sd_len(r); if (l > 8) { r->err = 1; return 0; }
    uint64_t v = sd_get(r, (int)l * 8);
    if (v > 0x7fffffffU) { r->err = 1; return 0; }
    return (long)v;
}
static void sd_put(struct sd_out *o, const char *fmt, ...) {
    if (o->trunc) return;
    va_list ap; va_start(ap, fmt);
    int w = vsnprintf(o->b + o->n, (size_t)(o->cap - o->n), fmt, ap);
    va_end(ap);
    if (w < 0 || o->n + w >= o->cap - 32) { o->trunc = 1; o->b[o->n] = 0; return; }
    o->n += w;
}
static void sd_ind(struct sd_out *o, int depth) { for (int i = 0; i < depth && !o->trunc; i++) sd_put(o, "  "); }
static const char *sd_s(uint32_t off) { return k_rs_str + off; }

/* field names masked in the default signaling detail tree */
static int sd_masked(const char *name) {
    static const char *k[] = { "tmsi", "imsi", "imei", "i-rnti", "resumeid", "resumeidentity", "ue-identity", "randomvalue",
        "nas-container", "location", "gnss", "ellipsoid", "altitude", "velocity",
        "wlan", "bssid", "ssid", "bt-", "bluetooth", "sensor", "visitedcell", "logmeasreport", "mmec", "ue-inactivetime",
        "connestfailreport", "rlf-report", "ra-reportlist", "successho-report", "shortmac", "targetmac", NULL };
    char lw[96]; int i = 0;
    for (; name[i] && i < 95; i++) lw[i] = (char)tolower((unsigned char)name[i]);
    lw[i] = 0;
    for (int j = 0; k[j]; j++) if (strstr(lw, k[j])) return 1;
    return 0;
}
static void sd_report_range(char *buf, size_t cap, long long v, double floor, double step,
                            int last, double display_value, const char *unit) {
    const double hi = floor + step * v;
    if (v < 0 || v > last) snprintf(buf, cap, "index %lld", v);
    else if (!v) snprintf(buf, cap, "<%g %s (%lld)", floor, unit, v);
    else if (v == last) snprintf(buf, cap, "≥%g %s (%lld)", hi - step, unit, v);
    else snprintf(buf, cap, "%.1f %s (%lld)", display_value, unit, v);
}

static int sd_conv(const struct sd *d, const char *tn, const char *label, long long v, char *buf, size_t cap) {
    if (!tn || !tn[0]) return 0;
    /* Show the DiagCap representative value, with the raw index and open-ended limits. */
    if (!strcmp(label, "rsrp") || !strncmp(label, "rsrpResult", 10)) {
        if (!strcmp(tn, "RSRP-Range") || !strcmp(tn, "RSRP-RangeEUTRA") || !strcmp(tn, "RSRP-RangeNR-r15")) {
            int nr = !strcmp(tn, "RSRP-RangeNR-r15") || (!strcmp(tn, "RSRP-Range") && d->nr);
            int floor = nr ? -156 : -140;
            sd_report_range(buf, cap, v, floor, 1, nr ? 126 : 97, v + floor, "dBm");
            return 1;
        }
    }
    if (!strcmp(label, "rsrq") || !strncmp(label, "rsrqResult", 10)) {
        if (!strcmp(tn, "RSRQ-Range") || !strcmp(tn, "RSRQ-RangeEUTRA") || !strcmp(tn, "RSRQ-RangeNR-r15")) {
            int nr = !strcmp(tn, "RSRQ-RangeNR-r15") || (!strcmp(tn, "RSRQ-Range") && d->nr);
            sd_report_range(buf, cap, v, nr ? -43 : -19.5, 0.5, nr ? 127 : 34,
                            (v - (nr ? 87 : 40)) / 2.0, "dB");
            return 1;
        }
    }
    if ((!strcmp(label, "sinr") || !strncmp(label, "rs-sinr-Result", 14)) &&
        (!strcmp(tn, "SINR-Range") || !strcmp(tn, "SINR-RangeEUTRA") ||
         !strcmp(tn, "RS-SINR-Range-r13") || !strcmp(tn, "RS-SINR-RangeNR-r15"))) {
        sd_report_range(buf, cap, v, -23, 0.5, 127, (v - 46) / 2.0, "dB");
        return 1;
    }
    if (d->nr && !strcmp(tn, "RSRP-Range") && v == 127) {
        snprintf(buf, cap, "∞ (127)");
        return 1;
    }
    if (!strcmp(tn, "RSRP-Range")) { snprintf(buf, cap, "%.1f dBm (%lld)", (double)(d->nr ? v - 156 : v - 140), v); return 1; }
    if (!strcmp(tn, "RSRQ-Range")) { snprintf(buf, cap, "%.1f dB (%lld)", d->nr ? (v - 87) / 2.0 : (v - 40) / 2.0, v); return 1; }
    if (!strcmp(tn, "SINR-Range") || !strcmp(tn, "RS-SINR-Range-r13") || !strcmp(tn, "SINR-RangeEUTRA") || !strcmp(tn, "RS-SINR-RangeNR-r15"))
        { snprintf(buf, cap, "%.1f dB (%lld)", (v - 46) / 2.0, v); return 1; }
    if (!strcmp(tn, "RSRP-RangeEUTRA")) { snprintf(buf, cap, "%.1f dBm (%lld)", (double)(v - 140), v); return 1; }
    if (!strcmp(tn, "RSRQ-RangeEUTRA")) { snprintf(buf, cap, "%.1f dB (%lld)", (v - 40) / 2.0, v); return 1; }
    if (!strcmp(tn, "RSRP-RangeNR-r15")) { snprintf(buf, cap, "%.1f dBm (%lld)", (double)(v - 156), v); return 1; }
    if (!strcmp(tn, "RSRQ-RangeNR-r15")) { snprintf(buf, cap, "%.1f dB (%lld)", (v - 87) / 2.0, v); return 1; }
    if (!strcmp(tn, "Q-RxLevMin")) { snprintf(buf, cap, "%lld dBm (%lld)", v * 2, v); return 1; }
    if (!strcmp(tn, "Hysteresis")) { snprintf(buf, cap, "%.1f dB (%lld)", v / 2.0, v); return 1; }
    if (!strcmp(tn, "ReselectionThreshold")) { snprintf(buf, cap, "%lld dB (%lld)", v * 2, v); return 1; }
    if (!strcmp(tn, "P-Max")) { snprintf(buf, cap, "%lld dBm", v); return 1; }
    return 0;
}

static int32_t sd_root(const char *rat, const char *name) {
    for (int i = 0; k_rs_root[i].rat; i++) if (!strcmp(k_rs_root[i].rat, rat) && !strcmp(k_rs_root[i].name, name)) return (int32_t)k_rs_root[i].node;
    return -1;
}
static void sd_val(struct sd *d, struct sd_br *r, uint32_t ni, int depth, const char *label, int hide);
static void sd_val_impl(struct sd *d, struct sd_br *r, uint32_t ni, int depth, const char *label, int hide);
static int nas_render(struct sd_out *o, const uint8_t *m, int len, int dl, int depth, int level, int reveal);
static const char *nas_msg_name(const uint8_t *m, int len, int dl, char *buf, size_t cap);

static void sd_size(struct sd_br *r, const struct rs_node *n, long *cnt) {
    int e = (n->flags & 1) ? (int)sd_get(r, 1) : 0;
    int haslb = n->flags & 2, hasub = n->flags & 4;
    long lb = haslb ? (long)n->lb : 0;
    if (!e && hasub && n->ub < 65536) {
        if (n->ub == lb) *cnt = lb;
        else *cnt = lb + (long)sd_get(r, sd_bits_for((uint64_t)(n->ub - lb + 1)));
    } else *cnt = sd_len(r);
}
static void sd_hex(struct sd_out *o, const uint8_t *b, long n, long max) {
    for (long i = 0; i < n && i < max; i++) sd_put(o, "%02X", b[i]);
    if (n > max) sd_put(o, "...");
}
static void sd_open(struct sd *d, struct sd_br *r, uint32_t ni, int depth, const char *label, int hide) {
    long l = sd_len(r);
    if (r->err || r->pos + l * 8 > r->nbits) { r->err = 1; return; }
    struct sd_br s = { r->p + (r->pos >> 3), 0, 0, 0 };
    uint8_t tmp[8192];
    if (r->pos & 7) {   /* unaligned: copy */
        if (l > (long)sizeof(tmp)) { r->err = 1; return; }
        long p0 = r->pos;
        for (long i = 0; i < l; i++) { uint8_t v = 0; for (int k = 0; k < 8; k++, p0++) v = (uint8_t)((v << 1) | ((r->p[p0 >> 3] >> (7 - (p0 & 7))) & 1)); tmp[i] = v; }
        s.p = tmp;
    }
    s.nbits = l * 8;
    r->pos += l * 8;
    if (ni) {
        sd_val(d, &s, ni, depth, label, hide);
        if (s.err && !hide) d->nested_error = 1;
    } else if (!hide && !d->canon) {
        d->nested_error = 1;
        sd_ind(d->o, depth);
        sd_put(d->o, "%s: <未知扩展 %ld 字节>\n", label, l);
    }
}

static void sd_val(struct sd *d, struct sd_br *r, uint32_t ni, int depth, const char *label, int hide) {
    if (!hide && d->summary) {
        if (!strcmp(label, "reconfigurationWithSync")) d->summary->mobility_flags |= 1;
        else if (!strcmp(label, "mobilityControlInfo") || !strcmp(label, "mobilityControlInfoSCG-r12"))
            d->summary->mobility_flags |= 2;
        if (d->summary->message && strstr(d->summary->message, "eestablishment") &&
            !strcmp(label, "handoverFailure"))
            d->summary->reest_handover_failure = 1;
    }
    const char *type = sd_s(k_rs_node[ni].tname);
    if (!hide && !d->nr && d->summary && d->dl && !strcmp(label, "sib2") &&
        d->summary->message && !strcmp(d->summary->message, "systemInformation")) {
        d->in_lte_sib2++;
        sd_val_impl(d, r, ni, depth, label, hide);
        d->in_lte_sib2--;
        return;
    }
    /* SIB3/SIB4/SIB5 subtrees of SystemInformation: track the SIB for the
       broadcast reselection-parameter capture. LTE sib2 stays on its own
       hook above; NR sib2 (cellReselectionInfoCommon) lands here. */
    if (!hide && d->summary && d->dl && d->summary->message &&
        !strcmp(d->summary->message, "systemInformation") &&
        !strncmp(label, "sib", 3) && label[3] >= '2' && label[3] <= '8' && !label[4]) {
        d->in_sib = 1;
        snprintf(d->summary->sib.name, sizeof(d->summary->sib.name), "%s", label);
        d->summary->sib.mask |= 1u << (label[3] - '0');
        sd_val_impl(d, r, ni, depth, label, hide);
        d->in_sib = 0;
        return;
    }
    if (!hide && d->summary && d->dl && d->summary->message &&
        !strcmp(d->summary->message, "systemInformationBlockType1")) {
        int *scope = NULL;
        if (d->in_initial_bwp && !strcmp(type, "BWP") && !strcmp(label, "genericParameters")) {
            int field = d->in_initial_bwp == SD_DL_BWP_RIV ? SD_DL_CP_TYPE : SD_UL_CP_TYPE;
            /* An absent cyclicPrefix in a decoded BWP means normal CP. */
            d->summary->sib1[field] = 0;
            d->summary->sib1_present |= 1u << field;
        }
        if (!strcmp(label, "plmn-IdentityList")) scope = &d->in_sib1_plmns;
        else if (!d->nr && !strcmp(label, "tdd-Config") && !strcmp(type, "TDD-Config"))
            scope = &d->in_lte_tdd;
        else if (d->nr && (!strcmp(label, "initialDownlinkBWP") || !strcmp(label, "initialUplinkBWP"))) {
            d->in_initial_bwp = label[7] == 'D' ? SD_DL_BWP_RIV : SD_UL_BWP_RIV;
            sd_val_impl(d, r, ni, depth, label, hide);
            d->in_initial_bwp = 0;
            return;
        }
        else if (!strcmp(label, "prach-RootSequenceIndex")) scope = &d->in_prach_root;
        else if (d->nr && !strcmp(type, "DownlinkConfigCommonSIB")) scope = &d->in_sib1_dl;
        else if (d->nr && !strcmp(label, "uplinkConfigCommon")) scope = &d->in_sib1_ul;
        else if (d->in_sib1_dl && !strcmp(type, "SCS-SpecificCarrier")) {
            d->summary->sib1_dl_grids++;
            scope = &d->in_sib1_grid;
        }
        if (d->nr && !strcmp(type, "TDD-UL-DL-Pattern") &&
            (!strcmp(label, "pattern1") || !strcmp(label, "pattern2"))) {
            d->in_tdd_pattern = label[7] - '0';
            sd_val_impl(d, r, ni, depth, label, hide);
            d->in_tdd_pattern = 0;
            return;
        }
        if (scope) {
            (*scope)++;
            sd_val_impl(d, r, ni, depth, label, hide);
            (*scope)--;
            return;
        }
    }
    if (!hide && d->nr && d->dl && d->summary) {
        if (!strcmp(label, "spCellConfigCommon") && !strcmp(type, "ServingCellConfigCommon")) {
            d->in_spcell_common++;
            sd_val_impl(d, r, ni, depth, label, hide);
            d->in_spcell_common--;
            return;
        }
        if (d->in_spcell_common && (!strcmp(type, "FrequencyInfoDL") || !strcmp(type, "FrequencyInfoUL"))) {
            int previous = d->nr_frequency_direction;
            d->nr_frequency_direction = !strcmp(type, "FrequencyInfoDL") ? 1 : 2;
            sd_val_impl(d, r, ni, depth, label, hide);
            d->nr_frequency_direction = previous;
            return;
        }
        /* A supplementary UL can also contain FrequencyInfoUL. */
        if (d->in_spcell_common && !strcmp(label, "supplementaryUplinkConfig")) {
            d->in_spcell_common--;
            sd_val_impl(d, r, ni, depth, label, hide);
            d->in_spcell_common++;
            return;
        }
    }
    if (d->in_sib1_plmns && !hide && !r->err && k_rs_node[ni].kind == 1 && !strcmp(type, "PLMN-Identity"))
        sd_record_plmn(d->summary, *r);
    if (!hide && d->summary && d->dl && depth <= 8 && !strcmp(label, "measConfig") && !strcmp(type, "MeasConfig")) {
        int earlier_error = d->nested_error;
        d->nested_error = 0;
        d->in_meas_config = 1;
        d->meas_parent_depth = depth + 1;
        sd_val_impl(d, r, ni, depth, label, hide);
        d->in_meas_config = 0;
        d->summary->meas_complete = !r->err && !d->nested_error && !d->o->trunc;
        d->nested_error |= earlier_error;
        return;
    }
    if (!hide && d->summary && d->nr && d->dl && depth <= 8 && !strcmp(label, "suspendConfig"))
        d->summary->suspended = 1;
    if (!hide && d->summary && !d->nr && d->summary->message &&
        !strcmp(d->summary->message, "measurementReport") && !strcmp(label, "measResultPCell-v1310")) {
        d->lte_pcell_depth = depth + 1;
        sd_val_impl(d, r, ni, depth, label, hide);
        d->lte_pcell_depth = 0;
        return;
    }
    if (d->meas_list == SD_ADD_OBJECT) {
        int rat = (!strcmp(type, "MeasObjectNR") || !strcmp(type, "MeasObjectNR-r15")) ? 1
            : (!strcmp(type, "MeasObjectEUTRA") || !strcmp(label, "measObjectEUTRA-r13")) ? 2 : 0;
        if (rat && d->meas_object_depth < 0) {
            d->meas_object_depth = depth;
            d->meas_object.rat = rat;
            sd_val_impl(d, r, ni, depth, label, hide);
            d->meas_object_depth = -1;
            return;
        }
    }
    sd_val_impl(d, r, ni, depth, label, hide);
}

static void sd_val_impl(struct sd *d, struct sd_br *r, uint32_t ni, int depth, const char *label, int hide) {
    if (r->err || d->o->trunc) return;
    if (++d->nodes > 400000 || depth > 48) { r->err = 1; return; }
    const struct rs_node *n = &k_rs_node[ni];
    struct sd_out *o = d->o;
    if (!hide && !d->canon && !d->show_private && sd_masked(label)) {
        sd_ind(o, depth); sd_put(o, "%s: ***（已隐藏）\n", label);
        hide = 1;
    }
    switch (n->kind) {
    case 1: case 10: {
        int ext = (n->kind == 1 && (n->flags & 1)) ? (int)sd_get(r, 1) : 0;
        int nopt = 0; for (int i = 0; i < n->nroot; i++) nopt += k_rs_mem[n->m0 + i].opt;
        uint64_t bm = 0; uint8_t bmx[160]; int big = nopt > 64;
        if (big) { if (nopt > 160) { r->err = 1; return; } for (int i = 0; i < nopt; i++) bmx[i] = (uint8_t)sd_get(r, 1); }
        else bm = sd_get(r, nopt);
        if (!hide && n->kind == 1) { sd_ind(o, depth); sd_put(o, "%s\n", label); }
        int oi = 0, cd = n->kind == 1 ? depth + 1 : depth;
        for (int i = 0; i < n->nroot && !r->err; i++) {
            const struct rs_mem *m = &k_rs_mem[n->m0 + i];
            if (m->opt) { int pres = big ? bmx[oi] : (int)((bm >> (nopt - 1 - oi)) & 1); oi++; if (!pres) continue; }
            sd_val(d, r, m->type, cd, sd_s(m->name), hide);
        }
        if (ext && !r->err) {
            long cnt = sd_get(r, 1) ? sd_len(r) : (long)sd_get(r, 6) + 1;
            if (cnt > 160) { r->err = 1; return; }
            uint8_t pr[160]; for (long i = 0; i < cnt; i++) pr[i] = (uint8_t)sd_get(r, 1);
            for (long i = 0; i < cnt && !r->err; i++) {
                if (!pr[i]) continue;
                if (i < n->next) { const struct rs_mem *m = &k_rs_mem[n->m0 + n->nroot + i]; sd_open(d, r, m->type, cd, sd_s(m->name), hide); }
                else sd_open(d, r, 0, cd, label, hide);
            }
        }
        break;
    }
    case 2: {
        int ext = (n->flags & 1) ? (int)sd_get(r, 1) : 0;
        if (!hide) { sd_ind(o, depth); sd_put(o, "%s\n", label); }
        if (!ext) {
            long idx = (long)sd_get(r, sd_bits_for(n->nroot));
            if (idx >= n->nroot) { r->err = 1; return; }
            const struct rs_mem *m = &k_rs_mem[n->m0 + idx];
            /* The chosen branch names the event, the measured quantity or the
               trigger class; record it while inside a reportConfig item. */
            if (!r->err && d->meas_list == SD_ADD_REPORT) {
                const char *branch = sd_s(m->name);
                struct sd_meas_report *rp = &d->meas_report;
                if (!strcmp(label, "eventId"))
                    snprintf(rp->event, sizeof(rp->event), "%s", branch);
                else if (!strncmp(branch, "threshold-", 10))
                    rp->quantity = !strcmp(branch + 10, "RSRP") ? SD_Q_RSRP :
                        !strcmp(branch + 10, "RSRQ") ? SD_Q_RSRQ :
                        !strcmp(branch + 10, "SINR") ? SD_Q_SINR : SD_Q_NONE;
                else if ((!strcmp(label, "triggerType") || !strcmp(label, "reportType")) &&
                         !strncmp(branch, "periodical", 10))
                    snprintf(rp->event, sizeof(rp->event), "%s", "periodical");
            }
            if (d->summary && !d->summary->message && depth <= 3 &&
                (!strcmp(label, "c1") || !strcmp(label, "c2") || !strcmp(label, "c3")))
                d->summary->message = sd_s(m->name);
            sd_val(d, r, m->type, depth + 1, sd_s(m->name), hide);
        } else {
            long idx = sd_nsnn(r);
            if (idx < n->next) { const struct rs_mem *m = &k_rs_mem[n->m0 + n->nroot + idx]; sd_open(d, r, m->type, depth + 1, sd_s(m->name), hide); }
            else sd_open(d, r, 0, depth + 1, label, hide);
        }
        break;
    }
    case 3: {
        long cnt; sd_size(r, n, &cnt);
        if (r->err || cnt > 100000) { r->err = 1; return; }
        if (!hide) { sd_ind(o, depth); sd_put(o, "%s (%ld)\n", label, cnt); }
        char il[16];
        int onl = d->naslist;
        if (!strcmp(label, "dedicatedNAS-MessageList") || !strcmp(label, "dedicatedInfoNASList-r15") || !strcmp(label, "dedicatedInfoNASList")) d->naslist = 1;
        enum sd_meas_list list = d->in_meas_config && depth == d->meas_parent_depth
            ? sd_meas_list(label) : SD_MEAS_NONE;
        for (long i = 0; i < cnt && !r->err; i++) {
            snprintf(il, sizeof(il), "[%ld]", i);
            if (list != SD_MEAS_NONE) {
                d->meas_list = list;
                d->meas_item = 0;
                d->meas_object_depth = -1;
                d->meas_object = (struct sd_meas_object){0, -1, -1, -1};
                d->meas_link = (struct sd_meas_link){0};
                if (list == SD_ADD_REPORT) { memset(&d->meas_report, 0, sizeof(d->meas_report)); d->meas_report_id = 0; }
            }
            sd_val(d, r, n->sub, depth + 1, il, hide);
            if (list == SD_ADD_OBJECT || list == SD_ADD_ID || list == SD_ADD_REPORT) {
                int id = list == SD_ADD_REPORT ? d->meas_report_id : d->meas_item;
                if (id && !r->err) {
                    struct sd_meas_config *m = &d->summary->meas;
                    uint64_t bit = UINT64_C(1) << (id - 1);
                    if (list == SD_ADD_OBJECT) { m->objects[id] = d->meas_object; m->objects_present |= bit; }
                    else if (list == SD_ADD_ID) { m->links[id] = d->meas_link; m->ids_present |= bit; }
                    else { m->reports[id] = d->meas_report; m->reports_present |= bit; }
                }
            }
            if (list != SD_MEAS_NONE) d->meas_list = SD_MEAS_NONE;
        }
        d->naslist = onl;
        break;
    }
    case 4: {
        int e = (n->flags & 1) ? (int)sd_get(r, 1) : 0;
        long long v;
        if (!e && (n->flags & 6) == 6) v = n->lb + (long long)sd_get(r, sd_bits_for((uint64_t)(n->ub - n->lb) + 1));
        else if (!e && (n->flags & 2)) { long l = sd_len(r); if (l > 8) { r->err = 1; return; } v = n->lb + (long long)sd_get(r, (int)l * 8); }
        else { long l = sd_len(r); if (l > 8 || l < 1) { r->err = 1; return; } uint64_t u = sd_get(r, (int)l * 8);
               if ((u >> (l * 8 - 1)) & 1) u |= l < 8 ? ~0ULL << (l * 8) : 0;
               v = (long long)u; }
        /* This is the RRC resource indication for the initial BWP.
         * The generic location redaction still applies to the signaling text. */
        if (!r->err && d->summary && !strcmp(label, "targetPhysCellId") && v >= 0 && v <= 1007)
            d->summary->target_pci_plus_one = (int)v + 1;
        if (!r->err && d->in_initial_bwp && !strcmp(label, "locationAndBandwidth")) {
            d->summary->sib1[d->in_initial_bwp] = (int)v;
            d->summary->sib1_present |= 1u << d->in_initial_bwp;
        }
        if (!hide) {
            /* Broadcast reselection parameters (SIB3/SIB5 LTE, SIB2/4/5 NR). */
            if (!r->err && d->in_sib && d->summary) {
                struct sd_reselection *rs = &d->summary->sib.resel;
                if (!strcmp(label, "threshServingLow") || !strcmp(label, "threshServingLowQ")) {
                    rs->thresh_serving_low = 2 * (int)v;
                    rs->has_thresh_serving_low = 1;
                } else if (!strcmp(label, "threshX-High")) {
                    rs->thresh_x_high = 2 * (int)v;
                    rs->has_thresh_x_high = 1;
                } else if (!strcmp(label, "threshX-Low")) {
                    rs->thresh_x_low = 2 * (int)v;
                    rs->has_thresh_x_low = 1;
                } else if (!strcmp(label, "s-IntraSearch") || !strcmp(label, "s-IntraSearchQ")) {
                    rs->s_intra_search = 2 * (int)v;
                    rs->has_s_intra = 1;
                } else if (!strcmp(label, "t-ReselectionEUTRA") || !strcmp(label, "t-ReselectionNR")) {
                    rs->t_reselect = (int)v;
                    rs->has_t_reselect = 1;
                } else if (!strcmp(label, "cellReselectionPriority")) {
                    rs->priority = (int)v;
                    rs->has_priority = 1;
                }
            }
            if (!r->err && d->summary && d->summary->message &&
                !strcmp(d->summary->message, "systemInformationBlockType1")) {
                if (!strcmp(label, "p-Max") && (!d->nr || d->in_sib1_ul))
                    sd_set_access(d->summary, SD_ACCESS_P_MAX, (int)v);
                if (!d->nr && !strcmp(label, "q-RxLevMin"))
                    sd_set_access(d->summary, SD_ACCESS_Q_RX_MIN, 2 * (int)v);
                if (d->nr && d->in_sib1_ul && !strcmp(label, "preambleReceivedTargetPower"))
                    sd_set_access(d->summary, SD_ACCESS_TARGET_DBM, (int)v);
            }
            if (!r->err && d->in_lte_sib2) {
                if (!strcmp(label, "prach-ConfigIndex"))
                    sd_set_access(d->summary, SD_ACCESS_PRACH_INDEX, (int)v);
                else if (!strcmp(label, "rootSequenceIndex"))
                    sd_set_access(d->summary, SD_ACCESS_ROOT, (int)v);
            }
            if (!r->err && d->meas_list != SD_MEAS_NONE) sd_meas_integer(d, label, depth, v);
            if (!r->err && d->nr && d->summary && d->summary->message &&
                strcmp(d->summary->message, "systemInformationBlockType1") == 0) {
                sd_sib1_value(d->summary, label, v);
                int field = !strcmp(label, "absoluteFrequencyPointA") && d->in_sib1_ul ? SD_UL_POINT_A :
                    !d->in_sib1_grid ? -1 : !strcmp(label, "offsetToCarrier") ? SD_GRID_OFFSET :
                    !strcmp(label, "carrierBandwidth") ? SD_GRID_RBS : -1;
                if (field >= 0) {
                    d->summary->sib1[field] = (int)v;
                    d->summary->sib1_present |= 1u << field;
                }
                if (d->in_prach_root && !(d->summary->sib1_present & (1u << SD_PRACH_ROOT)) &&
                    (!strcmp(label, "l839") || !strcmp(label, "l139"))) {
                    d->summary->sib1[SD_PRACH_ROOT] = (int)v;
                    d->summary->sib1_present |= 1u << SD_PRACH_ROOT;
                }
                if (d->in_tdd_pattern) {
                    struct sd_tdd_pattern *pattern = &d->summary->tdd[d->in_tdd_pattern - 1];
                    if (!strcmp(label, "nrofDownlinkSlots")) pattern->dl_slots = (int)v;
                    else if (!strcmp(label, "nrofDownlinkSymbols")) pattern->dl_symbols = (int)v;
                    else if (!strcmp(label, "nrofUplinkSlots")) pattern->ul_slots = (int)v;
                    else if (!strcmp(label, "nrofUplinkSymbols")) pattern->ul_symbols = (int)v;
                }
            }
            if (!r->err && d->in_spcell_common) {
                int field = !strcmp(label, "physCellId") ? SD_NR_PCI :
                    d->nr_frequency_direction == 1 && !strcmp(label, "absoluteFrequencySSB") ? SD_NR_SSB :
                    !strcmp(label, "absoluteFrequencyPointA") && d->nr_frequency_direction == 1 ? SD_NR_DL_A :
                    !strcmp(label, "absoluteFrequencyPointA") && d->nr_frequency_direction == 2 ? SD_NR_UL_A : -1;
                if (field >= 0) {
                    d->summary->nr_frequency[field] = (int)v;
                    d->summary->nr_frequency_present |= 1u << field;
                }
            }
            if (!r->err && d->lte_pcell_depth > 0 && depth == d->lte_pcell_depth &&
                !strcmp(label, "rs-sinr-Result-r13") && v >= 0 && v <= 127)
                d->summary->lte_pcell_sinr_plus_one = (int)v + 1;
            if (!r->err && d->summary && !d->dl && d->summary->message && v >= 1 && v <= SD_PLMN_LIMIT &&
                !strcmp(label, "selectedPLMN-Identity") &&
                (!strcmp(d->summary->message, "rrcSetupComplete") || !strcmp(d->summary->message, "rrcConnectionSetupComplete")))
                d->summary->selected_plmn = (int)v;
            char cb[64]; sd_ind(o, depth);
            if (!d->canon && sd_conv(d, sd_s(n->tname), label, v, cb, sizeof(cb))) sd_put(o, "%s: %s\n", label, cb);
            else sd_put(o, "%s: %lld\n", label, v);
        }
        break;
    }
    case 5: {
        int e = (n->flags & 1) ? (int)sd_get(r, 1) : 0;
        long idx = e ? n->nroot + sd_nsnn(r) : (long)sd_get(r, sd_bits_for(n->nroot));
        const char *nm = idx < n->nroot + n->next ? sd_s(k_rs_mem[n->m0 + idx].name) : "?";
        /* Trigger parameters are ENUMERATED names: "dB2", "ms320", "dB-30". */
        if (!r->err && d->meas_list == SD_ADD_REPORT) {
            struct sd_meas_report *rp = &d->meas_report;
            if (!strcmp(label, "hysteresis")) {
                int v = sd_enum_num(nm, "dB");
                if (v >= 0) { rp->hysteresis = v; rp->has_hysteresis = 1; }
            } else if (!strcmp(label, "timeToTrigger")) {
                int v = sd_enum_num(nm, "ms");
                if (v >= 0) { rp->ttt_ms = v; rp->has_ttt = 1; }
            } else if (!strcmp(label, "a3-Offset") || !strcmp(label, "a6-Offset")) {
                int v = sd_enum_num(nm, "dB");
                if (v != -1) { rp->offset_db = v; rp->has_offset = 1; }
            } else if (!strcmp(label, "triggerQuantity")) {
                rp->quantity = !strcmp(nm, "rsrp") ? SD_Q_RSRP :
                    !strcmp(nm, "rsrq") ? SD_Q_RSRQ : SD_Q_NONE;
            }
        }
        /* Broadcast q-Hyst (ENUMERATED dB0..dB30) inside a SIB subtree. */
        if (!r->err && d->in_sib && d->summary && !strcmp(label, "q-Hyst")) {
            int v = sd_enum_num(nm, "dB");
            if (v >= 0) {
                d->summary->sib.resel.q_hyst = v;
                d->summary->sib.resel.has_q_hyst = 1;
            }
        }
        int nr_access = d->nr && d->in_sib1_ul && d->summary && d->summary->message &&
            !strcmp(d->summary->message, "systemInformationBlockType1");
        if (!hide && !r->err && d->in_lte_tdd) {
            if (!strcmp(label, "subframeAssignment") && idx >= 0 && idx <= 6)
                d->summary->lte_tdd_assignment_plus_one = (int)idx + 1;
            else if (!strcmp(label, "specialSubframePatterns") && idx >= 0 && idx <= 8)
                d->summary->lte_tdd_special_plus_one = (int)idx + 1;
        }
        if (!hide && !r->err && (d->in_lte_sib2 || nr_access)) {
            if (!strcmp(label, "powerRampingStep") && idx >= 0 && idx < 4)
                sd_set_access(d->summary, SD_ACCESS_RAMP_DB, (int)idx * 2);
            else if (!strcmp(label, "preambleTransMax")) {
                static const int max_tx[] = { 3, 4, 5, 6, 7, 8, 10, 20, 50, 100, 200 };
                if (idx >= 0 && idx < (long)(sizeof(max_tx) / sizeof(max_tx[0])))
                    sd_set_access(d->summary, SD_ACCESS_MAX_TX, max_tx[idx]);
            } else if (d->in_lte_sib2 && !strcmp(label, "preambleInitialReceivedTargetPower") && idx >= 0 && idx < 16) {
                sd_set_access(d->summary, SD_ACCESS_TARGET_DBM, -120 + (int)idx * 2);
            } else if (d->in_lte_sib2 && !strcmp(label, "ra-ResponseWindowSize")) {
                static const int window[] = { 2, 3, 4, 5, 6, 7, 8, 10 };
                if (idx >= 0 && idx < (long)(sizeof(window) / sizeof(window[0])))
                    sd_set_access(d->summary, SD_ACCESS_RAR_WINDOW, window[idx]);
            } else if (d->in_lte_sib2 && !strcmp(label, "numberOfRA-Preambles") && idx >= 0 && idx < 16) {
                sd_set_access(d->summary, SD_ACCESS_PREAMBLES, 4 * ((int)idx + 1));
            } else if (d->in_lte_sib2 && !strcmp(label, "ul-CyclicPrefixLength") && idx >= 0 && idx <= 1) {
                sd_set_access(d->summary, SD_ACCESS_UL_CP_TYPE, (int)idx);
            }
        }
        if (!hide && !r->err && d->nr && d->summary && d->summary->message &&
            !strcmp(d->summary->message, "systemInformationBlockType1")) {
            int field = -1, value = 0;
            int scs = !strcmp(nm, "kHz15") ? 15 : !strcmp(nm, "kHz30") ? 30 :
                !strcmp(nm, "kHz60") ? 60 : !strcmp(nm, "kHz120") ? 120 : 0;
            if (d->in_sib1_grid && !strcmp(label, "subcarrierSpacing")) {
                field = SD_GRID_SCS;
                value = scs;
            } else if (d->in_initial_bwp && !strcmp(label, "cyclicPrefix") && !strcmp(nm, "extended")) {
                field = d->in_initial_bwp == SD_DL_BWP_RIV ? SD_DL_CP_TYPE : SD_UL_CP_TYPE;
                value = 1;
            } else if (!strcmp(label, "referenceSubcarrierSpacing")) {
                field = SD_TDD_SCS;
                value = scs;
            } else if (!strcmp(label, "ssb-PeriodicityServingCell")) {
                static const int period_ms[] = { 5, 10, 20, 40, 80, 160 };
                field = SD_SSB_PERIOD_MS;
                if (idx >= 0 && idx < (long)(sizeof(period_ms) / sizeof(period_ms[0]))) value = period_ms[idx];
            } else if (d->in_tdd_pattern && !strcmp(label, "dl-UL-TransmissionPeriodicity")) {
                static const int period_us[] = { 500, 625, 1000, 1250, 2000, 2500, 5000, 10000 };
                if (idx >= 0 && idx < (long)(sizeof(period_us) / sizeof(period_us[0])))
                    d->summary->tdd[d->in_tdd_pattern - 1].period_us = period_us[idx];
            } else if (d->in_tdd_pattern && !strcmp(label, "dl-UL-TransmissionPeriodicity-v1530")) {
                if (!strcmp(nm, "ms3")) d->summary->tdd[d->in_tdd_pattern - 1].period_us = 3000;
                else if (!strcmp(nm, "ms4")) d->summary->tdd[d->in_tdd_pattern - 1].period_us = 4000;
            }
            if (field >= 0 && value > 0) {
                d->summary->sib1[field] = value;
                d->summary->sib1_present |= 1u << field;
            }
        }
        if (!strcmp(label, "rat-Type")) { d->rat_type = (int)idx; snprintf(d->rat_name, sizeof(d->rat_name), "%s", nm); }
        if (!hide) { sd_ind(o, depth); sd_put(o, "%s: %s\n", label, nm); }
        break;
    }
    case 6: { int v = (int)sd_get(r, 1); if (!hide) { sd_ind(o, depth); sd_put(o, "%s: %s\n", label, v ? "true" : "false"); } break; }
    case 7: if (!hide) { sd_ind(o, depth); sd_put(o, "%s: NULL\n", label); } break;
    case 8: case 9: {
        long cnt; sd_size(r, n, &cnt);
        long nb = n->kind == 8 ? cnt : cnt * 8;
        if (r->err || nb < 0 || r->pos + nb > r->nbits) { r->err = 1; return; }
        uint8_t buf[8192]; long nbytes = (nb + 7) / 8;
        if (nbytes > (long)sizeof(buf)) { r->pos += nb; if (!hide) { sd_ind(o, depth); sd_put(o, "%s: <%ld 字节>\n", label, nbytes); } break; }
        memset(buf, 0, (size_t)nbytes);
        for (long i = 0; i < nb; i++, r->pos++) if ((r->p[r->pos >> 3] >> (7 - (r->pos & 7))) & 1) buf[i >> 3] |= (uint8_t)(0x80 >> (i & 7));
        if (n->flags & 8) {
            struct sd_br s = { buf, nb, 0, 0 };
            if (!hide) { sd_ind(o, depth); sd_put(o, "%s (containing)\n", label); }
            sd_val(d, &s, n->sub, depth + 1, sd_s(k_rs_node[n->sub].tname)[0] ? sd_s(k_rs_node[n->sub].tname) : "value", hide);
            if (s.err && !hide) d->nested_error = 1;
            break;
        }
        /* NAS PDU inside RRC: rendered by nasdec (identities masked there) */
        if (n->kind == 9 && (!strcmp(label, "dedicatedNAS-Message") || !strcmp(label, "dedicatedInfoNAS") || (d->naslist && label[0] == '['))) {
            if (!hide) {
                if (d->summary && !d->summary->nas[0])
                    nas_msg_name(buf, (int)nbytes, d->dl, d->summary->nas, sizeof(d->summary->nas));
                sd_ind(o, depth);
                sd_put(o, "%s (NAS, %ld 字节)\n", label, nbytes);
                if (nas_render(o, buf, (int)nbytes, d->dl, depth + 1, 0, d->show_private)) d->nested_error = 1;
            }
            break;
        }
        /* UE capability container: decode by preceding rat-Type (display mode) */
        if (!d->canon && n->kind == 9 && (!strcmp(label, "ue-CapabilityRAT-Container") || !strcmp(label, "ueCapabilityRAT-Container")) && d->rat_type >= 0) {
            int32_t ri = -1;
            if (!strcmp(d->rat_name, "nr")) ri = sd_root("NR", "UE-NR-Capability");
            else if (!strcmp(d->rat_name, "eutra-nr")) ri = sd_root("NR", "UE-MRDC-Capability");
            else if (!strcmp(d->rat_name, "eutra")) ri = sd_root("LTE", "UE-EUTRA-Capability");
            if (ri > 0) {
                struct sd_br s = { buf, nb, 0, 0 }; int onr = d->nr;
                d->nr = strcmp(d->rat_name, "eutra") != 0;
                if (!hide) { sd_ind(o, depth); sd_put(o, "%s (%ld 字节, %s)\n", label, nbytes, d->rat_name); }
                sd_val(d, &s, (uint32_t)ri, depth + 1, sd_s(k_rs_node[ri].tname)[0] ? sd_s(k_rs_node[ri].tname) : d->rat_name, hide);
                if (s.err && !hide) { d->nested_error = 1; sd_ind(o, depth + 1); sd_put(o, "<解码出错>\n"); }
                d->nr = onr; break;
            }
        }
        if (!hide) {
            sd_ind(o, depth); sd_put(o, "%s: ", label);
            if (n->kind == 8 && nb <= 32) { sd_put(o, "'"); for (long i = 0; i < nb; i++) sd_put(o, "%d", (buf[i >> 3] >> (7 - (i & 7))) & 1); sd_put(o, "'B"); if (!d->canon) sd_put(o, " (%ld bits)", nb); }
            else if (d->canon || (d->show_private && sd_masked(label))) {
                sd_put(o, "0x"); sd_hex(o, buf, nbytes, 100000);
                if (n->kind == 8) sd_put(o, " (%ld bits)", nb);
            } else if (n->kind == 8) sd_put(o, "<%ld bits>", nb);
            else sd_put(o, "<%ld 字节>", nbytes);
            sd_put(o, "\n");
        }
        break;
    }
    default: r->err = 1;
    }
}
/* decode one PDU with root type (rat "NR"/"LTE", name e.g. "UL-DCCH-Message"). Returns 0 ok, 1 error (partial output kept) */
static int sd_decode(const char *rat, const char *root, const uint8_t *pdu, int len, int canon, int show_private,
                     struct sd_out *o, int depth, struct sd_summary *summary) {
    int32_t ri = sd_root(rat, root);
    if (ri < 0) { sd_put(o, "<不支持的消息类型 %s %s>\n", rat, root); return 1; }
    struct sd d; memset(&d, 0, sizeof(d));
    d.o = o; d.canon = canon; d.show_private = show_private; d.nr = !strcmp(rat, "NR"); d.rat_type = -1;
    d.summary = summary;
    d.dl = strncmp(root, "UL-", 3) != 0 && strcmp(root, "RRCReconfigurationComplete") != 0;
    struct sd_br r = { pdu, (long)len * 8, 0, 0 };
    sd_val(&d, &r, (uint32_t)ri, depth, root, 0);
    if (r.err) {
        if (summary) { summary->meas_complete = 0; summary->plmn_invalid = 1; summary->selected_plmn = 0; }
        sd_put(o, "<解码出错 @bit %ld/%ld>\n", r.pos, r.nbits);
        return 1;
    }
    return d.nested_error || o->trunc;
}

#include "nasdec.c"

#ifdef SIGDEC_TEST
/* test: stdin lines "RAT ROOT HEX" -> canonical trees separated by "==" */
int main(int argc, char **argv) {
    static char line[70000]; static uint8_t pdu[32768]; static char ob[1 << 21];
    int canon = argc > 1 && !strcmp(argv[1], "-c");
    while (fgets(line, sizeof(line), stdin)) {
        char rat[8], root[64]; int off = 0;
        if (sscanf(line, "%7s %63s %n", rat, root, &off) < 2) continue;
        int n = 0; for (char *p = line + off; p[0] && p[1] && isxdigit((unsigned char)p[0]); p += 2) { unsigned v; sscanf(p, "%2x", &v); pdu[n++] = (uint8_t)v; }
        struct sd_out o = { ob, 0, sizeof(ob), 0 }; ob[0] = 0;
        int rc;
        if (!strncmp(rat, "NAS", 3)) { char nm[96]; nas_msg_name(pdu, n, !strcmp(root, "DL"), nm, sizeof(nm)); sd_put(&o, "NAME %s\n", nm); rc = nas_render(&o, pdu, n, !strcmp(root, "DL"), 0, 0, canon) ? 1 : 0; }
        else rc = sd_decode(rat, root, pdu, n, canon, canon, &o, 0, NULL);
        printf("%s== rc=%d\n", ob, rc);
    }
    return 0;
}
#endif
