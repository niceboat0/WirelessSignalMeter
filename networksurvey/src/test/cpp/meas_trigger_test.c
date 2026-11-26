/* Host tests for the measurement-trigger conversions extracted from
   root_diag.c / sigdec.c: reported ranges -> dB values and the ENUMERATED
   member names ("dB2", "ms320", "dB-30") -> numbers. */
#include <stddef.h>
#include <stdint.h>

/* Freestanding host target: supply the three string helpers the extracted
   code uses instead of a system string.h. */
static size_t strlen(const char *s) {
    size_t n = 0;
    while (s[n]) n++;
    return n;
}
static int strncmp(const char *a, const char *b, size_t n) {
    for (size_t i = 0; i < n; i++) {
        if (a[i] != b[i]) return (unsigned char)a[i] - (unsigned char)b[i];
        if (!a[i]) return 0;
    }
    return 0;
}
static int strcmp(const char *a, const char *b) {
    while (*a && *a == *b) { a++; b++; }
    return (unsigned char)*a - (unsigned char)*b;
}

#include "meas_trigger_under_test.inc"

/* lte_bw_mhz-style double comparisons link a floating-point helper. */
int _fltused = 0;

#define CHECK(x) do { if (!(x)) return __LINE__; } while (0)

__declspec(dllexport) int test_meas_trigger(void) {
    /* LTE RSRP-Range: 0..97 -> -140..-43 dBm. */
    CHECK(meas_threshold_db(0, SD_Q_RSRP, 0) == -140.0);
    CHECK(meas_threshold_db(0, SD_Q_RSRP, 32) == -108.0);
    CHECK(meas_threshold_db(0, SD_Q_RSRP, 97) == -43.0);
    CHECK(meas_threshold_db(0, SD_Q_RSRP, 98) == NA_F);
    /* NR RSRP-Range: 0..126 -> -156..-30 dBm. */
    CHECK(meas_threshold_db(1, SD_Q_RSRP, 0) == -156.0);
    CHECK(meas_threshold_db(1, SD_Q_RSRP, 126) == -30.0);
    CHECK(meas_threshold_db(1, SD_Q_RSRP, 127) == NA_F);
    /* LTE RSRQ-Range: 0..34 in 0.5 dB steps from -19.5 dB. */
    CHECK(meas_threshold_db(0, SD_Q_RSRQ, 0) == -19.5);
    CHECK(meas_threshold_db(0, SD_Q_RSRQ, 34) == -2.5);
    CHECK(meas_threshold_db(0, SD_Q_RSRQ, 35) == NA_F);
    /* NR RSRQ-Range: 0..126 in 0.5 dB steps from -43 dB. */
    CHECK(meas_threshold_db(1, SD_Q_RSRQ, 0) == -43.0);
    CHECK(meas_threshold_db(1, SD_Q_RSRQ, 86) == 0.0);
    CHECK(meas_threshold_db(1, SD_Q_RSRQ, 127) == NA_F);
    /* NR SINR-Range: 0..126 in 0.5 dB steps from -23 dB. */
    CHECK(meas_threshold_db(1, SD_Q_SINR, 0) == -23.0);
    CHECK(meas_threshold_db(1, SD_Q_SINR, 46) == 0.0);
    CHECK(meas_threshold_db(1, SD_Q_SINR, 126) == 40.0);
    CHECK(meas_threshold_db(1, SD_Q_SINR, 127) == NA_F);
    /* Unknown quantity has no scale. */
    CHECK(meas_threshold_db(0, SD_Q_NONE, 20) == NA_F);

    CHECK(!strcmp(meas_quantity_name(SD_Q_RSRP), "RSRP"));
    CHECK(!strcmp(meas_quantity_name(SD_Q_RSRQ), "RSRQ"));
    CHECK(!strcmp(meas_quantity_name(SD_Q_SINR), "SINR"));
    CHECK(meas_quantity_name(SD_Q_NONE)[0] == '\0');

    /* ENUMERATED member names. */
    CHECK(sd_enum_num("dB2", "dB") == 2);
    CHECK(sd_enum_num("dB0", "dB") == 0);
    CHECK(sd_enum_num("dB15", "dB") == 15);
    CHECK(sd_enum_num("dB-30", "dB") == -30);
    CHECK(sd_enum_num("dB30", "dB") == 30);
    CHECK(sd_enum_num("ms320", "ms") == 320);
    CHECK(sd_enum_num("ms0", "ms") == 0);
    CHECK(sd_enum_num("ms10240", "ms") == 10240);
    /* Prefix must match and a number must follow. */
    CHECK(sd_enum_num("ms320", "dB") == -1);
    CHECK(sd_enum_num("dB", "dB") == -1);
    CHECK(sd_enum_num("dBx", "dB") == -1);
    CHECK(sd_enum_num("xyz5", "dB") == -1);
    return 0;
}