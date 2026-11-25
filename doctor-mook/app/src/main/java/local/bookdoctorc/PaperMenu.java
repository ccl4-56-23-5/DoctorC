package local.bookdoctorc;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.IntConsumer;

final class PaperMenu {
    enum Icon { READ, EDIT, EXPORT, REMOVE, ADD, SORT, IMAGE, SEARCH, CHECK, NEXT, CLOSE }

    private final ShelfUi ui;
    private final Dialog dialog;
    private final LinearLayout panel;
    private final ScrollView scroll;
    final LinearLayout body;
    private boolean picked;
    private Runnable abandoned;
    private java.util.function.Consumer<Runnable> closeMorph;

    PaperMenu(ShelfUi ui, String category, String title, String detail) {
        this(ui, category, title, detail, null);
    }

    PaperMenu(ShelfUi ui, String category, String title, String detail, View cover) {
        this.ui = ui;
        dialog = new Dialog(ui.context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setTitle(title);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> {
            if (ui.context instanceof MainActivity activity) activity.releasePaperDialog(dialog);
            if (!picked && abandoned != null) abandoned.run();
        });
        boolean compact = category.isEmpty();
        panel = compact ? new LinearLayout(ui.context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int height = Math.min(ui.dp(470), Math.round(MeasureSpec.getSize(heightSpec) * .67f));
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            }
        } : ui.column();
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(16));
        GradientDrawable paper = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                ui.dark ? new int[]{0xff3d3028, 0xff2d241f} : new int[]{0xfffff8ed, 0xfff6e7d3});
        paper.setCornerRadius(ui.dp(24));
        paper.setStroke(ui.dp(1), ui.dark ? 0xff655043 : 0xffe7cfb1);
        panel.setBackground(paper);
        panel.setFocusableInTouchMode(true);

        View close = glyph(ui, Icon.CLOSE, ui.accent, 17);
        close.setContentDescription("关闭菜单");
        close.setBackground(press(ui, ui.dark ? 0xff4b3c31 : 0xfff2e4d1, 16));
        close.setOnClickListener(v -> {
            if (closeMorph != null) closeMorph.accept(null);
            else dialog.dismiss();
        });
        if (!compact) {
            LinearLayout top = ui.row();
            TextView label = ui.text(category, 12, ui.accent, true);
            label.setLetterSpacing(.12f);
            top.addView(label, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
            top.addView(close, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
            panel.addView(top);
        }

        LinearLayout header = ui.row();
        if (cover != null) {
            cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            cover.setElevation(ui.dp(2));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(ui.coverHeight(48)));
            size.rightMargin = ui.dp(13);
            header.addView(cover, size);
        }
        LinearLayout words = ui.column();
        TextView heading = ui.text(title, compact ? 21 : 23, ui.ink, true);
        if (!compact) heading.setTypeface(Typeface.create("serif", Typeface.BOLD));
        heading.setMaxLines(2);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        words.addView(heading);
        if (!detail.isEmpty()) {
            TextView caption = ui.text(detail, 13, ui.secondary, false);
            caption.setPadding(0, ui.dp(3), 0, 0);
            caption.setMaxLines(3);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(caption);
        }
        header.addView(words, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        if (compact) header.addView(close, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        panel.addView(header);
        ui.spacer(panel, 12);
        panel.addView(ui.divider());
        ui.spacer(panel, 9);

        scroll = new ScrollView(ui.context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        body = ui.column();
        scroll.addView(body, new ScrollView.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP));
        panel.addView(scroll, new LinearLayout.LayoutParams(ShelfUi.MATCH, compact ? 0 : ShelfUi.WRAP, 1));
        panel.requestFocus();
    }

    void item(Icon icon, String label, String detail, boolean primary, Runnable action) {
        add(row(ui, icon, label, detail, primary, false, false, () -> select(action)));
    }

    void choice(String label, String detail, boolean selected, Runnable action) {
        View row = row(ui, null, label, detail, selected, false, selected, () -> select(action));
        row.setSelected(selected);
        if (selected && Build.VERSION.SDK_INT >= 30) row.setStateDescription("当前选项");
        add(row);
    }

    void excerptList(String[] excerpts, IntConsumer action) {
        ListView list = new ListView(ui.context);
        list.setDivider(new ColorDrawable(ui.rule));
        list.setDividerHeight(ui.dp(1));
        list.setSelector(press(ui, Color.TRANSPARENT, 9));
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return excerpts.length; }
            @Override public String getItem(int position) { return excerpts[position]; }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView label = (TextView) recycled;
                if (label == null) {
                    label = ui.text("", 15, ui.ink, false);
                    label.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
                    label.setMinHeight(ui.dp(48));
                    label.setGravity(Gravity.CENTER_VERTICAL);
                }
                label.setText(getItem(position));
                return label;
            }
        });
        list.setOnItemClickListener((parent, row, position, id) -> select(() -> action.accept(position)));
        panel.removeView(scroll);
        int height = ui.context.getResources().getConfiguration().screenHeightDp / 2;
        int listHeight = Math.min(excerpts.length * 64, Math.min(420, height));
        panel.addView(list, new LinearLayout.LayoutParams(ShelfUi.MATCH, ui.dp(listHeight), 1));
    }

    void danger(String label, String detail, Runnable action) {
        ui.spacer(body, 5);
        body.addView(ui.divider());
        ui.spacer(body, 6);
        add(row(ui, Icon.REMOVE, label, detail, false, true, false, () -> select(action)));
    }

    private void add(View item) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP);
        params.bottomMargin = ui.dp(4);
        body.addView(item, params);
    }

    void onAbandon(Runnable action) {
        abandoned = action;
    }

    void select(Runnable action) {
        if (picked) return;
        picked = true;
        if (closeMorph != null) closeMorph.accept(action);
        else {
            dialog.dismiss();
            action.run();
        }
    }

    void show() { show(null); }

    void show(View anchor) { show(anchor, false); }

    void showNear(View anchor) { show(anchor, true); }

    private void show(View anchor, boolean nearAnchor) {
        if (anchor != null && anchor.isAttachedToWindow() && anchor.getWidth() > 0
                && ui.context instanceof MainActivity activity) {
            Runnable dismissed = () -> {
                if (!picked && abandoned != null) abandoned.run();
            };
            closeMorph = nearAnchor ? activity.showSettingsMenu(panel, anchor, dismissed)
                    : activity.showPaperMenu(panel, anchor, dismissed);
            return;
        }
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window == null) return;
        int width = ui.context.getResources().getConfiguration().screenWidthDp;
        int height = ui.context.getResources().getConfiguration().screenHeightDp;
        boolean bottom = width < 600 && height > width;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(ui.dark ? .54f : .38f);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        WindowManager.LayoutParams attrs = window.getAttributes();
        attrs.gravity = (bottom ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL) | Gravity.CENTER_HORIZONTAL;
        attrs.y = bottom ? ui.dp(14) : 0;
        attrs.windowAnimations = R.style.BookMenuMotion;
        window.setAttributes(attrs);
        dialog.show();
        if (ui.context instanceof MainActivity activity) activity.trackPaperDialog(dialog);
        window.setLayout(ui.dp(Math.min(width - 24, 452)), ShelfUi.WRAP);
    }

    static View option(ShelfUi ui, Icon icon, String label, Runnable action) {
        return row(ui, icon, label, "", true, false, false, action);
    }

    static View searchButton(ShelfUi ui, String label, View.OnClickListener action) {
        View button = glyph(ui, Icon.SEARCH, ui.ink, 24);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        button.setContentDescription(label);
        button.setFocusable(true);
        button.setBackground(press(ui, Color.TRANSPARENT, 24));
        button.setOnClickListener(action);
        return button;
    }

    private static View row(ShelfUi ui, Icon icon, String label, String detail, boolean primary,
                            boolean danger, boolean checked, Runnable action) {
        LinearLayout line = ui.row();
        line.setMinimumHeight(ui.dp(detail.isEmpty() ? 48 : 58));
        line.setPadding(ui.dp(10), ui.dp(7), ui.dp(11), ui.dp(7));
        int color = danger ? (ui.dark ? 0xffffb6a0 : 0xff983e2d) : ui.accent;
        int fill = primary ? (ui.dark ? 0xff554332 : 0xfff0ddc0) : Color.TRANSPARENT;
        line.setBackground(press(ui, fill, 12));
        if (icon != null) {
            View tile = glyph(ui, icon, color, 19);
            tile.setBackground(ui.rounded(ui.dark ? 0xff49392f : 0xfff3e6d2, 10));
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34));
            size.rightMargin = ui.dp(10);
            line.addView(tile, size);
        }
        LinearLayout words = ui.column();
        TextView name = ui.text(label, 16, danger ? color : ui.ink, true);
        words.addView(name);
        if (!detail.isEmpty()) words.addView(ui.text(detail, 12, ui.secondary, false));
        line.addView(words, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        View end = glyph(ui, checked ? Icon.CHECK : Icon.NEXT, color, 16);
        line.addView(end, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(25)));
        line.setOnClickListener(v -> action.run());
        if (Build.VERSION.SDK_INT >= 28) line.setScreenReaderFocusable(true);
        return line;
    }

    static GradientDrawable outline(ShelfUi ui, int color, int radius) {
        GradientDrawable background = ui.rounded(color, radius);
        background.setStroke(ui.dp(1), ui.rule);
        return background;
    }

    static RippleDrawable press(ShelfUi ui, int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(ui.dark ? 0x40dbba84 : 0x28805137),
                ui.rounded(color, radius), ui.rounded(Color.WHITE, radius));
    }

    static View glyph(ShelfUi ui, Icon icon, int color, int size) {
        View view = new View(ui.context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                float edge = ui.dp(size);
                canvas.save();
                canvas.translate((getWidth() - edge) / 2, (getHeight() - edge) / 2);
                canvas.scale(edge / 24, edge / 24);
                paint.setColor(color);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.6f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                switch (icon) {
                    case READ -> {
                        canvas.drawRoundRect(3, 4, 21, 20, 2, 2, paint);
                        canvas.drawLine(12, 5, 12, 19, paint);
                        path(canvas, paint, 6, 9, 9, 9);
                        path(canvas, paint, 15, 9, 18, 9);
                    }
                    case EDIT -> path(canvas, paint, 4, 20, 6, 14, 16, 4, 20, 8, 10, 18, 4, 20);
                    case EXPORT -> {
                        path(canvas, paint, 5, 14, 5, 20, 19, 20, 19, 14);
                        path(canvas, paint, 8, 8, 12, 4, 16, 8);
                        path(canvas, paint, 12, 4, 12, 15);
                    }
                    case REMOVE -> path(canvas, paint, 5, 12, 19, 12);
                    case ADD -> {
                        path(canvas, paint, 5, 12, 19, 12);
                        path(canvas, paint, 12, 5, 12, 19);
                    }
                    case SORT -> {
                        path(canvas, paint, 4, 6, 20, 6);
                        path(canvas, paint, 4, 12, 15, 12);
                        path(canvas, paint, 4, 18, 10, 18);
                    }
                    case IMAGE -> {
                        canvas.drawRoundRect(3, 4, 21, 20, 2, 2, paint);
                        canvas.drawCircle(9, 9, 1.4f, paint);
                        path(canvas, paint, 4, 17, 9, 13, 12, 15, 16, 11, 20, 15);
                    }
                    case SEARCH -> {
                        canvas.drawCircle(10, 10, 5.5f, paint);
                        path(canvas, paint, 14, 14, 20, 20);
                    }
                    case CHECK -> path(canvas, paint, 5, 12, 10, 17, 20, 7);
                    case NEXT -> path(canvas, paint, 9, 6, 15, 12, 9, 18);
                    case CLOSE -> {
                        path(canvas, paint, 7, 7, 17, 17);
                        path(canvas, paint, 17, 7, 7, 17);
                    }
                }
                canvas.restore();
            }
        };
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (icon == Icon.CLOSE) view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        return view;
    }

    private static void path(Canvas canvas, Paint paint, float... points) {
        for (int i = 2; i < points.length; i += 2) {
            canvas.drawLine(points[i - 2], points[i - 1], points[i], points[i + 1], paint);
        }
    }
}
