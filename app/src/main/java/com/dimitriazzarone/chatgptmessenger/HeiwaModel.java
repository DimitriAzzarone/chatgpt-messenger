package com.dimitriazzarone.chatgptmessenger;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** One explicitly selected Qwen3 GGUF; downloaded/imported bytes must match the publisher digest. */
final class HeiwaModel {
    static final long SIZE=396705472L;
    static final String SHA="ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a";
    static final String URL="https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf";
    private final Context context;
    HeiwaModel(Context context){this.context=context.getApplicationContext();}
    File file(){return new File(context.getFilesDir(),"heiwa-qwen3-0.6b-q4_k_m.gguf");}
    boolean ready(){File f=file();android.content.SharedPreferences p=context.getSharedPreferences("heiwa_model",0);
        return f.isFile()&&f.length()==SIZE&&SHA.equals(p.getString("sha",""))&&p.getLong("mtime",-1)==f.lastModified();}
    void remove(){if(file().exists()&&!file().delete())throw new IllegalStateException("Impossibile rimuovere il modello");context.getSharedPreferences("heiwa_model",0).edit().clear().commit();}
    private void store(InputStream input,BooleanSupplier canceled,IntConsumer progress) throws Exception {
        if(context.getFilesDir().getUsableSpace()<SIZE+64*1024*1024L)throw new IOException("Servono almeno 450 MB liberi per il modello");
        File temp=new File(context.getFilesDir(),"heiwa-model.partial");
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;int last=-1;
            try(OutputStream output=new FileOutputStream(temp)){
                byte[] buffer=new byte[65536];int count;
                while((count=input.read(buffer))!=-1){
                    if(canceled.getAsBoolean())throw new CancellationException();
                    total+=count;if(total>SIZE)throw new IOException("Dimensione modello diversa da quella prevista");
                    output.write(buffer,0,count);digest.update(buffer,0,count);
                    int percent=(int)(total*100/SIZE);if(percent!=last){progress.accept(percent);last=percent;}
                }
            }
            if(canceled.getAsBoolean())throw new CancellationException();
            StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(Locale.ROOT,"%02x",b&255));
            if(total!=SIZE||!SHA.equals(sha.toString()))throw new IOException("Modello incompleto o non corrispondente: verifica SHA-256 fallita");
            if(!temp.renameTo(file()))throw new IOException("Impossibile finalizzare il modello");
            if(!context.getSharedPreferences("heiwa_model",0).edit().putString("sha",SHA).putLong("mtime",file().lastModified()).commit())throw new IOException("Modello copiato ma verifica non salvata");
        }finally{temp.delete();}
    }
    void importFile(Uri uri,BooleanSupplier canceled,IntConsumer progress) throws Exception {
        try(InputStream input=context.getContentResolver().openInputStream(uri)){
            if(input==null)throw new IOException("File non leggibile");store(input,canceled,progress);
        }
    }
    void download(BooleanSupplier canceled,IntConsumer progress) throws Exception {
        String url=URL;
        for(int redirects=0;redirects<8;redirects++){
            if(canceled.getAsBoolean())throw new CancellationException();
            HttpURLConnection connection=(HttpURLConnection)new java.net.URL(url).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent","Dan-Heiwa/1.155.15");int status=connection.getResponseCode();
                if(status>=300&&status<400){String next=connection.getHeaderField("Location");
                    if(next==null)throw new IOException("Reindirizzamento del modello non valido");
                    java.net.URL target=new java.net.URL(new java.net.URL(url),next);
                    if(!"https".equals(target.getProtocol()))throw new IOException("Il modello richiede una connessione HTTPS");url=target.toExternalForm();continue;}
                if(status!=200)throw new IOException("Download modello non riuscito (HTTP "+status+")");
                try(InputStream input=connection.getInputStream()){store(input,canceled,progress);}return;
            }finally{connection.disconnect();}
        }
        throw new IOException("Troppi reindirizzamenti durante il download");
    }
}
