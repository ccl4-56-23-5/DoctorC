package local.bookdoctorc;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class DesignHome {
    record Palette(int canvas, int glow, int paper, int ink, int muted, int rule, int selected, int accent) {
        Drawable wall() {
            return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{glow, canvas});
        }
    }

    static Palette books(boolean night) {
        return night
                ? new Palette(0xff121d2b, 0xff1a2839, 0xff233142, 0xfff1f3f8, 0xffb4c1d2, 0xff36485d, 0xff2e4560, 0xffc9deef)
                : new Palette(0xffedf2f7, 0xfff8fafc, 0xfffdfbf7, 0xff212f41, 0xff5a6b81, 0xffd6e0ec, 0xffdce8f4, 0xff334f6a);
    }

    static Palette podcast(boolean night) {
        return night
                ? new Palette(0xff211a22, 0xff2b202a, 0xff30232f, 0xfffbefea, 0xffcdb5c1, 0xff4e3948, 0xff4b2e41, 0xffffc5cc)
                : new Palette(0xfff4ebe7, 0xfffcf7f4, 0xfffffaf7, 0xff342730, 0xff735b67, 0xffe7d4ce, 0xfff0dcd7, 0xff783f52);
    }

    static void header(MainActivity activity, ShelfUi ui, Palette colors, LinearLayout root,
                       String title, boolean serif, String search, View.OnClickListener action) {
        LinearLayout row = ui.row();
        LinearLayout names = ui.column();
        TextView brand = ui.text("MookDoctorC", 12, colors.muted(), true);
        brand.setTypeface(Typeface.create("serif", Typeface.BOLD));
        names.addView(brand);
        TextView heading = ui.text(title, TabletLayout.wide(ui) ? 35 : 33, colors.ink(), true);
        if (serif) heading.setTypeface(Typeface.create("serif", Typeface.BOLD));
        androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
        names.addView(heading);
        row.addView(names, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        ImageView searchButton = iconAction(activity, ui, colors, R.drawable.mook_search, search, action);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ui.dp(43), ui.dp(43));
        size.rightMargin = ui.dp(8);
        row.addView(searchButton, size);
        ImageView theme = iconAction(activity, ui, colors, ui.dark ? R.drawable.mook_sun : R.drawable.mook_moon,
                ui.dark ? "日间模式" : "夜间模式", activity::toggleHomeTheme);
        row.addView(theme, new LinearLayout.LayoutParams(ui.dp(43), ui.dp(43)));
        root.addView(row);
        ui.spacer(root, 12);
    }

    private static ImageView iconAction(MainActivity activity, ShelfUi ui, Palette colors, int drawable,
                                        String description, View.OnClickListener action) {
        ImageView button = new ImageView(activity);
        button.setImageResource(drawable);
        button.setImageTintList(ColorStateList.valueOf(colors.muted()));
        button.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        button.setContentDescription(description);
        button.setBackground(surface(ui, colors.paper(), colors.rule(), 10));
        button.setOnClickListener(action);
        return button;
    }

    static Drawable surface(ShelfUi ui, int fill, int border, int radius) {
        GradientDrawable shape = ui.rounded(fill, radius);
        if (border != 0) shape.setStroke(ui.dp(1), border);
        return new RippleDrawable(ColorStateList.valueOf(ui.dark ? 0x25ffffff : 0x160a1830), shape, null);
    }

    static TextView action(ShelfUi ui, String text, int foreground, int background, int radius, View.OnClickListener listener) {
        TextView button = ui.text(text, 13, foreground, true);
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(ui.dp(38));
        button.setPadding(ui.dp(13), ui.dp(4), ui.dp(13), ui.dp(4));
        button.setBackground(surface(ui, background, 0, radius));
        button.setOnClickListener(listener);
        return button;
    }

    static FrameLayout hero(ShelfUi ui, int art, int height, float align, int shade, int radius) {
        FrameLayout image = new FrameLayout(ui.context);
        image.setBackground(ui.rounded(shade, radius));
        image.setClipToOutline(true);
        // Keep the photograph's original proportions and let the card crop from a consistent focal point.
        ImageView photo = new ImageView(ui.context) {
            @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
                super.onSizeChanged(w, h, ow, oh);
                Drawable drawable = getDrawable();
                if (w == 0 || h == 0 || drawable == null) return;
                float factor = Math.max(w / (float) drawable.getIntrinsicWidth(), h / (float) drawable.getIntrinsicHeight());
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.setScale(factor, factor);
                matrix.postTranslate((w - drawable.getIntrinsicWidth() * factor) * align,
                        (h - drawable.getIntrinsicHeight() * factor) * .55f);
                setImageMatrix(matrix);
            }
        };
        photo.setScaleType(ImageView.ScaleType.MATRIX);
        photo.setImageResource(art);
        photo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.addView(photo, new FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH));
        View overlay = new View(ui.context);
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{shade, (shade & 0xffffff) | 0x99000000, shade & 0xffffff}));
        image.addView(overlay, new FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH));
        image.setLayoutParams(new LinearLayout.LayoutParams(ShelfUi.MATCH, ui.dp(height)));
        return image;
    }

    static LinearLayout heroText(ShelfUi ui, FrameLayout hero, int padding) {
        LinearLayout copy = ui.column();
        copy.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        copy.setPadding(ui.dp(padding), 0, ui.dp(padding), 0);
        hero.addView(copy, new FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH));
        return copy;
    }

    static void truncate(TextView label) {
        label.setSingleLine();
        label.setEllipsize(TextUtils.TruncateAt.END);
    }

    static void divider(ShelfUi ui, Palette colors, ViewGroup parent) {
        View line = new View(ui.context);
        line.setBackgroundColor(colors.rule());
        parent.addView(line, new LinearLayout.LayoutParams(ShelfUi.MATCH, ui.dp(1)));
    }

    static LinearLayout percentage(ShelfUi ui, Palette colors, Integer value, String prefix) {
        LinearLayout badge = ui.row();
        TextView amount = ui.text(value == null ? "—" : value + "%", 10, colors.muted(), false);
        amount.setIncludeFontPadding(false);
        badge.setContentDescription(value == null ? prefix + "进度待更新" : prefix + " " + value + "%");
        View ring = new View(ui.context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF bounds = new RectF();

            @Override protected void onDraw(Canvas canvas) {
                float stroke = ui.dp(1.5f);
                bounds.set(stroke / 2, stroke / 2, getWidth() - stroke / 2, getHeight() - stroke / 2);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setColor(colors.rule());
                canvas.drawOval(bounds, paint);
                if (value != null && value > 0) {
                    paint.setColor(colors.accent());
                    canvas.drawArc(bounds, -90, value * 3.6f, false, paint);
                }
            }
        };
        ring.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ui.dp(13), ui.dp(13));
        size.rightMargin = ui.dp(4);
        badge.addView(ring, size);
        badge.addView(amount);
        return badge;
    }
}
