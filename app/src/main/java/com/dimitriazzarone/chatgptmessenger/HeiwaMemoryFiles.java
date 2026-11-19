package com.dimitriazzarone.chatgptmessenger;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.concurrent.CancellationException;

/** Read-only SAF access, including Drive documents selected in Android's Files UI. */
final class HeiwaMemoryFiles {
    private final Context context;
    private final SharedPreferences prefs;
    HeiwaMemoryFiles(Context context){this.context=context.getApplicationContext();prefs=context.getSharedPreferences("heiwa_json_sources",0);}
    List<Uri> linked(){ArrayList<Uri> uris=new ArrayList<>();try{JSONArray a=new JSONArray(prefs.getString("files","[]"));for(int i=0;i<a.length();i++)uris.add(Uri.parse(a.getString(i)));}catch(JSONException ignored){}return uris;}
    void forget(){prefs.edit().clear().commit();}
    private void remember(Uri uri){List<Uri> list=linked();if(!list.contains(uri))list.add(uri);JSONArray array=new JSONArray();for(Uri item:list)array.put(item.toString());prefs.edit().putString("files",array.toString()).commit();}
    private String name(Uri uri) throws Exception {
        try(Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
            if(c==null||!c.moveToFirst())throw new IOException("Documento non accessibile");
            if(!c.isNull(1)&&c.getLong(1)>32*1024*1024L)throw new IOException("File oltre 32 MB");return c.getString(0);
        }
    }
    static boolean json(String name){return name!=null&&name.toLowerCase(Locale.ROOT).matches(".*\\.(json|jsonl|ndjson)$");}
    static final class Result {int imported,failed,fragments,files;final ArrayList<String> errors=new ArrayList<>();}
    Result importFiles(List<Uri> uris,BooleanSupplier canceled,Consumer<String> progress) throws Exception {
        Result result=new Result();
        try(HeiwaMemoryIndex index=new HeiwaMemoryIndex(context)){
            for(Uri uri:new LinkedHashSet<>(uris)){
                if(canceled.getAsBoolean())throw new CancellationException();String name="Documento";
                try{
                    name=name(uri);if(!json(name))throw new IOException("Non è un file JSON o JSONL");
                    progress.accept("Indicizzo "+name+"…");
                    try(InputStream input=context.getContentResolver().openInputStream(uri)){
                        if(input==null)throw new IOException("File non leggibile");
                        index.importFile(input,uri.toString(),name,canceled);remember(uri);result.imported++;
                    }
                }catch(CancellationException canceledError){throw canceledError;}
                catch(Exception error){result.failed++;if(result.errors.size()<8)result.errors.add(HeiwaPrompt.clip(name,65)+": "+HeiwaPrompt.clip(error.getMessage(),100));}
            }
            result.files=index.files();result.fragments=index.fragments();return result;
        }
    }
    List<Uri> existingDanFolder(BooleanSupplier canceled) throws Exception {
        String saved=context.getSharedPreferences("radio_prefs",0).getString(DanDriveSync.PREF_FOLDER,"");
        if(saved.isEmpty())throw new IOException("Nessuna cartella Dan già collegata: usa Importa JSON da Drive/File");
        ArrayList<Uri> files=new ArrayList<>();walk(Uri.parse(saved),DocumentsContract.getTreeDocumentId(Uri.parse(saved)),0,new HashSet<>(),files,canceled);return files;
    }
    private void walk(Uri tree,String parent,int depth,Set<String> seen,List<Uri> files,BooleanSupplier canceled) throws Exception {
        if(depth>12||seen.size()>1000)throw new IOException("Cartella troppo estesa o annidata");if(!seen.add(parent))return;
        try(Cursor c=context.getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent),new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            if(c==null)throw new IOException("Cartella non accessibile");
            while(c.moveToNext()){
                if(canceled.getAsBoolean())throw new CancellationException();String id=c.getString(0);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)))walk(tree,id,depth+1,seen,files,canceled);
                else if(json(c.getString(1))){files.add(DocumentsContract.buildDocumentUriUsingTree(tree,id));if(files.size()>500)throw new IOException("Oltre 500 JSON: importa gruppi di file");}
            }
        }
    }
}
