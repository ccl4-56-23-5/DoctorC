package local.bookdoctorc;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

final class OakFrame extends Drawable {
    private final float radius;
    private final int fill, edge;
    private int opacity = 255;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    OakFrame(float radius, int fill, int edge) {
        this.radius = radius;
        this.fill = fill;
        this.edge = edge;
    }

    @Override public void draw(Canvas canvas) {
        var bounds = getBounds();
        paint.setColor(fill);
        paint.setAlpha(opacity);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, radius, radius, paint);
        paint.setColor(edge);
        paint.setAlpha(opacity);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        canvas.drawRoundRect(bounds.left + 1, bounds.top + 1, bounds.right - 1, bounds.bottom - 1, radius, radius, paint);
    }

    @Override public void getOutline(Outline outline) {
        outline.setRoundRect(getBounds(), radius);
    }

    @Override public void setAlpha(int alpha) { opacity = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
