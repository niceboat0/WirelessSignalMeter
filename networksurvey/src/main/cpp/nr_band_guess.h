#ifndef NR_BAND_GUESS_H
#define NR_BAND_GUESS_H
/*
 * Last-resort NR band when no RRC/B823/B825 band has been seen.
 * Ranges that TS 38.104 puts in more than one deployed band are left unknown.
 * n78 sits inside n77, n48 sits inside both, and n38/n7 sit inside n41.
 * A later explicit band still replaces this guess.
 */
static int nr_band_guess(long n) {
    static const struct { long lo, hi; int b; } t[] = {
        {151600, 160600, 28}, {173800, 178800, 5}, {185000, 192000, 8},
        {361000, 376000, 3}, {422000, 434000, 1},
        {499200, 513999, 41},
        {653334, 680000, 77}, {693334, 733333, 79},
        {2054166, 2104165, 258},
    };
    for (unsigned i = 0; i < sizeof(t) / sizeof(t[0]); i++)
        if (n >= t[i].lo && n <= t[i].hi) return t[i].b;
    return 0;
}

#endif
