package com.shobhankarthish.shelf

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

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

class SubjectSelectionView(context: Context, private val original: Bitmap) : View(context) {
    var subjects: List<PhotoSubject> = emptyList()
    var onSelection: (Int?) -> Unit = {}
    var selectedIndex: Int? = null
        private set
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var alpha: Bitmap? = null
    private var background: Bitmap? = null
    private var softened: Bitmap? = null
    private var progress = 0f
    private var floating = 0f
    private var revealing = false
    private var selectionLocked = false
    private var animator: ValueAnimator? = null
    private var paused = false
    private val haloBlur = BlurMaskFilter(3*density,BlurMaskFilter.Blur.OUTER)
    private val shadowBlur = BlurMaskFilter(12*density,BlurMaskFilter.Blur.NORMAL)
    private fun smooth(value: Float): Float { val t = value.coerceIn(0f,1f); return t*t*(3-2*t) }
    private fun photoRect(): RectF {
        val scale = minOf((width-24*density)/original.width, (height-24*density)/original.height)
        val w = original.width*scale; val h = original.height*scale
        return RectF((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
    }
    fun subjectAt(x: Float, y: Float): Int? {
        val rect = photoRect()
        if (!rect.contains(x,y)) return null
        val px = (x-rect.left)*original.width/rect.width()
        val py = (y-rect.top)*original.height/rect.height()
        return subjects.indices.lastOrNull { subjects[it].contains(px,py) }
    }
    fun select(index: Int?) {
        if (selectionLocked) return
        selectedIndex = index?.takeIf { it in subjects.indices }
        alpha?.recycle()
        alpha = selectedIndex?.let { subjects[it].bitmap.extractAlpha() }
        invalidate(); onSelection(selectedIndex)
    }
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = !selectionLocked
        override fun onLongPress(e: MotionEvent) {
            if (selectionLocked) return
            val index = subjectAt(e.x,e.y)
            select(index)
            if(index != null) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean { performClick(); return true }
    })
    init {
        // Software rendering supports the same soft alpha halo on Android 9 and newer.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        contentDescription = "Photo. Press and hold an object to select it, or use the object buttons below."
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility") // GestureDetector calls performClick for taps; buttons offer accessible selection.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestures.onTouchEvent(event)
        return handled || event.actionMasked == MotionEvent.ACTION_UP
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    fun lockSelection() { selectionLocked=true }
    fun reveal(done: () -> Unit) {
        val subject = selectedIndex?.let { subjects[it] } ?: return
        selectionLocked = true
        revealing = true
        if (!ValueAnimator.areAnimatorsEnabled()) { progress=1f; invalidate(); done(); return }
        background = original.copy(Bitmap.Config.ARGB_8888,true).also {
            val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
            Canvas(it).drawBitmap(subject.bitmap,subject.left.toFloat(),subject.top.toFloat(),clear)
        }
        // ponytail: a filtered small backdrop approximates blur; use a shader if larger photos need finer mist.
        softened = Bitmap.createScaledBitmap(background!!,maxOf(1,original.width/32),maxOf(1,original.height/32),true)
        animator = ValueAnimator.ofFloat(0f,1f).apply {
            duration=3000; interpolator=LinearInterpolator()
            addUpdateListener { progress=it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled=false
                override fun onAnimationCancel(animation: Animator) { cancelled=true }
                override fun onAnimationEnd(animation: Animator) {
                    if(cancelled) return
                    done()
                    if(!isAttachedToWindow) return
                    animator=ValueAnimator.ofFloat(0f,1f).apply {
                        duration=9000; repeatCount=ValueAnimator.INFINITE
                        addUpdateListener { floating=sin((it.animatedValue as Float)*2*PI).toFloat(); invalidate() }
                        start()
                    }
                    if(paused) animator?.pause()
                }
            }); start()
        }
        if(paused) animator?.pause()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect=photoRect()
        paint.reset(); paint.isAntiAlias=true; paint.isFilterBitmap=true
        val subject=selectedIndex?.let { subjects[it] }
        if(subject==null) { canvas.drawBitmap(original,null,rect,paint); return }
        val t=progress*11f
        val dissolve=smooth((t-2.5f)/5.8f)
        val release=smooth((t-6f)/4f)
        val breath=1f+.012f*smooth((t-.6f)/1.9f)*(1f-smooth((t-2.5f)/2.7f))
        canvas.save(); canvas.scale(breath,breath,rect.centerX(),rect.centerY())
        val scale=rect.width()/original.width
        val target=RectF(rect.left+subject.left*scale,rect.top+subject.top*scale,
            rect.left+(subject.left+subject.bitmap.width)*scale,rect.top+(subject.top+subject.bitmap.height)*scale)
        if(!revealing) {
            canvas.drawBitmap(original,null,rect,paint)
            paint.color=Color.argb(24,41,45,40); canvas.drawRect(rect,paint)
        } else {
            paint.alpha=((1-dissolve)*(1-dissolve)*255).toInt()
            canvas.drawBitmap(background ?: original,null,rect,paint)
            paint.alpha=((1-dissolve)*dissolve*255).toInt()
            softened?.let { canvas.drawBitmap(it,null,rect,paint) }
            paint.alpha=255
            paint.color=Color.argb((release*24).toInt(),75,64,45)
            paint.maskFilter=shadowBlur
            canvas.drawOval(RectF(target.left+target.width()*.15f,target.bottom-2*density,target.right-target.width()*.15f,target.bottom+5*density),paint)
            paint.maskFilter=null
        }
        target.offset(0f,(-7*release+floating*1.5f)*density)
        paint.color=Color.WHITE; paint.alpha=if(revealing) (sin(PI*dissolve)*70).toInt().coerceIn(0,255) else 160
        paint.maskFilter=haloBlur
        alpha?.let { canvas.drawBitmap(it,null,target,paint) }
        paint.maskFilter=null; paint.alpha=255
        canvas.drawBitmap(subject.bitmap,null,target,paint)
        if(revealing) {
            paint.color=Color.rgb(154,153,134)
            for(i in 0..5) {
                paint.alpha=(release*22).toInt()
                val x=target.centerX()+sin(i*2.4).toFloat()*target.width()*.8f
                val y=target.centerY()+(i%3-1)*target.height()*.4f-release*15*density
                canvas.drawCircle(x,y,density*.7f,paint)
            }
        }
        canvas.restore()
    }
    fun pause() { paused=true; animator?.pause() }
    fun resume() { paused=false; animator?.resume() }
    fun dispose() { animator?.cancel(); animator=null }
    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }
}
