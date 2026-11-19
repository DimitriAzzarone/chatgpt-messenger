package com.dimitriazzarone.chatgptmessenger;

import org.json.*;
import java.io.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** Data-only parser for Dan JSONL, arrays of turns, notes and active ChatGPT branches. */
final class HeiwaJsonReader {
    static final class Record {
        final String id,text,title,role;final long time;
        Record(String id,String text,String title,String role,long time){this.id=id;this.text=text;this.title=title;this.role=role;this.time=time;}
    }
    private final Consumer<Record> sink;
    private final BooleanSupplier canceled;
    private final String name;
    private int records;
    private HeiwaJsonReader(String name,BooleanSupplier canceled,Consumer<Record> sink){this.name=name;this.canceled=canceled;this.sink=sink;}
    static int read(InputStream source,String name,BooleanSupplier canceled,Consumer<Record> sink) throws Exception {
        HeiwaJsonReader parser=new HeiwaJsonReader(name,canceled,sink);
        CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try(BufferedReader input=new BufferedReader(new InputStreamReader(source,decoder))){
            if(name.toLowerCase(Locale.ROOT).matches(".*\\.(jsonl|ndjson)$")){
                StringBuilder line=new StringBuilder();long total=0;int ch;
                while((ch=input.read())!=-1){
                    parser.check();if(++total>32*1024*1024L)throw new IOException("JSONL oltre 32 milioni di caratteri");
                    if(ch=='\n'){parser.line(line);line.setLength(0);}else{line.append((char)ch);if(line.length()>1024*1024)throw new IOException("Riga JSONL oltre 1 milione di caratteri");}
                }
                parser.line(line);
            }else{
                StringBuilder text=new StringBuilder();char[] buffer=new char[8192];int count;
                while((count=input.read(buffer))!=-1){parser.check();if(text.length()+count>8*1024*1024)throw new IOException("JSON oltre 8 milioni di caratteri: usa JSONL");text.append(buffer,0,count);}
                parser.visit(parse(text.toString()),0,null);
            }
        }
        if(parser.records==0)throw new IOException("Nessun contenuto di memoria leggibile nel file");return parser.records;
    }
    private static Object parse(String text) throws Exception {
        String clean=text.trim();if(clean.startsWith("\ufeff"))clean=clean.substring(1);
        if(clean.isEmpty()||"{[\"".indexOf(clean.charAt(0))<0)throw new IOException("Il record non inizia con un JSON valido");
        JSONTokener tokenizer=new JSONTokener(clean);Object value=tokenizer.nextValue();
        if(tokenizer.nextClean()!=0)throw new IOException("Dati dopo il JSON");return value;
    }
    private void line(StringBuilder value) throws Exception {String text=value.toString().trim();if(!text.isEmpty())visit(parse(text),0,null);}
    private void check(){if(canceled.getAsBoolean())throw new CancellationException();}
    private static boolean sensitive(String key){return key.toLowerCase(Locale.ROOT).replace("-","_").matches(".*(?:password|passwd|api_?key|secret|access_?token|refresh_?token|private_?key|authorization|bearer|token).*");}
    private void emit(String text,String role,String title,String event,String conversation,long time) throws Exception {
        check();text=text.trim();if(text.isEmpty())return;
        if(text.length()>262144)throw new IOException("Singolo record oltre 262144 caratteri");
        if(++records>50000)throw new IOException("Troppi record in un file");
        String key=role+"\n"+conversation+"\n"+event+"\n"+text;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");StringBuilder hash=new StringBuilder();
        for(byte b:digest.digest(key.getBytes(StandardCharsets.UTF_8)))hash.append(String.format(Locale.ROOT,"%02x",b&255));
        sink.accept(new Record(hash.toString(),text,HeiwaPrompt.clip(title==null||title.isEmpty()?name:title,160),role,time));
    }
    private void visit(Object value,int depth,String inheritedTitle) throws Exception {
        check();if(depth>32)throw new IOException("JSON troppo annidato");
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)visit(array.get(i),depth+1,inheritedTitle);return;}
        if(value instanceof JSONObject){
            JSONObject object=(JSONObject)value;
            String title=object.optString("chat_title",object.optString("title",inheritedTitle==null?name:inheritedTitle));
            if(object.optJSONObject("mapping")!=null){activeBranch(object,title,depth);return;}
            String role=object.optString("role","");JSONObject author=object.optJSONObject("author");if(role.isEmpty()&&author!=null)role=author.optString("role","");
            if("human".equals(role))role="user";if("ai".equals(role))role="assistant";
            if(Arrays.asList("system","developer","tool","function").contains(role))return;
            if("user".equals(role)||"assistant".equals(role)){
                String channel=object.optString("channel","");JSONObject meta=object.optJSONObject("metadata");if(meta!=null)channel=meta.optString("channel",channel);
                if("analysis".equals(channel)||"commentary".equals(channel))return;
                String text=object.optString("text","");Object content=object.opt("content");
                if(text.isEmpty()&&content instanceof String)text=(String)content;
                if(text.isEmpty()&&content instanceof JSONObject){JSONArray parts=((JSONObject)content).optJSONArray("parts");if(parts!=null){StringBuilder joined=new StringBuilder();for(int i=0;i<parts.length();i++)if(parts.opt(i) instanceof String)joined.append(parts.getString(i)).append('\n');text=joined.toString();}}
                emit(text,role,title,object.optString("event_id",object.optString("id","")),object.optString("conversation_url",""),object.optLong("recorded_at_ms",(long)(object.optDouble("create_time",0)*1000)));return;
            }
            for(String field:new String[]{"messages","turns","records","events","memories"}){
                if(object.optJSONArray(field)!=null){visit(object.get(field),depth+1,title);return;}
            }
            StringBuilder flat=new StringBuilder();flatten(object,"",flat,0);emit(flat.toString(),"note",title,"","",0);return;
        }
        if(value instanceof String)emit((String)value,"note",inheritedTitle,"","",0);
    }
    private void activeBranch(JSONObject conversation,String title,int depth) throws Exception {
        JSONObject mapping=conversation.getJSONObject("mapping");String id=conversation.optString("current_node","");
        if(id.isEmpty())throw new IOException("Esportazione ChatGPT senza ramo attivo: non importata");
        Set<String> seen=new HashSet<>();List<JSONObject> ordered=new ArrayList<>();
        while(!id.isEmpty()){
            check();if(!seen.add(id)||seen.size()>50000)throw new IOException("Ramo di conversazione non valido");
            JSONObject node=mapping.optJSONObject(id);if(node==null)throw new IOException("Ramo di conversazione incompleto");
            JSONObject message=node.optJSONObject("message");if(message!=null)ordered.add(message);
            id=node.isNull("parent")?"":node.optString("parent","");
        }
        Collections.reverse(ordered);
        for(JSONObject message:ordered)visit(message,depth+1,title);
    }
    private void flatten(Object value,String path,StringBuilder output,int depth) throws Exception {
        check();if(depth>32||output.length()>262144)throw new IOException("Nota JSON troppo grande o annidata");
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;List<String> keys=new ArrayList<>();Iterator<String> iterator=object.keys();while(iterator.hasNext())keys.add(iterator.next());Collections.sort(keys);
            for(String key:keys)if(!sensitive(key))flatten(object.get(key),path.isEmpty()?key:path+"."+key,output,depth+1);
        }else if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)flatten(array.get(i),path+"["+i+"]",output,depth+1);
        }else if(value!=null&&value!=JSONObject.NULL){output.append(path).append(": ").append(String.valueOf(value)).append('\n');}
    }
}
