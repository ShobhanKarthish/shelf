package com.shobhankarthish.shelf

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.*

/*
 * The scrapbook's vocabulary. Each thing means one thing everywhere:
 * a book's name is cut-out letters, a thing's name is a kraft tag, a story is a note in blue pen,
 * a date is a red stamp, flat paper is held by tape, and doodles only ever fill empty space.
 * Anything you can touch has an ink outline and a hard shadow, like a sticker lifted off the page.
 * Most of this draws in page units: one unit is 1/360 of a page's width, so callers scale the canvas first.
 */

/**
 * A book's pattern, in one of the inks. It's the one thing that changes from book to book: bold on the cover,
 * paler on every page inside, so the things stuck there stay easy to see.
 */
enum class Paper(val label: String, val ink: Int) {
    GRID("Grid", Ink.COBALT),
    DOTS("Dots", Ink.SUN),
    STRIPES("Stripes", Ink.BLUSH),
    CHECKER("Checker", Ink.SKY),
    GINGHAM("Gingham", Ink.MINT);
    /** The page colour under the pattern. */
    val ground get()=Ink.PAPER
    companion object {
        /** Papers from before the patterns were named this way become the nearest pattern. */
        fun of(name: String?)=when(name) { "LINED" -> STRIPES; "PLAIN" -> GINGHAM; "CORK" -> CHECKER; else -> entries.firstOrNull { it.name==name } ?: GRID }
    }
}

private val groundPaint=Paint()
private val patterns=HashMap<String,BitmapShader>()
private var patternDensity=0f
/** A repeating tile of [paper]'s pattern: [cover] is the bold version for the outside of the book. */
private fun pattern(paper: Paper, cover: Boolean, d: Float): BitmapShader {
    if(patternDensity!=d) { patterns.clear(); patternDensity=d }
    return patterns.getOrPut("${paper.name}$cover") {
        val p=Paint(Paint.ANTI_ALIAS_FLAG); val ink=paper.ink
        fun tile(size: Float, draw: Canvas.(Float) -> Unit)=Bitmap.createBitmap((size*d).roundToInt(),(size*d).roundToInt(),Bitmap.Config.ARGB_8888).also { b -> Canvas(b).draw(b.width.toFloat()) }
        val bitmap=when(paper) {
            Paper.GINGHAM -> tile(if(cover) 28f else 32f) { s ->
                drawColor(if(cover) Color.WHITE else Ink.PAPER); p.color=Ink.alpha(ink,if(cover) .55f else .26f)
                drawRect(0f,0f,s,s/2,p); drawRect(0f,0f,s/2,s,p) }
            Paper.DOTS -> tile(if(cover) 16f else 22f) { s ->
                drawColor(if(cover) ink else Ink.PAPER); p.color=if(cover) Ink.INK else Ink.alpha(ink,.8f)
                drawCircle(s/2,s/2,(if(cover) 2.3f else 3f)*d,p) }
            Paper.STRIPES -> tile(if(cover) 24f else 32f) { s ->
                drawColor(if(cover) Color.WHITE else Ink.PAPER); p.color=if(cover) ink else Ink.alpha(ink,.3f); drawRect(0f,0f,s/2,s,p) }
            Paper.CHECKER -> tile(if(cover) 28f else 32f) { s ->
                drawColor(if(cover) Color.WHITE else Ink.PAPER); p.color=if(cover) ink else Ink.alpha(ink,.35f)
                drawRect(0f,0f,s/2,s/2,p); drawRect(s/2,s/2,s,s,p) }
            Paper.GRID -> tile(if(cover) 18f else 16f) { s ->
                drawColor(if(cover) Ink.light(Ink.SKY,.55f) else Ink.PAPER); p.color=Ink.alpha(ink,if(cover) .7f else .2f)
                val w=(if(cover) 2f else 1.2f)*d; drawRect(0f,0f,s,w,p); drawRect(0f,0f,w,s,p) }
        }
        BitmapShader(bitmap,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT)
    }
}
/** Fills [rect] with a page of [paper]. The canvas is in pixels; [d] is the screen density. */
fun drawPaper(c: Canvas, paper: Paper, rect: RectF, d: Float) {
    groundPaint.shader=pattern(paper,false,d); c.drawRect(rect,groundPaint); groundPaint.shader=null
}
/** Fills [shape] with the bold pattern of [paper]'s cover. The canvas is in pixels. */
fun drawCoverPattern(c: Canvas, paper: Paper, shape: Path, d: Float) {
    groundPaint.shader=pattern(paper,true,d); c.drawPath(shape,groundPaint); groundPaint.shader=null
}

