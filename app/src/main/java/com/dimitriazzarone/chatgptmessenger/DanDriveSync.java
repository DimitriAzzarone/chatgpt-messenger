package com.dimitriazzarone.chatgptmessenger;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Syncs a user-selected SAF folder. Each installation owns only its own backup files. */
final class DanDriveSync {
    static final String PREF_FOLDER = "dan_drive_folder";
    private static final String PREF_ARCHIVE = "dan_drive_archive_stamp";
    private static final String PREF_SLOT = "dan_drive_slot";
    private final Context context;
    private final SharedPreferences prefs;
    private final DanMemoryStore memory;
    private final DanHistoryStore history;

    DanDriveSync(Context context, SharedPreferences prefs, DanMemoryStore memory,
                 DanHistoryStore history) {
        this.context = context;
        this.prefs = prefs;
        this.memory = memory;
        this.history = history;
    }

    boolean configured() { return prefs.contains(PREF_FOLDER); }

    void setFolder(Uri uri) throws IOException {
        String name = documentName(uri);
        if (!"Memoria Dan".equals(name))
            throw new IOException("Seleziona la cartella Memoria Dan");
        prefs.edit().putString(PREF_FOLDER, uri.toString()).apply();
    }

    Result sync(boolean saveBackup) throws Exception {
        Uri tree = Uri.parse(prefs.getString(PREF_FOLDER, ""));
        if (tree.toString().isEmpty()) throw new IOException("Cartella non configurata");
        Map<String, Uri> files = children(tree);
        int historic = history.count();
        Uri archive = files.get("Dan-storico-completo.zip");
        if (archive != null) {
            String stamp = archive.toString() + ":" + documentSize(archive)
                    + ":" + documentModified(archive);
            if (historic == 0 || !stamp.equals(prefs.getString(PREF_ARCHIVE, ""))) {
                try (InputStream input = open(archive)) {
                    historic = history.importArchive(input);
                }
                prefs.edit().putString(PREF_ARCHIVE, stamp).apply();
            }
        }
        int added = 0;
        Set<String> goodDevices = new HashSet<>();
        Set<String> damagedDevices = new HashSet<>();
        for (Map.Entry<String, Uri> file : files.entrySet()) {
            String name = file.getKey();
            if (name.equals("Dan-memory.jsonl") || (name.startsWith("Dan-device-")
                    && name.endsWith(".jsonl"))) {
                String owner = name.matches("Dan-device-.+-[AB]\\.jsonl")
                        ? name.substring(0, name.length() - 8) : name;
                try (InputStream input = open(file.getValue())) {
                    added += memory.importJsonl(input);
                    goodDevices.add(owner);
                } catch (Exception e) {
                    damagedDevices.add(owner);
                }
            }
        }
        damagedDevices.removeAll(goodDevices);
        if (!damagedDevices.isEmpty())
            throw new IOException("Backup Drive non leggibile: " + damagedDevices);
        if (saveBackup) {
            String id = deviceId();
            String slot = prefs.getBoolean(PREF_SLOT, false) ? "A" : "B";
            String name = "Dan-device-" + id + "-" + slot + ".jsonl";
            Uri target = files.get(name);
            if (target == null) {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                        DocumentsContract.getTreeDocumentId(tree));
                target = DocumentsContract.createDocument(context.getContentResolver(), parent,
                        "application/x-ndjson", name);
            }
            if (target == null) throw new IOException("Impossibile creare il backup su Drive");
            try (OutputStream output = context.getContentResolver().openOutputStream(target, "wt")) {
                if (output == null) throw new IOException("Backup Drive non scrivibile");
                memory.exportJsonl(output);
            }
            prefs.edit().putBoolean(PREF_SLOT, !prefs.getBoolean(PREF_SLOT, false)).apply();
        }
        return new Result(historic, added, archive != null);
    }

    private String deviceId() throws IOException {
        // Android backup can restore SharedPreferences to a second device. Keep this
        // identifier outside that backup so two installations never own the same file.
        File file = new File(context.getNoBackupFilesDir(), "dan-device-id");
        if (!file.exists()) {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
        }
        byte[] bytes = new byte[36];
        try (FileInputStream input = new FileInputStream(file)) {
            if (input.read(bytes) != bytes.length || input.read() != -1)
                throw new IOException("ID dispositivo non valido");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private InputStream open(Uri uri) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("File Drive non leggibile");
        return input;
    }

    private Map<String, Uri> children(Uri tree) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String id = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        Map<String, Uri> result = new HashMap<>();
        try (Cursor c = resolver.query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null) throw new IOException("Cartella Drive non leggibile");
            while (c.moveToNext()) {
                if (result.put(c.getString(1),
                        DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))) != null)
                    throw new IOException("Nomi duplicati nella cartella: " + c.getString(1));
            }
        }
        return result;
    }

    private String documentName(Uri uri) throws IOException {
        return documentField(uri, DocumentsContract.Document.COLUMN_DISPLAY_NAME, "");
    }

    private long documentSize(Uri uri) throws IOException {
        return Long.parseLong(documentField(uri, DocumentsContract.Document.COLUMN_SIZE, "0"));
    }

    private long documentModified(Uri uri) throws IOException {
        return Long.parseLong(documentField(uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED, "0"));
    }

    private String documentField(Uri uri, String field, String fallback) throws IOException {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(uri,
                DocumentsContract.getTreeDocumentId(uri));
        // Child document URIs already contain their document ID.
        if (uri.getPath() != null && uri.getPath().contains("/document/")) doc = uri;
        try (Cursor c = context.getContentResolver().query(doc,
                new String[]{field}, null, null, null)) {
            if (c == null || !c.moveToFirst()) throw new IOException("Metadati Drive non leggibili");
            return c.isNull(0) ? fallback : c.getString(0);
        }
    }

    static final class Result {
        final int historyCount;
        final int newTurns;
        final boolean archiveFound;
        Result(int historyCount, int newTurns, boolean archiveFound) {
            this.historyCount = historyCount;
            this.newTurns = newTurns;
            this.archiveFound = archiveFound;
        }
    }
}
