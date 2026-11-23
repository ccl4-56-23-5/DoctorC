package local.bookdoctorc;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.parser.Parser;

final class EditingSession implements Closeable {
    record Passage(int start, int end, String text) {}
    private static final Pattern ENTITY = Pattern.compile("&(?:#(?:[xX][0-9a-fA-F]+|[0-9]+)|[a-zA-Z][a-zA-Z0-9]*);");
    private final SourceFiles files;
    private final Library.Book book;
    private final File original;
    private final String path;
    private final TextSource source;
    final List<Passage> passages;

    static EditingSession open(Context context, SourceFiles files, Library.Book book,
                               ReaderDocument.Chapter chapter, int chapterIndex) throws IOException {
        File original = files.temp();
        try {
            files.snapshot(book.uri(), original, book.epub() ? SourceFiles.EPUB_LIMIT : SourceFiles.TXT_LIMIT);
            TextSource source;
            List<Passage> passages;
            if (book.epub()) {
                try (ZipFile zip = ZipFile.builder().setFile(original).get()) {
                    if (zip.getEntry("META-INF/signatures.xml") != null) {
                        throw new IOException("该 EPUB 包含数字签名，编辑会破坏签名");
                    }
                    byte[] bytes = entryBytes(zip, chapter.path());
                    source = TextSource.decode(bytes, true);
                    source.checkRoundTrip(bytes);
                }
                passages = markupPassages(source.text());
            } else {
                try (InputStream in = new FileInputStream(original)) {
                    byte[] bytes = SourceFiles.bytes(in, SourceFiles.TXT_LIMIT);
                    source = TextSource.decode(bytes, false);
                    source.checkRoundTrip(bytes);
                }
                ReaderDocument.Plain document = new ReaderDocument.Plain(source, TxtContents.mask(context));
                if (chapterIndex >= document.chapters.size()) throw new IOException("源文件目录已变化，请重新打开");
                var current = document.chapters.get(chapterIndex);
                passages = List.of(new Passage(current.start(), current.end(),
                        source.text().substring(current.start(), current.end())));
            }
            if (passages.isEmpty()) throw new IOException("此页没有可单独编辑的文字");
            return new EditingSession(files, book, original, chapter.path(), source, passages);
        } catch (IOException | RuntimeException error) {
            original.delete();
            throw error;
        }
    }

    private EditingSession(SourceFiles files, Library.Book book, File original, String path,
                           TextSource source, List<Passage> passages) {
        this.files = files;
        this.book = book;
        this.original = original;
        this.path = path;
        this.source = source;
        this.passages = passages;
    }

    File revise(Passage passage, String edited) throws IOException {
        File revised = files.temp();
        try {
            String old = source.text().substring(passage.start, passage.end);
            String updated = book.epub() ? replaceMarkupText(old, edited) : source.lineEndings(edited);
            String full = source.text().substring(0, passage.start) + updated + source.text().substring(passage.end);
            byte[] bytes = source.encode(full);
            if (book.epub()) {
                if (bytes.length > ReaderDocument.MARKUP_LIMIT) throw new IOException("修改后的章节过大");
                rewriteEpub(original, revised, path, bytes);
            } else {
                if (bytes.length > SourceFiles.TXT_LIMIT) throw new IOException("修改后的 TXT 超过 24 MB");
                if (full.trim().isEmpty()) throw new IOException("请保留至少一段正文");
                SourceFiles.write(revised, bytes);
            }
            return revised;
        } catch (IOException | RuntimeException error) {
            revised.delete();
            throw error;
        }
    }

    void save(File revised) throws IOException { files.save(book, original, revised); }

    @Override public void close() { original.delete(); }

    static List<Passage> markupPassages(String markup) {
        Element body = Jsoup.parse(markup, "", Parser.htmlParser().setTrackPosition(true)).body();
        List<Passage> passages = new ArrayList<>();
        collect(body, markup, passages);
        passages.sort(Comparator.comparingInt(Passage::start));
        return passages;
    }

