package com.dimitriazzarone.chatgptmessenger;

import android.content.Context;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Local llama.cpp runtime. The GGUF is imported with Android SAF; never shared with a server. */
final class DanNativeQwen {
    private static final String MODEL_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf";
    private static final String SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db";
    private static final long MAX_FILE_SIZE = 800L * 1024L * 1024L;
    private static boolean libraryReady;
    private static boolean modelReady;

    private DanNativeQwen() {}

    private static native boolean nativeLoad(String path);
    private static native String nativeGenerate(String prompt, int maxTokens);
    private static native void nativeUnload();

    static File modelFile(Context context) {
        return new File(new File(context.getFilesDir(), "models"), MODEL_NAME);
    }

    static boolean hasImportedModel(Context context) {
        File f = modelFile(context);
        return f.isFile() && f.length() > 450L * 1024L * 1024L;
    }

    /** Call on a background worker, not UI thread. Only current officially downloaded GGUF accepted. */
    static synchronized void importModel(Context context, Uri uri) throws Exception {
        File target = modelFile(context);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Impossibile creare la cartella modelli");
        File tmp = new File(parent, MODEL_NAME + ".partial");
        if (tmp.exists() && !tmp.delete())
            throw new IOException("Importazione precedente incompleta: impossibile eliminare il temporaneo");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try {
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("File non leggibile");
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_FILE_SIZE) throw new IOException("Modello troppo grande");
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
                out.getFD().sync();
            }
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest())
                hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (!SHA256.equals(hash.toString()))
                throw new IOException("SHA-256 non corrisponde al modello Qwen verificato");
            try (FileInputStream in = new FileInputStream(tmp)) {
                byte[] head = new byte[4];
                if (in.read(head) != 4 || !"GGUF".equals(new String(head, StandardCharsets.US_ASCII)))
                    throw new IOException("File GGUF non valido");
            }
            // Existing model is never overwritten automatically.
            if (target.exists()) {
                if (target.length() == tmp.length()) return;
                throw new IOException("Modello gia' presente: nessuna sovrascrittura automatica");
            }
            if (!tmp.renameTo(target)) throw new IOException("Impossibile finalizzare il modello");
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    static synchronized String answer(Context context, JSONArray messages) throws Exception {
        File model = modelFile(context);
        if (!model.isFile()) throw new IOException("Importa il modello Qwen da Impostazioni");
        if (!libraryReady) {
            try { System.loadLibrary("dan_qwen"); libraryReady = true; }
            catch (UnsatisfiedLinkError e) {
                throw new IOException("Motore nativo non disponibile in questa build: " + e.getMessage(), e);
            }
        }
        if (!modelReady) {
            modelReady = nativeLoad(model.getAbsolutePath());
            if (!modelReady) throw new IOException("Qwen: caricamento nativo non riuscito");
        }
        StringBuilder prompt = new StringBuilder();
        // ChatML is the standard chat format used by Qwen2.5-Instruct.
        for (int i = 0; i < messages.length(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            String role = msg.optString("role", "user");
            if (!role.equals("system") && !role.equals("user") && !role.equals("assistant"))
                role = "user";
            String body = msg.optString("content", "");
            // Prevent text stored in the conversation from forging new ChatML roles.
            body = body.replace("<|im_start|>", "[inizio]")
                       .replace("<|im_end|>", "[fine]");
            prompt.append("<|im_start|>").append(role).append("\n")
                  .append(body).append("<|im_end|>\n");
        }
        prompt.append("<|im_start|>assistant\n");
        String reply = nativeGenerate(prompt.toString(), 160);
        if (reply == null || reply.trim().isEmpty())
            throw new IOException("Qwen non ha generato una risposta");
        return reply.trim();
    }
}
