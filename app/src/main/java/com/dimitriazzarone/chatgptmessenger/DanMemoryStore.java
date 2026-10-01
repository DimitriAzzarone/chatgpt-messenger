package com.dimitriazzarone.chatgptmessenger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Local, append-only copy of turns observed in Dan after this feature is installed. */
final class DanMemoryStore extends SQLiteOpenHelper {
    DanMemoryStore(Context context) {
        super(context, "dan_memory.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE turns (" +
                "event_id TEXT PRIMARY KEY, " +
                "conversation_url TEXT NOT NULL, " +
                "role TEXT NOT NULL, " +
                "body TEXT NOT NULL, " +
                "recorded_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX turns_conversation ON turns(conversation_url, recorded_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Migrations will preserve existing turns when the schema changes.
    }

    void record(String eventId, String conversationUrl, String role, String body) {
        ContentValues values = new ContentValues();
        values.put("event_id", eventId);
        values.put("conversation_url", conversationUrl);
        values.put("role", role);
        values.put("body", body);
        values.put("recorded_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "turns", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    int exportJsonl(OutputStream output) throws IOException {
        int count = 0;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT event_id, conversation_url, role, body, recorded_at " +
                        "FROM turns ORDER BY recorded_at, rowid", null);
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                try {
                    item.put("event_id", cursor.getString(0));
                    item.put("conversation_url", cursor.getString(1));
                    item.put("role", cursor.getString(2));
                    item.put("text", cursor.getString(3));
                    item.put("recorded_at_ms", cursor.getLong(4));
                } catch (org.json.JSONException e) {
                    throw new IOException("Unable to export a memory record", e);
                }
                writer.write(item.toString());
                writer.newLine();
                count++;
            }
            writer.flush();
        }
        return count;
    }
}
