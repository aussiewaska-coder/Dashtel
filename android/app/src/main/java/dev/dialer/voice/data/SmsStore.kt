package dev.dialer.voice.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.dialer.voice.model.SmsMessage

/** App-private, excluded from backup by the application's backup policy. */
class SmsStore(context: Context) : SQLiteOpenHelper(context, "messages.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (scope TEXT, sid TEXT, sender TEXT, recipient TEXT, body TEXT, status TEXT, time INTEGER, error TEXT, media INTEGER, preview INTEGER, unread INTEGER, notified INTEGER, PRIMARY KEY(scope,sid))")
        db.execSQL("CREATE TABLE drafts (scope TEXT, peer TEXT, body TEXT, PRIMARY KEY(scope,peer))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun merge(scope: String, line: String, messages: List<SmsMessage>, visiblePeer: String?): Set<String> {
        val notify = mutableSetOf<String>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            messages.forEach { message ->
                var unread = message.incoming(line) && message.peer(line) != visiblePeer
                var notified = false
                var fullBody = false
                db.rawQuery("SELECT unread,notified,preview FROM messages WHERE scope=? AND sid=?", arrayOf(scope, message.sid)).use {
                    if (it.moveToFirst()) {
                        unread = it.getInt(0) == 1 && message.peer(line) != visiblePeer
                        notified = it.getInt(1) == 1
                        fullBody = it.getInt(2) == 0
                    }
                }
                if (message.preview && fullBody) return@forEach
                val values = ContentValues().apply {
                    put("scope", scope); put("sid", message.sid); put("sender", message.from); put("recipient", message.to)
                    put("body", message.body); put("status", message.status); put("time", message.timestamp)
                    put("error", message.errorCode); put("media", message.mediaCount); put("preview", if (message.preview) 1 else 0)
                    put("unread", if (unread) 1 else 0)
                    put("notified", if (notified || message.preview) 1 else 0)
                }
                db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                if (message.preview && unread && !notified) notify.add(message.sid)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return notify
    }

    @Synchronized
    fun messages(scope: String): List<SmsMessage> = readableDatabase.rawQuery(
        "SELECT sid,sender,recipient,body,status,time,error,media,preview FROM messages WHERE scope=? ORDER BY time ASC,sid ASC",
        arrayOf(scope),
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(SmsMessage(
            cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
            cursor.getLong(5), cursor.getString(6), cursor.getInt(7), cursor.getInt(8) == 1,
        )) }
    }

    @Synchronized
    fun unread(scope: String): Set<String> = readableDatabase.rawQuery(
        "SELECT sid FROM messages WHERE scope=? AND unread=1", arrayOf(scope),
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    @Synchronized
    fun markRead(scope: String, peer: String) {
        writableDatabase.execSQL("UPDATE messages SET unread=0 WHERE scope=? AND sender=?", arrayOf(scope, peer))
    }

    @Synchronized
    fun draft(scope: String, peer: String): String = readableDatabase.rawQuery(
        "SELECT body FROM drafts WHERE scope=? AND peer=?", arrayOf(scope, peer),
    ).use { if (it.moveToFirst()) it.getString(0) else "" }

    @Synchronized
    fun saveDraft(scope: String, peer: String, body: String) {
        writableDatabase.insertWithOnConflict("drafts", null, ContentValues().apply {
            put("scope", scope); put("peer", peer); put("body", body)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