/** Ink colours beyond the base palette: the desk the books sit on, the pen everything is written in, and the string tags hang from. */
object Pen { val DESK=Ink.COBALT; val BLUE=Color.rgb(45,69,214); val STRING=Color.rgb(138,122,98) }

private val fillPaint=Paint(Paint.ANTI_ALIAS_FLAG)
private val strokePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
private val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG)

/**
 * A rounded card in the house style: [fill] outlined in ink, standing on a hard ink shadow [lift] below and to the right.
 * [rect] is the card itself; the shadow falls outside it.
 */
fun drawPop(c: Canvas, rect: RectF, radius: Float, fill: Int, line: Float, lift: Float) {
    fillPaint.shader=null
    if(lift>0f) { fillPaint.color=Ink.INK; c.drawRoundRect(rect.left+lift,rect.top+lift,rect.right+lift,rect.bottom+lift,radius,radius,fillPaint) }
    fillPaint.color=fill; c.drawRoundRect(rect,radius,radius,fillPaint)
    strokePaint.color=Ink.INK; strokePaint.strokeWidth=line; strokePaint.pathEffect=null
    c.drawRoundRect(rect.left+line/2,rect.top+line/2,rect.right-line/2,rect.bottom-line/2,max(0f,radius-line/2),max(0f,radius-line/2),strokePaint)
}

private val maskPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter=PorterDuffColorFilter(Ink.INK,PorterDuff.Mode.SRC_IN) }
private val bitmapPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
private val ROUND=Array(8) { i -> val a=i*PI/4; floatArrayOf(cos(a).toFloat(),sin(a).toFloat()) }
/**
 * A sticker or print [b] in [dst]: traced in ink [line] wide around its own edge, standing on a hard shadow [lift] away.
 * The sticker's white border is already part of the bitmap.
 */
fun drawCutout(c: Canvas, b: Bitmap, dst: RectF, line: Float, lift: Float, alpha: Int=255) {
    if(lift>0f) { maskPaint.alpha=alpha*90/255; c.save(); c.translate(lift,lift*1.25f); c.drawBitmap(b,null,dst,maskPaint); c.restore() }
    if(line>0f) {
        maskPaint.alpha=alpha
        for(o in ROUND) { c.save(); c.translate(o[0]*line,o[1]*line); c.drawBitmap(b,null,dst,maskPaint); c.restore() }
    }
    bitmapPaint.alpha=alpha; c.drawBitmap(b,null,dst,bitmapPaint)
}

/** Fat display words in white, outlined in ink and standing on a hard ink shadow, centred on [cx] with the baseline at [y]. */
fun drawOutlined(c: Canvas, text: String, cx: Float, y: Float, size: Float, face: Typeface, fill: Int=Color.WHITE) {
    textPaint.reset(); textPaint.isAntiAlias=true; textPaint.letterSpacing=0f; textPaint.typeface=face; textPaint.textSize=size; textPaint.textAlign=Paint.Align.CENTER
    val line=size*.09f; val lift=size*.07f
    textPaint.style=Paint.Style.FILL_AND_STROKE; textPaint.strokeWidth=line*2; textPaint.strokeJoin=Paint.Join.ROUND; textPaint.color=Ink.INK
    c.drawText(text,cx+lift,y+lift,textPaint); c.drawText(text,cx,y,textPaint)
    textPaint.style=Paint.Style.FILL; textPaint.color=fill; c.drawText(text,cx,y,textPaint)
    textPaint.textAlign=Paint.Align.LEFT
}

