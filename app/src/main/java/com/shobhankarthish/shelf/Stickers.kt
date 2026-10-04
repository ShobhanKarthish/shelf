package com.shobhankarthish.shelf

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.*

/** A sticker bitmap, and where its top-left corner sits in the source image's coordinates. */
class Sticker(val bitmap: Bitmap, val left: Float, val top: Float)

/**
 * Traces a loose scissor-cut line around the opaque pixels of [src], about [pad] pixels out.
 * Returns x,y pairs in [src] coordinates, or null when nothing is opaque.
 */
fun traceOutline(src: Bitmap, pad: Float, seed: Int, rays: Int=72): FloatArray? {
    val s=min(1f,256f/max(src.width,src.height))
    val small=if(s<1f) Bitmap.createScaledBitmap(src,max(1,(src.width*s).roundToInt()),max(1,(src.height*s).roundToInt()),true) else src
    val w=small.width; val h=small.height; val pixels=IntArray(w*h)
    small.getPixels(pixels,0,w,0,0,w,h)
    if(small!==src) small.recycle()
    var sx=0.0; var sy=0.0; var n=0
    for(y in 0 until h) for(x in 0 until w) if(pixels[y*w+x] ushr 24>128) { sx+=x; sy+=y; n++ }
    if(n==0) return null
    val cx=(sx/n).toFloat(); val cy=(sy/n).toFloat(); val reach=hypot(w.toFloat(),h.toFloat())
    // The farthest opaque pixel along each ray, so the cut never bites into the object.
    val radius=FloatArray(rays)
    for(i in 0 until rays) {
        val a=i*2*PI/rays; val ca=cos(a).toFloat(); val sa=sin(a).toFloat(); var t=0f
        while(t<reach) {
            val x=(cx+ca*t).roundToInt(); val y=(cy+sa*t).roundToInt()
            if(x<0 || y<0 || x>=w || y>=h) break
            if(pixels[y*w+x] ushr 24>90) radius[i]=t
            t+=1f
        }
    }
    val smooth=FloatArray(rays) { i -> max(radius[i],(radius[(i+rays-1)%rays]+radius[(i+1)%rays])/2*.97f) }
    val r=Rng(seed); val out=FloatArray(rays); var k=0
    for(i in 0 until rays step 2) { // Every other ray: fewer, straighter cuts.
        val a=i*2*PI/rays; val reachOut=max(smooth[i],smooth[(i+1)%rays])/s+pad+(r.next()-.5f)*pad*.24f
        out[k++]=(cx/s+cos(a)*reachOut).toFloat(); out[k++]=(cy/s+sin(a)*reachOut).toFloat()
    }
    return out
}

fun outlinePath(outline: FloatArray)=Path().apply {
    moveTo(outline[0],outline[1]); var i=2
    while(i<outline.size) { lineTo(outline[i],outline[i+1]); i+=2 }
    close()
}

/** White paper cut along [outline], with [src] stuck on top. */
fun paintSticker(src: Bitmap, outline: FloatArray): Sticker {
    var l=Float.MAX_VALUE; var t=Float.MAX_VALUE; var r=-Float.MAX_VALUE; var b=-Float.MAX_VALUE
    for(i in outline.indices step 2) { l=min(l,outline[i]); r=max(r,outline[i]); t=min(t,outline[i+1]); b=max(b,outline[i+1]) }
    l=floor(l)-2; t=floor(t)-2
    val out=Bitmap.createBitmap(max(1,(ceil(r)+2-l).toInt()),max(1,(ceil(b)+2-t).toInt()),Bitmap.Config.ARGB_8888)
    val canvas=Canvas(out).apply { translate(-l,-t) }
    val path=outlinePath(outline); val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    paint.color=Color.rgb(255,254,250); canvas.drawPath(path,paint)
    paint.style=Paint.Style.STROKE; paint.strokeWidth=max(1f,out.width/300f); paint.color=Ink.alpha(Ink.INK,.1f); canvas.drawPath(path,paint)
    paint.style=Paint.Style.FILL; paint.color=Color.BLACK; canvas.drawBitmap(src,0f,0f,paint)
    return Sticker(out,l,t)
}

fun makeSticker(cutout: Bitmap, seed: Int): Bitmap? {
    val pad=max(5f,max(cutout.width,cutout.height)*.045f)
    return traceOutline(cutout,pad,seed)?.let { paintSticker(cutout,it).bitmap }
}

