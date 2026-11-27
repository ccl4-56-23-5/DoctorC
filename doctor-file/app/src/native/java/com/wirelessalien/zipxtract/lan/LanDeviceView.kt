package com.wirelessalien.zipxtract.lan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.nativeui.Palette
import com.wirelessalien.zipxtract.nativeui.accessible
import com.wirelessalien.zipxtract.nativeui.dp
import com.wirelessalien.zipxtract.nativeui.label
import java.util.Locale

internal fun lanDeviceButton(context: Context, device: LanDevice, link: LanLink, directUsb: Boolean = false, click: () -> Unit): View = with(context) {
    val palette = Palette(this)
    val wired = link == LanLink.WIRED
    val button = object : FrameLayout(this) {
        override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
    }.apply {
        background = palette.pressable(palette.surface, dp(20).toFloat())
        isFocusable = true
        accessible(listOf(device.shape.label, device.brand, device.model, if (directUsb) "USB 有线" else if (wired) "有线连接" else "无线连接")
            .filter { it.isNotBlank() }.joinToString(" "))
        setOnClickListener { click() }
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(14), dp(8), dp(14))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    val graphic = FrameLayout(this)
    graphic.addView(DeviceGlyph(this, device.shape, palette.text), FrameLayout.LayoutParams(dp(56), dp(54), Gravity.CENTER))
    val logo = brandLogo(device.brand)
    if (logo != 0) {
        graphic.addView(ImageView(this).apply {
            setImageResource(logo)
            setColorFilter(palette.text)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = palette.background(palette.page, dp(10).toFloat())
        }, FrameLayout.LayoutParams(dp(31), dp(26), Gravity.BOTTOM or Gravity.END))
    } else if (device.brand.isNotBlank()) {
        graphic.addView(label(device.brand.take(2).uppercase(Locale.ROOT), 10f, palette.text, true).apply {
            gravity = Gravity.CENTER
            background = palette.background(palette.page, dp(10).toFloat())
        }, FrameLayout.LayoutParams(dp(30), dp(23), Gravity.BOTTOM or Gravity.END))
    }
    val model = label(device.model, 14f, palette.text, true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val type = label(listOf(device.shape.label, device.brand).filter { it.isNotBlank() }.joinToString(" · "),
        12f, palette.secondary).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    listOf(graphic, model, type).forEach { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
    content.addView(graphic, LinearLayout.LayoutParams(dp(74), dp(62)))
    content.addView(model, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
    content.addView(type, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
    button.addView(content, FrameLayout.LayoutParams(-1, -2))
    if (wired) button.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_wired_link)
        setColorFilter(palette.blue)
        setPadding(dp(5), dp(5), dp(5), dp(5))
        background = palette.background(palette.selected, dp(12).toFloat())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END).apply {
        topMargin = dp(8)
        marginEnd = dp(8)
    })
    button
}

private fun brandLogo(brand: String) = when (brand.lowercase(Locale.ROOT).trim()) {
    "xiaomi", "redmi", "poco" -> R.drawable.brand_xiaomi
    "samsung" -> R.drawable.brand_samsung
    "lenovo", "legion", "thinkpad" -> R.drawable.brand_lenovo
    "huawei" -> R.drawable.brand_huawei
    "honor" -> R.drawable.brand_honor
    "oppo" -> R.drawable.brand_oppo
    "vivo", "iqoo" -> R.drawable.brand_vivo
    "oneplus" -> R.drawable.brand_oneplus
    "google", "pixel" -> R.drawable.brand_google
    "apple" -> R.drawable.brand_apple
    "asus", "rog" -> R.drawable.brand_asus
    "acer" -> R.drawable.brand_acer
    "hp", "hewlett-packard" -> R.drawable.brand_hp
    "dell" -> R.drawable.brand_dell
    "motorola", "moto" -> R.drawable.brand_motorola
    "sony" -> R.drawable.brand_sony
    else -> 0
}

private class DeviceGlyph(context: Context, private val shape: LanShape, tint: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val outline = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.scale(width / 56f, height / 54f)
        fun frame(left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
            outline.set(left, top, right, bottom)
            canvas.drawRoundRect(outline, radius, radius, paint)
        }
        when (shape) {
            LanShape.PHONE -> {
                frame(15f, 5f, 40f, 48f, 6f)
                canvas.drawLine(25f, 42f, 30f, 42f, paint)
            }
            LanShape.TABLET -> {
                frame(3f, 10f, 52f, 44f, 6f)
                canvas.drawLine(24f, 39f, 31f, 39f, paint)
            }
            LanShape.PC -> {
                frame(3f, 7f, 52f, 38f, 4f)
                canvas.drawLine(27.5f, 38f, 27.5f, 47f, paint)
                canvas.drawLine(19f, 47f, 36f, 47f, paint)
            }
            LanShape.FOLDABLE -> {
                frame(5f, 7f, 50f, 46f, 6f)
                canvas.drawLine(27.5f, 8f, 27.5f, 45f, paint)
            }
        }
        canvas.restore()
    }
}
