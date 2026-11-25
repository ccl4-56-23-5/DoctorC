package local.bookdoctorc;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;

final class Library extends SQLiteOpenHelper {
    record Box(long id, String name, BookOrder order) {}
    record Book(long id, long box, Uri uri, String name, String title, String author,
                boolean epub, int chapter, double position, long readAt, int offset,
                String description, String coverName) {}
    record Import(Uri uri, String name, String title, String author, String description, boolean epub) {}

    record Bookmark(long id, long book, String label, int chapter, double position, int offset) {}
    record Reading(long bookId, String title, String author, long millis, long total, String coverName) {}
    record Listening(String id, String uri, String title, long millis, long total) {}
    record Rule(long id, String name, String find, String replace) {}

    enum BookOrder {
        IMPORTED("导入顺序"), RECENT("最近阅读"), TITLE_ASC("名称升序"), TITLE_DESC("名称降序");

        final String label;

        BookOrder(String label) { this.label = label; }

        static BookOrder read(String key) {
            for (BookOrder order : values()) if (order.name().equals(key)) return order;
            return IMPORTED;
        }

        void sort(List<Book> books) {
            if (this == IMPORTED) return;
            if (this == RECENT) {
                books.sort(Comparator.comparingLong(Book::readAt).reversed().thenComparingLong(Book::id));
                return;
            }
            Collator names = Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
            Comparator<Book> byTitle = (first, second) -> names.compare(first.title(), second.title());
            if (this == TITLE_DESC) byTitle = byTitle.reversed();
            books.sort(byTitle.thenComparingLong(Book::id));
        }
    }

    private final File covers;

    private static Library instance;

    static synchronized Library get(Context context) {
        if (instance == null) instance = new Library(context.getApplicationContext());
        return instance;
    }

