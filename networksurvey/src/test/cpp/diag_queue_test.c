#include "../../main/cpp/diag_queue.c"
#include <stdio.h>
#include <string.h>

#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); return 1; } } while (0)

int main(void) {
    struct diag_queue q;
    struct diag_q_item it;
    uint8_t a[8] = {1,2,3,4,5,6,7,8};
    uint8_t b[4] = {9,9,9,9};
    diag_q_init(&q);
    CHECK(diag_q_empty(&q));
    CHECK(diag_q_push(&q, a, 8));
    CHECK(diag_q_push(&q, b, 4));
    CHECK(diag_q_count(&q) == 2);
    CHECK(diag_q_pop(&q, &it) && it.len == 8 && it.data[0] == 1 && it.data[7] == 8);
    CHECK(diag_q_pop(&q, &it) && it.len == 4 && it.data[0] == 9);
    CHECK(diag_q_empty(&q));
    CHECK(!diag_q_pop(&q, &it));

    /* fill then drop */
    diag_q_init(&q);
    for (int i = 0; i < DIAG_Q_SLOTS; i++) CHECK(diag_q_push(&q, a, 8));
    CHECK(!diag_q_push(&q, a, 8));
    CHECK(q.stats.dropped == 1);
    CHECK(diag_q_count(&q) == DIAG_Q_SLOTS);

    /* wrap the slot ring after draining some */
    for (int i = 0; i < 4; i++) CHECK(diag_q_pop(&q, &it));
    for (int i = 0; i < 4; i++) CHECK(diag_q_push(&q, b, 4));
    CHECK(diag_q_count(&q) == DIAG_Q_SLOTS);

    diag_q_init(&q);
    CHECK(!diag_q_push(&q, a, 0));
    CHECK(diag_q_push(&q, a, 8));

    /* priority classification: counters low, signalling/measurement high */
    CHECK(diag_code_priority(0xB888) == DIAG_PRIO_LOW);
    CHECK(diag_code_priority(0xB883) == DIAG_PRIO_LOW);
    CHECK(diag_code_priority(0xB884) == DIAG_PRIO_LOW);
    CHECK(diag_code_priority(0xB8A7) == DIAG_PRIO_LOW);
    CHECK(diag_code_priority(0xB842) == DIAG_PRIO_LOW);
    CHECK(diag_code_priority(0xB860) == DIAG_PRIO_LOW);
    CHECK(diag_code_priority(0xB84D) == DIAG_PRIO_LOW);
    CHECK(diag_code_priority(0xB868) == DIAG_PRIO_LOW);
    CHECK(diag_code_priority(0xB881) == DIAG_PRIO_LOW);
    CHECK(diag_code_priority(0xB887) == DIAG_PRIO_LOW);
    /* must NOT be dropped under pressure */
    CHECK(diag_code_priority(0xB821) == DIAG_PRIO_HIGH);   /* NR RRC */
    CHECK(diag_code_priority(0xB97F) == DIAG_PRIO_HIGH);   /* NR measurement DB */
    CHECK(diag_code_priority(0xB96A) == DIAG_PRIO_HIGH);   /* NR neighbour SINR */
    CHECK(diag_code_priority(0xB8D8) == DIAG_PRIO_HIGH);   /* nr_snr source */
    CHECK(diag_code_priority(0xB80B) == DIAG_PRIO_HIGH);   /* NR NAS */
    CHECK(diag_code_priority(0x156E) == DIAG_PRIO_HIGH);   /* IMS SIP */

    /* split-queue behaviour: a counter flood on the low priority queue must
       never evict a high-priority record, and the high queue drains first. */
    struct diag_queue hi, lo;
    diag_q_init(&hi);
    diag_q_init(&lo);
    CHECK(diag_q_push(&hi, a, 8));                 /* one RRC-class record */
    for (int i = 0; i < DIAG_Q_SLOTS; i++) CHECK(diag_q_push(&lo, b, 4));  /* flood */
    CHECK(!diag_q_push(&lo, b, 4));                /* low queue drops the excess */
    CHECK(lo.stats.dropped == 1);
    CHECK(diag_q_count(&hi) == 1);                 /* high record survived */
    CHECK(diag_q_pop(&hi, &it) && it.len == 8 && it.data[0] == 1);

    printf("diag_queue_test ok\n");
    return 0;
}
