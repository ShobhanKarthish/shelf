package com.shobhankarthish.shelf

import android.content.Context
import android.graphics.*
import android.view.View

/** Exact exported aura assets; timing follows the accepted reversing browser study. */
class AuraView(context: Context, step: Int) : View(context) {
    private val bitmaps=listOf("olive","field","muted").map { kind -> context.assets.open("aura_${step}_$kind.png").use { BitmapFactory.decodeStream(it) } }
    private val positions=arrayOf(
        arrayOf(floatArrayOf(-170f,140f,620f,560f),floatArrayOf(60f,210f,560f,520f),floatArrayOf(140f,80f,480f,460f)),
        arrayOf(floatArrayOf(70f,110f,600f,540f),floatArrayOf(-190f,270f,580f,520f),floatArrayOf(-50f,170f,500f,480f)),
        arrayOf(floatArrayOf(-150f,270f,600f,560f),floatArrayOf(80f,120f,560f,520f),floatArrayOf(160f,290f,480f,460f))
    )[step]
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    var drift=1f
        set(value) { field=value; invalidate() }
    override fun onDraw(canvas:Canvas) {
        canvas.save(); canvas.scale(width/412f,height/915f)
        val dx=floatArrayOf(-30f,34f,-18f); val dy=floatArrayOf(24f,-18f,-30f)
        bitmaps.forEachIndexed { i,b ->
            val p=positions[i]; val scale=.92f+.08f*drift
            val x=p[0]+dx[i]*(1-drift); val y=p[1]+dy[i]*(1-drift)
            paint.alpha=(255*(.6f+.4f*minOf(1f,drift*2))).toInt()
            canvas.drawBitmap(b,null,RectF(x,y,x+p[2]*scale,y+p[3]*scale),paint)
        }
        canvas.restore()
    }
}