/** A strip of washi tape centred on [cx],[cy]. Four kinds, chosen by [kind]. */
fun drawTape(c: Canvas, cx: Float, cy: Float, width: Float, angle: Float, kind: Int) {
    c.save(); c.translate(cx,cy); c.rotate(angle)
    val (a,b)=when(Math.floorMod(kind,4)) { 0 -> Ink.SUN to Ink.light(Ink.SUN,.45f); 1 -> Ink.SKY to Ink.light(Ink.SKY,.45f); 2 -> Ink.BLUSH to Ink.light(Ink.BLUSH,.4f); else -> Ink.MINT to Ink.light(Ink.MINT,.45f) }
    fillPaint.shader=when(Math.floorMod(kind,4)) {
        0 -> LinearGradient(0f,0f,16f,0f,intArrayOf(a,a,b,b),floatArrayOf(0f,.5f,.5f,1f),Shader.TileMode.REPEAT)
        1 -> LinearGradient(0f,0f,10f,0f,intArrayOf(a,a,b,b),floatArrayOf(0f,.5f,.5f,1f),Shader.TileMode.REPEAT)
        2 -> LinearGradient(0f,0f,9.9f,-9.9f,intArrayOf(a,a,b,b),floatArrayOf(0f,.5f,.5f,1f),Shader.TileMode.REPEAT)
        else -> LinearGradient(0f,0f,-8.5f,-8.5f,intArrayOf(a,a,b,b),floatArrayOf(0f,.5f,.5f,1f),Shader.TileMode.REPEAT)
    }
    fillPaint.color=a; fillPaint.alpha=235
    c.drawRect(-width/2,-10f,width/2,10f,fillPaint)
    fillPaint.shader=null; fillPaint.alpha=255
    c.restore()
}
/** Tape across the top edge of a flat thing [w] wide. */
fun drawFastener(c: Canvas, w: Float, seed: Int) {
    val r=Rng(seed)
    drawTape(c,w/2+r.between(-10f,10f),0f,58f,r.between(-12f,12f),(r.next()*4).toInt())
}

private fun stampPaint(): TextPaint=textPaint.apply { reset(); isAntiAlias=true; typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD); textSize=12f; letterSpacing=.12f; color=Ink.ACCENT }
fun stampWidth(text: String)=stampPaint().measureText(text)+18f+6f
const val STAMP_HEIGHT=12f+12f+6f
/** A red date stamp with its top-left corner at [left],[top], ringed twice like a rubber stamp. */
fun drawStamp(c: Canvas, text: String, left: Float, top: Float, angle: Float, punch: Float=1f) {
    val p=stampPaint(); val w=stampWidth(text); val h=STAMP_HEIGHT
    c.save(); c.rotate(angle,left+w/2,top+h/2); c.scale(punch,punch,left+w/2,top+h/2)
    fillPaint.shader=null; fillPaint.color=Color.argb(110,255,255,255); c.drawRoundRect(left,top,left+w,top+h,6f,6f,fillPaint)
    strokePaint.color=Ink.ACCENT; strokePaint.strokeWidth=2.2f; strokePaint.shader=null; strokePaint.pathEffect=null
    c.drawRoundRect(left+1.1f,top+1.1f,left+w-1.1f,top+h-1.1f,6f,6f,strokePaint)
    strokePaint.strokeWidth=1.2f; c.drawRoundRect(left+4.5f,top+4.5f,left+w-4.5f,top+h-4.5f,3f,3f,strokePaint)
    c.drawText(text,left+12f,top+h/2-(p.descent()+p.ascent())/2,p)
    c.restore()
}

/** The string from a thing to its tag. */
fun drawString(c: Canvas, path: Path) { strokePaint.color=Pen.STRING; strokePaint.strokeWidth=1.4f; strokePaint.shader=null; strokePaint.pathEffect=null; c.drawPath(path,strokePaint) }

/** The longest a small tag gets, so two fit side by side on a page; longer names are shortened to fit. */
const val TAG_MAX=150f
/** How wide a small tag for [name] is, in page units. */
fun tagWidth(name: String, hand: Typeface): Float {
    textPaint.reset(); textPaint.isAntiAlias=true; textPaint.letterSpacing=0f; textPaint.typeface=hand; textPaint.textSize=18f
    return textPaint.measureText(TextUtils.ellipsize(name,textPaint,TAG_MAX-19f-11f,TextUtils.TruncateAt.END).toString())+19f+11f
}
/**
 * A kraft name tag, like the one in the app icon. [anchorX] is the end with the hole; with [holeRight] the tag
 * hangs to the left of it, and with [center] it hangs centred on it. Long names are shortened to fit.
 */
