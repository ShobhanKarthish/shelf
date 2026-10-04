package com.shobhankarthish.shelf

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
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

/**
 * A round button: white with an ink icon, yellow when it's the chosen one of a pair, or the red add button in its sunburst.
 * Pressed, it sinks onto its shadow; let go, its icon does one small thing that suits it.
 */
@SuppressLint("AppCompatCustomView")
class RoundButton(context: Context, private val doodle: Doodle, label: String, style: Style=Style.WHITE) : Button(context) {
    enum class Style { WHITE, ON, RED }
    private val d=resources.displayMetrics.density
    private val icon=Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    private val disc=Paint(Paint.ANTI_ALIAS_FLAG)
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style=Paint.Style.STROKE; color=Ink.INK; strokeJoin=Paint.Join.ROUND }
    var look=style
        set(value) { field=value; invalidate() }
    private var glyph=Path()
    private var sun=Path()
    // Where the icon and the sunburst are in their little dance.
    private var gx=0f; private var gy=0f; private var turn=0f; private var sx=1f; private var sy=1f
    private var sunTurn=0f; private var sunScale=1f
    private val hops=FloatArray(3)
    private val red get()=look==Style.RED
    private val lift get()=(if(red) 4f else 3f)*d
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val s=(if(red) 26f else 21f)*d
        glyph=doodlePath(doodle,RectF(-s/2,-s/2,s/2,s/2))
        val r=min(w,h)/2f; sun=burstPath(0f,0f,r-2*d,r*.84f,24)
    }
    init {
        text=""; contentDescription=label; stateListAnimator=null; minWidth=0; minimumWidth=0; minHeight=0; minimumHeight=0; setPadding(0,0,0,0)
        background=null
    }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(c: Canvas) {
        val down=if(isPressed) lift else 0f
        val cx: Float; val cy: Float; val r: Float
        if(red) {
            cx=width/2f; cy=height/2f; r=min(width,height)*.36f
            c.save(); c.translate(cx,cy); c.rotate(sunTurn); c.scale(sunScale,sunScale)
            disc.color=Ink.SUN; c.drawPath(sun,disc); line.strokeWidth=2.5f*d; c.drawPath(sun,line); c.restore()
        } else { cx=(width-lift)/2f; cy=(height-lift)/2f; r=min(width,height)/2f-lift/2-1.5f*d }
        if(!isPressed) { disc.color=Ink.INK; c.drawCircle(cx+lift,cy+lift,r,disc) }
        disc.color=when(look) { Style.RED -> Ink.ACCENT; Style.ON -> Ink.SUN; Style.WHITE -> Color.WHITE }
        c.drawCircle(cx+down,cy+down,r,disc)
        line.strokeWidth=(if(red) 3f else 2.5f)*d; c.drawCircle(cx+down,cy+down,r-line.strokeWidth/2,line)
        c.save(); c.translate(cx+down+gx,cy+down+gy); c.rotate(turn); c.scale(sx,sy)
        icon.color=if(red) Color.WHITE else Ink.INK
        if(doodle==Doodle.MORE) {
            disc.color=Ink.INK; for(i in 0..2) c.drawCircle((i-1)*7*d,hops[i],2.4f*d,disc)
        } else { icon.strokeWidth=(if(red) 3.4f else 2.3f)*d; c.drawPath(glyph,icon) }
        c.restore()
    }
    override fun performClick(): Boolean { flourish(); return super.performClick() }
    private fun reset() { gx=0f; gy=0f; turn=0f; sx=1f; sy=1f; sunTurn=0f; sunScale=1f; hops.fill(0f); invalidate() }
    private fun flourish() {
        if(!moving) return
        when(doodle) {
            Doodle.PLUS -> {
                tween(520,update={ t -> turn=keys(t,0f,45f,90f,90f); sx=keys(t,1f,.8f,1.12f,1f); sy=sx; invalidate() },end={ reset() })
                tween(700,update={ t -> sunTurn=keys(t,0f,120f,180f); sunScale=keys(t,1f,1.3f,1f); invalidate() })
                Confetti.burst(this,width/2f,height/2f,14,130f)
            }
            Doodle.PREV,Doodle.NEXT,Doodle.BACK -> { val dir=if(doodle==Doodle.NEXT) 1 else -1; tween(300,update={ t -> gx=keys(t,0f,dir*6*d,0f); invalidate() },end={ reset() }) }
            Doodle.MORE -> tween(460,update={ t -> for(i in 0..2) hops[i]=-6*d*sin(PI*((t*460-i*70)/320f).coerceIn(0f,1f)).toFloat(); invalidate() },end={ reset() })
            Doodle.PENCIL -> tween(480,update={ t -> turn=keys(t,0f,-18f,10f,-6f,0f); invalidate() },end={ reset() })
            Doodle.BOOK,Doodle.CALENDAR -> tween(380,update={ t -> gy=keys(t,0f,-7*d,0f); sx=keys(t,1f,1.15f,1f); sy=keys(t,1f,.9f,1f); invalidate() },end={ reset() })
            else -> tween(260,update={ t -> sx=keys(t,1f,1.2f,1f); sy=sx; invalidate() },end={ reset() })
        }
    }
}

