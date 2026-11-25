package local.bookdoctorc;

import android.graphics.*;
import android.view.*;
import android.widget.FrameLayout;
import java.util.*;
import java.util.function.Supplier;

/** Like the wooden cabinet: one enclosure and records moving into their own slots. */
final class CdCrateMotion extends FrameLayout {
    record Entry(Object key, Rect bounds, Bitmap cover) {}
    private record MovingRecord(Entry entry, VinylFace face, Rect end) {}
    interface Source {
        List<Entry> records();
    }
    final MotionProgress motion;
    private final View panel, source, dim;
    private final Bitmap backdrop;
    private final Supplier<Rect> sourceBounds;
    private final Supplier<List<VinylFace>> targets;
    private final List<Entry> entries;
    private final CdCrateSurface material;
    private final List<MovingRecord> movingRecords = new ArrayList<>();
    private final Rect backdropBounds = new Rect();
    private final Rect origin, target = new Rect();
    private final RectF moving = new RectF(), recordBounds = new RectF(), label = new RectF();
    private final int[] window = new int[2];
    private final Path clip = new Path(), recordClip = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect crop = new Rect();
    private final float sourceAlpha;
    private final ShelfUi ui;
    private boolean tracking;
    private OnLayoutChangeListener pending;

    CdCrateMotion(ShelfUi ui, View panel, LayoutParams placement, View dim, View anchor,
                  Supplier<Rect> sourceBounds, Supplier<List<VinylFace>> targets, Bitmap backdrop) {
        super(ui.context);
        this.ui = ui;
        this.panel = panel;
        this.source = anchor;
        this.dim = dim;
        this.backdrop = backdrop;
        this.sourceBounds = sourceBounds;
        this.targets = targets;
        this.origin = new Rect(sourceBounds.get());
        entries = anchor instanceof Source front ? front.records() : List.of();
        material = new CdCrateSurface(ui);
        sourceAlpha = anchor.getAlpha();
        setClickable(true);
        setOutlineProvider(null);
        addView(dim, new LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH));
        addView(panel, placement);
        motion = new MotionProgress(Transitions.PANEL_MS, Transitions.OPEN_CLOSE, this::frame);
    }

    void move(float value, Runnable finish) {
        stop();
        if (panel.getWidth() == 0) {
            pending = (v,l,t,r,b,ol,ot,or,ob) -> move(value, finish);
            addOnLayoutChangeListener(pending);
            return;
        }
        Rect current = sourceBounds.get();
        if (!current.isEmpty()) origin.set(current);
        motion.move(value, finish);
    }

    private void frame(float p) {
        source.setAlpha(p == 0 ? sourceAlpha : 0);
        dim.setAlpha(p);
        if (p > 0 && p < 1 && !tracking) captureRecords();
        if ((p == 0 || p == 1) && tracking) releaseRecords();
        invalidate();
    }

    private void captureRecords() {
        tracking = true;
        List<VinylFace> faces = targets.get();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            for (VinylFace face : faces) {
                if (!entry.key.equals(face.getTag())) continue;
                Rect bounds = face.visibleRecord();
                if (!bounds.isEmpty()) {
                    face.setAlpha(0);
                    movingRecords.add(new MovingRecord(entry, face, bounds));
                }
                break;
            }
        }
    }

    private void releaseRecords() {
        for (MovingRecord record : movingRecords) record.face.setAlpha(1);
        movingRecords.clear();
        tracking = false;
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        float p = motion.progress();
        if (p <= 0) return;
        if (backdrop != null) {
            backdropBounds.set(0, 0, getWidth(), getHeight());
            paint.setAlpha(Math.round(p * 255));
            canvas.drawBitmap(backdrop, null, backdropBounds, paint);
            paint.setAlpha(255);
        }
        drawChild(canvas, dim, getDrawingTime());
        getLocationInWindow(window);
        moving.set(mix(origin.left-window[0], panel.getLeft(), p),
                mix(origin.top-window[1], panel.getTop(), p),
                mix(origin.right-window[0], panel.getRight(), p),
                mix(origin.bottom-window[1], panel.getBottom(), p));
        float radius = ui.dp(mix(6,17,p));
        clip.rewind();
        clip.addRoundRect(moving, radius, radius, Path.Direction.CW);
        int saved = canvas.save();
        canvas.clipPath(clip);
        moving.round(target);
        material.setBounds(target);
        material.draw(canvas);
        int content = canvas.save();
        // Match MorphSurface: a fixed-size interior follows the growing boundary.
        canvas.translate(moving.left-panel.getLeft(), moving.top-panel.getTop());
        drawChild(canvas, panel, getDrawingTime());
        canvas.restoreToCount(content);
        if (p < 1) {
            for (MovingRecord record : movingRecords) {
                Entry entry = record.entry;
                Rect end = record.end;
                recordBounds.set(mix(entry.bounds.left, end.left, p)-window[0],
                        mix(entry.bounds.top, end.top, p)-window[1],
                        mix(entry.bounds.right, end.right, p)-window[0],
                        mix(entry.bounds.bottom, end.bottom, p)-window[1]);
                drawRecord(canvas, entry, record.face, p);
            }
        }
        canvas.restoreToCount(saved);
    }

    private void drawRecord(Canvas canvas, Entry entry, VinylFace face, float p) {
        float radius = recordBounds.width() * mix(.024f, .5f, p);
        paint.setColor(0xff15171c);
        canvas.drawRoundRect(recordBounds, radius, radius, paint);
        int saved = canvas.save();
        recordClip.rewind();
        recordClip.addRoundRect(recordBounds, radius, radius, Path.Direction.CW);
        canvas.clipPath(recordClip);
        face.drawRecord(canvas, recordBounds);
        if (entry.cover != null) {
            label.set(recordBounds);
            float inset = mix(.035f, (1 - VinylArtwork.COVER_RADIUS_RATIO) / 2, p);
            label.inset(recordBounds.width()*inset, recordBounds.height()*inset);
            recordClip.rewind();
            recordClip.addRoundRect(label, label.width()*.5f*p, label.height()*.5f*p, Path.Direction.CW);
            int image = canvas.save();
            canvas.clipPath(recordClip);
            int side = Math.min(entry.cover.getWidth(),entry.cover.getHeight());
            int x = (entry.cover.getWidth()-side)/2, y = (entry.cover.getHeight()-side)/2;
            crop.set(x,y,x+side,y+side);
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(entry.cover,crop,label,paint);
            canvas.restoreToCount(image);
            // The spindle grows with the physical square-to-record transformation.
            paint.setColor(0xff0a0a0e);
            canvas.drawCircle(label.centerX(),label.centerY(),recordBounds.width()*.0165f*p,paint);
        }
        canvas.restoreToCount(saved);
    }

    private static float mix(float a,float b,float p) { return a+(b-a)*p; }
    void stop() {
        if (pending != null) { removeOnLayoutChangeListener(pending); pending = null; }
        motion.stop();
    }
    void release() {
        stop();
        source.setAlpha(sourceAlpha);
        releaseRecords();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        return motion.progress()<1 || super.dispatchTouchEvent(event);
    }
}
