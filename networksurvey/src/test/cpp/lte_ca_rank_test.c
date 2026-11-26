/* Host tests for the 0xB126 / 0xB184 decoders extracted from root_diag.c.
   Frames are built here from the documented per-version layouts; each case
   asserts one field so a wrong bit position fails at a named line. */
#include <stddef.h>
#include <stdint.h>

#include "lte_ca_rank_under_test.inc"

/* The canned CRT entry point expects this symbol once a floating-point
   comparison is linked in (lte_bw_mhz returns MHz as a double). */
int _fltused = 0;

#define CHECK(x) do { if (!(x)) return __LINE__; } while (0)

static void clear(uint8_t *p, int n) { for (int i = 0; i < n; i++) p[i] = 0; }

static void put_bits(uint8_t *p, int off, int bits, uint32_t v) {
    for (int i = 0; i < bits; i++) {
        int b = off + i;
        if ((v >> i) & 1) p[b >> 3] = (uint8_t)(p[b >> 3] | (1u << (b & 7)));
    }
}

static int test_b126(void) {
    /* v144: 8-byte header, carrier @ bit 8 (4), count @ bit 12 (8), 56-byte records. */
    uint8_t f[8 + 2 * 56];
    struct lte_b126_sample s[8];
    uint8_t ver = 0;
    int got;

    clear(f, sizeof f);
    f[0] = 144;
    put_bits(f, 8, 4, 1);                    /* carrier slot 1 */
    put_bits(f, 12, 8, 1);                   /* one record */
    put_bits(f + 8, 23, 4, 1);               /* rnti 1: C-RNTI */
    put_bits(f + 8, 27, 2, 1);               /* tx code 1 -> 2 antennas */
    put_bits(f + 8, 29, 2, 2);               /* rx code 2 -> 3 chains */
    put_bits(f + 8, 50, 2, 2);               /* rank 2 */
    put_bits(f + 8, 60, 4, 5);               /* scheme 5 */
    got = lte_b126_decode(f, sizeof f, s, 8, &ver);
    CHECK(got == 1);
    CHECK(ver == 144);
    CHECK(s[0].carrier == 1);
    CHECK(s[0].rnti == 1);
    CHECK(s[0].tx_code == 1 && k_lte_tx_antennas[s[0].tx_code] == 2);
    CHECK(s[0].rx_code == 2 && k_lte_rx_antennas[s[0].rx_code] == 3);
    CHECK(s[0].rank == 2);
    CHECK(s[0].scheme == 5);

    /* Two records: the second carries a non-serving RNTI that the caller filters. */
    clear(f, sizeof f);
    f[0] = 144;
    put_bits(f, 12, 8, 2);
    put_bits(f + 8 + 56, 23, 4, 3);
    put_bits(f + 8 + 56, 50, 2, 1);
    got = lte_b126_decode(f, sizeof f, s, 8, &ver);
    CHECK(got == 2);
    CHECK(s[1].rnti == 3 && s[1].rank == 1);

    /* v162: 8-byte header, 44-byte records, different field positions. */
    clear(f, sizeof f);
    f[0] = 162;
    put_bits(f, 8, 4, 2);
    put_bits(f, 12, 8, 1);
    put_bits(f + 8, 14, 4, 1);
    put_bits(f + 8, 18, 2, 2);               /* tx code 2 -> 4 antennas */
    put_bits(f + 8, 20, 2, 3);               /* rx code 3 -> 4 chains */
    put_bits(f + 8, 32, 2, 3);               /* rank 3 */
    put_bits(f + 8, 42, 4, 9);
    got = lte_b126_decode(f, 8 + 44, s, 8, &ver);
    CHECK(got == 1);
    CHECK(ver == 162);
    CHECK(s[0].carrier == 2);
    CHECK(k_lte_tx_antennas[s[0].tx_code] == 4);
    CHECK(k_lte_rx_antennas[s[0].rx_code] == 4);
    CHECK(s[0].rank == 3 && s[0].scheme == 9);

    /* v164: rec 52 bytes, rnti at bit 347. */
    clear(f, sizeof f);
    f[0] = 164;
    put_bits(f, 12, 8, 1);
    put_bits(f + 8, 347, 4, 1);
    put_bits(f + 8, 14, 2, 1);
    got = lte_b126_decode(f, 8 + 52, s, 8, &ver);
    CHECK(got == 1 && s[0].rnti == 1 && s[0].tx_code == 1);

    /* Legacy v1 has no header: records start at the payload. */
    clear(f, sizeof f);
    f[0] = 1;
    put_bits(f, 48, 4, 1);
    put_bits(f, 62, 2, 2);
    put_bits(f, 59, 3, 3);
    got = lte_b126_decode(f, 52, s, 8, &ver);
    CHECK(got == 1);
    CHECK(s[0].carrier == 0 && s[0].rank == 2 && s[0].scheme == 3);

    /* Unknown version and a truncated record. */
    clear(f, sizeof f);
    f[0] = 99;
    CHECK(lte_b126_decode(f, sizeof f, s, 8, &ver) == -1);
    f[0] = 144;
    put_bits(f, 12, 8, 1);
    CHECK(lte_b126_decode(f, 8 + 10, s, 8, &ver) == 0);
    return 0;
}

