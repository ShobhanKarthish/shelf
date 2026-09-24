package com.shobhankarthish.shelf

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Shelf keeps things in books. There are two places: the desk, where the books and the calendar are,
 * and the page, inside a book. Adding something is a short flow on the paper of the book it goes into.
 */
class MainActivity : Activity(), ThingHost {
    private lateinit var fonts: Fonts
    private lateinit var frame: FrameLayout
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var store: ShelfStore
    private val worker = Executors.newSingleThreadExecutor()
    private val segmenter by lazy { SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableSubjectBitmap().build()).build()) }
    private var selectionView: SubjectSelectionView? = null
    private var page = "desk"
    private var original: Bitmap? = null
    private var cutout: Bitmap? = null
    private var framed = false
    private var generation = 0
    private var draftTitle = ""
    private var draftStory = ""
    private var draftBook: String? = null
    /** The thing the form is editing; null while adding something new. */
    private var editing: String? = null
    private var saving = false
    private var warmed = false
    private var titleField: EditText? = null
    private var storyField: EditText? = null
    private var veil: View? = null
    private var closeNote: (() -> Unit)? = null
    private var insetTop = 0
    private var insetBottom = 0
    /** Words on cork are white. */
    private var onDark = false
    /** Rebuilds a screen laid out around the system bars when their size changes. */
    private var relayout: (() -> Unit)? = null
    /** How much of the bottom of the screen the keyboard covers; zero while it's closed. */
    private var keyboard = 0
    /** The open dialog or sheet, kept clear of the keyboard. */
    private var openDialog: View? = null

    // Where you are.
    private var calendarMode = false
    private var bookIndex = 0
    private var openBook: String? = null
    private var pageIndex = 0
    override var arranging = false
    private var month = -1 // months since year zero on the calendar; -1 means this month
    private var thingId: String? = null
    private var thingFrom = "page"
    private var addFrom = "desk"
    private var bookView: BookView? = null
    private val peeked = HashSet<String>()

    private val prefs by lazy { getSharedPreferences("shelf", MODE_PRIVATE) }
    private val moving get() = ValueAnimator.areAnimatorsEnabled()
    private val density get() = resources.displayMetrics.density
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun dp(n: Float) = n * resources.displayMetrics.density
    private val loader: Loader = { item, size, done -> Stickers.cached(item, size)?.let(done) ?: Stickers.load(this, store, item, size, done) }
    /** A whole photo as it was taken, without its print border. */
    private val photoLoader: Loader = { item, size, done -> Stickers.background { val b=decodeSampled(store.image(item.id).path,size); runOnUiThread { done(b) } } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        fonts = Fonts.of(this)
        store = ShelfStore(this)
        edgeToEdge()
        // The system bars are the same size from one launch to the next, so the first screen can be laid out around them straight away.
        insetTop=prefs.getInt("insetTop",0); insetBottom=prefs.getInt("insetBottom",0)
        // Paper is drawn plain until its grain is made; then everything on screen redraws once.
        Textures.warm { if(!isDestroyed && ::frame.isInitialized) redraw(frame) }
        draftTitle = state?.getString("title") ?: ""
        draftStory = state?.getString("story") ?: ""
        draftBook = state?.getString("book")
        editing = state?.getString("editing")
        framed = state?.getBoolean("framed") ?: false
        calendarMode = state?.getBoolean("calendar") ?: false
        bookIndex = state?.getInt("bookIndex") ?: 0
        openBook = state?.getString("openBook")
        pageIndex = state?.getInt("pageIndex") ?: 0
        month = state?.getInt("month", -1) ?: -1
        thingId = state?.getString("thing")
        thingFrom = state?.getString("thingFrom") ?: "page"
        addFrom = state?.getString("addFrom") ?: "desk"
        original = BitmapFactory.decodeFile(File(cacheDir,"draft.png").path)
        cutout = if(state?.getBoolean("cutoutReady")==true) BitmapFactory.decodeFile(File(cacheDir,"cutout.png").path) else null
        page = state?.getString("page") ?: if (prefs.getBoolean("onboarded",false)) "desk" else "onboarding"
        when {
            page == "onboarding" -> onboarding()
            page == "thing" && thingId != null -> thing(thingId!!, thingFrom)
            page == "page" && openBook != null -> openPage(openBook!!, pageIndex)
            page == "form" && (original != null || editing != null) -> form()
            (page == "review" || page == "revealing") && original != null -> if (cutout != null || framed) review() else process()
            page == "processing" && original != null -> process()
            else -> desk()
        }
        // Let the first screen draw before the model starts loading; they compete for the same CPU.
        window.decorView.postDelayed({ if(!isDestroyed) warmUp() },1200)
    }
    /** Loads the cut-out model in the background, so the first photo doesn't wait for it. */
    private fun warmUp() = Stickers.background {
        try { Tasks.await(segmenter.process(InputImage.fromBitmap(Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888),0)),30,TimeUnit.SECONDS) }
        finally { runOnUiThread { warmed=true } }
    }
    private fun rememberFields() { titleField?.let { draftTitle=it.text.toString() }; storyField?.let { draftStory=it.text.toString() } }
    override fun onSaveInstanceState(out: Bundle) {
        rememberFields()
        out.putBoolean("cutoutReady",cutout!=null)
        out.putString("page",page); out.putString("title",draftTitle); out.putString("story",draftStory); out.putString("book",draftBook); out.putString("editing",editing)
        out.putBoolean("framed",framed); out.putBoolean("calendar",calendarMode); out.putInt("bookIndex",bookIndex); out.putString("openBook",openBook); out.putInt("pageIndex",pageIndex)
        out.putInt("month",month); out.putString("thing",thingId); out.putString("thingFrom",thingFrom); out.putString("addFrom",addFrom)
        super.onSaveInstanceState(out)
    }

    // ---------- the window ----------
    /** Every screen draws behind the system bars: the desk is dark and paper is light, all the way to the edges. */
    private fun edgeToEdge() {
        @Suppress("DEPRECATION") run { window.statusBarColor=Color.TRANSPARENT; window.navigationBarColor=Color.TRANSPARENT }
        if(Build.VERSION.SDK_INT>=29) window.isNavigationBarContrastEnforced=false
        if(Build.VERSION.SDK_INT>=30) window.setDecorFitsSystemWindows(false)
    }
    private fun bars(dark: Boolean) {
        if(Build.VERSION.SDK_INT>=30) {
            val light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if(dark) 0 else light,light)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if(dark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }
    }
    /** Starts a screen on the desk ([paper] null) or on a sheet of paper. */
    private fun stage(name: String, paper: Paper?) {
        selectionView?.dispose(); selectionView=null; closeNote=null; bookView=null; relayout=null; veil=null; openDialog=null
        page=name; titleField=null; storyField=null; onDark=paper==null || paper.dark
        frame=FrameLayout(this).apply {
            if(paper==null) setBackgroundColor(Pen.DESK) else background=PaperBackground(paper,density)
            // Only the bars decide a screen's layout. The keyboard coming and going must never rebuild a screen,
            // or a dialog being typed into would be thrown away with it.
            setOnApplyWindowInsetsListener { _,insets ->
                val (t,b,k)=barsAndKeyboard(insets)
                if(t!=insetTop || b!=insetBottom) {
                    insetTop=t; insetBottom=b; post { relayout?.invoke() ?: pad() }
                    prefs.edit().putInt("insetTop",t).putInt("insetBottom",b).apply()
                }
                if(k!=keyboard) { keyboard=k; post { pad(); openDialog?.setPadding(0,0,0,keyboard) } }
                insets
            }
        }
        setContentView(frame)
        bars(onDark)
    }
    /** The system bars at the top and bottom, and the keyboard's height from the bottom of the screen. */
    @Suppress("DEPRECATION")
    private fun barsAndKeyboard(insets: WindowInsets): Triple<Int,Int,Int> =
        if(Build.VERSION.SDK_INT>=30) insets.getInsets(WindowInsets.Type.systemBars()).let { Triple(it.top,it.bottom,insets.getInsets(WindowInsets.Type.ime()).bottom) }
        else Triple(insets.stableInsetTop,insets.stableInsetBottom,max(0,insets.systemWindowInsetBottom-insets.stableInsetBottom))
    /** A scrolling column of words and buttons on [paper], for adding and naming things. */
    private fun flow(name: String, paper: Paper=bookFor(draftBook).paper) {
        stage(name,paper)
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; clipChildren=false; clipToPadding=false }
        scroll=ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; clipChildren=false; addView(body) }
        frame.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        // Paper behind the status bar, so scrolled words never run under the clock.
        veil=View(this).apply { background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(paper.ground,paper.ground,Ink.alpha(paper.ground,0f))) }
        frame.addView(veil,FrameLayout.LayoutParams(-1,insetTop+dp(12)))
        pad()
        if(moving) { body.alpha=0f; body.translationY=dp(10f); body.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start() }
    }
    private fun redraw(view: View) { view.invalidate(); (view as? ViewGroup)?.let { g -> for(i in 0 until g.childCount) redraw(g.getChildAt(i)) } }
    private fun pad() {
        if(!::body.isInitialized) return
        // With the keyboard open, the column ends above it; scrolling views keep the field being typed in on screen as they shrink.
        body.setPadding(dp(20),insetTop+dp(8),dp(20),(if(keyboard>0) 0 else insetBottom)+dp(24))
        (scroll.layoutParams as? FrameLayout.LayoutParams)?.let { if(it.bottomMargin!=keyboard) { it.bottomMargin=keyboard; scroll.layoutParams=it } }
        veil?.let { it.layoutParams=it.layoutParams.apply { height=insetTop+dp(12) } }
    }
    private fun text(value: String, sp: Float, face: Typeface, color: Int=if(onDark) Color.WHITE else Ink.INK, spacing: Float=1.3f) = TextView(this).apply {
        text=value; typeface=face; setTextSize(TypedValue.COMPLEX_UNIT_SP,sp); setTextColor(color); setLineSpacing(0f,spacing)
    }
    private fun title(value: String, sp: Float=34f) = text(value,sp,fonts.hand,spacing=1f).apply { isAccessibilityHeading=true }
    private fun prose(value: String) = text(value,15.5f,fonts.sans,if(onDark) Color.WHITE else Ink.INK_2,1.35f)
    private fun label(value: String, color: Int=if(onDark) Color.WHITE else Ink.INK_2) = text(value.uppercase(),11f,fonts.mono,color,1.2f).apply { letterSpacing=.16f }
    private fun add(view: View, top: Int=0, width: Int=-1) { body.addView(view,LinearLayout.LayoutParams(width,-2).apply { topMargin=dp(top) }) }
    private fun spring() { body.addView(Space(this),LinearLayout.LayoutParams(1,0,1f)) }
    private fun count(n: Int, word: String="thing") = "$n $word${if(n==1) "" else "s"}"
    /** The main action, full width at the bottom, with any other choices as plain buttons underneath. */
    private fun actions(main: Button, vararg others: Button) = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(20),0,0)
        addView(main,LinearLayout.LayoutParams(-1,-2))
        others.forEach { addView(it,LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(4) }) }
    }
    private fun primary(label: String, action: () -> Unit) = PrimaryButton(this,label).apply { setOnClickListener { action() } }
    private fun quiet(label: String, color: Int=if(onDark) Color.WHITE else Ink.INK, action: () -> Unit) = QuietButton(this,label,color=color).apply { setOnClickListener { action() } }
    /** Close on the left; which step you're on, in the middle. */
    private fun topBar(step: String) {
        val bar=FrameLayout(this)
        bar.addView(RoundButton(this,Doodle.X,if(editing!=null) "Stop editing" else "Cancel adding").apply { setOnClickListener { leave() } },FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START or Gravity.CENTER_VERTICAL))
        bar.addView(label(step),FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        body.addView(bar,LinearLayout.LayoutParams(-1,dp(56)).apply { marginStart=-dp(6); marginEnd=-dp(6) })
    }
    private fun EditText.onEdit(block: () -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { block() }
    })
    /** Shows a sticker or framed print of [source] once it has been made on a background thread. */
    private fun artOf(source: Bitmap?, asPrint: Boolean, maxSide: Int, into: StickerView) {
        val src=source ?: return
        Stickers.background { val made=src.fit(maxSide).let { if(asPrint) polaroid(it) else makeSticker(it,11) ?: it }; runOnUiThread { into.show(made,true) } }
    }
    private fun bookFor(id: String?): Book = store.books().find { it.id==id } ?: store.firstBook()

    // ---------- controls that sit in the same places on every screen ----------
    private fun corner(button: RoundButton, start: Int?=null, end: Int?=null) {
        frame.addView(button,FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP or (if(end!=null) Gravity.END else Gravity.START)).apply {
            topMargin=insetTop+dp(10); if(start!=null) marginStart=dp(start); if(end!=null) marginEnd=dp(end)
        })
    }
    private fun topLabel(value: String) = label(value).apply {
        gravity=Gravity.CENTER; maxLines=1; ellipsize=TextUtils.TruncateAt.END
        if(onDark && page!="desk") setShadowLayer(dp(2f),0f,dp(1f),Color.argb(110,0,0,0))
        frame.addView(this,FrameLayout.LayoutParams(-1,dp(48),Gravity.TOP).apply { topMargin=insetTop+dp(10); marginStart=dp(72); marginEnd=dp(72) })
    }
    private fun addButton(label: String, action: () -> Unit) {
        frame.addView(RoundButton(this,Doodle.PLUS,label,RoundButton.Style.RED).apply { setOnClickListener { action() } },
            FrameLayout.LayoutParams(dp(64),dp(64),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=insetBottom+dp(16) })
    }
    /** Previous and next, either side of the add button: pages in a book, books on the desk, months on the calendar. */
    private fun arrows(what: String, onPrev: () -> Unit, onNext: () -> Unit): Pair<View,View> {
        val prev=RoundButton(this,Doodle.PREV,"Previous $what").apply { setOnClickListener { onPrev() } }
        val next=RoundButton(this,Doodle.NEXT,"Next $what").apply { setOnClickListener { onNext() } }
        frame.addView(prev,FrameLayout.LayoutParams(dp(54),dp(54),Gravity.BOTTOM or Gravity.START).apply { bottomMargin=insetBottom+dp(21); marginStart=dp(44) })
        frame.addView(next,FrameLayout.LayoutParams(dp(54),dp(54),Gravity.BOTTOM or Gravity.END).apply { bottomMargin=insetBottom+dp(21); marginEnd=dp(44) })
        return prev to next
    }
    private fun deskButtons() {
        corner(RoundButton(this,Doodle.BOOK,"Books",if(calendarMode) RoundButton.Style.GLASS else RoundButton.Style.WHITE).apply {
            isSelected=!calendarMode; setOnClickListener { if(calendarMode) { calendarMode=false; desk() } } },start=14)
        corner(RoundButton(this,Doodle.CALENDAR,"Calendar",if(calendarMode) RoundButton.Style.WHITE else RoundButton.Style.GLASS).apply {
            isSelected=calendarMode; setOnClickListener { if(!calendarMode) { calendarMode=true; desk() } } },start=64)
        corner(RoundButton(this,Doodle.MORE,"Book options").apply { setOnClickListener { menu() } },end=14)
    }

    // ---------- dialogs and sheets ----------
    /** Hides everything else from screen readers while a dialog or sheet is open. */
    private fun cover(overlay: View): () -> Unit {
        val others=(0 until frame.childCount).map { frame.getChildAt(it) }.filter { it!==overlay }
        others.forEach { it.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        return { others.forEach { it.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO } }
    }
    /** A white card with a question: a plain way out, and the action in red. [field] asks for a few words too. */
    private fun ask(heading: String, message: String?, confirm: String, cancel: String?=null, field: EditText?=null, onConfirm: () -> Boolean={ true }) {
        closeNote?.invoke()
        val scrim=FrameLayout(this).apply { setBackgroundColor(Color.argb(122,18,20,30)); isClickable=true; setPadding(0,0,0,keyboard) }
        frame.addView(scrim,FrameLayout.LayoutParams(-1,-1))
        val uncover=cover(scrim)
        val close={ frame.removeView(scrim); uncover(); closeNote=null; if(openDialog===scrim) openDialog=null }
        openDialog=scrim
        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=pill(Color.WHITE,dp(22f)); setPadding(dp(22),dp(22),dp(18),dp(12)); elevation=dp(10f); isClickable=true }
        card.addView(TextView(this).apply { text=heading; typeface=fonts.hand; setTextSize(TypedValue.COMPLEX_UNIT_SP,27f); setTextColor(Ink.INK); isAccessibilityHeading=true })
        if(message!=null) card.addView(TextView(this).apply { text=message; typeface=fonts.sans; setTextSize(TypedValue.COMPLEX_UNIT_SP,15f); setTextColor(Ink.INK_2); setLineSpacing(0f,1.3f) },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(6) })
        if(field!=null) card.addView(field,LinearLayout.LayoutParams(-1,dp(52)).apply { topMargin=dp(14); marginEnd=dp(4) })
        val row=LinearLayout(this).apply { gravity=Gravity.END or Gravity.CENTER_VERTICAL }
        cancel?.let { row.addView(QuietButton(this,it).apply { setOnClickListener { close() } }) }
        row.addView(PrimaryButton(this,confirm).apply { minHeight=dp(48); minimumHeight=dp(48); setPadding(dp(22),0,dp(22),0); setOnClickListener { if(onConfirm()) close() } },LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(8) })
        card.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(18) })
        scrim.addView(card,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER).apply { setMargins(dp(22),0,dp(22),0) })
        if(cancel!=null) scrim.setOnClickListener { close() }
        closeNote=close
        if(moving) { scrim.alpha=0f; scrim.animate().alpha(1f).setDuration(150).start(); card.scaleX=.94f; card.scaleY=.94f; card.animate().scaleX(1f).scaleY(1f).setDuration(180).start() }
        field?.requestFocus()
        scrim.announceForAccessibility(heading)
    }
    private fun error(message: String) = ask(message,null,"OK")
    /** Asks for a short name, such as a book's. */
    private fun askName(heading: String, message: String?, initial: String, confirm: String, onName: (String) -> Unit) {
        val field=EditText(this).apply {
            setText(initial); setSelection(initial.length); hint="Trips, Home, Grandma’s…"; typeface=fonts.sans; setTextSize(TypedValue.COMPLEX_UNIT_SP,16f); setTextColor(Ink.INK); setHintTextColor(Ink.INK_3)
            isSingleLine=true; filters=arrayOf(InputFilter.LengthFilter(40)); inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS; imeOptions=EditorInfo.IME_ACTION_DONE
            background=pill(Color.WHITE,dp(12f),Ink.LINE,dp(1.5f)); setPadding(dp(14),0,dp(14),0); contentDescription=heading
        }
        ask(heading,message,confirm,"Cancel",field) { val name=field.text.toString().trim(); if(name.isEmpty()) { field.requestFocus(); false } else { onName(name); true } }
        field.setOnEditorActionListener { _,_,_ -> val name=field.text.toString().trim(); if(name.isNotEmpty()) { closeNote?.invoke(); onName(name) }; true }
    }
    /** A white sheet from the bottom of the screen, for options. */
    private fun sheet(build: (LinearLayout, () -> Unit) -> Unit) {
        closeNote?.invoke()
        val scrim=FrameLayout(this).apply { setBackgroundColor(Color.argb(122,18,20,30)); isClickable=true; setPadding(0,0,0,keyboard) }
        frame.addView(scrim,FrameLayout.LayoutParams(-1,-1))
        val uncover=cover(scrim)
        openDialog=scrim
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; isClickable=true; elevation=dp(12f); setPadding(dp(16),dp(10),dp(16),insetBottom+dp(20))
            background=GradientDrawable().apply { setColor(Color.WHITE); cornerRadii=floatArrayOf(dp(24f),dp(24f),dp(24f),dp(24f),0f,0f,0f,0f) }
        }
        panel.addView(View(this).apply { background=pill(Ink.LINE,dp(2f)) },LinearLayout.LayoutParams(dp(40),dp(4)).apply { gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=dp(10) })
        val close={ frame.removeView(scrim); uncover(); closeNote=null; if(openDialog===scrim) openDialog=null }
        build(panel,close)
        scrim.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        scrim.setOnClickListener { close() }
        closeNote=close
        if(moving) { scrim.alpha=0f; scrim.animate().alpha(1f).setDuration(150).start(); panel.translationY=dp(300f); panel.animate().translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator(2f)).start() }
    }
    private fun sheetRow(panel: LinearLayout, icon: Doodle?, label: String, danger: Boolean=false, action: () -> Unit) {
        val color=if(danger) Ink.ACCENT_DARK else Ink.INK
        panel.addView(TextView(this).apply {
            text=label; typeface=fonts.medium; setTextSize(TypedValue.COMPLEX_UNIT_SP,16f); setTextColor(color); gravity=Gravity.CENTER_VERTICAL
            maxLines=1; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(10),0,dp(10),0); background=ripple(dp(12f)); isClickable=true; isFocusable=true
            if(icon!=null) { setCompoundDrawablesRelative(DoodleDrawable(icon,color,1.8f,dp(22)),null,null,null); compoundDrawablePadding=dp(14) }
            setOnClickListener { action() }
        },LinearLayout.LayoutParams(-1,dp(54)))
    }
    /** The five papers, as round samples; the book's own is ringed. */
    private fun paperRow(current: Paper, onPick: (Paper) -> Unit) = LinearLayout(this).apply {
        gravity=Gravity.CENTER_VERTICAL
        Paper.entries.forEach { p ->
            addView(LinearLayout(this@MainActivity).apply {
                orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL
                addView(Swatch(this@MainActivity,p).apply { isSelected=p==current; setOnClickListener { onPick(p) } },LinearLayout.LayoutParams(dp(48),dp(48)))
                addView(TextView(this@MainActivity).apply { text=p.label.uppercase(); typeface=fonts.mono; setTextSize(TypedValue.COMPLEX_UNIT_SP,9f); letterSpacing=.12f; setTextColor(Ink.INK_2); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            },LinearLayout.LayoutParams(0,-2,1f))
        }
    }
    /** A short confirmation that slides up and leaves on its own. */
    private fun snack(message: String) {
        val chip=text(message,15f,fonts.medium,Color.WHITE,1.2f).apply {
            background=pill(Ink.INK,dp(24f)); setPadding(dp(16),dp(12),dp(20),dp(12)); gravity=Gravity.CENTER_VERTICAL; elevation=dp(6f)
            setCompoundDrawablesRelative(DoodleDrawable(Doodle.CHECK,Color.WHITE,2.2f,dp(20)),null,null,null); compoundDrawablePadding=dp(10)
        }
        frame.addView(chip,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=insetBottom+dp(96) })
        frame.announceForAccessibility(message)
        if(moving) { chip.alpha=0f; chip.translationY=dp(8f); chip.animate().alpha(1f).translationY(0f).setDuration(180).start() }
        chip.postDelayed({ chip.animate().alpha(0f).setDuration(if(moving) 200 else 0).withEndAction { (chip.parent as? ViewGroup)?.removeView(chip) }.start() },2400)
    }

    private fun leave() {
        if(saving) return
        if(page in listOf("form","review","processing","revealing")) {
            if(editing!=null) { editing=null; draftTitle=""; draftStory=""; return back() }
            ask("Leave without keeping it?","This photo won’t be added.","Leave","Keep going") { generation++; clearDraft(); back(); true }
        } else desk()
    }
    /** Back to wherever adding or editing started. */
    private fun back() {
        val book=openBook
        when {
            addFrom=="thing" && thingId!=null && store.find(thingId!!)!=null -> thing(thingId!!,thingFrom)
            addFrom=="page" && book!=null -> openPage(book,pageIndex)
            else -> desk()
        }
    }
    @Deprecated("Android back compatibility") override fun onBackPressed() {
        if(closeNote!=null) { closeNote?.invoke(); return }
        when(page) {
            "onboarding" -> super.onBackPressed()
            "desk" -> if(calendarMode) { calendarMode=false; desk() } else super.onBackPressed()
            "page" -> if(arranging) { arranging=false; openPage(openBook ?: return desk(),pageIndex) } else toDesk()
            "thing" -> thingBack()
            else -> leave()
        }
    }
    private fun clearDraft() { original=null; cutout=null; draftTitle=""; draftStory=""; File(cacheDir,"draft.png").delete(); File(cacheDir,"cutout.png").delete() }
    private fun finishOnboarding() { prefs.edit().putBoolean("onboarded",true).apply(); desk() }

    // ---------- welcome ----------
    private fun onboarding() {
        flow("onboarding",Paper.PLAIN)
        val field=WelcomeField(this)
        frame.addView(field,0,FrameLayout.LayoutParams(-1,-1))
        frame.addView(View(this).apply { background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Ink.alpha(Paper.PLAIN.ground,0f),Ink.alpha(Paper.PLAIN.ground,.95f),Paper.PLAIN.ground)) },
            1,FrameLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.5f).toInt(),Gravity.BOTTOM))
        add(label("Shelf",Ink.INK).apply { gravity=Gravity.CENTER_HORIZONTAL },top=14)
        spring()
        val heading=title("A book for the\nsmall things.",42f)
        val intro=prose("Photograph the things you keep. Shelf cuts them out and sticks them in your books, only on this phone.")
        add(heading); add(intro,top=12)
        val footer=FrameLayout(this)
        val loading=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL }
        val line=ProgressLine(this)
        loading.addView(line,LinearLayout.LayoutParams(-1,dp(12)))
        loading.addView(label("Getting ready"),LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(10) })
        val begin=primary("Begin") {}.apply { visibility=View.INVISIBLE }
        footer.addView(loading,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER))
        footer.addView(begin,FrameLayout.LayoutParams(-1,-2))
        add(footer,top=32)
        // Ready once the cut-out model has loaded, but nobody waits more than a couple of seconds for it.
        val started=SystemClock.uptimeMillis()
        val tick=object : Runnable { override fun run() {
            if(page!="onboarding") return
            val t=SystemClock.uptimeMillis()-started; val done=(warmed && t>=900) || t>=2600
            line.progress=if(done) 1f else min(.9f,t/2600f+(if(warmed) .35f else 0f))
            if(!done) { frame.postDelayed(this,32); return }
            begin.visibility=View.VISIBLE
            if(moving) { loading.animate().alpha(0f).setDuration(160).start(); begin.alpha=0f; begin.animate().alpha(1f).setDuration(200).start() } else loading.visibility=View.INVISIBLE
        } }
        frame.post(tick)
        begin.setOnClickListener {
            begin.isEnabled=false
            listOf(heading,intro,footer).forEach { it.animate().alpha(0f).setDuration(if(moving) 180 else 0).start() }
            field.gather { finishOnboarding() }
        }
    }

    // ---------- the desk: books ----------
    private fun toDesk() { arranging=false; calendarMode=false; desk() }
    private fun desk() {
        val books=store.books().ifEmpty { listOf(store.firstBook()) }
        val all=try { store.all() } catch(e: Exception) { emptyList() }
        stage("desk",null)
        relayout={ desk() }
        deskButtons()
        if(calendarMode) return calendar(books,all)
        bookIndex=bookIndex.coerceIn(0,books.size-1)
        val heading=text("",40f,fonts.hand,Color.WHITE,1f).apply { gravity=Gravity.CENTER; maxLines=1; ellipsize=TextUtils.TruncateAt.END; isAccessibilityHeading=true }
        frame.addView(heading,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { topMargin=insetTop+dp(72); marginStart=dp(40); marginEnd=dp(40) })
        val sub=text("",12.5f,fonts.medium,Ink.alpha(Color.WHITE,.7f)).apply { gravity=Gravity.CENTER }
        frame.addView(sub,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { topMargin=insetTop+dp(122) })
        val covers=books.map { b -> val items=all.filter { it.book==b.id }; CoverView(this,b,items,PageLayout.pages(items.size),fonts.hand,loader).apply { isClickable=true; isFocusable=true } } +
            GhostBook(this,fonts.hand).apply { contentDescription="New book"; isClickable=true; isFocusable=true }
        lateinit var carousel: DeskCarousel
        val dots=LinearLayout(this).apply { gravity=Gravity.CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        books.forEach { _ -> dots.addView(View(this),LinearLayout.LayoutParams(dp(7),dp(7)).apply { marginStart=dp(4); marginEnd=dp(4) }) }
        val hint=text("",12.5f,fonts.sans,Ink.alpha(Color.WHITE,.62f)).apply { gravity=Gravity.CENTER }
        val start=text("start\nhere",21f,fonts.hand,Color.WHITE,.95f).apply { rotation=-5f; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val arrow=ArrowView(this)
        val (prev,next)=arrows("book",{ carousel.go(carousel.index-1) },{ carousel.go(carousel.index+1) })
        fun show(i: Int) {
            bookIndex=i
            val book=books[i]; val items=all.filter { it.book==book.id }
            heading.text=book.name
            sub.text=if(items.isEmpty()) "Empty, for now" else "${count(items.size)} · ${count(PageLayout.pages(items.size),"page")}"
            for(k in 0 until dots.childCount) dots.getChildAt(k).background=if(k==i) pill(Color.WHITE,dp(4f)) else pill(Color.TRANSPARENT,dp(4f),Ink.alpha(Color.WHITE,.6f),dp(1.3f))
            hint.text=(if(items.isEmpty()) "Photograph something you kept" else "Tap to open")+(if(books.size>1) " · swipe for other books" else "")
            prev.visibility=if(i>0) View.VISIBLE else View.GONE; next.visibility=if(i<books.size-1) View.VISIBLE else View.GONE
            val first=all.isEmpty() && i==0
            start.visibility=if(first) View.VISIBLE else View.GONE; arrow.visibility=start.visibility
            covers[i].contentDescription="${book.name}, ${count(items.size)}. Open book"
        }
        carousel=DeskCarousel(this,covers,bookIndex,onPick={ i -> if(i>=books.size) { carousel.go(books.size-1); newBook() } else show(i) },
            onOpen={ i -> if(i>=books.size) newBook() else openPage(books[i].id,0,peek=true) })
        frame.addView(carousel,FrameLayout.LayoutParams(-1,-1).apply { topMargin=insetTop+dp(166); bottomMargin=insetBottom+dp(146) })
        frame.addView(dots,FrameLayout.LayoutParams(-1,dp(10),Gravity.BOTTOM).apply { bottomMargin=insetBottom+dp(126) })
        frame.addView(hint,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { bottomMargin=insetBottom+dp(98) })
        frame.addView(start,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply { bottomMargin=insetBottom+dp(34); marginStart=dp(34) })
        frame.addView(arrow,FrameLayout.LayoutParams(dp(70),dp(40),Gravity.BOTTOM or Gravity.START).apply { bottomMargin=insetBottom+dp(22); marginStart=dp(86) })
        prev.bringToFront(); next.bringToFront()
        addButton("Add a thing") { startAdd(books[bookIndex].id,"desk") }
        show(bookIndex)
    }
    private fun newBook() = askName("A new book","What should it be called?","","Make the book") { name ->
        try { store.addBook(name,Paper.entries[store.books().size%4]); bookIndex=store.books().size-1; calendarMode=false; desk() } catch(e: Exception) { error("That book couldn’t be made. Please try again.") }
    }
    private fun menu() {
        val books=store.books(); val book=books.getOrNull(bookIndex) ?: return
        sheet { panel,close ->
            sheetRow(panel,Doodle.NEW_BOOK,"New book") { close(); newBook() }
            if(!calendarMode) {
                sheetRow(panel,Doodle.RENAME,"Rename “${book.name}”") { close(); askName("Rename",null,book.name,"Save") { name -> store.renameBook(book.id,name); desk() } }
                panel.addView(label("Paper for “${book.name}”",Ink.INK_2),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8); marginStart=dp(10); bottomMargin=dp(8) })
                panel.addView(paperRow(book.paper) { p -> store.setPaper(book.id,p); desk(); menu() },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
                if(books.size>1) sheetRow(panel,Doodle.TRASH,"Delete “${book.name}”",danger=true) {
                    close()
                    val n=store.inBook(book.id).size
                    ask("Delete “${book.name}”?",if(n>0) "The ${count(n)} inside will be deleted too. This can’t be undone." else "It’s empty.","Delete","Keep it") {
                        store.deleteBook(book.id).forEach { Stickers.forget(this,it) }; bookIndex=max(0,bookIndex-1); desk(); true
                    }
                }
            }
        }
    }

    // ---------- the desk: calendar ----------
    private fun calendar(books: List<Book>, all: List<Keepsake>) {
        val now=Calendar.getInstance(); val thisMonth=now.get(Calendar.YEAR)*12+now.get(Calendar.MONTH)
        val shown=if(month<0) thisMonth else month; val y=shown/12; val m=shown%12
        val byDay=all.mapNotNull { item ->
            val c=Calendar.getInstance().apply { timeInMillis=item.created }
            if(c.get(Calendar.YEAR)==y && c.get(Calendar.MONTH)==m) c.get(Calendar.DAY_OF_MONTH) to item else null
        }.groupBy({ it.first },{ it.second })
        val kept=byDay.values.sumOf { it.size }
        frame.addView(text(CalendarSheet.monthName(m),40f,fonts.hand,Color.WHITE,1f).apply { gravity=Gravity.CENTER; isAccessibilityHeading=true },
            FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { topMargin=insetTop+dp(72) })
        frame.addView(text("$y · ${if(kept>0) count(kept)+" kept" else "nothing kept yet"}",12.5f,fonts.medium,Ink.alpha(Color.WHITE,.7f)).apply { gravity=Gravity.CENTER },
            FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply { topMargin=insetTop+dp(122) })
        val today=if(shown==thisMonth) now.get(Calendar.DAY_OF_MONTH) else -1
        val sheet=CalendarSheet(this,y,m,byDay,today,fonts.hand,fonts.mono,loader,photoLoader) { day -> openDay(m,day,byDay[day].orEmpty()) }
        sheet.onSwipe={ dir -> shiftMonth(dir) }
        frame.addView(sheet,FrameLayout.LayoutParams(-1,-1).apply { topMargin=insetTop+dp(150); bottomMargin=insetBottom+dp(96) })
        arrows("month",{ shiftMonth(-1) },{ shiftMonth(1) })
        addButton("Add a thing") { startAdd(books[bookIndex.coerceIn(0,books.size-1)].id,"desk") }
    }
    private fun shiftMonth(k: Int) {
        val now=Calendar.getInstance()
        month=(if(month<0) now.get(Calendar.YEAR)*12+now.get(Calendar.MONTH) else month)+k
        desk()
    }
    private fun openDay(m: Int, day: Int, list: List<Keepsake>) {
        if(list.size==1) return thing(list[0].id,"calendar")
        val books=store.books()
        sheet { panel,close ->
            panel.addView(text("${CalendarSheet.monthName(m)} $day",26f,fonts.hand,Ink.INK,1f).apply { isAccessibilityHeading=true },LinearLayout.LayoutParams(-1,-2).apply { marginStart=dp(10); bottomMargin=dp(6) })
            list.forEach { item ->
                val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; background=ripple(dp(12f)); isClickable=true; isFocusable=true; setPadding(dp(8),0,dp(10),0); contentDescription=item.title; setOnClickListener { close(); thing(item.id,"calendar") } }
                val art=StickerView(this,0f,bottom=false)
                row.addView(art,LinearLayout.LayoutParams(dp(48),dp(48)))
                loader(item,dp(96)) { art.show(it,false) }
                row.addView(text(item.title,16f,fonts.medium,Ink.INK).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END },LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(12) })
                row.addView(text(books.find { it.id==item.book }?.name ?: "",13f,fonts.sans,Ink.INK_3))
                panel.addView(row,LinearLayout.LayoutParams(-1,dp(60)))
            }
        }
    }

    // ---------- a book's pages ----------
    private fun openPage(bookId: String, index: Int, fresh: String?=null, peek: Boolean=false) {
        val books=store.books(); val book=books.find { it.id==bookId } ?: return desk()
        val items=store.inBook(bookId); val pages=PageLayout.pages(items.size)
        openBook=bookId; pageIndex=index.coerceIn(0,pages-1); bookIndex=books.indexOf(book)
        stage("page",book.paper)
        relayout={ openPage(bookId,pageIndex) }
        lateinit var label: TextView
        lateinit var nav: Pair<View,View>
        val view=BookView(this,pages,pageIndex,
            make={ i,fit -> Leaf(this,book,i,pages,items.drop(i*PageLayout.PER).take(PageLayout.PER),items.isEmpty(),fit,fonts.hand,loader,this) },
            fitFor={ w,h -> pageFit(w,h,insetTop,insetBottom,density) },
            onTurned={ p ->
                pageIndex=p; label.text="Page ${p+1} of $pages".uppercase()
                if(!arranging) { nav.first.visibility=if(p>0) View.VISIBLE else View.GONE; nav.second.visibility=if(p<pages-1) View.VISIBLE else View.GONE }
                label.announceForAccessibility("Page ${p+1} of $pages")
            })
        view.arranging=arranging
        frame.addView(view,FrameLayout.LayoutParams(-1,-1))
        bookView=view
        corner(RoundButton(this,Doodle.PREV,"Back to your books").apply { setOnClickListener { toDesk() } },start=14)
        label=topLabel(if(arranging) "Arranging" else "Page ${pageIndex+1} of $pages")
        if(arranging) {
            corner(RoundButton(this,Doodle.CHECK,"Done arranging").apply { setOnClickListener { arranging=false; openPage(bookId,pageIndex) } },end=14)
            arrangeBar(book,items)
        } else {
            corner(RoundButton(this,Doodle.PENCIL,"Arrange this page").apply { setOnClickListener { arranging=true; openPage(bookId,pageIndex); snack("Drag to move · twist with the round handle") } },end=14)
            nav=arrows("page",{ view.turn(-1) },{ view.turn(1) })
            nav.first.visibility=if(pageIndex>0) View.VISIBLE else View.GONE; nav.second.visibility=if(pageIndex<pages-1) View.VISIBLE else View.GONE
            addButton("Add a thing to ${book.name}") { startAdd(book.id,"page") }
        }
        view.onReady={
            if(fresh!=null) view.current?.things?.find { it.item.id==fresh }?.stickIn()
            if(peek && !arranging && book.id !in peeked) { peeked+=book.id; view.postDelayed({ view.peek() },450) }
        }
    }
    private fun arrangeBar(book: Book, items: List<Keepsake>) {
        val bar=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL; elevation=dp(12f); isClickable=true; setPadding(dp(10),dp(12),dp(10),insetBottom+dp(14))
            background=GradientDrawable().apply { setColor(Color.WHITE); cornerRadii=floatArrayOf(dp(22f),dp(22f),dp(22f),dp(22f),0f,0f,0f,0f) }
        }
        bar.addView(paperRow(book.paper) { p -> store.setPaper(book.id,p); openPage(book.id,pageIndex) },LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(QuietButton(this,"Tidy up").apply { contentDescription="Tidy up this page"; setOnClickListener {
            items.drop(pageIndex*PageLayout.PER).take(PageLayout.PER).forEach { store.place(it.id,null) }; openPage(book.id,pageIndex)
        } })
        frame.addView(bar,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
    }
    override fun open(thing: ThingView) = thing(thing.item.id,"page")
    override fun choose(thing: ThingView) {
        val leaf=thing.parent as? Leaf ?: return
        leaf.things.forEach { it.chosen=it===thing }
        thing.place=thing.place.copy(z=leaf.things.maxOf { it.place.z }+1)
        leaf.raise(thing); keepPlace(thing)
    }
    override fun moved(thing: ThingView) { (thing.parent as? Leaf)?.settle(thing); keepPlace(thing) }
    private fun keepPlace(thing: ThingView) { val id=thing.item.id; val place=thing.place; worker.execute { try { store.place(id,place) } catch(e: Exception) {} } }

    // ---------- a thing's own page ----------
    private fun thing(id: String, from: String) {
        val item=store.find(id) ?: return desk()
        val book=store.books().find { it.id==item.book } ?: return desk()
        thingId=id; thingFrom=from
        stage("thing",book.paper)
        relayout={ thing(id,from) }
        val k=min(resources.displayMetrics.widthPixels/360f,dp(1.3f))
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; clipChildren=false; clipToPadding=false }
        val art=ThingArt(this,item,book.paper,k,fonts.hand)
        column.addView(art,LinearLayout.LayoutParams(-1,-2))
        loader(item,(260*k).toInt()) { art.showArt(it) }
        if(!item.framed) Stickers.background { val photo=decodeSampled(store.photo(id).path,720); if(photo!=null) runOnUiThread { art.showPhoto(photo) } }
        if(item.story.isNotEmpty()) {
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=pill(Color.WHITE,dp(3f)); elevation=dp(3f); setPadding(dp(18),dp(18),dp(18),dp(20)); rotation=-1f }
            card.addView(label("The story",Ink.INK_2))
            card.addView(text(item.story,16f,fonts.sans,Ink.INK,1.45f).apply { setTextIsSelectable(true) },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10) })
            val holder=FrameLayout(this).apply { clipChildren=false }
            holder.addView(card,FrameLayout.LayoutParams(-1,-2).apply { setMargins(dp(24),dp(10),dp(24),0) })
            holder.addView(TapeView(this,book.paper,1),FrameLayout.LayoutParams(dp(70),dp(28),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            column.addView(holder,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
        } else {
            column.addView(text("+ add a story",20f,fonts.hand,if(onDark) Color.WHITE else Pen.BLUE).apply {
                background=ripple(dp(12f)); setPadding(dp(12),dp(10),dp(12),dp(10)); isClickable=true; setOnClickListener { edit(item) }
            },LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(16) })
        }
        val scroller=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipToPadding=false; setPadding(0,insetTop+dp(62),0,insetBottom+dp(40)); addView(column) }
        frame.addView(scroller,FrameLayout.LayoutParams(-1,-1))
        frame.addView(View(this).apply { background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(book.paper.ground,book.paper.ground,Ink.alpha(book.paper.ground,0f))) },
            FrameLayout.LayoutParams(-1,insetTop+dp(60)))
        val index=store.inBook(book.id).indexOfFirst { it.id==id }
        corner(RoundButton(this,Doodle.PREV,if(from=="calendar") "Back to the calendar" else "Back to ${book.name}").apply { setOnClickListener { thingBack() } },start=14)
        topLabel("${book.name} · page ${PageLayout.pageOf(index)+1}")
        corner(RoundButton(this,Doodle.PENCIL,"Edit this thing").apply { setOnClickListener { edit(item) } },end=14)
    }
    private fun thingBack() {
        val item=thingId?.let { store.find(it) }
        if(thingFrom=="calendar" || item==null) { calendarMode=thingFrom=="calendar"; return desk() }
        openPage(item.book,PageLayout.pageOf(store.inBook(item.book).indexOfFirst { it.id==item.id }))
    }
    private fun edit(item: Keepsake) {
        editing=item.id; addFrom="thing"; draftTitle=item.title; draftStory=item.story; draftBook=item.book; framed=item.framed
        form()
    }

    // ---------- adding something ----------
    private fun startAdd(bookId: String, from: String) { draftBook=bookId; addFrom=from; editing=null; draftTitle=""; draftStory=""; pick() }
    private fun pick() {
        val intent=if(Build.VERSION.SDK_INT>=33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*") else Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(intent,42) } catch(e:Exception) { error("No photo picker is available on this device.") }
    }
    @Deprecated("Activity result compatibility") override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        super.onActivityResult(request,result,data)
        if(request!=42 || result!=RESULT_OK) return
        val uri=data?.data ?: return
        generation++; val token=generation
        flow("processing"); topBar("Step 1 of 2"); add(title("What would you like to keep?",32f),top=8); add(prose("Opening your photo…"),top=8)
        worker.execute {
            try {
                val b=ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,uri)) { decoder,info,_ ->
                    val scale=minOf(1.0,1600.0/maxOf(info.size.width,info.size.height))
                    decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt())); decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                }
                File(cacheDir,"draft.png").outputStream().use { check(b.compress(Bitmap.CompressFormat.PNG,100,it)) }
                File(cacheDir,"cutout.png").delete()
                runOnUiThread { if(token==generation && !isDestroyed) { original=b; cutout=null; framed=false; process() } }
            } catch(e:Exception) { runOnUiThread { if(token==generation && !isDestroyed) { back(); error("This photo couldn’t be opened. Try another image.") } } }
        }
    }
    private fun process() {
        val b=original ?: return
        cutout=null; framed=false
        flow("processing"); topBar("Step 1 of 2")
        val heading=title("What would you like to keep?",32f)
        val hint=prose("Finding the things in your photo…")
        add(heading,top=8); add(hint,top=8)
        val stage=SubjectSelectionView(this,b)
        selectionView=stage
        val available=resources.displayMetrics.widthPixels-dp(40)
        val height=((available-dp(32))*b.height/b.width.toFloat()+dp(32)).toInt().coerceIn(dp(220),dp(440))
        body.addView(stage,LinearLayout.LayoutParams(-1,height).apply { topMargin=dp(12) })
        val chips=LinearLayout(this).apply { gravity=Gravity.CENTER; clipChildren=false }
        val chipRow=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFillViewport=true; visibility=View.GONE; addView(chips) }
        add(chipRow,top=8)
        val status=text("",14f,fonts.sans,if(onDark) Color.WHITE else Ink.INK_2).apply { gravity=Gravity.CENTER_HORIZONTAL; accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        add(status,top=6)
        spring()
        val keep=primary("Snip it out") {}.apply { isEnabled=false }
        var acts=actions(keep,quiet("Keep the whole photo instead") { generation++; framed=true; review() })
        body.addView(acts)
        val token=++generation
        val timeout=Runnable {
            if(token==generation && page=="processing") {
                generation++; framed=true; review("Finding things is taking too long. Keep the photo, or try again.")
            }
        }
        val host=frame
        host.postDelayed(timeout,45000)
        stage.onSelection={ index ->
            keep.isEnabled=index!=null
            status.text=when { index==null -> "Nothing chosen yet."; stage.subjects.size==1 -> ""; else -> "Object ${index+1} chosen." }
            for(i in 0 until chips.childCount) chips.getChildAt(i).isSelected=i==index
            if(index!=null) hint.text="Good choice. Snip it out."
        }
        stage.onMiss={ status.text="Nothing to cut there. Tap inside a dashed line." }
        keep.setOnClickListener {
            val index=stage.selectedIndex ?: return@setOnClickListener
            if(page!="processing") return@setOnClickListener
            stage.lockSelection()
            page="revealing"; keep.isEnabled=false
            chipRow.visibility=View.INVISIBLE; acts.visibility=View.INVISIBLE
            heading.minHeight=heading.height; hint.minHeight=hint.height
            heading.text="Snipping…"
            hint.text="Cutting along the line."
            status.text=""
            val subject=stage.subjects[index]
            worker.execute {
                try {
                    val chosen=subject.export()
                    val file=android.util.AtomicFile(File(cacheDir,"cutout.png"))
                    val stream=file.startWrite()
                    try { check(chosen.compress(Bitmap.CompressFormat.PNG,100,stream)); file.finishWrite(stream) }
                    catch(e: Exception) { file.failWrite(stream); throw e }
                    runOnUiThread {
                        if(token!=generation || isDestroyed) return@runOnUiThread
                        cutout=chosen
                        stage.snip {
                            if(token==generation && !isDestroyed) {
                                page="review"; heading.text="Snipped."; hint.text="Just the thing, ready for your book."
                                chipRow.visibility=View.GONE
                                val next=actions(primary("Use this sticker") { form() },quiet("Choose a different object") { process() },quiet("Keep the whole photo instead") { framed=true; review() })
                                body.removeView(acts); body.addView(next); acts=next
                                stage.announceForAccessibility("Snipped. Just the thing, ready for your book.")
                            }
                        }
                    }
                } catch(e:Exception) {
                    runOnUiThread { if(token==generation && !isDestroyed) { process(); error("Couldn’t prepare that cutout. Please try again; your original photo is still here.") } }
                }
            }
        }
        segmenter.process(InputImage.fromBitmap(b,0)).addOnSuccessListener { result ->
            if(isDestroyed || token!=generation) return@addOnSuccessListener
            host.removeCallbacks(timeout)
            val subjects=result.subjects.mapNotNull { subject ->
                subject.bitmap?.takeIf { subject.startX>=0 && subject.startY>=0 &&
                    subject.startX+it.width<=b.width && subject.startY+it.height<=b.height }?.let {
                    PhotoSubject(it,subject.startX,subject.startY)
                }
            }
            if(subjects.isEmpty()) { framed=true; review("There’s no separate thing to cut out here, so it stays a whole photo."); return@addOnSuccessListener }
            // Tracing cut lines and cutting stickers is real work: do it off the main thread.
            worker.execute {
                val outlines=SubjectSelectionView.trace(subjects,b)
                val cut=subjects.indices.map { SubjectSelectionView.cutOut(subjects[it],outlines[it]) }
                val thumbs=cut.map { it.bitmap.fit(dp(96)) }
                runOnUiThread {
                    if(isDestroyed || token!=generation) return@runOnUiThread
                    stage.show(subjects,outlines,cut)
                    hint.text=if(subjects.size==1) "Found it. Snip it out when you’re ready." else "Tap the thing you want to cut out."
                    if(subjects.size>1) {
                        thumbs.forEachIndexed { i,thumb -> chips.addView(ChipButton(this,i+1,thumb).apply { setOnClickListener { stage.select(i) } },LinearLayout.LayoutParams(dp(72),dp(72))) }
                        chipRow.visibility=View.VISIBLE
                    }
                    if(subjects.size==1) stage.select(0) // Nothing to choose between: go straight to snipping.
                }
            }
        }.addOnFailureListener {
            if(token==generation && !isDestroyed) {
                host.removeCallbacks(timeout); framed=true
                review("Things couldn’t be found yet. Keep the photo, or try again once Shelf is ready.")
            }
        }
    }
    private fun review(message: String="") {
        val paper=bookFor(draftBook).paper
        flow("review"); topBar("Step 1 of 2")
        add(title(if(framed) "The whole photo, then." else "Snipped.",32f),top=8)
        add(prose(if(framed) message.ifEmpty { "It will go in your book as a print." } else "Just the thing, ready for your book."),top=8)
        val art=StickerView(this,if(framed) -3f else -3f,bottom=false,paper=if(framed) paper else null).apply { contentDescription=if(framed) "Your photo" else "Your object"; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES }
        body.addView(art,LinearLayout.LayoutParams(-1,dp(300)).apply { topMargin=dp(24) })
        artOf(if(framed) original else cutout,framed,dp(360),art)
        spring()
        body.addView(if(framed) actions(primary("Use this photo") { form() },quiet(if(message.isNotEmpty()) "Try the cutout again" else "Cut out an object instead") { framed=false; process() },quiet("Choose another photo") { pick() })
            else actions(primary("Use this sticker") { form() },quiet("Choose a different object") { process() },quiet("Keep the whole photo instead") { framed=true; review() }))
    }

    // ---------- naming it ----------
    private fun chip(label: String, chosen: Boolean, action: () -> Unit) = Button(this).apply {
        text=label; isAllCaps=false; typeface=fonts.medium; setTextSize(TypedValue.COMPLEX_UNIT_SP,14f); stateListAnimator=null
        setTextColor(if(chosen) Color.WHITE else Ink.INK); minWidth=0; minimumWidth=0; minHeight=dp(44); minimumHeight=dp(44); setPadding(dp(16),0,dp(16),0)
        background=RippleDrawable(ColorStateList.valueOf(Ink.alpha(Ink.INK,.1f)),if(chosen) pill(Ink.INK,dp(22f)) else pill(Color.WHITE,dp(22f),Ink.LINE,dp(1.2f)),null)
        isSelected=chosen; setOnClickListener { action() }
    }
    private fun form() {
        val book=bookFor(draftBook); draftBook=book.id
        val item=editing?.let { store.find(it) }
        if(editing!=null && item==null) { editing=null; return desk() }
        flow("form"); topBar(if(item!=null) "Editing" else "Step 2 of 2")
        add(title(if(item!=null) "Edit this thing" else "Name it",32f),top=8)
        val art=StickerView(this,-4f,bottom=true,paper=if(framed) book.paper else null).apply { contentDescription=if(framed) "Your photo" else "Your object"; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES }
        body.addView(art,LinearLayout.LayoutParams(-1,dp(170)).apply { topMargin=dp(10) })
        if(item!=null) loader(item,dp(340)) { art.show(it,true) } else artOf(if(framed) original else cutout,framed,dp(340),art)
        body.addView(StringView(this),LinearLayout.LayoutParams(-1,dp(26)))
        val tag=TagBackground(density)
        val name=EditText(this).apply {
            id=View.generateViewId(); setText(draftTitle); hint="name it…"; contentDescription="Name"; typeface=fonts.hand; setTextSize(TypedValue.COMPLEX_UNIT_SP,26f); setTextColor(Ink.INK); setHintTextColor(Ink.alpha(Ink.INK,.42f))
            background=tag; setPadding(dp(32),0,dp(16),0); isSingleLine=true; filters=arrayOf(InputFilter.LengthFilter(120)); gravity=Gravity.CENTER_VERTICAL
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES; imeOptions=EditorInfo.IME_ACTION_NEXT; rotation=-1.5f
            setOnFocusChangeListener { _,has -> tag.focused=has }
        }
        body.addView(name,LinearLayout.LayoutParams(-1,dp(54)).apply { marginStart=dp(8); marginEnd=dp(24) })
        val nameError=text("↑ give it a name",18f,fonts.hand,Ink.ACCENT_DARK).apply { visibility=View.GONE; accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        add(nameError,top=4)
        name.onEdit { nameError.visibility=View.GONE }
        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=pill(Color.WHITE,dp(3f)); elevation=dp(3f); setPadding(dp(16),dp(14),dp(16),dp(8)) }
        val story=EditText(this).apply {
            id=View.generateViewId(); setText(draftStory); hint="A line or two, if you like."; typeface=fonts.sans; setTextSize(TypedValue.COMPLEX_UNIT_SP,15.5f); setTextColor(Ink.INK); setHintTextColor(Ink.INK_3)
            background=null; setPadding(0,dp(6),0,dp(6)); minLines=3; gravity=Gravity.TOP or Gravity.START; setLineSpacing(0f,1.35f); filters=arrayOf(InputFilter.LengthFilter(4000))
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        card.addView(label("The story · optional",Ink.INK_2).apply { labelFor=story.id })
        card.addView(story,LinearLayout.LayoutParams(-1,-2))
        body.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(18) })
        add(label("In which book"),top=22)
        val chips=LinearLayout(this).apply { setPadding(0,dp(8),0,dp(2)) }
        store.books().forEach { b -> chips.addView(chip(b.name,b.id==draftBook) { rememberFields(); draftBook=b.id; form() }.apply { contentDescription="Put it in ${b.name}" },LinearLayout.LayoutParams(-2,-2).apply { marginEnd=dp(8) }) }
        chips.addView(chip("+ New book",false) {
            rememberFields()
            askName("A new book","What should it be called?","","Make the book") { n -> try { draftBook=store.addBook(n,Paper.entries[store.books().size%4]).id; form() } catch(e: Exception) { error("That book couldn’t be made. Please try again.") } }
        })
        body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; clipToPadding=false; addView(chips) },LinearLayout.LayoutParams(-1,-2))
        spring()
        val save=primary(if(item!=null) "Save changes" else "Stick it in") {}
        body.addView(if(item!=null) actions(save,quiet("Delete this thing",Ink.ACCENT_DARK) { confirmDelete(item) }) else actions(save))
        titleField=name; storyField=story
        save.setOnClickListener {
            rememberFields()
            if(draftTitle.isBlank()) { nameError.visibility=View.VISIBLE; name.requestFocus(); scroll.smoothScrollTo(0,max(0,name.top-dp(160))); nameError.announceForAccessibility("Give it a name"); return@setOnClickListener }
            if(saving) return@setOnClickListener
            val bookId=draftBook ?: return@setOnClickListener
            saving=true; save.isEnabled=false
            if(item!=null) worker.execute {
                try { store.update(item.id,draftTitle,draftStory,bookId); runOnUiThread { saving=false; if(!isDestroyed) { editing=null; clearDraft(); thing(item.id,thingFrom); snack("Saved") } } }
                catch(e: Exception) { runOnUiThread { saving=false; if(!isDestroyed) { save.isEnabled=true; error("Couldn’t save your changes. Please try again.") } } }
            } else {
                val bitmap=(if(framed) original else cutout) ?: run { saving=false; save.isEnabled=true; return@setOnClickListener }
                val photo=if(framed) null else original
                worker.execute {
                    try {
                        val saved=store.save(bitmap,draftTitle,draftStory,bookId,framed,photo)
                        Stickers.prepare(this,store,saved)
                        val index=store.inBook(bookId).indexOfFirst { it.id==saved.id }
                        runOnUiThread { saving=false; if(!isDestroyed) { clearDraft(); arranging=false; openPage(bookId,PageLayout.pageOf(index),fresh=saved.id) } }
                    } catch(e:Exception) { runOnUiThread { saving=false; if(!isDestroyed) { save.isEnabled=true; error("Couldn’t save it. Free some space and try again; your draft is still here.") } } }
                }
            }
        }
    }
    private fun confirmDelete(item: Keepsake) = ask("Delete “${item.title}”?","It will be taken out of its book. This can’t be undone.","Delete","Keep it") {
        val index=store.inBook(item.book).indexOfFirst { it.id==item.id }
        worker.execute {
            try { store.delete(item.id); Stickers.forget(this,item.id)
                runOnUiThread { if(!isDestroyed) { editing=null; clearDraft(); openPage(item.book,PageLayout.pageOf(index)); snack("Deleted") } }
            } catch(e: Exception) { runOnUiThread { if(!isDestroyed) error("Couldn’t delete it. Please try again.") } }
        }
        true
    }

    override fun onStop() { selectionView?.pause(); super.onStop() }
    override fun onStart() { super.onStart(); selectionView?.resume() }
    override fun onDestroy() { generation++; selectionView?.dispose(); worker.shutdown(); segmenter.close(); super.onDestroy() }
}
