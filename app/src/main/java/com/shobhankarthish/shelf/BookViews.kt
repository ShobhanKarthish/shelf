package com.shobhankarthish.shelf

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import kotlin.math.*

/** Loads the sticker or print for a thing at about [size] pixels, then calls back on the main thread. */
typealias Loader=(item: Keepsake, size: Int, done: (Bitmap?) -> Unit) -> Unit

private val moving get()=ValueAnimator.areAnimatorsEnabled()
/** Puts the camera [distance] pixels in front of the view, as CSS perspective does; Android measures it in 72ths of an inch. */
private fun View.perspective(distance: Float) { cameraDistance=distance*resources.displayMetrics.densityDpi/72f }
/** Android's rotationY sign that lifts a page toward you as it turns. */
private const val SWING=-1f

/** A round button: white with an ink icon, see-through when it's the unselected half of a pair, or the red add button. */
@SuppressLint("AppCompatCustomView")
class RoundButton(context: Context, private val doodle: Doodle, label: String, style: Style=Style.WHITE) : Button(context) {
    enum class Style { WHITE, GLASS, RED }
    private val d=resources.displayMetrics.density
    private val icon=Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    private val disc=Paint(Paint.ANTI_ALIAS_FLAG)
    var look=style
        set(value) { field=value; invalidate() }
    private var glyph=Path()
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s=(if(look==Style.RED) 22f else 20f)*d; val cx=w/2f; val cy=h/2f-d
        glyph=doodlePath(doodle,RectF(cx-s/2,cy-s/2,cx+s/2,cy+s/2))
    }
    init {
        text=""; contentDescription=label; stateListAnimator=null; minWidth=0; minimumWidth=0; minHeight=0; minimumHeight=0; setPadding(0,0,0,0)
        background=null; foreground=RippleDrawable(ColorStateList.valueOf(Ink.alpha(Ink.INK,.14f)),null,ShapeDrawable(OvalShape()))
    }
    override fun onDraw(canvas: Canvas) {
        val r=min(width,height)/2f-3*d; val cx=width/2f; val cy=height/2f-d
        when(look) {
            Style.GLASS -> { disc.color=Color.argb(26,255,255,255); disc.clearShadowLayer(); canvas.drawCircle(cx,cy,r,disc); icon.color=Color.argb(107,255,255,255); icon.strokeWidth=d; canvas.drawCircle(cx,cy,r-d/2,icon) }
            Style.RED -> { disc.color=Ink.ACCENT; disc.setShadowLayer(8*d,0f,4*d,Color.argb(110,160,50,20)); canvas.drawCircle(cx,cy,r,disc) }
            Style.WHITE -> { disc.color=Color.WHITE; disc.setShadowLayer(3*d,0f,1.5f*d,Color.argb(70,28,30,40)); canvas.drawCircle(cx,cy,r,disc) }
        }
        icon.color=if(look==Style.WHITE) Ink.INK else Color.WHITE
        icon.strokeWidth=(if(doodle==Doodle.MORE) 3f else if(look==Style.RED) 2.4f else 1.8f)*d
        canvas.drawPath(glyph,icon)
    }
}

/** How a page's 360-unit collage fits the screen: [k] pixels per unit, with its top-left corner at [ox],[oy]. */
class PageFit(val k: Float, val ox: Float, val oy: Float)
fun pageFit(width: Int, height: Int, top: Int, bottom: Int, d: Float): PageFit {
    val usable=height-top-bottom-(64+84)*d
    val k=max(.45f*d,min(width/360f,usable/PageLayout.HEIGHT))
    return PageFit(k,(width-360*k)/2,top+64*d)
}

/** Where things go on a page when nobody has arranged them: four slots, each thing a little off, always the same way. */
object PageLayout {
    const val PER=4
    const val HEIGHT=590f
    private val slots=arrayOf(floatArrayOf(118f,96f,172f),floatArrayOf(264f,182f,140f),floatArrayOf(112f,316f,152f),floatArrayOf(262f,370f,130f))
    fun pages(count: Int)=max(1,(count+PER-1)/PER)
    fun pageOf(indexInBook: Int)=max(0,indexInBook)/PER
    /** Where the first thing starts: under the book's name on the first page, near the top on the others. */
    fun top(title: CutLetters?)=if(title==null) 12f else title.height+8f
    fun auto(item: Keepsake, slot: Int, top: Float): Placement {
        val r=Rng(seedOf(item.id)); val s=slots[slot.coerceIn(0,PER-1)]
        return Placement(s[0]+r.between(-10f,10f),top+s[1]+r.between(-10f,10f),r.between(-8f,8f),s[2]*r.between(.92f,1.06f),1)
    }
    fun title(book: Book)=CutLetters(book.name,28f,seedOf(book.id)+1,230f)
}

/** Width and height of a thing that fits a [size]-unit square. */
fun boxOf(aspect: Float, size: Float)=if(aspect>=1f) PointF(size,size/aspect) else PointF(size*aspect,size)

interface ThingHost {
    val arranging: Boolean
    fun open(thing: ThingView)
    fun choose(thing: ThingView)
    fun moved(thing: ThingView)
}

/**
 * One thing on a page: its sticker or print, the kraft tag on its string, and the start of its story in pen.
 * The view reaches past the sticker ([padLeft] and so on, in page units) to hold the tag, the note and the twist handle,
 * but only a touch on the sticker itself belongs to it.
 */
