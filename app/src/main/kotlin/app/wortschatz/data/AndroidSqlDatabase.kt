package app.wortschatz.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.wortschatz.core.imports.SqlDatabase
import java.io.File

/** Read-only access to an extracted Anki collection. */
class AndroidSqlDatabase(file: File) : SqlDatabase {
    private val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

    override fun tables(): Set<String> =
        query("SELECT name FROM sqlite_master WHERE type = 'table'").mapNotNull { it[0] }.toSet()

    override fun query(sql: String): List<List<String?>> = db.rawQuery(sql, null).use { c ->
        buildList {
            while (c.moveToNext()) add((0 until c.columnCount).map { i -> if (c.getType(i) == Cursor.FIELD_TYPE_NULL) null else c.getString(i) })
        }
    }

    override fun close() = db.close()
}
