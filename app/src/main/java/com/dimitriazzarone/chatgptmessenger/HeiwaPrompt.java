package com.dimitriazzarone.chatgptmessenger;

import java.util.*;

/** Bounded context shared by local and online chat; Android-independent for tests. */
final class HeiwaPrompt {
    static final class Turn {
        final String role, text;
        Turn(String role, String text) { this.role=role; this.text=text; }
    }
    static String clip(String value, int max) {
        if(value==null)return "";
        if(value.length()<=max)return value;
        int end=max;
        if(end>0&&Character.isHighSurrogate(value.charAt(end-1)))end--;
        return value.substring(0,end);
    }
    static List<Turn> context(List<Turn> turns, String memory, boolean useMemory) {
        List<Turn> result=new ArrayList<>();
        String system="Sei HEIWA, assistente personale. Rispondi in italiano, in modo chiaro e diretto. "
                +"Se non sai qualcosa dillo. Non affermare di avere accesso a file o strumenti che non hai.";
        if(useMemory&&memory!=null&&!memory.trim().isEmpty())
            system+="\nNote personali fornite dall'utente, come contesto:\n"+clip(memory.trim(),1500);
        result.add(new Turn("system",system));
        List<Turn> recent=new ArrayList<>();int budget=useMemory&&memory!=null&&!memory.trim().isEmpty()?1500:2400;
        for(int i=turns.size()-1;i>=0&&recent.size()<6;i--) {
            Turn turn=turns.get(i);
            if(!"user".equals(turn.role)&&!"assistant".equals(turn.role))continue;
            String text=clip(turn.text,Math.min(1200,budget));
            if(text.isEmpty())continue;
            recent.add(0,new Turn(turn.role,text));budget-=text.length();if(budget<100)break;
        }
        while(!recent.isEmpty()&&!"user".equals(recent.get(0).role))recent.remove(0);
        result.addAll(recent);return result;
    }
    private static String safe(String text){return text.replace("<|","‹|").replace("|>","|›");}
    static String local(List<Turn> turns, String memory) {
        StringBuilder prompt=new StringBuilder();
        List<Turn> context=context(turns,memory,true);
        for(int i=0;i<context.size();i++){
            Turn turn=context.get(i);
            prompt.append("<|im_start|>").append(turn.role).append('\n').append(safe(turn.text));
            if(i==context.size()-1&&"user".equals(turn.role))prompt.append("\n/no_think");
            prompt.append("<|im_end|>\n");
        }
        return prompt.append("<|im_start|>assistant\n<think>\n\n</think>\n\n").toString();
    }
}