    private static void collect(Node node, String markup, List<Passage> result) {
        if (node instanceof Element e && Set.of("script", "style", "svg", "math", "noscript", "textarea").contains(e.normalName())) return;
        if (node instanceof TextNode text && !text.getWholeText().trim().isEmpty()) {
            Range range = text.sourceRange();
            if (!range.isTracked()) return;
            int start = range.start().pos(), end = range.end().pos();
            if (start < 0 || start >= end || end > markup.length()) return;
            String raw = markup.substring(start, end);
            if (raw.indexOf('<') >= 0) return;
            String displayed = Parser.unescapeEntities(raw, false);
            if (displayed.equals(text.getWholeText())) result.add(new Passage(start, end, displayed));
        }
        for (Node child : node.childNodes()) collect(child, markup, result);
    }

    static String replaceMarkupText(String raw, String changed) throws IOException {
        if (changed.codePoints().anyMatch(cp -> cp < 0x20 && cp != 9 && cp != 10 && cp != 13)) {
            throw new IOException("文字含有 EPUB 不支持的控制字符");
        }
        String current = Parser.unescapeEntities(raw, false);
        if (current.equals(changed)) return raw;
        int[] rawAt = rawOffsets(raw,current);
        int first = 0, limit = Math.min(current.length(), changed.length());
        while (first < limit && current.charAt(first) == changed.charAt(first)) first++;
        while (first > 0 && rawAt[first] == -1) first--;
        int suffix = 0;
        while (suffix < limit - first
                && current.charAt(current.length() - suffix - 1) == changed.charAt(changed.length() - suffix - 1)) suffix++;
        while (suffix > 0 && rawAt[current.length() - suffix] == -1) suffix--;
        String middle = changed.substring(first, changed.length() - suffix)
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return raw.substring(0, rawAt[first]) + middle + raw.substring(rawAt[current.length() - suffix]);
    }

    static String replaceMatches(String raw,String current,String needle,String replacement) throws IOException {
        int[] offsets=rawOffsets(raw,current);
        if(replacement.codePoints().anyMatch(cp->cp<0x20 && cp!=9 && cp!=10 && cp!=13))
            throw new IOException("替换文字含有不支持的控制字符");
        String escaped=replacement.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        StringBuilder out=new StringBuilder(raw.length());
        int written=0;
        for(int match=0;(match=current.indexOf(needle,match))>=0;match+=needle.length()){
            int start=offsets[match],end=offsets[match+needle.length()];
            if(start<0 || end<0)throw new IOException("匹配范围切开了一个实体，无法无损替换");
            out.append(raw,written,start).append(escaped);
            written=end;
        }
        return out.append(raw,written,raw.length()).toString();
    }

    private static int[] rawOffsets(String raw,String current) throws IOException {
        int[] rawAt = new int[current.length() + 1];
        Arrays.fill(rawAt, -1);
        rawAt[0] = 0;
        StringBuilder decoded = new StringBuilder();
        var entity = ENTITY.matcher(raw);
        for (int at = 0; at < raw.length();) {
            int end = at + Character.charCount(raw.codePointAt(at));
            if (raw.charAt(at) == '&' && entity.region(at, raw.length()).lookingAt()) {
                end = entity.end();
                decoded.append(Parser.unescapeEntities(raw.substring(at, end), false));
            } else {
                decoded.append(raw, at, end);
            }
            if (decoded.length() > current.length()) throw new IOException("无法定位原文字符边界");
            rawAt[decoded.length()] = end;
            at = end;
        }
        if (!decoded.toString().equals(current)) throw new IOException("该片段使用了无法无损定位的实体写法");
        return rawAt;
    }

    static byte[] entryBytes(ZipFile zip, String path) throws IOException {
        ZipArchiveEntry entry = zip.getEntry(path);
        if (entry == null || entry.getSize() > ReaderDocument.MARKUP_LIMIT) throw new IOException("无法定位 EPUB 正文");
        try (InputStream in = zip.getInputStream(entry)) {
            if (in == null) throw new IOException("无法打开 EPUB 正文");
            return SourceFiles.bytes(in, ReaderDocument.MARKUP_LIMIT);
        }
    }

    static void rewriteEpub(File original, File revised, String path, byte[] changed) throws IOException {
        rewriteEpub(original, revised, Map.of(path, changed));
    }

