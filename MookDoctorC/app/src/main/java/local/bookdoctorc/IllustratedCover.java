package local.bookdoctorc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

final class IllustratedCover extends View {
    private final Bitmap illustrations;
    private final Rect source = new Rect(), destination = new Rect();
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);

    IllustratedCover(Context context, Bitmap illustrations, int variant) {
        super(context);
        this.illustrations = illustrations;
        int width = illustrations.getWidth() / 3;
        int height = illustrations.getHeight() / 2;
        int x = variant % 3 * width;
        int y = variant / 3 * height;
        source.set(x, y, x + width, y + height);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        destination.set(0, 0, width, height);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawBitmap(illustrations, source, destination, image);
    }
}
