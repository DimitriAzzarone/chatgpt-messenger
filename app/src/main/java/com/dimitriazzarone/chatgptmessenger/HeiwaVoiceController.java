package com.dimitriazzarone.chatgptmessenger;

import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.speech.*;
import android.speech.tts.*;
import java.util.*;

/** Android recognition + TTS, in foreground, with listening suspended during playback. */
final class HeiwaVoiceController implements HeiwaVoiceSession.Ports {
    interface Listener {void text(String text);void state(boolean active,boolean speaking,String text);}
    private final Context context;private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final HeiwaVoiceSession session=new HeiwaVoiceSession(this);
    private final TextToSpeech tts;private final AudioManager audio;
    private SpeechRecognizer recognizer;private boolean ready,pending,destroyed,initializationFailed;
    private Runnable listenTask;
    private int failures;private boolean localRecognition;
    private final AudioManager.OnAudioFocusChangeListener focus=change->{if(change==AudioManager.AUDIOFOCUS_LOSS||change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)handler.post(()->stop("Voce interrotta da un’altra app o chiamata"));};
    HeiwaVoiceController(Context context,Listener listener){
        this.context=context;this.listener=listener;audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        tts=new TextToSpeech(context,status->handler.post(()->initialized(status)));
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            @Override public void onStart(String id){}
            @Override public void onDone(String id){handler.post(()->{long ticket=parse(id);if(ticket>=0)session.done(ticket);});}
            @Override public void onError(String id){handler.post(()->{long ticket=parse(id);if(ticket>=0)session.fail(ticket,"Sintesi vocale non disponibile");});}
            @Override public void onError(String id,int code){onError(id);}
        });
    }
    private long parse(String id){try{return Long.parseLong(id.replace("heiwa-",""));}catch(Exception error){return -1;}}
    private void initialized(int status){
        if(destroyed)return;
        if(status!=TextToSpeech.SUCCESS){pending=false;initializationFailed=true;listener.state(false,false,"Voce Android non disponibile: controlla la sintesi vocale nelle impostazioni");return;}
        int language=tts.setLanguage(Locale.ITALIAN);
        if(language==TextToSpeech.LANG_MISSING_DATA||language==TextToSpeech.LANG_NOT_SUPPORTED){pending=false;initializationFailed=true;listener.state(false,false,"Installa una voce italiana nelle impostazioni Android");return;}
        try{Set<Voice> voices=tts.getVoices();if(voices!=null)for(Voice voice:voices)if("it".equals(voice.getLocale().getLanguage())&&!voice.isNetworkConnectionRequired()){tts.setVoice(voice);break;}}catch(RuntimeException ignored){}
        ready=true;if(pending){pending=false;session.start();}
    }
    boolean active(){return pending||session.active();}
    boolean speaking(){return session.speaking();}
    void start(){if(destroyed)return;if(initializationFailed){listener.state(false,false,"Controlla la voce italiana nelle impostazioni Android e riapri HEIWA");return;}failures=0;if(ready)session.start();else{pending=true;listener.state(true,false,"Preparo la voce Android…");}}
    void answer(String text){session.reply(text);}
    void resume(){session.resume();}
    void interrupt(){session.interrupt();}
    void stop(String reason){pending=false;if(listenTask!=null)handler.removeCallbacks(listenTask);session.stop(reason);}
    void destroy(){stop("Voce chiusa");destroyed=true;tts.shutdown();}
    @Override public void state(HeiwaVoiceSession.State state,String text){
        if(state==HeiwaVoiceSession.State.LISTENING)text+=" · "+(localRecognition?"riconoscimento sul dispositivo":"voce Android, può usare Internet");
        listener.state(session.active(),session.speaking(),text);
    }
    @Override public void text(String text){failures=0;listener.text(text);}
    @Override public void listen(long ticket){
        if(listenTask!=null)handler.removeCallbacks(listenTask);
        listenTask=()->{
            if(destroyed||!session.canListen(ticket))return;
            cancelCapture();
            try{
                localRecognition=Build.VERSION.SDK_INT>=31&&SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
                if(!localRecognition&&!SpeechRecognizer.isRecognitionAvailable(context)){session.fail(ticket,"Nessun servizio di riconoscimento vocale Android disponibile");return;}
                recognizer=localRecognition?SpeechRecognizer.createOnDeviceSpeechRecognizer(context):SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(new RecognitionListener(){
                    @Override public void onReadyForSpeech(Bundle b){session.partial(ticket,"");}
                    @Override public void onBeginningOfSpeech(){}
                    @Override public void onRmsChanged(float value){}
                    @Override public void onBufferReceived(byte[] bytes){}
                    @Override public void onEndOfSpeech(){}
                    @Override public void onEvent(int type,Bundle bundle){}
                    @Override public void onResults(Bundle result){ArrayList<String> values=result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);session.heard(ticket,values==null||values.isEmpty()?"":values.get(0));}
                    @Override public void onPartialResults(Bundle result){ArrayList<String> values=result.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);if(values!=null&&!values.isEmpty())session.partial(ticket,values.get(0));}
                    @Override public void onError(int error){
                        if(!session.canListen(ticket))return;
                        if(error==SpeechRecognizer.ERROR_NO_MATCH||error==SpeechRecognizer.ERROR_SPEECH_TIMEOUT){session.silence(ticket);return;}
                        if((error==SpeechRecognizer.ERROR_NETWORK||error==SpeechRecognizer.ERROR_NETWORK_TIMEOUT||error==SpeechRecognizer.ERROR_RECOGNIZER_BUSY)&&++failures<=2){session.silence(ticket);return;}
                        String message=error==SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS?"Microfono negato nelle impostazioni Android":error==SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED||error==SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE?"Riconoscimento italiano non disponibile: controlla le lingue vocali Android":"Riconoscimento vocale non riuscito (codice "+error+")";
                        session.fail(ticket,message);
                    }
                });
                Intent intent=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"it-IT").putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);
                recognizer.startListening(intent);
            }catch(RuntimeException error){session.fail(ticket,"Microfono non disponibile: "+error.getClass().getSimpleName());}
        };
        handler.postDelayed(listenTask,600);
    }
    @Override public void cancelCapture(){if(recognizer!=null){SpeechRecognizer previous=recognizer;recognizer=null;try{previous.cancel();}catch(RuntimeException ignored){}try{previous.destroy();}catch(RuntimeException ignored){}}}
    @Override public void speak(long ticket,String text){
        String spoken=text.replaceAll("(?s)```.*?```"," Blocco di codice mostrato nella chat. ").replaceAll("\\[([^]]+)\\]\\([^)]+\\)","$1").replaceAll("(?m)^#{1,6}\\s*","").replace("**","").replace("`","");
        if(audio!=null&&audio.requestAudioFocus(focus,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){session.fail(ticket,"Audio occupato da un’altra app");return;}
        if(tts.speak(HeiwaPrompt.clip(spoken,3500),TextToSpeech.QUEUE_FLUSH,null,"heiwa-"+ticket)==TextToSpeech.ERROR)session.fail(ticket,"Impossibile leggere la risposta");
    }
    @Override public void cancelSpeech(){try{tts.stop();}catch(RuntimeException ignored){}if(audio!=null)audio.abandonAudioFocus(focus);}
}
