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
 * a date is a red stamp, and flat paper is held by tape (or a pin, on cork).
 * Everything here draws in page units: one unit is 1/360 of a page's width, so callers scale the canvas first.
 */

/** The paper a book is made of. It is the only thing a book chooses. */
enum class Paper(val label: String, val ground: Int) {
    GRID("Grid", Color.rgb(250,248,242)),
    DOTS("Dots", Color.rgb(251,250,246)),
    LINED("Lined", Color.rgb(253,252,248)),
    PLAIN("Plain", Color.rgb(251,250,246)),
    CORK("Cork", Color.rgb(198,148,89));
    /** Words on cork are white, notes go on paper slips, and photos are pinned instead of taped. */
    val dark get()=this==CORK
    companion object { fun of(name: String?)=entries.firstOrNull { it.name==name } ?: GRID }
}

/**
 * Paper grain, made once from noise on a background thread and stretched or tiled over pages, covers and the
 * calendar. Until it's ready, paper is drawn without grain rather than making anyone wait.
 */
object Textures {
    @Volatile var crumple: Bitmap?=null; private set
    @Volatile var cork: Bitmap?=null; private set
    @Volatile var corkShade: Bitmap?=null; private set
    @Volatile var linen: Bitmap?=null; private set
    private val waiting=ArrayList<() -> Unit>()
    @Volatile private var started=false
    /** Makes the textures once; [ready] runs on the main thread when they exist (straight away if they already do). */
    fun warm(ready: () -> Unit={}) {
        if(linen!=null) { ready(); return }
        synchronized(waiting) { waiting+=ready }
        if(started) return
        started=true
        Stickers.background {
            crumple=crumpled(120,256,11); cork=specks(160,9); corkShade=blotches(64,136,5); linen=linen(96,4)
            android.os.Handler(android.os.Looper.getMainLooper()).post { val all=synchronized(waiting) { waiting.toList().also { waiting.clear() } }; all.forEach { it() } }
        }
    }

    /** Smooth value noise over a [cols]×[rows] lattice that wraps, so tiles meet without seams. */
    private class Lattice(val cols: Int, val rows: Int, seed: Int) {
        private val v=FloatArray(cols*rows).also { val r=Rng(seed); for(i in it.indices) it[i]=r.next() }
        fun at(x: Float, y: Float): Float {
            val x0=floor(x).toInt(); val y0=floor(y).toInt(); val fx=x-x0; val fy=y-y0
            val sx=fx*fx*(3-2*fx); val sy=fy*fy*(3-2*fy)
            val i0=Math.floorMod(x0,cols); val i1=(i0+1)%cols; val j0=Math.floorMod(y0,rows); val j1=(j0+1)%rows
            val a=v[j0*cols+i0]; val b=v[j0*cols+i1]; val c=v[j1*cols+i0]; val d=v[j1*cols+i1]
            return a+(b-a)*sx+(c-a)*sy+(a-b-c+d)*sx*sy
        }
    }
    private fun fbm(layers: Array<Lattice>, x: Float, y: Float): Float {
        var sum=0f; var amp=.5f; var f=1f; var total=0f
        for(l in layers) { sum+=l.at(x*f,y*f)*amp; total+=amp; amp*=.5f; f*=2f }
        return sum/total
    }
    /** Creased paper: ridged noise lit from the top left, kept as a dark shadow mask. */
    private fun crumpled(w: Int, h: Int, seed: Int): Bitmap {
        val cell=11f; val layers=Array(4) { Lattice(64,64,seed+it) }
        val height=FloatArray(w*h)
        for(y in 0 until h) for(x in 0 until w) { val n=fbm(layers,x/cell,y/cell); height[y*w+x]=1-abs(2*n-1) }
        val lx=-.304f; val ly=-.434f; val lz=.848f; val relief=1.5f; val out=IntArray(w*h)
        for(y in 0 until h) for(x in 0 until w) {
            val dx=(height[y*w+min(w-1,x+1)]-height[y*w+max(0,x-1)])*relief
            val dy=(height[min(h-1,y+1)*w+x]-height[max(0,y-1)*w+x])*relief
            val shade=max(0f,(-dx*lx-dy*ly+lz)/sqrt(dx*dx+dy*dy+1))
            out[y*w+x]=Color.argb(((1-shade)*.26f*255).roundToInt().coerceIn(0,255),28,24,18)
        }
        return Bitmap.createBitmap(out,w,h,Bitmap.Config.ARGB_8888)
    }
    private fun specks(size: Int, seed: Int): Bitmap {
        val r=Rng(seed); val out=IntArray(size*size)
        for(i in out.indices) { val v=r.next(); out[i]=when { v<.11f -> Color.argb((110+r.next()*100).toInt(),92,54,20); v>.945f -> Color.argb((110+r.next()*110).toInt(),237,199,140); else -> 0 } }
        return Bitmap.createBitmap(out,size,size,Bitmap.Config.ARGB_8888)
    }
    private fun blotches(w: Int, h: Int, seed: Int): Bitmap {
        val layers=Array(2) { Lattice(32,32,seed+it) }; val out=IntArray(w*h)
        for(y in 0 until h) for(x in 0 until w) out[y*w+x]=Color.argb(((1.6f*fbm(layers,x/6f,y/6f)-.7f).coerceIn(0f,1f)*.35f*255).roundToInt(),115,71,31)
        return Bitmap.createBitmap(out,w,h,Bitmap.Config.ARGB_8888)
    }
    /** Fine threads running down the cover, repeating every [size] pixels. */
    private fun linen(size: Int, seed: Int): Bitmap {
        val fine=Lattice(size,size/12,seed); val coarse=Lattice(size/2,size/24,seed+1); val out=IntArray(size*size)
        for(y in 0 until size) for(x in 0 until size) {
            val n=fine.at(x.toFloat(),y/12f)*.6f+coarse.at(x/2f,y/24f)*.4f
            out[y*size+x]=Color.argb(((1.4f*n-.4f).coerceIn(0f,1f)*.25f*255).roundToInt(),115,89,51)
        }
        return Bitmap.createBitmap(out,size,size,Bitmap.Config.ARGB_8888)
    }
}

