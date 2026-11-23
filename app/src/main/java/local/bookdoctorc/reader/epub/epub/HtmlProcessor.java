package local.bookdoctorc.reader.epub.epub;

import java.io.OutputStream;

import local.bookdoctorc.reader.epub.domain.Resource;

@SuppressWarnings("unused")
public interface HtmlProcessor {

    void processHtmlResource(Resource resource, OutputStream out);
}
