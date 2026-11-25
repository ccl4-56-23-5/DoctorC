package local.bookdoctorc;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import androidx.annotation.RequiresApi;
import java.util.function.BooleanSupplier;

/** Backdrop-only optical material. Controls draw their text and icons above it. */
final class LiquidGlass {
    enum Profile {
        ACTION_GROUP(4, 16, 32, 0x99ffffff, 0x47000000),
        THUMB(2, 12, 24, 0x99ffffff, 0x47000000);

        final float blur, bevel, amount;
        final int lightFill, darkFill;

        Profile(float blur, float bevel, float amount, int lightFill, int darkFill) {
            this.blur = blur;
            this.bevel = bevel;
            this.amount = amount;
            this.lightFill = lightFill;
            this.darkFill = darkFill;
        }
    }

    private final float density;
    private final Profile profile;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF capsule = new RectF();
    private final Path mask = new Path();
    private final int[] origin = new int[2], behind = new int[2];
    private Backdrop backdrop;
    private Shader directional;
    private int width, height;
    private float corner;

    LiquidGlass(View owner, Profile profile) {
        this.profile = profile;
        density = owner.getResources().getDisplayMetrics().density;
    }

    static Drawable group(View owner, View source, boolean dark, BooleanSupplier settled) {
        LiquidGlass material = new LiquidGlass(owner, Profile.ACTION_GROUP);
        return new Drawable() {
            @Override public void draw(Canvas canvas) {
                Rect rect = getBounds();
                material.draw(canvas, owner, settled.getAsBoolean() ? source : null, rect.left, rect.top,
                        rect.width(), rect.height(), dark, false);
            }

            @Override public void setAlpha(int alpha) {}
            @Override public void setColorFilter(ColorFilter filter) {}
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    void draw(Canvas canvas, View owner, View source, int left, int top, int w, int h, boolean dark, boolean dirty) {
        draw(canvas, owner, source, left, top, w, h, dark, dirty, -1);
    }

    void draw(Canvas canvas, View owner, View source, int left, int top, int w, int h,
              boolean dark, boolean dirty, float requestedCorner) {
        if (w <= 0 || h <= 0) return;
        int saved = canvas.save();
        canvas.translate(left, top);
        float inset = .5f * density;
        float maxCorner = Math.max(0, Math.min(w, h) * .5f - inset);
        float radius = requestedCorner < 0 ? maxCorner : Math.min(maxCorner, requestedCorner);
        if (w != width || h != height || corner != radius) {
            width = w;
            height = h;
            corner = radius;
            capsule.set(inset, inset, w - inset, h - inset);
            mask.rewind();
            mask.addRoundRect(capsule, radius, radius, Path.Direction.CW);
            if (profile == Profile.THUMB) {
                directional = new LinearGradient(0, 0, w * .4f, h,
                        new int[]{0xc5ffffff, 0x28ffffff, 0x0cffffff, 0x82ffffff},
                        new float[]{0, .31f, .68f, 1}, Shader.TileMode.CLAMP);
            }
        }
        boolean optical = Build.VERSION.SDK_INT >= 31 && source != null
                && source.getWidth() > 0 && canvas.isHardwareAccelerated();
        if (optical && backdrop == null) backdrop = new Backdrop(density, profile);
        canvas.save();
        canvas.clipPath(mask);
        if (optical) {
            owner.getLocationInWindow(origin);
            source.getLocationInWindow(behind);
            backdrop.capture(source, w, h, origin[0] - behind[0] + left, origin[1] - behind[1] + top, dirty, radius);
            backdrop.draw(canvas);
        }
        boolean tint = optical || profile == Profile.ACTION_GROUP;
        canvas.drawColor(tint ? (dark ? profile.darkFill : profile.lightFill)
                : (dark ? 0xff2b3039 : 0xfff4f1ed));
        if (dark) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(density);
            paint.setColor(0x05000000);
            canvas.drawRoundRect(capsule, radius, radius, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((profile == Profile.THUMB ? 1f : .5f) * density);
        paint.setColor(dark ? 0x38ffffff : 0x61ffffff);
        if (directional != null) paint.setShader(directional);
        if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated()) paint.setBlendMode(BlendMode.PLUS);
        canvas.drawRoundRect(capsule, radius, radius, paint);
        if (Build.VERSION.SDK_INT >= 29 && canvas.isHardwareAccelerated()) paint.setBlendMode(null);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        canvas.restoreToCount(saved);
    }

    void release() {
        if (Build.VERSION.SDK_INT >= 31 && backdrop != null) backdrop.node.discardDisplayList();
    }

    @RequiresApi(31)
    private static final class Backdrop {
        private final RenderNode node = new RenderNode("BookDoctorC liquid glass");
        private final RenderEffect blurred;
        private final RuntimeShader lens;
        private final int pad;
        private final float bevel, amount;
        private int width, height, x, y;
        private float corner;

        Backdrop(float density, Profile profile) {
            float blur = profile.blur * density;
            bevel = profile.bevel * density;
            amount = profile.amount * density;
            pad = (int) Math.ceil(amount + blur * 2);
            RenderEffect vibrancy = RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(
                    new float[]{
                            1.3935f, -.3575f, -.036f, 0, 0,
                            -.1065f, 1.1425f, -.036f, 0, 0,
                            -.1065f, -.3575f, 1.464f, 0, 0,
                            0, 0, 0, 1, 0
                    }));
            blurred = blur == 0 ? vibrancy : RenderEffect.createChainEffect(
                    RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP), vibrancy);
            RuntimeShader shader = null;
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    shader = new RuntimeShader("""
                            uniform shader content;
                            uniform float4 geometry;
                            uniform float3 optics;

                            half4 main(float2 p) {
                                float2 local = p - geometry.xy;
                                float2 axis = clamp(local, -geometry.zw, geometry.zw);
                                float2 delta = local - axis;
                                float magnitude = length(delta);
                                float radius = optics.y;
                                float inside = max(radius - magnitude, 0.0);
                                if (inside >= optics.x || magnitude <= 0.0001) return content.eval(p);
                                float edge = 1.0 - inside / optics.x;
                                float bend = (1.0 - sqrt(max(0.0, 1.0 - edge * edge))) * optics.z;
                                return content.eval(p - delta / magnitude * bend);
                            }
                            """);
                } catch (IllegalArgumentException unsupported) {
                    android.util.Log.w("BookDoctorC", "Liquid glass lens unavailable", unsupported);
                }
            }
            lens = shader;
        }

        void capture(View source, int w, int h, int dx, int dy, boolean dirty, float radius) {
            boolean resized = width != w || height != h;
            if (resized || corner != radius) {
                corner = radius;
                width = w;
                height = h;
                node.setPosition(-pad, -pad, w + pad, h + pad);
                RenderEffect effect = blurred;
                if (Build.VERSION.SDK_INT >= 33 && lens != null) {
                    lens.setFloatUniform("geometry", w * .5f + pad, h * .5f + pad,
                            Math.max(0, w * .5f - radius), Math.max(0, h * .5f - radius));
                    lens.setFloatUniform("optics", bevel, radius, amount);
                    effect = RenderEffect.createChainEffect(
                            RenderEffect.createRuntimeShaderEffect(lens, "content"), blurred);
                }
                node.setRenderEffect(effect);
            }
            if (dirty || resized || !node.hasDisplayList() || x != dx || y != dy) {
                x = dx;
                y = dy;
                Canvas record = node.beginRecording(w + pad * 2, h + pad * 2);
                record.translate(pad - dx, pad - dy);
                source.draw(record);
                node.endRecording();
            }
        }

        void draw(Canvas canvas) { canvas.drawRenderNode(node); }
    }
}
