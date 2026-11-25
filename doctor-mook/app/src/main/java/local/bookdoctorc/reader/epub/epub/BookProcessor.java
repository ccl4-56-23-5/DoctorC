package local.bookdoctorc.reader.epub.epub;

import local.bookdoctorc.reader.epub.domain.EpubBook;

/**
 * Post-processes a book.
 * <p>
 * Can be used to clean up a book after reading or before writing.
 *
 * @author paul
 */
public interface BookProcessor {

    /**
     * A BookProcessor that returns the input book unchanged.
     */
    BookProcessor IDENTITY_BOOKPROCESSOR = book -> book;

    EpubBook processBook(EpubBook book);
}
