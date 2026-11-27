#include "diag_queue.h"
#include <string.h>

void diag_q_init(struct diag_queue *q) { memset(q, 0, sizeof(*q)); }
int diag_q_empty(const struct diag_queue *q) { return q->count == 0; }
unsigned diag_q_count(const struct diag_queue *q) { return q->count; }

int diag_q_push(struct diag_queue *q, const uint8_t *data, uint16_t len) {
    if (!data || !len) return 0;
    if (q->count >= DIAG_Q_SLOTS) {
        q->stats.dropped++;
        q->stats.dropped_bytes += len;
        return 0;
    }
    memcpy(q->bytes[q->head], data, len);
    q->len[q->head] = len;
    q->head = (q->head + 1) % DIAG_Q_SLOTS;
    q->count++;
    q->stats.pushed++;
    if (q->count > q->stats.high_water) q->stats.high_water = q->count;
    return 1;
}

int diag_q_pop(struct diag_queue *q, struct diag_q_item *item) {
    if (!q->count) return 0;
    item->data = q->bytes[q->tail];
    item->len = q->len[q->tail];
    q->tail = (q->tail + 1) % DIAG_Q_SLOTS;
    q->count--;
    q->stats.popped++;
    return 1;
}

void diag_epoch_mark(struct diag_epoch_barrier *barrier, uint64_t timestamp) {
    barrier->timestamp = timestamp;
    barrier->active = 1;
}

int diag_epoch_allows(const struct diag_epoch_barrier *barrier, uint64_t timestamp) {
    return !barrier->active || (barrier->timestamp && timestamp > barrier->timestamp);
}

void diag_q_drop_epoch(struct diag_queue *q, uint16_t len) {
    q->stats.dropped++;
    q->stats.dropped_bytes += len;
    q->stats.epoch_dropped++;
}

void diag_latest_init(struct diag_latest_set *s) { memset(s, 0, sizeof(*s)); }

int diag_latest_pending(const struct diag_latest_set *s) {
    for (int i = 0; i < DIAG_LATEST_SLOTS; i++) if (s->slot[i].occupied) return 1;
    return 0;
}

unsigned diag_latest_count(const struct diag_latest_set *s) {
    unsigned n = 0;
    for (int i = 0; i < DIAG_LATEST_SLOTS; i++) n += s->slot[i].occupied ? 1u : 0u;
    return n;
}

void diag_latest_drop_epoch(struct diag_latest_set *s, uint16_t len) {
    (void)len;
    s->epoch_dropped++;
}

/* One unread snapshot per cumulative code. A replacement is not a sample drop:
   the counters are absolute, so the next parsed frame still yields the delta. */
int diag_latest_push(struct diag_latest_set *s, uint16_t code, const uint8_t *data, uint16_t len) {
    /* len is uint16_t and the slot is DIAG_Q_SLOT bytes, so it always fits. */
    if (!s || !data || !len || !diag_code_coalesce(code)) return 0;
    struct diag_latest *slot = NULL;
    for (int i = 0; i < DIAG_LATEST_SLOTS; i++) {
        if (s->slot[i].occupied && s->slot[i].code == code) { slot = &s->slot[i]; break; }
    }
    if (!slot) {
        for (int i = 0; i < DIAG_LATEST_SLOTS; i++) if (!s->slot[i].occupied) { slot = &s->slot[i]; break; }
    }
    if (!slot) {
        s->dropped++;
        return 0;
    }
    if (slot->occupied) s->superseded++;
    memcpy(slot->bytes, data, len);
    slot->len = len;
    slot->code = code;
    slot->occupied = 1;
    s->pushed++;
    return 1;
}

int diag_latest_pop(struct diag_latest_set *s, struct diag_q_item *item) {
    for (int n = 0; n < DIAG_LATEST_SLOTS; n++) {
        unsigned i = (s->cursor + (unsigned)n) % DIAG_LATEST_SLOTS;
        if (!s->slot[i].occupied) continue;
        item->data = s->slot[i].bytes;
        item->len = s->slot[i].len;
        s->slot[i].occupied = 0;
        s->cursor = (i + 1) % DIAG_LATEST_SLOTS;
        return 1;
    }
    return 0;
}

/* See diag_queue.h. Keep this list in sync with the parser switch in
   root_diag.c: signalling and serving/measurement records must stay high. */
int diag_code_priority(uint16_t code) {
    switch (code) {
    case 0xB888:                                      /* NR MAC cumulative PDSCH counters */
    case 0xB883: case 0xB884:                          /* NR UL scheduling and power samples */
    case 0xB8A7:                                      /* NR CQI/RI reports */
    case 0xB842: case 0xB860: case 0xB84D:
    case 0xB868: case 0xB881:                          /* NR layer-2 stats */
    case 0xB887:                                       /* NR MAC PDSCH per-TB */
        return DIAG_PRIO_LOW;
    default:
        return DIAG_PRIO_HIGH;
    }
}

/* Absolute counters. Replacing an unread frame does not lose the byte delta.
   Per-TB (B887/B883), power (B884) and CQI (B8A7) stay on the sample queue. */
int diag_code_coalesce(uint16_t code) {
    switch (code) {
    case 0xB888:
    case 0xB842: case 0xB860: case 0xB84D:
    case 0xB868: case 0xB881:
        return 1;
    default:
        return 0;
    }
}

int diag_code_lane(uint16_t code) {
    if (diag_code_coalesce(code)) return DIAG_LANE_COALESCE;
    return diag_code_priority(code) == DIAG_PRIO_LOW ? DIAG_LANE_LOW : DIAG_LANE_HIGH;
}
