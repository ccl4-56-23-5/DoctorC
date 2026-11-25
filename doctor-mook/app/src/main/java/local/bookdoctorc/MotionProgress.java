package local.bookdoctorc;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;
import java.util.function.Consumer;

final class MotionProgress {
    private static final LinearInterpolator LINEAR = new LinearInterpolator();
    private final int duration;
    private final Consumer<Float> frame;
    private final TimeInterpolator interpolator;
    private ValueAnimator animator;
    private float fraction;

    MotionProgress(int duration, Consumer<Float> frame) {
        this(duration, Transitions.EASE, frame);
    }

    MotionProgress(int duration, TimeInterpolator interpolator, Consumer<Float> frame) {
        this.duration = Transitions.atRate(duration);
        this.interpolator = interpolator;
        this.frame = frame;
    }

    float progress() { return fraction; }

    void set(float value) {
        fraction = value;
        frame.accept(value);
    }

    void move(float target, Runnable end) {
        stop();
        if (!ValueAnimator.areAnimatorsEnabled() || fraction == target) {
            set(target);
            if (end != null) end.run();
            return;
        }
        set(fraction);
        if (interpolator == Transitions.OPEN_CLOSE) {
            // Reverse the same timeline, including after an interrupted opening or a back gesture.
            float start = Transitions.openCloseTime(fraction);
            float finish = Transitions.openCloseTime(target);
            animator = ValueAnimator.ofFloat(start, finish);
            animator.setDuration(Math.max(1, Math.round(duration * Math.abs(finish - start))));
            animator.setInterpolator(LINEAR);
            animator.addUpdateListener(value -> set(interpolator.getInterpolation((float) value.getAnimatedValue())));
        } else {
            animator = ValueAnimator.ofFloat(fraction, target);
            animator.setDuration(Math.max(Transitions.atRate(85), Math.round(duration * Math.abs(target - fraction))));
            animator.setInterpolator(interpolator);
            animator.addUpdateListener(value -> set((float) value.getAnimatedValue()));
        }
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                animator = null;
                if (end != null) end.run();
            }
        });
        animator.start();
    }

    void stop() {
        if (animator == null) return;
        animator.removeAllListeners();
        animator.cancel();
        animator = null;
    }
}
