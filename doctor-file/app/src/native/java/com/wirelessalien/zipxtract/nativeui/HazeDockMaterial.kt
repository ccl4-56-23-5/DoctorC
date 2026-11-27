// Haze 2.1.0 shader programs: copyright 2025, Christopher Banes and Haze contributors.
package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.wirelessalien.zipxtract.R

@RequiresApi(33)
internal class HazeDockMaterial(context: Context, private val density: Float) {
    private val palette = Palette(context)
    val containerColor = palette.surface
    private val optic = RuntimeShader(context.shaderSource(R.raw.haze_glass_optical))
    private val rim = RuntimeShader(context.shaderSource(R.raw.haze_glass_rim))
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = rim }
    private val blur = RenderEffect.createBlurEffect(BLUR_DP * density, BLUR_DP * density, Shader.TileMode.CLAMP)

    init {
        val dark = palette.dark
        optic.setFloatUniform("refractionStrength", 0.7f)
        optic.setFloatUniform("refractionFoldStrength", 0f)
        optic.setFloatUniform("refractionScale", 48f * density)
        optic.setFloatUniform("edgeRefractionWidth", 20f * density)
        optic.setFloatUniform("chromaticAberrationStrength", 0f)
        optic.setFloatUniform("chromaticAberrationMode", 0f)
        optic.setFloatUniform("surfaceProfile", 0f)
        optic.setFloatUniform("contentNormalBlend", .15f)
        optic.setFloatUniform("fresnelExponent", 3f)
        optic.setFloatUniform("ambientResponse", if (dark) .08f else .15f)
        optic.setFloatUniform("contrast", if (dark) .08f else 0f)
        optic.setFloatUniform("whitePoint", if (dark) -.22f else .55f)
        optic.setFloatUniform("chromaMultiplier", if (dark) 1.1f else 1.5f)
        optic.setColorUniform("tintColor", ColorUtils.setAlphaComponent(containerColor, TINT_ALPHA))
        rim.setFloatUniform("specularIntensity", if (dark) .38f else .48f)
        rim.setFloatUniform("specularExponent", 24f)
        rim.setColorUniform("edgeShadow", Color.argb(if (dark) 82 else 36, 0, 0, 0))
    }

    fun effect(width: Int, height: Int, padding: Int): RenderEffect {
        val w = width.toFloat()
        val h = height.toFloat()
        val edge = minOf(w, h) / 2f
        optic.setFloatUniform("sampleSize", w + 2 * padding, h + 2 * padding)
        optic.setFloatUniform("materialOrigin", padding.toFloat(), padding.toFloat())
        optic.setFloatUniform("materialSize", w, h)
        optic.setFloatUniform("cornerRadii", edge, edge, edge, edge)
        optic.setFloatUniform("sampleStep", 2f)
        optic.setFloatUniform("edgeSoftness", density)
        optic.setFloatUniform("refractionHeight", minOf(w, h) * .15f)

        rim.setFloatUniform("materialOrigin", 0f, 0f)
        rim.setFloatUniform("materialSize", w, h)
        rim.setFloatUniform("cornerRadii", edge, edge, edge, edge)
        rim.setFloatUniform("sampleStep", 2f)
        rim.setFloatUniform("edgeSoftness", density)
        rim.setFloatUniform("lightPosition", w / 2f, h / 2f)

        // Haze GlassOptics.depth = 0 takes its input as-is. The native dock provides the
        // requested 16 dp diffusion on the separate backdrop before this glass stage.
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(optic, "content"), blur)
    }

    fun drawRim(canvas: Canvas, width: Int, height: Int) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), rimPaint)
    }

    private fun Context.shaderSource(id: Int) = resources.openRawResource(id).bufferedReader().use { it.readText() }

    companion object {
        private const val BLUR_DP = 16f
        private const val TINT_ALPHA = 51 // 20% of 255
    }
}
