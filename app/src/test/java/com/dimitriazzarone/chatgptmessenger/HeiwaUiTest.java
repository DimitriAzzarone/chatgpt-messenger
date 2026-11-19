package com.dimitriazzarone.chatgptmessenger;

import android.app.AlertDialog;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,qualifiers="w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HeiwaUiTest {
    private ActivityController<HeiwaActivity> controller;
    private View description(View root,String text){
        if(text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View found=description(group.getChildAt(i),text);if(found!=null)return found;}}
        return null;
    }
    private boolean containsText(View root,String text){
        if(root instanceof TextView&&((TextView)root).getText().toString().contains(text))return true;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)if(containsText(group.getChildAt(i),text))return true;}
        return false;
    }
    private void capture(HeiwaActivity activity,int width,int height,String name)throws Exception{
        View decor=activity.getWindow().getDecorView();
        for(int i=0;i<3;i++){shadowOf(Looper.getMainLooper()).idle();decor.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));decor.layout(0,0,width,height);}
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);decor.draw(new Canvas(bitmap));
        File directory=new File("build/reports/heiwa-ui");assertTrue(directory.isDirectory()||directory.mkdirs());
        try(OutputStream output=new FileOutputStream(new File(directory,name+".png"))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
    }
    @After public void destroy(){if(controller!=null)controller.pause().stop().destroy();}
    @Test public void phoneEmptyChatAndMemoryMenuAreUsable()throws Exception{
        controller=Robolectric.buildActivity(HeiwaActivity.class).setup().visible();HeiwaActivity activity=controller.get();
        capture(activity,412,915,"phone-home");View decor=activity.getWindow().getDecorView();
        EditText composer=(EditText)description(decor,"Messaggio a HEIWA");assertNotNull(composer);assertTrue(composer.getMeasuredWidth()>180);
        View send=description(decor,"Invia messaggio a HEIWA");assertNotNull(send);assertEquals(View.VISIBLE,send.getVisibility());
        int[] position=new int[2];send.getLocationInWindow(position);assertTrue(position[0]+send.getWidth()<=412);assertTrue(position[1]+send.getHeight()<=915);
        View menu=description(decor,"Opzioni HEIWA");assertNotNull(menu);menu.performClick();shadowOf(Looper.getMainLooper()).idle();
        AlertDialog options=ShadowAlertDialog.getLatestAlertDialog();assertTrue(options.isShowing());
        View memory=description(options.getWindow().getDecorView(),"Memoria personale e JSON");assertNotNull(memory);memory.performClick();
        AlertDialog memoryDialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(memoryDialog.getListView());assertTrue(memoryDialog.getListView().getAdapter().getCount()>=5);
    }
    @Test @Config(qualifiers="w1280dp-h800dp-mdpi") public void tabletConversationFormatsMarkdownAndPreservesComposer()throws Exception{
        File file=new File(RuntimeEnvironment.getApplication().getFilesDir(),"heiwa-conversation.json");
        String turns="[{\"role\":\"user\",\"text\":\"Mi aiuti a organizzare le priorità di oggi?\"},{\"role\":\"assistant\",\"text\":\"Partiamo da **tre cose importanti**.\\n\\n1. Scegli l’impegno principale.\\n2. Riserva uno spazio per concentrarti.\\n3. Lascia un margine per gli imprevisti.\\n\\nQual è la prima cosa che vuoi affrontare?\"}]";
        try(OutputStream output=new FileOutputStream(file)){output.write(turns.getBytes(StandardCharsets.UTF_8));}
        controller=Robolectric.buildActivity(HeiwaActivity.class).setup().visible();HeiwaActivity activity=controller.get();
        capture(activity,1280,800,"tablet-conversation");View decor=activity.getWindow().getDecorView();
        assertTrue(containsText(decor,"tre cose importanti"));assertFalse(containsText(decor,"**tre cose importanti**"));
        EditText composer=(EditText)description(decor,"Messaggio a HEIWA");assertNotNull(composer);assertEquals("",composer.getText().toString());
        assertTrue(composer.getWidth()>500&&composer.getWidth()<900);
    }
}
