package com.dimitriazzarone.chatgptmessenger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** A separate archive so importing old chats does not enlarge the live JSONL backup. */
final class DanHistoryStore extends SQLiteOpenHelper {
    DanHistoryStore(Context context) {
        super(context, "dan_history.db", null, 1);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, title TEXT, "
                + "created_at REAL, updated_at REAL, raw_json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX conversations_title ON conversations(title)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Versione archivio non supportata");
    }

    int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM conversations", null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }

    int importArchive(InputStream source) throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        Map<String, Part> observed = new HashMap<>();
        Set<String> ids = new HashSet<>();
        JSONObject manifest = null;
        int total = 0;
        db.beginTransaction();
        try (ZipInputStream zip = new ZipInputStream(source, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || (!name.matches("conversations-[0-9]{3}\\.json")
                        && !name.equals("dan-history-manifest.json"))) {
                    throw new IOException("File inatteso nell'archivio: " + name);
                }
                byte[] bytes = readEntry(zip, 40 * 1024 * 1024);
                if (name.equals("dan-history-manifest.json")) {
                    if (manifest != null) throw new IOException("Manifesto duplicato");
                    manifest = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                } else {
                    if (manifest != null || observed.containsKey(name))
                        throw new IOException("Parte duplicata o fuori ordine: " + name);
                    JSONArray chats = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
                    for (int i = 0; i < chats.length(); i++) {
                        JSONObject chat = chats.getJSONObject(i);
                        String id = chat.optString("conversation_id", chat.optString("id", ""));
                        if (id.isEmpty() || !chat.has("mapping") || !ids.add(id))
                            throw new IOException("Conversazione senza ID, mapping o duplicata");
                        ContentValues row = new ContentValues();
                        row.put("id", id);
                        row.put("title", chat.optString("title", ""));
                        row.put("created_at", chat.optDouble("create_time", 0));
                        row.put("updated_at", chat.optDouble("update_time", 0));
                        row.put("raw_json", chat.toString());
                        if (db.insertWithOnConflict("conversations", null, row,
                                SQLiteDatabase.CONFLICT_REPLACE) == -1)
                            throw new IOException("Scrittura della chat fallita");
                    }
                    observed.put(name, new Part(chats.length(), bytes.length, sha256(bytes)));
                    total += chats.length();
                }
                zip.closeEntry();
            }
            if (manifest == null || observed.isEmpty()) throw new IOException("Manifesto o chat assenti");
            JSONArray parts = manifest.getJSONArray("parts");
            if (parts.length() != observed.size() || manifest.getInt("conversations") != total)
                throw new IOException("Numero di chat o parti non corrispondente");
            for (int i = 0; i < parts.length(); i++) {
                JSONObject expected = parts.getJSONObject(i);
                Part actual = observed.remove(expected.getString("name"));
                if (actual == null || actual.count != expected.getInt("count")
                        || actual.bytes != expected.getInt("bytes")
                        || !actual.hash.equalsIgnoreCase(expected.getString("sha256")))
                    throw new IOException("Verifica fallita: " + expected.getString("name"));
            }
            if (!observed.isEmpty()) throw new IOException("Parti non verificate");
            db.setTransactionSuccessful();
            return total;
        } finally {
            db.endTransaction();
        }
    }

    private static byte[] readEntry(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() > limit - n) throw new IOException("Parte troppo grande");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte b : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }

    private static final class Part {
        final int count;
        final int bytes;
        final String hash;
        Part(int count, int bytes, String hash) {
            this.count = count;
            this.bytes = bytes;
            this.hash = hash;
        }
    }
}