private val groundPaint=Paint()
private val grainPaint=Paint(Paint.FILTER_BITMAP_FLAG)
private val rulings=HashMap<Paper,BitmapShader>()
private var rulingDensity=0f
private fun ruling(paper: Paper, d: Float): BitmapShader? {
    if(rulingDensity!=d) { rulings.clear(); rulingDensity=d }
    if(paper==Paper.PLAIN || paper==Paper.CORK) return null
    return rulings.getOrPut(paper) {
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        val tile=when(paper) {
            Paper.GRID -> Bitmap.createBitmap((16*d).roundToInt(),(16*d).roundToInt(),Bitmap.Config.ARGB_8888).also { b ->
                p.color=Color.rgb(228,224,214); Canvas(b).apply { drawRect(0f,0f,b.width.toFloat(),max(1f,d),p); drawRect(0f,0f,max(1f,d),b.height.toFloat(),p) } }
            Paper.DOTS -> Bitmap.createBitmap((16*d).roundToInt(),(16*d).roundToInt(),Bitmap.Config.ARGB_8888).also { b ->
                p.color=Color.rgb(203,197,184); Canvas(b).drawCircle(b.width/2f,b.height/2f,1.1f*d,p) }
            else -> Bitmap.createBitmap(4,(24*d).roundToInt(),Bitmap.Config.ARGB_8888).also { b ->
                p.color=Color.rgb(211,225,236); Canvas(b).drawRect(0f,b.height-max(1f,d),4f,b.height.toFloat(),p) }
        }
        BitmapShader(tile,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT)
    }
}
private var corkShader: BitmapShader?=null
private var linenShader: BitmapShader?=null

