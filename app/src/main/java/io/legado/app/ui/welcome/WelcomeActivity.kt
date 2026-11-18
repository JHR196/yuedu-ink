package io.legado.app.ui.welcome

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.view.postDelayed
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppConst
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityWelcomeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.fullScreen
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.app.utils.windowSize

open class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>() {

    override val binding by viewBinding(ActivityWelcomeBinding::inflate)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.tvGzh.text = AppConst.appInfo.versionName.takeIf { it.isNotBlank() }?.let { "v$it" }
        // 避免从桌面启动程序后，会重新实例化入口类的activity
        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            finish()
        } else {
            playEntrance()
            binding.root.postDelayed(600) { startMainActivity() }
        }
    }

    /** 入场：图标淡入并 0.94→1 放大，文字依次淡入（120ms 间隔）；系统关闭动画时跳过 */
    private fun playEntrance() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) return
        binding.ivBook.run {
            alpha = 0f
            scaleX = 0.94f
            scaleY = 0.94f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).start()
        }
        listOf<View>(binding.tvTitle, binding.vwTitleLine, binding.tvSubTitle)
            .forEachIndexed { i, v ->
                v.alpha = 0f
                v.animate().alpha(1f).setStartDelay(120L * (i + 1)).setDuration(200).start()
            }
    }

    override fun setupSystemBar() {
        fullScreen()
        setStatusBarColorAuto(backgroundColor, true, fullScreen)
        upNavigationBarColor()
    }

    override fun upBackgroundImage() {
        if (getPrefBoolean(PreferKey.customWelcome)) {
            kotlin.runCatching {
                when (ThemeConfig.getTheme()) {
                    Theme.Dark -> getPrefString(PreferKey.welcomeImageDark)?.let { path ->
                        val size = windowManager.windowSize
                        BitmapUtils.decodeBitmap(path, size.widthPixels, size.heightPixels).let {
                            binding.tvLegado.visible(AppConfig.welcomeShowTextDark)
                            binding.ivBook.visible(AppConfig.welcomeShowIconDark)
                            binding.tvGzh.visible(AppConfig.welcomeShowTextDark)
                            binding.root.background = null
                            window.decorView.background = BitmapDrawable(resources, it)
                            return
                        }
                    }

                    else -> getPrefString(PreferKey.welcomeImage)?.let { path ->
                        val size = windowManager.windowSize
                        BitmapUtils.decodeBitmap(path, size.widthPixels, size.heightPixels).let {
                            binding.tvLegado.visible(AppConfig.welcomeShowText)
                            binding.ivBook.visible(AppConfig.welcomeShowIcon)
                            binding.tvGzh.visible(AppConfig.welcomeShowText)
                            binding.root.background = null
                            window.decorView.background = BitmapDrawable(resources, it)
                            return
                        }
                    }
                }
            }
        }
        super.upBackgroundImage()
    }

    private fun startMainActivity() {
        startActivity<MainActivity>()
        if (getPrefBoolean(PreferKey.defaultToRead) && appDb.bookDao.lastReadBook != null) {
            startActivity<ReadBookActivity>()
        }
        finish()
    }

}

class Launcher1 : WelcomeActivity()
class Launcher2 : WelcomeActivity()
class Launcher3 : WelcomeActivity()
class Launcher4 : WelcomeActivity()
class Launcher5 : WelcomeActivity()
class Launcher6 : WelcomeActivity()