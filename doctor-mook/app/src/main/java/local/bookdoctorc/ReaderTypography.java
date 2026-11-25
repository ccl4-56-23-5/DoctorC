package local.bookdoctorc;

import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

record ReaderTypography(Font font, int size, int line, int paragraph, int margin, int indent) {
    static final String FONT_KEY = "reader_font_family";

    enum Font {
        BOOK("跟随书籍", ""), SANS("无衬线", "sans-serif"), SERIF("衬线", "serif"), MONOSPACE("等宽", "monospace"), IMPORTED("导入字体", "BookDoctorFont, sans-serif");

        final String label, css;
        Font(String label, String css) { this.label = label; this.css = css; }

        static Font read(String key) {
            for (Font font : values()) if (font.name().equals(key)) return font;
            return BOOK;
        }
    }

    static String label(Font font, SharedPreferences settings) {
        return font == Font.IMPORTED ? settings.getString(ReaderFontFile.NAME_KEY, font.label) : font.label;
    }

    enum Metric {
        SIZE("字号", "font", 19, 16, 19, 22, 25, 28, 32),
        LINE("行距", "reader_line", 0, 0, 140, 160, 180, 200, 230),
        PARAGRAPH("段距", "reader_paragraph", -1, -1, 0, 4, 8, 12),
        MARGIN("页边距", "reader_margin", 23, 12, 23, 32, 44),
        INDENT("首行缩进", "reader_indent", -1, -1, 0, 1, 2);

        final String label, key;
        final int defaultValue;
        final int[] choices;

        Metric(String label, String key, int defaultValue, int... choices) {
            this.label = label;
            this.key = key;
            this.defaultValue = defaultValue;
            this.choices = choices;
        }

        int read(SharedPreferences settings) { return accepted(settings.getInt(key, defaultValue)); }

        int accepted(int value) {
            if (this == SIZE) return Math.min(32, Math.max(16, value));
            for (int choice : choices) if (choice == value) return value;
            return defaultValue;
        }

        String describe(int value) {
            return switch (this) {
                case SIZE -> value + (value == defaultValue ? " · 默认" : "");
                case LINE -> value == 0 ? "跟随书籍" : value + "%";
                case PARAGRAPH -> value < 0 ? "跟随书籍" : value == 0 ? "无间距" : (value / 10.0) + " 字高";
                case MARGIN -> switch (value) {
                    case 12 -> "窄";
                    case 32 -> "宽";
                    case 44 -> "加宽";
                    default -> "标准";
                };
                case INDENT -> value < 0 ? "跟随书籍" : value == 0 ? "无缩进" : value + " 字";
            };
        }
    }

    static ReaderTypography read(SharedPreferences settings) {
        return new ReaderTypography(Font.read(settings.getString(FONT_KEY, "")),
                Metric.SIZE.read(settings), Metric.LINE.read(settings), Metric.PARAGRAPH.read(settings),
                Metric.MARGIN.read(settings), Metric.INDENT.read(settings));
    }

    static void saveTo(JSONObject state, SharedPreferences settings) throws JSONException {
        state.put(FONT_KEY, Font.read(settings.getString(FONT_KEY, "")).name());
        for (Metric metric : Metric.values()) state.put(metric.key, metric.read(settings));
    }

    static void restoreFrom(JSONObject state, SharedPreferences.Editor editor) {
        if (state.has(FONT_KEY)) editor.putString(FONT_KEY, Font.read(state.optString(FONT_KEY)).name());
        for (Metric metric : Metric.values()) {
            if (state.has(metric.key)) editor.putInt(metric.key, metric.accepted(state.optInt(metric.key, metric.defaultValue)));
        }
    }
}
