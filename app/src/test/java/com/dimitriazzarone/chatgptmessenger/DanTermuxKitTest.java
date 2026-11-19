package com.dimitriazzarone.chatgptmessenger;

import android.content.*;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.*;
import java.util.regex.*;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class DanTermuxKitTest {
    @Test public void bootstrapUsesOnlyExplicitTermuxServiceAndBundledBytes()throws Exception{
        Context context=RuntimeEnvironment.getApplication();Intent intent=DanTermuxKit.prepare(context);
        assertEquals("com.termux.RUN_COMMAND",intent.getAction());assertEquals("com.termux/com.termux.app.RunCommandService",intent.getComponent().flattenToString());
        assertEquals("/data/data/com.termux/files/usr/bin/bash",intent.getStringExtra("com.termux.RUN_COMMAND_PATH"));
        assertFalse(intent.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND",true));
        String[] args=intent.getStringArrayExtra("com.termux.RUN_COMMAND_ARGUMENTS");assertEquals("-lc",args[0]);assertTrue(args[1].length()<200000);
        Matcher chunks=Pattern.compile("printf '%s' '([A-Za-z0-9+/=]+)' ").matcher(args[1]);int found=0;
        for(String asset:new String[]{"files_bridge.py","setup.sh"}){
            assertTrue(chunks.find());byte[] decoded=Base64.decode(chunks.group(1),Base64.DEFAULT);
            try(InputStream input=context.getAssets().open("termux/"+asset);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1)out.write(buffer,0,n);assertArrayEquals(out.toByteArray(),decoded);
            }found++;
        }
        assertEquals(2,found);assertFalse(chunks.find());assertTrue(args[1].contains("sha256sum -c"));assertFalse(args[1].contains("curl "));
    }
    private Button find(View view,String text){if(view instanceof Button&&text.equals(((Button)view).getText().toString()))return (Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button result=find(((ViewGroup)view).getChildAt(i),text);if(result!=null)return result;}return null;}
    @Test public void absentTermuxNeverStartsServiceOrEnablesArchive(){
        ActivityController<DanFilesActivity> controller=Robolectric.buildActivity(DanFilesActivity.class).create().start().visible();DanFilesActivity activity=controller.get();
        try{
            assertFalse(find(activity.getWindow().getDecorView(),"Apri archivio in Luminex").isEnabled());
            find(activity.getWindow().getDecorView(),"Prepara / avvia il kit integrato").performClick();
            assertNotNull(ShadowAlertDialog.getLatestAlertDialog());assertNull(shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        }finally{controller.destroy();}
    }
}
