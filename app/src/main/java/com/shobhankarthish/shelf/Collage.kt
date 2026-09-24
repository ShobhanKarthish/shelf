package com.shobhankarthish.shelf

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.*
import kotlin.math.*

/** Warm paper, near-black ink and one red for stamps and actions. Colour otherwise comes from the things people keep. */
object Ink {
    val PAPER=Color.rgb(246,244,238); val SURFACE=Color.WHITE
    val INK=Color.rgb(28,30,40); val INK_2=Color.rgb(92,96,110); val INK_3=Color.rgb(128,131,142)
    val LINE=Color.rgb(222,219,210)
    val ACCENT=Color.rgb(222,82,48); val ACCENT_DARK=Color.rgb(186,60,34)
    val SUN=Color.rgb(255,224,138); val BLUSH=Color.rgb(249,204,214); val SKY=Color.rgb(203,225,242); val MINT=Color.rgb(199,231,212); val KRAFT=Color.rgb(233,211,175)
    fun alpha(color: Int, amount: Float)=Color.argb((amount*255).roundToInt(),Color.red(color),Color.green(color),Color.blue(color))
}

/** One handwritten face for titles; everything people need to read is set in the system sans. */
class Fonts private constructor(assets: AssetManager) {
    val hand: Typeface=Typeface.createFromAsset(assets,"patrick.ttf")
    val sans: Typeface=Typeface.SANS_SERIF
    val medium: Typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    val mono: Typeface=Typeface.MONOSPACE
    companion object {
        @Volatile private var loaded: Fonts?=null
        fun of(context: Context)=loaded ?: synchronized(this) { loaded ?: Fonts(context.applicationContext.assets).also { loaded=it } }
    }
}

val density: Float get()=Resources.getSystem().displayMetrics.density

/** Seeded randomness, so every sticker keeps the same cut and tilt between launches. */
class Rng(seed: Int) {
    private var a=seed
    fun next(): Float {
        a+=0x6D2B79F5; var t=a
        t=(t xor (t ushr 15))*(t or 1); t=t xor (t+(t xor (t ushr 7))*(t or 61))
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL)/4294967296f
    }
    fun between(low: Float, high: Float)=low+(high-low)*next()
}
fun seedOf(value: String): Int { var h=-2128831035; for(c in value) { h=h xor c.code; h*=16777619 }; return h }

/** A pencil line from [x0] to [x1]: straight enough to read as a shelf, loose enough to be drawn by hand. */
fun wobblyLine(x0: Float, x1: Float, y: Float, seed: Int, amplitude: Float): Path {
    val r=Rng(seed); val path=Path(); val steps=max(2,((x1-x0)/(60*density)).roundToInt())
    path.moveTo(x0,y+r.between(-amplitude,amplitude))
    for(i in 1..steps) {
        val x=x0+(x1-x0)*i/steps; val mid=x-(x1-x0)/steps/2
        path.quadTo(mid,y+r.between(-amplitude,amplitude)*1.6f,x,y+r.between(-amplitude,amplitude))
    }
    return path
}
/** A loosely drawn rounded box, for empty places waiting to be filled. */
fun sketchBox(rect: RectF, radius: Float, seed: Int): Path {
    val r=Rng(seed); val j={ r.between(-.8f,.8f)*density }
    return Path().apply { addRoundRect(RectF(rect.left+j(),rect.top+j(),rect.right+j(),rect.bottom+j()),radius,radius,Path.Direction.CW) }
}

