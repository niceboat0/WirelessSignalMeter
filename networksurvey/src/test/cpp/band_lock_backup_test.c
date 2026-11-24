#include "../../main/cpp/band_lock_protocol.h"

#define O_RDONLY 0
#define O_WRONLY 1
#define O_CREAT 2
#define O_EXCL 4
#define O_NOFOLLOW 8
#define O_CLOEXEC 16
#define AT_SYMLINK_NOFOLLOW 256
#define ENOENT 2
#define CHECK(condition) do { if (!(condition)) return __LINE__; } while (0)

static int state_dir = 100, errno;
static int stored, nsa_stored, nsa_readable, read_error, open_error, write_error, file_sync_error, dir_sync_error;
static int marker, nsa_corrupt, readback_error, mismatch, put_error, remove_error;
static int reads, creates, writes, file_syncs, dir_syncs, unlinks, removes, cm, efs, restarts, restart_observed;
static int wrote_primary, wrote_nsa;
static uint8_t saved[64], primary_written[64], nsa_written[64];

void *memset(void *target, int value, size_t size) {
    volatile uint8_t *p = target;
    for (size_t i = 0; i < size; i++) p[i] = (uint8_t)value;
    return target;
}

void *memcpy(void *target, const void *source, size_t size) {
    uint8_t *dst = target;
    const uint8_t *src = source;
    for (size_t i = 0; i < size; i++) dst[i] = src[i];
    return target;
}

int memcmp(const void *left, const void *right, size_t size) {
    const uint8_t *a = left, *b = right;
    for (size_t i = 0; i < size; i++) if (a[i] != b[i]) return (int)a[i] - b[i];
    return 0;
}

static void reset(void) {
    stored = nsa_stored = nsa_readable = read_error = open_error = write_error = file_sync_error = dir_sync_error = 0;
    marker = nsa_corrupt = readback_error = mismatch = put_error = remove_error = 0;
    reads = creates = writes = file_syncs = dir_syncs = unlinks = removes = cm = efs = restarts = restart_observed = 0;
    wrote_primary = wrote_nsa = 0;
    memset(saved, 0, sizeof(saved));
}

static int fail(const char *message) { (void)message; return 1; }
static int puts(const char *message) { (void)message; return 0; }
static int printf(const char *format, ...) { (void)format; return 0; }
static int is_nsa_path(const char *path) {
    return path[sizeof(LB_MMODE "/nr_") - 1] == 'n';
}
static int read_mask(const char *path, int bytes, uint8_t *mask) {
    reads++;
    if (read_error) return 1;
    if (bytes == 64) {
        int nsa = is_nsa_path(path);
        if (nsa ? wrote_nsa : wrote_primary) {
            if (readback_error) return 1;
            memcpy(mask, nsa ? nsa_written : primary_written, (size_t)bytes);
            if (mismatch) mask[0] ^= 1;
            return 0;
        }
        if (nsa && !nsa_readable) return 1;
    }
    for (int i = 0; i < bytes; i++) mask[i] = (uint8_t)(i + 1);
    return 0;
}
/* nr_nsa_present() probes through read_mask_to, not read_mask. */
static int read_mask_to(const char *path, int bytes, uint8_t *mask,
        int mask_cap, int timeout_ms) {
    (void)mask_cap; (void)timeout_ms;
    return read_mask(path, bytes, mask);
}
static int restore_mask(const char *name, int bytes, uint8_t *mask) {
    int nsa = name[0] == 'n' && name[3] == 'n';
    if (nsa ? !nsa_stored : !stored) return 1;
    memset(mask, 0, (size_t)bytes);
    mask[0] = nsa ? 0x80 : 0x10;
    return 0;
}
struct stat { int unused; };
static int fstatat(int dir, const char *name, struct stat *info, int flags) {
    (void)dir; (void)info; (void)flags;
    if (name[3] == 'n' && name[4] == 'o' && marker) return 0;
    if (name[3] == 'n' && name[4] == 's' && nsa_corrupt) return 0;
    errno = ENOENT;
    return -1;
}
static int openat(int dir, const char *name, int flags, ...) {
    (void)dir; (void)name; (void)flags;
    creates++;
    if (open_error) return -1;
    return 5;
}
static long long write(int fd, const void *data, size_t bytes) {
    (void)fd;
    writes++;
    const uint8_t *p = data;
    for (size_t i = 0; i < bytes; i++) saved[i] = p[i];
    return write_error ? (long long)bytes - 1 : (long long)bytes;
}
static int fsync(int fd) {
    if (fd == state_dir) { dir_syncs++; return dir_sync_error; }
    file_syncs++;
    return file_sync_error;
}
static int close(int fd) { (void)fd; return 0; }
static int unlinkat(int dir, const char *name, int flags) {
    (void)dir; (void)name; (void)flags; unlinks++; return 0;
}
static int put_file(const char *dir, const char *file, const char *sync,
        const uint8_t *data, int bytes, uint8_t a, uint8_t b, int lte) {
    (void)dir; (void)sync; (void)a; (void)b;
    efs++;
    if (put_error) return 1;
    if (!lte) {
        int nsa = is_nsa_path(file);
        memcpy(nsa ? nsa_written : primary_written, data, (size_t)bytes);
        if (nsa) wrote_nsa++;
        else wrote_primary++;
    }
    return 0;
}
static int remove_file(const char *path, const char *dir) {
    (void)path; (void)dir;
    removes++;
    return remove_error;
}
static int exchange(const uint8_t *data, int bytes, int kind, uint32_t tag, int a, int b) {
    (void)data; (void)bytes; (void)kind; (void)tag; (void)a; (void)b;
    cm++;
    return 0;
}
static int reboot_modem(int observe) { restarts++; restart_observed = observe; return 0; }

