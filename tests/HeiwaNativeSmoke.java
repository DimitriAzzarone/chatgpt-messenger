package com.dimitriazzarone.chatgptmessenger;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
public class HeiwaNativeSmoke {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("GGUF path required");
        List<HeiwaPrompt.Turn> turns=Arrays.asList(new HeiwaPrompt.Turn("user","Rispondi con un breve saluto italiano."));
        String reply=DanNativeQwen.answer(args[0],HeiwaPrompt.local(turns,"Preferisco l'italiano."),()->false);
        if(reply.isBlank()||reply.contains("\ufffd"))throw new AssertionError("Invalid native response");
        System.out.println("Qwen3 real-model smoke reply: "+reply);
        AtomicBoolean canceled=new AtomicBoolean(false);AtomicBoolean finished=new AtomicBoolean(false);
        Thread generation=new Thread(()->{
            try{DanNativeQwen.answer(args[0],HeiwaPrompt.local(Arrays.asList(new HeiwaPrompt.Turn("user","Scrivi una lunga storia.")),""),canceled::get);}
            catch(Exception expected){}finally{finished.set(true);}
        });
        generation.start();Thread.sleep(200);canceled.set(true);DanNativeQwen.cancel();generation.join(15000);
        if(!finished.get())throw new AssertionError("Native generation did not cancel");
        DanNativeQwen.unload();
        System.out.println("Native model load, UTF-8 response, cancellation and unload passed");
    }
}
