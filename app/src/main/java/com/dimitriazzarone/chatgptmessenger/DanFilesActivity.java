package com.dimitriazzarone.chatgptmessenger;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-driven Termux setup. Never accepts commands from intents or web pages. */
public final class DanFilesActivity extends Activity {
    private static final int PERMISSION=6101;
    private TextView status;private Button open;private boolean destroyed,pending;
    private final AtomicBoolean checking=new AtomicBoolean();
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(0xff030d1a);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,20,24,20);scroll.addView(root);setContentView(scroll);
        title(root,"File · Dan",26);title(root,"Kit integrato · Termux e Google Drive",17);
        title(root,"Una volta su ogni dispositivo: autorizza Termux, prepara il kit e collega il tuo Drive. La chat e il Bridge 15 restano disponibili.",16);
        status=title(root,"Controllo collegamento…",16);
        button(root,"Prepara / avvia il kit integrato",v->prepare());
        button(root,"Verifica collegamento",v->probe());
        open=button(root,"Apri archivio in Luminex",v->{setResult(RESULT_OK);finish();});open.setEnabled(false);
        button(root,"Prima configurazione Termux",v->help());
        button(root,"Apri Termux",v->launchTermux());
        button(root,"Torna alla chat",v->finish());
        title(root,"La copia viene verificata su Drive prima di permettere la rimozione. La prima autorizzazione Google si completa in Termux/rclone. Android può sospendere Termux: lascialo attivo durante i trasferimenti.",14);
    }
    private TextView title(LinearLayout root,String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextColor(0xffe0f4ff);view.setTextSize(size);view.setPadding(0,10,0,14);root.addView(view);return view;}
    private Button button(LinearLayout root,String value,View.OnClickListener listener){Button view=new Button(this);view.setText(value);view.setAllCaps(false);view.setTextColor(0xff00c8ff);view.setOnClickListener(listener);root.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
    private boolean installed(){try{getPackageManager().getServiceInfo(new ComponentName("com.termux","com.termux.app.RunCommandService"),0);return true;}catch(PackageManager.NameNotFoundException error){return false;}}
    private void prepare(){
        if(!installed()){status.setText("Installa e apri Termux, poi torna qui. Il servizio RUN_COMMAND non è disponibile.");help();return;}
        if(checkSelfPermission(DanTermuxKit.PERMISSION)!=PackageManager.PERMISSION_GRANTED){pending=true;requestPermissions(new String[]{DanTermuxKit.PERMISSION},PERMISSION);return;}
        try{
            startService(DanTermuxKit.prepare(this));status.setText("Preparazione inviata a Termux: completa i passaggi nel terminale, poi torna qui e verifica. L’invio non conferma che il servizio sia pronto.");launchTermux();
        }catch(SecurityException error){status.setText("Termux non autorizzato. Apri Prima configurazione Termux e consenti a Dan di eseguire comandi.");}
        catch(Exception error){status.setText("Preparazione non avviata: "+error.getClass().getSimpleName());}
    }
    private void launchTermux(){Intent intent=getPackageManager().getLaunchIntentForPackage("com.termux");if(intent!=null)startActivity(intent);else status.setText("Termux non installato: vedi Prima configurazione Termux.");}
    private void help(){
        String text="Il kit è già dentro Dan: non serve scaricare ZIP.\n\n1. Installa e apri Termux (versione ufficiale compatibile RUN_COMMAND).\n2. Abilita allow-external-apps in Termux e consenti il permesso di esecuzione richiesto da Dan. Il pulsante qui sotto copia il comando per abilitarlo: incollalo una volta nel terminale.\n3. Torna in Dan e premi Prepara / avvia. Nel terminale configura Drive e scegli la cartella da gestire. Non inserire password o chiavi nella chat.\n4. Torna qui e premi Verifica collegamento.\n\nRipeti questa preparazione su ogni dispositivo.";
        new AlertDialog.Builder(this).setTitle("Prima configurazione").setMessage(text).setPositiveButton("Copia comando",(d,w)->{
            String command="mkdir -p \"$HOME/.termux\"; touch \"$HOME/.termux/termux.properties\"; sed -i '/^[[:space:]]*allow-external-apps[[:space:]]*=/d' \"$HOME/.termux/termux.properties\"; printf '\\nallow-external-apps=true\\n' >> \"$HOME/.termux/termux.properties\"; termux-reload-settings";
            ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Abilita Termux per Dan",command));status.setText("Comando copiato: incollalo una volta in Termux, poi torna su Prepara / avvia.");
        }).setNeutralButton("Sito Termux",(d,w)->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://termux.dev/")))).setNegativeButton("Chiudi",null).show();
    }
    private void probe(){
        if(!checking.compareAndSet(false,true))return;
        new Thread(()->{String message;boolean ready=false;HttpURLConnection connection=null;
            try{connection=(HttpURLConnection)new URL("http://127.0.0.1:8766/health").openConnection();connection.setConnectTimeout(1500);connection.setReadTimeout(1500);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
                try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[512];int n;while((n=in.read(bytes))!=-1){out.write(bytes,0,n);if(out.size()>4096)throw new IOException("Risposta troppo grande");}JSONObject reply=new JSONObject(out.toString("UTF-8"));ready="Dan Files".equals(reply.optString("service"))&&"1.1".equals(reply.optString("version"));message=ready?"Archivio pronto su questo dispositivo · "+reply.optString("device"):"Servizio diverso o vecchio sulla porta 8766: controlla Termux.";}
            }catch(Exception error){message="Archivio non raggiungibile. Premi Prepara / avvia e completa la configurazione in Termux.";}
            finally{if(connection!=null)connection.disconnect();checking.set(false);}
            final String value=message;final boolean connected=ready;runOnUiThread(()->{if(!destroyed){status.setText(value);open.setEnabled(connected);}});
        },"Dan-Files-Check").start();
    }
    @Override public void onRequestPermissionsResult(int request,String[] names,int[] results){super.onRequestPermissionsResult(request,names,results);if(request==PERMISSION){boolean wanted=pending;pending=false;if(wanted&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)prepare();else status.setText("Permesso Termux non concesso: abilitalo nelle autorizzazioni Android di Dan.");}}
    @Override protected void onResume(){super.onResume();probe();}
    @Override protected void onDestroy(){destroyed=true;super.onDestroy();}
}
