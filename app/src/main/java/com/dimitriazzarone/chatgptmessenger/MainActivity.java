package com.dimitriazzarone.chatgptmessenger;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
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

public class MainActivity extends Activity {
    private static final String HOME = "https://chatgpt.com/";

    private WebView webView;
    private FrameLayout webContainer;
    private ProgressBar progressBar;
    private ToggleButton autoReadButton;
    private String lastUrl = HOME;
    private boolean autoReadEnabled = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
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
        topBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        topBar.setBackgroundColor(Color.rgb(32, 44, 51));

        TextView logo = new TextView(this);
        logo.setText("AI");
        logo.setTextColor(Color.rgb(6, 44, 37));
        logo.setTextSize(16);
        logo.setGravity(Gravity.CENTER);
        logo.setBackgroundColor(Color.rgb(0, 168, 132));

        TextView title = new TextView(this);
        title.setText("  ChatGPT Messenger");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);

        Button back = makeButton("‹");
        Button reload = makeButton("↻");

        topBar.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        topBar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        topBar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        topBar.addView(reload, new LinearLayout.LayoutParams(dp(48), dp(48)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setVisibility(View.GONE);

        webContainer = new FrameLayout(this);

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(12), dp(5), dp(12), dp(5));
        bottomBar.setBackgroundColor(Color.rgb(32, 44, 51));

        TextView hint = new TextView(this);
        hint.setText("Enter = invia   •   Shift+Enter = nuova riga");
        hint.setTextColor(Color.rgb(190, 200, 205));
        hint.setTextSize(12);

        autoReadButton = new ToggleButton(this);
        autoReadButton.setTextOff("VOCE OFF");
        autoReadButton.setTextOn("VOCE ON");
        autoReadButton.setChecked(true);

        bottomBar.addView(hint, new LinearLayout.LayoutParams(0, dp(48), 1));
        bottomBar.addView(autoReadButton, new LinearLayout.LayoutParams(dp(110), dp(48)));

        root.addView(topBar);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        root.addView(webContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        setContentView(root);

        back.setOnClickListener(v -> {
            if (webView != null && webView.canGoBack()) webView.goBack();
        });
        reload.setOnClickListener(v -> {
            if (webView != null) webView.reload();
        });
        autoReadButton.setOnCheckedChangeListener((buttonView, checked) -> {
            autoReadEnabled = checked;
            updateAutoReadSetting();
        });
    }

    private Button makeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(22);
        button.setBackgroundColor(Color.TRANSPARENT);
        return button;
    }

    private void createWebView() {
        if (webView != null) {
            webContainer.removeView(webView);
            webView.destroy();
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(11, 20, 26));
        webContainer.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

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
                progressBar.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                lastUrl = url;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                lastUrl = url;
                injectEnhancements();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                String restoreUrl = lastUrl;
                createWebView();
                webView.loadUrl(restoreUrl);
                Toast.makeText(MainActivity.this, "Pagina ripristinata", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
    }

    private void injectEnhancements() {
        if (webView == null) return;

        String script =
            "(function(){" +
            "if(window.__cgptMessengerInstalled){window.__cgptMessengerAutoRead=" + (autoReadEnabled ? "true" : "false") + ";return;}" +
            "window.__cgptMessengerInstalled=true;" +
            "window.__cgptMessengerAutoRead=" + (autoReadEnabled ? "true" : "false") + ";" +
            "function norm(s){return(s||'').toLowerCase().trim();}" +
            "function isReadButton(el){" +
            " if(!el||el.tagName!=='BUTTON')return false;" +
            " const a=norm((el.getAttribute('aria-label')||'')+' '+(el.getAttribute('title')||'')+' '+(el.innerText||''));" +
            " return a.includes('read aloud')||a.includes('leggi ad alta voce')||a.includes('lettura ad alta voce');" +
            "}" +
            "function reads(){return Array.from(document.querySelectorAll('button')).filter(isReadButton);}" +
            "function generating(){" +
            " return Array.from(document.querySelectorAll('button')).some(b=>{" +
            "  const x=norm((b.getAttribute('aria-label')||'')+' '+(b.innerText||''));" +
            "  return x.includes('stop generating')||x.includes('interrompi generazione')||x.includes('stop streaming');" +
            " });" +
            "}" +
            "let known=reads().length;let timer=null;" +
            "function schedule(){" +
            " clearTimeout(timer);" +
            " timer=setTimeout(()=>{" +
            "  if(!window.__cgptMessengerAutoRead)return;" +
            "  if(generating()){schedule();return;}" +
            "  const bs=reads();if(!bs.length)return;" +
            "  const b=bs[bs.length-1];" +
            "  if(b.dataset.cgptMessengerRead==='1')return;" +
            "  b.dataset.cgptMessengerRead='1';b.click();" +
            " },1400);" +
            "}" +
            "new MutationObserver(()=>{" +
            " const c=reads().length;" +
            " if(c>known){known=c;schedule();}" +
            " else if(generating()){clearTimeout(timer);}" +
            "}).observe(document.documentElement,{childList:true,subtree:true,attributes:true});" +
            "document.addEventListener('keydown',e=>{" +
            " if(e.key!=='Enter'||e.shiftKey||e.isComposing)return;" +
            " const t=e.target;if(!t)return;" +
            " const editor=t.tagName==='TEXTAREA'||t.isContentEditable||t.getAttribute('contenteditable')==='true';" +
            " if(!editor)return;" +
            " const send=document.querySelector(\"button[data-testid='send-button']\")||Array.from(document.querySelectorAll('button')).find(b=>{" +
            "  const x=norm((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('data-testid')||''));" +
            "  return x.includes('send')||x.includes('invia');" +
            " });" +
            " if(send&&!send.disabled){e.preventDefault();e.stopImmediatePropagation();send.click();}" +
            "},true);" +
            "})();";

        webView.evaluateJavascript(script, null);
    }

    private void updateAutoReadSetting() {
        if (webView != null) {
            webView.evaluateJavascript(
                    "window.__cgptMessengerAutoRead=" + (autoReadEnabled ? "true" : "false") + ";",
                    null);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onDestroy() {
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
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
