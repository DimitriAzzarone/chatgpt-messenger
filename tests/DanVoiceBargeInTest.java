package com.dimitriazzarone.chatgptmessenger;
public class DanVoiceBargeInTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        DanVoiceBargeIn guard=new DanVoiceBargeIn();
        guard.playback("La memoria locale conserva i tuoi documenti e le preferenze.");
        check(!guard.accepts("memoria locale conserva i tuoi documenti",10));
        check(!guard.interrupts("La memòria locale conserva i documenti",10));
        check(guard.interrupts("aspetta",10));
        check(guard.interrupts("Vorrei cambiare argomento",10));
        check(!guard.interrupts("nuovo",10));
        guard.playback("Adesso possiamo procedere con il prossimo passo.");
        check(!guard.accepts("documenti e le preferenze",10)); // prior chunk tail
        check(!guard.accepts("possiamo procedere",10));
        guard.finished(100);check(!guard.accepts("possiamo procedere",900));
        check(guard.accepts("possiamo procedere",1400));
        guard.clear();check(guard.accepts("possiamo procedere",1));
        check(!guard.accepts("   ",1));
        System.out.println("Dan voice: playback echo, approximate echo, chunk tail, real interruption, tail expiry and reset passed");
    }
}
