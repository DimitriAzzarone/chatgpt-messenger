package com.dimitriazzarone.chatgptmessenger;

import android.content.Context;
import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Xml;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Read-only, incremental import from two independent SAF-selected Drive directories:
 *  1. Memoria Dan (existing backup folder, user-authored notes only)
 *  2. Tutta la mia storia o quasi (separately authorized by Android)
 * Files are NEVER edited/deleted. Documents become searchable off-line.
 */
final class DanPersonalMemorySync {
    static final String PREF_STORY_FOLDER = "dan_story_folder";
    private static final String PREF_MEMORY_FOLDER = DanDriveSync.PREF_FOLDER;
    private static final int MAX_FILES_PER_SYNC = 8;
    private static final int MAX_SCAN_FILES = 250;
    private static final int MAX_DOCUMENT_BYTES = 24 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 850_000;
    private static final int MAX_PDF_PAGES = 280;
    private static final String STORIES = "Tutta la mia storia o quasi";

    private final Context context;
    private final SharedPreferences prefs;

    DanPersonalMemorySync(Context c, SharedPreferences p) {
        context = c.getApplicationContext(); prefs = p;
    }
    boolean hasFolders() { return prefs.contains(PREF_MEMORY_FOLDER) || prefs.contains(PREF_STORY_FOLDER); }
    boolean hasStoryFolder() { return prefs.contains(PREF_STORY_FOLDER); }

    void setStoryFolder(Uri uri) throws Exception {
        if (!STORIES.equals(folderName(uri)))
            throw new IllegalArgumentException("Scegli la cartella 'Tutta la mia storia o quasi'");
        prefs.edit().putString(PREF_STORY_FOLDER, uri.toString()).apply();
    }

