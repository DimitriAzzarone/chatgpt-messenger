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
import android.os.Message;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
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
import android.provider.Settings;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
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
import android.webkit.PermissionRequest;
import androidx.webkit.WebSettingsCompat;
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
import android.widget.ImageView;
import android.graphics.Typeface;

import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.File;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String HOME = "https://chatgpt.com/";
    private static final String LUMINEX_HOME = "https://www.google.com/";
    private static final String LUMINEX_CONTROL_URL =
            "https://luminex-ai-control.dimitri-azzarone.workers.dev/device/next";
    private static final long LUMINEX_POLL_MS = 1000L;
    private static final long LUMINEX_SNAPSHOT_MS = 4000L;
    private static final int REQ_AUDIO = 3001;
    private static final int REQ_FILE_CHOOSER = 4001;
    private static final int REQ_MEMORY_EXPORT = 4002;
    private static final int REQ_MEMORY_BACKUP = 4003;
    private static final int REQ_MEMORY_IMPORT = 4004;
    private static final int REQ_HISTORY_IMPORT = 4005;
    private static final int REQ_DRIVE_FOLDER = 4006;
    private static final int REQ_STORY_FOLDER = 4007;
    private static final String PREF_MEMORY_BACKUP_URI = "dan_memory_backup_uri";
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
    private volatile String memoryPageUrl = "";
    private DanMemoryStore memoryStore;
    private DanHistoryStore historyStore;
    private DanDriveSync driveSync;
    private final ScheduledExecutorService memoryExecutor = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> pendingMemoryBackup;
    private String pendingMemoryId = "";
    private String pendingMemoryText = "";
    private long pendingMemoryAt = 0L;
    private WebView luminexWebView;
     private Button luminexButton;
    private Button privateButton;

    private static class ChatPrivateState {
        final String profileName;
        final List<WebView> tabs = new ArrayList<>();
        int tabIndex = 0;
        boolean restored = false;

        ChatPrivateState(String profileName) {
            this.profileName = profileName;
        }
    }

    private boolean privateVisible = false;
    private boolean luminexVisible = false;

    private final List<WebView> luminexTabs = new ArrayList<>();
    private int luminexTabIndex = 0;

    // Ogni chat Dan possiede la propria P e le proprie schede.
    private final Map<String, ChatPrivateState> privateChats =
            new HashMap<>();
    private boolean luminexPolling = false;
    private boolean luminexSnapshotInFlight = false;
    private String lastLuminexContext = "";

    private boolean fullChatLoading = false;
    private boolean fullChatPrintAfterLoad = false;
    private int fullChatStableRounds = 0;
    private int fullChatAttempts = 0;
    private long fullChatLastHeight = -1L;
    private int fullChatLastMessageCount = -1;
    private WebView fullChatWebView = null;

    private final Handler luminexHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable luminexPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!luminexPolling) return;

            new Thread(() -> {
                pollNextLuminexCommand();

                if (luminexPolling) {
                    luminexHandler.postDelayed(
                            this,
                            LUMINEX_POLL_MS
                    );
                }
            }, "Dan-Luminex-Poll").start();
        }
    };
    private final Runnable luminexSnapshotRunnable = new Runnable() {
        @Override
        public void run() {
            WebView activeLuminex = getActiveLuminexWebView();
            if (!luminexVisible || activeLuminex == null) return;

            if (!luminexSnapshotInFlight) {
                luminexSnapshotInFlight = true;
                sendLuminexPageState(activeLuminex, () -> {
                    luminexSnapshotInFlight = false;
                    if (luminexVisible) {
                        luminexHandler.postDelayed(this, LUMINEX_SNAPSHOT_MS);
                    }
                });
            } else {
                luminexHandler.postDelayed(this, LUMINEX_SNAPSHOT_MS);
            }
        }
    };
    private WebView popupWebView;
    private LinearLayout luminexAddressBar;
    private TextView luminexAddress;
    private FrameLayout webContainer;
    private ProgressBar progressBar;
    private TextView statusText;
    private Button micButton;
    private Button voiceButton;
    private Button autoButton;
    private Button speedButton;
    private Button soundButton;
    private Button stopSpeechButton;
    private Button playSpeechButton;

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean listening = false;
    private boolean recognizerSessionActive = false;
    private boolean manualStopPending = false;
    private long voiceSendGeneration = 0L;
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
    private String activeDanChatKey = "";

    // DAN 1.97: il telefono mantiene aggiornata automaticamente la chat
    // aperta anche quando i messaggi vengono inviati dal tablet.
    private static final long CHAT_LIVE_REFRESH_MS = 8000L;
    private final Handler chatLiveRefreshHandler = new Handler(Looper.getMainLooper());
    private boolean chatLiveRefreshRunning = false;

    private final Runnable chatLiveRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!chatLiveRefreshRunning) return;

            WebView current = webView;
            if (current == null || luminexVisible || popupWebView != null || fullChatLoading) {
                chatLiveRefreshHandler.postDelayed(this, CHAT_LIVE_REFRESH_MS);
                return;
            }

            String url = current.getUrl();
            if (TextUtils.isEmpty(url)
                    || !url.startsWith("https://chatgpt.com/")
                    || !url.contains("/c/")) {
                chatLiveRefreshHandler.postDelayed(this, CHAT_LIVE_REFRESH_MS);
                return;
            }

            String script =
                    "(function(){try{"
                    + "var a=document.activeElement;"
                    + "var editing=!!(a&&(a.tagName==='TEXTAREA'||a.tagName==='INPUT'||a.isContentEditable));"
                    + "var txt=editing?String(a.value||a.innerText||a.textContent||'').trim():'';"
                    + "if(editing&&txt.length>0)return 'busy';"
                    + "var stop=document.querySelector('button[data-testid=\\\"stop-button\\\"],button[aria-label*=\\\"Stop\\\"],button[aria-label*=\\\"Interrompi\\\"]');"
                    + "if(stop)return 'busy';"
                    + "return 'ok';"
                    + "}catch(e){return 'busy';}})();";

            current.evaluateJavascript(script, result -> {
                if (chatLiveRefreshRunning
                        && current == webView
                        && result != null
                        && result.contains("ok")) {
                    current.reload();
                }

                if (chatLiveRefreshRunning) {
                    chatLiveRefreshHandler.postDelayed(
                            chatLiveRefreshRunnable,
                            CHAT_LIVE_REFRESH_MS
                    );
                }
            });
        }
    };

    private TextToSpeech tts;
    private TextView liveCaption;
    private boolean liveEnabled;
    private boolean webVoiceRequested, webVoiceActive, pendingWebVoicePermission;
    private long lastWebVoiceTouch = 0L;
    private long webVoiceGeneration;
    private String webVoicePage;
    private String webVoiceToken = "";
    private Button webVoiceClose;
    private boolean suppressAutoRead;
    private boolean liveWaiting;
    private boolean liveSendConfirmed;
    private boolean foreground;
    private long liveGeneration;
    private long liveTurnGeneration;
    private long liveListenGeneration;
    private String livePage;

    private boolean ttsReady = false;
    private String lastSpokenText = "";

    private ValueCallback<Uri[]> filePathCallback;
    private volatile int attachmentGeneration;

    private String pendingDownloadName = null;
    private long pendingDownloadNameAt = 0L;
    private long lastDownloadId = -1L;
    private BroadcastReceiver downloadReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1.146: la variante web apre direttamente ChatGPT/Work.
        memoryStore = new DanMemoryStore(getApplicationContext());
        historyStore = new DanHistoryStore(getApplicationContext());

        SharedPreferences startupPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        driveSync = new DanDriveSync(getApplicationContext(), startupPrefs, memoryStore, historyStore);

        // v1.23: conserva l'ultimo stato AUTO scelto dall'utente.
        // Al primo avvio resta OFF.
        handsFreeEnabled = false;

        recordingSoundsEnabled = startupPrefs.getBoolean(PREF_RECORDING_SOUNDS, true);

        buildInterface();
        registerDownloadReceiver();
        createTextToSpeech();
        createSpeechRecognizer();
        createHeadsetMediaSession();
        createWebView();
        createLuminexWebView();

        webView.loadUrl(attachmentStartupUrl());

        if (handsFreeEnabled) {
            autoButton.setText("LIVE");
            statusText.setText("🟢 Auto ON — preparo il microfono…");
            statusText.postDelayed(this::recoverHandsFreeMicrophone, 500L);
        } else {
            autoButton.setText("LIVE");
            statusText.setText("Tocca il microfono per parlare");
        }
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(3, 13, 26));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        topBar.setBackgroundColor(Color.rgb(7, 20, 40));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("Dan · cerchi luminosi azzurri");

        TextView title = new TextView(this);
        title.setText("Dan");
        title.setContentDescription("Dan " + BuildConfig.VERSION_NAME);
        title.setOnLongClickListener(v -> { showAttachmentDiagnostics(); return true; });
        title.setTextColor(Color.rgb(0, 200, 255));
        title.setTextSize(17);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        try {
            title.setTypeface(Typeface.createFromAsset(getAssets(), "fonts/Orbitron.ttf"));
        } catch (RuntimeException unsupportedFont) {
            title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        }
        title.setShadowLayer(dp(8), 0, 0, Color.argb(120, 0, 200, 255));
        getWindow().setStatusBarColor(Color.rgb(3, 13, 26));
        getWindow().setNavigationBarColor(Color.rgb(3, 13, 26));

        voiceButton = makeButton("🔊");
        voiceButton.setTextSize(18);

        soundButton = makeButton(recordingSoundsEnabled ? "🔔" : "🔕");
        soundButton.setTextSize(18);

        speedButton = makeButton(speedLabel());
        speedButton.setTextSize(15);

        playSpeechButton = makeButton("▶");
        playSpeechButton.setTextSize(18);

        stopSpeechButton = makeButton("⏹");
        stopSpeechButton.setTextSize(18);

        autoButton = makeButton("MIC");
        autoButton.setTextSize(10);
        luminexButton = makeButton("L");
        luminexButton.setTextSize(16);
        luminexButton.setContentDescription(
                "Mostra o nascondi Luminex AI"
        );

        privateButton = makeButton("P");
        privateButton.setTextSize(15);
        privateButton.setContentDescription(
                "Apri sessione privata Luminex"
        );
        privateButton.setVisibility(View.GONE);

        Button reload = makeButton("↻");

        boolean compactPhone = getResources().getConfiguration().smallestScreenWidthDp < 600;
        int logoWidth = compactPhone ? 34 : 38;
        int iconWidth = compactPhone ? 32 : 42;
        int speedWidth = compactPhone ? 38 : 48;
        int micWidth = compactPhone ? 46 : 68;
        int luminexWidth = compactPhone ? 30 : 42;
        int privateWidth = compactPhone ? 30 : 42;
        int reloadWidth = compactPhone ? 30 : 38;

        if (compactPhone) {
            topBar.setPadding(dp(4), dp(4), dp(4), dp(4));
            title.setText("Dan");
            soundButton.setPadding(0, 0, 0, 0);
            speedButton.setPadding(0, 0, 0, 0);
            voiceButton.setPadding(0, 0, 0, 0);
            playSpeechButton.setPadding(0, 0, 0, 0);
            stopSpeechButton.setPadding(0, 0, 0, 0);
            luminexButton.setPadding(0, 0, 0, 0);
            privateButton.setPadding(0, 0, 0, 0);
        }

        topBar.addView(logo, new LinearLayout.LayoutParams(dp(logoWidth), dp(38)));
        topBar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        topBar.addView(soundButton, new LinearLayout.LayoutParams(dp(iconWidth), dp(44)));
        topBar.addView(speedButton, new LinearLayout.LayoutParams(dp(speedWidth), dp(44)));
        topBar.addView(voiceButton, new LinearLayout.LayoutParams(dp(iconWidth), dp(44)));
        topBar.addView(playSpeechButton, new LinearLayout.LayoutParams(dp(iconWidth), dp(44)));
        topBar.addView(stopSpeechButton, new LinearLayout.LayoutParams(dp(iconWidth), dp(44)));
        topBar.addView(luminexButton, new LinearLayout.LayoutParams(dp(luminexWidth), dp(44)));

        topBar.addView(reload, new LinearLayout.LayoutParams(dp(reloadWidth), dp(44)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);

        webContainer = new FrameLayout(this);

        FrameLayout.LayoutParams privateLp =
                new FrameLayout.LayoutParams(dp(privateWidth), dp(44));
        privateLp.gravity = Gravity.TOP | Gravity.END;
        privateLp.topMargin = dp(8);
        privateLp.rightMargin = dp(8);
        webContainer.addView(privateButton, privateLp);
        privateButton.bringToFront();

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(12), dp(6), dp(12), dp(8));
        bottomBar.setBackgroundColor(Color.rgb(7, 20, 40));

        statusText = new TextView(this);
        statusText.setText(handsFreeEnabled
                ? "🟢 Jasper ON — dì Jasper per iniziare"
                : "Tocca il microfono per parlare");
        statusText.setTextColor(Color.LTGRAY);
        statusText.setTextSize(12);
        statusText.setMaxLines(3);
        statusText.setText("Tocca il microfono per parlare");
        liveCaption = statusText;
        statusText.setGravity(Gravity.CENTER_VERTICAL);

        micButton = new Button(this);
        micButton.setText("🎙");
        micButton.setTextSize(25);
        micButton.setPadding(0, 0, 0, 0);
        micButton.setContentDescription("Avvia la conversazione; tocca ancora o X per fermare");
        micButton.setTextColor(Color.WHITE);
        micButton.setGravity(Gravity.CENTER);
        applyMicStyle(false);

        bottomBar.addView(statusText, new LinearLayout.LayoutParams(0, dp(58), 1));

        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        micParams.setMargins(dp(8), 0, 0, 0);
        bottomBar.addView(micButton, micParams);
        webVoiceClose = makeButton("✕");
        webVoiceClose.setTextSize(23);
        webVoiceClose.setContentDescription("Chiudi LIVE e libera il microfono");
        webVoiceClose.setVisibility(View.GONE);
        webVoiceClose.setOnClickListener(v -> {
            if (liveEnabled || pendingLivePermission) stopLiveMode("LIVE chiuso");
            else stopWebVoice("LIVE chiuso");
        });
        bottomBar.addView(webVoiceClose, new LinearLayout.LayoutParams(dp(52), dp(58)));

        LinearLayout controlsBar = new LinearLayout(this);
        controlsBar.setOrientation(LinearLayout.HORIZONTAL);
        controlsBar.setPadding(dp(8), dp(2), dp(8), dp(4));
        controlsBar.setBackgroundColor(Color.rgb(7, 20, 40));

        Button memoryButton = makeButton("Memoria");
        memoryButton.setAllCaps(false);
        memoryButton.setTextSize(12);
        memoryButton.setContentDescription("Carica, ripristina o salva la memoria di Dan");
        memoryButton.setOnClickListener(v -> showMemoryMenu());

        Button engineButton = makeButton("Utilizzo");
        engineButton.setAllCaps(false);
        engineButton.setTextSize(12);
        engineButton.setContentDescription("Apri i limiti e l'utilizzo ufficiale di Work e Codex");
        engineButton.setOnClickListener(v -> {
            setLuminexVisible(false);
            // Keep the authenticated session and navigation inside Dan.
            webView.loadUrl("https://chatgpt.com/codex/cloud/settings/analytics#usage");
        });
        engineButton.setOnLongClickListener(v -> { showEngineMenu(); return true; });

        Button codexButton = makeButton("Codex");
        codexButton.setAllCaps(false);
        codexButton.setTextSize(12);
        codexButton.setContentDescription("Apri Codex dentro Dan");
        codexButton.setOnClickListener(v -> {
            setLuminexVisible(false);
            webView.loadUrl("https://chatgpt.com/codex");
        });
        Button heiwaButton = makeButton("HEIWA");
        heiwaButton.setAllCaps(false);
        heiwaButton.setTextSize(12);
        heiwaButton.setContentDescription("Apri HEIWA con Qwen locale o Groq online");
        heiwaButton.setOnClickListener(v -> startActivity(new Intent(this, HeiwaActivity.class)));
        controlsBar.addView(heiwaButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        controlsBar.addView(codexButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        controlsBar.addView(engineButton, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button copyChatButton = makeButton("Copia chat");
        copyChatButton.setAllCaps(false);
        copyChatButton.setTextSize(12);
        copyChatButton.setContentDescription("Copia i messaggi e le risposte della chat aperta negli appunti");
        copyChatButton.setOnClickListener(v -> copyCurrentChat());


        root.addView(topBar);
        root.addView(controlsBar);
        luminexAddressBar = new LinearLayout(this);
        luminexAddressBar.setOrientation(LinearLayout.HORIZONTAL);
        luminexAddressBar.setVisibility(View.GONE);
        luminexAddress = new TextView(this);
        luminexAddress.setSingleLine(true);
        luminexAddress.setTextSize(14);
        luminexAddress.setTextColor(Color.BLACK);
        luminexAddress.setBackgroundColor(Color.WHITE);
        luminexAddress.setContentDescription("Indirizzo completo della pagina Luminex");
        luminexAddress.setPadding(dp(10), dp(12), dp(10), dp(12));
        luminexAddress.setHorizontallyScrolling(true);
        luminexAddressBar.addView(luminexAddress,
                new LinearLayout.LayoutParams(0, dp(48), 1));
        Button copyAddress = makeButton("Copia URL");
        copyAddress.setOnClickListener(v -> {
            String url = luminexAddress.getText().toString();
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null && !url.isEmpty()) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL", url));
                Toast.makeText(this, "Indirizzo copiato", Toast.LENGTH_SHORT).show();
            }
        });
        luminexAddressBar.addView(copyAddress,
                new LinearLayout.LayoutParams(dp(100), dp(48)));
        root.addView(luminexAddressBar);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        root.addView(webContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));

        setContentView(root);

        // DAN_V127_COMPACT_MOBILE_TOOLBAR
        autoButton.setAllCaps(false);
        autoButton.setTextSize(12);
        autoButton.setMinWidth(0);
        autoButton.setMinimumWidth(0);
        autoButton.setPadding(dp(7), 0, dp(7), 0);

        reload.setMinWidth(0);
        reload.setMinimumWidth(0);
        reload.setPadding(0, 0, 0, 0);
        reload.setText("↻");
        reload.setTextSize(18);
        reload.setVisibility(View.VISIBLE);

        autoButton.setText("LIVE");
        autoButton.setContentDescription("Avvia o ferma la conversazione automatica a turni");
        autoButton.setOnClickListener(v -> toggleLiveMode());
        autoButton.setOnLongClickListener(v -> { recoverMicrophoneAccess(); return true; });
        luminexButton.setOnClickListener(v -> toggleLuminexView());
        privateButton.setOnClickListener(v -> togglePrivateSession());

        reload.setOnClickListener(v -> {
            if (liveEnabled) stopLiveMode("Live fermato · ricaricamento pagina");
            WebView active = luminexVisible
                    ? getActiveLuminexWebView()
                    : webView;

            if (active != null) active.reload();
        });

        voiceButton.setOnClickListener(v -> showVoiceChooser());
        soundButton.setOnClickListener(v -> toggleRecordingSounds());
        speedButton.setOnClickListener(v -> cycleTtsSpeed());
        playSpeechButton.setOnClickListener(v -> replayLastSpeech());
        stopSpeechButton.setOnClickListener(v -> stopAllSpeech());

        micButton.setOnClickListener(v -> toggleLiveMode());
    }

    private boolean pendingManualPermission;

    private void toggleManualDictation() {
        if (liveEnabled) { stopLiveMode("LIVE chiuso"); return; }
        if (webVoiceRequested) { stopWebVoice("LIVE chiuso"); return; }
        if (manualCapture) {
            if (!recordingLocked) return; // Wait for the current transcription.
            recordingLocked = false;
            pressToTalkRequested = false;
            stopPressToTalk();
            return;
        }
        if (pendingManualPermission) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingManualPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        handsFreeEnabled = false;
        SpeechRecognizer previous = speechRecognizer;
        speechRecognizer = null;
        if (previous != null) {
            try { previous.cancel(); } catch (Exception ignored) {}
            try { previous.destroy(); } catch (Exception ignored) {}
        }
        recognizerSessionActive = false;
        listening = false;
        manualStopPending = false;
        lastPartialText = "";
        createSpeechRecognizer();
        if (speechRecognizer == null) return;
        manualCapture = true;
        recordingLocked = true;
        headsetRecording = false;
        pressToTalkRequested = true;
        micButton.setText("■");
        micButton.setContentDescription("Termina la dettatura");
        statusText.setText("Preparo il microfono… attendi Ti ascolto");
        startPressToTalk();
    }


    private String voiceAsset(String name) {
        try (InputStream input = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }

    private boolean trustedVoiceOrigin(String url) {
        if (url == null) return false;
        try {
            Uri u = Uri.parse(url);
            return "https".equals(u.getScheme()) && "chatgpt.com".equals(u.getHost())
                    && (u.getPort() == -1 || u.getPort() == 443);
        } catch (Exception e) { return false; }
    }

    private void toggleWebVoice() {
        beginWebVoice(null);
    }

    private void beginWebVoice(String pageToken) {
        if (webVoiceRequested) { stopWebVoice("LIVE chiuso"); return; }
        if (webView == null || !trustedVoiceOrigin(webView.getUrl()) || luminexVisible) {
            statusText.setText("Apri la chat desiderata prima di LIVE"); return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingWebVoicePermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO); return;
        }
        if (liveEnabled) stopLiveMode("Passaggio alla voce continua");
        handsFreeEnabled = false;
        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); speechRecognizer.destroy(); } catch (Exception ignored) {}
            speechRecognizer = null;
        }
        recognizerSessionActive = false; listening = false; manualCapture = false;
        headsetRecording = false; manualStopPending = false;
        if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
        ttsSpeaking = false; suppressAutoRead = true;
        webVoiceRequested = true; webVoiceActive = false; webVoicePage = webView.getUrl();
        final long generation = ++webVoiceGeneration;
        webVoiceToken = pageToken == null ? Long.toString(generation) : pageToken;
        webVoiceClose.setVisibility(View.VISIBLE);
        autoButton.setActivated(true);
        applyMicStyle(true);
        statusText.setText("LIVE · avvio…");
        if (pageToken == null) webView.evaluateJavascript(voiceAsset("dan-web-voice.js"), result -> {
            if (!webVoiceRequested || generation != webVoiceGeneration || webView == null) return;
            webView.evaluateJavascript("window.__danWebVoice ? window.__danWebVoice.start("
                    + JSONObject.quote(webVoiceToken) + ") : 'unsupported'", null);
        });
        statusText.postDelayed(() -> {
            if (webVoiceRequested && generation == webVoiceGeneration && !webVoiceActive)
                stopWebVoice("Voce continua non avviata · verifica la disponibilità nella tua sessione web");
        }, 30000L);
    }

    private void stopWebVoice(String reason) {
        boolean requested = webVoiceRequested;
        webVoiceRequested = false; webVoiceActive = false; pendingWebVoicePermission = false;
        webVoiceGeneration++;
        if (requested && webView != null) {
            try { if (trustedVoiceOrigin(webView.getUrl()))
                webView.evaluateJavascript("window.__danWebVoice && window.__danWebVoice.stop()", null);
            } catch (Exception ignored) {}
        }
        if (webVoiceClose != null) webVoiceClose.setVisibility(View.GONE);
        if (autoButton != null) autoButton.setActivated(false);
        if (micButton != null) applyMicStyle(false);
        if (statusText != null) statusText.setText(reason);
    }

    private void toggleLiveMode() {
        if (liveEnabled || pendingLivePermission) { stopLiveMode("LIVE chiuso"); return; }
        if (webView == null || !sameVoicePage(webView.getUrl(), webView.getUrl()) || luminexVisible) {
            statusText.setText("Apri una chat prima di avviare LIVE");
            return;
        }
        if (webVoiceRequested) stopWebVoice("Passaggio alla conversazione automatica");
        if (!ttsReady || !recordingSoundsEnabled) {
            statusText.setText("Attiva l'audio e attendi che la voce sia pronta prima di LIVE");
            return;
        }
        if (speechRecognizer == null) {
            createSpeechRecognizer();
            if (speechRecognizer == null) return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLivePermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        handsFreeEnabled = false;
        SpeechRecognizer previous = speechRecognizer;
        speechRecognizer = null;
        try { previous.cancel(); } catch (Exception ignored) {}
        try { previous.destroy(); } catch (Exception ignored) {}
        createSpeechRecognizer();
        if (speechRecognizer == null) return;
        if (tts != null) tts.stop();
        ttsSpeaking = false;
        lastPartialText = "";
        recordingLocked = false;
        pressToTalkRequested = false;
        recognizerSessionActive = false;
        listening = false;
        manualCapture = false;
        headsetRecording = false;
        suppressAutoRead = false;
        liveEnabled = true;
        liveWaiting = false;
        liveSendConfirmed = false;
        liveGeneration++;
        livePage = webView.getUrl();
        webVoiceClose.setVisibility(View.VISIBLE);
        autoButton.setActivated(true);
        micButton.setActivated(true);
        micButton.setContentDescription("Ferma la conversazione");
        applyMicStyle(true);
        statusText.setText("LIVE · preparo l'ascolto…");
        scheduleLiveListen(450L);
    }

    private boolean pendingLivePermission;

    private void stopLiveMode(String reason) {
        liveEnabled = false;
        suppressAutoRead = true;
        liveWaiting = false;
        liveSendConfirmed = false;
        pendingLivePermission = false;
        liveGeneration++;
        // Also invalidate a voice draft whose insertion/send callback is pending.
        voiceSendGeneration++;
        SpeechRecognizer previous = speechRecognizer;
        speechRecognizer = null;
        if (previous != null) {
            try { previous.cancel(); } catch (Exception ignored) {}
            try { previous.destroy(); } catch (Exception ignored) {}
        }
        if (webVoiceClose != null) webVoiceClose.setVisibility(View.GONE);
        if (autoButton != null) autoButton.setActivated(false);
        recognizerSessionActive = false;
        listening = false;
        if (tts != null) { try { tts.stop(); } catch (Exception ignored) {} }
        ttsSpeaking = false;
        manualCapture = false;
        headsetRecording = false;
        manualStopPending = false;
        recordingLocked = false;
        pressToTalkRequested = false;
        if (micButton != null) {
            micButton.setActivated(false);
            micButton.setContentDescription("Avvia la conversazione; tocca ancora o X per fermare");
        }
        if (webView != null) webView.evaluateJavascript("window.__danLivePending=null;", null);
        if (statusText != null) statusText.setText(reason);
        if (micButton != null) applyMicStyle(false);
    }

    private void scheduleLiveListen(long delay) {
        final long generation = liveGeneration;
        statusText.postDelayed(() -> {
            if (!liveEnabled || generation != liveGeneration || !foreground || liveWaiting
                    || ttsSpeaking || recognizerSessionActive || manualCapture || headsetRecording) return;
            if (webView == null || !sameVoicePage(livePage, webView.getUrl()) || luminexVisible) {
                stopLiveMode("Chat cambiata · riavvia LIVE nella chat desiderata");
                return;
            }
            statusText.setText("LIVE · ti ascolto…");
            if (liveCaption != null) liveCaption.setText("TI ASCOLTO · pausa per inviare");
            startListening();
        }, delay);
    }

    private void sendLiveTurn(String text) {
        if (!liveEnabled || liveWaiting || ttsSpeaking) return;
        if (text.trim().isEmpty()) { scheduleLiveListen(500L); return; }
        liveWaiting = true;
        liveSendConfirmed = false;
        liveCaption.setText("ATTENDO LA RISPOSTA");
        statusText.setText("LIVE · trascrizione pronta, invio…");
        final long generation = liveGeneration;
        final long turn = ++liveTurnGeneration;
        // Every dictated utterance goes to the current chat; no wake word is required.
        injectTextAndSend(text);
        statusText.postDelayed(() -> {
            if (liveEnabled && liveWaiting && generation == liveGeneration && turn == liveTurnGeneration)
                stopLiveMode("Risposta non rilevata · controlla la chat prima di riprovare");
        }, 180000L);
    }

    private WebView getActiveLuminexWebView() {
        List<WebView> tabs = getActiveLuminexTabs();
        if (tabs.isEmpty()) return null;

        int index = getActiveLuminexTabIndex();
        if (index < 0) index = 0;
        if (index >= tabs.size()) index = tabs.size() - 1;
        setActiveLuminexTabIndex(index);
        return tabs.get(index);
    }



    private String privateProfileNameForChat(String chatKey) {
        if (TextUtils.isEmpty(chatKey)) {
            throw new IllegalStateException(
                    "Chat Dan non identificata"
            );
        }

        String key = chatKey;
        String safe = key.replaceAll("[^A-Za-z0-9_-]", "_");

        if (safe.length() > 90) {
            safe = safe.substring(0, 90);
        }

        return "dan-chat-" + safe;
    }

    private String privatePrefsKey(
            String chatKey,
            String suffix
    ) {
        String safe = chatKey == null
                ? ""
                : chatKey.replaceAll(
                        "[^A-Za-z0-9_-]",
                        "_"
                );

        return "luminex_private_"
                + safe
                + "_"
                + suffix;
    }


    private void savePrivateState(
            String chatKey,
            ChatPrivateState state
    ) {
        if (TextUtils.isEmpty(chatKey)
                || state == null) {
            return;
        }

        try {
            org.json.JSONArray urls =
                    new org.json.JSONArray();

            for (WebView tab : state.tabs) {
                if (tab == null) continue;

                String url = tab.getUrl();

                if (!isLuminexHttpUrl(url)) {
                    url = LUMINEX_HOME;
                }

                urls.put(url);
            }

            int index = state.tabIndex;

            if (!state.tabs.isEmpty()) {
                index = Math.max(
                        0,
                        Math.min(
                                index,
                                state.tabs.size() - 1
                        )
                );
            } else {
                index = 0;
            }

            getSharedPreferences(
                    PREFS,
                    MODE_PRIVATE
            )
                    .edit()
                    .putString(
                            privatePrefsKey(
                                    chatKey,
                                    "urls"
                            ),
                            urls.toString()
                    )
                    .putInt(
                            privatePrefsKey(
                                    chatKey,
                                    "index"
                            ),
                            index
                    )
                    .apply();

        } catch (Exception e) {
            android.util.Log.w(
                    "DanLuminex",
                    "Salvataggio sessione P fallito",
                    e
            );
        }
    }


    private void saveCurrentPrivateState() {
        if (TextUtils.isEmpty(activeDanChatKey)) {
            return;
        }

        ChatPrivateState state =
                privateChats.get(activeDanChatKey);

        if (state != null) {
            savePrivateState(
                    activeDanChatKey,
                    state
            );
        }
    }


    private void savePrivateStateForWebView(
            WebView view
    ) {
        if (view == null) return;

        for (Map.Entry<String, ChatPrivateState> entry
                : privateChats.entrySet()) {

            ChatPrivateState state =
                    entry.getValue();

            if (state != null
                    && state.tabs.contains(view)) {

                savePrivateState(
                        entry.getKey(),
                        state
                );

                return;
            }
        }
    }


    private void restoreCurrentPrivateState(
            ChatPrivateState state
    ) {
        if (state == null
                || state.restored
                || TextUtils.isEmpty(activeDanChatKey)) {
            return;
        }

        state.restored = true;

        SharedPreferences prefs =
                getSharedPreferences(
                        PREFS,
                        MODE_PRIVATE
                );

        String json = prefs.getString(
                privatePrefsKey(
                        activeDanChatKey,
                        "urls"
                ),
                ""
        );

        int wantedIndex = prefs.getInt(
                privatePrefsKey(
                        activeDanChatKey,
                        "index"
                ),
                0
        );

        if (!TextUtils.isEmpty(json)) {
            try {
                org.json.JSONArray urls =
                        new org.json.JSONArray(json);

                for (int i = 0;
                     i < urls.length();
                     i++) {

                    String url =
                            urls.optString(i, "");

                    if (!isLuminexHttpUrl(url)) {
                        continue;
                    }

                    createLuminexTabForSession(
                            1,
                            url
                    );
                }

            } catch (Exception e) {
                android.util.Log.w(
                        "DanLuminex",
                        "Ripristino sessione P fallito",
                        e
                );
            }
        }

        if (!state.tabs.isEmpty()) {
            state.tabIndex = Math.max(
                    0,
                    Math.min(
                            wantedIndex,
                            state.tabs.size() - 1
                    )
            );
        }
    }


    private ChatPrivateState getCurrentPrivateState() {
        if (TextUtils.isEmpty(activeDanChatKey)) {
            throw new IllegalStateException(
                    "Chat Dan non identificata"
            );
        }

        String key = activeDanChatKey;

        ChatPrivateState state = privateChats.get(key);

        if (state == null) {
            state = new ChatPrivateState(
                    privateProfileNameForChat(key)
            );
            privateChats.put(key, state);
        }

        return state;
    }


    private List<WebView> getActiveLuminexTabs() {
        if (!privateVisible) return luminexTabs;
        return getCurrentPrivateState().tabs;
    }

    private int getActiveLuminexTabIndex() {
        if (!privateVisible) return luminexTabIndex;
        return getCurrentPrivateState().tabIndex;
    }

    private void setActiveLuminexTabIndex(int index) {
        if (!privateVisible) {
            luminexTabIndex = index;
        } else {
            getCurrentPrivateState().tabIndex = index;
        }
    }

    private String activeLuminexSessionLabel() {
        return privateVisible ? "P" : "MAIN";
    }

    private void hideAllLuminexTabs() {
        for (WebView tab : luminexTabs) {
            if (tab != null) tab.setVisibility(View.GONE);
        }

        for (ChatPrivateState state : privateChats.values()) {
            for (WebView tab : state.tabs) {
                if (tab != null) tab.setVisibility(View.GONE);
            }
        }
    }

    private void updateLuminexAddress() {
        if (luminexAddressBar == null || luminexAddress == null) return;
        luminexAddressBar.setVisibility(luminexVisible ? View.VISIBLE : View.GONE);
        WebView active = getActiveLuminexWebView();
        String url = active == null ? "" : active.getUrl();
        if (url == null) url = "";
        if (!url.contentEquals(luminexAddress.getText())) {
            luminexAddress.setText(url);
            // La barra mostra soltanto l’URL.
        }
    }

    private void showActiveLuminexTab() {
        updateLuminexAddress();
        hideAllLuminexTabs();
        if (!luminexVisible) return;
        WebView active = getActiveLuminexWebView();
        if (active != null) {
            active.setVisibility(View.VISIBLE);
            active.bringToFront();
        }

        if (privateButton != null && luminexVisible) {
            privateButton.bringToFront();
        }
    }

    private WebView createLuminexTabForSession(
            int session
    ) {
        return createLuminexTabForSession(
                session,
                LUMINEX_HOME
        );
    }


    private WebView createLuminexTabForSession(
            int session,
            String initialUrl
    ) {
        WebView tab = new WebView(this);
        configureDownloads(tab);

        if (session != 0) {
            WebViewCompat.setProfile(
                    tab,
                    getCurrentPrivateState().profileName
            );
        }

        tab.setBackgroundColor(Color.WHITE);
        WebSettings settings = tab.getSettings();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/140.0.0.0 Safari/537.36"
        );

        CookieManager cookies;

        if (session != 0) {
            cookies = WebViewCompat
                    .getProfile(tab)
                    .getCookieManager();
        } else {
            cookies = CookieManager.getInstance();
        }

        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(tab, true);
        cookies.flush();

        tab.setWebViewClient(new WebViewClient() {
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                updateLuminexAddress();
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                if (session != 0) {
                    savePrivateStateForWebView(view);
                }

                if (luminexVisible && view == getActiveLuminexWebView()) {
                    view.postDelayed(() -> sendLuminexPageState(view), 1200L);
                }
                if (statusText == null || view != getActiveLuminexWebView()) return;
                String title = view.getTitle();
                statusText.setText(
                        "Luminex " + activeLuminexSessionLabel()
                                + " — scheda " + (getActiveLuminexTabIndex() + 1)
                                + " — " + (TextUtils.isEmpty(title) ? url : title)
                );
            }
        });

        tab.setVisibility(View.GONE);
        webContainer.addView(
                tab,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        List<WebView> tabs;

        if (session == 0) {
            tabs = luminexTabs;
        } else {
            tabs = getCurrentPrivateState().tabs;
        }

        tabs.add(tab);

        if (session == 0) {
            luminexTabIndex = tabs.size() - 1;
        } else {
            getCurrentPrivateState().tabIndex = tabs.size() - 1;
        }

        String urlToLoad =
                isLuminexHttpUrl(initialUrl)
                        ? initialUrl
                        : LUMINEX_HOME;

        tab.loadUrl(urlToLoad);
        showActiveLuminexTab();
        return tab;
    }

    private void newLuminexTab() {
        int session = privateVisible ? 1 : 0;

        createLuminexTabForSession(session);

        if (privateVisible) {
            saveCurrentPrivateState();
        }

        statusText.setText(
                "Luminex " + activeLuminexSessionLabel()
                        + " — nuova scheda " + getActiveLuminexTabs().size()
        );
    }

    private void nextLuminexTab() {
        List<WebView> tabs = getActiveLuminexTabs();
        if (tabs.size() <= 1) return;
        int index = (getActiveLuminexTabIndex() + 1) % tabs.size();
        setActiveLuminexTabIndex(index);

        if (privateVisible) {
            saveCurrentPrivateState();
        }

        showActiveLuminexTab();
        statusText.setText("Luminex " + activeLuminexSessionLabel()
                + " — scheda " + (index + 1) + "/" + tabs.size());
    }

    private void previousLuminexTab() {
        List<WebView> tabs = getActiveLuminexTabs();
        if (tabs.size() <= 1) return;
        int index = getActiveLuminexTabIndex() - 1;
        if (index < 0) index = tabs.size() - 1;
        setActiveLuminexTabIndex(index);

        if (privateVisible) {
            saveCurrentPrivateState();
        }

        showActiveLuminexTab();
        statusText.setText("Luminex " + activeLuminexSessionLabel()
                + " — scheda " + (index + 1) + "/" + tabs.size());
    }

    private void closeActiveLuminexTab() {
        List<WebView> tabs = getActiveLuminexTabs();
        if (tabs.size() <= 1) {
            statusText.setText("Luminex: almeno una scheda deve restare aperta");
            return;
        }

        int index = getActiveLuminexTabIndex();
        if (index < 0 || index >= tabs.size()) index = tabs.size() - 1;
        WebView closing = tabs.remove(index);
        try {
            webContainer.removeView(closing);
            closing.stopLoading();
            closing.loadUrl("about:blank");
            closing.clearHistory();
            closing.removeAllViews();
            closing.destroy();
        } catch (Exception ignored) {}

        if (index >= tabs.size()) index = tabs.size() - 1;
        setActiveLuminexTabIndex(index);

        if (privateVisible) {
            saveCurrentPrivateState();
        }

        showActiveLuminexTab();
        statusText.setText("Luminex " + activeLuminexSessionLabel()
                + " — scheda " + (index + 1) + "/" + tabs.size());
    }

    private void togglePrivateSession() {
        if (!WebViewFeature.isFeatureSupported(
                WebViewFeature.MULTI_PROFILE
        )) {
            Toast.makeText(
                    this,
                    "Sessioni multiple non supportate da questa WebView",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }

        if (!luminexVisible) {
            setLuminexVisible(true);
        }

        if (privateVisible) {
            closePrivateSession();
        } else {
            openPrivateSession();
        }
    }


    private String decodeJavascriptString(String value) {
        if (value == null) return "";

        String v = value.trim();

        if ("null".equals(v) || "undefined".equals(v)) {
            return "";
        }

        if (v.length() >= 2
                && v.startsWith("\"")
                && v.endsWith("\"")) {

            v = v.substring(1, v.length() - 1);
            v = v.replace("\\\"", "\"");
            v = v.replace("\\\\", "\\");
        }

        return v.trim();
    }

    private void resolveCurrentDanChatAndOpenPrivate() {
        if (webView == null) {
            return;
        }

        // La WebView di Dan conosce già l'URL reale della conversazione.
        // Non usiamo più JavaScript/DOM/sidebar per indovinare la chat.
        String currentUrl = webView.getUrl();

        if (!TextUtils.isEmpty(currentUrl)) {
            updateActiveDanChat(currentUrl);
        }

        if (TextUtils.isEmpty(activeDanChatKey)) {
            Toast.makeText(
                    MainActivity.this,
                    "Apri prima una conversazione Dan dalla cronologia.",
                    Toast.LENGTH_LONG
            ).show();

            if (statusText != null) {
                statusText.setText(
                        "Luminex: nessuna conversazione Dan attiva"
                );
            }

            return;
        }

        openPrivateSessionResolved();
    }


    private void openPrivateSession() {
        resolveCurrentDanChatAndOpenPrivate();
    }

    private void openPrivateSessionResolved() {
        ChatPrivateState state = getCurrentPrivateState();

        privateVisible = true;

        restoreCurrentPrivateState(state);

        if (state.tabs.isEmpty()) {
            createLuminexTabForSession(
                    1,
                    LUMINEX_HOME
            );
        }

        luminexVisible = true;

        if (webView != null) {
            webView.setVisibility(View.GONE);
        }

        showActiveLuminexTab();

        if (luminexButton != null) {
            luminexButton.setText("D");
        }

        if (privateButton != null) {
            privateButton.setVisibility(View.VISIBLE);
            privateButton.setText("P");
        }

        statusText.setText(
                "Luminex P — scheda "
                        + (getActiveLuminexTabIndex() + 1)
                        + "/"
                        + getActiveLuminexTabs().size()
        );

        luminexHandler.removeCallbacks(luminexSnapshotRunnable);
        luminexHandler.post(luminexSnapshotRunnable);
    }


    private void openPrivateSession(int ignoredSessionNumber) {
        openPrivateSession();
    }


    private void closePrivateSession() {
        saveCurrentPrivateState();

        privateVisible = false;
        luminexVisible = true;
        if (webView != null) webView.setVisibility(View.GONE);
        showActiveLuminexTab();

        if (luminexButton != null) luminexButton.setText("D");
        if (privateButton != null) {
            privateButton.setVisibility(View.VISIBLE);
            privateButton.setText("P");
        }

        statusText.setText(
                "Luminex MAIN — scheda " + (getActiveLuminexTabIndex() + 1)
                        + "/" + getActiveLuminexTabs().size()
        );

        luminexHandler.removeCallbacks(luminexSnapshotRunnable);
        luminexHandler.post(luminexSnapshotRunnable);
    }



    private void toggleRecordingSounds() {
        if (webVoiceRequested) stopWebVoice("LIVE chiuso · audio modificato");
        if (liveEnabled) stopLiveMode("Live fermato · audio modificato");
        recordingSoundsEnabled = !recordingSoundsEnabled;

        if (!recordingSoundsEnabled && tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
            ttsSpeaking = false;
        }

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_RECORDING_SOUNDS, recordingSoundsEnabled)
                .apply();

        if (soundButton != null) {
            soundButton.setText(recordingSoundsEnabled ? "🔔" : "🔕");
        }

        Toast.makeText(
                this,
                recordingSoundsEnabled ? "Audio di Dan attivo" : "Audio di Dan disattivato",
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

    private void playRecognitionErrorSound() {
        if (!recordingSoundsEnabled) return;
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 28);
            tone.startTone(ToneGenerator.TONE_PROP_NACK, 180);
            statusText.postDelayed(tone::release, 280L);
        } catch (Exception ignored) {}
    }

    private void playReadingFinishedSound() {
        if (!recordingSoundsEnabled) return;
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 24);
            tone.startTone(ToneGenerator.TONE_PROP_PROMPT, 150);
            statusText.postDelayed(tone::release, 260L);
        } catch (Exception ignored) {}
    }

    private void stopAllSpeech() {
        if (webVoiceRequested) stopWebVoice("LIVE chiuso");
        if (liveEnabled) stopLiveMode("Live fermato");
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) {}
        }
        ttsSpeaking = false;

        if (webView != null) {
            String js =
                    "(function(){"
                    + "const buttons=Array.from(document.querySelectorAll('button'));"
                    + "const b=buttons.find(x=>{"
                    + " const s=((x.getAttribute('aria-label')||'')+' '+(x.getAttribute('title')||'')+' '+(x.innerText||'')).toLowerCase();"
                    + " return s.includes('stop')||s.includes('interrompi')||s.includes('pause')||s.includes('pausa');"
                    + "});"
                    + "if(b){try{b.click();return 'STOPPED';}catch(e){}}"
                    + "return 'NO_NATIVE_READER';"
                    + "})();";
            try { webView.evaluateJavascript(js, null); } catch (Exception ignored) {}
        }

        statusText.setText("⏹ Lettura interrotta");
    }

    private void replayLastSpeech() {
        if (!recordingSoundsEnabled) {
            statusText.setText("🔕 Audio di Dan disattivato");
            return;
        }
        if (webView == null) {
            statusText.setText("▶ Pagina non disponibile");
            return;
        }

        final String replayPage = webView.getUrl();
        String script =
                "(function(){"
                + " function txt(e){"
                + "  if(!e)return '';"
                + "  return (e.innerText||e.textContent||'')"
                + "   .replace(/\\s+/g,' ')"
                + "   .trim();"
                + " }"

                + " const root=document.querySelector('main')||document.body;"
                + " if(!root)return '';"

                + danPageScript()
                + " const direct=danAnswer();if(direct)return direct;"
                + " const buttons=Array.from(root.querySelectorAll('button'));"

                + " for(let i=buttons.length-1;i>=0;i--){"
                + "  const b=buttons[i];"

                + "  const meta=("
                + "   (b.getAttribute('aria-label')||'')+' '+"
                + "   (b.getAttribute('title')||'')+' '+"
                + "   (b.getAttribute('data-testid')||'')"
                + "  ).toLowerCase();"

                + "  if(!("
                + "   meta.includes('copy')||"
                + "   meta.includes('copia')||"
                + "   meta.includes('read aloud')||"
                + "   meta.includes('leggi')||"
                + "   meta.includes('good-response')||"
                + "   meta.includes('bad-response')"
                + "  ))continue;"

                + "  let e=b.parentElement;"

                + "  for(let level=0;e&&level<6;level++,e=e.parentElement){"
                + "   if(e===root)break;"

                + "   const blocks=Array.from(e.querySelectorAll("
                + "    'p,li,pre,blockquote,h1,h2,h3,h4,h5,h6'"
                + "   )).filter(x=>txt(x).length>0);"

                + "   if(!blocks.length)continue;"

                + "   const answer=blocks.map(txt).join(' ').trim();"

                + "   if(answer.length>=20 && answer.length<=12000){"
                + "    return answer;"
                + "   }"
                + "  }"
                + " }"

                + " return '';"
                + "})()";

        webView.evaluateJavascript(
                script,
                value -> runOnUiThread(() -> {
                    if (webView == null || !sameVoicePage(replayPage, webView.getUrl())) return;
                    String recovered = "";

                    try {
                        if (value != null
                                && !"null".equals(value)
                                && !"\"\"".equals(value)) {

                            recovered =
                                    new org.json.JSONArray(
                                            "[" + value + "]"
                                    ).getString(0);
                        }
                    } catch (Exception ignored) {}

                    recovered = cleanSpeechLabels(recovered);

                    if (recovered.isEmpty()) {
                        statusText.setText(
                                "▶ Risposta non individuata"
                        );
                        return;
                    }

                    lastSpokenText = recovered;
                    replaySpeechText(recovered);
                })
        );
    }

    private void replaySpeechText(String text) {
        if (!recordingSoundsEnabled) {
            statusText.setText("🔕 Audio di Dan disattivato");
            return;
        }
        if (TextUtils.isEmpty(text)) {
            statusText.setText(
                    "▶ Nessuna lettura da ripetere"
            );
            return;
        }

        if (!ttsReady || tts == null) {
            statusText.setText(
                    "Sintesi vocale non pronta"
            );
            return;
        }

        speakLongText(
                text,
                "chatgpt_replay"
        );

        statusText.setText(
                "▶ Lettura dall'inizio"
        );
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

    private void recoverMicrophoneAccess() {
        if (liveEnabled) stopLiveMode("Live fermato · microfono manuale");
        handsFreeEnabled = false;
        handsFreeDictating = false;
        handsFreeBuffer.setLength(0);
        headsetRecording = false;

        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_HANDS_FREE, false)
                .apply();

        if (headsetMediaSession != null) {
            headsetMediaSession.setActive(false);
            headsetMediaSession.release();
            headsetMediaSession = null;
        }

        createHeadsetMediaSession();
        if (headsetMediaSession != null) {
            headsetMediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f).build());
            headsetMediaSession.setActive(true);
        }
        autoButton.setText("LIVE");
        statusText.setText("Cuffie riattivate");
    }

    private void openMicrophoneAppSettings() {
        try {
            Intent intent = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)
            );
            startActivity(intent);
            statusText.setText("Apri Permessi → Microfono e abilitalo per Dan");
        } catch (Exception e) {
            statusText.setText("Impossibile aprire le impostazioni dei permessi");
        }
    }

    private void recoverHandsFreeMicrophone() {
        if (!handsFreeEnabled) return;

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_AUDIO);
            return;
        }

        if (speechRecognizer != null) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) {}
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
        }

        speechRecognizer = null;
        recognizerSessionActive = false;
        listening = false;
        manualCapture = false;
        headsetRecording = false;

        createSpeechRecognizer();

        if (speechRecognizer != null) {
            statusText.setText("🟢 Auto ON — dì Jasper per iniziare");
            scheduleHandsFreeRestart(300L);
        } else {
            statusText.setText("Microfono non disponibile");
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
        micButton.setBackground(new DanHudMicDrawable(active || liveEnabled || webVoiceRequested));
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.rgb(0, 200, 255));
        button.setTextSize(21);
        GradientDrawable panel = new GradientDrawable();
        panel.setColor(Color.rgb(7, 20, 40));
        panel.setCornerRadius(dp(20));
        panel.setStroke(dp(1), Color.argb(46, 0, 200, 255));
        button.setBackground(panel);
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
                    final long callbackGeneration = liveGeneration;
                    runOnUiThread(() -> {
                        if (callbackGeneration != liveGeneration
                                || (utteranceId != null && utteranceId.startsWith("chatgpt_")
                                && !utteranceId.contains("_g" + liveGeneration + "_"))) return;
                        ttsSpeaking = true;
                        if (speechRecognizer != null && recognizerSessionActive) {
                            try { speechRecognizer.cancel(); } catch (Exception ignored) {}
                        }
                        statusText.setText("🔊 Lettura risposta…");
                    });
                }

                @Override
                public void onDone(String utteranceId) {
                    final long callbackGeneration = liveGeneration;
                    if (utteranceId != null
                            && (utteranceId.startsWith("chatgpt_response_")
                            || utteranceId.startsWith("chatgpt_replay_"))
                            && !utteranceId.endsWith("_last")) {
                        return;
                    }

                    runOnUiThread(() -> {
                        if (callbackGeneration != liveGeneration
                                || (utteranceId != null && utteranceId.startsWith("chatgpt_")
                                && !utteranceId.contains("_g" + liveGeneration + "_"))) return;
                        ttsSpeaking = false;
                        if (liveEnabled) {
                            recognizerSessionActive = false;
                            listening = false;
                            scheduleLiveListen(650L);
                        }
                        playReadingFinishedSound();
                        statusText.setText(handsFreeEnabled
                                ? "🟢 In attesa — dì Jasper per iniziare"
                                : "Tocca il microfono per parlare");
                        if (handsFreeEnabled) {
                            recognizerSessionActive = false;
                            listening = false;
                            scheduleHandsFreeRestart(500L);
                        }
                    });
                }

                @Override
                public void onError(String utteranceId) {
                    final long callbackGeneration = liveGeneration;
                    runOnUiThread(() -> {
                        if (callbackGeneration != liveGeneration
                                || (utteranceId != null && utteranceId.startsWith("chatgpt_")
                                && !utteranceId.contains("_g" + liveGeneration + "_"))) return;
                        ttsSpeaking = false;
                        if (liveEnabled) {
                            stopLiveMode("Voce non disponibile · risposta visibile nella chat");
                            return;
                        }
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

        if (preview && recordingSoundsEnabled) {
            tts.speak(
                    "Questa è la modalità Saggio. Più calma, lenta e decisamente più profonda.",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "sage_preview"
            );
        }
    }

    private void showVoiceChooser() {
        if (webVoiceRequested) stopWebVoice("LIVE chiuso · scelta voce locale");
        if (liveEnabled) stopLiveMode("Live fermato · scelta voce");
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

                        if (recordingSoundsEnabled) {
                            tts.speak(
                                    "Questa è la voce selezionata. Ascoltala e scegli solo se ti piace.",
                                    TextToSpeech.QUEUE_FLUSH,
                                    null,
                                    "voice_preview"
                            );
                        }
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

    private String cleanSpeechLabels(String text) {
        String cleaned = stripEmojis(text == null ? "" : text)
                .replaceAll("[\\s\\u00a0\\u202f]+", " ").trim();
        for (int i = 0; i < 3; i++) {
            String next = cleaned.replaceFirst(
                    "(?iu)^\\s*(?:dan\\s+ha\\s+detto|chatgpt\\s+ha\\s+detto|chat\\s+ha\\s+detto|tu\\s+hai\\s+detto|hai\\s+detto)\\s*[:：,.!?–—-]?\\s*", "").trim();
            if (next.equals(cleaned)) break;
            cleaned = next;
        }
        return cleaned;
    }

    private void speakAssistantText(String text) {
        if (webVoiceRequested) return; // The official voice session supplies its own audio.
        if (text == null) return;

        String cleaned = cleanSpeechLabels(text);

        if (cleaned.isEmpty()
                || cleaned.equals(lastSpokenText)) {
            return;
        }

        lastSpokenText = cleaned;

        runOnUiThread(() -> {
            if (!recordingSoundsEnabled) {
                statusText.setText("🔕 Audio di Dan disattivato");
                return;
            }
            if (!ttsReady || tts == null) {
                statusText.setText("Risposta pronta");
                return;
            }

            speakLongText(
                    cleaned,
                    "chatgpt_response"
            );
        });
    }

    private void speakLongText(
            String text,
            String idPrefix
    ) {
        if (!recordingSoundsEnabled || tts == null || TextUtils.isEmpty(text)) {
            return;
        }

        String remaining = text.trim();

        int systemMax =
                TextToSpeech.getMaxSpeechInputLength();

        int max =
                Math.min(
                        3200,
                        Math.max(800, systemMax - 200)
                );

        List<String> chunks =
                new ArrayList<>();

        while (!remaining.isEmpty()) {
            if (remaining.length() <= max) {
                chunks.add(remaining);
                break;
            }

            int cut = max;

            int dot =
                    remaining.lastIndexOf(". ", max);

            int question =
                    remaining.lastIndexOf("? ", max);

            int exclamation =
                    remaining.lastIndexOf("! ", max);

            int semicolon =
                    remaining.lastIndexOf("; ", max);

            int best =
                    Math.max(
                            Math.max(dot, question),
                            Math.max(exclamation, semicolon)
                    );

            if (best > max / 2) {
                cut = best + 1;
            } else {
                int space =
                        remaining.lastIndexOf(' ', max);

                if (space > max / 2) {
                    cut = space;
                }
            }

            chunks.add(
                    remaining.substring(0, cut).trim()
            );

            remaining =
                    remaining.substring(cut).trim();
        }

        if (chunks.isEmpty()) return;

        tts.stop();

        for (int i = 0; i < chunks.size(); i++) {
            boolean last =
                    i == chunks.size() - 1;

            String utteranceId =
                    idPrefix
                            + "_g" + liveGeneration
                            + "_"
                            + i
                            + (last ? "_last" : "");

            int queued = tts.speak(
                    chunks.get(i),
                    i == 0
                            ? TextToSpeech.QUEUE_FLUSH
                            : TextToSpeech.QUEUE_ADD,
                    null,
                    utteranceId
            );
            if (queued == TextToSpeech.ERROR) {
                if (liveEnabled) stopLiveMode("Lettura non riuscita · risposta visibile nella chat");
                return;
            }
        }
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
        if (liveEnabled) stopLiveMode("Live fermato · comando cuffia");
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
                armDictationResultTimeout();
                try { speechRecognizer.stopListening(); } catch (Exception ignored) {}
            } else {
                manualCapture = false;
                statusText.setText(handsFreeEnabled
                        ? "🟢 In attesa — dì Jasper per iniziare"
                        : "Tocca il microfono per parlare");
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

        if (speechRecognizer != null) {
            SpeechRecognizer previous = speechRecognizer;
            speechRecognizer = null;
            try { previous.cancel(); } catch (Exception ignored) {}
            try { previous.destroy(); } catch (Exception ignored) {}
        }
        recognizerSessionActive = false;
        listening = false;
        manualStopPending = false;
        lastPartialText = "";
        createSpeechRecognizer();
        if (speechRecognizer == null) {
            headsetRecording = false;
            statusText.setText("Riconoscimento vocale non disponibile");
            return;
        }
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

    private void recoverStalledDictation(SpeechRecognizer owner, String reason) {
        if (speechRecognizer != owner || !manualCapture || !recognizerSessionActive) return;
        String partial = lastPartialText == null ? "" : lastPartialText.trim();
        speechRecognizer = null;
        recognizerSessionActive = false;
        listening = false;
        manualCapture = false;
        manualStopPending = false;
        headsetRecording = false;
        recordingLocked = false;
        pressToTalkRequested = false;
        lastPartialText = "";
        try { owner.cancel(); } catch (Exception ignored) {}
        try { owner.destroy(); } catch (Exception ignored) {}
        micButton.setText("🎙");
        applyMicStyle(false);
        createSpeechRecognizer();
        statusText.setText(reason + (partial.isEmpty()
                ? " · microfono ripristinato, riprova"
                : " · testo parziale recuperato"));
        if (!partial.isEmpty()) showUnsentVoiceDraft(partial,
                reason + " Il testo è parziale: controllalo prima di inviarlo.");
        scheduleHandsFreeRestart(700L);
    }

    private void armDictationResultTimeout() {
        final SpeechRecognizer owner = speechRecognizer;
        if (owner != null && manualCapture) statusText.postDelayed(() ->
                recoverStalledDictation(owner, "Il riconoscitore non ha restituito il testo"), 12000L);
    }

    private void createSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this,
                    "Riconoscimento vocale Android non disponibile",
                    Toast.LENGTH_LONG).show();
            statusText.setText("Installa o abilita il servizio di riconoscimento vocale Android");
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

        final SpeechRecognizer owner = speechRecognizer;
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                if (speechRecognizer != owner) return;
                listening = true;
                recognizerSessionActive = true;
                lastPartialText = "";
                applyMicStyle(true);
                if (manualCapture && manualStopPending) {
                    statusText.setText("🎙 Fine dettatura in corso…");
                    statusText.postDelayed(() -> {
                        if (manualStopPending && manualCapture && recognizerSessionActive) {
                            manualStopPending = false;
                            try { speechRecognizer.stopListening(); }
                            catch (Exception e) { statusText.setText("Errore chiusura dettatura"); }
                        }
                    }, 400L);
                } else if (manualCapture) {
                    playRecordingStartSound();
                    statusText.setText("🎙 Ti ascolto… tocca di nuovo per terminare");
                } else if (liveEnabled) {
                    statusText.setText("LIVE · ti ascolto…");
                } else if (handsFreeDictating) {
                    statusText.setText("🔴 Dettatura attiva — dì Jasper per inviare");
                } else {
                    statusText.setText("🟢 In attesa — dì Jasper per iniziare");
                }
            }

            @Override
            public void onBeginningOfSpeech() {
                if (speechRecognizer != owner) return;
                if (liveEnabled) {
                    statusText.setText("LIVE · parla normalmente…");
                } else if (manualCapture) {
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
                if (speechRecognizer != owner) return;
                statusText.setText(manualCapture ? "Trascrivo…" : "Elaboro…");
                if (manualCapture) statusText.postDelayed(() ->
                        recoverStalledDictation(owner, "Il riconoscitore non ha concluso la trascrizione"), 12000L);
            }

            @Override
            public void onError(int error) {
                if (speechRecognizer != owner) return;
                boolean wasManual = manualCapture;
                String recoveredPartial = lastPartialText == null ? "" : lastPartialText.trim();
                lastPartialText = "";
                listening = false;
                recognizerSessionActive = false;
                manualStopPending = false;
                applyMicStyle(false);

                if (liveEnabled) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        scheduleLiveListen(650L);
                    } else {
                        stopLiveMode("Riconoscimento interrotto · verifica rete e permesso microfono");
                    }
                    return;
                }

                if (wasManual) {
                    manualCapture = false;
                    headsetRecording = false;
                    recordingLocked = false;
                    pressToTalkRequested = false;
                    micButton.setText("🎙");
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        statusText.setText("Permesso microfono mancante.");
                    } else if (error == SpeechRecognizer.ERROR_NO_MATCH
                            || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        playRecognitionErrorSound();
                        statusText.setText("Nessuna frase riconosciuta: attendi Ti ascolto prima di parlare (codice " + error + ").");
                    } else if (error == SpeechRecognizer.ERROR_CLIENT) {
                        statusText.setText("Errore tecnico del riconoscitore (codice " + error + ").");
                    } else {
                        statusText.setText("Errore riconoscimento vocale: " + error);
                    }
                    speechRecognizer = null;
                    try { owner.cancel(); } catch (Exception ignored) {}
                    try { owner.destroy(); } catch (Exception ignored) {}
                    createSpeechRecognizer();
                    if (!recoveredPartial.isEmpty()) showUnsentVoiceDraft(recoveredPartial,
                            "Riconoscimento interrotto (codice " + error
                            + "). Testo parziale recuperato: controllalo prima di inviare.");
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
                if (speechRecognizer != owner) return;
                listening = false;
                recognizerSessionActive = false;
                manualStopPending = false;
                applyMicStyle(false);

                String text = chooseBestRecognition(results);
                if (liveEnabled) {
                    // Never auto-send a recovered partial transcription.
                    ArrayList<String> finals = results == null ? null :
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    text = finals == null || finals.isEmpty() || finals.get(0) == null ? "" : finals.get(0).trim();
                    sendLiveTurn(text);
                    return;
                }

                if (manualCapture) {
                    manualCapture = false;
                    headsetRecording = false;
                    recordingLocked = false;
                    pressToTalkRequested = false;
                    micButton.setText("🎙");

                    if (!text.isEmpty()) {
                        handleRecognizedCommandOrMessage(text);
                    } else {
                        playRecognitionErrorSound();
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
                if (speechRecognizer != owner) return;
                if (partialResults == null) return;

                ArrayList<String> partials =
                        partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (partials == null || partials.isEmpty()) return;

                String candidate = partials.get(0) == null ? "" : partials.get(0).trim();
                if (liveEnabled && liveCaption != null && !candidate.isEmpty()) {
                    liveCaption.setText(candidate);
                }
                if (!candidate.isEmpty() && candidate.length() >= lastPartialText.length()) {
                    lastPartialText = candidate;
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void startHandsFreeMode() {
        if (!handsFreeEnabled) {
            statusText.setText("Tocca il microfono per parlare");
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
        if (liveEnabled) stopLiveMode("Conversazione fermata · dettatura manuale");
        if (speechRecognizer == null) createSpeechRecognizer();
        if (webVoiceRequested) stopWebVoice("LIVE chiuso · dettatura manuale");
        suppressAutoRead = false;
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
            if (manualCapture && speechRecognizer != null && !recognizerSessionActive)
                startListening();
        }, 120L);
    }

    private void startListening() {
        if (speechRecognizer == null || recognizerSessionActive) return;

        try {
            recognizerSessionActive = true;
            Intent request = new Intent(speechIntent);
            if (manualCapture || liveEnabled) {
                // Long-listening timeout hints are unsuitable for push-to-talk.
                request.removeExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS);
                request.removeExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS);
                request.removeExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS);
            }
            if (liveEnabled) {
                request.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L);
            }
            final SpeechRecognizer startedRecognizer = speechRecognizer;
            final long capture = ++liveListenGeneration;
            if (manualCapture) statusText.postDelayed(() -> {
                if (speechRecognizer == startedRecognizer && !listening)
                    recoverStalledDictation(startedRecognizer, "Il servizio vocale non ha avviato l'ascolto");
            }, 8000L);
            speechRecognizer.startListening(request);
            if (liveEnabled) {
                final long generation = liveGeneration;
                statusText.postDelayed(() -> {
                    if (liveEnabled && generation == liveGeneration && capture == liveListenGeneration
                            && speechRecognizer == startedRecognizer && recognizerSessionActive && !liveWaiting)
                        stopLiveMode("Il microfono non ha concluso · riavvia LIVE");
                }, 65000L);
            }
        } catch (Exception e) {
            recognizerSessionActive = false;
            listening = false;
            if (liveEnabled) {
                stopLiveMode("Impossibile avviare il microfono");
            } else if (manualCapture) {
                statusText.setText("Impossibile avviare il microfono");
            } else {
                scheduleHandsFreeRestart(800L);
            }
        }
    }

    private void finishLockedRecording() {
        if (!recordingLocked) return;
        armDictationResultTimeout();

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
        armDictationResultTimeout();
        if (manualCapture) {
            playRecordingEndSound();
        }
        if (speechRecognizer != null && manualCapture) {
            if (recognizerSessionActive && listening) {
                manualStopPending = false;
                statusText.setText("Trascrivo…");
                try { speechRecognizer.stopListening(); } catch (Exception ignored) {}
            } else {
                // A short press may finish during the Android startup handshake.
                manualStopPending = true;
                statusText.setText("🎙 Attendo il riconoscitore…");
                statusText.postDelayed(() -> {
                    if (manualStopPending && manualCapture && recognizerSessionActive) {
                        manualStopPending = false;
                        statusText.setText("Trascrivo…");
                        try { speechRecognizer.stopListening(); }
                        catch (Exception e) { statusText.setText("Errore fine dettatura"); }
                    }
                }, 2000L);
            }
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
            if (pendingManualPermission) {
                pendingManualPermission = false;
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    statusText.postDelayed(() -> { if (foreground) toggleManualDictation(); }, 350L);
                else statusText.setText("Permesso microfono negato");
                return;
            }
            if (pendingWebVoicePermission) {
                pendingWebVoicePermission = false;
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    statusText.postDelayed(() -> { if (foreground) toggleWebVoice(); }, 350L);
                else stopWebVoice("Permesso microfono negato");
                return;
            }
            if (pendingLivePermission) {
                pendingLivePermission = false;
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    statusText.postDelayed(() -> { if (foreground) toggleLiveMode(); }, 350L);
                } else {
                    stopLiveMode("Permesso microfono negato");
                }
                return;
            }
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pressToTalkRequested || manualCapture) {
                    startPressToTalk();
                } else {
                    handsFreeEnabled = true;
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit()
                            .putBoolean(PREF_HANDS_FREE, true)
                            .apply();
                    recoverHandsFreeMicrophone();
                }
            } else {
                openMicrophoneAppSettings();
            }
        }
    }

    private static final int REQ_BLOB_SAVE = 4010;
    private static final int REQ_UPLOAD_FOLDER = 4011;
    private final BlobDownloadBridge blobDownload = new BlobDownloadBridge();
    private File blobSaveFile;
    private boolean folderUploadBusy;

    private void configureDownloads(WebView source) {
        source.addJavascriptInterface(blobDownload, "DanFileDownload");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(source, voiceAsset("dan-file-download.js"),
                        Collections.singleton("*"));
            } catch (RuntimeException ignored) { /* Existing fetch path remains available. */ }
        }
        source.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url == null || url.trim().isEmpty()) {
                Toast.makeText(
                        MainActivity.this,
                        "Download non disponibile",
                        Toast.LENGTH_SHORT
                ).show();
                return;
            }

            if (url.startsWith("blob:")) {
                beginBlobDownload(source, url, mimetype);
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
    }

    private void beginBlobDownload(WebView source, String url, String mime) {
        if (blobSaveFile != null || blobDownload.busy()) {
            Toast.makeText(this, "Completa prima il salvataggio in corso", Toast.LENGTH_LONG).show();
            return;
        }
        final String token;
        try { token = blobDownload.begin(); }
        catch (Exception e) {
            Toast.makeText(this, "Spazio temporaneo non disponibile", Toast.LENGTH_LONG).show();
            return;
        }
        String name = pendingDownloadName;
        pendingDownloadName = null;
        pendingDownloadNameAt = 0L;
        if (TextUtils.isEmpty(name)) name = URLUtil.guessFileName("file", null, mime);
        final String filename = sanitizeFilename(name);
        final String contentType = TextUtils.isEmpty(mime) ? "application/octet-stream" : mime;
        statusText.setText("Preparazione del file da salvare…");
        String js = voiceAsset("dan-file-download.js")
                + ";window.__danFileDownloads.save(" + JSONObject.quote(url) + ","
                + JSONObject.quote(token) + "," + JSONObject.quote(filename) + ","
                + JSONObject.quote(contentType) + ");";
        source.evaluateJavascript(js, null);
    }

    private final class BlobDownloadBridge {
        private String token;
        private File file;
        private java.io.FileOutputStream output;
        private long bytes;
        synchronized boolean busy() { return token != null; }
        synchronized String begin() throws Exception {
            file = File.createTempFile("dan-download-", ".part", getCacheDir());
            output = new java.io.FileOutputStream(file);
            bytes = 0;
            token = java.util.UUID.randomUUID().toString();
            return token;
        }
        @JavascriptInterface
        public synchronized boolean append(String key, String data) {
            if (token == null || !token.equals(key) || data == null || data.length() > 65536) return false;
            try {
                byte[] part = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                output.write(part); bytes += part.length; return true;
            } catch (Exception e) { fail(key, "Scrittura non riuscita"); return false; }
        }
        @JavascriptInterface
        public synchronized void finish(String key, long size, String name, String mime) {
            if (token == null || !token.equals(key)) return;
            if (size != bytes) { fail(key, "File incompleto"); return; }
            try { output.close(); }
            catch (Exception e) { fail(key, "Chiusura file non riuscita"); return; }
            final File ready = file;
            output = null; file = null; token = null;
            runOnUiThread(() -> {
                blobSaveFile = ready;
                try {
                    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    save.addCategory(Intent.CATEGORY_OPENABLE);
                    save.setType(TextUtils.isEmpty(mime) ? "application/octet-stream" : mime);
                    save.putExtra(Intent.EXTRA_TITLE, sanitizeFilename(name));
                    startAttachmentProtection();
                    startActivityForResult(save, REQ_BLOB_SAVE);
                } catch (Exception e) {
                    stopAttachmentProtection();
                    ready.delete(); blobSaveFile = null;
                    statusText.setText("Impossibile aprire il salvataggio del file");
                }
            });
        }
        @JavascriptInterface
        public synchronized void fail(String key, String reason) {
            if (token == null || !token.equals(key)) return;
            try { if (output != null) output.close(); } catch (Exception ignored) {}
            if (file != null) file.delete();
            output = null; file = null; token = null;
            runOnUiThread(() -> {
                statusText.setText("Download temporaneo non riuscito");
                Toast.makeText(MainActivity.this, "Download non riuscito: " + reason, Toast.LENGTH_LONG).show();
            });
        }
    }

    private void chooseFolderForDrive() {
        if (folderUploadBusy) {
            Toast.makeText(this, "Preparazione cartella già in corso", Toast.LENGTH_SHORT).show(); return;
        }
        Intent select = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        select.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(select, REQ_UPLOAD_FOLDER); }
        catch (Exception e) { statusText.setText("Selettore cartelle non disponibile"); }
    }

    private void collectFolderFiles(Uri tree, String id, ArrayList<Uri> files,
                                    java.util.Set<String> visited) throws Exception {
        if (!visited.add(id)) return;
        Uri children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        ArrayList<String> directories = new ArrayList<>();
        try (Cursor c = getContentResolver().query(children, new String[]{
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (c == null) throw new java.io.IOException("Cartella non leggibile");
            while (c.moveToNext()) {
                String child = c.getString(0);
                if (android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(1)))
                    directories.add(child);
                else files.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, child));
            }
        }
        for (String directory : directories) collectFolderFiles(tree, directory, files, visited);
    }

    private void prepareFolderForDrive(Uri tree) {
        folderUploadBusy = true;
        statusText.setText("Raccolgo i file della cartella e delle sottocartelle…");
        memoryExecutor.execute(() -> {
            try {
                ArrayList<Uri> files = new ArrayList<>();
                collectFolderFiles(tree, android.provider.DocumentsContract.getTreeDocumentId(tree),
                        files, new java.util.HashSet<>());
                runOnUiThread(() -> {
                    folderUploadBusy = false;
                    if (files.isEmpty()) { statusText.setText("La cartella non contiene file"); return; }
                    Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE);
                    send.setType("*/*");
                    send.setPackage("com.google.android.apps.docs");
                    send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
                    ClipData clip = ClipData.newUri(getContentResolver(), "File della cartella", files.get(0));
                    for (int i = 1; i < files.size(); i++) clip.addItem(new ClipData.Item(files.get(i)));
                    send.setClipData(clip);
                    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(send);
                        statusText.setText(files.size() + " file passati a Drive: scegli destinazione e Salva");
                    } catch (Exception e) {
                        statusText.setText("Impossibile passare la cartella a Drive");
                        new AlertDialog.Builder(MainActivity.this).setTitle("Copia su Drive non avviata")
                                .setMessage("Verifica che l’app Google Drive sia installata e aggiornata. "
                                        + "Nessun file originale è stato modificato.")
                                .setPositiveButton("OK", null).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    folderUploadBusy = false;
                    statusText.setText("Cartella non leggibile: " + e.getMessage());
                });
            }
        });
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


    // Dan Luminex AI Integration v1.25
    private void createLuminexWebView() {
        luminexWebView = new WebView(this);
        configureDownloads(luminexWebView);
        luminexWebView.setBackgroundColor(Color.WHITE);

        WebSettings settings = luminexWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        );

        settings.setUserAgentString(
                settings.getUserAgentString()
                        + " Dan-Luminex/1.25"
        );

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(
                luminexWebView,
                true
        );

        luminexWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                updateLuminexAddress();
            }
            @Override
            public void onPageFinished(
                    WebView view,
                    String url
            ) {
                view.postDelayed(
                        () -> sendLuminexPageState(view),
                        1200L
                );

                if (!luminexVisible || statusText == null) return;

                String title = view.getTitle();
                statusText.setText(
                        "Luminex AI — "
                                + (TextUtils.isEmpty(title)
                                ? url
                                : title)
                );
            }
        });

        luminexWebView.setVisibility(View.GONE);

        webContainer.addView(
                luminexWebView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        luminexWebView.getSettings().setUseWideViewPort(true);
        luminexWebView.getSettings().setLoadWithOverviewMode(true);
        luminexWebView.getSettings().setUserAgentString(
                "Mozilla/5.0 (X11; Linux x86_64) "
                + "AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/140.0.0.0 Safari/537.36"
        );

        if (!luminexTabs.contains(luminexWebView)) {
            luminexTabs.add(luminexWebView);
            luminexTabIndex = 0;
        }

        luminexWebView.loadUrl(LUMINEX_HOME);
    }


    // DAN_LUMINEX_PAGE_UPLOAD_V1
    private void sendLuminexPageState(WebView source) {
        sendLuminexPageState(source, null);
    }

    private void sendLuminexPageState(WebView source, Runnable completion) {
        if (source == null) {
            if (completion != null) completion.run();
            return;
        }

        String script =
                "(function(){"
                + "try{"
                + "var body=document.body;"
                + "var text=body?(body.innerText||''):'';"
                + "var links=Array.from(document.querySelectorAll('a[href]'))"
                + ".slice(0,200).map(function(a,i){"
                + "return {index:i+1,"
                + "text:String(a.innerText||a.getAttribute('aria-label')||a.title||'').trim().slice(0,300),"
                + "url:String(a.href||'')};"
                + "}).filter(function(link){return /^https?:\\/\\//i.test(link.url);});"
                + "return JSON.stringify({"
                + "url:String(location.href||''),"
                + "title:String(document.title||''),"
                + "text:String(text).slice(0,30000),"
                + "links:links"
                + "});"
                + "}catch(e){return null;}"
                + "})()";

        source.evaluateJavascript(script, value -> {
            if (value == null
                    || "null".equals(value)
                    || "\"null\"".equals(value)) {
                if (completion != null) completion.run();
                return;
            }

            try {
                String decoded =
                        new org.json.JSONArray(
                                "[" + value + "]"
                        ).getString(0);

                JSONObject state =
                        new JSONObject(decoded);

                String url = state.optString("url", "");

                if (!isLuminexHttpUrl(url)) {
                    return;
                }

                String luminexDeviceId = Settings.Secure.getString(
                        getContentResolver(),
                        Settings.Secure.ANDROID_ID
                );
                if (!TextUtils.isEmpty(luminexDeviceId)) {
                    state.put("luminexDevice", luminexDeviceId);
                }

                if (source == getActiveLuminexWebView()) {
                    state.put(
                            "luminexSession",
                            activeLuminexSessionLabel()
                    );
                    state.put(
                            "luminexTab",
                            getActiveLuminexTabIndex() + 1
                    );
                    state.put(
                            "luminexTabCount",
                            getActiveLuminexTabs().size()
                    );
                }

                lastLuminexContext = state.toString();
                syncLuminexContextToChatGpt();

                final String pageTitle = state.optString("title", "");
                runOnUiThread(() -> {
                    if (statusText != null) {
                        statusText.setText(
                                "👁 Luminex letto: "
                                        + (TextUtils.isEmpty(pageTitle)
                                        ? "(senza titolo)"
                                        : pageTitle)
                        );
                    }
                });

                if (!TextUtils.isEmpty(BuildConfig.LUMINEX_DEVICE_TOKEN)) {
                    postLuminexPageState(lastLuminexContext);
                }

            } catch (Exception error) {
                android.util.Log.w(
                        "DanLuminex",
                        "Lettura pagina non riuscita",
                        error
                );
            } finally {
                if (completion != null) completion.run();
            }
        });
    }

    private void postLuminexPageState(String payload) {
        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL endpoint = new URL(
                        "https://luminex-ai-control."
                                + "dimitri-azzarone.workers.dev"
                                + "/device/page-state"
                );

                connection =
                        (HttpURLConnection)
                                endpoint.openConnection();

                connection.setRequestMethod("POST");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setUseCaches(false);
                connection.setDoOutput(true);

                connection.setRequestProperty(
                        "Authorization",
                        "Bearer "
                                + BuildConfig
                                .LUMINEX_DEVICE_TOKEN
                );

                connection.setRequestProperty(
                        "Content-Type",
                        "application/json; charset=utf-8"
                );

                byte[] data = payload.getBytes(
                        StandardCharsets.UTF_8
                );

                connection.setFixedLengthStreamingMode(
                        data.length
                );

                try (java.io.OutputStream output =
                             connection.getOutputStream()) {
                    output.write(data);
                }

                int status = connection.getResponseCode();

                if (status < 200 || status >= 300) {
                    android.util.Log.w(
                            "DanLuminex",
                            "Invio pagina: HTTP " + status
                    );
                }

            } catch (Exception error) {
                android.util.Log.w(
                        "DanLuminex",
                        "Invio pagina non riuscito",
                        error
                );
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "Dan-Luminex-Page").start();
    }

    private void toggleLuminexView() {
        WebView activeLuminex = getActiveLuminexWebView();

        if (luminexVisible && activeLuminex != null) {
            sendLuminexPageState(activeLuminex, () -> setLuminexVisible(false));
            return;
        }

        setLuminexVisible(true);
    }

    private void setLuminexVisible(boolean visible) {
        if (visible && webVoiceRequested) stopWebVoice("LIVE chiuso · Luminex aperto");
        if (visible && liveEnabled) stopLiveMode("Live fermato · Luminex aperto");
        luminexVisible = visible;
        updateLuminexAddress();

        if (webView != null) {
            webView.setVisibility(luminexVisible ? View.GONE : View.VISIBLE);
        }

        if (luminexVisible) showActiveLuminexTab();
        else hideAllLuminexTabs();

        if (luminexButton != null) luminexButton.setText(luminexVisible ? "D" : "L");

        if (privateButton != null) {
            privateButton.setVisibility(
                    luminexVisible ? View.VISIBLE : View.GONE
            );
            privateButton.setText("P");
        }

        if (statusText != null) {
            if (!luminexVisible) {
                statusText.setText("Dan visibile — premi L per aprire Luminex AI");
            } else {
                statusText.setText(
                        "Luminex " + activeLuminexSessionLabel()
                                + " — scheda " + (getActiveLuminexTabIndex() + 1)
                                + "/" + getActiveLuminexTabs().size()
                );
            }
        }
    }



    private void startLuminexPolling() {
        if (TextUtils.isEmpty(
                BuildConfig.LUMINEX_DEVICE_TOKEN
        )) {
            android.util.Log.w(
                    "DanLuminex",
                    "LUMINEX_DEVICE_TOKEN non configurato"
            );
            return;
        }

        luminexPolling = true;
        luminexHandler.removeCallbacks(
                luminexPollRunnable
        );
        luminexHandler.post(luminexPollRunnable);
        if (luminexVisible) {
            luminexHandler.removeCallbacks(luminexSnapshotRunnable);
            luminexHandler.postDelayed(luminexSnapshotRunnable, 1000L);
        }
    }

    private void stopLuminexPolling() {
        luminexPolling = false;
        luminexHandler.removeCallbacks(
                luminexPollRunnable
        );
        luminexHandler.removeCallbacks(luminexSnapshotRunnable);
        luminexSnapshotInFlight = false;
    }

    private void pollNextLuminexCommand() {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(LUMINEX_CONTROL_URL)
                            .openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);

            connection.setRequestProperty(
                    "Authorization",
                    "Bearer "
                            + BuildConfig.LUMINEX_DEVICE_TOKEN
            );

            connection.setRequestProperty(
                    "Accept",
                    "application/json"
            );

            String luminexDeviceId = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
            if (!TextUtils.isEmpty(luminexDeviceId)) {
                connection.setRequestProperty(
                        "X-Luminex-Device",
                        luminexDeviceId
                );
            }

            int status = connection.getResponseCode();

            if (status ==
                    HttpURLConnection.HTTP_NO_CONTENT) {
                return;
            }

            if (status != HttpURLConnection.HTTP_OK) {
                android.util.Log.w(
                        "DanLuminex",
                        "Server HTTP " + status
                );
                return;
            }

            String body = readLuminexUtf8(
                    connection.getInputStream()
            );

            JSONObject command = new JSONObject(body);
            String action =
                    command.optString("action", "");
            String targetUrl =
                    command.optString("url", null);
            String targetSession =
                    command.optString("session", "");
            int targetTab =
                    command.optInt("tab", 0);

            runOnUiThread(
                    () -> executeLuminexCommand(
                            action,
                            targetUrl,
                            targetSession,
                            targetTab
                    )
            );

        } catch (Exception error) {
            android.util.Log.w(
                    "DanLuminex",
                    "Polling non riuscito",
                    error
            );
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readLuminexUtf8(
            InputStream input
    ) throws Exception {
        StringBuilder result =
                new StringBuilder();

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     input,
                                     StandardCharsets.UTF_8
                             )
                     )) {
            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }

        return result.toString();
    }

    private boolean isLuminexHttpUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;

        try {
            String scheme = Uri.parse(url).getScheme();

            return "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void selectLuminexTarget(
            String requestedSession,
            int requestedTab
    ) {
        if (webView != null) {
            updateActiveDanChat(webView.getUrl());
        }

        String session = requestedSession == null
                ? ""
                : requestedSession.trim().toUpperCase(Locale.ROOT);

        if ("MAIN".equals(session)) {
            privateVisible = false;
        } else if (
                "P".equals(session)
                        || "P1".equals(session)
                        || "P2".equals(session)
        ) {
            if (TextUtils.isEmpty(activeDanChatKey)) {
                if (statusText != null) {
                    statusText.setText(
                            "Luminex: nessuna chat Dan identificata"
                    );
                }
                return;
            }

            privateVisible = true;
        }

        luminexVisible = true;

        if (webView != null) {
            webView.setVisibility(View.GONE);
        }

        List<WebView> tabs = getActiveLuminexTabs();

        if (tabs.isEmpty()) {
            createLuminexTabForSession(
                    privateVisible ? 1 : 0
            );
            tabs = getActiveLuminexTabs();
        }

        int tabNumber = requestedTab > 0
                ? requestedTab
                : 1;

        while (tabs.size() < tabNumber) {
            createLuminexTabForSession(
                    privateVisible ? 1 : 0
            );
            tabs = getActiveLuminexTabs();
        }

        int index = Math.max(
                0,
                Math.min(tabNumber - 1, tabs.size() - 1)
        );

        setActiveLuminexTabIndex(index);
        showActiveLuminexTab();

        if (luminexButton != null) {
            luminexButton.setText("D");
        }

        if (privateButton != null) {
            privateButton.setVisibility(View.VISIBLE);
            privateButton.setText("P");
        }

        if (statusText != null) {
            statusText.setText(
                    "Luminex "
                            + activeLuminexSessionLabel()
                            + " — scheda "
                            + (index + 1)
                            + "/"
                            + tabs.size()
            );
        }
    }


    private void executeLuminexCommand(
            String action,
            String targetUrl,
            String targetSession,
            int targetTab
    ) {
        if (!TextUtils.isEmpty(targetSession) || targetTab > 0) {
            selectLuminexTarget(
                    targetSession,
                    targetTab
            );
        }

        WebView activeLuminex = getActiveLuminexWebView();
        if (activeLuminex == null) return;

        switch (action) {
            case "open":
                if (!isLuminexHttpUrl(targetUrl)) {
                    statusText.setText(
                            "Luminex AI: URL non valido"
                    );
                    return;
                }

                activeLuminex.loadUrl(targetUrl);
                break;

            case "new-tab":
                newLuminexTab();
                break;

            case "next-tab":
                nextLuminexTab();
                break;

            case "previous-tab":
                previousLuminexTab();
                break;

            case "close-tab":
                closeActiveLuminexTab();
                break;

            case "back":
                if (activeLuminex.canGoBack()) {
                    activeLuminex.goBack();
                }
                break;

            case "forward":
                if (activeLuminex.canGoForward()) {
                    activeLuminex.goForward();
                }
                break;

            case "reload":
                activeLuminex.reload();
                break;

            case "home":
                activeLuminex.loadUrl(LUMINEX_HOME);
                break;

            case "open-private":
                if (!privateVisible) {
                    openPrivateSession();
                } else {
                    setLuminexVisible(true);
                }
                break;

            case "close-private":
                if (privateVisible) {
                    closePrivateSession();
                } else {
                    setLuminexVisible(true);
                }
                break;

            case "load-full-chat":
                loadFullLuminexPage(false);
                return;

            case "print-page-pdf":
                printLuminexPagePdf(getActiveLuminexWebView());
                return;

            case "export-chat-pdf":
                loadFullLuminexPage(true);
                return;

            case "scroll-up":
                scrollLuminex("up");
                return;

            case "scroll-down":
                scrollLuminex("down");
                return;

            case "scroll-top":
                scrollLuminex("top");
                return;

            case "scroll-bottom":
                scrollLuminex("bottom");
                return;

            case "click-text":
                clickLuminexText(targetUrl);
                break;

            case "type-text":
                typeLuminexText(targetUrl);
                break;

            case "send-confirmed":
                confirmAndSendLuminexMessage();
                break;

            default:
                android.util.Log.w(
                        "DanLuminex",
                        "Comando sconosciuto: " + action
                );
                return;
        }

        statusText.setText(
                "Luminex AI ha eseguito: " + action
        );
    }

    private void scrollLuminex(String direction) {
        WebView active = getActiveLuminexWebView();

        if (active == null) {
            if (statusText != null) {
                statusText.setText(
                        "Luminex: nessuna pagina attiva"
                );
            }
            return;
        }

        String safeDirection =
                direction == null
                        ? ""
                        : direction.trim().toLowerCase(Locale.ROOT);

        String script =
                "(function(){"
                + " const dir='" + safeDirection + "';"
                + " const all=["
                + "   document.scrollingElement,"
                + "   document.documentElement,"
                + "   document.body"
                + " ].concat(Array.from(document.querySelectorAll('*')))"
                + "  .filter(Boolean);"

                + " let best=null;"
                + " let bestScore=-1;"

                + " for(const e of all){"
                + "  try{"
                + "   const sh=e.scrollHeight||0;"
                + "   const ch=e.clientHeight||0;"
                + "   const overflow=sh-ch;"
                + "   if(overflow<=80)continue;"

                + "   const style=getComputedStyle(e);"
                + "   const oy=(style.overflowY||'').toLowerCase();"
                + "   const scrollable="
                + "     oy==='auto'||oy==='scroll'||oy==='overlay'"
                + "     || e===document.scrollingElement"
                + "     || e===document.documentElement"
                + "     || e===document.body;"

                + "   if(!scrollable)continue;"

                + "   const r=e.getBoundingClientRect"
                + "     ? e.getBoundingClientRect()"
                + "     : {width:0,height:0};"

                + "   const visibleArea="
                + "     Math.max(1,r.width||1)"
                + "     * Math.max(1,r.height||ch||1);"

                + "   const score=overflow+visibleArea;"
                + "   if(score>bestScore){"
                + "    bestScore=score;"
                + "    best=e;"
                + "   }"
                + "  }catch(x){}"
                + " }"

                + " if(!best){"
                + "  best=document.scrollingElement"
                + "   || document.documentElement"
                + "   || document.body;"
                + " }"

                + " if(!best)return 'NO_SCROLL_TARGET';"

                + " const before=best.scrollTop||0;"
                + " const max=Math.max("
                + "   0,"
                + "   (best.scrollHeight||0)-(best.clientHeight||0)"
                + " );"

                + " const step=Math.max("
                + "   300,"
                + "   Math.floor((best.clientHeight||window.innerHeight||600)*0.72)"
                + " );"

                + " let target=before;"

                + " if(dir==='up') target=Math.max(0,before-step);"
                + " else if(dir==='down') target=Math.min(max,before+step);"
                + " else if(dir==='top') target=0;"
                + " else if(dir==='bottom') target=max;"
                + " else return 'BAD_DIRECTION';"

                + " try{"
                + "  best.scrollTo({top:target,behavior:'smooth'});"
                + " }catch(x){"
                + "  try{best.scrollTop=target;}catch(y){}"
                + " }"

                + " if(best===document.body"
                + "   || best===document.documentElement"
                + "   || best===document.scrollingElement){"
                + "  try{window.scrollTo({top:target,behavior:'smooth'});}catch(x){}"
                + " }"

                + " return JSON.stringify({"
                + "  ok:true,"
                + "  before:before,"
                + "  target:target,"
                + "  max:max,"
                + "  direction:dir"
                + " });"
                + "})()";

        active.evaluateJavascript(
                script,
                result -> runOnUiThread(() -> {
                    if (statusText == null) return;

                    if (result == null
                            || result.contains("NO_SCROLL_TARGET")) {

                        statusText.setText(
                                "Luminex: area scorrevole non trovata"
                        );

                    } else if (result.contains("BAD_DIRECTION")) {

                        statusText.setText(
                                "Luminex: direzione scroll non valida"
                        );

                    } else {

                        String label;

                        switch (safeDirection) {
                            case "up":
                                label = "scorrimento verso l'alto";
                                break;

                            case "down":
                                label = "scorrimento verso il basso";
                                break;

                            case "top":
                                label = "inizio pagina";
                                break;

                            case "bottom":
                                label = "fine pagina";
                                break;

                            default:
                                label = "scroll";
                                break;
                        }

                        statusText.setText(
                                "Luminex: " + label
                        );

                        active.postDelayed(
                                () -> sendLuminexPageState(active),
                                500L
                        );
                    }
                })
        );
    }


    private void loadFullLuminexPage(boolean printAfterLoad) {
        WebView active = getActiveLuminexWebView();

        if (active == null) {
            statusText.setText("Luminex: nessuna pagina attiva");
            return;
        }

        if (fullChatLoading) {
            statusText.setText("Luminex: caricamento pagina già in corso");
            return;
        }

        fullChatLoading = true;
        fullChatPrintAfterLoad = printAfterLoad;
        fullChatStableRounds = 0;
        fullChatAttempts = 0;
        fullChatLastHeight = -1L;
        fullChatLastMessageCount = -1;
        fullChatWebView = active;

        statusText.setText("Luminex: sto caricando tutta la pagina…");
        luminexHandler.post(this::stepLoadFullLuminexPage);
    }

    private void stepLoadFullLuminexPage() {
        if (!fullChatLoading || fullChatWebView == null) return;

        String script =
                "(function(){" +
                " const candidates=[document.scrollingElement,document.documentElement,document.body]" +
                "  .concat(Array.from(document.querySelectorAll('*'))).filter(Boolean);" +
                " let best=document.scrollingElement||document.documentElement||document.body;" +
                " let maxHeight=0;" +
                " for(const e of candidates){" +
                "  try{" +
                "   const sh=e.scrollHeight||0;" +
                "   const ch=e.clientHeight||0;" +
                "   if(sh-ch>200 && sh>maxHeight){maxHeight=sh;best=e;}" +
                "  }catch(x){}" +
                " }" +
                " try{best.scrollTop=0;}catch(x){}" +
                " try{best.scrollTo(0,0);}catch(x){}" +
                " try{window.scrollTo(0,0);}catch(x){}" +
                " const h=(best&&best.scrollHeight)||document.documentElement.scrollHeight||0;" +
                " const top=(best&&best.scrollTop)||window.scrollY||0;" +
                " const msgs=document.querySelectorAll('[data-message-author-role]').length;" +
                " return [h,top,msgs];" +
                "})()";

        fullChatWebView.evaluateJavascript(script, result -> {
            if (!fullChatLoading) return;

            long height = -1L;
            long top = -1L;
            int messageCount = -1;

            try {
                org.json.JSONArray info = new org.json.JSONArray(result);
                height = info.optLong(0, -1L);
                top = info.optLong(1, -1L);
                messageCount = info.optInt(2, -1);
            } catch (Exception ignored) {}

            fullChatAttempts++;

            boolean sameHeight =
                    height > 0L
                            && height == fullChatLastHeight;

            boolean sameMessages =
                    messageCount >= 0
                            && messageCount == fullChatLastMessageCount;

            boolean atTop = top >= 0L && top <= 2L;

            if (sameHeight && sameMessages && atTop) {
                fullChatStableRounds++;
            } else {
                fullChatStableRounds = 0;
            }

            fullChatLastHeight = height;
            fullChatLastMessageCount = messageCount;

            if (statusText != null) {
                String detail = messageCount >= 0
                        ? " — messaggi caricati: " + messageCount
                        : "";
                statusText.setText(
                        "Luminex: risalgo la chat"
                                + detail
                                + " — controllo "
                                + fullChatAttempts
                );
            }

            if (fullChatStableRounds >= 5 || fullChatAttempts >= 180) {
                finishLoadFullLuminexPage();
            } else {
                luminexHandler.postDelayed(
                        this::stepLoadFullLuminexPage,
                        1500L
                );
            }
        });
    }

    private void finishLoadFullLuminexPage() {
        boolean printAfter = fullChatPrintAfterLoad;
        WebView loadedView = fullChatWebView;

        fullChatLoading = false;
        fullChatPrintAfterLoad = false;
        fullChatWebView = null;

        if (statusText != null) {
            String detail = fullChatLastMessageCount >= 0
                    ? " (" + fullChatLastMessageCount + " messaggi rilevati)"
                    : "";
            statusText.setText(
                    "Luminex: pagina caricata fino all'inizio" + detail
            );
        }

        if (printAfter && loadedView != null) {
            luminexHandler.postDelayed(
                    () -> printLuminexPagePdf(loadedView),
                    1200L
            );
        }
    }

    private void printLuminexPagePdf(WebView source) {
        if (source == null) {
            statusText.setText("Luminex: nessuna pagina da salvare");
            return;
        }

        try {
            String title = source.getTitle();

            if (TextUtils.isEmpty(title)) {
                title = "Dan Luminex Chat";
            }

            title = title
                    .replace('/', '-')
                    .replace('\\', '-')
                    .replace(':', '-')
                    .trim();

            if (title.length() > 80) {
                title = title.substring(0, 80);
            }

            PrintManager printManager =
                    (PrintManager) getSystemService(Context.PRINT_SERVICE);

            if (printManager == null) {
                statusText.setText("Luminex: servizio di stampa non disponibile");
                return;
            }

            PrintDocumentAdapter adapter =
                    source.createPrintDocumentAdapter(title);

            PrintAttributes attributes =
                    new PrintAttributes.Builder().build();

            printManager.print(
                    title,
                    adapter,
                    attributes
            );

            statusText.setText(
                    "Luminex: scegli Salva come PDF"
            );

        } catch (Exception error) {
            android.util.Log.e(
                    "DanLuminex",
                    "Stampa PDF non riuscita",
                    error
            );

            statusText.setText(
                    "Luminex: impossibile aprire il salvataggio PDF"
            );
        }
    }

    private void clickLuminexText(String text) {
        WebView activeLuminex = getActiveLuminexWebView();
        if (activeLuminex == null || TextUtils.isEmpty(text)) return;
        String script = "(function(){\n const wanted=" + org.json.JSONObject.quote(text) + ".trim().replace(/\\s+/g,' ').toLocaleLowerCase();\n if(!wanted)return 'EMPTY';\n const roots=[], seen=new Set();\n function visit(root){\n  if(!root||seen.has(root))return; seen.add(root);roots.push(root);\n  for(const e of root.querySelectorAll('*')){\n   if(e.shadowRoot)visit(e.shadowRoot);\n   if(e.tagName==='IFRAME'){try{visit(e.contentDocument);}catch(ignore){}}\n  }\n }\n visit(document);\n const norm=s=>(s||'').trim().replace(/\\s+/g,' ').toLocaleLowerCase();\n const matches=[];\n for(const root of roots)for(const e of root.querySelectorAll('button,a[href],input[type=submit],input[type=button],[role=button],[role=link],[role=menuitem],[role=tab],[onclick],[tabindex]')){\n  const win=e.ownerDocument.defaultView, style=win.getComputedStyle(e), rect=e.getBoundingClientRect();\n  if(!rect.width||!rect.height||style.visibility==='hidden'||style.display==='none'||e.closest('[hidden],[inert]'))continue;\n  const ids=(e.getAttribute('aria-labelledby')||'').split(/\\s+/);\n  const labelled=ids.map(id=>root.getElementById?root.getElementById(id):null).filter(Boolean).map(n=>n.textContent).join(' ');\n  const labels=[e.getAttribute('aria-label'),labelled,e.title,e.innerText,e.value].map(norm).filter(Boolean);\n  let score=labels.some(t=>t===wanted)?2:labels.some(t=>t.includes(wanted))?1:0;\n  if(score)matches.push({e,score});\n }\n if(!matches.length)return 'NOT_FOUND';\n const best=Math.max(...matches.map(m=>m.score));\n let chosen=matches.filter(m=>m.score===best);\n chosen=chosen.filter(m=>!chosen.some(n=>n!==m&&m.e.contains(n.e)));\n if(chosen.length!==1)return 'AMBIGUOUS';\n const el=chosen[0].e;\n if(el.disabled||el.getAttribute('aria-disabled')==='true'||el.matches(':disabled'))return 'DISABLED';\n el.scrollIntoView({block:'center',inline:'center'});\n el.focus();el.click();return 'CLICKED';\n})()\n";
        activeLuminex.evaluateJavascript(script, result -> {
            if (isFinishing() || isDestroyed()) return;
            String message;
            if ("\"CLICKED\"".equals(result)) message = "Luminex: clic eseguito su " + text;
            else if ("\"AMBIGUOUS\"".equals(result)) message = "Luminex: più pulsanti corrispondono; usa un'etichetta più precisa";
            else if ("\"DISABLED\"".equals(result)) message = "Luminex: pulsante disabilitato";
            else message = "Luminex: pulsante non trovato nella pagina accessibile";
            statusText.setText(message);
            activeLuminex.postDelayed(() -> sendLuminexPageState(activeLuminex), 1500L);
        });
    }

    private void typeLuminexText(String text) {
        WebView activeLuminex = getActiveLuminexWebView();
        if (activeLuminex == null || text == null) return;
        String script = "(function(){\n const text=" + org.json.JSONObject.quote(text) + ",roots=[],seen=new Set();\n function visit(root){if(!root||seen.has(root))return;seen.add(root);roots.push(root);for(const e of root.querySelectorAll('*')){if(e.shadowRoot)visit(e.shadowRoot);if(e.tagName==='IFRAME'){try{visit(e.contentDocument);}catch(ignore){}}}}\n visit(document);\n const editable=e=>e&&(e.isContentEditable||e.tagName==='TEXTAREA'||(e.tagName==='INPUT'&&/^(text|search|url|email|tel|number|password)$/.test(e.type)))&&!e.disabled&&!e.readOnly;\n const visible=e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&!e.closest('[hidden],[inert]')&&e.ownerDocument.defaultView.getComputedStyle(e).visibility!=='hidden';};\n let el=null;\n for(const root of roots){let a=root.activeElement;if(editable(a)&&visible(a))el=a;}\n if(!el){const fields=[];for(const root of roots)for(const e of root.querySelectorAll('textarea,input,[contenteditable=true]'))if(editable(e)&&visible(e)&&!fields.includes(e))fields.push(e);if(fields.length!==1)return fields.length?'AMBIGUOUS':'NOT_FOUND';el=fields[0];}\n const win=el.ownerDocument.defaultView;el.focus();\n if(el.isContentEditable){el.textContent=text;}else{\n  const proto=el.tagName==='TEXTAREA'?win.HTMLTextAreaElement.prototype:win.HTMLInputElement.prototype;\n  Object.getOwnPropertyDescriptor(proto,'value').set.call(el,text);\n }\n el.dispatchEvent(new win.InputEvent('input',{bubbles:true,composed:true,inputType:'insertText',data:text}));\n el.dispatchEvent(new win.Event('change',{bubbles:true}));return 'TYPED';\n})()\n";
        activeLuminex.evaluateJavascript(script, result -> {
            if (isFinishing() || isDestroyed()) return;
            if ("\"TYPED\"".equals(result)) statusText.setText("Luminex: testo inserito nel campo");
            else if ("\"AMBIGUOUS\"".equals(result)) statusText.setText("Luminex: seleziona prima il campo da compilare");
            else statusText.setText("Luminex: campo modificabile non trovato");
            activeLuminex.postDelayed(() -> sendLuminexPageState(activeLuminex), 800L);
        });
    }

    private void confirmAndSendLuminexMessage() {
        if (getActiveLuminexWebView() == null) return;

        new AlertDialog.Builder(this)
                .setTitle("Conferma invio")
                .setMessage("Inviare il messaggio preparato nella pagina corrente?")
                .setNegativeButton("Annulla", (dialog, which) -> {
                    statusText.setText("Luminex: invio annullato");
                })
                .setPositiveButton("Invia", (dialog, which) -> {
                    sendPreparedLuminexMessage();
                })
                .show();
    }

    private void sendPreparedLuminexMessage() {
        WebView activeLuminex = getActiveLuminexWebView();
        if (activeLuminex == null) return;

        String script =
                "(function(){" +
                " const footer=document.querySelector('footer')||document;" +
                " const buttons=Array.from(footer.querySelectorAll('button,[role=button]'));" +
                " const btn=buttons.find(b=>{" +
                "  const a=((b.getAttribute('aria-label')||'')+' '+" +
                "   (b.getAttribute('data-testid')||'')+' '+" +
                "   (b.title||'')).toLowerCase();" +
                "  return a.includes('send')||a.includes('invia')||" +
                "   a.includes('compose-btn-send');" +
                " });" +
                " if(!btn)return 'NOT_FOUND';" +
                " if(btn.disabled)return 'DISABLED';" +
                " btn.click();" +
                " return 'SENT';" +
                "})()";

        activeLuminex.evaluateJavascript(script, result -> {
            runOnUiThread(() -> {
                if (result != null && result.contains("SENT")) {
                    statusText.setText("Luminex: messaggio inviato");
                } else if (result != null && result.contains("DISABLED")) {
                    statusText.setText("Luminex: pulsante invio non disponibile");
                } else {
                    statusText.setText("Luminex: pulsante invio non trovato");
                }
            });
        });
    }

    private void composeLuminexMessage(String recipient, String message) {
        if (getActiveLuminexWebView() == null
                || TextUtils.isEmpty(recipient)
                || TextUtils.isEmpty(message)) {
            return;
        }

        clickLuminexText(recipient);

        luminexHandler.postDelayed(() -> {
            typeLuminexText(message);

            luminexHandler.postDelayed(() -> {
                confirmAndSendLuminexMessage();
            }, 700L);

        }, 900L);
    }

    private void installDanDocumentStartBranding() {
        if (webView == null || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        try {
            WebViewCompat.addDocumentStartJavaScript(webView, voiceAsset("dan-web-voice.js"),
                    Collections.singleton("https://chatgpt.com"));
            WebViewCompat.addDocumentStartJavaScript(webView, voiceAsset("dan-composer-hint.js"),
                    Collections.singleton("https://chatgpt.com"));
        } catch (Exception ignored) {}
    }

    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(3, 13, 26));
        webView.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) lastWebVoiceTouch = android.os.SystemClock.uptimeMillis();
            return false;
        });
        webView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) lastWebVoiceTouch = android.os.SystemClock.uptimeMillis();
            return false;
        });
        webView.addJavascriptInterface(new NativeBridge(), "AndroidRadio");
        installDanDocumentStartBranding();

        webContainer.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        WebSettings settings = webView.getSettings();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK))
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        }

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        configureDownloads(webView);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    long sinceTouch = android.os.SystemClock.uptimeMillis() - lastWebVoiceTouch;
                    boolean explicitPageTap = lastWebVoiceTouch != 0L && sinceTouch >= 0L && sinceTouch <= 5000L;
                    boolean allowed = foreground && (webVoiceRequested || explicitPageTap) && webView != null
                            && trustedVoiceOrigin(webView.getUrl())
                            && trustedVoiceOrigin(request.getOrigin().toString())
                            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                    boolean asksAudio = java.util.Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE);
                    if (allowed && asksAudio) {
                        if (!webVoiceRequested) {
                            if (liveEnabled) stopLiveMode("LIVE chiuso · dettatura della pagina");
                            SpeechRecognizer previous = speechRecognizer;
                            speechRecognizer = null;
                            if (previous != null) {
                                try { previous.cancel(); } catch (Exception ignored) {}
                                try { previous.destroy(); } catch (Exception ignored) {}
                            }
                            handsFreeEnabled = false;
                            recognizerSessionActive = false;
                            listening = false;
                            manualCapture = false;
                            recordingLocked = false;
                            headsetRecording = false;
                            pressToTalkRequested = false;
                            manualStopPending = false;
                            if (tts != null) tts.stop();
                            ttsSpeaking = false;
                            applyMicStyle(false);
                        }
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    }
                    else request.deny();
                });
            }

            @Override
            public boolean onCreateWindow(
                    WebView view,
                    boolean isDialog,
                    boolean isUserGesture,
                    Message resultMsg
            ) {
                closePopupWebView();

                popupWebView = new WebView(MainActivity.this);
                configureDownloads(popupWebView);
                WebSettings popupSettings = popupWebView.getSettings();
                popupSettings.setJavaScriptEnabled(true);
                popupSettings.setDomStorageEnabled(true);
                popupSettings.setDatabaseEnabled(true);
                popupSettings.setAllowFileAccess(true);
                popupSettings.setAllowContentAccess(true);
                popupSettings.setSupportMultipleWindows(true);
                popupSettings.setJavaScriptCanOpenWindowsAutomatically(true);
                popupSettings.setMediaPlaybackRequiresUserGesture(false);
                popupSettings.setSupportZoom(true);
                popupSettings.setBuiltInZoomControls(true);
                popupSettings.setDisplayZoomControls(false);
                popupSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    popupSettings.setSafeBrowsingEnabled(true);
                }

                popupWebView.setFocusable(true);
                popupWebView.setFocusableInTouchMode(true);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    popupWebView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
                }

                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                cookieManager.setAcceptThirdPartyCookies(popupWebView, true);

                popupWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(
                            WebView view,
                            WebResourceRequest request
                    ) {
                        Uri uri = request.getUrl();
                        if (uri == null) return false;

                        String scheme = uri.getScheme();
                        if ("http".equalsIgnoreCase(scheme)
                                || "https".equalsIgnoreCase(scheme)) {
                            return false;
                        }

                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW, uri));
                            return true;
                        } catch (Exception ignored) {
                            return false;
                        }
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
                    }
                });

                popupWebView.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public void onCloseWindow(WebView window) {
                        closePopupWebView();
                        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
                        if (webView != null) webView.reload();
                    }
                });

                webContainer.addView(
                        popupWebView,
                        new FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                        )
                );

                WebView.WebViewTransport transport =
                        (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popupWebView);
                resultMsg.sendToTarget();

                statusText.setText("Accesso browser dentro Dan");
                return true;
            }

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

                attachmentGeneration++;
                MainActivity.this.filePathCallback = filePathCallback;
                Uri[] recovered = recoveredDocumentsFor(webView.getUrl());
                if (recovered != null && recovered.length > 0) {
                    recordAttachmentPhase("Recupero del documento dopo riapertura", true);
                    deliverSystemDocuments(recovered, null);
                    return true;
                }
                getSharedPreferences("dan_upload_recovery", MODE_PRIVATE).edit()
                        .putString("target", webView.getUrl()).putBoolean("pickerPending", true).remove("documents").commit();
                recordAttachmentPhase("Selettore documenti aperto", true);
                startAttachmentProtection();

                try {
                    // Respect the file types and multiple-selection mode requested by ChatGPT.
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    Intent requested = fileChooserParams == null ? null : fileChooserParams.createIntent();
                    if (requested != null) {
                        if (requested.getType() != null) intent.setType(requested.getType());
                        String[] types = requested.getStringArrayExtra(Intent.EXTRA_MIME_TYPES);
                        if (types != null) intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
                    }
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    if (intent.getType() == null || intent.getType().trim().isEmpty()) {
                        intent.setType("*/*");
                    }
                    if (fileChooserParams != null) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                                fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                    }
                    try {
                        startActivityForResult(intent, REQ_FILE_CHOOSER);
                    } catch (android.content.ActivityNotFoundException missingDocuments) {
                        intent.setAction(Intent.ACTION_GET_CONTENT);
                        startActivityForResult(intent, REQ_FILE_CHOOSER);
                    }
                    return true;
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    filePathCallback.onReceiveValue(null);
                    getSharedPreferences("dan_upload_recovery", MODE_PRIVATE).edit().putBoolean("pickerPending", false).commit();
                    stopAttachmentProtection();
                    recordAttachmentPhase("Errore apertura selettore: " + e.getClass().getSimpleName(), false);
                    android.util.Log.e("DanFileUpload", "File picker failed", e);
                    Toast.makeText(MainActivity.this,
                            "Impossibile aprire il selettore file", Toast.LENGTH_LONG).show();
                    return true;
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
                if (webVoiceRequested) stopWebVoice("LIVE chiuso · pagina cambiata");
                lastUrl = url;
                memoryPageUrl = url;
                updateActiveDanChat(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                lastUrl = url;
                memoryPageUrl = url;
                updateActiveDanChat(url);
                injectPageBehaviors();
                try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
            }

            @Override
            public boolean onRenderProcessGone(
                    WebView view,
                    RenderProcessGoneDetail detail
            ) {
                attachmentGeneration++;
                getSharedPreferences("dan_upload_diagnostics", MODE_PRIVATE).edit()
                        .putString("rendererExit", "crash=" + detail.didCrash() + "; priorità=" + detail.rendererPriorityAtExit())
                        .putLong("rendererTime", System.currentTimeMillis()).commit();
                recordAttachmentPhase("WebView terminato: " + (detail.didCrash() ? "crash" : "processo chiuso da Android"), false);
                if (filePathCallback != null) {
                    ValueCallback<Uri[]> pending = filePathCallback;filePathCallback=null;
                    try { pending.onReceiveValue(null); } catch (RuntimeException ignored) {}
                }
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

    private void closePopupWebView() {
        if (popupWebView == null) return;

        try {
            webContainer.removeView(popupWebView);
            popupWebView.stopLoading();
            popupWebView.loadUrl("about:blank");
            popupWebView.removeAllViews();
            popupWebView.destroy();
        } catch (Exception ignored) {
        }

        popupWebView = null;
    }

    private void chooseMemoryDestination(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-ndjson");
        intent.putExtra(Intent.EXTRA_TITLE, "Dan-memory.jsonl");
        startActivityForResult(intent, requestCode);
    }

    private void showEngineMenu() {
        final String[] engines = {
                "ChatGPT · attivo",
                "Claude · da collegare",
                "Grok · da collegare",
                "Gemini · da collegare",
                "Monica · da collegare",
                "Altri servizi · da collegare"
        };
        new AlertDialog.Builder(this)
                .setTitle("Motore di Dan")
                .setItems(engines, (dialog, selected) -> {
                    if (selected == 0) {
                        Toast.makeText(this, "ChatGPT è il motore attivo", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String details = selected >= 5
                            ? "Per usarlo senza rete bisogna verificare modello, licenza e "
                                    + "prestazioni sul telefono e sul tablet. Dan continuerà a "
                                    + "usare ChatGPT finché la prova locale non funzionerà."
                            : "Questo motore richiede un collegamento API verificato. "
                                    + "Dan continuerà a usare ChatGPT finché il collegamento "
                                    + "non sarà pronto.";
                    new AlertDialog.Builder(this)
                            .setTitle(engines[selected].split(" · ")[0])
                            .setMessage(details + " La memoria e la voce restano di Dan.")
                            .setPositiveButton("OK", null)
                            .show();
                })
                .setNegativeButton("Chiudi", null)
                .show();
    }

    private void showMemoryMenu() {
        final String[] actions = {
                "Importa storico Dan (ZIP preparato)",
                "Ripristina messaggi (JSONL)",
                "Esporta messaggi (JSONL)",
                "Attiva backup automatico su un file",
                "Collega cartella Memoria Dan",
                "Sincronizza cartella collegata",
                "Conta chat archiviate",
                "Collega Tutta la mia storia o quasi (lettura)",
                "Aggiorna documenti autobiografici",
                "Carica una cartella su Drive"
        };
        new AlertDialog.Builder(this)
                .setTitle("Memoria di Dan")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0 || which == 1) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType(which == 0 ? "application/zip" : "*/*");
                        startActivityForResult(intent,
                                which == 0 ? REQ_HISTORY_IMPORT : REQ_MEMORY_IMPORT);
                    } else if (which == 2) {
                        chooseMemoryDestination(REQ_MEMORY_EXPORT);
                    } else if (which == 3) {
                        chooseMemoryDestination(REQ_MEMORY_BACKUP);
                    } else if (which == 4) {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        startActivityForResult(intent, REQ_DRIVE_FOLDER);
                    } else if (which == 5) {
                        syncDrive(true, true);
                    } else if (which == 6) {
                        memoryExecutor.execute(() -> {
                            int count = historyStore.count();
                            runOnUiThread(() -> Toast.makeText(this,
                                    "Chat archiviate: " + count, Toast.LENGTH_LONG).show());
                        });
                    } else if (which == 7) {
                        // A separate, read-only SAF grant. It cannot be silently granted by Dan.
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        startActivityForResult(intent, REQ_STORY_FOLDER);
                    } else if (which == 9) {
                        chooseFolderForDrive();
                    } else {
                        syncPersonalFolder(true);
                    }
                }).show();
    }

    /** Incremental, offline document indexing. No Drive documents are modified. */
    private void syncPersonalFolder(boolean showResult) {
        DanPersonalMemorySync reader = new DanPersonalMemorySync(this,
                getSharedPreferences(PREFS, MODE_PRIVATE));
        if (!reader.hasFolders()) return;
        memoryExecutor.execute(() -> {
            try {
                DanPersonalMemorySync.Result stats = reader.sync();
                if (showResult) runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Memoria autobiografica aggiornata")
                        .setMessage(stats.summary())
                        .setPositiveButton("OK", null).show());
            } catch (Exception e) {
                android.util.Log.w("DanAutobiography", "Sync delle memorie non riuscito", e);
                if (showResult) runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Importazione non riuscita")
                        .setMessage("Controlla i permessi delle cartelle. I documenti e i ricordi già salvati rimangono intatti.")
                        .setPositiveButton("OK", null).show());
            }
        });
    }

    private void scheduleMemoryBackup() {
        if (pendingMemoryBackup != null) pendingMemoryBackup.cancel(false);
        pendingMemoryBackup = memoryExecutor.schedule(() -> {
            if (driveSync.configured()) {
                try {
                    driveSync.sync(true);
                } catch (Exception e) {
                    android.util.Log.e("DanMemory", "Sincronizzazione Drive fallita", e);
                    runOnUiThread(() -> Toast.makeText(this,
                            "Drive non raggiungibile; nuovi messaggi conservati sul dispositivo",
                            Toast.LENGTH_LONG).show());
                }
                return;
            }
            String stored = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(PREF_MEMORY_BACKUP_URI, null);
            if (stored == null) return;
            try (OutputStream output = getContentResolver().openOutputStream(Uri.parse(stored), "wt")) {
                if (output == null) throw new java.io.IOException("Destinazione non scrivibile");
                memoryStore.exportJsonl(output);
            } catch (Exception e) {
                android.util.Log.e("DanMemory", "Backup automatico fallito", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Backup automatico non riuscito; memoria locale conservata",
                        Toast.LENGTH_LONG).show());
            }
        }, 5, TimeUnit.SECONDS);
    }

    private void syncDrive(boolean saveBackup, boolean showResult) {
        if (!driveSync.configured()) {
            Toast.makeText(this, "Collega prima la cartella Memoria Dan",
                    Toast.LENGTH_LONG).show();
            return;
        }
        memoryExecutor.execute(() -> {
            try {
                DanDriveSync.Result result = driveSync.sync(saveBackup);
                if (showResult) runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Memoria Dan sincronizzata")
                        .setMessage(result.historyCount + " chat nello storico; "
                                + result.newTurns + " nuovi messaggi recuperati. "
                                + (result.archiveFound ? "Archivio Drive presente."
                                : "Archivio ZIP non trovato nella cartella."))
                        .setPositiveButton("OK", null).show());
            } catch (Exception e) {
                android.util.Log.e("DanMemory", "Sincronizzazione Drive fallita", e);
                if (showResult) runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Sincronizzazione non riuscita")
                        .setMessage("Controlla la connessione e l'accesso alla cartella Memoria Dan. "
                                + "I dati già presenti sul dispositivo restano disponibili.")
                        .setPositiveButton("OK", null).show());
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
        if (requestCode == REQ_UPLOAD_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null)
                prepareFolderForDrive(data.getData());
            return;
        }
        if (requestCode == REQ_BLOB_SAVE) {
            stopAttachmentProtection();
            final File ready = blobSaveFile;
            blobSaveFile = null;
            if (ready == null) return;
            if (resultCode != RESULT_OK || data == null || data.getData() == null) {
                ready.delete(); statusText.setText("Salvataggio annullato"); return;
            }
            final Uri destination = data.getData();
            memoryExecutor.execute(() -> {
                try (InputStream input = new java.io.FileInputStream(ready);
                     OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                    if (output == null) throw new java.io.IOException("Destinazione non scrivibile");
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    output.flush();
                    runOnUiThread(() -> statusText.setText("File salvato"));
                } catch (Exception e) {
                    runOnUiThread(() -> statusText.setText("Salvataggio non riuscito: " + e.getMessage()));
                } finally { ready.delete(); }
            });
            return;
        }

        if (requestCode == REQ_STORY_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    Uri folder = data.getData();
                    if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0)
                        throw new SecurityException("Lettura non autorizzata");
                    getContentResolver().takePersistableUriPermission(
                            folder, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    new DanPersonalMemorySync(this, getSharedPreferences(PREFS, MODE_PRIVATE))
                            .setStoryFolder(folder);
                    Toast.makeText(this, "Memoria autobiografica collegata (sola lettura)",
                            Toast.LENGTH_LONG).show();
                    syncPersonalFolder(true);
                } catch (Exception e) {
                    android.util.Log.w("DanAutobiography", "Cartella non autorizzata", e);
                    Toast.makeText(this, "Seleziona 'Tutta la mia storia o quasi'",
                            Toast.LENGTH_LONG).show();
                }
            }
            return;
        }

        if (requestCode == REQ_DRIVE_FOLDER) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try {
                    Uri folder = data.getData();
                    int granted = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    if (granted != (Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
                        throw new java.io.IOException("Servono lettura e scrittura");
                    getContentResolver().takePersistableUriPermission(folder, granted);
                    driveSync.setFolder(folder);
                    Toast.makeText(this, "Recupero e salvataggio da Memoria Dan in corso",
                            Toast.LENGTH_LONG).show();
                    syncDrive(true, true);
                } catch (Exception e) {
                    android.util.Log.e("DanMemory", "Cartella non collegata", e);
                    Toast.makeText(this, "Seleziona Memoria Dan su Drive con accesso in scrittura",
                            Toast.LENGTH_LONG).show();
                }
            }
            return;
        }

        if (requestCode == REQ_HISTORY_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri source = data.getData();
                Toast.makeText(this, "Importazione dello storico in corso. Tieni Dan aperto.",
                        Toast.LENGTH_LONG).show();
                memoryExecutor.execute(() -> {
                    try (InputStream input = getContentResolver().openInputStream(source)) {
                        if (input == null) throw new java.io.IOException("Archivio non leggibile");
                        int imported = historyStore.importArchive(input);
                        runOnUiThread(() -> new AlertDialog.Builder(this)
                                .setTitle("Storico importato")
                                .setMessage(imported + " chat verificate e conservate sul dispositivo.")
                                .setPositiveButton("OK", null).show());
                    } catch (Exception e) {
                        android.util.Log.e("DanHistory", "Importazione fallita", e);
                        runOnUiThread(() -> new AlertDialog.Builder(this)
                                .setTitle("Importazione non riuscita")
                                .setMessage("Archivio non valido o importazione interrotta. "
                                        + "Nessuna chat di questa importazione è stata aggiunta.")
                                .setPositiveButton("OK", null).show());
                    }
                });
            }
            return;
        }

        if (requestCode == REQ_MEMORY_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri source = data.getData();
                memoryExecutor.execute(() -> {
                    try (InputStream input = getContentResolver().openInputStream(source)) {
                        if (input == null) throw new java.io.IOException("Backup non leggibile");
                        int added = memoryStore.importJsonl(input);
                        if (added > 0) scheduleMemoryBackup();
                        runOnUiThread(() -> Toast.makeText(this,
                                "Ripristino completato: " + added + " messaggi aggiunti",
                                Toast.LENGTH_LONG).show());
                    } catch (Exception e) {
                        android.util.Log.e("DanMemory", "Ripristino fallito", e);
                        runOnUiThread(() -> Toast.makeText(this,
                                "Backup non valido o non leggibile: nessun messaggio importato",
                                Toast.LENGTH_LONG).show());
                    }
                });
            }
            return;
        }

        if (requestCode == REQ_MEMORY_EXPORT || requestCode == REQ_MEMORY_BACKUP) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri destination = data.getData();
                if (requestCode == REQ_MEMORY_BACKUP) {
                    try {
                        int granted = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        if ((granted & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                            throw new SecurityException("Permesso di scrittura non concesso");
                        }
                        getContentResolver().takePersistableUriPermission(destination, granted);
                    } catch (SecurityException e) {
                        Toast.makeText(this, "Impossibile mantenere l'accesso al backup",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                }
                memoryExecutor.execute(() -> {
                    try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                        if (output == null) throw new java.io.IOException("Destinazione non scrivibile");
                        int count = memoryStore.exportJsonl(output);
                        if (requestCode == REQ_MEMORY_BACKUP) {
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                    .putString(PREF_MEMORY_BACKUP_URI, destination.toString()).apply();
                        }
                        runOnUiThread(() -> Toast.makeText(this,
                                (requestCode == REQ_MEMORY_BACKUP ? "Backup attivo: " : "Memoria esportata: ")
                                        + count + " messaggi", Toast.LENGTH_LONG).show());
                    } catch (Exception e) {
                        android.util.Log.e("DanMemory", "Esportazione fallita", e);
                        runOnUiThread(() -> Toast.makeText(this,
                                "Salvataggio memoria non riuscito", Toast.LENGTH_LONG).show());
                    }
                });
            }
            return;
        }

        if (requestCode == REQ_FILE_CHOOSER) {
            stopAttachmentProtection();
            recordAttachmentPhase("Ritorno selettore: risultato " + resultCode
                    + "; callback " + (filePathCallback != null), false);
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

            if (results == null || results.length == 0) {
                ValueCallback<Uri[]> callback = filePathCallback;
                filePathCallback = null;
                recordAttachmentPhase("Selezione annullata o senza documento", false);
                statusText.setText("Nessun documento selezionato");
                getSharedPreferences("dan_upload_recovery", MODE_PRIVATE).edit().putBoolean("pickerPending", false).commit();
                if (callback != null) callback.onReceiveValue(null);
            } else {
                rememberSystemDocuments(results, data);
                if (filePathCallback == null) {
                    recordAttachmentPhase("Selezione conservata; allegato non ancora caricato", false);
                    updateAttachmentRecoveryStatus();
                    Toast.makeText(this, "Selezione conservata, file non ancora allegato: nella stessa chat premi Allega", Toast.LENGTH_LONG).show();
                } else deliverSystemDocuments(results, data);
            }

        }
    }

    private String attachmentStartupUrl() {
        SharedPreferences prefs=getSharedPreferences("dan_upload_recovery",MODE_PRIVATE);
        String target=prefs.getString("target",null);
        if (DanAttachmentRecovery.allowedPage(target) && (prefs.getBoolean("pickerPending",false)
                || !"[]".equals(prefs.getString("documents","[]")))) return target;
        return HOME;
    }

    private void rememberSystemDocuments(Uri[] selected, Intent data) {
        java.util.ArrayList<String> source=new java.util.ArrayList<>();
        for(Uri uri:selected){
            if(uri==null)continue;
            int flags=data==null?0:data.getFlags();
            if("content".equals(uri.getScheme()) && (flags&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0
                    && (flags&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0) {
                try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
                catch(SecurityException ignored){}
            }
            source.add(uri.toString());
        }
        org.json.JSONArray saved=new org.json.JSONArray(DanAttachmentRecovery.documents(source));
        getSharedPreferences("dan_upload_recovery",MODE_PRIVATE).edit()
                .putBoolean("pickerPending",false).putString("documents",saved.toString()).commit();
    }

    private Uri[] recoveredDocumentsFor(String current) {
        SharedPreferences prefs=getSharedPreferences("dan_upload_recovery",MODE_PRIVATE);
        if(!DanAttachmentRecovery.samePage(prefs.getString("target",null),current))return null;
        try{
            org.json.JSONArray saved=new org.json.JSONArray(prefs.getString("documents","[]"));
            java.util.ArrayList<String> source=new java.util.ArrayList<>();
            for(int i=0;i<saved.length();i++)source.add(saved.getString(i));
            java.util.List<String> valid=DanAttachmentRecovery.documents(source);
            Uri[] result=new Uri[valid.size()];
            for(int i=0;i<result.length;i++)result[i]=Uri.parse(valid.get(i));
            return result;
        }catch(Exception ignored){return null;}
    }

    private void updateAttachmentRecoveryStatus() {
        SharedPreferences prefs=getSharedPreferences("dan_upload_recovery",MODE_PRIVATE);
        if(!"[]".equals(prefs.getString("documents","[]")))
            statusText.setText("File non ancora allegato · premi Allega nella stessa chat");
        else if(prefs.getBoolean("pickerPending",false))
            statusText.setText("Selezione interrotta · puoi riaprire Allega nella chat");
    }

    private void startAttachmentProtection() {
        try {
            Intent session = new Intent(this, DanAttachmentSessionService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(session);
            else startService(session);
        } catch (RuntimeException error) {
            getSharedPreferences("dan_upload_diagnostics", MODE_PRIVATE).edit()
                    .putString("pickerProtection", "Avvio non disponibile: " + error.getClass().getSimpleName())
                    .putLong("pickerProtectionTime", System.currentTimeMillis()).commit();
        }
    }

    private void stopAttachmentProtection() {
        stopService(new Intent(this, DanAttachmentSessionService.class));
        getSharedPreferences("dan_upload_diagnostics", MODE_PRIVATE).edit()
                .putString("pickerProtection", "Terminata al ritorno dal selettore")
                .putLong("pickerProtectionTime", System.currentTimeMillis()).commit();
    }

    private String attachmentMemorySnapshot() {
        try {
            android.app.ActivityManager manager = getSystemService(android.app.ActivityManager.class);
            android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            Runtime runtime = Runtime.getRuntime();
            return "RAM libera " + (info.availMem / 1048576L) + " MB / "
                    + (info.totalMem / 1048576L) + " MB; soglia " + (info.threshold / 1048576L)
                    + " MB; pressione memoria " + info.lowMemory
                    + "; heap Java " + ((runtime.totalMemory()-runtime.freeMemory()) / 1048576L)
                    + "/" + (runtime.maxMemory() / 1048576L) + " MB";
        } catch (RuntimeException error) { return "Dati memoria non disponibili"; }
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        getSharedPreferences("dan_upload_diagnostics", MODE_PRIVATE).edit()
                .putInt("trimLevel", level).putLong("trimTime", System.currentTimeMillis())
                .putString("trimMemory", attachmentMemorySnapshot()).commit();
    }

    private void recordAttachmentPhase(String phase, boolean newAttempt) {
        SharedPreferences.Editor edit = getSharedPreferences("dan_upload_diagnostics", MODE_PRIVATE).edit();
        edit.putString("phase", phase).putLong("phaseTime", System.currentTimeMillis())
                .putString("phaseMemory", attachmentMemorySnapshot());
        if (newAttempt) edit.putString("attemptMemory", attachmentMemorySnapshot());
        if (newAttempt) edit.putLong("attemptTime", System.currentTimeMillis());
        edit.commit(); // Persist the last boundary even if Android terminates the process.
    }

    private void deliverSystemDocuments(Uri[] selected, Intent data) {
        ValueCallback<Uri[]> callback = filePathCallback;
        if (callback == null) { rememberSystemDocuments(selected, data); updateAttachmentRecoveryStatus(); return; }
        filePathCallback = null;
        java.util.ArrayList<Uri> valid = new java.util.ArrayList<>();
        for (Uri uri : selected) {
            if (uri == null || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme()))) continue;
            int flags = data == null ? 0 : data.getFlags();
            if ("content".equals(uri.getScheme()) && (flags & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0
                    && (flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException ignored) { /* Temporary read grants remain valid. */ }
            }
            if (!valid.contains(uri)) valid.add(uri);
        }
        if (valid.isEmpty()) {
            recordAttachmentPhase("Il selettore non ha restituito un documento valido", false);
            statusText.setText("Documento non disponibile dal selettore");
            callback.onReceiveValue(null);
            return;
        }
        final int generation = attachmentGeneration;
        recordAttachmentPhase("Consegna diretta al WebView: " + valid.size() + " documenti", false);
        try {
            callback.onReceiveValue(valid.toArray(new Uri[0]));
            getSharedPreferences("dan_upload_recovery",MODE_PRIVATE).edit()
                    .remove("documents").putBoolean("pickerPending",false).commit();
            recordAttachmentPhase("Callback WebView completata", false);
            statusText.setText("Documento consegnato alla pagina · attendo il caricamento");
            if (webView != null) webView.postDelayed(() -> {
                if (isDestroyed() || generation != attachmentGeneration || webView == null) return;
                webView.evaluateJavascript("JSON.stringify(Array.from(document.querySelectorAll('input[type=file]')).map(e=>({count:e.files?e.files.length:0,connected:e.isConnected})))", value -> {
                    if (generation == attachmentGeneration) recordAttachmentPhase("Callback completata; campi file della pagina: " + value, false);
                });
            }, 1200L);
        } catch (RuntimeException error) {
            recordAttachmentPhase("Errore callback WebView: " + error.getClass().getSimpleName(), false);
            statusText.setText("La pagina non ha acquisito il documento");
        }
    }

    private void showAttachmentDiagnostics() {
        SharedPreferences prefs = getSharedPreferences("dan_upload_diagnostics", MODE_PRIVATE);
        StringBuilder report = new StringBuilder("Dan Heiwa ").append(BuildConfig.VERSION_NAME)
                .append("\nAndroid ").append(Build.VERSION.RELEASE)
                .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("\nUltimo passaggio: ").append(prefs.getString("phase", "Nessun tentativo registrato"));
        report.append("\nProcesso attuale: PID ").append(android.os.Process.myPid());
        if (prefs.contains("pickerProtection")) report.append("\nProtezione selettore: ")
                .append(prefs.getString("pickerProtection", "")).append(" a ")
                .append(new java.util.Date(prefs.getLong("pickerProtectionTime", 0)));
        report.append("\nAndroid chiude attività in background: ").append(
                Settings.Global.getInt(getContentResolver(), Settings.Global.ALWAYS_FINISH_ACTIVITIES, 0) == 1);
        report.append("\nOra: ").append(attachmentMemorySnapshot())
                .append("\nPrima della selezione: ").append(prefs.getString("attemptMemory", "non registrata"))
                .append("\nAll’ultimo passaggio: ").append(prefs.getString("phaseMemory", "non registrata"));
        if (prefs.contains("trimLevel")) report.append("\nUltimo avviso Android: livello ")
                .append(prefs.getInt("trimLevel", 0)).append(" a ")
                .append(new java.util.Date(prefs.getLong("trimTime", 0)))
                .append("\n").append(prefs.getString("trimMemory", ""));
        if (prefs.contains("rendererExit")) report.append("\nUltimo arresto WebView: ")
                .append(prefs.getString("rendererExit", "")).append(" a ")
                .append(new java.util.Date(prefs.getLong("rendererTime", 0)));
        report.append("\nI dati storici possono riferirsi a tentativi precedenti; il segnale da solo non prova la causa.");
        try {
            android.content.pm.PackageInfo engine = androidx.webkit.WebViewCompat.getCurrentWebViewPackage(this);
            if (engine != null) report.append("\nWebView: ").append(engine.packageName).append(' ').append(engine.versionName);
        } catch (Exception ignored) {}
        if (Build.VERSION.SDK_INT >= 30) try {
            android.app.ActivityManager manager = getSystemService(android.app.ActivityManager.class);
            for (android.app.ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(getPackageName(), 0, 5)) {
                if (!getPackageName().equals(exit.getProcessName()) || exit.getTimestamp() < prefs.getLong("attemptTime", Long.MAX_VALUE) - 5000L) continue;
                String cause;
                switch (exit.getReason()) {
                    case android.app.ApplicationExitInfo.REASON_SIGNALED: cause="Processo terminato dal segnale " + exit.getStatus();break;
                    case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: cause="Memoria RAM insufficiente";break;
                    case android.app.ApplicationExitInfo.REASON_CRASH: cause="Errore dell’app";break;
                    case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: cause="Errore nativo";break;
                    case android.app.ApplicationExitInfo.REASON_ANR: cause="App non risponde";break;
                    default: cause="Chiusura Android, codice " + exit.getReason();
                }
                report.append("\nUltima chiusura: ").append(cause)
                        .append("; a ").append(new java.util.Date(exit.getTimestamp()))
                        .append("; importanza ").append(exit.getImportance())
                        .append("; PSS ").append(exit.getPss()).append(" KB; RSS ").append(exit.getRss()).append(" KB");
                break;
            }
        } catch (Exception ignored) {}
        final String text = report.toString();
        new AlertDialog.Builder(this).setTitle("Diagnostica allegati")
                .setMessage(text).setPositiveButton("Copia", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("Diagnostica allegati", text));
                }).setNegativeButton("Chiudi", null).show();
    }

    private class NativeBridge {
        @JavascriptInterface public void startWebVoiceFromPage(String url, String token) {
            runOnUiThread(() -> {
                long elapsed = android.os.SystemClock.uptimeMillis() - lastWebVoiceTouch;
                if (!foreground || luminexVisible || webView == null || !trustedVoiceOrigin(url)
                        || !sameVoicePage(url, webView.getUrl()) || lastWebVoiceTouch == 0L || elapsed > 2000L) return;
                lastWebVoiceTouch = 0L;
                if (token == null || !token.matches("page-[0-9]+")) return;
                if (!webVoiceRequested) beginWebVoice(token);
            });
        }

        @JavascriptInterface public void webVoiceState(String url, String token, String state) {
            runOnUiThread(() -> {
                if (!webVoiceRequested || !webVoiceToken.equals(token)
                        || webView == null || !trustedVoiceOrigin(url) || !sameVoicePage(url, webView.getUrl())) return;
                if (!foreground) { stopWebVoice("LIVE chiuso · app in background"); return; }
                if ("active".equals(state)) {
                    webVoiceActive = true;
                    statusText.setText("LIVE · microfono aperto · parla liberamente · X per chiudere");
                } else if ("closed".equals(state)) stopWebVoice("LIVE chiuso");
                else if ("capture-denied".equals(state)) stopWebVoice("Microfono negato · controlla Permessi → Microfono nelle impostazioni Android");
                else if ("capture-busy".equals(state)) stopWebVoice("Microfono non accessibile · controlla privacy Android e altre app");
                else if ("capture-missing".equals(state)) stopWebVoice("Nessun microfono disponibile sul dispositivo");
                else if ("capture-error".equals(state)) stopWebVoice("Microfono web non disponibile · controlla il permesso e riprova");
                else if ("unsupported".equals(state) || "unavailable".equals(state))
                    stopWebVoice("LIVE non disponibile in questa pagina");
                else if ("opened".equals(state) && !webVoiceActive)
                    statusText.setText("LIVE · modalità vocale aperta, attendo il microfono…");
            });
        }

        @JavascriptInterface
        public void rememberMemoryPending(String id, String text) {
            if (!isDanChatGptPage()) return;
            if (id == null || text == null || id.length() > 100 || text.isEmpty()) return;
            synchronized (MainActivity.this) {
                pendingMemoryId = id;
                pendingMemoryText = text;
                pendingMemoryAt = System.currentTimeMillis();
            }
        }

        @JavascriptInterface
        public void clearMemoryPending(String id) {
            if (!isDanChatGptPage()) return;
            synchronized (MainActivity.this) {
                if (pendingMemoryId.equals(id)) {
                    pendingMemoryId = "";
                    pendingMemoryText = "";
                    pendingMemoryAt = 0L;
                }
            }
        }

        @JavascriptInterface
        public void recordMemoryTurn(String url, String role, String eventId, String text) {
            if (url == null || role == null || eventId == null || text == null ||
                    text.trim().isEmpty() || !eventId.matches("dan-[A-Za-z0-9.-]+:(user|assistant)")) return;
            if (!"user".equals(role) && !"assistant".equals(role)) return;
            Uri parsed = Uri.parse(url);
            if (!"https".equals(parsed.getScheme()) ||
                    !"chatgpt.com".equals(parsed.getHost()) ||
                    parsed.getPath() == null ||
                    !parsed.getPath().matches("/(?:g/[^/]+/)?c/[^/]+")) return;
            if (!isDanChatGptPage()) return;
            memoryExecutor.execute(() -> {
                if (memoryStore.record(eventId, url, role, text)) scheduleMemoryBackup();
            });
        }

        @JavascriptInterface
        public void assistantReady(String text) {
            // Unscoped callbacks are retained for binary compatibility only.
        }

        @JavascriptInterface
        public void userTurnStartedForPage(String url) {
            runOnUiThread(() -> {
                if (webView == null || webVoiceRequested || !sameVoicePage(url, webView.getUrl())) return;
                // X stops the current turn. A new typed turn restores automatic reading.
                suppressAutoRead = false;
            });
        }

        @JavascriptInterface
        public void assistantReadyForPage(String url, String text) {
            runOnUiThread(() -> {
                if (webView == null || webVoiceRequested || !sameVoicePage(url, webView.getUrl()) || suppressAutoRead) return;
                if (liveEnabled) {
                    if (!liveWaiting || !liveSendConfirmed || text == null || text.trim().isEmpty()) return;
                    liveWaiting = false;
                    liveSendConfirmed = false;
                    livePage = url;
                    if (liveCaption != null) liveCaption.setText("DAN STA PARLANDO");
                    if (!ttsReady || !recordingSoundsEnabled || tts == null) {
                        stopLiveMode("Voce non disponibile · risposta visibile nella chat");
                        return;
                    }
                    // Identical replies to separate turns must still be read.
                    lastSpokenText = "";
                    ttsSpeaking = true;
                }
                speakAssistantText(text);
            });
        }

        @JavascriptInterface
        public void nativeReadStarted() {
            runOnUiThread(() -> { if (!webVoiceRequested) statusText.setText("🔊 Voce ChatGPT…"); });
        }

        @JavascriptInterface
        public void danChatChanged(String url) {
            if (url == null) return;
            runOnUiThread(() -> updateActiveDanChat(url));
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
        if (lower.matches("(?:carica|copia|invia) (?:una |la )?cartella su drive[.!?]?")) {
            chooseFolderForDrive();
            return;
        }

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

    private String danPageScript() {
        return "function danRoots(){const roots=[],seen=new Set();function visit(root){if(!root||seen.has(root))return;seen.add(root);roots.push(root);for(const e of root.querySelectorAll('*')){if(e.shadowRoot&&danVisible(e))visit(e.shadowRoot);if(e.tagName==='IFRAME'&&danVisible(e)){try{visit(e.contentDocument);}catch(ignore){}}}}visit(document);return roots;}\nfunction danVisible(e){if(!e||e.closest('[hidden],[inert],[aria-hidden=true]'))return false;for(let n=e;n&&n.nodeType===1;n=n.parentElement){const c=n.ownerDocument.defaultView.getComputedStyle(n);if(c.display==='none'||c.visibility==='hidden')return false;}return e.getClientRects().length>0;}\nfunction danEditor(){const strong=[],other=[];for(const root of danRoots())for(const e of root.querySelectorAll('#prompt-textarea,textarea,[contenteditable=true],[role=textbox]')){if(!danVisible(e)||e.disabled||e.readOnly||e.getAttribute('contenteditable')==='false'||e.closest('nav,aside,[role=dialog]'))continue;if(!(e.isContentEditable||e.tagName==='TEXTAREA'||e.tagName==='INPUT'))continue;const hint=((e.id||'')+' '+(e.getAttribute('data-testid')||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('placeholder')||'')+' '+(e.getAttribute('data-placeholder')||'')).toLowerCase();const list=/prompt|composer|message|messaggio|chiedi|ask|reply|rispondi/.test(hint)?strong:other;if(!list.includes(e))list.push(e);}const a=strong.length?strong:other;return a.length===1?a[0]:null;}\nfunction danSend(e){if(!e)return null;const root=e.getRootNode();const form=e.closest('form');const scope=form||root;const buttons=Array.from(scope.querySelectorAll('button,[role=button]')).filter(danVisible);const exact=buttons.filter(b=>/^(send-button|composer-submit-button)$/.test(b.getAttribute('data-testid')||''));if(exact.length===1)return exact[0];const a=buttons.filter(b=>{const t=((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'')+' '+(b.getAttribute('data-testid')||'')).trim().toLowerCase();return /send|invia|submit|inviare/.test(t)&&!/voice|vocale|stop|interrompi/.test(t);});return a.length===1?a[0]:null;}\nfunction danAnswer(){const roots=danRoots(),answers=[];for(const root of roots){const turns=Array.from(root.querySelectorAll('[data-message-author-role=assistant],[data-testid=assistant],[data-testid=assistant-message]')).filter(danVisible);for(const e of turns){const blocks=Array.from(e.querySelectorAll('.markdown,.prose,[data-testid=response-content],[data-testid=message-content]')).filter(danVisible);const leaves=blocks.filter(x=>!blocks.some(y=>x!==y&&x.contains(y)));const text=(leaves.length?leaves:[e]).map(x=>(x.innerText||x.textContent||'').trim()).filter(Boolean).join('\\n').trim();answers.push(text);}}if(answers.length)return answers[answers.length-1].slice(0,12000);for(const root of roots){const buttons=Array.from(root.querySelectorAll('button')).filter(danVisible);for(let i=buttons.length-1;i>=0;i--){const b=buttons[i];const label=((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('data-testid')||'')+' '+(b.getAttribute('title')||'')).toLowerCase();if(!/read aloud|leggi|good-response|bad-response/.test(label))continue;let e=b.parentElement;for(let j=0;e&&j<6;j++,e=e.parentElement){if(e.tagName==='MAIN'||e.tagName==='BODY'||e.getAttribute('role')==='main')break;if(e.querySelector('[data-message-author-role=user]'))break;const blocks=Array.from(e.querySelectorAll('p,li,pre,blockquote,h1,h2,h3,h4,h5,h6')).filter(danVisible);const text=blocks.map(x=>(x.innerText||x.textContent||'').trim()).filter(Boolean).join(' ').trim();if(text.length>=2&&text.length<=12000)return text;}}}return '';}\n";
    }

    private boolean sameVoicePage(String first, String second) {
        if (first == null || second == null) return false;
        try {
            Uri a = Uri.parse(first), b = Uri.parse(second);
            return "https".equals(a.getScheme()) && "https".equals(b.getScheme())
                    && "chatgpt.com".equals(a.getHost()) && "chatgpt.com".equals(b.getHost())
                    && a.getPath() != null && a.getPath().equals(b.getPath());
        } catch (Exception e) { return false; }
    }

    private void injectTextAndSend(String text) {
        if (webView == null) return;
        suppressAutoRead = false;

        String textToSend = text;

        if (!TextUtils.isEmpty(lastLuminexContext)) {
            String context = lastLuminexContext;

            if (context.length() > 12000) {
                context = context.substring(0, 12000);
            }

            textToSend =
                    text
                    + "\n\n[CONTESTO LUMINEX - PAGINA CORRENTE]\n"
                    + context;
        }

        // JSON quoting prevents apostrophes/newlines in dictated speech from
        // corrupting the JavaScript injected into the ChatGPT composer.
        String encodedText = JSONObject.quote(textToSend);

        String script =
                "(function(){" +
                " const text=" + encodedText + ";" +
                danPageScript() +
                "const editor=danEditor();" +
                "if(!editor)return 'NO_EDITOR';" +
                "if(editor.disabled || editor.getAttribute('contenteditable')==='false')return 'READ_ONLY';" +
                "try{" +
                "editor.focus();" +
                "if(editor.tagName==='TEXTAREA'||editor.tagName==='INPUT'){" +
                "const win=editor.ownerDocument.defaultView;const proto=editor.tagName==='TEXTAREA'?win.HTMLTextAreaElement.prototype:win.HTMLInputElement.prototype;" +
                "const setter=Object.getOwnPropertyDescriptor(proto,'value').set;" +
                "setter.call(editor,text);" +
                "}else{" +
                "const doc=editor.ownerDocument;const selection=doc.defaultView.getSelection();" +
                "const range=doc.createRange();" +
                "range.selectNodeContents(editor);" +
                "selection.removeAllRanges();" +
                "selection.addRange(range);" +
                "const ok=(typeof doc.execCommand==='function')&&doc.execCommand('insertText',false,text);" +
                "if(!ok || !(editor.innerText||editor.textContent||'').includes(text.slice(0,Math.min(text.length,40)))){" +
                "editor.textContent=text;" +
                "}" +
                "}" +
                "const events=editor.ownerDocument.defaultView;try{editor.dispatchEvent(new events.InputEvent('input',{bubbles:true,composed:true,inputType:'insertText',data:text}));}catch(eventError){editor.dispatchEvent(new events.Event('input',{bubbles:true,composed:true}));}" +
                "editor.dispatchEvent(new events.Event('change',{bubbles:true,composed:true}));" +
                "const draft=(editor.value||editor.innerText||editor.textContent||'');" +
                "return draft.includes(text.slice(0,Math.min(40,text.length)))?'INSERTED':'NO_INSERT';" +
                "}catch(err){return 'INSERT_ERROR';}" +
                "})();";

        final long requestId = ++voiceSendGeneration;
        final String originalSpeech = text;
        final String voicePage = webView.getUrl();
        webView.evaluateJavascript(script, value -> runOnUiThread(() -> {
            if (requestId != voiceSendGeneration) return;
            if (webView == null || !sameVoicePage(voicePage, webView.getUrl())) {
                showUnsentVoiceDraft(originalSpeech, "Hai cambiato chat: messaggio non inviato.");
                return;
            }
            if ("\"INSERTED\"".equals(value)) {
                statusText.setText("Testo dettato nella casella: preparo invio…");
                statusText.postDelayed(() -> trySendVoiceDraft(originalSpeech, requestId, voicePage, 0), 550L);
            } else {
                statusText.setText("Dettatura non inserita nella chat");
                showUnsentVoiceDraft(originalSpeech, "DAN non è riuscito a scrivere nella casella.");
            }
        }));
    }

    // A successful JavaScript call is not proof that ChatGPT accepted a message.
    // First verify the text remains in the composer, then click Send only once.
    private void trySendVoiceDraft(String originalSpeech, long requestId, String voicePage, int attempt) {
        if (requestId != voiceSendGeneration || webView == null) return;
        if (!sameVoicePage(voicePage, webView.getUrl())) {
            showUnsentVoiceDraft(originalSpeech, "Hai cambiato chat: messaggio non inviato.");
            return;
        }
        final String prefix = originalSpeech.substring(0, Math.min(35, originalSpeech.length()));
        String checkAndSend =
                "(function(){" +
                danPageScript() +
                "const e=danEditor();" +
                "if(!e)return 'NO_EDITOR';" +
                "const draft=(e.value||e.innerText||e.textContent||'');" +
                "if(!draft.includes(" +
                JSONObject.quote(prefix) +
                "))return 'TEXT_MISSING';" +
                "const b=danSend(e);" +
                "if(!b)return 'NO_SEND';" +
                "if(b.disabled || b.getAttribute('aria-disabled')==='true')return 'DISABLED';" +
                "try{window.__danLivePending={users:document.querySelectorAll('[data-message-author-role=user]').length,answers:document.querySelectorAll('[data-message-author-role=assistant]').length};b.click();return 'CLICKED';}catch(err){return 'CLICK_ERROR';}" +
                "})()";
        webView.evaluateJavascript(checkAndSend, value -> runOnUiThread(() -> {
            if (requestId != voiceSendGeneration) return;
            if ("\"CLICKED\"".equals(value)) {
                if (liveEnabled && liveWaiting) {
                    liveSendConfirmed = true;
                    statusText.setText("LIVE · attendo la risposta…");
                } else statusText.setText("Invio premuto; verifica la risposta nella chat");
            } else if (("\"DISABLED\"".equals(value) || "\"NO_SEND\"".equals(value))
                    && attempt < 5) {
                statusText.postDelayed(() -> trySendVoiceDraft(originalSpeech, requestId, voicePage, attempt + 1), 550L);
            } else {
                statusText.setText("Invio non riuscito: il testo è recuperabile");
                showUnsentVoiceDraft(originalSpeech, "La casella non ha confermato il pulsante Invia.");
            }
        }));
    }

    private void showUnsentVoiceDraft(String text, String reason) {
        if (liveEnabled) stopLiveMode("Invio non riuscito · testo conservato");
        if (isFinishing()) return;
        EditText draft = new EditText(this);
        draft.setText(text);
        draft.setMinLines(2);
        draft.setMaxLines(8);
        draft.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Messaggio dettato non inviato")
                .setMessage(reason + " Puoi riprovare o copiare il testo senza perderlo.")
                .setView(draft)
                .setPositiveButton("Riprova", (dialog, which) ->
                        handleRecognizedCommandOrMessage(draft.getText().toString()))
                .setNeutralButton("Copia testo", (dialog, which) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) clipboard.setPrimaryClip(
                            ClipData.newPlainText("DAN dettatura", draft.getText().toString()));
                })
                .setNegativeButton("Chiudi", null)
                .show();
    }

    private void injectDanHudTheme() {
        if (webView == null || !sameVoicePage(webView.getUrl(), webView.getUrl())) return;
        String css;
        try (InputStream input = getAssets().open("dan-hud.css")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
            css = bytes.toString("UTF-8");
        } catch (Exception e) {
            android.util.Log.w("DanHud", "Tema non disponibile", e);
            return;
        }
        webView.evaluateJavascript("(()=>{let s=document.getElementById('dan146-hud');"
                + "if(!s){s=document.createElement('style');s.id='dan146-hud';document.head.appendChild(s);}"
                + "s.textContent=" + JSONObject.quote(css) + ";})()", null);
    }

    private final Handler copyChatHandler = new Handler(Looper.getMainLooper());
    private int copyChatToken = 0;
    private boolean copyingChat = false;

    private boolean sameCopyChat(WebView source, String url, int token) {
        return !isFinishing() && !isDestroyed() && token == copyChatToken
                && source == webView && trustedVoiceOrigin(source.getUrl())
                && url.equals(source.getUrl());
    }

    private void copyCurrentChat() {
        if (copyingChat) { Toast.makeText(this, "Lettura della chat in corso…", Toast.LENGTH_SHORT).show(); return; }
        final WebView source = webView;
        final String url = source == null ? null : source.getUrl();
        if (url == null || !trustedVoiceOrigin(url)) {
            Toast.makeText(this, "Apri prima la chat da copiare su ChatGPT.", Toast.LENGTH_LONG).show(); return;
        }
        copyingChat = true;
        final int token = ++copyChatToken;
        Toast.makeText(this, "Lettura della chat completa…", Toast.LENGTH_SHORT).show();
        source.evaluateJavascript(voiceAsset("diary-export.js") + ";window.__diaryStart();", null);
        pollCopyChat(source, url, token, 0);
    }

    private void pollCopyChat(WebView source, String url, int token, int ticks) {
        copyChatHandler.postDelayed(() -> {
            if (!sameCopyChat(source, url, token)) { copyingChat = false; return; }
            if (ticks > 65) { copyingChat = false; Toast.makeText(this, "Lettura scaduta: riprova.", Toast.LENGTH_LONG).show(); return; }
            source.evaluateJavascript("JSON.stringify(window.__diaryResult||{pending:true})", raw -> {
                if (!sameCopyChat(source, url, token)) { copyingChat = false; return; }
                try {
                    Object decoded = new org.json.JSONTokener(raw).nextValue();
                    JSONObject result = new JSONObject((String) decoded);
                    if (result.optBoolean("pending")) { pollCopyChat(source, url, token, ticks + 1); return; }
                    copyingChat = false;
                    if (result.has("error")) { Toast.makeText(this, result.getString("error"), Toast.LENGTH_LONG).show(); return; }
                    final String text = result.getString("text");
                    final String title = result.optString("title", "Chat Dan Heiwa");
                    final int count = result.optInt("count");
                    if (text.getBytes(StandardCharsets.UTF_8).length > 650000) {
                        new AlertDialog.Builder(this).setTitle("Chat troppo grande per gli appunti")
                            .setMessage("Per evitare il limite degli appunti Android, usa l’esportazione Word di Dan Heiwa Diari oppure copia la chat in parti.")
                            .setPositiveButton("OK", null).show(); return;
                    }
                    Runnable copy = () -> {
                        if (!sameCopyChat(source, url, token)) { Toast.makeText(this, "Chat cambiata: ripeti la copia.", Toast.LENGTH_LONG).show(); return; }
                        try {
                            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            clipboard.setPrimaryClip(ClipData.newPlainText(title, text));
                            Toast.makeText(this, count + " messaggi copiati. Ora puoi incollarli nella nuova chat.", Toast.LENGTH_LONG).show();
                        } catch (Exception e) { Toast.makeText(this, "Copia non riuscita: il testo potrebbe superare il limite degli appunti.", Toast.LENGTH_LONG).show(); }
                    };
                    if (!result.optBoolean("complete")) {
                        new AlertDialog.Builder(this).setTitle("Disponibile solo un estratto")
                            .setMessage(result.optString("warning") + "\n\n" + count + " messaggi caricati.")
                            .setNegativeButton("Annulla", null).setPositiveButton("Copia estratto", (d, w) -> copy.run()).show();
                    } else copy.run();
                } catch (Exception e) {
                    copyingChat = false;
                    Toast.makeText(this, "Non riesco a leggere la chat. Attendi il caricamento e riprova.", Toast.LENGTH_LONG).show();
                }
            });
        }, 500);
    }

    private void injectDanBranding() {
        if (webView == null || !trustedVoiceOrigin(webView.getUrl())) return;
        // Brand only the sidebar home control; preserve conversation and account content.
        webView.evaluateJavascript(voiceAsset("dan-branding.js"), null);
        webView.evaluateJavascript(voiceAsset("dan-composer-hint.js"), null);
        webView.evaluateJavascript(voiceAsset("dan-web-voice.js"), null);
    }

    private void updateActiveDanChat(String url) {
        if (TextUtils.isEmpty(url)) return;

        String newKey = "";

        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();

            if (path != null) {
                String[] parts = path.split("/");

                for (int i = 0; i < parts.length - 1; i++) {
                    if ("c".equals(parts[i])
                            && !TextUtils.isEmpty(parts[i + 1])) {
                        newKey = "c_" + parts[i + 1];
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (TextUtils.isEmpty(newKey)) {
            return;
        }

        if (!newKey.equals(activeDanChatKey)) {
            if (liveEnabled && livePage != null && Uri.parse(livePage).getPath() != null
                    && Uri.parse(livePage).getPath().contains("/c/")) {
                stopLiveMode("Chat cambiata · riavvia LIVE nella chat desiderata");
            }
            if (webVoiceRequested) {
                if (webVoicePage != null && Uri.parse(webVoicePage).getPath() != null
                        && Uri.parse(webVoicePage).getPath().contains("/c/")) stopWebVoice("LIVE chiuso · chat cambiata");
                else webVoicePage = url; // The first utterance may create a conversation from the home page.
            }
            activeDanChatKey = newKey;
            lastSpokenText = "";

            lastLuminexContext = "";
            syncLuminexContextToChatGpt();

            privateVisible = false;

            if (privateButton != null) {
                privateButton.setText("P");
            }
        }
    }

    private void injectDanChatWatcher() {
        if (webView == null) return;

        String script =
                "(function(){"
                + "if(window.__danChatWatcherInstalled)return;"
                + "window.__danChatWatcherInstalled=true;"
                + "let last='';"
                + "function send(){"
                + " const u=location.href||'';"
                + " if(u===last)return;"
                + " last=u;"
                + " try{"
                + "  if(window.AndroidRadio&&window.AndroidRadio.danChatChanged)"
                + "   window.AndroidRadio.danChatChanged(u);"
                + " }catch(e){}"
                + "}"
                + "const oldPush=history.pushState;"
                + "history.pushState=function(){"
                + " const r=oldPush.apply(this,arguments);"
                + " setTimeout(send,0);"
                + " return r;"
                + "};"
                + "const oldReplace=history.replaceState;"
                + "history.replaceState=function(){"
                + " const r=oldReplace.apply(this,arguments);"
                + " setTimeout(send,0);"
                + " return r;"
                + "};"
                + "window.addEventListener('popstate',send);"
                + "setInterval(send,700);"
                + "send();"
                + "})();";

        webView.evaluateJavascript(script, null);
    }

    private void injectPinnedChatOrder() {
        if (webView == null) return;
        String script =
                "(function(){\n" +
                "if(window.__danPinnedOrderInstalled)return;\n" +
                "window.__danPinnedOrderInstalled=true;\n" +
                "const names=['File Pen Name Drive','Associazione Gruppo Form','Avvisi email KDP','Calendario Accesso Googl','Apertura sito 2+2 Lezioni','Aprire sessione privata Alievi','Apri WhatsApp Web Associ','WhatsApp Web Luminex','Dan 3.0'];\n" +
                "const norm=s=>(s||'').replace(/\\s+/g,' ').trim().toLocaleLowerCase();\n" +
                "function leaf(words){return Array.from(document.querySelectorAll('nav *,aside *')).find(e=>!e.children.length&&words.includes(norm(e.textContent)));}\n" +
                "function fix(){\n" +
                " const head=leaf(['fissati in alto','bloccate','pinned']);\n" +
                " if(!head)return;\n" +
                " const scope=head.closest('nav,aside')||document.body;\n" +
                " const recent=Array.from(scope.querySelectorAll('*')).find(e=>!e.children.length&&['recenti','recents'].includes(norm(e.textContent)));\n" +
                " const links=Array.from(scope.querySelectorAll('a[href*=\"/c/\"]')).filter(a=>(head.compareDocumentPosition(a)&Node.DOCUMENT_POSITION_FOLLOWING)&&(!recent||(a.compareDocumentPosition(recent)&Node.DOCUMENT_POSITION_FOLLOWING)));\n" +
                " const found=names.map(n=>links.find(a=>norm(a.textContent||a.getAttribute('aria-label')).startsWith(norm(n))));\n" +
                " if(found.some(a=>!a)||new Set(found).size!==names.length)return;\n" +
                " let parent=found[0].parentElement;\n" +
                " while(parent&&!found.every(a=>parent.contains(a)))parent=parent.parentElement;\n" +
                " if(!parent||(recent&&parent.contains(recent)))return;\n" +
                " const rows=found.map(a=>{let r=a;while(r.parentElement&&r.parentElement!==parent)r=r.parentElement;return r;});\n" +
                " if(new Set(rows).size!==names.length)return;\n" +
                " if(rows.some(r=>(r.matches('a[href*=\"/c/\"]')?1:0)+r.querySelectorAll('a[href*=\"/c/\"]').length!==1))return;\n" +
                " const current=Array.from(parent.children).filter(x=>rows.includes(x));\n" +
                " if(current.length!==names.length||current.every((x,i)=>x===rows[i]))return;\n" +
                " rows.forEach(r=>parent.appendChild(r));\n" +
                "}\n" +
                "let timer;\n" +
                "new MutationObserver(()=>{clearTimeout(timer);timer=setTimeout(fix,300);}).observe(document.documentElement,{childList:true,subtree:true});\n" +
                "setInterval(fix,2000);\n" +
                "fix();\n" +
                "})();";
        webView.evaluateJavascript(script, null);
    }


    // DAN 155.1: osserva anche i nodi di testo ripristinati durante il primo caricamento.
    // DAN 155.2: sostituisce il suggerimento anche quando viene ricreato fuori dal form.
    private void injectPageBehaviors() {
        injectDanChatWatcher();
        injectDanBranding();
        injectDanHudTheme();
        // Riordino automatico sospeso per verificare la lista delle chat.
        injectEnterToSend();
        injectManualLuminexContext();
        syncLuminexContextToChatGpt();
        injectDownloadNameCapture();
        injectAssistantObserver();
        injectMemoryObserver();
    }

    private void injectMemoryObserver() {
        if (webView == null || webView.getUrl() == null ||
                !webView.getUrl().startsWith("https://chatgpt.com/")) return;
        try (InputStream input = getAssets().open("dan_memory_observer.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) != -1) {
                output.write(buffer, 0, n);
            }
            webView.evaluateJavascript("window.__danMemoryPending=" +
                    pendingMemoryJson() + ";\n" +
                    new String(output.toByteArray(), StandardCharsets.UTF_8), null);
        } catch (Exception e) {
            android.util.Log.e("DanMemory", "Osservatore non installato", e);
        }
    }

    private boolean isDanChatGptPage() {
        Uri uri = Uri.parse(memoryPageUrl);
        return "https".equals(uri.getScheme()) && "chatgpt.com".equals(uri.getHost());
    }

    private synchronized String pendingMemoryJson() {
        if (pendingMemoryId.isEmpty() ||
                System.currentTimeMillis() - pendingMemoryAt > 10 * 60 * 1000) return "null";
        JSONObject value = new JSONObject();
        try {
            value.put("id", pendingMemoryId);
            value.put("text", pendingMemoryText);
            value.put("started", pendingMemoryAt);
            return value.toString();
        } catch (Exception e) {
            return "null";
        }
    }

    private void syncLuminexContextToChatGpt() {
        if (webView == null) return;

        String context = lastLuminexContext == null
                ? ""
                : lastLuminexContext;

        if (context.length() > 12000) {
            context = context.substring(0, 12000);
        }

        String escaped = context
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "");

        String script =
                "window.__danLuminexContext='" + escaped + "';";

        webView.evaluateJavascript(script, null);
    }

    private void injectManualLuminexContext() {
        if (webView == null) return;

        String script =
                "(function(){" +
                " if(window.__danLuminexManualInstalled)return;" +
                " window.__danLuminexManualInstalled=true;" +

                " function editor(){" +
                "  return document.querySelector('#prompt-textarea') ||" +
                "   document.querySelector(\"textarea[data-testid='prompt-textarea']\") ||" +
                "   document.querySelector(\"div[contenteditable='true'][data-testid='prompt-textarea']\");" +
                " }" +

                " function sendButton(){" +
                "  return document.querySelector(\"button[data-testid='send-button']\") ||" +
                "   Array.from(document.querySelectorAll('button')).find(b=>{" +
                "    const a=((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('data-testid')||'')).toLowerCase();" +
                "    return a.includes('send')||a.includes('invia');" +
                "   });" +
                " }" +

                " function readText(el){" +
                "  if(!el)return '';" +
                "  return el.tagName==='TEXTAREA'||el.tagName==='INPUT' ? el.value : (el.innerText||el.textContent||'');" +
                " }" +

                " function writeText(el,text){" +
                "  if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){" +
                "   const setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value')?.set;" +
                "   if(setter)setter.call(el,text);else el.value=text;" +
                "  }else{" +
                "   el.innerHTML='';" +
                "   el.textContent=text;" +
                "  }" +
                "  el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));" +
                "  el.dispatchEvent(new Event('change',{bubbles:true}));" +
                " }" +

                " document.addEventListener('click',function(e){" +
                "  const btn=e.target&&e.target.closest?e.target.closest('button'):null;" +
                "  const send=sendButton();" +
                "  if(!btn||!send||btn!==send)return;" +

                "  if(window.__danLuminexBypass){" +
                "   window.__danLuminexBypass=false;" +
                "   return;" +
                "  }" +

                "  const ctx=window.__danLuminexContext||'';" +
                "  if(!ctx)return;" +

                "  const ed=editor();" +
                "  if(!ed)return;" +

                "  const original=readText(ed).trim();" +
                "  if(!original)return;" +

                "  if(original.includes('[CONTESTO LUMINEX - PAGINA CORRENTE]'))return;" +

                "  e.preventDefault();" +
                "  e.stopImmediatePropagation();" +

                "  const combined=original+'\\n\\n[CONTESTO LUMINEX - PAGINA CORRENTE]\\n'+ctx;" +
                "  writeText(ed,combined);" +

                "  setTimeout(()=>{" +
                "   const again=sendButton();" +
                "   if(again&&!again.disabled){" +
                "    window.__danLuminexBypass=true;" +
                "    again.click();" +
                "   }" +
                "  },120);" +
                " },true);" +
                "})();";

        webView.evaluateJavascript(script, null);
    }

    private void injectEnterToSend() {
        if (webView != null && trustedVoiceOrigin(webView.getUrl()))
            webView.evaluateJavascript(voiceAsset("dan-enter-send.js"), null);
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
                "(function(){"
                + " if(window.__danGenericReadInstalled)return;"
                + " window.__danGenericReadInstalled=true;"

                + " let timer=null;"
                + " let candidate='';"
                + " let candidateSince=0;"
                + " let lastHandled='';"
                + " let page=location.href;"
                + " let seenUsers=document.querySelectorAll('[data-message-author-role=user]').length;"

                + " function txt(e){"
                + "  if(!e)return '';"
                + "  return (e.innerText||e.textContent||'')"
                + "   .replace(/\\s+/g,' ')"
                + "   .trim();"
                + " }"

                + " function visible(e){"
                + "  if(!e)return false;"
                + "  const r=e.getBoundingClientRect();"
                + "  const s=getComputedStyle(e);"
                + "  return r.width>20 && r.height>10"
                + "   && s.display!=='none'"
                + "   && s.visibility!=='hidden';"
                + " }"

                + " function excluded(e){"
                + "  return !!e.closest("
                + "   'textarea,input,nav,aside,form,footer,"
                + "   [role=\"navigation\"],"
                + "   [role=\"dialog\"],"
                + "   [contenteditable=\"true\"]'"
                + "  );"
                + " }"

                + " function fromActionButtons(root){"
                + "  const buttons=Array.from(root.querySelectorAll('button'));"

                + "  for(let i=buttons.length-1;i>=0;i--){"
                + "   const b=buttons[i];"

                + "   const meta=("
                + "    (b.getAttribute('aria-label')||'')+' '+"
                + "    (b.getAttribute('title')||'')+' '+"
                + "    (b.getAttribute('data-testid')||'')"
                + "   ).toLowerCase();"

                + "   if(!("
                + "    meta.includes('read aloud')||"
                + "    meta.includes('leggi')||"
                + "    meta.includes('good-response')||"
                + "    meta.includes('bad-response')"
                + "   ))continue;"

                + "   let e=b.parentElement;"

                + "   for(let level=0;e&&level<6;level++,e=e.parentElement){"
                + "    if(e===root)break;"

                + "    const blocks=Array.from(e.querySelectorAll("
                + "     'p,li,pre,blockquote,h1,h2,h3,h4,h5,h6'"
                + "    )).filter(x=>txt(x).length>0);"

                + "    if(!blocks.length)continue;"

                + "    const answer=blocks.map(txt).join(' ').trim();"

                + "    if(answer.length>=20 && answer.length<=12000){"
                + "     return answer;"
                + "    }"
                + "   }"
                + "  }"

                + "  return '';"
                + " }"

                + " function genericBottomText(root){"
                + "  const blocks=Array.from(root.querySelectorAll("
                + "   'p,li,pre,blockquote,h1,h2,h3,h4,h5,h6'"
                + "  )).filter(e=>"
                + "   visible(e)"
                + "   && !excluded(e)"
                + "   && txt(e).length>=20"
                + "  );"

                + "  if(!blocks.length)return '';"

                + "  blocks.sort((a,b)=>{"
                + "   const ra=a.getBoundingClientRect();"
                + "   const rb=b.getBoundingClientRect();"
                + "   return ra.bottom-rb.bottom;"
                + "  });"

                + "  const leaf=blocks[blocks.length-1];"
                + "  let best=leaf;"
                + "  let e=leaf.parentElement;"

                + "  for(let level=0;e&&level<7;level++,e=e.parentElement){"
                + "   if(e===root)break;"
                + "   if(excluded(e))break;"

                + "   const t=txt(e);"
                + "   const count=e.querySelectorAll("
                + "    'p,li,pre,blockquote,h1,h2,h3,h4,h5,h6'"
                + "   ).length;"

                + "   if(t.length>=txt(best).length"
                + "      && t.length<=12000"
                + "      && count<=40){"
                + "    best=e;"
                + "   }"
                + "  }"

                + "  return txt(best);"
                + " }"

                + danPageScript()
                + " function latestResponse(){return danAnswer();}"

                + "function check(){"
                + "  if(page!==location.href){page=location.href;candidate='';candidateSince=0;seenUsers=document.querySelectorAll('[data-message-author-role=user]').length;if(!window.__danLivePending){lastHandled=document.querySelectorAll('[data-message-author-role=user]').length+':'+latestResponse();return;}}"
                + "  const currentUsers=document.querySelectorAll('[data-message-author-role=user]').length;"
                + "  if(currentUsers>seenUsers){try{window.AndroidRadio?.userTurnStartedForPage(page);}catch(e){}}seenUsers=currentUsers;"
                + "  const roots=danRoots();if(roots.some(r=>r.querySelector('[data-testid=stop-button],[data-is-streaming=true]'))){candidate='';return;}"
                + "  const pending=window.__danLivePending;"
                + "  const users=document.querySelectorAll('[data-message-author-role=user]').length;"
                + "  const answers=document.querySelectorAll('[data-message-author-role=assistant]').length;"
                + "  if(pending&&(users<=pending.users||answers<=pending.answers)){candidate='';return;}"
                + "  const text=latestResponse();"
                + "  const turnKey=users+':'+text; if(!text||turnKey===lastHandled)return;"
                + "  if(text!==candidate){"
                + "   candidate=text;"
                + "   candidateSince=Date.now();"
                + "   schedule(1500);"
                + "   return;"
                + "  }"
                + "  if(Date.now()-candidateSince<1400){schedule(500);return;}"

                + "  lastHandled=turnKey; window.__danLivePending=null;"

                + "  try{"
                + "   if(window.AndroidRadio&&window.AndroidRadio.assistantReadyForPage){"
                + "    window.AndroidRadio.assistantReadyForPage(page,text);"
                + "   }"
                + "  }catch(e){}"
                + " }"

                + " function schedule(ms){"
                + "  clearTimeout(timer);"
                + "  timer=setTimeout(check,ms||1000);"
                + " }"

                + " new MutationObserver(function(){"
                + "  schedule(900);"
                + " }).observe(document.documentElement,{"
                + "  childList:true,"
                + "  subtree:true,"
                + "  characterData:true"
                + " });"

                + " lastHandled=document.querySelectorAll('[data-message-author-role=user]').length+':'+latestResponse();"
                + " setInterval(check,2000);"
                + "})();";

        webView.evaluateJavascript(script, null);
    }

    @Override
    public void onBackPressed() {
        if (webVoiceRequested) { stopWebVoice("LIVE chiuso"); return; }
        if (liveEnabled) { stopLiveMode("Live fermato"); return; }
        if (luminexVisible) {
            WebView activeLuminex = getActiveLuminexWebView();
            if (activeLuminex != null && activeLuminex.canGoBack()) {
                activeLuminex.goBack();
            } else {
                toggleLuminexView();
            }
            return;
        }

        if (popupWebView != null) {
            if (popupWebView.canGoBack()) {
                popupWebView.goBack();
            } else {
                closePopupWebView();
            }
            return;
        }

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

    private void startChatLiveRefresh() {
        // Il tablet resta il dispositivo di lavoro. Solo gli schermi da telefono
        // fanno il refresh automatico della conversazione condivisa.
        if (getResources().getConfiguration().smallestScreenWidthDp >= 600) return;

        chatLiveRefreshRunning = true;
        chatLiveRefreshHandler.removeCallbacks(chatLiveRefreshRunnable);
        chatLiveRefreshHandler.postDelayed(chatLiveRefreshRunnable, 4000L);
    }

    private void stopChatLiveRefresh() {
        chatLiveRefreshRunning = false;
        chatLiveRefreshHandler.removeCallbacks(chatLiveRefreshRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
        if (driveSync != null && driveSync.configured()) syncDrive(false, false);
        syncPersonalFolder(false);
        startLuminexPolling();
        // DAN 1.98: refresh automatico chat disattivato.

        // v1.21: keep-screen-on sulla view, meno invasivo per multi-window.
        if (webContainer != null) {
            webContainer.setKeepScreenOn(true);
        }

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
                : "Tocca il microfono per parlare");

        if (handsFreeEnabled && !manualCapture && !headsetRecording && !ttsSpeaking) {
            scheduleHandsFreeRestart(250L);
        }
        updateAttachmentRecoveryStatus();
    }

    @Override
    protected void onPause() {
        foreground = false;
        if (webVoiceRequested) stopWebVoice("LIVE chiuso · app in background");
        if (liveEnabled) stopLiveMode("Live fermato · app in background");
        stopChatLiveRefresh();

        // DAN 1.94: Luminex deve continuare a ricevere comandi anche quando
        // DAN passa in background, per esempio mentre Termux/Bridge è visibile.

        // Fuori da Dan Android torna a gestire normalmente lo spegnimento schermo.
        if (webContainer != null) {
            webContainer.setKeepScreenOn(false);
        }

        // Dan deve lasciare completamente liberi microfono e controlli cuffie
        // quando l'utente passa a Brave, YouTube, WhatsApp, ecc.
        if (speechRecognizer != null && recognizerSessionActive) {
            try { speechRecognizer.cancel(); } catch (Exception ignored) {}
        }

        recognizerSessionActive = false;
        listening = false;
        manualCapture = false;
        manualStopPending = false;
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
        copyChatToken++;
        copyChatHandler.removeCallbacksAndMessages(null);
        if (webVoiceRequested) stopWebVoice("LIVE chiuso");
        for (Map.Entry<String, ChatPrivateState> entry
                : privateChats.entrySet()) {

            savePrivateState(
                    entry.getKey(),
                    entry.getValue()
            );
        }

        stopLuminexPolling();
        stopChatLiveRefresh();
        closePopupWebView();
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

        attachmentGeneration++;
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }

        for (WebView tab : new ArrayList<>(luminexTabs)) {
            if (tab == null || tab == luminexWebView) continue;
            try {
                webContainer.removeView(tab);
                tab.stopLoading();
                tab.loadUrl("about:blank");
                tab.clearHistory();
                tab.removeAllViews();
                tab.destroy();
            } catch (Exception ignored) {}
        }
        luminexTabs.clear();

        for (ChatPrivateState state :
                new ArrayList<>(privateChats.values())) {

            for (WebView tab : new ArrayList<>(state.tabs)) {
                if (tab == null) continue;

                try {
                    webContainer.removeView(tab);
                    tab.stopLoading();
                    tab.loadUrl("about:blank");
                    tab.clearHistory();
                    tab.removeAllViews();
                    tab.destroy();
                } catch (Exception ignored) {
                }
            }

            state.tabs.clear();
        }

        privateChats.clear();

        if (luminexWebView != null) {
            webContainer.removeView(luminexWebView);
            luminexWebView.stopLoading();
            luminexWebView.loadUrl("about:blank");
            luminexWebView.clearHistory();
            luminexWebView.removeAllViews();
            luminexWebView.destroy();
            luminexWebView = null;
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
