package com.dimitriazzarone.chatgptmessenger;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
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
import android.widget.ToggleButton;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String HOME = "https://chatgpt.com/";
    private static final int REQ_AUDIO = 3001;

    private WebView webView;
    private FrameLayout webContainer;
    private ProgressBar progressBar;
    private Button talkButton;
    private ToggleButton autoReadButton;
    private TextView statusText;

    private SpeechRecognizer speechRecognizer;
    private Intent speechIntent;
    private boolean listening = false;
    private boolean autoReadEnabled = true;
    private String lastUrl = HOME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        buildInterface();
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

        Button back = makeButton("‹");
        Button reload = makeButton("↻");

        topBar.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        topBar.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        topBar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        topBar.addView(reload, new LinearLayout.LayoutParams(dp(44), dp(44)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);

        webContainer = new FrameLayout(this);

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(8), dp(6), dp(8), dp(8));
        bottomBar.setBackgroundColor(Color.rgb(32, 44, 51));

        statusText = new TextView(this);
        statusText.setText("Pronto");
        statusText.setTextColor(Color.LTGRAY);
        statusText.setTextSize(12);
        statusText.setGravity(Gravity.CENTER);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);

        talkButton = new Button(this);
        talkButton.setText("🎙  PARLA");
        talkButton.setTextSize(18);

        autoReadButton = new ToggleButton(this);
        autoReadButton.setTextOff("VOCE OFF");
        autoReadButton.setTextOn("VOCE ON");
        autoReadButton.setChecked(true);

        controls.addView(talkButton, new LinearLayout.LayoutParams(0, dp(56), 2));
        controls.addView(autoReadButton, new LinearLayout.LayoutParams(0, dp(56), 1));

        bottomBar.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));
        bottomBar.addView(controls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        root.addView(topBar);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        root.addView(webContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(90)));

        setContentView(root);

        back.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            }
        });

        reload.setOnClickListener(v -> {
            if (webView != null) {
                webView.reload();
            }
        });

        talkButton.setOnClickListener(v -> toggleSpeechRecognition());

        autoReadButton.setOnCheckedChangeListener((buttonView, checked) -> {
            autoReadEnabled = checked;
            updateAutoReadSetting();
        });
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(21);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private void createSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                    this,
                    "Riconoscimento vocale Android non disponibile",
                    Toast.LENGTH_LONG
            ).show();
            talkButton.setEnabled(false);
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);

        speechIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        );
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault().toLanguageTag()
        );
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                false
        );
        speechIntent.putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                1
        );

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                listening = true;
                talkButton.setText("■  STOP");
                statusText.setText("Ti ascolto…");
            }

            @Override
            public void onBeginningOfSpeech() {
                statusText.setText("Parla…");
            }

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {
                statusText.setText("Trascrivo…");
            }

            @Override
            public void onError(int error) {
                listening = false;
                talkButton.setText("🎙  PARLA");

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
                talkButton.setText("🎙  PARLA");

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

            @Override
            public void onPartialResults(Bundle partialResults) {}

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void toggleSpeechRecognition() {
        if (speechRecognizer == null) {
            return;
        }

        if (listening) {
            speechRecognizer.stopListening();
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_AUDIO
            );
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
                startListening();
            } else {
                statusText.setText("Microfono non autorizzato");
            }
        }
    }

    private void createWebView() {
        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(11, 20, 26));

        webContainer.addView(
                webView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
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
                        progress >= 100 ? View.GONE : View.VISIBLE
                );
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
                injectAutoReadObserver();
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

    private void injectTextAndSend(String text) {
        if (webView == null) {
            return;
        }

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

    private void injectAutoReadObserver() {
        if (webView == null) {
            return;
        }

        String script =
                "(function(){" +
                " if(window.__radioInstalled){" +
                "  window.__radioAutoRead=" + (autoReadEnabled ? "true" : "false") + ";" +
                "  return;" +
                " }" +
                " window.__radioInstalled=true;" +
                " window.__radioAutoRead=" + (autoReadEnabled ? "true" : "false") + ";" +

                " function norm(s){return(s||'').toLowerCase().trim();}" +

                " function readButtons(){" +
                "  return Array.from(document.querySelectorAll('button')).filter(b=>{" +
                "   const a=norm((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'')+' '+(b.innerText||''));" +
                "   return a.includes('read aloud')||a.includes('leggi ad alta voce')||a.includes('lettura ad alta voce');" +
                "  });" +
                " }" +

                " function generating(){" +
                "  return Array.from(document.querySelectorAll('button')).some(b=>{" +
                "   const a=norm((b.getAttribute('aria-label')||'')+' '+(b.innerText||''));" +
                "   return a.includes('stop generating')||a.includes('interrompi generazione')||a.includes('stop streaming');" +
                "  });" +
                " }" +

                " let known=readButtons().length;" +
                " let timer=null;" +

                " function tryRead(){" +
                "  clearTimeout(timer);" +
                "  timer=setTimeout(()=>{" +
                "   if(!window.__radioAutoRead)return;" +
                "   if(generating()){tryRead();return;}" +
                "   const bs=readButtons();" +
                "   if(!bs.length)return;" +
                "   const b=bs[bs.length-1];" +
                "   if(b.dataset.radioRead==='1')return;" +
                "   b.dataset.radioRead='1';" +
                "   b.click();" +
                "  },1500);" +
                " }" +

                " new MutationObserver(()=>{" +
                "  const c=readButtons().length;" +
                "  if(c>known){" +
                "   known=c;" +
                "   tryRead();" +
                "  }else if(generating()){" +
                "   clearTimeout(timer);" +
                "  }" +
                " }).observe(document.documentElement,{" +
                "  childList:true,subtree:true,attributes:true" +
                " });" +
                "})();";

        webView.evaluateJavascript(script, null);
    }

    private void updateAutoReadSetting() {
        if (webView != null) {
            webView.evaluateJavascript(
                    "window.__radioAutoRead=" +
                    (autoReadEnabled ? "true" : "false") +
                    ";",
                    null
            );
        }
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
