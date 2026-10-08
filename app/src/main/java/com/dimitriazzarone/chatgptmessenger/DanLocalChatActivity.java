package com.dimitriazzarone.chatgptmessenger;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.content.Intent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DAN 1.65 - chat autonoma, indipendente dalla WebView di ChatGPT.
 *
 * Trasporto esclusivo verso http://127.0.0.1:8080 (stesso dispositivo).
 * Compatibile con llama-server (llama.cpp) o server che espongono
 * POST /v1/chat/completions in formato OpenAI-compatibile.
 * Il formato API non implica l'utilizzo di OpenAI: non sono contattati
 * domini esterni e non e' previsto alcun fallback cloud.
 */
public final class DanLocalChatActivity extends Activity {
    private static final String BASE_URL = "http://127.0.0.1:8080";
    private static final String PREFS = "dan_local_chat_165";
    private static final String KEY_HISTORY = "conversation";
    private static final int MAX_STORED_MESSAGES = 120;
    private static final int MAX_CONTEXT_MESSAGES = 30;
    private static final int MAX_USER_CHARS = 5000;
    private static final int MAX_REPLY_CHARS = 40000;
    private static final String SYSTEM_PROMPT =
            "Sei Dan, un assistente personale in italiano. "
          + "Sei un'identita' separata dal motore AI utilizzato. "
          + "Rispondi con chiarezza, gentilezza e precisione. "
          + "Non inventare fatti, risultati di azioni, file o verifiche. "
          + "Se non conosci un dato, dichiaralo. "
          + "Non affermare di ricordare informazioni che non sono disponibili. "
          + "Non dichiarare di poter comandare il dispositivo senza strumenti reali.";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<ChatMessage> history = new ArrayList<>();
    private LinearLayout messagesArea;
    private ScrollView scroll;
    private EditText input;
    private Button send;
    private TextView connectionLabel;
    private boolean waiting = false;