/** Interface marks, as SVG path data in 24×24 boxes. */
enum class Doodle(private val data: String) {
    X("M6 6 C 10 10, 14 14, 18 18 M18 6 C 14 10, 10 14, 6 18"),
    BACK("M20 12 C 15 12, 10 12, 5 12 M11 6 C 9 8, 7 10, 5 12 C 7 14, 9 16, 11 18"),
    PLUS("M12 5 L12 19 M5 12 L19 12"),
    CHECK("M5 12.5 L10 17 L19 7"),
    PREV("M14.5 5 L7.5 12 L14.5 19"),
    NEXT("M9.5 5 L16.5 12 L9.5 19"),
    BOOK("M3 5.5 C6 4.5 9 4.5 12 6 C15 4.5 18 4.5 21 5.5 L21 19 C18 18 15 18 12 19.5 C9 18 6 18 3 19 Z M12 6 L12 19.5"),
    CALENDAR("M5.5 5 L18.5 5 C19.6 5 20.5 5.9 20.5 7 L20.5 18 C20.5 19.1 19.6 20 18.5 20 L5.5 20 C4.4 20 3.5 19.1 3.5 18 L3.5 7 C3.5 5.9 4.4 5 5.5 5 Z M3.5 10 L20.5 10 M8 3 L8 7 M16 3 L16 7"),
    MORE("M5 12 L5.1 12 M12 12 L12.1 12 M19 12 L19.1 12"),
    PENCIL("M5 19 L9 18 L19 8 L16 5 L6 15 Z M14 7 L17 10"),
    ROTATE("M19 12 C19 15.9 15.9 19 12 19 C8.1 19 5 15.9 5 12 C5 8.1 8.1 5 12 5 C13.9 5 15.6 5.7 16.9 7 M17.5 2.5 L17 7 L12.5 6.5"),
    NEW_BOOK("M4 5 C7 4 10 4.5 12 6 L12 19.5 C10 18 7 17.5 4 18.5 Z M12 6 C14 4.5 17 4 20 5 L20 11 M17 15 L17 21 M14 18 L20 18"),
    RENAME("M4 19 L8 18 L18 8 L15 5 L5 15 Z M13 7 L16 10 M12 19 L20 19"),
    TRASH("M5 7 L19 7 M10 7 L10 4.5 L14 4.5 L14 7 M7 7 L8 20 L16 20 L17 7");
    val path: Path by lazy { svgPath(data) }
}

/** Line drawings of everyday keepsakes for the welcome screen: drawn, not photographed, so nothing pretends to be yours. */
enum class Trinket(private val shape: String, private val detail: String) {
    TICKET("M5 15 L43 15 L43 21 C40 21 40 27 43 27 L43 33 L5 33 L5 27 C8 27 8 21 5 21 Z","M33 17 L33 31 M10 21 L27 21 M10 25 L24 25 M10 29 L19 29"),
    SHELL("M24 41 L8 20 C12 8 36 8 40 20 Z","M24 41 L15 12 M24 41 L24 10 M24 41 L33 12 M24 41 L10 17 M24 41 L38 17 M19 41 L29 41 L27 45 L21 45 Z"),
    KEY("M14 18 C14 12 19 8 24 8 C29 8 34 12 34 18 C34 24 29 28 24 28 C19 28 14 24 14 18 Z","M21 18 C21 16 22.5 15 24 15 C25.5 15 27 16 27 18 C27 20 25.5 21 24 21 C22.5 21 21 20 21 18 Z M24 28 L24 44 M24 36 L29 36 M24 40 L28 40 L28 43"),
    MUG("M10 16 L34 16 L32 40 C32 42 30 43 28 43 L16 43 C14 43 12 42 12 40 Z","M34 21 C41 21 42 32 33 33 M18 12 C16 9 20 7 18 4 M26 12 C24 9 28 7 26 4"),
    LEAF("M8 40 C8 22 20 8 40 8 C40 28 28 40 8 40 Z","M8 40 L32 16 M16 32 L16 24 M22 26 L22 18 M16 32 L24 32 M22 26 L30 26"),
    PEBBLE("M10 28 C8 18 16 10 26 11 C37 12 42 20 40 29 C38 38 28 41 19 39 C13 38 11 34 10 28 Z","M20 22 L20.5 22 M28 30 L28.5 30 M31 20 L31.5 20"),
    FEATHER("M38 6 C44 16 34 32 20 36 C18 26 26 12 38 6 Z","M12 42 C20 30 28 18 38 6 M30 18 L36 16 M26 25 L33 24 M22 31 L28 31"),
    POSTCARD("M5 12 L43 12 L43 38 L5 38 Z","M26 16 L26 34 M29 24 L39 24 M29 28 L39 28 M29 32 L36 32 M35 15 L40 15 L40 20 L35 20 Z M9 30 C12 26 15 34 18 30 S 22 26 23 30"),
    RING("M12 28 C12 20 18 16 24 16 C30 16 36 20 36 28 C36 36 30 40 24 40 C18 40 12 36 12 28 Z M20 16 L24 9 L28 16 Z","M16 28 C16 23 20 20 24 20 C28 20 32 23 32 28 C32 33 28 36 24 36 C20 36 16 33 16 28 Z"),
    BUTTON("M8 24 C8 15 15 8 24 8 C33 8 40 15 40 24 C40 33 33 40 24 40 C15 40 8 33 8 24 Z","M12 24 C12 17 17 12 24 12 C31 12 36 17 36 24 C36 31 31 36 24 36 C17 36 12 31 12 24 Z M20 20 L20.5 20 M28 20 L28.5 20 M20 28 L20.5 28 M28 28 L28.5 28"),
    FLOWER("M24 24 C18 14 22 6 24 6 C26 6 30 14 24 24 C34 18 42 22 42 24 C42 26 34 30 24 24 C30 34 26 42 24 42 C22 42 18 34 24 24 C14 30 6 26 6 24 C6 22 14 18 24 24 Z","M21 24 C21 22 22.5 21 24 21 C25.5 21 27 22 27 24 C27 26 25.5 27 24 27 C22.5 27 21 26 21 24 Z");
    val fill: Path by lazy { svgPath(shape) }
    val lines: Path by lazy { svgPath("$shape $detail") }
}

