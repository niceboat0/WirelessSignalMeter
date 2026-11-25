#ifndef LTE_B193_SINR_H
#define LTE_B193_SINR_H
/*
 * B193 subpacket 0x19 v66 per-antenna SINR.
 * 10-bit codes at bits 832/842/864/874, value = code/10 - 20 dB.
 * 511, 601 and codes above 650 are the invalid markers already used by the decoder.
 * The combined value is the maximum valid receive chain. Callers that display it
 * must show every valid chain from this same sample.
 */
#include <stdint.h>

#define LTE_B193_SINR_NA (-9999.0)

struct lte_b193_sinr {
    int valid_mask;
    double rx[4];
    double combined;
};

static inline uint32_t lte_b193_rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static inline void lte_b193_v66_sinr(const uint8_t *cell, struct lte_b193_sinr *out) {
    int rx_mask = cell[0] & 15;
    out->valid_mask = 0;
    out->combined = LTE_B193_SINR_NA;
    for (int rx = 0; rx < 4; rx++) {
        out->rx[rx] = LTE_B193_SINR_NA;
        if (!(rx_mask & (1 << rx))) continue;
        unsigned raw = (lte_b193_rd32(cell + 104 + 4 * (rx / 2)) >> (10 * (rx % 2))) & 1023u;
        if (raw == 511 || raw == 601 || raw > 650) continue;
        double reading = raw * 0.1 - 20.0;
        out->rx[rx] = reading;
        out->valid_mask |= 1 << rx;
        if (!(out->combined > LTE_B193_SINR_NA + 1.0) || reading > out->combined)
            out->combined = reading;
    }
}

#endif
