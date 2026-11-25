package local.bookdoctorc;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.*;
import com.example.clmplayer.LibraryItem;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import android.view.inputmethod.InputMethodManager;
import local.bookdoctorc.PaperMenu.Icon;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

public final class MainActivity extends Activity {
    private static final int PICK_FOLDER = 41, EXPORT_BOOK = 42, CREATE_BACKUP = 43, PICK_READER_FONT = 44, PICK_AUDIO_FOLDER = 45, PICK_MUSIC_FOLDER = 46, PICK_MIGRATION = 47, SAVE_MIGRATION = 48, HANDOFF_MIGRATION = 49;
    private static final int FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private Library library;
    private ClmPlayback playback;
    private ModuleTabs moduleTabs;
    private MusicPlayback music;
    private MusicPage.Mini sharedMini;
    private Panel musicPlayerPanel;
    private int recordModule;
    private File migrationDraft;
    private final Runnable audioObserver = this::syncSharedPlayer;
    private boolean bookNavigationContrast;
    private AudiobookPage audioPage;
    private TurntableDeck.Artwork deckArtwork;
    private boolean deckRequested;
    private final Runnable warmPlayer = this::warmPlayerView;
    private int lastPlayerBarColor;
    private final ArgbEvaluator playerBars = new ArgbEvaluator();
    private SourceFiles files;
    private SharedPreferences preferences;
    private ShelfUi ui;
    private Bitmap coverAtlas;
    private FrameLayout root, stage, base, overlay;
    private View shade, tray;
    private ImageView boxBlur;
    private GlassDock dock;
    private MorphSurface boxMorph;
    private ReaderFlight readerFlight;
    private MotionProgress tabMotion;
    private FrameLayout outgoingPage;
    private int pageIndex, incomingTab, outgoingTab, pendingTab = -1;
    private android.window.OnBackInvokedCallback backCallback;
    private boolean readerClosing, readerGesture;
    private boolean folding, resumed;
    private BoxMotion boxMotion;
    private final Map<Long,View> cabinetAnchors=new HashMap<>();
    private Map<Long,View> expandedCovers=Map.of();
    private boolean shelfNeedsRefresh;
    private PreparedReader preparedReader;
    private record PreparedReader(Library.Book book, ReaderDocument document, ReadingUi.Start start) implements Closeable {
        @Override public void close() throws IOException { document.close(); }
    }
    private Panel readerSourcePanel;
    private FolderBrowser browser;
    private final ArrayDeque<Panel> panels=new ArrayDeque<>();
    private record Panel(View view, BiConsumer<Float, Runnable> move, Runnable stop, Runnable release,
                         MotionProgress gesture, Runnable shown) {}
    private ScrollView shelfScroll;
    private ShelfBackdrop shelfBackdrop, cdBackdrop;
    private final Set<Long> pendingBookProgress = new HashSet<>();
    private FrameLayout audioShelfPage, historyPage, bookShelfPage, musicShelfPage;
    private long openBox, importTarget, exportId;
    private boolean exportBackup;
    private File exportDraft;
    private ReadingUi reader;
    private Panel audioPlayerPanel, closingPanel;
    private AlertDialog busyDialog;
    private Dialog paperDialog;
    private BookInfoEditor bookInfo;
    private Panel bookInfoPanel;
    private Bundle restoredBookInfo;
    private boolean bookInfoChangedInReader;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 29) bookNavigationContrast = getWindow().isNavigationBarContrastEnforced();
        library = Library.get(this);
        playback = ClmPlayback.get(this);
        boolean fromMedia = state == null && AudioMediaService.OPEN_PLAYER.equals(getIntent().getAction());
        files = new SourceFiles(this);
        preferences = getSharedPreferences("reader", MODE_PRIVATE);
        moduleTabs = new ModuleTabs(preferences);
        music = MusicPlayback.get(this);
        pageIndex = moduleTabs.startup();
        recordModule = pageIndex;
        if (fromMedia) pageIndex = moduleTabs.preferred(music.isActive() ? ModuleTabs.MUSIC : ModuleTabs.PODCAST);
        coverAtlas = BitmapFactory.decodeResource(getResources(), R.drawable.mook_cover_atlas);
        coverAtlas.prepareToDraw();
        root = new FrameLayout(this);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets inset = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                int bottom = Math.max(inset.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
                view.setPadding(inset.left, inset.top, inset.right, bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        stage = new FrameLayout(this) {
            @Override public boolean onInterceptTouchEvent(MotionEvent event) {
                return tabMotion != null || super.onInterceptTouchEvent(event);
            }
        };
        root.addView(stage, full());
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) installBackCallback();
        ModuleTransfer.recoverDrafts(this, state == null ? null : state.getString("migrationDraft"));
        if (state != null) {
            importTarget = state.getLong("import");
            exportId = state.getLong("export");
            exportBackup = state.getBoolean("backup");
            String draft = state.getString("draft");
            if (draft != null) exportDraft = new File(getCacheDir(), draft);
            int savedPage = state.getInt("page");
            if (!state.containsKey("moduleSchema") && savedPage >= 2) savedPage++;
            pageIndex = moduleTabs.contains(savedPage) ? savedPage : moduleTabs.startup();
            recordModule = moduleTabs.preferred(state.getInt("recordModule",
                    state.getBoolean("listeningRecords") ? ModuleTabs.PODCAST : ModuleTabs.BOOKS));
            String delivery = state.getString("migrationDraft");
            if (delivery != null) migrationDraft = new File(new File(getCacheDir(), "module-transfers"), delivery);
            restoredBookInfo = state.getBundle("bookInfo");
        }
        style();
        renderBase();
        playback.addObserver(audioObserver);
        music.addObserver(audioObserver);
        music.refresh(false);
        if (fromMedia) root.post(() -> showSharedPlayer(null));
        if (state == null && isTransferIntent(getIntent())) root.post(() -> confirmTransfer(getIntent().getData()));
        if (state != null) {
            long box = state.getLong("box"), book = state.getLong("book");
            if (box > 0 && book <= 0 && pageIndex == ModuleTabs.BOOKS) root.post(() -> redraw(box));
            if (book > 0) root.post(() -> {
                Library.Book saved = library.book(book);
                if (saved != null) openReader(saved);
                else restoreBookInfo();
            });
            else if (restoredBookInfo != null) root.post(this::restoreBookInfo);
            else if (state.getBoolean("audioPlayer")) root.post(() -> showSharedPlayer(null));
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (AudioMediaService.OPEN_PLAYER.equals(intent.getAction())) showSharedPlayer(null);
        else if (isTransferIntent(intent)) confirmTransfer(intent.getData());
    }

    private void style() {
        boolean dark = ShelfUi.nightMode(this, preferences.getString("mode", "system")) != 0;
        if (ui == null || ui.dark != dark) {
            audioShelfPage = null;
            bookShelfPage = null;
            musicShelfPage = null;
            historyPage = null;
            audioPage = null;
            ui = new ShelfUi(this, dark, coverAtlas);
            ui.warmRoom(ShelfUi.Room.PLAYER);
            prepareDeckArtwork();
            root.removeCallbacks(warmPlayer);
            root.postDelayed(warmPlayer, 160);
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : light, light);
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private boolean usesAudioPalette() {
        return pageIndex == ModuleTabs.PODCAST || pageIndex == ModuleTabs.MUSIC
                || pageIndex == ModuleTabs.HISTORY && recordModule != ModuleTabs.BOOKS;
    }

    private int audioPaper() {
        boolean musicColors = pageIndex == ModuleTabs.MUSIC || pageIndex == ModuleTabs.HISTORY && recordModule == ModuleTabs.MUSIC;
        return (musicColors ? MusicPage.colors(ui) : AudiobookPage.Colors.forMode(ui.dark)).paper();
    }

    private void applyPagePalette(boolean audio) {
        root.setBackgroundColor(pageIndex == ModuleTabs.PODCAST
                ? DesignHome.podcast(ui.dark).canvas() : audio ? audioPaper() : ui.paper);
        dock.useAudioColors(audio);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(!audio && bookNavigationContrast);
    }

    private void renderBase() { renderBase(false); }

    private void renderBase(boolean slide) {
        int scroll = shelfScroll == null ? 0 : shelfScroll.getScrollY();
        removeOverlay();
        if (!slide) {
            if (tabMotion != null) tabMotion.stop();
            tabMotion = null;
            pendingTab = -1;
            outgoingPage = null;
            stage.removeAllViews();
        } else {
            outgoingPage = base;
        }
        boolean audioColors = usesAudioPalette();
        FrameLayout retained = pageIndex == ModuleTabs.PODCAST ? audioShelfPage
                : pageIndex == ModuleTabs.MUSIC ? musicShelfPage : pageIndex == ModuleTabs.HISTORY ? historyPage
                : pageIndex == 0 && slide && !shelfNeedsRefresh ? bookShelfPage : null;
        if (retained != null) {
            base = retained;
            base.setTranslationX(0);
            base.setTranslationY(0);
            base.setClipBounds(null);
            base.setClipToOutline(false);
            base.setAlpha(1);
            base.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (!slide && base.getTag() instanceof HistoryPage history) history.refresh();
            stage.addView(base, full());
        } else {
            createBasePage(scroll);
        }
        bindBasePage(slide, audioColors);
    }

    private void createBasePage(int scroll) {
        base = new FrameLayout(this);
        boolean home = pageIndex == ModuleTabs.BOOKS || pageIndex == ModuleTabs.PODCAST;
        base.setBackground(pageIndex == ModuleTabs.MUSIC ? MusicPage.wall(ui)
                : pageIndex == ModuleTabs.PODCAST ? DesignHome.podcast(ui.dark).wall() : ui.wall());
        stage.addView(base, full());
        LinearLayout page = ui.column();
        base.addView(page, full());
        int widthLimit = ui.dp(TabletLayout.wide(ui) ? 1200 : 720);
        ScrollView scroller = pageViewport(widthLimit);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setPadding(0, 0, 0, ui.dp(136));
        ShelfBackdrop backdrop = !home ? null : new ShelfBackdrop(base, () ->
                tabMotion == null && overlay == null && readerFlight == null && panels.isEmpty());
        if (pageIndex == 0) shelfBackdrop = backdrop;
        if (pageIndex == 1) cdBackdrop = backdrop;
        scroller.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (dock != null) dock.backdropChanged();
            if (backdrop != null) backdrop.changed();
            if (v.isAttachedToWindow() && page.getParent() instanceof View owner && owner.getTag() instanceof MusicPage musicPage) musicPage.scrolled();
        });
        page.addView(scroller, new LinearLayout.LayoutParams(ShelfUi.MATCH, 0, 1));
        LinearLayout content = ui.column();
        int inset = home && TabletLayout.wide(ui) ? 38 : home ? 19 : 16;
        content.setPadding(ui.dp(inset), ui.dp(home ? 14 : 6), ui.dp(inset), ui.dp(18));
        scroller.addView(content, new ScrollView.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP, Gravity.CENTER_HORIZONTAL));
        if (pageIndex == ModuleTabs.PROFILE) {
            new LibraryPages(this,ui,library).settings(content);
        } else if (pageIndex == ModuleTabs.MUSIC) {
            base.setTag(new MusicPage(this, ui, content, scroller, music));
            musicShelfPage = base;
        } else if (pageIndex == ModuleTabs.HISTORY) {
            FrameLayout recordsPage = base;
            historyPage = recordsPage;
            HistoryPage history = new HistoryPage(library, ui, content, recordModule, playback, music, moduleTabs, this,
                    module -> {
                        if (historyPage != recordsPage) return;
                        recordModule = module;
                        recordsPage.setBackground(module == ModuleTabs.BOOKS ? ui.wall()
                                : module == ModuleTabs.MUSIC ? MusicPage.wall(ui)
                                : new ColorDrawable(AudiobookPage.Colors.forMode(ui.dark).paper()));
                        if (base == recordsPage && dock != null) applyPagePalette(module != ModuleTabs.BOOKS);
                    }, (id, face) -> {
                        Library.Book book = library.book(id);
                        if (book != null) openReader(book, face);
                    }, (source, face) -> openAudioItem(source, face, face.getWidth() * .475f));
            recordsPage.setTag(history);
        } else if (pageIndex == 1) {
            base.setTag(new AudiobookShelf(this, ui, content, playback));
            audioShelfPage = base;
        } else {
            shelfScroll = scroller;
            renderShelf(content);
            bookShelfPage = base;
            if (scroll > 0) scroller.post(() -> scroller.scrollTo(0, scroll));
        }
    }

    private void bindBasePage(boolean slide, boolean audioColors) {
        if (dock == null) {
            dock = new GlassDock(this, ui, this::switchTab);
            boolean wide = TabletLayout.wide(ui);
            int width = Math.min(wide ? 436 : 354, getResources().getConfiguration().screenWidthDp - 32);
            FrameLayout.LayoutParams floating = new FrameLayout.LayoutParams(
                    ui.dp(width + GlassDock.SIDE_OVERSCAN * 2), ui.dp(GlassDock.trayHeight(ui) + GlassDock.VERTICAL_OVERSCAN * 2),
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            floating.bottomMargin = ui.dp(10 - GlassDock.VERTICAL_OVERSCAN);
            root.addView(dock, floating);
        }
        dock.bind(ui, stage, pageIndex, slide, moduleTabs.dock());
        if (sharedMini == null) {
            sharedMini = new MusicPage.Mini(this, ui, music, playback);
            FrameLayout.LayoutParams strip = new FrameLayout.LayoutParams(
                    ui.dp(Math.min(420, getResources().getConfiguration().screenWidthDp - 28)), ui.dp(57),
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            strip.bottomMargin = ui.dp(GlassDock.trayHeight(ui) + 20);
            root.addView(sharedMini.getView(), strip);
        }
        syncSharedPlayer();
        applyPagePalette(audioColors);
        updateLayerAccessibility();
        if (outgoingPage != null) {
            FrameLayout incoming = base;
            View previous = outgoingPage;
            previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            incomingTab = pageIndex;
            int direction = incomingTab > outgoingTab ? 1 : -1;
            tabMotion = new MotionProgress(Transitions.TAB_MS, Transitions.OPEN_CLOSE, fraction -> {
                float distance = stage.getWidth() * direction;
                incoming.setTranslationX(distance * (1 - fraction));
                previous.setTranslationX(-distance * fraction);
                dock.backdropChanged();
            });
            tabMotion.move(1, () -> finishTab(true));
        }
    }

    private ScrollView pageViewport(int maxWidth) {
        return new ScrollView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                View content = getChildAt(0);
                if (content != null && maxWidth > 0 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                    int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
                    content.getLayoutParams().width = Math.max(0, Math.min(available, maxWidth));
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
    }

    private void switchTab(int index) {
        if (!moduleTabs.contains(index)) return;
        if (!panels.isEmpty() || readerFlight != null || overlay != null) return;
        if (tabMotion != null) {
            dock.select(index, true);
            if (index != outgoingTab && index != incomingTab) {
                pendingTab = index;
                return;
            }
            pendingTab = -1;
            if (pageIndex == index) return;
            pageIndex = index;
            applyPagePalette(usesAudioPalette());
            boolean forward = index == incomingTab;
            tabMotion.move(forward ? 1 : 0, () -> finishTab(forward));
            return;
        }
        if (pageIndex == index) return;
        outgoingTab = pageIndex;
        pageIndex = index;
        renderBase(true);
    }

    private void finishTab(boolean forward) {
        if (outgoingPage == null) return;
        if (forward) {
            stage.removeView(outgoingPage);
        } else {
            stage.removeView(base);
            base = outgoingPage;
            base.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
        outgoingPage = null;
        tabMotion = null;
        base.setTranslationX(0);
        dock.backdropChanged();
        if (base.getTag() instanceof AudiobookShelf shelf) shelf.onViewportChanged();
        if (base.getTag() instanceof MusicPage musicPage) musicPage.scrolled();
        if (base.getTag() instanceof HistoryPage history) {
            FrameLayout current = base;
            current.postDelayed(() -> {
                if (base == current && tabMotion == null && panels.isEmpty()) history.refresh();
            }, 120);
        }
        int next = pendingTab;
        pendingTab = -1;
        if (next >= 0) switchTab(next);
    }

    private void renderShelf(LinearLayout content) {
        cabinetAnchors.clear();
        shelfNeedsRefresh = false;
        new BookShelfHome(this, ui, library, content);
    }

    void toggleHomeTheme(View anchor) {
        preferences.edit().putString("mode", ui.dark ? "light" : "dark").apply();
        style();
        renderBase();
    }

    void searchHomeBooks(View anchor) {
        searchBooks("搜索图书", library.allBooks(), anchor);
    }

    void readHomeBook(Library.Book book, View anchor) {
        openReader(book, anchor);
    }

    void homeBookMenu(Library.Book book, View anchor) {
        bookMenu(book, anchor);
    }

    void homeCategoryMenu(Library.Box category, View anchor) {
        boxMenu(category, anchor);
    }

    void sortHomeBooks(Library.Box category, View anchor) {
        chooseBoxOrder(category, anchor);
    }

    void homeModal(String title, LinearLayout body, View anchor, DesignHome.Palette colors) {
        LinearLayout menu = ui.column();
        menu.setBackground(ui.rounded(colors.paper(), 17));
        menu.setPadding(ui.dp(15), ui.dp(13), ui.dp(15), ui.dp(15));
        LinearLayout heading = ui.row();
        TextView name = ui.text(title, 21, colors.ink(), true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(name, true);
        heading.addView(name, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        TextView close = DesignHome.action(ui, "×", colors.accent(), colors.selected(), 10, v -> closePanel());
        close.setContentDescription("关闭" + title);
        close.setTextSize(18);
        heading.addView(close);
        menu.addView(heading);
        ui.spacer(menu, 12);
        ScrollView content = new ScrollView(this) {
            @Override protected void onMeasure(int width, int height) {
                int limit = Math.max(ui.dp(100), root.getHeight() - ui.dp(182)
                        - root.getPaddingTop() - root.getPaddingBottom());
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
        };
        content.setVerticalScrollBarEnabled(false);
        content.addView(body, new ScrollView.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP));
        menu.addView(content, new LinearLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP));
        showMenuLayer(menu, anchor, () -> { if (title.startsWith("搜索")) hideKeyboard(); },
                false, ui.dp(9), ui.dp(17), ui.dark ? 0x8a101318 : 0x67131f30);
    }

    Integer homeBookProgress(Library.Book book, Consumer<Integer> refresh) {
        if (book.readAt() <= 0) return 0;
        String key = "chapters:" + book.uri();
        int count = preferences.getInt(key, 0);
        if (count <= 0 && pendingBookProgress.add(book.id())) {
            submit(() -> {
                try (ReaderDocument doc = ReaderDocument.open(this, files, book)) {
                    return doc.chapters.size();
                } catch (IOException | RuntimeException missing) {
                    return 0;
                }
            }, size -> {
                pendingBookProgress.remove(book.id());
                if (size > 0) {
                    preferences.edit().putInt(key, size).apply();
                    refresh.accept(bookPercent(book, size));
                }
            }, error -> pendingBookProgress.remove(book.id()));
        }
        return count > 0 ? bookPercent(book, count) : null;
    }

    private int bookPercent(Library.Book book, int count) {
        return (int) Math.round(100 * Math.max(0, Math.min(1, (book.chapter() + book.position()) / count)));
    }

    String themeLabel(){
        return switch(preferences.getString("mode","system")){
            case "dark" -> "夜间";case "light" -> "日间";default -> "跟随系统";
        };
    }

    void chooseMode(View anchor) {
        String[] values = {"system", "light", "dark"};
        int current = Arrays.asList(values).indexOf(preferences.getString("mode", "system"));
        String[] labels = {"跟随系统", "日间", "夜间"};
        PaperMenu menu = new PaperMenu(ui, "", "主题模式", "");
        for (int index = 0; index < labels.length; index++) {
            String mode = values[index];
            menu.choice(labels[index], "", index == current, () -> {
                preferences.edit().putString("mode", mode).apply();
                style();
                renderBase();
            });
        }
        menu.showNear(anchor);
    }

    private void restoreBox(long id){
        View anchor=cabinetAnchors.get(id);
        if(anchor==null || anchor.getWidth()>0){restoreMountedBox(id, anchor);return;}
        anchor.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){
            @Override public void onLayoutChange(View view,int l,int t,int r,int b,int ol,int ot,int or,int ob){
                view.removeOnLayoutChangeListener(this);
                view.post(() -> {
                    if(view.isAttachedToWindow() && cabinetAnchors.get(id)==view) restoreMountedBox(id, view);
                });
            }
        });
    }

    private void restoreMountedBox(long id, View anchor) {
        if (anchor != null && !anchor.getLocalVisibleRect(new Rect())) {
            anchor.requestRectangleOnScreen(new Rect(0, 0, anchor.getWidth(), anchor.getHeight()), true);
        }
        showBox(id, false, anchor);
    }

    private void showBox(long id, boolean animated, View anchor) {
        Library.Box box = library.box(id);
        if (box == null || pageIndex != 0) return;
        if (overlay != null && openBox == id && folding) {
            animateBox(1);
            return;
        }
        removeOverlay();
        openBox = id;
        overlay = new FrameLayout(this);
        root.addView(overlay, full());
        overlay.setElevation(ui.dp(12));
        overlay.setClickable(true);
        Bitmap preparedBlur = shelfBackdrop == null ? null : shelfBackdrop.image();
        if (preparedBlur != null) {
            boxBlur = new ImageView(this);
            boxBlur.setImageBitmap(preparedBlur);
            boxBlur.setScaleType(ImageView.ScaleType.FIT_XY);
            overlay.addView(boxBlur, full());
        }
        shade = new View(this);
        shade.setBackgroundColor(ui.dark ? 0x730e0906 : 0x18bb9875);
        shade.setContentDescription("关闭木匣");
        shade.setOnClickListener(v -> animateBox(0));
        overlay.addView(shade, full());
        LinearLayout interior = ui.column();
        interior.setPadding(ui.dp(17), ui.dp(6), ui.dp(17), ui.dp(15));
        interior.setClipToOutline(true);
        interior.setElevation(ui.dp(9));
        interior.setClickable(true);
        OakFrame frame = ui.oakFrame(16);
        interior.setBackground(new BoxMotion.Surface(interior, frame));
        interior.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ui.dp(16));
            }
        });
        FrameLayout.LayoutParams trayParams = new FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP, Gravity.CENTER);
        int trayMargin = Math.max(14, (getResources().getConfiguration().screenWidthDp - 430) / 2);
        trayParams.setMargins(ui.dp(trayMargin), ui.dp(12), ui.dp(trayMargin), ui.dp(12));
        overlay.addView(interior, trayParams);
        tray = interior;
        LinearLayout header = ui.row();
        TextView sort = ui.action("排序", null);
        sort.setOnClickListener(anchorView -> chooseBoxOrder(box, anchorView));
        sort.setPadding(0, 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 30) sort.setStateDescription(box.order().label);
        header.addView(sort);
        FrameLayout center = new FrameLayout(this);
        TextView title = ui.gildedTitle(box.name(), 18);
        title.setMinHeight(ui.dp(33));
        FrameLayout.LayoutParams plate = new FrameLayout.LayoutParams(ShelfUi.WRAP, ShelfUi.WRAP, Gravity.CENTER);
        plate.setMargins(ui.dp(4), 0, ui.dp(4), 0);
        center.addView(title, plate);
        header.addView(center, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        header.addView(PaperMenu.searchButton(ui, "搜索匣内图书", view ->
                searchBooks("搜索 · " + box.name(), library.books(box), view)),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        TextView close = ui.action("×", () -> animateBox(0));
        close.setTextSize(22);
        close.setContentDescription("收起木匣");
        header.addView(close);
        interior.addView(header);
        ui.spacer(interior, 12);
        expandedCovers=addBoxGrid(box,interior);
        boxMotion=new BoxMotion(interior,anchor,expandedCovers,header);
        updateLayerAccessibility();
        boxMorph = new MorphSurface(tray, boxMotion.sourceBounds(), ui.dp(1), ui.dp(16), Transitions.PANEL_MS, this::paintUnfold);
        if (animated) {
            paintUnfold(0);
            animateBox(1);
        } else {
            paintUnfold(1);
            boxMorph.snap(1);
        }
    }

    private void searchBooks(String title, List<Library.Book> books, View anchor) {
        LinearLayout page = ui.column();
        EditText field = paperInput("输入书名或作者", "");
        page.addView(field);
        LinearLayout results = ui.column();
        page.addView(results);
        Consumer<String> filter = query -> {
            results.removeAllViews();
            String needle = query.trim().toLowerCase(Locale.ROOT);
            if (needle.isEmpty()) return;
            for (Library.Book book : books) {
                if (!book.title().toLowerCase(Locale.ROOT).contains(needle)
                        && !book.author().toLowerCase(Locale.ROOT).contains(needle)) continue;
                View result = PaperMenu.option(ui, Icon.READ, book.title(), () -> {});
                result.setOnClickListener(face -> {
                    hideKeyboard();
                    openReader(book, face);
                });
                results.addView(result);
            }
            if (results.getChildCount() == 0) {
                ui.spacer(results, 14);
                results.addView(ui.text("没有匹配的图书", 14, ui.secondary, false));
            }
        };
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                filter.accept(text.toString());
            }
            @Override public void afterTextChanged(android.text.Editable text) {}
        });
        present(title, page, anchor, true, () -> {});
        focusSearch(field);
    }

    void focusSearch(EditText field) {
        Panel search = panels.peekLast();
        field.postDelayed(() -> {
            if (search == null || panels.peekLast() != search || closingPanel == search || !field.isAttachedToWindow()) return;
            field.requestFocus();
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
        }, Transitions.PANEL_MS);
    }

    private Map<Long,View> addBoxGrid(Library.Box box, LinearLayout interior) {
        Map<Long,View> targets=new LinkedHashMap<>();
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout grid = ui.column();
        scroll.addView(grid, new ScrollView.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP));
        List<Library.Book> books = library.books(box);
        int available = Math.min(430, getResources().getConfiguration().screenWidthDp - 28) - 34;
        int coverWidth = Math.max(26, (available - 18) / 3);
        int coverHeight = Math.round(coverWidth / ShelfUi.COVER_RATIO);
        int limit = ui.dp(Math.max(115, getResources().getConfiguration().screenHeightDp - 165));
        int rowStep = ui.dp(coverHeight + 10);
        int rows = (books.size() + 2) / 3;
        if (books.isEmpty()) {
            TextView empty = ui.text("木匣是空的，点 ＋ 添加书本", 15, ui.ink, true);
            empty.setGravity(Gravity.CENTER);
            grid.addView(empty, new LinearLayout.LayoutParams(ShelfUi.MATCH, ui.dp(82)));
        } else {
            addBookRows(grid, books, targets, coverWidth, coverHeight, Math.max(4, limit / rowStep + 2));
            scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
                if (grid.getChildCount() < rows && grid.getHeight() - (y + scroll.getHeight()) <= rowStep) {
                    addBookRows(grid, books, targets, coverWidth, coverHeight, 4);
                }
            });
        }
        int height = books.isEmpty() ? ui.dp(82) : rows * rowStep - ui.dp(10);
        interior.addView(scroll, new LinearLayout.LayoutParams(ShelfUi.MATCH, Math.min(limit, height)));
        return targets;
    }

    private void addBookRows(LinearLayout grid, List<Library.Book> books, Map<Long, View> targets,
                             int coverWidth, int coverHeight, int count) {
        int start = grid.getChildCount(), end = Math.min((books.size() + 2) / 3, start + count);
        for (int i = start; i < end; i++) {
            LinearLayout row = ui.row();
            row.setGravity(Gravity.TOP);
            if (i > 0) row.setPadding(0, ui.dp(10), 0, 0);
            for (int j = 0; j < 3; j++) {
                LinearLayout cell = ui.column();
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                row.addView(cell, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
                int index = i * 3 + j;
                if (index >= books.size()) continue;
                Library.Book book = books.get(index);
                View cover = ui.bookCover(book, library.cover(book), anchor -> openReader(book, anchor), anchor -> bookMenu(book, anchor));
                cover.setElevation(ui.dp(4));
                targets.put(book.id(),cover);
                cell.addView(cover, new LinearLayout.LayoutParams(ui.dp(coverWidth), ui.dp(coverHeight)));
            }
            grid.addView(row);
        }
    }

    private void paintUnfold(float fraction) {
        if (tray == null) return;
        shade.setAlpha(fraction);
        if(boxMotion!=null)boxMotion.paint(fraction);
        tray.setElevation(ui.dp(9) * fraction);
        if (dock != null) dock.setAlpha(1 - .75f * fraction);
        if (boxBlur != null) boxBlur.setAlpha(fraction);
    }

    private void animateBox(float target) {
        if (boxMorph == null) return;
        folding = target == 0;
        boxMorph.move(target,target==0?()->{
            removeOverlay();
            if(shelfNeedsRefresh && readerFlight == null)renderBase();
        }:null);
    }

    private void removeOverlay() {
        if (boxMorph != null) {
            boxMorph.stop();
            boxMorph = null;
        }
        if(boxMotion!=null){boxMotion.release();boxMotion=null;}
        if (dock != null) dock.setAlpha(1);
        if (overlay != null) root.removeView(overlay);
        if (base != null) base.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        overlay = null;
        tray = null;
        expandedCovers=Map.of();
        boxBlur = null;
        openBox = 0;
        folding = false;
        updateLayerAccessibility();
    }

    private void redraw(long box) {
        if (box > 0 && pageIndex == ModuleTabs.BOOKS && library.box(box) != null) {
            preferences.edit().putLong("home_category", box).apply();
        }
        renderBase();
    }

    private void pick(long box, View anchor) {
        importTarget=box;
        openBrowser(FolderBrowser.Mode.BOOKS, anchor, (picks,parent)->{
            closePanelNow();
            if(box!=0 && library.box(box)!=null){importBooks(box,"",picks);return;}
            String suggested=parent.isBlank()?"新木匣":parent;
            EditText name=paperInput("木匣名称",suggested);
            name.selectAll();
            PaperMenu menu = new PaperMenu(ui, "导入书籍", "新建木匣", "已选 " + picks.size() + " 本书");
            menu.body.addView(name);
            ui.spacer(menu.body, 8);
            menu.item(Icon.ADD, "导入", "", true, () -> importBooks(null, cleanName(name, suggested), picks));
            menu.show(anchor);
        });
    }

    private void openBrowser(FolderBrowser.Mode mode, View anchor, BiConsumer<Set<Uri>, String> onChoose) {
        FolderBrowser surface=new FolderBrowser(this,ui,mode,onChoose);
        browser=surface;
        present("",surface.view,anchor,false,()->{surface.dispose();if(browser==surface)browser=null;});
    }

    void requestFolder(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(FLAGS|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent,PICK_FOLDER);
    }

    void clmPassphraseChanged(String message) {
        playback.getCatalog().retryPreviews();
        toast(message);
    }

    void pickAudioFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (playback.getTree() != null) {
            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, playback.getTree());
        }
        startActivityForResult(intent, PICK_AUDIO_FOLDER);
    }

    private void importAudioFolder(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            playback.setTree(uri);
            if (pageIndex == ModuleTabs.PROFILE) renderBase();
        } catch (SecurityException denied) {
            toast("无法保留目录授权，请重新选择本地文件夹");
        }
    }

    void pickMusicFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (music.getTree() != null) intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, music.getTree());
        startActivityForResult(intent, PICK_MUSIC_FOLDER);
    }

    void pickBooksFromSettings(View anchor) {
        List<Library.Box> boxes = library.boxes();
        if (boxes.isEmpty()) {
            pick(0, anchor);
            return;
        }
        String[] targets = new String[boxes.size() + 1];
        targets[0] = "新建木匣";
        for (int i = 0; i < boxes.size(); i++) targets[i + 1] = boxes.get(i).name();
        choose("导入位置", targets, -1, anchor, selected ->
                pick(selected == 0 ? 0 : boxes.get(selected - 1).id(), anchor));
    }

    String selectedDirectory(boolean songs) {
        Uri uri = songs ? music.getTree() : playback.getTree();
        if (uri == null) return songs ? "每个含音频的文件夹生成一个歌单" : "扫描目录中的 CLM 有声书";
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(uri);
            int cut = Math.max(id.lastIndexOf('/'), id.lastIndexOf(':'));
            return id.substring(cut + 1).isEmpty() ? id : id.substring(cut + 1);
        } catch (IllegalArgumentException invalid) {
            return "已授权";
        }
    }

    void moduleSettings(LinearLayout group) {
        LinearLayout choices = ui.row();
        CheckBox[] boxes = new CheckBox[3];
        for (int index = 0; index < boxes.length; index++) {
            final int bit = 1 << index;
            CheckBox box = new CheckBox(this);
            box.setText(ModuleTabs.LABELS[index]);
            box.setTextSize(14);
            box.setTextColor(ui.ink);
            box.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent));
            box.setMinHeight(ui.dp(43));
            box.setChecked((moduleTabs.mask() & bit) != 0);
            boxes[index] = box;
            choices.addView(box, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        }
        group.addView(choices);
        TextView startup = ui.action("启动时打开 · " + ModuleTabs.LABELS[moduleTabs.startup()], null);
        startup.setOnClickListener(anchor -> {
            int[] enabled = moduleTabs.modules();
            String[] labels = new String[enabled.length];
            int selected = 0;
            for (int index = 0; index < enabled.length; index++) {
                labels[index] = ModuleTabs.LABELS[enabled[index]];
                if (enabled[index] == moduleTabs.startup()) selected = index;
            }
            choose("启动时打开", labels, selected, anchor, index -> {
                moduleTabs.startup(enabled[index]);
                startup.setText("启动时打开 · " + ModuleTabs.LABELS[moduleTabs.startup()]);
            });
        });
        group.addView(startup);
        for (int index = 0; index < boxes.length; index++) {
            final int bit = 1 << index;
            boxes[index].setOnClickListener(view -> {
                int previous = moduleTabs.mask();
                int next = ((CheckBox) view).isChecked() ? previous | bit : previous & ~bit;
                if (!moduleTabs.enabled(next)) {
                    ((CheckBox) view).setChecked(true);
                    toast("至少保留一个模块");
                    return;
                }
                recordModule = moduleTabs.preferred(recordModule);
                historyPage = null;
                dock.bind(ui, stage, pageIndex, false, moduleTabs.dock());
                startup.setText("启动时打开 · " + ModuleTabs.LABELS[moduleTabs.startup()]);
            });
        }
    }

    void migrationSettings(View anchor) {
        LinearLayout content = ui.column();
        content.addView(ui.text("每个所选模块的源文件和对应记录会一起传输。", 14, ui.secondary, false));
        ui.spacer(content, 8);
        CheckBox[] boxes = new CheckBox[3];
        String[] details = {"书籍、书匣、书签和阅读记录", "CLM、有声书进度和收听记录", "音乐文件、文件夹、播放进度和播放记录"};
        for (int index = 0; index < boxes.length; index++) {
            CheckBox item = new CheckBox(this);
            item.setText(ModuleTabs.LABELS[index] + " · " + details[index]);
            item.setTextColor(ui.ink);
            item.setTextSize(14);
            item.setMinHeight(ui.dp(49));
            item.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent));
            item.setChecked((moduleTabs.mask() & (1 << index)) != 0);
            boxes[index] = item;
            content.addView(item);
        }
        content.addView(ui.action("交给 FileDoctorC", () -> {
            int mask = 0;
            for (int index = 0; index < boxes.length; index++) if (boxes[index].isChecked()) mask |= 1 << index;
            if (mask == 0) { toast("请选择模块"); return; }
            int selected = mask;
            closePanelThen(() -> prepareTransfer(selected));
        }));
        content.addView(ui.action("恢复迁移文件", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(pick, PICK_MIGRATION);
        }));
        panel("模块迁移", content, anchor, true);
    }

    private record TransferDraft(File file) implements Closeable {
        @Override public void close() { file.delete(); }
    }

    private void prepareTransfer(int mask) {
        playback.saveCurrent();
        music.save(false);
        Uri podcastTree = playback.getTree(), musicTree = music.getTree();
        List<LibraryItem> known = new ArrayList<>(playback.getRecentEntries());
        LibraryItem playingItem = playback.currentItem();
        if (playingItem != null) known.add(playingItem);
        ModuleTransfer transfer = new ModuleTransfer(this, library, files);
        job("正在读取所选模块的源文件与记录…", () -> {
            music.flushRecords();
            return new TransferDraft(transfer.pack(mask, podcastTree, musicTree, known));
        }, draft -> {
            File bundle = draft.file();
            migrationDraft = bundle;
            try {
                startActivityForResult(transfer.sendingIntent(bundle), HANDOFF_MIGRATION);
            } catch (ActivityNotFoundException unavailable) {
                dialog().setTitle("需要新版 FileDoctorC")
                        .setMessage("可先把迁移文件保存到本机，再用 FileDoctorC 发送。")
                        .setPositiveButton("保存到本机", (d, which) -> {
                            Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(ModuleTransfer.MIME)
                                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, bundle.getName());
                            startActivityForResult(save, SAVE_MIGRATION);
                        }).setNegativeButton("关闭", (d, which) -> discardMigrationDraft())
                        .setOnCancelListener(d -> discardMigrationDraft()).show();
            } catch (RuntimeException failure) {
                discardMigrationDraft();
                alertError(failure);
            }
        });
    }

    private void discardMigrationDraft() {
        File candidate = migrationDraft;
        migrationDraft = null;
        if (candidate != null) candidate.delete();
    }

    private static boolean isTransferIntent(Intent intent) {
        return intent != null && Intent.ACTION_VIEW.equals(intent.getAction())
                && ModuleTransfer.MIME.equals(intent.getType()) && intent.getData() != null;
    }

    private void confirmTransfer(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return;
        dialog().setTitle("恢复 MookDoctorC 模块")
                .setMessage("将选定的源文件与对应记录导入本机资料库。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (d, which) -> job("正在校验并恢复源文件与记录…",
                        () -> new ModuleTransfer(this, library, files).restore(uri), done -> {
                            while (!panels.isEmpty()) closePanelNow();
                            shelfNeedsRefresh = true;
                            audioShelfPage = historyPage = bookShelfPage = null;
                            playback.restored();
                            music.refresh(true);
                            renderBase();
                            toast(done.getAlreadyRestored() ? "此迁移文件已恢复" : "已恢复 " + done.getCount() + " 个源文件及记录");
                        }))
                .show();
    }

    private void syncSharedPlayer() {
        if (sharedMini == null) return;
        sharedMini.sync();
        if (reader != null || overlay != null) sharedMini.getView().setVisibility(View.GONE);
    }

    void showSharedPlayer(View anchor) {
        if (musicPlayerPanel != null || audioPlayerPanel != null || !panels.isEmpty()) return;
        if (music.isActive() && music.getCurrent() != null) {
            MusicPage.Player player = new MusicPage.Player(this, ui, music);
            lastPlayerBarColor = usesAudioPalette() ? audioPaper() : ui.paper;
            present("正在播放", player.getView(), anchor, true, () -> {
                musicPlayerPanel = null;
                applyPagePalette(usesAudioPalette());
            }, null, MusicPage.colors(ui), player.getBackdrop(), ui.dp(13), () -> {}, null);
            musicPlayerPanel = panels.peekLast();
        } else if (playback.currentItem() != null || playback.getOpeningTitle() != null) {
            showAudioPlayer(anchor);
        }
    }

    boolean canOpenMusicPlaylist(View anchor) {
        return !isFinishing() && !isDestroyed() && pageIndex == ModuleTabs.MUSIC && base == musicShelfPage
                && panels.isEmpty() && overlay == null && anchor.isAttachedToWindow() && anchor.isShown();
    }

    void musicSurface(String title, LinearLayout body, View anchor, boolean scroll, Runnable dismissed, Runnable ready) {
        lastPlayerBarColor = usesAudioPalette() ? audioPaper() : ui.paper;
        View background = new View(this);
        background.setBackground(MusicPage.wall(ui));
        present(title, body, anchor, scroll, dismissed == null ? () -> {} : dismissed, null,
                MusicPage.colors(ui), background, ui.dp(13), ready, null);
    }

    void closePanelThen(Runnable after) { closePanel(panels.peekLast(), after); }

    void openAudioItem(LibraryItem item, View anchor, float radius) {
        openAudioItem(item, anchor, radius, () -> {});
    }

    void openAudioItem(LibraryItem item, View anchor, float radius, Runnable sourceReleased) {
        hideKeyboard();
        if (audioPlayerPanel != null) {
            playback.open(item);
            sourceReleased.run();
        } else {
            presentAudioPlayer(item, anchor, radius, sourceReleased);
        }
    }

    void showAudioPlayer(View anchor) {
        presentAudioPlayer(null, anchor, ui.dp(10), () -> {});
    }

    private void presentAudioPlayer(LibraryItem selection, View anchor, float radius, Runnable sourceReleased) {
        if (audioPlayerPanel != null) return;
        AudiobookPage player = preparedAudioPage();
        if (player.view.getParent() instanceof ViewGroup oldContent) oldContent.removeView(player.view);
        if (player.background.getParent() instanceof ViewGroup oldBackground) oldBackground.removeView(player.background);
        player.prepare(selection, anchor instanceof VinylFace vinyl ? vinyl.cover() : null);
        lastPlayerBarColor = usesAudioPalette() ? audioPaper() : ui.paper;
        present("播放", player.view, anchor, true, () -> {
            audioPlayerPanel = null;
            player.settled(false);
            player.prepare(null, null);
            applyPagePalette(usesAudioPalette());
            if (base.getTag() instanceof AudiobookShelf shelf) shelf.onViewportChanged();
            sourceReleased.run();
        }, null, AudiobookPage.Colors.forMode(ui.dark), player.background, radius, () -> {
            player.settled(true);
            player.playSelection(selection);
        }, player.deck);
        audioPlayerPanel = panels.peekLast();
    }

    private AudiobookPage preparedAudioPage() {
        if (audioPage == null) {
            audioPage = new AudiobookPage(this, ui, playback);
            if (deckArtwork != null) audioPage.deck.setArtwork(deckArtwork);
        }
        return audioPage;
    }

    private void prepareDeckArtwork() {
        if (deckRequested) return;
        deckRequested = true;
        var resources = getResources();
        ShelfUi.prepareArtwork(() -> {
            TurntableDeck.Artwork ready = TurntableDeck.Artwork.load(resources);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                deckArtwork = ready;
                if (audioPage != null) audioPage.deck.setArtwork(ready);
            });
        });
    }

    private void warmPlayerView() {
        if (isDestroyed() || audioPage != null) return;
        if (tabMotion != null || readerFlight != null || overlay != null || !panels.isEmpty()) {
            root.postDelayed(warmPlayer, Transitions.atRate(Transitions.PANEL_MS));
        } else {
            preparedAudioPage();
        }
    }

    boolean isAudioShelfCurrent() {
        return pageIndex == 1 && tabMotion == null && panels.isEmpty();
    }

    void audioCrate(String title, LinearLayout body, View anchor, Supplier<Rect> origin, Runnable release, int rows, Supplier<List<VinylFace>> faces) {
        View dim = new View(this);
        dim.setBackgroundColor(ui.dark ? 0x410c1426 : 0x2442587d);
        dim.setAlpha(0);
        dim.setContentDescription("收起唱片匣");
        dim.setOnClickListener(view -> closePanel());

        int desiredHeight = ui.dp(Math.min(660, 139 + 155 * rows));
        FrameLayout enclosure = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int height = Math.min(desiredHeight, MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }

        };

        LinearLayout crate = ui.column();
        crate.setClipChildren(false);
        crate.setClipToPadding(false);
        crate.setPadding(ui.dp(15), ui.dp(6), ui.dp(15), ui.dp(16));
        crate.setClickable(true);
        crate.setFocusableInTouchMode(true);
        enclosure.addView(crate, full());
        int width = getResources().getConfiguration().screenWidthDp;
        int side = Math.max(15, (width - 510) / 2);
        int vertical = 14;
        FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH, Gravity.CENTER);
        size.setMargins(ui.dp(side), ui.dp(vertical), ui.dp(side), ui.dp(vertical));
        LinearLayout header = ui.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = ui.text(title, 19, CdCrateSurface.ink(ui), true);
        name.setPadding(ui.dp(4), 0, ui.dp(8), 0);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(name, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        TextView close = ui.action("×", this::closePanel);
        close.setTextSize(22);
        close.setTextColor(CdCrateSurface.ink(ui));
        close.setContentDescription("关闭唱片匣");
        header.addView(close);
        crate.addView(header);
        ui.spacer(crate, 5);

        body.setBackground(CdCrateSurface.lining(ui));
        body.setPadding(ui.dp(7), ui.dp(3), ui.dp(7), 0);
        crate.addView(body, new LinearLayout.LayoutParams(ShelfUi.MATCH, 0, 1));

        Bitmap backdrop = cdBackdrop == null ? null : cdBackdrop.image();
        CdCrateMotion flight = new CdCrateMotion(ui, enclosure, size, dim, anchor, origin, faces, backdrop);
        flight.setElevation(ui.dp(32 + panels.size() * 2));
        root.addView(flight, full());
        Panel panel = new Panel(flight, flight::move, flight::stop, () -> {
            flight.release();
            release.run();
        }, flight.motion, null);
        openPanel(panel, crate);
    }

    void showAudioChapters(View anchor) {
        AudioToolSheet.chapters(ui, playback, this::closePanel, sheet -> presentAudioTool(sheet, anchor));
    }

    void showAudioSettings(View anchor) {
        AudioToolSheet.settings(ui, playback, this::closePanel, this::hideKeyboard, sheet -> presentAudioTool(sheet, anchor));
    }

    private Consumer<Runnable> presentAudioTool(LinearLayout sheet, View anchor) {
        return showMenuLayer(sheet, anchor, this::hideKeyboard, true, ui.dp(22), ui.dp(26), 0x490b1425);
    }

    void pickReaderFont() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        // Some Android document providers advertise .ttf as application/octet-stream.
        picker.setType("*/*");
        startActivityForResult(picker, PICK_READER_FONT);
    }

    private void importReaderFont(Uri uri) {
        job("正在导入字体…", () -> {
            String name = files.displayName(uri);
            if (!name.toLowerCase(Locale.ROOT).endsWith(".ttf")) throw new IOException("请选择 .ttf 文件");
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("无法读取字体文件");
                ReaderFontFile.install(this, in);
            }
            preferences.edit().putString(ReaderFontFile.NAME_KEY, name)
                    .putString(ReaderTypography.FONT_KEY, ReaderTypography.Font.IMPORTED.name()).apply();
            return name;
        }, name -> {
            if (reader != null) reader.setTypography(ReaderTypography.read(preferences));
            toast("已使用 " + name);
        });
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        Uri uri=result==RESULT_OK && data!=null?data.getData():null;
        if(request==EXPORT_BOOK){finishExport(uri);return;}
        if (request == HANDOFF_MIGRATION || (request == SAVE_MIGRATION && uri == null)) {
            discardMigrationDraft();
            return;
        }
        if(uri==null)return;
        if (request == SAVE_MIGRATION) {
            File candidate = migrationDraft;
            migrationDraft = null;
            if (candidate != null) job("正在保存迁移文件…", () -> {
                try {
                    files.export(candidate, uri);
                    return true;
                } finally {
                    candidate.delete();
                }
            }, done -> toast("迁移文件已保存"));
            return;
        }
        if (request == PICK_MIGRATION) { confirmTransfer(uri); return; }
        if (request == PICK_MUSIC_FOLDER) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                music.setTree(uri);
                renderBase();
            } catch (SecurityException error) { alertError(error); }
            return;
        }
        if (request == PICK_READER_FONT) { importReaderFont(uri); return; }
        if (request == PICK_AUDIO_FOLDER) {
            importAudioFolder(uri);
            return;
        }
        if(request==PICK_FOLDER){
            try {
                if((data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0){
                    getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                }else{
                    getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                if(browser!=null)browser.granted(uri);
            } catch(SecurityException error){alertError(error);}
        }else if(request==CREATE_BACKUP){
            job("正在创建备份…",()->{
                new LocalBackup(this, library, files, preferences).write(uri, getContentResolver());return true;
            },done->toast("本地备份已保存"));
        }
    }


    private record ImportReport(int added, List<String> rejected) {}

    private void importBooks(Long target, String name, Set<Uri> picks) {
        job("登记源文件…", () -> {
            ArrayList<Library.Import> items = new ArrayList<>();
            ArrayList<Bitmap> images = new ArrayList<>();
            ArrayList<String> rejected = new ArrayList<>();
            for (Uri uri : picks) {
                String filename = uri.getLastPathSegment();
                try {
                    filename = files.displayName(uri);
                    String lower = filename.toLowerCase(Locale.ROOT);
                    if (!lower.endsWith(".epub") && !lower.endsWith(".txt")) throw new IOException("格式不支持");
                    boolean epub = lower.endsWith(".epub");
                    String title = filename.substring(0, filename.lastIndexOf('.'));
                    if (title.trim().isEmpty()) title = "未命名书籍";
                    String author = "", description = "";
                    Bitmap cover = null;
                    if (epub) {
                        try (ReaderDocument doc = new ReaderDocument.Epub(files.descriptor(uri), filename)) {
                            ReaderDocument.Info info = doc.info();
                            if (!info.title().isEmpty()) title = info.title();
                            author = info.author();
                            description = info.description();
                            if (info.cover() != null) cover = SourceFiles.bitmap(info.cover(), 430);
                        }
                    } else {
                        try (InputStream in = files.read(uri)) {
                            if (in.read() < 0) throw new IOException("TXT 是空文件");
                        }
                    }
                    items.add(new Library.Import(uri, filename, title, author, description, epub));
                    images.add(cover);
                } catch (IOException | RuntimeException error) {
                    rejected.add(filename + " · " + message(error));
                }
            }
            List<Long> ids = library.add(target, name, items);
            int count = 0;
            for (int i = 0; i < ids.size(); i++) {
                long id = ids.get(i);
                Bitmap cover = images.get(i);
                if (id >= 0) {
                    count++;
                    if (cover != null) {
                        try { SourceFiles.storeCover(library.cover(id, ""), cover); }
                        catch (IOException ignored) { /* A failed cover cache does not invalidate the book. */ }
                    }
                } else {
                    rejected.add(items.get(i).name() + " · 已在这个木匣");
                }
                if (cover != null) cover.recycle();
            }
            return new ImportReport(count, rejected);
        }, report -> {
            shelfNeedsRefresh = true;
            bookShelfPage = null;
            redraw(target == null ? 0 : target);
            String summary = "已导入 " + report.added() + " 本";
            if (report.rejected().isEmpty()) toast(summary);
            else dialog().setTitle(summary).setMessage(String.join("\n", report.rejected())).setPositiveButton("确定", null).show();
        });
    }

    private void boxMenu(Library.Box box, View anchor) {
        PaperMenu menu = new PaperMenu(ui, "木匣操作", box.name(), library.books(box.id()).size() + " 本书");
        menu.item(Icon.EDIT, "重命名木匣", "", false, () -> renameBox(box, anchor));
        menu.item(Icon.SORT, "木匣内排序", box.order().label, false, () -> chooseBoxOrder(box, anchor));
        menu.danger("移除木匣", "", () -> confirmRemoval("移除木匣",
                "将移除这个木匣和其中的书架记录。源文件保留在原位置。", () -> {
                    library.removeBox(box.id());
                    redraw(0);
                }));
        menu.show(anchor);
    }

    private void renameBox(Library.Box box, View anchor) {
        EditText name = paperInput("木匣名称", box.name());
        name.selectAll();
        PaperMenu menu = new PaperMenu(ui, "木匣操作", "重命名木匣", "");
        menu.body.addView(name);
        ui.spacer(menu.body, 8);
        menu.item(Icon.CHECK, "保存名称", "", true, () -> {
            library.renameBox(box.id(), cleanName(name, box.name()));
            redraw(openBox);
        });
        menu.show(anchor);
    }

    private void chooseBoxOrder(Library.Box box, View anchor) {
        Library.BookOrder[] orders = Library.BookOrder.values();
        String[] labels = Arrays.stream(orders).map(order -> order.label).toArray(String[]::new);
        choose("木匣内排序", labels, box.order().ordinal(), anchor, choice -> {
            if (orders[choice] == box.order()) return;
            library.sortBox(box.id(), orders[choice]);
            redraw(openBox);
        });
    }

    void choose(String title, String[] labels, int selected, View anchor, IntConsumer choose) {
        PaperMenu menu = new PaperMenu(ui, "选择", title, "");
        for (int index = 0; index < labels.length; index++) {
            int option = index;
            menu.choice(labels[index], "", index == selected, () -> choose.accept(option));
        }
        menu.show(anchor);
    }

    private void confirmRemoval(String title, String detail, Runnable remove) {
        PaperMenu confirm = new PaperMenu(ui, "移除确认", title, detail);
        confirm.danger("确认移除", "", remove);
        confirm.show();
    }

    private void bookMenu(Library.Book selected, View anchor) {
        Library.Book book = library.book(selected.id());
        if (book == null) return;
        PaperMenu menu = new PaperMenu(ui, book.epub() ? "书籍 · EPUB" : "书籍 · TXT", book.title(),
                book.author().isBlank() ? "作者未标注" : book.author(), ui.coverPreview(book, library.cover(book)));
        menu.item(Icon.READ, "阅读", book.readAt() > 0 ? "接续上次阅读位置" : "打开正文", true, () -> openReader(book, anchor));
        menu.item(Icon.EDIT, "编辑信息", "封面、标题、作者和简介", false, () -> editBookInfo(book, null, anchor));
        menu.item(Icon.EXPORT, "导出书籍", "原格式或转换格式", false, () -> exportMenu(book, anchor));
        menu.danger("从木匣移除", "", () -> confirmRemoval("从木匣移除", "书架中的这条记录将移除，源文件保留。", () -> {
            library.removeBook(book);
            redraw(openBox);
        }));
        menu.show(anchor);
    }

    private void openReader(Library.Book selected) { openReader(selected, null); }

    private void openReader(Library.Book selected, View anchor) { openReader(selected,anchor,null); }

    void openBookmark(Library.Book book,Library.Bookmark bookmark,View anchor){openReader(book,anchor,bookmark);}

    private void openReader(Library.Book selected, View anchor, Library.Bookmark bookmark) {
        if (readerFlight != null) return;
        readerSourcePanel = panels.peekLast();
        Bitmap backdrop = readerSourcePanel == null && overlay == null && shelfBackdrop != null
                ? shelfBackdrop.image() : null;
        ReaderFlight flight = new ReaderFlight(this, ui, selected, anchor, backdrop);
        readerFlight = flight;
        flight.setElevation(readerSourcePanel == null ? ui.dp(22) : Math.max(readerSourcePanel.view.getElevation() + ui.dp(1), ui.dp(32 + panels.size() * 2)));
        root.addView(flight, full());
        updateLayerAccessibility();
        flight.begin(() -> {
            if (readerFlight == flight && !readerClosing && !readerGesture) flight.move(1, this::mountReader);
        });
        submit(() -> {
            Library.Book book = library.book(selected.id());
            if (book == null) throw new FileNotFoundException("书籍已从书架移除");
            ReaderDocument doc = ReaderDocument.open(this, files, book);
            try {
                ReaderTypography typography = ReaderTypography.read(preferences);
                ReaderLayout.Mode mode = ReaderLayout.Mode.read(preferences.getString(ReadingUi.MODE_KEY, "VERTICAL"));
                return new PreparedReader(book, doc, ReadingUi.prepare(doc, book, bookmark, typography, mode, ui.dark));
            } catch (Exception error) {
                try { doc.close(); }
                catch (IOException closeError) { error.addSuppressed(closeError); }
                throw error;
            }
        }, prepared -> {
            if (readerFlight != flight || readerClosing) {
                closeDocument(prepared.document());
                return;
            }
            preparedReader = prepared;
            mountReader();
        }, error -> {
            if (readerFlight != flight || readerClosing) return;
            closeReader(() -> {
                restoreBookInfo();
                alertError(error);
            });
        });
    }

    private void mountReader() {
        ReaderFlight flight = readerFlight;
        if (flight == null || !flight.hasLanded() || readerGesture || readerClosing || preparedReader == null) return;
        PreparedReader prepared = preparedReader;
        preparedReader = null;
        preferences.edit().putInt("chapters:" + prepared.book().uri(), prepared.document().chapters.size()).apply();
        ReadingUi incoming = new ReadingUi(this, ui, library, prepared.book(), prepared.document(), prepared.start());
        reader = incoming;
        shelfNeedsRefresh = true;
        flight.mount(incoming.view);
        incoming.whenFirstFrame(() -> {
            if (reader != incoming || readerClosing) return;
            flight.ready();
            updateClock();
            restoreBookInfo();
        });
    }

    void closeReader() { closeReader(null); }

    private void closeReader(Runnable after) {
        ReaderFlight flight = readerFlight;
        if (flight == null || readerClosing) return;
        readerClosing = true;
        ReadingUi closing = reader;
        long box = openBox;
        if (closing != null) {
            closing.stopClock();
            closing.persist(false);
        }
        if (preparedReader != null) {
            closeDocument(preparedReader.document());
            preparedReader = null;
        }
        flight.move(0, () -> {
            flight.release();
            root.removeView(flight);
            if (closing != null) closing.dispose();
            reader = null;
            syncSharedPlayer();
            readerFlight = null;
            readerClosing = false;
            boolean returnToPanel = readerSourcePanel != null;
            readerSourcePanel = null;
            if (closing == null || returnToPanel || !bookInfoChangedInReader && box > 0 && openBox == box && overlay != null) {
                updateLayerAccessibility();
            } else {
                redraw(box);
            }
            bookInfoChangedInReader = false;
            if (after != null) after.run();
        });
    }

    void closeDocument(ReaderDocument doc) {
        if (work.isShutdown()) return;
        work.execute(() -> { try { doc.close(); } catch (IOException ignored) {} });
    }

    void readerMenu(ReadingUi reading) {
        if (reader != reading || readerClosing || panels.peekLast() != readerSourcePanel) return;
        ReaderOptions options = new ReaderOptions(this, ui, reading);
        options.typography();
        options.group("阅读与书签", rows -> {
            options.paging(rows);
            options.action(rows, "添加书签", () -> addBookmark(reading));
        });
        options.group("书籍与文件", rows -> {
            options.action(rows, "编辑内容", () -> prepareEditor(reading));
            options.actionFrom(rows, "替换净化规则", view -> new LibraryPages(this, ui, library).readerRules(reading, view));
            options.actionFrom(rows, "导出书籍", view -> exportMenu(reading.book, view));
            options.actionFrom(rows, "编辑信息", view -> editBookInfo(reading.book, null, view));
            if (files.backup(reading.book).isFile()) {
                options.action(rows, "导出修改前备份", () -> beginExport(reading.book, null, true));
            }
        });
        present("阅读选项", options.view, null, true, () -> {});
    }

    void showReaderSearch(ReadingUi reading, View anchor) {
        if (reader != reading || readerClosing || panels.peekLast() != readerSourcePanel) return;
        ReaderSearchPage search = new ReaderSearchPage(this, ui, reading);
        present("搜索正文", search.view, anchor, true, search::close);
        search.focus();
    }

    void readSearchHit(ReadingUi reading, ReaderSearch.Hit hit) {
        Panel top = panels.peekLast();
        if (reader != reading || readerClosing || top == null || top == readerSourcePanel
                || top == closingPanel) return;
        hideKeyboard();
        reading.jumpToSearchHit(hit);
        removePanel(top);
    }

    private void addBookmark(ReadingUi reader){
        EditText label=input("书签名称",reader.chapter().title());
        dialog().setTitle("添加书签").setView(padded(label)).setNegativeButton("取消",null)
                .setPositiveButton("保存",(d,w)->{reader.bookmark(cleanName(label,reader.chapter().title()));toast("已保存书签");}).show();
    }

    void showContents(ReadingUi reader, View anchor){new LibraryPages(this,ui,library).contents(reader,anchor);}

    private void prepareEditor(ReadingUi reading) {
        var chapter = reading.chapter();
        int index = reading.index();
        job("定位原文…", () -> EditingSession.open(this, files, reading.book, chapter, index), session -> {
            if (!reading.book.epub()) {
                editPassage(reading, session, session.passages.get(0));
                return;
            }
            String[] excerpts = new String[session.passages.size()];
            for (int i = 0; i < excerpts.length; i++) {
                String text = session.passages.get(i).text().trim().replaceAll("\\s+", " ");
                excerpts[i] = text.length() > 68 ? text.substring(0, 68) + "…" : text;
            }
            PaperMenu chooser = new PaperMenu(ui, "编辑内容", "选择文字片段", "");
            chooser.excerptList(excerpts, selected -> editPassage(reading, session, session.passages.get(selected)));
            chooser.onAbandon(session::close);
            chooser.show();
        });
    }

    private void editPassage(ReadingUi reading, EditingSession session, EditingSession.Passage passage) {
        EditText edit = input("原文", passage.text());
        edit.setSingleLine(false);
        edit.setMinLines(4);
        edit.setMaxLines(10);
        edit.setGravity(Gravity.TOP);
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        boolean writable = files.canWrite(reading.book.uri());
        AlertDialog.Builder builder = dialog().setTitle(reading.book.epub() ? "编辑 EPUB 文字" : "编辑 TXT 正文")
                .setView(padded(edit)).setNegativeButton("取消", (d, which) -> session.close())
                .setNeutralButton("另存修改稿", (d, which) -> applyEdit(reading, session, passage, edit.getText().toString(), false));
        if (writable) builder.setPositiveButton("保存到源文件", (d, which) -> applyEdit(reading, session, passage, edit.getText().toString(), true));
        else builder.setMessage("文件提供程序只开放读取。");
        AlertDialog popup = builder.create();
        popup.setOnCancelListener(d -> session.close());
        popup.show();
    }

    private record EditResult(File draft, ReaderDocument document) {}

    private void applyEdit(ReadingUi reading, EditingSession session, EditingSession.Passage passage, String text, boolean save) {
        if (passage.text().equals(text)) {
            session.close();
            toast("文字未变化");
            return;
        }
        job(save ? "校验并写回源文件…" : "校验修改稿…", () -> {
            try (session) {
                File revised = session.revise(passage, text);
                if (!save) return new EditResult(revised, null);
                try { session.save(revised); } finally { revised.delete(); }
                return new EditResult(null, ReaderDocument.open(this, files, reading.book));
            }
        }, result -> {
            if (result.draft() != null) beginExport(reading.book, result.draft(), false);
            else {
                if (reader == reading) reading.replace(result.document());
                else closeDocument(result.document());
                toast("已保存到源文件");
            }
        });
    }

    private void exportMenu(Library.Book book, View anchor){
        String format=book.epub()?"EPUB":"TXT";
        String converted=book.epub()?"导出为 TXT 纯文本":"转换为 EPUB";
        PaperMenu menu = new PaperMenu(ui, "书籍文件", "导出书籍", book.title());
        menu.item(Icon.EXPORT, "导出原格式 " + format, "复制源文件", true, () -> beginExport(book, null, false));
        menu.item(Icon.EXPORT, converted, "", false,
                () -> job("转换书籍…", () -> BookExport.convert(this, files, library, book, !book.epub()),
                        draft -> beginExport(book, draft, false, !book.epub())));
        menu.show(anchor);
    }

    private void beginExport(Library.Book book,File draft,boolean backup){beginExport(book,draft,backup,book.epub());}

    private void beginExport(Library.Book book, File draft, boolean backup, boolean epub) {
        exportId = book.id();
        exportDraft = draft;
        exportBackup = backup;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType(epub ? "application/epub+zip" : "text/plain");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, BookExport.fileName(book, epub));
        startActivityForResult(intent, EXPORT_BOOK);
    }

    private void finishExport(Uri destination) {
        Library.Book book = library.book(exportId);
        File draft = exportDraft;
        boolean backup = exportBackup;
        exportId = 0;
        exportDraft = null;
        exportBackup = false;
        if (destination == null || book == null) {
            if (draft != null) draft.delete();
            return;
        }
        job("正在导出…", () -> {
            try {
                if (draft != null) files.export(draft, destination);
                else if (backup) files.export(files.backup(book), destination);
                else files.export(book.uri(), destination);
            } finally {
                if (draft != null) draft.delete();
            }
            return true;
        }, ignored -> toast("已导出"));
    }

    private void restoreBookInfo() {
        Bundle draft = restoredBookInfo;
        if (draft == null) return;
        restoredBookInfo = null;
        Library.Book book = library.book(draft.getLong("id"));
        if (book != null) editBookInfo(book, draft);
    }

    private void editBookInfo(Library.Book book, Bundle draft) {
        editBookInfo(book, draft, null);
    }

    private void editBookInfo(Library.Book book, Bundle draft, View anchor) {
        BookInfoEditor editor = new BookInfoEditor(this, ui, book, library.cover(book), draft);
        bookInfo = editor;
        TextView save = ui.action("保存", () -> saveBookInfo(editor));
        save.setBackground(PaperMenu.press(ui, ui.dark ? 0xff554332 : 0xffecd8b9, 13));
        save.setMinWidth(ui.dp(66));
        save.setMinHeight(ui.dp(40));
        present("编辑信息", editor.view, anchor, true, () -> {
            if (!isChangingConfigurations()) editor.close();
            bookInfo = null;
            bookInfoPanel = null;
        }, save);
        bookInfoPanel = panels.peekLast();
    }

    private void saveBookInfo(BookInfoEditor editor) {
        if (editor != bookInfo || panels.peekLast() != bookInfoPanel || !editor.validate()) return;
        hideKeyboard();
        if (!editor.changed()) {
            closePanel();
            return;
        }
        String title = editor.title(), author = editor.author(), description = editor.description();
        File cover = editor.cover();
        job("保存书籍信息…", () -> library.saveBookInfo(editor.book.id(), title, author, description, cover), saved -> {
            editor.markSaved();
            if (reader != null && reader.book.id() == saved.id()) {
                reader.updateBookInfo(saved);
                bookInfoChangedInReader = true;
                shelfNeedsRefresh = true;
            } else {
                redraw(openBox);
            }
            closePanel();
            toast("书籍信息已保存");
        });
    }

    private void hideKeyboard() {
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
    }

    SourceFiles sourceFiles(){return files;}
    SharedPreferences settings(){return preferences;}

    void panel(String title,LinearLayout body,View anchor,boolean scroll){
        present(title,body,anchor,scroll,()->{});
    }

    private void present(String title,LinearLayout body,View anchor,boolean scroll,Runnable release){
        present(title, body, anchor, scroll, release, null);
    }

    private void present(String title, LinearLayout body, View anchor, boolean scroll, Runnable release, View headerAction) {
        present(title, body, anchor, scroll, release, headerAction, null, null, ui.dp(12), null, null);
    }

    private void present(String title, LinearLayout body, View anchor, boolean scroll, Runnable release,
                         View headerAction, AudiobookPage.Colors audio, View background,
                         float sourceRadius, Runnable playerReady, TurntableDeck playerDeck) {
        LinearLayout shell=ui.column();
        shell.setFocusableInTouchMode(true);
        shell.setClickable(true);
        if (audio == null) shell.setBackgroundColor(ui.paper);
        shell.setPadding(ui.dp(18),ui.dp(5),ui.dp(18),ui.dp(12));
        LinearLayout header=ui.row();
        boolean musicPlayer = background instanceof MusicPlayerBackdrop;
        boolean photo = audio != null && !audio.equals(MusicPage.colors(ui));
        if (musicPlayer) {
            android.widget.ImageButton back = MusicPage.iconButton(ui, audio.ink());
            back.setImageResource(R.drawable.player_back_glyph);
            back.setContentDescription("返回");
            back.setOnClickListener(v -> panelBack());
            header.addView(back, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            TextView name = ui.text(title, 15, audio.muted(), true);
            name.setGravity(Gravity.CENTER);
            name.setLetterSpacing(.045f);
            header.addView(name, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
            header.addView(new View(this), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        } else {
            TextView back = ui.back(this::panelBack);
            if (photo) AudiobookPage.onPhoto(back, ui, AudiobookPage.PHOTO_ACCENT);
            else if (audio != null) back.setTextColor(audio.accent());
            header.addView(back);
            if(!title.isEmpty()){
                TextView name=ui.text(title,19,audio == null ? ui.ink : photo ? AudiobookPage.PHOTO_INK : audio.ink(),true);
                if (photo) AudiobookPage.onPhoto(name, ui, AudiobookPage.PHOTO_INK);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                name.setGravity(Gravity.CENTER_VERTICAL);
                header.addView(name,new LinearLayout.LayoutParams(0,ui.dp(48),1));
            }
            if (headerAction != null) header.addView(headerAction);
        }
        shell.addView(header);
        if (!musicPlayer) {
            View divider = ui.divider();
            if (audio != null) divider.setBackgroundColor(photo ? 0x7cbbc9ee : audio.border());
            shell.addView(divider);
            ui.spacer(shell,10);
        }
        int bodyWidth = headerAction != null || audio != null
                ? TabletLayout.contentWidth(ui, 624, scroll ? 1040 : 960) : ShelfUi.MATCH;
        if (scroll) {
            ScrollView content = pageViewport(bodyWidth);
            content.setVerticalScrollBarEnabled(false);
            content.addView(body, new ScrollView.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP, Gravity.CENTER_HORIZONTAL));
            shell.addView(content, new LinearLayout.LayoutParams(ShelfUi.MATCH, 0, 1));
        } else {
            LinearLayout.LayoutParams bodySize = new LinearLayout.LayoutParams(bodyWidth, 0, 1);
            bodySize.gravity = Gravity.CENTER_HORIZONTAL;
            shell.addView(body, bodySize);
        }
        Rect anchorBounds = panelOrigin(anchor);
        boolean anchored = !anchorBounds.isEmpty();
        if (audio != null) {
            FrameLayout enclosure = new FrameLayout(this);
            enclosure.setClickable(true);
            enclosure.addView(background, full());
            enclosure.addView(shell, full());
            AudioPanelFlight flight = new AudioPanelFlight(ui, enclosure, full(), null, anchor,
                    anchored ? () -> panelOrigin(anchor) : null, sourceRadius, 0, audio.paper(), Transitions.PANEL_MS,
                    playerReady == null ? null : this::applyPlayerPalette, playerDeck);
            openAudioFlight(flight, shell, release, playerReady);
            return;
        }

        float elevation = ui.dp(32 + panels.size() * 2);
        shell.setAlpha(0);
        root.addView(shell, full());
        if (!anchored) {
            shell.setElevation(elevation);
            MotionProgress transition = new MotionProgress(Transitions.LOCAL_MS, fraction -> {
                shell.setAlpha(fraction);
                shell.setTranslationY(ui.dp(20) * (1 - fraction));
            });
            openPanel(new Panel(shell, transition::move, transition::stop, release, null, null), shell);
            return;
        }
        float sourceAlpha = anchor.getAlpha();
        MorphSurface morph = new MorphSurface(shell, anchorBounds, sourceRadius, 0, Transitions.PANEL_MS, fraction ->
                paintAnchoredSurface(shell, anchor, sourceAlpha, elevation, fraction));
        morph.followSource(() -> panelOrigin(anchor));
        Panel panel = new Panel(shell, morph::move, morph::stop, () -> {
            anchor.setAlpha(sourceAlpha);
            release.run();
        }, morph.motion, null);
        openPanel(panel, shell);
    }

    private void openAudioFlight(AudioPanelFlight flight, View focus, Runnable release, Runnable landed) {
        flight.setElevation(ui.dp(32 + panels.size() * 2));
        root.addView(flight, full());
        Runnable shown = landed == null ? null : () -> flight.postOnAnimation(() -> {
            if (flight.isAttachedToWindow() && flight.motion.progress() == 1
                    && (closingPanel == null || closingPanel.view != flight)) landed.run();
        });
        Panel panel = new Panel(flight, flight::move, flight::stop, () -> {
            flight.release();
            release.run();
        }, flight.motion, shown);
        openPanel(panel, focus);
    }

    private void applyPlayerPalette(float fraction) {
        int home = usesAudioPalette() ? audioPaper() : ui.paper;
        int color = (int) playerBars.evaluate(fraction, home, audioPaper());
        if (lastPlayerBarColor == color) return;
        lastPlayerBarColor = color;
        root.setBackgroundColor(color);
    }

    private static Rect panelOrigin(View anchor) {
        if (anchor instanceof VinylFace vinyl) return vinyl.visibleRecord();
        return AudioPanelFlight.windowBounds(anchor, null);
    }

    private FrameLayout foregroundPanelLayer() {
        FrameLayout layer = new FrameLayout(this);
        layer.setClickable(true);
        layer.setOutlineProvider(null);
        // The container owns stacking. Only the moving surface casts a changing shadow.
        layer.setElevation(ui.dp(32 + panels.size() * 2));
        return layer;
    }

    private void openPanel(Panel panel, View focus) {
        panels.addLast(panel);
        focus.requestFocus();
        updateLayerAccessibility();
        updateClock();
        panel.move.accept(1f, panel.shown);
    }

    private void paintAnchoredSurface(View panel, View anchor, float alpha, float elevation, float fraction) {
        float reveal = Math.min(1, fraction * 4);
        panel.setAlpha(reveal);
        panel.setElevation(elevation * fraction);
        if (anchor != null) anchor.setAlpha(alpha * (1 - reveal));
    }

    Consumer<Runnable> showPaperMenu(LinearLayout menu, View anchor, Runnable dismissed) {
        return showMenuLayer(menu, anchor, dismissed, false, ui.dp(10), ui.dp(24),
                ui.dark ? 0x8a000000 : 0x61000000);
    }

    Consumer<Runnable> showSettingsMenu(LinearLayout menu, View anchor, Runnable dismissed) {
        return showMenuLayer(menu, anchor, dismissed, true, ui.dp(12), ui.dp(24),
                ui.dark ? 0x730d0907 : 0x49190f0a);
    }

    private Consumer<Runnable> showMenuLayer(LinearLayout menu, View anchor, Runnable dismissed,
                                             boolean nearAnchor, int sourceRadius, int finalRadius, int dimColor) {
        int margin = ui.dp(12);
        View scrim = new View(this);
        AnchoredMenuLayer local = nearAnchor ? new AnchoredMenuLayer(anchor, scrim, menu, margin) : null;
        FrameLayout layer = local == null ? new FrameLayout(this) : local;
        layer.setClickable(true);
        layer.setOutlineProvider(null);
        layer.setElevation(ui.dp(32 + panels.size() * 2));
        scrim.setBackgroundColor(dimColor);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.setOnClickListener(view -> closePanel());
        layer.addView(scrim, full());
        var screen = getResources().getConfiguration();
        int available = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - margin * 2;
        int width = Math.max(1, Math.min(ui.dp(452), available));
        boolean bottom = screen.screenWidthDp < 600 && screen.screenHeightDp > screen.screenWidthDp;
        FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(width, ShelfUi.WRAP,
                Gravity.CENTER_HORIZONTAL | (bottom ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL));
        size.setMargins(0, margin, 0, margin);
        menu.setClickable(true);
        layer.addView(menu, size);
        root.addView(layer, full());
        menu.setAlpha(0);
        scrim.setAlpha(0);
        float sourceAlpha = anchor.getAlpha();
        Supplier<Rect> source = local == null ? () -> MorphSurface.visible(anchor) : local::anchorBounds;
        int duration = nearAnchor ? Transitions.ANCHORED_MENU_MS : Transitions.PANEL_MS;
        MorphSurface surface = new MorphSurface(menu, source.get(), sourceRadius, finalRadius, duration,
                fraction -> {
                    paintAnchoredSurface(menu, anchor, sourceAlpha, ui.dp(8), fraction);
                    scrim.setAlpha(fraction);
                });
        surface.followSource(source);
        Panel panel = new Panel(layer, (target, after) -> {
            if (local != null) local.updateAnchor();
            surface.move(target, after);
        }, surface::stop, () -> {
            anchor.setAlpha(sourceAlpha);
            dismissed.run();
        }, surface.motion, null);
        openPanel(panel, menu);
        return after -> closePanel(panel, after);
    }

    private static final class AnchoredMenuLayer extends FrameLayout {
        private final View anchor, scrim, menu;
        private final int margin;
        private final Rect origin = new Rect();
        private final int[] window = new int[2];

        AnchoredMenuLayer(View anchor, View scrim, View menu, int margin) {
            super(anchor.getContext());
            this.anchor = anchor;
            this.scrim = scrim;
            this.menu = menu;
            this.margin = margin;
            updateAnchor();
        }

        void updateAnchor() {
            Rect current = panelOrigin(anchor);
            if (!current.isEmpty()) origin.set(current);
        }

        Rect anchorBounds() { return origin; }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left, height = bottom - top;
            scrim.layout(0, 0, width, height);
            updateAnchor();
            getLocationInWindow(window);
            int centerX = origin.isEmpty() ? width / 2 : origin.centerX() - window[0];
            int centerY = origin.isEmpty() ? height / 2 : origin.centerY() - window[1];
            int w = menu.getMeasuredWidth(), h = menu.getMeasuredHeight();
            int x = Math.max(margin, Math.min(width - margin - w, centerX - w / 2));
            int y = Math.max(margin, Math.min(height - margin - h, centerY - h / 2));
            menu.layout(x, y, x + w, y + h);
        }
    }

    private void panelBack(){
        if (closingPanel != null && panels.peekLast() == closingPanel) return;
        if(browser!=null && browser.back())return;
        if (bookInfo != null && panels.peekLast() == bookInfoPanel) {
            hideKeyboard();
            if (bookInfo.changed()) {
                PaperMenu confirm = new PaperMenu(ui, "编辑信息", "放弃本次修改？", "已填写的资料和新选封面尚未保存。");
                confirm.danger("放弃修改", "", this::closePanel);
                confirm.show();
                return;
            }
        }
        closePanel();
    }

    void closePanel() { closePanel(panels.peekLast(), null); }

    private void closePanel(Panel panel, Runnable after) {
        if (panel == null || panels.peekLast() != panel || closingPanel == panel) return;
        closingPanel = panel;
        panel.move.accept(0f, () -> {
            if (panels.peekLast() != panel) return;
            removePanel(panel);
            if (after != null) after.run();
        });
    }

    void closePanelNow(){
        Panel top=panels.peekLast();
        if(top!=null)removePanel(top);
    }

    private void removePanel(Panel panel){
        if(panels.peekLast()!=panel)return;
        panels.removeLast();
        if (closingPanel == panel) closingPanel = null;
        panel.stop.run();
        root.removeView(panel.view);
        panel.release.run();
        updateLayerAccessibility();
        updateClock();
        syncSharedPlayer();
        if (panels.isEmpty() && base != null && base.getTag() instanceof HistoryPage history) history.refresh();
        if (!isFinishing() && !isChangingConfigurations() && panels.isEmpty() && readerFlight == null
                && shelfNeedsRefresh && pageIndex == 0 && overlay == null) renderBase();
    }

    private void updateLayerAccessibility(){
        View foremost = readerFlight != null && panels.peekLast() == readerSourcePanel ? readerFlight
                : !panels.isEmpty() ? panels.getLast().view : overlay;
        for(int i=0;i<root.getChildCount();i++){
            View child=root.getChildAt(i);
            boolean visible=foremost==null?(child==stage||child==dock||sharedMini!=null&&child==sharedMini.getView()):child==foremost;
            child.setImportantForAccessibility(visible?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
    }


    void beginLocalBackup(){
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE,"MookDoctorC-"+java.time.LocalDate.now()+".zip");
        startActivityForResult(intent,CREATE_BACKUP);
    }

    void restoreLocalBackup(View anchor){
        openBrowser(FolderBrowser.Mode.BACKUP, anchor, (picks,ignored)->{
            Uri selected=picks.iterator().next();
            closePanelNow();
            dialog().setTitle("恢复本地备份").setMessage("将备份资料合并到当前书架。首次使用的书籍目录可在导入页重新授权。")
                    .setNegativeButton("取消",null).setPositiveButton("恢复",(d,w)->job("正在读取备份…",()->
                            new LocalBackup(this, library, files, preferences).restore(selected),count->{
                                closePanelNow();style();renderBase();toast("已恢复 "+count+" 本书的记录");
                            })).show();
        });
    }

    void importCover(BookInfoEditor editor, View anchor) {
        hideKeyboard();
        openBrowser(FolderBrowser.Mode.COVER, anchor, (picks, ignored) -> {
            Uri source = picks.iterator().next();
            closePanelNow();
            job("读取本地封面…", () -> {
                try (InputStream in = files.read(source)) {
                    Bitmap image = SourceFiles.bitmap(SourceFiles.bytes(in, 24 * 1024 * 1024), 1200);
                    if (image == null) throw new IOException("无法识别这张图片");
                    return BookInfoEditor.CoverDraft.write(getCacheDir(), image);
                }
            }, editor::useCover);
        });
    }

    <T> void io(Callable<T> task, Consumer<T> complete) { submit(task, complete, this::alertError); }

    <T> void job(String label, Callable<T> task, Consumer<T> complete) {
        ProgressBar progress = new ProgressBar(this);
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(21), ui.dp(16), ui.dp(21), ui.dp(16));
        row.addView(progress, new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)));
        TextView text = new TextView(this);
        text.setText(label);
        text.setPadding(ui.dp(12), 0, 0, 0);
        row.addView(text);
        AlertDialog indicator = dialog().setView(row).setCancelable(false).create();
        busyDialog = indicator;
        indicator.show();
        submit(task, result -> {
            indicator.dismiss();
            busyDialog = null;
            complete.accept(result);
        }, error -> {
            indicator.dismiss();
            busyDialog = null;
            alertError(error);
        });
    }

    private <T> void submit(Callable<T> task, Consumer<T> complete, Consumer<Exception> fail) {
        if (work.isShutdown()) return;
        work.execute(() -> {
            try {
                T result = task.call();
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) complete.accept(result);
                    else if (result instanceof Closeable resource) {
                        try { resource.close(); } catch (IOException ignored) {}
                    }
                });
            } catch (Exception error) {
                android.util.Log.w("BookDoctorC", "Operation failed", error);
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) fail.accept(error); });
            }
        });
    }

    AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, ui.dark ? R.style.BookDialogNight : R.style.BookDialogDay);
    }

    private static String message(Throwable error) {
        if (error instanceof SecurityException) return "源文件访问权限不可用，请从文件选择器重新添加";
        String text = error.getLocalizedMessage();
        return text == null || text.isBlank() ? "无法完成，请检查源文件和访问权限" : text;
    }

    private void alertError(Exception error) {
        dialog().setTitle("操作未完成").setMessage(message(error)).setPositiveButton("确定", null).show();
    }

    EditText input(String hint, String value) {
        EditText edit = new EditText(new ContextThemeWrapper(this,
                ui.dark ? android.R.style.Theme_DeviceDefault : android.R.style.Theme_DeviceDefault_Light));
        edit.setText(value);
        edit.setHint(hint);
        edit.setSingleLine(true);
        return edit;
    }

    EditText paperInput(String hint, String value) {
        EditText edit = input(hint, value);
        edit.setTextSize(16);
        edit.setTextColor(ui.ink);
        edit.setHintTextColor(ui.secondary);
        edit.setBackground(PaperMenu.outline(ui, ui.dark ? 0xff332a24 : 0xfffff8ed, 12));
        edit.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
        edit.setMinHeight(ui.dp(49));
        return edit;
    }

    void trackPaperDialog(Dialog dialog) { paperDialog = dialog; }
    void releasePaperDialog(Dialog dialog) { if (paperDialog == dialog) paperDialog = null; }

    LinearLayout padded(View child) {
        LinearLayout holder = ui.column();
        holder.setPadding(ui.dp(18), ui.dp(5), ui.dp(18), ui.dp(3));
        holder.addView(child, new LinearLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP));
        return holder;
    }

    private static String cleanName(EditText input, String fallback) {
        String name = input.getText().toString().trim();
        return name.isEmpty() ? fallback : name;
    }

    private static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH);
    }

    void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    @android.annotation.TargetApi(33)
    private void installBackCallback() {
        if (Build.VERSION.SDK_INT >= 34) {
            backCallback = new android.window.OnBackAnimationCallback() {
                private MotionProgress surface;
                private Panel panel;
                private boolean alreadyClosing;
                private float start;

                @Override public void onBackStarted(android.window.BackEvent event) {
                    panel = panels.peekLast();
                    alreadyClosing = panel != null && panel == closingPanel;
                    if (alreadyClosing) return;
                    readerGesture = panel == readerSourcePanel && readerFlight != null && !readerClosing;
                    surface = readerGesture ? readerFlight.motion : panel != null ? panel.gesture
                            : readerFlight == null && !folding && boxMorph != null ? boxMorph.motion : null;
                    if (surface != null) {
                        surface.stop();
                        start = Transitions.openCloseTime(surface.progress());
                    }
                }

                @Override public void onBackProgressed(android.window.BackEvent event) {
                    if (surface != null) {
                        // Keep a short settling interval for the committed gesture.
                        float time = start * (1 - .82f * event.getProgress());
                        surface.set(Transitions.OPEN_CLOSE.getInterpolation(time));
                    }
                }

                @Override public void onBackCancelled() {
                    boolean restoreReader = readerGesture;
                    readerGesture = false;
                    if (restoreReader && readerFlight != null) readerFlight.move(1, MainActivity.this::mountReader);
                    else if (panel != null && panel == panels.peekLast() && surface != null) panel.move.accept(1f, panel.shown);
                    else if (panel == null && surface != null) surface.move(1, null);
                    surface = null;
                    panel = null;
                    alreadyClosing = false;
                }

                @Override public void onBackInvoked() {
                    readerGesture = false;
                    surface = null;
                    panel = null;
                    boolean consume = alreadyClosing;
                    alreadyClosing = false;
                    if (!consume && !goBack()) finishAfterTransition();
                }
            };
        } else {
            backCallback = () -> { if (!goBack()) finishAfterTransition(); };
        }
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
    }

    private boolean goBack() {
        if (!panels.isEmpty() && (readerFlight == null || panels.peekLast() != readerSourcePanel)) {
            panelBack();
            return true;
        }
        if (readerFlight != null) closeReader();
        else if (overlay != null) {
            if (!folding) animateBox(0);
        } else if (pageIndex != moduleTabs.startup()) switchTab(moduleTabs.startup());
        else return false;
        return true;
    }

    // API 33 and later use the registered platform back callback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() {
        if (!goBack()) super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        playback.setForeground(true);
        music.refresh(false);
        updateClock();
    }

    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);updateClock();}

    private void updateClock(){
        if(reader==null)return;
        if (resumed && hasWindowFocus() && readerFlight.isReady() && panels.peekLast() == readerSourcePanel
                && !readerClosing) reader.startClock();
        else reader.stopClock();
    }

    @Override protected void onPause() {
        resumed=false;
        playback.setForeground(false);
        music.save(false);
        if(reader!=null){reader.stopClock();reader.persist(false);}
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (reader != null) reader.persist(false);
        if (readerFlight != null && !readerClosing) state.putLong("book", readerFlight.bookId);
        if (bookInfo != null) state.putBundle("bookInfo", bookInfo.state());
        state.putLong("box", openBox);
        state.putLong("import", importTarget);
        state.putLong("export", exportId);
        state.putBoolean("backup", exportBackup);
        state.putInt("page",pendingTab >= 0 ? pendingTab : pageIndex);
        state.putInt("moduleSchema", 1);
        state.putInt("recordModule", recordModule);
        state.putBoolean("audioPlayer", audioPlayerPanel != null || musicPlayerPanel != null);
        if (migrationDraft != null) state.putString("migrationDraft", migrationDraft.getName());
        if (exportDraft != null) state.putString("draft", exportDraft.getName());
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        playback.removeObserver(audioObserver);
        music.removeObserver(audioObserver);
        root.removeCallbacks(warmPlayer);
        if (busyDialog != null) busyDialog.dismiss();
        if (paperDialog != null) paperDialog.dismiss();
        while(!panels.isEmpty())closePanelNow();
        if (reader != null) reader.dispose();
        if (boxMorph != null) boxMorph.stop();
        if (readerFlight != null) readerFlight.release();
        if (preparedReader != null) closeDocument(preparedReader.document());
        if (tabMotion != null) tabMotion.stop();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        work.shutdown();
        super.onDestroy();
    }
}
