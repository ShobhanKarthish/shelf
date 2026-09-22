package com.shobhankarthish.shelf

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.content.res.ColorStateList
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
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var fonts: Fonts
    private lateinit var frame: FrameLayout
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var store: ShelfStore
    private val worker = Executors.newSingleThreadExecutor()
    private val segmenter by lazy { SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableSubjectBitmap().build()).build()) }
    private var selectionView: SubjectSelectionView? = null
    private var page = "shelf"
    private var original: Bitmap? = null
    private var cutout: Bitmap? = null
    private var framed = false
    private var generation = 0
    private var draftTitle = ""
    private var draftStory = ""
    private var draftShelf = "My first shelf"
    private var selected: Keepsake? = null
    private var saving = false
    private var warmed = false
    private var titleField: EditText? = null
    private var storyField: EditText? = null
    private var shelfField: EditText? = null
    private var dock: View? = null
    private var veil: View? = null
    private var closeNote: (() -> Unit)? = null
    private var insetTop = 0
    private var insetBottom = 0
    private val prefs by lazy { getSharedPreferences("shelf", MODE_PRIVATE) }
    private val moving get() = ValueAnimator.areAnimatorsEnabled()
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun dp(n: Float) = n * resources.displayMetrics.density

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        fonts = Fonts.of(this)
        store = ShelfStore(this)
        draftTitle = state?.getString("title") ?: ""
        draftStory = state?.getString("story") ?: ""
        draftShelf = state?.getString("shelf") ?: "My first shelf"
        framed = state?.getBoolean("framed") ?: false
        original = BitmapFactory.decodeFile(File(cacheDir,"draft.png").path)
        cutout = if(state?.getBoolean("cutoutReady")==true) BitmapFactory.decodeFile(File(cacheDir,"cutout.png").path) else null
        page = state?.getString("page") ?: if (prefs.getBoolean("onboarded",false)) "shelf" else "onboarding"
        selected = state?.getString("selected")?.let { id -> store.all().find { it.id == id } }
        when {
            page == "onboarding" -> onboarding()
            page == "details" && selected != null -> details(selected!!)
            page == "form" && original != null -> form()
            (page == "review" || page == "revealing") && original != null -> if (cutout != null || framed) review() else process()
            page == "processing" && original != null -> process()
            else -> shelf()
        }
        // Let the first screen draw before the model starts loading; they compete for the same CPU.
        window.decorView.postDelayed({ if(!isDestroyed) warmUp() },1200)
    }
    /** Loads the cut-out model in the background, so the first photo doesn't wait for it. */
    private fun warmUp() = Stickers.background {
        try { Tasks.await(segmenter.process(InputImage.fromBitmap(Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888),0)),30,TimeUnit.SECONDS) }
        finally { runOnUiThread { warmed=true } }
    }
    private fun rememberFields() { titleField?.let { draftTitle=it.text.toString() }; storyField?.let { draftStory=it.text.toString() }; shelfField?.let { draftShelf=it.text.toString() } }
    override fun onSaveInstanceState(out: Bundle) {
        rememberFields()
        out.putBoolean("cutoutReady",cutout!=null)
        out.putString("page",page); out.putString("title",draftTitle); out.putString("story",draftStory); out.putString("shelf",draftShelf); out.putBoolean("framed",framed); out.putString("selected",selected?.id)
        super.onSaveInstanceState(out)
    }

    // ---------- page, words and buttons ----------
    private fun screen(name: String) {
        selectionView?.dispose(); selectionView=null; closeNote=null; dock=null
        page=name; titleField=null; storyField=null; shelfField=null
        body = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; clipChildren=false; clipToPadding=false }
        scroll = ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; clipChildren=false; addView(body) }
        frame = FrameLayout(this).apply { setBackgroundColor(Ink.PAPER); addView(scroll,FrameLayout.LayoutParams(-1,-1)) }
        // Paper behind the status bar, so scrolled words never run under the clock.
        veil = View(this).apply { background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Ink.PAPER,Ink.PAPER,Ink.alpha(Ink.PAPER,0f))) }
        frame.addView(veil,FrameLayout.LayoutParams(-1,insetTop+dp(12)))
        frame.setOnApplyWindowInsetsListener { _,insets -> insetTop=insets.systemWindowInsetTop; insetBottom=insets.systemWindowInsetBottom; pad(); insets }
        pad()
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(frame)
        if(moving) { body.alpha=0f; body.translationY=dp(10f); body.animate().alpha(1f).translationY(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).start() }
    }
    private fun pad() {
        body.setPadding(dp(20),insetTop+dp(8),dp(20),insetBottom+dp(24)+(if(dock!=null) dp(88) else 0))
        dock?.setPadding(dp(20),dp(32),dp(20),insetBottom+dp(16))
        veil?.let { it.layoutParams=it.layoutParams.apply { height=insetTop+dp(12) } }
    }
    private fun text(value: String, sp: Float, face: Typeface, color: Int=Ink.INK, spacing: Float=1.3f) = TextView(this).apply {
        text=value; typeface=face; setTextSize(TypedValue.COMPLEX_UNIT_SP,sp); setTextColor(color); setLineSpacing(0f,spacing)
    }
    private fun title(value: String, sp: Float=34f) = text(value,sp,fonts.hand,Ink.INK,1f).apply { isAccessibilityHeading=true }
    private fun body(value: String) = text(value,16f,fonts.sans,Ink.INK_2,1.35f)
    private fun label(value: String, color: Int=Ink.INK_2) = text(value.uppercase(),11.5f,fonts.mono,color,1.2f).apply { letterSpacing=.14f }
    private fun add(view: View, top: Int=0, width: Int=-1) { body.addView(view,LinearLayout.LayoutParams(width,-2).apply { topMargin=dp(top) }) }
    private fun spring() { body.addView(Space(this),LinearLayout.LayoutParams(1,0,1f)) }
    private fun count(n: Int) = "$n ${if(n==1) "thing" else "things"}"
    /** The main action, full width at the bottom, with any other choices as plain buttons underneath. */
    private fun actions(main: Button, vararg others: Button) = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,dp(20),0,0)
        addView(main,LinearLayout.LayoutParams(-1,-2))
        others.forEach { addView(it,LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(6) }) }
    }
    private fun primary(label: String, action: () -> Unit) = PrimaryButton(this,label).apply { setOnClickListener { action() } }
    private fun quiet(label: String, action: () -> Unit) = QuietButton(this,label).apply { setOnClickListener { action() } }
    /** Close on the left; which step of adding something you're on, in the middle. */
    private fun topBar(step: String) {
        val bar = FrameLayout(this)
        bar.addView(IconButton(this,Doodle.X,"Cancel new object").apply { setOnClickListener { leave() } },FrameLayout.LayoutParams(dp(48),dp(48),Gravity.START or Gravity.CENTER_VERTICAL))
        bar.addView(label(step),FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        body.addView(bar,LinearLayout.LayoutParams(-1,dp(56)).apply { marginStart=-dp(12); marginEnd=-dp(12) })
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
    private fun load(item: Keepsake, into: StickerView, size: Int) {
        val ready=Stickers.cached(item,size)
        if(ready!=null) into.show(ready,false) else Stickers.load(this,store,item,size) { into.show(it,true) }
    }

    /** A dialog card for questions and problems. */
    private fun ask(heading: String, message: String?, confirm: String, cancel: String?=null, onConfirm: () -> Unit={}) {
        closeNote?.invoke()
        val scrim = FrameLayout(this).apply { setBackgroundColor(Ink.alpha(Ink.INK,.4f)); isClickable=true }
        val close = { frame.removeView(scrim); scroll.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO; dock?.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_AUTO; closeNote=null }
        val card = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=pill(Ink.PAPER,dp(24f)); setPadding(dp(24),dp(24),dp(20),dp(16)); elevation=dp(8f); isClickable=true }
        card.addView(text(heading,19f,fonts.medium,Ink.INK,1.25f).apply { isAccessibilityHeading=true })
        if(message!=null) card.addView(body(message),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        val row = LinearLayout(this).apply { gravity=Gravity.END or Gravity.CENTER_VERTICAL }
        val cancelButton=cancel?.let { quiet(it) { close() } }
        cancelButton?.let { row.addView(it) }
        val ok = PrimaryButton(this,confirm).apply { minHeight=dp(48); minimumHeight=dp(48); setPadding(dp(22),0,dp(22),0); setOnClickListener { close(); onConfirm() } }
        row.addView(ok,LinearLayout.LayoutParams(-2,-2).apply { marginStart=dp(8) })
        card.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(20) })
        scrim.addView(card,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER).apply { setMargins(dp(24),0,dp(24),0) })
        if(cancel!=null) scrim.setOnClickListener { close() }
        scroll.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS; dock?.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        frame.addView(scrim,FrameLayout.LayoutParams(-1,-1))
        closeNote=close
        if(moving) { scrim.alpha=0f; scrim.animate().alpha(1f).setDuration(150).start(); card.scaleX=.96f; card.scaleY=.96f; card.animate().scaleX(1f).scaleY(1f).setDuration(150).start() }
        scrim.announceForAccessibility(heading)
    }
    private fun error(message:String) = ask(message,null,"OK")
    /** A short confirmation that slides up and leaves on its own. */
    private fun snack(message: String) {
        val chip = text(message,15f,fonts.medium,Ink.PAPER,1.2f).apply {
            background=pill(Ink.INK,dp(24f)); setPadding(dp(16),dp(12),dp(20),dp(12)); gravity=Gravity.CENTER_VERTICAL; elevation=dp(6f)
            setCompoundDrawablesRelative(DoodleDrawable(Doodle.CHECK,Ink.PAPER,2.2f,dp(20)),null,null,null); compoundDrawablePadding=dp(10)
        }
        frame.addView(chip,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=insetBottom+dp(if(dock!=null) 92 else 24) })
        frame.announceForAccessibility(message)
        if(moving) { chip.alpha=0f; chip.translationY=dp(8f); chip.animate().alpha(1f).translationY(0f).setDuration(180).start() }
        chip.postDelayed({ chip.animate().alpha(0f).setDuration(if(moving) 200 else 0).withEndAction { (chip.parent as? ViewGroup)?.removeView(chip) }.start() },2600)
    }

    private fun leave() {
        if(saving) return
        if(page in listOf("form","review","processing","revealing")) ask("Leave without saving?","This photo won’t be added to your shelf.","Leave","Keep working") { generation++; clearDraft(); shelf() }
        else shelf()
    }
    @Deprecated("Android back compatibility") override fun onBackPressed() {
        if(closeNote!=null) { closeNote?.invoke(); return }
        if(page=="shelf" || page=="onboarding") super.onBackPressed() else leave()
    }
    private fun clearDraft() { original=null; cutout=null; draftTitle=""; draftStory=""; File(cacheDir,"draft.png").delete(); File(cacheDir,"cutout.png").delete() }
    private fun finishOnboarding() { prefs.edit().putBoolean("onboarded",true).apply(); shelf() }

    // ---------- welcome ----------
    private fun onboarding() {
        screen("onboarding")
        val field=WelcomeField(this)
        frame.addView(field,0,FrameLayout.LayoutParams(-1,-1))
        frame.addView(View(this).apply { background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Ink.alpha(Ink.PAPER,0f),Ink.alpha(Ink.PAPER,.95f),Ink.PAPER)) },
            1,FrameLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*.5f).toInt(),Gravity.BOTTOM))
        add(label("Shelf",Ink.INK).apply { gravity=Gravity.CENTER_HORIZONTAL },top=14)
        spring()
        val heading=title("A shelf for the\nsmall things.",42f)
        val intro=body("Photograph the things you keep. Shelf cuts them out and keeps them here, only on this phone.")
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

    // ---------- the shelf ----------
    private fun shelf(fresh: String?=null) {
        screen("shelf")
        val objects = try { store.all() } catch(e:Exception) { error("Your shelf could not be opened. Please restart the app."); return }
        val head=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        head.addView(label("Shelf",Ink.INK),LinearLayout.LayoutParams(0,-2,1f))
        if(objects.isNotEmpty()) head.addView(label(count(objects.size)))
        body.addView(head,LinearLayout.LayoutParams(-1,dp(56)))
        add(title("Your little shelf.",40f),top=4)
        if(objects.isEmpty()) emptyShelf() else objects.groupBy { it.shelf }.forEach { (name,items) -> shelfGroup(name,items,fresh) }
        dock(objects.isEmpty())
        if(fresh!=null) body.post { body.findViewWithTag<View>(fresh)?.let { v -> scroll.smoothScrollTo(0,max(0,(v.parent as View).top-dp(160))) } }
    }
    private fun shelfGroup(name: String, items: List<Keepsake>, fresh: String?) {
        val head=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; contentDescription="$name, ${count(items.size)}"; isAccessibilityHeading=true; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES }
        head.addView(label(name,Ink.INK).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(0,-2,1f))
        head.addView(label(items.size.toString()).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        add(head,top=32)
        items.chunked(3).forEachIndexed { r,row ->
            val shelfRow=ShelfRow(this,seedOf(name)+r,LINE_AT)
            row.forEach { shelfRow.addView(cell(it,it.id==fresh),LinearLayout.LayoutParams(0,-2,1f)) }
            repeat(3-row.size) { shelfRow.addView(Space(this),LinearLayout.LayoutParams(0,1,1f)) }
            add(shelfRow,top=if(r==0) 10 else 18)
        }
    }
    private fun cell(item: Keepsake, fresh: Boolean) = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_HORIZONTAL; clipChildren=false; tag=item.id
        isClickable=true; isFocusable=true; contentDescription="Open ${item.title}"; background=ripple(dp(16f)); setOnClickListener { details(item) }
        val art=StickerView(context,Rng(seedOf(item.id)).between(-3f,3f))
        addView(art,LinearLayout.LayoutParams(-1,dp(117)))
        addView(text(item.title,13.5f,fonts.sans,Ink.INK,1.2f).apply { gravity=Gravity.CENTER_HORIZONTAL; maxLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(4),0,dp(4),dp(8)); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        load(item,art,dp(117))
        if(fresh && moving) { alpha=0f; scaleX=.85f; scaleY=.85f; animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(220).setDuration(280).setInterpolator(OvershootInterpolator(1.4f)).start() }
    }
    private fun emptyShelf() {
        val row=ShelfRow(this,11,LINE_AT).apply { gravity=Gravity.CENTER_HORIZONTAL }
        row.addView(SlotView(this).apply { isClickable=true; setOnClickListener { pick() } },LinearLayout.LayoutParams(dp(132),dp(96)).apply { topMargin=dp(17) })
        add(row,top=28)
        add(text("Your first thing goes here",14f,fonts.sans,Ink.INK_2).apply { gravity=Gravity.CENTER_HORIZONTAL },top=14)
        add(title("Every collection starts with one thing.",28f),top=40)
        add(body("A ticket. A shell. Something you kept. Take a photo and Shelf cuts it out, then gives it a little place of its own."),top=10)
    }
    private fun dock(empty: Boolean) {
        val bar=FrameLayout(this).apply { background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(Ink.PAPER,Ink.PAPER,Ink.alpha(Ink.PAPER,0f))) }
        bar.addView(PrimaryButton(this,if(empty) "Add your first thing" else "Add a thing",Doodle.PLUS).apply { setOnClickListener { pick() } },
            FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM))
        frame.addView(bar,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        dock=bar; pad()
    }

    // ---------- adding something ----------
    private fun pick() {
        val intent=if(android.os.Build.VERSION.SDK_INT>=33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*") else Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(intent,42) } catch(e:Exception) { error("No photo picker is available on this device.") }
    }
    @Deprecated("Activity result compatibility") override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        super.onActivityResult(request,result,data)
        if(request!=42 || result!=RESULT_OK) return
        val uri=data?.data ?: return
        generation++; val token=generation
        screen("processing"); topBar("Step 1 of 2"); add(title("What would you like to keep?",32f),top=8); add(body("Opening your photo…"),top=8)
        worker.execute {
            try {
                val b=ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver,uri)) { decoder,info,_ ->
                    val scale=minOf(1.0,1600.0/maxOf(info.size.width,info.size.height))
                    decoder.setTargetSize(maxOf(1,(info.size.width*scale).toInt()),maxOf(1,(info.size.height*scale).toInt())); decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
                }
                File(cacheDir,"draft.png").outputStream().use { check(b.compress(Bitmap.CompressFormat.PNG,100,it)) }
                File(cacheDir,"cutout.png").delete()
                runOnUiThread { if(token==generation && !isDestroyed) { original=b; cutout=null; framed=false; process() } }
            } catch(e:Exception) { runOnUiThread { if(token==generation && !isDestroyed) { shelf(); error("This photo couldn’t be opened. Try another image.") } } }
        }
    }
    private fun process() {
        val b=original ?: return
        cutout=null; framed=false
        screen("processing"); topBar("Step 1 of 2")
        val heading=title("What would you like to keep?",32f)
        val hint=body("Finding the objects in your photo…")
        add(heading,top=8); add(hint,top=8)
        val stage=SubjectSelectionView(this,b)
        selectionView=stage
        val available=resources.displayMetrics.widthPixels-dp(40)
        val height=((available-dp(8))*b.height/b.width.toFloat()+dp(8)).toInt().coerceIn(dp(200),dp(420))
        body.addView(stage,LinearLayout.LayoutParams(-1,height).apply { topMargin=dp(20) })
        val chips=LinearLayout(this).apply { gravity=Gravity.CENTER; clipChildren=false }
        val chipRow=HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; isFillViewport=true; visibility=View.GONE; addView(chips) }
        add(chipRow,top=12)
        val status=text("",14f,fonts.sans,Ink.INK_2).apply { gravity=Gravity.CENTER_HORIZONTAL; accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        add(status,top=8)
        spring()
        val keep=primary("Snip it out") {}.apply { isEnabled=false }
        var acts=actions(keep,quiet("Keep the whole photo") { generation++; framed=true; review() })
        body.addView(acts)
        val token=++generation
        val timeout=Runnable {
            if(token==generation && page=="processing") {
                generation++; framed=true; review("Finding objects is taking too long. Keep the photo or retry.")
            }
        }
        val host=frame
        host.postDelayed(timeout,45000)
        stage.onSelection={ index ->
            keep.isEnabled=index!=null
            status.text=when { index==null -> "Nothing selected yet."; stage.subjects.size==1 -> ""; else -> "Object ${index+1} selected." }
            for(i in 0 until chips.childCount) chips.getChildAt(i).isSelected=i==index
        }
        stage.onMiss={ status.text="Nothing there. Tap inside a dashed line." }
        keep.setOnClickListener {
            val index=stage.selectedIndex ?: return@setOnClickListener
            if(page!="processing") return@setOnClickListener
            stage.lockSelection()
            page="revealing"; keep.isEnabled=false
            chipRow.visibility=View.INVISIBLE; acts.visibility=View.INVISIBLE
            heading.minHeight=heading.height; hint.minHeight=hint.height
            heading.text="Letting go."
            hint.text="A little less background."
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
                                page="review"; heading.text="Just the little thing."; hint.text="Ready for your shelf."
                                chipRow.visibility=View.GONE
                                val next=actions(primary("Use this sticker") { form() },quiet("Choose a different object") { process() },quiet("Keep the whole photo") { framed=true; review() })
                                body.removeView(acts); body.addView(next); acts=next
                                stage.announceForAccessibility("Just the little thing. Ready for your shelf.")
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
            if(subjects.isEmpty()) { framed=true; review("We couldn’t find a separate object here. Keep the photo, or try again."); return@addOnSuccessListener }
            // Tracing cut lines and cutting stickers is real work: do it off the main thread.
            worker.execute {
                val outlines=SubjectSelectionView.trace(subjects,b)
                val cut=subjects.indices.map { SubjectSelectionView.cutOut(subjects[it],outlines[it]) }
                val thumbs=cut.map { it.bitmap.fit(dp(96)) }
                runOnUiThread {
                    if(isDestroyed || token!=generation) return@runOnUiThread
                    stage.show(subjects,outlines,cut)
                    hint.text=if(subjects.size==1) "We found one thing in your photo." else "Tap the object you want to keep."
                    if(subjects.size>1) {
                        thumbs.forEachIndexed { i,thumb -> chips.addView(ChipButton(this,i+1,thumb).apply { setOnClickListener { stage.select(i) } },LinearLayout.LayoutParams(dp(76),dp(76))) }
                        chipRow.visibility=View.VISIBLE
                    }
                    if(subjects.size==1) stage.select(0) // Nothing to choose between: go straight to snipping.
                }
            }
        }.addOnFailureListener {
            if(token==generation && !isDestroyed) {
                host.removeCallbacks(timeout); framed=true
                review("Objects couldn’t be detected yet. Keep the photo, or retry once the model is ready.")
            }
        }
    }
    private fun review(message:String="") {
        screen("review"); topBar("Step 1 of 2")
        add(title(if(framed) "Still worth keeping." else "Just the little thing.",32f),top=8)
        add(body(if(framed) message.ifEmpty { "A photo works beautifully." } else "Ready for your shelf."),top=8)
        val art=StickerView(this,if(framed) 1.5f else -3f,bottom=false).apply { contentDescription=if(framed) "Your photo" else "Your object"; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES }
        body.addView(art,LinearLayout.LayoutParams(-1,dp(300)).apply { topMargin=dp(20) })
        artOf(if(framed) original else cutout,framed,dp(360),art)
        spring()
        body.addView(if(framed) actions(primary("Use this photo") { form() },quiet(if(message.isNotEmpty()) "Try the cutout again" else "Cut out an object instead") { framed=false; process() },quiet("Choose another photo") { pick() })
            else actions(primary("Use this sticker") { form() },quiet("Choose a different object") { process() },quiet("Keep the whole photo") { framed=true; review() }))
    }

    // ---------- naming it ----------
    private fun fieldBackground(error: Boolean) = StateListDrawable().apply {
        val r=dp(14f); val w=dp(1.6f)
        addState(intArrayOf(android.R.attr.state_focused),pill(Ink.SURFACE,r,if(error) Ink.ACCENT else Ink.INK,w))
        addState(intArrayOf(),pill(Ink.SURFACE,r,if(error) Ink.ACCENT else Ink.LINE,w))
    }
    private fun field(name: String, value: String, hint: String, limit: Int, multiline: Boolean): Pair<EditText,TextView> {
        val input=EditText(this).apply {
            id=View.generateViewId(); setText(value); this.hint=hint; typeface=fonts.sans; setTextSize(TypedValue.COMPLEX_UNIT_SP,17f); setTextColor(Ink.INK); setHintTextColor(Ink.INK_3)
            background=fieldBackground(false); setPadding(dp(16),dp(14),dp(16),dp(14)); filters=arrayOf(InputFilter.LengthFilter(limit))
            if(multiline) { minLines=4; gravity=Gravity.TOP or Gravity.START; setLineSpacing(0f,1.3f); inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
            else { isSingleLine=true; imeOptions=EditorInfo.IME_ACTION_NEXT; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
        }
        add(label(name).apply { labelFor=input.id },top=24); add(input,top=8)
        val error=text("",14f,fonts.sans,Ink.ACCENT_DARK).apply { visibility=View.GONE }
        add(error,top=6)
        input.onEdit { if(error.visibility==View.VISIBLE) { error.visibility=View.GONE; input.background=fieldBackground(false) } }
        return input to error
    }
    private fun complain(input: EditText, error: TextView, message: String) {
        error.text=message; error.visibility=View.VISIBLE; input.background=fieldBackground(true); input.requestFocus()
        scroll.smoothScrollTo(0,max(0,input.top-dp(120))); error.announceForAccessibility(message)
    }
    private fun form() {
        screen("form"); topBar("Step 2 of 2")
        add(title("Give it a little story.",32f),top=8)
        val art=StickerView(this,-3f,bottom=false).apply { contentDescription=if(framed) "Your photo" else "Your object"; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES }
        body.addView(art,LinearLayout.LayoutParams(-1,dp(150)).apply { topMargin=dp(16) })
        artOf(if(framed) original else cutout,framed,dp(300),art)
        val (title,titleError)=field("Name",draftTitle,"What is it?",120,multiline=false)
        val (story,_)=field("Story · optional",draftStory,"A line or two, if you feel like it.",4000,multiline=true)
        val (shelfName,shelfError)=field("Shelf",draftShelf,"Which shelf?",80,multiline=false)
        shelfName.imeOptions=EditorInfo.IME_ACTION_DONE
        val names=try { store.all().map { it.shelf }.distinct() } catch(e: Exception) { emptyList() }
        if(names.isNotEmpty()) {
            val picks=LinearLayout(this).apply { setPadding(0,dp(10),0,dp(2)) }
            names.forEach { name -> picks.addView(QuietButton(this,name).apply {
                background=RippleDrawable(ColorStateList.valueOf(Ink.alpha(Ink.INK,.08f)),pill(Ink.SURFACE,dp(22f),Ink.LINE,dp(1.2f)),null)
                contentDescription="Put it on $name"; setOnClickListener { shelfName.setText(name); shelfName.setSelection(name.length) }
            },LinearLayout.LayoutParams(-2,dp(44)).apply { marginEnd=dp(8) }) }
            body.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(picks) },LinearLayout.LayoutParams(-1,-2))
        }
        spring()
        val save=primary("Save to shelf") {}
        body.addView(actions(save))
        titleField=title; storyField=story; shelfField=shelfName
        save.setOnClickListener {
            rememberFields()
            if(draftTitle.isBlank()) { complain(title,titleError,"Give your object a name."); return@setOnClickListener }
            if(draftShelf.isBlank()) { complain(shelfName,shelfError,"Name the shelf it goes on."); return@setOnClickListener }
            val bitmap=(if(framed) original else cutout) ?: return@setOnClickListener
            if(saving)return@setOnClickListener
            saving=true; save.isEnabled=false
            worker.execute {
                try { val item=store.save(bitmap,draftTitle,draftStory,draftShelf,framed)
                    Stickers.prepare(this,store,item)
                    runOnUiThread { saving=false; if(!isDestroyed) { clearDraft(); shelf(item.id); snack("A little thing, kept.") } }
                } catch(e:Exception) { runOnUiThread { saving=false; if(!isDestroyed) { save.isEnabled=true; error("Couldn’t save your object. Free some space and try again; your draft is still here.") } } }
            }
        }
    }

    // ---------- one object ----------
    private fun details(item:Keepsake) {
        selected=item; screen("details")
        body.addView(QuietButton(this,"Your shelf",Doodle.BACK).apply { contentDescription="Back to your shelf"; setPadding(dp(8),0,dp(14),0); setOnClickListener { shelf() } },
            LinearLayout.LayoutParams(-2,dp(56)).apply { marginStart=-dp(8) })
        val art=StickerView(this,if(item.framed) 1.5f else -2f,bottom=false).apply { contentDescription=if(item.framed) "${item.title}, framed photo" else item.title; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES }
        body.addView(art,LinearLayout.LayoutParams(-1,dp(320)).apply { topMargin=dp(8) })
        load(item,art,dp(320))
        val number=store.all().indexOfFirst { it.id==item.id }+1
        add(label("No. ${number.toString().padStart(3,'0')} · ${item.shelf}"),top=24)
        add(title(item.title,36f),top=8)
        add(text("Kept on ${DateFormat.getDateInstance(DateFormat.LONG).format(Date(item.created))}",15f,fonts.sans,Ink.INK_2),top=6)
        if(item.story.isNotEmpty()) {
            body.addView(View(this).apply { setBackgroundColor(Ink.LINE) },LinearLayout.LayoutParams(-1,dp(1)).apply { topMargin=dp(20) })
            add(text(item.story,17f,fonts.sans,Ink.INK,1.45f).apply { setTextIsSelectable(true) },top=16)
        }
        spring()
        add(text("Some things are worth holding on to.",20f,fonts.hand,Ink.INK_2,1.1f).apply { gravity=Gravity.CENTER_HORIZONTAL },top=32)
    }
    override fun onStop() { selectionView?.pause(); super.onStop() }
    override fun onStart() { super.onStart(); selectionView?.resume() }
    override fun onDestroy() { generation++; selectionView?.dispose(); worker.shutdown(); segmenter.close(); super.onDestroy() }

    companion object { private const val LINE_AT=113f }
}
