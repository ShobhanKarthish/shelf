package com.shobhankarthish.shelf

import android.animation.*
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.*

/*
 * How Shelf moves. Everything you press first sinks onto its own shadow, like pressing a sticker flat,
 * then does one short thing that suits what it is. Nothing moves on its own, and with system animations
 * off every motion jumps straight to its end.
 */

val animating get()=ValueAnimator.areAnimatorsEnabled()

/** Runs [update] from 0 to 1 over [ms], then [end]. With animations off it jumps straight to the end. */
fun tween(ms: Long, ease: TimeInterpolator=LinearInterpolator(), delay: Long=0, update: (Float) -> Unit, end: () -> Unit={}): ValueAnimator? {
    if(!animating) { update(1f); end(); return null }
    return ValueAnimator.ofFloat(0f,1f).apply {
        duration=ms; startDelay=delay; interpolator=ease
        addUpdateListener { update(it.animatedValue as Float) }
        addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { end() } })
        start()
    }
}
/** Eases through [values], spaced evenly over [t] from 0 to 1, like CSS keyframes. */
fun keys(t: Float, vararg values: Float): Float {
    if(values.size==1) return values[0]
    val x=t.coerceIn(0f,1f)*(values.size-1); val i=min(values.size-2,x.toInt()); val f=x-i
    val s=f*f*(3-2*f)
    return values[i]+(values[i+1]-values[i])*s
}
/** A gentle overshoot, for things landing in place. */
val spring: TimeInterpolator get()=TimeInterpolator { t -> val u=t-1; 1+u*u*((2.2f+1)*u+2.2f) }

/** While pressed, the view slides [lift] down and right, onto where its shadow was. */
fun View.pressDown(lift: Float) {
    fun move(to: Float, ms: Long)=AnimatorSet().apply { playTogether(ObjectAnimator.ofFloat(this@pressDown,"translationX",to),ObjectAnimator.ofFloat(this@pressDown,"translationY",to)); duration=ms }
    stateListAnimator=StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed),move(lift,60))
        addState(intArrayOf(),move(0f,120))
    }
}

/**
 * A card or pill in the house style: [fill] outlined in ink on a hard shadow [lift] below and to the right.
 * The shadow sits inside the bounds, so pad the right and bottom by [lift]. Pressed, the shadow goes; use [pressDown]
 * so the card moves onto it.
 */
class PopDrawable(fill: Int, private val radius: Float, private val line: Float, private val lift: Float) : Drawable() {
    var fill=fill
        set(value) { field=value; invalidateSelf() }
    private var pressed=false
    private var enabled=true
    private val rect=RectF()
    override fun isStateful()=true
    override fun onStateChange(state: IntArray): Boolean {
        val p=android.R.attr.state_pressed in state; val e=android.R.attr.state_enabled in state || state.isEmpty()
        if(p==pressed && e==enabled) return false
        pressed=p; enabled=e; invalidateSelf(); return true
    }
    override fun draw(c: Canvas) {
        val b=bounds; rect.set(b.left.toFloat(),b.top.toFloat(),b.right-lift,b.bottom-lift)
        drawPop(c,rect,min(radius,rect.height()/2),if(enabled) fill else Ink.light(fill,.5f),line,if(pressed || !enabled) 0f else lift)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** Stars, hearts and sparkles thrown out from a point and falling away: the reward for adding something or loving it. */
object Confetti {
    private class Piece(val mark: Mark, val color: Int, val dx: Float, val dy: Float, val size: Float, val spin: Float)
    private val colors=intArrayOf(Ink.SUN,Ink.BLUSH,Ink.MINT,Ink.SKY,Ink.ACCENT,Color.WHITE)
    /** Throws [count] doodles from [x],[y] in [from]'s own pixels, up to [spread] dp away. */
    fun burst(from: View, x: Float, y: Float, count: Int=12, spread: Float=110f) {
        if(!animating || !from.isAttachedToWindow) return
        val root=from.rootView; val d=from.resources.displayMetrics.density
        val a=IntArray(2); val r=IntArray(2); from.getLocationInWindow(a); root.getLocationInWindow(r)
        val ox=a[0]-r[0]+x; val oy=a[1]-r[1]+y
        val rng=Rng((System.nanoTime() and 0xffff).toInt())
        val pieces=List(count) { i ->
            val angle=i*2*PI/count+rng.between(-.3f,.3f); val dist=rng.between(spread*.55f,spread)*d
            Piece(arrayOf(Mark.STAR,Mark.HEART,Mark.SPARKLE,Mark.STAR)[i%4],colors[i%colors.size],(cos(angle)*dist).toFloat(),(sin(angle)*dist).toFloat(),rng.between(14f,26f)*d,rng.between(-200f,200f))
        }
        val layer=object : Drawable() {
            var t=0f
            override fun draw(c: Canvas) {
                val k=1-(1-t)*(1-t); val fall=30*d*t*t
                val scale=if(t<.55f) .2f+.9f*(t/.55f) else 1.1f-.4f*((t-.55f)/.45f)
                val alpha=if(t<.55f) 255 else (255*(1-(t-.55f)/.45f)).roundToInt()
                pieces.forEach { p -> drawMark(c,p.mark,ox+p.dx*k,oy+p.dy*k+fall,p.size*scale,p.color,p.spin*t,alpha.coerceIn(0,255)) }
            }
            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(filter: ColorFilter?) {}
            @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
        }
        layer.setBounds(0,0,root.width,root.height)
        root.overlay.add(layer)
        tween(800,DecelerateInterpolator(1.2f),update={ layer.t=it; layer.invalidateSelf() },end={ root.overlay.remove(layer) })
    }
}
