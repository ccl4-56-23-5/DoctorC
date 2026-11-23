package local.bookdoctorc;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Draws the source card and reading page inside one moving outline. */
final class ReaderFlight extends FrameLayout {
    final MotionProgress motion;
    final long bookId;
    private final View source;
    private final float sourceAlpha, sourceRadius;
    private final Bitmap backdrop;
    private final Rect backdropBounds = new Rect();
    private final ShelfUi ui;
    private final LinearLayout loading;
    private final Rect visible = new Rect();
    private final RectF origin = new RectF(), bounds = new RectF();
    private final Path outline = new Path();
    private final int[] sourceLocation = new int[2], hostLocation = new int[2];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Camera camera = new Camera();
    private final Matrix perspective = new Matrix();
    private final boolean hinged;
    private final Bitmap snapshot;
    private final RenderNode card;
    private final int cardWidth, cardHeight;
    private View readerPage;
    private boolean landed;

    ReaderFlight(MainActivity activity, ShelfUi ui, Library.Book book, View anchor, Bitmap backdrop) {
        super(activity);
        this.ui = ui;
        this.bookId = book.id();
        this.backdrop = backdrop;
        source = anchor != null && anchor.isAttachedToWindow() && anchor.getLocalVisibleRect(visible)
                ? anchor : null;
        sourceAlpha = source == null ? 1 : source.getAlpha();
        boolean cover = source != null && ShelfUi.BOOK_FACE.equals(source.getTag());
        sourceRadius = ui.dp(cover ? 3 : 10);
        Configuration config = getResources().getConfiguration();
        hinged = cover && config.smallestScreenWidthDp >= 600
                && config.orientation == Configuration.ORIENTATION_LANDSCAPE;
        camera.setLocation(0, 0, -20f * getResources().getDisplayMetrics().density);
        cardWidth = source == null ? 0 : source.getWidth();
        cardHeight = source == null ? 0 : source.getHeight();
        if (source == null) {
            card = null;
            snapshot = null;
        } else if (Build.VERSION.SDK_INT >= 29 && source.isHardwareAccelerated()) {
            card = new RenderNode("Book card");
            card.setPosition(0, 0, cardWidth, cardHeight);
            Canvas recording = card.beginRecording();
            source.draw(recording);
            card.endRecording();
            snapshot = null;
        } else {
            card = null;
            float scale = Math.min(1, 512f / Math.max(cardWidth, cardHeight));
            snapshot = Bitmap.createBitmap(Math.max(1, Math.round(cardWidth * scale)),
                    Math.max(1, Math.round(cardHeight * scale)), Bitmap.Config.ARGB_8888);
            Canvas capture = new Canvas(snapshot);
            capture.scale(scale, scale);
            source.draw(capture);
        }
        loading = ui.column();
        loading.setBackgroundColor(ui.paper);
        TextView title = ui.text(book.title(), 17, ui.ink, true);
        loading.addView(ReadingUi.header(ui, title, activity::closeReader));
        loading.addView(ui.divider());
        TextView status = ui.text("正在打开…", 15, ui.secondary, false);
        status.setGravity(Gravity.CENTER);
        status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        loading.addView(status, new LinearLayout.LayoutParams(ShelfUi.MATCH, 0, 1));
        addView(loading, new LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH));
        setClickable(true);
        motion = new MotionProgress(Transitions.PANEL_MS, Transitions.OPEN_CLOSE, this::frame);
    }

    void move(float target, Runnable finish) {
        if (source != null && source.isAttachedToWindow()) {
            Rect current = new Rect();
            if (source.getLocalVisibleRect(current)) {
                visible.set(current);
                source.getLocationInWindow(sourceLocation);
            }
        }
        landed = false;
        if (target == 0) {
            motion.move(0, finish);
            return;
        }
        motion.move(1, () -> postOnAnimation(() -> {
            if (!isAttachedToWindow() || motion.progress() != 1) return;
            landed = true;
            finish.run();
        }));
    }

    boolean hasLanded() { return landed && motion.progress() == 1; }

    void mount(View page) {
        readerPage = page;
        page.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        addView(page, 0, new LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH));
    }

    boolean isReady() { return loading.getParent() == null; }

    void ready() {
        removeView(loading);
        readerPage.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    private void frame(float fraction) {
        if (source != null) source.setAlpha(fraction == 0 ? sourceAlpha : 0);
        invalidate();
    }

    private void drawBackdrop(Canvas canvas, float p) {
        float fade = Math.min(1f, Math.min(p * 4, (1 - p) * 4));
        if (backdrop != null) {
            paint.setAlpha(Math.round(210 * fade));
            backdropBounds.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(backdrop, null, backdropBounds, paint);
            paint.setAlpha(255);
        }
        int color = ui.dark ? 0x150c0907 : 0x172d231a;
        canvas.drawColor((Math.round((color >>> 24) * fade) << 24) | (color & 0xffffff));
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        float fraction = motion.progress();
        if (fraction >= 1) {
            super.dispatchDraw(canvas);
            return;
        }
        if (fraction <= 0 || getWidth() <= 0) return;
        if (source == null) {
            int saved = canvas.save();
            canvas.translate(getWidth() * (1 - fraction), 0);
            super.dispatchDraw(canvas);
            canvas.restoreToCount(saved);
            return;
        }
        drawBackdrop(canvas, fraction);
        getLocationInWindow(hostLocation);
        origin.set(visible);
        origin.offset(sourceLocation[0] - hostLocation[0], sourceLocation[1] - hostLocation[1]);
        bounds.set(origin.left * (1 - fraction), origin.top * (1 - fraction),
                mix(origin.right, getWidth(), fraction), mix(origin.bottom, getHeight(), fraction));
        outline.rewind();
        float radius = sourceRadius * (1 - fraction);
        outline.addRoundRect(bounds, radius, radius, Path.Direction.CW);
        int clip = canvas.save();
        canvas.clipPath(outline);
        int page = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        float scale = bounds.width() / getWidth();
        canvas.scale(scale, scale);
        canvas.drawColor(ui.paper);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(page);
        drawCard(canvas, fraction);
        canvas.restoreToCount(clip);
    }

    private void drawCard(Canvas canvas, float fraction) {
        float reveal = Math.max(0, Math.min(1, (fraction - .10f) / .49f));
        if (reveal == 1) return;
        float exposed = 1 - reveal * reveal * (3 - 2 * reveal);
        float scale = Math.max(bounds.width() / origin.width(), bounds.height() / origin.height());
        int saved = canvas.save();
        canvas.clipRect(bounds.left, bounds.top, bounds.left + bounds.width() * exposed, bounds.bottom);
        canvas.translate(bounds.left, bounds.top);
        if (hinged) {
            camera.save();
            camera.rotateY(-88 * reveal);
            camera.getMatrix(perspective);
            camera.restore();
            canvas.concat(perspective);
        }
        canvas.scale(scale, scale);
        canvas.translate(-visible.left, -visible.top);
        if (Build.VERSION.SDK_INT >= 29 && card != null && canvas.isHardwareAccelerated()) {
            canvas.drawRenderNode(card);
        } else if (snapshot != null) {
            canvas.scale((float) cardWidth / snapshot.getWidth(), (float) cardHeight / snapshot.getHeight());
            canvas.drawBitmap(snapshot, 0, 0, paint);
        }
        canvas.restoreToCount(saved);
    }

    private static float mix(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }

    // Switch ownership once this layer is laid out, so the source is present on the tap frame.
    void begin(Runnable finish) {
        ViewTreeObserver tree = getViewTreeObserver();
        tree.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (tree.isAlive()) tree.removeOnPreDrawListener(this);
                if (isAttachedToWindow()) finish.run();
                return true;
            }
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        return motion.progress() < 1 || super.dispatchTouchEvent(event);
    }

    void release() {
        motion.stop();
        if (source != null) source.setAlpha(sourceAlpha);
    }
}