/** How a page's 360-unit collage fits the screen: [k] pixels per unit, with its top-left corner at [ox],[oy]. */
class PageFit(val k: Float, val ox: Float, val oy: Float)
fun pageFit(width: Int, height: Int, top: Int, bottom: Int, d: Float): PageFit {
    val usable=height-top-bottom-(64+112)*d
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
    fun top(title: CutLetters?)=if(title==null) 24f else title.heightIn(300f)+14f
    fun auto(item: Keepsake, slot: Int, top: Float): Placement {
        val r=Rng(seedOf(item.id)); val s=slots[slot.coerceIn(0,PER-1)]
        // Long, thin things get more room, or they'd be a sliver.
        val thin=if(item.aspect>2.2f || item.aspect<.45f) 1.3f else 1f
        return Placement(s[0]+r.between(-10f,10f),top+s[1]+r.between(-10f,10f),r.between(-8f,8f),s[2]*r.between(.92f,1.06f)*thin,1)
    }
    /** How tall a thing's tag, and its note if it has one, hang below its sticker. */
    private fun labelsHeight(item: Keepsake): Float {
        val note=snippet(item.story); if(note.isEmpty()) return 40f
        val lines=min(2,1+(note.length*8.6f/NOTE_WIDTH).toInt())
        return 40f+8f+lines*21f
    }
    /** The room a thing takes on its page: its sticker with the tag and note hanging centred under it, in page units. */
    fun footprint(item: Keepsake, p: Placement): RectF {
        val b=boxOf(item.aspect,p.size)
        val wide=maxOf(b.x,tagWidth(item.title,Fonts.handFace),if(item.story.isBlank()) 0f else NOTE_WIDTH)
        val t=p.y-b.y/2
        return RectF(p.x-wide/2-5f,t-6f,p.x+wide/2+5f,t+b.y+labelsHeight(item)+4f)
    }
    /**
     * Where an unarranged thing goes. Pages are a 2×2 grid under the title and date stamp; each thing is sized to fit its
     * cell with its tag and note, so nothing overlaps. If something arranged by hand is in the way, it finds the nearest
     * free spot instead, a little smaller each time the page is too full.
     */
    fun fit(item: Keepsake, slot: Int, top: Float, taken: List<RectF>, firstPage: Boolean=true): Placement {
        val r=Rng(seedOf(item.id))
        val areaTop=top+(if(firstPage) 32f else 20f)
        val cellH=(HEIGHT-areaTop)/2f; val col=slot%2; val row=min(1,slot/2)
        val roomH=max(60f,cellH-labelsHeight(item)-20f); val roomW=150f
        val a=item.aspect
        var size=min(170f,if(a>=1f) min(roomW,roomH*a) else min(roomW/a,roomH))
        val box=boxOf(a,size)
        // A little off-centre, always toward the middle of the page, so neighbours never touch.
        val cell=Placement(90f+col*180f+(if(col==0) -1f else 1f)*r.between(0f,6f),areaTop+row*cellH+10f+box.y/2+r.between(0f,6f),r.between(-6f,6f),size,1)
        if(taken.none { RectF.intersects(it,footprint(item,cell)) }) return cell
        repeat(6) {
            val spots=ArrayList<PointF>()
            var y=top+20f; while(y<=HEIGHT-20f) { var x=30f; while(x<=330f) { spots+=PointF(x,y); x+=10f }; y+=10f }
            spots.sortBy { hypot(it.x-cell.x,(it.y-cell.y)*1.4f) }
            for(spot in spots) {
                val p=cell.copy(x=spot.x,y=spot.y,size=size); val f=footprint(item,p)
                if(f.left>=-4f && f.right<=364f && f.top>=top-6f && f.bottom<=HEIGHT+4f && taken.none { RectF.intersects(it,f) }) return p
            }
            size*=.88f
        }
        return cell.copy(size=size)
    }
    fun title(book: Book)=CutLetters(book.name,24f,seedOf(book.id)+1,250f)
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
 * but only a touch on the sticker itself belongs to it. Tapped, it peels up off the page and its tag swings before it opens.
 */
@SuppressLint("ViewConstructor") // Made in code with what it shows; never inflated from XML.
class ThingView(context: Context, val item: Keepsake, var place: Placement, private val k: Float, private val hand: Typeface, private val host: ThingHost) : View(context) {
    val w: Float; val h: Float
    val padLeft: Float; val padTop: Float; val padRight: Float; val padBottom: Float
    private val d=resources.displayMetrics.density
    private var bitmap: Bitmap?=null
    private val right=place.x>180f
    private val rng=Rng(seedOf(item.id)+7)
    private val tagAngle=rng.between(-4f,4f)
    private val noteAngle=rng.between(-2.5f,2.5f)
    private val note: PenNote?
    private val outline=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=2f; color=Pen.BLUE; pathEffect=DashPathEffect(floatArrayOf(6f,4f),0f) }
    private val handle=Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst=RectF()
    private val twist: Path
    private val string: Path
    /** How far the tag is swinging on its string, in degrees. */
    private var swing=0f
    /** A little wobble while the page is being arranged, so it's clear things can move. */
    private var wobble=0f
    private var wobbling: ValueAnimator?=null
    var chosen=false
        set(value) { field=value; invalidate() }
    init {
        val box=boxOf(item.aspect,place.size); w=box.x; h=box.y
        note=snippet(item.story).takeIf { it.isNotEmpty() }?.let { PenNote(it,hand) }
        // The tag and note hang centred under the sticker, so a thing is never wider than its labels or its picture.
        val tag=tagWidth(item.title,hand); val wide=maxOf(w,tag,note?.width ?: 0f)
        padTop=HANDLE
        padLeft=max(HANDLE,(wide-w)/2+4f); padRight=max(HANDLE,(wide-w)/2+4f)
        padBottom=maxOf(HANDLE,44f+(note?.height?.plus(12f) ?: 0f))
        contentDescription=if(item.favourite) "${item.title}, a favourite" else item.title; isFocusable=true; isClickable=true
        rotation=place.angle
        val ty=h+6f; val hole=if(right) w/2+tag/2-10f else w/2-tag/2+10f
        string=Path().apply { moveTo(w*.5f,h*.8f); cubicTo(w*.5f,h+2f,hole,ty+2f,hole,ty+14f) }
        twist=doodlePath(Doodle.ROTATE,RectF(w,-16f,w+16f,0f))
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { pivotX=(padLeft+this.w/2)*k; pivotY=(padTop+this.h/2)*k }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if(host.arranging && moving) wobbling=ValueAnimator.ofFloat(-1.6f,1.6f).apply {
            duration=320; repeatMode=ValueAnimator.REVERSE; repeatCount=ValueAnimator.INFINITE; interpolator=AccelerateDecelerateInterpolator()
            currentPlayTime=(rng.next()*320).toLong(); addUpdateListener { wobble=it.animatedValue as Float; if(!chosen) invalidate() }; start()
        }
    }
    override fun onDetachedFromWindow() { wobbling?.cancel(); wobbling=null; super.onDetachedFromWindow() }
    fun show(b: Bitmap?) { bitmap=b; invalidate() }
    /** Pixel size to load the image at, in steps so neighbouring sizes share the cache. */
    val loadSize get()=((max(w,h)*k/64f).roundToInt()+1)*64

    override fun onDraw(c: Canvas) {
        c.save(); c.scale(k,k); c.translate(padLeft,padTop)
        drawString(c,string)
        if(host.arranging && !chosen) c.rotate(wobble,w/2,h/2)
        bitmap?.let { b ->
            val s=min(w/b.width,h/b.height); val bw=b.width*s; val bh=b.height*s
            dst.set((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2)
            drawCutout(c,b,dst,1.8f,3.5f)
        }
        if(item.framed) drawFastener(c,w,seedOf(item.id))
        if(item.favourite) drawMark(c,Mark.STAR,w-6f,6f,28f,Ink.ACCENT,14f)
        if(host.arranging && chosen) {
            c.drawRect(-8f,-8f,w+8f,h+8f,outline)
            handle.style=Paint.Style.FILL; handle.color=Color.WHITE; c.drawCircle(w+8f,-8f,16f,handle)
            handle.style=Paint.Style.STROKE; handle.color=Ink.INK; handle.strokeWidth=2f; c.drawCircle(w+8f,-8f,16f,handle)
            handle.strokeCap=Paint.Cap.ROUND; c.drawPath(twist,handle)
        }
        c.restore()
    }

    /** The tag and note, drawn by the page above every sticker so a neighbour never hides a name. [c] is already in this thing's units. */
    fun drawLabels(c: Canvas) {
        val ty=h+6f
        drawTag(c,item.title,w/2,ty,tagAngle+swing,hand,holeRight=right,center=true)
        note?.draw(c,w/2-note.width/2,ty+36f,noteAngle)
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
                    // The whole sticker stays on the page.
                    val hx=min(w/2+2f,178f); val hy=min(h/2+2f,PageLayout.HEIGHT/2)
                    place=place.copy(x=(startX+(e.rawX-downRawX)/k).coerceIn(hx,360f-hx),y=(startY+(e.rawY-downRawY)/k).coerceIn(hy,PageLayout.HEIGHT-hy))
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
    private var opening=false
    override fun performClick(): Boolean {
        super.performClick()
        if(host.arranging || opening) return true
        if(!moving) { host.open(this); return true }
        // Peel up off the page, let the tag swing, then open.
        opening=true
        val a=place.angle
        tween(560,update={ t ->
            val s=keys(t,1f,1.14f,.97f,1f); scaleX=s; scaleY=s
            translationY=keys(t,0f,-10*d,2*d,0f); rotation=a+keys(t,0f,-a*.7f,a*.2f,0f); moved()
        },end={ scaleX=1f; scaleY=1f; translationY=0f; rotation=place.angle; moved() })
        tween(1100,update={ t -> swing=keys(t,0f,16f,-11f,7f,-4f,2f,0f); moved() },end={ swing=0f; moved() })
        postDelayed({ opening=false; if(isAttachedToWindow) host.open(this) },330)
        return true
    }

    /** The one moment worth a bigger show: a new thing lands on its page and sticks. */
    fun stickIn(delay: Long=120) {
        if(!moving) return
        alpha=0f; scaleX=1.4f; scaleY=1.4f; translationY=-46*k; rotation=place.angle-10f
        animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).rotation(place.angle).setStartDelay(delay).setDuration(700).setInterpolator(OvershootInterpolator(1.3f))
            .setUpdateListener { moved() }.withEndAction { moved(); val p=(parent as? View); if(p!=null) Confetti.burst(p,left+pivotX,top+pivotY,10,100f) }.start()
    }
    companion object { private const val HANDLE=28f }
}

/**
 * One page of a book: its paper and the things stuck on it, with doodles in the gaps. [lift] darkens it as it turns away;
 * [spine] is the shadow cast on it by a page turning over it. Tap its date stamp and it thunks.
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
    private val shadeView=View(context).apply { background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Ink.alpha(Ink.INK,0f),Ink.alpha(Ink.INK,.05f),Ink.alpha(Ink.INK,.34f))); alpha=0f; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private class Scribble(val mark: Mark, val x: Float, val y: Float, val size: Float, val color: Int, val angle: Float)
    /** Doodles in the gaps, placed the same way every time the page is drawn. */
    private val doodles: List<Scribble>
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
    private val spineView=View(context).apply { background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.argb(100,20,20,30),Color.argb(24,20,20,30),Color.argb(0,20,20,30),Color.argb(0,20,20,30))); alpha=0f; importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    var lift: Float
        get()=shadeView.alpha
        set(value) { shadeView.alpha=value }
    var spine: Float
        get()=spineView.alpha
        set(value) { spineView.alpha=value }
    private var punch=1f
    private var ring=0f
    private val stampRect=RectF()
    private val ringPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; color=Ink.ACCENT; strokeWidth=3f }
    init {
        setWillNotDraw(false); clipChildren=false
        val r=Rng(seedOf(book.id)+index*31)
        val spots=mutableListOf(floatArrayOf(332f,30f),floatArrayOf(22f,236f),floatArrayOf(340f,286f),floatArrayOf(24f,452f),floatArrayOf(334f,500f),floatArrayOf(196f,572f),floatArrayOf(56f,574f),floatArrayOf(300f,580f))
        val inks=intArrayOf(Ink.SUN,Ink.BLUSH,Ink.SKY,Ink.MINT,Ink.ACCENT)
        doodles=List(4) { i ->
            val s=spots.removeAt((r.next()*spots.size).toInt().coerceAtMost(spots.size-1))
            Scribble(Mark.entries[(r.next()*3).toInt().coerceAtMost(2)],s[0],s[1],r.between(18f,30f),inks[(i+index)%inks.size],r.between(-20f,20f))
        }
        // Things someone arranged stay put; the rest find room around them, the title and the date stamp.
        val taken=ArrayList<RectF>()
        if(stamp!=null) taken+=RectF(330f-stampWidth(stamp),if(index==0) top-8f else -4f,364f,(if(index==0) top-2f else 0f)+STAMP_HEIGHT+10f)
        items.forEach { item -> item.place?.let { taken+=PageLayout.footprint(item,it) } }
        items.forEachIndexed { slot,item ->
            val place=item.place ?: PageLayout.fit(item,slot,top,taken,index==0).also { taken+=PageLayout.footprint(item,it) }
            val t=ThingView(context,item,place,fit.k,hand,host)
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
        doodles.forEach { drawMark(c,it.mark,it.x,it.y,it.size,it.color,it.angle) }
        title?.draw(c,20f,0f,300f)
        stamp?.let {
            val w=stampWidth(it); val left=340f-w; val top=if(index==0) top-2f else 0f
            drawStamp(c,it,left,top,if(index==0) 5f else -4f,punch)
            stampRect.set(fit.ox+left*fit.k,fit.oy+top*fit.k,fit.ox+(left+w)*fit.k,fit.oy+(top+STAMP_HEIGHT)*fit.k)
            if(ring>0f && ring<1f) { ringPaint.alpha=((1-ring)*200).roundToInt(); c.drawCircle(left+w/2,top+STAMP_HEIGHT/2,30f*(.4f+2f*ring),ringPaint) }
        }
        if(bookEmpty && index==0) drawSlot(c,RectF(80f,top+40f,280f,top+210f),"your first\nthing goes here",hand,false)
        c.restore()
        c.save(); c.scale(d,d)
        drawPageEdges(c,width/d,18f,height/d-18f,min(3,pages-1-index),true)
        drawPageEdges(c,0f,18f,height/d-18f,min(3,index),false)
        c.restore()
    }
    @SuppressLint("ClickableViewAccessibility") // The stamp is decoration; nothing is lost without the thunk.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return stampRect.contains(e.x,e.y)
            MotionEvent.ACTION_UP -> if(stampRect.contains(e.x,e.y)) thunk()
        }
        return true
    }
    /** The date stamp comes down hard and leaves a ring of ink. */
    private fun thunk() = tween(500,update={ t -> punch=keys(t,1f,1.25f,.92f,1f); ring=t; invalidate() },end={ punch=1f; ring=0f; invalidate() })
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

    /** Turns one page forward (1) or back (-1), as the arrow buttons do. With no page that way, the edge lifts and falls back. */
    fun turn(dir: Int) {
        if(turning!=null || arranging || current==null) return
        if(page+dir !in 0 until pages) { nudge(dir); return }
        val t=Turn(dir)
        t.run(1f,640,AccelerateDecelerateInterpolator()) { t.commit() }
    }
    private fun nudge(dir: Int) {
        val leaf=current ?: return
        if(!moving) return
        leaf.pivotX=0f; leaf.pivotY=height/2f; leaf.perspective(width*3.9f)
        tween(420,DecelerateInterpolator(),update={ t -> leaf.rotationY=SWING*keys(t,0f,if(dir>0) 16f else -8f,0f) },end={ leaf.rotationY=0f })
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

/**
 * A book on the desk: a fat patterned cover with an ink spine, an elastic band and page edges, its first things stuck on
 * the front and its name in cut-out letters. [open] swings the cover back on its spine.
 */
@SuppressLint("ViewConstructor")
class CoverView(context: Context, val book: Book, private val items: List<Keepsake>, private val hand: Typeface, load: Loader) : View(context) {
    private val d=resources.displayMetrics.density
    private val bitmaps=arrayOfNulls<Bitmap>(3)
    private val name=CutLetters(book.name,20f,seedOf(book.id),168f)
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; color=Ink.INK; strokeJoin=Paint.Join.ROUND }
    private val camera=Camera()
    private val turn=Matrix()
    private val rng=Rng(seedOf(book.id)+5)
    private val star=floatArrayOf(rng.between(30f,60f),rng.between(150f,190f),rng.between(-20f,20f))
    /** How far the cover has swung open, from 0 (shut) to 1. */
    private var openness=0f
    init {
        items.take(3).forEachIndexed { i,item -> load(item,(170*d).roundToInt()) { b -> bitmaps[i]=b; invalidate() } }
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun coverPath(scale: Float)=Path().apply { addRoundRect(RectF(0f,0f,COVER_W*scale,COVER_H*scale),floatArrayOf(5*scale,5*scale,18*scale,18*scale,18*scale,18*scale,5*scale,5*scale),Path.Direction.CW) }
    override fun onDraw(c: Canvas) {
        c.save(); c.scale(width/(WIDTH*d),height/(HEIGHT*d)) // the carousel scales covers down on short screens
        // The book's shadow, then the page edges, then the inside page that shows as the cover swings open.
        c.save(); c.scale(d,d)
        fill.shader=null; fill.color=Ink.INK; c.drawRoundRect(8f,8f,COVER_W+14f+8f,COVER_H+8f,18f,18f,fill)
        val edges=RectF(COVER_W-14f,10f,COVER_W+14f,COVER_H-10f)
        fill.color=Color.WHITE; c.drawRoundRect(edges,8f,8f,fill)
        fill.color=Ink.alpha(Ink.INK,.3f); var y=edges.top+4f; while(y<edges.bottom-2f) { c.drawRect(edges.left,y,edges.right-2f,y+1f,fill); y+=4f }
        line.strokeWidth=2.5f; c.drawRoundRect(edges,8f,8f,line)
        c.restore()
        if(openness>0f) {
            drawPaper(c,book.paper,RectF(0f,0f,COVER_W*d,COVER_H*d),d)
            c.save(); c.scale(d,d); line.strokeWidth=3f; c.drawPath(coverPath(1f),line); c.restore()
            val angle=-165f*openness
            camera.save(); camera.setLocation(0f,0f,-24f); camera.rotateY(angle); camera.getMatrix(turn); camera.restore()
            turn.preTranslate(0f,-COVER_H*d/2); turn.postTranslate(0f,COVER_H*d/2)
            c.concat(turn)
            if(abs(angle)>90f) { // Past halfway, we're looking at the inside of the cover.
                c.save(); c.scale(d,d); fill.color=Ink.PAPER; c.drawPath(coverPath(1f),fill); line.strokeWidth=3f; c.drawPath(coverPath(1f),line); c.restore()
                c.restore(); return
            }
        }
        drawCoverPattern(c,book.paper,coverPath(d),d)
        c.save(); c.scale(d,d)
        c.save(); c.clipPath(coverPath(1f))
        fill.color=Ink.INK; c.drawRect(0f,0f,20f,COVER_H,fill)
        val band=RectF(COVER_W-40f,-2f,COVER_W-28f,COVER_H+2f)
        fill.color=Ink.SUN; c.drawRect(band,fill); line.strokeWidth=2.5f; c.drawLine(band.left,band.top,band.left,band.bottom,line); c.drawLine(band.right,band.top,band.right,band.bottom,line)
        c.restore()
        if(items.isEmpty()) {
            drawSlot(c,RectF(40f,40f,COVER_W-50f,210f),"your first\nthing goes here",hand,false)
            drawTape(c,COVER_W/2-5f,41f,52f,-10f,2)
        } else items.take(3).forEachIndexed { i,item ->
            val (x,y,size,angle)=SLOTS[i]
            val box=boxOf(item.aspect,if(item.framed) size else size*(if(item.aspect>=1f) 1f else .82f))
            c.save(); c.translate(x,y); c.rotate(angle,box.x/2,box.y/2)
            bitmaps[i]?.let { b -> drawCutout(c,b,RectF(0f,0f,box.x,box.y),1.6f,3f) }
            if(item.framed) drawTape(c,box.x/2,-1f,46f,4f,i)
            c.restore()
        }
        drawMark(c,Mark.STAR,star[0],star[1],26f,Ink.SUN,star[2])
        val room=COVER_W-56f; val shown=min(name.width,room)
        name.draw(c,22f+(COVER_W-50f-shown)/2,COVER_H-20f-name.heightIn(room),room)
        line.strokeWidth=3f; c.drawPath(coverPath(1f),line)
        c.restore(); c.restore()
    }
    /** Swings the cover open on its spine, then calls [then]. */
    fun open(then: () -> Unit) {
        if(!moving) { then(); return }
        tween(460,DecelerateInterpolator(1.4f),update={ openness=it; invalidate() },end={ then(); postDelayed({ openness=0f; invalidate() },400) })
    }
    companion object {
        const val WIDTH=270f; const val HEIGHT=330f
        private const val COVER_W=226f; private const val COVER_H=316f
        private val SLOTS=arrayOf(floatArrayOf(30f,26f,128f,-9f),floatArrayOf(96f,146f,116f,8f),floatArrayOf(132f,24f,76f,13f))
    }
}

/** The books on the desk, one in front and its neighbours peeking in, with a burst badge for how full it is. Swipe or use the arrows. */
@SuppressLint("ViewConstructor")
class DeskCarousel(context: Context, private val covers: List<View>, index: Int, private val badge: View?, private val onPick: (Int) -> Unit, private val onOpen: (Int) -> Unit) : FrameLayout(context) {
    private val d=resources.displayMetrics.density
    var index=index; private set
    private var scale=1f
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f; private var downY=0f; private var dragging=false; private var dx=0f
    init {
        clipChildren=false
        covers.forEachIndexed { i,v -> addView(v); v.setOnClickListener { if(i==this.index) onOpen(i) else go(i) } }
        badge?.let { addView(it) }
    }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w=MeasureSpec.getSize(widthSpec); val h=MeasureSpec.getSize(heightSpec)
        scale=min(w/(360*d),(h-24*d)/(CoverView.HEIGHT*d)).coerceIn(.5f,1.25f)
        covers.forEach { it.measure(MeasureSpec.makeMeasureSpec((CoverView.WIDTH*d*scale).roundToInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((CoverView.HEIGHT*d*scale).roundToInt(),MeasureSpec.EXACTLY)) }
        badge?.measure(MeasureSpec.makeMeasureSpec((74*d*scale).roundToInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((74*d*scale).roundToInt(),MeasureSpec.EXACTLY))
        setMeasuredDimension(w,h)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { post { arrange(false) } }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val left=(width/2f-125*d*scale).roundToInt(); val top=((height-CoverView.HEIGHT*d*scale)/2).roundToInt().coerceAtLeast(0)
        covers.forEach { v -> v.layout(left,top,left+v.measuredWidth,top+v.measuredHeight) }
        badge?.let { val bx=left+(212*d*scale).roundToInt(); val by=top-(26*d*scale).roundToInt(); it.layout(bx,by,bx+it.measuredWidth,by+it.measuredHeight) }
    }
    /** Moves the books into place; [animate] slides them, as after a swipe. */
    fun arrange(animate: Boolean) {
        covers.forEachIndexed { i,v ->
            val o=i-index; val cur=o==0
            v.pivotX=113*d*scale; v.pivotY=CoverView.HEIGHT*d*scale/2
            val tx=o*258*d*scale+dx; val s=if(cur) 1f else .82f; val tilt=if(cur) -3f else if(o<0) -6f else 5f
            v.isFocusable=abs(o)<=1; v.importantForAccessibility=if(abs(o)<=1) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
            v.z=if(cur) 1f else 0f
            if(animate && moving) v.animate().translationX(tx).scaleX(s).scaleY(s).rotation(tilt).setDuration(520).setInterpolator(OvershootInterpolator(1.1f)).start()
            else { v.animate().cancel(); v.translationX=tx; v.scaleX=s; v.scaleY=s; v.rotation=tilt }
        }
        badge?.z=2f
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

/**
 * The top of a thing's own page: its sticker large in the sunshine, the photo it came from taped up beside it,
 * and its date stamp. The sticker squishes when tapped, the photo swings on its tape, and the stamp thunks.
 */
@SuppressLint("ViewConstructor")
class ThingArt(context: Context, private val item: Keepsake, private val k: Float) : View(context) {
    private val d=resources.displayMetrics.density
    private var art: Bitmap?=null
    private var photo: Bitmap?=null
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; color=Ink.INK }
    private val image=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stamp=stampDate(item.created)
    private val heroAngle=if(item.framed) -3f else -6f
    private var squashX=1f; private var squashY=1f; private var photoSwing=0f; private var punch=1f
    private val hero=RectF(); private val print=RectF()
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES; contentDescription=if(item.framed) "${item.title}, a photo" else item.title; isClickable=true }
    fun showArt(b: Bitmap?) { art=b; invalidate() }
    fun showPhoto(b: Bitmap?) { photo=b; invalidate() }
    /** Where the sticker's middle is, in pixels from the top, for the sunshine to fan out from. */
    val heroCenterY get()=CY*k
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w),(HEIGHT*k).roundToInt())
    override fun onDraw(c: Canvas) {
        c.save(); c.translate((width-360*k)/2,0f); c.scale(k,k)
        drawMark(c,Mark.SPARKLE,44f,52f,40f,Color.WHITE)
        drawMark(c,Mark.STAR,322f,236f,32f,Ink.ACCENT,12f)
        drawMark(c,Mark.HEART,36f,258f,28f,Ink.BLUSH,-10f)
        val behind=photo
        if(!item.framed && behind!=null) {
            val w=112f; val h=min(150f,w*behind.height/behind.width.toFloat())
            print.set(226f,18f,226f+w+14f,18f+h+30f)
            c.save(); c.rotate(8f+photoSwing,print.centerX(),print.top)
            drawPop(c,print,2f,Color.WHITE,2f,3f)
            val inner=RectF(print.left+7f,print.top+7f,print.right-7f,print.bottom-23f)
            c.save(); c.clipRect(inner); val s=max(inner.width()/behind.width,inner.height()/behind.height)
            c.drawBitmap(behind,null,RectF(inner.centerX()-behind.width*s/2,inner.centerY()-behind.height*s/2,inner.centerX()+behind.width*s/2,inner.centerY()+behind.height*s/2),image); c.restore()
            drawTape(c,print.centerX(),print.top,62f,-6f,0)
            c.restore()
        }
        art?.let { b ->
            val box=boxOf(item.aspect,if(item.framed) 250f else 230f)
            hero.set(CX-box.x/2,CY-box.y/2,CX+box.x/2,CY+box.y/2)
            c.save(); c.rotate(heroAngle,CX,CY); c.scale(squashX,squashY,CX,CY)
            val s=min(box.x/b.width,box.y/b.height)
            drawCutout(c,b,RectF(CX-b.width*s/2,CY-b.height*s/2,CX+b.width*s/2,CY+b.height*s/2),2.2f,4.5f)
            c.restore()
            if(item.framed) { c.save(); c.rotate(heroAngle,CX,CY); c.translate(hero.left,hero.top); drawFastener(c,box.x,seedOf(item.id)); c.restore() }
        }
        drawStamp(c,stamp,22f,HEIGHT-STAMP_HEIGHT-10f,-6f,punch)
        c.restore()
    }
    @SuppressLint("ClickableViewAccessibility") // performClick squishes the sticker; the other two are decoration.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if(e.actionMasked==MotionEvent.ACTION_DOWN) return true
        if(e.actionMasked!=MotionEvent.ACTION_UP) return true
        val ux=(e.x-(width-360*k)/2)/k; val uy=e.y/k
        when {
            print.contains(ux,uy) -> tween(1000,update={ t -> photoSwing=keys(t,0f,-12f,8f,-4f,2f,0f); invalidate() })
            uy>HEIGHT-STAMP_HEIGHT-24f && ux<200f -> tween(420,update={ t -> punch=keys(t,1f,1.25f,.92f,1f); invalidate() })
            else -> performClick()
        }
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        tween(620,update={ t -> squashX=keys(t,1f,1.18f,.9f,1.05f,1f); squashY=keys(t,1f,.84f,1.12f,.96f,1f); invalidate() })
        Confetti.burst(this,width/2f,CY*k-40*d,6,120f)
        return true
    }
    companion object { const val HEIGHT=330f; private const val CX=172f; private const val CY=160f }
}

