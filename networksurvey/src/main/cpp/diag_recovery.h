#ifndef DIAG_RECOVERY_H
#define DIAG_RECOVERY_H

#include <stdint.h>

#define DIAG_RECOVERY_FIRST_MS 15000LL
#define DIAG_RECOVERY_RETRY_MS 30000LL
#define DIAG_RECOVERY_MAX_ATTEMPTS 2

static inline int diag_recovery_due(int64_t silent_ms, int attempts) {
    if (silent_ms < 0 || attempts < 0 || attempts >= DIAG_RECOVERY_MAX_ATTEMPTS) return 0;
    return silent_ms >= DIAG_RECOVERY_FIRST_MS + attempts * DIAG_RECOVERY_RETRY_MS;
}

#endif
