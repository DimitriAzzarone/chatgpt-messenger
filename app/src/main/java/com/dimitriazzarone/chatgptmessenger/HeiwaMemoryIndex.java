package com.dimitriazzarone.chatgptmessenger;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.InputStream;
import java.text.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Private searchable copy, separate from the web chat's backups and notes. */
final class HeiwaMemoryIndex extends SQLiteOpenHelper {
    HeiwaMemoryIndex(Context context){super(context,"heiwa_json_memory.db",null,1);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE entries(id TEXT PRIMARY KEY,title TEXT NOT NULL,role TEXT NOT NULL,body TEXT NOT NULL,stamp INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE sources(source TEXT PRIMARY KEY,name TEXT NOT NULL)");
        db.execSQL("CREATE TABLE links(source TEXT NOT NULL,entry TEXT NOT NULL,PRIMARY KEY(source,entry))");
        db.execSQL("CREATE INDEX links_entry ON links(entry)");
        db.execSQL("CREATE VIRTUAL TABLE search USING fts4(title,body,tokenize=unicode61)");
        db.execSQL("CREATE TRIGGER entry_insert AFTER INSERT ON entries BEGIN INSERT INTO search(rowid,title,body) VALUES(new.rowid,new.title,new.body);END");
        db.execSQL("CREATE TRIGGER entry_delete AFTER DELETE ON entries BEGIN DELETE FROM search WHERE rowid=old.rowid;END");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int from,int to){}
    int files(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM sources",null)){c.moveToFirst();return c.getInt(0);}}
    int fragments(){try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM entries",null)){c.moveToFirst();return c.getInt(0);}}
    void clear(){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{db.delete("links",null,null);db.delete("sources",null,null);db.delete("entries",null,null);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    int importFile(InputStream input,String source,String name,BooleanSupplier canceled) throws Exception {
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();int before=fragments();
        try{
            db.delete("links","source=?",new String[]{source});
            int parsed=HeiwaJsonReader.read(input,name,canceled,record->{
                for(int start=0,part=0;start<record.text.length();part++){
                    if(canceled.getAsBoolean())throw new java.util.concurrent.CancellationException();
                    int end=Math.min(record.text.length(),start+900);if(end<record.text.length()&&Character.isHighSurrogate(record.text.charAt(end-1)))end--;
                    String id=record.id+"/"+part;ContentValues entry=new ContentValues();entry.put("id",id);entry.put("title",record.title);entry.put("role",record.role);entry.put("body",record.text.substring(start,end));entry.put("stamp",record.time);
                    db.insertWithOnConflict("entries",null,entry,SQLiteDatabase.CONFLICT_IGNORE);
                    ContentValues link=new ContentValues();link.put("source",source);link.put("entry",id);db.insertWithOnConflict("links",null,link,SQLiteDatabase.CONFLICT_IGNORE);
                    if(end==record.text.length())break;start=end-80;if(start>0&&Character.isLowSurrogate(record.text.charAt(start)))start--;
                }
            });
            if(parsed==0)throw new java.io.IOException("File senza record leggibili");
            ContentValues file=new ContentValues();file.put("source",source);file.put("name",name);db.insertWithOnConflict("sources",null,file,SQLiteDatabase.CONFLICT_REPLACE);
            db.execSQL("DELETE FROM entries WHERE id NOT IN (SELECT entry FROM links)");
            db.setTransactionSuccessful();return Math.max(0,fragments()-before);
        }finally{db.endTransaction();}
    }
    static final class Selection {
        final String context;final int count;
        Selection(String context,int count){this.context=context;this.count=count;}
    }
    private static String folded(String text){return Normalizer.normalize(text,Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);}
    Selection relevant(String question){
        String queryText=folded(question);LinkedHashSet<String> terms=new LinkedHashSet<>();
        String stop="|come|cosa|sono|della|delle|degli|questo|questa|quello|quella|ricordi|ricordo|dimmi|sapere|vorrei|quanti|quando|perche|anche|puoi|fare|dove|quale|nella|nello|dalle|dallo|conosci|parlami|";
        for(String word:queryText.split("[^\\p{L}\\p{N}]+")){if(word.length()>=3&&!stop.contains("|"+word+"|"))terms.add(word);if(terms.size()>=7)break;}
        if(queryText.matches(".*(?:nasc|nato|quanti anni|compleanno|che anno).*")){terms.add("nato");terms.add("nasc");}
        StringBuilder match=new StringBuilder();for(String word:terms){if(match.length()>0)match.append(" OR ");match.append(word).append('*');}
        SQLiteDatabase db=getReadableDatabase();Cursor candidates;
        if(match.length()>0)candidates=db.rawQuery("SELECT e.title,e.role,e.body,e.stamp FROM search JOIN entries e ON e.rowid=search.rowid WHERE search MATCH ? ORDER BY e.stamp DESC LIMIT 300",new String[]{match.toString()});
        else candidates=db.rawQuery("SELECT title,role,body,stamp FROM entries WHERE role='user' OR role='note' ORDER BY CASE WHEN body LIKE '%chiamo%' OR body LIKE '%nato%' OR body LIKE '%preferisco%' THEN 0 ELSE 1 END, stamp DESC LIMIT 200",null);
        class Hit {String title,role,body;long time;int score,position;}
        List<Hit> found=new ArrayList<>();
        try(Cursor cursor=candidates){while(cursor.moveToNext()){
            Hit hit=new Hit();hit.title=cursor.getString(0);hit.role=cursor.getString(1);hit.body=cursor.getString(2);hit.time=cursor.getLong(3);String body=folded(hit.body),title=folded(hit.title);
            hit.score="assistant".equals(hit.role)?0:2;hit.position=0;int first=-1;
            for(String word:terms){int at=body.indexOf(word);if(at>=0){hit.score+=5;if(first<0||at<first)first=at;}if(title.contains(word))hit.score+=3;}
            if(first>=0)hit.position=Math.max(0,first-80);if(terms.isEmpty()||hit.score>2)found.add(hit);
        }}
        found.sort((a,b)->{int score=Integer.compare(b.score,a.score);return score!=0?score:Long.compare(b.time,a.time);});
        StringBuilder text=new StringBuilder();Set<String> excerpts=new HashSet<>();int used=0;
        SimpleDateFormat date=new SimpleDateFormat("dd/MM/yyyy",Locale.ITALIAN);
        for(Hit hit:found){int start=Math.min(hit.position,hit.body.length());if(start>0&&Character.isLowSurrogate(hit.body.charAt(start)))start--;
            String excerpt=HeiwaPrompt.clip(hit.body.substring(start).replace('\n',' '),220);if(!excerpts.add(excerpt))continue;
            String role="user".equals(hit.role)?"utente":"assistant".equals(hit.role)?"assistente precedente":"nota";
            String line="["+HeiwaPrompt.clip(hit.title,45)+" · "+role+(hit.time>0?" · "+date.format(new Date(hit.time)):"")+"] "+excerpt+"\n";
            if(text.length()+line.length()>1000)continue;text.append(line);if(++used==3)break;
        }
        return new Selection(text.toString(),used);
    }
}
