package com.dimitriazzarone.chatgptmessenger;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

final class DanNativeQwen {
    private static volatile boolean available;
    private static boolean loaded;
    private static synchronized void ensureLibrary(){if(!available){System.loadLibrary("dan_qwen");available=true;}}
    static void cancel(){if(available)nativeCancel();}
    static synchronized String answer(String path,String prompt,BooleanSupplier canceled) throws Exception {
        ensureLibrary();nativeResetCancel();
        if(canceled.getAsBoolean())throw new CancellationException();
        if(!loaded){loaded=nativeLoad(path);if(!loaded)throw new IllegalStateException("Qwen: impossibile caricare il modello sul dispositivo");}
        if(canceled.getAsBoolean())throw new CancellationException();
        byte[] result=nativeGenerate(prompt.getBytes(StandardCharsets.UTF_8),256,false);
        if(canceled.getAsBoolean())throw new CancellationException();
        String text=new String(result,StandardCharsets.UTF_8).trim();
        int thought=text.indexOf("</think>");if(thought>=0)text=text.substring(thought+8).trim();
        if(text.isEmpty())throw new IllegalStateException("Qwen non ha generato una risposta; prova un messaggio più breve");return text;
    }
    static synchronized void unload(){if(available){nativeUnload();loaded=false;}}
    private static native boolean nativeLoad(String path);
    private static native byte[] nativeGenerate(byte[] prompt,int tokens,boolean faithful);
    private static native void nativeUnload();
    private static native void nativeCancel();
    private static native void nativeResetCancel();
}
