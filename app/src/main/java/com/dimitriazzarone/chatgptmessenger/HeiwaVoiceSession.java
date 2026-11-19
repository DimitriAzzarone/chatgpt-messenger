package com.dimitriazzarone.chatgptmessenger;

/** Ticketed foreground voice loop. Audio adapters cannot submit late/duplicate turns. */
final class HeiwaVoiceSession {
    enum State { IDLE, LISTENING, WAITING, SPEAKING }
    interface Ports {
        void listen(long ticket);void cancelCapture();void speak(long ticket,String text);void cancelSpeech();
        void text(String text);void state(State state,String message);
    }
    private final Ports ports;private State state=State.IDLE;private long ticket;
    HeiwaVoiceSession(Ports ports){this.ports=ports;}
    boolean active(){return state!=State.IDLE;}
    boolean speaking(){return state==State.SPEAKING;}
    boolean canListen(long from){return from==ticket&&state==State.LISTENING;}
    private void listen(){state=State.LISTENING;long next=++ticket;ports.state(state,"Ti ascolto…");ports.listen(next);}
    void start(){if(!active())listen();}
    void heard(long from,String value){if(from!=ticket||state!=State.LISTENING)return;String text=value==null?"":value.trim();
        if(text.isEmpty()){silence(from);return;}state=State.WAITING;ticket++;ports.cancelCapture();ports.state(state,"Preparo la risposta…");ports.text(text);}
    void silence(long from){if(from==ticket&&state==State.LISTENING){ticket++;ports.cancelCapture();listen();}}
    void partial(long from,String text){if(from==ticket&&state==State.LISTENING)ports.state(state,text==null||text.trim().isEmpty()?"Ti ascolto…":"Ti ascolto · "+HeiwaPrompt.clip(text,90));}
    void reply(String text){if(state!=State.WAITING)return;state=State.SPEAKING;long next=++ticket;ports.state(state,"HEIWA sta parlando · ■ per interrompere");ports.speak(next,text);}
    void done(long from){if(from==ticket&&state==State.SPEAKING){ticket++;ports.cancelSpeech();listen();}}
    void interrupt(){if(state==State.SPEAKING){ticket++;ports.cancelSpeech();listen();}}
    void resume(){if(state==State.WAITING)listen();}
    void fail(long from,String reason){if(from==ticket&&active())stop(reason);}
    void stop(String reason){state=State.IDLE;ticket++;ports.cancelCapture();ports.cancelSpeech();ports.state(state,reason);}
}
