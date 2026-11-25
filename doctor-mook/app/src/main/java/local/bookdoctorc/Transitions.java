package local.bookdoctorc;

import android.view.View;
import android.view.animation.PathInterpolator;

final class Transitions {
    static final PathInterpolator EASE = new PathInterpolator(.22f, .76f, .22f, 1f);

    static final int PANEL_MS = 560;
    static final int TAB_MS = 410;
    static final int LOCAL_MS = 240;
    static final int ANCHORED_MENU_MS = 380;
    static final PathInterpolator OPEN_CLOSE = new PathInterpolator(.42f, 0f, .58f, 1f);

    private Transitions() {}

    static int atRate(int millis) { return Math.round(millis / 1.10f); }

    static float openCloseTime(float progress) {
        if (progress <= 0 || progress >= 1) return Math.max(0, Math.min(1, progress));
        float low = 0, high = 1;
        for (int i = 0; i < 20; i++) {
            float middle = (low + high) / 2;
            if (OPEN_CLOSE.getInterpolation(middle) < progress) low = middle;
            else high = middle;
        }
        return (low + high) / 2;
    }

    static void arrive(View view, float displacement) {
        view.animate().cancel();
        view.setAlpha(0);
        view.setTranslationX(displacement);
        view.animate().alpha(1).translationX(0).setDuration(atRate(LOCAL_MS)).setInterpolator(EASE).start();
    }
}
