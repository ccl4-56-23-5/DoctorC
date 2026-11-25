package local.bookdoctorc;

import android.content.Context;
import java.util.regex.Pattern;

final class TxtContents {
    static final String KEY = "txt_heading_mask";
    private static final String N = "[0-9０-９零〇○一二两兩三四五六七八九十百千万萬壹贰貳叁參肆伍陆陸柒捌玖拾佰仟]+";
    private static final String W = "[\\s　]*";
    private static final String ROMAN = "(?:[0-9０-９]+|[IVXLCDM]+)";
    enum Kind {
        CHAPTER_EN("Chapter 12", "Chapter" + W + ROMAN + "(?![\\p{Alnum}])", false),
        CHAPTER("第十二章", "第" + W + N + W + "章", false),
        EPISODE("第十二话", "第" + W + N + W + "[话話]", false),
        ROUND("第十二回", "第" + W + N + W + "回", false),
        VOLUME("第十二卷", "第" + W + N + W + "卷", true),
        VOLUME_EN("Vol. 12", "Vol(?:ume)?\\.?" + W + ROMAN + "(?![\\p{Alnum}])", true),
        SPECIAL("序章／楔子／终章／番外", "(?:序章|楔子|终章|終章|尾声|尾聲|后记|後記|番外)(?=$|[\\s　：:、.（(0-9])", false);

        final String label;
        final Pattern pattern;
        final boolean volume;
        Kind(String label, String expression, boolean volume) {
            this.label = label;
            this.pattern = Pattern.compile("^" + expression, Pattern.CASE_INSENSITIVE);
            this.volume = volume;
        }
        int bit() { return 1 << ordinal(); }
    }

    static final int ALL = (1 << Kind.values().length) - 1;
    private static final Kind[] KINDS = Kind.values();

    static int mask(Context context) {
        return context.getSharedPreferences("reader", Context.MODE_PRIVATE).getInt(KEY, ALL);
    }

    static Kind match(String line, int mask) {
        for (Kind kind : KINDS) {
            if ((mask & kind.bit()) != 0 && kind.pattern.matcher(line).find()) return kind;
        }
        return null;
    }
}
