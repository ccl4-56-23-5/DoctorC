package local.bookdoctorc;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

/** Composites the Runwell render in its orthographic projection. */
@SuppressLint("ViewConstructor")
final class TurntableDeck extends View {
    private static final float WIDTH = 1200, HEIGHT = 924, TILT = .9437f;
    private static final float DISC_X = 573.814f, DISC_Y = 430.803f, DISC_RX = 330.626f;
    private static final float LABEL_RX = 138.444f, LABEL_Y = 430.464f;
    private static final float PIVOT_X = 1033.776f, PIVOT_Y = 201.078f;
    private static final RectF DISC = new RectF(DISC_X - DISC_RX, DISC_Y - DISC_RX * TILT,
            DISC_X + DISC_RX, DISC_Y + DISC_RX * TILT);

    private record Part(Bitmap bitmap, RectF bounds) {
        void draw(Canvas canvas, Paint paint) { canvas.drawBitmap(bitmap, null, bounds, paint); }
    }

    record Artwork(Part chassis, Part disc, Part label, Part spindle, Part arm) {
        static Artwork load(Resources resources) {
            return new Artwork(
                    loadPart(resources, R.drawable.runwell_chassis, 35, 76),
                    loadPart(resources, R.drawable.runwell_disc, 240, 116),
                    loadPart(resources, R.drawable.runwell_label, 433, 297),
                    loadPart(resources, R.drawable.runwell_spindle, 559, 415),
                    loadPart(resources, R.drawable.runwell_arm, 995, 55));
        }

        private static Part loadPart(Resources resources, int id, int x, int y) {
            Bitmap bitmap = BitmapFactory.decodeResource(resources, id);
            bitmap.prepareToDraw();
            return new Part(bitmap, new RectF(x, y, x + bitmap.getWidth(), y + bitmap.getHeight()));
        }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path coverMask = new Path(), wire = new Path();
    private final Rect crop = new Rect();
    private final RectF coverBounds = new RectF();
    private final MotionProgress arm;
    private final VinylArtwork fallback = new VinylArtwork();
    private Artwork artwork;
    private Bitmap cover;
    private boolean moving, wantsPlayback, settled;
    private float flight = 1;
    private long spinStart;
    private float parkedAngle;