/** Fills [rect] with [paper]: its colour, its ruling, then its grain. The canvas is in pixels; [d] is the screen density. */
fun drawPaper(c: Canvas, paper: Paper, rect: RectF, d: Float, grain: Float=1f) {
    groundPaint.shader=null; groundPaint.color=paper.ground; c.drawRect(rect,groundPaint)
    ruling(paper,d)?.let { groundPaint.shader=it; c.drawRect(rect,groundPaint); groundPaint.shader=null }
    if(grain<=0f) return
    grainPaint.alpha=(grain*255).roundToInt()
    if(paper==Paper.CORK) {
        val specks=Textures.cork ?: return; val shade=Textures.corkShade ?: return
        val shader=corkShader ?: BitmapShader(specks,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT).also { corkShader=it }
        shader.setLocalMatrix(Matrix().apply { setScale(d*.55f,d*.55f) })
        grainPaint.shader=shader; c.drawRect(rect,grainPaint); grainPaint.shader=null
        c.drawBitmap(shade,null,rect,grainPaint)
    } else Textures.crumple?.let { c.drawBitmap(it,null,rect,grainPaint) }
}
/** The linen of a book cover. */
fun drawLinen(c: Canvas, rect: RectF, d: Float) {
    val tile=Textures.linen ?: return
    val shader=linenShader ?: BitmapShader(tile,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT).also { linenShader=it }
    shader.setLocalMatrix(Matrix().apply { setScale(d*.6f,d*.6f) })
    grainPaint.alpha=255; grainPaint.shader=shader; c.drawRect(rect,grainPaint); grainPaint.shader=null
}

/** Ink colours beyond the base palette: the desk the books sit on, and the pen everything is written in. */
object Pen { val DESK=Color.rgb(38,43,61); val BLUE=Color.rgb(47,85,164); val STRING=Color.rgb(138,122,98) }

private val fillPaint=Paint(Paint.ANTI_ALIAS_FLAG)
private val strokePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
private val textPaint=TextPaint(Paint.ANTI_ALIAS_FLAG)

/** A strip of washi tape centred on [cx],[cy]. Four kinds, chosen by [kind]. */
fun drawTape(c: Canvas, cx: Float, cy: Float, width: Float, angle: Float, kind: Int) {
    c.save(); c.translate(cx,cy); c.rotate(angle)
    val (a,b)=when(Math.floorMod(kind,4)) { 0 -> Color.rgb(255,224,138) to Color.rgb(255,213,107); 1 -> Color.rgb(203,225,242) to Color.rgb(184,213,236); 2 -> Ink.BLUSH to Ink.BLUSH; else -> Ink.MINT to Color.rgb(178,220,195) }
    fillPaint.shader=when(Math.floorMod(kind,4)) {
        0 -> LinearGradient(0f,0f,8.5f,-8.5f,intArrayOf(a,a,b,b),floatArrayOf(0f,.5f,.5f,1f),Shader.TileMode.REPEAT)
        1 -> LinearGradient(0f,0f,10f,0f,intArrayOf(a,a,b,b),floatArrayOf(0f,.5f,.5f,1f),Shader.TileMode.REPEAT)
        3 -> LinearGradient(0f,0f,-5.7f,-5.7f,intArrayOf(a,a,b,b),floatArrayOf(0f,.5f,.5f,1f),Shader.TileMode.REPEAT)
        else -> null
    }
    fillPaint.color=a; fillPaint.alpha=230
    c.drawRect(-width/2,-9f,width/2,9f,fillPaint)
    fillPaint.shader=null; fillPaint.alpha=255
    c.restore()
}
/** A red pushpin with its point at [cx],[cy]. */
fun drawPin(c: Canvas, cx: Float, cy: Float) {
    fillPaint.shader=null; fillPaint.color=Color.argb(90,0,0,0); c.drawCircle(cx+2f,cy+3f,7.5f,fillPaint)
    fillPaint.shader=RadialGradient(cx,cy,7.5f,intArrayOf(Color.rgb(224,69,42),Color.rgb(224,69,42),Color.rgb(168,42,22)),floatArrayOf(0f,.6f,1f),Shader.TileMode.CLAMP)
    c.drawCircle(cx,cy,7.5f,fillPaint); fillPaint.shader=null
    fillPaint.color=Color.WHITE; c.drawCircle(cx-2f,cy-2.5f,1.5f,fillPaint)
}
/** Tape on paper, a pin on cork: how a flat thing [w] wide is held at its top edge. */
fun drawFastener(c: Canvas, paper: Paper, w: Float, seed: Int) {
    val r=Rng(seed)
    if(paper.dark) drawPin(c,w/2,3f) else drawTape(c,w/2+r.between(-10f,10f),0f,54f,r.between(-8f,8f),(r.next()*4).toInt())
}

