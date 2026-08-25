package com.dimitriazzarone.chatgptmessenger;

import android.app.DownloadManager;
import android.content.Context;
import android.os.Environment;
import android.webkit.URLUtil;
import android.content.ClipData;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.database.Cursor;
import android.os.Build;
import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.view.KeyEvent;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String HOME = "https://chatgpt.com/";
    private static final int REQ_AUDIO = 3001;
    private static final int REQ_FILE_CHOOSER = 4001;
    private static final String PREFS = "radio_prefs";
    private static final String PREF_TTS_VOICE = "tts_voice";
    private static final String PREF_TTS_MODE = "tts_mode";
    private static final String MODE_NORMAL = "normal";
    private static final String MODE_SAGE = "sage";
    private static final String PREF_SAGE_BASE_VOICE = "sage_base_voice";
    private static final String PREF_TTS_SPEED = "tts_speed";
    private static final String PREF_HANDS_FREE = "hands_free_enabled";
    private static final String PREF_RECORDING_SOUNDS = "recording_sounds_enabled";

    private WebView webView;
    private FrameLayout webContainer;
    private ProgressBar progressBar;
    private TextView statusText;
    private Button micButton;
    private Button voiceButton;
    private Button autoButton;
    private Button speedButton;
    private Button soundButton;

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean listening = false;
    private boolean recognizerSessionActive = false;
    private boolean pressToTalkRequested = false;
    private boolean recordingLocked = false;
    private boolean manualCapture = false;
    private boolean handsFreeEnabled = false;
    private boolean handsFreeDictating = false;
    private boolean ttsSpeaking = false;
    private boolean headsetRecording = false;
    private boolean recordingSoundsEnabled = true;
    private String lastPartialText = "";
    private MediaSession headsetMediaSession;
    private final StringBuilder handsFreeBuffer = new StringBuilder();
    private static final String WAKE_WORD = "jasper";
    private float touchStartY = 0f;
    private float ttsSpeed = 1.0f;
    private String lastUrl = HOME;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String lastSpokenText = "";

    private ValueCallback<Uri[]> filePathCallback;
    private String pendingDownloadName = null;
    private long pendingDownloadNameAt = 0L;
    private long lastDownloadId = -1L;
    private BroadcastReceiver downloadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences startupPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        // v1.16 Audio Safe reale:
        // Jasper parte sempre OFF e il vecchio stato salvato viene azzerato.
        handsFreeEnabled = false;
        startupPrefs.edit().putBoolean(PREF_HANDS_FREE, false).apply();

        recordingSoundsEnabled = startupPrefs.getBoolean(PREF_RECORDING_SOUNDS, true);

        buildInterface();
        registerDownloadReceiver();
        createTextToSpeech();
        createSpeechRecognizer();
        createHeadsetMediaSession();
        createWebView();

        webView.loadUrl(HOME);
        // v1.15 Audio Safe: niente ascolto automatico all'avvio.
        // Il microfono si attiva solo con comando esplicito.
        statusText.setText("⚪ Audio Safe — usa microfono o cuffie quando vuoi");
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(11, 20, 26));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        topBar.setBackgroundColor(Color.rgb(32, 44, 51));

        TextView logo = new TextView(this);
        logo.setText("AI");
        logo.setTextColor(Color.rgb(6, 44, 37));
        logo.setTextSize(15);
        logo.setGravity(Gravity.CENTER);
        logo.setBackgroundColor(Color.rgb(0, 168, 132));

        TextView title = new TextView(this);
        title.setText("  Dan");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);

        voiceButton = makeButton("🔊");
        voiceButton.setTextSize(18);

        soundButton = makeButton(recordingSoundsEnabled ? "🔔" : "🔕");
        soundButton.setTextSize(18);

        Button back = makeButton("‹");
        Button reload = makeButton("↻");

        topBar.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        topBar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        topBar.addView(soundButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        topBar.addView(voiceButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        topBar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        topBar.addView(reload, new LinearLayout.LayoutParams(dp(44), dp(44)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);

        webContainer = new FrameLayout(this);

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(12), dp(6), dp(12), dp(8));
        bottomBar.setBackgroundColor(Color.rgb(32, 44, 51));

        statusText = new TextView(this);
        statusText.setText(handsFreeEnabled
                ? "🟢 Jasper ON — dì Jasper per iniziare"
                : "⚪ Audio libero — microfono/cuffie solo quando li usi");
        statusText.setTextColor(Color.LTGRAY);
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER_VERTICAL);

        micButton = new Button(this);
        micButton.setText("🎙");
        micButton.setTextSize(25);
        micButton.setTextColor(Color.WHITE);
        micButton.setGravity(Gravity.CENTER);
        applyMicStyle(false);

        bottomBar.addView(statusText, new LinearLayout.LayoutParams(0, dp(58), 1));

        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        micParams.setMargins(dp(8), 0, 0, 0);
        bottomBar.addView(micButton, micParams);

        root.addView(topBar);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        root.addView(webContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));

        setContentView(root);

        back.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) webView.goBack();
        });

        reload.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });

        voiceButton.setOnClickListener(v -> showVoiceChooser());
        soundButton.setOnClickListener(v -> toggleRecordingSounds());

        micButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (recordingLocked) {
                        finishLockedRecording();
                        return true;
                    }

                    touchStartY = event.getRawY();
                    pressToTalkRequested = true;
                    manualCapture = true;
                    if (speechRecognizer != null && recognizerSessionActive) {
                        try { speechRecognizer.cancel(); } catch (Exception ignored) {}
                    }
                    startPressToTalk();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (listening && !recordingLocked) {
                        float movedUp = touchStartY - event.getRawY();

                        if (movedUp >= dp(80)) {
                            recordingLocked = true;
                            pressToTalkRequested = false;
                            micButton.setText("■");
                            applyMicStyle(true);
                            statusText.setText("🔒 Registrazione bloccata — tocca ■ per inviare");
                        }
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pressToTalkRequested = false;

                    if (!recordingLocked) {
                        stopPressToTalk();
                    }
                    return true;
            }

            return false;
        });
    }

    private void toggleRecordingSounds() {
        recordingSoundsEnabled = !recordingSoundsEnabled;

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_RECORDING_SOUNDS, recordingSoundsEnabled)
                .apply();

        if (soundButton != null) {
            soundButton.setText(recordingSoundsEnabled ? "🔔" : "🔕");
        }

        Toast.makeText(
                this,
                recordingSoundsEnabled ? "Suoni registrazione attivi" : "Suoni registrazione disattivati",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void playRecordingStartSound() {
        if (!recordingSoundsEnabled) return;
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 28);
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 90);
            statusText.postDelayed(tone::release, 180L);
        } catch (Exception ignored) {}
    }

    private void playRecordingEndSound() {
        if (!recordingSoundsEnabled) return;
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 24);
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 120);
            statusText.postDelayed(tone::release, 220L);
        } catch (Exception ignored) {}
    }

    private String chooseBestRecognition(Bundle results) {
        if (results == null) return lastPartialText == null ? "" : lastPartialText.trim();

        ArrayList<String> matches =
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

        if (matches == null || matches.isEmpty()) {
            return lastPartialText == null ? "" : lastPartialText.trim();
        }

        float[] confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        int bestIndex = 0;
        float bestScore = -1.0f;

        if (confidence != null) {
            int limit = Math.min(matches.size(), confidence.length);
            for (int i = 0; i < limit; i++) {
                String candidate = matches.get(i) == null ? "" : matches.get(i).trim();
                float score = confidence[i];
                if (!candidate.isEmpty() && score >= 0.0f && score > bestScore) {
                    bestScore = score;
                    bestIndex = i;
                }
            }
        }

        String best = matches.get(bestIndex) == null ? "" : matches.get(bestIndex).trim();
        String partial = lastPartialText == null ? "" : lastPartialText.trim();

        if (best.isEmpty()) return partial;
        if (!partial.isEmpty() && best.length() < partial.length() / 2) return partial;
        return best;
    }

    private void toggleHandsFreeMode() {
        handsFreeEnabled = !handsFreeEnabled;

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_HANDS_FREE, handsFreeEnabled)
                .apply();

        if (handsFreeEnabled) {
            handsFreeDictating = false;
            handsFreeBuffer.setLength(0);
            autoButton.setText("Jasper✓");
            statusText.setText("🟢 Auto ON — dì Jasper per iniziare");
            startHandsFreeMode();
        } else {
            handsFreeDictating = false;
            handsFreeBuffer.setLength(0);
            autoButton.setText("Jasper×");

            if (speechRecognizer != null && recognizerSessionActive) {
                try { speechRecognizer.cancel(); } catch (Exception ignored) {}
            }

            recognizerSessionActive = false;
            listening = false;
            applyMicStyle(false);
            statusText.setText("⚪ Auto OFF — usa il microfono manuale");
        }
    }

    private void restoreTtsSpeed() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        ttsSpeed = prefs.getFloat(PREF_TTS_SPEED, 1.0f);

        if (ttsSpeed != 1.0f && ttsSpeed != 1.5f && ttsSpeed != 2.0f) {
            ttsSpeed = 1.0f;
        }

        updateSpeedButton();
        applyCurrentSpeechRate();
    }

    private void cycleTtsSpeed() {
        if (ttsSpeed == 1.0f) {
            ttsSpeed = 1.5f;
        } else if (ttsSpeed == 1.5f) {
            ttsSpeed = 2.0f;
        } else {
            ttsSpeed = 1.0f;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putFloat(PREF_TTS_SPEED, ttsSpeed)
                .apply();

        updateSpeedButton();
        applyCurrentSpeechRate();

        Toast.makeText(
                this,
                "Velocità voce: " + speedLabel(),
                Toast.LENGTH_SHORT
        ).show();
    }

    private String speedLabel() {
        if (ttsSpeed == 1.5f) return "1,5×";
        if (ttsSpeed == 2.0f) return "2×";
        return "1×";
    }

    private void updateSpeedButton() {
        if (speedButton != null) {
            speedButton.setText(speedLabel());
        }
    }

    private void applyCurrentSpeechRate() {
        if (tts == null) return;

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = prefs.getString(PREF_TTS_MODE, MODE_NORMAL);

        float baseRate = MODE_SAGE.equals(mode) ? 0.76f : 1.0f;
        tts.setSpeechRate(baseRate * ttsSpeed);
    }

    private void openDocumentPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQ_FILE_CHOOSER);
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "Impossibile aprire il selettore documenti",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void applyMicStyle(boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(active
                ? Color.rgb(239, 83, 80)
                : Color.rgb(0, 168, 132));
        micButton.setBackground(bg);
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(21);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private void createTextToSpeech() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) {
                ttsReady = false;
                return;
            }

            int result = tts.setLanguage(Locale.getDefault());
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED;

            restoreSavedVoice();
            restoreTtsSpeed();

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    ttsSpeaking = true;
                    runOnUiThread(() -> {
                        if (speechRecognizer != null && recognizerSessionActive) {
                            try { speechRecognizer.cancel(); } catch (Exception ignored) {}
                        }
                        statusText.setText("🔊 Lettura risposta…");
                    });
                }

                @Override
                public void onDone(String utteranceId) {
                    ttsSpeaking = false;
                    runOnUiThread(() -> {
                        statusText.setText(handsFreeEnabled
                                ? "🟢 In attesa — dì Jasper per iniziare"
                                : "⚪ Auto OFF — usa il microfono manuale");
                        scheduleHandsFreeRestart(350L);
                    });
                }

                @Override
                public void onError(String utteranceId) {
                    ttsSpeaking = false;
                    runOnUiThread(() -> {
                        statusText.setText("Errore nella sintesi vocale");
                        scheduleHandsFreeRestart(500L);
                    });
                }
            });
        });
    }

    private void restoreSavedVoice() {
        if (tts == null) return;

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String mode = prefs.getString(PREF_TTS_MODE, MODE_NORMAL);

        if (MODE_SAGE.equals(mode)) {
            String sageVoiceName = prefs.getString(PREF_SAGE_BASE_VOICE, null);
            if (sageVoiceName != null) {
                Voice sageVoice = findVoiceByName(sageVoiceName);
                if (sageVoice != null) {
                    tts.setVoice(sageVoice);
                }
            }
            tts.setPitch(0.61f);
            applyCurrentSpeechRate();
            return;
        }

        tts.setPitch(1.0f);
        applyCurrentSpeechRate();

        String savedName = prefs.getString(PREF_TTS_VOICE, null);
        if (savedName == null) return;

        Voice saved = findVoiceByName(savedName);
        if (saved != null) {
            tts.setVoice(saved);
        }
    }

    private Voice findVoiceByName(String name) {
        if (tts == null || name == null) return null;

        Set<Voice> voices = tts.getVoices();
        if (voices == null) return null;

        for (Voice voice : voices) {
            if (name.equals(voice.getName())) {
                return voice;
            }
        }
        return null;
    }

    private boolean explicitlyMasculineName(Voice voice) {
        if (voice == null || voice.getName() == null) return false;

        String n = voice.getName().toLowerCase(Locale.ROOT);

        return n.contains("male")
                || n.contains("masch")
                || n.contains("uomo")
                || n.contains("man_")
                || n.endsWith("_man")
                || n.contains("-man-");
    }

    private void applySageVoice(boolean preview) {
        if (tts == null) return;

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        String baseVoiceName = prefs.getString(PREF_TTS_VOICE, null);
        if (baseVoiceName == null) {
            Toast.makeText(
                    this,
                    "Prima scegli una voce dall'elenco e ascolta l'anteprima.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        Voice baseVoice = findVoiceByName(baseVoiceName);
        if (baseVoice == null) {
            Toast.makeText(
                    this,
                    "La voce scelta non è più disponibile.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        tts.setVoice(baseVoice);
        tts.setPitch(0.61f);
        applyCurrentSpeechRate();

        prefs.edit()
                .putString(PREF_TTS_MODE, MODE_SAGE)
                .putString(PREF_SAGE_BASE_VOICE, baseVoice.getName())
                .apply();

        if (preview) {
            tts.speak(
                    "Questa è la modalità Saggio. Più calma, lenta e decisamente più profonda.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "sage_preview"
            );
        }
    }

    private void showVoiceChooser() {
        if (!ttsReady || tts == null) {
            Toast.makeText(this,
                    "La sintesi vocale non è ancora pronta",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Set<Voice> set = tts.getVoices();
        if (set == null || set.isEmpty()) {
            Toast.makeText(this,
                    "Nessuna voce disponibile",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        List<Voice> voices = new ArrayList<>();
        String currentLanguage = Locale.getDefault().getLanguage();

        for (Voice voice : set) {
            if (voice.getLocale() != null
                    && currentLanguage.equals(voice.getLocale().getLanguage())) {
                voices.add(voice);
            }
        }

        if (voices.isEmpty()) {
            voices.addAll(set);
        }

        Collections.sort(voices, (a, b) -> {
            boolean am = explicitlyMasculineName(a);
            boolean bm = explicitlyMasculineName(b);

            if (am != bm) {
                return am ? -1 : 1;
            }

            return a.getName().compareToIgnoreCase(b.getName());
        });

        String[] labels = new String[voices.size() + 1];
        labels[0] = "🧔 Saggio maschile – usa la voce scelta sotto";

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedMode = prefs.getString(PREF_TTS_MODE, MODE_NORMAL);
        String savedVoice = prefs.getString(PREF_TTS_VOICE, null);

        int checked = MODE_SAGE.equals(savedMode) ? 0 : -1;

        for (int i = 0; i < voices.size(); i++) {
            Voice voice = voices.get(i);

            String localeLabel = voice.getLocale() != null
                    ? voice.getLocale().getDisplayName()
                    : "";

            String type = voice.isNetworkConnectionRequired()
                    ? "online"
                    : "locale";

            String declared = explicitlyMasculineName(voice)
                    ? " ♂"
                    : "";

            labels[i + 1] = declared
                    + voice.getName()
                    + (localeLabel.isEmpty() ? "" : " — " + localeLabel)
                    + " (" + type + ")";

            if (!MODE_SAGE.equals(savedMode)
                    && savedVoice != null
                    && savedVoice.equals(voice.getName())) {
                checked = i + 1;
            }
        }

        final List<Voice> finalVoices = voices;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Scegli e ascolta la voce")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    if (which == 0) {
                        applySageVoice(true);
                        return;
                    }

                    Voice selected = finalVoices.get(which - 1);

                    tts.setPitch(1.0f);
                    applyCurrentSpeechRate();

                    int result = tts.setVoice(selected);

                    if (result == TextToSpeech.SUCCESS) {
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                                .edit()
                                .putString(PREF_TTS_MODE, MODE_NORMAL)
                                .putString(PREF_TTS_VOICE, selected.getName())
                                .apply();

                        tts.speak(
                                "Questa è la voce selezionata. Ascoltala e scegli solo se ti piace.",
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                "voice_preview"
                        );
                    } else {
                        Toast.makeText(
                                MainActivity.this,
                                "Impossibile usare questa voce",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                })
                .setPositiveButton("OK", null)
                .create();

        dialog.show();
    }

    private void speakAssistantText(String text) {
        if (text == null) return;

        String cleaned = stripEmojis(text).trim();
        if (cleaned.isEmpty() || cleaned.equals(lastSpokenText)) return;

        lastSpokenText = cleaned;

        runOnUiThread(() -> {
            if (!ttsReady || tts == null) {
                statusText.setText("Risposta pronta");
                return;
            }

            tts.speak(
                    cleaned,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "chatgpt_response"
            );
        });
    }

    private void createHeadsetMediaSession() {
        try {
            headsetMediaSession = new MediaSession(this, "DanHeadsetControls");
            headsetMediaSession.setFlags(
                    MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                            | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            );

            PlaybackState state = new PlaybackState.Builder()
                    .setActions(
                            PlaybackState.ACTION_PLAY
                                    | PlaybackState.ACTION_PAUSE
                                    | PlaybackState.ACTION_PLAY_PAUSE
                    )
                    .setState(PlaybackState.STATE_PAUSED, 0L, 1.0f)
                    .build();

            headsetMediaSession.setPlaybackState(state);

            headsetMediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public boolean onMediaButtonEvent(Intent mediaButtonIntent) {
                    if (mediaButtonIntent == null) return false;

                    KeyEvent event = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                    if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) {
                        return true;
                    }

                    int code = event.getKeyCode();
                    if (code == KeyEvent.KEYCODE_HEADSETHOOK
                            || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                            || code == KeyEvent.KEYCODE_MEDIA_PLAY
                            || code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                        runOnUiThread(() -> toggleHeadsetRecording());
                        return true;
                    }

                    return super.onMediaButtonEvent(mediaButtonIntent);
                }

                @Override
                public void onPlay() {
                    runOnUiThread(() -> toggleHeadsetRecording());
                }

                @Override
                public void onPause() {
                    runOnUiThread(() -> toggleHeadsetRecording());
                }
            });

            // Verrà attivata solo quando Dan è in primo piano.
            headsetMediaSession.setActive(false);
        } catch (Exception e) {
            headsetMediaSession = null;
        }
    }

    private void toggleHeadsetRecording() {
        if (speechRecognizer == null) {
            Toast.makeText(this,
                    "Riconoscimento vocale non disponibile",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (headsetRecording) {
            playRecordingEndSound();
            headsetRecording = false;
            recordingLocked = false;
            pressToTalkRequested = false;
            micButton.setText("🎙");
            applyMicStyle(false);

            if (speechRecognizer != null && recognizerSessionActive && manualCapture) {
                statusText.setText("🎧 Trascrivo e invio…");
                try { speechRecognizer.stopListening(); } catch (Exception ignored) {}
            } else {
                manualCapture = false;
                statusText.setText(handsFreeEnabled
                        ? "🟢 In attesa — dì Jasper per iniziare"
                        : "⚪ Auto OFF — usa il microfono manuale");
                scheduleHandsFreeRestart(400L);
            }
            return;
        }

        headsetRecording = true;
        handsFreeDictating = false;
        handsFreeBuffer.setLength(0);

        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
        }
        ttsSpeaking = false;

        if (speechRecognizer != null && recognizerSessionActive) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) {}
        }

        recognizerSessionActive = false;
        listening = false;
        statusText.setText("🎧 Cuffia: preparo il microfono…");

        statusText.postDelayed(() -> {
            if (!headsetRecording) return;

            manualCapture = true;
            recordingLocked = true;
            pressToTalkRequested = true;
            micButton.setText("■");
            applyMicStyle(true);
            statusText.setText("🎧 Registrazione cuffia — premi di nuovo per inviare");
            startPressToTalk();
        }, 350L);
    }

    private void createSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this,
                    "Riconoscimento vocale Android non disponibile",
                    Toast.LENGTH_LONG).show();
            micButton.setEnabled(false);
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault().toLanguageTag());
        speechIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        // v1.18 Long Listening: più tolleranza a pause naturali e respiro.
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                30000L);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                3500L);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                5000L);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                listening = true;
                recognizerSessionActive = true;
                lastPartialText = "";
                applyMicStyle(true);
                if (manualCapture) {
                    playRecordingStartSound();
                    statusText.setText("🎙 Ti ascolto… rilascia per inviare");
                } else if (handsFreeDictating) {
                    statusText.setText("🔴 Dettatura attiva — dì Jasper per inviare");
                } else {
                    statusText.setText("🟢 In attesa — dì Jasper per iniziare");
                }
            }

            @Override
            public void onBeginningOfSpeech() {
                if (manualCapture) {
                    statusText.setText("🎙 Parla…");
                } else if (handsFreeDictating) {
                    statusText.setText("🔴 Ti ascolto… dì Jasper quando hai finito");
                } else {
                    statusText.setText("👂 Ascolto la parola Jasper…");
                }
            }

            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                statusText.setText(manualCapture ? "Trascrivo…" : "Elaboro…");
            }

            @Override
            public void onError(int error) {
                listening = false;
                recognizerSessionActive = false;
                applyMicStyle(false);

                if (manualCapture) {
                    manualCapture = false;
                    headsetRecording = false;
                    recordingLocked = false;
                    pressToTalkRequested = false;
                    micButton.setText("🎙");
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        statusText.setText("Permesso microfono mancante.");
                    } else if (error == SpeechRecognizer.ERROR_NO_MATCH
                            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                            || error == SpeechRecognizer.ERROR_CLIENT) {
                        statusText.setText("Non ho capito. Riprova.");
                    } else {
                        statusText.setText("Errore riconoscimento vocale: " + error);
                    }
                    scheduleHandsFreeRestart(500L);
                    return;
                }

                if (!handsFreeEnabled) return;

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    statusText.setText("Permesso microfono mancante.");
                    return;
                }

                long delay = (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        || error == SpeechRecognizer.ERROR_CLIENT) ? 800L : 300L;
                scheduleHandsFreeRestart(delay);
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                recognizerSessionActive = false;
                applyMicStyle(false);

                String text = chooseBestRecognition(results);

                if (manualCapture) {
                    manualCapture = false;
                    headsetRecording = false;
                    recordingLocked = false;
                    pressToTalkRequested = false;
                    micButton.setText("🎙");

                    if (!text.isEmpty()) {
                        handleRecognizedCommandOrMessage(text);
                    } else {
                        statusText.setText("Nessun testo riconosciuto");
                    }
                    scheduleHandsFreeRestart(700L);
                    return;
                }

                if (!text.isEmpty()) {
                    processHandsFreeText(text);
                } else {
                    scheduleHandsFreeRestart(250L);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                if (partialResults == null) return;

                ArrayList<String> partials =
                        partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partials == null || partials.isEmpty()) return;

                String candidate = partials.get(0) == null ? "" : partials.get(0).trim();
                if (!candidate.isEmpty() && candidate.length() >= lastPartialText.length()) {
                    lastPartialText = candidate;
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void startHandsFreeMode() {
        if (!handsFreeEnabled) {
            statusText.setText("⚪ Auto OFF — usa il microfono manuale");
            return;
        }

        if (speechRecognizer == null) return;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_AUDIO);
            return;
        }

        statusText.setText(handsFreeDictating
                ? "🔴 Dettatura attiva — dì Jasper per inviare"
                : "🟢 In attesa — dì Jasper per iniziare");
        scheduleHandsFreeRestart(100L);
    }

    private void scheduleHandsFreeRestart(long delayMs) {
        if (!handsFreeEnabled || manualCapture || headsetRecording
                || ttsSpeaking || speechRecognizer == null) return;

        statusText.postDelayed(() -> {
            if (!handsFreeEnabled || manualCapture || headsetRecording || ttsSpeaking
                    || speechRecognizer == null || recognizerSessionActive) {
                return;
            }
            startListening();
        }, delayMs);
    }

    private void processHandsFreeText(String text) {
        String clean = text.trim();
        if (clean.isEmpty()) {
            scheduleHandsFreeRestart(200L);
            return;
        }

        int commandStart = findWakeWord(clean);

        if (!handsFreeDictating) {
            if (commandStart < 0) {
                statusText.setText("🟢 In attesa — dì Jasper per iniziare");
                scheduleHandsFreeRestart(200L);
                return;
            }

            handsFreeDictating = true;
            handsFreeBuffer.setLength(0);

            String afterDan = clean.substring(commandStart + WAKE_WORD.length())
                    .replaceFirst("^[\\s,.:;!?-]+", "")
                    .trim();
            if (!afterDan.isEmpty()) {
                handsFreeBuffer.append(afterDan);
            }

            statusText.setText("🔴 Dettatura attiva — dì Jasper per inviare");
            scheduleHandsFreeRestart(180L);
            return;
        }

        if (commandStart >= 0) {
            String beforeDan = clean.substring(0, commandStart)
                    .replaceFirst("[\\s,.:;!?-]+$", "")
                    .trim();
            appendHandsFreeChunk(beforeDan);
            finishHandsFreeMessage();
            return;
        }

        appendHandsFreeChunk(clean);
        statusText.setText("🔴 Continuo ad ascoltare… dì Jasper per inviare");
        scheduleHandsFreeRestart(180L);
    }

    private int findWakeWord(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int from = 0;

        while (from < lower.length()) {
            int i = lower.indexOf(WAKE_WORD, from);
            if (i < 0) return -1;

            int end = i + WAKE_WORD.length();
            boolean leftOk = i == 0 || !Character.isLetterOrDigit(lower.charAt(i - 1));
            boolean rightOk = end >= lower.length()
                    || !Character.isLetterOrDigit(lower.charAt(end));

            if (leftOk && rightOk) return i;
            from = i + 1;
        }

        return -1;
    }

    private void appendHandsFreeChunk(String chunk) {
        if (chunk == null) return;
        String clean = chunk.trim();
        if (clean.isEmpty()) return;

        if (handsFreeBuffer.length() > 0) {
            handsFreeBuffer.append(' ');
        }
        handsFreeBuffer.append(clean);
    }

    private void finishHandsFreeMessage() {
        String message = handsFreeBuffer.toString().trim();
        handsFreeBuffer.setLength(0);
        handsFreeDictating = false;

        if (message.isEmpty()) {
            statusText.setText("Messaggio vuoto — dì Jasper per ricominciare");
            scheduleHandsFreeRestart(350L);
            return;
        }

        handleRecognizedCommandOrMessage(message);
        scheduleHandsFreeRestart(800L);
    }

    private void startPressToTalk() {
        if (speechRecognizer == null) return;

        if (tts != null) {
            tts.stop();
        }
        ttsSpeaking = false;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_AUDIO);
            return;
        }

        statusText.postDelayed(() -> {
            if (manualCapture) startListening();
        }, 180L);
    }

    private void startListening() {
        if (speechRecognizer == null || recognizerSessionActive) return;

        try {
            recognizerSessionActive = true;
            speechRecognizer.startListening(speechIntent);
        } catch (Exception e) {
            recognizerSessionActive = false;
            listening = false;
            if (manualCapture) {
                statusText.setText("Impossibile avviare il microfono");
            } else {
                scheduleHandsFreeRestart(800L);
            }
        }
    }

    private void finishLockedRecording() {
        if (!recordingLocked) return;

        playRecordingEndSound();
        recordingLocked = false;
        pressToTalkRequested = false;
        micButton.setText("🎙");
        applyMicStyle(false);

        if (speechRecognizer != null && recognizerSessionActive) {
            statusText.setText("Trascrivo…");
            speechRecognizer.stopListening();
        }
    }

    private void stopPressToTalk() {
        if (manualCapture) {
            playRecordingEndSound();
        }
        if (speechRecognizer != null && recognizerSessionActive && manualCapture) {
            statusText.setText("Trascrivo…");
            try { speechRecognizer.stopListening(); } catch (Exception ignored) {}
        }
        micButton.setText("🎙");
        applyMicStyle(false);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_AUDIO) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pressToTalkRequested || manualCapture) {
                    startPressToTalk();
                } else {
                    startHandsFreeMode();
                }
            } else {
                statusText.setText("Microfono non autorizzato");
            }
        }
    }

    private void showDownloadNameDialog(
            String url,
            String userAgent,
            String mimetype,
            String suggestedName
    ) {
        final String safeSuggested = sanitizeFilename(suggestedName);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(safeSuggested);
        input.setSelectAllOnFocus(true);
        input.setHint("Nome del file");

        int pad = dp(20);
        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(pad, dp(4), pad, 0);
        holder.addView(
                input,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                )
        );

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Salva file come")
                .setMessage("Scegli il nome con cui salvare il file nella cartella Download.")
                .setView(holder)
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Salva", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String typed = sanitizeFilename(input.getText().toString());

                if (typed.isEmpty() || "download".equalsIgnoreCase(typed)) {
                    input.setError("Inserisci un nome valido");
                    return;
                }

                typed = keepOriginalExtensionIfMissing(
                        typed,
                        safeSuggested
                );

                String finalName = uniqueDownloadFilename(typed);
                dialog.dismiss();

                startNamedDownload(
                        url,
                        userAgent,
                        mimetype,
                        finalName
                );
            });
        });

        dialog.show();
        input.requestFocus();
        if (input.getText() != null) {
            input.setSelection(0, input.getText().length());
        }
    }

    private void startNamedDownload(
            String url,
            String userAgent,
            String mimetype,
            String filename
    ) {
        try {
            DownloadManager.Request request =
                    new DownloadManager.Request(Uri.parse(url));

            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }

            if (userAgent != null && !userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", userAgent);
            }

            request.setTitle(filename);
            request.setDescription("Download da Dan");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            if (mimetype != null && !mimetype.isEmpty()) {
                request.setMimeType(mimetype);
            }

            request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    filename
            );

            DownloadManager manager =
                    (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);

            if (manager == null) {
                Toast.makeText(
                        MainActivity.this,
                        "Gestore download Android non disponibile",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            lastDownloadId = manager.enqueue(request);

            Toast.makeText(
                    MainActivity.this,
                    "Download avviato: " + filename,
                    Toast.LENGTH_LONG
            ).show();

        } catch (Exception e) {
            Toast.makeText(
                    MainActivity.this,
                    "Impossibile avviare il download",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private String keepOriginalExtensionIfMissing(
            String typed,
            String suggested
    ) {
        if (typed == null) return "download";

        String clean = sanitizeFilename(typed);
        if (hasExtension(clean)) return clean;

        String ext = extensionOf(suggested);
        if (!ext.isEmpty()) {
            return clean + ext;
        }

        return clean;
    }

    private boolean hasExtension(String name) {
        if (name == null) return false;

        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1;
    }

    private String extensionOf(String name) {
        if (name == null) return "";

        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot >= name.length() - 1) return "";

        String ext = name.substring(dot);
        if (!ext.matches("\\.[A-Za-z0-9]{1,8}")) return "";

        return ext;
    }

    private String uniqueDownloadFilename(String requested) {
        String clean = sanitizeFilename(requested);

        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
        );

        File candidate = new File(downloads, clean);
        if (!candidate.exists()) return clean;

        String ext = extensionOf(clean);
        String base = ext.isEmpty()
                ? clean
                : clean.substring(0, clean.length() - ext.length());

        for (int i = 1; i < 1000; i++) {
            String candidateName = base + " (" + i + ")" + ext;
            if (!new File(downloads, candidateName).exists()) {
                return candidateName;
            }
        }

        return base + "-" + System.currentTimeMillis() + ext;
    }

    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(11, 20, 26));
        webView.addJavascriptInterface(new NativeBridge(), "AndroidRadio");

        webContainer.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url == null || url.trim().isEmpty()) {
                Toast.makeText(
                        MainActivity.this,
                        "Download non disponibile",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }

            if (url.startsWith("blob:")) {
                Toast.makeText(
                        MainActivity.this,
                        "Questo file usa un collegamento temporaneo blob. Se non parte, dimmelo e lo adattiamo.",
                        Toast.LENGTH_LONG
                ).show();
                return;
            }

            String guessed = URLUtil.guessFileName(
                    url,
                    contentDisposition,
                    mimetype
            );

            String suggested = null;

            long age = System.currentTimeMillis() - pendingDownloadNameAt;
            if (pendingDownloadName != null
                    && !pendingDownloadName.trim().isEmpty()
                    && age >= 0
                    && age <= 10000L) {
                suggested = sanitizeFilename(pendingDownloadName);
            }

            pendingDownloadName = null;
            pendingDownloadNameAt = 0L;

            if (suggested == null || suggested.isEmpty()) {
                suggested = sanitizeFilename(guessed);
            }

            if (suggested == null || suggested.isEmpty()
                    || suggested.toLowerCase(Locale.ROOT).matches(
                    "^content(?:[-_]?\\d+)?\\.[a-z0-9]{2,5}$")) {
                suggested = fallbackDownloadName(guessed);
            }

            showDownloadNameDialog(
                    url,
                    userAgent,
                    mimetype,
                    suggested
            );
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                progressBar.setProgress(progress);
                progressBar.setVisibility(
                        progress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(
                    WebView webView,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }

                MainActivity.this.filePathCallback = filePathCallback;

                try {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);

                    String[] acceptTypes = fileChooserParams != null
                            ? fileChooserParams.getAcceptTypes()
                            : null;

                    String mime = "*/*";
                    ArrayList<String> validTypes = new ArrayList<>();

                    if (acceptTypes != null) {
                        for (String type : acceptTypes) {
                            if (type != null && !type.trim().isEmpty()) {
                                validTypes.add(type.trim());
                            }
                        }
                    }

                    if (validTypes.size() == 1) {
                        mime = validTypes.get(0);
                    } else if (validTypes.size() > 1) {
                        intent.putExtra(
                                Intent.EXTRA_MIME_TYPES,
                                validTypes.toArray(new String[0])
                        );
                    }

                    intent.setType(mime);
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                    return true;

                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;

                    Toast.makeText(
                            MainActivity.this,
                            "Impossibile aprire il selettore file",
                            Toast.LENGTH_LONG
                    ).show();

                    return false;
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                return false;
            }

            @Override
            public void onPageStarted(
                    WebView view,
                    String url,
                    android.graphics.Bitmap favicon
            ) {
                lastUrl = url;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                lastUrl = url;
                injectPageBehaviors();
            }

            @Override
            public boolean onRenderProcessGone(
                    WebView view,
                    RenderProcessGoneDetail detail
            ) {
                String restoreUrl = lastUrl;

                webContainer.removeView(webView);
                webView.destroy();
                createWebView();
                webView.loadUrl(restoreUrl);

                Toast.makeText(
                        MainActivity.this,
                        "Pagina ripristinata",
                        Toast.LENGTH_SHORT
                ).show();

                return true;
            }
        });
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) return;

            Uri[] results = null;

            if (resultCode == RESULT_OK && data != null) {
                ClipData clipData = data.getClipData();

                if (clipData != null && clipData.getItemCount() > 0) {
                    results = new Uri[clipData.getItemCount()];

                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        results[i] = clipData.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }

            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    private class NativeBridge {
        @JavascriptInterface
        public void assistantReady(String text) {
            speakAssistantText(text);
        }

        @JavascriptInterface
        public void nativeReadStarted() {
            runOnUiThread(() -> statusText.setText("🔊 Voce ChatGPT…"));
        }

        @JavascriptInterface
        public void setPendingDownloadName(String name) {
            if (name == null) return;
            String cleaned = sanitizeFilename(name);
            if (!cleaned.isEmpty()) {
                pendingDownloadName = cleaned;
                pendingDownloadNameAt = System.currentTimeMillis();
            }
        }
    }

    private void handleRecognizedCommandOrMessage(String text) {
        if (text == null) return;

        String clean = text.trim();
        if (clean.isEmpty()) return;

        String lower = clean.toLowerCase(Locale.ROOT);

        if (lower.startsWith("apri ")) {
            String target = clean.substring(5).trim();

            if (target.toLowerCase(Locale.ROOT).startsWith("conversazione ")) {
                target = target.substring("conversazione ".length()).trim();
            } else if (target.toLowerCase(Locale.ROOT).startsWith("chat ")) {
                target = target.substring("chat ".length()).trim();
            }

            if (!target.isEmpty()) {
                openChatByTitle(target);
                return;
            }
        }

        statusText.setText("Invio a ChatGPT…");
        injectTextAndSend(clean);
    }

    private void openChatByTitle(String requestedTitle) {
        if (webView == null || requestedTitle == null) return;

        String cleanTitle = requestedTitle.trim();
        if (cleanTitle.isEmpty()) return;

        String escaped = cleanTitle
                .replace("\\", "\\\\")
                .replace("'", "\\'");

        statusText.setText("Cerco chat: " + cleanTitle + "…");

        String script =
                "(function(){" +
                " const wanted='" + escaped + "'.toLowerCase().trim();" +
                " const norm=s=>(s||'').replace(/\\s+/g,' ').trim().toLowerCase();" +
                " const links=Array.from(document.querySelectorAll('a[href]'))" +
                "   .filter(a=>{" +
                "     const h=a.getAttribute('href')||'';" +
                "     return h.includes('/c/')||h.includes('/g/');" +
                "   });" +
                " let exact=links.find(a=>norm(a.innerText||a.textContent)===wanted);" +
                " if(!exact) exact=links.find(a=>norm(a.innerText||a.textContent).includes(wanted));" +
                " if(!exact) return 'NOT_FOUND';" +
                " const href=exact.href||exact.getAttribute('href');" +
                " if(href){ location.href=href; return 'OPENED'; }" +
                " try{ exact.click(); return 'OPENED'; }catch(e){}" +
                " return 'FAILED';" +
                "})();";

        webView.evaluateJavascript(script, value -> runOnUiThread(() -> {
            if (value == null) {
                statusText.setText("Chat non trovata: " + cleanTitle);
                return;
            }

            if (value.contains("OPENED")) {
                statusText.setText("Apro chat: " + cleanTitle);
            } else if (value.contains("NOT_FOUND")) {
                statusText.setText("Chat non trovata: " + cleanTitle);
                Toast.makeText(
                        MainActivity.this,
                        "Non trovo una chat visibile con titolo: " + cleanTitle,
                        Toast.LENGTH_LONG
                ).show();
            } else {
                statusText.setText("Non riesco ad aprire: " + cleanTitle);
            }
        }));
    }

    private void injectTextAndSend(String text) {
        if (webView == null) return;

        String escaped = text
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "");

        String script =
                "(function(){" +
                " const text='" + escaped + "';" +
                " function findEditor(){" +
                "  return document.querySelector('#prompt-textarea') ||" +
                "         document.querySelector(\"textarea[data-testid='prompt-textarea']\") ||" +
                "         document.querySelector(\"div[contenteditable='true'][data-testid='prompt-textarea']\") ||" +
                "         document.querySelector(\"div[contenteditable='true']\");" +
                " }" +
                " function findSend(){" +
                "  return document.querySelector(\"button[data-testid='send-button']\") ||" +
                "         Array.from(document.querySelectorAll('button')).find(b=>{" +
                "          const a=((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('data-testid')||'')).toLowerCase();" +
                "          return a.includes('send')||a.includes('invia');" +
                "         });" +
                " }" +
                " const editor=findEditor();" +
                " if(!editor)return 'NO_EDITOR';" +
                " editor.focus();" +
                " if(editor.tagName==='TEXTAREA'||editor.tagName==='INPUT'){" +
                "  const setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(editor),'value')?.set;" +
                "  if(setter)setter.call(editor,text);else editor.value=text;" +
                " }else{" +
                "  editor.innerHTML='';" +
                "  editor.textContent=text;" +
                " }" +
                " editor.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));" +
                " editor.dispatchEvent(new Event('change',{bubbles:true}));" +
                " setTimeout(()=>{" +
                "  const send=findSend();" +
                "  if(send&&!send.disabled)send.click();" +
                " },350);" +
                " return 'OK';" +
                "})();";

        webView.evaluateJavascript(script, value -> runOnUiThread(() -> {
            if (value != null && value.contains("NO_EDITOR")) {
                statusText.setText("Casella ChatGPT non trovata");
            } else {
                statusText.setText("Attendo la risposta…");
            }
        }));
    }

    private void injectPageBehaviors() {
        injectEnterToSend();
        injectDownloadNameCapture();
        injectAssistantObserver();
    }

    private void injectEnterToSend() {
        if (webView == null) return;

        String script =
                "(function(){" +
                " if(window.__radioEnterInstalled)return;" +
                " window.__radioEnterInstalled=true;" +

                " function isEditor(el){" +
                "  if(!el)return false;" +
                "  if(el.id==='prompt-textarea')return true;" +
                "  if(el.getAttribute&&el.getAttribute('data-testid')==='prompt-textarea')return true;" +
                "  return !!(el.closest&&el.closest('#prompt-textarea,[data-testid=\"prompt-textarea\"]'));" +
                " }" +

                " function findSend(){" +
                "  return document.querySelector(\"button[data-testid='send-button']\") ||" +
                "   Array.from(document.querySelectorAll('button')).find(b=>{" +
                "    const a=((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('data-testid')||'')).toLowerCase();" +
                "    return a.includes('send')||a.includes('invia');" +
                "   });" +
                " }" +

                " document.addEventListener('keydown',function(e){" +
                "  if(e.key!=='Enter'||e.shiftKey||e.isComposing)return;" +
                "  if(!isEditor(e.target))return;" +
                "  e.preventDefault();" +
                "  e.stopPropagation();" +
                "  setTimeout(()=>{" +
                "   const send=findSend();" +
                "   if(send&&!send.disabled)send.click();" +
                "  },0);" +
                " },true);" +
                "})();";

        webView.evaluateJavascript(script, null);
    }

    private void injectDownloadNameCapture() {
        if (webView == null) return;

        String script =
                "(function(){" +
                " if(window.__radioDownloadNameInstalled)return;" +
                " window.__radioDownloadNameInstalled=true;" +

                " function clean(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                " function fileFromText(s){" +
                "  s=clean(s);" +
                "  const m=s.match(/([^\\n\\r\\t\\/\\\\]+\\.(?:zip|pdf|docx?|xlsx?|pptx?|apk|txt|csv|json|jpg|jpeg|png|webp|mp3|mp4))(?=\\s|$)/i);" +
                "  return m?clean(m[1]):'';" +
                " }" +
                " function candidate(el){" +
                "  if(!el)return '';" +
                "  const a=el.closest?el.closest('a'):null;" +
                "  const b=el.closest?el.closest('button'):null;" +
                "  let n='';" +
                "  if(a){" +
                "   n=clean(a.getAttribute('download'));" +
                "   if(!n)n=fileFromText(a.innerText||a.textContent||'');" +
                "   if(!n){" +
                "    try{" +
                "     const u=new URL(a.href,location.href);" +
                "     const last=decodeURIComponent((u.pathname.split('/').pop()||''));" +
                "     if(/\\.[a-z0-9]{2,5}$/i.test(last) && !/^content(?:[-_]?\\d+)?\\./i.test(last))n=last;" +
                "    }catch(e){}" +
                "   }" +
                "  }" +
                "  if(!n&&b)n=fileFromText(b.innerText||b.textContent||b.getAttribute('aria-label')||'');" +
                "  if(!n)n=fileFromText(el.innerText||el.textContent||'');" +
                "  return clean(n);" +
                " }" +
                " document.addEventListener('pointerdown',function(e){" +
                "  const n=candidate(e.target);" +
                "  if(n&&window.AndroidRadio&&window.AndroidRadio.setPendingDownloadName){" +
                "   window.AndroidRadio.setPendingDownloadName(n);" +
                "  }" +
                " },true);" +
                " document.addEventListener('click',function(e){" +
                "  const n=candidate(e.target);" +
                "  if(n&&window.AndroidRadio&&window.AndroidRadio.setPendingDownloadName){" +
                "   window.AndroidRadio.setPendingDownloadName(n);" +
                "  }" +
                " },true);" +
                "})();";

        webView.evaluateJavascript(script, null);
    }

    private void injectAssistantObserver() {
        if (webView == null) return;

        String script =
                "(function(){" +
                " if(window.__danHybridReadInstalled)return;" +
                " window.__danHybridReadInstalled=true;" +
                " let timer=null;" +

                " function generating(){" +
                "  return Array.from(document.querySelectorAll('button')).some(b=>{" +
                "   const a=((b.getAttribute('aria-label')||'')+' '+(b.innerText||'')).toLowerCase();" +
                "   return a.includes('stop generating')||a.includes('interrompi generazione')||a.includes('stop streaming');" +
                "  });" +
                " }" +

                " function latestAssistant(){" +
                "  const msgs=Array.from(document.querySelectorAll(\"[data-message-author-role='assistant']\"));" +
                "  return msgs.length?msgs[msgs.length-1]:null;" +
                " }" +

                " function latestAssistantText(){" +
                "  const m=latestAssistant();" +
                "  return m?(m.innerText||'').trim():'';" +
                " }" +

                " function isReadButton(b){" +
                "  if(!b)return false;" +
                "  const s=((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'')+' '+(b.getAttribute('data-testid')||'')+' '+(b.innerText||'')).toLowerCase();" +
                "  return s.includes('read aloud')||s.includes('leggi ad alta voce')||s.includes('lettura ad alta voce')||s.includes('read-aloud');" +
                " }" +

                " function findReadButton(){" +
                "  const msg=latestAssistant();" +
                "  if(msg){" +
                "   let scope=msg;" +
                "   for(let i=0;i<5&&scope;i++,scope=scope.parentElement){" +
                "    const local=Array.from(scope.querySelectorAll('button')).find(isReadButton);" +
                "    if(local)return local;" +
                "   }" +
                "  }" +
                "  const all=Array.from(document.querySelectorAll('button')).filter(isReadButton);" +
                "  return all.length?all[all.length-1]:null;" +
                " }" +

                " let lastHandled=latestAssistantText();" +

                " function check(){" +
                "  if(generating()){schedule();return;}" +
                "  const text=latestAssistantText();" +
                "  if(!text||text===lastHandled)return;" +
                "  lastHandled=text;" +
                "  let clicked=false;" +
                "  const btn=findReadButton();" +
                "  if(btn&&!btn.disabled){" +
                "   try{" +
                "    btn.click();" +
                "    clicked=true;" +
                "    if(window.AndroidRadio&&window.AndroidRadio.nativeReadStarted)window.AndroidRadio.nativeReadStarted();" +
                "   }catch(e){}" +
                "  }" +
                "  if(!clicked){" +
                "   try{" +
                "    if(window.AndroidRadio&&window.AndroidRadio.assistantReady)window.AndroidRadio.assistantReady(text);" +
                "   }catch(e){}" +
                "  }" +
                " }" +

                " function schedule(){clearTimeout(timer);timer=setTimeout(check,1600);}" +
                " new MutationObserver(schedule).observe(document.documentElement,{childList:true,subtree:true,characterData:true});" +
                "})();";

        webView.evaluateJavascript(script, null);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private String fallbackDownloadName(String guessed) {
        String ext = ".bin";

        if (guessed != null) {
            int dot = guessed.lastIndexOf('.');
            if (dot >= 0 && dot < guessed.length() - 1) {
                ext = guessed.substring(dot);
            }
        }

        String stamp = new SimpleDateFormat(
                "yyyyMMdd-HHmmss",
                Locale.getDefault()
        ).format(new Date());

        return "ChatGPT-download-" + stamp + ext;
    }

    private String sanitizeFilename(String name) {
        if (name == null) return "download";
        String cleaned = name
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isEmpty() ? "download" : cleaned;
    }

    private void registerDownloadReceiver() {
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;

                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (id == -1L || id != lastDownloadId) return;

                DownloadManager manager =
                        (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) return;

                DownloadManager.Query query = new DownloadManager.Query();
                query.setFilterById(id);

                try (Cursor cursor = manager.query(query)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int titleIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);

                        int status = statusIndex >= 0 ? cursor.getInt(statusIndex) : -1;
                        String title = titleIndex >= 0 ? cursor.getString(titleIndex) : "file";

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            Toast.makeText(
                                    MainActivity.this,
                                    "Download completato: " + title,
                                    Toast.LENGTH_LONG
                            ).show();
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            Toast.makeText(
                                    MainActivity.this,
                                    "Download fallito: " + title,
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, filter);
        }
    }

    private String stripEmojis(String input) {
        if (input == null || input.isEmpty()) return "";

        StringBuilder out = new StringBuilder();

        for (int i = 0; i < input.length();) {
            int cp = input.codePointAt(i);
            i += Character.charCount(cp);

            boolean emoji =
                    (cp >= 0x1F300 && cp <= 0x1FAFF) || // emoji, simboli, faccine
                    (cp >= 0x1F1E6 && cp <= 0x1F1FF) || // bandiere
                    (cp >= 0x1F3FB && cp <= 0x1F3FF) || // tonalità pelle
                    (cp >= 0x2600 && cp <= 0x26FF) ||   // simboli vari
                    (cp >= 0x2700 && cp <= 0x27BF) ||   // dingbats
                    cp == 0xFE0F ||                     // variation selector
                    cp == 0x200D ||                     // zero width joiner
                    cp == 0x20E3;                       // keycap

            if (!emoji) {
                out.appendCodePoint(cp);
            }
        }

        return out.toString()
                .replaceAll("\\\\s{2,}", " ")
                .trim();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // v1.18: finché Dan è in primo piano lo schermo non va in sospensione.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (headsetMediaSession != null) {
            try {
                PlaybackState foregroundState = new PlaybackState.Builder()
                        .setActions(
                                PlaybackState.ACTION_PLAY
                                        | PlaybackState.ACTION_PAUSE
                                        | PlaybackState.ACTION_PLAY_PAUSE
                        )
                        .setState(
                                PlaybackState.STATE_PLAYING,
                                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                                1.0f
                        )
                        .build();

                headsetMediaSession.setPlaybackState(foregroundState);
                headsetMediaSession.setActive(true);
            } catch (Exception ignored) {}
        }

        statusText.setText(handsFreeEnabled
                ? "🟢 Jasper ON — dì Jasper per iniziare"
                : "⚪ Audio libero — microfono/cuffie solo quando li usi");

        if (handsFreeEnabled && !manualCapture && !headsetRecording && !ttsSpeaking) {
            scheduleHandsFreeRestart(250L);
        }
    }

    @Override
    protected void onPause() {
        // Fuori da Dan Android torna a gestire normalmente lo spegnimento schermo.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Dan deve lasciare completamente liberi microfono e controlli cuffie
        // quando l'utente passa a Brave, YouTube, WhatsApp, ecc.
        if (speechRecognizer != null && recognizerSessionActive) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) {}
        }

        recognizerSessionActive = false;
        listening = false;
        manualCapture = false;
        headsetRecording = false;
        recordingLocked = false;
        pressToTalkRequested = false;

        if (micButton != null) {
            micButton.setText("🎙");
            applyMicStyle(false);
        }

        if (headsetMediaSession != null) {
            try {
                PlaybackState backgroundState = new PlaybackState.Builder()
                        .setActions(0L)
                        .setState(
                                PlaybackState.STATE_STOPPED,
                                PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                                0.0f
                        )
                        .build();

                headsetMediaSession.setPlaybackState(backgroundState);
                headsetMediaSession.setActive(false);
            } catch (Exception ignored) {}
        }

        // v1.18: la lettura TTS può continuare anche se Dan passa in background.
        // Microfono e controlli cuffie restano invece rilasciati come sopra.
        // ttsSpeaking non viene azzerato qui: sarà onDone/onError a farlo.

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handsFreeEnabled = false;
        headsetRecording = false;

        if (headsetMediaSession != null) {
            try {
                headsetMediaSession.setActive(false);
                headsetMediaSession.release();
            } catch (Exception ignored) {
            }
            headsetMediaSession = null;
        }
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) {}
            speechRecognizer.destroy();
            speechRecognizer = null;
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }

        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }

        if (webView != null) {
            webContainer.removeView(webView);
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }

        if (downloadReceiver != null) {
            try {
                unregisterReceiver(downloadReceiver);
            } catch (Exception ignored) {
            }
            downloadReceiver = null;
        }

        super.onDestroy();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
