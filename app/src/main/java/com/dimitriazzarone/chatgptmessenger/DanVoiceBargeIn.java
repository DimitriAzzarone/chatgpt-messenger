package com.dimitriazzarone.chatgptmessenger;

import java.text.Normalizer;
import java.util.*;

/** Conservative textual echo guard, not acoustic echo cancellation. */
final class DanVoiceBargeIn {
    private String current="",previous="";private long until;private boolean playing;
    private static String normalized(String text){return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
    void playback(String text){previous=current;current=normalized(text);playing=true;until=0;}
    void finished(long now){playing=false;until=now+1200;}
    void clear(){current="";previous="";playing=false;until=0;}
    boolean accepts(String text,long now){
        String candidate=normalized(text);if(candidate.isEmpty())return false;
        if(!playing&&now>=until)return true;
        String reference=previous+" "+current;
        if((" "+reference+" ").contains(" "+candidate+" "))return false;
        Set<String> spoken=new HashSet<>(Arrays.asList(reference.split(" +")));
        String[] words=candidate.split(" +");int matching=0;
        for(String word:words)if(spoken.contains(word))matching++;
        // Reject a recognition made mostly of the voice currently being played.
        return matching*4<words.length*3;
    }
    boolean interrupts(String partial,long now){
        String value=normalized(partial);
        return accepts(value,now)&&(value.split(" +").length>=2||Arrays.asList("stop","basta","fermati","aspetta").contains(value));
    }
}