    private Library(Context context) {
        super(context, "bookshelf.db", null, 7);
        covers = new File(context.getFilesDir(), "covers");
        covers.mkdirs();
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE boxes (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, book_order TEXT NOT NULL DEFAULT 'IMPORTED')");
        db.execSQL("CREATE TABLE books (id INTEGER PRIMARY KEY AUTOINCREMENT, box INTEGER NOT NULL REFERENCES boxes(id) ON DELETE CASCADE,"
                + " uri TEXT NOT NULL, name TEXT NOT NULL, title TEXT NOT NULL, author TEXT NOT NULL,"
                + " epub INTEGER NOT NULL, chapter INTEGER NOT NULL DEFAULT 0, position REAL NOT NULL DEFAULT 0,"
                + " read_at INTEGER NOT NULL DEFAULT 0, text_offset INTEGER NOT NULL DEFAULT -1,"
                + " description TEXT NOT NULL DEFAULT '', cover_name TEXT NOT NULL DEFAULT '', UNIQUE (box, uri))");
        db.execSQL("CREATE INDEX books_recent ON books(read_at DESC, id DESC)");
        createExtras(db);
        createListening(db);
        MusicStore.INSTANCE.create(db);
        MusicStore.INSTANCE.createTimes(db);
        ModuleTransfer.createTables(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1) {
            db.execSQL("ALTER TABLE books ADD COLUMN text_offset INTEGER NOT NULL DEFAULT -1");
            createExtras(db);
        }
        if (oldVersion < 3) db.execSQL("ALTER TABLE boxes ADD COLUMN book_order TEXT NOT NULL DEFAULT 'IMPORTED'");
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE books ADD COLUMN description TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE books ADD COLUMN cover_name TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 5) createListening(db);
        if (oldVersion < 6) {
            MusicStore.INSTANCE.create(db);
            ModuleTransfer.createTables(db);
        }
        if (oldVersion < 7) MusicStore.INSTANCE.createTimes(db);
    }

    private static void createListening(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE listening_time (day TEXT NOT NULL, album TEXT NOT NULL, uri TEXT NOT NULL,"
                + " title TEXT NOT NULL, ms INTEGER NOT NULL, PRIMARY KEY(day,album))");
    }

    private static void createExtras(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, book INTEGER NOT NULL REFERENCES books(id) ON DELETE CASCADE, label TEXT NOT NULL, chapter INTEGER NOT NULL, position REAL NOT NULL, text_offset INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE reading_time (day TEXT NOT NULL, uri TEXT NOT NULL, title TEXT NOT NULL, ms INTEGER NOT NULL, PRIMARY KEY(day,uri))");
        db.execSQL("CREATE TABLE replace_rules (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, needle TEXT NOT NULL, replacement TEXT NOT NULL)");
    }

    List<Book> allBooks() { return query("ORDER BY title COLLATE NOCASE, id", null); }

    List<Bookmark> bookmarks() {
        List<Bookmark> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,book,label,chapter,position,text_offset FROM bookmarks ORDER BY id DESC", null)) {
            while (c.moveToNext()) result.add(new Bookmark(c.getLong(0), c.getLong(1), c.getString(2), c.getInt(3), c.getDouble(4), c.getInt(5)));
        }
        return result;
    }

    void addBookmark(Book book, String label, int chapter, double position, int offset) {
        getWritableDatabase().execSQL("INSERT INTO bookmarks(book,label,chapter,position,text_offset) VALUES(?,?,?,?,?)",
                new Object[]{book.id(), label, chapter, position, offset});
    }

    void removeBookmark(long id) { getWritableDatabase().delete("bookmarks", "id=?", args(id)); }

    void addReadingTime(Book book, String day, long millis) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO reading_time(day,uri,title,ms) VALUES(?,?,?,0)",
                    new Object[]{day, book.uri().toString(), book.title()});
            db.execSQL("UPDATE reading_time SET title=?, ms=ms+? WHERE day=? AND uri=?",
                    new Object[]{book.title(), millis, day, book.uri().toString()});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    void addListeningTime(String album, String uri, String title, String day, long millis) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO listening_time(day,album,uri,title,ms) VALUES(?,?,?,?,0)",
                    new Object[]{day, album, uri, title});
            db.execSQL("UPDATE listening_time SET uri=?, title=?, ms=ms+? WHERE day=? AND album=?",
                    new Object[]{uri, title, millis, day, album});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    List<Listening> listeningDurations(String day) {
        List<Listening> result = new ArrayList<>();
        String where = day == null ? "" : "WHERE day=?";
        String order = day == null ? "a.last_day DESC, a.duration DESC" : "a.duration DESC";
        String sql = "WITH totals AS (SELECT album, MAX(day) AS last_day, SUM(ms) AS duration"
                + " FROM listening_time " + where + " GROUP BY album)"
                + " SELECT a.album,r.uri,r.title,a.duration,(SELECT SUM(duration) FROM totals)"
                + " FROM totals a JOIN listening_time r ON r.album=a.album AND r.day=a.last_day"
                + " ORDER BY " + order + ", a.album LIMIT 20";
        try (Cursor c = getReadableDatabase().rawQuery(sql, day == null ? null : new String[]{day})) {
            while (c.moveToNext()) result.add(new Listening(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4)));
        }
        return result;
    }

    Map<String, Long> musicDays(String first, String next) {
        Map<String, Long> times = new java.util.HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT day, SUM(ms) FROM music_time WHERE day>=? AND day<? GROUP BY day",
                new String[]{first, next})) {
            while (c.moveToNext()) times.put(c.getString(0), c.getLong(1));
        }
        return times;
    }

    Map<String, Long> musicDay(String day) {
        Map<String, Long> times = new java.util.HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT uri,ms FROM music_time WHERE day=?",
                new String[]{day})) {
            while (c.moveToNext()) times.put(c.getString(0), c.getLong(1));
        }
        return times;
    }

    java.util.Map<String,Long> dailyTotals(String first, String next, boolean listening) {
        java.util.Map<String,Long> result = new java.util.HashMap<>();
        String table = listening ? "listening_time" : "reading_time";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT day,SUM(ms) FROM " + table
                + " WHERE day>=? AND day<? GROUP BY day", new String[]{first,next})) {
            while (c.moveToNext()) result.put(c.getString(0), c.getLong(1));
        }
        return result;
    }

    List<Reading> durations(String day) {
        List<Reading> result = new ArrayList<>();
        String where = day == null ? "" : "WHERE day=?";
        String order = day == null ? "a.last_day DESC, b.read_at DESC, a.duration DESC" : "a.duration DESC, b.read_at DESC";
        String sql = "WITH totals AS (SELECT uri, MAX(day) AS last_day, SUM(ms) AS duration"
                + " FROM reading_time " + where + " GROUP BY uri)"
                + " SELECT COALESCE(b.id,0), COALESCE(b.title,r.title), COALESCE(b.author,''),"
                + " a.duration, (SELECT SUM(duration) FROM totals), COALESCE(b.cover_name, '')"
                + " FROM totals a JOIN reading_time r ON r.uri=a.uri AND r.day=a.last_day"
                + " LEFT JOIN books b ON b.id=(SELECT id FROM books WHERE uri=a.uri ORDER BY read_at DESC,id DESC LIMIT 1)"
                + " ORDER BY " + order + ", a.uri LIMIT 20";
        try (Cursor c = getReadableDatabase().rawQuery(sql, day == null ? null : new String[]{day})) {
            while (c.moveToNext()) result.add(new Reading(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4), c.getString(5)));
        }
        return result;
    }

    List<Rule> rules() {
        List<Rule> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,name,needle,replacement FROM replace_rules ORDER BY id", null)) {
            while (c.moveToNext()) result.add(new Rule(c.getLong(0),c.getString(1),c.getString(2),c.getString(3)));
        }
        return result;
    }

    void saveRule(Rule rule) {
        ContentValues v = nameValues(rule.name());
        v.put("needle",rule.find());
        v.put("replacement",rule.replace());
        if (rule.id()==0) getWritableDatabase().insertOrThrow("replace_rules",null,v);
        else getWritableDatabase().update("replace_rules",v,"id=?",args(rule.id()));
    }

    void removeRule(long id) { getWritableDatabase().delete("replace_rules","id=?",args(id)); }


    List<Long> add(Long target, String boxName, List<Import> items) {
        SQLiteDatabase db = getWritableDatabase();
        List<Long> ids = new ArrayList<>();
        if (items.isEmpty()) return ids;
        db.beginTransaction();
        try {
            long box = target == null ? db.insertOrThrow("boxes", null, nameValues(boxName)) : target;
            java.util.Set<String> existing=new java.util.HashSet<>();
            try(Cursor paths=db.rawQuery("SELECT uri FROM books WHERE box=?",args(box))){
                while(paths.moveToNext())existing.add(SourceFiles.key(Uri.parse(paths.getString(0))));
            }
            for (Import item : items) {
                if(!existing.add(SourceFiles.key(item.uri))){ids.add(-1L);continue;}
                ContentValues v = new ContentValues();
                v.put("box", box);
                v.put("uri", item.uri.toString());
                v.put("name", item.name);
                v.put("title", item.title);
                v.put("author", item.author);
                v.put("description", item.description);
                v.put("epub", item.epub ? 1 : 0);
                long id = db.insertWithOnConflict("books", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                ids.add(id);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return ids;
    }

    List<Box> boxes() {
        List<Box> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, name, book_order FROM boxes ORDER BY id DESC", null)) {
            while (c.moveToNext()) result.add(new Box(c.getLong(0), c.getString(1), BookOrder.read(c.getString(2))));
        }
        return result;
    }

    Box box(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id, name, book_order FROM boxes WHERE id=?", args(id))) {
            return c.moveToFirst() ? new Box(c.getLong(0), c.getString(1), BookOrder.read(c.getString(2))) : null;
        }
    }

    List<Book> books(long box) {
        return query("WHERE box=? ORDER BY id", args(box));
    }

    List<Book> books(Box box) {
        List<Book> books = books(box.id());
        box.order().sort(books);
        return books;
    }

    void sortBox(long id, BookOrder order) {
        ContentValues values = new ContentValues();
        values.put("book_order", order.name());
        getWritableDatabase().update("boxes", values, "id=?", args(id));
    }

    long bookCount() {
        return android.database.DatabaseUtils.queryNumEntries(getReadableDatabase(), "books");
    }

    List<Book> recent() {
        return query("WHERE read_at > 0 GROUP BY uri ORDER BY MAX(read_at) DESC, id DESC LIMIT 6", null);
    }

    Book book(long id) {
        List<Book> result = query("WHERE id=?", args(id));
        return result.isEmpty() ? null : result.get(0);
    }

    private List<Book> query(String clause, String[] args) {
        List<Book> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,box,uri,name,title,author,epub,chapter,position,read_at,text_offset,description,cover_name FROM books " + clause, args)) {
            while (c.moveToNext()) result.add(new Book(c.getLong(0), c.getLong(1), Uri.parse(c.getString(2)),
                    c.getString(3), c.getString(4), c.getString(5), c.getInt(6) != 0,
                    c.getInt(7), c.getDouble(8), c.getLong(9), c.getInt(10), c.getString(11), c.getString(12)));
        }
        return result;
    }

    void saveProgress(Book book, int chapter, double position, int offset, boolean opened) {
        ContentValues v = new ContentValues();
        v.put("chapter", chapter);
        v.put("position", position);
        v.put("text_offset", offset);
        if (opened) v.put("read_at", System.currentTimeMillis());
        getWritableDatabase().update("books", v, "uri=?", new String[]{book.uri.toString()});
    }

    Book saveBookInfo(long id, String title, String author, String description, File image) throws IOException {
        Book previous = book(id);
        if (previous == null) throw new IOException("这本书已从木匣移除");
        if (title.isBlank()) throw new IOException("请输入书名");
        ContentValues values = new ContentValues();
        values.put("title", title);
        values.put("author", author);
        values.put("description", description);
        File replacement = null;
        try {
            // Publish the new cover and text together after the new image is written.
            if (image != null) {
                replacement = File.createTempFile(id + "-cover-", ".jpg", covers);
                try (FileInputStream in = new FileInputStream(image);
                     FileOutputStream out = new FileOutputStream(replacement)) {
                    SourceFiles.copy(in, out, 3L * 1024 * 1024);
                    out.getFD().sync();
                }
                values.put("cover_name", replacement.getName());
            }
            if (getWritableDatabase().update("books", values, "id=?", args(id)) != 1) {
                throw new IOException("这本书已从木匣移除");
            }
        } catch (IOException | RuntimeException error) {
            if (replacement != null) replacement.delete();
            throw error;
        }
        if (replacement != null) cover(previous).delete();
        return book(id);
    }

    void renameBox(long id, String name) {
        getWritableDatabase().update("boxes", nameValues(name), "id=?", args(id));
    }

    void removeBook(Book book) {
        getWritableDatabase().delete("books", "id=?", args(book.id));
        cover(book).delete();
    }

    void removeBox(long box) {
        List<Book> members = books(box);
        getWritableDatabase().delete("boxes", "id=?", args(box));
        for (Book book : members) cover(book).delete();
    }

    File cover(Book book) { return cover(book.id(), book.coverName()); }

    File cover(long id, String name) {
        return new File(covers, name.isEmpty() ? id + ".jpg" : name);
    }

    private static ContentValues nameValues(String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        return v;
    }

    private static String[] args(long id) { return new String[]{Long.toString(id)}; }
}