@SuppressLint("ViewConstructor") // Made in code with what it shows; never inflated from XML.
class ThingView(context: Context, val item: Keepsake, var place: Placement, private val paper: Paper, private val k: Float, private val hand: Typeface, private val host: ThingHost) : View(context) {
    val w: Float; val h: Float
    val padLeft: Float; val padTop: Float; val padRight: Float; val padBottom: Float
    private val d=resources.displayMetrics.density
    private var bitmap: Bitmap?=null
    private val right=place.x>180f
    private val rng=Rng(seedOf(item.id)+7)
    private val tagAngle=rng.between(-4f,4f)
    private val noteAngle=rng.between(-2.5f,2.5f)
    private val note: PenNote?
    private val image=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade=Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter=PorterDuffColorFilter(Ink.INK,PorterDuff.Mode.SRC_IN); alpha=22 }
    private val outline=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=1.6f; color=Pen.BLUE }
    private val handle=Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashes=DashPathEffect(floatArrayOf(5f,4f),0f)
    private val dst=RectF()
    private val twist: Path
    private val string: Path
    var chosen=false
        set(value) { field=value; invalidate() }
    init {
        val box=boxOf(item.aspect,place.size); w=box.x; h=box.y
        note=snippet(item.story).takeIf { it.isNotEmpty() }?.let { PenNote(it,hand,max(w,150f),right,paper.dark) }
        val tag=172f; val noteWidth=note?.width ?: 0f
        padTop=HANDLE
        padLeft=maxOf(HANDLE,if(right) tag-w*.64f+4f else 0f,if(right) noteWidth+4f-w else 0f)
        padRight=maxOf(HANDLE,if(right) 0f else w*.36f+tag-w+4f,if(right) 0f else noteWidth+4f-w)
        padBottom=maxOf(HANDLE,40f+(note?.height?.plus(12f) ?: 0f))
        contentDescription=item.title; isFocusable=true; isClickable=true
        rotation=place.angle
        val tx=if(right) w*.64f else w*.36f; val ty=h+4f; val hole=if(right) tx-8f else tx+8f
        string=Path().apply { moveTo(w*.5f,h*.8f); cubicTo(w*.5f,h+2f,hole,ty+2f,hole,ty+13f) }
        twist=doodlePath(Doodle.ROTATE,RectF(w,-16f,w+16f,0f))
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { pivotX=(padLeft+this.w/2)*k; pivotY=(padTop+this.h/2)*k }
    fun show(b: Bitmap?) { bitmap=b; invalidate() }
    /** Pixel size to load the image at, in steps so neighbouring sizes share the cache. */
    val loadSize get()=((max(w,h)*k/64f).roundToInt()+1)*64

    override fun onDraw(c: Canvas) {
        c.save(); c.scale(k,k); c.translate(padLeft,padTop)
        drawString(c,string)
        bitmap?.let { b ->
            val s=min(w/b.width,h/b.height); val bw=b.width*s; val bh=b.height*s
            dst.set((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2)
            c.save(); c.translate(0f,3f); c.drawBitmap(b,null,dst,shade); c.restore()
            c.drawBitmap(b,null,dst,image)
        }
        if(item.framed) drawFastener(c,paper,w,seedOf(item.id))
        if(host.arranging) {
            outline.pathEffect=if(chosen) null else dashes; outline.alpha=if(chosen) 255 else 115
            c.drawRect(-7f,-7f,w+7f,h+7f,outline)
            if(chosen) {
                handle.style=Paint.Style.FILL; handle.color=Color.WHITE; c.drawCircle(w+8f,-8f,16f,handle)
                handle.style=Paint.Style.STROKE; handle.color=Pen.BLUE; handle.strokeWidth=1.6f; c.drawCircle(w+8f,-8f,16f,handle)
                handle.strokeCap=Paint.Cap.ROUND; c.drawPath(twist,handle)
            }
        }
        c.restore()
    }

    /** The tag and note, drawn by the page above every sticker so a neighbour never hides a name. [c] is already in this thing's units. */
    fun drawLabels(c: Canvas) {
        val tx=if(right) w*.64f else w*.36f; val ty=h+4f
        drawTag(c,item.title,tx,ty,tagAngle,hand,holeRight=right)
        note?.draw(c,if(right) w-4f-note.width else 4f,ty+32f,noteAngle)
    }
    private fun moved() { (parent as? Leaf)?.labels?.invalidate() }
    private var downRawX=0f; private var downRawY=0f; private var startX=0f; private var startY=0f; private var startAngle=0f
    private var twisting=false; private var travelled=false; private var aim=0.0
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    @SuppressLint("ClickableViewAccessibility") // Taps call performClick; arranging is a drag.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val ux=e.x/k-padLeft; val uy=e.y/k-padTop
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                twisting=host.arranging && chosen && hypot(ux-(w+8f),uy+8f)<24f
                if(!twisting && (ux<-6f || uy<-6f || ux>w+6f || uy>h+6f)) return false
                downRawX=e.rawX; downRawY=e.rawY; startX=place.x; startY=place.y; startAngle=place.angle; travelled=false
                if(host.arranging) { parent.requestDisallowInterceptTouchEvent(true); if(!chosen) host.choose(this); aim=angleTo(e) }
                isPressed=!host.arranging
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if(hypot(e.rawX-downRawX,e.rawY-downRawY)>slop) { travelled=true; isPressed=false }
                if(!host.arranging) return true
                if(twisting) {
                    val a=(startAngle+Math.toDegrees(angleTo(e)-aim)).toFloat()
                    place=place.copy(angle=((a+540f)%360f-180f).coerceIn(-45f,45f)); rotation=place.angle; moved()
                } else {
                    place=place.copy(x=(startX+(e.rawX-downRawX)/k).coerceIn(20f,340f),y=(startY+(e.rawY-downRawY)/k).coerceIn(10f,PageLayout.HEIGHT-10f))
                    translationX=(place.x-startX)*k; translationY=(place.y-startY)*k; moved()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed=false
                if(host.arranging) { if(travelled) host.moved(this) } else if(!travelled) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { isPressed=false; if(host.arranging && travelled) host.moved(this); return true }
        }
        return false
    }
    private fun angleTo(e: MotionEvent): Double {
        val at=IntArray(2); (parent as View).getLocationOnScreen(at)
        val cx=at[0]+left+translationX+pivotX; val cy=at[1]+top+translationY+pivotY
        return atan2((e.rawY-cy).toDouble(),(e.rawX-cx).toDouble())
    }
    override fun performClick(): Boolean { super.performClick(); if(!host.arranging) host.open(this); return true }

    /** The one moment worth animating: a new thing lands on its page and sticks. */
    fun stickIn() {
        if(!moving) return
        alpha=0f; scaleX=1.4f; scaleY=1.4f; translationY=-46*k; rotation=place.angle-10f
        animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).rotation(place.angle).setStartDelay(120).setDuration(700).setInterpolator(OvershootInterpolator(1.3f))
            .setUpdateListener { moved() }.withEndAction { moved() }.start()
    }
    companion object { private const val HANDLE=28f }
}