fun drawTag(c: Canvas, name: String, anchorX: Float, top: Float, angle: Float, hand: Typeface, big: Boolean=false, holeRight: Boolean=false, center: Boolean=false) {
    val h=if(big) 38f else 28f; val size=if(big) 25f else 18f; val notch=if(big) 14f else 11f
    val holePad=if(big) 24f else 19f; val endPad=if(big) 16f else 11f; val maxWidth=if(big) 250f else TAG_MAX
    textPaint.reset(); textPaint.isAntiAlias=true; textPaint.letterSpacing=0f; textPaint.typeface=hand; textPaint.textSize=size; textPaint.color=Ink.INK
    val text=TextUtils.ellipsize(name,textPaint,maxWidth-holePad-endPad,TextUtils.TruncateAt.END).toString()
    val w=textPaint.measureText(text)+holePad+endPad
    c.save(); c.translate(anchorX,top); c.rotate(angle)
    if(center) c.translate(-w/2,0f) else if(holeRight) c.translate(-w,0f)
    val shape=Path().apply {
        if(holeRight) { moveTo(0f,0f); lineTo(w-notch,0f); lineTo(w,h/2); lineTo(w-notch,h); lineTo(0f,h) }
        else { moveTo(notch,0f); lineTo(w,0f); lineTo(w,h); lineTo(notch,h); lineTo(0f,h/2) }
        close()
    }
    fillPaint.shader=null; fillPaint.color=Ink.KRAFT; c.drawPath(shape,fillPaint)
    strokePaint.color=Ink.INK; strokePaint.strokeWidth=2f; strokePaint.pathEffect=null; c.drawPath(shape,strokePaint)
    val holeX=if(holeRight) w-(if(big) 13f else 10f) else (if(big) 13f else 10f); val r=if(big) 4f else 3f
    fillPaint.color=Ink.PAPER; c.drawCircle(holeX,h/2,r,fillPaint)
    strokePaint.strokeWidth=1.4f; c.drawCircle(holeX,h/2,r,strokePaint)
    c.drawText(text,if(holeRight) endPad else holePad,h/2-(textPaint.descent()+textPaint.ascent())/2,textPaint)
    c.restore()
}

/** How wide a note under a thing is, in page units: two fit side by side on a page. */
const val NOTE_WIDTH=150f
/** A few words in blue pen, wrapped to [width] and centred, at most two lines. */
class PenNote(text: String, hand: Typeface, val width: Float=NOTE_WIDTH, size: Float=18f) {
    private val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface=hand; textSize=size; color=Pen.BLUE }
    private val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,width.roundToInt().coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(0f,1.02f).setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).build()
    val height get()=layout.height.toFloat()
    fun draw(c: Canvas, left: Float, top: Float, angle: Float) {
        c.save(); c.translate(left,top); c.rotate(angle,width/2,height/2)
        layout.draw(c); c.restore()
    }
}

/** Short enough to write next to a thing: the first sentence, or the first few words. */
fun snippet(story: String): String {
    val s=story.trim(); if(s.isEmpty()) return ""
    val first=s.split(Regex("(?<=[.!?])\\s")).first()
    if(first.length<=38) return first
    val cut=s.take(32); val space=cut.lastIndexOf(' ')
    return (if(space>12) cut.take(space) else cut)+"…"
}

