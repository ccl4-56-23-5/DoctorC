// Haze 2.1.0 shader programs: copyright 2025, Christopher Banes and Haze contributors.
package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import com.wirelessalien.zipxtract.R

@RequiresApi(33)
internal class HazeDockMaterial(context: Context, private val density: Float) {
    private val palette = Palette(context)
    val containerColor = palette.surface
    private val optic = RuntimeShader(context.shaderSource(R.raw.haze_glass_optical)).apply { configureOptics() }
    private val rim = RuntimeShader(context.shaderSource(R.raw.haze_glass_rim))
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = rim }

    init {
        val dark = palette.dark
        rim.setFloatUniform("specularIntensity", if (dark) .14f else .18f)
        rim.setFloatUniform("specularExponent", 18f)
        rim.setColorUniform("edgeShadow", if (dark) 0x26000000 else 0x24445874)
    }

    private fun RuntimeShader.configureOptics() {
        val dark = palette.dark
        setFloatUniform("refractionStrength", .96f)
        setFloatUniform("refractionFoldStrength", 0f)
        setFloatUniform("refractionScale", 56f * density)
        setFloatUniform("edgeRefractionWidth", 22f * density)
        setFloatUniform("chromaticAberrationStrength", 0f)
        setFloatUniform("chromaticAberrationMode", 0f)
        setFloatUniform("surfaceProfile", 0f)
        setFloatUniform("contentNormalBlend", .15f)
        setFloatUniform("fresnelExponent", 3f)
        setFloatUniform("ambientResponse", if (dark) .11f else .17f)
        setFloatUniform("contrast", if (dark) .08f else 0f)
        setFloatUniform("whitePoint", if (dark) -.22f else .43f)
        setFloatUniform("chromaMultiplier", if (dark) 1.1f else 1.5f)
        setColorUniform("tintColor", ColorUtils.setAlphaComponent(containerColor, TINT_ALPHA))
    }

    fun effect(width: Int, height: Int, padding: Int): RenderEffect {
        val w = width.toFloat()
        val h = height.toFloat()
        val edge = minOf(w, h) / 2f
        with(optic) {
            setFloatUniform("sampleSize", w + 2 * padding, h + 2 * padding)
            setFloatUniform("materialOrigin", padding.toFloat(), padding.toFloat())
            setFloatUniform("materialSize", w, h)
            setFloatUniform("cornerRadii", edge, edge, edge, edge)
            setFloatUniform("sampleStep", 2f)
            setFloatUniform("edgeSoftness", density)
            setFloatUniform("refractionHeight", minOf(w, h) * .15f)
        }

        rim.setFloatUniform("materialOrigin", 0f, 0f)
        rim.setFloatUniform("materialSize", w, h)
        rim.setFloatUniform("cornerRadii", edge, edge, edge, edge)
        rim.setFloatUniform("sampleStep", 2f)
        rim.setFloatUniform("edgeSoftness", 1.15f * density)
        // The light must be outside the silhouette. An interior light faces the
        // inward normals on every side and makes a bright ring on flat backgrounds.
        rim.setFloatUniform("lightPosition", -h, -h)

        return RenderEffect.createRuntimeShaderEffect(optic, "content")
    }

    fun drawRim(canvas: Canvas, width: Int, height: Int) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), rimPaint)
    }

    private fun Context.shaderSource(id: Int) = resources.openRawResource(id).bufferedReader().use { it.readText() }

    companion object {
        private const val TINT_ALPHA = 51 // 20% of 255
    }
}
