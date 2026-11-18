package io.legado.app.ui.book.read.config

import android.graphics.drawable.GradientDrawable
import android.view.View
import io.legado.app.utils.dpToPx

/** 底部弹层背景：同色、顶部 12dp 圆角（仅展示） */
fun View.setRdSheetBackground(color: Int) {
    val r = 12.dpToPx().toFloat()
    background = GradientDrawable().apply {
        setColor(color)
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }
}
