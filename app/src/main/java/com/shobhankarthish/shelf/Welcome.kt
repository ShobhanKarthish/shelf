package com.shobhankarthish.shelf

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.*

/**
 * The first screen's backdrop: hand-drawn keepsakes drifting on the paper while Shelf gets ready.
 * [gather] lines five of them up on a pencil shelf and lets the rest float away.
 */
class WelcomeField(context: Context) : View(context) {
    private val d=resources.displayMetrics.density
    private val moving=ValueAnimator.areAnimatorsEnabled()
    private val born=SystemClock.uptimeMillis()
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; color=Ink.INK; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    private val pencil=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=1.8f*d; color=Ink.INK; strokeCap=Paint.Cap.ROUND }
    private val ease=DecelerateInterpolator(2f)
    private class Piece(val trinket: Trinket, val tint: Int, val x: Float, val y: Float, val size: Float, val angle: Float, val phase: Float) {
        var target: PointF?=null; var targetAngle=0f
    }
    private val pieces: List<Piece>
    private var gatherAt=0L
    private var onGathered: (() -> Unit)?=null
    private var shelf=Path()

    init {
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        val tints=intArrayOf(Ink.SUN,Ink.BLUSH,Ink.SKY,Ink.MINT,Ink.KRAFT)
        val rows=floatArrayOf(.14f,.29f,.44f,.59f,.74f); val r=Rng(21); val list=ArrayList<Piece>()
        rows.forEachIndexed { row,y ->
            val columns=if(row%2==0) floatArrayOf(.17f,.5f,.83f) else floatArrayOf(-.02f,.33f,.67f,1.02f)
            columns.forEach { x ->
                val i=list.size
                list+=Piece(Trinket.entries[(i*4)%Trinket.entries.size],tints[i%tints.size],x+r.between(-.03f,.03f),y+r.between(-.02f,.02f),
                    if(row%2==0) 92f else 80f,r.between(-18f,18f),r.next()*6.28f)
            }
        }
        pieces=list
        // The five nearest the middle are the ones that end up on the shelf, left to right as they were.
        pieces.sortedBy { hypot(it.x-.5f,(it.y-.44f)*1.6f) }.take(5).sortedBy { it.x }.forEachIndexed { i,p ->
            p.target=PointF(.14f+i*.18f,.4f); p.targetAngle=r.between(-6f,6f)
        }
    }

    /** Lines the chosen keepsakes up on a shelf, then calls [done]. */
    fun gather(done: () -> Unit) {
        if(!moving) { done(); return }
        onGathered=done; gatherAt=SystemClock.uptimeMillis(); invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { shelf=wobblyLine(w*.05f,w*.95f,h*.4f+33*d,5,.9f*d) }

    override fun onDraw(canvas: Canvas) {
        val now=SystemClock.uptimeMillis(); val t=(now-born)/1000f
        val drift=if(moving) min(1f,max(0f,(12f-t)/2f)) else 0f // Drifts for ten seconds, then comes to rest.
        val g=if(gatherAt==0L) 0f else ease.getInterpolation(min(1f,(now-gatherAt)/750f))
        for(p in pieces) {
            val bob=sin(t*1.2f+p.phase)*6*d*drift; val sway=sin(t*.9f+p.phase)*3*drift
            var x=p.x*width; var y=p.y*height+bob; var size=p.size; var angle=p.angle+sway; var alpha=1f
            val target=p.target
            if(target!=null) { x+=(target.x*width-x)*g; y+=(target.y*height-y)*g; size+=(58f-size)*g; angle+=(p.targetAngle-angle)*g }
            else if(g>0f) { alpha=1-g; x+=(x-width/2f)*.25f*g; y-=40*d*g; size*=1-.3f*g }
            if(alpha<=0f) continue
            draw(canvas,p,x,y,size,angle,alpha)
        }
        if(g>0f) { pencil.alpha=(g*190).roundToInt(); canvas.drawPath(shelf,pencil) }
        if(g>=1f) { onGathered?.invoke(); onGathered=null }
        else if(drift>0f || gatherAt!=0L) postInvalidateOnAnimation()
    }
    private fun draw(canvas: Canvas, p: Piece, x: Float, y: Float, size: Float, angle: Float, alpha: Float) {
        val s=size*d/48f
        canvas.save(); canvas.translate(x,y); canvas.rotate(angle); canvas.scale(s,s); canvas.translate(-24f,-24f)
        // Colour sits a little off the line, like a cut-paper fill under an ink drawing.
        fill.color=p.tint; fill.alpha=(alpha*255).roundToInt()
        canvas.save(); canvas.translate(2.5f,2.5f); canvas.drawPath(p.trinket.fill,fill); canvas.restore()
        ink.strokeWidth=2*d/s; ink.alpha=(alpha*255).roundToInt(); canvas.drawPath(p.trinket.lines,ink)
        canvas.restore()
    }
}
