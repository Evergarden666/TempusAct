package com.jianshen.clock.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import com.jianshen.clock.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ClockRepository internal constructor(context: Context, databaseName: String = "clock.db") {
    private val helper = Database(context.applicationContext, databaseName)
    private val mutablePlans = MutableStateFlow<List<TimerPlan>>(emptyList())
    private val mutableHistory = MutableStateFlow<List<TimerSession>>(emptyList())
    private var completedHistory = emptyList<TimerSession>()
    private val mutableSession = MutableStateFlow<TimerSession?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    val plans: StateFlow<List<TimerPlan>> = mutablePlans.asStateFlow()
    val history: StateFlow<List<TimerSession>> = mutableHistory.asStateFlow()
    val session: StateFlow<TimerSession?> = mutableSession.asStateFlow()
    val storageError: StateFlow<String?> = mutableError.asStateFlow()

    init {
        try {
            reloadPlans(); reloadHistory()
            helper.readableDatabase.rawQuery("SELECT json FROM active WHERE slot=1", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val saved = JsonCodec.decodeSession(cursor.getString(0))
                    if (saved.isTerminal) finishSession(saved)
                    else setSession(TimerEngine.recover(saved))
                }
            }
        } catch (error: Exception) {
            reportError("本地数据读取失败，原始记录已保留，请勿卸载应用", error)
        }
    }

    @Synchronized fun savePlan(plan: TimerPlan) = checked("方案保存失败") {
        require(validatePlan(plan) == null) { validatePlan(plan).orEmpty() }
        helper.writableDatabase.insertWithOnConflict("plans", null, ContentValues().apply {
            put("id", plan.id); put("json", JsonCodec.encodePlan(plan)); put("updated_at", plan.updatedAt)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        reloadPlans()
    }

    @Synchronized fun deletePlan(id: String) = checked("方案删除失败") {
        helper.writableDatabase.delete("plans", "id=?", arrayOf(id))
        reloadPlans()
    }

    /** Delete only finalized records; all requested IDs are handled in one transaction. */
    @Synchronized fun deleteHistory(recordIds: Set<String>) = checked("历史记录删除失败") {
        if (recordIds.isNotEmpty()) {
            val db = helper.writableDatabase
            db.transaction {
                val active = db.rawQuery("SELECT json FROM active WHERE slot=1", null).use {
                    if (it.moveToFirst()) JsonCodec.decodeSession(it.getString(0)) else null
                }
                listOfNotNull(active, mutableSession.value).forEach {
                    require(it.isTerminal || it.id !in recordIds) { "运行中、已暂停或待恢复的计时不能删除，请先结束本次计时" }
                }
                recordIds.forEach { id ->
                    val saved = db.rawQuery("SELECT json FROM history WHERE id=?", arrayOf(id)).use {
                        if (it.moveToFirst()) JsonCodec.decodeSession(it.getString(0)) else null
                    }
                    if (saved != null) {
                        require(saved.isTerminal) { "只能删除已结束的正式历史记录" }
                        // Retain deleted IDs so a delayed finish callback cannot recreate a record.
                        db.execSQL("INSERT OR IGNORE INTO history_deletions(id) VALUES(?)", arrayOf<Any>(id))
                        db.delete("history", "id=?", arrayOf(id))
                    }
                }
            }
            reloadHistory()
        }
    }

    @Synchronized fun setSession(value: TimerSession?, persist: Boolean = true) = checked("计时进度保存失败") {
        if (persist) {
            val db = helper.writableDatabase
            if (value == null) db.delete("active", "slot=1", null)
            else db.insertWithOnConflict("active", null, ContentValues().apply {
                put("slot", 1); put("json", JsonCodec.encodeSession(value))
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        }
        val wasRecovery = mutableSession.value?.status == SessionStatus.RECOVERY
        mutableSession.value = value
        if (wasRecovery || value?.status == SessionStatus.RECOVERY) publishHistory()
    }

    @Synchronized fun finishSession(value: TimerSession) = checked("历史记录保存失败") {
        check(value.isTerminal)
        val db = helper.writableDatabase
        db.transaction {
            if (value.elapsedEventMs > 0L) {
                // Finalized and deliberately deleted IDs both remain idempotent after a restart.
                db.execSQL("""
                    INSERT OR IGNORE INTO history(id,json,started_at)
                    SELECT ?,?,? WHERE NOT EXISTS(SELECT 1 FROM history_deletions WHERE id=?)
                """.trimIndent(),
                    arrayOf<Any>(value.id, JsonCodec.encodeSession(value), value.startedAtEpochMs, value.id))
            }
            val activeId = db.rawQuery("SELECT json FROM active WHERE slot=1", null).use {
                if (it.moveToFirst()) org.json.JSONObject(it.getString(0)).optString("id") else null
            }
            if (activeId == value.id) db.delete("active", "slot=1", null)
        }
        if (mutableSession.value == null || mutableSession.value?.id == value.id || mutableSession.value?.isTerminal == true)
            mutableSession.value = value
        reloadHistory()
    }

    private fun reloadPlans() {
        val values = mutableListOf<TimerPlan>()
        helper.readableDatabase.rawQuery("SELECT json FROM plans ORDER BY updated_at DESC, id", null).use {
            while (it.moveToNext()) try { values += JsonCodec.decodePlan(it.getString(0)) }
            catch (error: Exception) { reportError("有方案无法读取，原始数据已保留", error) }
        }
        mutablePlans.value = values
    }

    private fun reloadHistory() {
        val values = mutableListOf<TimerSession>()
        helper.readableDatabase.rawQuery("SELECT json FROM history ORDER BY started_at DESC, id", null).use {
            while (it.moveToNext()) try { values += JsonCodec.decodeSession(it.getString(0)) }
            catch (error: Exception) { reportError("有历史记录无法读取，原始数据已保留", error) }
        }
        completedHistory = values
        publishHistory()
    }

    private fun publishHistory() {
        val recovery = mutableSession.value?.takeIf {
            it.status == SessionStatus.RECOVERY && it.elapsedEventMs > 0L &&
                completedHistory.none { saved -> saved.id == it.id }
        }
        mutableHistory.value = if (recovery == null) completedHistory
            else (completedHistory + recovery).sortedByDescending { it.startedAtEpochMs }
    }

    private inline fun checked(message: String, block: () -> Unit) {
        try { block() } catch (error: Exception) { reportError(message, error); throw error }
    }

    private fun reportError(message: String, error: Exception) {
        mutableError.value = "$message。${error.localizedMessage.orEmpty().take(100)}"
    }

    internal fun close() = helper.close()

    private class Database(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 2) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE plans(id TEXT PRIMARY KEY NOT NULL,json TEXT NOT NULL,updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE history(id TEXT PRIMARY KEY NOT NULL,json TEXT NOT NULL,started_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE active(slot INTEGER PRIMARY KEY CHECK(slot=1),json TEXT NOT NULL)")
            createHistoryDeletions(db)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == 1 && newVersion == 2) createHistoryDeletions(db)
            else error("需要从版本 $oldVersion 迁移到 $newVersion，禁止自动清空用户数据")
        }
        private fun createHistoryDeletions(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE history_deletions(id TEXT PRIMARY KEY NOT NULL)")
        }
    }

    companion object {
        @Volatile private var instance: ClockRepository? = null
        fun get(context: Context): ClockRepository = instance ?: synchronized(this) {
            instance ?: ClockRepository(context).also { instance = it }
        }
    }
}
