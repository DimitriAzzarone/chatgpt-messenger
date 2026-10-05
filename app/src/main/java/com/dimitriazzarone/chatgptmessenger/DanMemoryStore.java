package com.dimitriazzarone.chatgptmessenger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    boolean record(String eventId, String conversationUrl, String role, String body) {
        ContentValues values = new ContentValues();
        values.put("event_id", eventId);
        values.put("conversation_url", conversationUrl);
        values.put("role", role);
        values.put("body", body);
        values.put("recorded_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                "turns", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1;
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

    int importJsonl(InputStream input) throws IOException {
        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        int lineNumber = 0;
        db.beginTransaction();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.trim().isEmpty()) continue;
                JSONObject item;
                try {
                    item = new JSONObject(line);
                    String id = item.getString("event_id");
                    String url = item.getString("conversation_url");
                    String role = item.getString("role");
                    String body = item.getString("text");
                    long timestamp = item.getLong("recorded_at_ms");
                    if (id.isEmpty() || url.isEmpty() || body.isEmpty() ||
                            !(role.equals("user") || role.equals("assistant")) || timestamp <= 0) {
                        throw new IOException("Record non valido alla riga " + lineNumber);
                    }
                    ContentValues values = new ContentValues();
                    values.put("event_id", id);
                    values.put("conversation_url", url);
                    values.put("role", role);
                    values.put("body", body);
                    values.put("recorded_at", timestamp);
                    if (db.insertWithOnConflict("turns", null, values,
                            SQLiteDatabase.CONFLICT_IGNORE) != -1) added++;
                } catch (org.json.JSONException e) {
                    throw new IOException("JSON non valido alla riga " + lineNumber, e);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return added;
    }
}
