package local.bookdoctorc;

import java.io.*;
import java.util.*;
import org.apache.commons.compress.archivers.zip.ZipFile;

final class BatchReplace implements Closeable {
    private final SourceFiles files;
    private final Library.Book book;
    private final File original, revised;
    final int count;

    private BatchReplace(SourceFiles files, Library.Book book, File original, File revised, int count) {
        this.files = files;
        this.book = book;
        this.original = original;
        this.revised = revised;
        this.count = count;
    }

    static BatchReplace prepare(SourceFiles files, Library.Book book, Library.Rule rule) throws IOException {
        if (rule.find().isEmpty()) throw new IOException("请输入要查找的文字");
        File original = files.temp();
        File revised = null;
        try {
            revised = files.temp();
            files.snapshot(book.uri(), original, book.epub() ? SourceFiles.EPUB_LIMIT : SourceFiles.TXT_LIMIT);
            int count;
            if (book.epub()) count = epub(original, revised, rule);
            else count = plain(original, revised, rule);
            return new BatchReplace(files, book, original, revised, count);
        } catch (IOException | RuntimeException e) {
            original.delete();
            if (revised != null) revised.delete();
            throw e;
        }
    }

    private static int plain(File original, File revised, Library.Rule rule) throws IOException {
        byte[] bytes;
        try (InputStream in = new FileInputStream(original)) { bytes = SourceFiles.bytes(in, SourceFiles.TXT_LIMIT); }
        TextSource source = TextSource.decode(bytes, false);
        source.checkRoundTrip(bytes);
        int matches = occurrences(source.text(), rule.find());
        if (matches > 0) {
            String replacement = source.lineEndings(rule.replace());
            long size = source.text().length() + (long) matches * (replacement.length() - rule.find().length());
            if (size > SourceFiles.TXT_LIMIT) throw new IOException("替换后的文件过大");
            String text = source.text().replace(rule.find(), replacement);
            if (text.trim().isEmpty()) throw new IOException("替换后请保留正文");
            byte[] output = source.encode(text);
            if (output.length > SourceFiles.TXT_LIMIT) throw new IOException("替换后的文件过大");
            SourceFiles.write(revised, output);
        }
        return matches;
    }

    private static int epub(File original, File revised, Library.Rule rule) throws IOException {
        Map<String,byte[]> changed = new LinkedHashMap<>();
        int matches = 0;
        long changedBytes = 0;
        try (ZipFile zip = ZipFile.builder().setFile(original).get()) {
            if (zip.getEntry("META-INF/signatures.xml") != null) throw new IOException("此 EPUB 带数字签名");
            var entries = zip.getEntriesInPhysicalOrder();
            Set<String> paths = new HashSet<>();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String path = entry.getName();
                String lower = path.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm"))) continue;
                if (!paths.add(path)) throw new IOException("EPUB 中有重名正文");
                byte[] bytes = EditingSession.entryBytes(zip, path);
                TextSource source = TextSource.decode(bytes, true);
                List<EditingSession.Passage> nodes = EditingSession.markupPassages(source.text());
                StringBuilder page = null;
                for (int i = nodes.size() - 1; i >= 0; i--) {
                    var span = nodes.get(i);
                    int n = occurrences(span.text(), rule.find());
                    if (n == 0) continue;
                    long size = span.text().length() + (long) n * (rule.replace().length() - rule.find().length());
                    if (size > ReaderDocument.MARKUP_LIMIT) throw new IOException("替换后的正文过大");
                    if (page == null) page = new StringBuilder(source.text());
                    String raw = source.text().substring(span.start(), span.end());
                    String replacement = EditingSession.replaceMatches(raw,span.text(),rule.find(),rule.replace());
                    if (page.length() - (span.end() - span.start()) + replacement.length() > ReaderDocument.MARKUP_LIMIT) {
                        throw new IOException("替换后的章节过大");
                    }
                    page.replace(span.start(), span.end(), replacement);
                    matches += n;
                }
                if (page != null) {
                    source.checkRoundTrip(bytes);
                    byte[] output = source.encode(page.toString());
                    changedBytes += output.length;
                    if (output.length > ReaderDocument.MARKUP_LIMIT || changedBytes > 32L * 1024 * 1024) {
                        throw new IOException("一次替换的正文过大");
                    }
                    changed.put(path, output);
                }
            }
        }
        if (!changed.isEmpty()) EditingSession.rewriteEpub(original, revised, changed);
        return matches;
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int at = 0; (at = value.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }

    void save() throws IOException { if (count > 0) files.save(book, original, revised); }
    @Override public void close() { original.delete(); revised.delete(); }
}
