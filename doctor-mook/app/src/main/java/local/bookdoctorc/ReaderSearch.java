package local.bookdoctorc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;

final class ReaderSearch {
    static final String ANCHOR = "bookdoctor-reader-search-result";
    private static final int LIMIT = 80;

    record Hit(int chapter, int start, int end, String preview) {}
    record Result(List<Hit> hits, boolean more) {}
    private record Run(TextNode node, int start, int end) {}

    private final StringBuilder text = new StringBuilder();
    private final List<Run> runs = new ArrayList<>();

    static Result find(ReaderDocument book, ReaderTypography style, String query, BooleanSupplier cancelled) throws IOException {
        Pattern literal = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < book.chapters.size() && !cancelled.getAsBoolean(); i++) {
            Element section = ReaderLayout.searchable(book.page(i, ""), i, style, book instanceof ReaderDocument.Plain);
            ReaderSearch chapter = new ReaderSearch(section);
            var matches = literal.matcher(chapter.text);
            while (matches.find()) {
                if (hits.size() == LIMIT) return new Result(hits, true);
                int before = Math.max(0, matches.start() - 25);
                int after = Math.min(chapter.text.length(), matches.end() + 36);
                if (before > 0 && Character.isLowSurrogate(chapter.text.charAt(before))) before--;
                if (after < chapter.text.length() && Character.isLowSurrogate(chapter.text.charAt(after))) after++;
                String excerpt = chapter.text.substring(before, after).replaceAll("\\s+", " ").strip();
                hits.add(new Hit(i, matches.start(), matches.end(),
                        (before == 0 ? "" : "…") + excerpt + (after == chapter.text.length() ? "" : "…")));
            }
        }
        return new Result(hits, false);
    }

    static void mark(Element chapter, Hit hit) {
        ReaderSearch source = new ReaderSearch(chapter);
        boolean first = true;
        for (Run run : source.runs) {
            if (run.end <= hit.start()) continue;
            if (run.start >= hit.end()) break;
            int from = Math.max(0, hit.start() - run.start);
            int to = Math.min(run.end, hit.end()) - run.start;
            TextNode selected = from == 0 ? run.node : run.node.splitText(from);
            if (to < run.end - run.start) selected.splitText(to - from);
            Element highlight = new Element("mark").attr("data-bookdoctor-hit", "");
            if (first) highlight.id(ANCHOR);
            selected.before(highlight);
            highlight.appendChild(selected);
            first = false;
        }
    }

    private ReaderSearch(Element section) {
        NodeTraversor.filter(new NodeFilter() {
            @Override public FilterResult head(Node node, int depth) {
                if (node instanceof Element element) {
                    if (switch (element.normalName()) {
                        case "style", "svg", "math", "rt", "rp", "audio", "video" -> true;
                        default -> false;
                    }) return FilterResult.SKIP_ENTIRELY;
                    if (element.tag().isBlock() || element.normalName().equals("br")) separate();
                } else if (node instanceof TextNode run) {
                    int start = text.length();
                    text.append(run.getWholeText());
                    if (text.length() > start) runs.add(new Run(run, start, text.length()));
                }
                return FilterResult.CONTINUE;
            }

            @Override public FilterResult tail(Node node, int depth) {
                if (node instanceof Element element && element.tag().isBlock()) separate();
                return FilterResult.CONTINUE;
            }
        }, section);
    }

    private void separate() {
        if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') text.append('\n');
    }
}
