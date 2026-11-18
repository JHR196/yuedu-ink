package io.legado.app.lib.theme.view

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.legado.app.R
import io.legado.app.databinding.ViewNavigationBadgeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.Selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.ui.widget.text.BadgeView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.getCompatColor

class ThemeBottomNavigationVIew(context: Context, attrs: AttributeSet) :
    BottomNavigationView(context, attrs) {

    init {
        val bgColor = context.bottomBackground
        // 悬浮胶囊：全圆角 + 0.5dp 细描边，颜色跟随主题 bottomBackground
        val density = resources.displayMetrics.density
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 32f * density
            setColor(bgColor)
            setStroke(
                (0.5f * density).toInt().coerceAtLeast(1),
                context.getCompatColor(R.color.ink_line)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val shadow = ColorUtils.withAlpha(Color.BLACK, 0.35f)
            outlineAmbientShadowColor = shadow
            outlineSpotShadowColor = shadow
        }
        val textColor = if (ColorUtils.isColorLight(bgColor)) {
            context.getCompatColor(R.color.ink_text_faint)
        } else {
            ColorUtils.withAlpha(Color.WHITE, 0.55f)
        }
        val colorStateList = Selector.colorBuild()
            .setDefaultColor(textColor)
            .setSelectedColor(ThemeStore.accentColor(context)).create()
        itemIconTintList = colorStateList
        itemTextColor = colorStateList
        itemActiveIndicatorColor = ColorStateList.valueOf(
            ColorUtils.withAlpha(ThemeStore.accentColor(context), 0.14f)
        )

        if (AppConfig.isEInkMode) {
            isItemHorizontalTranslationEnabled = false
            itemBackground = ColorDrawable(Color.TRANSPARENT)
            isItemActiveIndicatorEnabled = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(this, null)
    }

    fun addBadgeView(index: Int): BadgeView {
        //获取底部菜单view
        val menuView = getChildAt(0) as ViewGroup
        //获取第index个itemView
        val itemView = menuView.getChildAt(index) as ViewGroup
        val badgeBinding = ViewNavigationBadgeBinding.inflate(LayoutInflater.from(context))
        itemView.addView(badgeBinding.root)
        return badgeBinding.viewBadge
    }

}