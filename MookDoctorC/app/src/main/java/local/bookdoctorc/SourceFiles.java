package local.bookdoctorc;

import android.content.ContentResolver;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

final class SourceFiles {
    static final long EPUB_LIMIT = 256L * 1024 * 1024;
    static final int TXT_LIMIT = 24 * 1024 * 1024;

    private final Context context;
    private final ContentResolver resolver;

    SourceFiles(Context context) {
        this.context = context.getApplicationContext();
        resolver = context.getContentResolver();
    }

    String displayName(Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        }
        String name = uri.getLastPathSegment();
        return name == null ? "书籍" : name;
    }

    InputStream read(Uri uri) throws IOException {
        InputStream in = resolver.openInputStream(authorizedUri(uri,false));
        if (in == null) throw new IOException("无法打开源文件");
        return in;
    }

    ParcelFileDescriptor descriptor(Uri uri) throws IOException {
        ParcelFileDescriptor fd = resolver.openFileDescriptor(authorizedUri(uri,false), "r");
        if (fd == null) throw new IOException("无法打开源文件");
        return fd;
    }

    boolean canWrite(Uri uri){
        if ((context.getPackageName() + ".files").equals(uri.getAuthority())) return true;
        Uri candidate=authorizedUri(uri,true);
        for(UriPermission permission:resolver.getPersistedUriPermissions()){
            if(permission.isWritePermission() && grants(permission.getUri(),candidate))return true;
        }
        return false;
    }

    private Uri authorizedUri(Uri uri,boolean write){
        var grants=resolver.getPersistedUriPermissions();
        for(UriPermission permission:grants){
            if((write?permission.isWritePermission():permission.isReadPermission()) && grants(permission.getUri(),uri))
                return uri;
        }
        if(!"com.android.externalstorage.documents".equals(uri.getAuthority()))return uri;
        String id;
        try{id=DocumentsContract.getDocumentId(uri);}catch(IllegalArgumentException invalid){return uri;}
        Uri best=null;
        int length=-1;
        for(UriPermission permission:grants){
            Uri tree=permission.getUri();
            if(!(write?permission.isWritePermission():permission.isReadPermission()) ||
                    !DocumentsContract.isTreeUri(tree) || !uri.getAuthority().equals(tree.getAuthority()))continue;
            String root=DocumentsContract.getTreeDocumentId(tree);
            if(root.length()>length && (id.equals(root)||id.startsWith(root.endsWith(":")?root:root+"/"))){
                best=tree;
                length=root.length();
            }
        }
        return best==null?uri:DocumentsContract.buildDocumentUriUsingTree(best,id);
    }

    private static boolean grants(Uri grant,Uri document){
        if(grant.equals(document))return true;
        return DocumentsContract.isTreeUri(document) && DocumentsContract.isTreeUri(grant)
                && java.util.Objects.equals(grant.getAuthority(),document.getAuthority())
                && DocumentsContract.getTreeDocumentId(document).equals(DocumentsContract.getTreeDocumentId(grant));
    }

    static String key(Uri uri){
        try{return uri.getAuthority()+"\\n"+DocumentsContract.getDocumentId(uri);}
        catch(IllegalArgumentException invalid){return uri.toString();}
    }


    File temp() throws IOException { return File.createTempFile("book-", ".tmp", context.getCacheDir()); }

    File backup(Library.Book book) {
        File dir = new File(context.getFilesDir(), "recovery");
        dir.mkdirs();
        return new File(dir, book.id() + (book.epub() ? ".epub" : ".txt"));
    }

    void snapshot(Uri uri, File file, long max) throws IOException {
        try (InputStream in = read(uri); OutputStream out = new FileOutputStream(file)) {
            copy(in, out, max);
        }
    }

    void export(Uri uri, Uri target) throws IOException {
        if (key(uri).equals(key(target))) throw new IOException("请为导出选择新的文件位置");
        try (InputStream in = read(uri); OutputStream out = resolver.openOutputStream(target, "wt")) {
            if (out == null) throw new IOException("无法打开导出位置");
            copy(in, out, EPUB_LIMIT);
        }
    }

    void export(File file, Uri target) throws IOException {
        try (InputStream in = new FileInputStream(file); OutputStream out = resolver.openOutputStream(target, "wt")) {
            if (out == null) throw new IOException("无法打开导出位置");
            copy(in, out, EPUB_LIMIT);
        }
    }

    void save(Library.Book book, File original, File revised) throws IOException {
        if (!canWrite(book.uri())) throw new IOException("该来源只允许读取。可用「另存修改稿」。");
        byte[] originalHash = digest(original);
        byte[] revisedHash = digest(revised);
        File backup = backup(book);
        AtomicFile safeBackup = new AtomicFile(backup);
        try (InputStream in = new FileInputStream(original)) {
            FileOutputStream out = safeBackup.startWrite();
            try {
                copy(in, out, EPUB_LIMIT);
                safeBackup.finishWrite(out);
            } catch (IOException error) {
                safeBackup.failWrite(out);
                throw error;
            }
        }
        try (InputStream in = read(book.uri())) {
            if (!Arrays.equals(originalHash, digest(in))) {
                throw new IOException("源文件已被其他程序修改，请重新打开后再编辑");
            }
        }
        if (Arrays.equals(originalHash, revisedHash)) return;
        ParcelFileDescriptor fd = resolver.openFileDescriptor(authorizedUri(book.uri(),true), "rw");
        if (fd == null) throw new IOException("文件提供程序未开放写入，可用「另存修改稿」");
        try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
            FileChannel channel = out.getChannel();
            channel.position(0); // 验证可定位，避免向只写管道写入原件
            try {
                writeAtStart(out, revised);
                try (InputStream in = read(book.uri())) {
                    if (!Arrays.equals(revisedHash, digest(in))) throw new IOException("源文件写回校验失败");
                }
            } catch (IOException | RuntimeException failure) {
                try {
                    writeAtStart(out, original);
                } catch (IOException | RuntimeException rollback) {
                    failure.addSuppressed(rollback);
                    throw new IOException("恢复写入失败，请从书籍菜单导出修改前备份", failure);
                }
                throw new IOException("写入失败，已尝试恢复源文件，修改前备份可导出", failure);
            }
        }
    }

    private static void writeAtStart(FileOutputStream out, File input) throws IOException {
        FileChannel channel = out.getChannel();
        channel.position(0);
        try (InputStream in = new FileInputStream(input)) { copy(in, out, EPUB_LIMIT); }
        channel.truncate(channel.position());
        channel.force(true);
    }

    static byte[] bytes(InputStream in, int max) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(in, out, max);
            return out.toByteArray();
        }
    }

    static void copy(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buffer = new byte[65536];
        long total = 0;
        for (int count; (count = in.read(buffer)) != -1;) {
            total += count;
            if (total > max) throw new IOException("文件超过本版允许的大小");
            out.write(buffer, 0, count);
        }
    }

    static void write(File file, byte[] data) throws IOException { Files.write(file.toPath(), data); }

    private static byte[] digest(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return digest(in); }
    }

    private static byte[] digest(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int count; (count = in.read(buffer)) != -1;) md.update(buffer, 0, count);
            return md.digest();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    static Bitmap bitmap(byte[] bytes, int maxEdge) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxEdge) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    static void storeCover(File file, Bitmap bitmap) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = atomic.startWrite();
        try {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) throw new IOException("封面无法保存");
            atomic.finishWrite(out);
        } catch (IOException error) {
            atomic.failWrite(out);
            throw error;
        }
    }
}
