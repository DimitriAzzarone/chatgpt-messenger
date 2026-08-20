package com.dimitriazzarone.chatgptmessenger;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;

public class MainActivity extends Activity {
    private static final String HOME="https://chatgpt.com/", CHANNEL_ID="chatgpt_responses";
    private static final int REQ_AUDIO=2001, REQ_NOTIFICATIONS=2002;
    private WebView webView; private FrameLayout webContainer; private ProgressBar progressBar;
    private ToggleButton autoReadButton; private boolean autoReadEnabled=true; private String lastUrl=HOME;
    private PermissionRequest pendingWebPermission;

    @Override public void onCreate(Bundle b){ super.onCreate(b); createNotificationChannel(); requestNotificationPermissionIfNeeded(); buildInterface(); createWebView(); webView.loadUrl(HOME); }

    private void buildInterface(){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(11,20,26));
        LinearLayout top=new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(8),dp(4),dp(8),dp(4)); top.setBackgroundColor(Color.rgb(32,44,51));
        TextView logo=new TextView(this); logo.setText("AI"); logo.setGravity(Gravity.CENTER); logo.setTextColor(Color.rgb(6,44,37)); logo.setBackgroundColor(Color.rgb(0,168,132));
        TextView title=new TextView(this); title.setText("  ChatGPT"); title.setTextColor(Color.WHITE); title.setTextSize(17);
        Button back=button("‹"), reload=button("↻");
        top.addView(logo,new LinearLayout.LayoutParams(dp(38),dp(38))); top.addView(title,new LinearLayout.LayoutParams(0,dp(44),1)); top.addView(back,new LinearLayout.LayoutParams(dp(44),dp(44))); top.addView(reload,new LinearLayout.LayoutParams(dp(44),dp(44)));
        progressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progressBar.setMax(100); progressBar.setVisibility(View.GONE);
        webContainer=new FrameLayout(this);
        LinearLayout bottom=new LinearLayout(this); bottom.setGravity(Gravity.CENTER_VERTICAL); bottom.setPadding(dp(8),dp(4),dp(8),dp(4)); bottom.setBackgroundColor(Color.rgb(32,44,51));
        TextView hint=new TextView(this); hint.setText("Enter = invia"); hint.setTextColor(Color.LTGRAY); hint.setTextSize(11);
        autoReadButton=new ToggleButton(this); autoReadButton.setTextOff("VOCE OFF"); autoReadButton.setTextOn("VOCE ON"); autoReadButton.setChecked(true);
        bottom.addView(hint,new LinearLayout.LayoutParams(0,dp(46),1)); bottom.addView(autoReadButton,new LinearLayout.LayoutParams(dp(108),dp(46)));
        root.addView(top); root.addView(progressBar,new LinearLayout.LayoutParams(-1,dp(3))); root.addView(webContainer,new LinearLayout.LayoutParams(-1,0,1)); root.addView(bottom,new LinearLayout.LayoutParams(-1,dp(54))); setContentView(root);
        back.setOnClickListener(v->{if(webView.canGoBack())webView.goBack();}); reload.setOnClickListener(v->webView.reload()); autoReadButton.setOnCheckedChangeListener((btt,c)->{autoReadEnabled=c; updateAutoReadSetting();});
    }
    private Button button(String t){ Button b=new Button(this); b.setText(t); b.setTextColor(Color.WHITE); b.setTextSize(21); b.setBackgroundColor(Color.TRANSPARENT); return b; }

    private void createWebView(){
        webView=new WebView(this); webContainer.addView(webView,new FrameLayout.LayoutParams(-1,-1)); webView.addJavascriptInterface(new NativeBridge(),"AndroidMessenger");
        WebSettings s=webView.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true); s.setAllowFileAccess(false); s.setAllowContentAccess(true); s.setMediaPlaybackRequiresUserGesture(false); s.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager c=CookieManager.getInstance(); c.setAcceptCookie(true); c.setAcceptThirdPartyCookies(webView,true);
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView v,int p){progressBar.setProgress(p); progressBar.setVisibility(p>=100?View.GONE:View.VISIBLE);} 
            @Override public void onPermissionRequest(PermissionRequest r){ runOnUiThread(()->handleWebPermissionRequest(r)); }
        });
        webView.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return false;}
            @Override public void onPageStarted(WebView v,String u,android.graphics.Bitmap f){lastUrl=u;}
            @Override public void onPageFinished(WebView v,String u){lastUrl=u; injectEnhancements();}
            @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail d){String u=lastUrl; webContainer.removeView(webView); webView.destroy(); createWebView(); webView.loadUrl(u); return true;}
        });
    }

    private void handleWebPermissionRequest(PermissionRequest r){
        boolean audio=false; for(String x:r.getResources()) if(PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(x)) audio=true;
        if(!audio){r.deny();return;}
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) r.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        else { pendingWebPermission=r; requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_AUDIO); }
    }
    @Override public void onRequestPermissionsResult(int rc,String[] p,int[] g){ super.onRequestPermissionsResult(rc,p,g); if(rc==REQ_AUDIO&&pendingWebPermission!=null){ if(g.length>0&&g[0]==PackageManager.PERMISSION_GRANTED) pendingWebPermission.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}); else pendingWebPermission.deny(); pendingWebPermission=null; } }

    private void createNotificationChannel(){ if(Build.VERSION.SDK_INT>=26){ NotificationChannel ch=new NotificationChannel(CHANNEL_ID,"Risposte ChatGPT",NotificationManager.IMPORTANCE_DEFAULT); getSystemService(NotificationManager.class).createNotificationChannel(ch); } }
    private void requestNotificationPermissionIfNeeded(){ if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFICATIONS); }
    private void showResponseNotification(String preview){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        String body=(preview==null||preview.trim().isEmpty())?"Nuova risposta disponibile":preview.trim(); if(body.length()>140)body=body.substring(0,140)+"…";
        Intent i=new Intent(this,MainActivity.class); PendingIntent pi=PendingIntent.getActivity(this,0,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL_ID):new Notification.Builder(this); b.setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("ChatGPT ha risposto").setContentText(body).setAutoCancel(true).setContentIntent(pi); ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify((int)(System.currentTimeMillis()%Integer.MAX_VALUE),b.build());
    }
    private class NativeBridge { @JavascriptInterface public void notifyResponse(String p){ runOnUiThread(()->showResponseNotification(p)); } }

    private void injectEnhancements(){
        String js="(function(){if(window.__cgptMessengerInstalled){window.__cgptMessengerAutoRead="+(autoReadEnabled?"true":"false")+";return;}window.__cgptMessengerInstalled=true;window.__cgptMessengerAutoRead="+(autoReadEnabled?"true":"false")+";function n(s){return(s||'').toLowerCase().trim();}function msgs(){return Array.from(document.querySelectorAll(\"[data-message-author-role='assistant']\"));}function rb(e){if(!e||e.tagName!=='BUTTON')return false;const a=n((e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+(e.innerText||''));return a.includes('read aloud')||a.includes('leggi ad alta voce')||a.includes('lettura ad alta voce');}function reads(){return Array.from(document.querySelectorAll('button')).filter(rb);}function gen(){return Array.from(document.querySelectorAll('button')).some(b=>{const x=n((b.getAttribute('aria-label')||'')+' '+(b.innerText||''));return x.includes('stop generating')||x.includes('interrompi generazione')||x.includes('stop streaming');});}let km=msgs().length,kr=reads().length,t=null;function go(){clearTimeout(t);t=setTimeout(()=>{if(gen()){go();return;}const m=msgs(),txt=m.length?(m[m.length-1].innerText||'').trim():'';try{AndroidMessenger.notifyResponse(txt);}catch(e){}if(!window.__cgptMessengerAutoRead)return;const bs=reads();if(!bs.length)return;const b=bs[bs.length-1];if(b.dataset.cgptMessengerRead==='1')return;b.dataset.cgptMessengerRead='1';b.click();},1500);}new MutationObserver(()=>{const mc=msgs().length,rc=reads().length;if(mc>km||rc>kr){km=mc;kr=rc;go();}else if(gen())clearTimeout(t);}).observe(document.documentElement,{childList:true,subtree:true,attributes:true});document.addEventListener('keydown',e=>{if(e.key!=='Enter'||e.shiftKey||e.isComposing)return;const x=e.target;if(!x)return;const ed=x.tagName==='TEXTAREA'||x.isContentEditable||x.getAttribute('contenteditable')==='true';if(!ed)return;const send=document.querySelector(\"button[data-testid='send-button']\")||Array.from(document.querySelectorAll('button')).find(b=>{const a=n((b.getAttribute('aria-label')||'')+' '+(b.getAttribute('data-testid')||''));return a.includes('send')||a.includes('invia');});if(send&&!send.disabled){e.preventDefault();e.stopImmediatePropagation();send.click();}},true);})();";
        webView.evaluateJavascript(js,null);
    }
    private void updateAutoReadSetting(){ if(webView!=null) webView.evaluateJavascript("window.__cgptMessengerAutoRead="+(autoReadEnabled?"true":"false")+";",null); }
    @Override public void onBackPressed(){ if(webView!=null&&webView.canGoBack())webView.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy(){ if(webView!=null)webView.destroy(); super.onDestroy(); }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
}
