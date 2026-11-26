#include "../../main/cpp/band_lock_protocol.h"

#define CHECK(condition) do { if (!(condition)) return __LINE__; } while (0)
#define AF_UNIX 1
#define SOCK_SEQPACKET 2
#define SOCK_STREAM 4
#define SOCK_CLOEXEC 8
#define SOCK_NONBLOCK 16
#define POLLIN 1
#define POLLRDHUP 2
#define POLLOUT 4
#define POLLHUP 8
#define POLLERR 16
#define POLLNVAL 32
#define SOL_SOCKET 1
#define SO_ERROR 1
#define ENOTCONN 1
#define EINTR 2
#define EAGAIN 3
#define EINPROGRESS 4
#define O_RDWR 1
#define O_NOCTTY 2
#define O_NONBLOCK 4

struct pollfd { int fd, events, revents; };
struct sockaddr { int family; };
struct sockaddr_un { int sun_family; char sun_path[108]; };
typedef unsigned socklen_t;

enum { SAMSUNG = 1, STREAM = 2, SEQPACKET = 3, AT_PORT = 10 };
static uint32_t client_id, transaction = 0x80;
static int router = -1, errno;
static char connected_label[64];
static int available, registrations, writes, closed, candidate_count, pending, peer_closed, write_fails, at_reads;
static int candidates[12];
static long long now;
static char at_output[128];
static unsigned at_output_size;
static int at_printing;
#define stdout 0

void *memset(void *target, int value, size_t size) {
    volatile uint8_t *p = target;
    while (size--) *p++ = (uint8_t)value;
    return target;
}

void *memcpy(void *target, const void *source, size_t size) {
    uint8_t *p = target;
    const uint8_t *q = source;
    while (size--) *p++ = *q++;
    return target;
}

static int strcmp(const char *a, const char *b) {
    while (*a && *a == *b) { a++; b++; }
    return *a - *b;
}

static int snprintf(char *out, size_t size, const char *format, const char *value) {
    unsigned i = 0;
    while (i + 2 < size && *value) out[i++] = *value++;
    if (format[2] == '\r') out[i++] = '\r';
    out[i] = 0;
    return (int)i;
}

static int putchar(int c) {
    if (at_printing && at_output_size + 1 < sizeof(at_output))
        at_output[at_output_size++] = (char)c;
    return c;
}
static int fputs(const char *text, int stream) {
    (void)stream;
    while (*text) putchar(*text++);
    return 0;
}
static int puts(const char *text) { fputs(text, stdout); return putchar('\n'); }
static int printf(const char *format, ...) { (void)format; return 0; }
static int fail(const char *text) { (void)text; return 1; }
static long long monotonic_ms(void) { return now; }
static unsigned sleep(unsigned seconds) { now += 1000 * seconds; return 0; }
static int getpid(void) { return 5; }

static int socket(int family, int type, int protocol) {
    (void)family; (void)protocol;
    return type & SOCK_SEQPACKET ? SOCK_SEQPACKET : SOCK_STREAM;
}
static int connect(int fd, const struct sockaddr *address, socklen_t size) {
    (void)address;
    int kind = fd == SOCK_STREAM ? STREAM :
        size == sizeof(struct sockaddr_un) ? SAMSUNG : SEQPACKET;
    candidates[candidate_count++] = kind;
    errno = ENOTCONN;
    return kind == available ? 0 : -1;
}
static int getsockopt(int fd, int level, int opt, void *value, socklen_t *size) {
    (void)fd; (void)level; (void)opt; (void)value; (void)size;
    return -1;
}
static int close(int fd) {
    if (fd != AT_PORT) {
        closed++;
        pending = 0;
        peer_closed = 0;
    }
    return 0;
}
static int poll(struct pollfd *p, int count, int timeout) {
    (void)count;
    if (p->fd != AT_PORT && peer_closed) {
        p->revents = POLLHUP;
        return 1;
    }
    if ((p->events & POLLIN) && (p->fd == AT_PORT ? at_reads <= 2 : pending)) {
        p->revents = POLLIN;
        return 1;
    }
    now += timeout;
    return 0;
}
static int recv(int fd, void *buffer, size_t size, int flags) {
    (void)fd; (void)size; (void)flags;
    pending = 0;
    lb_u32(buffer, 0x20000);
    lb_u32((uint8_t *)buffer + 4, (uint32_t)registrations);
    return 8;
}
static long long write(int fd, const void *bytes, size_t size) {
    if (fd != AT_PORT && lb_read32(bytes) == 0x17) {
        registrations++;
        pending = 1;
    } else if (fd != AT_PORT) {
        writes++;
        if (write_fails) { errno = ENOTCONN; return -1; }
    }
    return (long long)size;
}
static int open(const char *path, int flags) { (void)path; (void)flags; return AT_PORT; }
static long long read(int fd, void *buffer, size_t size) {
    (void)fd; (void)size;
    switch (at_reads++) {
        case 1: memcpy(buffer, "SESSION ex", 10); return 10;
        case 2: memcpy(buffer, "it 2\nOK", 7); return 7;
        default: return -1;
    }
}
static void observe_modem_restart(void) {}

#include "band_lock_session_under_test.inc"

__declspec(dllexport) int test_band_lock_session(void) {
    const uint8_t command[] = {0x4b, 0x0f, 0x02, 0};
    available = STREAM;
    CHECK(ensure_router("samsung-diag-router") == 0);
    CHECK(candidate_count == 2 && candidates[0] == SAMSUNG && candidates[1] == STREAM);
    CHECK(client_id == 1 && strcmp(connected_label, "diag-stream") == 0);
    CHECK(ensure_router(NULL) == 0 && registrations == 1);

    CHECK(reboot_modem(0) == 0 && router == -1 && writes == 1);
    CHECK(ensure_router("samsung-diag-router") == 0);
    CHECK(registrations == 2 && client_id == 2 && candidates[2] == STREAM);
    CHECK(exchange(command, sizeof(command), LB_ACK_LOOSE, 0, 0, 0) == 1);
    CHECK(router == -1 && registrations == 2 && writes == 2);

    available = SAMSUNG;
    CHECK(ensure_router(NULL) == 0 && candidates[3] == STREAM && candidates[4] == SAMSUNG);
    peer_closed = 1;
    CHECK(receive(NULL, 0, 10) == -1 && router == -1);
    CHECK(ensure_router(NULL) == 0 && registrations == 4 && candidates[5] == SAMSUNG);
    write_fails = 1;
    CHECK(exchange(command, sizeof(command), LB_ACK_LOOSE, 0, 0, 0) == 1);
    CHECK(router == -1 && registrations == 4 && writes == 3);
    CHECK(closed == 6);

    at_printing = 1;
    CHECK(at_command("/dev/test", "AT") == 0);
    puts("SESSION exit 0");
    CHECK(strcmp(at_output, "[AT] <- SESSION exit 2\n[AT] <- OK\nSESSION exit 0\n") == 0);
    return 0;
}
