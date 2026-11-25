package local.bookdoctorc;

import android.graphics.*;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Draws both ends of a CD transition in a stationary, full-window layer. */
final class AudioPanelFlight extends FrameLayout {
    final MotionProgress motion;
    private final View panel, source, dim;
    private final Supplier<Rect> sourceBounds;
    private final float sourceAlpha, sourceRadius, targetRadius;
    private final int fallbackColor;
    private final Consumer<Float> onFrame;
    private final RenderNode recording;
    private final Bitmap bitmap;
    private final Rect origin;
    private final int captureWidth, captureHeight;
    private final RectF moving = new RectF();
    private final RectF discDestination = new RectF(), discFrame = new RectF();
    private final int[] deckWindow = new int[2];
    private final Path outline = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final int[] window = new int[2];
    private OnLayoutChangeListener pendingLayout;
    private final TurntableDeck deck;

    AudioPanelFlight(ShelfUi ui, View panel, LayoutParams placement, View dim, View anchor,
                     Supplier<Rect> sourceBounds, float sourceRadius, float targetRadius, int fallbackColor,
                     int duration, Consumer<Float> onFrame, TurntableDeck playerDeck) {
        super(ui.context);
        this.panel = panel;
        this.dim = dim;
        this.sourceBounds = sourceBounds;
        this.sourceRadius = sourceRadius;
        this.targetRadius = targetRadius;
        this.fallbackColor = fallbackColor;
        this.onFrame = onFrame;
        setClickable(true);
        setOutlineProvider(null);
        if (dim != null) addView(dim, new LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH));
        addView(panel, placement);
        origin = sourceBounds == null ? new Rect() : new Rect(sourceBounds.get());
        source = origin.isEmpty() ? null : anchor;
        deck = source instanceof VinylFace ? playerDeck : null;
        captureWidth = origin.width();
        captureHeight = origin.height();
        sourceAlpha = source == null ? 1 : source.getAlpha();
        if (source == null || deck != null) {
            recording = null;
            bitmap = null;
        } else {
            Point position = new Point();
            windowBounds(source, position);
            if (Build.VERSION.SDK_INT >= 29 && source.isHardwareAccelerated()) {
                recording = new RenderNode("CD source");
                recording.setPosition(0, 0, origin.width(), origin.height());
                Canvas canvas = recording.beginRecording();
                canvas.translate(position.x - origin.left, position.y - origin.top);
                source.draw(canvas);
                recording.endRecording();
                bitmap = null;
            } else {
                recording = null;
                bitmap = Bitmap.createBitmap(origin.width(), origin.height(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                canvas.translate(position.x - origin.left, position.y - origin.top);
                source.draw(canvas);
                bitmap.prepareToDraw();
            }
        }
        motion = new MotionProgress(duration, Transitions.OPEN_CLOSE, this::frame);
    }

    void move(float target, Runnable finish) {
        stop();
        if (getWidth() == 0 || panel.getWidth() == 0) {
            pendingLayout = (view, l, t, r, b, ol, ot, or, ob) -> move(target, finish);
            addOnLayoutChangeListener(pendingLayout);
            return;
        }
        if (source != null) {
            Rect measured = sourceBounds.get();
            if (!measured.isEmpty()) origin.set(measured);
        }
        motion.move(target, finish);
    }

    void stop() {
        if (pendingLayout != null) {
            removeOnLayoutChangeListener(pendingLayout);
            pendingLayout = null;
        }
        motion.stop();
    }

    private void frame(float fraction) {
        if (source != null) source.setAlpha(fraction > 0 ? 0 : sourceAlpha);
        if (deck != null) deck.flightProgress(fraction);
        if (dim != null) dim.setAlpha(fraction);
        if (onFrame != null) onFrame.accept(fraction);
        invalidate();
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        float fraction = motion.progress();
        if (fraction <= 0 || panel.getWidth() <= 0 || panel.getHeight() <= 0) return;
        long time = getDrawingTime();
        if (dim != null) drawChild(canvas, dim, time);
        if (fraction >= 1 && targetRadius == 0) {
            drawChild(canvas, panel, time);
            return;
        }
        if (source == null && targetRadius == 0) {
            int saved = canvas.save();
            canvas.translate(getWidth() * (1 - fraction), 0);
            drawChild(canvas, panel, time);
            canvas.restoreToCount(saved);
            return;
        }
        getLocationInWindow(window);
        int left = panel.getLeft(), top = panel.getTop();
        float startLeft = source == null ? left + panel.getWidth() * .4f : origin.left - window[0];
        float startTop = source == null ? top + panel.getHeight() * .4f : origin.top - window[1];
        float startRight = source == null ? startLeft + panel.getWidth() * .2f : origin.right - window[0];
        float startBottom = source == null ? startTop + panel.getHeight() * .2f : origin.bottom - window[1];
        moving.set(mix(startLeft, left, fraction), mix(startTop, top, fraction),
                mix(startRight, panel.getRight(), fraction), mix(startBottom, panel.getBottom(), fraction));
        float initialRadius = deck == null ? sourceRadius
                : Math.min(sourceRadius, Math.min(origin.width(), origin.height()) / 2f);
        float radius = mix(initialRadius, targetRadius, fraction);
        if (targetRadius > 0) {
            float shadow = mix(0, targetRadius * .27f, fraction);
            paint.setColor(0x48061119);
            canvas.drawRoundRect(moving.left - shadow, moving.top,
                    moving.right + shadow, moving.bottom + shadow * 1.5f, radius + shadow, radius + shadow, paint);
        }
        outline.rewind();
        outline.addRoundRect(moving, radius, radius, Path.Direction.CW);
        int saved = canvas.save();
        canvas.clipPath(outline);
        canvas.drawColor(fallbackColor);
        int content = canvas.save();
        if (deck != null) {
            // The growing panel and its single visible disc share the same transform.
            locateDisc(fraction);
            if (!discDestination.isEmpty()) {
                float scaleX = discFrame.width() / discDestination.width();
                float scaleY = discFrame.height() / discDestination.height();
                canvas.translate(discFrame.left - discDestination.left * scaleX,
                        discFrame.top - discDestination.top * scaleY);
                canvas.scale(scaleX, scaleY);
            }
        } else {
            float scale = Math.max(moving.width() / panel.getWidth(), moving.height() / panel.getHeight());
            canvas.translate(moving.left - left * scale, moving.top - top * scale);
            canvas.scale(scale, scale);
        }
        drawChild(canvas, panel, time);
        canvas.restoreToCount(content);
        if (deck == null) drawSource(canvas, moving, 1 - smooth(fraction));
        canvas.restoreToCount(saved);
    }

    private void locateDisc(float fraction) {
        deck.discBounds(discDestination);
        deck.getLocationInWindow(deckWindow);
        discDestination.offset(deckWindow[0] - window[0], deckWindow[1] - window[1]);
        discFrame.set(mix(origin.left - window[0], discDestination.left, fraction),
                mix(origin.top - window[1], discDestination.top, fraction),
                mix(origin.right - window[0], discDestination.right, fraction),
                mix(origin.bottom - window[1], discDestination.bottom, fraction));
    }

    private void drawSource(Canvas canvas, RectF frame, float opacity) {
        if (source == null || opacity <= 0 || frame.isEmpty()) return;
        float scale = Math.min(frame.width() / captureWidth, frame.height() / captureHeight);
        int saved = canvas.save();
        if (opacity < 1) canvas.saveLayerAlpha(frame, Math.round(opacity * 255));
        canvas.translate(frame.centerX() - captureWidth * scale / 2,
                frame.centerY() - captureHeight * scale / 2);
        canvas.scale(scale, scale);
        if (Build.VERSION.SDK_INT >= 29 && recording != null && canvas.isHardwareAccelerated()) {
            canvas.drawRenderNode(recording);
        } else if (bitmap != null) {
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(bitmap, 0, 0, paint);
        }
        canvas.restoreToCount(saved);
    }

    /** Keeps the clipped rectangle and drawing origin in the same coordinate space. */
    static Rect windowBounds(View source, Point position) {
        Rect bounds = new Rect();
        if (source == null || !source.isAttachedToWindow() || !source.getGlobalVisibleRect(bounds, position)) {
            bounds.setEmpty();
            return bounds;
        }
        int[] root = new int[2];
        source.getRootView().getLocationInWindow(root);
        bounds.offset(root[0], root[1]);
        if (position != null) position.offset(root[0], root[1]);
        return bounds;
    }

    private static float mix(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }

    private static float smooth(float value) { return value * value * (3 - 2 * value); }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        return motion.progress() < 1 || super.dispatchTouchEvent(event);
    }

    void release() {
        stop();
        if (source != null) source.setAlpha(sourceAlpha);
        if (deck != null) deck.flightProgress(1);
        if (Build.VERSION.SDK_INT >= 29 && recording != null) recording.discardDisplayList();
    }
}
