package local.bookdoctorc;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

final class LocalBackup {
    private static final String[] TABLES={"boxes","books","bookmarks","reading_time","listening_time","replace_rules"};
    private static final String META="bookdoctorc.json";
    private static final long LIMIT=64L*1024*1024;
    private static final String FONT = "fonts/reader.ttf";
    private final Context context;
    private final Library library;
    private final SourceFiles files;
    private final SharedPreferences settings;

    LocalBackup(Context context, Library library, SourceFiles files, SharedPreferences settings) {
        this.context=context.getApplicationContext();
        this.library=library;this.files=files;this.settings=settings;
    }

    void write(Uri target,ContentResolver resolver) throws IOException,JSONException {
        JSONObject state=new JSONObject().put("format","BookDoctorC").put("schema",1);
        File draft=files.temp();
        try {
            Map<Long,String> covers=new LinkedHashMap<>();
            SQLiteDatabase db=library.getReadableDatabase();
            db.beginTransactionNonExclusive();
            try {
                for(String table:TABLES) state.put(table,rows(db,table));
                try(Cursor books=db.rawQuery("SELECT id,cover_name FROM books ORDER BY id",null)){
                    while(books.moveToNext()) covers.put(books.getLong(0),books.getString(1));
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            state.put("mode",settings.getString("mode","system"))
                    .put("headings",settings.getInt(TxtContents.KEY,TxtContents.ALL))
                    .put("paging",settings.getString(ReadingUi.MODE_KEY,"VERTICAL"));
            ReaderTypography.saveTo(state, settings);
            File font = ReaderFontFile.file(context);
            if (font.isFile()) state.put(ReaderFontFile.NAME_KEY, settings.getString(ReaderFontFile.NAME_KEY, "导入字体"));
            try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(draft)))){
                zip.putNextEntry(new ZipEntry(META));
                byte[] meta=state.toString().getBytes(StandardCharsets.UTF_8);
                if(meta.length>8*1024*1024) throw new IOException("备份记录超过大小限制");
                zip.write(meta);
                zip.closeEntry();
                long total=meta.length;
                if (font.isFile()) {
                    total += font.length();
                    zip.putNextEntry(new ZipEntry(FONT));
                    try (InputStream in = new FileInputStream(font)) {
                        SourceFiles.copy(in, zip, ReaderFontFile.LIMIT);
                    }
                    zip.closeEntry();
                }
                for(var entry:covers.entrySet()){
                    long id=entry.getKey();
                    File cover=library.cover(id,entry.getValue());
                    if(!cover.isFile()) continue;
                    total+=cover.length();
                    if(total>LIMIT) throw new IOException("备份封面超过大小限制");
                    zip.putNextEntry(new ZipEntry("covers/"+id+".jpg"));
                    try(InputStream in=new FileInputStream(cover)){SourceFiles.copy(in,zip,3L*1024*1024);}
                    zip.closeEntry();
                }
            }
            try(InputStream in=new FileInputStream(draft);OutputStream out=resolver.openOutputStream(target,"wt")){
                if(out==null) throw new IOException("无法写入备份位置");
                SourceFiles.copy(in,out,LIMIT);
            }
        } finally { draft.delete(); }
    }

    int restore(Uri source) throws IOException,JSONException {
        File draft=files.temp();
        try {
            files.snapshot(source,draft,LIMIT);
            try(ZipFile zip=new ZipFile(draft)){
                ZipEntry entry=zip.getEntry(META);
                if(entry==null) throw new IOException("找不到 MookDoctorC 备份记录");
                JSONObject data;
                try(InputStream in=zip.getInputStream(entry)){
                    data=new JSONObject(new String(SourceFiles.bytes(in,8*1024*1024),StandardCharsets.UTF_8));
                }
                if(!"BookDoctorC".equals(data.optString("format")) || data.optInt("schema")!=1) throw new IOException("备份格式不受支持");
                Map<Long,Long> books=merge(data);
                for(var mapping:books.entrySet()){
                    File cover=library.cover(library.book(mapping.getValue()));
                    ZipEntry image=zip.getEntry("covers/"+mapping.getKey()+".jpg");
                    if(cover.exists() || image==null || image.getSize()>3*1024*1024) continue;
                    try(InputStream in=zip.getInputStream(image)){
                        var bitmap=SourceFiles.bitmap(SourceFiles.bytes(in,3*1024*1024),1024);
                        if(bitmap!=null){
                            try{SourceFiles.storeCover(cover,bitmap);}finally{bitmap.recycle();}
                        }
                    }catch(IOException ignored){ /* A later restore can retry a missing cover. */ }
                }
                String mode=data.optString("mode","system");
                SharedPreferences.Editor editor = settings.edit()
                        .putString("mode",Set.of("system","light","dark").contains(mode)?mode:"system")
                        .putInt(TxtContents.KEY,data.optInt("headings",TxtContents.ALL)&TxtContents.ALL)
                        .putString(ReadingUi.MODE_KEY,ReaderLayout.Mode.read(data.optString("paging","VERTICAL")).name());
                ReaderTypography.restoreFrom(data, editor);
                ZipEntry font = zip.getEntry(FONT);
                boolean installed = false;
                if (font != null && font.getSize() <= ReaderFontFile.LIMIT) {
                    try (InputStream in = zip.getInputStream(font)) {
                        ReaderFontFile.install(context, in);
                        editor.putString(ReaderFontFile.NAME_KEY, data.optString(ReaderFontFile.NAME_KEY, "导入字体"));
                        installed = true;
                    } catch (IOException | RuntimeException ignored) { /* The reader can use system fonts. */ }
                }
                if (!installed && ReaderTypography.Font.IMPORTED.name().equals(data.optString(ReaderTypography.FONT_KEY))) {
                    editor.putString(ReaderTypography.FONT_KEY, ReaderTypography.Font.BOOK.name());
                }
                editor.apply();
                return books.size();
            }
        } finally { draft.delete(); }
    }

    Map<Long,Long> merge(JSONObject data) throws JSONException {
        SQLiteDatabase db=library.getWritableDatabase();
        Map<Long,Long> boxes=new HashMap<>(), books=new LinkedHashMap<>();
        db.beginTransaction();
        try {
            JSONArray rows=data.getJSONArray("boxes");
            Map<String,Integer> nameCounts=new HashMap<>();
            for(int i=0;i<rows.length();i++){
                JSONObject box=rows.getJSONObject(i);
                String name=box.getString("name");
                int index=nameCounts.getOrDefault(name,0);
                nameCounts.put(name,index+1);
                long id=id(db,"SELECT id FROM boxes WHERE name=? ORDER BY id LIMIT 1 OFFSET "+index,name);
                if(id==0){
                    ContentValues value=new ContentValues();
                    value.put("name",name);
                    id=db.insertOrThrow("boxes",null,value);
                }
                if (box.has("book_order")) {
                    ContentValues order = new ContentValues();
                    order.put("book_order", Library.BookOrder.read(box.optString("book_order")).name());
                    db.update("boxes", order, "id=?", new String[]{Long.toString(id)});
                }
                boxes.put(box.getLong("id"),id);
            }
            rows=data.getJSONArray("books");
            for(int i=0;i<rows.length();i++){
                JSONObject book=rows.getJSONObject(i);
                Long box=boxes.get(book.getLong("box"));
                Uri uri=Uri.parse(book.getString("uri"));
                if(box==null || !"content".equals(uri.getScheme()) || uri.getAuthority()==null) throw new JSONException("书籍位置无效");
                long id=id(db,"SELECT id FROM books WHERE box=? AND uri=?",box.toString(),uri.toString());
                ContentValues value=new ContentValues();
                value.put("chapter",Math.max(0,book.getInt("chapter")));
                value.put("position",Math.max(0,Math.min(1,book.getDouble("position"))));
                value.put("text_offset",book.optInt("text_offset",-1));
                value.put("read_at",book.getLong("read_at"));
                if(id==0){
                    value.put("box",box);value.put("uri",uri.toString());
                    value.put("name",book.getString("name"));value.put("title",book.getString("title"));
                    value.put("author",book.getString("author"));value.put("epub",book.getInt("epub")==0?0:1);
                    value.put("description",book.optString("description",""));
                    id=db.insertOrThrow("books",null,value);
                }else{
                    db.update("books",value,"id=? AND read_at<?",new String[]{Long.toString(id),Long.toString(book.getLong("read_at"))});
                }
                books.put(book.getLong("id"),id);
            }
            rows=data.getJSONArray("bookmarks");
            for(int i=0;i<rows.length();i++){
                JSONObject item=rows.getJSONObject(i);
                Long book=books.get(item.getLong("book"));
                if(book==null) continue;
                String label=item.getString("label");
                int chapter=item.getInt("chapter"),offset=item.getInt("text_offset");
                double position=item.getDouble("position");
                if(id(db,"SELECT id FROM bookmarks WHERE book=? AND label=? AND chapter=? AND text_offset=? AND position=?",
                        book.toString(),label,Integer.toString(chapter),Integer.toString(offset),Double.toString(position))==0)
                    db.execSQL("INSERT INTO bookmarks(book,label,chapter,position,text_offset) VALUES(?,?,?,?,?)",new Object[]{book,label,chapter,position,offset});
            }
            rows=data.getJSONArray("reading_time");
            for(int i=0;i<rows.length();i++){
                JSONObject item=rows.getJSONObject(i);
                String day=item.getString("day"),uri=item.getString("uri");
                try {java.time.LocalDate.parse(day);} catch(java.time.DateTimeException bad){throw new JSONException("阅读日期无效");}
                long duration=Math.max(0,item.getLong("ms"));
                db.execSQL("INSERT OR IGNORE INTO reading_time(day,uri,title,ms) VALUES(?,?,?,0)",new Object[]{day,uri,item.getString("title")});
                db.execSQL("UPDATE reading_time SET ms=MAX(ms,?) WHERE day=? AND uri=?",new Object[]{duration,day,uri});
            }
            JSONArray listening = data.optJSONArray("listening_time");
            for (int i = 0; listening != null && i < listening.length(); i++) {
                JSONObject item = listening.getJSONObject(i);
                String day = item.getString("day"), album = item.getString("album");
                Uri uri = Uri.parse(item.getString("uri"));
                try { java.time.LocalDate.parse(day); }
                catch (java.time.DateTimeException bad) { throw new JSONException("听书日期无效"); }
                if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null || album.isEmpty()) continue;
                long duration = Math.max(0, item.getLong("ms"));
                db.execSQL("INSERT OR IGNORE INTO listening_time(day,album,uri,title,ms) VALUES(?,?,?,?,0)",
                        new Object[]{day, album, uri.toString(), item.getString("title")});
                db.execSQL("UPDATE listening_time SET ms=MAX(ms,?) WHERE day=? AND album=?",
                        new Object[]{duration, day, album});
            }
            rows=data.getJSONArray("replace_rules");
            for(int i=0;i<rows.length();i++){
                JSONObject item=rows.getJSONObject(i);
                String name=item.getString("name"),needle=item.getString("needle"),replacement=item.getString("replacement");
                if(!needle.isEmpty() && id(db,"SELECT id FROM replace_rules WHERE name=? AND needle=? AND replacement=?",name,needle,replacement)==0)
                    db.execSQL("INSERT INTO replace_rules(name,needle,replacement) VALUES(?,?,?)",new Object[]{name,needle,replacement});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return books;
    }

    private static long id(SQLiteDatabase db,String sql,String... values){
        try(Cursor c=db.rawQuery(sql,values)){return c.moveToFirst()?c.getLong(0):0;}
    }

    static JSONArray rows(SQLiteDatabase db,String table) throws JSONException {
        JSONArray rows=new JSONArray();
        try(Cursor c=db.query(table,null,null,null,null,null,switch (table) { case "reading_time" -> "day,uri"; case "listening_time" -> "day,album"; default -> "id"; })){
            while(c.moveToNext()){
                JSONObject row=new JSONObject();
                for(int i=0;i<c.getColumnCount();i++){
                    if (c.getColumnName(i).equals("cover_name")) continue;
                    Object value=switch(c.getType(i)){
                        case Cursor.FIELD_TYPE_INTEGER -> c.getLong(i);
                        case Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i);
                        default -> c.getString(i);
                    };
                    row.put(c.getColumnName(i),value);
                }
                rows.put(row);
            }
        }
        return rows;
    }
}
