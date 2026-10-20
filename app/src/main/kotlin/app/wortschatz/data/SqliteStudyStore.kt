package app.wortschatz.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.wortschatz.core.Card
import app.wortschatz.core.CardType
import app.wortschatz.core.ReviewLog
import app.wortschatz.core.StudyStore
import app.wortschatz.core.fsrs.MemoryState
import app.wortschatz.core.fsrs.Phase
import app.wortschatz.core.fsrs.Rating

/**
 * Cards hold the current memory state; review_log is append-only and is the source of
 * truth that a future sync will exchange between devices.
 */
class SqliteStudyStore(private val context: Context) :
    SQLiteOpenHelper(context, NAME, null, 2), StudyStore {

    /**
     * The database file with everything written so far, for a backup.
     * Schema changes go through [onUpgrade] only (never drop or recreate tables), so a backup
     * from any older version restores into a newer app.
     */
    @Synchronized
    fun snapshot(): java.io.File {
        writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        return context.getDatabasePath(NAME)
    }

    /** Swaps in a database file from a backup; the next access opens and, if older, upgrades it. */
    @Synchronized
    fun replaceWith(file: java.io.File) {
        close()
        val target = context.getDatabasePath(NAME)
        listOf("-wal", "-shm", "-journal").forEach { java.io.File(target.path + it).delete() }
        file.copyTo(target, overwrite = true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE card (
                id TEXT PRIMARY KEY, entry_id TEXT NOT NULL, type TEXT NOT NULL,
                phase TEXT NOT NULL, stability REAL NOT NULL, difficulty REAL NOT NULL,
                due INTEGER NOT NULL, last_review INTEGER, step INTEGER NOT NULL,
                reps INTEGER NOT NULL, lapses INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE review_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT, card_id TEXT NOT NULL, ts INTEGER NOT NULL,
                rating INTEGER NOT NULL, duration_ms INTEGER NOT NULL,
                phase TEXT NOT NULL, stability REAL NOT NULL, difficulty REAL NOT NULL,
                due INTEGER NOT NULL, last_review INTEGER, step INTEGER NOT NULL,
                reps INTEGER NOT NULL, lapses INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX review_log_ts ON review_log(ts)")
        createKnownEntries(db)
    }

    companion object {
        const val NAME = "wortschatz.db"
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createKnownEntries(db)
    }

    /** Words marked as already known ("斩"), with when; kept separate so a future sync can exchange them. */
    private fun createKnownEntries(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE known_entry (entry_id TEXT PRIMARY KEY, ts INTEGER NOT NULL)")

    override fun knownEntries(): Set<String> =
        readableDatabase.rawQuery("SELECT entry_id FROM known_entry", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    override fun setKnown(entryId: String, known: Boolean) {
        if (known) {
            writableDatabase.insertWithOnConflict("known_entry", null, ContentValues().apply {
                put("entry_id", entryId)
                put("ts", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            writableDatabase.delete("known_entry", "entry_id = ?", arrayOf(entryId))
        }
    }

    override fun reviewTimesSince(timestamp: Long): List<Long> =
        readableDatabase.rawQuery("SELECT ts FROM review_log WHERE ts >= ?", arrayOf(timestamp.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }

    override fun loadCards(): List<Card> = readableDatabase.rawQuery("SELECT * FROM card", null).use { c ->
        buildList {
            while (c.moveToNext()) {
                val type = runCatching { CardType.valueOf(c.str("type")) }.getOrNull() ?: continue
                add(Card(c.str("entry_id"), type, c.memory()))
            }
        }
    }

    override fun insertCards(cards: List<Card>) = transaction {
        cards.forEach { insertWithOnConflict("card", null, it.values(), SQLiteDatabase.CONFLICT_IGNORE) }
    }

    override fun recordReview(updated: Card, log: ReviewLog): Long = transaction {
        replace("card", null, updated.values())
        insertOrThrow("review_log", null, ContentValues().apply {
            put("card_id", log.cardId)
            put("ts", log.timestamp)
            put("rating", log.rating.value)
            put("duration_ms", log.durationMs)
            putMemory(log.before)
        })
    }

    override fun undoReview(restored: Card, logId: Long) = transaction {
        replace("card", null, restored.values())
        delete("review_log", "id = ?", arrayOf(logId.toString()))
        Unit
    }

    override fun logsSince(timestamp: Long): List<ReviewLog> =
        readableDatabase.rawQuery("SELECT * FROM review_log WHERE ts >= ? ORDER BY id", arrayOf(timestamp.toString())).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ReviewLog(
                            id = c.long("id"),
                            cardId = c.str("card_id"),
                            timestamp = c.long("ts"),
                            rating = Rating.entries.first { it.value == c.int("rating") },
                            durationMs = c.long("duration_ms"),
                            before = c.memory(),
                        )
                    )
                }
            }
        }

    private fun <T> transaction(block: SQLiteDatabase.() -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val result = db.block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun Card.values() = ContentValues().apply {
        put("id", id)
        put("entry_id", entryId)
        put("type", type.name)
        putMemory(memory)
    }

    private fun ContentValues.putMemory(m: MemoryState) {
        put("phase", m.phase.name)
        put("stability", m.stability)
        put("difficulty", m.difficulty)
        put("due", m.due)
        val lastReview = m.lastReview
        if (lastReview == null) putNull("last_review") else put("last_review", lastReview)
        put("step", m.step)
        put("reps", m.reps)
        put("lapses", m.lapses)
    }

    private fun Cursor.memory() = MemoryState(
        phase = Phase.valueOf(str("phase")),
        stability = getDouble(getColumnIndexOrThrow("stability")),
        difficulty = getDouble(getColumnIndexOrThrow("difficulty")),
        due = long("due"),
        lastReview = getColumnIndexOrThrow("last_review").let { if (isNull(it)) null else getLong(it) },
        step = int("step"),
        reps = int("reps"),
        lapses = int("lapses"),
    )

    private fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
}
