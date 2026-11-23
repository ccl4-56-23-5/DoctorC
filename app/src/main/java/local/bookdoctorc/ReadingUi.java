package local.bookdoctorc;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import org.json.*;

final class ReadingUi {
    static final String MODE_KEY="page_mode";
    private final MainActivity activity;
    private final ShelfUi ui;
    private final Library library;
    Library.Book book;
    private final TextView title;
    volatile ReaderDocument document;
    final LinearLayout view;
    final WebView web;
    private final TextView counter;
    private final TextView previous,next;
    private final LinearLayout searchReturn;
    private final ReadingClock clock;
    private final GestureDetector taps;
    private final int touchSlop;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable checkpoint=this::positionChanged;
    private final TreeMap<Integer,Rect> sections=new TreeMap<>();
    private ReaderLayout.Mode mode;
    private ValueAnimator slide;
    private int index, requested, serial, pageCount=1, pageIndex;
    private double desiredPosition;
    private boolean loading=true, active=true, slidingGesture, dragClassified, multiTouch;
    private float downX,downY;
    private int startX;
    private ReaderTypography typography;
    private ReaderDocument.Position searchOrigin;
    private String requestedFragment;
    private Runnable firstFrame;

    private record Rect(int top,int height) {}

    void updateBookInfo(Library.Book updated) {
        book = updated;
        title.setText(updated.title());
        clock.updateBook(updated);
    }

    private static final int FIRST_GENERATION = 1;

    record Start(ReaderTypography typography, ReaderLayout.Mode mode, ReaderDocument.Position position,
                 ReaderLayout.Rendered content) {}

    static Start prepare(ReaderDocument doc, Library.Book book, Library.Bookmark bookmark,
                         ReaderTypography typography, ReaderLayout.Mode mode, boolean dark) throws IOException {
        ReaderDocument.Position position = bookmark == null
                ? doc.position(book.chapter(), book.position(), book.offset())
                : doc.position(bookmark.chapter(), bookmark.position(), bookmark.offset());
        ReaderLayout.Rendered content = ReaderLayout.render(doc, position.chapter(), mode, typography,
                dark, FIRST_GENERATION, null);
        return new Start(typography, mode, position, content);
    }