/* The runner generates this include from the actual production functions. */
#include "band_lock_backup_under_test.inc"

__declspec(dllexport) int test_band_lock_backup(void) {
    reset();
    CHECK(lte_band_command("5", 0) == 0);
    CHECK(reads == 1 && creates == 1 && writes == 1 && file_syncs == 1 && dir_syncs == 1);
    CHECK(cm == 1 && efs == 0);
    for (int i = 0; i < 8; i++) CHECK(saved[i] == i + 1);

    reset(); read_error = 1;
    CHECK(lte_band_command("5", 0) == 0 && creates == 0 && cm == 1 && efs == 0);

    reset(); read_error = 1;
    CHECK(lte_band_command("41", 0) != 0 && creates == 0 && cm == 0 && efs == 0);

    reset(); open_error = 1;
    CHECK(lte_band_command("5", 0) != 0 && cm == 0 && efs == 0);

    reset(); write_error = 1;
    CHECK(lte_band_command("41", 0) != 0 && unlinks == 1 && cm == 0 && efs == 0);

    reset(); file_sync_error = 1;
    CHECK(lte_band_command("5", 0) != 0 && unlinks == 1 && cm == 0 && efs == 0);

    reset(); dir_sync_error = 1;
    CHECK(lte_band_command("41", 0) != 0 && cm == 0 && efs == 0);

    reset();
    CHECK(lte_band_command("41", 0) == 0 && writes == 1 && efs == 1 && restarts == 1 && cm == 0);

    reset(); stored = 1;
    CHECK(lte_band_command("5", 0) == 0 && reads == 0 && creates == 0 && cm == 1);
    CHECK(lte_band_command(0, 1) == 0 && cm == 2 && creates == 0);

    reset(); read_error = 1;
    CHECK(lte_band_command(0, 1) != 0 && cm == 0 && efs == 0);

    reset(); open_error = 1;
    CHECK(nr_band_command("78", 0) != 0 && creates == 1 && marker == 0 && efs == 0);

    reset(); write_error = 1;
    CHECK(nr_band_command("78", 0) != 0 && unlinks == 1 && marker == 0 && efs == 0);

    reset(); file_sync_error = 1;
    CHECK(nr_band_command("78", 0) != 0 && unlinks == 1 && efs == 0);

    reset(); dir_sync_error = 1;
    CHECK(nr_band_command("78", 0) != 0 && efs == 0);

    reset(); read_error = 1;
    CHECK(nr_band_command("78", 0) != 0 && creates == 0 && efs == 0 && restarts == 0);

    reset(); nsa_readable = 1;
    CHECK(nr_band_command("78", 0) == 0 && reads == 5 && writes == 2 && creates == 2
        && efs == 2 && restarts == 1 && restart_observed);

    reset(); stored = 1;
    CHECK(nr_band_command("78", 0) == 0 && reads == 2 && creates == 0 && efs == 1 && restarts == 1);

    /* Restore exact, independent masks and do not touch an unsaved NSA item. */
    reset(); stored = 1; nsa_readable = 1;
    CHECK(nr_band_command(0, 1) == 0 && reads == 1 && creates == 0 && efs == 1 && restarts == 1 && restart_observed);
    CHECK(wrote_primary == 1 && primary_written[0] == 0x10 && wrote_nsa == 0);

    reset(); stored = nsa_stored = 1;
    CHECK(nr_band_command(0, 1) == 0 && reads == 2 && efs == 2 && restarts == 1);
    CHECK(primary_written[0] == 0x10 && nsa_written[0] == 0x80);
    for (int i = 1; i < 64; i++) CHECK(primary_written[i] == 0 && nsa_written[i] == 0);

    reset(); stored = 1; readback_error = 1;
    CHECK(nr_band_command(0, 1) != 0 && efs == 1 && restarts == 0);
    reset(); stored = nsa_stored = 1; mismatch = 1;
    CHECK(nr_band_command(0, 1) != 0 && wrote_nsa == 1 && wrote_primary == 0 && restarts == 0);
    reset(); stored = 1; put_error = 1;
    CHECK(nr_band_command(0, 1) != 0 && efs == 1 && reads == 0 && restarts == 0);
    reset(); stored = 1; nsa_corrupt = 1;
    CHECK(nr_band_command(0, 1) != 0 && efs == 0 && restarts == 0);
    reset(); stored = 1; marker = 1;
    CHECK(nr_band_command("1", 0) != 0 && reads == 0 && creates == 0
        && efs == 0 && removes == 0 && unlinks == 0 && restarts == 0);

    /* Cover the already-committed legacy restore branch without treating its
     * saved masks as a verified baseline. */
    reset(); stored = 1; marker = 1;
    CHECK(nr_band_command(0, 1) == 0 && reads == 1 && removes == 1
        && efs == 0 && unlinks == 3 && restarts == 1 && restart_observed);
    reset(); marker = 1; nsa_readable = 1;
    CHECK(nr_band_command(0, 1) == 0 && reads == 1 && removes == 2
        && efs == 0 && unlinks == 3 && restarts == 1 && restart_observed);
    reset(); marker = 1; remove_error = 1;
    CHECK(nr_band_command(0, 1) != 0 && reads == 0 && removes == 1
        && efs == 0 && unlinks == 0 && restarts == 0);
    reset();
    CHECK(nr_band_command(0, 1) != 0 && efs == 0 && restarts == 0);
    return 0;
}
