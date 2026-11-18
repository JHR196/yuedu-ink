package io.legado.app.ui.main.bookshelf.shelf3d

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.spToPx
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 书柜墙：书架双指合拢后的总览。
 * 一层一类，每层左侧竖排类名铭条，书脊朝外整齐排列，同样大小、竖排书名；
 * 上下滑动翻层，层内左右拖动看更多；点一层（或在该层双指张开）放大进入那一类。
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

    var callback: Callback? = null

    private val dark = ColorUtils.calculateLuminance(context.backgroundColor) < 0.5
    private val accent = context.accentColor
    private val bgColor = context.backgroundColor
    private val inkColor = if (dark) 0xFFECE6DA.toInt() else 0xFF2B2722.toInt()
    private val subColor = if (dark) 0xFF9A9286.toInt() else 0xFF7A7166.toInt()

    // 木色：框、层板、背板
    private val woodHi = if (dark) 0xFF5A4431.toInt() else 0xFFA8805A.toInt()
    private val woodLo = if (dark) 0xFF33261A.toInt() else 0xFF7A5A3C.toInt()
    private val backHi = if (dark) 0xFF1E1813.toInt() else 0xFF5A4330.toInt()
    private val backLo = if (dark) 0xFF15110D.toInt() else 0xFF46331F.toInt()

    private var layers: List<Layer> = emptyList()
    private var current = 0

    // ---------- 几何 ----------
    private val sideM = 14f.dpToPx()
    private val post = 10f.dpToPx()
    private val crownH = 14f.dpToPx()
    private val plankH = 11f.dpToPx()
    private val baseH = 16f.dpToPx()
    private val railW = 34f.dpToPx()
    private val gutter = 3f.dpToPx()
    private val pad = 8f.dpToPx()
    private val navReserve = 96f.dpToPx()
    private var layerH = 0f
    private var top0 = 0f
    private var scrollOff = 0f
    private var maxScroll = 0f
    private var hOff = FloatArray(0)
    private var hMax = FloatArray(0)

    // ---------- 动画状态 ----------
    private var zoom = 1f
    private var pivotY = 0f
    private var fade = 1f
    private var anim: ValueAnimator? = null
    private var flingAnim: ValueAnimator? = null

    // ---------- 画笔 ----------
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint()
    private val glowPaint = Paint()
    private val vignette = Paint()
    private val railText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        color = if (dark) 0xFF2A2012.toInt() else 0xFF2E2414.toInt()
    }
    private val railCount = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 8.5f.spToPx()
        textAlign = Paint.Align.CENTER
        color = if (dark) 0xCC2A2012.toInt() else 0xCC2E2414.toInt()
    }
    private val spineText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f.spToPx()
        color = subColor
        letterSpacing = 0.06f
    }
    private val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSize = 15f.spToPx()
        color = inkColor
        letterSpacing = 0.1f
    }
    private val emptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.SERIF
        textSize = 12f.spToPx()
        textAlign = Paint.Align.CENTER
        color = if (dark) 0xFF6E655A.toInt() else 0xFFC9B79A.toInt()
    }
    private val rect = RectF()

    // ---------- 封面 ----------
    private val requested = HashSet<String>()

    private val lowMotion: Boolean
        get() = AppConfig.isEInkMode ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())

    init {
        isClickable = true
        isFocusable = true
    }

    // ================= 对外 =================

    val isShowing get() = visibility == VISIBLE && anim?.isRunning != true

    /** 展示书柜：从当前层缩小出来 */
    fun show(data: List<Layer>, currentIndex: Int) {
        layers = data
        current = currentIndex.coerceIn(0, max(0, data.size - 1))
        hOff = FloatArray(data.size)
        hMax = FloatArray(data.size)
        visibility = VISIBLE
        relayout()
        // 让当前层尽量居中可见
        if (maxScroll > 0f) {
            val mid = (height - navReserve) / 2f
            scrollOff = (top0 + crownH + current * layerH + layerH / 2 - mid).coerceIn(0f, maxScroll)
        }
        pivotY = layerCenter(current)
        if (lowMotion) {
            zoom = 1f; fade = 1f; invalidate(); return
        }
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val t = it.animatedValue as Float
                zoom = 2.4f - 1.4f * t
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
                zoom = 1f + 1.6f * t
                fade = 1f - t
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishHide()
            })
            start()
        }
    }

    /** 返回键：回到当前层 */
    fun dismiss() = enter(current)

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
            w / 2f, h * 0.4f, max(w, h) * 0.8f,
            intArrayOf(0x00000000, 0x00000000, if (dark) 0x80000000.toInt() else 0x334A3A20),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        glowPaint.shader = RadialGradient(
            w / 2f, h * 0.18f, max(w, h) * 0.7f,
            intArrayOf(if (dark) 0x2EFFD7A0 else 0x55FFFFFF, 0x00000000),
            null, Shader.TileMode.CLAMP
        )
    }

    private fun relayout() {
        if (width == 0 || height == 0) return
        val n = max(1, layers.size)
        val headTop = paddingTop + 44f.dpToPx()
        val avail = height - navReserve - headTop - crownH - baseH
        layerH = (avail / n).coerceIn(150f.dpToPx(), 200f.dpToPx())
        val contentH = crownH + n * layerH + baseH
        top0 = headTop + max(0f, (avail - n * layerH) / 2f)
        maxScroll = max(0f, top0 + contentH - (height - navReserve))
        scrollOff = scrollOff.coerceIn(0f, maxScroll)
        val innerW = innerRight() - innerLeft() - railW - pad
        for (i in layers.indices) {
            hMax[i] = max(0f, contentWidth(layers[i]) - innerW)
            hOff[i] = hOff[i].coerceIn(0f, hMax[i])
        }
    }

    private fun innerLeft() = sideM + post
    private fun innerRight() = width - sideM - post
    private fun layerTop(i: Int) = top0 + crownH + i * layerH - scrollOff
    private fun layerCenter(i: Int) = layerTop(i) + layerH / 2f
    private fun bookArea(i: Int): Float = layerH - plankH - pad * 2

    /** 书脊统一尺寸、整齐排列 */
    private val spineW = 24f.dpToPx()
    private val spineGap = 2f.dpToPx()

    private fun contentWidth(layer: Layer): Float =
        layer.books.size * (spineW + spineGap)

    // ================= 绘制 =================

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val layered = fade < 0.999f
        if (layered) canvas.saveLayerAlpha(0f, 0f, w, h, (fade * 255).toInt())
        fill.color = bgColor
        canvas.drawRect(0f, 0f, w, h, fill)
        canvas.drawRect(0f, 0f, w, h, glowPaint)
        canvas.save()
        if (zoom <= 1.001f) canvas.clipRect(0f, paddingTop + 38f.dpToPx(), w, h)
        canvas.scale(zoom, zoom, w / 2f, pivotY)
        drawCabinet(canvas)
        canvas.restore()
        canvas.drawRect(0f, 0f, w, h, vignette)
        drawHeader(canvas)
        if (layered) canvas.restore()
    }

    private fun drawHeader(canvas: Canvas) {
        val y = paddingTop + 26f.dpToPx()
        val total = layers.sumOf { it.books.size }
        canvas.drawText("书柜", sideM + 4f.dpToPx(), y, headPaint)
        val x = sideM + 4f.dpToPx() + headPaint.measureText("书柜") + 10f.dpToPx()
        canvas.drawText("${layers.size} 类 · $total 册   点一层进入", x, y, hintPaint)
    }

    private fun drawCabinet(canvas: Canvas) {
        if (layers.isEmpty()) return
        val l = sideM
        val r = width - sideM
        val top = top0 - scrollOff
        val bottom = layerTop(layers.size) + 0f
        // 落地阴影
        fill.color = Color.BLACK
        fill.shader = RadialGradient(
            width / 2f, bottom + baseH, (r - l) * 0.6f,
            0x55000000, 0x00000000, Shader.TileMode.CLAMP
        )
        canvas.drawRect(l - 20f.dpToPx(), bottom, r + 20f.dpToPx(), bottom + baseH + 30f.dpToPx(), fill)
        fill.shader = null
        // 背板
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, top, 0f, bottom, backHi, backLo, Shader.TileMode.CLAMP)
        canvas.drawRect(l + post, top + crownH, r - post, bottom, fill)
        fill.shader = null
        // 每一层
        for (i in layers.indices) {
            val t = layerTop(i)
            if (t > height || t + layerH < 0) continue
            drawLayer(canvas, i, t)
        }
        // 立柱
        fill.color = Color.BLACK
        fill.shader = LinearGradient(l, 0f, l + post, 0f, woodHi, woodLo, Shader.TileMode.CLAMP)
        canvas.drawRect(l, top + crownH, l + post, bottom, fill)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(r - post, 0f, r, 0f, woodLo, woodHi, Shader.TileMode.CLAMP)
        canvas.drawRect(r - post, top + crownH, r, bottom, fill)
        // 顶冠
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, top, 0f, top + crownH, woodHi, woodLo, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(l - 6f.dpToPx(), top, r + 6f.dpToPx(), top + crownH, 3f.dpToPx(), 3f.dpToPx(), fill)
        fill.shader = null
        fill.color = 0x33FFFFFF
        canvas.drawRect(l - 6f.dpToPx(), top + 1f, r + 6f.dpToPx(), top + 2f.dpToPx(), fill)
        // 底座
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, bottom, 0f, bottom + baseH, woodHi, woodLo, Shader.TileMode.CLAMP)
        canvas.drawRect(l - 4f.dpToPx(), bottom, r + 4f.dpToPx(), bottom + baseH, fill)
        fill.shader = null
    }

    private fun drawLayer(canvas: Canvas, i: Int, t: Float) {
        val layer = layers[i]
        val il = innerLeft()
        val ir = innerRight()
        val floor = t + layerH - plankH
        // 层顶阴影（上一块层板压下来的暗部）
        shadePaint.shader = LinearGradient(0f, t, 0f, t + 22f.dpToPx(), 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(il, t, ir, t + 22f.dpToPx(), shadePaint)
        // 当前层一盏暖光
        if (i == current) {
            fill.color = Color.BLACK
            fill.shader = RadialGradient(
                (il + ir) / 2f, t, (ir - il) * 0.7f,
                if (dark) 0x40FFD7A0 else 0x40FFF2D8, 0x00000000, Shader.TileMode.CLAMP
            )
            canvas.drawRect(il, t, ir, floor, fill)
            fill.shader = null
        }
        // 封面拼贴
        val area = bookArea(i)
        val x0 = il + railW + pad
        val y0 = t + pad
        canvas.save()
        canvas.clipRect(x0 - 1f, t, ir, floor)
        if (layer.books.isEmpty()) {
            canvas.drawText("这一层还空着", (x0 + ir) / 2f, y0 + area / 2f, emptyPaint)
        }
        val sh = area + pad - 2f.dpToPx()
        val sy = floor - sh
        for ((k, item) in layer.books.withIndex()) {
            val sx = x0 - hOff[i] + k * (spineW + spineGap)
            if (sx + spineW < x0 || sx > ir) continue
            rect.set(sx, sy, sx + spineW, floor)
            drawSpine(canvas, item, rect)
        }
        canvas.restore()
        // 层内还有更多：右侧渐隐
        if (hMax[i] > 0f && hOff[i] < hMax[i] - 1f) {
            shadePaint.shader = LinearGradient(ir - 26f.dpToPx(), 0f, ir, 0f, 0x00000000, 0xAA000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(ir - 26f.dpToPx(), t, ir, floor, shadePaint)
        }
        // 层板
        fill.color = Color.BLACK
        fill.shader = LinearGradient(0f, floor, 0f, floor + plankH, woodHi, woodLo, Shader.TileMode.CLAMP)
        canvas.drawRect(il, floor, ir, floor + plankH, fill)
        fill.shader = null
        fill.color = 0x2EFFFFFF
        canvas.drawRect(il, floor, ir, floor + 1f.dpToPx(), fill)
        // 左侧铜铭条：竖排类名 + 册数
        drawRail(canvas, layer, il + 3f.dpToPx(), t + pad, floor - pad, i == current)
    }

    private fun drawRail(canvas: Canvas, layer: Layer, left: Float, top: Float, bottom: Float, cur: Boolean) {
        val w = railW - 8f.dpToPx()
        rect.set(left, top, left + w, bottom)
        fill.color = Color.BLACK
        fill.shader = LinearGradient(
            rect.left, 0f, rect.right, 0f,
            if (dark) 0xFFB89A62.toInt() else 0xFFE6CF98.toInt(),
            if (dark) 0xFF7A6238.toInt() else 0xFFB8955A.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, 3f.dpToPx(), 3f.dpToPx(), fill)
        fill.shader = null
        if (cur) {
            fill.color = accent
            canvas.drawRect(rect.left, rect.top, rect.right, rect.top + 3f.dpToPx(), fill)
        }
        fill.color = 0x553A2A10
        canvas.drawCircle(rect.centerX(), rect.top + 9f.dpToPx(), 1.4f.dpToPx(), fill)
        canvas.drawCircle(rect.centerX(), rect.bottom - 9f.dpToPx(), 1.4f.dpToPx(), fill)
        val countH = 22f.dpToPx()
        val chars = layer.name.trim()
        val room = rect.height() - 26f.dpToPx() - countH
        railText.textSize = min(w * 0.62f, 15f.spToPx())
        val step = railText.textSize * 1.1f
        val n = max(1, min(chars.length, (room / step).toInt()))
        val shown = if (chars.length > n) chars.take(max(1, n - 1)) + "…" else chars
        var y = rect.top + 18f.dpToPx() + railText.textSize
        for (ch in shown) {
            canvas.drawText(ch.toString(), rect.centerX(), y, railText)
            y += step
        }
        canvas.drawText("${layer.books.size}", rect.centerX(), rect.bottom - 18f.dpToPx(), railCount)
        canvas.drawText("册", rect.centerX(), rect.bottom - 18f.dpToPx() + railCount.textSize + 2f, railCount)
    }

    private fun drawSpine(canvas: Canvas, item: Shelf3DView.Item, r: RectF) {
        val base = spineColor(item)
        // 圆脊：左亮右暗
        fill.color = Color.BLACK
        fill.shader = LinearGradient(
            r.left, 0f, r.right, 0f,
            intArrayOf(
                ColorUtils.blendARGB(base, Color.WHITE, 0.10f),
                ColorUtils.blendARGB(base, Color.WHITE, 0.18f),
                base,
                ColorUtils.blendARGB(base, Color.BLACK, 0.35f)
            ),
            floatArrayOf(0f, 0.25f, 0.6f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(r, fill)
        fill.shader = null
        // 上下两道烫金线
        val gold = if (ColorUtils.calculateLuminance(base) > 0.45) 0xFF6A5230.toInt() else 0xFFD9BE84.toInt()
        fill.color = gold
        val bw = 1.2f.dpToPx()
        canvas.drawRect(r.left + 2f, r.top + 6f.dpToPx(), r.right - 2f, r.top + 6f.dpToPx() + bw, fill)
        canvas.drawRect(r.left + 2f, r.top + 9f.dpToPx(), r.right - 2f, r.top + 9f.dpToPx() + bw * 0.6f, fill)
        canvas.drawRect(r.left + 2f, r.bottom - 10f.dpToPx(), r.right - 2f, r.bottom - 10f.dpToPx() + bw, fill)
        // 竖排书名
        spineText.color = if (ColorUtils.calculateLuminance(base) > 0.45) 0xFF2B2722.toInt() else 0xFFF3E7CC.toInt()
        spineText.textSize = min(r.width() * 0.56f, 13f.spToPx())
        val step = spineText.textSize * 1.08f
        val room = r.height() - 30f.dpToPx()
        val name = item.name.trim()
        val n = max(1, min(name.length, (room / step).toInt()))
        val shown = if (name.length > n) name.take(max(1, n - 1)) + "…" else name
        var y = r.top + 15f.dpToPx() + spineText.textSize
        for (ch in shown) {
            if (ch == '…') canvas.drawText("︙", r.centerX(), y, spineText)
            else canvas.drawText(ch.toString(), r.centerX(), y, spineText)
            y += step
        }
        // 书脊间的细缝
        fill.color = 0x40000000
        canvas.drawRect(r.right - 1f, r.top, r.right, r.bottom, fill)
    }

    // ================= 封面 =================

    private val spineColors = HashMap<String, Int>()

    private fun spineColor(item: Shelf3DView.Item): Int {
        spineColors[item.key]?.let { return it }
        val cover = item.cover
        if (!AppConfig.useDefaultCover && !cover.isNullOrBlank() && requested.add(item.key)) {
            loadCover(item, cover)
        }
        val hue = ((item.name.hashCode() and 0x7fffffff) % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.38f, if (dark) 0.38f else 0.48f))
    }

    private fun loadCover(item: Shelf3DView.Item, url: String) {
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, false)
        item.origin?.let { options = options.set(OkHttpModelLoader.sourceOriginOption, it) }
        kotlin.runCatching {
            ImageLoader.loadBitmap(context, url).apply(options).override(24, 32).centerCrop()
                .into(object : CustomTarget<Bitmap>(24, 32) {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        val avg = Bitmap.createScaledBitmap(resource, 1, 1, true).getPixel(0, 0)
                        // 压一点饱和与亮度，像布面
                        val hsv = FloatArray(3)
                        Color.colorToHSV(avg, hsv)
                        hsv[1] = min(hsv[1], 0.6f)
                        hsv[2] = hsv[2].coerceIn(0.22f, 0.7f)
                        spineColors[item.key] = Color.HSVToColor(hsv)
                        invalidate()
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) = Unit
                    override fun onLoadCleared(placeholder: Drawable?) = Unit
                })
        }
    }

    // ================= 触摸 =================

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var mode = 0 // 0 未定 1 竖滚 2 横拖 3 双指
    private var dragLayer = -1
    private var pinchScale = 1f
    private var vx = 0f
    private var vy = 0f
    private var lastT = 0L

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                pinchScale *= d.scaleFactor
                if (pinchScale > 1.28f && mode == 3) {
                    mode = 4
                    layerAt(d.focusY)?.let { enter(it) }
                }
                return true
            }
        })

    private fun layerAt(y: Float): Int? {
        if (layers.isEmpty()) return null
        val i = ((y - (top0 + crownH - scrollOff)) / layerH).toInt()
        val inRange = y >= top0 + crownH - scrollOff
        return if (inRange && i in layers.indices) i else null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (anim?.isRunning == true) return true
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                flingAnim?.cancel()
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                lastT = event.eventTime
                vx = 0f; vy = 0f
                mode = 0
                pinchScale = 1f
                dragLayer = layerAt(event.y) ?: -1
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> if (mode < 3) mode = 3

            MotionEvent.ACTION_MOVE -> {
                if (mode >= 3) return true
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