private fun stampPaint(): TextPaint=textPaint.apply { reset(); isAntiAlias=true; typeface=Typeface.create(Typeface.MONOSPACE,Typeface.BOLD); textSize=11f; letterSpacing=.12f; color=Ink.ACCENT }
fun stampWidth(text: String)=stampPaint().measureText(text)+16f+3.4f
/** A red date stamp with its top-left corner at [left],[top]; on cork it is stamped onto a paper slip. */
fun drawStamp(c: Canvas, text: String, left: Float, top: Float, angle: Float, slip: Boolean) {
    val p=stampPaint(); val w=stampWidth(text); val h=11f+12f+3.4f
    c.save(); c.rotate(angle,left+w/2,top+h/2)
    if(slip) { fillPaint.color=Color.rgb(255,253,246); c.drawRoundRect(left,top,left+w,top+h,4f,4f,fillPaint) }
    strokePaint.color=Ink.ACCENT; strokePaint.alpha=225; strokePaint.strokeWidth=1.7f; strokePaint.shader=null
    c.drawRoundRect(left+.85f,top+.85f,left+w-.85f,top+h-.85f,4f,4f,strokePaint)
    p.alpha=225; c.drawText(text,left+9.7f,top+h/2-(p.descent()+p.ascent())/2,p)
    c.restore(); strokePaint.alpha=255
}

/** The string from a thing to its tag. */
fun drawString(c: Canvas, path: Path) { strokePaint.color=Pen.STRING; strokePaint.strokeWidth=1.3f; strokePaint.shader=null; c.drawPath(path,strokePaint) }

/**
 * A kraft name tag, like the one in the app icon. [anchorX] is the end with the hole; with [holeRight] the tag
 * hangs to the left of it. Long names are shortened to fit [maxWidth].
 */
fun drawTag(c: Canvas, name: String, anchorX: Float, top: Float, angle: Float, hand: Typeface, big: Boolean=false, holeRight: Boolean=false) {
    val h=if(big) 36f else 26f; val size=if(big) 25f else 17f; val notch=if(big) 13f else 10f
    val holePad=if(big) 22f else 17f; val endPad=if(big) 16f else 11f; val maxWidth=if(big) 250f else 170f
    textPaint.reset(); textPaint.isAntiAlias=true; textPaint.typeface=hand; textPaint.textSize=size; textPaint.color=Ink.INK
    val text=TextUtils.ellipsize(name,textPaint,maxWidth-holePad-endPad,TextUtils.TruncateAt.END).toString()
    val w=textPaint.measureText(text)+holePad+endPad
    c.save(); c.translate(anchorX,top); c.rotate(angle)
    if(holeRight) c.translate(-w,0f)
    val shape=Path().apply {
        if(holeRight) { moveTo(0f,0f); lineTo(w-notch,0f); lineTo(w,h/2); lineTo(w-notch,h); lineTo(0f,h) }
        else { moveTo(notch,0f); lineTo(w,0f); lineTo(w,h); lineTo(notch,h); lineTo(0f,h/2) }
        close()
    }
    fillPaint.shader=null; fillPaint.color=Ink.alpha(Ink.INK,.22f); c.save(); c.translate(0f,1.2f); c.drawPath(shape,fillPaint); c.restore()
    fillPaint.color=Ink.KRAFT; c.drawPath(shape,fillPaint)
    val holeX=if(holeRight) w-(if(big) 12.5f else 9.5f) else (if(big) 12.5f else 9.5f); val r=if(big) 3.5f else 2.5f
    fillPaint.color=Color.argb(230,255,255,255); c.drawCircle(holeX,h/2,r,fillPaint)
    strokePaint.color=Ink.alpha(Ink.INK,.35f); strokePaint.strokeWidth=1f; c.drawCircle(holeX,h/2,r,strokePaint)
    c.drawText(text,if(holeRight) endPad else holePad,h/2-(textPaint.descent()+textPaint.ascent())/2,textPaint)
    c.restore()
}

