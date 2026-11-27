package com.craxiom.networksurvey.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import com.craxiom.networksurvey.model.SurveyEvent
import com.craxiom.networksurvey.model.SurveyLocationMode
import com.craxiom.networksurvey.model.SurveyTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Tasks survive navigation and process restarts; only explicit finish/new closes them. */
internal class SurveyTaskStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "survey_tasks.db", null, 2) {
    private val appContext = context.applicationContext
    private val activeState = MutableStateFlow<SurveyTask?>(null)
    val active = activeState.asStateFlow()
    private val revisionState = MutableStateFlow(0L)
    val revision = revisionState.asStateFlow()
    private val boundMissions = mutableSetOf<Pair<String, String>>()
    private var activeMission: String? = null

    init {
        readableDatabase.rawQuery(
            "SELECT id,name,started_at,started_elapsed_ms,clock_id,need_log FROM survey_task WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1", null,
        ).use { if (it.moveToFirst()) activeState.value = SurveyTask(it.getString(0), it.getString(1), it.getLong(2),
            startedElapsedMs = it.getLong(3), clockId = it.getString(4), needLog = it.getInt(5) != 0) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE survey_task(id TEXT PRIMARY KEY,name TEXT NOT NULL,started_at INTEGER NOT NULL,ended_at INTEGER,started_elapsed_ms INTEGER NOT NULL,clock_id TEXT NOT NULL,need_log INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE task_mission(task_id TEXT NOT NULL,mission_id TEXT NOT NULL,PRIMARY KEY(task_id,mission_id))")
        db.execSQL("""CREATE TABLE survey_event(id TEXT PRIMARY KEY,task_id TEXT NOT NULL,time INTEGER NOT NULL,
            elapsed_ms INTEGER NOT NULL,clock_id TEXT NOT NULL,source TEXT NOT NULL,kind TEXT NOT NULL,
            title TEXT NOT NULL,detail TEXT NOT NULL,subscription_id INTEGER,sim_slot INTEGER,rat TEXT,pci INTEGER,arfcn INTEGER,source_time_ms INTEGER,time_basis TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX idx_event_task_time ON survey_event(task_id,time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE survey_task ADD COLUMN need_log INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * Returns the running task, or creates one with no DT/CQT choice.
     * Mission start and map tracking must not call this; they only attach to a task the user created.
     */
    @Synchronized fun ensure(): SurveyTask = activeState.value ?: start()

    /** Returns the running test task only. Viewing live packets must not open a recording session. */
    @Synchronized fun observationTask(): SurveyTask? = activeState.value

    /** [locationMode] is stored by task id, not as a survey_task column. Blank names may show DT or CQT. */
    @Synchronized fun start(name: String = "", needLog: Boolean = false, locationMode: SurveyLocationMode? = null): SurveyTask {
        val now = System.currentTimeMillis()
        val requested = name.trim().take(80)
        val task = SurveyTask(UUID.randomUUID().toString(), requested.ifBlank {
            val kind = locationMode?.let { "${it.name} " }.orEmpty()
            kind + "采集 " + SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
        }, now, startedElapsedMs = SystemClock.elapsedRealtime(), clockId = CLOCK_ID, needLog = needLog)
        val db = writableDatabase
        db.beginTransaction()
        try {
            activeState.value?.let {
                db.execSQL("UPDATE survey_task SET ended_at=? WHERE id=?", arrayOf<Any>(now, it.id))
                insertEvent(db, lifecycle(it, now, "task_end", "任务结束"))
            }
            db.insertOrThrow("survey_task", null, ContentValues().apply {
                put("id", task.id); put("name", task.name); put("started_at", now)
                put("started_elapsed_ms", task.startedElapsedMs); put("clock_id", task.clockId)
                put("need_log", if (task.needLog) 1 else 0)
            })
            insertEvent(db, lifecycle(task, now, "task_start", "任务开始"))
            activeMission?.let { mission ->
                db.insertWithOnConflict("task_mission", null, ContentValues().apply {
                    put("task_id", task.id); put("mission_id", mission)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        locationMode?.let { mode ->
            val remembered = runCatching { rememberSurveyLocationMode(appContext, task.id, mode) }.getOrDefault(false)
            if (!remembered) timber.log.Timber.w("Could not remember DT/CQT choice for this task")
        }
        activeState.value = task
        activeMission?.let { boundMissions.add(task.id to it) }
        revisionState.value++
        return task
    }

    @Synchronized fun finish() {
        val task = activeState.value ?: return
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE survey_task SET ended_at=? WHERE id=?", arrayOf<Any>(now, task.id))
            insertEvent(db, lifecycle(task, now, "task_end", "任务结束"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        activeState.value = null
        revisionState.value++
    }

    @Synchronized fun bindMission(taskId: String, missionId: String?) {
        if (missionId.isNullOrBlank()) return
        val key = taskId to missionId
        if (key in boundMissions) return
        writableDatabase.insertWithOnConflict("task_mission", null, ContentValues().apply {
            put("task_id", taskId); put("mission_id", missionId)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        boundMissions.add(key)
    }

    @Synchronized private fun missionState(missionId: String?, active: Boolean) {
        activeMission = missionId?.takeIf { active && it.isNotBlank() }
        val taskId = missionTaskToBind(activeState.value?.id, missionId, active) ?: return
        activeMission?.let { bindMission(taskId, it) }
    }

    @Synchronized fun append(event: SurveyEvent) {
        insertEvent(writableDatabase, event)
        revisionState.value++
    }

    fun events(taskId: String, limit: Int = EVENT_LIMIT + 1): List<SurveyEvent> =
        readableDatabase.query("survey_event", null, "task_id=?", arrayOf(taskId),
            null, null, "time DESC,rowid DESC", limit.coerceIn(1, EVENT_LIMIT + 1).toString()).use { c ->
            buildList {
                fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
                fun number(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
                fun optional(key: String) = c.getColumnIndexOrThrow(key).let { if (c.isNull(it)) null else c.getInt(it) }
                while (c.moveToNext()) add(SurveyEvent(text("id"), text("task_id"), number("time"),
                    number("elapsed_ms"), text("clock_id"), text("source"), text("kind"), text("title"),
                    text("detail"), optional("subscription_id"), optional("sim_slot"),
                    text("rat"), optional("pci"), optional("arfcn"),
                    c.getColumnIndexOrThrow("source_time_ms").let { if (c.isNull(it)) null else c.getLong(it) }, text("time_basis")))
            }.asReversed()
        }

    fun taskMetadata(taskId: String): JSONObject {
        val result = JSONObject().put("task_id", taskId)
        readableDatabase.rawQuery("SELECT name,started_at,ended_at,started_elapsed_ms,clock_id,need_log FROM survey_task WHERE id=?", arrayOf(taskId)).use {
            if (it.moveToFirst()) result.put("name", it.getString(0)).put("started_at", it.getLong(1))
                .put("ended_at", if (it.isNull(2)) JSONObject.NULL else it.getLong(2))
                .put("started_elapsed_ms", it.getLong(3)).put("clock_id", it.getString(4))
                .put("need_log", it.getInt(5) != 0)
        }
        val missions = org.json.JSONArray()
        readableDatabase.rawQuery("SELECT mission_id FROM task_mission WHERE task_id=? ORDER BY mission_id", arrayOf(taskId))
            .use { while (it.moveToNext()) missions.put(it.getString(0)) }
        return result.put("mission_ids", missions)
    }

    fun recentTasks(): List<SurveyTask> = readableDatabase.rawQuery(
        "SELECT id,name,started_at,ended_at,started_elapsed_ms,clock_id,need_log FROM survey_task ORDER BY started_at DESC LIMIT 200", null,
    ).use { c -> buildList {
        while (c.moveToNext()) add(SurveyTask(c.getString(0), c.getString(1), c.getLong(2),
            if (c.isNull(3)) null else c.getLong(3), c.getLong(4), c.getString(5), c.getInt(6) != 0))
    } }

    private fun lifecycle(task: SurveyTask, now: Long, kind: String, title: String) =
        SurveyEvent(UUID.randomUUID().toString(), task.id, now, SystemClock.elapsedRealtime(), CLOCK_ID,
            "task", kind, title, task.name)

    private fun insertEvent(db: SQLiteDatabase, e: SurveyEvent) {
        db.insertOrThrow("survey_event", null, ContentValues().apply {
            put("id", e.id); put("task_id", e.taskId); put("time", e.time); put("elapsed_ms", e.elapsedMs)
            put("clock_id", e.clockId); put("source", e.source); put("kind", e.kind); put("title", e.title)
            put("detail", e.detail); put("subscription_id", e.subscriptionId); put("sim_slot", e.simSlot)
            put("rat", e.rat); put("pci", e.pci); put("arfcn", e.arfcn)
            put("source_time_ms", e.sourceTimeMs)
            put("time_basis", e.timeBasis)
        })
    }

    companion object {
        const val EVENT_LIMIT = 10_000
        val CLOCK_ID: String = UUID.randomUUID().toString()
        @JvmStatic fun peekActiveTaskId(): String? = instance?.active?.value?.id
        @JvmStatic fun clockId(): String = CLOCK_ID
        @Volatile private var instance: SurveyTaskStore? = null
        private val missionWriter = java.util.concurrent.Executors.newSingleThreadExecutor()
        fun get(context: Context): SurveyTaskStore = instance ?: synchronized(this) {
            instance ?: SurveyTaskStore(context).also { instance = it }
        }
        @JvmStatic fun onMissionState(context: Context, missionId: String?, active: Boolean) {
            val app = context.applicationContext
            missionWriter.execute {
                try { get(app).missionState(missionId, active) }
                catch (error: android.database.SQLException) { timber.log.Timber.w(error, "Could not link survey Mission ID") }
            }
        }
    }
}

/** A mission start binds the id only when a test task already exists. It does not create one. */
internal fun missionTaskToBind(activeTaskId: String?, missionId: String?, sessionActive: Boolean): String? {
    if (!sessionActive || missionId.isNullOrBlank() || activeTaskId.isNullOrBlank()) return null
    return activeTaskId
}

/** Map tracking records into the current task. A blank id is not a task. */
internal fun existingTaskForMapTracking(activeTaskId: String?): String? =
    activeTaskId?.trim()?.takeIf { it.isNotEmpty() }

internal fun SurveyEvent.toJson(): JSONObject = JSONObject()
    .put("type", "task_event").put("id", id).put("task_id", taskId).put("time_ms", time)
    .put("elapsed_ms", elapsedMs).put("clock_id", clockId).put("source", source).put("kind", kind)
    .put("title", title).put("detail", detail).put("subscription_id", subscriptionId ?: JSONObject.NULL)
    .put("sim_slot", simSlot ?: JSONObject.NULL).put("rat", rat ?: JSONObject.NULL)
    .put("pci", pci ?: JSONObject.NULL).put("arfcn", arfcn ?: JSONObject.NULL)
    .put("source_time_ms", sourceTimeMs ?: JSONObject.NULL).put("time_basis", timeBasis)
