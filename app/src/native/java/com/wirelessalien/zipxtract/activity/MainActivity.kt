/* Native archive/file UI. Modified 2026-11-03, GPL-3.0-or-later. */
package com.wirelessalien.zipxtract.activity

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.wirelessalien.zipxtract.R
import com.wirelessalien.zipxtract.constant.ServiceConstants as S
import com.wirelessalien.zipxtract.helper.*
import com.wirelessalien.zipxtract.nativeui.*
import com.wirelessalien.zipxtract.service.*
import eightbitlab.com.blurview.BlurTarget
import eightbitlab.com.blurview.BlurView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import net.lingala.zip4j.ZipFile
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*

class MainActivity:FileActionActivity(){
    enum class Page{ARCHIVES,HUB,DIRECTORY,RECENT,CATEGORY,FAVORITES,ARCHIVE}
    data class Item(val title:String,val subtitle:String="",val icon:String="file",val file:File?=null,
        val action:(()->Unit)?=null,val kind:String="row",val entry:String?=null,val directory:Boolean=false,val groupKey:String="",val count:Int=0,val duration:Long=0)
    data class Entry(val name:String,val directory:Boolean,val size:Long)
    private var loading:Job?=null
    private var migrationImport: Job? = null
    private var pendingBookMigration: File? = null
    private lateinit var root:FrameLayout
    private lateinit var target:BlurTarget
    lateinit var recycler:RecyclerView;private set
    private lateinit var adapter:Rows
    private lateinit var topFade:FadeMask
    private lateinit var topBlur:BlurView
    private lateinit var topControls:LinearLayout
    // One safe-area toolbar owns every action position; list header only reserves scroll space.
    private val toolbarButtons=mutableListOf<LiquidGlassView>()
    private lateinit var compact:TextView
    private lateinit var dock:GestureDock
    private lateinit var dockSelector:View
    private var dockPosition=0f
    private var dockStrength=0f
    private var dockDragging=false
    private var dockHolding=false
    private var dockMode=""
    private var dockAnimator:android.animation.ValueAnimator?=null
    private lateinit var dockHost:FrameLayout
    private lateinit var searchDock:LiquidGlassView
    private lateinit var dockContent:LinearLayout
    private var dockGeometry:DockGeometry.Layout?=null
    private lateinit var dockModeMotion: DockModeMotion
    private lateinit var banner:LinearLayout
    private lateinit var taskStack:LinearLayout
    private lateinit var bannerText:TextView
    private val prefs by lazy{getSharedPreferences("native_ui",MODE_PRIVATE)}
    private var page=Page.ARCHIVES
    private var directory=Environment.getExternalStorageDirectory()
    private var writableDirectory: File? = null
    private var directoryOpenRequest = 0
    private var category=""
    private var query=""
    private var entries=listOf<Entry>()
    private var archive:File?=null
    private var archivePrefix=""
    private var archivePassword:String?=null
    private val selected=linkedSetOf<String>()
    private val selectedEntries=linkedSetOf<String>()
    private lateinit var dragSelection:DragSelectionController
    private val selectionPayload=Any()
    private var hostResumed=false
    private var renameIconView:ClockRenameIconView?=null
    private var sourcePicker=false
    private var floatProgress=0f
    private var compactProgress=0f
    private val listItems=mutableListOf<Item>()
    private var firstResume=true
    private val glass by lazy{GlassMaterial(this)}
    private var pageAnimator:android.animation.ValueAnimator?=null
    private var pendingAnchor:ViewportAnchor?=null
    private var pausedAnchor:ViewportAnchor?=null
    private var loadGeneration=0
    private var pendingReveal:String?=null
    private val categoryCatalog by lazy{CategoryCatalog(this)}
    private data class CategorySection(val key:String,val title:String,val items:List<Item>)
    private var categorySections=emptyList<CategorySection>()
    private val collapsedGroups=mutableSetOf<String>()
    private fun routeKey()="$page|${if(page==Page.DIRECTORY)directory.path else if(page==Page.CATEGORY)category else ""}|${if(page==Page.ARCHIVE)"${archive?.path}|$archivePrefix" else ""}"
    private fun itemKey(item:Item):String=when{item.file!=null->"file:${item.file.path}";item.entry!=null->"entry:${item.entry}";item.kind=="date"->"date:${item.groupKey}";else->"${item.kind}:${item.title}"}
    private fun captureViewport():ViewportAnchor?{
        if(!::recycler.isInitialized||listItems.isEmpty())return null
        val manager=recycler.layoutManager as? LinearLayoutManager?:return null
        val first=manager.findFirstVisibleItemPosition();if(first<0)return null
        val view=manager.findViewByPosition(first)?:return null
        return ViewportAnchor(listItems.map(::itemKey),first,view.top-recycler.paddingTop)
    }
    private fun mediaGrid()=page==Page.CATEGORY&&category in listOf("图片","视频")
    private fun safeWidth() = (root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) -
        root.paddingLeft - root.paddingRight
    private fun wideArchives() = page == Page.ARCHIVES && safeWidth() >= dp(840)
    private fun contentGutter() = ((safeWidth() - dp(1040)) / 2).coerceAtLeast(0)
    private fun installListLayout(){
        val spans = when {
            mediaGrid() -> ((safeWidth() - 2 * contentGutter()) / dp(120)).coerceIn(4, 8)
            wideArchives() -> 2
            else -> 1
        }
        val grid = recycler.layoutManager as? GridLayoutManager
        if (spans == 1) {
            if (grid != null) recycler.layoutManager = LinearLayoutManager(this)
        } else if (grid != null) {
            grid.spanCount = spans
            grid.spanSizeLookup.invalidateSpanIndexCache()
        } else {
            recycler.layoutManager = GridLayoutManager(this, spans).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        val item = listItems.getOrNull(position)
                        return if (item?.kind == "media" || (wideArchives() && item?.file != null)) 1 else spanCount
                    }
                }
            }
        }
    }
    private fun restoreViewport(){
        val manager=recycler.layoutManager as LinearLayoutManager
        val reveal=pendingReveal
        if(reveal!=null){
            listItems.indexOfFirst{it.file?.path==reveal}.takeIf{it>=0}?.let{manager.scrollToPositionWithOffset(it,dp(124))}
            pendingReveal=null;pendingAnchor=null;restoredScroll=null
        }else if(restoredScroll!=null){manager.onRestoreInstanceState(restoredScroll);restoredScroll=null;pendingAnchor=null}
        else pendingAnchor?.let{manager.scrollToPositionWithOffset(it.resolve(listItems.map(::itemKey)),it.offset);pendingAnchor=null}
    }
    private data class Screen(val page:Page,val directory:String,val category:String="",val query:String="",val scroll:Parcelable?=null)
    private var fileScreen:Screen?=null
    private var archiveReturn:Screen?=null
    private var categoryReturn:Screen?=null
    private var directoryOrigin:Screen?=null
    private var restoredScroll:Parcelable?=null
    private var renderedKey=""
    private var renderedSearch=""
    private var motionDirection=1
    private var pageGhost:ImageView?=null
    private var pageBitmap:Bitmap?=null
    private fun screen()=Screen(page,directory.path,category,query,recycler.layoutManager?.onSaveInstanceState())
    private fun restore(value:Screen){
        val destination=if(value.page==Page.ARCHIVE&&archive==null)Screen(Page.HUB,value.directory)else value
        page=destination.page;directory=File(destination.directory);category=destination.category;query=destination.query;restoredScroll=destination.scroll
        selected.clear();selectedEntries.clear();loadPage()
    }
    private fun openCategory(name:String){categoryReturn=screen();category=name;page=Page.CATEGORY;query="";loadPage()}
    private fun tabIndex()=when(page){
        Page.ARCHIVES->0
        Page.CATEGORY->if(categoryReturn?.page==Page.ARCHIVES)0 else 1
        Page.ARCHIVE->if(archiveReturn?.page==Page.ARCHIVES||archiveReturn?.page==Page.CATEGORY&&categoryReturn?.page==Page.ARCHIVES)0 else 1
        else->1
    }
    private fun Screen.isFileBrowser()=page in listOf(Page.HUB,Page.DIRECTORY)
    private fun fileBrowserScreen():Screen?=when(page){
        Page.HUB,Page.DIRECTORY->screen()
        Page.CATEGORY->categoryReturn?.takeIf{it.isFileBrowser()}
        Page.ARCHIVE->archiveReturn?.let{if(it.page==Page.CATEGORY)categoryReturn else it}?.takeIf{it.isFileBrowser()}
        else->null
    }
    private fun clearArchivePreview(){archive=null;archivePassword=null;archivePrefix="";entries=emptyList();archiveReturn=null;selectedEntries.clear()}
    private fun switchTab(index:Int){
        if(index==tabIndex()&&(page==Page.ARCHIVES||page in listOf(Page.HUB,Page.DIRECTORY))){settleDock(index.toFloat());return}
        motionDirection=if(index==1)1 else -1
        val browser=fileBrowserScreen()
        if(browser!=null)fileScreen=browser
        clearArchivePreview();selected.clear();sourcePicker=false
        if(index==0){page=Page.ARCHIVES;query="";loadPage()}
        else restore(fileScreen?.takeIf{it.isFileBrowser()}?:Screen(Page.HUB,directory.path))
    }
    private fun clearPageMotion(){
        pageAnimator?.removeAllListeners();pageAnimator?.cancel();pageAnimator=null
        recycler.animate().cancel();recycler.alpha=1f;recycler.translationX=0f
        pageGhost?.let{it.setImageDrawable(null);target.removeView(it)};pageGhost=null
        pageBitmap?.recycle();pageBitmap=null
    }
    private fun preparePageMotion(){
        val key=routeKey();if(key==renderedKey)return
        val initial=renderedKey.isEmpty();renderedKey=key;pendingAnchor=null
        dock.cancelGesture();dockDragging=false;dockHolding=false;settleDock(tabIndex().toFloat())
        // Capture the current composition before cancelling an interrupted transition.
        val bitmap=if(!initial&&target.width>0&&target.height>0)Bitmap.createBitmap(target.width,target.height,Bitmap.Config.ARGB_8888).also{
            val canvas=Canvas(it);canvas.drawColor((target.background as? android.graphics.drawable.ColorDrawable)?.color?:colors.page);target.draw(canvas)
        }else null
        clearPageMotion()
        if(bitmap!=null){
            pageBitmap=bitmap
            pageGhost=ImageView(this).apply{setImageBitmap(bitmap);isClickable=true;scaleType=ImageView.ScaleType.FIT_XY;alpha=1f}
            target.addView(pageGhost,FrameLayout.LayoutParams(bitmap.width,bitmap.height))
            val frame=MotionFrames.pages(bitmap.width,0f,motionDirection)
            recycler.translationX=frame.newX.toFloat()
            topFade.visibility=View.INVISIBLE;topControls.visibility=View.INVISIBLE
        }
        recycler.scrollToPosition(0)
    }
    private fun playPageMotion(){
        val ghost=pageGhost?:return
        if(ghost.getTag(R.id.native_page_motion)==true)return
        ghost.setTag(R.id.native_page_motion,true)
        val direction=motionDirection;motionDirection=1
        val width=target.width.coerceAtLeast(1)
        pageAnimator=android.animation.ValueAnimator.ofFloat(0f,1f).apply{
            duration=300;interpolator=UiMotion.ease
            addUpdateListener{
                val frame=MotionFrames.pages(width,it.animatedValue as Float,direction)
                recycler.translationX=frame.newX.toFloat();ghost.translationX=frame.oldX.toFloat()
            }
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(a:android.animation.Animator){if(pageGhost===ghost){clearPageMotion();updateFloating()}}})
            start()
        }
    }

    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        if(state!=null){page=runCatching{Page.valueOf(state.getString("page")?:"ARCHIVES")}.getOrDefault(Page.ARCHIVES)
            // Older builds saved ARCHIVE without its file/entries. Never restore that empty shell.
            if(page==Page.ARCHIVE)page=Page.ARCHIVES
            directory=File(state.getString("directory")?:directory.path);selected.addAll(state.getStringArrayList("selection")?:emptyList());category=state.getString("category")?:""
            categoryReturn=Screen(runCatching{Page.valueOf(state.getString("category_return")?:"HUB")}.getOrDefault(Page.HUB),directory.path)
            directoryOrigin=Screen(runCatching{Page.valueOf(state.getString("directory_origin")?:"HUB")}.getOrDefault(Page.HUB),directory.path)
            restoredScroll=state.getParcelable("viewport");query=state.getString("query")?:"";collapsedGroups.addAll(state.getStringArrayList("collapsed_groups")?:emptyList())}
        WindowCompat.setDecorFitsSystemWindows(window,false)
        WindowCompat.getInsetsController(window,window.decorView).apply{isAppearanceLightStatusBars=!colors.dark;isAppearanceLightNavigationBars=!colors.dark}
        window.statusBarColor=Color.TRANSPARENT;window.navigationBarColor=Color.TRANSPARENT
        buildShell();observeEvents();loadPage()
        if (state == null || intent.action != BookMigration.SEND_ACTION) handleIntent(intent)
    }
    override fun onNewIntent(value:Intent){super.onNewIntent(value);intent=value;handleIntent(value)}
    override fun onPause(){hostResumed=false;renameIconView?.setHostResumed(false);if(::dragSelection.isInitialized)dragSelection.cancelGesture();pausedAnchor=captureViewport();dock.cancelGesture();dockDragging=false;dockHolding=false;dockAnimator?.cancel();dockModeMotion.finish();dockStrength=0f;renderDock();clearPageMotion();super.onPause()}
    override fun onResume(){super.onResume();hostResumed=true;renameIconView?.setHostResumed(true);if(!firstResume){if(restoredScroll==null)pendingAnchor=pausedAnchor;loadPage()};pausedAnchor=null;firstResume=false;presentBookMigration()}
    override fun onSaveInstanceState(out:Bundle){
        val restorable=if(page==Page.ARCHIVE)archiveReturn?.takeIf{it.page!=Page.ARCHIVE}?:Screen(Page.ARCHIVES,directory.path) else screen()
        out.putString("page",restorable.page.name);out.putString("directory",restorable.directory);out.putString("category",restorable.category)
        out.putStringArrayList("selection",ArrayList(selected));out.putString("category_return",categoryReturn?.page?.name);out.putString("directory_origin",directoryOrigin?.page?.name)
        out.putParcelable("viewport",restorable.scroll);out.putString("query",restorable.query);out.putStringArrayList("collapsed_groups",ArrayList(collapsedGroups));super.onSaveInstanceState(out)
    }
    override fun onDestroy(){if(::dragSelection.isInitialized)dragSelection.dispose();clearPageMotion();dockAnimator?.cancel();if(::dockModeMotion.isInitialized)dockModeMotion.finish();loading?.cancel();pendingBookMigration?.let(::releaseMigration);pendingBookMigration=null;categoryCatalog.close();super.onDestroy()}
    @Deprecated("Deprecated in Java") override fun onBackPressed(){
        dragSelection.cancelGesture()
        motionDirection=-1
        when{
            selected.isNotEmpty()||selectedEntries.isNotEmpty()->{selected.clear();selectedEntries.clear();refreshViews()}
            query.isNotBlank()->{query="";loadPage()}
            page==Page.ARCHIVE&&archivePrefix.isNotEmpty()->{archivePrefix=archivePrefix.trimEnd('/').substringBeforeLast('/',"").let{if(it.isEmpty())"" else "$it/"};renderArchive()}
            page==Page.ARCHIVE->{val destination=archiveReturn?:Screen(Page.ARCHIVES,directory.path);clearArchivePreview();restore(destination)}
            page==Page.CATEGORY->restore(categoryReturn?:Screen(Page.HUB,directory.path))
            page==Page.DIRECTORY->{
                val base=storageRoots().firstOrNull{directory==it||directory.path.startsWith(it.path+"/")}
                if(base!=null&&directory!=base&&directory.parentFile!=null)openDirectory(directory.parentFile!!)
                else restore(directoryOrigin?:Screen(Page.HUB,directory.path))
            }
            page in listOf(Page.FAVORITES,Page.RECENT)->restore(Screen(Page.HUB,directory.path))
            else->super.onBackPressed()
        }
    }
    private fun buildShell(){
        root=FrameLayout(this).apply{setBackgroundColor(colors.page);clipChildren=false;clipToPadding=false}
        target=BlurTarget(this).apply{setBackgroundColor(colors.page);clipChildren=true;clipToPadding=true}
        recycler=RecyclerView(this).apply{
            id=R.id.native_list;layoutManager=LinearLayoutManager(this@MainActivity);clipToPadding=false
            setPadding(0,0,0,dp(116));itemAnimator=null;overScrollMode=View.OVER_SCROLL_NEVER
        }
        adapter=Rows().apply{setHasStableIds(true)};recycler.adapter=adapter
        dragSelection=DragSelectionController(recycler,object:DragSelectionController.Host{
            override fun selectionKey(position:Int)=listItems.getOrNull(position)?.let(::selectionIdentity)
            override fun isSelected(key:String)=when{
                key.startsWith("file:")->key.removePrefix("file:") in selected
                key.startsWith("entry:")->key.removePrefix("entry:") in selectedEntries
                else->false
            }
            override fun onSelectionChanges(changes:Map<String,Boolean>){
                for((key,chosen) in changes){
                    val set=if(key.startsWith("file:"))selected else selectedEntries
                    val value=key.substringAfter(':')
                    if(chosen)set.add(value)else set.remove(value)
                }
                // Never restore a viewport captured before this MOVE, and never invalidate adapter
                // positions: a payload change keeps the live touch target and its layout intact.
                refreshSelectionChrome()
            }
        }).apply{topInsetPx=dp(108);bottomInsetPx=dp(116)}
        target.addView(recycler,FrameLayout.LayoutParams(-1,-1));root.addView(target,FrameLayout.LayoutParams(-1,-1))
        topFade=FadeMask(this).apply{id=R.id.native_top_fade;visibility=View.INVISIBLE;isClickable=false}
        topBlur=BlurView(this)
        topBlur.setupWith(target).setFrameClearDrawable(android.graphics.drawable.ColorDrawable(colors.page)).setBlurRadius(10f)
        topBlur.setOverlayColor(if(colors.dark)0xcf141518.toInt()else 0xebffffff.toInt())
        topFade.addView(topBlur,FrameLayout.LayoutParams(-1,-1))
        root.addView(topFade,FrameLayout.LayoutParams(-1,dp(142),Gravity.TOP))
        topControls=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(12),0)
            clipChildren=false;clipToPadding=false
        }
        root.addView(topControls,FrameLayout.LayoutParams(-1,dp(108),Gravity.TOP))
        dock=GestureDock(this).apply{id=R.id.native_dock;selectionOverflowEnabled=true}
        dock.setupWith(target).setFrameClearDrawable(android.graphics.drawable.ColorDrawable(colors.page)).setBlurRadius(18f)
        dock.bindGlassTarget(target)
        dockContent=LinearLayout(this).apply{gravity=Gravity.CENTER;orientation=LinearLayout.HORIZONTAL;elevation=dp(6).toFloat()}
        dockSelector=View(this).apply{id=R.id.native_dock_selector}
        dock.addView(dockSelector,FrameLayout.LayoutParams(dp(96),dp(58),Gravity.TOP or Gravity.LEFT).apply{topMargin=dp(4);leftMargin=dp(4)})
        dock.addView(dockContent,FrameLayout.LayoutParams(-1,-1))
        dock.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->renderDock()}
        dock.onPressChanged={pressed->animateDockPress(pressed)}
        dock.onDrag={position->dockDragging=true;dockPosition=position;renderDock()}
        dock.onRelease={index,cancelled->
            dockDragging=false;dockHolding=false
            if(cancelled)settleDock(tabIndex().toFloat())else switchTab(index)
        }
        dockHost=FrameLayout(this).apply{id=R.id.native_dock_host;isClickable=false;clipChildren=false;clipToPadding=false;setBackgroundColor(Color.TRANSPARENT)}
        dockHost.addOnLayoutChangeListener{_,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom->
            if(right-left!=oldRight-oldLeft||bottom-top!=oldBottom-oldTop)applyDockGeometry()
        }
        dockHost.addView(dock,FrameLayout.LayoutParams(1,1,Gravity.TOP or Gravity.LEFT))
        searchDock=LiquidGlassView(this).apply{id=R.id.native_search_dock;clipToOutline=true;setOnClickListener{showSearch()};accessible("搜索")}
        searchDock.setupWith(target).setFrameClearDrawable(android.graphics.drawable.ColorDrawable(colors.page)).setBlurRadius(18f)
        searchDock.bindGlassTarget(target)
        searchDock.addView(iconButton("search","搜索"){showSearch()},FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        dockHost.addView(searchDock,FrameLayout.LayoutParams(1,1,Gravity.TOP or Gravity.LEFT))
        dockModeMotion = DockModeMotion(dockHost, dock, searchDock, dockContent)
        dockHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> dockModeMotion.applyFrame() }
        root.addView(dockHost,FrameLayout.LayoutParams(-1,dp(88),Gravity.BOTTOM))
        banner=LinearLayout(this).apply{id=R.id.task_banner;orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(8),dp(4),dp(8));background=colors.background(colors.selected,dp(20).toFloat());visibility=View.GONE}
        bannerText=label("",13f,colors.text).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END}
        banner.addView(bannerText,LinearLayout.LayoutParams(0,-2,1f));banner.addView(iconButton("close","取消当前任务"){cancelTask()},LinearLayout.LayoutParams(dp(44),dp(44)))
        taskStack=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            addView(transferPanel(),LinearLayout.LayoutParams(-1,-2))
            addView(banner,LinearLayout.LayoutParams(-1,dp(58)))
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if(bottom-top!=oldBottom-oldTop) updateBanner()
            }
        }
        root.addView(taskStack,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(dp(20),0,dp(20),dp(90))})
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root){v,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            applyDockGeometry()
            insets
        }
        root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                installListLayout()
                updateBanner()
            }
        }
        recycler.addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(rv:RecyclerView,dx:Int,dy:Int){if(rv.scrollState==RecyclerView.SCROLL_STATE_DRAGGING){pendingAnchor=null;restoredScroll=null};updateFloating()}})
    }
    private fun title():String=when(page){Page.ARCHIVES->"压缩";Page.HUB->"浏览";Page.DIRECTORY->if(directory==Environment.getExternalStorageDirectory())"我的手机"else AndroidFolderAccess.displayFile(directory).name;Page.RECENT->"最近文件";Page.CATEGORY->category;Page.FAVORITES->"收藏";Page.ARCHIVE->archive?.name?:"压缩包"}
    private fun selectionCount()=if(page==Page.ARCHIVE)selectedEntries.size else selected.size
    /** The toolbar lens samples the already blurred header without re-exposing list detail. */
    private fun topGlassButton(icon:String,description:String,action:()->Unit):LiquidGlassView{
        val button=object:LiquidGlassView(this){override fun getAccessibilityClassName():CharSequence="android.widget.Button"}.apply{
            accessible(description);isFocusable=true
            setOnClickListener{menuAnchor();action()}
        }
        button.bindGlassTarget(topFade)
        glass.applyToolbarSurface(button,floatProgress)
        toolbarButtons+=button
        button.addView(ImageView(this).apply{
            scaleType=ImageView.ScaleType.FIT_CENTER;setImageDrawable(Art.drawable(context,icon))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false;isFocusable=false
        },FrameLayout.LayoutParams(dp(25),dp(25),Gravity.CENTER))
        button.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->glass.applyToolbarSurface(button,floatProgress)}
        return button
    }
    private fun buildTop():View{
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false}
        fun add(icon:String,description:String,leading:Boolean=false,action:()->Unit){
            row.addView(topGlassButton(icon,description,action),LinearLayout.LayoutParams(dp(48),dp(48)).apply{
                if(leading)rightMargin=dp(8)else leftMargin=dp(6)
            })
        }
        val back=page !in listOf(Page.ARCHIVES,Page.HUB)||selectionCount()>0
        if(back)add(if(selectionCount()>0)"close"else"back",if(selectionCount()>0)"取消选择"else"返回",leading=true){onBackPressed()}
        add("more","更多选项",leading=true){showSettings()}
        compact=label(if(selectionCount()>0)"已选 ${selectionCount()} 项"else title(),19f,colors.text,true).apply{
            id=R.id.native_compact_title;maxLines=1;ellipsize=TextUtils.TruncateAt.MIDDLE;alpha=compactProgress
            importantForAccessibility=if(compactProgress>0)View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(compact,LinearLayout.LayoutParams(0,dp(56),1f))
        if(selectionCount()>0)add("all","全选"){
            if(page==Page.ARCHIVE){listItems.mapNotNull{it.entry}.forEach{selectedEntries.add(it)}}
            else listItems.mapNotNull{it.file}.forEach{selected.add(it.path)}
            refreshViews()
        }else {
            if(page==Page.DIRECTORY&&directory==writableDirectory)add("newfolder","新建文件夹"){newFolder(directory){loadPage()}}
            if(page in listOf(Page.DIRECTORY,Page.CATEGORY))add("sort","排序"){showSort()}
        }
        return row
    }
    private fun header():View{
        val box=LinearLayout(this).apply{id=R.id.native_header;orientation=LinearLayout.VERTICAL;setPadding(if(mediaGrid())0 else dp(20),dp(2),if(mediaGrid())0 else dp(20),dp(14))}
        // Actions stay in the fixed root-side toolbar, never in this scrolling/sampled subtree.
        box.addView(View(this),LinearLayout.LayoutParams(-1,dp(54)))
        box.addView(label(if(selectionCount()>0)"已选 ${selectionCount()} 项"else title(),35f,colors.text,false).apply{id=R.id.native_large_title;maxLines=2;ellipsize=TextUtils.TruncateAt.MIDDLE},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(13);bottomMargin=dp(16)})
        when(page){
            Page.DIRECTORY->box.addView(breadcrumbs(directory),LinearLayout.LayoutParams(-1,dp(40)))
            Page.ARCHIVE->box.addView(label(if(archivePrefix.isEmpty())"压缩包内容 · 只读预览"else archivePrefix,13f,colors.secondary).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.MIDDLE},LinearLayout.LayoutParams(-1,dp(28)))

            else->{}
        }
        if(sourcePicker)box.addView(label("选择文件后，点击下方“压缩”",13f,colors.blue),LinearLayout.LayoutParams(-1,dp(30)))
        if(query.isNotBlank())box.addView(label("搜索：$query · 点击清除",13f,colors.blue).apply{setOnClickListener{query="";loadPage()}},LinearLayout.LayoutParams(-1,dp(30)))
        return box
    }
    private fun breadcrumbs(folder:File):View{
        val scroll=HorizontalScrollView(this).apply{id=R.id.native_breadcrumbs;isHorizontalScrollBarEnabled=false}
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        val base=storageRoots().firstOrNull{folder.path==it.path||folder.path.startsWith(it.path+"/")}
        val chain=mutableListOf<File>();var value:File?=folder
        while(value!=null&&chain.size<30){chain.add(0,value);if(value==base)break;value=value.parentFile}
        if(base==null)chain.clear().also{chain.add(folder)}
        for((i,file) in chain.withIndex()){
            if(i>0)row.addView(ImageView(this).apply{setImageDrawable(Art.drawable(context,"chevron"));imageAlpha=85;setPadding(dp(5),dp(11),dp(5),dp(11))},LinearLayout.LayoutParams(dp(20),-1))
            val chip=label(if(file==base)"内部存储"else AndroidFolderAccess.displayFile(file).name,13f,if(i==chain.lastIndex)0xffffad24.toInt() else colors.secondary).apply{
                gravity=Gravity.CENTER;setPadding(dp(13),0,dp(13),0);background=colors.background(if(i==chain.lastIndex)0xfffff5dc.toInt() else colors.surface,dp(19).toFloat());setOnClickListener{openDirectory(file)}
            };row.addView(chip,LinearLayout.LayoutParams(-2,dp(36)))
        }
        scroll.addView(row);scroll.post{scroll.fullScroll(View.FOCUS_RIGHT)};return scroll
    }
    private fun updateFloating(){
        if(!::recycler.isInitialized)return
        if(pageGhost!=null){topFade.visibility=View.INVISIBLE;topControls.visibility=View.INVISIBLE;return}
        val manager=recycler.layoutManager as LinearLayoutManager
        val header=manager.findViewByPosition(0)
        val first=manager.findFirstVisibleItemPosition()
        val offset=if(first<0)0 else if(first==0&&header!=null)-header.top else dp(200)
        val p=((offset-dp(28)).toFloat()/dp(80)).coerceIn(0f,1f)
        floatProgress=p
        topFade.visibility=if(p>0)View.VISIBLE else View.INVISIBLE;topFade.alpha=p
        val controls=((offset-dp(72)).toFloat()/dp(32)).coerceIn(0f,1f)
        compactProgress=controls
        // Root safe-area padding is the only coordinate owner. Scroll affects material/title, not actions.
        topControls.visibility=View.VISIBLE;topControls.alpha=1f
        if(::compact.isInitialized)compact.apply{
            alpha=controls
            importantForAccessibility=if(controls>0)View.IMPORTANT_FOR_ACCESSIBILITY_YES else View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if(topControls.childCount>1)topControls.getChildAt(1).apply{
            visibility=if(controls>0)View.VISIBLE else View.INVISIBLE;alpha=controls
        }
        toolbarButtons.forEach{glass.applyToolbarSurface(it,p)}
        val expanded=((dp(64)-offset).toFloat()/dp(36)).coerceIn(0f,1f)
        header?.findViewById<View>(R.id.native_large_title)?.alpha=expanded
        header?.findViewById<View>(R.id.native_breadcrumbs)?.alpha=expanded
        topBlur.setBlurEnabled(p>0)
        // Header scrolling never owns dock geometry, selector scale or material.
    }
    /** One owner for dock bounds. No layout transitions are allowed inside a BlurView. */
    private fun applyDockGeometry(){
        if(!::searchDock.isInitialized)return
        val normal=selectionCount()==0&&page!=Page.ARCHIVE
        val available=(dockHost.width.takeIf{it>0}?:resources.displayMetrics.widthPixels)-dockHost.paddingLeft-dockHost.paddingRight
        val actions=if(page==Page.ARCHIVE)if(selectionCount()>0)1 else 2 else 6
        val metrics=DockGeometry.calculate(available,resources.displayMetrics.density,resources.configuration.fontScale,
            if(normal)DockGeometry.Mode.TABS else DockGeometry.Mode.ACTIONS,actions,root.paddingBottom)
        dockGeometry=metrics
        fun place(view:View,box:DockGeometry.Box){
            val lp=view.layoutParams as FrameLayout.LayoutParams
            if(lp.width!=box.width||lp.height!=box.height||lp.leftMargin!=box.left||lp.topMargin!=box.top||lp.gravity!=(Gravity.TOP or Gravity.LEFT)){
                lp.width=box.width;lp.height=box.height;lp.leftMargin=box.left;lp.topMargin=box.top;lp.gravity=Gravity.TOP or Gravity.LEFT;view.layoutParams=lp
            }
            view.translationX=0f;view.translationY=0f
        }
        place(dock,metrics.capsule);metrics.search?.let{place(searchDock,it)}
        val host=dockHost.layoutParams as FrameLayout.LayoutParams
        if(host.height!=metrics.hostHeight){host.height=metrics.hostHeight;dockHost.layoutParams=host}
        dockHost.translationY=0f
        if (!dockModeMotion.active) searchDock.visibility=if(normal)View.VISIBLE else View.GONE
        dockSelector.visibility=if(normal)View.VISIBLE else View.GONE
        dockContent.setPadding(metrics.selectorInset,0,metrics.selectorInset,0)
        for(i in 0 until dockContent.childCount)sizeDockItem(dockContent.getChildAt(i) as LinearLayout,metrics)
        renderDock()
    }
    private fun renderDock(){
        if(!::searchDock.isInitialized)return
        val t=dockStrength.coerceIn(0f,1f)
        glass.applySurface(dock,t,LiquidGlassStyle.COMMENT_GROUP)
        glass.applySurface(searchDock,style=LiquidGlassStyle.COMMENT_BUTTON)
        // Only the background lens swells. Dock bounds, icon/text layout and touch targets stay fixed.
        dock.scaleX=1f;dock.scaleY=1f;dockSelector.scaleX=1f+.06f*t;dockSelector.scaleY=1f+.16f*t
        if(dockSelector.visibility==View.VISIBLE){
            val metrics=dockGeometry?:return
            val lp=dockSelector.layoutParams as FrameLayout.LayoutParams
            if(lp.width!=metrics.selectorWidth||lp.height!=metrics.selectorHeight||lp.leftMargin!=metrics.selectorInset||lp.topMargin!=metrics.selectorInset){
                lp.width=metrics.selectorWidth;lp.height=metrics.selectorHeight;lp.leftMargin=metrics.selectorInset;lp.topMargin=metrics.selectorInset;dockSelector.layoutParams=lp
            }
            dockSelector.translationX=metrics.selectorTravel*dockPosition
            glass.applySelector(dockSelector,t)
            if (dockMode == "tabs") markDockTab()
        }
        dock.setSelectionLens(dockSelector)
    }
    private fun animateDockPress(pressed:Boolean){
        dockHolding=pressed
        dockAnimator?.cancel()
        dockAnimator=android.animation.ValueAnimator.ofFloat(dockStrength,if(pressed)1f else 0f).apply{
            duration=if(pressed)180L else UiMotion.ENTER
            interpolator=UiMotion.ease
            addUpdateListener{dockStrength=it.animatedValue as Float;renderDock()}
            start()
        }
    }
    private fun settleDock(position:Float){
        dockAnimator?.cancel()
        val from = dockPosition
        val strength = dockStrength
        val spring = android.view.animation.OvershootInterpolator(.8f)
        dockAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = UiMotion.ENTER
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val elapsed = it.animatedValue as Float
                val travel = spring.getInterpolation(elapsed)
                dockPosition = (from + (position - from) * travel).coerceIn(-.024f, 1.024f)
                dockStrength = strength * (1f - UiMotion.ease.getInterpolation(elapsed))
                renderDock()
            }
            start()
        }
    }
    private fun refreshSelectionChrome(){
        if(listItems.isNotEmpty())adapter.notifyItemRangeChanged(0,listItems.size,selectionPayload)
        toolbarButtons.clear();topControls.removeAllViews()
        topControls.addView(buildTop(),LinearLayout.LayoutParams(-1,dp(58)))
        if(page==Page.DIRECTORY)topControls.addView(breadcrumbs(directory).apply{
            visibility=if(compactProgress>0)View.VISIBLE else View.INVISIBLE;alpha=compactProgress
        },LinearLayout.LayoutParams(-1,dp(40)))
        updateDock();updateBanner()
    }
    private fun refreshViews(finalRows:Boolean=true){
        if(finalRows&&pendingAnchor==null&&restoredScroll==null&&pageGhost==null)pendingAnchor=captureViewport()
        val background=if(page in listOf(Page.ARCHIVES,Page.HUB))colors.surface else colors.page
        root.setBackgroundColor(background);target.setBackgroundColor(background)
        installListLayout();adapter.notifyDataSetChanged()
        toolbarButtons.clear();topControls.removeAllViews()
        topControls.addView(buildTop(),LinearLayout.LayoutParams(-1,dp(58)))
        if(page==Page.DIRECTORY)topControls.addView(breadcrumbs(directory).apply{
            visibility=if(compactProgress>0)View.VISIBLE else View.INVISIBLE;alpha=compactProgress
        },LinearLayout.LayoutParams(-1,dp(40)))
        updateDock();updateBanner()
        if(finalRows)restoreViewport()
        // Navigation starts on the next frame; catalog completion never owns its start time.
        recycler.post{updateFloating();playPageMotion()}
    }
    private fun updateDock(){
        dock.dragEnabled=selectionCount()==0&&page!=Page.ARCHIVE
        dock.selectedIndex=tabIndex()
        if(!dockDragging&&!dockHolding)settleDock(tabIndex().toFloat())
        val mode=when{selectionCount()>0&&page==Page.ARCHIVE->"archive-selection";selectionCount()>0->"selection";page==Page.ARCHIVE->"archive";else->"tabs"}
        val changed = mode != dockMode
        if (changed) {
            val selectionToggle = (dockMode == "tabs" && mode == "selection") ||
                (dockMode == "selection" && mode == "tabs")
            if (hostResumed && selectionToggle) dockModeMotion.capture() else dockModeMotion.finish()
        }
        applyDockGeometry()
        if (!changed) return
        dockMode=mode;dockContent.removeAllViews()
        if(selectionCount()>0){
            if(page==Page.ARCHIVE)addDock("extract","解压所选"){startExtraction(archive!!,archivePassword,selectedEntries.toList())}
            else {
                addDock("archive","压缩"){showCreate(selected.toList())}
                addDock("copy","复制"){fileOperation("copy")}
                addDock("move","移动"){fileOperation("move")}
                addDock("rename","重命名"){showBatchRename()}
                addDock("transfer","传输"){showLanSend(selected.map(::File))}
                addDock("more","更多"){selectionMenu()}
            }
        }else if(page==Page.ARCHIVE){addDock("extract","全部解压"){showExtract(archive!!)};addDock("more","更多"){archive?.let{fileMenu(it)}}}
        else {
            addDock("archive","压缩"){switchTab(0)}
            addDock("folder","文件"){switchTab(1)}
            markDockTab()
        }
        dockGeometry?.let(dockModeMotion::play)
    }
    private fun markDockTab() {
        for (i in 0 until dockContent.childCount) {
            dockContent.getChildAt(i).isSelected = i == dock.selectedIndex
        }
    }

    private fun addDock(icon:String,text:String,action:()->Unit){
        val item=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(4),dp(4),dp(4),dp(4));setOnClickListener{menuAnchor();action()};accessible(if(icon=="rename")"批量重命名"else text)
            }
        val graphic:View=if(icon=="rename"){
            // A real minute clock, not an animation: it only ticks while attached and resumed.
            ClockRenameIconView(this).apply{setHostResumed(hostResumed)}.also{renameIconView=it}
        }else ImageView(this).apply{scaleType=ImageView.ScaleType.FIT_CENTER;setImageDrawable(Art.drawable(context,when(icon){"archive"->"compress";"folder"->"browse";else->icon}))}
        item.addView(graphic,LinearLayout.LayoutParams(dp(26),dp(26)))
        item.addView(label(text,13f,colors.text,false).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,dp(22)))
        dockGeometry?.let{sizeDockItem(item,it)}
        dockContent.addView(item,LinearLayout.LayoutParams(0,-1,1f))
    }
    private fun sizeDockItem(item:LinearLayout,metrics:DockGeometry.Layout){
        if(item.childCount!=2)return
        val image=item.getChildAt(0);val label=item.getChildAt(1) as TextView
        val imageLp=image.layoutParams as LinearLayout.LayoutParams
        if(imageLp.width!=metrics.iconSize||imageLp.height!=metrics.iconSize){imageLp.width=metrics.iconSize;imageLp.height=metrics.iconSize;image.layoutParams=imageLp}
        if(label.maxLines!=1)label.maxLines=1
        // Measure the base size without resetting the live TextView on every layout.
        // Five actions must retain their full labels at 320dp / larger system fonts.
        val actionCount=if(page==Page.ARCHIVE)if(selectionCount()>0)1 else 2 else if(selectionCount()>0)5 else 2
        val cellWidth=(metrics.capsule.width-2*metrics.selectorInset)/actionCount
        val room=(cellWidth-item.paddingLeft-item.paddingRight).coerceAtLeast(1).toFloat()
        val baseSize=android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,metrics.labelSp,resources.displayMetrics)
        val measured=android.graphics.Paint(label.paint).apply{textSize=baseSize}.measureText(label.text.toString())
        val targetSize=if(measured>room)baseSize*room/measured else baseSize
        if(kotlin.math.abs(label.textSize-targetSize)>.05f)label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,targetSize)
        val labelLp=label.layoutParams as LinearLayout.LayoutParams
        if(labelLp.height!=metrics.labelHeight||labelLp.topMargin!=metrics.iconLabelGap){labelLp.height=metrics.labelHeight;labelLp.topMargin=metrics.iconLabelGap;label.layoutParams=labelLp}
    }
    private fun storageAllowed() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun openStorageSettings() {
        val app = Uri.fromParts("package", packageName, null)
        val screens = if (Build.VERSION.SDK_INT >= 30) listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, app),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        ) else listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
        for (screen in screens) {
            try {
                startActivity(screen)
                return
            } catch (_: ActivityNotFoundException) {
                // Some system variants expose only the list of apps.
            } catch (_: SecurityException) {
                // Try the remaining system settings entry.
            }
        }
        notice("无法打开权限页。请在系统设置中为 FileDoctorC 开启文件访问。")
    }

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 30 || storageAllowed()) {
            openStorageSettings()
        } else {
            val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val asked = prefs.getBoolean("storage_permission_requested", false)
            if (asked && permissions.none { shouldShowRequestPermissionRationale(it) }) {
                openStorageSettings()
            } else {
                prefs.edit().putBoolean("storage_permission_requested", true).apply()
                requestPermissions(permissions, 31)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 31) loadPage()
    }

    override fun storageRoots():List<File>{
        val roots=mutableListOf(Environment.getExternalStorageDirectory());getExternalFilesDirs(null).filterNotNull().forEach{f->val path=f.path.substringBefore("/Android/");File(path).takeIf{it.exists()&&roots.none{r->r.path==path}}?.let{roots.add(it)}};return roots
    }

    private fun isAndroidRoot(folder: File?) =
        folder?.name == "Android" && folder.parentFile in storageRoots()

    private fun androidSubfolder(folder: File) =
        folder.name in listOf("media", "data", "obb") && isAndroidRoot(folder.parentFile)

    private fun protectedAndroidFolder(folder: File) =
        Build.VERSION.SDK_INT >= 30 && folder.name in listOf("data", "obb") && isAndroidRoot(folder.parentFile)

    fun openDirectory(file:File){
        dragSelection.cancelGesture()
        val request = ++directoryOpenRequest
        if (protectedAndroidFolder(file) && storageAllowed()) {
            val generation = loadGeneration
            scope.launch {
                val target = withContext(Dispatchers.IO) { AndroidFolderAccess.resolve(file) }
                if (request == directoryOpenRequest && generation == loadGeneration) openResolvedDirectory(target)
            }
        } else openResolvedDirectory(file)
    }

    private fun openResolvedDirectory(file: File) {
        if(!file.isDirectory&&!isAndroidRoot(file)&&!androidSubfolder(AndroidFolderAccess.displayFile(file))){notice("目录不可用");return}
        if(page!=Page.DIRECTORY)directoryOrigin=screen()
        directory=file;page=Page.DIRECTORY;query="";selected.clear();selectedEntries.clear();loadPage()
    }

    private fun listDirectory(folder: File, search: String, hidden: Boolean, reveal: String?): Pair<List<Item>, Boolean> {
        fun included(file: File) =
            (hidden || !file.name.startsWith('.') || file.path == reveal) &&
                (search.isBlank() || file.name.contains(search, true))

        val children = try { folder.listFiles() } catch (_: SecurityException) { null }
        val displayFolder = AndroidFolderAccess.displayFile(folder)
        if (children == null && protectedAndroidFolder(displayFolder)) {
            val own = if (displayFolder.name == "data") getExternalFilesDirs(null) else obbDirs
            val ownFolders = own.filterNotNull().filter {
                val appFolder = if (displayFolder.name == "data") it.parentFile else it
                appFolder?.parentFile == displayFolder && it.isDirectory && it.canRead()
            }
            val rows = ownFolders.map { target ->
                Item("FileDoctorC 文件夹", "${displayFolder.name}/$packageName" + if (displayFolder.name == "data") "/files" else "",
                    "folder", action = { openDirectory(target) })
            }
            return (rows + Item("系统限制访问", "这台设备没有开放 Android/${displayFolder.name}，仍可浏览 FileDoctorC 自己的文件夹。", kind = "empty")) to false
        }

        if (isAndroidRoot(folder)) {
            // Some devices hide protected entries from directory enumeration. Show their status
            // without making the virtual shortcuts eligible for file operations.
            val known = linkedMapOf("media" to "共享媒体", "data" to "应用外部文件", "obb" to "应用资源包")
            val entries = known.mapNotNull { (name, subtitle) ->
                val child = File(folder, name)
                when {
                    !included(child) -> null
                    !protectedAndroidFolder(child) && child.isDirectory && child.canRead() ->
                        fileItem(child).copy(subtitle = subtitle)
                    else -> Item(name, subtitle, "folder", action = { openDirectory(child) })
                }
            }
            val rest = sortFiles(children.orEmpty().filter { it.name !in known && included(it) }).map { fileItem(it) }
            val rows = entries + rest
            return rows.ifEmpty { listOf(Item("没有找到文件", kind = "empty")) } to
                (Build.VERSION.SDK_INT < 30 && children != null && folder.canWrite())
        }
        if (children == null) return listOf(Item("无法读取此目录",
            if (androidSubfolder(folder)) "目录不存在，或当前系统未开放访问。" else "请检查系统权限，或返回上一层。",
            kind = "empty")) to false
        val rows = sortFiles(children.filter(::included)).map { fileItem(it) }
        return rows.ifEmpty { listOf(Item("这里没有文件", kind = "empty")) } to folder.canWrite()
    }
    private fun loadPage(){
        dragSelection.cancelGesture()
        val samePage=renderedKey==routeKey();val sameRoute=samePage&&renderedSearch==query
        if(samePage&&renderedSearch!=query){pendingAnchor=null;restoredScroll=null;recycler.scrollToPosition(0)}
        renderedSearch=query
        if(sameRoute&&pendingAnchor==null&&restoredScroll==null)pendingAnchor=captureViewport()
        preparePageMotion();loading?.cancel()
        val ticket=++loadGeneration;val requestedRoute=routeKey()
        val prefix=mutableListOf(Item("",kind="header"))
        if(page==Page.ARCHIVES){prefix+=Item("",kind="homeActions");prefix+=Item("最近的压缩包",kind="section")}
        fun publish(rows:List<Item>,restore:Boolean=true){
            if(ticket!=loadGeneration||requestedRoute!=routeKey())return
            dragSelection.cancelGesture()
            // Snapshot the old identities before progressive results replace the data.
            if(restore&&pendingAnchor==null&&restoredScroll==null&&pageGhost==null)pendingAnchor=captureViewport()
            listItems.clear();listItems.addAll(prefix);listItems.addAll(rows);refreshViews(restore)
        }
        if(!sameRoute||listItems.isEmpty()){listItems.clear();listItems.addAll(prefix);listItems+=Item("正在读取…",kind="empty");refreshViews(false)}
        if(!storageAllowed()){
            writableDirectory = null
            selected.clear(); selectedEntries.clear()
            publish(listOf(Item("允许访问内部存储","在系统中开启文件访问后，即可浏览本机文件和可用的 Android 文件夹。","phone",action={askStorage()},kind="permission")));return
        }
        when(page){
            Page.HUB->{listItems.clear();listItems.addAll(prefix);buildHub();refreshViews()}
            Page.ARCHIVE->renderArchive()
            Page.DIRECTORY->{
                val folder=directory;val search=query;val hidden=prefs.getBoolean("hidden",false);val reveal=pendingReveal
                loading=scope.launch{
                    val (rows, writable)=withContext(Dispatchers.IO){listDirectory(folder, search, hidden, reveal)}
                    if(ticket!=loadGeneration||requestedRoute!=routeKey())return@launch
                    writableDirectory = folder.takeIf { writable }
                    publish(rows)
                }
            }
            Page.FAVORITES->{val paths=prefs.getStringSet("favorites",emptySet())!!.toList();loading=scope.launch{val rows=withContext(Dispatchers.IO){paths.map{File(it)}.filter{it.exists()}.map{fileItem(it)}};publish(if(rows.isEmpty())listOf(Item("还没有收藏","在文件的更多菜单中添加收藏。",kind="empty"))else rows)}}
            else->{
                val wanted=if(page==Page.ARCHIVES)"压缩包"else if(page==Page.RECENT)"最近"else category
                val search=query;val categoryPage=page==Page.CATEGORY
                val recent=prefs.getStringSet("recent",emptySet())!!.toList()
                val roots=storageRoots();val hidden=prefs.getBoolean("hidden",false)
                loading=scope.launch{
                    val itemCache=HashMap<String,Pair<CategoryCatalog.Entry,Item>>()
                    categoryCatalog.snapshots(wanted,search,recent,roots,hidden).collect{batch->
                        val prepared=withContext(Dispatchers.IO){
                            if(categoryPage)buildCategorySections(batch.entries,wanted,itemCache) to emptyList<Item>()
                            else{
                                val task=currentCoroutineContext()
                                emptyList<CategorySection>() to batch.entries.mapIndexed{index,entry->if(index%64==0)task.ensureActive();fileItem(entry.file,entry)}
                            }
                        }
                        if(ticket!=loadGeneration||requestedRoute!=routeKey())return@collect
                        val items=if(categoryPage)categoryRows(prepared.first)else prepared.second.toMutableList()
                        // A partial index must not consume an external-player/recreation anchor
                        // whose file has not arrived yet. Keep the still-valid previous viewport.
                        if(!batch.complete&&sameRoute&&listItems.any{it.file!=null}){
                            val wantedKey=pendingAnchor?.let{it.keys.getOrNull(it.position)}
                            if(restoredScroll!=null||wantedKey!=null&&items.none{itemKey(it)==wantedKey})return@collect
                        }
                        if(categoryPage)categorySections=prepared.first
                        if(items.isEmpty())items+=Item(if(!batch.complete)"正在查找文件…"else if(wanted=="压缩包")"还没有发现压缩包"else"没有找到文件",if(batch.complete)"可到“文件”中浏览本机目录。"else"",kind="empty")
                        else if(!batch.complete)items+=Item("正在查找更多文件…",kind="empty")
                        if(wanted=="最近"&&batch.complete&&batch.entries.size>=200)items+=Item("显示最近200个文件","其余文件可通过分类或目录查看。",kind="empty")
                        if(page==Page.ARCHIVES)items+=storageItem()
                        publish(items,batch.complete||restoredScroll==null)
                    }
                }
            }
        }
    }
    private fun categoryRows(sections:List<CategorySection> = categorySections):MutableList<Item>{
        val result=mutableListOf<Item>()
        for(group in sections){
            result+=Item(group.title,kind="date",groupKey=group.key,count=group.items.size)
            if(group.key !in collapsedGroups)result.addAll(group.items)
        }
        return result
    }
    /** Catalog metadata and row formatting are resolved on IO before any UI publication. */
    private suspend fun buildCategorySections(entries:List<CategoryCatalog.Entry>,name:String,cache:MutableMap<String,Pair<CategoryCatalog.Entry,Item>>):List<CategorySection>{
        val now=System.currentTimeMillis();val grid=name in listOf("图片","视频")
        val date=SimpleDateFormat("yyyy-MM-dd",Locale.ROOT)
        val groups=LinkedHashMap<String,MutableList<Item>>();val titles=HashMap<String,String>()
        val task=currentCoroutineContext()
        for((index,entry) in entries.withIndex()){
            if(index%64==0)task.ensureActive()
            val day=date.format(Date(entry.modified))
            val item=cache[entry.file.path]?.takeIf{it.first==entry}?.second?:fileItem(entry.file,entry).let{
                val row=if(grid)it.copy(kind="media",duration=entry.duration)else it
                cache[entry.file.path]=entry to row;row
            }
            groups.getOrPut(day){titles[day]=DateSections.title(entry.modified,now);mutableListOf()}.add(item)
        }
        return groups.map{(day,items)->CategorySection("$name|$day",titles.getValue(day),items)}
    }
    private fun toggleDateGroup(key:String){
        dragSelection.cancelGesture()
        pendingAnchor=captureViewport();if(!collapsedGroups.add(key))collapsedGroups.remove(key)
        listItems.clear();listItems+=Item("",kind="header");listItems.addAll(categoryRows());refreshViews()
    }
    private fun sortFiles(files:List<File>):List<File>{
        val by=prefs.getString("sort","name");val order=if(prefs.getBoolean("descending",false))-1 else 1
        return files.sortedWith(Comparator{a,b->if(a.isDirectory!=b.isDirectory)if(a.isDirectory)-1 else 1 else order*when(by){"date"->a.lastModified().compareTo(b.lastModified());"size"->a.length().compareTo(b.length());else->a.name.compareTo(b.name,true)}})
    }
    private fun matches(f:File,type:String):Boolean=CategoryCatalog.matches(type,f.name)
    private fun isArchive(f:File):Boolean=CategoryCatalog.matches("压缩包",f.name)
    private fun buildHub(){
        listItems+=Item("",kind="categories")
        listItems+=Item("下载","本机下载目录","download",action={openDirectory(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))})
        listItems+=Item("收藏","常用文件与文件夹","favorite",action={page=Page.FAVORITES;loadPage()})
        listItems+=Item("最近文件","最近修改与访问的本机文件","recent",action={page=Page.RECENT;loadPage()})
        storageRoots().forEachIndexed{i,f->listItems+=Item(if(i==0)"我的手机"else "存储卡",storageText(f),"phone",action={openDirectory(f)})}
        listItems+=Item("常用位置",kind="section")
        for((name,path) in listOf("相机" to "DCIM/Camera","截屏与录屏" to "DCIM/Screenshots","文档" to "Documents")){
            val f=File(Environment.getExternalStorageDirectory(),path);if(f.isDirectory)listItems+=Item(name,path,"folder",action={openDirectory(f)})
        }
    }
    private fun storageItem()=Item("内部存储",storageText(Environment.getExternalStorageDirectory()),"phone",action={openDirectory(Environment.getExternalStorageDirectory())})
    private fun storageText(f:File)=try{val stat=StatFs(f.path);"可用 ${size(stat.availableBytes)} / 共 ${size(stat.totalBytes)}"}catch(_:Exception){f.path}
    private fun fileItem(f:File,metadata:CategoryCatalog.Entry?=null):Item{
        val directory=metadata==null&&f.isDirectory
        val modified=metadata?.modified?:f.lastModified();val bytes=metadata?.bytes?:f.length()
        return Item(f.name,(if(directory)"文件夹"else (if(isArchive(f))f.extension.uppercase()+" · "else "")+size(bytes))+"  ·  "+SimpleDateFormat("yyyy/MM/dd",Locale.getDefault()).format(Date(modified)),fileArt(f,directory),f,directory=directory)
    }
    private fun fileClick(f:File){
        if(selected.isNotEmpty()){toggleFile(f);return}
        if(f.isDirectory)openDirectory(f)else{rememberRecent(f);if(isArchive(f)){if(Regex(".*\\.(7z\\.\\d+|zip\\.\\d+|[zr]\\d+)$",RegexOption.IGNORE_CASE).matches(f.name))showExtract(f)else openArchive(f)}else openFile(f)}
    }
    private fun toggleFile(f:File){if(!selected.add(f.path))selected.remove(f.path);refreshViews()}
    private fun rememberRecent(f:File){val recent=prefs.getStringSet("recent",emptySet())!!.toMutableSet();recent.add(f.path);if(recent.size>300)recent.remove(recent.first());prefs.edit().putStringSet("recent",recent).apply()}
    private fun openFile(f:File){scope.launch{
        try{
            if (BookMigration.isMigration(f.name)) {
                val outgoing = withContext(Dispatchers.IO) { BookMigration.viewIntent(this@MainActivity, f) }
                try {
                    startActivity(outgoing)
                } catch (_: ActivityNotFoundException) {
                    notice("请安装支持迁移恢复的 BookDoctorC")
                }
                return@launch
            }
            val handoff=FileHandoff(this@MainActivity)
            val outgoing=withContext(Dispatchers.IO){handoff.viewIntent(f)}
            try{startActivity(outgoing)}catch(_:SecurityException){
                // Some MediaStore readers can read but cannot re-grant a URI. Keep the safe provider path.
                startActivity(withContext(Dispatchers.IO){handoff.viewIntent(f,preferMediaStore=false)})
            }
        }catch(_:Exception){notice("没有找到可打开此文件的应用")}
    }}
    private fun openArchive(file:File,password:String?=null){
        if(ActiveOperation.busy()){notice("请等待当前任务完成");return}
        loading?.cancel()
        val ticket=++loadGeneration;val requestedRoute=routeKey()
        loading=scope.launch{
            val task=currentCoroutineContext()
            try{
                val found=withContext(Dispatchers.IO){
                    SafeExtractionEngine({ !task.isActive }).list(file,password).map { Entry(it.name,it.directory,it.size) }
                }
                if(ticket!=loadGeneration||requestedRoute!=routeKey())return@launch
                if(page!=Page.ARCHIVE)archiveReturn=screen();archive=file;archivePassword=password;archivePrefix="";entries=found;page=Page.ARCHIVE;selectedEntries.clear();selected.clear();renderArchive()
            }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){
                if(ticket!=loadGeneration||requestedRoute!=routeKey())return@launch
                if(password==null&&(e.message.orEmpty().contains("password",true)||e.message.orEmpty().contains("密码")))askPassword(file){openArchive(file,it)}else errorDialog("无法预览压缩包",e.message?:"请检查密码或尝试解压")
            }
        }
    }
    private fun renderArchive(){
        dragSelection.cancelGesture()
        preparePageMotion();listItems.clear();listItems+=Item("",kind="header")
        val children=linkedMapOf<String,Entry>()
        for(e in entries){
            if(!e.name.startsWith(archivePrefix))continue
            val rest=e.name.removePrefix(archivePrefix).trimEnd('/');if(rest.isBlank())continue
            val top=rest.substringBefore('/');val hasChildren=rest.contains('/')
            val full=archivePrefix+top
            children[full]=if(hasChildren)Entry(full,true,0)else e.copy(name=full)
        }
        for(e in children.values.sortedWith(compareBy<Entry>{!it.directory}.thenBy{it.name.lowercase()})){
            listItems+=Item(e.name.substringAfterLast('/'),if(e.directory)"文件夹"else if(e.size>=0)size(e.size)else "大小未知",if(e.directory)"folder"else fileArt(File(e.name)),entry=e.name,directory=e.directory)
        }
        if(children.isEmpty())listItems+=Item("这里没有条目",kind="empty")
        refreshViews()
    }
    private fun askPassword(file:File,answer:(String)->Unit){
        val field=edit("输入密码").apply{inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD}
        nativePrompt("${file.name} 的密码",field,"继续"){answer(field.text.toString())}
    }
    private fun showExtract(file:File){
        val dialog=fullDialog("解压文件")
        val container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(10),dp(22),0)}
        var destination=if(FileUtils.isInternalPath(this,file.path))Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)else file.parentFile!!
        container.addView(label(file.name,20f,colors.text,true),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(24)})
        val pathRow=pathRow("解压到",destination){selectedPath->destination=selectedPath}
        container.addView(pathRow)
        val password=edit("密码（没有可留空）").apply{inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;setText(archivePassword?:"")}
        section(container,"密码");container.addView(password,LinearLayout.LayoutParams(-1,dp(52)))
        container.addView(label("会自动创建独立文件夹，保留原压缩包。",13f,colors.secondary),LinearLayout.LayoutParams(-1,dp(64)))
        val scroll=ScrollView(this);scroll.addView(container);dialog.body.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val button=primary("开始解压"){dialog.dialog.dismiss();startExtraction(file,password.text.toString().ifEmpty{null},null,destination)}
        dialog.body.addView(button,LinearLayout.LayoutParams(-1,dp(52)).apply{setMargins(dp(22),dp(12),dp(22),dp(20))});sizeDialog(dialog);dialog.dialog.show()
    }
    private fun startExtraction(file:File,password:String?,items:List<String>?=null,destination:File?=null){
        if(ActiveOperation.busy()){notice("请等待当前任务完成");return}
        val service=SafeExtractionService::class.java
        val dao=FileOperationsDao(this);val job=dao.addFilesForJob(listOf(file.path));ActiveOperation.start("解压文件",file.name)
        val output=destination?:if(FileUtils.isInternalPath(this,file.path))Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)else file.parentFile!!
        ContextCompat.startForegroundService(this,Intent(this,service).apply{putExtra(S.EXTRA_JOB_ID,job);putExtra(S.EXTRA_PASSWORD,password);putExtra(S.EXTRA_DESTINATION_PATH,output.path);if(items!=null)putStringArrayListExtra(S.EXTRA_ITEMS_TO_EXTRACT,ArrayList(items))})
        updateBanner()
    }
    private fun observeEvents(){scope.launch{EventBus.events.collect{event->
        when(event){
            is AppEvent.ArchiveComplete->{categoryCatalog.invalidate();if(hostResumed)event.message?.let{notice(it)};selected.clear();sourcePicker=false;event.dirPath?.let{dir->File(dir).listFiles()?.filter{isArchive(it)}?.forEach{rememberRecent(it)}};loadPage()}
            is AppEvent.ExtractionComplete->{categoryCatalog.invalidate();if(hostResumed)notice("解压完成");selectedEntries.clear();loadPage()}
            is AppEvent.ArchiveError->{categoryCatalog.invalidate();if(hostResumed)errorDialog("操作未完成",event.errorMessage?:"请检查文件与输出位置");loadPage()}
            is AppEvent.ExtractionError->{categoryCatalog.invalidate();if(hostResumed)errorDialog("解压未完成",event.errorMessage);loadPage()}
            is AppEvent.RenameComplete->{if(event.clearSelection)selected.clear();categoryCatalog.invalidate();loadPage()}
            is AppEvent.PhotoTimeSyncComplete->{
                categoryCatalog.invalidate()
                loadPage()
                if(hostResumed) messagePanel(PhotoTimeSync.TITLE,event.message,"好",false){}
            }
            AppEvent.LanFilesReceived->{categoryCatalog.invalidate();loadPage()}
            else->updateBanner()
        };updateBanner()
    }}}
    private fun updateBanner(){
        if(!::banner.isInitialized)return
        val busy = ActiveOperation.busy()
        banner.visibility=if(busy)View.VISIBLE else View.GONE
        bannerText.text=ActiveOperation.phase
        val gutter = contentGutter()
        topControls.setPadding(gutter + dp(12), 0, gutter + dp(12), 0)
        val horizontal = gutter + if(mediaGrid()) dp(24) else 0
        val dockSpace = maxOf(dp(88), dockGeometry?.hostHeight ?: 0)
        val lp = taskStack.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin != gutter + dp(20) || lp.bottomMargin != dockSpace + dp(4)) {
            lp.setMargins(gutter + dp(20), 0, gutter + dp(20), dockSpace + dp(4))
            taskStack.layoutParams = lp
        }
        val bottom = dockSpace + maxOf(dp(if(busy) 92 else 20), taskStack.height + dp(20))
        if (recycler.paddingLeft != horizontal || recycler.paddingRight != horizontal || recycler.paddingBottom != bottom) {
            recycler.setPadding(horizontal, 0, horizontal, bottom)
        }
    }
    @Suppress("DEPRECATION")
    private fun showSearch() {
        if (!storageAllowed()) {
            askStorage()
            return
        }
        val intent = Intent(this, SearchActivity::class.java)
            .putStringArrayListExtra(SearchActivity.EXTRA_ROOTS, ArrayList(storageRoots().map { it.path }))
            .putExtra(SearchActivity.EXTRA_DIRECTORY, directory.path.takeIf { page == Page.DIRECTORY })
            .putExtra(SearchActivity.EXTRA_HIDDEN, prefs.getBoolean("hidden", false))
            .putExtra(SearchActivity.EXTRA_QUERY, query)
        startActivityForResult(intent, SEARCH_REQUEST)
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.search_page_in, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SEARCH_REQUEST || resultCode != RESULT_OK) return
        data?.getStringExtra(SearchActivity.EXTRA_RESULT)?.let { path ->
            if (data.getBooleanExtra(SearchActivity.EXTRA_REVEAL, false)) revealFile(File(path))
            else fileClick(File(path))
        }
    }

    private fun bookmarkedPaths(): Set<String> = prefs.getStringSet(bookmarkKey, emptySet()).orEmpty()

    private fun setBookmarked(path: String, bookmarked: Boolean) {
        val paths = bookmarkedPaths()
        prefs.edit().putStringSet(bookmarkKey, if (bookmarked) paths + path else paths - path).apply()
        notice(getString(if (bookmarked) R.string.bookmark_added else R.string.bookmark_removed))
    }

    private fun showSettings() {
        val anchor = window.decorView.getTag(R.id.native_menu_anchor) as? View
        val folder = directory.takeIf { page == Page.DIRECTORY && storageAllowed() }
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options += "存储访问权限" to { askStorage() }
        options += getString(R.string.bookmarks) to { showBookmarks(anchor) }
        if (folder != null) {
            val path = folder.absolutePath
            val saved = path in bookmarkedPaths()
            options += getString(if (saved) R.string.bookmark_remove_current else R.string.bookmark_add_current) to {
                setBookmarked(path, !saved)
            }
        }
        options += "排序方式" to { anchor?.menuAnchor(); showSort() }
        options += (if (prefs.getBoolean("hidden",false)) "隐藏隐藏文件" else "显示隐藏文件") to {
            prefs.edit().putBoolean("hidden", !prefs.getBoolean("hidden",false)).apply()
            loadPage()
        }
        menu("选项", options.map { it.first }) { options[it].second() }
    }

    private fun showBookmarks(anchor: View?) {
        val folders = bookmarkedPaths().sorted().map(::File)
        anchor?.menuAnchor()
        val title = getString(R.string.bookmarks)
        if (folders.isEmpty()) {
            menu(title, listOf(getString(R.string.bookmarks_empty))) {}
            return
        }
        val rows = folders.map { folder ->
            val path = displayPath(folder)
            val name = if (folder == Environment.getExternalStorageDirectory()) path else folder.name
            NativePopups.Row(name, path, getString(R.string.bookmark_remove_named, path))
        }
        popups.showRows(title, rows, onRemove = { index ->
            setBookmarked(folders[index].absolutePath, false)
            showBookmarks(anchor)
        }) { index ->
            if (!storageAllowed()) askStorage() else openDirectory(folders[index])
        }
    }
    private fun fileMenu(file:File){
        val actions=mutableListOf<Pair<String,()->Unit>>()
        if(isArchive(file))actions+="解压" to {showExtract(file)}
        actions+="压缩" to {showCreate(listOf(file.path))}
        actions+="复制" to {selected.clear();selected.add(file.path);fileOperation("copy")}
        actions+="移动" to {selected.clear();selected.add(file.path);fileOperation("move")}
        actions+="重命名" to {
            val f=edit("名称").apply{setText(file.name)}
            nativePrompt("重命名",f,"保存"){
                val wanted=f.text.toString()
                // A file goes through the MediaStore-aware backend so an indexed item keeps its
                // row and its original insertion/modification dates. Folders keep the old path.
                if(file.isDirectory){
                    runCatching{FileManagerKernel.rename(file,wanted)}.onSuccess{categoryCatalog.invalidate();loadPage()}.onFailure{notice(it.message?:"重命名失败")}
                }else{
                    Thread{
                        val outcome=runCatching{MediaStoreRename.renameSingle(applicationContext,file,wanted)}
                        runOnUiThread{
                            outcome.onSuccess{result->
                                if(result.success){categoryCatalog.invalidate();loadPage()}else errorDialog("重命名",result.message)
                            }.onFailure{notice(it.message?:"重命名失败")}
                        }
                    }.apply{isDaemon=true;name="single-rename"}.start()
                }
            }
        }
        actions+="分享" to {shareFiles(listOf(file))}
        if (file.isFile || file.isDirectory) actions += "传输" to { showLanSend(listOf(file)) }
        actions+="收藏 / 取消收藏" to {val set=prefs.getStringSet("favorites",emptySet())!!.toMutableSet();if(!set.add(file.path))set.remove(file.path);prefs.edit().putStringSet("favorites",set).apply();notice("收藏已更新")}
        actions+="文件信息" to {showFileInfo(file)}
        actions+="删除" to {selected.clear();selected.add(file.path);confirmDelete()}
        menu(file.name,actions.map{it.first}){actions[it].second()}
    }
    private fun showFileInfo(file:File) = showInfoPanel(file) { revealFile(file) }

    private fun revealFile(file: File) {
        val parent = file.parentFile ?: return
        selected.clear();selectedEntries.clear();pendingAnchor=null;restoredScroll=null
        openDirectory(parent);pendingReveal=file.path
    }
    private fun selectionIdentity(item:Item):String?=when{
        item.file!=null->"file:${item.file.path}"
        item.entry!=null->"entry:${item.entry}"
        else->null
    }
    private fun startDragSelection(item:Item){
        val key=selectionIdentity(item)?:return
        val position=listItems.indexOfFirst{selectionIdentity(it)==key}
        if(dragSelection.beginSelection(position))return
        // Accessibility/programmatic long-clicks have no live pointer stream.
        when{item.file!=null->toggleFile(item.file);item.entry!=null->{if(!selectedEntries.add(item.entry))selectedEntries.remove(item.entry);refreshViews()}}
    }
    private fun showBatchRename(){
        dragSelection.cancelGesture()
        renameFiles(selected.map(::File), window.decorView.getTag(R.id.native_menu_anchor) as? View)
    }
    private fun selectionMenu() {
        val files = selected.map(::File)
        if (files.isEmpty()) return
        val actions = mutableListOf<Pair<String, () -> Unit>>(
            "分享" to { shareFiles(files) },
            "删除" to { confirmDelete(files.map { it.path }, directory) }
        )
        addPhotoTimeSyncAction(actions, files)
        if (files.size == 1) actions += "文件信息" to { showFileInfo(files.single()) }
        actions += "取消选择" to { selected.clear(); refreshViews() }
        menu("已选 ${files.size} 项", actions.map { it.first }) { actions[it].second() }
    }
    private fun confirmDelete() = confirmDelete(selected.toList(), directory)
    private fun fileOperation(action:String) = fileOperation(action, directory, selected.toList())
    override fun onFileOperationStarted() = updateBanner()

    fun showCreate(paths:List<String>){
        if(paths.isEmpty()){sourcePicker=true;openDirectory(directory);return}
        createArchive(paths)
    }

    private inner class Rows:RecyclerView.Adapter<Rows.Holder>(){
        inner class Holder(val box:FrameLayout):RecyclerView.ViewHolder(box)
        override fun getItemCount()=listItems.size
        override fun getItemId(position:Int):Long{
            var hash=-3750763034362895579L;for(c in itemKey(listItems[position]))hash=(hash xor c.code.toLong())*1099511628211L
            return hash
        }
        override fun onCreateViewHolder(parent:ViewGroup,viewType:Int)=Holder(FrameLayout(this@MainActivity).apply{layoutParams=RecyclerView.LayoutParams(-1,-2)})
        // Selection changes must not perform a full notifyDataSetChanged: RecyclerView temporarily
        // reports NO_POSITION for every child before the next layout, which made a drag lose its
        // endpoint (fast MOVE/UP right after a selection refresh). Payload rebinds only the chrome
        // and deliberately keeps the existing touch target view alive.
        override fun onBindViewHolder(holder:Holder,position:Int,payloads:MutableList<Any>){
            val item=listItems.getOrNull(position)
            val root=holder.box.getChildAt(0)
            if(payloads.contains(selectionPayload)&&item!=null&&root!=null&&applySelectionChrome(root,item,position))return
            bindItem(holder,position)
        }
        override fun onBindViewHolder(holder:Holder,position:Int){bindItem(holder,position)}
        private fun bindItem(holder:Holder,position:Int){
            val item=listItems[position];holder.box.removeAllViews()
            val view=when(item.kind){"header"->header();"section"->label(item.title,15f,colors.secondary).apply{setPadding(dp(23),dp(24),dp(23),dp(10))};"empty"->emptyView(item);"categories"->categories();"homeActions"->homeActions();"date"->CategoryViews.heading(this@MainActivity,item.title,item.count,item.groupKey in collapsedGroups,position>1){toggleDateGroup(item.groupKey)};"media"->CategoryViews.thumbnail(this@MainActivity,item.file!!,category=="视频",item.duration,selected.contains(item.file.path),selectionCount()>0,{fileClick(item.file)},{startDragSelection(item)});else->rowView(item)}
            val grouped=page in listOf(Page.ARCHIVES,Page.HUB)&&item.kind=="row"
            val params=FrameLayout.LayoutParams(-1,-2)
            if(grouped && wideArchives() && item.file != null){
                val tint = if (item.file.path in selected) colors.selected else colors.page
                view.background = colors.background(tint, dp(22).toFloat())
                params.setMargins(dp(10), dp(4), dp(10), dp(4))
            } else if(grouped){
                val previous=listItems.getOrNull(position-1);val next=listItems.getOrNull(position+1)
                val start=previous?.kind!="row"||item.icon=="phone"||previous.icon=="phone"
                val end=next?.kind!="row"||item.icon=="phone"||next.icon=="phone"
                val top=if(start)dp(27).toFloat()else 0f;val bottom=if(end)dp(27).toFloat()else 0f
                view.background=GradientDrawable().apply{setColor(this@MainActivity.colors.page);cornerRadii=floatArrayOf(top,top,top,top,bottom,bottom,bottom,bottom)}
                params.setMargins(dp(14),if(start&&item.icon=="phone")dp(14)else 0,dp(14),if(end)dp(10)else 0)
            }
            if(item.kind=="date"&&!mediaGrid())params.setMargins(dp(24),0,dp(24),0);if(item.kind=="media")params.setMargins(dp(1),dp(1),dp(1),dp(1))
            holder.box.addView(view,params)
            if(item.kind=="media"&&category=="视频"&&item.duration<=0){
                val path=item.file!!.path;view.tag=path
                scope.launch{val duration=MediaFacts.durationAsync(item.file);if(view.tag==path&&view.isAttachedToWindow)view.findViewById<TextView>(R.id.media_duration)?.apply{text=if(duration>0)DateSections.duration(duration)else"--:--";textSize=if(duration>=3_600_000L)10f else 12f}}
            }
        }
    }
    /** Returns true when the existing view was updated in place; false requests a full rebind. */
    private fun applySelectionChrome(root:View,item:Item,position:Int):Boolean{
        if(item.kind!="row"||root.id!=R.id.file_row)return false
        val chosen=item.file?.path?.let{selected.contains(it)}?:item.entry?.let{selectedEntries.contains(it)}?:false
        // Grouped rows keep their page-coloured card background, exactly as rowView() leaves it.
        val grouped=page in listOf(Page.ARCHIVES,Page.HUB)
        if(wideArchives() && item.file != null) root.background = colors.background(
            if(chosen) colors.selected else colors.page, dp(22).toFloat())
        else if(!grouped)root.setBackgroundColor(if(chosen)colors.selected else colors.page)
        // The click listener already reads live selection state, so only its appearance changes.
        root.findViewById<ImageButton>(R.id.file_trailing)?.let{configureTrailing(it,item,chosen)}
        root.isSelected=chosen
        return true
    }
    private fun configureTrailing(trailing:ImageButton,item:Item,chosen:Boolean){
        val label=if(chosen)"check"else if(selectionCount()>0)"circle"else if(item.file!=null&&!item.directory)"more"else"chevron"
        val description=if(chosen)"取消选择"else if(selectionCount()>0)"选择"else "更多"
        trailing.setImageDrawable(Art.drawable(this,label))
        trailing.accessible(description)
        trailing.imageAlpha=if(chosen)255 else if(selectionCount()>0)77 else 170
    }
    private fun emptyView(item:Item):View=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(28),dp(24),dp(36));addView(label(item.title,16f,colors.secondary),LinearLayout.LayoutParams(-1,-2));if(item.subtitle.isNotEmpty())addView(label(item.subtitle,13f,colors.secondary),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})}
    private fun showSort(){menu("排序",listOf("名称","日期","大小",if(prefs.getBoolean("descending",false))"切换为升序"else"切换为降序"),listOf("name","date","size").indexOf(prefs.getString("sort","name"))){i->if(i<3)prefs.edit().putString("sort",listOf("name","date","size")[i]).apply()else prefs.edit().putBoolean("descending",!prefs.getBoolean("descending",false)).apply();loadPage()}}
    private fun homeActions():View{
        val row=LinearLayout(this).apply{setPadding(dp(8),dp(2),dp(8),dp(10))}
        for((index,name)in listOf("新建压缩","解压文件").withIndex()){
            val card=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(14),0,dp(10),0);background=colors.background(colors.page,dp(27).toFloat());setOnClickListener{if(index==0){sourcePicker=true;openDirectory(directory)}else openCategory("压缩包")};if(index==0)id=R.id.create_archive}
            val icon=ImageView(this).apply{setImageDrawable(Art.drawable(context,if(index==0)"archive"else"extract"))}
            card.addView(icon,LinearLayout.LayoutParams(dp(40),dp(40)));card.addView(label(name,16f).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(8)})
            row.addView(card,LinearLayout.LayoutParams(0,dp(66),1f).apply{setMargins(dp(6),0,dp(6),0)})
        };return row
    }
    private fun categories():View{
        val grid=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),0,dp(8),dp(14))}
        val names=listOf("文档","图片","视频","音乐","压缩包","安装包")
        val hues=listOf(0xffffba1c.toInt(),0xff8064f4.toInt(),0xff8064f4.toInt(),0xfffa403c.toInt(),0xff3986f9.toInt(),0xff27c83c.toInt())
        val icons=listOf("file","image","video","music","archive","apk")
        for(pair in 0..2){val row=LinearLayout(this);for(i in pair*2..pair*2+1){val name=names[i]
            val card=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),0,dp(12),0);background=colors.background(colors.page,dp(27).toFloat());setOnClickListener{openCategory(name)}}
            val image=ImageView(this).apply{setImageDrawable(Art.drawable(context,icons[i]))}
            card.addView(image,LinearLayout.LayoutParams(dp(40),dp(40)));card.addView(label(name,18f),LinearLayout.LayoutParams(0,dp(64),1f).apply{leftMargin=dp(10)})
            row.addView(card,LinearLayout.LayoutParams(0,dp(66),1f).apply{setMargins(dp(6),dp(5),dp(6),dp(5))})
        };grid.addView(row)};return grid
    }
    private fun rowView(item:Item):View{
        val outer=LinearLayout(this).apply{id=R.id.file_row;orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(20),dp(18),dp(12),dp(18));minimumHeight=dp(96)}
        val chosen=item.file?.path?.let{selected.contains(it)}?:item.entry?.let{selectedEntries.contains(it)}?:false
        if(item.kind=="create")outer.background=colors.background(colors.selected,dp(22).toFloat())else outer.setBackgroundColor(if(chosen)colors.selected else colors.page)
        val ext=item.file?.extension?.lowercase()?:""
        val typeColor=when{item.directory->0xffffbe27.toInt();ext=="7z"->0xff8b72f9.toInt();ext in listOf("pdf","ppt","pptx")->0xfff36d68.toInt();ext in listOf("xls","xlsx")->0xff4dcc72.toInt();else->colors.blue}
        val icon=FrameLayout(this).apply{background=colors.background(Color.TRANSPARENT,dp(8).toFloat());clipToOutline=true}
        val image=ImageView(this).apply{setImageDrawable(Art.drawable(context,if(item.directory)"folder"else item.icon))}
        icon.addView(image,FrameLayout.LayoutParams(-1,-1))
        outer.addView(icon,LinearLayout.LayoutParams(dp(42),dp(42)))
        item.file?.takeIf{matches(it,"图片")}?.let{image.setPadding(0,0,0,0);image.scaleType=ImageView.ScaleType.CENTER_CROP;Glide.with(this).load(it).disallowHardwareConfig().thumbnail(.15f).into(image)}
        val words=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(2),0,dp(2))}
        val name=label(item.title,17f,colors.text,false).apply{id=R.id.file_title;maxLines=2;ellipsize=TextUtils.TruncateAt.END;setLineSpacing(dp(3).toFloat(),1f)}
        words.addView(name,LinearLayout.LayoutParams(-1,-2))
        if(item.subtitle.isNotBlank())words.addView(label(item.subtitle,13f,colors.secondary).apply{id=R.id.file_subtitle;maxLines=2;ellipsize=TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        outer.addView(words,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(14)})
        val trailing=iconButton("more","更多"){when{item.file!=null->if(selected.isNotEmpty())toggleFile(item.file)else fileMenu(item.file);item.entry!=null->{if(!selectedEntries.add(item.entry))selectedEntries.remove(item.entry);refreshViews()};else->item.action?.invoke()}}
        trailing.id=R.id.file_trailing;configureTrailing(trailing,item,chosen)
        outer.addView(trailing,LinearLayout.LayoutParams(dp(48),dp(48)).apply{leftMargin=dp(8)})
        if(item.kind=="create"){outer.id=R.id.create_archive}
        outer.setOnClickListener{when{item.action!=null->item.action.invoke();item.file!=null->fileClick(item.file);item.entry!=null->{if(selectedEntries.isNotEmpty()){if(!selectedEntries.add(item.entry))selectedEntries.remove(item.entry);refreshViews()}else if(item.directory){archivePrefix=item.entry.trimEnd('/')+"/";renderArchive()}else showExtract(archive!!)}}}
        outer.setOnLongClickListener{startDragSelection(item);true}
        icon.setOnClickListener{outer.performClick()}
        icon.setOnLongClickListener{outer.performLongClick()}
        return outer
    }

    private fun importBookMigration(value: Intent) {
        if (migrationImport?.isActive == true || pendingBookMigration != null) {
            notice("已有迁移文件正在准备")
            return
        }
        notice("正在准备 BookDoctorC 迁移文件…")
        migrationImport = scope.launch {
            var staged: File? = null
            try {
                withContext(Dispatchers.IO) {
                    val operation = currentCoroutineContext()
                    staged = BookMigration.stage(applicationContext, value, operation::ensureActive)
                }
                pendingBookMigration = checkNotNull(staged)
                staged = null
                presentBookMigration()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorDialog("无法准备迁移文件", e.message ?: "请重新从 BookDoctorC 发起")
            } finally {
                staged?.let(::releaseMigration)
            }
        }
    }

    private fun presentBookMigration() {
        if (!hostResumed) return
        val staged = pendingBookMigration ?: return
        pendingBookMigration = null
        showLanSend(listOf(staged)) { BookMigration.discard(staged) }
    }

    private fun handleIntent(value:Intent?){
        if(value==null)return
        if (value.action == BookMigration.SEND_ACTION) {
            importBookMigration(value)
            return
        }
        if(value.action==ACTION_OPEN_DIRECTORY){value.getStringExtra(EXTRA_DIRECTORY_PATH)?.let{File(it).takeIf{f->f.isDirectory}?.let{f->openDirectory(f)}};return}
        val uris=when(value.action){Intent.ACTION_VIEW->listOfNotNull(value.data);Intent.ACTION_SEND->listOfNotNull(value.getParcelableExtra<Uri>(Intent.EXTRA_STREAM));Intent.ACTION_SEND_MULTIPLE->value.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.toList()?:emptyList();else->emptyList()}
        if(uris.isEmpty())return
        scope.launch{
            try{
                val files=withContext(Dispatchers.IO){
                    val handoff=FileHandoff(this@MainActivity)
                    uris.map{uri->handoff.importFile(uri)}
                }
                if(value.action==Intent.ACTION_VIEW&&files.size==1&&isArchive(files.first())){openArchive(files.first());return@launch}
                showCreate(files.map{it.path})
            }catch(e:Exception){errorDialog("无法打开分享文件",e.message?:"请检查来源权限")}
        }
    }
    companion object{
        private const val SEARCH_REQUEST = 32
        const val ACTION_CREATE_ARCHIVE="com.wirelessalien.zipxtract.ACTION_CREATE_ARCHIVE"
        const val EXTRA_ARCHIVE_TYPE="com.wirelessalien.zipxtract.EXTRA_ARCHIVE_TYPE"
        const val ACTION_OPEN_DIRECTORY="com.wirelessalien.zipxtract.ACTION_OPEN_DIRECTORY"
        const val EXTRA_DIRECTORY_PATH="com.wirelessalien.zipxtract.EXTRA_DIRECTORY_PATH"
    }
}
