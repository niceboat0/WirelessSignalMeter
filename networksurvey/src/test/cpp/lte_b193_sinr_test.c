#include "../../main/cpp/lte_b193_sinr.h"
#include <stdio.h>
#include <string.h>

#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); return 1; } } while (0)

static void put_raw(uint8_t *cell, int rx, unsigned raw) {
    uint8_t *p = cell + 104 + 4 * (rx / 2);
    uint32_t word = (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
    unsigned shift = 10u * (rx % 2);
    word &= ~(1023u << shift);
    word |= (raw & 1023u) << shift;
    p[0] = (uint8_t)word;
    p[1] = (uint8_t)(word >> 8);
    p[2] = (uint8_t)(word >> 16);
    p[3] = (uint8_t)(word >> 24);
}

static int approx(double v, double expect) {
    double d = v - expect;
    if (d < 0) d = -d;
    return d < 0.001;
}

int main(void) {
    uint8_t cell[144];
    struct lte_b193_sinr out;
    const unsigned masks[] = {0x5, 0x6, 0x9, 0xC, 0xF, 0x1};
    const double chain_db[] = {-20.0, 0.0, 12.5, 30.0};
    const unsigned chain_raw[] = {0, 200, 325, 500};

    for (unsigned m = 0; m < sizeof(masks) / sizeof(masks[0]); m++) {
        memset(cell, 0, sizeof(cell));
        cell[0] = (uint8_t)masks[m];
        for (int rx = 0; rx < 4; rx++) put_raw(cell, rx, chain_raw[rx]);
        lte_b193_v66_sinr(cell, &out);
        double expect_max = LTE_B193_SINR_NA;
        int expect_mask = 0;
        for (int rx = 0; rx < 4; rx++) {
            if (masks[m] & (1u << rx)) {
                CHECK(out.valid_mask & (1 << rx));
                CHECK(approx(out.rx[rx], chain_db[rx]));
                expect_mask |= 1 << rx;
                if (!(expect_max > LTE_B193_SINR_NA + 1) || chain_db[rx] > expect_max) expect_max = chain_db[rx];
            } else {
                CHECK(!(out.valid_mask & (1 << rx)));
                CHECK(!(out.rx[rx] > LTE_B193_SINR_NA + 1));
            }
        }
        CHECK(out.valid_mask == expect_mask);
        CHECK(approx(out.combined, expect_max));
    }

    /* Two active chains that are not Rx0+Rx1. Mask 0xC is Rx2+Rx3. */
    memset(cell, 0, sizeof(cell));
    cell[0] = 0xC;
    put_raw(cell, 2, 100); /* -10 dB */
    put_raw(cell, 3, 511); /* invalid */
    lte_b193_v66_sinr(cell, &out);
    CHECK(out.valid_mask == (1 << 2));
    CHECK(approx(out.combined, -10.0));
    CHECK(approx(out.rx[2], -10.0));

    /* 601 and 651 are invalid; 650 is still a reading. */
    memset(cell, 0, sizeof(cell));
    cell[0] = 0xF;
    put_raw(cell, 0, 601);
    put_raw(cell, 1, 651);
    put_raw(cell, 2, 650);
    put_raw(cell, 3, 511);
    lte_b193_v66_sinr(cell, &out);
    CHECK(out.valid_mask == (1 << 2));
    CHECK(approx(out.rx[2], 650 * 0.1 - 20.0));
    CHECK(approx(out.combined, out.rx[2]));

    memset(cell, 0, sizeof(cell));
    cell[0] = 0x5;
    put_raw(cell, 0, 511);
    put_raw(cell, 2, 601);
    lte_b193_v66_sinr(cell, &out);
    CHECK(out.valid_mask == 0);
    CHECK(!(out.combined > LTE_B193_SINR_NA + 1));

    puts("lte_b193_sinr_test ok");
    return 0;
}
