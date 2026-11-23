package local.bookdoctorc;

import android.content.SharedPreferences;
import android.os.Build;
import android.transition.ChangeBounds;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

final class ReaderOptions {
    private final MainActivity activity;
    private final ShelfUi ui;
    private final ReadingUi reading;
    private final SharedPreferences settings;
    final LinearLayout view;

    ReaderOptions(MainActivity activity, ShelfUi ui, ReadingUi reading) {
        this.activity = activity;
        this.ui = ui;
        this.reading = reading;
        settings = activity.settings();
        view = ui.column();
    }

    void group(String name, Consumer<LinearLayout> populate) {
        LinearLayout card = ui.column();
        card.setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4));
        card.setBackground(PaperMenu.outline(ui, ui.dark ? 0xff3c3027 : 0xfffff6e9, 16));
        view.addView(card);
        ui.spacer(view, 10);
        LinearLayout header = ui.row();
        header.setPadding(ui.dp(11), 0, ui.dp(11), 0);
        header.setBackground(PaperMenu.press(ui, ui.dark ? 0xff544133 : 0xffeddcc1, 12));
        TextView heading = ui.text(name, 17, ui.ink, true);
        header.addView(heading, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        TextView arrow = ui.text("⌄", 18, ui.accent, true);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(arrow);
        header.setMinimumHeight(ui.dp(55));
        card.addView(header);
        LinearLayout rows = ui.column();
        rows.setPadding(ui.dp(12), ui.dp(5), ui.dp(3), ui.dp(2));
        rows.setVisibility(View.GONE);
        card.addView(rows);
        populate.accept(rows);
        if (Build.VERSION.SDK_INT >= 30) header.setStateDescription("已折叠");
        header.setOnClickListener(v -> {
            boolean open = rows.getVisibility() != View.VISIBLE;
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                TransitionManager.beginDelayedTransition(view, new ChangeBounds()
                        .setDuration(Transitions.atRate(220)).setInterpolator(Transitions.EASE));
            }
            rows.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.setRotation(open ? 180 : 0);
            if (Build.VERSION.SDK_INT >= 30) header.setStateDescription(open ? "已展开" : "已折叠");
        });
    }

    void typography() {
        group("排版", rows -> {
            TextView family = row(rows, "字体",
                    ReaderTypography.label(ReaderTypography.read(settings).font(), settings));
            ((View) family.getParent()).setOnClickListener(v -> {
                boolean hasImported = ReaderFontFile.file(activity).isFile();
                ReaderTypography.Font[] fonts = Arrays.stream(ReaderTypography.Font.values())
                        .filter(font -> font != ReaderTypography.Font.IMPORTED || hasImported)
                        .toArray(ReaderTypography.Font[]::new);
                PaperMenu menu = new PaperMenu(ui, "排版", "字体", "");
                for (ReaderTypography.Font font : fonts) {
                    String label = ReaderTypography.label(font, settings);
                    menu.choice(label, "", ReaderTypography.read(settings).font() == font, () -> {
                        settings.edit().putString(ReaderTypography.FONT_KEY, font.name()).apply();
                        reading.setTypography(ReaderTypography.read(settings));
                        family.setText(label);
                    });
                }
                menu.item(PaperMenu.Icon.ADD, "导入 TTF 字体", "", false, () -> {
                    activity.closePanelNow();
                    activity.pickReaderFont();
                });
                menu.show(v);
            });
            for (ReaderTypography.Metric metric : ReaderTypography.Metric.values()) {
                TextView value = row(rows, metric.label, metric.describe(metric.read(settings)));
                ((View) value.getParent()).setOnClickListener(v -> {
                    String[] choices = Arrays.stream(metric.choices).mapToObj(metric::describe).toArray(String[]::new);
                    int selected = Arrays.binarySearch(metric.choices, metric.read(settings));
                    choose(metric.label, choices, Math.max(-1, selected), v, index -> {
                        settings.edit().putInt(metric.key, metric.choices[index]).apply();
                        reading.setTypography(ReaderTypography.read(settings));
                        value.setText(choices[index]);
                    });
                });
            }
        });
    }

    void paging(LinearLayout rows) {
        ReaderLayout.Mode[] modes = ReaderLayout.Mode.values();
        String[] labels = Arrays.stream(modes).map(mode -> mode.label).toArray(String[]::new);
        TextView value = row(rows, "翻页方式", reading.mode().label);
        ((View) value.getParent()).setOnClickListener(v -> choose("翻页方式", labels, reading.mode().ordinal(), v, index -> {
            settings.edit().putString(ReadingUi.MODE_KEY, modes[index].name()).apply();
            reading.setMode(modes[index]);
            value.setText(labels[index]);
        }));
    }

    void action(LinearLayout rows, String name, Runnable action) {
        TextView value = row(rows, name, "›");
        ((View) value.getParent()).setOnClickListener(v -> {
            activity.closePanelNow();
            action.run();
        });
    }

    void actionFrom(LinearLayout rows, String name, java.util.function.Consumer<View> action) {
        TextView value = row(rows, name, "›");
        ((View) value.getParent()).setOnClickListener(action::accept);
    }

    private TextView row(LinearLayout rows, String name, String current) {
        LinearLayout item = ui.row();
        item.setPadding(ui.dp(11), 0, ui.dp(10), 0);
        item.setMinimumHeight(ui.dp(48));
        item.setBackground(PaperMenu.press(ui, ui.dark ? 0xff44362c : 0xfff8ead8, 9));
        item.addView(ui.text(name, 15, ui.ink, false), new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        TextView value = ui.text(current, 13, ui.accent, true);
        value.setGravity(Gravity.END);
        value.setMaxWidth(ui.dp(132));
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.END);
        item.addView(value);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP);
        size.bottomMargin = ui.dp(3);
        rows.addView(item, size);
        return value;
    }

    private void choose(String title, String[] labels, int selected, View anchor, IntConsumer set) {
        activity.choose(title, labels, selected, anchor, set);
    }
}
