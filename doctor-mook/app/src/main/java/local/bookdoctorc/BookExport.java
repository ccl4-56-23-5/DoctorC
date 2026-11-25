package local.bookdoctorc;

import android.content.Context;
import android.text.TextUtils;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import local.bookdoctorc.reader.epub.domain.*;
import local.bookdoctorc.reader.epub.epub.EpubWriter;
import org.jsoup.Jsoup;

final class BookExport {
    static String fileName(Library.Book book, boolean epub) {
        String extension = epub ? ".epub" : ".txt";
        String suffix = " 作者：" + fileNamePart(book.author(), "未标注", 92) + extension;
        int titleBudget = 240 - suffix.getBytes(StandardCharsets.UTF_8).length;
        return fileNamePart(book.title(), "未命名书籍", titleBudget) + suffix;
    }

    private static String fileNamePart(String value, String fallback, int byteLimit) {
        String safe = value.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]+", " ").trim();
        if (safe.isEmpty()) return fallback;
        // A filename limit is counted in UTF-8 bytes. Keep complete code points at the boundary.
        CharBuffer text = CharBuffer.wrap(safe);
        StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPLACE)
                .encode(text, ByteBuffer.allocate(byteLimit), true);
        return safe.substring(0, text.position()).trim();
    }

    static File convert(Context context,SourceFiles files,Library library,Library.Book book,boolean toEpub) throws IOException {
        File target=files.temp();
        try(ReaderDocument source=ReaderDocument.open(context,files,book);
            OutputStream out=new BufferedOutputStream(new FileOutputStream(target))){
            if(toEpub) writeEpub((ReaderDocument.Plain)source,book,library.cover(book),out);
            else writeTxt(source,out);
            return target;
        }catch(IOException | RuntimeException error){
            target.delete();
            throw error;
        }
    }

    private static void writeTxt(ReaderDocument source,OutputStream out) throws IOException {
        BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(out,StandardCharsets.UTF_8));
        for(int i=0;i<source.chapters.size();i++){
            var chapter=source.chapters.get(i);
            writer.write(chapter.title());
            writer.newLine();
            var document=Jsoup.parse(source.page(i,"").html());
            document.select("script,style,head").remove();
            document.select("p,div,li,blockquote,h1,h2,h3,h4,h5,h6").forEach(block->block.appendText("\n"));
            writer.write(document.body().wholeText().trim());
            writer.newLine();
            writer.newLine();
        }
        writer.flush();
    }

    private static void writeEpub(ReaderDocument.Plain source,Library.Book book,File cover,OutputStream out) throws IOException {
        EpubBook epub=new EpubBook();
        epub.setVersion("2.0");
        epub.getMetadata().addTitle(book.title());
        epub.getMetadata().setLanguage("zh");
        if(!book.author().isBlank()) epub.getMetadata().addAuthor(new Author(book.author()));
        if (!book.description().isBlank()) epub.getMetadata().addDescription(book.description());
        if(cover.isFile()){
            try(InputStream in=new FileInputStream(cover)){
                epub.setCoverImage(new Resource(SourceFiles.bytes(in,3*1024*1024),"cover.jpg"));
            }
        }
        for(int i=0;i<source.chapters.size();i++){
            ReaderDocument.Chapter chapter=source.chapters.get(i);
            String text=source.source.text().substring(chapter.start(),chapter.end());
            String html="<?xml version='1.0' encoding='UTF-8'?><html xmlns='http://www.w3.org/1999/xhtml'><head><title>"
                    +TextUtils.htmlEncode(chapter.title())+"</title></head><body><div style='white-space:pre-wrap'>"
                    +TextUtils.htmlEncode(text)+"</div></body></html>";
            epub.addSection(chapter.title(),new Resource(html.getBytes(StandardCharsets.UTF_8),"text/section-"+(i+1)+".xhtml"));
        }
        new EpubWriter().write(epub,out);
    }
}