    static void rewriteEpub(File original, File revised, Map<String,byte[]> changedEntries) throws IOException {
        try (ZipFile input = ZipFile.builder().setFile(original).get();
             ZipArchiveOutputStream output = new ZipArchiveOutputStream(revised)) {
            Set<String> edited = new HashSet<>();
            var entries = input.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                ZipArchiveEntry copy = new ZipArchiveEntry(entry);
                byte[] changed = changedEntries.get(entry.getName());
                if (changed != null) {
                    if (!edited.add(entry.getName())) throw new IOException("EPUB 内存在重名章节");
                    CRC32 crc = new CRC32();
                    crc.update(changed);
                    copy.setSize(changed.length);
                    copy.setCompressedSize(-1);
                    copy.setCrc(crc.getValue());
                    output.putArchiveEntry(copy);
                    output.write(changed);
                    output.closeArchiveEntry();
                } else {
                    try (InputStream raw = input.getRawInputStream(entry)) {
                        if (raw == null) throw new IOException("不能无损复制 EPUB 内的文件");
                        output.addRawArchiveEntry(copy, raw);
                    }
                }
            }
            if (edited.size() != changedEntries.size()) throw new IOException("修改位置已不存在");
        }
        copyZipComment(original, revised);
        verifyEpub(original, revised, changedEntries);
    }

    private static void copyZipComment(File original, File revised) throws IOException {
        byte[] comment;
        try (RandomAccessFile in = new RandomAccessFile(original, "r")) {
            int length = (int) Math.min(in.length(), 65557);
            byte[] tail = new byte[length];
            in.seek(in.length() - length);
            in.readFully(tail);
            for (int at = length - 22; at >= 0; at--) {
                if (tail[at] != 0x50 || tail[at + 1] != 0x4b || tail[at + 2] != 0x05 || tail[at + 3] != 0x06) continue;
                int size = (tail[at + 20] & 255) | ((tail[at + 21] & 255) << 8);
                if (at + 22 + size != length) continue;
                if (size == 0) return;
                comment = Arrays.copyOfRange(tail, at + 22, length);
                try (RandomAccessFile out = new RandomAccessFile(revised, "rw")) {
                    out.seek(out.length() - 2);
                    out.write(size & 255);
                    out.write((size >>> 8) & 255);
                    out.write(comment);
                }
                return;
            }
        }
        throw new IOException("EPUB ZIP 目录校验失败");
    }

    private static void verifyEpub(File original, File revised, Map<String,byte[]> changedEntries) throws IOException {
        try (ZipFile old = ZipFile.builder().setFile(original).get();
             ZipFile fresh = ZipFile.builder().setFile(revised).get()) {
            for (var entry : changedEntries.entrySet()) {
                if (!Arrays.equals(entry.getValue(), entryBytes(fresh, entry.getKey()))) throw new IOException("修改后的正文校验失败");
            }
            var previous = old.getEntriesInPhysicalOrder();
            var after = fresh.getEntriesInPhysicalOrder();
            while (previous.hasMoreElements() && after.hasMoreElements()) {
                var a = previous.nextElement();
                var b = after.nextElement();
                if (!a.getName().equals(b.getName()) || a.getMethod() != b.getMethod()) {
                    throw new IOException("EPUB 目录结构校验失败");
                }
                if (changedEntries.containsKey(a.getName())) continue;
                if (a.getCrc() != b.getCrc() || a.getSize() != b.getSize() || a.getCompressedSize() != b.getCompressedSize()) {
                    throw new IOException("未编辑文件的校验值变化");
                }
                try (InputStream one = old.getRawInputStream(a); InputStream two = fresh.getRawInputStream(b)) {
                    if (!sameBytes(one, two)) throw new IOException("未编辑文件的压缩数据变化");
                }
            }
            if (previous.hasMoreElements() || after.hasMoreElements()) throw new IOException("EPUB 条目数量变化");
        }
    }

    private static boolean sameBytes(InputStream a, InputStream b) throws IOException {
        byte[] left = new byte[16384], right = new byte[16384];
        for (;;) {
            int n = readBlock(a, left);
            if (n != readBlock(b, right)) return false;
            if (n == 0) return true;
            for (int i = 0; i < n; i++) if (left[i] != right[i]) return false;
        }
    }

    private static int readBlock(InputStream stream, byte[] buffer) throws IOException {
        int at = 0;
        while (at < buffer.length) {
            int n = stream.read(buffer, at, buffer.length - at);
            if (n < 0) break;
            at += n;
        }
        return at;
    }
}