/** A round sample of a book's pattern, to choose it with. The book's own is ringed in ink. */
class Swatch(context: Context, val paper: Paper) : View(context) {
    private val d=resources.displayMetrics.density
    private val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; color=Ink.INK }
    init { isClickable=true; isFocusable=true; contentDescription="${paper.label} pattern" }
    override fun onDraw(c: Canvas) {
        val r=min(width,height)/2f-6*d; val cx=width/2f; val cy=height/2f
        drawCoverPattern(c,paper,Path().apply { addCircle(cx,cy,r,Path.Direction.CW) },d)
        ring.strokeWidth=2*d; c.drawCircle(cx,cy,r,ring)
        if(isSelected) { ring.strokeWidth=3*d; c.drawCircle(cx,cy,r+4*d,ring) }
    }
    override fun setSelected(selected: Boolean) { super.setSelected(selected); invalidate() }
}

/** The kraft tag a thing's name is written on, as a text field's background. */
class TagBackground(private val d: Float) : Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    var focused=false
        set(value) { field=value; invalidateSelf() }
    override fun draw(c: Canvas) {
        val b=bounds; val notch=17*d; val lift=3*d; val h=b.height()-lift
        val shape=Path().apply { moveTo(b.left+notch,b.top.toFloat()); lineTo(b.right-lift,b.top.toFloat()); lineTo(b.right-lift,b.top+h); lineTo(b.left+notch,b.top+h); lineTo(b.left.toFloat(),b.top+h/2); close() }
        paint.style=Paint.Style.FILL; paint.color=Ink.INK; c.save(); c.translate(lift,lift); c.drawPath(shape,paint); c.restore()
        paint.color=if(focused) Color.rgb(232,198,152) else Ink.KRAFT; c.drawPath(shape,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2.2f*d; paint.color=Ink.INK; paint.strokeJoin=Paint.Join.ROUND; c.drawPath(shape,paint)
        paint.style=Paint.Style.FILL; paint.color=Ink.PAPER; c.drawCircle(b.left+15*d,b.top+h/2,4.5f*d,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1.6f*d; paint.color=Ink.INK; c.drawCircle(b.left+15*d,b.top+h/2,4.5f*d,paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter }
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** Any view's background drawn as a page of a book. */
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
        drawSlot(c,RectF(2f,2f,224f,CoverView.HEIGHT-14f),"+\nNew book",hand,true)
        c.restore()
    }
}

/** A hand-drawn arrow from the "start here" note to the add button. */
class ArrowView(context: Context) : View(context) {
    private val d=resources.displayMetrics.density
    private val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; color=Color.WHITE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        c.save(); c.scale(d,d); pen.strokeWidth=2.2f
        c.drawPath(svgPath("M4 8 C 20 24, 38 32, 58 28 M49 22 L59 28 L50 35"),pen); c.restore()
    }
}

/** A strip of tape holding the top of the card below it. */
class TapeView(context: Context, private val kind: Int) : View(context) {
    private val d=resources.displayMetrics.density
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO; elevation=6*d; outlineProvider=null }
    override fun onDraw(c: Canvas) {
        c.save(); c.scale(d,d)
        drawTape(c,width/d/2,height/d/2,58f,3f,kind)
        c.restore()
    }
}