/**
 * One page of a book: its paper and the things stuck on it. [lift] darkens it as it turns away;
 * [spine] is the shadow cast on it by a page turning over it.
 */
@SuppressLint("ViewConstructor")
class Leaf(context: Context, val book: Book, val index: Int, private val pages: Int, items: List<Keepsake>, private val bookEmpty: Boolean,
           val fit: PageFit, private val hand: Typeface, load: Loader, host: ThingHost) : FrameLayout(context) {
    private val d=resources.displayMetrics.density
    val things=ArrayList<ThingView>()
    private val title=if(index==0) PageLayout.title(book) else null
    val top=PageLayout.top(title)
    private val stamp=items.minOfOrNull { it.created }?.let { stampDate(it) }
    private val paperRect=RectF()
    private val shadeView=View(context).apply { background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Ink.alpha(Ink.INK,0f),Ink.alpha(Ink.INK,.05f),Ink.alpha(Ink.INK,.34f))); alpha=0f; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    /** Every thing's tag and note, above all the stickers. */
    val labels=object : View(context) {
        override fun onDraw(c: Canvas) {
            for(i in 0 until this@Leaf.childCount) {
                val t=this@Leaf.getChildAt(i) as? ThingView ?: continue
                if(t.alpha<=0f) continue
                val saved=if(t.alpha<1f) c.saveLayerAlpha(null,(t.alpha*255).roundToInt()) else c.save()
                c.translate(t.left.toFloat(),t.top.toFloat()); c.concat(t.matrix); c.scale(fit.k,fit.k); c.translate(t.padLeft,t.padTop)
                t.drawLabels(c)
                c.restoreToCount(saved)
            }
        }
    }.apply { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val spineView=View(context).apply { background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.argb(100,20,20,30),Color.argb(24,20,20,30),Color.argb(0,20,20,30),Color.argb(0,20,20,30))); alpha=0f; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    var lift: Float
        get()=shadeView.alpha
        set(value) { shadeView.alpha=value }
    var spine: Float
        get()=spineView.alpha
        set(value) { spineView.alpha=value }
    init {
        setWillNotDraw(false); clipChildren=false
        items.forEachIndexed { slot,item ->
            val t=ThingView(context,item,item.place ?: PageLayout.auto(item,slot,top),book.paper,fit.k,hand,host)
            things+=t; addView(t,paramsFor(t))
            load(item,t.loadSize) { t.show(it) }
        }
        things.sortedBy { it.place.z }.forEach { it.bringToFront() }
        addView(labels,LayoutParams(-1,-1)); addView(spineView,LayoutParams(-1,-1)); addView(shadeView,LayoutParams(-1,-1))
    }
    fun paramsFor(t: ThingView): LayoutParams {
        val k=fit.k
        return LayoutParams(((t.w+t.padLeft+t.padRight)*k).roundToInt(),((t.h+t.padTop+t.padBottom)*k).roundToInt()).apply {
            leftMargin=(fit.ox+(t.place.x-t.w/2-t.padLeft)*k).roundToInt(); topMargin=(fit.oy+(t.place.y-t.h/2-t.padTop)*k).roundToInt()
        }
    }
    /** Commits a dragged thing's new place to its layout, so the drag offset can go. */
    fun settle(t: ThingView) { t.layoutParams=paramsFor(t); t.translationX=0f; t.translationY=0f; labels.invalidate() }
    fun raise(t: ThingView) { t.bringToFront(); labels.bringToFront(); spineView.bringToFront(); shadeView.bringToFront(); labels.invalidate() }

    override fun onDraw(c: Canvas) {
        paperRect.set(0f,0f,width.toFloat(),height.toFloat())
        drawPaper(c,book.paper,paperRect,d)
        c.save(); c.translate(fit.ox,fit.oy); c.scale(fit.k,fit.k)
        title?.draw(c,20f,0f)
        stamp?.let { drawStamp(c,it,340f-stampWidth(it),if(index==0) top+8f else 0f,if(index==0) 5f else -4f,book.paper.dark) }
        if(bookEmpty && index==0) drawSlot(c,RectF(80f,top+40f,280f,top+210f),"your first\nthing goes here",hand,book.paper.dark)
        c.restore()
        c.save(); c.scale(d,d)
        drawPageEdges(c,width/d,18f,height/d-18f,min(3,pages-1-index),true)
        drawPageEdges(c,0f,18f,height/d-18f,min(3,index),false)
        c.restore()
    }
}

/**
 * A book open on the screen. Drag a page sideways, or call [turn], and it swings around the spine on the
 * left like paper, with the next page already underneath.
 */
