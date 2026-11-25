package local.bookdoctorc;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Compact playback controls that leave the player in view. */
final class AudioToolSheet extends LinearLayout {
    private final ShelfUi ui;
    private final int ink, muted, accent, field, active, edge;

    private AudioToolSheet(ShelfUi ui, String title, String subtitle, Runnable dismiss) {
        super(ui.context);
        this.ui = ui;
        ink = ui.dark ? 0xfff1f4fd : 0xff182840;
        muted = ui.dark ? 0xffb4c4dd : 0xff506581;
        accent = ui.dark ? 0xffbbd4ff : 0xff274e8e;
        field = ui.dark ? 0xff233550 : 0xffe9eef7;
        active = ui.dark ? 0xff28416a : 0xffe1ebfc;
        edge = ui.dark ? 0xff3b526f : 0xffdbe4f0;
        setOrientation(VERTICAL);
        setFocusableInTouchMode(true);
        setPadding(ui.dp(16), ui.dp(9), ui.dp(16), ui.dp(14));
        GradientDrawable card = ui.rounded(ui.dark ? 0xff17263e : 0xfff7f9fd, 26);
        card.setStroke(ui.dp(1), edge);
        setBackground(card);

        LinearLayout header = ui.row();
        LinearLayout names = ui.column();
        names.setPadding(ui.dp(4), ui.dp(4), ui.dp(8), ui.dp(4));
        TextView heading = ui.text(title, 21, ink, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        names.addView(heading);
        if (!subtitle.isBlank()) {
            TextView detail = ui.text(subtitle, 13, muted, false);
            detail.setMaxLines(2);
            detail.setEllipsize(TextUtils.TruncateAt.END);
            names.addView(detail);
        }
        header.addView(names, new LayoutParams(0, ShelfUi.WRAP, 1));
        FrameLayout close = new FrameLayout(ui.context);
        close.setBackground(press(Color.TRANSPARENT, 24));
        close.setContentDescription("关闭" + title);
        close.setFocusable(true);
        close.setOnClickListener(v -> dismiss.run());
        View glyph = PaperMenu.glyph(ui, PaperMenu.Icon.CLOSE, accent, 17);
        glyph.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        glyph.setClickable(false);
        close.addView(glyph, new FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.CENTER));
        header.addView(close, new LayoutParams(ui.dp(48), ui.dp(48)));
        addView(header);
        View rule = new View(ui.context);
        rule.setBackgroundColor(edge);
        LayoutParams line = new LayoutParams(ShelfUi.MATCH, ui.dp(1));
        line.topMargin = ui.dp(6);
        line.bottomMargin = ui.dp(8);
        addView(rule, line);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(heightSpec);
        int maximum = Math.min(ui.dp(470), Math.round(available * .67f));
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST));
    }

    static void chapters(ShelfUi ui, ClmPlayback playback, Runnable dismiss,
                         Function<LinearLayout, Consumer<Runnable>> present) {
        List<String> titles = playback.getTrackNames();
        if (titles.isEmpty()) return;
        String album = playback.getAlbumId(), name = playback.getAlbumTitle();
        int selected = playback.getSelected();
        String count = titles.size() + " 章";
        AudioToolSheet sheet = new AudioToolSheet(ui, "章节目录",
                TextUtils.isEmpty(name) ? count : name + "  ·  " + count, dismiss);
        ListView list = new ListView(ui.context);
        list.setDivider(null);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(sheet.press(Color.TRANSPARENT, 13));
        list.setAdapter(sheet.new Chapters(titles, selected));
        sheet.addView(list, new LayoutParams(ShelfUi.MATCH, 0, 1));
        Consumer<Runnable> close = present.apply(sheet);
        list.setOnItemClickListener((parent, row, index, id) -> close.accept(() -> {
            if (Objects.equals(album, playback.getAlbumId())) playback.selectTrack(index);
        }));
        if (selected >= 0 && selected < titles.size()) list.setSelection(Math.max(0, selected - 1));
    }

    private final class Chapters extends BaseAdapter {
        private final List<String> titles;
        private final int selected;

        Chapters(List<String> titles, int selected) {
            this.titles = titles;
            this.selected = selected;
        }

        @Override public int getCount() { return titles.size(); }
        @Override public String getItem(int index) { return titles.get(index); }
        @Override public long getItemId(int index) { return index; }
        @Override public View getView(int index, View recycled, ViewGroup parent) {
            ChapterRow row = recycled instanceof ChapterRow existing ? existing : new ChapterRow();
            row.bind(index, getItem(index), index == selected);
            return row;
        }
    }

    private final class ChapterRow extends LinearLayout {
        private final TextView number, title, badge;

        ChapterRow() {
            super(ui.context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(ui.dp(11), ui.dp(8), ui.dp(11), ui.dp(8));
            setMinimumHeight(ui.dp(53));
            number = ui.text("", 13, muted, false);
            number.setTypeface(Typeface.MONOSPACE);
            number.setSingleLine();
            addView(number, new LayoutParams(ui.dp(43), ShelfUi.WRAP));
            title = ui.text("", 15, ink, false);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, new LayoutParams(0, ShelfUi.WRAP, 1));
            badge = ui.text("当前", 12, accent, true);
            badge.setPadding(ui.dp(7), 0, 0, 0);
            addView(badge);
        }

        void bind(int index, String value, boolean current) {
            number.setText(String.format(Locale.ROOT, "%02d", index + 1));
            number.setTextColor(current ? accent : muted);
            title.setText(value);
            title.setTypeface(Typeface.create(current ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            badge.setVisibility(current ? VISIBLE : GONE);
            setBackground(current ? ui.rounded(active, 13) : null);
            setSelected(current);
        }
    }

    static void settings(ShelfUi ui, ClmPlayback playback, Runnable dismiss, Runnable hideKeyboard,
                         Function<LinearLayout, Consumer<Runnable>> present) {
        AudioToolSheet sheet = new AudioToolSheet(ui, "播放设置", "", dismiss);
        ScrollView scroll = new ScrollView(ui.context);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = ui.column();
        scroll.addView(body, new ScrollView.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP));
        sheet.addView(scroll, new LayoutParams(ShelfUi.MATCH, 0, 1));

        LinearLayout rateTitle = ui.row();
        TextView speedLabel = ui.text("播放倍速", 15, sheet.ink, true);
        rateTitle.addView(speedLabel, new LayoutParams(0, ShelfUi.WRAP, 1));
        EditText speed = sheet.number("播放倍速", Float.toString(playback.getSpeed()), true);
        rateTitle.addView(sheet.unitField(speed, "×"), new LayoutParams(ui.dp(106), ShelfUi.WRAP));
        body.addView(rateTitle);
        SeekBar rate = new SeekBar(ui.context);
        rate.setContentDescription("调节播放倍速");
        rate.setMax(30);
        rate.setKeyProgressIncrement(1);
        rate.setProgress(Math.round((playback.getSpeed() - .5f) * 20));
        rate.setThumbTintList(ColorStateList.valueOf(sheet.accent));
        rate.setProgressTintList(ColorStateList.valueOf(sheet.accent));
        rate.setProgressBackgroundTintList(ColorStateList.valueOf(sheet.edge));
        body.addView(rate, new LayoutParams(ShelfUi.MATCH, ui.dp(37)));
        LinearLayout range = ui.row();
        range.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        range.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        range.addView(ui.text("0.5×", 12, sheet.muted, false), new LayoutParams(0, ShelfUi.WRAP, 1));
        range.addView(ui.text("2×", 12, sheet.muted, false));
        body.addView(range);
        rate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar slider, int progress, boolean fromUser) {
                if (fromUser) speed.setText(Float.toString((10 + progress) / 20f));
            }
            @Override public void onStartTrackingTouch(SeekBar slider) {}
            @Override public void onStopTrackingTouch(SeekBar slider) {}
        });
        speed.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                float value = valueOf(s, true, .5f, 2);
                if (!Float.isNaN(value)) rate.setProgress(Math.round((value - .5f) * 20));
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        ui.spacer(body, 14);
        LinearLayout skips = ui.row();
        EditText intro = sheet.skip(skips, "跳过片头", playback.getIntro());
        EditText outro = sheet.skip(skips, "跳过片尾", playback.getOutro());
        body.addView(skips);
        TextView save = ui.action("保存设置", () -> {});
        save.setTextColor(ui.dark ? 0xff142748 : Color.WHITE);
        save.setBackground(sheet.press(ui.dark ? 0xffbdd2fc : 0xff305794, 15));
        LayoutParams footer = new LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP);
        footer.topMargin = ui.dp(12);
        sheet.addView(save, footer);

        Consumer<Runnable> close = present.apply(sheet);
        save.setOnClickListener(button -> {
            float speedValue = validated(speed, true, .5f, 2, "请输入 0.5 到 2 之间的倍速");
            float start = validated(intro, false, 0, 86400, "请输入 0 到 86400 之间的秒数");
            float end = validated(outro, false, 0, 86400, "请输入 0 到 86400 之间的秒数");
            if (Float.isNaN(speedValue) || Float.isNaN(start) || Float.isNaN(end)) return;
            hideKeyboard.run();
            close.accept(() -> playback.updateSettings(speedValue, (int) start, (int) end));
        });
    }

    private EditText skip(LinearLayout row, String label, int seconds) {
        LinearLayout column = ui.column();
        LayoutParams size = new LayoutParams(0, ShelfUi.WRAP, 1);
        if (row.getChildCount() > 0) size.setMarginStart(ui.dp(12));
        row.addView(column, size);
        column.addView(ui.text(label, 14, ink, true));
        ui.spacer(column, 4);
        EditText input = number(label + "秒数", Integer.toString(seconds), false);
        column.addView(unitField(input, "秒"));
        return input;
    }

    private EditText number(String label, String value, boolean decimal) {
        EditText input = new EditText(ui.context);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        input.setTextSize(16);
        input.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        input.setTextColor(ink);
        input.setContentDescription(label);
        input.setBackground(null);
        input.setPadding(0, 0, 0, 0);
        input.setSelectAllOnFocus(true);
        input.setText(value);
        return input;
    }

    private LinearLayout unitField(EditText input, String unit) {
        LinearLayout group = ui.row();
        group.setBackground(ui.rounded(field, 13));
        group.setPadding(ui.dp(11), 0, ui.dp(10), 0);
        group.addView(input, new LayoutParams(0, ui.dp(46), 1));
        TextView suffix = ui.text(unit, 13, muted, false);
        suffix.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        group.addView(suffix);
        return group;
    }

    private RippleDrawable press(int fill, int radius) {
        int ripple = ui.dark ? 0x42d4e1fb : 0x315277af;
        return new RippleDrawable(ColorStateList.valueOf(ripple),
                ui.rounded(fill, radius), ui.rounded(Color.WHITE, radius));
    }

    private static float validated(EditText edit, boolean decimal, float min, float max, String error) {
        float value = valueOf(edit.getText(), decimal, min, max);
        if (Float.isNaN(value)) edit.setError(error);
        return value;
    }

    private static float valueOf(CharSequence text, boolean decimal, float min, float max) {
        try {
            String raw = text.toString().trim();
            float value = decimal ? Float.parseFloat(raw.replace(',', '.')) : Integer.parseInt(raw);
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) {}
        return Float.NaN;
    }
}