    TurntableDeck(ShelfUi ui) {
        super(ui.context);
        arm = new MotionProgress(780, Transitions.OPEN_CLOSE, ignored -> invalidate());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setArtwork(Artwork ready) {
        artwork = ready;
        invalidate();
    }

    void flightProgress(float value) {
        if (flight == value) return;
        flight = value;
        invalidate();
    }

    void update(Bitmap image, boolean active) {
        boolean changed = cover != image;
        cover = image;
        if (changed) fallback.setCover(image);
        if (changed && image != null) {
            int side = Math.min(image.getWidth(), image.getHeight());
            int left = (image.getWidth() - side) / 2, top = (image.getHeight() - side) / 2;
            crop.set(left, top, left + side, top + side);
        }
        if (wantsPlayback != active) {
            wantsPlayback = active;
            updateMotion();
        } else if (changed) {
            invalidate();
        }
    }

    void prepareSelection() {
        parkedAngle = 0;
        spinStart = SystemClock.elapsedRealtime();
        invalidate();
    }

    void settled(boolean value) {
        if (settled == value) return;
        settled = value;
        updateMotion();
    }

    private void updateMotion() {
        boolean live = settled && isAttachedToWindow();
        boolean spin = wantsPlayback && live;
        long now = SystemClock.elapsedRealtime();
        if (moving != spin) {
            if (moving) parkedAngle = angle(now);
            else spinStart = now;
            moving = spin;
        }
        if (live) arm.move(spin ? 1 : 0, null);
        else {
            arm.stop();
            arm.set(wantsPlayback ? 1 : 0);
        }
        invalidate();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateMotion();
    }

    @Override protected void onDetachedFromWindow() {
        if (moving) parkedAngle = angle(SystemClock.elapsedRealtime());
        moving = false;
        arm.stop();
        super.onDetachedFromWindow();
    }

    private float angle(long now) {
        return (parkedAngle + (moving ? (now - spinStart) * .065f : 0)) % 360;
    }

    private float scale() { return Math.min(getWidth() / WIDTH, getHeight() / HEIGHT); }

    void discBounds(RectF out) {
        float scale = scale(), left = (getWidth() - WIDTH * scale) / 2;
        float top = (getHeight() - HEIGHT * scale) / 2;
        out.set(left + DISC.left * scale, top + DISC.top * scale,
                left + DISC.right * scale, top + DISC.bottom * scale);
    }

    @Override protected void onDraw(Canvas canvas) {
        float scale = scale();
        if (scale <= 0) return;
        int saved = canvas.save();
        canvas.translate((getWidth() - WIDTH * scale) / 2, (getHeight() - HEIGHT * scale) / 2);
        canvas.scale(scale, scale);
        float handoff = Math.min(1, flight / .52f);
        float initialLabel = DISC_RX * VinylArtwork.COVER_RADIUS_RATIO;
        float radius = cover == null ? LABEL_RX : initialLabel + (LABEL_RX - initialLabel) * flight;
        if (artwork == null) {
            drawSourceDisc(canvas, radius);
        } else {
            paint.setColor(Color.WHITE);
            artwork.chassis.draw(canvas, paint);
            drawDisc(canvas, radius, scale);
            if (handoff < 1) {
                int overlay = canvas.saveLayerAlpha(DISC, Math.round(255 * (1 - handoff)));
                drawSourceDisc(canvas, radius);
                canvas.restoreToCount(overlay);
            }
            drawArm(canvas);
        }
        canvas.restoreToCount(saved);
        if (moving) postInvalidateOnAnimation();
    }

    private void drawSourceDisc(Canvas canvas, float labelRadius) {
        int mark = canvas.save();
        canvas.scale(1, TILT, DISC_X, DISC_Y);
        fallback.draw(canvas, DISC_X, DISC_Y, DISC_RX, angle(SystemClock.elapsedRealtime()), labelRadius / DISC_RX);
        canvas.restoreToCount(mark);
    }

    private void drawDisc(Canvas canvas, float radius, float deckScale) {
        paint.setColor(Color.WHITE);
        artwork.disc.draw(canvas, paint);
        int discSurface = canvas.save();
        canvas.scale(1, TILT, DISC_X, DISC_Y);
        fallback.detail(canvas, DISC_X, DISC_Y, DISC_RX, DISC_RX * deckScale);
        canvas.restoreToCount(discSurface);
        if (cover == null) {
            artwork.label.draw(canvas, paint);
        } else {
            int mark = canvas.save();
            canvas.translate(DISC_X, LABEL_Y);
            canvas.scale(1, TILT);
            canvas.rotate(angle(SystemClock.elapsedRealtime()));
            coverMask.rewind();
            coverMask.addCircle(0, 0, radius, Path.Direction.CW);
            canvas.clipPath(coverMask);
            coverBounds.set(-radius, -radius, radius, radius);
            canvas.drawBitmap(cover, crop, coverBounds, paint);
            canvas.restoreToCount(mark);
        }
        artwork.spindle.draw(canvas, paint);
    }

    private void drawArm(Canvas canvas) {
        float progress = arm.progress();
        float yaw = progress * 34;
        float lift = (float) Math.sin(Math.PI * progress) * 5f;
        int mark = canvas.save();
        canvas.translate(PIVOT_X, PIVOT_Y - lift);
        canvas.scale(1, TILT);
        canvas.rotate(yaw);
        canvas.scale(1, 1 / TILT);
        canvas.translate(-PIVOT_X, -PIVOT_Y);
        paint.setColor(Color.WHITE);
        artwork.arm.draw(canvas, paint);
        canvas.restoreToCount(mark);

        // The small signal wire is anchored to the socket and follows the moving arm.
        float radians = (float) Math.toRadians(yaw);
        float endX = PIVOT_X + 8 * (float) Math.cos(radians) - 26 * (float) Math.sin(radians);
        float endY = PIVOT_Y + TILT * (8 * (float) Math.sin(radians) + 26 * (float) Math.cos(radians)) - lift;
        wire.rewind();
        wire.moveTo(929, 134);
        wire.cubicTo(954, 123, endX - 28, endY - 52, endX, endY);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.3f);
        paint.setColor(0xff695e58);
        canvas.drawPath(wire, paint);
        paint.setStrokeWidth(1.0f);
        paint.setColor(0xffc1ab9c);
        canvas.drawPath(wire, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