    private static final class ChatMessage {
        final String role;
        final String content;
        ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(7, 33, 49));
        getWindow().setNavigationBarColor(Color.rgb(7, 33, 49));
        loadHistory();
        buildLayout();
        redrawMessages();
        checkEngine();
    }

    private int dp(float d) {
        return (int) (d * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable bg(int color, int border, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (border != 0) d.setStroke(dp(1), border);
        return d;
    }

    private TextView label(String text, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        return t;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setBackground(bg(Color.rgb(15, 90, 110), Color.rgb(58, 159, 171), 14));
        return b;
    }

    private void buildLayout() {
        int white = Color.rgb(247, 247, 255);
        int faded = Color.rgb(186, 196, 220);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(7, 33, 49));
        root.setPadding(dp(12), dp(12), dp(12), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("✦  DAN  ·  LOCALE", 19, white);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button web = button("↗");
        web.setContentDescription("Ritorna alla modalita ChatGPT");
        web.setOnClickListener(v -> {
            Intent openWeb = new Intent(this, MainActivity.class);
            openWeb.putExtra("dan_open_web", true);
            startActivity(openWeb);
            finish();
        });
        header.addView(web, new LinearLayout.LayoutParams(dp(50), dp(46)));
        root.addView(header);

        TextView intro = label("Conversazione nativa · memoria sul dispositivo", 12, faded);
        intro.setPadding(0, dp(6), 0, dp(14));
        root.addView(intro);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(bg(Color.rgb(15, 58, 75), Color.rgb(50, 126, 143), 17));
        connectionLabel = label("Verifica motore in corso…", 13, Color.rgb(126, 226, 222));
        card.addView(connectionLabel);
        TextView route = label("Qwen / llama.cpp · 127.0.0.1:8080 · nessun invio a ChatGPT", 11, faded);
        route.setPadding(0, dp(7), 0, dp(9));
        card.addView(route);
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        Button verify = button("Verifica motore");
        verify.setOnClickListener(v -> checkEngine());
        tools.addView(verify, new LinearLayout.LayoutParams(0, dp(40), 1));
        Button reset = button("Nuova chat");
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(0, dp(40), 1);
        resetParams.leftMargin = dp(8);
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Nuova chat locale")
                .setMessage("Eliminare i messaggi della chat locale corrente? Questa azione non puo' essere annullata.")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Elimina", (dialog, which) -> {
                    history.clear();
                    saveHistory();
                    redrawMessages();
                }).show());
        tools.addView(reset, resetParams);
        card.addView(tools);
        root.addView(card);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        messagesArea = new LinearLayout(this);
        messagesArea.setOrientation(LinearLayout.VERTICAL);
        messagesArea.setPadding(0, dp(12), 0, dp(12));
        scroll.addView(messagesArea);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        root.addView(scroll, scrollParams);

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(8), dp(6), dp(7), dp(6));
        composer.setBackground(bg(Color.rgb(15, 55, 73), Color.rgb(50, 129, 148), 17));
        input = new EditText(this);
        input.setHint("Scrivi direttamente a Dan…");
        input.setHintTextColor(Color.rgb(158, 167, 196));
        input.setTextColor(white);
        input.setTextSize(15);
        input.setMinLines(1);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendMessage();
                return true;
            }
            return false;
        });
        input.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && !event.isShiftPressed()) {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getRepeatCount() == 0) {
                    sendMessage();
                }
                return true;
            }
            return false;
        });
        composer.addView(input, new LinearLayout.LayoutParams(0, dp(54), 1));
        send = button("Invia ➤");
        send.setBackground(bg(Color.rgb(21, 148, 176), 0, 14));
        send.setOnClickListener(v -> sendMessage());
        composer.addView(send, new LinearLayout.LayoutParams(dp(90), dp(52)));
        root.addView(composer);
        TextView note = label("Dan locale funziona solo se un server AI e' attivo su QUESTO dispositivo.\n"
                + "Nessun fallback a Internet o ChatGPT.", 11, faded);
        note.setPadding(dp(4), dp(10), dp(4), dp(3));
        root.addView(note);
        setContentView(root);
    }

    private void redrawMessages() {
        if (messagesArea == null) return;
        messagesArea.removeAllViews();
        if (history.isEmpty()) {
            TextView welcome = label("Ciao! Questa e' la mia nuova casa.\n\n"
                    + "Per ricevere risposte avvia un modello locale compatibile, "
                    + "per esempio llama-server in Termux. "
                    + "Se non e' attivo, te lo diro' senza inventare una risposta.",
                    15, Color.rgb(217, 221, 246));
            welcome.setPadding(dp(16), dp(20), dp(16), dp(20));
            welcome.setBackground(bg(Color.rgb(14, 63, 80), Color.rgb(48, 135, 152), 18));
            messagesArea.addView(welcome);
        }
        for (ChatMessage msg : history) {
            boolean user = "user".equals(msg.role);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(14), dp(10), dp(14), dp(11));
            item.setBackground(bg(user ? Color.rgb(20, 95, 117)
                    : Color.rgb(24, 49, 68), user ? Color.rgb(63, 164, 180)
                    : Color.rgb(50, 102, 120), 16));
            TextView who = label(user ? "TU" : "DAN · MOTORE LOCALE", 11,
                    Color.rgb(141, 221, 225));
            who.setTypeface(null, android.graphics.Typeface.BOLD);
            item.addView(who);
            TextView body = label(msg.content, 15, Color.WHITE);
            body.setPadding(0, dp(6), 0, 0);
            body.setTextIsSelectable(true);
            item.addView(body);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            ip.bottomMargin = dp(10);
            ip.leftMargin = dp(user ? 34 : 0);
            ip.rightMargin = dp(user ? 0 : 34);
            messagesArea.addView(item, ip);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setStatus(String status, boolean ok) {
        if (connectionLabel != null) {
            connectionLabel.setText(status);
            connectionLabel.setTextColor(ok ? Color.rgb(128, 234, 197)
                    : Color.rgb(253, 191, 150));
        }
    }

    private void checkEngine() {
        setStatus("Controllo motore locale…", false);
        executor.execute(() -> {
            String status;
            boolean healthy = false;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(BASE_URL + "/health").openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(3500);
                int code = conn.getResponseCode();
                healthy = code == 200;
                status = healthy ? "● Motore locale disponibile"
                        : "● Motore non pronto · HTTP " + code;
            } catch (Exception e) {
                status = "● Motore non connesso · avvia il server in Termux";
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String finalStatus = status;
            final boolean finalHealthy = healthy;
            runOnUiThread(() -> { if (!isFinishing()) setStatus(finalStatus, finalHealthy); });
        });
    }

    private void sendMessage() {
        if (waiting) return;
        String question = input.getText().toString().trim();
        if (question.isEmpty()) return;
        if (question.length() > MAX_USER_CHARS) {
            new AlertDialog.Builder(this).setTitle("Messaggio troppo lungo")
                    .setMessage("Limite: " + MAX_USER_CHARS + " caratteri per messaggio.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        input.setText("");
        history.add(new ChatMessage("user", question));
        trimHistory();
        saveHistory();
        redrawMessages();
        waiting = true;
        send.setEnabled(false);
        setStatus("Dan sta interrogando il motore locale…", true);
        List<ChatMessage> snapshot = new ArrayList<>(history);
        executor.execute(() -> {
            String reply = null;
            String error = null;
            try {
                reply = requestLocalReply(snapshot);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String answer = reply;
            final String failure = error;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                waiting = false;
                send.setEnabled(true);
                if (answer != null && !answer.trim().isEmpty()) {
                    history.add(new ChatMessage("assistant", answer.trim()));
                    trimHistory();
                    saveHistory();
                    redrawMessages();
                    setStatus("● Risposta ricevuta dal motore locale", true);
                } else {
                    // La domanda rimane in memoria; l'errore NON e' una risposta AI.
                    setStatus("● Errore motore locale", false);
                    new AlertDialog.Builder(this)
                            .setTitle("Dan locale non ha risposto")
                            .setMessage("Nessuna risposta inventata o inviata a ChatGPT.\n\n"
                                    + (failure == null ? "Risposta vuota dal modello." : failure))
                            .setPositiveButton("OK", null).show();
                }
            });
        });
    }

    private String requestLocalReply(List<ChatMessage> snapshot) throws Exception {
        JSONArray messages = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT);
        messages.put(sys);
        int start = Math.max(0, snapshot.size() - MAX_CONTEXT_MESSAGES);
        for (int i = start; i < snapshot.size(); i++) {
            ChatMessage m = snapshot.get(i);
            JSONObject j = new JSONObject();
            j.put("role", m.role);
            j.put("content", m.content);
            messages.put(j);
        }
        JSONObject payload = new JSONObject();
        payload.put("messages", messages);
        payload.put("stream", false);
        payload.put("temperature", 0.6);
        payload.put("max_tokens", 768);
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE_URL + "/v1/chat/completions")
                    .openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(6500);
            conn.setReadTimeout(180000);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(bytes);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String details = readLimit(conn.getErrorStream(), 2000);
                throw new Exception("Server locale HTTP " + code + "\n" + details);
            }
            String raw = readLimit(conn.getInputStream(), 1024 * 1024);
            JSONObject root = new JSONObject(raw);
            JSONArray choices = root.getJSONArray("choices");
            if (choices.length() == 0) throw new Exception("Il server non ha restituito scelte.");
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            String result = message.optString("content", "");
            if (result.isEmpty()) throw new Exception("Il modello ha restituito contenuto vuoto.");
            return result.length() > MAX_REPLY_CHARS ? result.substring(0, MAX_REPLY_CHARS) : result;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readLimit(InputStream in, int maxChars) throws Exception {
        if (in == null) return "";
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                if (result.length() + count > maxChars)
                    throw new Exception("Risposta del server troppo lunga.");
                result.append(buffer, 0, count);
            }
            return result.toString();
        }
    }

    private void trimHistory() {
        while (history.size() > MAX_STORED_MESSAGES) history.remove(0);
    }

    private void loadHistory() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            JSONArray raw = new JSONArray(prefs.getString(KEY_HISTORY, "[]"));
            for (int i = 0; i < raw.length(); i++) {
                JSONObject row = raw.getJSONObject(i);
                String role = row.optString("role", "");
                String text = row.optString("content", "");
                if (("user".equals(role) || "assistant".equals(role)) && !text.isEmpty()) {
                    history.add(new ChatMessage(role, text));
                }
            }
            trimHistory();
        } catch (Exception ignored) {
            history.clear();
        }
    }

    private void saveHistory() {
        JSONArray data = new JSONArray();
        try {
            for (ChatMessage m : history) {
                JSONObject row = new JSONObject();
                row.put("role", m.role);
                row.put("content", m.content);
                data.put(row);
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_HISTORY, data.toString()).apply();
        } catch (Exception ignored) {
            setStatus("Salvataggio locale non riuscito", false);
        }
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
