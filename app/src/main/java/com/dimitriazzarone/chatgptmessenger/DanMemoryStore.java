package com.dimitriazzarone.chatgptmessenger;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedWriter;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

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
        db.execSQL("CREATE TABLE IF NOT EXISTS chat_titles (conversation_url TEXT PRIMARY KEY, "
                + "title TEXT NOT NULL, updated_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Migrations will preserve existing turns when the schema changes.
    }


    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        // Existing 1.82 databases keep their original turns table and version.
        if (!db.isReadOnly()) db.execSQL("CREATE TABLE IF NOT EXISTS chat_titles ("
                + "conversation_url TEXT PRIMARY KEY, title TEXT NOT NULL, "
                + "updated_at INTEGER NOT NULL)");
    }

    /** Conservatively backfill conversations already saved in the local chat UI.
     * Existing messages are counted by role and exact body in each conversation;
     * replaying this procedure does not duplicate previous turns.
     */
    int importSavedLocalChats(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("dan_local_chat_165", Context.MODE_PRIVATE);
        JSONArray index = new JSONArray(prefs.getString("chat_index_v2", "[]"));
        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        db.beginTransaction();
        try {
            for (int i = 0; i < index.length(); i++) {
                JSONObject entry = index.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("id", "");
                if (!id.matches("[0-9a-fA-F-]{36}")) continue;
                String raw = prefs.getString("chat_v2_" + id, null);
                if (raw == null) continue;
                JSONArray saved;
                try { saved = new JSONArray(raw); }
                catch (Exception invalid) {
                    android.util.Log.w("DanMemory", "Chat locale non leggibile: " + id, invalid);
                    continue; // Never alter the user's original chat data.
                }
                String url = "dan-local://" + id;
                String title = entry.optString("title", "").trim();
                if (!title.isEmpty()) putTitleIfMissing(db, url, title);
                Map<String, Integer> already = new HashMap<>();
                try (Cursor cursor = db.rawQuery(
                        "SELECT role, body FROM turns WHERE conversation_url=?", new String[]{url})) {
                    while (cursor.moveToNext()) {
                        String key = cursor.getString(0) + "|" + cursor.getString(1);
                        already.put(key, already.containsKey(key) ? already.get(key) + 1 : 1);
                    }
                }
                Map<String, Integer> seen = new HashMap<>();
                for (int j = 0; j < saved.length(); j++) {
                    JSONObject turn = saved.optJSONObject(j);
                    if (turn == null) continue;
                    String role = turn.optString("role", "");
                    String body = turn.optString("content", "");
                    if ((!"user".equals(role) && !"assistant".equals(role)) || body.isEmpty()) continue;
                    String key = role + "|" + body;
                    int occurrence = seen.containsKey(key) ? seen.get(key) + 1 : 1;
                    seen.put(key, occurrence);
                    if (occurrence <= (already.containsKey(key) ? already.get(key) : 0)) continue;
                    // Stable ID allows recovery on two devices without duplicating this turn.
                    String source = url + "|" + role + "|" + body + "|" + occurrence;
                    String eventId = "dan-backfill-v1:" + UUID.nameUUIDFromBytes(
                            source.getBytes(StandardCharsets.UTF_8));
                    long timestamp = turn.optLong("timestamp", 0);
                    if (timestamp <= 0) timestamp = Math.max(1L,
                            System.currentTimeMillis() - (saved.length() - j) * 1000L);
                    ContentValues row = new ContentValues();
                    row.put("event_id", eventId);
                    row.put("conversation_url", url);
                    row.put("role", role);
                    row.put("body", body);
                    row.put("recorded_at", timestamp);
                    if (db.insertWithOnConflict("turns", null, row,
                            SQLiteDatabase.CONFLICT_IGNORE) != -1) added++;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return added;
    }

    private static void putTitleIfMissing(SQLiteDatabase db, String url, String title) {
        ContentValues values = new ContentValues();
        values.put("conversation_url", url);
        values.put("title", title.length() > 60 ? title.substring(0, 60) : title);
        values.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("chat_titles", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static void putTitleIfNewer(SQLiteDatabase db, String url,
                                        String title, long updatedAt) {
        if (title == null || title.trim().isEmpty() || updatedAt <= 0 ||
                !url.startsWith("dan-local://")) return;
        try (Cursor c = db.rawQuery("SELECT updated_at FROM chat_titles WHERE conversation_url=?",
                new String[]{url})) {
            if (c.moveToFirst() && c.getLong(0) >= updatedAt) return;
        }
        ContentValues values = new ContentValues();
        values.put("conversation_url", url);
        values.put("title", title.length() > 60 ? title.substring(0, 60) : title);
        values.put("updated_at", updatedAt);
        db.insertWithOnConflict("chat_titles", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void updateChatTitle(String url, String title) {
        putTitleIfNewer(getWritableDatabase(), url, title, System.currentTimeMillis());
    }

    String getChatTitle(String url) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT title FROM chat_titles WHERE conversation_url=?", new String[]{url})) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
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
                "SELECT t.event_id, t.conversation_url, t.role, t.body, t.recorded_at, " +
                        "n.title, n.updated_at FROM turns t LEFT JOIN chat_titles n " +
                        "ON t.conversation_url=n.conversation_url " +
                        "ORDER BY t.recorded_at, t.rowid", null);
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
                    // Optional fields are ignored safely by older (1.82) readers.
                    if (!cursor.isNull(5)) {
                        item.put("chat_title", cursor.getString(5));
                        item.put("chat_title_updated_at_ms", cursor.getLong(6));
                    }
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
                    putTitleIfNewer(db, url, item.optString("chat_title", ""),
                            item.optLong("chat_title_updated_at_ms", 0));
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
