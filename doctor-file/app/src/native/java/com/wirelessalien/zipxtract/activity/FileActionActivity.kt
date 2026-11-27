package com.wirelessalien.zipxtract.activity

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.wirelessalien.zipxtract.App
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.constant.BroadcastConstants
import com.wirelessalien.zipxtract.constant.ServiceConstants as S
import com.wirelessalien.zipxtract.helper.AppEvent
import com.wirelessalien.zipxtract.helper.ArchiveSafety
import com.wirelessalien.zipxtract.helper.EventBus
import com.wirelessalien.zipxtract.helper.FileOperationsDao
import com.wirelessalien.zipxtract.lan.*
import com.wirelessalien.zipxtract.nativeui.*
import com.wirelessalien.zipxtract.service.NativeFileService
import com.wirelessalien.zipxtract.service.SafeArchiveService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import java.io.File
import java.util.Locale

/** The browser and full-screen search run the same file-operation dialogs and services. */
abstract class FileActionActivity : Activity() {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    protected val colors by lazy { Palette(this) }
    protected val openDialogs = mutableSetOf<Dialog>()
    protected val popups by lazy { NativePopups(this) }
    protected val bookmarkKey = "directory_bookmarks"

    protected abstract fun storageRoots(): List<File>
    protected abstract fun onFileOperationStarted()

    override fun onDestroy() {
        popups.dismiss()
        openDialogs.toList().forEach { if (it is MotionDialog) it.dispose() else it.dismiss() }
        scope.cancel()
        super.onDestroy()
    }

    protected fun fileOperation(action: String, start: File, paths: List<String>) {
        if (paths.isNotEmpty()) folderPicker(start) { startFileJob(action, paths, it) }
    }

    protected fun confirmDelete(paths: List<String>, folder: File) {
        if (paths.isEmpty()) return
        messagePanel("删除 ${paths.size} 个项目？", "此操作会永久删除所选文件，无法撤销。", "删除", true) {
            startFileJob("delete", paths, folder)
        }
    }

    protected fun addPhotoTimeSyncAction(actions: MutableList<Pair<String, () -> Unit>>, files: List<File>) {
        if (files.none { MediaFacts.isExifPhotoCandidate(it) && !it.isDirectory }) return
        actions += PhotoTimeSync.TITLE to {
            messagePanel(
                PhotoTimeSync.TITLE,
                "用每张照片的 EXIF 拍摄时间更新它的文件修改时间，保留照片内容和 EXIF。\n\n" +
                    "缺少时区时使用手机当前时区。非照片和没有有效拍摄时间的文件会跳过。",
                "同步",
                false
            ) {
                startFileJob(PhotoTimeSync.ACTION, files.map { it.path })
            }
        }
    }

    private val lan by lazy { (application as App).lan }
    private var lanUiResumed = false
    private var foldDetected = false
    private var incomingDialog: MotionDialog? = null
    private var displayedOffer: LanReceiver.Offer? = null
    private var deviceChooser: MotionDialog? = null

    protected fun releaseMigration(file: File) {
        lan.release { BookMigration.discard(file) }
    }