/** Absolute M, L, C, S, Q and Z commands: all the drawings need. */
fun svgPath(data: String): Path {
    val path=Path(); val tokens=Regex("[MLCSQZ]|-?[0-9]*\\.?[0-9]+").findAll(data).map { it.value }.toList()
    var i=0; var cmd='M'; var x=0f; var y=0f; var cx=0f; var cy=0f
    fun n()=tokens[i++].toFloat()
    while(i<tokens.size) {
        if(tokens[i][0].isLetter()) cmd=tokens[i++][0]
        when(cmd) {
            'M' -> { x=n(); y=n(); path.moveTo(x,y); cx=x; cy=y; cmd='L' }
            'L' -> { x=n(); y=n(); path.lineTo(x,y); cx=x; cy=y }
            'C' -> { val x1=n(); val y1=n(); cx=n(); cy=n(); x=n(); y=n(); path.cubicTo(x1,y1,cx,cy,x,y) }
            'S' -> { val x1=2*x-cx; val y1=2*y-cy; cx=n(); cy=n(); x=n(); y=n(); path.cubicTo(x1,y1,cx,cy,x,y) }
            'Q' -> { cx=n(); cy=n(); x=n(); y=n(); path.quadTo(cx,cy,x,y) }
            'Z' -> path.close()
        }
    }
    return path
}
/** [doodle] scaled into [dst], keeping its proportions. */
fun doodlePath(doodle: Doodle, dst: RectF)=Path(doodle.path).apply {
    val s=min(dst.width(),dst.height())/24f
    transform(Matrix().apply { setScale(s,s); postTranslate(dst.centerX()-12*s,dst.centerY()-12*s) })
}

private val scissorFill=Paint(Paint.ANTI_ALIAS_FLAG)
private val scissorLine=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
/** Scissors centred on the pivot, pointing right; [open] is the half-angle between the blades, in degrees. */
fun drawScissors(canvas: Canvas, open: Float, size: Float) {
    canvas.save(); canvas.scale(size/60f,size/60f)
    for(side in intArrayOf(1,-1)) {
        val s=side.toFloat()
        canvas.save(); canvas.rotate(-open*s)
        val blade=Path().apply { moveTo(-2f,s); lineTo(25f,-6.5f*s); cubicTo(27.5f,-6.8f*s,28f,-4.2f*s,26f,-3f*s); lineTo(1f,3.5f*s); close() }
        scissorFill.color=Color.rgb(238,240,246); canvas.drawPath(blade,scissorFill)
        scissorLine.color=Ink.INK; scissorLine.strokeWidth=1.6f; canvas.drawPath(blade,scissorLine)
        scissorLine.color=Ink.ACCENT; scissorLine.strokeWidth=4.5f
        canvas.drawLine(-2f,s,-9f,8f*s,scissorLine); canvas.drawCircle(-14f,12.5f*s,7f,scissorLine)
        canvas.restore()
    }
    scissorFill.color=Ink.INK; canvas.drawCircle(0f,0f,2.2f,scissorFill)
    canvas.restore()
}
