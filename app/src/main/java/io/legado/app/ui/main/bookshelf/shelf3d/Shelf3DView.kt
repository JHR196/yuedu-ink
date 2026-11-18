package io.legado.app.ui.main.bookshelf.shelf3d

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.model.BookCover
import io.legado.app.utils.dpToPx
import io.legado.app.utils.spToPx
import io.legado.app.utils.textHeight
import io.legado.app.utils.toStringArray
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 立体书架：书以盒体绘制，中心书正面朝前并被抽出，两侧书转向露出书脊。
 * 只负责展示与选书，点击中心书后由 [Callback.onOpen] 交给原有开书逻辑。
 */
class Shelf3DView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Item(
        val key: String,
        val name: String,
        val author: String,
        val cover: String?,
        val origin: String?,
        val info: String,
        val data: Any
    )

    interface Callback {
        fun onOpen(item: Any)
        fun onLongPress(item: Any)
    }

    var callback: Callback? = null
    var centerKey: String? = null
        private set
    private var items: List<Item> = emptyList()
    private var focus = 0f

    // 几何，onSizeChanged 中计算
    private var bookW = 0f
    private var bookH = 0f
    private var bookT = 0f
    private var gap = 0f
    private var pitch = 0f
    private var eyeF = 0f
    private var baseY = 0f
    private var floorY = 0f
    private var unitPx = 1f
    private val maxTurn = Math.toRadians(80.0).toFloat()
    private val pitchAngle = Math.toRadians(9.0).toFloat()
    private val centerYaw = Math.toRadians(14.0).toFloat()

    private val dark = ColorUtils.calculateLuminance(context.backgroundColor) < 0.5
    private val accent = context.accentColor
    private val inkColor = if (dark) 0xFFECE6DA.toInt() else 0xFF2B2722.toInt()
    private val subColor = if (dark) 0xFF9A9286.toInt() else 0xFF7A7166.toInt()
    private val paperColor = if (dark) 0xFFCFC6B4.toInt() else 0xFFF4EEDF.toInt()
    private val paperShade = if (dark) 0xFF8F8878.toInt() else 0xFFD9D0BC.toInt()

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val plankPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSize = 19f.spToPx()
        color = inkColor
        textAlign = Paint.Align.CENTER
    }
    private val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12.5f.spToPx()
        color = subColor
        textAlign = Paint.Align.CENTER
    }
    private val indexPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 10f.spToPx()
        color = accent
        letterSpacing = 0.18f
        textAlign = Paint.Align.CENTER
    }

    private val matrix3 = Matrix()
    private val srcPts = FloatArray(8)
    private val dstPts = FloatArray(8)
    private val facePath = Path()

    // 每帧可见书的投影角点，按绘制顺序存放，供命中检测使用
    private val maxSlots = 64
    private val slotCorners = Array(maxSlots) { FloatArray(16) }
    private val slotIndex = IntArray(maxSlots)
    private var slotCount = 0
    private val order = ArrayList<Int>()

    private val cacheBytes = (Runtime.getRuntime().maxMemory() / 10).toInt()
    private val coverCache = object : LruCache<String, Bitmap>(cacheBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val spineCache = object : LruCache<String, Bitmap>(cacheBytes / 6) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val spineColors = HashMap<String, Int>()
    private val requested = HashSet<String>()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false
    private var longPressed = false
    private var lastOpenAt = 0L
    private var animator: ValueAnimator? = null
    private val longPressRunnable = Runnable {
        val idx = hitTest(downX, downY) ?: return@Runnable
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        callback?.onLongPress(items[idx].data)
    }

    init {
        isFocusable = true
        isClickable = true
    }

    private val lowMotion: Boolean
        get() = AppConfig.isEInkMode ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled())

    fun setItems(newItems: List<Item>) {
        val oldRound = focus.roundToInt()
        val idx = centerKey?.let { k -> newItems.indexOfFirst { it.key == k } } ?: -1
        items = newItems
        if (idx >= 0) {
            if (animator?.isRunning == true || !dragging) {
                animator?.cancel()
                focus = idx.toFloat()
            } else {
                focus = idx + (focus - oldRound)
            }
        } else {
            animator?.cancel()
            focus = oldRound.coerceIn(0, max(0, newItems.size - 1)).toFloat()
        }
        val keys = newItems.mapTo(HashSet()) { it.key }
        spineColors.keys.retainAll(keys)
        updateCenter(false)
        invalidate()
    }

    /** 数据到达前预设焦点书，用于视图重建后回到上次的书 */
    fun presetCenter(key: String?) {
        if (items.isEmpty() && key != null) centerKey = key
    }

    fun scrollToFirst() = animateTo(0)

    private fun updateCenter(tick: Boolean) {
        if (items.isEmpty()) {
            centerKey = null
            contentDescription = null
            return
        }
        val item = items[focus.roundToInt().coerceIn(0, items.size - 1)]
        if (item.key != centerKey) {
            centerKey = item.key
            contentDescription = item.name
            if (tick) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bookW = min(w * 0.44f, h * 0.56f / 1.42f)
        bookH = bookW * 1.42f
        bookT = (bookW * 0.15f).coerceIn(14f.dpToPx(), 30f.dpToPx())
        pitch = bookT * 1.12f
        gap = bookW * 0.5f + bookT * 1.1f + 10f.dpToPx()
        unitPx = gap * 0.8f
        eyeF = w * 1.8f
        baseY = paddingTop + h * 0.40f
        floorY = baseY + bookH * 0.5f
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            if (dark) 0xFF201E1B.toInt() else 0xFFF5F1E8.toInt(),
            if (dark) 0xFF141311.toInt() else 0xFFE6DFD0.toInt(),
            Shader.TileMode.CLAMP
        )
        shadowPaint.shader = RadialGradient(
            0f, 0f, 1f, 0x40000000, 0x00000000, Shader.TileMode.CLAMP
        )
        coverCache.evictAll()
        spineCache.evictAll()
        requested.clear()
    }

    // ---------- 几何 ----------

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    private fun offsetX(d: Float): Float {
        val a = abs(d)
        val v = gap * min(a, 1f) + pitch * max(a - 1f, 0f)
        return if (d < 0) -v else v
    }

    /** 计算第 i 本书的 8 个角点投影，写入 out(16) */
    private fun projectBook(i: Int, out: FloatArray) {
        val d = i - focus
        val s = smooth(abs(d))
        // 中心书也略侧转，露出书脊厚度
        val theta = -maxTurn * s - centerYaw * (1 - s)
        val cosT = cos(theta)
        val sinT = sin(theta)
        val cosP = cos(pitchAngle)
        val sinP = sin(pitchAngle)
        val tx = offsetX(d)
        val tz = -(1 - s) * bookT * 2.2f
        val cx = width / 2f
        for (c in 0 until 8) {
            val x = if (c and 1 == 0) -bookW / 2 else bookW / 2
            val y = if (c and 2 == 0) -bookH / 2 else bookH / 2
            val z = if (c and 4 == 0) -bookT / 2 else bookT / 2
            val rx = x * cosT + z * sinT
            val rz = -x * sinT + z * cosT
            // 视线略向下俯：书顶离眼更远，能看到顶面
            val py = y * cosP + rz * sinP
            val pz = -y * sinP + rz * cosP
            val wx = rx + tx
            val wz = pz + tz
            val k = eyeF / (eyeF + wz)
            out[c * 2] = cx + wx * k
            out[c * 2 + 1] = baseY + py * k
        }
        // 每本书的最低点贴住书架板
        var bottom = -Float.MAX_VALUE
        for (c in 0 until 8) bottom = max(bottom, out[c * 2 + 1])
        val dy = floorY - bottom
        for (c in 0 until 8) out[c * 2 + 1] += dy
    }

    private fun quad(corners: FloatArray, a: Int, b: Int, c: Int, d: Int, dst: FloatArray) {
        dst[0] = corners[a * 2]; dst[1] = corners[a * 2 + 1]
        dst[2] = corners[b * 2]; dst[3] = corners[b * 2 + 1]
        dst[4] = corners[c * 2]; dst[5] = corners[c * 2 + 1]
        dst[6] = corners[d * 2]; dst[7] = corners[d * 2 + 1]
    }

    private fun area(q: FloatArray): Float {
        var s = 0f
        for (j in 0 until 4) {
            val k = (j + 1) % 4
            s += q[j * 2] * q[k * 2 + 1] - q[k * 2] * q[j * 2 + 1]
        }
        return s / 2
    }

    private fun contains(q: FloatArray, x: Float, y: Float): Boolean {
        for (j in 0 until 4) {
            val k = (j + 1) % 4
            val cross = (q[k * 2] - q[j * 2]) * (y - q[j * 2 + 1]) -
                    (q[k * 2 + 1] - q[j * 2 + 1]) * (x - q[j * 2])
            if (cross < 0) return false
        }
        return true
    }

    // 面的角点顺序：从盒外看为 左上、右上、右下、左下
    private val faces = arrayOf(
        intArrayOf(0, 1, 3, 2), // 封面
        intArrayOf(4, 0, 2, 6), // 书脊
        intArrayOf(1, 5, 7, 3), // 书页侧
        intArrayOf(4, 5, 1, 0)  // 顶面
    )

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (items.isEmpty() || bookW <= 0f) return
        drawPlank(canvas, floorY)

        val half = ((width / 2f - gap) / pitch).toInt() + 3
        val c = focus.roundToInt()
        val lo = max(0, c - half)
        val hi = min(items.size - 1, c + half)
        order.clear()
        for (i in lo..hi) order.add(i)
        order.sortByDescending { abs(it - focus) }
        slotCount = 0
        for (i in order) {
            if (slotCount >= maxSlots) break
            projectBook(i, slotCorners[slotCount])
            slotIndex[slotCount] = i
            slotCount++
        }
        for (n in 0 until slotCount) drawShadow(canvas, slotCorners[n])
        for (n in 0 until slotCount) drawBook(canvas, slotIndex[n], slotCorners[n])
        drawInfo(canvas, floorY)
    }

    private fun drawPlank(canvas: Canvas, floorY: Float) {
        val plankH = 7f.dpToPx()
        plankPaint.shader = LinearGradient(
            0f, floorY, 0f, floorY + plankH,
            if (dark) 0xFF3A3530.toInt() else 0xFFD8CCB4.toInt(),
            if (dark) 0xFF24211E.toInt() else 0xFFBFB197.toInt(),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, floorY, width.toFloat(), floorY + plankH, plankPaint)
        plankPaint.shader = LinearGradient(
            0f, floorY + plankH, 0f, floorY + plankH + 18f.dpToPx(),
            0x22000000, 0x00000000, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, floorY + plankH, width.toFloat(), floorY + plankH + 18f.dpToPx(), plankPaint)
    }

    private fun drawShadow(canvas: Canvas, k: FloatArray) {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var bottom = 0f
        for (c in intArrayOf(2, 3, 6, 7)) {
            minX = min(minX, k[c * 2]); maxX = max(maxX, k[c * 2])
            bottom = max(bottom, k[c * 2 + 1])
        }
        val rx = (maxX - minX) * 0.62f
        val ry = 7f.dpToPx()
        canvas.save()
        canvas.translate((minX + maxX) / 2, bottom)
        canvas.scale(rx, ry)
        canvas.drawCircle(0f, 0f, 1f, shadowPaint)
        canvas.restore()
    }

    private fun drawBook(canvas: Canvas, i: Int, k: FloatArray) {
        val item = items[i]
        val s = smooth(abs(i - focus))
        for ((f, idx) in faces.withIndex()) {
            quad(k, idx[0], idx[1], idx[2], idx[3], dstPts)
            if (area(dstPts) <= 0.5f) continue
            when (f) {
                0 -> {
                    drawBitmapFace(canvas, coverFor(item))
                    shade(canvas, 0.5f * s)
                }
                1 -> {
                    drawBitmapFace(canvas, spineFor(item))
                    shade(canvas, 0.06f + 0.3f * (1 - s))
                }
                2 -> fillFace(canvas, paperShade)
                3 -> fillFace(canvas, paperColor)
            }
        }
    }

    private fun drawBitmapFace(canvas: Canvas, bmp: Bitmap) {
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        srcPts[0] = 0f; srcPts[1] = 0f; srcPts[2] = w; srcPts[3] = 0f
        srcPts[4] = w; srcPts[5] = h; srcPts[6] = 0f; srcPts[7] = h
        matrix3.setPolyToPoly(srcPts, 0, dstPts, 0, 4)
        canvas.drawBitmap(bmp, matrix3, bmpPaint)
    }

    private fun pathOf(q: FloatArray): Path {
        facePath.rewind()
        facePath.moveTo(q[0], q[1])
        facePath.lineTo(q[2], q[3])
        facePath.lineTo(q[4], q[5])
        facePath.lineTo(q[6], q[7])
        facePath.close()
        return facePath
    }

    private fun fillFace(canvas: Canvas, color: Int) {
        fillPaint.color = color
        canvas.drawPath(pathOf(dstPts), fillPaint)
    }

    private fun shade(canvas: Canvas, alpha: Float) {
        if (alpha <= 0.01f) return
        shadePaint.color = Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0)
        canvas.drawPath(pathOf(dstPts), shadePaint)
    }

    private fun drawInfo(canvas: Canvas, floorY: Float) {
        val ci = focus.roundToInt().coerceIn(0, items.size - 1)
        val item = items[ci]
        val alpha = (1 - abs(focus - ci) * 2.2f).coerceIn(0.25f, 1f)
        val cx = width / 2f
        var y = floorY + 48f.dpToPx()
        indexPaint.alpha = (alpha * 255).toInt()
        canvas.drawText(
            "№ %02d / %02d".format(ci + 1, items.size), cx, y, indexPaint
        )
        y += 30f.dpToPx()
        titlePaint.alpha = (alpha * 255).toInt()
        val title = TextUtils.ellipsize(item.name, titlePaint, width * 0.84f, TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, cx, y, titlePaint)
        y += 22f.dpToPx()
        infoPaint.alpha = (alpha * 255).toInt()
        val sub = listOf(item.author, item.info).filter { it.isNotBlank() }.joinToString("  ·  ")
        val info = TextUtils.ellipsize(sub, infoPaint, width * 0.84f, TextUtils.TruncateAt.END)
        canvas.drawText(info, 0, info.length, cx, y, infoPaint)
    }

    // ---------- 封面与书脊 ----------

    private val coverW get() = max(1, bookW.toInt())
    private val coverH get() = max(1, bookH.toInt())

    private fun hueOf(item: Item): Float = ((item.name.hashCode() and 0x7fffffff) % 360).toFloat()

    private fun coverFor(item: Item): Bitmap {
        val key = item.key + "|" + item.cover
        coverCache.get(key)?.let { return it }
        if (requested.add(key)) requestCover(item, key)
        val genKey = item.key + "|#gen|" + item.name + "|" + item.author
        return coverCache.get(genKey) ?: generateCover(item).also { coverCache.put(genKey, it) }
    }

    private fun requestCover(item: Item, key: String) {
        if (AppConfig.useDefaultCover || item.cover.isNullOrBlank() || !isAttachedToWindow) return
        var options = RequestOptions().set(OkHttpModelLoader.loadOnlyWifiOption, false)
        item.origin?.let { options = options.set(OkHttpModelLoader.sourceOriginOption, it) }
        val w = coverW
        val h = coverH
        kotlin.runCatching {
            ImageLoader.loadBitmap(context, item.cover).apply(options).override(w, h).centerCrop()
                .into(object : CustomTarget<Bitmap>(w, h) {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        if (w != coverW || h != coverH) return
                        coverCache.put(key, composeCover(resource, w, h))
                        val avg = Bitmap.createScaledBitmap(resource, 1, 1, true).getPixel(0, 0)
                        spineColors[item.key] = avg
                        invalidate()
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        requested.remove(key)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) = Unit
                })
        }
    }

    /** 真实封面加一道装订折痕 */
    private fun composeCover(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawBitmap(src, null, Rect(0, 0, w, h), bmpPaint)
        val crease = w * 0.07f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, crease, 0f,
            intArrayOf(0x55000000, 0x18FFFFFF, 0x00000000),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, crease, h.toFloat(), p)
        return out
    }

    /**
     * 没有书源封面或加载失败时，使用原有默认封面（BookCover.defaultDrawable，含用户配置），
     * 书名与作者的竖排方式与 CoverImageView 一致
     */
    private fun generateCover(item: Item): Bitmap {
        val w = coverW
        val h = coverH
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val d = BookCover.defaultDrawable
        val dw = d.intrinsicWidth.takeIf { it > 0 } ?: w
        val dh = d.intrinsicHeight.takeIf { it > 0 } ?: h
        val scale = max(w / dw.toFloat(), h / dh.toFloat())
        val sw = (dw * scale).toInt()
        val sh = (dh * scale).toInt()
        val left = (w - sw) / 2
        val top = (h - sh) / 2
        val oldBounds = Rect(d.bounds)
        d.setBounds(left, top, left + sw, top + sh)
        d.draw(c)
        d.bounds = oldBounds
        drawDefaultName(c, item, w.toFloat(), h.toFloat())
        spineColors[item.key] = Bitmap.createScaledBitmap(out, 1, 1, true).getPixel(0, 0)
        return out
    }

    private fun drawDefaultName(canvas: Canvas, item: Item, vw: Float, vh: Float) {
        if (!BookCover.drawBookName) return
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        fun stroke(ch: String, x: Float, y: Float) {
            p.color = Color.WHITE
            p.style = Paint.Style.STROKE
            canvas.drawText(ch, x, y, p)
            p.color = accent
            p.style = Paint.Style.FILL
            canvas.drawText(ch, x, y, p)
        }
        var x = vw * 0.2f
        var y = vh * 0.2f
        val name = item.name.replace(AppPattern.bdRegex, "").trim().toStringArray()
        p.textSize = vw / 6
        p.strokeWidth = p.textSize / 5
        name.forEachIndexed { index, ch ->
            stroke(ch, x, y)
            y += p.textHeight
            if (y > vh * 0.8f) {
                x += p.textSize
                p.textSize = vw / 10
                y = (vh - (name.size - index - 1) * p.textHeight) / 2
            }
        }
        if (!BookCover.drawBookAuthor) return
        val author = item.author.replace(AppPattern.bdRegex, "").trim().toStringArray()
        p.textSize = vw / 10
        p.strokeWidth = p.textSize / 5
        x = vw * 0.8f
        y = max(vh * 0.95f - author.size * p.textHeight, vh * 0.3f)
        for (ch in author) {
            stroke(ch, x, y)
            y += p.textHeight
            if (y > vh * 0.95f) break
        }
    }

    private fun spineColorOf(item: Item): Int {
        spineColors[item.key]?.let {
            return ColorUtils.blendARGB(it, Color.BLACK, 0.12f)
        }
        return Color.HSVToColor(floatArrayOf(hueOf(item), 0.34f, if (dark) 0.34f else 0.52f))
    }

    private fun spineFor(item: Item): Bitmap {
        val color = spineColorOf(item)
        val key = item.key + "|" + color + "|" + item.name
        spineCache.get(key)?.let { return it }
        return generateSpine(item, color).also { spineCache.put(key, it) }
    }

    /** 书脊：底色取封面平均色，书名竖排，上下两道细线 */
    private fun generateSpine(item: Item, color: Int): Bitmap {
        val w = max(1, bookT.toInt())
        val h = coverH
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                ColorUtils.blendARGB(color, Color.BLACK, 0.25f),
                ColorUtils.blendARGB(color, Color.WHITE, 0.10f),
                ColorUtils.blendARGB(color, Color.BLACK, 0.20f)
            ),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val light = ColorUtils.calculateLuminance(color) < 0.5
        val ink = if (light) 0xF2FFFFFF.toInt() else 0xE6211E1A.toInt()
        val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (light) 0x88E8D3A0.toInt() else 0x66000000
            strokeWidth = max(1f, w * 0.05f)
        }
        for (y in floatArrayOf(h * 0.06f, h * 0.075f, h * 0.925f, h * 0.94f)) {
            c.drawLine(0f, y, w.toFloat(), y, band)
        }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textSize = w * 0.52f
            this.color = ink
            textAlign = Paint.Align.CENTER
        }
        val step = tp.textSize * 1.08f
        val top = h * 0.12f
        val avail = h * 0.76f
        val maxChars = max(1, (avail / step).toInt())
        val name = item.name.trim()
        val chars = ArrayList<String>()
        var ci = 0
        while (ci < name.length) {
            val n = Character.charCount(name.codePointAt(ci))
            chars.add(name.substring(ci, ci + n))
            ci += n
        }
        val shown = if (chars.size > maxChars) chars.take(maxChars - 1) + "…" else chars
        val fm = tp.fontMetrics
        var y = top - fm.ascent
        for (ch in shown) {
            c.drawText(ch, w / 2f, y, tp)
            y += step
        }
        return out
    }

    // ---------- 交互 ----------

    private fun hitTest(x: Float, y: Float): Int? {
        for (n in slotCount - 1 downTo 0) {
            val k = slotCorners[n]
            for (idx in faces) {
                quad(k, idx[0], idx[1], idx[2], idx[3], dstPts)
                if (area(dstPts) > 0.5f && contains(dstPts, x, y)) return slotIndex[n]
            }
        }
        return null
    }

    private fun animateTo(target: Int) {
        if (items.isEmpty()) return
        val t = target.coerceIn(0, items.size - 1).toFloat()
        animator?.cancel()
        if (lowMotion) {
            focus = t
            updateCenter(false)
            invalidate()
            return
        }
        val dist = abs(t - focus)
        animator = ValueAnimator.ofFloat(focus, t).apply {
            duration = (200 + 160 * sqrt(dist)).toLong().coerceAtMost(720)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                focus = it.animatedValue as Float
                updateCenter(false)
                invalidate()
            }
            start()
        }
    }

    private fun settle(velocityX: Float) {
        if (items.isEmpty()) return
        // 惯性按书脊间距换算：快速一划可以掠过多本侧书
        val projected = focus - velocityX / pitch * 0.11f
        animateTo(projected.roundToInt())
    }

    private fun handleTap(x: Float, y: Float) {
        val idx = hitTest(x, y) ?: return
        val center = focus.roundToInt()
        if (idx == center && abs(focus - idx) < 0.05f) {
            val now = SystemClock.uptimeMillis()
            if (now - lastOpenAt < 800) return
            lastOpenAt = now
            performClick()
            callback?.onOpen(items[idx].data)
        } else {
            animateTo(idx)
        }
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.isEmpty()) return super.onTouchEvent(event)
        val vt = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        vt.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                downX = event.x
                downY = event.y
                lastX = event.x
                dragging = false
                longPressed = false
                parent?.requestDisallowInterceptTouchEvent(true)
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging && !longPressed) {
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        removeCallbacks(longPressRunnable)
                        parent?.requestDisallowInterceptTouchEvent(false)
                    } else if (abs(dx) > touchSlop) {
                        // 横向手势始终由书架处理，首尾以阻尼回弹，不切换外层页面
                        removeCallbacks(longPressRunnable)
                        dragging = true
                        lastX = event.x
                    }
                }
                if (dragging) {
                    var delta = (event.x - lastX) / unitPx
                    val over = focus < 0f || focus > items.size - 1f
                    if (over) delta *= 0.35f
                    focus = (focus - delta).coerceIn(-0.45f, items.size - 1 + 0.45f)
                    lastX = event.x
                    updateCenter(true)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                vt.computeCurrentVelocity(1000)
                if (dragging) {
                    settle(vt.xVelocity)
                } else if (!longPressed) {
                    handleTap(event.x, event.y)
                }
                recycleTracker()
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                if (dragging) settle(0f)
                recycleTracker()
            }
        }
        return true
    }

    private fun recycleTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
        dragging = false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val c = focus.roundToInt()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (c > 0) animateTo(c - 1).also { return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (c < items.size - 1) animateTo(c + 1).also { return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> items.getOrNull(c)?.let {
                callback?.onOpen(it.data)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        removeCallbacks(longPressRunnable)
        requested.clear()
    }

    override fun onSaveInstanceState(): Parcelable {
        return Bundle().apply {
            putParcelable("super", super.onSaveInstanceState())
            putString("centerKey", centerKey)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            centerKey = state.getString("centerKey")
            super.onRestoreInstanceState(state.getParcelable("super"))
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    companion object {
        /** AppConfig.bookshelfLayout 中立体书架的取值 */
        const val LAYOUT_INDEX = 5
    }
}
