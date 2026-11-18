package io.legado.app.ui.main.bookshelf.shelf3d

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextPaint
import android.util.AttributeSet
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.BookCover
import io.legado.app.utils.dpToPx
import io.legado.app.utils.spToPx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 书柜总览：书架双指合拢后看到的整座胡桃木书柜（顶冠齿饰、壁柱凹槽、金钉、托脚）。
 * 一层一类，层板前沿嵌金字类名与册数；书一律书脊朝外、同样大小整齐排列，
 * 书脊用书自己的封面（含自定义封面），没有就用默认封面；书名与细线沿用放大后立体书架的书脊样式。
 * 上下滑动翻层，层内左右拖动；点一层或在该层双指张开，放大进入那一类。
 */
class ShelfCabinetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    class Layer(val name: String, val books: List<Shelf3DView.Item>)

    interface Callback {
        /** 选中第 index 层，放大动画开始时回调 */
        fun onPick(index: Int)

        /** 动画结束、书柜已隐藏 */
        fun onHidden() {}
    }

    companion object {
        /** 按分组读出每层的书（最近阅读在前） */
        suspend fun loadLayers(groups: List<BookGroup>): List<Layer> = withContext(Dispatchers.IO) {
            groups.map { g ->
                val list = appDb.bookDao.flowByGroup(g.groupId).first()
                    .sortedByDescending { it.durChapterTime }
                Layer(g.groupName, Shelf3DItems.of(list))
            }
        }
    }

    var callback: Callback? = null

    private val dark = ColorUtils.calculateLuminance(context.backgroundColor) < 0.5
    private val accent = context.accentColor
    private val bgColor = context.backgroundColor
    private val inkColor = if (dark) 0xFFE9E1D3.toInt() else 0xFF2B2722.toInt()
    private val subColor = if (dark) 0xFF8C847A.toInt() else 0xFF8A8174.toInt()

    // 胡桃木书柜
    private val woodLight = if (dark) 0xFF6E452A.toInt() else 0xFF8C5B36.toInt()
    private val woodDark = if (dark) 0xFF3A2213.toInt() else 0xFF55331C.toInt()
    private val gold = 0xFFD8B978.toInt()
    private val goldDeep = 0xFF9C7B42.toInt()
    private val lightColor = 0xFFFFE2B0.toInt()

    private var layers: List<Layer> = emptyList()
    private var current = -1

    // ---------- 几何 ----------
    private val sideM = 12f.dpToPx()
    private val frame = 16f.dpToPx()      // 两侧壁柱宽
    private val crownH = 30f.dpToPx()
    private val plinthH = 18f.dpToPx()
    private val feetH = 8f.dpToPx()
    private val faceH = 18f.dpToPx()
    private val topGap = 14f.dpToPx()
    private val spineW = 26f.dpToPx()
    private val spineGap = 1.5f.dpToPx()
    private val bookPad = 10f.dpToPx()
    private val navReserve = 96f.dpToPx()
    private var headTop = 0f
    private var layerH = 0f
    private var top0 = 0f
    private var scrollOff = 0f
    private var maxScroll = 0f
    private var hOff = FloatArray(0)
    private var hMax = FloatArray(0)

    // ---------- 动画 ----------
    private var zoom = 1f
    private var pivotY = 0f
    private var fade = 1f
    private var anim: ValueAnimator? = null
    private var flingAnim: ValueAnimator? = null

    // ---------- 画笔 ----------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint()
    private val vignette = Paint()
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSize = 11.5f.spToPx()
        letterSpacing = 0.3f
        color = 0xFFD8B978.toInt()
        setShadowLayer(1f.dpToPx(), 0f, 0.6f.dpToPx(), 0xAA000000.toInt())
    }
    private val countPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 9f.spToPx()
        letterSpacing = 0.12f
        color = 0xCCD8B978.toInt()
    }
    private val spineText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSize = 15f.spToPx()
        letterSpacing = 0.2f
        color = inkColor
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f.spToPx()
        letterSpacing = 0.06f
        color = subColor
    }
    private val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SERIF
        textSize = 12f.spToPx()
        letterSpacing = 0.2f
        textAlign = Paint.Align.CENTER
        color = if (dark) 0xFF5E574E.toInt() else 0xFFB8AD9C.toInt()
    }
    private val rect = RectF()

    // ---------- 封面 ----------
    private val requested = HashSet<String>()
    private val coverW = 64f.dpToPx().toInt()
    private val coverH = (coverW / 0.72f).toInt()
    private val covers = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private var defaultCover: Bitmap? = null
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()

    private val lowMotion: Boolean
        get() = AppConfig.isEInkMode ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())

    init {
        isClickable = true
        isFocusable = true
    }

    // ================= 对外 =================

    val isShowing get() = visibility == VISIBLE && anim?.isRunning != true

    /** 展示书柜：从当前层缩小出来；currentIndex<0 表示没有当前层（如书架根目录） */
    fun show(data: List<Layer>, currentIndex: Int) {
        layers = data
        current = if (currentIndex in data.indices) currentIndex else -1
        hOff = FloatArray(data.size)
        hMax = FloatArray(data.size)
        visibility = VISIBLE
        relayout()
        if (maxScroll > 0f && current >= 0) {
            val mid = (headTop + height - navReserve) / 2f
            scrollOff = (top0 + current * layerH + layerH / 2 - mid).coerceIn(0f, maxScroll)
        }
        pivotY = if (current >= 0) layerCenter(current) else (headTop + height - navReserve) / 2f
        if (lowMotion) {
            zoom = 1f; fade = 1f; invalidate(); return
        }
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                zoom = 2.2f - 1.2f * t
                fade = t
                invalidate()
            }
            start()
        }
    }

    /** 放大进入第 index 层并隐藏 */
    fun enter(index: Int) {
        if (index !in layers.indices || anim?.isRunning == true) return
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        current = index
        callback?.onPick(index)
        pivotY = layerCenter(index)
        if (lowMotion) {
            finishHide(); return
        }
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                val t = it.animatedValue as Float
                zoom = 1f + 1.5f * t
                fade = 1f - t
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishHide()
            })
            start()
        }
    }

    /** 返回键：回到当前层；没有当前层就直接收起 */
    fun dismiss() {
        if (current >= 0) enter(current) else finishHide()
    }

    private fun finishHide() {
        visibility = GONE
        zoom = 1f
        fade = 1f
        callback?.onHidden()
    }

    // ================= 布局 =================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
        vignette.shader = RadialGradient(
            w / 2f, h * 0.42f, max(w, h) * 0.8f,
            intArrayOf(0x00000000, 0x00000000, if (dark) 0x70000000 else 0x1E4A3A20),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        glowPaint.shader = RadialGradient(
            w / 2f, h * 0.12f, max(w, h) * 0.75f,
            intArrayOf(if (dark) 0x22FFD7A0 else 0x55FFFFFF, 0x00000000),
            null, Shader.TileMode.CLAMP
        )
    }

    private fun relayout() {
        if (width == 0 || height == 0) return
        val n = max(1, layers.size)
        headTop = paddingTop + 44f.dpToPx()
        val chrome = crownH + plinthH + feetH
        val avail = height - navReserve - headTop - chrome - 8f.dpToPx()
        layerH = (avail / n).coerceIn(150f.dpToPx(), 192f.dpToPx())
        top0 = headTop + crownH + max(0f, (avail - n * layerH) / 2f)
        maxScroll = max(0f, top0 + n * layerH + plinthH + feetH + 16f.dpToPx() - (height - navReserve))
        scrollOff = scrollOff.coerceIn(0f, maxScroll)
        val innerW = innerRight() - innerLeft() - bookPad * 2
        for (i in layers.indices) {
            hMax[i] = max(0f, layers[i].books.size * (spineW + spineGap) - innerW)
            hOff[i] = hOff[i].coerceIn(0f, hMax[i])
        }
    }

    private fun innerLeft() = sideM + frame
    private fun innerRight() = width - sideM - frame
    private fun layerTop(i: Int) = top0 + i * layerH - scrollOff
    private fun layerCenter(i: Int) = layerTop(i) + layerH / 2f

    // ================= 绘制 =================

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val layered = fade < 0.999f
        if (layered) canvas.saveLayerAlpha(0f, 0f, w, h, (fade * 255).toInt())
        canvas.drawRect(0f, 0f, w, h, solid(bgColor))
        canvas.drawRect(0f, 0f, w, h, glowPaint)
        canvas.save()
        if (zoom <= 1.001f) canvas.clipRect(0f, headTop - 4f.dpToPx(), w, h)
        canvas.scale(zoom, zoom, w / 2f, pivotY)
        drawCase(canvas)
        canvas.restore()
        canvas.drawRect(0f, 0f, w, h, vignette)
        drawHeader(canvas)
        if (layered) canvas.restore()
    }

    private fun drawHeader(canvas: Canvas) {
        val y = paddingTop + 27f.dpToPx()
        val x = sideM + 2f.dpToPx()
        canvas.drawText("书柜", x, y, headPaint)
        val total = layers.sumOf { it.books.size }
        val tx = x + headPaint.measureText("书柜") + 12f.dpToPx()
        canvas.drawText("${layers.size} 类  ·  $total 册", tx, y, hintPaint)
        val tip = "点一层进入"
        canvas.drawText(tip, width - sideM - hintPaint.measureText(tip), y, hintPaint)
    }

    private fun solid(c: Int): Paint {
        fill.shader = null
        fill.color = c
        return fill
    }

    private fun grad(s: Shader): Paint {
        fill.color = Color.BLACK
        fill.shader = s
        return fill
    }

    // ---------- 木纹 ----------

    /** 程序生成的胡桃木纹（横向纹理），镜像平铺 */
    private val woodTex: Bitmap by lazy {
        val w = 384
        val h = 96
        val rnd = java.util.Random(11)
        val ph = FloatArray(4) { rnd.nextFloat() * 6.28f }
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val warp = sin(x * 0.019f + ph[0]) * 6f + sin(x * 0.061f + ph[1]) * 1.8f
            val ring = sin((y + warp) * 0.42f + sin(x * 0.011f + ph[2]) * 1.4f)
            val t = (((ring * 0.5f + 0.5f).pow(4)) * 0.6f + rnd.nextFloat() * 0.12f +
                    (sin(y * 0.09f + ph[3]) * 0.5f + 0.5f) * 0.18f).coerceIn(0f, 1f)
            px[y * w + x] = ColorUtils.blendARGB(woodLight, woodDark, t)
        }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
    private val woodH by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(woodTex, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
        }
    }
    private val woodV by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(woodTex, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).apply {
                setLocalMatrix(Matrix().apply { setRotate(90f) })
            }
        }
    }

    /** 木料上叠一层明暗 */
    private fun woodRect(
        canvas: Canvas, l: Float, t: Float, r: Float, b: Float,
        vertical: Boolean, shadeA: Int, shadeB: Int
    ) {
        canvas.drawRect(l, t, r, b, if (vertical) woodV else woodH)
        canvas.drawRect(
            l, t, r, b, grad(
                if (vertical) LinearGradient(l, 0f, r, 0f, shadeA, shadeB, Shader.TileMode.CLAMP)
                else LinearGradient(0f, t, 0f, b, shadeA, shadeB, Shader.TileMode.CLAMP)
            )
        )
    }

    private fun hairline(canvas: Canvas, l: Float, y: Float, r: Float, c: Int, h: Float = 1f) {
        canvas.drawRect(l, y, r, y + h, solid(c))
    }

    // ---------- 书柜 ----------

    private fun drawCase(canvas: Canvas) {
        if (layers.isEmpty()) return
        val l = sideM
        val r = width - sideM
        val bodyTop = top0 - scrollOff
        val bodyBottom = layerTop(layers.size)
        // 落地柔影
        canvas.drawRect(
            l - 30f.dpToPx(), bodyBottom, r + 30f.dpToPx(), bodyBottom + plinthH + feetH + 34f.dpToPx(),
            grad(
                RadialGradient(
                    width / 2f, bodyBottom + plinthH + feetH, (r - l) * 0.62f,
                    if (dark) 0x77000000 else 0x40402810, 0x00000000, Shader.TileMode.CLAMP
                )
            )
        )
        for (i in layers.indices) {
            val t = layerTop(i)
            if (t > height || t + layerH < 0) continue
            drawLayer(canvas, i, t)
        }
        drawPilaster(canvas, l, bodyTop, l + frame, bodyBottom)
        drawPilaster(canvas, r - frame, bodyTop, r, bodyBottom)
        drawCrown(canvas, l, bodyTop - crownH, r, bodyTop)
        drawPlinth(canvas, l, bodyBottom, r)
    }

    /** 顶冠：出檐盖板、凹弧、齿饰带、金线、中央椭圆饰牌 */
    private fun drawCrown(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val o = 7f.dpToPx()
        val capH = 7f.dpToPx()
        val coveH = 6f.dpToPx()
        val dentH = 8f.dpToPx()
        woodRect(canvas, l - o, t, r + o, t + capH, false, 0x22FFFFFF, 0x33000000)
        hairline(canvas, l - o, t, r + o, 0x40FFFFFF)
        hairline(canvas, l - o, t + capH - 1f, r + o, 0x66000000)
        var y = t + capH
        woodRect(canvas, l - o * 0.5f, y, r + o * 0.5f, y + coveH, false, 0x88000000.toInt(), 0x10000000)
        y += coveH
        woodRect(canvas, l, y, r, y + dentH, false, 0x55000000, 0x55000000)
        val dw = 5f.dpToPx()
        val dg = 3.5f.dpToPx()
        var x = l + frame * 0.5f
        while (x + dw < r - frame * 0.5f) {
            woodRect(canvas, x, y + 1f.dpToPx(), x + dw, y + dentH - 1.5f.dpToPx(), false, 0x26FFFFFF, 0x40000000)
            hairline(canvas, x, y + 1f.dpToPx(), x + dw, 0x33FFFFFF)
            x += dw + dg
        }
        y += dentH
        hairline(canvas, l, y, r, gold, 1.2f.dpToPx())
        y += 1.2f.dpToPx()
        woodRect(canvas, l, y, r, b, false, 0x10FFFFFF, 0x55000000)
        // 中央饰牌：金框椭圆，内嵌一颗金钉
        val cx = (l + r) / 2f
        val cy = t + capH + coveH + dentH / 2f
        rect.set(cx - 22f.dpToPx(), cy - 8f.dpToPx(), cx + 22f.dpToPx(), cy + 8f.dpToPx())
        canvas.drawOval(
            rect,
            grad(LinearGradient(0f, rect.top, 0f, rect.bottom, woodLight, woodDark, Shader.TileMode.CLAMP))
        )
        fill.shader = null
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 1.4f.dpToPx()
        fill.color = gold
        canvas.drawOval(rect, fill)
        fill.strokeWidth = 0.7f.dpToPx()
        fill.color = goldDeep
        rect.inset(3f.dpToPx(), 2.5f.dpToPx())
        canvas.drawOval(rect, fill)
        fill.style = Paint.Style.FILL
        drawStud(canvas, cx, cy, 2.4f.dpToPx())
    }

    /** 壁柱：竖纹木料、两道凹槽、柱头柱础、柱头一颗金钉 */
    private fun drawPilaster(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        woodRect(canvas, l, t, r, b, true, 0x30FFFFFF, 0x50000000)
        val w = r - l
        for (k in 1..2) {
            val x = l + w * k / 3f
            canvas.drawRect(
                x - 0.8f.dpToPx(), t + 14f.dpToPx(), x + 0.6f.dpToPx(), b - 12f.dpToPx(),
                solid(0x80000000.toInt())
            )
            canvas.drawRect(
                x + 0.6f.dpToPx(), t + 14f.dpToPx(), x + 1.3f.dpToPx(), b - 12f.dpToPx(),
                solid(0x26FFFFFF)
            )
        }
        val e = 2f.dpToPx()
        woodRect(canvas, l - e, t, r + e, t + 11f.dpToPx(), false, 0x30FFFFFF, 0x50000000)
        hairline(canvas, l - e, t + 11f.dpToPx() - 1.2f.dpToPx(), r + e, goldDeep, 1.2f.dpToPx())
        drawStud(canvas, (l + r) / 2f, t + 5.5f.dpToPx(), 3f.dpToPx())
        woodRect(canvas, l - e, b - 10f.dpToPx(), r + e, b, false, 0x30FFFFFF, 0x60000000)
        hairline(canvas, l - e, b - 10f.dpToPx(), r + e, goldDeep, 1.2f.dpToPx())
    }

    /** 底座与托脚 */
    private fun drawPlinth(canvas: Canvas, l: Float, t: Float, r: Float) {
        val o = 5f.dpToPx()
        woodRect(canvas, l - o, t, r + o, t + plinthH, false, 0x28FFFFFF, 0x66000000)
        hairline(canvas, l - o, t, r + o, gold, 1.2f.dpToPx())
        hairline(canvas, l - o, t + 5f.dpToPx(), r + o, 0x40000000)
        hairline(canvas, l - o, t + 6f.dpToPx(), r + o, 0x1AFFFFFF)
        val fy = t + plinthH
        val fw = 26f.dpToPx()
        for (side in 0..1) {
            val x0 = if (side == 0) l - o else r + o - fw
            val path = Path().apply {
                moveTo(x0, fy)
                lineTo(x0 + fw, fy)
                if (side == 0) {
                    lineTo(x0 + fw - 6f.dpToPx(), fy + feetH * 0.55f)
                    quadTo(x0 + fw * 0.4f, fy + feetH * 0.55f, x0 + 4f.dpToPx(), fy + feetH)
                    lineTo(x0, fy + feetH)
                } else {
                    lineTo(x0 + fw, fy + feetH)
                    lineTo(x0 + fw - 4f.dpToPx(), fy + feetH)
                    quadTo(x0 + fw * 0.6f, fy + feetH * 0.55f, x0 + 6f.dpToPx(), fy + feetH * 0.55f)
                }
                close()
            }
            canvas.drawPath(path, woodH)
            canvas.drawPath(path, solid(0x55000000))
        }
    }

    /** 金钉 */
    private fun drawStud(canvas: Canvas, cx: Float, cy: Float, rad: Float) {
        canvas.drawCircle(
            cx, cy, rad, grad(
                RadialGradient(
                    cx - rad * 0.3f, cy - rad * 0.3f, rad * 1.3f,
                    0xFFF3DFA8.toInt(), goldDeep, Shader.TileMode.CLAMP
                )
            )
        )
        canvas.drawCircle(cx, cy, rad * 0.35f, solid(0x66402A10))
    }

    private fun drawLayer(canvas: Canvas, i: Int, t: Float) {
        val layer = layers[i]
        val il = innerLeft()
        val ir = innerRight()
        val floor = t + layerH - faceH
        val cur = i == current
        // 背板：深色木板 + 竖向拼缝
        canvas.drawRect(il, t, ir, floor, woodH)
        canvas.drawRect(il, t, ir, floor, solid(if (dark) 0xB0120A05.toInt() else 0x8A1E1008.toInt()))
        var sx = il + 44f.dpToPx()
        while (sx < ir) {
            canvas.drawRect(sx, t, sx + 1f, floor, solid(0x50000000))
            canvas.drawRect(sx + 1f, t, sx + 2f, floor, solid(0x10FFFFFF))
            sx += 44f.dpToPx()
        }
        // 顶上一盏暖光
        canvas.drawRect(
            il, t, ir, floor, grad(
                RadialGradient(
                    (il + ir) / 2f, t, (ir - il) * 0.7f,
                    ColorUtils.setAlphaComponent(lightColor, if (cur) 0x55 else 0x26),
                    0x00000000, Shader.TileMode.CLAMP
                )
            )
        )
        // 上层板压下的阴影、两侧内壁暗角
        canvas.drawRect(
            il, t, ir, t + 18f.dpToPx(),
            grad(LinearGradient(0f, t, 0f, t + 18f.dpToPx(), 0x88000000.toInt(), 0x00000000, Shader.TileMode.CLAMP))
        )
        val sw = 12f.dpToPx()
        canvas.drawRect(
            il, t, il + sw, floor,
            grad(LinearGradient(il, 0f, il + sw, 0f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP))
        )
        canvas.drawRect(
            ir - sw, t, ir, floor,
            grad(LinearGradient(ir - sw, 0f, ir, 0f, 0x00000000, 0x66000000, Shader.TileMode.CLAMP))
        )
        // 书脊
        val x0 = il + bookPad
        val sh = floor - t - topGap
        canvas.save()
        canvas.clipRect(il, t, ir, floor)
        if (layer.books.isEmpty()) {
            emptyPaint.color = ColorUtils.setAlphaComponent(gold, 0x80)
            canvas.drawText("空  置", (il + ir) / 2f, t + (floor - t) / 2f + emptyPaint.textSize / 3, emptyPaint)
        }
        for ((k, item) in layer.books.withIndex()) {
            val bx = x0 - hOff[i] + k * (spineW + spineGap)
            if (bx + spineW < il || bx > ir) continue
            rect.set(bx, floor - sh, bx + spineW, floor)
            drawSpine(canvas, item, rect)
        }
        canvas.drawRect(
            il, floor - 6f.dpToPx(), ir, floor,
            grad(LinearGradient(0f, floor - 6f.dpToPx(), 0f, floor, 0x00000000, 0x66000000, Shader.TileMode.CLAMP))
        )
        canvas.restore()
        // 层内还有更多：右侧渐暗
        if (hMax[i] > 0f && hOff[i] < hMax[i] - 1f) {
            val fw = 30f.dpToPx()
            canvas.drawRect(
                ir - fw, t, ir, floor,
                grad(LinearGradient(ir - fw, 0f, ir, 0f, 0x00000000, 0xCC140A04.toInt(), Shader.TileMode.CLAMP))
            )
        }
        // 层板正面：木料 + 上沿倒角高光 + 下沿线脚，类名金字嵌入
        woodRect(canvas, il, floor, ir, floor + faceH, false, 0x18FFFFFF, 0x55000000)
        hairline(canvas, il, floor, ir, 0x55FFFFFF)
        hairline(canvas, il, floor + 2f.dpToPx(), ir, 0x33000000)
        hairline(
            canvas, il, floor + faceH - 3f.dpToPx(), ir,
            ColorUtils.setAlphaComponent(goldDeep, 0x99), 0.8f.dpToPx()
        )
        val ty = floor + faceH / 2f + namePaint.textSize * 0.3f
        var tx = il + bookPad
        if (cur) {
            val cy = floor + faceH / 2f - 0.5f.dpToPx()
            val d = 3f.dpToPx()
            val path = Path().apply {
                moveTo(tx + d, cy - d); lineTo(tx + 2 * d, cy); lineTo(tx + d, cy + d); lineTo(tx, cy); close()
            }
            canvas.drawPath(path, solid(accent))
            tx += 10f.dpToPx()
        }
        namePaint.color = if (cur) 0xFFF6E2B0.toInt() else gold
        canvas.drawText(layer.name, tx, ty, namePaint)
        val count = "%02d 册".format(layer.books.size)
        canvas.drawText(count, ir - bookPad - countPaint.measureText(count), ty, countPaint)
    }

    private fun drawSpine(canvas: Canvas, item: Shelf3DView.Item, r: RectF) {
        val bmp = cover(item)
        val stripW = (bmp.height * r.width() / r.height()).toInt().coerceIn(1, bmp.width)
        val bx = (bmp.width - stripW) / 2
        src.set(bx, 0, bx + stripW, bmp.height)
        canvas.drawBitmap(bmp, src, r, bmpPaint)
        val color = stripColor(item, bmp, src)
        // 圆脊：两侧暗、略偏左一线高光
        canvas.drawRect(
            r, grad(
                LinearGradient(
                    r.left, 0f, r.right, 0f,
                    intArrayOf(0x55000000, 0x22FFFFFF, 0x00000000, 0x66000000),
                    floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP
                )
            )
        )
        // 书头书根压暗
        val e = r.height() * 0.08f
        canvas.drawRect(
            r.left, r.top, r.right, r.top + e,
            grad(LinearGradient(0f, r.top, 0f, r.top + e, 0x40000000, 0x00000000, Shader.TileMode.CLAMP))
        )
        canvas.drawRect(
            r.left, r.bottom - e, r.right, r.bottom,
            grad(LinearGradient(0f, r.bottom - e, 0f, r.bottom, 0x00000000, 0x50000000, Shader.TileMode.CLAMP))
        )
        val lightInk = ColorUtils.calculateLuminance(color) < 0.45
        // 上下两组细线
        val band = if (lightInk) 0x99E2C68C.toInt() else 0x66000000
        val bw = max(1f, r.width() * 0.05f)
        for (f in floatArrayOf(0.06f, 0.078f, 0.922f, 0.94f)) {
            val y = r.top + r.height() * f
            canvas.drawRect(r.left + 1f, y, r.right - 1f, y + bw, solid(band))
        }
        // 竖排书名：只有没封面（用默认封面）的书才写，有封面的让封面自己说话
        if (covers.get(item.key) == null) {
            spineText.color = if (lightInk) 0xF2F3E6CC.toInt() else 0xE6251F18.toInt()
            spineText.setShadowLayer(
                1.4f.dpToPx(), 0f, 0.5f.dpToPx(),
                if (lightInk) 0xCC000000.toInt() else 0x99FFFFFF.toInt()
            )
            spineText.textSize = r.width() * 0.5f
            val step = spineText.textSize * 1.1f
            val top = r.top + r.height() * 0.12f
            val avail = r.height() * 0.76f
            val maxChars = max(1, (avail / step).toInt())
            val name = item.name.trim()
            val shown = if (name.length > maxChars) name.take(maxChars - 1) + "…" else name
            var y = top - spineText.fontMetrics.ascent
            for (ch in shown) {
                canvas.drawText(if (ch == '…') "︙" else ch.toString(), r.centerX(), y, spineText)
                y += step
            }
        }
        // 书与书之间的细缝
        canvas.drawRect(r.right - 1f, r.top, r.right, r.bottom, solid(0x66000000))
    }

    // ================= 封面 =================

    private val stripAvg = HashMap<String, Int>()

    /** 封面：有书源封面或自定义封面就用它，没有就用默认封面（与放大后的立体书架一致） */
    private fun cover(item: Shelf3DView.Item): Bitmap {
        covers.get(item.key)?.let { return it }
        val url = item.cover
        if (!AppConfig.useDefaultCover && !url.isNullOrBlank() && requested.add(item.key)) {
            loadCover(item, url)
        }
        return defaultCover ?: renderDefault().also { defaultCover = it }
    }

    /** 书脊那一条的平均色，用来决定书名用浅字还是深字 */
    private fun stripColor(item: Shelf3DView.Item, bmp: Bitmap, strip: Rect): Int {
        val key = item.key + "|" + bmp.generationId
        stripAvg[key]?.let { return it }
        val part = Bitmap.createBitmap(bmp, strip.left, strip.top, strip.width(), strip.height())
        val avg = Bitmap.createScaledBitmap(part, 1, 1, true).getPixel(0, 0)
        stripAvg[key] = avg
        return avg
    }

    private fun loadCover(item: Shelf3DView.Item, url: String) {
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, false)
        item.origin?.let { options = options.set(OkHttpModelLoader.sourceOriginOption, it) }
        kotlin.runCatching {
            ImageLoader.loadBitmap(context, url).apply(options).override(coverW, coverH).centerCrop()
                .into(object : CustomTarget<Bitmap>(coverW, coverH) {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        covers.put(item.key, resource)
                        invalidate()
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) = Unit
                    override fun onLoadCleared(placeholder: Drawable?) = Unit
                })
        }
    }

    /** 默认封面（沿用应用设置里的默认封面） */
    private fun renderDefault(): Bitmap {
        val out = Bitmap.createBitmap(coverW, coverH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val d = BookCover.defaultDrawable
        val dw = d.intrinsicWidth.takeIf { it > 0 } ?: coverW
        val dh = d.intrinsicHeight.takeIf { it > 0 } ?: coverH
        val scale = max(coverW / dw.toFloat(), coverH / dh.toFloat())
        val w = (dw * scale).toInt()
        val h = (dh * scale).toInt()
        val old = Rect(d.bounds)
        d.setBounds((coverW - w) / 2, (coverH - h) / 2, (coverW + w) / 2, (coverH + h) / 2)
        d.draw(c)
        d.bounds = old
        return out
    }

    // ================= 触摸 =================

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var mode = 0 // 0 未定 1 竖滚 2 横拖 3 双指 4 已触发
    private var dragLayer = -1
    private var pinchStart = 0f
    private var vx = 0f
    private var vy = 0f
    private var lastT = 0L

    private fun span(e: MotionEvent) =
        if (e.pointerCount < 2) 0f else hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun layerAt(y: Float): Int? {
        if (layers.isEmpty() || y < top0 - scrollOff) return null
        val i = ((y - (top0 - scrollOff)) / layerH).toInt()
        return if (i in layers.indices) i else null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (anim?.isRunning == true) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flingAnim?.cancel()
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                lastT = event.eventTime
                vx = 0f; vy = 0f
                mode = 0
                dragLayer = layerAt(event.y) ?: -1
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> if (mode < 3) {
                mode = 3
                pinchStart = span(event)
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == 3) {
                    val s = span(event)
                    if (pinchStart > 0f && s / pinchStart > 1.15f) {
                        mode = 4
                        val fy = (event.getY(0) + event.getY(1)) / 2f
                        (layerAt(fy) ?: current.takeIf { it >= 0 })?.let { enter(it) }
                    }
                    return true
                }
                if (mode == 4) return true
                val dx = event.x - downX
                val dy = event.y - downY
                if (mode == 0) {
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy) && dragLayer >= 0 && hMax[dragLayer] > 0f) mode = 2
                    else if (abs(dy) > touchSlop && abs(dy) > abs(dx)) mode = 1
                }
                val dt = max(1L, event.eventTime - lastT) / 1000f
                if (mode == 1 && maxScroll > 0f) {
                    vy = (event.y - lastY) / dt
                    scrollOff = (scrollOff - (event.y - lastY)).coerceIn(0f, maxScroll)
                    invalidate()
                } else if (mode == 2) {
                    vx = (event.x - lastX) / dt
                    hOff[dragLayer] = (hOff[dragLayer] - (event.x - lastX)).coerceIn(0f, hMax[dragLayer])
                    invalidate()
                }
                lastX = event.x; lastY = event.y; lastT = event.eventTime
            }

            MotionEvent.ACTION_UP -> {
                when (mode) {
                    0 -> layerAt(event.y)?.let { performClick(); enter(it) }
                    1 -> fling(vy, -1)
                    2 -> fling(vx, dragLayer)
                }
                mode = 0
            }

            MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** 简单惯性：layer=-1 竖向滚动，否则该层横向 */
    private fun fling(v: Float, layer: Int) {
        if (abs(v) < 300f || lowMotion) return
        flingAnim?.cancel()
        var last = 0f
        val dist = v * 0.28f
        flingAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                val cur = dist * (it.animatedValue as Float)
                val d = cur - last
                last = cur
                if (layer < 0) scrollOff = (scrollOff - d).coerceIn(0f, maxScroll)
                else if (layer < hOff.size) hOff[layer] = (hOff[layer] - d).coerceIn(0f, hMax[layer])
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anim?.cancel()
        flingAnim?.cancel()
    }
}
