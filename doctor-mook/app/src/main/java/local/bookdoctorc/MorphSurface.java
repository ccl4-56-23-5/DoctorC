package local.bookdoctorc;

import android.graphics.Outline;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewOutlineProvider;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class MorphSurface {
    private final View panel;
    private final Rect start;
    private final Rect clipped = new Rect();
    private final int[] location = new int[2];
    private final float sourceRadius;
    private final float finalRadius;
    private final Consumer<Float> onFrame;
    final MotionProgress motion;
    private View.OnLayoutChangeListener pendingLayout;
    private Supplier<Rect> sourceBounds;

    MorphSurface(View panel,Rect start,float sourceRadius,float finalRadius,Consumer<Float> onFrame){
        this(panel,start,sourceRadius,finalRadius,Transitions.PANEL_MS,onFrame);
    }

    MorphSurface(View panel,Rect start,float sourceRadius,float finalRadius,int duration,Consumer<Float> onFrame){
        this.panel = panel;
        this.start = new Rect(start);
        this.sourceRadius = sourceRadius;
        this.finalRadius = finalRadius;
        this.onFrame = onFrame;
        motion = new MotionProgress(duration, Transitions.OPEN_CLOSE, this::paint);
        panel.setClipToOutline(true);
        panel.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                float progress = motion.progress();
                if (progress == 1) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), finalRadius);
                } else if (!clipped.isEmpty()) {
                    outline.setRoundRect(clipped, lerp(sourceRadius, finalRadius, progress));
                }
            }
        });
    }

    static Rect visible(View source) {
        if (source == null || !source.isAttachedToWindow() || source.getWidth() <= 0) return new Rect();
        int[] location = new int[2];
        source.getLocationInWindow(location);
        return new Rect(location[0], location[1], location[0] + source.getWidth(), location[1] + source.getHeight());
    }

    void followSource(Supplier<Rect> bounds) { sourceBounds = bounds; }

    void move(float target, Runnable end) {
        stop();
        if (panel.getWidth() == 0) {
            pendingLayout = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> move(target, end);
            panel.addOnLayoutChangeListener(pendingLayout);
            return;
        }
        motion.move(target, end);
    }

    void snap(float target) {
        stop();
        if (panel.getWidth() == 0) {
            pendingLayout = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> snap(target);
            panel.addOnLayoutChangeListener(pendingLayout);
        } else {
            motion.set(target);
        }
    }

    void stop() {
        if (pendingLayout != null) {
            panel.removeOnLayoutChangeListener(pendingLayout);
            pendingLayout = null;
        }
        motion.stop();
    }

    private void paint(float value) {
        if (sourceBounds != null) {
            Rect current = sourceBounds.get();
            if (!current.isEmpty()) start.set(current);
        }
        panel.getLocationInWindow(location);
        float finalX = location[0] - panel.getTranslationX();
        float finalY = location[1] - panel.getTranslationY();
        float left = start.isEmpty() ? finalX + panel.getWidth() * .42f : start.left;
        float top = start.isEmpty() ? finalY + panel.getHeight() * .35f : start.top;
        float width = start.isEmpty() ? panel.getWidth() * .16f : start.width();
        float height = start.isEmpty() ? panel.getHeight() * .22f : start.height();
        panel.setTranslationX(lerp(left - finalX, 0, value));
        panel.setTranslationY(lerp(top - finalY, 0, value));
        clipped.set(0, 0, Math.round(lerp(width, panel.getWidth(), value)),
                Math.round(lerp(height, panel.getHeight(), value)));
        panel.setClipBounds(value == 1 ? null : clipped);
        panel.invalidateOutline();
        if (onFrame != null) onFrame.accept(value);
    }

    private static float lerp(float first, float last, float progress) { return first + (last - first) * progress; }
}
