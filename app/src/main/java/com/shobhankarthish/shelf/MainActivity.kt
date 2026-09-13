package com.shobhankarthish.shelf

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputFilter
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val cream = Color.rgb(246,243,236)
    private val olive = Color.rgb(72,83,66)
    private val ink = Color.rgb(41,45,40)
    private val muted = Color.rgb(105,112,96)
    private lateinit var serif: Typeface
    private lateinit var sans: Typeface
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var store: ShelfStore
    private val worker = Executors.newSingleThreadExecutor()
    private val segmenter by lazy { SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableSubjectBitmap().build()).build()) }
    private var motion: ObjectAnimator? = null
    private var selectionView: SubjectSelectionView? = null
    private var page = "shelf"
    private var step = 0
    private var original: Bitmap? = null
    private var cutout: Bitmap? = null
    private var framed = false
    private var generation = 0
    private var draftTitle = ""
    private var draftStory = ""
    private var draftShelf = "My first shelf"
    private var selected: Keepsake? = null
    private var saving = false
    private var titleField: EditText? = null
    private var storyField: EditText? = null
    private var shelfField: EditText? = null
    private val prefs by lazy { getSharedPreferences("shelf", MODE_PRIVATE) }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        serif = Typeface.createFromAsset(assets, "cormorant.ttf")
        sans = Typeface.createFromAsset(assets, "inter.ttf")
        store = ShelfStore(this)
        step = state?.getInt("step") ?: 0
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
    }
    private fun rememberFields() { titleField?.let { draftTitle=it.text.toString() }; storyField?.let { draftStory=it.text.toString() }; shelfField?.let { draftShelf=it.text.toString() } }
    override fun onSaveInstanceState(out: Bundle) {
        rememberFields()
        out.putBoolean("cutoutReady",cutout!=null)
        out.putString("page",page); out.putInt("step",step); out.putString("title",draftTitle); out.putString("story",draftStory); out.putString("shelf",draftShelf); out.putBoolean("framed",framed); out.putString("selected",selected?.id)
        super.onSaveInstanceState(out)
    }
    private fun screen(name: String) {
        motion?.cancel(); selectionView?.dispose(); selectionView=null; page=name; titleField=null; storyField=null; shelfField=null
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(cream); setPadding(dp(24),dp(20),dp(24),dp(16)) }
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dp(24),insets.systemWindowInsetTop+dp(20),dp(24),insets.systemWindowInsetBottom+dp(16)); insets
        }
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(root)
        body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isFillViewport=true; addView(body) },LinearLayout.LayoutParams(-1,0,1f))
    }
    private fun text(value:String, size:Int=14, heading:Boolean=false, center:Boolean=false) = TextView(this).apply {
        text=value; textSize=size.toFloat(); typeface=if(heading) serif else sans; setTextColor(if(heading) ink else muted)
        gravity=if(center) Gravity.CENTER else Gravity.START; setLineSpacing(dp(3).toFloat(),1f); includeFontPadding=false
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) }
    }
    private fun gap(n:Int) { body.addView(Space(this),LinearLayout.LayoutParams(1,dp(n))) }
    private fun button(label:String, primary:Boolean=true, action:()->Unit): Button = Button(this).apply {
        text=label; textSize=15f; typeface=sans; isAllCaps=false; setTextColor(if(primary) Color.WHITE else olive)
        background=GradientDrawable().apply { setColor(if(primary) olive else Color.TRANSPARENT); cornerRadius=dp(26).toFloat() }
        layoutParams=LinearLayout.LayoutParams(-1,dp(52)).apply { topMargin=dp(8) }
        stateListAnimator=null; setOnClickListener { action() }
    }
    private fun footer(label:String, caption:String="", action:()->Unit) {
        root.addView(button(label,action=action)); if(caption.isNotEmpty())root.addView(text(caption,11,center=true).apply { setPadding(0,dp(16),0,0) })
    }
    private fun back() {
        val row=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val icon=ImageButton(this).apply {
            setImageDrawable(assets.open(if(page=="details") "back.png" else "close.png").use { android.graphics.drawable.Drawable.createFromStream(it,null) })
            scaleType=ImageView.ScaleType.FIT_CENTER; setPadding(dp(12),dp(12),dp(12),dp(12)); setBackgroundColor(Color.TRANSPARENT)
            contentDescription=if(page=="details") "Back to your shelf" else "Cancel new object"; setOnClickListener { leave() }
        }
        row.addView(icon,LinearLayout.LayoutParams(dp(48),dp(48)))
        if(page=="details") row.addView(text("Your shelf",15).apply { layoutParams=LinearLayout.LayoutParams(-1,-2); setTextColor(ink) })
        body.addView(row); gap(16)
    }
    private fun leave() {
        if(saving) return
        if(page in listOf("form","review","processing","revealing")) AlertDialog.Builder(this).setMessage("Leave this object without saving?").setNegativeButton("Keep working",null).setPositiveButton("Leave") { _,_-> generation++; clearDraft(); shelf() }.show()
        else shelf()
    }
    @Deprecated("Android back compatibility") override fun onBackPressed() { if(page=="shelf") super.onBackPressed() else if(page=="onboarding" && step>0) { step--; onboarding() } else leave() }
    private fun clearDraft() { original=null; cutout=null; draftTitle=""; draftStory=""; File(cacheDir,"draft.png").delete(); File(cacheDir,"cutout.png").delete() }
    private fun finishOnboarding() { prefs.edit().putBoolean("onboarded",true).apply(); shelf() }
    private fun onboarding() {
        screen("onboarding")
        val headings=listOf("A shelf for\nthe small things.","Keep the thing.\nKeep the story.","It stays\nwith you.")
        val captions=listOf("The ticket. The shell. The thing you kept.\nGive each one a little place of its own.","Photograph it. We trim the background.\nAdd a line or two, if you feel like it.","Nothing is uploaded. Nothing is shared.\nYour shelf lives on this phone.")
        gap(38)
        val mark=ImageView(this).apply { setImageDrawable(assets.open("mark.png").use { android.graphics.drawable.Drawable.createFromStream(it,null) }); contentDescription="Shelf" }
        body.addView(mark,LinearLayout.LayoutParams(dp(32),dp(40)).apply { gravity=Gravity.CENTER; bottomMargin=dp(18) })
        body.addView(text("SHELF",11,center=true))
        val stage=FrameLayout(this)
        val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; addView(text(headings[step],54,true,true).apply { setLineSpacing(0f,0.92f) }); addView(text(captions[step],14,center=true)) }
        stage.addView(copy,FrameLayout.LayoutParams(-1,-1))
        body.addView(stage,LinearLayout.LayoutParams(-1,0,1f).apply { height=dp(360) })
        val aura=AuraView(this,step)
        root.setBackgroundColor(Color.TRANSPARENT)
        (root.parent as? ViewGroup)?.removeView(root)
        val layers=FrameLayout(this).apply { setBackgroundColor(cream); addView(aura,FrameLayout.LayoutParams(-1,-1)); addView(root,FrameLayout.LayoutParams(-1,-1)) }
        setContentView(layers)
        if(ValueAnimator.areAnimatorsEnabled()) {
            motion=ObjectAnimator.ofFloat(aura,"drift",0f,1f).apply { duration=2400; repeatMode=ValueAnimator.REVERSE; repeatCount=ValueAnimator.INFINITE; interpolator=AccelerateDecelerateInterpolator(); start() }
        }
        body.addView(text((0..2).joinToString("  "){if(it==step) "●" else "·"},18,center=true).apply { contentDescription="Welcome, page ${step+1} of 3" })
        footer(listOf("Begin","Continue","Open your shelf")[step]) { if(step<2) { step++; onboarding() } else finishOnboarding() }
        root.addView(button("Skip for now",false) { finishOnboarding() })
    }
    // ponytail: eager grid suits small collections; use RecyclerView thumbnails for hundreds of objects.
    private fun shelf() {
        screen("shelf"); body.addView(text("SHELF",11)); body.addView(text("Your little shelf.",34,true))
        val objects=try { store.all() } catch(e:Exception) { error("Your shelf could not be opened. Please restart the app."); return }
        if(objects.isEmpty()) {
            gap(70); body.addView(text("01",96,true)); body.addView(text("Every collection starts\nwith one thing.",34,true)); body.addView(text("A ticket. A shell. Something you kept. Give it a little place of its own."))
        } else {
            body.addView(text("A few things. A lot of life.")); gap(12)
            objects.groupBy { it.shelf }.forEach { (shelf,items) ->
                body.addView(text("$shelf · ${items.size} ${if(items.size==1) "object" else "objects"}",17))
                items.chunked(2).forEach { pair ->
                    val row=LinearLayout(this)
                    pair.forEach { item ->
                        val tile=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(4),0,dp(4),dp(16)); isFocusable=true; contentDescription="Open ${item.title}"; setOnClickListener { details(item) } }
                        tile.addView(photo(BitmapFactory.decodeFile(store.image(item.id).path),item.framed,140))
                        tile.addView(text(item.title,13,center=true).apply { setTextColor(ink) })
                        row.addView(tile,LinearLayout.LayoutParams(0,-2,1f))
                    }
                    if(pair.size==1)row.addView(Space(this),LinearLayout.LayoutParams(0,1,1f))
                    body.addView(row)
                }
            }
        }
        footer("Add an object",if(objects.isEmpty()) "A photo is enough. The story can come later." else "Yours to keep. No need to keep up.") { pick() }
    }
    private fun pick() {
        val intent=if(android.os.Build.VERSION.SDK_INT>=33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*") else Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(intent,42) } catch(e:Exception) { error("No photo picker is available on this device.") }
    }
    @Deprecated("Activity result compatibility") override fun onActivityResult(request:Int,result:Int,data:Intent?) {
        super.onActivityResult(request,result,data)
        if(request!=42 || result!=RESULT_OK) return
        val uri=data?.data ?: return
        generation++; val token=generation
        screen("processing"); back(); body.addView(text("Finding your object.",34,true)); body.addView(text("Opening your photo…"))
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
        screen("processing"); back()
        val heading=text("What would you like to keep?",34,true)
        body.addView(heading)
        val hint=text("Finding the objects in your photo…",14)
        body.addView(hint)
        val stage=SubjectSelectionView(this,b).apply { layoutParams=LinearLayout.LayoutParams(-1,dp(340)) }
        selectionView=stage; body.addView(stage)
        val status=text("Your photo stays whole until you choose.",14,center=true)
        body.addView(status)
        val choices=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        body.addView(choices)
        val keep=button("Keep this") {}.apply { isEnabled=false; alpha=.45f }
        root.addView(keep)
        root.addView(button("Keep a framed photo instead",false) { generation++; framed=true; review() })
        val token=++generation
        val timeout=Runnable {
            if(token==generation && page=="processing") {
                generation++; framed=true; review("Finding objects is taking too long. Keep the photo or retry.")
            }
        }
        val host=root
        host.postDelayed(timeout,45000)
        stage.onSelection={ index ->
            keep.isEnabled=index!=null; keep.alpha=if(index==null) .45f else 1f
            status.text=if(index==null) "No object here. Try holding another part of the photo." else "Object ${index+1} selected. Keep it, or hold another object."
            for(i in 0 until choices.childCount) choices.getChildAt(i).isSelected=i==index
            stage.announceForAccessibility(status.text)
        }
        keep.setOnClickListener {
            if(page=="review") { form(); return@setOnClickListener }
            val index=stage.selectedIndex ?: return@setOnClickListener
            if(page!="processing") return@setOnClickListener
            stage.lockSelection()
            page="revealing"; keep.isEnabled=false; keep.alpha=.45f
            choices.visibility=View.GONE
            heading.minHeight=heading.height; hint.minHeight=hint.height
            heading.text="Letting go."
            hint.text="A little less background."
            status.text="Preparing your chosen object…"
            val subject=stage.subjects[index]
            worker.execute {
                try {
                    val chosen=subject.export()
                    val file=android.util.AtomicFile(File(cacheDir,"cutout.png"))
                    val stream=file.startWrite()
                    try { check(chosen.compress(Bitmap.CompressFormat.PNG,100,stream)); file.finishWrite(stream) }
                    catch(e:Exception) { file.failWrite(stream); throw e }
                    runOnUiThread {
                        if(token!=generation || isDestroyed) return@runOnUiThread
                        cutout=chosen
                        status.text="And slowly, let go."
                        stage.reveal {
                            if(token==generation && !isDestroyed) {
                                page="review"; heading.text="Just the little thing."; status.text="Ready for your shelf."
                                hint.text="Just the little thing."
                                keep.text="Use this cutout"; keep.isEnabled=true; keep.alpha=1f
                                root.addView(button("Choose a different object",false) { process() })
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
            stage.subjects=subjects
            hint.text="Press and hold the object you want to keep."
            status.text="Or select an object below."
            subjects.forEachIndexed { index,subject ->
                choices.addView(button("Select object ${index+1}",false) { stage.select(index) }.apply {
                    val thumbnail=android.graphics.drawable.BitmapDrawable(resources,subject.bitmap)
                    thumbnail.setBounds(0,0,dp(44),dp(44)); setCompoundDrawables(thumbnail,null,null,null)
                    contentDescription="Select object ${index+1}, shown in the preview"
                })
            }
        }.addOnFailureListener {
            if(token==generation && !isDestroyed) {
                host.removeCallbacks(timeout); framed=true
                review("Objects couldn’t be detected yet. Keep the photo, or retry once the model is ready.")
            }
        }
    }
    @android.annotation.SuppressLint("AppCompatCustomView") // Native Activity/theme; no AppCompat tinting is used.
    private fun photo(bitmap:Bitmap?, frame:Boolean, height:Int): View {
        val holder=FrameLayout(this).apply { layoutParams=LinearLayout.LayoutParams(-1,dp(height)).apply { bottomMargin=dp(16) } }
        if(bitmap==null) { holder.addView(text("Photo unavailable",14,center=true)); return holder }
        val image=object : ImageView(this) {
            private val shadowPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { setShadowLayer(dp(8).toFloat(),0f,dp(6).toFloat(),0x35292D28) }
            override fun onDraw(canvas:Canvas) {
                if(frame) { super.onDraw(canvas); return }
                val scale=minOf((width-dp(32)).toFloat()/bitmap.width,(this.height-dp(32)).toFloat()/bitmap.height)
                val w=bitmap.width*scale; val h=bitmap.height*scale
                canvas.drawBitmap(bitmap,null,RectF((width-w)/2,(this.height-h)/2,(width+w)/2,(this.height+h)/2),shadowPaint)
            }
        }.apply { if(!frame)setLayerType(View.LAYER_TYPE_SOFTWARE,null); setImageBitmap(bitmap); scaleType=ImageView.ScaleType.FIT_CENTER; contentDescription=if(frame) "Framed original photo" else "Object photo"; setPadding(dp(if(frame) 14 else 16),dp(12),dp(if(frame) 14 else 16),dp(12)) }
        if(frame) { image.background=GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1),0xFFD8D4C9.toInt()); cornerRadius=dp(3).toFloat() }; image.elevation=dp(3).toFloat() }
        holder.addView(image,FrameLayout.LayoutParams(-1,-1).apply { setMargins(dp(8),dp(6),dp(8),dp(8)) })
        return holder
    }
    private fun review(message:String="") {
        screen("review"); back(); body.addView(text(if(framed) "Still worth keeping." else "Just the little thing.",34,true)); gap(40)
        body.addView(photo(if(framed)original else cutout,framed,260)); body.addView(text(if(framed) "A photo works beautifully." else "Ready for your shelf.",28,true,true))
        if(framed) { body.addView(text(message.ifEmpty { "We couldn’t separate this object. Keep the full photo, or try again once the model is ready." },14,center=true)); body.addView(button("Retry background removal",false) { framed=false; process() }) }
        else {
            body.addView(button("Choose a different object",false) { process() })
            body.addView(button("Keep original photo instead",false) { framed=true; review("Your original photo, ready to keep.") })
        }
        body.addView(button("Choose another photo",false) { pick() })
        footer(if(framed) "Use this photo" else "Use this cutout","Next, give your object a name.") { form() }
    }
    private fun field(label:String,value:String,limit:Int,multiline:Boolean=false):EditText {
        body.addView(text(label,12))
        return EditText(this).apply {
            setText(value); textSize=15f; typeface=sans; setTextColor(ink); setPadding(dp(16),dp(12),dp(16),dp(12)); setSingleLine(!multiline)
            if(multiline) { minLines=3; gravity=Gravity.TOP; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }
            filters=arrayOf(InputFilter.LengthFilter(limit)); contentDescription=label
            background=GradientDrawable().apply { setColor(0xFFEDEAE2.toInt()); cornerRadius=dp(10).toFloat() }
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(18) }; body.addView(this)
        }
    }
    private fun form() {
        screen("form"); back(); body.addView(text("Give it a little story.",34,true)); body.addView(photo(if(framed)original else cutout,framed,160))
        titleField=field("Object title",draftTitle,120); storyField=field("Story (optional)",draftStory,4000,true); shelfField=field("Shelf",draftShelf,80)
        footer("Save to shelf","You can leave the story empty.") {
            rememberFields()
            if(draftTitle.isBlank()) { titleField?.error="Give your object a name"; titleField?.requestFocus(); return@footer }
            if(draftShelf.isBlank()) { shelfField?.error="Name your shelf"; return@footer }
            val bitmap=(if(framed) original else cutout) ?: return@footer
            if(saving)return@footer
            saving=true; root.getChildAt(1).isEnabled=false
            worker.execute {
                try { store.save(bitmap,draftTitle,draftStory,draftShelf,framed)
                    runOnUiThread { saving=false; if(!isDestroyed) { clearDraft(); shelf(); Toast.makeText(this,"A little thing, kept.",Toast.LENGTH_SHORT).show() } }
                } catch(e:Exception) { runOnUiThread { saving=false; if(!isDestroyed) { root.getChildAt(1).isEnabled=true; error("Couldn’t save your object. Free some space and try again; your draft is still here.") } } }
            }
        }
    }
    private fun details(item:Keepsake) {
        selected=item; screen("details"); back(); gap(20); body.addView(photo(BitmapFactory.decodeFile(store.image(item.id).path),item.framed,300)); gap(12)
        val number=store.all().indexOfFirst { it.id==item.id }+1
        body.addView(text("OBJECT ${number.toString().padStart(3,'0')} · ${item.shelf.uppercase()}",11)); body.addView(text(item.title,38,true)); body.addView(text(DateFormat.getDateInstance(DateFormat.LONG).format(Date(item.created))))
        body.addView(View(this).apply { setBackgroundColor(0xFFD8D4C9.toInt()) },LinearLayout.LayoutParams(-1,dp(1))); gap(16)
        if(item.story.isNotEmpty()) body.addView(text(item.story,16).apply { setTextColor(ink) })
        root.addView(text("Kept in ${item.shelf}",11)); root.addView(text("Some things are worth holding on to.",18,true))
    }
    private fun error(message:String) { AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK",null).show() }
    override fun onStop() { selectionView?.pause(); motion?.pause(); super.onStop() }
    override fun onStart() { super.onStart(); selectionView?.resume(); motion?.resume() }
    override fun onDestroy() { generation++; selectionView?.dispose(); motion?.cancel(); worker.shutdown(); segmenter.close(); super.onDestroy() }
}