static int test_b184(void) {
    /* v40: 20-byte header, count @ bit 16, PCell @ byte 8, 12-byte SCells,
       SCell slot @ bit 88. */
    uint8_t f[20 + 8 + 2 * 12];
    struct lte_ca_cell c[33];
    uint8_t ver = 0;
    int got;

    clear(f, sizeof f);
    f[0] = 40;
    put_bits(f, 16, 8, 2);
    put_bits(f + 8, 0, 32, 1650);            /* PCell EARFCN */
    put_bits(f + 8, 32, 16, 120);            /* PCell PCI */
    put_bits(f + 8, 48, 8, 3);               /* PCell band 3 */
    put_bits(f + 8, 56, 8, 100);             /* PCell 100 RB -> 20 MHz */
    put_bits(f + 20, 0, 32, 100);            /* SCell 1 EARFCN */
    put_bits(f + 20, 32, 16, 121);
    put_bits(f + 20, 48, 8, 3);
    put_bits(f + 20, 56, 8, 50);             /* 10 MHz */
    put_bits(f + 20, 64, 8, 2);              /* activated */
    put_bits(f + 20, 88, 8, 1);              /* carrier slot 1 */
    put_bits(f + 32, 0, 32, 200);
    put_bits(f + 32, 32, 16, 122);
    put_bits(f + 32, 48, 8, 1);
    put_bits(f + 32, 56, 8, 25);             /* 5 MHz */
    put_bits(f + 32, 64, 8, 1);              /* deactivated */
    put_bits(f + 32, 88, 8, 2);
    got = lte_b184_decode(f, sizeof f, c, 33, &ver);
    CHECK(got == 3);
    CHECK(ver == 40);
    CHECK(c[0].slot == 0 && c[0].state == 2);
    CHECK(c[0].earfcn == 1650 && c[0].pci == 120 && c[0].band == 3);
    CHECK(lte_bw_mhz(c[0].bw_raw) == 20.0);
    CHECK(c[1].slot == 1 && c[1].state == 2 && c[1].pci == 121);
    CHECK(lte_bw_mhz(c[1].bw_raw) == 10.0);
    CHECK(c[2].slot == 2 && c[2].state == 1 && c[2].earfcn == 200);
    CHECK(lte_bw_mhz(c[2].bw_raw) == 5.0);

    /* v1 legacy enum: state_add 1 turns 0 -> deactivated and 1 -> activated. */
    uint8_t g[16 + 8 + 2 * 8];
    clear(g, sizeof g);
    g[0] = 1;
    put_bits(g, 16, 8, 2);
    put_bits(g + 8, 0, 32, 6300);
    put_bits(g + 8, 32, 16, 10);
    put_bits(g + 16, 0, 32, 6301);
    put_bits(g + 16, 32, 16, 11);
    put_bits(g + 16, 56, 8, 0);              /* legacy absent -> 1 */
    put_bits(g + 24, 0, 32, 6302);
    put_bits(g + 24, 32, 16, 12);
    put_bits(g + 24, 56, 8, 1);              /* legacy active -> 2 */
    got = lte_b184_decode(g, sizeof g, c, 33, &ver);
    CHECK(got == 3);
    CHECK(c[0].bw_raw == -1);                /* v1 has no bandwidth field */
    CHECK(lte_bw_mhz(c[0].bw_raw) == NA_F);
    CHECK(c[1].state == 1 && c[2].state == 2);

    /* v50: 328-byte header and 84-byte SCells (32-carrier layout). */
    int v50_len = 328 + 8 + 2 * 84;
    static uint8_t h[328 + 8 + 2 * 84];
    clear(h, sizeof h);
    h[0] = 50;
    put_bits(h, 16, 8, 2);
    put_bits(h + 8, 0, 32, 1850);
    put_bits(h + 8, 32, 16, 300);
    put_bits(h + 328, 0, 32, 1851);
    put_bits(h + 328, 32, 16, 301);
    put_bits(h + 328, 64, 8, 0);             /* absent */
    put_bits(h + 328, 88, 8, 4);             /* slot 4 */
    put_bits(h + 328 + 84, 0, 32, 1852);
    put_bits(h + 328 + 84, 32, 16, 302);
    put_bits(h + 328 + 84, 64, 8, 2);
    put_bits(h + 328 + 84, 88, 8, 5);
    got = lte_b184_decode(h, v50_len, c, 33, &ver);
    CHECK(got == 3);
    CHECK(ver == 50);
    CHECK(c[1].slot == 4 && c[1].state == 0 && c[1].pci == 301);
    CHECK(c[2].slot == 5 && c[2].state == 2 && c[2].earfcn == 1852);

    /* Duplicate slot falls back to list position + 1. */
    clear(h, 328 + 8 + 2 * 84);
    h[0] = 40;
    put_bits(h, 16, 8, 2);
    put_bits(h + 20, 88, 8, 1);
    put_bits(h + 32, 88, 8, 1);
    got = lte_b184_decode(h, 20 + 8 + 2 * 12, c, 33, &ver);
    CHECK(got == 3);
    CHECK(c[1].slot == 1 && c[2].slot == 2);

    /* Unknown version, truncated SCell list, and bandwidth decoding. */
    clear(f, sizeof f);
    f[0] = 7;
    CHECK(lte_b184_decode(f, sizeof f, c, 33, &ver) == -1);
    f[0] = 40;
    put_bits(f, 16, 8, 3);                   /* three SCells promised */
    CHECK(lte_b184_decode(f, sizeof f, c, 33, &ver) == -1);
    CHECK(lte_bw_mhz(6) == 1.4 && lte_bw_mhz(15) == 3.0 && lte_bw_mhz(75) == 15.0);
    CHECK(lte_bw_mhz(0) == 1.4 && lte_bw_mhz(3) == 10.0 && lte_bw_mhz(5) == 20.0);
    CHECK(lte_bw_mhz(-1) == NA_F && lte_bw_mhz(200) == NA_F);
    return 0;
}

__declspec(dllexport) int test_lte_ca_rank(void) {
    int r = test_b126();
    if (r) return r;
    return test_b184();
}