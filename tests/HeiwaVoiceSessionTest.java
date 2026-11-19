package com.dimitriazzarone.chatgptmessenger;
public class HeiwaVoiceSessionTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static final class Audio implements HeiwaVoiceSession.Ports {
        long listening,speaking;int captures,spoken,canceledCapture,canceledSpeech,sent;String last;
        public void listen(long id){listening=id;captures++;}public void cancelCapture(){canceledCapture++;}
        public void speak(long id,String text){speaking=id;spoken++;}public void cancelSpeech(){canceledSpeech++;}
        public void text(String text){sent++;last=text;}public void state(HeiwaVoiceSession.State state,String text){}
    }
    public static void main(String[] args){
        Audio audio=new Audio();HeiwaVoiceSession session=new HeiwaVoiceSession(audio);
        session.start();long first=audio.listening;check(session.active());
        session.heard(first,"  Ciao  ");check(audio.sent==1);check(audio.last.equals("Ciao"));check(!session.canListen(first));
        session.heard(first,"duplicato");check(audio.sent==1);
        session.reply("Buongiorno");long utterance=audio.speaking;check(session.speaking());check(audio.captures==1);
        session.heard(first,"eco");check(audio.sent==1);
        session.done(utterance);check(audio.captures==2);check(!session.speaking());
        session.done(utterance);check(audio.captures==2);
        long second=audio.listening;session.silence(second);check(audio.captures==3);session.fail(second,"late");check(session.active());
        long third=audio.listening;session.heard(third,"domanda");session.reply("Risposta lunga");long oldSpeech=audio.speaking;
        session.interrupt();check(audio.captures==4);session.done(oldSpeech);check(audio.captures==4);
        long beforeStop=audio.listening;session.stop("X");check(!session.active());session.heard(beforeStop,"late audio");session.reply("late answer");check(audio.sent==2);check(!session.active());
        session.start();session.fail(beforeStop,"old error");check(session.active());session.fail(audio.listening,"microfono negato");check(!session.active());
        System.out.println("HEIWA voice: continuous turns, no capture during speech, interrupt, X, duplicate and late-callback isolation passed");
    }
}
