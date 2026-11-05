package com.example.myasr

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteStatement
import android.util.Log

class DictionaryDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    companion object {
        private const val TAG = "DictDbHelper"
        const val DATABASE_NAME = "mozc_dictionaries.db"
        private const val DATABASE_VERSION = 2

        const val TABLE_ENTRIES = "dictionary_entries"
        const val COL_READING = "reading"
        const val COL_SURFACE = "surface"
        const val COL_COST = "cost"
        const val COL_SOURCE = "source_type"

        @Volatile
        private var instance: DictionaryDatabaseHelper? = null

        fun getInstance(context: Context): DictionaryDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: DictionaryDatabaseHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ENTRIES (
                $COL_READING TEXT NOT NULL,
                $COL_SURFACE TEXT NOT NULL,
                $COL_COST INTEGER NOT NULL,
                $COL_SOURCE TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dict_reading_cost ON $TABLE_ENTRIES($COL_READING, $COL_COST DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_dict_source ON $TABLE_ENTRIES($COL_SOURCE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ENTRIES")
        onCreate(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try {
            db.enableWriteAheadLogging()
        } catch (e: Exception) {
            Log.w(TAG, "WAL enable failed: ${e.message}")
        }
    }

    /**
     * Inserts a list of dictionary records in a single fast transaction.
     */
    fun insertBatch(records: List<DictRecord>) {
        if (records.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        var stmt: SQLiteStatement? = null
        try {
            stmt = db.compileStatement(
                "INSERT INTO $TABLE_ENTRIES ($COL_READING, $COL_SURFACE, $COL_COST, $COL_SOURCE) VALUES (?, ?, ?, ?)"
            )
            for (rec in records) {
                stmt.bindString(1, rec.reading)
                stmt.bindString(2, rec.surface)
                stmt.bindLong(3, rec.cost.toLong())
                stmt.bindString(4, rec.sourceType)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            db.setTransactionSuccessful()
        } finally {
            stmt?.close()
            db.endTransaction()
        }
    }

    fun deleteBySource(sourceType: String) {
        val db = writableDatabase
        db.delete(TABLE_ENTRIES, "$COL_SOURCE = ?", arrayOf(sourceType))
    }

    fun getWordCountBySource(sourceType: String): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_ENTRIES WHERE $COL_SOURCE = ?", arrayOf(sourceType))
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun getTotalWordCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_ENTRIES", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun getExactMatches(reading: String, limit: Int = 40): List<ConversionCandidate> {
        val db = readableDatabase
        val result = mutableListOf<ConversionCandidate>()
        val cursor = db.rawQuery(
            "SELECT $COL_SURFACE, $COL_COST, $COL_SOURCE FROM $TABLE_ENTRIES WHERE $COL_READING = ? ORDER BY $COL_COST DESC LIMIT ?",
            arrayOf(reading, limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                val surface = it.getString(0)
                val source = it.getString(2)
                result.add(ConversionCandidate(surface = surface, reading = reading, isPrediction = false, source = source))
            }
        }
        return result
    }

    fun getPrefixMatches(reading: String, limit: Int = 30): List<ConversionCandidate> {
        val db = readableDatabase
        val result = mutableListOf<ConversionCandidate>()
        val upper = reading + "\uffff"
        val cursor = db.rawQuery(
            "SELECT $COL_READING, $COL_SURFACE, $COL_COST, $COL_SOURCE FROM $TABLE_ENTRIES WHERE $COL_READING > ? AND $COL_READING <= ? ORDER BY $COL_COST DESC LIMIT ?",
            arrayOf(reading, upper, limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                val fullReading = it.getString(0)
                val surface = it.getString(1)
                val source = it.getString(3)
                result.add(ConversionCandidate(surface = surface, reading = fullReading, isPrediction = true, source = source))
            }
        }
        return result
    }
}

data class DictRecord(
    val reading: String,
    val surface: String,
    val cost: Int,
    val sourceType: String
)
