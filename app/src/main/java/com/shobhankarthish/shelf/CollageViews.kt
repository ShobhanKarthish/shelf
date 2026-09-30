package com.shobhankarthish.shelf

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import kotlin.math.*

fun pill(color: Int, radius: Float, stroke: Int=0, strokeWidth: Float=0f)=GradientDrawable().apply {
    setColor(color); cornerRadius=radius; if(strokeWidth>0f) setStroke(strokeWidth.roundToInt(),stroke)
}
fun ripple(radius: Float)=RippleDrawable(ColorStateList.valueOf(Ink.alpha(Ink.INK,.08f)),null,pill(Color.WHITE,radius))

/** A small interface mark drawn in ink, for icons inside buttons. */
class DoodleDrawable(private val doodle: Doodle, color: Int, strokeDp: Float, private val size: Int) : Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=strokeDp*density; this.color=color; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    private var path=Path()
    init { setBounds(0,0,size,size) }
    override fun onBoundsChange(b: Rect) { path=doodlePath(doodle,RectF(b)) }
    override fun draw(canvas: Canvas) { canvas.drawPath(path,paint) }
    override fun getIntrinsicWidth()=size
    override fun getIntrinsicHeight()=size
    override fun setAlpha(alpha: Int) { paint.alpha=alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter=filter }
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** The one button that moves you forward on each screen: a red pill on its shadow, easy to find and easy to hit. */
@SuppressLint("AppCompatCustomView") // Native Activity/theme; no AppCompat tinting is used.
class PrimaryButton(context: Context, label: String, icon: Doodle?=null) : Button(context) {
    init {
        val d=resources.displayMetrics.density; val lift=4*d
        text=label; isAllCaps=false; typeface=Fonts.of(context).display; setTextSize(TypedValue.COMPLEX_UNIT_SP,17f); letterSpacing=.02f
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),intArrayOf(Ink.alpha(Color.WHITE,.85f),Color.WHITE)))
        setShadowLayer(.01f,1.5f*d,1.5f*d,Ink.INK)
        background=PopDrawable(Ink.ACCENT,30*d,2.5f*d,lift); pressDown(lift)
        gravity=Gravity.CENTER; minWidth=0; minimumWidth=0; minHeight=(60*d).roundToInt(); minimumHeight=minHeight
        setPadding((28*d).roundToInt(),0,(28*d+lift).roundToInt(),lift.roundToInt())
        if(icon!=null) { setCompoundDrawablesRelative(DoodleDrawable(icon,Color.WHITE,2.2f,(20*d).roundToInt()),null,null,null); compoundDrawablePadding=(8*d).roundToInt() }
    }
}

/** Other choices: plain underlined words, still a comfortable 48dp to tap. */
@SuppressLint("AppCompatCustomView")
class QuietButton(context: Context, label: String, icon: Doodle?=null, color: Int=Ink.INK) : Button(context) {
    init {
        val d=resources.displayMetrics.density
        text=label; isAllCaps=false; typeface=Fonts.of(context).medium; setTextSize(TypedValue.COMPLEX_UNIT_SP,15f); setTextColor(color)
        paintFlags=paintFlags or Paint.UNDERLINE_TEXT_FLAG
        background=ripple(24*d); stateListAnimator=null; gravity=Gravity.CENTER
        minWidth=0; minimumWidth=0; minHeight=(48*d).roundToInt(); minimumHeight=minHeight; setPadding((16*d).roundToInt(),0,(16*d).roundToInt(),0)
        if(icon!=null) { setCompoundDrawablesRelative(DoodleDrawable(icon,Ink.INK,2f,(22*d).roundToInt()),null,null,null); compoundDrawablePadding=(6*d).roundToInt() }
    }
}

