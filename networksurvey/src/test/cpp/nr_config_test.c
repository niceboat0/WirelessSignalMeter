#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>

int snprintf(char *, size_t, const char *, ...);
int _vsnprintf(char *, size_t, const char *, va_list);
int strcmp(const char *, const char *);
void *memset(void *, int, size_t);
double log10(double);
#define vsnprintf _vsnprintf

/* MSVC floating-point marker for the freestanding test DLL. */
int _fltused = 0;

static int64_t clock_ms;
static int64_t mono_ms(void) { return clock_ms; }
static int64_t pkt_now_ms(void) { return clock_ms; }
static char output[8192];
static void emit_locked(const char *line) { snprintf(output, sizeof(output), "%s", line); }
static void layout_seen(uint16_t code, uint32_t ver, const uint8_t *b, int n) {}
static void unknown_layout(uint16_t code, uint32_t ver, const uint8_t *b, int n, const char *why) {}
static void layout_variant(uint16_t code, uint32_t ver, int blen, int records) {}

#include "nr_config_under_test.inc"

static struct { int pci; long arfcn; } g_nr = { 0, 627264 };
static int primary_calls;
static void emit_nr_snr(const char *source, uint32_t version, int pci, long arfcn, int carrier,
                        int trs, const uint8_t *samples, int stride, int throttle) {
    if (!carrier) primary_calls++;
}

#include "nr_snr_under_test.inc"
#include "nr_pdsch_under_test.inc"

static void put_le(uint8_t *p, uint32_t v, int bytes) {
    for (int i = 0; i < bytes; i++) p[i] = (uint8_t)(v >> (8 * i));
}

__declspec(dllexport) const char *nr_config_feed(int count, int tail_pci_delta, int truncate, int64_t now) {
    uint8_t b[72 + 17 * 26];
    for (size_t i = 0; i < sizeof(b); i++) b[i] = 0;
    b[0] = 8;
    b[2] = 3;
    b[12] = 2;
    b[69] = (uint8_t)count;
    for (int i = 0; i < count; i++) {
        uint8_t *c = b + 72 + i * 26;
        c[0] = (uint8_t)i;
        put_le(c + 1, 100 + i + (i == count - 1 ? tail_pci_delta : 0), 2);
        put_le(c + 3, 635000 + 1000 * i, 4);
        put_le(c + 11, 634990 + 1000 * i, 4);
        put_le(c + 15, 78, 2);
        c[18] = 14;
        c[20] = 1;
    }
    output[0] = 0;
    clock_ms = now;
    parse_nr_b825(b, 72 + count * 26 - truncate);
    return output;
}

__declspec(dllexport) const char *nr_ca_feed(int version, int count, int length_delta, int fault) {
    uint8_t b[24 + 17 * 24 + 1] = { 0 };
    put_le(b, (uint32_t)version, 4);
    int count_at = version == 0x20003 ? 5 : 9;
    b[count_at] = (uint8_t)count;
    for (int i = 0; i < count; i++) {
        uint8_t *c = b + 24 + i * 24;
        c[0] = (uint8_t)i;
        int sul = count == 3 && i == 2;
        put_le(c + 2, sul || i == 0 ? 65535 : 100 + i, 2);
        put_le(c + 4, sul ? 0 : i == 0 ? 3 : 2, 2);
        put_le(c + 8, sul || i == 0 ? 0 : 635000 + 1000 * i, 4);
        put_le(c + 12, sul ? 79 : 77, 2);
        put_le(c + 16, sul ? 2 : 1, 2);
    }
    uint8_t *secondary = b + 48;
    switch (fault) {
        case 1: secondary[0] = 0; break;
        case 2: secondary[0] = 16; break;
        case 3: put_le(secondary + 4, 4, 2); break;
        case 4: put_le(secondary + 16, 65535, 2); break;
        case 5: put_le(secondary + 8, 0, 4); break;
        case 6: b[count_at] = 1; break;
        case 7: b[count_at] = 0; break;
        case 8: b[count_at] = 1; secondary[0] = 0; break;
        case 9: b[count_at] = 1; put_le(secondary + 8, 0, 4); break;
        case 10: b[24 + 8 * 24] = 1; break; /* non-zero trailing slot beyond count */
    }
    output[0] = 0;
    parse_nr_b9a7(b, 24 + count * 24 + length_delta);
    return output;
}

__declspec(dllexport) const char *nr_pdsch_feed(int version, int count, int rows,
        int length_delta, int fault, int sequence, int64_t now) {
    uint8_t b[28 + 9 * 72 + 1] = { 0 };
    int header = version == 0x20002 ? 28 : 20;
    put_le(b, (uint32_t)version, 4);
    if (version == 0x20002) b[15] = (uint8_t)count;
    else if (version == 0x30000) b[8] = (uint8_t)(count << 4);
    else b[7] = (uint8_t)count;
    for (int i = 0; i < rows; i++) {
        uint8_t *p = b + header + i * 72;
        int scale = sequence + 1;
        put_le(p, (uint32_t)i | (version == 0x30001 ? 0x10000u : 0), 4);
        put_le(p + 4, (uint32_t)(1000 * scale), 4);
        put_le(p + 8, (uint32_t)(100 * scale), 4);
        put_le(p + 12, (uint32_t)(90 * scale), 4);
        put_le(p + 16, (uint32_t)(10 * scale), 4);
        put_le(p + 20, (uint32_t)(5 * scale), 4);
        put_le(p + 32, (uint32_t)((1000 + 100 * i) * scale), 4);
        put_le(p + 40, (uint32_t)(200 * scale), 4);
        put_le(p + 48, (uint32_t)((1200 + 100 * i) * scale), 4);
        put_le(p + 56, (uint32_t)(100 * scale), 4);
    }
    uint8_t *secondary = b + header + 72;
    switch (fault) {
        case 1: secondary[0] = 0; break;
        case 2: secondary[0] = 8; break;
        case 3: put_le(secondary + 32, 9999, 4); break;
        case 4: put_le(secondary + 40, 0, 4); break;
    }
    if (!sequence) {
        memset(g_pd_prev, 0, sizeof(g_pd_prev));
        memset(g_pd_base, 0, sizeof(g_pd_base));
        g_pd_t = 0;
    }
    output[0] = 0;
    clock_ms = now;
    parse_nr_b888(b, header + rows * 72 + length_delta);
    return output;
}

__declspec(dllexport) const char *nr_snr_feed(int carrier, int pci, int version, int trs, int length, int sample, int64_t now) {
    uint8_t b[28] = { 0 };
    put_le(b, (uint32_t)version, 4);
    put_le(b + 4, (uint32_t)carrier << 26 | (uint32_t)pci << 16, 4);
    b[8] = (uint8_t)trs;
    put_le(b + 12, (uint32_t)sample, 4);
    clock_ms = now;
    output[0] = 0;
    parse_nr_b8d8(b, length);
    return output;
}

__declspec(dllexport) int nr_snr_primary_calls(void) { return primary_calls; }
