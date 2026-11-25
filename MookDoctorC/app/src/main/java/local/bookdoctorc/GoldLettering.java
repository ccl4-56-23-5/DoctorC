package local.bookdoctorc;

import android.content.Context;
import android.graphics.*;
import android.text.TextPaint;
import android.widget.TextView;

/** A foil highlight on the glyphs, with the wood showing through around the text. */
final class GoldLettering extends TextView {
    private final float density;
    private LinearGradient foil;
    private float lastTop, lastBottom;

    GoldLettering(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        setTextColor(0xff4b290e);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (getLayout() == null || getLayout().getLineCount() == 0) {
            super.onDraw(canvas);
            return;
        }
        TextPaint ink = getPaint();
        int baseline = getLayout().getLineBaseline(0);
        float top = baseline + ink.ascent() * .85f, bottom = baseline + ink.descent() * .25f;
        if (foil == null || top != lastTop || bottom != lastBottom) {
            foil = new LinearGradient(0, top, 0, bottom,
                    new int[]{0xffffefa9, 0xffe1b242, 0xffffe495, 0xffad721f},
                    new float[]{0, .38f, .62f, 1}, Shader.TileMode.CLAMP);
            lastTop = top;
            lastBottom = bottom;
        }
        ink.setStyle(Paint.Style.FILL_AND_STROKE);
        ink.setStrokeWidth(density * .85f);
        super.onDraw(canvas);
        ink.setStyle(Paint.Style.FILL);
        ink.setShader(foil);
        super.onDraw(canvas);
        ink.setShader(null);
    }
}