/** A few words in blue pen, wrapped to [width]; on cork they're written on a paper slip so they stay readable. */
class PenNote(text: String, hand: Typeface, val width: Float, private val alignRight: Boolean, private val slip: Boolean, size: Float=17f) {
    private val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface=hand; textSize=size; color=Pen.BLUE }
    private val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,width.roundToInt().coerceAtLeast(1))
        .setAlignment(if(alignRight) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f,1.02f).setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).build()
    val height get()=layout.height.toFloat()
    fun draw(c: Canvas, left: Float, top: Float, angle: Float) {
        c.save(); c.translate(left,top); c.rotate(angle,width/2,height/2)
        if(slip) {
            var l=Float.MAX_VALUE; var r=0f
            for(i in 0 until layout.lineCount) { l=min(l,layout.getLineLeft(i)); r=max(r,layout.getLineRight(i)) }
            fillPaint.shader=null; fillPaint.color=Color.argb(76,0,0,0); c.drawRect(l-9f,-5f+2f,r+9f,height+5f+2f,fillPaint)
            fillPaint.color=Color.rgb(255,253,246); c.drawRect(l-9f,-5f,r+9f,height+5f,fillPaint)
        }
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

/** A book's name cut letter by letter from different papers, like a ransom note. Laid out once, in page units. */
class CutLetters(text: String, size: Float, seed: Int, private val maxWidth: Float) {
    private class Letter(val ch: String, val look: Int, val size: Float, val px: Float, val py: Float, val angle: Float, val dy: Float) { var x=0f; var y=0f; var w=0f; var h=0f }
    private val letters=ArrayList<Letter>()
    var width=0f; private set
    var height=0f; private set
    init {
        val r=Rng(seed)
        val words=text.uppercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { word ->
            word.map { ch -> Letter(ch.toString(),(r.next()*LOOKS.size).toInt().coerceAtMost(LOOKS.size-1),size*(.85f+r.next()*.32f),3f+r.next()*3f,2f+r.next()*3f,(r.next()-.5f)*14f,(r.next()-.5f)*5f) }
        }
        val rows=ArrayList<MutableList<Letter>>(); var row=ArrayList<Letter>(); var x=0f
        for(word in words) {
            word.forEach { l -> paintFor(l); l.w=textPaint.measureText(l.ch)+2*l.px; l.h=l.size+2*l.py }
            val ww=word.sumOf { it.w.toDouble() }.toFloat()+2f*(word.size-1)
            if(x>0f && x+ww>maxWidth) { rows+=row; row=ArrayList(); x=0f }
            word.forEach { l -> l.x=x; x+=l.w+2f; row+=l }
            x+=9f
        }
        if(row.isNotEmpty()) rows+=row
        var y=0f
        for(line in rows) {
            val rh=line.maxOf { it.h }
            line.forEach { it.y=y+rh-it.h; letters+=it }
            width=max(width,line.last().x+line.last().w); y+=rh+3f
        }
        height=max(0f,y-3f)
    }
    private fun paintFor(l: Letter): TextPaint=textPaint.apply { reset(); isAntiAlias=true; typeface=LOOKS[l.look].face; textSize=l.size; color=LOOKS[l.look].fg }
    fun draw(c: Canvas, left: Float, top: Float) {
        for(l in letters) {
            val look=LOOKS[l.look]
            c.save(); c.translate(left+l.x+l.w/2,top+l.y+l.h/2+l.dy); c.rotate(l.angle)
            fillPaint.shader=null; fillPaint.color=Ink.alpha(Ink.INK,.22f); c.drawRect(-l.w/2,-l.h/2+1.5f,l.w/2,l.h/2+1.8f,fillPaint)
            fillPaint.color=look.bg; c.drawRect(-l.w/2,-l.h/2,l.w/2,l.h/2,fillPaint)
            val p=paintFor(l); c.drawText(l.ch,-l.w/2+l.px,-(p.descent()+p.ascent())/2,p)
            c.restore()
        }
    }
    private class Look(val bg: Int, val fg: Int, val face: Typeface)
    companion object {
        private val LOOKS by lazy { listOf(
            Look(Color.WHITE,Ink.INK,Typeface.create(Typeface.SERIF,Typeface.BOLD)),
            Look(Ink.INK,Color.WHITE,Typeface.create("sans-serif-black",Typeface.NORMAL)),
            Look(Ink.ACCENT,Color.WHITE,Typeface.create(Typeface.MONOSPACE,Typeface.BOLD)),
            Look(Ink.SUN,Ink.INK,Typeface.create("sans-serif-condensed",Typeface.BOLD)),
            Look(Color.rgb(233,211,175),Color.rgb(58,42,20),Typeface.create(Typeface.SERIF,Typeface.NORMAL)),
            Look(Ink.SKY,Color.rgb(35,64,138),Typeface.create("serif-monospace",Typeface.BOLD)),
        ) }
    }
}

/** A dashed place waiting to be filled, with a line of handwriting in it. */
fun drawSlot(c: Canvas, rect: RectF, text: String, hand: Typeface, onDark: Boolean) {
    strokePaint.shader=null; strokePaint.color=if(onDark) Color.argb(128,255,255,255) else Ink.alpha(Ink.INK,.25f); strokePaint.strokeWidth=2f
    strokePaint.pathEffect=DashPathEffect(floatArrayOf(6f,5f),0f); c.drawRoundRect(rect,22f,22f,strokePaint); strokePaint.pathEffect=null
    textPaint.reset(); textPaint.isAntiAlias=true; textPaint.typeface=hand; textPaint.textSize=17f; textPaint.color=if(onDark) Color.WHITE else Ink.INK_3; textPaint.textAlign=Paint.Align.CENTER
    val lines=text.split("\n"); val lh=19.5f; var y=rect.centerY()-lh*(lines.size-1)/2-(textPaint.descent()+textPaint.ascent())/2
    lines.forEach { c.drawText(it,rect.centerX(),y,textPaint); y+=lh }
    textPaint.textAlign=Paint.Align.LEFT
}

/** Thin stacked page edges along one side, one line for each page still to come (or already turned). */
fun drawPageEdges(c: Canvas, x: Float, top: Float, bottom: Float, pages: Int, right: Boolean) {
    if(pages<=0) return
    val w=pages*3f+1f; val left=if(right) x-w else x
    fillPaint.shader=null; fillPaint.color=Color.argb(217,255,255,255); c.drawRect(left,top,left+w,bottom,fillPaint)
    fillPaint.color=Ink.alpha(Ink.INK,.2f)
    for(i in 0..pages) { val lx=left+i*3f; c.drawRect(lx,top,lx+1f,bottom,fillPaint) }
}

/** An uneven torn edge around a [w]×[h] print. */
fun tornPath(w: Float, h: Float, seed: Int): Path {
    val r=Rng(seed); val j={ r.next()*3.4f }; val p=Path()
    p.moveTo(0f,j()); var x=5f
    while(x<=w) { p.lineTo(x,j()); x+=5f }
    var y=5f; while(y<=h) { p.lineTo(w-j(),y); y+=5f }
    x=w-5f; while(x>=0f) { p.lineTo(x,h-j()); x-=5f }
    y=h-5f; while(y>=0f) { p.lineTo(j(),y); y-=5f }
    p.close(); return p
}
/** Shapes a whole photo can be cut into in the calendar, in a unit box. */
fun cutShape(kind: Int, size: Float): Path=when(Math.floorMod(kind,3)) {
    0 -> svgPath("M16 29 C 6 21 0 15 0 8.5 C 0 3.5 4 0 8.5 0 C 12 0 14.5 2 16 4.5 C 17.5 2 20 0 23.5 0 C 28 0 32 3.5 32 8.5 C 32 15 26 21 16 29 Z").apply { transform(Matrix().apply { setScale(size/32f,size/30f) }) }
    1 -> svgPath("M20 1 C27 1 31 5 34 9 C39 14 40 21 37 28 C34 35 27 39 20 39 C12 39 5 35 2 28 C0 21 1 13 6 8 C10 3 14 1 20 1 Z").apply { transform(Matrix().apply { setScale(size/40f,size/40f) }) }
    else -> Path().apply { addCircle(size/2,size/2,size/2,Path.Direction.CW) }
}

private val months=arrayOf("JAN","FEB","MAR","APR","MAY","JUN","JUL","AUG","SEP","OCT","NOV","DEC")
fun stampDate(time: Long): String {
    val c=java.util.Calendar.getInstance().apply { timeInMillis=time }
    return "${c.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2,'0')} ${months[c.get(java.util.Calendar.MONTH)]} ${c.get(java.util.Calendar.YEAR)}"
}