/** A book's name cut letter by letter from bright paper, each tile outlined and standing on its shadow. Laid out once, in page units. */
class CutLetters(text: String, size: Float, seed: Int, private val maxWidth: Float) {
    private class Letter(val ch: String, val look: Int, val angle: Float, val dy: Float) { var x=0f; var y=0f; var w=0f; var h=0f }
    private val letters=ArrayList<Letter>()
    private val face=Fonts.displayFace
    private val size=size
    var width=0f; private set
    var height=0f; private set
    /** How tall the letters are once shrunk to fit [room]. */
    fun heightIn(room: Float)=height*min(1f,room/max(1f,width))
    init {
        val r=Rng(seed); var look=(r.next()*LOOKS.size).toInt()
        val words=text.uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { word ->
            // Neighbouring letters never share a colour.
            word.map { ch -> look=(look+1+(r.next()*(LOOKS.size-1)).toInt())%LOOKS.size; Letter(ch.toString(),look,(r.next()-.5f)*10f,(r.next()-.5f)*3f) }
        }
        val rows=ArrayList<MutableList<Letter>>(); var row=ArrayList<Letter>(); var x=0f
        for(word in words) {
            word.forEach { l -> paint(); l.w=max(textPaint.measureText(l.ch),size*.5f)+size*.5f; l.h=size*1.28f }
            val ww=word.sumOf { it.w.toDouble() }.toFloat()+3f*(word.size-1)
            if(x>0f && x+ww>maxWidth) { rows+=row; row=ArrayList(); x=0f }
            word.forEach { l -> l.x=x; x+=l.w+3f; row+=l }
            x+=size*.45f
        }
        if(row.isNotEmpty()) rows+=row
        var y=0f
        for(line in rows) {
            val rh=line.maxOf { it.h }
            line.forEach { it.y=y+rh-it.h; letters+=it }
            width=max(width,line.last().x+line.last().w); y+=rh+5f
        }
        height=max(0f,y-5f)
    }
    private fun paint(): TextPaint=textPaint.apply { reset(); isAntiAlias=true; letterSpacing=0f; typeface=face; textSize=size }
    /** Draws the letters with their top-left at [left],[top], shrunk if need be so they're no wider than [room]. */
    fun draw(c: Canvas, left: Float, top: Float, room: Float=Float.MAX_VALUE) {
        val fit=min(1f,room/max(1f,width))
        if(fit<1f) { c.save(); c.translate(left,top); c.scale(fit,fit); draw(c,0f,0f); c.restore(); return }
        val line=max(1.6f,size*.075f); val lift=max(1.6f,size*.08f)
        for(l in letters) {
            val look=LOOKS[l.look]
            c.save(); c.translate(left+l.x+l.w/2,top+l.y+l.h/2+l.dy); c.rotate(l.angle)
            drawPop(c,RectF(-l.w/2,-l.h/2,l.w/2,l.h/2),1.5f,look.first,line,lift)
            val p=paint(); p.color=look.second; p.textAlign=Paint.Align.CENTER
            c.drawText(l.ch,0f,-(p.descent()+p.ascent())/2+size*.04f,p); p.textAlign=Paint.Align.LEFT
            c.restore()
        }
    }
    companion object {
        private val LOOKS by lazy { listOf(Ink.ACCENT to Color.WHITE,Ink.SUN to Ink.INK,Color.WHITE to Ink.INK,Ink.SKY to Ink.INK,Ink.INK to Color.WHITE,Ink.BLUSH to Ink.INK,Ink.MINT to Ink.INK) }
    }
}

/** A dashed place waiting to be filled, with a line of handwriting in it. */
fun drawSlot(c: Canvas, rect: RectF, text: String, hand: Typeface, onDark: Boolean) {
    strokePaint.shader=null; strokePaint.color=if(onDark) Color.argb(160,255,255,255) else Ink.alpha(Ink.INK,.35f); strokePaint.strokeWidth=2.5f
    strokePaint.pathEffect=DashPathEffect(floatArrayOf(8f,6f),0f); c.drawRoundRect(rect,22f,22f,strokePaint); strokePaint.pathEffect=null
    textPaint.reset(); textPaint.isAntiAlias=true; textPaint.letterSpacing=0f; textPaint.typeface=hand; textPaint.textSize=19f; textPaint.color=if(onDark) Color.WHITE else Ink.INK_2; textPaint.textAlign=Paint.Align.CENTER
    val lines=text.split("\n"); val lh=21f; var y=rect.centerY()-lh*(lines.size-1)/2-(textPaint.descent()+textPaint.ascent())/2
    lines.forEach { c.drawText(it,rect.centerX(),y,textPaint); y+=lh }
    textPaint.textAlign=Paint.Align.LEFT
}

/** Thin stacked page edges along one side, one line for each page still to come (or already turned). */
fun drawPageEdges(c: Canvas, x: Float, top: Float, bottom: Float, pages: Int, right: Boolean) {
    if(pages<=0) return
    val w=pages*3f+1f; val left=if(right) x-w else x
    fillPaint.shader=null; fillPaint.color=Color.WHITE; c.drawRect(left,top,left+w,bottom,fillPaint)
    fillPaint.color=Ink.alpha(Ink.INK,.45f)
    for(i in 0..pages) { val lx=left+i*3f; c.drawRect(lx,top,lx+1f,bottom,fillPaint) }
}

private val months=arrayOf("JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC")
fun stampDate(time: Long): String {
    val c=java.util.Calendar.getInstance().apply { timeInMillis=time }
    return "${c.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2,'0')} ${months[c.get(java.util.Calendar.MONTH)]} ${c.get(java.util.Calendar.YEAR)}"
}
