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
