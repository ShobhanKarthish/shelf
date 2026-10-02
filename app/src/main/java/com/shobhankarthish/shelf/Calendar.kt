package com.shobhankarthish.shelf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import java.util.Calendar
import kotlin.math.*

/** How full a day is, as a colour: one thing, two, three, four or more. */
fun fullness(count: Int)=when(count) { 0 -> Color.WHITE; 1 -> Ink.SKY; 2 -> Ink.MINT; 3 -> Ink.SUN; else -> Ink.BLUSH }

/**
 * One day on the calendar. The date always stays readable in the corner; the day's things sit under it:
 * one alone, two fanned, three in a pile, four in a little grid, and more as three with a +N.
 */
@SuppressLint("ViewConstructor")
class DayCell(context: Context, val day: Int, val items: List<Keepsake>, private val today: Boolean, load: Loader) : View(context) {
    private val d=resources.displayMetrics.density
    private val bitmaps=arrayOfNulls<Bitmap>(4)
    private val shown=if(items.size>4) items.take(3) else items
    private val number=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.DEFAULT_BOLD; color=Ink.INK }
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; color=Ink.ACCENT; strokeCap=Paint.Cap.ROUND }
    private val body=RectF()
    /** How much of the red marker ring around today has been drawn. */
    private var drawn=0f
    private val lift=2*d
    init {
        shown.forEachIndexed { i,item -> load(item,(64*d).roundToInt()) { b -> bitmaps[i]=b; invalidate() } }
        if(items.isNotEmpty()) { isClickable=true; isFocusable=true; pressDown(lift) }
        else importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(c: Canvas) {
        body.set(0f,0f,width-lift,height-lift)
        drawPop(c,body,9*d,fullness(items.size),2*d,if(isPressed) 0f else lift)
        // Under the date: [x %, y %, size in a 41dp-wide cell, tilt].
        val pile=PILES[shown.size] ?: emptyArray()
        val room=RectF(body.left+d,body.top+15*d,body.right-d,body.bottom-2*d); val scale=body.width()/(41*d)
        shown.forEachIndexed { i,item ->
            val b=bitmaps[i] ?: return@forEachIndexed
            val (px,py,size,angle)=pile[i]
            val box=boxOf(item.aspect,size*d*scale)
            val cx=room.left+room.width()*px/100f; val cy=room.top+room.height()*py/100f
            c.save(); c.rotate(angle,cx,cy)
            drawCutout(c,b,RectF(cx-box.x/2,cy-box.y/2,cx+box.x/2,cy+box.y/2),d,1.3f*d)
            c.restore()
        }
        number.textSize=11*d*min(1.15f,scale)
        val label=day.toString(); val tw=number.measureText(label)
        fill.color=fullness(items.size); c.drawRoundRect(body.left+3*d,body.top+2.5f*d,body.left+7*d+tw,body.top+3.5f*d+number.textSize,4*d,4*d,fill)
        c.drawText(label,body.left+5*d,body.top+2*d+number.textSize,number)
        if(items.size>4) {
            val more="+${items.size-3}"; number.textSize=10*d; val w=number.measureText(more)+10*d
            val tag=RectF(body.right-w+4*d,-6*d,body.right+6*d,9*d)
            drawPop(c,tag,7.5f*d,Ink.ACCENT,2*d,0f)
            number.color=Color.WHITE; c.drawText(more,tag.left+5*d,tag.bottom-4.5f*d,number); number.color=Ink.INK
        }
        if(today && drawn>0f) {
            ring.strokeWidth=3*d
            val path=Path(); val m=PathMeasure(markerRing(body),false); m.getSegment(0f,m.length*drawn,path,true)
            c.drawPath(path,ring)
        }
    }
    /** A loop of red marker around the day, drawn by hand: it overshoots where it started. */
    private fun markerRing(r: RectF)=Path().apply {
        val l=r.left-6*d; val t=r.top-6*d; val w=r.width()+12*d; val h=r.height()+12*d
        moveTo(l+w*.56f,t+h*.04f)
        cubicTo(l+w*.86f,t+h*.06f,l+w*.98f,t+h*.26f,l+w*.96f,t+h*.52f)
        cubicTo(l+w*.94f,t+h*.8f,l+w*.8f,t+h*.97f,l+w*.48f,t+h*.96f)
        cubicTo(l+w*.14f,t+h*.95f,l+w*.02f,t+h*.76f,l+w*.04f,t+h*.46f)
        cubicTo(l+w*.06f,t+h*.12f,l+w*.3f,t+h*.01f,l+w*.66f,t+h*.08f)
    }
    /** Circles today in red marker. */
    fun circle() { if(today) tween(700,DecelerateInterpolator(),300,update={ drawn=it; invalidate() }) }
    companion object {
        private val PILES=mapOf(
            1 to arrayOf(floatArrayOf(50f,50f,34f,-8f)),
            2 to arrayOf(floatArrayOf(34f,54f,25f,-14f),floatArrayOf(66f,46f,25f,10f)),
            3 to arrayOf(floatArrayOf(28f,58f,21f,-16f),floatArrayOf(52f,40f,21f,4f),floatArrayOf(74f,60f,21f,14f)),
            4 to arrayOf(floatArrayOf(29f,27f,20f,-10f),floatArrayOf(71f,25f,20f,8f),floatArrayOf(31f,75f,20f,6f),floatArrayOf(69f,75f,20f,-12f)))
    }
}