    protected fun transferPanel(): View = LanTransferPanel(this, colors, lan::cancelSend, lan::dismiss).also { panel ->
        scope.launch {
            combine(lan.sending, lan.receiving, ::Pair).collect { (sending, receiving) ->
                panel.render(sending, receiving)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lan.enter(LanDevice.current(this, foldDetected))
    }

    override fun onStop() {
        lan.leave()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        lanUiResumed = true
        lan.update(LanDevice.current(this, foldDetected))
        showIncoming(lan.pending.value)
    }

    override fun onPause() {
        lanUiResumed = false
        incomingDialog?.dispose()
        incomingDialog = null
        displayedOffer = null
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scope.launch { lan.pending.collect { if (lanUiResumed) showIncoming(it) } }
        scope.launch {
            WindowInfoTracker.getOrCreate(this@FileActionActivity).windowLayoutInfo(this@FileActionActivity).collect { info ->
                if (!foldDetected && info.displayFeatures.any { it is FoldingFeature }) {
                    foldDetected = true
                    if (lanUiResumed) lan.update(LanDevice.current(this@FileActionActivity, hasFold = true))
                }
            }
        }
    }

    private fun lanForm(page: DialogPage): LinearLayout {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(8), dp(22), dp(22))
        }
        val width = if (resources.configuration.screenWidthDp > 860) dp(860) else -1
        page.body.addView(ScrollView(this).apply {
            isFillViewport = true
            val frame = FrameLayout(this@FileActionActivity)
            frame.addView(content, FrameLayout.LayoutParams(width, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addView(frame, FrameLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -1))
        return content
    }

    private fun LinearLayout.lanRow(view: View) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    protected fun showLanSend(files: List<File>, onRelease: (() -> Unit)? = null) {
        if (isDestroyed || isFinishing) {
            lan.release(onRelease)
            return
        }
        if (files.isEmpty()) {
            lan.release(onRelease)
            errorDialog("局域网传输", "请选择文件或文件夹。")
            return
        }
        if (lan.sending.value?.active == true) {
            lan.release(onRelease)
            notice("已有发送任务正在进行")
            return
        }
        if (deviceChooser != null) {
            lan.release(onRelease)
            notice("正在选择接收设备")
            return
        }
        val page = fullDialog("选择接收设备")
        deviceChooser = page.dialog
        val content = lanForm(page)
        var discovery: LanDiscovery? = null
        var dismissed = false
        var chosen = false
        var pendingRelease = onRelease
        fun render(peers: List<LanPeer>) {
            if (dismissed || chosen) return
            content.removeAllViews()
            if (peers.isEmpty()) {
                content.lanRow(emptyState("正在搜索接收设备", "让接收设备保持打开 FileDoctorC", "transfer"))
                return
            }
            val columns = (minOf(resources.configuration.screenWidthDp, 860) / 154).coerceIn(1, 5)
            peers.chunked(columns).forEach { group ->
                val row = LinearLayout(this)
                group.forEach { peer ->
                    val button = lanDeviceButton(this, peer.device, peer.route.link) {
                        if (!chosen) {
                            chosen = true
                            val started = lan.send(files, peer, pendingRelease)
                            if (started) pendingRelease = null
                            page.dialog.close { if (!started) notice("已有发送任务正在进行") }
                        }
                    }
                    row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        setMargins(dp(4), 0, dp(4), 0)
                    })
                }
                repeat(columns - group.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
                content.lanRow(row)
            }
        }
        render(emptyList())
        page.dialog.setOnDismissListener {
            dismissed = true
            discovery?.close()
            if (deviceChooser === page.dialog) deviceChooser = null
            lan.release(pendingRelease)
            pendingRelease = null
            openDialogs.remove(page.dialog)
        }
        sizeDialog(page)
        page.dialog.show()
        scope.launch {
            try {
                val own = lan.ownFingerprint()
                if (!dismissed) discovery = LanDiscovery(this@FileActionActivity, own) { peers ->
                    runOnUiThread { render(peers) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!dismissed) {
                    content.removeAllViews()
                    content.lanRow(emptyState("暂时无法发现设备", "请检查 Wi‑Fi 或有线网络", "transfer"))
                }
            }
        }
    }

    private fun showIncoming(offer: LanReceiver.Offer?) {
        if (displayedOffer === offer) return
        incomingDialog?.dispose()
        incomingDialog = null
        displayedOffer = offer
        if (offer == null || offer.decision.isDone || isFinishing || isDestroyed) return
        val prefs = getSharedPreferences("native_ui", MODE_PRIVATE)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val roots = storageRoots()
        var folder = prefs.getString("lan_receive_folder", null)?.let(::File)?.takeIf { saved ->
            saved.isDirectory && roots.any { saved == it || saved.path.startsWith(it.path + "/") }
        } ?: downloads.takeIf { it.isDirectory } ?: roots.first()
        val page = fullDialog("接收 ${offer.description}")
        val content = lanForm(page)
        incomingDialog = page.dialog
        content.lanRow(label("${offer.alias} · ${offer.peer}", 16f, colors.text, true))
        val summary = offer.preview.joinToString("\n") { "${it.name} · ${it.size?.let(::size) ?: "文件夹"}" }
        val more = (offer.rootCount - offer.preview.size).takeIf { it > 0 }?.let { "\n另有 $it 个根项目" }.orEmpty()
        content.lanRow(label(summary + more, 15f, colors.secondary))
        content.lanRow(pathRow("保存到", folder) { folder = it })
        val actions = LinearLayout(this)
        val reject = primary("拒绝") { page.dialog.close { offer.decision.complete(null) } }
        val accept = primary("保存到此处并接收") {
            val target = runCatching { folder.canonicalFile }.getOrNull()
            if (target == null || !target.isDirectory || !target.canWrite()) {
                notice("请选择可写的保存目录")
            } else {
                page.dialog.close {
                    if (lan.accept(offer, target)) {
                        prefs.edit().putString("lan_receive_folder", folder.absolutePath).apply()
                    }
                }
            }
        }
        actions.addView(reject, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(8) })
        actions.addView(accept, LinearLayout.LayoutParams(0, dp(52), 2f))
        content.lanRow(actions)
        page.dialog.onBackAction = {
            page.dialog.close { offer.decision.complete(null) }
            true
        }
        page.dialog.setOnDismissListener { openDialogs.remove(page.dialog) }
        sizeDialog(page)
        page.dialog.show()
    }

    private fun startFileJob(action: String, paths: List<String>, destination: File? = null) {
        if (ActiveOperation.busy()) {
            notice("请等待当前任务完成")
            return
        }
        val job = FileOperationsDao(this).addFilesForJob(paths)
        ActiveOperation.start(when (action) {
            "move" -> "移动文件"
            "delete" -> "删除文件"
            PhotoTimeSync.ACTION -> PhotoTimeSync.TITLE
            else -> "复制文件"
        }, "${paths.size} 个项目")
        ContextCompat.startForegroundService(this, Intent(this, NativeFileService::class.java)
            .putExtra("job", job).putExtra("operation", action).putExtra("destination", destination?.path))
        onFileOperationStarted()
    }

    protected fun showInfoPanel(file: File, onLocation: () -> Unit) {
        val dialog = FileInfoPanel.show(this, file, fileArt(file), if (file.isDirectory) "文件夹" else size(file.length()), onLocation)
        openDialogs.add(dialog)
        dialog.setOnDismissListener { openDialogs.remove(dialog) }
    }

    protected fun newFolder(parent:File,done:()->Unit){val field=edit("文件夹名称");nativePrompt("新建文件夹",field,"创建"){runCatching{FileManagerKernel.createFolder(parent,field.text.toString())}.onSuccess{done()}.onFailure{notice(it.message?:"创建失败")}}}

    protected fun cancelTask(){sendBroadcast(Intent(BroadcastConstants.ACTION_CANCEL_OPERATION).setPackage(packageName));notice("正在取消，剩余源文件保留")}
    protected fun fileArt(f:File,directory:Boolean=f.isDirectory)=when{directory->"folder";CategoryCatalog.matches("压缩包",f.name)->"archive";CategoryCatalog.matches("图片",f.name)->"image";CategoryCatalog.matches("视频",f.name)->"video";CategoryCatalog.matches("音乐",f.name)->"music";CategoryCatalog.matches("安装包",f.name)->"apk";else->"document"}
    protected fun size(bytes:Long):String{if(bytes<1024)return "$bytes B";val units=arrayOf("KB","MB","GB","TB");var n=bytes.toDouble()/1024;var i=0;while(n>=1024&&i<units.lastIndex){n/=1024;i++};return String.format(Locale.getDefault(),"%.2f %s",n,units[i])}
    protected fun shareFiles(files:List<File>){scope.launch{
        try{
            val handoff=FileHandoff(this@FileActionActivity)
            fun launchShare(outgoing:Intent){startActivity(Intent.createChooser(outgoing,"分享文件").apply{
                clipData=outgoing.clipData;addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })}
            val outgoing=withContext(Dispatchers.IO){handoff.shareIntent(files)}?:return@launch
            try{launchShare(outgoing)}catch(_:SecurityException){
                withContext(Dispatchers.IO){handoff.shareIntent(files,preferMediaStore=false)}?.let{launchShare(it)}
            }
        }catch(e:Exception){notice(e.message?:"无法分享")}
    }}
    protected fun renameFiles(files: List<File>, source: View?){
        if(ActiveOperation.busy()){notice("请等待当前任务完成");return}
        BatchRenameDialog.show(this,files,source){result->
            // Keep saved paths coherent, including descendants of renamed folders.
            val moves=result.locations.mapNotNull{location->location.current?.takeIf{it.path!=location.source.path}?.let{location.source.path to it.path}}
            if(moves.isNotEmpty()){
                val prefs=getSharedPreferences("native_ui",MODE_PRIVATE)
                val editor=prefs.edit()
                for(key in listOf("favorites","recent",bookmarkKey)){
                    val paths=prefs.getStringSet(key,emptySet()).orEmpty().map{path->
                        val move=moves.firstOrNull{path==it.first||path.startsWith(it.first+"/")}
                        if(move==null)path else move.second+path.removePrefix(move.first)
                    }.toSet()
                    editor.putStringSet(key,paths)
                }
                editor.apply()
            }
            // The transaction may finish after this Activity was destroyed. Persisted references
            // above remain valid; only live EventBus collectors are allowed to refresh their UI.
            EventBus.emit(AppEvent.RenameComplete(result.success||moves.isNotEmpty()))
        }
    }

    protected fun notice(message:String){Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
    protected fun errorDialog(title:String,message:String){if(!isFinishing&&!isDestroyed)messagePanel(title,message,"好",false){}}
    protected fun menu(title:String,items:List<String>,checked:Int=-1,action:(Int)->Unit){popups.show(title,items,checked,action)}
    protected fun edit(hint:String)=EditText(this).apply{setSingleLine(true);textSize=16f;setTextColor(colors.text);setHintTextColor(colors.secondary);this.hint=hint;setPadding(dp(16),dp(12),dp(16),dp(12));background=colors.background(colors.surface,dp(15).toFloat())}
    protected fun nativePrompt(title:String,field:EditText,button:String,action:()->Unit){roundedPanel(title,field,button,false,true,action)}
    protected fun messagePanel(title:String,message:String,button:String,danger:Boolean,action:()->Unit){
        val text=label(message,15f,colors.text).apply{setLineSpacing(dp(5).toFloat(),1f)}
        roundedPanel(title,text,button,danger,button!="好",action)
    }
    private fun roundedPanel(title:String,body:View,button:String,danger:Boolean,cancel:Boolean,action:()->Unit){
        val d=MotionDialog(this,R.style.NativeTheme);openDialogs.add(d);d.setOnDismissListener{openDialogs.remove(d)}
        val width=minOf(resources.displayMetrics.widthPixels-dp(40),dp(380))
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(16));background=colors.background(colors.page,dp(28).toFloat());clipToOutline=true}
        box.addView(label(title,21f,colors.text,true).apply{maxLines=3;ellipsize=TextUtils.TruncateAt.MIDDLE},LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(20)})
        body.measure(View.MeasureSpec.makeMeasureSpec(width-dp(48),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val scroll=ScrollView(this).apply{isVerticalScrollBarEnabled=false;addView(body,FrameLayout.LayoutParams(-1,-2))}
        box.addView(scroll,LinearLayout.LayoutParams(-1,minOf(body.measuredHeight,resources.displayMetrics.heightPixels/2)))
        val buttons=LinearLayout(this).apply{gravity=Gravity.END or Gravity.CENTER_VERTICAL}
        if (cancel) buttons.addView(label("取消", 15f, colors.text, true).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            background = colors.pressable(colors.surface, dp(14).toFloat())
            setOnClickListener { d.close {} }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        val alert = if (colors.dark) 0xffffb7b0.toInt() else 0xffae2e26.toInt()
        buttons.addView(label(button, 15f, if (danger) alert else colors.onAccent, true).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            background = colors.pressable(if (danger) colors.surface else colors.blue, dp(14).toFloat())
            setOnClickListener { d.close(action) }
        }, LinearLayout.LayoutParams(if (cancel) 0 else dp(92), dp(48), if (cancel) 1f else 0f).apply {
            if (cancel) marginStart = dp(8)
        })
        box.addView(buttons,LinearLayout.LayoutParams(-1,dp(56)).apply{topMargin=dp(18)})
        d.showPanel(box,width,dimAmount=.12f)
    }
    protected fun primary(text:String,click:()->Unit)=label(text,16f,colors.onAccent,true).apply{gravity=Gravity.CENTER;background=colors.pressable(colors.blue,dp(16).toFloat());setOnClickListener{click()};minimumHeight=dp(52);isClickable=true;isFocusable=true}
    protected fun emptyState(title: String, description: String, icon: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(28), dp(24), dp(28))
        addView(ImageView(context).apply {
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setImageDrawable(Art.drawable(context, icon))
            background = colors.background(colors.selected, dp(19).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(label(title, 16f, colors.text, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (description.isNotBlank()) addView(label(description, 13f, colors.secondary).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
    protected fun section(parent:LinearLayout,text:String){parent.addView(label(text,14f,colors.secondary),LinearLayout.LayoutParams(-1,dp(48)).apply{topMargin=dp(8)})}
    protected data class DialogPage(val dialog:MotionDialog,val body:LinearLayout,val outer:LinearLayout)
    protected fun fullDialog(title:String):DialogPage{
        val d=MotionDialog(this,R.style.NativeTheme,true)
        openDialogs.add(d);d.setOnDismissListener{openDialogs.remove(d)}
        val outer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(colors.page)}
        val head=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),0,dp(18),0)}
        head.addView(iconButton("back","返回"){d.onBackPressed()},LinearLayout.LayoutParams(dp(48),dp(56)))
        head.addView(label(title,20f,colors.text,true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0,dp(60),1f).apply { marginStart = dp(6) })
        outer.addView(head);val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};outer.addView(body,LinearLayout.LayoutParams(-1,0,1f));d.motionContent=outer;d.setContentView(outer)
        return DialogPage(d,body,outer)
    }
    protected fun sizeDialog(d:DialogPage){d.dialog.window?.apply{setLayout(-1,-1);setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));setDimAmount(0f);setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);WindowCompat.setDecorFitsSystemWindows(this,false);WindowCompat.getInsetsController(this,decorView).apply{isAppearanceLightStatusBars=!colors.dark;isAppearanceLightNavigationBars=!colors.dark}}
        ViewCompat.setOnApplyWindowInsetsListener(d.outer){v,insets->if(!d.dialog.layoutFrozen){val a=insets.getInsets(WindowInsetsCompat.Type.systemBars());val ime=insets.getInsets(WindowInsetsCompat.Type.ime());v.setPadding(a.left,a.top,a.right,maxOf(a.bottom,ime.bottom))};insets};ViewCompat.requestApplyInsets(d.outer)
    }
    protected fun pathRow(title:String,initial:File,changed:(File)->Unit):View{
        var current=initial
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(14),dp(8),dp(14));background=colors.background(colors.surface,dp(18).toFloat())}
        val words=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};if(title.isNotBlank())words.addView(label(title,15f,colors.text,true))
        val value=label(displayPath(current),15f,colors.secondary).apply{id=R.id.archive_path;maxLines=1;ellipsize=TextUtils.TruncateAt.MIDDLE};words.addView(value,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        row.addView(words,LinearLayout.LayoutParams(0,-2,1f));row.addView(iconButton("chevron","选择其他路径"){row.performClick()},LinearLayout.LayoutParams(dp(44),dp(44)))
        row.setOnClickListener{folderPicker(current){current=it;value.text=displayPath(it);changed(it)}};return row
    }
    protected fun displayPath(file:File):String{val root=Environment.getExternalStorageDirectory().path;val path=AndroidFolderAccess.displayFile(file).path;return if(path.startsWith(root))"内部存储"+path.removePrefix(root)else path}
    private fun folderPicker(start:File,chosen:(File)->Unit){
        var current=if(start.isDirectory)start else Environment.getExternalStorageDirectory()
        val dialog=fullDialog("选择保存位置")
        val path=label(displayPath(current),13f,colors.secondary).apply{id=R.id.picker_path;setPadding(dp(22),dp(10),dp(22),dp(10));maxLines=2}
        dialog.body.addView(path,LinearLayout.LayoutParams(-1,-2))
        val buttons=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),0,dp(16),0)}
        val up=label("上一级",14f,colors.blue).apply{gravity=Gravity.CENTER;setPadding(dp(10),0,dp(10),0)}
        val storage=label("存储位置",14f,colors.blue).apply{gravity=Gravity.CENTER;setPadding(dp(10),0,dp(10),0)}
        val create=label("新建文件夹",14f,colors.blue).apply{gravity=Gravity.CENTER;setPadding(dp(10),0,dp(10),0)}
        buttons.addView(up,LinearLayout.LayoutParams(0,dp(48),1f));buttons.addView(storage,LinearLayout.LayoutParams(0,dp(48),1f));buttons.addView(create,LinearLayout.LayoutParams(0,dp(48),1f));dialog.body.addView(buttons)
        val list=ListView(this).apply{divider=null;setPadding(dp(12),0,dp(12),0)};dialog.body.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        var folders=listOf<File>()
        fun refresh(){if(list.isAttachedToWindow)UiMotion.enter(list,true);path.text=displayPath(current);folders=current.listFiles()?.filter{it.isDirectory&&!ArchiveSafety.isLink(it)}?.sortedBy{it.name.lowercase()}?:emptyList();list.adapter=object:BaseAdapter(){override fun getCount()=folders.size;override fun getItem(i:Int)=folders[i];override fun getItemId(i:Int)=i.toLong();override fun getView(i:Int,v:View?,p:ViewGroup):View=LinearLayout(this@FileActionActivity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(15),dp(12),dp(15),dp(12));minimumHeight=dp(68);addView(ImageView(context).apply{setImageDrawable(Art.drawable(context,"folder"))},LinearLayout.LayoutParams(dp(40),dp(40)));addView(label(folders[i].name,17f).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(14)})}}}
        fun goUp():Boolean{val parent=current.parentFile?.takeIf{f->storageRoots().any{f.path==it.path||f.path.startsWith(it.path+"/")}}?:return false;current=parent;refresh();return true};up.setOnClickListener{goUp()};dialog.dialog.onBackAction={goUp()}
        storage.setOnClickListener{it.menuAnchor();val roots=storageRoots();menu("存储位置",roots.mapIndexed{i,f->if(i==0)"内部存储"else"存储卡 ${f.name}"}){current=roots[it];refresh()}}
        create.setOnClickListener{newFolder(current){refresh()}}
        list.setOnItemClickListener{_,_,position,_->current=folders[position];refresh()}
        dialog.body.addView(primary("选择此文件夹"){chosen(current);dialog.dialog.dismiss()}.apply{id=R.id.picker_choose},LinearLayout.LayoutParams(-1,dp(52)).apply{setMargins(dp(22),dp(12),dp(22),dp(20))})
        refresh();sizeDialog(dialog);dialog.dialog.show()
    }

    protected fun createArchive(paths:List<String>){
        if(paths.isEmpty())return
        if(ActiveOperation.busy()){notice("请等待当前任务完成");return}
        val importedSources=paths.any{it.startsWith(File(cacheDir,"imports").path+"/")}
        val saved=CreationOptions()
        var format=saved.format
        val source=CreationOptions.sourceDirectory(paths)
        var output=if(importedSources)Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)else source
        val dialog=fullDialog("创建压缩包")
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(2),dp(22),dp(20))}
        val name=edit("压缩包名称").apply{id=R.id.archive_name;setText(if(paths.size==1)File(paths.first()).name else "文件归档");selectAll()}
        section(content,"压缩包名称");content.addView(name,LinearLayout.LayoutParams(-1,dp(56)))
        section(content,"压缩格式")
        val formats=LinearLayout(this).apply{background=colors.background(colors.surface,dp(16).toFloat());setPadding(dp(4),dp(4),dp(4),dp(4))}
        val formatButtons=mutableListOf<TextView>()
        val tarType=label("",14f,colors.blue).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(8),dp(6),dp(8),dp(6))}
        content.addView(formats,LinearLayout.LayoutParams(-1,dp(52)));content.addView(tarType,LinearLayout.LayoutParams(-1,dp(40)))
        section(content,"保存位置")
        content.addView(pathRow("",output){output=it})
        section(content,"其他选项")
        fun toggleRow(title:String,idValue:Int,initial:Boolean):Pair<LinearLayout,NativeToggle>{
            val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(3),0,dp(3))}
            row.addView(label(title,16f),LinearLayout.LayoutParams(0,-2,1f));val toggle=NativeToggle(this).apply{id=idValue;isChecked=initial;accessible(title)};row.addView(toggle,LinearLayout.LayoutParams(dp(60),dp(54)));content.addView(row);return row to toggle
        }
        val passwordToggle=toggleRow("设置密码",R.id.archive_password_toggle,saved.passwordEnabled).second
        val passwordRow=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;visibility=if(saved.passwordEnabled)View.VISIBLE else View.GONE}
        val password=edit("输入密码").apply{id=R.id.archive_password;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;setText(saved.password)}
        passwordRow.addView(password,LinearLayout.LayoutParams(0,dp(54),1f));var reveal=false
        passwordRow.addView(iconButton("eye","显示或隐藏密码"){reveal=!reveal;password.inputType=InputType.TYPE_CLASS_TEXT or if(reveal)InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD;password.setSelection(password.length())},LinearLayout.LayoutParams(dp(48),dp(48)))
        content.addView(passwordRow);passwordToggle.setOnCheckedChangeListener{_,checked->passwordRow.visibility=if(checked)View.VISIBLE else View.GONE}
        val splitPair=toggleRow("分卷（ZIP）",R.id.archive_split_toggle,saved.split)
        val splitValue=edit("分卷大小，MB").apply{id=R.id.archive_split_mb;inputType=InputType.TYPE_CLASS_NUMBER;setText(saved.splitMb.toString())}
        splitValue.setCompoundDrawablesWithIntrinsicBounds(null,null,SuffixDrawable(this,"MB"),null)
        content.addView(splitValue,LinearLayout.LayoutParams(-1,dp(54)))
        splitPair.second.setOnCheckedChangeListener{_,checked->splitValue.visibility=if(format=="ZIP"&&checked)View.VISIBLE else View.GONE}
        val delete=toggleRow("校验后删除源文件",R.id.archive_delete,saved.delete).second
        var startButton:TextView?=null
        fun deletionText(){startButton?.text=if(delete.isChecked)"压缩并删除源文件"else"开始压缩"}
        delete.setOnCheckedChangeListener{_,_->deletionText()}
        if(importedSources){delete.isEnabled=false;delete.isChecked=false}
        fun animateOptions(){/* Form visibility is atomic; there is no retained layout-transition overlay. */}
        fun updateFormat(){
            animateOptions()
            formatButtons.forEachIndexed{i,b->val active=when(i){0->format=="7Z";1->format=="ZIP";else->format !in listOf("7Z","ZIP")};b.background=if(active)colors.background(colors.page,dp(12).toFloat())else null;b.setTextColor(if(active)colors.blue else colors.secondary)}
            tarType.text=if(format in listOf("7Z","ZIP"))""else"选择 TAR 类型 · "+formatLabel(format);tarType.visibility=if(format in listOf("7Z","ZIP"))View.GONE else View.VISIBLE
            splitPair.first.visibility=if(format=="ZIP")View.VISIBLE else View.GONE;splitValue.visibility=if(format=="ZIP"&&splitPair.second.isChecked)View.VISIBLE else View.GONE
            passwordToggle.isEnabled=format in listOf("7Z","ZIP");passwordRow.visibility=if(passwordToggle.isEnabled&&passwordToggle.isChecked)View.VISIBLE else View.GONE
        }
        for((i,text) in listOf("7z","ZIP","TAR").withIndex()){
            val button=label(text,16f,colors.text,true).apply{id=listOf(R.id.archive_format_7z,R.id.archive_format_zip,R.id.archive_format_tar)[i];gravity=Gravity.CENTER;setOnClickListener{format=when(i){0->"7Z";1->"ZIP";else->if(saved.format !in listOf("7Z","ZIP"))saved.format else"TAR_ONLY"};updateFormat()}}
            formatButtons+=button;formats.addView(button,LinearLayout.LayoutParams(0,-1,1f))
        }
        tarType.setOnClickListener{it.menuAnchor();val keys=listOf("TAR_ONLY","gz","xz","bzip2","lzma","zstd");menu("TAR 类型",keys.map{formatLabel(it)},keys.indexOf(format)){format=keys[it];updateFormat()}}
        updateFormat()
        val scroll=ScrollView(this).apply{isFillViewport=false;clipToPadding=false};scroll.addView(content)
        dialog.body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val start=primary("开始压缩"){
            val archiveName=name.text.toString().trim()
            if(!FileManagerKernel.validName(archiveName)){name.error="名称不能含路径分隔符";return@primary}
            if(passwordToggle.isChecked&&passwordToggle.isEnabled&&password.text.isEmpty()){password.error="请输入密码，或关闭密码选项";return@primary}
            val splitMb=splitValue.text.toString().toLongOrNull()?:0
            if(format=="ZIP"&&splitPair.second.isChecked&&(splitMb<1||splitMb>1048576)){splitValue.error="请输入 1—1048576 MB";return@primary}
            val options=CreationOptions(format,passwordToggle.isChecked,password.text.toString(),splitPair.second.isChecked,if(splitMb>0)splitMb else saved.splitMb,if(importedSources)saved.delete else delete.isChecked)
            dialog.dialog.close{startArchive(paths,archiveName,output,options.copy(delete=delete.isChecked))}
        }.apply{id=R.id.archive_start}
        startButton=start;deletionText()
        dialog.body.addView(start,LinearLayout.LayoutParams(-1,dp(54)).apply{setMargins(dp(22),dp(12),dp(22),dp(20))})
        dialog.dialog.setOnDismissListener{openDialogs.remove(dialog.dialog)}
        sizeDialog(dialog);dialog.dialog.show()
    }
    private fun formatLabel(format:String)=when(format){"TAR_ONLY"->"TAR";"bzip2"->"TAR.BZ2";"zstd"->"TAR.ZST";else->"TAR.${format.uppercase()}"}
    private fun startArchive(paths:List<String>,name:String,output:File,options:CreationOptions){
        if(ActiveOperation.busy()){notice("请等待当前任务完成");return}
        val job=FileOperationsDao(this).addFilesForJob(paths)
        ActiveOperation.start("创建 ${if(options.format in listOf("7Z","ZIP"))options.format else formatLabel(options.format)} 压缩包",name)
        ContextCompat.startForegroundService(this,Intent(this,SafeArchiveService::class.java).apply{
            putExtra(S.EXTRA_JOB_ID,job);putExtra(S.EXTRA_ARCHIVE_NAME,name);putExtra(S.EXTRA_DESTINATION_PATH,output.path)
            putExtra(SafeArchiveService.EXTRA_FORMAT,options.format);putExtra(SafeArchiveService.EXTRA_DELETE,options.delete)
            putExtra(S.EXTRA_PASSWORD,if(options.passwordEnabled&&options.format in listOf("7Z","ZIP"))options.password else null)
            putExtra(S.EXTRA_SPLIT_SIZE,if(options.format=="ZIP"&&options.split)options.splitMb*1024*1024 else 0)
        });onFileOperationStarted()
    }
}