@SuppressLint("ViewConstructor")
class BookView(context: Context, private val pages: Int, start: Int, private val make: (index: Int, fit: PageFit) -> Leaf,
               private val fitFor: (w: Int, h: Int) -> PageFit, private val onTurned: (Int) -> Unit) : FrameLayout(context) {
    private val d=resources.displayMetrics.density
    var page=start.coerceIn(0,pages-1); private set
    var current: Leaf?=null; private set
    var arranging=false
    private var fit: PageFit?=null
    private var turning: Turn?=null
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f; private var downY=0f; private var dragging=false; private var rubber=false
    private var tracker: VelocityTracker?=null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if(w==0 || h==0) return
        turning?.cancel()
        fit=fitFor(w,h)
        current?.let { removeView(it) }
        current=make(page,fit!!).also { attach(it,0) }
        onReady?.invoke(); onReady=null
    }
    /** Adds a page at [at] and sizes it straight away, so it can be drawn and turned in the same frame. */
    private fun attach(leaf: Leaf, at: Int) {
        addView(leaf,at,LayoutParams(-1,-1))
        leaf.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY))
        leaf.layout(0,0,width,height)
    }
    /** Runs once the first page has been laid out. */
    var onReady: (() -> Unit)?=null

    private inner class Turn(val dir: Int) {
        val from=current!!
        val other=make(page+dir,fit!!)
        val lifting: Leaf; val under: Leaf
        var p=0f; private var anim: ValueAnimator?=null
        init {
            if(dir>0) { attach(other,0); lifting=from; under=other } else { attach(other,childCount); lifting=other; under=from }
            lifting.pivotX=0f; lifting.pivotY=height/2f; lifting.perspective(width*3.9f)
            lifting.setLayerType(LAYER_TYPE_HARDWARE,null)
            set(0f); turning=this
        }
        fun set(value: Float) {
            p=value; val lift=if(dir>0) value else 1-value
            lifting.rotationY=SWING*100f*lift; lifting.lift=lift*.95f
            under.spine=sin(min(1f,lift)*PI).toFloat()*.75f
        }
        fun run(to: Float, ms: Long, ease: Interpolator, then: () -> Unit) {
            anim?.cancel()
            if(!moving) { set(to); then(); return }
            anim=ValueAnimator.ofFloat(p,to).apply {
                duration=ms; interpolator=ease
                addUpdateListener { set(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled=false
                    override fun onAnimationCancel(animation: Animator) { cancelled=true }
                    override fun onAnimationEnd(animation: Animator) { if(!cancelled) then() }
                })
                start()
            }
        }
        fun finish(commit: Boolean) {
            val to=if(commit) 1f else 0f
            run(to,(160+360*abs(to-p)).toLong(),DecelerateInterpolator(1.5f)) { if(commit) commit() else cancel() }
        }
        fun commit() {
            removeView(from); reset(other); current=other; page+=dir; turning=null
            onTurned(page)
        }
        fun cancel() { anim?.cancel(); removeView(other); reset(from); turning=null }
        private fun reset(l: Leaf) { l.rotationY=0f; l.lift=0f; l.spine=0f; l.translationX=0f; l.setLayerType(LAYER_TYPE_NONE,null) }
    }

    /** Turns one page forward (1) or back (-1), as the arrow buttons do. */
    fun turn(dir: Int) {
        if(turning!=null || arranging || current==null || page+dir !in 0 until pages) return
        val t=Turn(dir)
        t.run(1f,640,AccelerateDecelerateInterpolator()) { t.commit() }
    }
    /** Lifts the corner of the first page a little and lets it fall back, so it's clear pages turn. */
    fun peek() {
        if(turning!=null || arranging || current==null || pages<2 || page!=0 || !moving) return
        val t=Turn(1)
        t.run(.2f,480,DecelerateInterpolator()) { postDelayed({ if(turning===t) t.run(0f,520,AccelerateDecelerateInterpolator()) { t.cancel() } },120) }
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if(arranging) return false
        track(e)
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=e.x; downY=e.y; dragging=false; rubber=false }
            MotionEvent.ACTION_MOVE -> if(!dragging && canStart(e)) { start(e.x-downX); return true }
        }
        return dragging
    }
    @SuppressLint("ClickableViewAccessibility") // Page turns are also offered as buttons.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(arranging) return false
        track(e)
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=e.x; downY=e.y; dragging=false; rubber=false; return true }
            MotionEvent.ACTION_MOVE -> {
                if(!dragging) { if(canStart(e)) start(e.x-downX) else return true }
                val dx=e.x-downX
                turning?.let { t -> if(dragging) t.set((-dx*t.dir/(250*d)).coerceIn(0f,1f)) }
                if(rubber) current?.translationX=dx*.12f
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                if(dragging) {
                    val t=turning
                    if(t!=null) {
                        tracker?.computeCurrentVelocity(1)
                        val v=(tracker?.xVelocity ?: 0f)/d
                        t.finish(e.actionMasked==MotionEvent.ACTION_UP && (t.p>.35f || -v*t.dir>.45f))
                    }
                    if(rubber) current?.animate()?.translationX(0f)?.setDuration(220)?.start()
                }
                dragging=false; rubber=false; tracker?.recycle(); tracker=null
            }
        }
        return true
    }
    private fun track(e: MotionEvent) { if(e.actionMasked==MotionEvent.ACTION_DOWN) { tracker?.recycle(); tracker=VelocityTracker.obtain() }; tracker?.addMovement(e) }
    private fun canStart(e: MotionEvent): Boolean {
        val dx=e.x-downX; val dy=e.y-downY
        return turning==null && current!=null && abs(dx)>slop && abs(dx)>abs(dy)*1.2f
    }
    private fun start(dx: Float) {
        dragging=true; parent?.requestDisallowInterceptTouchEvent(true)
        val dir=if(dx<0) 1 else -1
        if(page+dir in 0 until pages) Turn(dir) else rubber=true
    }
}