/** A month on a cream card. Days light up by how full they are; tap one to open it. Swipe for other months. */
@SuppressLint("ViewConstructor")
class CalendarSheet(context: Context, year: Int, private val month: Int, private val byDay: Map<Int,List<Keepsake>>, today: Int,
                    load: Loader, private val onDay: (DayCell) -> Unit) : FrameLayout(context) {
    private val d=resources.displayMetrics.density
    private val cal=Calendar.getInstance().apply { clear(); set(year,month,1) }
    private val firstDow=cal.firstDayOfWeek
    private val lead=Math.floorMod(cal.get(Calendar.DAY_OF_WEEK)-firstDow,7)
    private val days=cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    private val rows=(lead+days+6)/7
    private val cells=(1..days).map { day -> DayCell(context,day,byDay[day].orEmpty(),day==today,load) }
    private val text=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=Typeface.DEFAULT_BOLD; color=Ink.INK; textAlign=Paint.Align.CENTER }
    private val card=RectF()
    private var cellW=0f; private var cellH=0f
    private val gap=5*d; private val pad=8*d; private val head=20*d; private val foot=26*d; private val lift=4*d
    var onSwipe: ((Int) -> Unit)?=null
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f; private var downY=0f; private var swiping=false
    /** The weekday initials, starting on the first day of the week where you are. */
    private val initials=(0..6).map { i -> Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK,Math.floorMod(firstDow-1+i,7)+1) }.getDisplayName(Calendar.DAY_OF_WEEK,Calendar.NARROW_FORMAT,resources.configuration.locales[0]) ?: "" }
    init {
        setWillNotDraw(false); clipChildren=false; clipToPadding=false
        cells.forEach { c ->
            addView(c)
            if(c.items.isNotEmpty()) {
                c.contentDescription="${monthName(month)} ${c.day}: ${c.items.size} thing${if(c.items.size==1) "" else "s"}, ${c.items.joinToString { it.title }}"
                c.setOnClickListener { onDay(c) }
            }
        }
    }
    /** The card's height for a given width, so the calendar can sit at the top of its space. */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w=MeasureSpec.getSize(widthSpec); val h=MeasureSpec.getSize(heightSpec)
        val cw=min(w-28*d,440*d)-lift
        cellW=(cw-2*pad-6*gap)/7f
        cellH=min(cellW*1.42f,(h-lift-2*pad-head-foot-(rows-1)*gap)/rows).coerceAtLeast(cellW*.9f)
        cells.forEach { it.measure(MeasureSpec.makeMeasureSpec(cellW.roundToInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(cellH.roundToInt(),MeasureSpec.EXACTLY)) }
        setMeasuredDimension(w,h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw=min(width-28*d,440*d)-lift; val left=(width-cw-lift)/2
        card.set(left,0f,left+cw,2*pad+head+rows*cellH+(rows-1)*gap+foot)
        cells.forEach { c ->
            val i=lead+c.day-1; val x=card.left+pad+(i%7)*(cellW+gap); val y=card.top+pad+head+(i/7)*(cellH+gap)
            c.layout(x.roundToInt(),y.roundToInt(),x.roundToInt()+c.measuredWidth,y.roundToInt()+c.measuredHeight)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); cells.forEach { it.circle() } }
    override fun onDraw(c: Canvas) {
        drawPop(c,card,18*d,Ink.PAPER,2.5f*d,lift)
        text.textSize=11*d; text.textAlign=Paint.Align.CENTER; text.typeface=Typeface.DEFAULT_BOLD
        initials.forEachIndexed { i,s -> c.drawText(s,card.left+pad+i*(cellW+gap)+cellW/2,card.top+pad+14*d,text) }
        // The key: how full a day is.
        val y=card.bottom-pad-foot/2+2*d
        text.textSize=11*d; text.typeface=Typeface.DEFAULT; val label="how full a day is"; val lw=text.measureText(label)
        val box=20*d; val total=lw+8*d+4*(box+5*d); var x=card.centerX()-total/2
        text.textAlign=Paint.Align.LEFT; c.drawText(label,x,y+4*d,text); x+=lw+8*d
        listOf("1","2","3","4+").forEachIndexed { i,s ->
            val r=RectF(x,y-7*d,x+box,y+7*d); drawPop(c,r,4*d,fullness(i+1),2*d,0f)
            text.textAlign=Paint.Align.CENTER; text.typeface=Typeface.DEFAULT_BOLD; text.textSize=9*d; c.drawText(s,r.centerX(),r.centerY()+3.2f*d,text)
            x+=box+5*d
        }
        text.textAlign=Paint.Align.CENTER
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

/** One thing on a day's sheet: its sticker with its tag hanging under it. */
@SuppressLint("ViewConstructor")
class DayThing(context: Context, val item: Keepsake, private val hand: Typeface, private val angle: Float, load: Loader) : View(context) {
    private val d=resources.displayMetrics.density
    private var bitmap: Bitmap?=null
    init {
        load(item,(130*d).roundToInt()) { bitmap=it; invalidate() }
        contentDescription=item.title; isClickable=true; isFocusable=true
    }
    override fun onDraw(c: Canvas) {
        c.save(); c.scale(d,d)
        val box=boxOf(item.aspect,if(item.framed) 124f else 96f)
        val cx=width/d/2-8f; val cy=58f
        bitmap?.let { b ->
            c.save(); c.rotate(angle,cx,cy)
            drawCutout(c,b,RectF(cx-box.x/2,cy-box.y/2,cx+box.x/2,cy+box.y/2),1.6f,3f)
            if(item.framed) { c.translate(cx-box.x/2,cy-box.y/2); drawFastener(c,box.x,seedOf(item.id)) }
            c.restore()
        }
        drawTag(c,item.title,8f,cy+box.y/2+10f,-angle/2,hand)
        c.restore()
    }
}

/**
 * A day's things laid out loosely in two columns. [arrive] makes each one jump out of the calendar cell it came from
 * and land in its spot, one after another.
 */
@SuppressLint("ViewConstructor")
class DaySpread(context: Context, items: List<Keepsake>, hand: Typeface, load: Loader, onThing: (Keepsake) -> Unit) : ViewGroup(context) {
    private val d=resources.displayMetrics.density
    private val tilts=floatArrayOf(-6f,7f,5f,-8f,-4f,6f)
    private val things=items.mapIndexed { i,item -> DayThing(context,item,hand,tilts[i%tilts.size],load).apply { setOnClickListener { onThing(item) } } }
    // Hidden until they arrive, so nothing shows in its spot before it has jumped there.
    init { clipChildren=false; things.forEach { addView(it); if(animating) it.alpha=0f } }
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w=MeasureSpec.getSize(widthSpec); val col=(w/2f).roundToInt()
        things.forEach { it.measure(MeasureSpec.makeMeasureSpec(col,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((150*d).roundToInt(),MeasureSpec.EXACTLY)) }
        setMeasuredDimension(w,(((things.size+1)/2)*140*d+40*d).roundToInt())
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        things.forEachIndexed { i,v ->
            val x=if(i%2==0) 0 else width/2; val y=((i/2)*140*d+(if(i%2==1) 16*d else 0f)).roundToInt()
            v.layout(x,y,x+v.measuredWidth,y+v.measuredHeight)
        }
    }
    /** Each thing jumps out of [from] and lands in its place, starting after [delay]. Call it once the spread is where it will stay. */
    fun arrive(from: View, delay: Long) {
        if(!animating) return
        val f=IntArray(2); from.getLocationInWindow(f)
        things.forEachIndexed { i,v ->
            val t=IntArray(2); v.getLocationInWindow(t)
            val dx=f[0]+from.width/2f-(t[0]+v.width/2f); val dy=f[1]+from.height/2f-(t[1]+v.height/2f)
            tween(620,spring,delay+i*80L,update={ p -> v.alpha=min(1f,p*3); v.translationX=dx*(1-p); v.translationY=dy*(1-p); v.scaleX=.2f+.8f*p; v.scaleY=v.scaleX; v.rotation=-30f*(1-p) },
                end={ v.alpha=1f; v.translationX=0f; v.translationY=0f; v.scaleX=1f; v.scaleY=1f; v.rotation=0f })
        }
    }
}
