package local.bookdoctorc;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import java.util.*;

final class BoxMotion {
    private final ViewGroup tray;
    private final ShelfUi.BoxTile source;
    private final Map<Long,View> covers;
    private final List<MovingBook> moving = new ArrayList<>();
    private final MotionLayer layer = new MotionLayer();
    private final View header;
    private final Rect sourceBounds;
    private final float originalAlpha;
    private boolean hidden;
    private ScrollView scroll;

    private record MovingBook(View cover, Rect start, SurfaceCapture spine, float tilt, float alpha) {}

    BoxMotion(ViewGroup tray,View anchor,Map<Long,View> covers,View header){
        this.tray=tray;
        source=anchor instanceof ShelfUi.BoxTile tile?tile:null;
        this.covers=covers;
        this.header=header;
        originalAlpha = source == null ? 1 : source.getAlpha();
        Rect visible = new Rect();
        if (source != null && source.getLocalVisibleRect(visible)
                && source.getParent() instanceof CabinetLevels cabinet) {
            sourceBounds = MorphSurface.visible(source);
            if (tray.getBackground() instanceof Surface surface) {
                surface.bay = cabinet.captureBay(source, visible);
            }
            visible.offset(sourceBounds.left, sourceBounds.top);
            sourceBounds.set(visible);
            for (var entry : source.spines.entrySet()) {
                View spine = entry.getValue();
                View cover = covers.get(entry.getKey());
                if (cover == null) continue;
                if (spine.getVisibility() != View.VISIBLE || spine.getWidth() == 0 || spine.getHeight() == 0) continue;
                SurfaceCapture face = new SurfaceCapture(spine, spine.getWidth(), spine.getHeight(), spine::draw);
                moving.add(new MovingBook(cover, MorphSurface.visible(spine), face, spine.getRotation(), cover.getAlpha()));
            }
        } else {
            sourceBounds = MorphSurface.visible(anchor);
        }
        if (!moving.isEmpty()) {
            var parent = moving.get(0).cover.getParent();
            while (parent instanceof ViewGroup group && group != tray) {
                if (group instanceof ScrollView list) {
                    scroll = list;
                    break;
                }
                parent = group.getParent();
            }
            tray.getOverlay().add(layer);
        }
    }

    Rect sourceBounds() { return sourceBounds; }

    void paint(float progress){
        if(tray.getWidth()<=0)return;
        if(source!=null && !hidden){source.setAlpha(0);hidden=true;}
        header.setAlpha(Math.max(0,Math.min(1,(progress-.18f)*3.1f)));
        if (tray.getBackground() instanceof Surface surface) {
            surface.setProgress(progress);
        }
        float reveal = source == null ? 1 : Math.max(0, Math.min(1, (progress - .24f) * 2.7f));
        for (View cover : covers.values()) cover.setAlpha(reveal);
        float settled = Math.max(0, Math.min(1, (progress - .91f) / .09f));
        for (MovingBook book : moving) book.cover.setAlpha(book.alpha * settled);
        layer.progress = progress;
        layer.setBounds(0, 0, tray.getWidth(), tray.getHeight());
        layer.invalidateSelf();
        tray.invalidate();
    }

    void release() {
        if (hidden && source != null) source.setAlpha(originalAlpha);
        hidden = false;
        tray.getOverlay().remove(layer);
        for (MovingBook book : moving) { book.cover.setAlpha(book.alpha); book.spine.release(); }
        if (tray.getBackground() instanceof Surface surface && surface.bay != null) surface.bay.release();
    }

    private static float lerp(float a,float b,float f){return a+(b-a)*f;}

    static final class Surface extends Drawable {
        private final View host;
        private final OakFrame wood;
        private final Rect last=new Rect();
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private SurfaceCapture bay;
        Surface(View host,OakFrame wood){this.host=host;this.wood=wood;}
        void setProgress(float progress) {
            paint.setAlpha(Math.round(255 * (1 - Math.min(1, progress * 2.9f))));
        }
        @Override public void draw(Canvas canvas){
            Rect mask=host.getClipBounds();
            if(mask==null)mask=getBounds();
            if(!last.equals(mask)){last.set(mask);wood.setBounds(mask);}
            wood.draw(canvas);
            if (bay != null && paint.getAlpha() > 0) bay.draw(canvas, mask, paint.getAlpha());
        }
        @Override public void setAlpha(int alpha){wood.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter color){wood.setColorFilter(color);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }

    private final class MotionLayer extends Drawable {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect target = new Rect(), viewport = new Rect();
        private final int[] host = new int[2];
        private final Camera camera = new Camera();
        private final Matrix perspective = new Matrix();
        float progress;

        MotionLayer() { camera.setLocation(0, 0, -20f); }

        @Override public void draw(Canvas canvas) {
            if (progress >= 1 || scroll == null) return;
            tray.getLocationInWindow(host);
            viewport.set(0, 0, scroll.getWidth(), scroll.getHeight());
            tray.offsetDescendantRectToMyCoords(scroll, viewport);
            viewport.offset(scroll.getScrollX(), scroll.getScrollY());
            float p = progress;
            int save = canvas.save();
            canvas.clipRect(lerp(sourceBounds.left - host[0], viewport.left, p),
                    lerp(sourceBounds.top - host[1], viewport.top, p),
                    lerp(sourceBounds.right - host[0], viewport.right, p),
                    lerp(sourceBounds.bottom - host[1], viewport.bottom, p));
            for (MovingBook book : moving) {
                View cover = book.cover;
                if (cover.getWidth() <= 0 || cover.getHeight() <= 0) continue;
                target.set(0, 0, cover.getWidth(), cover.getHeight());
                tray.offsetDescendantRectToMyCoords(cover, target);
                float left = lerp(book.start.left - host[0], target.left, p);
                float top = lerp(book.start.top - host[1], target.top, p);
                float width = lerp(book.start.width(), target.width(), p);
                float height = lerp(book.start.height(), target.height(), p);
                int item = canvas.save();
                canvas.translate(left, top);
                canvas.rotate(book.tilt * (1 - p));
                camera.save();
                camera.rotateY(-46 * (float) Math.sin(Math.PI * p));
                camera.getMatrix(perspective);
                camera.restore();
                canvas.concat(perspective);
                canvas.scale(width / cover.getWidth(), height / cover.getHeight());
                if (p > 0) cover.draw(canvas);
                paint.setAlpha(Math.round(255 * (1 - Math.min(1, p * 2.7f))));
                if (paint.getAlpha() > 0) {
                    target.set(0, 0, cover.getWidth(), cover.getHeight());
                    book.spine.draw(canvas, target, paint.getAlpha());
                }
                canvas.restoreToCount(item);
            }
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter color) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
