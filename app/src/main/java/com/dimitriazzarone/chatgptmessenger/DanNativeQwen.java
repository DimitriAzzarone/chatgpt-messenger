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
    private static final String Q4_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf";
    private static final String Q4_SHA256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db";
    private static final String Q3_NAME = "Qwen2.5-0.5B-Instruct-Q3_K_S.gguf";
    private static final String Q3_SHA256 = "468787b643ef9081a75c6e7bd98da987f46583250248e0a0a7aca173308e8d34";
    private static final String SPARK_NAME = "SparkAI-0.5b.Q3_K_S.gguf";
    private static final String SPARK_SHA256 = "88682e6bf44109dacea0dfde8a5f02dba5eddade9522007894742d57e653343c";
    private static final String PREFS = "dan_native_qwen";
    private static final String SELECTED = "selected_verified_model";
    private static final long MIN_FILE_SIZE = 300L * 1024L * 1024L;
    private static final long MAX_FILE_SIZE = 800L * 1024L * 1024L;
    private static boolean libraryReady;
    private static boolean modelReady;

    private DanNativeQwen() {}

    private static native boolean nativeLoad(String path);
    private static native String nativeGenerate(String prompt, int maxTokens);
    private static native void nativeUnload();

    static File modelFile(Context context) {
        String selected = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(SELECTED, Q4_NAME);
        if (!Q4_NAME.equals(selected) && !Q3_NAME.equals(selected)
                && !SPARK_NAME.equals(selected)) selected = Q4_NAME;
        return new File(new File(context.getFilesDir(), "models"), selected);
    }

    static boolean hasImportedModel(Context context) {
        File f = modelFile(context);
        return f.isFile() && f.length() >= MIN_FILE_SIZE && f.length() <= MAX_FILE_SIZE;
    }

    private static String sha256Of(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) != -1) md.update(b, 0, n);
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : md.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hash.toString();
    }

    /** Import only GGUF binaries whose publisher SHA-256 hashes are pinned above. */
    static synchronized void importModel(Context context, Uri uri) throws Exception {
        File parent = new File(context.getFilesDir(), "models");
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Impossibile creare la cartella modelli");
        File tmp = new File(parent, "model-import.partial");
        if (tmp.exists() && !tmp.delete())
            throw new IOException("Impossibile eliminare un'importazione incompleta");
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
            if (total < MIN_FILE_SIZE)
                throw new IOException("Modello troppo piccolo o download incompleto");
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest())
                hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            String verified = hash.toString();
            final String name;
            if (Q4_SHA256.equals(verified)) name = Q4_NAME;
            else if (Q3_SHA256.equals(verified)) name = Q3_NAME;
            else if (SPARK_SHA256.equals(verified)) name = SPARK_NAME;
            else throw new IOException("SHA-256 non riconosciuto: modello Qwen o SparkAI non verificato");
            try (FileInputStream in = new FileInputStream(tmp)) {
                byte[] head = new byte[4];
                if (in.read(head) != 4 || !"GGUF".equals(new String(head, StandardCharsets.US_ASCII)))
                    throw new IOException("File GGUF non valido");
            }
            File target = new File(parent, name);
            if (target.exists()) {
                // Never silently replace an installed model with different bytes.
                if (!target.isFile() || target.length() != total || !verified.equals(sha256Of(target)))
                    throw new IOException("File del modello gia' presente ma diverso: nessuna sovrascrittura");
            } else if (!tmp.renameTo(target)) {
                throw new IOException("Impossibile finalizzare il modello");
            }
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(SELECTED, name).commit())
                throw new IOException("Impossibile salvare il modello selezionato");
            if (modelReady) {
                nativeUnload();
                modelReady = false;
            }
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    static synchronized String answer(Context context, JSONArray messages) throws Exception {
        File model = modelFile(context);
        if (!model.isFile()) throw new IOException("Importa Qwen o SparkAI da Impostazioni");
        if (!libraryReady) {
            try { System.loadLibrary("dan_qwen"); libraryReady = true; }
            catch (UnsatisfiedLinkError e) {
                throw new IOException("Motore nativo non disponibile in questa build: " + e.getMessage(), e);
            }
        }
        if (!modelReady) {
            modelReady = nativeLoad(model.getAbsolutePath());
            if (!modelReady) throw new IOException("Modello locale: caricamento nativo non riuscito");
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
        // SparkAI uses Qwen3; prefill an empty thinking section so the
        // small model gives the user a direct answer. Qwen2.5 unchanged.
        if (SPARK_NAME.equals(model.getName()))
            prompt.append("<think>\n\n</think>\n\n");
        String reply = nativeGenerate(prompt.toString(), 160);
        if (reply == null || reply.trim().isEmpty())
            throw new IOException("Qwen non ha generato una risposta");
        return reply.trim();
    }
}
