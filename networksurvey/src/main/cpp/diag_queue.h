/* Bounded record queue for DIAG decode off the libdiag callback path.
   Fixed-size slots avoid wrap-around overlap bugs and keep the hot path short. */
#ifndef DIAG_QUEUE_H
#define DIAG_QUEUE_H

#include <stddef.h>
#include <stdint.h>

#define DIAG_Q_SLOTS 128
#define DIAG_Q_SLOT 65535

struct diag_q_stats {
    unsigned long pushed;
    unsigned long popped;
    unsigned long dropped;
    unsigned long dropped_bytes;
    unsigned long oversized;
    unsigned long epoch_dropped;
    unsigned int high_water;
};

struct diag_q_item {
    const uint8_t *data;
    uint16_t len;
};

struct diag_queue {
    uint8_t bytes[DIAG_Q_SLOTS][DIAG_Q_SLOT];
    uint16_t len[DIAG_Q_SLOTS];
    unsigned head, tail, count;
    struct diag_q_stats stats;
};

void diag_q_init(struct diag_queue *q);
int diag_q_push(struct diag_queue *q, const uint8_t *data, uint16_t len);
int diag_q_pop(struct diag_queue *q, struct diag_q_item *item);
int diag_q_empty(const struct diag_queue *q);
unsigned diag_q_count(const struct diag_queue *q);

/* Raw modem timestamps keep old, identity-less statistics from being parsed
   against a newer serving cell after high-priority records overtake them. */
struct diag_epoch_barrier {
    uint64_t timestamp;
    int active;
};
void diag_epoch_mark(struct diag_epoch_barrier *barrier, uint64_t timestamp);
int diag_epoch_allows(const struct diag_epoch_barrier *barrier, uint64_t timestamp);
void diag_q_drop_epoch(struct diag_queue *q, uint16_t len);

/* Periodic statistics use a separate queue to protect signalling/measurement
   records from their bursts. Some are cumulative; per-TB/power/CQI samples
   are not replayed, so drops still represent missing statistical coverage. */
#define DIAG_PRIO_HIGH 0
#define DIAG_PRIO_LOW 1
int diag_code_priority(uint16_t code);

#endif
