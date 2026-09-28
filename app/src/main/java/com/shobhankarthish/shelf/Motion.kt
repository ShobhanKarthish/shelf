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

/**
 * A title in the display face: white, outlined in ink, on a hard shadow. It shrinks to fit its width,
 * and [pop] drops its letters in one after another.
 */
class OutlinedTitle(context: Context, private val face: Typeface, private val sizeSp: Float) : View(context) {
    private val d=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=face; strokeJoin=Paint.Join.ROUND }
    private var drop=FloatArray(0)
    var text=""
        set(value) { field=value; contentDescription=value; drop=FloatArray(value.length) { 1f }; requestLayout(); invalidate() }
    init { isAccessibilityHeading=true; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun onMeasure(w: Int, h: Int)=setMeasuredDimension(MeasureSpec.getSize(w),(sizeSp*d*1.3f).roundToInt())
    override fun onDraw(c: Canvas) {
        if(text.isEmpty()) return
        // Follows the font size setting, up to what the view's height leaves room for.
        @Suppress("DEPRECATION") var size=sizeSp*min(resources.displayMetrics.scaledDensity,d*1.15f)
        paint.textSize=size
        val room=width-12*d
        if(paint.measureText(text)>room) { size*=room/paint.measureText(text); paint.textSize=size }
        val widths=FloatArray(text.length); paint.getTextWidths(text,widths)
        val total=widths.sum(); val baseline=height/2f-(paint.descent()+paint.ascent())/2
        val line=size*.09f; val lift=size*.07f
        for(pass in 0..2) {
            var x=(width-total)/2
            text.forEachIndexed { i,ch ->
                val p=drop[i]
                if(p>0f) {
                    c.save(); c.translate(x+widths[i]/2,baseline-(1-p)*26*d); c.rotate(-12*(1-p))
                    paint.alpha=(p*255).roundToInt().coerceIn(0,255)
                    when(pass) {
                        0 -> { paint.style=Paint.Style.FILL_AND_STROKE; paint.strokeWidth=line*2; paint.color=Ink.INK; paint.alpha=(p*255).roundToInt(); c.drawText(ch.toString(),-widths[i]/2+lift,lift,paint) }
                        1 -> { paint.style=Paint.Style.FILL_AND_STROKE; paint.strokeWidth=line*2; paint.color=Ink.INK; paint.alpha=(p*255).roundToInt(); c.drawText(ch.toString(),-widths[i]/2,0f,paint) }
                        else -> { paint.style=Paint.Style.FILL; paint.color=Color.WHITE; paint.alpha=(p*255).roundToInt(); c.drawText(ch.toString(),-widths[i]/2,0f,paint) }
                    }
                    c.restore()
                }
                x+=widths[i]
            }
        }
    }
    /** Drops the letters in, one after another. */
    fun pop() {
        if(!animating) return
        val n=text.length; drop=FloatArray(n)
        tween(420L+35L*n,update={ t -> val ms=t*(420+35*n); for(i in 0 until n) drop[i]=spring.getInterpolation(((ms-35*i)/420f).coerceIn(0f,1f)); invalidate() })
    }
}

/** A sunburst sticker with a number on it, for how many things a book holds. It spins round to a new number. */
class BurstBadge(context: Context, private val face: Typeface) : View(context) {
    private val d=resources.displayMetrics.density
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; color=Ink.INK; strokeJoin=Paint.Join.ROUND }
    private val words=Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER; color=Ink.INK }
    private var count=0
    init { rotation=12f; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        val r=min(width,height)/2f; val cx=width/2f; val cy=height/2f
        val star=burstPath(cx,cy,r-1.5f*d,r*.8f,16)
        fill.color=Ink.SUN; c.drawPath(star,fill); line.strokeWidth=2.2f*d; c.drawPath(star,line)
        words.typeface=face; words.textSize=r*.62f; c.drawText(count.toString(),cx,cy+r*.12f,words)
        words.typeface=Typeface.DEFAULT_BOLD; words.textSize=r*.27f; c.drawText(if(count==1) "thing" else "things",cx,cy+r*.45f,words)
    }
    /** Shows [n]; with [spin], the badge whirls round and the number changes halfway. */
    fun show(n: Int, spin: Boolean) {
        if(!spin || !animating) { count=n; invalidate(); return }
        var switched=false
        tween(600,update={ t ->
            rotation=keys(t,12f,-170f,372f); scaleX=keys(t,1f,.6f,1f); scaleY=scaleX
            if(!switched && t>.45f) { switched=true; count=n; invalidate() }
        },end={ rotation=12f; scaleX=1f; scaleY=1f; count=n; invalidate() })
    }
}

