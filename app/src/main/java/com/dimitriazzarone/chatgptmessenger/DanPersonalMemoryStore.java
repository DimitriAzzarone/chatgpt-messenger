package com.dimitriazzarone.chatgptmessenger;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Independent offline document index. Never modifies or deletes the original Drive files. */
final class DanPersonalMemoryStore extends SQLiteOpenHelper {
    private static final int CHUNK_CHARS = 1250;
    private static final int MAX_CONTEXT_CHARS = 2350;

    DanPersonalMemoryStore(Context context) { super(context, "dan_personal_documents.db", null, 2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE documents (source TEXT PRIMARY KEY, name TEXT NOT NULL, "
                + "modified INTEGER NOT NULL, size INTEGER NOT NULL, chunks INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE chunks (source TEXT NOT NULL, part INTEGER NOT NULL, "
                + "name TEXT NOT NULL, body TEXT NOT NULL, PRIMARY KEY (source,part))");
        db.execSQL("CREATE INDEX chunks_source ON chunks(source)");
        createSearchIndex(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int before, int after) {
        if (before < 2) createSearchIndex(db);
    }


    private void createSearchIndex(SQLiteDatabase db) {
        db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS chunks_search USING fts4(name, body)");
        db.execSQL("INSERT INTO chunks_search(rowid,name,body) SELECT rowid,name,body FROM chunks");
        db.execSQL("CREATE TRIGGER chunks_search_insert AFTER INSERT ON chunks BEGIN "
                + "INSERT INTO chunks_search(rowid,name,body) VALUES(new.rowid,new.name,new.body); END");
        db.execSQL("CREATE TRIGGER chunks_search_delete AFTER DELETE ON chunks BEGIN "
                + "DELETE FROM chunks_search WHERE rowid=old.rowid; END");
        db.execSQL("CREATE TRIGGER chunks_search_update AFTER UPDATE ON chunks BEGIN "
                + "DELETE FROM chunks_search WHERE rowid=old.rowid; "
                + "INSERT INTO chunks_search(rowid,name,body) VALUES(new.rowid,new.name,new.body); END");
    }

    private Cursor searchCandidates(Set<String> terms) {
        if (terms.isEmpty())
            return getReadableDatabase().rawQuery(
                    "SELECT name,body FROM chunks_search WHERE name LIKE ?",
                    new String[]{"%profilo-dan%"});
        StringBuilder query = new StringBuilder();
        for (String term : terms) {
            if (query.length() > 0) query.append(" OR ");
            query.append(term).append("*");
        }
        return getReadableDatabase().rawQuery(
                "SELECT name,body FROM chunks_search WHERE chunks_search MATCH ?",
                new String[]{query.toString()});
    }

    boolean unchanged(String source, long modified, long size) {
        if (size <= 0 || modified <= 0) return false; // Providers may not expose modification timestamps.
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT modified,size FROM documents WHERE source=?", new String[]{source})) {
            return c.moveToFirst() && c.getLong(0) == modified && c.getLong(1) == size;
        }
    }

    /** Replaces one indexed document only after text extraction succeeds. */
    int index(String source, String name, long modified, long size, String content) throws Exception {
        if (content == null || content.trim().isEmpty()) return 0;
        if (source == null || source.length() > 1000 || name == null || name.length() > 500)
            throw new IllegalArgumentException("Identificativo documento non valido");
        String clean = content.replace('\u0000',' ').replaceAll("[\t ]+", " ").trim();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("chunks", "source=?", new String[]{source});
            int parts = 0;
            for (int start = 0; start < clean.length();) {
                // Do not split a surrogate pair representing a Unicode codepoint.
                int end = Math.min(clean.length(), start + CHUNK_CHARS);
                if (end < clean.length() && Character.isHighSurrogate(clean.charAt(end-1))) end--;
                ContentValues values = new ContentValues();
                values.put("source", source); values.put("part", parts);
                values.put("name", name); values.put("body", clean.substring(start,end));
                if (db.insertOrThrow("chunks", null, values) == -1)
                    throw new IllegalStateException("Memoria non salvata");
                parts++;
                start = end; // Unicode-safe progress.
            }
            ContentValues doc = new ContentValues();
            doc.put("source", source); doc.put("name", name);
            doc.put("modified", modified); doc.put("size", size); doc.put("chunks", parts);
            db.insertWithOnConflict("documents", null, doc, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
            return parts;
        } finally { db.endTransaction(); }
    }

    int documentCount() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM documents", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Only short quoted context snippets are sent to Qwen. Documents are NOT instructions. */
    String relevant(String question) {
        Set<String> terms = new LinkedHashSet<>();
        // Prevent boilerplate in autobiographical questions from outranking names.
        String stop = "|questa|questo|quello|quella|come|cosa|sono|della|delle|" +
                "ricordi|ricordo|raccontami|vorrei|quando|perche|perché|sapere|" +
                "dimmi|fammi|parlami|fatti|fatto|concreti|usando|memoria|" +
                "autobiografica|autobiografico|documenti|secondo|";
        for (String w : question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 4 && !stop.contains("|" + w + "|")) terms.add(w);
            if (terms.size() >= 6) break;
        }
        List<Match> found = new ArrayList<>();
        try (Cursor c = searchCandidates(terms)) {
            while (c.moveToNext()) {
                String name = c.getString(0), body = c.getString(1);
                String low = body.toLowerCase(Locale.ROOT);
                int score = 0, first = -1;
                boolean profile = name.toLowerCase(Locale.ROOT).contains("profilo-dan");
                if (profile) score = 1;
                for (String term : terms) {
                    int at = low.indexOf(term);
                    if (at >= 0) { score += 2; if (first < 0 || at < first) first = at; }
                    if (name.toLowerCase(Locale.ROOT).contains(term)) score += 3;
                }
                if (score <= 0) continue;
                int begin = first < 0 ? 0 : Math.max(0,first-120);
                int end = Math.min(body.length(), begin + (profile ? 520 : 650));
                found.add(new Match(name,score, (begin > 0 ? "…" : "")
                        + body.substring(begin,end).replace('\r',' ').replace('\n',' ')
                        + (end < body.length() ? "…" : "")));
            }
        }
        found.sort(Comparator.comparingInt((Match m)->m.score).reversed());
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (Match m : found) {
            String note = "- [" + m.name + "] " + m.excerpt + "\n";
            if (out.length() + note.length() > MAX_CONTEXT_CHARS) continue;
            out.append(note);
            if (++used >= 3) break;
        }
        return out.toString();
    }
    private static final class Match {
        final String name,excerpt; final int score;
        Match(String n,int s,String e) { name=n;score=s;excerpt=e; }
    }
}