/** A print with a plain white border. */
fun polaroid(photo: Bitmap): Bitmap {
    // Borders follow the short side, so a panorama doesn't get a deep bottom margin.
    val m=min(photo.width,photo.height).toFloat(); val side=m*.05f; val bottom=m*.16f
    val w=(photo.width+2*side).roundToInt(); val h=(photo.height+side+bottom).roundToInt()
    val out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(out); val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    paint.color=Color.WHITE; canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint)
    canvas.drawBitmap(photo,side,side,paint)
    paint.style=Paint.Style.STROKE; paint.strokeWidth=max(1f,w/320f); paint.color=Ink.alpha(Ink.INK,.08f); canvas.drawRect(0f,0f,w.toFloat(),h.toFloat(),paint)
    return out
}

fun Bitmap.fit(maxSide: Int): Bitmap {
    val k=maxSide.toFloat()/max(width,height)
    return if(k>=1f) this else Bitmap.createScaledBitmap(this,max(1,(width*k).roundToInt()),max(1,(height*k).roundToInt()),true)
}

fun decodeSampled(path: String, maxSide: Int): Bitmap? {
    val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
    BitmapFactory.decodeFile(path,bounds)
    if(bounds.outWidth<=0) return null
    var sample=1
    while(max(bounds.outWidth,bounds.outHeight)/(sample*2)>=maxSide) sample*=2
    return BitmapFactory.decodeFile(path,BitmapFactory.Options().apply { inSampleSize=sample })?.fit(maxSide)
}

/**
 * Stickers and framed prints, made off the main thread. Each is cut once at a generous size and kept
 * in the cache folder, so the shelf opens without redoing the work; smaller copies stay in memory.
 */
object Stickers {
    private const val MASTER=720
    private val memory=object : LruCache<String,Bitmap>((Runtime.getRuntime().maxMemory()/12).toInt()) {
        override fun sizeOf(key: String, value: Bitmap)=value.byteCount
    }
    private val pool=Executors.newFixedThreadPool(2)
    private val main=Handler(Looper.getMainLooper())

    fun cached(item: Keepsake, size: Int): Bitmap?=memory.get("${item.id}@$size")
    fun load(context: Context, store: ShelfStore, item: Keepsake, size: Int, done: (Bitmap?) -> Unit) {
        cached(item,size)?.let { done(it); return }
        val app=context.applicationContext
        pool.execute { val b=try { make(app,store,item,size) } catch(e: Exception) { null }; main.post { done(b) } }
    }
    /** Cuts a newly saved object ahead of time, so it appears on the shelf straight away. */
    fun prepare(context: Context, store: ShelfStore, item: Keepsake) { val app=context.applicationContext; pool.execute { try { master(app,store,item) } catch(e: Exception) {} } }
    /** Forgets a deleted thing's images, in memory and on disk. */
    fun forget(context: Context, id: String) {
        memory.snapshot().keys.filter { it.startsWith("$id@") }.forEach { memory.remove(it) }
        File(File(context.cacheDir,"stickers-v2"),"$id.png").delete()
    }
    /** Work that belongs on a background thread but not on the photo worker. */
    fun background(work: () -> Unit) = pool.execute { try { work() } catch(e: Exception) {} }

    private fun make(context: Context, store: ShelfStore, item: Keepsake, size: Int): Bitmap? {
        val file=master(context,store,item) ?: return null
        // A cut that can't be read (say, from a write the app was killed during) is made again rather than left blank.
        val b=decodeSampled(file.path,size) ?: run { file.delete(); master(context,store,item)?.let { decodeSampled(it.path,size) } }
        return b?.also { memory.put("${item.id}@$size",it) }
    }
    @Synchronized // One cut per object, even when a load and a prepare race.
    private fun master(context: Context, store: ShelfStore, item: Keepsake): File? {
        val file=File(File(context.cacheDir,"stickers-v2").apply { mkdirs() },"${item.id}.png")
        if(file.length()>0) return file
        val src=decodeSampled(store.image(item.id).path,MASTER) ?: return null
        val art=if(item.framed) polaroid(src) else makeSticker(src,seedOf(item.id)) ?: src
        val temp=File(file.path+".tmp")
        temp.outputStream().use { check(art.compress(Bitmap.CompressFormat.PNG,100,it)); it.fd.sync() }
        check(temp.renameTo(file))
        return file
    }
}