/** The desk: cobalt, printed all over with faint stars, hearts and squiggles, like wrapping paper. */
class DeskBackground(private val d: Float) : Drawable() {
    private val paint=Paint()
    private val tile: BitmapShader by lazy {
        val s=(120*d).roundToInt(); val b=Bitmap.createBitmap(s,s,Bitmap.Config.ARGB_8888); val c=Canvas(b)
        c.drawColor(Ink.COBALT)
        drawMark(c,Mark.STAR,16*d,18*d,16*d,Ink.SKY,0f,140)
        drawMark(c,Mark.SPARKLE,77*d,29*d,14*d,Ink.SUN,0f,140)
        drawMark(c,Mark.HEART,37*d,79*d,14*d,Ink.BLUSH,0f,140)
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        p.color=Ink.alpha(Ink.MINT,.6f); c.drawCircle(96*d,88*d,4*d,p)
        p.color=Ink.alpha(Color.WHITE,.5f); c.drawCircle(52*d,44*d,2.5f*d,p)
        p.style=Paint.Style.STROKE; p.strokeWidth=2.5f*d; p.strokeCap=Paint.Cap.ROUND; p.color=Ink.alpha(Ink.SKY,.5f)
        c.drawPath(Path().apply { moveTo(62*d,66*d); for(i in 0 until 4) { val x=62*d+i*7.5f*d; quadTo(x+3.75f*d,(if(i%2==0) 61 else 71)*d,x+7.5f*d,66*d) } },p)
        BitmapShader(b,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT)
    }
    override fun draw(c: Canvas) { paint.shader=tile; c.drawRect(bounds,paint) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.OPAQUE
}

/** Two-tone checks, for the calendar's wall. */
class CheckerBackground(private val a: Int, private val b: Int, private val cell: Float) : Drawable() {
    private val paint=Paint()
    private val tile: BitmapShader by lazy {
        val s=(cell*2).roundToInt(); val bm=Bitmap.createBitmap(s,s,Bitmap.Config.ARGB_8888); val c=Canvas(bm)
        c.drawColor(a); val p=Paint().apply { color=b }; c.drawRect(0f,0f,s/2f,s/2f,p); c.drawRect(s/2f,s/2f,s.toFloat(),s.toFloat(),p)
        BitmapShader(bm,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT)
    }
    override fun draw(c: Canvas) { paint.shader=tile; c.drawRect(bounds,paint) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.OPAQUE
}

/** Sunshine: yellow, with rays fanning out from behind a thing, so it sits in its own spotlight. [centerY] is in pixels. */
class RaysBackground(var centerY: Float) : Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas) {
        val b=bounds; c.drawColor(Ink.SUN)
        val cx=b.exactCenterX(); val r=hypot(b.width().toFloat(),b.height().toFloat())
        paint.color=Color.rgb(255,219,114)
        val rays=Path()
        for(i in 0 until 18) {
            val a0=Math.toRadians(i*20.0); val a1=Math.toRadians(i*20.0+10)
            rays.moveTo(cx,centerY); rays.lineTo(cx+(r*cos(a0)).toFloat(),centerY+(r*sin(a0)).toFloat()); rays.lineTo(cx+(r*cos(a1)).toFloat(),centerY+(r*sin(a1)).toFloat()); rays.close()
        }
        c.drawPath(rays,paint)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.OPAQUE
}

/** Blue rules across a card, like a page of a notebook, [every] pixels apart. */
class RuledLines(private val every: Float, private val first: Float, private val lift: Float) : Drawable() {
    private val paint=Paint().apply { color=Color.rgb(207,227,255) }
    override fun draw(c: Canvas) {
        val b=bounds; var y=b.top+first+every
        while(y<b.bottom-lift-8) { c.drawRect(b.left+lift,y,b.right-2*lift,y+max(1f,every/28f),paint); y+=every }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** A star that's white when off and red when on; [spin] whirls it round as it changes. */
class StarDrawable(private val size: Int) : Drawable() {
    var on=false
        set(value) { field=value; invalidateSelf() }
    private var turn=0f; private var grow=1f
    init { setBounds(0,0,size,size) }
    override fun draw(c: Canvas) {
        c.save(); c.scale(grow,grow,bounds.exactCenterX(),bounds.exactCenterY())
        drawMark(c,Mark.STAR,bounds.exactCenterX(),bounds.exactCenterY(),size.toFloat(),if(on) Ink.ACCENT else Color.WHITE,turn)
        c.restore()
    }
    fun spin()=tween(520,update={ t -> turn=144f*t; grow=keys(t,1f,1.8f,1f); invalidateSelf() },end={ turn=0f; grow=1f; invalidateSelf() })
    override fun getIntrinsicWidth()=size
    override fun getIntrinsicHeight()=size
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