    private String folderName(Uri uri) throws Exception {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri));
        try (Cursor c = context.getContentResolver().query(doc,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c == null || !c.moveToFirst()) throw new IllegalArgumentException("Cartella non leggibile");
            return c.getString(0);
        }
    }

    Result sync() throws Exception {
        Result stats = new Result();
        try (DanPersonalMemoryStore store = new DanPersonalMemoryStore(context)) {
            String notes = prefs.getString(PREF_MEMORY_FOLDER, "");
            String story = prefs.getString(PREF_STORY_FOLDER, "");
            if (!notes.isEmpty()) walk(Uri.parse(notes), "Memoria Dan/", 0, store, stats);
            if (!story.isEmpty()) walk(Uri.parse(story), STORIES + "/", 0, store, stats);
            stats.total = store.documentCount();
        }
        return stats;
    }

    private void walk(Uri tree, String prefix, int depth, DanPersonalMemoryStore store,
                      Result stats) throws Exception {
        if (depth > 3 || stats.scanned >= MAX_SCAN_FILES || stats.imported >= MAX_FILES_PER_SYNC) return;
        ContentResolver resolver = context.getContentResolver();
        String parentId = depth == 0 ? DocumentsContract.getTreeDocumentId(tree)
                : DocumentsContract.getDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId);
        try (Cursor c = resolver.query(children,new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,null)) {
            if (c == null) throw new IllegalArgumentException("Cartella non accessibile: "+prefix);
            while (c.moveToNext() && stats.scanned < MAX_SCAN_FILES && stats.imported < MAX_FILES_PER_SYNC) {
                String id=c.getString(0), name=c.getString(1), mime=c.getString(2);
                if (name == null) continue;
                long bytes=c.isNull(3)?-1:c.getLong(3), modified=c.isNull(4)?0:c.getLong(4);
                Uri file=DocumentsContract.buildDocumentUriUsingTree(tree,id);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(file,prefix+name+"/",depth+1,store,stats);
                    continue;
                }
                if (!supported(name,mime)) continue;
                stats.scanned++;
                if (bytes > MAX_DOCUMENT_BYTES) { stats.skipped++;continue; }
                String source=file.toString();
                if (store.unchanged(source,modified,bytes)) {stats.unchanged++;continue;}
                try {
                    String content=extract(file,name,mime);
                    if (content.isEmpty()) { stats.skipped++; continue; }
                    int chunks=store.index(source,prefix+name,modified,bytes,content);
                    if (chunks>0) {stats.imported++;stats.chunks+=chunks;}
                    else stats.skipped++;
                } catch (Exception e) {
                    // A damaged or cloud-only item must not stop the other documents.
                    stats.skipped++;
                    android.util.Log.w("DanAutobiography", "Documento non indicizzato: " + name, e);
                }
            }
        }
    }

    private boolean supported(String name,String mime) {
        String low=name.toLowerCase(Locale.ROOT);
        // Files generated by DAN's backup mechanism remain managed by DanDriveSync.
        if (low.startsWith("dan-device-") || low.startsWith("dan-memory")
                || low.startsWith("dan-storico-") || low.startsWith("dan_termux_")
                || low.startsWith("leggimi_") || low.startsWith("prompt_")
                || low.startsWith("luminex_dev_") || low.endsWith(".jsonl")) return false;
        return low.endsWith(".txt") || low.endsWith(".md") || low.endsWith(".markdown")
                || low.endsWith(".docx") || low.endsWith(".pdf") || "text/plain".equals(mime);
    }

    private String extract(Uri file,String name,String mime) throws Exception {
        String low=name.toLowerCase(Locale.ROOT);
        if (low.endsWith(".pdf")) return extractPdf(file);
        if (low.endsWith(".docx")) return extractDocx(file);
        try (InputStream in=context.getContentResolver().openInputStream(file)) {
            if (in == null) throw new IllegalArgumentException("Documento non accessibile");
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buf=new byte[8192];int n;
            while ((n=in.read(buf))!=-1 && out.size()<MAX_TEXT_CHARS*3) out.write(buf,0,n);
            String value=new String(out.toByteArray(),StandardCharsets.UTF_8);
            return value.length()>MAX_TEXT_CHARS?value.substring(0,MAX_TEXT_CHARS):value;
        }
    }

    private String extractDocx(Uri uri) throws Exception {
        StringBuilder text=new StringBuilder();
        try (InputStream input=context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("DOCX non leggibile");
            try (ZipInputStream zip=new ZipInputStream(input)) {
                ZipEntry entry;
                while ((entry=zip.getNextEntry())!=null) {
                    if (!"word/document.xml".equals(entry.getName())) { zip.closeEntry();continue; }
                    XmlPullParser x=Xml.newPullParser();
                    x.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
                    x.setInput(zip,"UTF-8");
                    int event;
                    while ((event=x.next())!=XmlPullParser.END_DOCUMENT && text.length()<MAX_TEXT_CHARS) {
                        if (event==XmlPullParser.START_TAG && "t".equals(x.getName()))
                            text.append(x.nextText());
                        if (event==XmlPullParser.END_TAG && ("p".equals(x.getName()) || "tr".equals(x.getName())))
                            text.append('\n');
                    }
                    break;
                }
            }
        }
        return text.length()>MAX_TEXT_CHARS?text.substring(0,MAX_TEXT_CHARS):text.toString();
    }

    private String extractPdf(Uri uri) throws Exception {
        PDFBoxResourceLoader.init(context);
        File temp=File.createTempFile("dan-index-", ".pdf", context.getCacheDir());
        try {
            try (InputStream in=context.getContentResolver().openInputStream(uri);
                 FileOutputStream out=new FileOutputStream(temp)) {
                if (in==null) throw new IllegalArgumentException("PDF non leggibile");
                byte[] buf=new byte[16384];int n;long copied=0;
                while ((n=in.read(buf))!=-1) {
                    copied+=n;
                    if (copied>MAX_DOCUMENT_BYTES) throw new IllegalArgumentException("PDF oltre 24 MB");
                    out.write(buf,0,n);
                }
            }
            StringBuilder text=new StringBuilder();
            try (PDDocument doc=PDDocument.load(temp)) {
                PDFTextStripper stripper=new PDFTextStripper();
                int pages=Math.min(doc.getNumberOfPages(),MAX_PDF_PAGES);
                for (int from=1;from<=pages && text.length()<MAX_TEXT_CHARS;from+=10) {
                    stripper.setStartPage(from);stripper.setEndPage(Math.min(pages,from+9));
                    text.append(stripper.getText(doc));
                }
            }
            return text.length()>MAX_TEXT_CHARS?text.substring(0,MAX_TEXT_CHARS):text.toString();
        } finally { if (temp.exists() && !temp.delete()) temp.deleteOnExit(); }
    }

    static final class Result {
        int scanned,imported,unchanged,skipped,chunks,total;
        String summary() {return "Documenti indicizzati: "+total+" · Nuovi/aggiornati: "+imported
                +" · Già aggiornati: "+unchanged+" · Da verificare: "+skipped;
        }
    }
}
