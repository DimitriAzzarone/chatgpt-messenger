package com.dimitriazzarone.chatgptmessenger;

import android.Manifest;
import android.app.Application;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Looper;
import android.webkit.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class WebAudioPermissionTest {
    private ActivityController<MainActivity> controller;
    static final class Request extends PermissionRequest {
        final Uri origin;boolean denied;String[] granted;
        Request(String origin){this.origin=Uri.parse(origin);}
        @Override public Uri getOrigin(){return origin;}
        @Override public String[] getResources(){return new String[]{RESOURCE_AUDIO_CAPTURE};}
        @Override public void grant(String[] resources){granted=resources;}
        @Override public void deny(){denied=true;}
    }
    private MainActivity setup(){
        // Do not resume MainActivity: no real Luminex polling/network jobs in tests.
        controller=Robolectric.buildActivity(MainActivity.class).create();MainActivity activity=controller.get();
        ReflectionHelpers.setField(activity,"foreground",true);ReflectionHelpers.setField(activity,"webVoiceRequested",true);
        WebView web=ReflectionHelpers.getField(activity,"webView");web.loadUrl("https://chatgpt.com/c/test");return activity;
    }
    @After public void destroy(){if(controller!=null)controller.destroy();}
    @Test public void missingAndroidPermissionStaysPendingThenGrantsAudio(){
        MainActivity activity=setup();Application app=RuntimeEnvironment.getApplication();shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);
        WebView web=ReflectionHelpers.getField(activity,"webView");Request request=new Request("https://chatgpt.com");web.getWebChromeClient().onPermissionRequest(request);
        assertFalse(request.denied);assertNull(request.granted);assertSame(request,ReflectionHelpers.getField(activity,"pendingPageAudioPermission"));
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
        activity.onRequestPermissionsResult(3002,new String[]{Manifest.permission.RECORD_AUDIO},new int[]{PackageManager.PERMISSION_GRANTED});
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
        assertFalse(request.denied);assertArrayEquals(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE},request.granted);
    }
    @Test public void unrelatedOriginAndBackgroundCannotAcquireMicrophone(){
        MainActivity activity=setup();shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        WebView web=ReflectionHelpers.getField(activity,"webView");Request foreign=new Request("https://example.com");web.getWebChromeClient().onPermissionRequest(foreign);assertTrue(foreign.denied);
        ReflectionHelpers.setField(activity,"foreground",false);Request background=new Request("https://chatgpt.com");web.getWebChromeClient().onPermissionRequest(background);assertTrue(background.denied);
    }
}
