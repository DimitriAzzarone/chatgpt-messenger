package com.dimitriazzarone.chatgptmessenger;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class HeiwaGroq {
    private volatile HttpURLConnection active;
    void cancel(){HttpURLConnection connection=active;if(connection!=null)connection.disconnect();}
    private String request(String path,String key,JSONObject body) throws Exception {
        if(key==null||key.trim().isEmpty())throw new IOException("Configura la chiave Groq in Impostazioni");
        HttpURLConnection connection=(HttpURLConnection)new URL("https://api.groq.com/openai/v1/"+path).openConnection();
        active=connection;
        try {
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(60000);
            connection.setRequestProperty("Authorization","Bearer "+key);
            connection.setRequestProperty("Accept","application/json");
            if(body!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream output=connection.getOutputStream()){output.write(bytes);}}
            int status=connection.getResponseCode();
            InputStream source=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            if(source!=null)try(InputStream input=source){byte[] chunk=new byte[8192];int count;while((count=input.read(chunk))!=-1){if(buffer.size()+count>1048576)throw new IOException("Risposta Groq troppo grande");buffer.write(chunk,0,count);}}
            String text=buffer.toString("UTF-8");
            if(status<200||status>=300){String message="Richiesta Groq non riuscita (HTTP "+status+")";
                try{message+=" · "+HeiwaPrompt.clip(new JSONObject(text).getJSONObject("error").optString("message"),240);}catch(JSONException ignored){}
                throw new IOException(message.replace(key,"[chiave]"));}
            return text;
        } finally {connection.disconnect();if(active==connection)active=null;}
    }
    String answer(String key,String model,List<HeiwaPrompt.Turn> turns,String memory,boolean shareMemory) throws Exception {
        JSONArray messages=new JSONArray();
        for(HeiwaPrompt.Turn turn:HeiwaPrompt.context(turns,memory,shareMemory))
            messages.put(new JSONObject().put("role",turn.role).put("content",turn.text));
        JSONObject body=new JSONObject().put("model",model).put("messages",messages).put("max_tokens",512).put("temperature",0.6);
        JSONObject result=new JSONObject(request("chat/completions",key,body));
        String answer=result.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content","").trim();
        if(answer.isEmpty())throw new IOException("Groq non ha restituito una risposta testuale");return answer;
    }
    List<String> models(String key) throws Exception {
        JSONArray data=new JSONObject(request("models",key,null)).getJSONArray("data");List<String> models=new ArrayList<>();
        for(int i=0;i<data.length();i++){JSONObject item=data.getJSONObject(i);if(item.optBoolean("active",true))models.add(item.getString("id"));}
        Collections.sort(models);return models;
    }
}
