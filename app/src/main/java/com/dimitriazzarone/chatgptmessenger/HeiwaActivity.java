package com.dimitriazzarone.chatgptmessenger;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.InputFilter;
import android.util.AtomicFile;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent native HEIWA chat. Never scrapes or replaces the authenticated web chat. */
public final class HeiwaActivity extends Activity {
    private static final int IMPORT_MODEL=5150;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean canceled=new AtomicBoolean();
    private final HeiwaGroq groq=new HeiwaGroq();
    private final ArrayList<HeiwaPrompt.Turn> turns=new ArrayList<>();
    private SharedPreferences prefs;
    private HeiwaModel model;
    private HeiwaSecrets secrets;
    private LinearLayout messages;
    private ScrollView scroll;
    private TextView status,modeLabel;
    private EditText composer;
    private Spinner provider;
    private Button send,stop,modelButton,settings,memoryButton,clear;
    private volatile boolean destroyed;
    private boolean busy;
    private int lastProvider;
    private int dp(int value){return (int)(getResources().getDisplayMetrics().density*value+.5f);}
    private Button button(String label){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(Color.rgb(0,200,255));return b;}
    private EditText edit(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextColor(Color.WHITE);e.setHintTextColor(Color.LTGRAY);e.setTextSize(16);return e;}
    private TextView label(String text,int size){TextView v=new TextView(this);v.setText(text);v.setTextColor(Color.rgb(224,244,255));v.setTextSize(size);v.setPadding(dp(8),dp(5),dp(8),dp(5));return v;}
    private boolean online(){return provider.getSelectedItemPosition()==1;}
    private void ui(Runnable runnable){runOnUiThread(()->{if(!destroyed&&!isFinishing())runnable.run();});}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=getSharedPreferences("heiwa_chat",MODE_PRIVATE);model=new HeiwaModel(this);secrets=new HeiwaSecrets(this);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(3,13,26));root.setPadding(dp(8),dp(4),dp(8),dp(4));
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
        Button back=button("‹ Dan");back.setOnClickListener(v->finish());header.addView(back);
        TextView title=label("HEIWA",24);title.setTextColor(Color.rgb(0,200,255));header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(header);
        provider=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,new String[]{"Qwen 3 · locale offline","Groq · online"}){
            @Override public View getView(int position,View convert,android.view.ViewGroup parent){TextView v=(TextView)super.getView(position,convert,parent);v.setTextColor(Color.WHITE);v.setPadding(dp(12),dp(10),dp(8),dp(10));return v;}
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);provider.setAdapter(adapter);lastProvider=prefs.getInt("provider",0);provider.setSelection(lastProvider);
        root.addView(provider);modeLabel=label("",12);root.addView(modeLabel);
        LinearLayout actions=new LinearLayout(this);
        modelButton=button("Modello");modelButton.setOnClickListener(v->modelMenu());actions.addView(modelButton,new LinearLayout.LayoutParams(0,dp(48),1));
        memoryButton=button("Memoria");memoryButton.setOnClickListener(v->memoryDialog());actions.addView(memoryButton,new LinearLayout.LayoutParams(0,dp(48),1));
        settings=button("Groq");settings.setOnClickListener(v->groqSettings());actions.addView(settings,new LinearLayout.LayoutParams(0,dp(48),1));
        clear=button("Nuova");clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Nuova chat HEIWA")
                .setMessage("Cancella questa conversazione locale. Le note personali restano disponibili.")
                .setNegativeButton("Annulla",null).setPositiveButton("Nuova chat",(d,w)->{turns.clear();saveTurns();render();}).show());actions.addView(clear,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(actions);
        scroll=new ScrollView(this);messages=new LinearLayout(this);messages.setOrientation(LinearLayout.VERTICAL);scroll.addView(messages);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        status=label("",12);root.addView(status);
        composer=edit("Scrivi a HEIWA");composer.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        composer.setMinLines(1);composer.setMaxLines(4);composer.setImeOptions(EditorInfo.IME_ACTION_SEND);composer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1200)});root.addView(composer);
        LinearLayout bottom=new LinearLayout(this);send=button("Invia");stop=button("Stop");stop.setEnabled(false);
        send.setOnClickListener(v->send());stop.setOnClickListener(v->cancelWork());
        bottom.addView(send,new LinearLayout.LayoutParams(0,dp(48),1));bottom.addView(stop,new LinearLayout.LayoutParams(0,dp(48),1));root.addView(bottom);setContentView(root);
        composer.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEND){send();return true;}return false;});
        composer.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_ENTER&&!event.isShiftPressed()&&!event.isCtrlPressed()&&!event.isAltPressed()){
            if(event.getAction()==KeyEvent.ACTION_UP&&event.getRepeatCount()==0)send();return true;}return false;});
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int at,long id){
            if(at!=lastProvider&& !turns.isEmpty()){
                int target=at;provider.setSelection(lastProvider);
                new AlertDialog.Builder(HeiwaActivity.this).setTitle("Passa a "+(target==1?"Groq online":"Qwen locale"))
                        .setMessage("Il cambio apre una nuova conversazione HEIWA. La memoria personale resta separata.")
                        .setNegativeButton("Annulla",null).setPositiveButton("Cambia",(d,w)->{turns.clear();saveTurns();render();lastProvider=target;prefs.edit().putInt("provider",target).apply();provider.setSelection(target);mode();}).show();
                return;
            }
            lastProvider=at;prefs.edit().putInt("provider",at).apply();mode();
        }});
        loadTurns();render();mode();
    }
    private void mode(){
        if(online())modeLabel.setText("Groq online · invia i messaggi al servizio · "+prefs.getString("groqModel","llama-3.1-8b-instant")
                +(prefs.getBoolean("shareMemory",false)?" · memoria inclusa":" · memoria personale esclusa"));
        else modeLabel.setText("Qwen3-0.6B · offline · "+(model.ready()?"modello pronto":"modello da scaricare o importare")+" · memoria locale");
        if(!busy)status.setText(online()?"Configura la chiave dal pulsante Groq":"Qwen risponde senza Internet dopo il download del modello");
    }
    private void setBusy(boolean value,String text){busy=value;send.setEnabled(!value);stop.setEnabled(value);provider.setEnabled(!value);modelButton.setEnabled(!value);settings.setEnabled(!value);memoryButton.setEnabled(!value);clear.setEnabled(!value);status.setText(text);}
    private void render(){messages.removeAllViews();
        if(turns.isEmpty())messages.addView(label("HEIWA\nScegli il motore e scrivi un messaggio. Le conversazioni di ChatGPT restano in Dan.",16));
        for(HeiwaPrompt.Turn turn:turns){TextView text=label(("user".equals(turn.role)?"Tu":"HEIWA")+"\n"+turn.text,16);text.setTextIsSelectable(true);
            text.setBackgroundColor("user".equals(turn.role)?Color.rgb(7,35,52):Color.rgb(7,20,40));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,dp(5));messages.addView(text,lp);}
        scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    }
    private void loadTurns(){
        File file=new File(getFilesDir(),"heiwa-conversation.json");if(!file.exists())return;
        try(InputStream input=new AtomicFile(file).openRead()){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1){if(bytes.size()+count>524288)throw new IOException("Chat locale troppo grande");bytes.write(buffer,0,count);}
            JSONArray rows=new JSONArray(bytes.toString("UTF-8"));
            for(int i=Math.max(0,rows.length()-40);i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String role=row.getString("role");if("user".equals(role)||"assistant".equals(role))turns.add(new HeiwaPrompt.Turn(role,HeiwaPrompt.clip(row.getString("text"),6000)));}
        }catch(Exception error){Toast.makeText(this,"Conversazione locale non leggibile",Toast.LENGTH_LONG).show();}
    }
    private void saveTurns(){
        while(turns.size()>40)turns.remove(0);AtomicFile file=new AtomicFile(new File(getFilesDir(),"heiwa-conversation.json"));FileOutputStream output=null;
        try{JSONArray rows=new JSONArray();for(HeiwaPrompt.Turn turn:turns)rows.put(new JSONObject().put("role",turn.role).put("text",HeiwaPrompt.clip(turn.text,6000)));
            output=file.startWrite();output.write(rows.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(output);
        }catch(Exception error){if(output!=null)file.failWrite(output);Toast.makeText(this,"Impossibile salvare la chat locale",Toast.LENGTH_LONG).show();}
    }
    private void send(){
        if(busy)return;String question=composer.getText().toString().trim();if(question.isEmpty())return;
        if(!online()&&!model.ready()){modelMenu();return;}
        final boolean cloud=online();final String selected=prefs.getString("groqModel","llama-3.1-8b-instant");
        final String memory=prefs.getString("memory","");final boolean share=prefs.getBoolean("shareMemory",false);
        turns.add(new HeiwaPrompt.Turn("user",question));composer.setText("");saveTurns();render();
        final ArrayList<HeiwaPrompt.Turn> snapshot=new ArrayList<>(turns);canceled.set(false);setBusy(true,cloud?"Groq · attendo la risposta…":"Qwen · carico il modello e preparo la risposta…");
        worker.execute(()->{
            try{
                String answer;
                if(cloud)answer=groq.answer(secrets.read(),selected,snapshot,memory,share);
                else answer=DanNativeQwen.answer(model.file().getAbsolutePath(),HeiwaPrompt.local(snapshot,memory),canceled::get);
                if(canceled.get())throw new CancellationException();
                ui(()->{turns.add(new HeiwaPrompt.Turn("assistant",answer));saveTurns();render();setBusy(false,cloud?"Risposta Groq online":"Risposta Qwen · elaborata sul dispositivo");});
            }catch(Exception|LinkageError error){ui(()->setBusy(false,canceled.get()?"Operazione interrotta":errorMessage(error)));}
        });
    }
    private String errorMessage(Throwable error){String message=error.getMessage();return message==null?"Operazione non riuscita: "+error.getClass().getSimpleName():HeiwaPrompt.clip(message,350);}
    private void cancelWork(){canceled.set(true);groq.cancel();DanNativeQwen.cancel();if(busy)status.setText("Interrompo l’operazione…");}
    private void memoryDialog(){
        EditText notes=edit("Preferenze e informazioni che HEIWA deve ricordare");notes.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);notes.setMinLines(5);notes.setMaxLines(10);notes.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});notes.setText(prefs.getString("memory",""));
        new AlertDialog.Builder(this).setTitle("Memoria locale HEIWA").setMessage("Note salvate sul dispositivo, modificabili qui. Qwen usa un estratto delle note; la memoria non viene importata automaticamente da ChatGPT.")
                .setView(notes).setNegativeButton("Annulla",null).setNeutralButton("Cancella memoria",(d,w)->prefs.edit().remove("memory").apply())
                .setPositiveButton("Salva",(d,w)->{prefs.edit().putString("memory",notes.getText().toString()).apply();Toast.makeText(this,"Memoria salvata sul dispositivo",Toast.LENGTH_SHORT).show();}).show();
    }
    private void groqSettings(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(18),dp(4),dp(18),dp(4));
        EditText key=edit("Nuova chiave API · vuoto per mantenere quella salvata");key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);key.setSingleLine(true);if(Build.VERSION.SDK_INT>=26)key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);form.addView(key);
        EditText name=edit("Modello Groq");name.setSingleLine(true);name.setText(prefs.getString("groqModel","llama-3.1-8b-instant"));form.addView(name);
        CheckBox share=new CheckBox(this);share.setText("Includi le note della memoria nelle richieste a Groq");share.setTextColor(Color.WHITE);share.setChecked(prefs.getBoolean("shareMemory",false));form.addView(share);
        form.addView(label("La chiave è cifrata con Android Keystore. Groq richiede Internet e un account API; non usa l’abbonamento ChatGPT.",12));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Groq online").setView(form).setNegativeButton("Chiudi",null)
                .setNeutralButton("Modelli disponibili",null).setPositiveButton("Salva",null).create();dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                try{String raw=key.getText().toString().trim();if(!raw.isEmpty())secrets.save(raw);
                    String selected=name.getText().toString().trim();if(selected.isEmpty())throw new IOException("Inserisci il modello Groq");
                    prefs.edit().putString("groqModel",selected).putBoolean("shareMemory",share.isChecked()).apply();dialog.dismiss();mode();
                }catch(Exception error){Toast.makeText(this,errorMessage(error),Toast.LENGTH_LONG).show();}
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                try{if(!key.getText().toString().trim().isEmpty())secrets.save(key.getText().toString());}catch(Exception error){Toast.makeText(this,errorMessage(error),Toast.LENGTH_LONG).show();return;}
                dialog.dismiss();canceled.set(false);setBusy(true,"Leggo i modelli disponibili su Groq…");
                worker.execute(()->{try{List<String> names=groq.models(secrets.read());if(canceled.get())throw new CancellationException();ui(()->{setBusy(false,"Scegli un modello Groq");
                    new AlertDialog.Builder(this).setTitle("Modelli disponibili su Groq").setItems(names.toArray(new String[0]),(which,index)->{prefs.edit().putString("groqModel",names.get(index)).apply();mode();}).setNegativeButton("Chiudi",null).show();});
                }catch(Exception error){ui(()->setBusy(false,canceled.get()?"Operazione interrotta":errorMessage(error)));}});
            });
        });dialog.show();
    }
    private void modelMenu(){
        new AlertDialog.Builder(this).setTitle("Qwen3-0.6B Q4_K_M").setItems(new String[]{"Scarica modello · 379 MB","Importa questo GGUF da File","Rimuovi modello dal dispositivo","Elimina chiave Groq","Licenze e sorgente del modello"},(dialog,which)->{
            if(which==0)new AlertDialog.Builder(this).setTitle("Scarica Qwen 3 locale")
                    .setMessage("Download di circa 379 MB da Hugging Face (quantizzazione Unsloth di Qwen3-0.6B). Richiede spazio libero e va completato tenendo HEIWA aperta. Dopo il download non serve Internet per Qwen.")
                    .setNegativeButton("Annulla",null).setPositiveButton("Scarica",(d,w)->modelTask(null)).show();
            else if(which==1){Intent pick=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
                try{startPickerProtection();startActivityForResult(pick,IMPORT_MODEL);}catch(Exception error){stopPickerProtection();status.setText(errorMessage(error));}}
            else if(which==2)new AlertDialog.Builder(this).setTitle("Rimuovi modello locale?").setMessage("La conversazione e le note personali restano salvate.").setNegativeButton("Annulla",null).setPositiveButton("Rimuovi",(d,w)->{
                canceled.set(false);setBusy(true,"Rimuovo il modello…");worker.execute(()->{try{DanNativeQwen.unload();model.remove();ui(()->{setBusy(false,"Modello rimosso");mode();});}catch(Exception error){ui(()->setBusy(false,errorMessage(error)));}});
            }).show();
            else if(which==3){try{secrets.save("");Toast.makeText(this,"Chiave Groq eliminata",Toast.LENGTH_SHORT).show();}catch(Exception error){status.setText(errorMessage(error));}}
            else new AlertDialog.Builder(this).setTitle("HEIWA · componenti locali").setMessage("Modello: Qwen3-0.6B, licenza Apache 2.0. Quantizzazione Q4_K_M pubblicata da Unsloth su huggingface.co/unsloth/Qwen3-0.6B-GGUF. Download verificato mediante SHA-256.\nMotore: llama.cpp (MIT), revisione de7fa0a.\nI pesi non sono inclusi nell’APK.").setPositiveButton("OK",null).show();
        }).setNegativeButton("Chiudi",null).show();
    }
    private void modelTask(Uri uri){
        canceled.set(false);setBusy(true,uri==null?"Scarico Qwen 3…":"Importo e verifico il modello…");getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(()->{try{DanNativeQwen.unload();java.util.function.IntConsumer progress=n->ui(()->status.setText((uri==null?"Download":"Importazione")+" Qwen 3 · "+n+"%"));
            if(uri==null)model.download(canceled::get,progress);else model.importFile(uri,canceled::get,progress);
            ui(()->{setBusy(false,"Qwen 3 verificato · pronto offline");modeLabel.setText("Qwen3-0.6B · modello pronto sul dispositivo");});
        }catch(Exception error){ui(()->setBusy(false,canceled.get()?"Operazione interrotta":errorMessage(error)));}
        finally{ui(()->getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));}});
    }
    private void startPickerProtection(){Intent service=new Intent(this,DanAttachmentSessionService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);}
    private void stopPickerProtection(){stopService(new Intent(this,DanAttachmentSessionService.class));}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==IMPORT_MODEL){stopPickerProtection();if(result==RESULT_OK&&data!=null&&data.getData()!=null)modelTask(data.getData());}}
    @Override protected void onStop(){super.onStop();cancelWork();if(!worker.isShutdown())worker.execute(DanNativeQwen::unload);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    @Override protected void onDestroy(){destroyed=true;cancelWork();if(!worker.isShutdown()){worker.execute(DanNativeQwen::unload);worker.shutdown();}super.onDestroy();}
}
