package local.bookdoctorc;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import java.text.NumberFormat;
import com.example.clmplayer.LibraryItem;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

final class HistoryPage {
    private static final int[] DAY_COLORS = {0xfff0e4d6, 0xffeed0b1, 0xffd8a278, 0xffb6754e, 0xff77462f};
    private static final int[] NIGHT_COLORS = {0xff45372e, 0xff5f4335, 0xff86553c, 0xffb97952, 0xffe1aa7d};
    private static final int[] LISTEN_DAY = {0xffe8edfb, 0xffcdd6f7, 0xffa4b2e6, 0xff7284cb, 0xff47599e};
    private static final int[] LISTEN_NIGHT = {0xff303952, 0xff3f4d77, 0xff5365a0, 0xff7c90d2, 0xffb6c4f9};
    private static final int[] MUSIC_DAY = {0xffedf1f5, 0xffd9e3ed, 0xffaec3d8, 0xff7699b9, 0xff37618a};
    private static final int[] MUSIC_NIGHT = {0xff303e4f, 0xff3d5570, 0xff507395, 0xff7295b5, 0xffadcbdf};
    private static final String[] RANGES = {"无阅读", "少于 5 分钟", "5 到 19 分钟", "20 到 59 分钟", "至少 1 小时"};

    private final Library library;
    private final ShelfUi ui;
    private final FrameLayout area;
    private final Face readingFace, cdFace, musicFace;
    private final TextView pageTitle;
    private final ImageButton change;
    private View activeFace;
    private final BiConsumer<Long, View> open;
    private final BiConsumer<LibraryItem, VinylFace> openAudio;
    private final ClmPlayback playback;
    private final AudiobookCatalog catalog;
    private final AudiobookPage.Colors audio;
    private final NumberFormat percent = NumberFormat.getPercentInstance();
    private final ModuleTabs tabs;
    private final MusicPlayback music;
    private final MainActivity activity;
    private final java.util.function.IntConsumer selectedModule;
    private final Runnable coverObserver = this::refreshListeningCovers;
    private final Runnable musicObserver = this::refreshMusic;
    private List<MusicStore.Song> renderedMusic;
    private Map<String, Long> renderedDay = Map.of();
    private String renderedDate;
    private int musicTimeRevision = -1;
    private int module;
    private YearMonth month = YearMonth.now();
    private LocalDate selected = LocalDate.now();
    private boolean filterByDay;