/** A book's cover, made from its first things and its name. The open book also shows its pages fanned out behind. */
@SuppressLint("ViewConstructor")
class CoverView(context: Context, val book: Book, private val items: List<Keepsake>, private val pages: Int, private val hand: Typeface, load: Loader) : View(context) {
    private val d=resources.displayMetrics.density
    private val bitmaps=arrayOfNulls<Bitmap>(3)
    private val name=CutLetters(book.name,21f,seedOf(book.id),172f)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade=Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter=PorterDuffColorFilter(Ink.INK,PorterDuff.Mode.SRC_IN); alpha=26 }
    var leaves=false
        set(value) { field=value; invalidate() }
    init {
        items.take(3).forEachIndexed { i,item -> load(item,(170*d).roundToInt()) { b -> bitmaps[i]=b; invalidate() } }
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun onDraw(c: Canvas) {
        c.save(); c.scale(width/(WIDTH*d),height/(HEIGHT*d)) // the carousel scales covers down on short screens
        if(leaves) {
            val n=min(4,pages+(if(items.isNotEmpty()) 1 else 0))
            for(k in n downTo 1) {
                val r=RectF((28+12*k)*d,4f*k*d,(208+12*k)*d,(HEIGHT-4*k)*d)
                paint.color=Color.argb(70,0,0,0); paint.shader=null; c.drawRoundRect(RectF(r).apply { offset(-3*d,0f) },11*d,11*d,paint)
                c.save(); c.clipPath(Path().apply { addRoundRect(r,floatArrayOf(3*d,3*d,11*d,11*d,11*d,11*d,3*d,3*d),Path.Direction.CW) })
                drawPaper(c,if(k%2==1) book.paper else Paper.PLAIN,r,d,0f); c.restore()
            }
        }
        val cover=Path().apply { addRoundRect(RectF(0f,0f,210*d,HEIGHT*d),floatArrayOf(4*d,4*d,13*d,13*d,13*d,13*d,4*d,4*d),Path.Direction.CW) }
        paint.shader=null; paint.color=Color.rgb(213,189,147); paint.setShadowLayer(18*d,0f,12*d,Color.argb(150,0,0,0)); c.drawPath(cover,paint); paint.clearShadowLayer()
        c.save(); c.clipPath(cover)
        drawLinen(c,RectF(0f,0f,210*d,HEIGHT*d),d)
        paint.shader=LinearGradient(0f,0f,13*d,0f,intArrayOf(Color.argb(56,0,0,0),Color.argb(13,0,0,0),Color.argb(51,255,255,255)),floatArrayOf(0f,.7f,1f),Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,13*d,HEIGHT*d,paint); paint.shader=null
        c.scale(d,d)
        if(items.isEmpty()) {
            drawSlot(c,RectF(42f,52f,168f,202f),"your first\nthing goes here",hand,false)
            drawTape(c,84f,53f,48f,-10f,2)
        } else items.take(3).forEachIndexed { i,item ->
            val (x,y,size,angle)=SLOTS[i]
            val box=boxOf(item.aspect,if(item.framed) size else size*(if(item.aspect>=1f) 1f else .8f))
            c.save(); c.translate(x,y); c.rotate(angle,box.x/2,box.y/2)
            bitmaps[i]?.let { b ->
                val dst=RectF(0f,0f,box.x,box.y)
                c.save(); c.translate(0f,2f); c.drawBitmap(b,null,dst,shade); c.restore(); c.drawBitmap(b,null,dst,paint)
            }
            if(item.framed) drawTape(c,box.x/2,-1f,44f,4f,i)
            c.restore()
        }
        name.draw(c,24f,HEIGHT-22f-name.height)
        c.restore(); c.restore()
    }
    companion object {
        const val WIDTH=270f; const val HEIGHT=330f
        private val SLOTS=arrayOf(floatArrayOf(24f,22f,150f,-6f),floatArrayOf(110f,124f,88f,9f),floatArrayOf(18f,150f,98f,-10f))
    }
}

