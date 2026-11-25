package local.bookdoctorc;

import android.graphics.*;
import android.os.Build;
import android.view.View;
import java.util.function.Consumer;

/** Record drawing commands on modern devices; never rasterize the whole cabinet on a tap. */
final class SurfaceCapture {
    private final RenderNode node;
    private final Bitmap bitmap;
    private final int width, height;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    SurfaceCapture(View host, int width, int height, Consumer<Canvas> draw) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        if (Build.VERSION.SDK_INT >= 29 && host.isHardwareAccelerated()) {
            node = new RenderNode("Cabinet surface");
            node.setPosition(0, 0, this.width, this.height);
            draw.accept(node.beginRecording());
            node.endRecording();
            bitmap = null;
        } else {
            node = null;
            float scale = Math.min(1, 768f / Math.max(this.width, this.height));
            bitmap = Bitmap.createBitmap(Math.max(1, Math.round(this.width * scale)),
                    Math.max(1, Math.round(this.height * scale)), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            draw.accept(canvas);
            bitmap.prepareToDraw();
        }
    }

    void draw(Canvas canvas, Rect target, int alpha) {
        if (alpha <= 0) return;
        int saved = canvas.save();
        canvas.translate(target.left, target.top);
        canvas.scale(target.width() / (float) width, target.height() / (float) height);
        if (node != null && Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated()) {
            node.setAlpha(alpha / 255f);
            canvas.drawRenderNode(node);
        } else if (bitmap != null) {
            paint.setAlpha(alpha);
            canvas.drawBitmap(bitmap, null, new Rect(0, 0, width, height), paint);
        }
        canvas.restoreToCount(saved);
    }

    void release() {
        if (node != null && Build.VERSION.SDK_INT >= 29) node.discardDisplayList();
    }
}
