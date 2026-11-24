/* QMDL2 container writer.
 *
 * The recorder in root_diag.c emits the v1 container by default: a stream of
 * HDLC frames (0x7e ... 0x7e) holding a 4-byte DIAG_LOG_F preamble, a
 * CRC-16/X-25 trailer and, inside the DIAG log header, a 64-bit packet
 * timestamp. That is what SCAT and the QCSuper lineage read, so it stays the
 * default and the app keeps calling the collector without a flag.
 *
 * QMDL2 is the newer container QXDM/QCAT can open:
 *
 *   file header : "QMDL" 02 00 00 00                      (8 bytes)
 *   record      : type u32 (=1, DIAG log)
 *                 len  u32  payload length
 *                 sec  u32  Unix time, seconds
 *                 usec u32  Unix time, microseconds
 *                 payload   the DIAG_LOG_F packet
 *
 * Measured on SCAT 2.1.2: a v1 file yields 5/5 packets with no CRC error, a
 * QMDL2 file yields 0/5 - SCAT's dump reader splits on 0x7e and has no QMDL2
 * parser, so QMDL2 is opt-in (--qmdl2) and not a replacement.
 *
 * The payload deliberately keeps the same 4-byte preamble the v1 writer uses
 * (0x10, 0x00, length low, length high) followed by the raw record bytes, so
 * v1 and v2 files carry identical packet bytes and only the framing differs.
 *
 * No I/O and no libc calls: the host tests compile this file freestanding.
 */
#ifndef QMDL2_FORMAT_H
#define QMDL2_FORMAT_H

#include <stddef.h>
#include <stdint.h>

#define QMDL2_HEADER_SIZE 8u
#define QMDL2_RECORD_HEADER 16u
#define QMDL2_PAYLOAD_PREAMBLE 4u
#define QMDL2_TYPE_DIAG_LOG 1u

static const uint8_t k_qmdl2_magic[QMDL2_HEADER_SIZE] = { 'Q', 'M', 'D', 'L', 2, 0, 0, 0 };

static void qmdl2_put32(uint8_t *out, uint32_t value) {
    out[0] = (uint8_t)value;
    out[1] = (uint8_t)(value >> 8);
    out[2] = (uint8_t)(value >> 16);
    out[3] = (uint8_t)(value >> 24);
}

/* Payload size for a raw log record of `len` bytes. */
static size_t qmdl2_payload_size(uint16_t len) {
    return (size_t)len + QMDL2_PAYLOAD_PREAMBLE;
}

/* Total on-disk size of one record. */
static size_t qmdl2_record_size(uint16_t len) {
    return QMDL2_RECORD_HEADER + qmdl2_payload_size(len);
}

/* Writes one complete record into out (at least qmdl2_record_size(len) bytes)
 * and returns the number of bytes written. */
static size_t qmdl2_record(uint8_t *out, const uint8_t *rec, uint16_t len,
        uint32_t seconds, uint32_t microseconds) {
    size_t payload = qmdl2_payload_size(len);
    qmdl2_put32(out, QMDL2_TYPE_DIAG_LOG);
    qmdl2_put32(out + 4, (uint32_t)payload);
    qmdl2_put32(out + 8, seconds);
    qmdl2_put32(out + 12, microseconds);
    out[16] = 0x10;
    out[17] = 0x00;
    out[18] = (uint8_t)len;
    out[19] = (uint8_t)(len >> 8);
    for (uint16_t i = 0; i < len; i++) out[20 + i] = rec[i];
    return QMDL2_RECORD_HEADER + payload;
}

#endif