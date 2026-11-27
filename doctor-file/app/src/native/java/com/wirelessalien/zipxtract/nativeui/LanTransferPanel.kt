package com.wirelessalien.zipxtract.nativeui

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.wirelessalien.zipxtract.lan.LanTask

internal class LanTransferPanel(
    context: Context,
    private val colors: Palette,
    private val cancel: (String) -> Unit,
    private val dismiss: (String) -> Unit
) : LinearLayout(context) {
    private val outgoing = TaskRow(true)
    private val incoming = TaskRow(false)

    init {
        orientation = VERTICAL
        visibility = GONE
        addView(outgoing, LayoutParams(-1, -2))
        addView(incoming, LayoutParams(-1, -2))
    }

    fun render(sending: LanTask?, receiving: LanTask?) {
        outgoing.render(sending)
        incoming.render(receiving)
        visibility = if (sending != null || receiving != null) VISIBLE else GONE
    }

    private inner class TaskRow(private val sending: Boolean) : LinearLayout(context) {
        private val title = context.label("", 14f, colors.text, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val percent = context.label("", 13f, colors.text, true)
        private val status = context.label("", 12f, colors.secondary).apply {
            ellipsize = TextUtils.TruncateAt.END
        }
        private val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressBackgroundTintList = ColorStateList.valueOf(colors.line)
            progressTintList = ColorStateList.valueOf(colors.blue)
            indeterminateTintList = ColorStateList.valueOf(colors.blue)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private var task: LanTask? = null
        private val action = context.iconButton("close", "关闭传输结果") {
            task?.let { if (it.active) cancel(it.id) else dismiss(it.id) }
        }

        init {
            visibility = GONE
            gravity = Gravity.CENTER_VERTICAL
            val content = LinearLayout(context).apply {
                orientation = VERTICAL
                background = colors.background(colors.surface, context.dp(18).toFloat())
                setPadding(context.dp(14), context.dp(7), context.dp(6), context.dp(7))
            }
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val texts = LinearLayout(context).apply { orientation = VERTICAL }
            val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(title, LayoutParams(0, -2, 1f))
            heading.addView(percent, LayoutParams(-2, -2).apply { marginStart = context.dp(8) })
            texts.addView(heading, LayoutParams(-1, -2))
            texts.addView(status, LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
            row.addView(texts, LayoutParams(0, -2, 1f))
            row.addView(action, LayoutParams(context.dp(48), context.dp(48)).apply { marginStart = context.dp(4) })
            content.addView(row, LayoutParams(-1, -2))
            content.addView(progressBar, LayoutParams(-1, context.dp(6)).apply {
                marginEnd = context.dp(8)
                topMargin = context.dp(3)
            })
            addView(content, LayoutParams(-1, -2).apply { topMargin = context.dp(4) })
        }

        fun render(value: LanTask?) {
            val previous = task
            task = value
            visibility = if (value == null) GONE else VISIBLE
            if (value == null) return
            if (previous?.title != value.title) title.text = value.title
            if (previous?.progress?.text != value.progress.text) status.text = value.progress.text
            if (previous?.active != value.active) status.maxLines = if (value.active) 1 else 4
            val progress = value.progress.percent
            if (previous?.progress?.percent != progress || previous?.id != value.id) {
                percent.text = progress?.let { "$it%" }.orEmpty()
                percent.visibility = if (progress == null) GONE else VISIBLE
                progressBar.isIndeterminate = value.active && progress == null
                if (progress != null) progressBar.setProgress(progress, previous?.id == value.id)
            }
            progressBar.visibility = if (value.active || progress != null) VISIBLE else GONE
            action.visibility = if (!value.active || sending && !value.cancelling) VISIBLE else GONE
            action.contentDescription = if (value.active) "取消发送" else "关闭传输结果"
        }
    }
}
