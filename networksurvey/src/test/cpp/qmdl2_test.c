#include "../../main/cpp/qmdl2_format.h"

#define CHECK(condition) do { if (!(condition)) return __LINE__; } while (0)

/* Freestanding host test: no modem, no libc, no file I/O. */
__declspec(dllexport) int test_qmdl2(void) {
    uint8_t out[64];
    /* Same record the v1 writer test uses, so the two containers can be
     * compared byte for byte by hand. */
    const uint8_t rec[12] = { 12, 0, 0x7d, 0x7e, 0, 1, 2, 3, 4, 5, 6, 7 };

    CHECK(QMDL2_HEADER_SIZE == 8 && QMDL2_RECORD_HEADER == 16);
    CHECK(k_qmdl2_magic[0] == 'Q' && k_qmdl2_magic[1] == 'M' &&
          k_qmdl2_magic[2] == 'D' && k_qmdl2_magic[3] == 'L');
    CHECK(k_qmdl2_magic[4] == 2 && k_qmdl2_magic[5] == 0 &&
          k_qmdl2_magic[6] == 0 && k_qmdl2_magic[7] == 0);

    CHECK(qmdl2_payload_size(12) == 16);
    CHECK(qmdl2_record_size(12) == 32);
    CHECK(qmdl2_record_size(0) == 20);

    size_t size = qmdl2_record(out, rec, 12, 0x11223344u, 0x55667788u);
    CHECK(size == 32);

    /* Record header: type 1, payload length 16, then seconds and microseconds,
     * all little endian. */
    CHECK(out[0] == 1 && out[1] == 0 && out[2] == 0 && out[3] == 0);
    CHECK(out[4] == 16 && out[5] == 0 && out[6] == 0 && out[7] == 0);
    CHECK(out[8] == 0x44 && out[9] == 0x33 && out[10] == 0x22 && out[11] == 0x11);
    CHECK(out[12] == 0x88 && out[13] == 0x77 && out[14] == 0x66 && out[15] == 0x55);

    /* Payload keeps the v1 DIAG_LOG_F preamble, then the record verbatim. */
    CHECK(out[16] == 0x10 && out[17] == 0x00 && out[18] == 12 && out[19] == 0);
    for (int i = 0; i < 12; i++) CHECK(out[20 + i] == rec[i]);

    /* v2 has no HDLC framing: 0x7d/0x7e stay unescaped in the payload. */
    CHECK(out[22] == 0x7d && out[23] == 0x7e);
    CHECK(out[31] == 7);

    /* Records longer than 255 bytes still carry their full length. */
    static uint8_t big[300];
    static uint8_t wide[320];
    for (int i = 0; i < 300; i++) big[i] = (uint8_t)(i * 7);
    CHECK(qmdl2_record(wide, big, 300, 1, 2) == 320);
    CHECK(wide[4] == 48 && wide[5] == 1 && wide[6] == 0 && wide[7] == 0);   /* 304 */
    CHECK(wide[8] == 1 && wide[12] == 2);
    CHECK(wide[18] == (uint8_t)(300 & 0xff) && wide[19] == (uint8_t)(300 >> 8));
    CHECK(wide[20] == 0 && wide[21] == 7 && wide[319] == (uint8_t)((299 * 7) & 0xff));
    return 0;
}