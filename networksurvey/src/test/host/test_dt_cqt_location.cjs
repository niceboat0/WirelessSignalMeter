const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const root = path.resolve(__dirname, "../../../..");
const source = (name) => fs.readFileSync(path.join(root, name), "utf8");
const diag = source("networksurvey/src/main/cpp/root_diag.c");

function arrayBody(name) {
    const start = diag.indexOf(name);
    assert.ok(start >= 0, name);
    const brace = diag.indexOf("{", start);
    const end = diag.indexOf("};", brace);
    assert.ok(end > brace, name);
    return diag.slice(brace, end);
}

let count = 0;
for (const name of ["k_default_codes[]", "k_qmdl_codes[]", "k_neighbor_probe_codes[]"]) {
    const body = arrayBody(name);
    for (const code of ["0x1476", "0x1384", "0X1476", "0X1384"]) {
        assert.equal(body.includes(code), false, `${name} contains ${code}`);
    }
    console.log(`PASS: ${name} does not enable DIAG GNSS 0x1476 or 0x1384`);
    count++;
}

const tasks = source("networksurvey/src/main/java/com/craxiom/networksurvey/db/SurveyTaskStore.kt");
const create = tasks.match(/CREATE TABLE survey_task\(([^)]*)\)/)[1];
assert.equal(
    create,
    "id TEXT PRIMARY KEY,name TEXT NOT NULL,started_at INTEGER NOT NULL,ended_at INTEGER,started_elapsed_ms INTEGER NOT NULL,clock_id TEXT NOT NULL,need_log INTEGER NOT NULL DEFAULT 0",
);
assert.equal(tasks.includes("location_mode"), false);
assert.equal(tasks.includes("ALTER TABLE survey_task ADD COLUMN need_log"), true);
assert.equal((tasks.match(/ALTER TABLE survey_task/g) || []).length, 1);
console.log("PASS: survey_task has no DT/CQT column");
count++;

const viewModel = source("networksurvey/src/main/java/com/craxiom/networksurvey/ui/rootdiag/RootDiagViewModel.kt");
const startHook = viewModel.indexOf("recordingSession.start(taskId)");
const sidecarOpen = viewModel.indexOf("locationRecording.onSessionStarted(taskId)");
const finishHook = viewModel.indexOf("locationRecording.onSessionFinished()");
const sessionFinish = viewModel.indexOf("recordingSession.finish()", finishHook);
assert.ok(startHook >= 0 && sidecarOpen > startHook, "sidecar opens after the recording session starts");
assert.ok(finishHook >= 0 && sessionFinish > finishHook, "sidecar stops in the same finish as the qmdl session");
console.log("PASS: location sidecar is tied to DiagRecordingSession start and finish");
count++;

const map = source("networksurvey/src/main/java/com/craxiom/networksurvey/ui/cellular/baidumap/ForegroundMapTrack.kt");
const sidecar = source("networksurvey/src/main/java/com/craxiom/networksurvey/ui/rootdiag/RecordingLocationSidecar.kt");
const feed = source("networksurvey/src/main/java/com/craxiom/networksurvey/ui/rootdiag/RecordingLocationFeed.kt");
assert.ok(map.includes("distance[0] < 3f"));
assert.equal(sidecar.includes("distanceBetween"), false);
assert.equal(feed.includes("ForegroundMapTrack"), false);
assert.equal(feed.includes("onPause"), false);
console.log("PASS: recording track is not the map-point listener");
count++;

console.log(`PASS: ${count} DT/CQT location source checks`);