/** An icon-only button, such as close. */
@SuppressLint("AppCompatCustomView")
class IconButton(context: Context, doodle: Doodle, label: String) : Button(context) {
    private val icon=DoodleDrawable(doodle,Ink.INK,2f,(24*resources.displayMetrics.density).roundToInt())
    init { text=""; contentDescription=label; background=ripple(24*resources.displayMetrics.density); stateListAnimator=null; minWidth=0; minimumWidth=0; minHeight=0; minimumHeight=0; setPadding(0,0,0,0) }
    override fun onDraw(canvas: Canvas) {
        canvas.save(); canvas.translate((width-icon.intrinsicWidth)/2f,(height-icon.intrinsicHeight)/2f); icon.draw(canvas); canvas.restore()
    }
}

/** A sticker or print, outlined in ink on its shadow. [bottom] stands it on its lower edge instead of centring it; a new bitmap fades in. [taped] tapes a print. */
class StickerView(context: Context, private val angle: Float=0f, private val bottom: Boolean=true, private val taped: Boolean=false) : View(context) {
    private val d=resources.displayMetrics.density
    private var bitmap: Bitmap?=null
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun show(b: Bitmap?, fade: Boolean) {
        bitmap=b; invalidate()
        if(b!=null && fade && ValueAnimator.areAnimatorsEnabled()) { alpha=0f; animate().alpha(1f).setDuration(160).start() }
    }
    override fun onDraw(canvas: Canvas) {
        val b=bitmap ?: return
        val m=8*d; val s=min((width-2*m)/b.width,(height-2*m)/b.height)
        val w=b.width*s; val h=b.height*s; val left=(width-w)/2; val top=if(bottom) height-h-m else (height-h)/2
        val dst=RectF(left,top,left+w,top+h)
        canvas.save(); canvas.rotate(angle,dst.centerX(),if(bottom) dst.bottom else dst.centerY())
        drawCutout(canvas,b,dst,2*d,4*d)
        if(taped) { canvas.translate(dst.left,dst.top); canvas.scale(d,d); drawFastener(canvas,dst.width()/d,11) }
        canvas.restore()
    }
}

/** A small card holding one object found in the photo. */
@SuppressLint("AppCompatCustomView")
class ChipButton(context: Context, number: Int, private val thumb: Bitmap) : Button(context) {
    private val d=resources.displayMetrics.density
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val image=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE; strokeWidth=2.5f*d; color=Pen.BLUE }
    init { text=""; contentDescription="Select object $number"; background=null; stateListAnimator=null; minWidth=0; minimumWidth=0; minHeight=0; minimumHeight=0; setPadding(0,0,0,0) }
    override fun setSelected(selected: Boolean) { super.setSelected(selected); invalidate() }
    override fun onDraw(canvas: Canvas) {
        val cx=width/2f; val cy=height/2f; val half=28*d; val card=RectF(cx-half,cy-half,cx+half,cy+half)
        fill.color=Ink.alpha(Ink.INK,.08f); canvas.drawRoundRect(RectF(card).apply { offset(0f,2*d) },14*d,14*d,fill)
        fill.color=Ink.SURFACE; canvas.drawRoundRect(card,14*d,14*d,fill)
        val s=min(42*d/thumb.width,42*d/thumb.height); val w=thumb.width*s; val h=thumb.height*s
        canvas.drawBitmap(thumb,null,RectF(cx-w/2,cy-h/2,cx+w/2,cy+h/2),image)
        if(isSelected) canvas.drawRoundRect(RectF(card).apply { inset(-3*d,-3*d) },16*d,16*d,ring)
    }
}

/** A hairline that fills as work finishes. */
class ProgressLine(context: Context) : View(context) {
    private val d=resources.displayMetrics.density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    var progress=0f
        set(value) { field=value.coerceIn(0f,1f); invalidate() }
    override fun onDraw(canvas: Canvas) {
        val y=height/2f; val h=1.5f*d
        paint.color=Ink.LINE; canvas.drawRoundRect(0f,y-h,width.toFloat(),y+h,h,h,paint)
        paint.color=Ink.INK; canvas.drawRoundRect(0f,y-h,width*progress,y+h,h,h,paint)
    }
}