/** The books on the desk, one in front and its neighbours peeking in. Swipe or use the arrows to move between them. */
@SuppressLint("ViewConstructor")
class DeskCarousel(context: Context, private val covers: List<View>, index: Int, private val onPick: (Int) -> Unit, private val onOpen: (Int) -> Unit) : FrameLayout(context) {
    private val d=resources.displayMetrics.density
    var index=index; private set
    private var scale=1f
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f; private var downY=0f; private var dragging=false; private var dx=0f
    private val floor=Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        clipChildren=false; setWillNotDraw(false)
        covers.forEachIndexed { i,v -> addView(v); v.setOnClickListener { if(i==this.index) onOpen(i) else go(i) } }
    }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w=MeasureSpec.getSize(widthSpec); val h=MeasureSpec.getSize(heightSpec)
        scale=min(w/(360*d),(h-40*d)/(CoverView.HEIGHT*d)).coerceIn(.5f,1.25f)
        covers.forEach { it.measure(MeasureSpec.makeMeasureSpec((CoverView.WIDTH*d*scale).roundToInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((CoverView.HEIGHT*d*scale).roundToInt(),MeasureSpec.EXACTLY)) }
        setMeasuredDimension(w,h)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        floor.shader=RadialGradient(0f,0f,1f,intArrayOf(Color.argb(102,0,0,0),Color.argb(0,0,0,0)),null,Shader.TileMode.CLAMP)
        post { arrange(false) }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left=(width/2f-18*d*scale-105*d*scale).roundToInt(); val top=((height-CoverView.HEIGHT*d*scale-24*d)/2).roundToInt().coerceAtLeast(0)
        covers.forEach { v -> v.layout(left,top,left+v.measuredWidth,top+v.measuredHeight) }
    }
    override fun onDraw(c: Canvas) {
        val cx=width/2f-8*d*scale; val cy=(height-CoverView.HEIGHT*d*scale-24*d)/2+CoverView.HEIGHT*d*scale+12*d
        c.save(); c.translate(cx,cy); c.scale(110*d*scale,12*d); c.drawCircle(0f,0f,1f,floor); c.restore()
    }
    /** Moves the books into place; [animate] slides them, as after a swipe. */
    fun arrange(animate: Boolean) {
        covers.forEachIndexed { i,v ->
            val o=i-index; val cur=o==0
            (v as? CoverView)?.leaves=cur
            v.pivotX=105*d*scale; v.pivotY=CoverView.HEIGHT*d*scale/2; v.perspective(900*d*scale)
            val tx=o*252*d*scale+dx; val s=if(cur) 1f else .78f; val a=if(cur) 1f else .45f; val ry=if(cur) -14f else 0f
            v.isFocusable=abs(o)<=1; v.importantForAccessibility=if(abs(o)<=1) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
            if(animate && moving) v.animate().translationX(tx).scaleX(s).scaleY(s).alpha(a).rotationY(ry).setDuration(350).setInterpolator(DecelerateInterpolator(2f)).start()
            else { v.animate().cancel(); v.translationX=tx; v.scaleX=s; v.scaleY=s; v.alpha=a; v.rotationY=ry }
        }
    }
    fun go(i: Int) { val to=i.coerceIn(0,covers.size-1); if(to==index) { arrange(true); return }; index=to; arrange(true); onPick(to) }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=e.x; downY=e.y; dragging=false }
            MotionEvent.ACTION_MOVE -> if(!dragging && abs(e.x-downX)>slop && abs(e.x-downX)>abs(e.y-downY)) { dragging=true; parent?.requestDisallowInterceptTouchEvent(true); return true }
        }
        return dragging
    }
    @SuppressLint("ClickableViewAccessibility") // Books are also reachable with the arrow buttons.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=e.x; downY=e.y; return true }
            MotionEvent.ACTION_MOVE -> { if(!dragging && abs(e.x-downX)>slop) dragging=true; if(dragging) { dx=e.x-downX; arrange(false) } }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                if(dragging) {
                    val step=when { dx< -50*d -> 1; dx>50*d -> -1; else -> 0 }
                    dx=0f; dragging=false
                    if(step!=0 && index+step in covers.indices) go(index+step) else arrange(true)
                }
            }
        }
        return true
    }
}

