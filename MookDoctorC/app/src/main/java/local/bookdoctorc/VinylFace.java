package local.bookdoctorc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.View;

final class VinylFace extends View {
    private final VinylArtwork record = new VinylArtwork();
    private Bitmap cover;

    VinylFace(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setCover(Bitmap cover) {
        this.cover = cover;
        if (record.setCover(cover)) invalidate();
    }

    Bitmap cover() { return cover; }

    Rect visibleRecord() {
        Point origin = new Point();
        Rect visible = AudioPanelFlight.windowBounds(this, origin);
        if (visible.isEmpty()) return visible;
        int x = origin.x + getWidth() / 2, y = origin.y + getHeight() / 2;
        int radius = Math.round(Math.min(getWidth(), getHeight()) * .475f);
        Rect record = new Rect(x - radius, y - radius, x + radius, y + radius);
        return record.intersect(visible) ? record : new Rect();
    }

    void drawRecord(Canvas canvas, android.graphics.RectF bounds) {
        record.draw(canvas, bounds.centerX(), bounds.centerY(), Math.min(bounds.width(), bounds.height()) / 2, 0);
    }

    @Override protected void onDraw(Canvas canvas) {
        record.draw(canvas, getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) * .475f, 0);
    }
}
