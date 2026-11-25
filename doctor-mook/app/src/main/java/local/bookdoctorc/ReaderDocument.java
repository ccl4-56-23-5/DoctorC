package local.bookdoctorc;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import java.io.*;
import java.util.*;
import local.bookdoctorc.reader.epub.domain.EpubBook;
import local.bookdoctorc.reader.epub.domain.Resource;
import local.bookdoctorc.reader.epub.domain.SpineReference;
import local.bookdoctorc.reader.epub.domain.TOCReference;
import local.bookdoctorc.reader.epub.epub.EpubReader;
import local.bookdoctorc.reader.epub.util.zip.AndroidZipFile;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

abstract class ReaderDocument implements Closeable {
    static final int MARKUP_LIMIT = 8 * 1024 * 1024;
    static final int ASSET_LIMIT = 12 * 1024 * 1024;

    record Chapter(String title, String path, int start, int end, int volume, boolean heading) {
        Chapter(String title, String path, int start, int end) { this(title, path, start, end, -1, false); }
    }
    record Toc(String title, int chapter, String fragment, int level, boolean group) {}
    record Position(int chapter, double fraction) {}
    record Page(String path, String html) {}
    record Info(String title, String author, String description, byte[] cover) {}
    record Asset(String mime, byte[] bytes) {}
    final List<Chapter> chapters = new ArrayList<>();

    static ReaderDocument open(Context context, SourceFiles files, Library.Book book) throws IOException {
        if (book.epub()) return new Epub(files.descriptor(book.uri()), book.name());
        try (InputStream in = files.read(book.uri())) {
            return new Plain(TextSource.decode(SourceFiles.bytes(in, SourceFiles.TXT_LIMIT), false), TxtContents.mask(context));
        }
    }

