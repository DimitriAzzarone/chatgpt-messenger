package com.dimitriazzarone.chatgptmessenger;

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
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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

    private WebView webView;
    private FrameLayout webContainer;
    private ProgressBar progressBar;
    private TextView statusText;
    private Button micButton;
    private Button voiceButton;

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean listening = false;
    private boolean pressToTalkRequested = false;
    private String lastUrl = HOME;

    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String lastSpokenText = "";

    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildInterface();
        createTextToSpeech();
        createSpeechRecognizer();
        createWebView();

        webView.loadUrl(HOME);
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
        title.setText("  ChatGPT Radio");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);

        voiceButton = makeButton("🔊");
        voiceButton.setTextSize(18);

        Button back = makeButton("‹");
        Button reload = makeButton("↻");

        topBar.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        topBar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
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
        statusText.setText("Tieni premuto il microfono per parlare");
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

        micButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pressToTalkRequested = true;
                    startPressToTalk();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    pressToTalkRequested = false;
                    stopPressToTalk();
                    v.performClick();
                    return true;
            }
            return false;
        });
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

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    runOnUiThread(() -> statusText.setText("🔊 Lettura risposta…"));
                }

                @Override
                public void onDone(String utteranceId) {
                    runOnUiThread(() -> statusText.setText(
                            "Tieni premuto il microfono per parlare"));
                }

                @Override
                public void onError(String utteranceId) {
                    runOnUiThread(() -> statusText.setText(
                            "Errore nella sintesi vocale"));
                }
            });
        });
    }

    private void restoreSavedVoice() {
        if (tts == null) return;

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedName = prefs.getString(PREF_TTS_VOICE, null);
        if (savedName == null) return;

        Set<Voice> voices = tts.getVoices();
        if (voices == null) return;

        for (Voice voice : voices) {
            if (savedName.equals(voice.getName())) {
                tts.setVoice(voice);
                return;
            }
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

        Collections.sort(voices, Comparator.comparing(Voice::getName));

        String[] labels = new String[voices.size()];
        int checked = -1;
        Voice currentVoice = tts.getVoice();

        for (int i = 0; i < voices.size(); i++) {
            Voice voice = voices.get(i);

            String localeLabel = voice.getLocale() != null
                    ? voice.getLocale().getDisplayName()
                    : "";

            String type = voice.isNetworkConnectionRequired()
                    ? "online"
                    : "locale";

            labels[i] = voice.getName()
                    + (localeLabel.isEmpty() ? "" : " — " + localeLabel)
                    + " (" + type + ")";

            if (currentVoice != null
                    && currentVoice.getName().equals(voice.getName())) {
                checked = i;
            }
        }

        final List<Voice> finalVoices = voices;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Scegli la voce")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    Voice selected = finalVoices.get(which);
                    int result = tts.setVoice(selected);

                    if (result == TextToSpeech.SUCCESS) {
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                                .edit()
                                .putString(PREF_TTS_VOICE, selected.getName())
                                .apply();

                        tts.speak(
                                "Questa è la voce selezionata.",
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

        String cleaned = text.trim();
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
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                false);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                1);

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                listening = true;
                applyMicStyle(true);
                statusText.setText("🎙 Ti ascolto… rilascia per inviare");
            }

            @Override
            public void onBeginningOfSpeech() {
                statusText.setText("🎙 Parla…");
            }

            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                statusText.setText("Trascrivo…");
            }

            @Override
            public void onError(int error) {
                listening = false;
                applyMicStyle(false);

                String msg;
                switch (error) {
                    case SpeechRecognizer.ERROR_NO_MATCH:
                        msg = "Non ho capito. Riprova.";
                        break;
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        msg = "Non ho sentito parlare.";
                        break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        msg = "Permesso microfono mancante.";
                        break;
                    default:
                        msg = "Errore riconoscimento vocale: " + error;
                }

                statusText.setText(msg);
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                applyMicStyle(false);

                ArrayList<String> matches =
                        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

                if (matches == null || matches.isEmpty()) {
                    statusText.setText("Nessun testo riconosciuto");
                    return;
                }

                String text = matches.get(0).trim();

                if (text.isEmpty()) {
                    statusText.setText("Nessun testo riconosciuto");
                    return;
                }

                statusText.setText("Invio a ChatGPT…");
                injectTextAndSend(text);
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void startPressToTalk() {
        if (speechRecognizer == null) return;

        if (tts != null) {
            tts.stop();
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_AUDIO);
            return;
        }

        startListening();
    }

    private void startListening() {
        try {
            speechRecognizer.startListening(speechIntent);
        } catch (Exception e) {
            statusText.setText("Impossibile avviare il microfono");
        }
    }

    private void stopPressToTalk() {
        if (speechRecognizer != null && listening) {
            statusText.setText("Trascrivo…");
            speechRecognizer.stopListening();
        }
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
                if (pressToTalkRequested) {
                    startListening();
                }
            } else {
                statusText.setText("Microfono non autorizzato");
            }
        }
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

                Intent intent;
                try {
                    intent = fileChooserParams.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }

                try {
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

            Uri[] results = WebChromeClient.FileChooserParams.parseResult(
                    resultCode,
                    data
            );

            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        }
    }

    private class NativeBridge {
        @JavascriptInterface
        public void assistantReady(String text) {
            speakAssistantText(text);
        }
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

    private void injectAssistantObserver() {
        if (webView == null) return;

        String script =
                "(function(){" +
                " if(window.__radioTtsInstalled)return;" +
                " window.__radioTtsInstalled=true;" +
                " let timer=null;" +

                " function generating(){" +
                "  return Array.from(document.querySelectorAll('button')).some(b=>{" +
                "   const a=((b.getAttribute('aria-label')||'')+' '+(b.innerText||'')).toLowerCase();" +
                "   return a.includes('stop generating')||" +
                "          a.includes('interrompi generazione')||" +
                "          a.includes('stop streaming');" +
                "  });" +
                " }" +

                " function latestAssistantText(){" +
                "  const msgs=Array.from(document.querySelectorAll(\"[data-message-author-role='assistant']\"));" +
                "  if(!msgs.length)return '';" +
                "  return (msgs[msgs.length-1].innerText||'').trim();" +
                " }" +

                " let lastSent=latestAssistantText();" +

                " function scheduleCheck(){" +
                "  clearTimeout(timer);" +
                "  timer=setTimeout(()=>{" +
                "   if(generating()){" +
                "    scheduleCheck();" +
                "    return;" +
                "   }" +
                "   const text=latestAssistantText();" +
                "   if(!text||text===lastSent)return;" +
                "   lastSent=text;" +
                "   try{" +
                "    if(window.AndroidRadio&&window.AndroidRadio.assistantReady){" +
                "     window.AndroidRadio.assistantReady(text);" +
                "    }" +
                "   }catch(e){}" +
                "  },1200);" +
                " }" +

                " new MutationObserver(()=>{" +
                "  scheduleCheck();" +
                " }).observe(document.documentElement,{" +
                "  childList:true,subtree:true,characterData:true" +
                " });" +
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

    @Override
    protected void onDestroy() {
        if (speechRecognizer != null) {
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

        super.onDestroy();
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