/** A month taped to the desk. Each day shows the first thing kept that day; days with things open them. Swipe for other months. */
@SuppressLint("ViewConstructor")
class CalendarSheet(context: Context, year: Int, private val month: Int, private val byDay: Map<Int,List<Keepsake>>, private val today: Int,
                    private val hand: Typeface, private val mono: Typeface, load: Loader, photo: Loader, private val onDay: (Int) -> Unit) : FrameLayout(context) {
    private val d=resources.displayMetrics.density
    private val cal=java.util.Calendar.getInstance().apply { clear(); set(year,month,1) }
    private val startDow=cal.get(java.util.Calendar.DAY_OF_WEEK)-1
    private val days=cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
    private val rows=(startDow+days+6)/7
    private val bitmaps=HashMap<Int,Bitmap>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text=Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid=Paint()
    private val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; color=Pen.BLUE; strokeCap=Paint.Cap.ROUND }
    private val numbers=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    /** The sheet itself; the view is larger, to leave room for its shadow and tape. */
    private val sheet=RectF()
    private var cellW=0f; private var cellH=0f
    var onSwipe: ((Int) -> Unit)?=null
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f; private var downY=0f; private var swiping=false
    init {
        setWillNotDraw(false); clipChildren=false
        byDay.forEach { (day,list) ->
            // A whole photo is cut into a shape from the photo itself, not from its white-bordered print.
            (if(list.first().framed) photo else load)(list.first(),(80*d).roundToInt()) { b -> if(b!=null) { bitmaps[day]=b; invalidate() } }
            addView(View(context).apply {
                isFocusable=true; isClickable=true; background=RippleDrawable(ColorStateList.valueOf(Ink.alpha(Ink.INK,.1f)),null,ShapeDrawable())
                contentDescription="${monthName(month)} $day: ${list.joinToString { it.title }}"
                setOnClickListener { onDay(day) }; tag=day
            })
        }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w=min(width-24*d,420*d); val left=(width-w)/2
        cellW=(w-14*d)/7f; cellH=min(76*d,(height-12*d-30*d-16*d-24*d)/rows)
        sheet.set(left,12*d,left+w,12*d+30*d+rows*cellH+16*d)
        for(i in 0 until childCount) {
            val v=getChildAt(i); val cell=cellRect(v.tag as Int)
            v.measure(MeasureSpec.makeMeasureSpec(cell.width().toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(cell.height().toInt(),MeasureSpec.EXACTLY))
            v.layout(cell.left.toInt(),cell.top.toInt(),cell.left.toInt()+v.measuredWidth,cell.top.toInt()+v.measuredHeight)
        }
    }
    private fun cellRect(day: Int): RectF {
        val i=startDow+day-1; val x=sheet.left+7*d+(i%7)*cellW; val y=sheet.top+30*d+(i/7)*cellH
        return RectF(x,y,x+cellW,y+cellH)
    }
    override fun onDraw(c: Canvas) {
        paint.color=Color.rgb(252,251,247); paint.setShadowLayer(18*d,0f,12*d,Color.argb(150,0,0,0)); c.drawRoundRect(sheet,5*d,5*d,paint); paint.clearShadowLayer()
        Textures.crumple?.let { c.save(); c.clipRect(sheet); paint.alpha=150; c.drawBitmap(it,null,sheet,paint); paint.alpha=255; c.restore() }
        text.typeface=mono; text.textSize=10.5f*d; text.color=Ink.INK_2; text.textAlign=Paint.Align.CENTER
        "SMTWTFS".forEachIndexed { i,ch -> c.drawText(ch.toString(),sheet.left+7*d+(i+.5f)*cellW,sheet.top+21*d,text) }
        grid.color=Ink.alpha(Ink.INK,.22f); grid.strokeWidth=max(1f,d)
        val gx=sheet.left+7*d; val gy=sheet.top+30*d
        for(r in 0..rows) c.drawLine(gx,gy+r*cellH,gx+7*cellW,gy+r*cellH,grid)
        for(col in 0..7) c.drawLine(gx+col*cellW,gy,gx+col*cellW,gy+rows*cellH,grid)
        for(day in 1..days) {
            val cell=cellRect(day)
            text.typeface=numbers; text.textSize=9.5f*d; text.color=Ink.INK; text.textAlign=Paint.Align.LEFT
            c.drawText(day.toString(),cell.left+4*d,cell.top+13*d,text)
            val list=byDay[day]
            val b=bitmaps[day]
            if(list!=null && b!=null) {
                val item=list.first(); val size=min(cellW-6*d,cellH-20*d)
                c.save(); c.translate(cell.centerX()-size/2,cell.top+17*d)
                val fill: Float
                if(item.framed) { c.clipPath(cutShape(seedOf(item.id),size)); fill=max(size/b.width,size/b.height) }
                else { c.rotate(Rng(seedOf(item.id)).between(-9f,9f),size/2,size/2); fill=min(size/b.width,size/b.height)*(if(item.aspect>1.6f) 1.3f else 1.05f) }
                c.drawBitmap(b,null,RectF((size-b.width*fill)/2,(size-b.height*fill)/2,(size+b.width*fill)/2,(size+b.height*fill)/2),paint)
                c.restore()
            }
            if(list!=null && list.size>1) { text.typeface=hand; text.textSize=13*d; text.color=Pen.BLUE; text.textAlign=Paint.Align.RIGHT; c.drawText("+${list.size-1}",cell.right-3*d,cell.bottom-4*d,text) }
            if(day==today) {
                ring.strokeWidth=1.5f
                c.save(); c.translate(cell.left+d,cell.top+d); c.scale(d,d)
                c.drawPath(svgPath("M2 10 C 1 3, 17 1, 19 8 C 21 15, 6 18, 3 12 C 2 9, 6 4, 11 3"),ring); c.restore()
            }
        }
        c.save(); c.scale(d,d)
        drawTape(c,sheet.left/d+4f,sheet.top/d+2f,46f,-38f,0); drawTape(c,sheet.right/d-4f,sheet.top/d+2f,46f,38f,1)
        c.restore()
    }
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=e.x; downY=e.y; swiping=false }
            MotionEvent.ACTION_MOVE -> if(!swiping && abs(e.x-downX)>slop && abs(e.x-downX)>abs(e.y-downY)) { swiping=true; return true }
        }
        return swiping
    }
    @SuppressLint("ClickableViewAccessibility") // Months are also changed with the arrow buttons.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX=e.x; downY=e.y; return true }
            MotionEvent.ACTION_UP -> { val dx=e.x-downX; if(abs(dx)>60*d && abs(dx)>abs(e.y-downY)) onSwipe?.invoke(if(dx<0) 1 else -1); swiping=false }
            MotionEvent.ACTION_CANCEL -> swiping=false
        }
        return true
    }
    companion object {
        fun monthName(m: Int)=arrayOf("January","February","March","April","May","June","July","August","September","October","November","December")[m]
    }
}

/** The top of a thing's own page: its sticker large, the photo it came from taped behind, its tag and its date. */
@SuppressLint("ViewConstructor")
class ThingArt(context: Context, private val item: Keepsake, private val paper: Paper, private val k: Float, private val hand: Typeface) : View(context) {
    private var art: Bitmap?=null
    private var photo: Bitmap?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade=Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter=PorterDuffColorFilter(Ink.INK,PorterDuff.Mode.SRC_IN); alpha=26 }
    private val torn=tornPath(188f,128f,seedOf(item.id)+9)
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES; contentDescription=if(item.framed) "${item.title}, a photo" else item.title }
    fun showArt(b: Bitmap?) { art=b; invalidate() }
    fun showPhoto(b: Bitmap?) { photo=b; invalidate() }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w),(HEIGHT*k).roundToInt())
    override fun onDraw(c: Canvas) {
        c.save(); c.translate((width-360*k)/2,0f); c.scale(k,k)
        val behind=photo
        val cx: Float; val cy: Float
        if(!item.framed && behind!=null) {
            c.save(); c.translate(30f,28f); c.rotate(-5f,94f,64f)
            paint.color=Ink.alpha(Ink.INK,.2f); c.save(); c.translate(0f,2f); c.drawPath(torn,paint); c.restore()
            c.save(); c.clipPath(torn); val s=max(188f/behind.width,128f/behind.height)
            c.drawBitmap(behind,null,RectF((188-behind.width*s)/2,(128-behind.height*s)/2,(188+behind.width*s)/2,(128+behind.height*s)/2),paint); c.restore()
            c.restore()
            if(paper.dark) drawPin(c,125f,30f) else drawTape(c,119f,29f,54f,-8f,seedOf(item.id))
            cx=200f; cy=200f
        } else { cx=180f; cy=190f }
        art?.let { b ->
            val box=boxOf(item.aspect,if(item.framed) 260f else 236f); val angle=if(item.framed) -3f else 4f+Rng(seedOf(item.id)+3).next()*4f
            c.save(); c.translate(cx-box.x/2,cy-box.y/2); c.rotate(angle,box.x/2,box.y/2)
            val s=min(box.x/b.width,box.y/b.height); val dst=RectF((box.x-b.width*s)/2,(box.y-b.height*s)/2,(box.x+b.width*s)/2,(box.y+b.height*s)/2)
            c.save(); c.translate(0f,4f); c.drawBitmap(b,null,dst,shade); c.restore(); c.drawBitmap(b,null,dst,paint)
            if(item.framed) drawFastener(c,paper,box.x,seedOf(item.id))
            c.restore()
        }
        drawString(c,Path().apply { moveTo(214f,292f); cubicTo(226f,308f,232f,318f,228f,330f) })
        drawTag(c,item.title,min(214f,330f-min(250f,40f+item.title.length*12f)),324f,4f,hand,big=true)
        drawStamp(c,stampDate(item.created),26f,336f,-6f,paper.dark)
        c.restore()
    }
    companion object { const val HEIGHT=390f }
}

