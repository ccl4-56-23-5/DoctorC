package local.bookdoctorc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/** Draws the pressed disc and its independently rotating printed label. */
final class VinylArtwork {
    static final float COVER_RADIUS_RATIO = .73f;
    private static final float FACE_RADIUS = .986f;
    private static final Shader EDGE = new LinearGradient(0, -.99f, 0, 1,
            new int[]{0xff898984, 0xff3e434c, 0xff090b10, 0xff232832, 0xff080a0d},
            new float[]{0, .26f, .54f, .95f, 1}, Shader.TileMode.CLAMP);
    private static final Shader RELIEF = new RadialGradient(0, 0, 1,
            new int[]{0x00080a10, 0x00080a10, 0x3506080b, 0x00080a10, 0x6406080b},
            new float[]{0, .70f, .937f, .971f, 1}, Shader.TileMode.CLAMP);
    private static final Shader REFLECTIONS = new LinearGradient(-.83f, -.68f, .95f, .81f,
            new int[]{0x01bacbe1, 0x50bacbe1, 0x15bacbe1, 0x00040508, 0x2bbac4d3, 0x04bac4d3},
            new float[]{0, .19f, .36f, .59f, .82f, 1}, Shader.TileMode.CLAMP);
    private static final int[] GROOVE_INKS = {0x4005070b, 0x2897a6b8, 0x6e030509, 0x4aaebac8};
    private static final Path[][] GROOVES = {grooves(16), grooves(36), grooves(64)};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect crop = new Rect();
    private final RectF label = new RectF();
    private final Path labelMask = new Path();
    private static final Shader LABEL_GLOSS = new LinearGradient(-.28f, -.4f, .22f, .28f,
            0x22fff4df, 0x00ffffff, Shader.TileMode.CLAMP);
    private Bitmap cover;

    boolean setCover(Bitmap next) {
        if (cover == next) return false;
        cover = next;
        if (next != null) {
            int side = Math.min(next.getWidth(), next.getHeight());
            int left = (next.getWidth() - side) / 2, top = (next.getHeight() - side) / 2;
            crop.set(left, top, left + side, top + side);
        }
        return true;
    }

    void draw(Canvas canvas, float x, float y, float radius, float angle) {
        draw(canvas, x, y, radius, angle, COVER_RADIUS_RATIO);
    }

    void draw(Canvas canvas, float x, float y, float radius, float angle, float labelRadius) {
        if (radius <= 0) return;
        int saved = canvas.save();
        canvas.translate(x, y);
        canvas.scale(radius, radius);
        paint.setColor(0x3506090d);
        canvas.drawOval(-.982f, -.922f, .982f, 1.08f, paint);
        paint.setColor(Color.WHITE);
        paint.setShader(EDGE);
        canvas.drawOval(-1, -.99f, 1, 1.033f, paint);
        paint.setShader(null);
        paint.setColor(0xff161a20);
        canvas.drawCircle(0, 0, FACE_RADIUS, paint);
        drawSurface(canvas, radius);
        if (cover != null) {
            paint.setColor(0xff0a0c10);
            canvas.drawCircle(0, 0, labelRadius + .013f, paint);
            label.set(-labelRadius, -labelRadius, labelRadius, labelRadius);
            labelMask.rewind();
            labelMask.addCircle(0, 0, labelRadius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(labelMask);
            canvas.rotate(angle);
            paint.setColor(Color.WHITE);
            canvas.drawBitmap(cover, crop, label, paint);
            canvas.restore();
        } else {
            paint.setColor(0xffc5a370);
            canvas.drawCircle(0, 0, .341f, paint);
            paint.setColor(0xff403735);
            canvas.drawCircle(0, 0, .29f, paint);
            paint.setColor(0xffd2ad76);
            canvas.drawCircle(0, 0, .16f, paint);
        }
        paint.setColor(Color.WHITE);
        paint.setShader(LABEL_GLOSS);
        canvas.drawCircle(0, 0, cover == null ? .34f : labelRadius, paint);
        paint.setShader(null);
        paint.setColor(0xff0a0a0e);
        canvas.drawCircle(0, 0, .033f, paint);
        paint.setColor(0xffb3ac9b);
        canvas.drawArc(-.029f, -.029f, .029f, .029f, 30, 153, true, paint);
        canvas.restoreToCount(saved);
    }

    /** Pressing grooves and radial reflections are fixed in the lamp's coordinates. */
    private void drawSurface(Canvas canvas, float radiusPx) {
        paint.setColor(Color.WHITE);
        paint.setShader(REFLECTIONS);
        canvas.drawCircle(0, 0, FACE_RADIUS, paint);
        paint.setShader(RELIEF);
        canvas.drawCircle(0, 0, FACE_RADIUS, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        Path[] paths = GROOVES[radiusPx < 45 ? 0 : radiusPx < 96 ? 1 : 2];
        paint.setStrokeWidth(Math.max(.002f, .42f / radiusPx));
        for (int i = 0; i < paths.length; i++) {
            paint.setColor(GROOVE_INKS[i]);
            canvas.drawPath(paths[i], paint);
        }
        paint.setStrokeWidth(.008f);
        paint.setColor(0x78acb6be);
        canvas.drawArc(-.978f, -.978f, .978f, .978f, 195, 105, false, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private static Path[] grooves(int count) {
        Path[] paths = {new Path(), new Path(), new Path(), new Path()};
        float spacing = (.958f - .374f) / count;
        for (int i = 0; i < count; i++) {
            float radius = .374f + i * spacing;
            int group = i % 7 == 0 ? 2 : 0;
            paths[group].addCircle(0, 0, radius, Path.Direction.CW);
            paths[group + 1].addCircle(0, 0, radius + spacing * .34f, Path.Direction.CW);
        }
        return paths;
    }

    void detail(Canvas canvas, float x, float y, float radius, float radiusPx) {
        int saved = canvas.save();
        canvas.translate(x, y);
        canvas.scale(radius, radius);
        drawSurface(canvas, radiusPx);
        canvas.restoreToCount(saved);
    }
}
