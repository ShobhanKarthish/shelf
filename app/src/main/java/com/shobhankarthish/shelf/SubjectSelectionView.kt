package com.shobhankarthish.shelf

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.*

// Coordinates and alpha stay in the original photo's space, including holes in handles.
data class PhotoSubject(val bitmap: Bitmap, val left: Int, val top: Int) {
    fun contains(x: Float, y: Float): Boolean {
        val px = x - left; val py = y - top
        return px >= 0 && py >= 0 && px < bitmap.width && py < bitmap.height &&
            Color.alpha(bitmap.getPixel(px.toInt(), py.toInt())) > 128
    }
    fun export(): Bitmap = Bitmap.createBitmap(bitmap.width + 24, bitmap.height + 24, Bitmap.Config.ARGB_8888).also {
        Canvas(it).drawBitmap(bitmap, 12f, 12f, null)
    }
}

/**
 * The chosen photo, with a dashed cut line around each object the model found. Tapping (or holding)
 * inside one selects it, and [snip] cuts it out along that line as a sticker.
 */
class SubjectSelectionView(context: Context, private val original: Bitmap) : View(context) {
    /** Setting subjects directly traces their outlines here; [show] takes outlines already traced on a worker. */
    var subjects: List<PhotoSubject>
        get() = found
        set(value) { show(value, trace(value, original), emptyList()) }
    var onSelection: (Int?) -> Unit = {}
    var onMiss: () -> Unit = {}
    var selectedIndex: Int? = null
        private set
    private val d = resources.displayMetrics.density
    private var found: List<PhotoSubject> = emptyList()
    private var outlines: List<FloatArray> = emptyList()
    private var viewPaths: List<Path>? = null
    private val stickers = HashMap<Int, Sticker>()
    private var locked = false
    private var paused = false
    private var snipper: ValueAnimator? = null
    private var elapsed = -1f
    private var cutLength = 0f
    private var cutMeasure: PathMeasure? = null
    private var ring: PointF? = null
    private var ringAt = 0L
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(Ink.INK, PorterDuff.Mode.SRC_IN) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val under = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f * d; strokeJoin = Paint.Join.ROUND; color = Color.WHITE }
    private val dashes = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; pathEffect = dashes }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.6f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Ink.ACCENT }
    private val pop = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = Ink.ACCENT }
    private val cut = Path()
    private val card = Path()
    private val smooth = AccelerateDecelerateInterpolator()
    private val lift = OvershootInterpolator(1.1f)

    init { contentDescription = "Photo. Tap an object to select it, or use the object buttons below." }

    /** Shows [subjects] with their traced [outlines]; [cut] may hold their stickers, made ahead of time. */
    fun show(subjects: List<PhotoSubject>, outlines: List<FloatArray>, cut: List<Sticker>) {
        found = subjects; this.outlines = outlines
        stickers.clear(); cut.forEachIndexed { i, s -> stickers[i] = s }
        viewPaths = null; invalidate()
    }
    private fun photoRect(): RectF {
        val m = 4 * d
        val scale = minOf((width - 2 * m) / original.width, (height - 2 * m) / original.height)
        val w = original.width * scale; val h = original.height * scale
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }
    /** Where a point of the photo lands on screen. */
    fun photoToView(x: Float, y: Float): PointF { val r = photoRect(); val s = r.width() / original.width; return PointF(r.left + x * s, r.top + y * s) }
    private fun viewToPhoto(x: Float, y: Float): PointF? {
        val r = photoRect()
        if (!r.contains(x, y)) return null
        val s = r.width() / original.width
        return PointF((x - r.left) / s, (y - r.top) / s)
    }
    /** The object's own pixels win; otherwise anywhere inside its cut line counts, so small things are easy to hit. */
    fun subjectAt(x: Float, y: Float): Int? {
        val p = viewToPhoto(x, y) ?: return null
        return found.indices.lastOrNull { found[it].contains(p.x, p.y) } ?: outlines.indices.lastOrNull { inside(p.x, p.y, outlines[it]) }
    }
    private fun inside(x: Float, y: Float, poly: FloatArray): Boolean {
        var c = false; var j = poly.size - 2; var i = 0
        while (i < poly.size) {
            val xi = poly[i]; val yi = poly[i + 1]; val xj = poly[j]; val yj = poly[j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i; i += 2
        }
        return c
    }
    fun select(index: Int?) {
        if (locked) return
        selectedIndex = index?.takeIf { it in found.indices }
        invalidate(); onSelection(selectedIndex)
    }
    /** The object cut along its dashed line; [Sticker.left] and [Sticker.top] are in the object's own pixels. */
    fun sticker(index: Int): Sticker = stickers.getOrPut(index) { cutOut(found[index], outlines[index]) }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = !locked && found.isNotEmpty()
        override fun onSingleTapUp(e: MotionEvent): Boolean { choose(e); performClick(); return true }
        override fun onLongPress(e: MotionEvent) { if (choose(e)) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
    })
    private fun choose(e: MotionEvent): Boolean {
        if (locked) return false
        ring = PointF(e.x, e.y); ringAt = SystemClock.uptimeMillis()
        val index = subjectAt(e.x, e.y)
        if (index == null) { invalidate(); onMiss() } else select(index)
        return index != null
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility") // GestureDetector calls performClick for taps; buttons offer accessible selection.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        return handled || event.actionMasked == MotionEvent.ACTION_UP
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun lockSelection() { locked = true }

    /** Scissors follow the selected line, the photo fades, and the sticker settles in the middle. */
    fun snip(done: () -> Unit) {
        val index = selectedIndex ?: return
        locked = true; sticker(index)
        paths().getOrNull(index)?.let { cutMeasure = PathMeasure(it, true); cutLength = cutMeasure!!.length }
        if (!ValueAnimator.areAnimatorsEnabled()) { elapsed = END; invalidate(); done(); return }
        snipper = ValueAnimator.ofFloat(0f, END).apply {
            duration = END.toLong(); interpolator = LinearInterpolator()
            addUpdateListener { elapsed = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) done() }
            })
            start()
        }
        if (paused) snipper?.pause()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { viewPaths = null }
    private fun paths(): List<Path> = viewPaths ?: run {
        val r = photoRect(); val s = r.width() / original.width
        val m = Matrix().apply { setScale(s, s); postTranslate(r.left, r.top) }
        outlines.map { outlinePath(it).apply { transform(m) } }.also { viewPaths = it }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = photoRect(); val e = elapsed
        val fade = if (e < CUT) 0f else ((e - CUT) / FADE).coerceIn(0f, 1f)
        if (fade < 1f) {
            val saved = if (fade > 0f) canvas.saveLayerAlpha(null as RectF?, ((1 - fade) * 255).roundToInt()) else canvas.save()
            drawPhoto(canvas, r)
            if (e < 0f) drawLines(canvas, r) else if (e < CUT) drawCut(canvas, e)
            canvas.restoreToCount(saved)
        }
        if (e >= CUT) drawSticker(canvas, r, e)
        if (e in 0f..CUT) drawCutter(canvas, e)
        drawRing(canvas)
    }
    private fun drawPhoto(c: Canvas, r: RectF) {
        val radius = 14 * d
        fill.color = Ink.alpha(Ink.INK, .06f); c.drawRoundRect(RectF(r).apply { offset(0f, 3 * d) }, radius, radius, fill)
        card.reset(); card.addRoundRect(r, radius, radius, Path.Direction.CW)
        c.save(); c.clipPath(card); c.drawBitmap(original, null, r, image); c.restore()
    }
    private fun drawLines(c: Canvas, r: RectF) {
        val all = paths(); val sel = selectedIndex
        if (sel != null && sel < all.size) { c.save(); c.clipPath(card); c.clipOutPath(all[sel]); fill.color = Ink.alpha(Ink.PAPER, .6f); c.drawRect(r, fill); c.restore() }
        all.forEachIndexed { i, p ->
            val faded = sel != null && i != sel
            under.alpha = if (faded) 50 else 190; c.drawPath(p, under)
            dash.color = if (i == sel) Ink.ACCENT else Ink.INK; dash.strokeWidth = (if (i == sel) 2.6f else 2f) * d; dash.alpha = if (faded) 80 else 255
            c.drawPath(p, dash)
        }
    }
    private fun cutProgress(e: Float) = smooth.getInterpolation(min(1f, e / CUT))
    private fun drawCut(c: Canvas, e: Float) {
        val p = paths().getOrNull(selectedIndex ?: return) ?: return
        under.alpha = 190; c.drawPath(p, under)
        dash.color = Ink.ACCENT; dash.strokeWidth = 2.6f * d; dash.alpha = 255; c.drawPath(p, dash)
        cut.reset(); cutMeasure?.getSegment(0f, cutProgress(e) * cutLength, cut, true); c.drawPath(cut, trail)
    }
    private fun drawCutter(c: Canvas, e: Float) {
        val m = cutMeasure ?: return; val pos = FloatArray(2); val tan = FloatArray(2)
        m.getPosTan(cutProgress(e) * cutLength, pos, tan)
        c.save(); c.translate(pos[0], pos[1]); c.rotate(Math.toDegrees(atan2(tan[1], tan[0]).toDouble()).toFloat())
        drawScissors(c, 4f + 8f * abs(sin(e / 70f)), 48 * d)
        c.restore()
    }
    private fun drawSticker(c: Canvas, r: RectF, e: Float) {
        val index = selectedIndex ?: return; val st = sticker(index); val sub = found[index]; val s = r.width() / original.width
        val dst = RectF(r.left + (sub.left + st.left) * s, r.top + (sub.top + st.top) * s, 0f, 0f).apply { right = left + st.bitmap.width * s; bottom = top + st.bitmap.height * s }
        val t = lift.getInterpolation(((e - CUT) / LIFT).coerceIn(0f, 1f))
        val target = min(1.8f, min(r.width() * .72f / dst.width(), r.height() * .9f / dst.height()))
        val k = 1 + (target - 1) * t
        c.save(); c.translate((r.centerX() - dst.centerX()) * t, (r.centerY() - dst.centerY()) * t)
        c.rotate(-3 * t, dst.centerX(), dst.centerY()); c.scale(k, k, dst.centerX(), dst.centerY())
        shade.alpha = 20; c.save(); c.translate(0f, 4 * d / k); c.drawBitmap(st.bitmap, null, dst, shade); c.restore()
        c.drawBitmap(st.bitmap, null, dst, image)
        c.restore()
    }
    private fun drawRing(c: Canvas) {
        val at = ring ?: return
        val age = SystemClock.uptimeMillis() - ringAt
        if (age > 260 || !ValueAnimator.areAnimatorsEnabled()) { ring = null; return }
        val t = age / 260f
        pop.alpha = ((1 - t) * 255).roundToInt(); c.drawCircle(at.x, at.y, (10 + 14 * t) * d, pop)
        postInvalidateOnAnimation()
    }

    fun pause() { paused = true; snipper?.pause() }
    fun resume() { paused = false; snipper?.resume() }
    fun dispose() { snipper?.cancel(); snipper = null }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }

    companion object {
        private const val CUT = 1300f
        private const val FADE = 300f
        private const val LIFT = 550f
        const val END = 1900f
        /** Cut lines for each subject, in photo coordinates. Safe to call off the main thread. */
        fun trace(subjects: List<PhotoSubject>, photo: Bitmap): List<FloatArray> {
            val pad = max(photo.width, photo.height) * .02f
            return subjects.mapIndexed { i, s ->
                (traceOutline(s.bitmap, pad, i * 31 + photo.width) ?: floatArrayOf(-pad, -pad, s.bitmap.width + pad, -pad, s.bitmap.width + pad, s.bitmap.height + pad, -pad, s.bitmap.height + pad))
                    .also { o -> for (k in o.indices step 2) { o[k] += s.left; o[k + 1] += s.top } }
            }
        }
        /** The sticker for one subject. Safe to call off the main thread. */
        fun cutOut(subject: PhotoSubject, outline: FloatArray) =
            paintSticker(subject.bitmap, outline.copyOf().also { o -> for (k in o.indices step 2) { o[k] -= subject.left; o[k + 1] -= subject.top } })
    }
}