/** A round paper sample to choose a book's paper with. */
class Swatch(context: Context, val paper: Paper) : View(context) {
    private val d=resources.displayMetrics.density
    private val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE }
    init { isClickable=true; isFocusable=true; contentDescription="${paper.label} paper" }
    override fun onDraw(c: Canvas) {
        val r=min(width,height)/2f-4*d; val cx=width/2f; val cy=height/2f
        c.save(); c.clipPath(Path().apply { addCircle(cx,cy,r,Path.Direction.CW) }); drawPaper(c,paper,RectF(cx-r,cy-r,cx+r,cy+r),d,0f); c.restore()
        ring.color=Ink.alpha(Ink.INK,.14f); ring.strokeWidth=d; c.drawCircle(cx,cy,r,ring)
        if(isSelected) { ring.color=Pen.BLUE; ring.strokeWidth=2.5f*d; c.drawCircle(cx,cy,r+2.5f*d,ring) }
    }
    override fun setSelected(selected: Boolean) { super.setSelected(selected); invalidate() }
}

/** The kraft tag a thing's name is written on, as a text field's background. */
class TagBackground(private val d: Float) : Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    var focused=false
        set(value) { field=value; invalidateSelf() }
    override fun draw(c: Canvas) {
        val b=bounds; val notch=17*d; val h=b.height().toFloat()
        val shape=Path().apply { moveTo(b.left+notch,b.top.toFloat()); lineTo(b.right.toFloat(),b.top.toFloat()); lineTo(b.right.toFloat(),b.bottom.toFloat()); lineTo(b.left+notch,b.bottom.toFloat()); lineTo(b.left.toFloat(),b.top+h/2); close() }
        paint.style=Paint.Style.FILL; paint.color=Ink.alpha(Ink.INK,.22f); c.save(); c.translate(0f,2*d); c.drawPath(shape,paint); c.restore()
        paint.color=if(focused) Color.rgb(226,199,155) else Ink.KRAFT; c.drawPath(shape,paint)
        paint.color=Color.argb(230,255,255,255); c.drawCircle(b.left+15*d,b.top+h/2,4*d,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=d; paint.color=Ink.alpha(Ink.INK,.35f); c.drawCircle(b.left+15*d,b.top+h/2,4*d,paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter }
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** Any view's background drawn as a book's paper. */
class PaperBackground(private val paper: Paper, private val d: Float) : Drawable() {
    private val rect=RectF()
    override fun draw(c: Canvas) { rect.set(bounds); drawPaper(c,paper,rect,d) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.OPAQUE
}

/** A curl of string, from the top of a view down and to the left, to tie a preview to the tag under it. */
class StringView(context: Context) : View(context) {
    private val d=resources.displayMetrics.density
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        c.save(); c.scale(d,d)
        val w=width/d; val h=height/d
        drawString(c,Path().apply { moveTo(w/2,0f); cubicTo(w/2,h*.6f,60f,h*.3f,58f,h) })
        c.restore()
    }
}

/** Where a new book will go: a dashed outline at the end of the books. */
class GhostBook(context: Context, private val hand: Typeface) : View(context) {
    private val d=resources.displayMetrics.density
    override fun onDraw(c: Canvas) {
        c.save(); c.scale(width/(CoverView.WIDTH*d)*d,height/(CoverView.HEIGHT*d)*d)
        drawSlot(c,RectF(2f,2f,208f,CoverView.HEIGHT-2f),"+\nNew book",hand,true)
        c.restore()
    }
}

/** A hand-drawn arrow from the "start here" note to the add button. */
class ArrowView(context: Context) : View(context) {
    private val d=resources.displayMetrics.density
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; color=Color.WHITE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        c.save(); c.scale(d,d); pen.strokeWidth=1.7f
        c.drawPath(svgPath("M4 8 C 20 24, 38 32, 58 28 M49 22 L59 28 L50 35"),pen); c.restore()
    }
}

/** A strip of tape, or a pin on cork, holding the top of the card below it. */
class TapeView(context: Context, private val paper: Paper, private val kind: Int) : View(context) {
    private val d=resources.displayMetrics.density
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO; elevation=6*d; outlineProvider=null }
    override fun onDraw(c: Canvas) {
        c.save(); c.scale(d,d)
        if(paper.dark) drawPin(c,width/d/2,height/d/2) else drawTape(c,width/d/2,height/d/2,54f,3f,kind)
        c.restore()
    }
}