    @SuppressLint("SetJavaScriptEnabled")
    ReadingUi(MainActivity activity,ShelfUi ui,Library library,Library.Book book,ReaderDocument doc,
              Start start) {
        this.activity=activity;
        this.ui=ui;
        this.library=library;
        this.book=book;
        document=doc;
        typography=start.typography();
        mode=start.mode();
        clock=new ReadingClock(library,book);
        view=ui.column();
        view.setBackgroundColor(ui.paper);
        title=ui.text(book.title(),17,ui.ink,true);
        view.addView(header(ui, title, activity::closeReader, anchor -> activity.showReaderSearch(this, anchor)));
        view.addView(ui.divider());
        web=new WebView(activity);
        web.setBackgroundColor(ui.paper);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setHorizontalScrollBarEnabled(false);
        WebSettings settings=web.getSettings();
        // Content Security Policy forbids document scripts. JavaScript evaluation is for local layout only.
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBlockNetworkLoads(true);
        settings.setSafeBrowsingEnabled(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setDomStorageEnabled(false);
        settings.setDefaultTextEncodingName("UTF-8");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request) {
                Uri url=request.getUrl();
                if("data".equals(url.getScheme())) return null;
                if(!"https".equals(url.getScheme()) || !ReaderLayout.HOST.equals(url.getHost())) return denied();
                String path=url.getPath();
                if(path==null || path.length()<2) return denied();
                if (ReaderFontFile.PATH.equals(path)) {
                    try {
                        return new WebResourceResponse("font/ttf", null, 200, "OK",
                                Collections.singletonMap("Cache-Control", "no-store"),
                                new FileInputStream(ReaderFontFile.file(activity)));
                    } catch (IOException unavailable) { return denied(); }
                }
                try {
                    ReaderDocument.Asset asset=document.asset(path.substring(1));
                    if(asset!=null) return new WebResourceResponse(asset.mime(),"UTF-8",200,"OK",
                            Collections.singletonMap("Cache-Control","no-store"),new ByteArrayInputStream(asset.bytes()));
                } catch(IOException | RuntimeException ignored) {}
                return denied();
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request) {
                Uri url=request.getUrl();
                if(!request.isForMainFrame() || !"https".equals(url.getScheme()) || !ReaderLayout.HOST.equals(url.getHost())) return true;
                String path=url.getPath();
                if(path==null || path.length()<2) return true;
                String local=path.substring(1);
                for(int i=0;i<document.chapters.size();i++) if(local.equals(document.chapters.get(i).path())) {
                    show(i,0,url.getFragment());
                    break;
                }
                return true;
            }

            @Override public void onPageFinished(WebView v,String url) {
                if(!active) return;
                int generation=serial;
                v.postDelayed(()->measure(generation,0),130);
            }
        });
        web.setOnScrollChangeListener((v,x,y,ox,oy)->{
            if(!active || loading) return;
            if(mode==ReaderLayout.Mode.VERTICAL) updateVerticalIndex();
            handler.removeCallbacks(checkpoint);
            handler.postDelayed(checkpoint,160);
        });
        web.setOnTouchListener(this::touch);
        FrameLayout page = new FrameLayout(activity);
        page.addView(web, new FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH));
        searchReturn = ui.row();
        searchReturn.setBackground(ui.rounded(ui.dark ? 0xff48372c : 0xfffae8d2, 24));
        searchReturn.setElevation(ui.dp(5));
        searchReturn.addView(ui.action("回到搜索前位置", this::returnFromSearch));
        TextView dismiss = ui.action("×", this::dismissSearchReturn);
        dismiss.setContentDescription("关闭搜索返回栏");
        searchReturn.addView(dismiss);
        searchReturn.setVisibility(View.GONE);
        FrameLayout.LayoutParams floating = new FrameLayout.LayoutParams(ShelfUi.WRAP, ShelfUi.WRAP, Gravity.END | Gravity.BOTTOM);
        floating.setMargins(ui.dp(10), 0, ui.dp(12), ui.dp(10));
        page.addView(searchReturn, floating);
        view.addView(page,new LinearLayout.LayoutParams(ShelfUi.MATCH,0,1));
        view.addView(ui.divider());
        LinearLayout controls=ui.row();
        controls.setPadding(ui.dp(8),ui.dp(3),ui.dp(8),ui.dp(3));
        previous=ui.action("上一章",()->step(-1));
        controls.addView(previous);
        counter=ui.action("目录", null);
        counter.setOnClickListener(anchor -> activity.showContents(this, anchor));
        controls.addView(counter,new LinearLayout.LayoutParams(0,ui.dp(50),1));
        next=ui.action("下一章",()->step(1));
        controls.addView(next);
        view.addView(controls);
        touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        taps = new GestureDetector(activity, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) { return true; }
        });
        taps.setOnDoubleTapListener(null);
        requested = index = start.position().chapter();
        desiredPosition = start.position().fraction();
        loading = true;
        serial = FIRST_GENERATION;
        refreshControls();
        load(start.content(), typography);
        persist(true);
    }

    private static WebResourceResponse denied() {
        return new WebResourceResponse("text/plain","UTF-8",404,"Unavailable",
                Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
    }

    int index(){return index;}
    ReaderDocument.Chapter chapter(){return document.chapters.get(index);}
    ReaderLayout.Mode mode(){return mode;}

    static LinearLayout header(ShelfUi ui, TextView title, Runnable close) {
        return header(ui, title, close, null);
    }

    private static LinearLayout header(ShelfUi ui, TextView title, Runnable close, View.OnClickListener search) {
        LinearLayout header = ui.row();
        header.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
        TextView back = ui.back(close);
        int endWidth = Math.max(ui.dp(68), (int) Math.ceil(back.getPaint().measureText(back.getText().toString()))
                + back.getPaddingLeft() + back.getPaddingRight());
        header.addView(back, new LinearLayout.LayoutParams(endWidth, ShelfUi.WRAP));
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, ui.dp(49), 1));
        FrameLayout end = new FrameLayout(ui.context);
        if (search != null) {
            end.addView(PaperMenu.searchButton(ui, "搜索正文", search),
                    new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.END | Gravity.CENTER_VERTICAL));
        }
        header.addView(end, new LinearLayout.LayoutParams(endWidth, ui.dp(49)));
        return header;
    }

    void whenFirstFrame(Runnable action) {
        firstFrame = action;
        handler.postDelayed(this::firstFrameReady, 2300);
    }

    private void firstFrameReady() {
        Runnable action = firstFrame;
        firstFrame = null;
        if (active && action != null) action.run();
    }

    void show(int chapter,double offset,String fragment) {
        show(chapter, offset, fragment, null);
    }

    private void show(int chapter,double offset,String fragment,ReaderSearch.Hit hit) {
        if(chapter<0 || chapter>=document.chapters.size()) return;
        if(slide!=null) slide.cancel();
        requested=index=chapter;
        requestedFragment=fragment;
        desiredPosition=Math.max(0,Math.min(1,offset));
        loading=true;
        int generation=++serial;
        refreshControls();
        ReaderDocument source=document;
        ReaderLayout.Mode layout=mode;
        ReaderTypography style = typography;
        activity.io(()->ReaderLayout.render(source,chapter,layout,style,ui.dark,generation,hit), page->{
            if(!active || source!=document || generation!=serial) return;
            load(page, style);
        });
    }

    private void load(ReaderLayout.Rendered page, ReaderTypography style) {
        web.getSettings().setTextZoom(Math.round(style.size() * 100f / ReaderTypography.Metric.SIZE.defaultValue));
        web.loadDataWithBaseURL(page.url(), page.html(), "text/html", "UTF-8", page.url());
    }

    private void measure(int generation,int attempt) {
        if(!active || generation!=serial || !loading) return;
        String fragment=requestedFragment==null?"null":JSONObject.quote(requestedFragment);
        web.evaluateJavascript("(function(){if(document.documentElement.dataset.bookdoctorRun!=='"+generation+"')return null;"
                +"if(document.fonts&&document.fonts.status==='loading'&&"+attempt+"<12)return 'fonts';"
                +"let result=[document.documentElement.scrollWidth,0,0];"
                +"for(let s of document.querySelectorAll('section[data-bookdoctor-chapter]')){"
                +"let r=s.getBoundingClientRect();let id=Number(s.dataset.bookdoctorChapter);"
                +"result.push(id,Math.round(r.top+scrollY),Math.round(r.height));"
                +"if(id==="+requested+"&&"+fragment+"!==null){let el=Array.from(s.querySelectorAll('[id]')).find(x=>x.id==="+fragment+");"
                +"if(el){let p=el.getBoundingClientRect();result[1]=p.top+scrollY;result[2]=p.left+scrollX;}}}return result;})()", result->{
            if(!active || generation!=serial || "null".equals(result)) return;
            if ("\"fonts\"".equals(result)) {
                handler.postDelayed(() -> measure(generation, attempt + 1), 120);
                return;
            }
            try{
                JSONArray data=new JSONArray(result);
                if(data.length()<6) throw new JSONException("layout");
                float scale=web.getScale();
                sections.clear();
                for(int i=3;i+2<data.length();i+=3) sections.put(data.getInt(i),new Rect(Math.round(data.getInt(i+1)*scale),
                        Math.max(1,Math.round(data.getInt(i+2)*scale))));
                Rect current=sections.get(requested);
                if(current==null) throw new JSONException("chapter");
                if(mode==ReaderLayout.Mode.VERTICAL) {
                    int y=requestedFragment!=null && data.getDouble(1)>0?Math.round((float)data.getDouble(1)*scale):
                            current.top+(int)Math.round((current.height-1)*desiredPosition);
                    web.scrollTo(0,y);
                } else {
                    int width=Math.max(1,web.getWidth());
                    pageCount=Math.max(1,(int)Math.ceil((data.getDouble(0)*scale-2)/width));
                    pageIndex=requestedFragment!=null?Math.min(pageCount-1,(int)(data.getDouble(2)*scale/width)):
                            (int)Math.round(desiredPosition*(pageCount-1));
                    web.scrollTo(pageIndex*width,0);
                }
                loading=false;
                refreshControls();
                persist(false);
                if (firstFrame != null) web.postVisualStateCallback(generation, new WebView.VisualStateCallback() {
                    @Override public void onComplete(long requestId) { firstFrameReady(); }
                });
            }catch(JSONException | RuntimeException error) {
                if(attempt<5) handler.postDelayed(()->measure(generation,attempt+1),160);
                else loading=false;
            }
        });
    }

    private void refreshControls() {
        boolean vertical=mode==ReaderLayout.Mode.VERTICAL;
        previous.setText(vertical?"上一章":"上一页");
        next.setText(vertical?"下一章":"下一页");
        previous.setEnabled(index>0 || (!vertical && pageIndex>0));
        next.setEnabled(index+1<document.chapters.size() || (!vertical && pageIndex+1<pageCount));
        previous.setAlpha(previous.isEnabled()?1:.43f);
        next.setAlpha(next.isEnabled()?1:.43f);
        counter.setText(vertical?"目录  "+(index+1)+"/"+document.chapters.size():
                "目录  "+(index+1)+" · "+(pageIndex+1)+"/"+pageCount);
    }

    private void updateVerticalIndex() {
        int y=web.getScrollY()+ui.dp(11);
        int previous=index;
        if(!sections.isEmpty())index=sections.firstKey();
        for(var entry:sections.entrySet()) if(entry.getValue().top<=y) index=entry.getKey(); else break;
        if(previous!=index) refreshControls();
    }

    private void positionChanged() {
        if(!active || loading) return;
        persist(false);
        if(mode==ReaderLayout.Mode.VERTICAL && !sections.isEmpty()) {
            int maximum=Math.max(0,Math.round(web.getContentHeight()*web.getScale())-web.getHeight());
            if(web.getScrollY()>=maximum-ui.dp(12) && maximum>ui.dp(6)
                    && sections.lastKey()+1<document.chapters.size()) {
                if(sections.lastKey()==requested)show(requested+1,0,null);
                else if(index>requested)show(index,position(),null);
            } else if(web.getScrollY()<=ui.dp(3) && sections.firstKey()>0 && index<requested) {
                show(index,position(),null);
            }
        }
    }

    private boolean touch(View ignored, MotionEvent event) {
        if (loading) return false;
        boolean vertical = mode == ReaderLayout.Mode.VERTICAL;
        float x = event.getX(), y = event.getY();
        boolean tap = taps.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                if (slide != null) slide.cancel();
                slidingGesture = dragClassified = multiTouch = false;
                downX = x;
                downY = y;
                startX = web.getScrollX();
            }
            case MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true;
                if (!vertical) snap(pageIndex);
            }
            case MotionEvent.ACTION_MOVE -> {
                if (vertical || multiTouch) return !vertical;
                float dx = x - downX, dy = y - downY;
                if (!dragClassified && dx * dx + dy * dy > touchSlop * touchSlop) {
                    dragClassified = true;
                    slidingGesture = Math.abs(dx) > Math.abs(dy);
                }
                if (slidingGesture && mode == ReaderLayout.Mode.SLIDE) {
                    web.scrollTo(Math.max(0, Math.min((pageCount - 1) * web.getWidth(), startX - Math.round(dx))), 0);
                }
            }
            case MotionEvent.ACTION_UP -> {
                if (vertical) {
                    if (!tap || webHasLink()) return false;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    web.onTouchEvent(cancel);
                    cancel.recycle();
                    useTapZone(x, y);
                } else {
                    float dx = x - downX;
                    if (!multiTouch && slidingGesture && Math.abs(dx) > ui.dp(27)) step(dx < 0 ? 1 : -1);
                    else if (tap && !dragClassified && !multiTouch) useTapZone(x, y);
                    else snap(pageIndex);
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL -> {
                if (!vertical) snap(pageIndex);
            }
        }
        return !vertical;
    }

    private boolean webHasLink() {
        WebView.HitTestResult hit = web.getHitTestResult();
        if (hit == null) return false;
        return switch (hit.getType()) {
            case WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                    WebView.HitTestResult.PHONE_TYPE, WebView.HitTestResult.EMAIL_TYPE,
                    WebView.HitTestResult.GEO_TYPE, WebView.HitTestResult.EDIT_TEXT_TYPE -> true;
            default -> false;
        };
    }

    private void useTapZone(float x, float y) {
        float across = x / web.getWidth(), down = y / web.getHeight();
        boolean menu = down < .23f || (down < .40f && across >= .35f && across < .65f);
        if (menu) {
            activity.readerMenu(this);
            return;
        }
        int direction = across < .35f ? -1 : 1;
        if (mode != ReaderLayout.Mode.VERTICAL) {
            step(direction);
            return;
        }
        int current = web.getScrollY();
        int maximum = Math.max(0, Math.round(web.getContentHeight() * web.getScale()) - web.getHeight());
        int page = Math.max(1, web.getHeight() - ui.dp(30));
        int target = Math.max(0, Math.min(maximum, current + direction * page));
        if (target != current) {
            animateOffset(0, target, true);
        } else if (!sections.isEmpty()) {
            if (direction < 0 && sections.firstKey() > 0) show(sections.firstKey() - 1, 1, null);
            if (direction > 0 && sections.lastKey() + 1 < document.chapters.size()) show(sections.lastKey() + 1, 0, null);
        }
    }

    private void step(int direction) {
        if(loading) return;
        if(mode==ReaderLayout.Mode.VERTICAL) {
            show(index+direction,0,null);
        } else {
            int target=pageIndex+direction;
            if(target<0) show(index-1,1,null);
            else if(target>=pageCount) show(index+1,0,null);
            else snap(target);
        }
    }

    private void snap(int target) {
        pageIndex=target;
        animateOffset(target * web.getWidth(), 0, mode == ReaderLayout.Mode.SLIDE);
        refreshControls();
        persist(false);
    }

    private void animateOffset(int x, int y, boolean animated) {
        if (slide != null) slide.cancel();
        int startX = web.getScrollX(), startY = web.getScrollY();
        if (!animated || !ValueAnimator.areAnimatorsEnabled() || (startX == x && startY == y)) {
            web.scrollTo(x, y);
            return;
        }
        slide = ValueAnimator.ofFloat(0, 1);
        slide.setDuration(Transitions.atRate(210));
        slide.setInterpolator(Transitions.EASE);
        slide.addUpdateListener(value -> {
            float fraction = (float) value.getAnimatedValue();
            web.scrollTo(startX + Math.round((x - startX) * fraction), startY + Math.round((y - startY) * fraction));
        });
        slide.start();
    }

    void setTypography(ReaderTypography style){
        if (typography.equals(style) && style.font() != ReaderTypography.Font.IMPORTED) return;
        double current=position();
        typography=style;
        show(index,current,null);
    }
    void setMode(ReaderLayout.Mode value){double current=position();mode=value;show(index,current,null);}
    void startClock(){if(active)clock.start();}
    void stopClock(){clock.stop();}

    void search(String query, java.util.function.BooleanSupplier cancelled, java.util.function.Consumer<ReaderSearch.Result> done) {
        ReaderDocument source = document;
        ReaderTypography style = typography;
        activity.io(() -> ReaderSearch.find(source, style, query, cancelled), done);
    }

    void jumpToSearchHit(ReaderSearch.Hit hit) {
        if (searchOrigin == null) searchOrigin = new ReaderDocument.Position(index, position());
        searchReturn.setVisibility(View.VISIBLE);
        show(hit.chapter(), 0, ReaderSearch.ANCHOR, hit);
    }

    private void returnFromSearch() {
        ReaderDocument.Position origin = searchOrigin;
        dismissSearchReturn();
        if (origin != null) show(origin.chapter(), origin.fraction(), null);
    }

    private void dismissSearchReturn() {
        searchOrigin = null;
        searchReturn.setVisibility(View.GONE);
    }

    void replace(ReaderDocument revised) {
        double progress=position();
        int absolute=document.offset(index,progress);
        ReaderDocument old=document;
        document=revised;
        activity.closeDocument(old);
        var position=revised.position(index,progress,absolute);
        show(position.chapter(),position.fraction(),null);
    }

    void bookmark(String label){
        double progress=position();
        library.addBookmark(book,label,index,progress,document.offset(index,progress));
    }

    private double position() {
        if(loading) return desiredPosition;
        if(mode!=ReaderLayout.Mode.VERTICAL) return pageCount<=1?0:pageIndex/(double)(pageCount-1);
        Rect section=sections.get(index);
        if(section==null) return desiredPosition;
        return Math.max(0,Math.min(1,(web.getScrollY()-section.top)/(double)Math.max(1,section.height-1)));
    }

    void persist(boolean opened){
        double progress=position();
        library.saveProgress(book,index,progress,document.offset(index,progress),opened);
    }

    void dispose() {
        stopClock();
        persist(false);
        active=false;
        if(slide!=null) slide.cancel();
        handler.removeCallbacksAndMessages(null);
        web.stopLoading();
        if (web.getParent() instanceof ViewGroup parent) parent.removeView(web);
        web.destroy();
        activity.closeDocument(document);
    }
}