    List<Toc> contents() {
        List<Toc> result = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            Chapter c = chapters.get(i);
            result.add(new Toc(c.title, i, null, c.volume >= 0 && !c.heading ? 1 : 0, c.heading));
        }
        return result;
    }

    int offset(int chapter, double fraction) { return -1; }
    Position position(int chapter, double fraction, int offset) {
        return new Position(Math.max(0, Math.min(chapter, chapters.size() - 1)), fraction);
    }


    abstract Page page(int index, String appearance) throws IOException;
    Asset asset(String path) throws IOException { return null; }
    Info info() throws IOException { return new Info("", "", "", null); }
    @Override public void close() throws IOException {}

    static final class Plain extends ReaderDocument {
        private static final int SECTION_LIMIT = 18000;
        final TextSource source;

        Plain(TextSource source, int enabled) throws IOException {
            this.source = source;
            String content = source.text();
            if (content.trim().isEmpty()) throw new IOException("TXT 没有正文");
            int start = 0, volume = -1;
            boolean heading = false;
            String title = "正文";
            for (int offset = 0; offset < content.length();) {
                int next = offset;
                while (next < content.length() && content.charAt(next) != '\n' && content.charAt(next) != '\r') next++;
                if (next - offset <= 82) {
                    String line = content.substring(offset, next).strip();
                    TxtContents.Kind kind = TxtContents.match(line, enabled);
                    if (kind != null) {
                        if (offset > start) split(content, title, start, offset, volume, heading);
                        start = offset;
                        title = line;
                        heading = kind.volume;
                        if (heading) volume = chapters.size();
                    }
                }
                if (next < content.length() && content.charAt(next++) == '\r'
                        && next < content.length() && content.charAt(next) == '\n') next++;
                offset = next;
            }
            split(content, title, start, content.length(), volume, heading);
        }

        private void split(String text, String title, int start, int end, int volume, boolean heading) {
            for (int segment = 1; start < end; segment++) {
                int stop = Math.min(start + SECTION_LIMIT, end);
                if (stop < end) {
                    int newline = text.lastIndexOf('\n', stop - 1);
                    if (newline >= start + SECTION_LIMIT / 2) stop = newline + 1;
                    if (Character.isHighSurrogate(text.charAt(stop - 1))) stop--;
                }
                String label = segment == 1 ? title : title + " · " + segment;
                chapters.add(new Chapter(label, "", start, stop, volume, heading && segment == 1));
                start = stop;
            }
        }

        @Override int offset(int chapter, double fraction) {
            Chapter section = chapters.get(chapter);
            return section.start + (int) Math.round(fraction * (section.end - section.start - 1));
        }

        @Override Position position(int chapter, double fraction, int offset) {
            if (offset < 0) return super.position(chapter, fraction, offset);
            int first = 0, last = chapters.size() - 1;
            while (first < last) {
                int mid = (first + last) / 2;
                if (offset >= chapters.get(mid).end) first = mid + 1; else last = mid;
            }
            Chapter found = chapters.get(first);
            return new Position(first, Math.max(0,Math.min(1,(offset-found.start)/(double)Math.max(1,found.end-found.start-1))));
        }


        @Override Page page(int index, String appearance) {
            Chapter section = chapters.get(index);
            String html = "<!doctype html><html><head><meta charset='utf-8'>"
                    + appearance + "</head><body><main class='plain'>"
                    + TextUtils.htmlEncode(source.text().substring(section.start, section.end))
                    + "</main></body></html>";
            return new Page("chapter.txt", html);
        }
    }

    static final class Epub extends ReaderDocument {
        private final AndroidZipFile zip;
        private final EpubBook book;
        private final List<Toc> nativeContents = new ArrayList<>();

        Epub(ParcelFileDescriptor fd, String name) throws IOException {
            AndroidZipFile opened = null;
            try {
                long size = fd.getStatSize();
                if (size <= 0 || size > SourceFiles.EPUB_LIMIT) {
                    throw new IOException("EPUB 需要可随机读取的本地文件，大小上限为 256 MB");
                }
                opened = new AndroidZipFile(fd, name);
                checkEntries(opened);
                book = new EpubReader().readEpubLazy(opened, "UTF-8");
                Map<String, String> toc = new HashMap<>();
                readToc(book.getTableOfContents().getTocReferences(), toc, 0);
                for (SpineReference ref : book.getSpine().getSpineReferences()) {
                    Resource resource = ref.getResource();
                    if (resource == null || !isMarkup(resource.getHref())) continue;
                    String title = toc.get(resource.getHref());
                    if (title == null || title.trim().isEmpty()) title = resource.getTitle();
                    if (title == null || title.trim().isEmpty()) title = "章节 " + (chapters.size() + 1);
                    chapters.add(new Chapter(title, resource.getHref(), 0, 0));
                }
                if (chapters.isEmpty()) throw new IOException("EPUB 没有可阅读的章节");
                readNativeContents(book.getTableOfContents().getTocReferences(), 0);
                zip = opened;
            } catch (IOException | RuntimeException invalid) {
                if (opened != null) opened.close(); else fd.close();
                if (invalid instanceof IOException) throw (IOException) invalid;
                throw new IOException("无法解析 EPUB，文件可能损坏或加密", invalid);
            }
        }

        @Override List<Toc> contents() {
            return nativeContents.isEmpty() ? super.contents() : nativeContents;
        }

        private void readNativeContents(List<TOCReference> refs, int level) {
            if (refs == null || level > 24) return;
            for (TOCReference ref : refs) {
                int index = -1;
                if (ref.getResource() != null) {
                    String href = ref.getResource().getHref();
                    for (int i = 0; i < chapters.size(); i++) if (chapters.get(i).path.equals(href)) {
                        index = i;
                        break;
                    }
                }
                boolean group = ref.getChildren() != null && !ref.getChildren().isEmpty();
                if (index >= 0 || group) nativeContents.add(new Toc(ref.getTitle(), index, ref.getFragmentId(), level, group));
                readNativeContents(ref.getChildren(), level + 1);
            }
        }


        private static void checkEntries(AndroidZipFile file) throws IOException {
            var entries = file.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (++count > 12000) throw new IOException("EPUB 内文件数量过多");
                String name = entry.getName().toLowerCase(Locale.ROOT);
                if ((name.endsWith(".xml") || name.endsWith(".opf") || name.endsWith(".ncx")
                        || isMarkup(name)) && entry.getSize() > MARKUP_LIMIT) {
                    throw new IOException("EPUB 内章节或目录过大");
                }
            }
        }

        private static void readToc(List<TOCReference> refs, Map<String, String> titles, int depth) {
            if (refs == null || depth > 32) return;
            for (TOCReference ref : refs) {
                if (ref.getResource() != null) titles.putIfAbsent(ref.getResource().getHref(), ref.getTitle());
                readToc(ref.getChildren(), titles, depth + 1);
            }
        }

        @Override Page page(int index, String appearance) throws IOException {
            String path = chapters.get(index).path;
            Resource resource = book.getResources().getByHref(path);
            if (resource == null) throw new IOException("目录指向的正文已缺失");
            String original = TextSource.decode(readResource(resource, MARKUP_LIMIT), true).text();
            var doc = Jsoup.parse(original);
            doc.outputSettings().prettyPrint(false);
            doc.select("script,iframe,frame,object,embed,base,meta[http-equiv],meta[charset]").remove();
            Element head = doc.head();
            head.prepend("<meta charset='utf-8'>"
                    + "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; "
                    + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; "
                    + "media-src 'self'; form-action 'none'; base-uri 'none'\">");
            head.append(appearance);
            return new Page(path, doc.outerHtml());
        }

        @Override Asset asset(String path) throws IOException {
            Resource resource = book.getResources().getByHref(path);
            if (resource == null || resource.getSize() > ASSET_LIMIT) return null;
            String name = path.toLowerCase(Locale.ROOT);
            String mime;
            if (name.endsWith(".css")) mime = "text/css";
            else if (name.endsWith(".png")) mime = "image/png";
            else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) mime = "image/jpeg";
            else if (name.endsWith(".gif")) mime = "image/gif";
            else if (name.endsWith(".webp")) mime = "image/webp";
            else if (name.endsWith(".svg")) mime = "image/svg+xml";
            else if (name.endsWith(".woff2")) mime = "font/woff2";
            else if (name.endsWith(".woff")) mime = "font/woff";
            else if (name.endsWith(".ttf")) mime = "font/ttf";
            else if (name.endsWith(".otf")) mime = "font/otf";
            else return null;
            return new Asset(mime, readResource(resource, ASSET_LIMIT));
        }

        @Override Info info() throws IOException {
            String title = book.getMetadata().getFirstTitle();
            String author = book.getMetadata().getAuthors().isEmpty()
                    ? "" : book.getMetadata().getAuthors().get(0).toString().replaceAll("^, |, $", "");
            byte[] cover = null;
            Resource image = book.getCoverImage();
            if (image != null && image.getSize() <= ASSET_LIMIT) {
                try { cover = readResource(image, ASSET_LIMIT); }
                catch (IOException ignored) { /* An unreadable cover does not invalidate the text. */ }
            }
            String description = String.join("\n\n", book.getMetadata().getDescriptions()).trim();
            return new Info(title == null ? "" : title.trim(), author, description, cover);
        }

        @Override public void close() throws IOException { zip.close(); }

        private static byte[] readResource(Resource resource, int limit) throws IOException {
            try (InputStream in = resource.getInputStream()) { return SourceFiles.bytes(in, limit); }
        }

        private static boolean isMarkup(String path) {
            String lower = path.toLowerCase(Locale.ROOT);
            return lower.endsWith(".htm") || lower.endsWith(".html") || lower.endsWith(".xhtml");
        }
    }
}