    HistoryPage(Library library, ShelfUi ui, LinearLayout root, int module, ClmPlayback playback,
                MusicPlayback music, ModuleTabs tabs, MainActivity activity,
                java.util.function.IntConsumer selectedModule, BiConsumer<Long, View> open,
                BiConsumer<LibraryItem, VinylFace> openAudio) {
        this.library = library;
        this.ui = ui;
        this.open = open;
        this.openAudio = openAudio;
        this.playback = playback;
        this.catalog = playback.getCatalog();
        this.music = music;
        this.tabs = tabs;
        this.activity = activity;
        this.selectedModule = selectedModule;
        this.module = tabs.preferred(module);
        audio = AudiobookPage.Colors.forMode(ui.dark);
        readingFace = new Face(ModuleTabs.BOOKS);
        cdFace = new Face(ModuleTabs.PODCAST);
        musicFace = new Face(ModuleTabs.MUSIC);
        LinearLayout header = ui.row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        pageTitle = heading("", 24, ui.ink);
        header.addView(pageTitle, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        change = MusicPage.iconButton(ui, ui.ink);
        change.setImageResource(R.drawable.history_switch);
        change.setVisibility(tabs.modules().length > 1 ? View.VISIBLE : View.GONE);
        change.setOnClickListener(view -> cycle());
        header.addView(change, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        root.addView(header);
        ui.spacer(root, 13);
        area = new FrameLayout(ui.context);
        area.setClipChildren(false);
        root.addView(area);
        mount(false);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                catalog.addObserver(coverObserver);
                music.addObserver(musicObserver);
                refresh();
            }
            @Override public void onViewDetachedFromWindow(View view) {
                catalog.removeObserver(coverObserver);
                music.removeObserver(musicObserver);
                for (int i = 0; i < area.getChildCount(); i++) area.getChildAt(i).animate().cancel();
            }
        });
    }

    private void cycle() {
        int[] enabled = tabs.modules();
        if (enabled.length < 2) return;
        int current = 0;
        while (current < enabled.length && enabled[current] != module) current++;
        module = enabled[(current + 1) % enabled.length];
        mount(true);
    }

    private void mount(boolean animate) {
        View outgoing = activeFace;
        Face face = currentFace();
        activeFace = face.pane;
        for (int i = area.getChildCount() - 1; i >= 0; i--) {
            View previous = area.getChildAt(i);
            previous.animate().cancel();
            if (previous != outgoing && previous != activeFace) area.removeViewAt(i);
        }
        boolean returning = activeFace.getParent() == area;
        if (!returning) area.addView(activeFace, new FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP));
        activeFace.bringToFront();
        activeFace.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (outgoing != null) outgoing.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        pageTitle.setText(ModuleTabs.LABELS[module] + "记录");
        pageTitle.setTextColor(face.ink());
        change.setImageTintList(android.content.res.ColorStateList.valueOf(face.ink()));
        change.setContentDescription("切换记录模块 · " + ModuleTabs.LABELS[module]);
        selectedModule.accept(module);
        refresh();
        if (!animate || outgoing == null || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            if (outgoing != null && outgoing != activeFace) area.removeView(outgoing);
            activeFace.setAlpha(1);
            activeFace.setTranslationX(0);
            return;
        }
        if (!returning) {
            activeFace.setAlpha(0f);
            activeFace.setTranslationX(ui.dp(19));
        }
        int time = Transitions.atRate(Transitions.LOCAL_MS);
        outgoing.animate().alpha(0).translationX(-ui.dp(16)).setInterpolator(Transitions.EASE)
                .setDuration(time).withEndAction(() -> {
                    if (outgoing != activeFace) area.removeView(outgoing);
                }).start();
        activeFace.animate().alpha(1).translationX(0).setInterpolator(Transitions.EASE).setDuration(time).start();
    }

    private Face currentFace() {
        return module == ModuleTabs.MUSIC ? musicFace : module == ModuleTabs.PODCAST ? cdFace : readingFace;
    }

    void refresh() {
        playback.saveCurrent();
        refreshCalendar();
        refreshRecords();
    }

    private void refreshMusic() {
        if (module != ModuleTabs.MUSIC) return;
        if (musicTimeRevision == music.getListeningRevision() && renderedMusic == music.getHistory()) return;
        musicTimeRevision = music.getListeningRevision();
        refreshCalendar();
        drawMusicRecords();
    }

    private void drawMusicRecords() {
        String day = filterByDay ? selected.toString() : null;
        Map<String, Long> daily = day == null ? Map.of() : library.musicDay(day);
        List<MusicStore.Song> history = music.getHistory();
        Map<String, Long> seconds = new java.util.HashMap<>();
        daily.forEach((uri, time) -> seconds.put(uri, time / 1000));
        if (renderedMusic == history && Objects.equals(renderedDate, day) && renderedDay.equals(seconds)) return;
        renderedMusic = history;
        renderedDate = day;
        renderedDay = seconds;
        LinearLayout body = musicFace.books;
        body.removeAllViews();
        musicFace.face(body);
        LinearLayout title = ui.row();
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(heading(day == null ? "播放记录" : selected.format(DateTimeFormatter.ofPattern("M 月 d 日")) + "的曲目",
                18, musicFace.ink()), new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        if (day != null) title.addView(musicFace.action("全部记录", () -> {
            filterByDay = false;
            drawMusicRecords();
        }));
        body.addView(title);
        List<MusicStore.Song> songs = day == null ? history : history.stream()
                .filter(song -> daily.containsKey(song.getKey()))
                .sorted(java.util.Comparator.comparingLong((MusicStore.Song song) -> daily.get(song.getKey())).reversed())
                .toList();
        MusicPage.history(activity, ui, music, body, songs, daily);
    }

    private void refreshListeningCovers() {
        if (module == ModuleTabs.PODCAST) cdFace.refreshCovers();
    }

    private LinearLayout card() {
        LinearLayout card = ui.column();
        card.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(12));
        return card;
    }

    private TextView heading(String title, int size, int color) {
        TextView label = ui.text(title, size, color, true);
        if (android.os.Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
        return label;
    }

    private void refreshCalendar() {
        currentFace().drawCalendar();
    }

    private void refreshRecords() {
        if (module == ModuleTabs.MUSIC) drawMusicRecords();
        else {
            currentFace().drawBooks();
            refreshListeningCovers();
        }
    }

    private record AlbumCover(Library.Listening entry, VinylFace face) {}

    private final class Face {
        private final boolean listening, isMusic;
        private final LinearLayout pane;
        private final LinearLayout calendar = card(), books = card();
        private final List<AlbumCover> dayCovers = new ArrayList<>();
        private final List<AlbumCover> recordCovers = new ArrayList<>();
        private YearMonth drawnMonth;
        private LocalDate drawnDate;
        private Map<String, Long> drawnTimes = Map.of();
        private final Map<LocalDate, TextView> musicDays = new java.util.HashMap<>();
        private TextView musicSummary;
        private String drawnRecordDay;
        private List<?> drawnRecords;

        Face(int module) {
            listening = module == ModuleTabs.PODCAST;
            isMusic = module == ModuleTabs.MUSIC;
            boolean wide = TabletLayout.wide(ui);
            pane = wide ? ui.row() : ui.column();
            if (wide) {
                pane.setGravity(Gravity.TOP);
                var chart = new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1);
                chart.setMarginEnd(ui.dp(24));
                pane.addView(calendar, chart);
                pane.addView(books, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
            } else {
                pane.addView(calendar);
                ui.spacer(pane, 16);
                pane.addView(books);
            }
        }

        private AudiobookPage.Colors palette() { return isMusic ? MusicPage.colors(ui) : audio; }

        private TextView heading(String title, int size) {
            return HistoryPage.this.heading(title, size, ink());
        }

        private void face(LinearLayout card) {
            var paper = ui.rounded(listening || isMusic ? palette().card() : ui.dark ? 0xff382c25 : 0xfffff6ea, 16);
            paper.setStroke(ui.dp(1), listening || isMusic ? palette().border() : ui.dark ? 0xff504034 : 0xffead9c6);
            card.setBackground(paper);
        }

        private int ink() { return listening || isMusic ? palette().ink() : ui.ink; }
        private int muted() { return listening || isMusic ? palette().muted() : ui.secondary; }

        private TextView action(String title, Runnable click) {
            return listening || isMusic ? AudiobookPage.button(ui, palette(), title, click) : ui.action(title, click);
        }

        private void drawCalendar() {
            LocalDate first = month.atDay(1);
            String nextMonth = month.plusMonths(1).atDay(1).toString();
            Map<String, Long> times = isMusic ? library.musicDays(first.toString(), nextMonth)
                    : library.dailyTotals(first.toString(), nextMonth, listening);
            if (month.equals(drawnMonth) && selected.equals(drawnDate)) {
                if (times.equals(drawnTimes)) return;
                if (isMusic && musicSummary != null) {
                    musicDays.forEach((date, tile) -> {
                        long elapsed = times.getOrDefault(date.toString(), 0L);
                        if (elapsed != drawnTimes.getOrDefault(date.toString(), 0L)) tuneDay(tile, date, elapsed);
                    });
                    musicSummary.setText(daySummary(times.getOrDefault(selected.toString(), 0L)));
                    drawnTimes = times;
                    return;
                }
            }
            musicDays.clear();
            drawnMonth = month;
            drawnDate = selected;
            drawnTimes = times;
            calendar.removeAllViews();
            dayCovers.clear();
            face(calendar);
            LinearLayout title = ui.row();
            TextView name = heading(isMusic ? "音乐热力图" : listening ? "播客热力图" : "图书热力图", 18);
            title.addView(name, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
            calendar.addView(title);
            LinearLayout controls = ui.row();
            TextView prev = action("‹", () -> jump(-1)), next = action("›", () -> jump(1));
            prev.setContentDescription("上个月");
            next.setContentDescription("下个月");
            controls.addView(prev);
            TextView label = ui.text(month.format(DateTimeFormatter.ofPattern("yyyy 年 M 月")), 16, ink(), true);
            label.setGravity(Gravity.CENTER);
            controls.addView(label, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
            controls.addView(next);
            calendar.addView(controls);
            LinearLayout weekday = ui.row();
            for (String value : new String[]{"一", "二", "三", "四", "五", "六", "日"}) {
                TextView word = ui.text(value, 12, muted(), false);
                word.setGravity(Gravity.CENTER);
                word.setMinHeight(ui.dp(25));
                weekday.addView(word, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
            }
            calendar.addView(weekday);
            int offset = first.getDayOfWeek().getValue() - 1, end = offset + month.lengthOfMonth();
            for (int row = 0; row < (end + 6) / 7; row++) {
                LinearLayout week = ui.row();
                for (int col = 0; col < 7; col++) {
                    int number = row * 7 + col - offset + 1;
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1);
                    cell.setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
                    if (number <= 0 || number > month.lengthOfMonth()) {
                        // Placeholders occupy a column without determining the week's height.
                        cell.height = 0;
                        week.addView(new View(ui.context), cell);
                        continue;
                    }
                    LocalDate date = month.atDay(number);
                    long elapsed = times.getOrDefault(date.toString(), 0L);
                    TextView day = ui.text(Integer.toString(number), 15, ink(), true);
                    day.setGravity(Gravity.CENTER);
                    day.setMinHeight(ui.dp(41));
                    day.setPadding(0, ui.dp(4), 0, ui.dp(4));
                    tuneDay(day, date, elapsed);
                    if (isMusic) musicDays.put(date, day);
                    day.setOnClickListener(v -> {
                        selected = date;
                        filterByDay = true;
                        refreshCalendar();
                        refreshRecords();
                    });
                    week.addView(day, cell);
                }
                calendar.addView(week);
            }
            addLegend();
            TextView summary = ui.text(daySummary(times.getOrDefault(selected.toString(), 0L)), 13, ink(), true);
            if (isMusic) musicSummary = summary;
            LinearLayout daySummary = ui.row();
            daySummary.setMinimumHeight(ui.dp(30));
            daySummary.addView(summary, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
            if (listening && times.getOrDefault(selected.toString(), 0L) > 0) {
                List<Library.Listening> heard = library.listeningDurations(selected.toString());
                for (int index = 0; index < Math.min(3, heard.size()); index++) {
                    addAlbumCover(daySummary, dayCovers, heard.get(index), 28);
                }
            }
            calendar.addView(daySummary);
        }

        private String daySummary(long ms) {
            return selected.format(DateTimeFormatter.ofPattern("M 月 d 日"))
                    + (listening || isMusic ? " · 播放 " : " · 阅读 ") + duration(ms);
        }

        private void tuneDay(TextView day, LocalDate date, long elapsed) {
            int fill = colors()[level(elapsed)];
            int foreground = foreground(fill);
            boolean chosen = date.equals(selected);
            day.setTextColor(foreground);
            var tile = ui.rounded(fill, 7);
            if (chosen) tile.setStroke(ui.dp(2), foreground);
            day.setBackground(tile);
            day.setSelected(chosen);
            day.setContentDescription(date + " · " + duration(elapsed));
        }

        private void addLegend() {
            LinearLayout legend = ui.row();
            legend.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            legend.setMinimumHeight(ui.dp(31));
            legend.addView(ui.text("少  ", 11, muted(), false));
            for (int i = 0; i < colors().length; i++) {
                View swatch = new View(ui.context);
                var shape = ui.rounded(colors()[i], 3);
                shape.setStroke(ui.dp(1), listening ? audio.border() : ui.rule);
                swatch.setBackground(shape);
                swatch.setContentDescription(i == 0 && (listening || isMusic) ? "无播放" : RANGES[i]);
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ui.dp(13), ui.dp(13));
                size.setMargins(ui.dp(2), 0, 0, 0);
                legend.addView(swatch, size);
            }
            legend.addView(ui.text("  多", 11, muted(), false));
            calendar.addView(legend);
        }

        private void drawBooks() {
            String day = filterByDay ? selected.toString() : null;
            List<Library.Listening> cdEntries = listening ? library.listeningDurations(day) : List.of();
            List<Library.Reading> readingEntries = listening ? List.of() : library.durations(day);
            List<?> entries = listening ? cdEntries : readingEntries;
            if (entries.equals(drawnRecords) && Objects.equals(day, drawnRecordDay)) return;
            drawnRecordDay = day;
            drawnRecords = entries;
            books.removeAllViews();
            face(books);
            LinearLayout header = ui.row();
            header.addView(heading(listening ? "收听记录" : "最近读过的书", 18), new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
            TextView range = action(filterByDay ? "查看最近" : "查看所选日期", () -> {
                filterByDay = !filterByDay;
                refreshRecords();
            });
            range.setTextSize(13);
            header.addView(range);
            books.addView(header);
            String period = filterByDay ? selected.format(DateTimeFormatter.ofPattern("M 月 d 日")) : "累计记录";
            books.addView(ui.text(period + (listening ? " · 按播放时长计算占比" : " · 按阅读时长计算占比"), 12, muted(), false));
            ui.spacer(books, 8);
            if (listening) {
                drawListening(cdEntries);
                return;
            }
            if (readingEntries.isEmpty()) {
                TextView empty = ui.text(filterByDay ? "当天还没有阅读记录" : "打开一本书开始记录", 14, ui.secondary, false);
                empty.setPadding(0, ui.dp(12), 0, ui.dp(8));
                books.addView(empty);
            }
            for (int index = 0; index < readingEntries.size(); index++) {
                Library.Reading book = readingEntries.get(index);
                if (index > 0) books.addView(ui.divider());
                LinearLayout row = ui.row();
                row.setPadding(ui.dp(2), ui.dp(9), ui.dp(2), ui.dp(9));
                View icon = ui.bookThumbnail(book, book.bookId() > 0 ? library.cover(book.bookId(), book.coverName()) : null);
                row.addView(icon, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(ui.coverHeight(48))));
                LinearLayout copy = ui.column();
                copy.setPadding(ui.dp(11), 0, 0, 0);
                row.addView(copy, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
                TextView title = ui.text(book.title(), 16, ui.ink, true);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(title);
                TextView author = ui.text(book.author().trim().isEmpty() ? "作者未标注" : book.author(), 13, ui.secondary, false);
                author.setSingleLine(true);
                author.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(author);
                addShare(copy, book.millis(), book.total());
                if (book.bookId() > 0) row.setOnClickListener(v -> open.accept(book.bookId(), row));
                books.addView(row);
            }
        }

        private void drawListening(List<Library.Listening> entries) {
            recordCovers.clear();
            if (entries.isEmpty()) {
                TextView empty = ui.text(filterByDay ? "当天还没有播放记录" : "播放一个播客节目开始记录", 14, muted(), false);
                empty.setPadding(0, ui.dp(12), 0, ui.dp(8));
                books.addView(empty);
            }
            for (Library.Listening entry : entries) {
                LinearLayout row = ui.row();
                row.setMinimumHeight(ui.dp(64));
                row.setPadding(ui.dp(5), ui.dp(6), ui.dp(5), ui.dp(6));
                row.setBackground(PaperMenu.press(ui, Color.TRANSPARENT, 9));
                VinylFace disc = addAlbumCover(row, recordCovers, entry, 52);
                LinearLayout copy = ui.column();
                copy.setPadding(ui.dp(11), 0, 0, 0);
                row.addView(copy, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
                TextView title = ui.text(entry.title(), 16, ink(), true);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                copy.addView(title);
                addShare(copy, entry.millis(), entry.total());
                row.setOnClickListener(v -> openAudio.accept(playback.historyItem(entry.uri(), entry.title()), disc));
                books.addView(row);
            }
        }

        private void addShare(LinearLayout copy, long millis, long total) {
            double share = total <= 0 ? 0 : Math.max(0, Math.min(1, (double) millis / total));
            LinearLayout numbers = ui.row();
            numbers.addView(ui.text(duration(millis), 13, muted(), false),
                    new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
            TextView label = ui.text(percent.format(share), 13, muted(), true);
            label.setSingleLine(true);
            numbers.addView(label);
            copy.addView(numbers);
            ShareBar bar = new ShareBar(share, listening ? audio.border() : ui.rule,
                    listening ? audio.accent() : ui.accent);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ShelfUi.MATCH, ui.dp(5));
            size.topMargin = ui.dp(5);
            copy.addView(bar, size);
        }

        private VinylFace addAlbumCover(LinearLayout row, List<AlbumCover> covers, Library.Listening entry, int size) {
            VinylFace disc = new VinylFace(ui.context);
            row.addView(disc, new LinearLayout.LayoutParams(ui.dp(size), ui.dp(size)));
            covers.add(new AlbumCover(entry, disc));
            return disc;
        }

        private void refreshCovers() {
            refreshCovers(dayCovers);
            refreshCovers(recordCovers);
        }

        private void refreshCovers(List<AlbumCover> albums) {
            for (AlbumCover album : albums) {
                Library.Listening entry = album.entry();
                LibraryItem item = playback.historyItem(entry.uri(), entry.title());
                var cover = catalog.smallPreview(item).getCover();
                if (cover != null) album.face().setCover(cover);
                else catalog.requestSmallPreview(item);
            }
        }

        private int[] colors() {
            if (isMusic) return ui.dark ? MUSIC_NIGHT : MUSIC_DAY;
            if (listening) return ui.dark ? LISTEN_NIGHT : LISTEN_DAY;
            return ui.dark ? NIGHT_COLORS : DAY_COLORS;
        }
    }

    private final class ShareBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final double share;
        private final int track, fill;

        ShareBar(double share, int track, int fill) {
            super(ui.context);
            this.share = share;
            this.track = track;
            this.fill = fill;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            paint.setColor(track);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
            float filled = (float) (getWidth() * share);
            if (filled <= 0) return;
            float left = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? getWidth() - filled : 0;
            paint.setColor(fill);
            canvas.drawRoundRect(left, 0, left + filled, getHeight(), Math.min(radius, filled / 2),
                    radius, paint);
        }
    }

    private static int foreground(int color) {
        return Color.luminance(color) > .179 ? Color.BLACK : Color.WHITE;
    }

    private static int level(long ms) {
        return ms == 0 ? 0 : ms < 300_000 ? 1 : ms < 1_200_000 ? 2 : ms < 3_600_000 ? 3 : 4;
    }

    private void jump(int count) {
        month = month.plusMonths(count);
        selected = month.equals(YearMonth.now()) ? LocalDate.now() : month.atDay(1);
        refreshCalendar();
        if (filterByDay) refreshRecords();
        else refreshListeningCovers();
    }

    private static String duration(long ms) {
        long seconds = ms / 1000;
        if (ms > 0 && seconds == 0) return "不足 1 秒";
        if (seconds < 60) return seconds + " 秒";
        long minutes = seconds / 60;
        return minutes < 60 ? minutes + " 分钟" : minutes / 60 + " 小时 " + minutes % 60 + " 分钟";
    }
}
