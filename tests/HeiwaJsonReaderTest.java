package com.dimitriazzarone.chatgptmessenger;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
public class HeiwaJsonReaderTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static List<HeiwaJsonReader.Record> parse(String body,String name) throws Exception {
        List<HeiwaJsonReader.Record> records=new ArrayList<>();HeiwaJsonReader.read(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),name,()->false,records::add);return records;
    }
    static void rejects(String body,String name) throws Exception {try{parse(body,name);throw new AssertionError("Accepted malformed input");}catch(IOException|org.json.JSONException expected){}}
    public static void main(String[] args) throws Exception {
        String row="{\"event_id\":\"id-1\",\"conversation_url\":\"local/chat\",\"role\":\"user\",\"text\":\"Sono nato a Zürich ☀️\",\"recorded_at_ms\":12345}";
        List<HeiwaJsonReader.Record> one=parse(row+"\n"+row,"A.jsonl");check(one.size()==2);check(one.get(0).id.equals(one.get(1).id));
        check(one.get(0).id.equals(parse(row,"other.jsonl").get(0).id));check(one.get(0).role.equals("user"));check(one.get(0).time==12345);check(one.get(0).text.contains("Zürich ☀️"));
        List<HeiwaJsonReader.Record> notes=parse("{\"role\":\"insegnante\",\"nome\":\"Ada\",\"api_key\":\"TOPSECRET\",\"device_token\":\"SECRET\",\"profilo\":{\"anno\":1980}}","notes.json");
        check(notes.size()==1);check(notes.get(0).text.contains("Ada"));check(notes.get(0).text.contains("1980"));check(!notes.get(0).text.contains("SECRET"));
        List<HeiwaJsonReader.Record> turns=parse("{\"messages\":[{\"role\":\"system\",\"content\":\"IGNORE\"},{\"role\":\"user\",\"content\":\"Domanda\"},{\"role\":\"assistant\",\"content\":\"Risposta\"}]}","array.json");check(turns.size()==2);check(turns.get(1).role.equals("assistant"));
        String exported="{\"title\":\"Titolo\",\"current_node\":\"b\",\"mapping\":{\"a\":{\"parent\":null,\"message\":{\"author\":{\"role\":\"user\"},\"content\":{\"parts\":[\"Domanda\"]}}},\"b\":{\"parent\":\"a\",\"message\":{\"author\":{\"role\":\"assistant\"},\"content\":{\"parts\":[\"Risposta attiva\"]}}},\"alternative\":{\"parent\":\"a\",\"message\":{\"role\":\"assistant\",\"content\":\"NON IMPORTARE\"}}}}";
        List<HeiwaJsonReader.Record> branch=parse(exported,"conversations.json");check(branch.size()==2);check(branch.get(1).text.equals("Risposta attiva"));check(branch.get(0).title.equals("Titolo"));
        rejects(row+"broken","x.json");rejects(row+"\nnot-json","x.jsonl");rejects("{\"mapping\":{}}","conversation.json");
        try{HeiwaJsonReader.read(new ByteArrayInputStream(row.getBytes(StandardCharsets.UTF_8)),"x.json",()->true,r->{});throw new AssertionError();}catch(CancellationException expected){}
        if(args.length>0){List<HeiwaJsonReader.Record> actual=new ArrayList<>();try(InputStream input=new FileInputStream(args[0])){HeiwaJsonReader.read(input,"private.jsonl",()->false,actual::add);}System.out.println("Private local sample parsed: "+actual.size()+" records; no content printed");}
        System.out.println("JSON/JSONL: roles, Unicode, stable IDs, notes, credential fields, active branch, malformed input and cancellation passed");
    }
}
