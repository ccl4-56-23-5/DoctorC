package local.bookdoctorc;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

/** Cool blue metal shared by the closed holder and the opened enclosure. */
final class CdCrateSurface extends Drawable {
    private final ShelfUi ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final RectF outer = new RectF(), face = new RectF();
    private final Shader brushing;
    private Shader nickel, facing, bevel;
    private float radius;
    private int alpha = 255;

    CdCrateSurface(ShelfUi ui) {
        this.ui = ui;
        brushing = new LinearGradient(0, 0, 0, ui.dp(4),
                new int[]{0x0fbdd3ec, 0x007b8fa7, 0x100d1724, 0x0fbdd3ec},
                new float[]{0, .24f, .66f, 1}, Shader.TileMode.REPEAT);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        outer.set(bounds);
        face.set(bounds);
        if (bounds.isEmpty()) return;
        float inset = Math.min(ui.dp(5), Math.min(bounds.width(), bounds.height()) * .16f);
        face.inset(inset, inset);
        radius = Math.min(ui.dp(13), Math.min(face.width(), face.height()) / 2f);
        nickel = metal(ui, outer.top, outer.bottom);
        facing = new LinearGradient(face.left, face.top, face.right, face.bottom,
                ui.dark ? new int[]{0xff3e4d68, 0xff37455e, 0xff303d55}
                        : new int[]{0xffdbe5f5, 0xffcedbed, 0xffbbcbe3},
                new float[]{0, .40f, 1}, Shader.TileMode.CLAMP);
        bevel = new LinearGradient(0, face.top, 0, face.bottom,
                ui.dark ? new int[]{0x8fbacdea, 0x345e7189, 0x990c1421}
                        : new int[]{0xbdf6f9ff, 0x357c94bb, 0x8b6c84ac},
                new float[]{0, .26f, 1}, Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas canvas) {
        if (outer.isEmpty()) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setAlpha(alpha);
        paint.setShader(nickel);
        canvas.drawRect(outer, paint);
        paint.setShader(brushing);
        canvas.drawRect(outer, paint);
        paint.setShader(facing);
        canvas.drawRoundRect(face, radius, radius, paint);
        paint.setShader(bevel);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(ui.dp(1));
        canvas.drawRoundRect(face, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    static Shader metal(ShelfUi ui, float top, float bottom) {
        return new LinearGradient(0, top, 0, bottom,
                ui.dark ? new int[]{0xff8498b9, 0xff5b6d8a, 0xff3c4c68, 0xff2b374f}
                        : new int[]{0xfff2f6fd, 0xffd7e2f1, 0xffb0c0d9, 0xff849aba},
                new float[]{0, .16f, .72f, 1}, Shader.TileMode.CLAMP);
    }

    static int ink(ShelfUi ui) { return ui.dark ? 0xffedf3fb : 0xff233453; }

    static GradientDrawable lining(ShelfUi ui) {
        return ui.dark ? material(ui, 10, 0xff60718e, 0xff29374e, 0xff34435d, 0xff2c3a52)
                : material(ui, 10, 0xff9eb0cc, 0xffbbcbe3, 0xffd8e3f4, 0xffcbd9ed);
    }

    static GradientDrawable recordWell(ShelfUi ui) {
        return ui.dark ? material(ui, 12, 0xff677992, 0xff243146, 0xff2b3b53, 0xff3b4d66)
                : material(ui, 12, 0xff94a9ca, 0xffaebfdc, 0xffc9d8ed, 0xffdee8f7);
    }

    static RippleDrawable selection(ShelfUi ui) {
        return new RippleDrawable(ColorStateList.valueOf(0x468da8ca), null, ui.rounded(Color.WHITE, 10));
    }

    private static GradientDrawable material(ShelfUi ui, int radius, int stroke, int... shades) {
        GradientDrawable result = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, shades);
        result.setCornerRadius(ui.dp(radius));
        result.setStroke(ui.dp(1), stroke);
        return result;
    }

    @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return alpha == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT; }
}
