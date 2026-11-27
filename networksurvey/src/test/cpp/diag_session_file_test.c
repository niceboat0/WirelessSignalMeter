#define _GNU_SOURCE
/* Host test: a live session creates no files; only an explicit start writes, and stop closes. */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int g_fd = -1;
static char g_dir[256];
static char g_active[300];
static char g_committed[300];

static void fail(const char *msg) {
    fprintf(stderr, "diag_session_file_test: %s (errno=%d)\n", msg, errno);
    exit(1);
}

static void live_creates_nothing(void) {
    struct stat st;
    if (stat(g_active, &st) == 0) fail("live mode must not create active.qmdl");
}

static void start_session(void) {
    int fd = open(g_active, O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (fd < 0) fail("start must create an empty file");
    g_fd = fd;
}

static void write_bytes(const char *data, size_t n) {
    if (g_fd < 0) fail("write without an open session");
    if (write(g_fd, data, n) != (ssize_t)n) fail("write");
}

static void stop_session(int expect_commit) {
    if (g_fd < 0) fail("stop without session");
    if (close(g_fd) != 0) fail("close");
    g_fd = -1;
    if (!expect_commit) {
        if (unlink(g_active) != 0) fail("unlink empty");
        return;
    }
    if (rename(g_active, g_committed) != 0) fail("rename to task file");
}

static void write_after_stop_is_ignored(void) {
    if (g_fd >= 0) fail("fd still open");
    /* Same guard as root_diag qmdl_write_locked: no fd, no write. */
}

int main(void) {
    snprintf(g_dir, sizeof(g_dir), "/tmp/diag-session-XXXXXX");
    if (!mkdtemp(g_dir)) fail("mkdtemp");
    snprintf(g_active, sizeof(g_active), "%s/active.qmdl", g_dir);
    snprintf(g_committed, sizeof(g_committed), "%s/task-1.qmdl", g_dir);

    live_creates_nothing();

    start_session();
    struct stat st;
    if (stat(g_active, &st) != 0 || st.st_size != 0) fail("active should start empty");
    write_bytes("QMD1", 4);
    stop_session(1);
    if (stat(g_active, &st) == 0) fail("active should be gone after finish");
    if (stat(g_committed, &st) != 0 || st.st_size != 4) fail("committed task file missing");
    write_after_stop_is_ignored();

    /* A second start must not clobber the first task file. */
    start_session();
    write_bytes("QMD2", 4);
    char second[300];
    snprintf(second, sizeof(second), "%s/task-2.qmdl", g_dir);
    if (g_fd < 0) fail("second session");
    if (close(g_fd) != 0) fail("close 2");
    g_fd = -1;
    if (rename(g_active, second) != 0) fail("rename 2");
    if (stat(g_committed, &st) != 0 || st.st_size != 4) fail("first file overwritten");
    if (stat(second, &st) != 0 || st.st_size != 4) fail("second file missing");

    /* Another floor-plan mark rewrites the sidecar and leaves the open air file in place. */
    start_session();
    write_bytes("AIR", 3);
    char side[300], plan[300], marker[300];
    snprintf(side, sizeof(side), "%s/active.cqt.json", g_dir);
    snprintf(plan, sizeof(plan), "%s/active.cqt-plan", g_dir);
    snprintf(marker, sizeof(marker), "%s/active.task", g_dir);
    FILE *points = fopen(side, "w");
    if (!points) fail("sidecar");
    fputs("{\"points\":[{\"x_px\":1}]}", points);
    fclose(points);
    struct stat air_before;
    if (stat(g_active, &air_before) != 0 || air_before.st_size != 3) fail("air before mark");
    points = fopen(side, "w");
    if (!points) fail("sidecar rewrite");
    fputs("{\"points\":[{\"x_px\":1},{\"x_px\":2}]}", points);
    fclose(points);
    struct stat air_after;
    if (stat(g_active, &air_after) != 0) fail("air after mark");
    if (air_before.st_ino != air_after.st_ino || air_after.st_size != 3) fail("mark split the air file");
    if (close(g_fd) != 0) fail("close marked");
    g_fd = -1;
    char marked[300];
    snprintf(marked, sizeof(marked), "%s/task-3.qmdl", g_dir);
    if (rename(g_active, marked) != 0) fail("rename marked air");

    /* An empty air segment is removed together with the sidecar. */
    start_session();
    points = fopen(side, "w");
    if (!points) fail("empty sidecar");
    fputs("{\"points\":[]}", points);
    fclose(points);
    stop_session(0);
    if (unlink(side) != 0) fail("empty air must drop sidecar");
    if (stat(side, &st) == 0) fail("sidecar remained after empty air");

    /* Crash recovery names the sidecar with the task id stored beside the air file. */
    start_session();
    write_bytes("LOG", 3);
    points = fopen(marker, "w");
    if (!points) fail("marker");
    fputs("keep-me", points);
    fclose(points);
    points = fopen(side, "w");
    if (!points) fail("crash sidecar");
    fputs("{\"task_id\":\"keep-me\"}", points);
    fclose(points);
    points = fopen(plan, "w");
    if (!points) fail("plan");
    fputs("plan", points);
    fclose(points);
    if (close(g_fd) != 0) fail("close crash");
    g_fd = -1;
    char saved_air[300], saved_side[300], saved_plan[300], orphan[300];
    snprintf(saved_air, sizeof(saved_air), "%s/keep-me.qmdl", g_dir);
    snprintf(saved_side, sizeof(saved_side), "%s/keep-me.cqt.json", g_dir);
    snprintf(saved_plan, sizeof(saved_plan), "%s/keep-me.cqt-plan", g_dir);
    snprintf(orphan, sizeof(orphan), "%s/orphan.cqt.json", g_dir);
    if (rename(g_active, saved_air) != 0) fail("rename crash air");
    if (rename(side, saved_side) != 0) fail("rename crash sidecar");
    if (rename(plan, saved_plan) != 0) fail("rename crash plan");
    if (stat(orphan, &st) == 0) fail("sidecar renamed orphan");
    if (stat(saved_air, &st) != 0 || stat(saved_side, &st) != 0 || stat(saved_plan, &st) != 0) {
        fail("task sidecar missing");
    }

    unlink(g_committed);
    unlink(second);
    unlink(marked);
    unlink(saved_air);
    unlink(saved_side);
    unlink(saved_plan);
    unlink(marker);
    rmdir(g_dir);
    puts("diag_session_file_test ok");
    return 0;
}
