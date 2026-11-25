package local.bookdoctorc;

import android.net.Uri;
import java.io.IOException;
import java.net.URI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

final class ReaderLayout {
    static final String HOST = "local-reader.invalid";
    enum Mode {
        VERTICAL("竖向连续滚动"), SLIDE("横向平移"), SINGLE("横向单页");
        final String label;
        Mode(String label){this.label=label;}
        static Mode read(String value){
            try{return valueOf(value);}catch(IllegalArgumentException error){return VERTICAL;}
        }
    }
    record Rendered(String url,String html) {}

    static String url(String path) { return "https://" + HOST + "/" + Uri.encode(path,"/"); }

    static Rendered render(ReaderDocument reader,int current,Mode mode,ReaderTypography type,boolean dark,int serial,
                           ReaderSearch.Hit hit) throws IOException {
        ReaderDocument.Page main=reader.page(current,"");
        Document primary=prepare(main);
        boolean plainText = reader instanceof ReaderDocument.Plain;
        String bg=dark?"#28221e":"#f8efdf", ink=dark?"#f0e5d3":"#332c24";
        int margin = type.margin();
        String styles="html{background:"+bg+";color:"+ink+";-webkit-text-size-adjust:none} "
                +"body{margin:0;padding:24px "+margin+"px 30px;box-sizing:border-box;overflow-wrap:anywhere;"
                +"font-size:19px;line-height:1.78} img,svg,video{max-width:100%;height:auto} "
                +".plain{white-space:pre-wrap;line-height:1.86} a{color:inherit} "
                +".plain>p{margin:0} mark[data-bookdoctor-hit]{background:#ebba63;color:#261809} "
                +"section[data-bookdoctor-chapter]+section[data-bookdoctor-chapter]{margin-top:1.5em;border-top:1px solid #97846955;padding-top:1.45em}";
        styles += typography(type);
        if (mode!=Mode.VERTICAL) styles += " html{overflow-y:hidden;direction:ltr} body{width:100vw;height:100vh;"
                +"column-width:calc(100vw - "+(margin*2)+"px);column-gap:"+(margin*2)+"px;column-fill:auto;overflow:visible!important}"
                +" img,svg,video{max-height:75vh;object-fit:contain} h1,h2,h3,h4{break-after:avoid}";
        Element mainSection=chapter(primary.body(),current,type,plainText);
        if (hit != null && hit.chapter() == current) ReaderSearch.mark(mainSection, hit);
        if (mode==Mode.VERTICAL) {
            int budget=950000-main.html().length();
            if (current>0 && budget>0) {
                ReaderDocument.Page previous=reader.page(current-1,"");
                if (previous.html().length()<=budget) {
                    Document parsed=prepare(previous);
                    mergeStyles(primary,parsed);
                    primary.body().prependChild(chapter(parsed.body(),current-1,type,plainText));
                    budget-=previous.html().length();
                }
            }
            for(int i=current+1;i<reader.chapters.size() && i<=current+16 && budget>0;i++) {
                ReaderDocument.Page next=reader.page(i,"");
                if(next.html().length()>budget) break;
                Document parsed=prepare(next);
                mergeStyles(primary,parsed);
                primary.body().appendChild(chapter(parsed.body(),i,type,plainText));
                budget-=next.html().length();
            }
        }
        if(mainSection.parent()==null) primary.body().appendChild(mainSection);
        primary.head().appendElement("style").appendText(styles);
        primary.selectFirst("html").attr("data-bookdoctor-run",Integer.toString(serial));
        primary.outputSettings().prettyPrint(false);
        return new Rendered(url(main.path()),primary.outerHtml());
    }

    private static void mergeStyles(Document primary,Document other) {
        for (Element item:other.head().select("link[rel=stylesheet], style")) {
            boolean same=false;
            for(Element existing:primary.head().children()) if(existing.outerHtml().equals(item.outerHtml())) {same=true;break;}
            if(!same) primary.head().appendChild(item.clone());
        }
    }

    private static String typography(ReaderTypography type) {
        String section = "section[data-bookdoctor-chapter]";
        StringBuilder css = new StringBuilder();
        if (type.font() == ReaderTypography.Font.IMPORTED) {
            css.append("@font-face{font-family:BookDoctorFont;src:url('")
                    .append(ReaderFontFile.PATH).append("') format('truetype');font-display:block}");
        }
        if (type.font() != ReaderTypography.Font.BOOK) {
            css.append("body,").append(section).append(" :is(main,article,div,p,h1,h2,h3,h4,h5,h6,li,blockquote,span,a,em,strong,b,i){font-family:")
                    .append(type.font().css).append("!important}");
        }
        if (type.line() != 0) {
            css.append("body,").append(section).append(" :is(.plain,p,div,li,blockquote){line-height:")
                    .append(type.line()).append("%!important}");
        }
        if (type.paragraph() >= 0) {
            css.append(section).append(" p{margin-block:0 ").append(type.paragraph() / 10.0).append("em!important}");
        }
        if (type.indent() >= 0) {
            css.append(section).append(" p{text-indent:").append(type.indent()).append("em!important}");
        }
        if (type.margin() != ReaderTypography.Metric.MARGIN.defaultValue) {
            css.append("body{padding-inline:").append(type.margin()).append("px!important}");
        }
        return css.toString();
    }

    static Element searchable(ReaderDocument.Page page, int number, ReaderTypography type, boolean plainText) {
        return chapter(prepare(page).body(), number, type, plainText);
    }

    private static Element chapter(Element body,int number,ReaderTypography type,boolean plainText) {
        Element plain = plainText ? body.selectFirst("main.plain") : null;
        if (plain != null && (type.paragraph() >= 0 || type.indent() >= 0)) {
            String[] lines = plain.wholeText().split("\\R", -1);
            plain.empty();
            for (int i = 0; i < lines.length; i++) {
                if (i == lines.length - 1 && lines[i].isEmpty()) break;
                String line = type.indent() >= 0 ? lines[i].stripLeading() : lines[i];
                Element paragraph = plain.appendElement("p");
                if (line.isEmpty()) paragraph.appendElement("br");
                else paragraph.appendChild(new TextNode(line));
            }
        }
        Element section=new Element("section").attr("data-bookdoctor-chapter",Integer.toString(number));
        for(Node node:body.childNodesCopy()) section.appendChild(node);
        body.empty().appendChild(section);
        return section;
    }

    private static Document prepare(ReaderDocument.Page page) {
        String base=url(page.path());
        Document doc=Jsoup.parse(page.html(),base);
        doc.select("script,iframe,frame,object,embed,form,base,meta[http-equiv],meta[name=viewport]").remove();
        for(Element element:doc.getAllElements()) {
            for(Attribute attribute:element.attributes().asList()) {
                String key=attribute.getKey();
                if (key.startsWith("on") || key.equals("srcdoc")) element.removeAttr(key);
                else if (key.equals("src") || key.equals("href") || key.equals("poster") || key.equals("xlink:href")) {
                    String link=attribute.getValue().trim();
                    if(link.regionMatches(true,0,"javascript:",0,11)) element.removeAttr(key);
                    else {
                        try {element.attr(key,URI.create(base).resolve(link.replace(" ","%20")).toString());}
                        catch(IllegalArgumentException invalid){element.removeAttr(key);}
                    }
                }
            }
        }
        doc.head().prepend("<meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>"
                +"<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; script-src 'none'; style-src 'self' 'unsafe-inline'; "
                +"img-src 'self' data:; font-src 'self' data:; media-src 'self'; form-action 'none'; base-uri 'none'\">");
        return doc;
    }
}
