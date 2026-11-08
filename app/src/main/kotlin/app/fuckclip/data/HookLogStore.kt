package app.fuckclip.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import app.fuckclip.core.model.HookLog
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val MAX_LOG_RECORDS = 100
private const val ARCHIVE_PAGE_SIZE = 50
private const val MERGE_WINDOW_MS = 5_000L

internal data class ArchivedLogPage(val logs: List<HookLog>, val nextBefore: Long?)

/** Stores recent audit events on disk and publishes the committed history to the UI. */
internal class HookLogStore(context: Context, private val json: Json) {
    private val database by lazy { HookLogDatabase(context.applicationContext) }
    private val operations = Channel<() -> Unit>(Channel.UNLIMITED)
    private val _hookLogs = MutableStateFlow<List<HookLog>>(emptyList())
    val hookLogs = _hookLogs.asStateFlow()

    // Only the database worker reads or writes this flag.
    private var loaded = false

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (operation in operations) operation()
        }
        submit { loadHistory() }
    }

    fun recordHookLog(log: HookLog) = submit {
        loadHistory()
        val current = _hookLogs.value
        val head = current.firstOrNull()
        val db = database.writableDatabase
        if (head != null && head.mergesWith(log)) {
            val merged = head.copy(
                count = (head.count.toLong() + log.count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                detail = log.detail,
                timestamp = log.timestamp,
                content = log.content
            )
            val values = ContentValues().apply { put("payload", json.encodeToString(merged)) }
            check(db.update("hook_logs", values, "id = ?", arrayOf(head.id)) == 1)
            _hookLogs.value = listOf(merged) + current.drop(1)
        } else {
            val values = ContentValues().apply {
                put("id", log.id)
                put("payload", json.encodeToString(log))
            }
            db.insertOrThrow("hook_logs", null, values)
            _hookLogs.value = listOf(log) + current.take(MAX_LOG_RECORDS - 1)
        }
    }

    suspend fun readArchive(before: Long?): ArchivedLogPage = submit {
        val older = if (before == null) "" else "AND seq < ? "
        val args = before?.let { arrayOf(it.toString()) }
        val rows = database.readableDatabase.rawQuery(
            "SELECT seq, payload FROM hook_logs WHERE seq <= " +
                "(SELECT seq FROM hook_logs ORDER BY seq DESC LIMIT 1 OFFSET $MAX_LOG_RECORDS) " +
                older + "ORDER BY seq DESC LIMIT ${ARCHIVE_PAGE_SIZE + 1}",
            args
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getLong(0) to json.decodeFromString<HookLog>(cursor.getString(1)))
                }
            }
        }
        val page = rows.take(ARCHIVE_PAGE_SIZE)
        ArchivedLogPage(page.map { it.second }, if (rows.size > page.size) page.last().first else null)
    }.await()

    fun clearHookLogs() = submit {
        database.writableDatabase.delete("hook_logs", null, null)
        _hookLogs.value = emptyList()
        loaded = true
    }

    private fun loadHistory() {
        if (loaded) return
        _hookLogs.value = database.readableDatabase.rawQuery(
            "SELECT payload FROM hook_logs ORDER BY seq DESC LIMIT $MAX_LOG_RECORDS", null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(json.decodeFromString<HookLog>(cursor.getString(0)))
            }
        }
        loaded = true
    }

    private fun <T> submit(operation: () -> T): CompletableDeferred<T> {
        val done = CompletableDeferred<T>()
        val queued = operations.trySend {
            try {
                done.complete(operation())
            } catch (e: Exception) {
                loaded = false
                // Exception messages can contain the serialized clipboard payload.
                Log.e("HookLogStore", "Audit database operation failed: ${e.javaClass.simpleName}")
                done.completeExceptionally(e)
            }
        }
        if (queued.isFailure) done.completeExceptionally(IllegalStateException("Audit log queue unavailable"))
        return done
    }

    private fun HookLog.mergesWith(other: HookLog): Boolean =
        packageName == other.packageName &&
            action.equals(other.action, ignoreCase = true) &&
            allowed == other.allowed &&
            other.timestamp - timestamp in 0..MERGE_WINDOW_MS
}

private class HookLogDatabase(context: Context) : SQLiteOpenHelper(
    context,
    File(context.noBackupFilesDir, "clipboard_audit.db").absolutePath,
    1,
    SQLiteDatabase.OpenParams.Builder().setJournalMode("DELETE").setSynchronousMode("FULL").build()
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE hook_logs (" +
                "seq INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, payload TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw SQLiteException("Unsupported audit database upgrade: $oldVersion to $newVersion")
    }
}
