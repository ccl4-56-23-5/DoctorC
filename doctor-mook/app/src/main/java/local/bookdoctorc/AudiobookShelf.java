package local.bookdoctorc;

import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.clmplayer.LibraryItem;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AudiobookShelf {
    private record Series(String id, String name, List<LibraryItem> entries) {}

    private static final int PAGE_SIZE = 24;
    private static final int[] STATIONS = {R.drawable.mook_station_0, R.drawable.mook_station_1, R.drawable.mook_station_2};
    private final MainActivity activity;
    private final ShelfUi ui;
    private final DesignHome.Palette colors;
    private final ClmPlayback playback;
    private final AudiobookCatalog catalog;
    private final TextView title, resume, time, totals, status;
    private final LinearLayout stations, episodes;
    private final Map<LibraryItem, TextView> names = new LinkedHashMap<>();
    private final Map<LibraryItem, LinearLayout> badges = new LinkedHashMap<>();
    private final Runnable observer = this::refresh;
    private final Runnable catalogObserver = this::syncTitles;
    private List<LibraryItem> displayed = null;
    private List<Series> series = List.of();
    private Series selected;
    private int visibleEpisodes = PAGE_SIZE;
    private long nextStats;
    private LocalDate statsDay;

    AudiobookShelf(MainActivity activity, ShelfUi ui, LinearLayout root, ClmPlayback playback) {
        this.activity = activity;
        this.ui = ui;
        this.playback = playback;
        catalog = playback.getCatalog();
        colors = DesignHome.podcast(ui.dark);
        boolean wide = TabletLayout.wide(ui);
        DesignHome.header(activity, ui, colors, root, "播客", false, "搜索播客", this::search);

        FrameLayout spotlight = DesignHome.hero(ui, R.drawable.mook_podcast_hero, wide ? 218 : 238, .65f, 0xee2d1c2a, 15);
        LinearLayout copy = DesignHome.heroText(ui, spotlight, wide ? 38 : 16);
        title = ui.text("暂无播放记录", wide ? 41 : 28, 0xfffff1e9, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setMaxWidth(ui.dp(wide ? Math.max(320, activity.getResources().getConfiguration().screenWidthDp / 2) : 245));
        copy.addView(title, new LinearLayout.LayoutParams(ShelfUi.WRAP, ShelfUi.WRAP));
        ui.spacer(copy, 13);
        resume = DesignHome.action(ui, "▶  继续收听", 0xff422436, 0xffffc4a6, 20, anchor -> {
            ClmPlayback.LastListened last = playback.lastListened();
            if (last != null) {
                activity.openAudioItem(last.getItem(), anchor, ui.dp(19));
            }
        });
        copy.addView(resume, new LinearLayout.LayoutParams(ShelfUi.WRAP, ShelfUi.WRAP));
        root.addView(spotlight);
        ui.spacer(root, 4);

        LinearLayout strip = ui.row();
        time = ui.text("", 12, colors.accent(), true);
        time.setSingleLine();
        strip.addView(time);
        totals = ui.text("", 11, colors.muted(), false);
        totals.setPadding(ui.dp(9), 0, ui.dp(2), 0);
        DesignHome.truncate(totals);
        strip.addView(totals, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        TextView recent = DesignHome.action(ui, "最近收听  ›", colors.accent(), 0, 7, this::showRecent);
        recent.setTextSize(12);
        recent.setPadding(ui.dp(5), 0, ui.dp(2), 0);
        strip.addView(recent);
        root.addView(strip, new LinearLayout.LayoutParams(ShelfUi.MATCH, ui.dp(49)));
        DesignHome.divider(ui, colors, root);
        ui.spacer(root, 13);

        LinearLayout browser = wide ? ui.row() : ui.column();
        browser.setGravity(Gravity.TOP);
        LinearLayout left = ui.column();
        LinearLayout navHeader = ui.row();
        TextView label = ui.text("节目系列", 18, colors.ink(), true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(label, true);
        navHeader.addView(label, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        left.addView(navHeader);
        ui.spacer(left, 7);
        stations = wide ? ui.column() : ui.row();
        if (wide) left.addView(stations);
        else {
            HorizontalScrollView scroll = new HorizontalScrollView(activity);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.addView(stations, new HorizontalScrollView.LayoutParams(ShelfUi.WRAP, ShelfUi.WRAP));
            left.addView(scroll);
        }
        LinearLayout.LayoutParams leftSize = new LinearLayout.LayoutParams(wide ? 0 : ShelfUi.MATCH, ShelfUi.WRAP, wide ? 1.1f : 0);
        if (wide) leftSize.rightMargin = ui.dp(34);
        else leftSize.bottomMargin = ui.dp(15);
        browser.addView(left, leftSize);

        episodes = ui.column();
        browser.addView(episodes, new LinearLayout.LayoutParams(wide ? 0 : ShelfUi.MATCH, ShelfUi.WRAP, wide ? 2.9f : 0));
        root.addView(browser);
        status = ui.text("", 13, colors.muted(), false);
        status.setPadding(0, ui.dp(12), 0, 0);
        root.addView(status);

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { attach(); }
            @Override public void onViewDetachedFromWindow(View v) {
                playback.removeObserver(observer);
                catalog.removeObserver(catalogObserver);
            }
        });
        if (root.isAttachedToWindow()) attach();
        else refresh();
    }

    private void attach() {
        playback.addObserver(observer);
        catalog.addObserver(catalogObserver);
        nextStats = 0;
        refresh();
    }

    void onViewportChanged() {
        refresh();
        syncProgress();
    }

    private void refresh() {
        ClmPlayback.LastListened last = playback.lastListened();
        String headline = last == null ? "暂无播放记录" : last.getTitle() + "·第" + (last.getIndex() + 1) + "集";
        if (!TextUtils.equals(title.getText(), headline)) title.setText(headline);
        resume.setVisibility(last == null ? View.GONE : View.VISIBLE);
        LocalDate today = LocalDate.now();
        long now = SystemClock.elapsedRealtime();
        if (now >= nextStats || !today.equals(statsDay)) {
            long millis = Library.get(activity).dailyTotals(today.toString(), today.plusDays(1).toString(), true)
                    .getOrDefault(today.toString(), 0L);
            time.setText("今日已收听 " + millis / 60_000 + " 分钟");
            nextStats = now + 5_000;
            statsDay = today;
            if (activity.isAudioShelfCurrent()) syncProgress();
        }

        List<LibraryItem> sources = playback.getEntries();
        if (!sources.equals(displayed)) {
            displayed = sources;
            Map<String, Series> grouped = new LinkedHashMap<>();
            for (LibraryItem item : sources) {
                grouped.computeIfAbsent(item.getFolderId(), id -> new Series(id, item.getFolder(), new ArrayList<>()))
                        .entries().add(item);
            }
            series = List.copyOf(grouped.values());
            String id = selected == null ? activity.settings().getString("home_podcast_series", "") : selected.id();
            selected = grouped.getOrDefault(id, series.isEmpty() ? null : series.get(0));
            totals.setText(series.size() + " 档节目 · " + sources.size() + " 集");
            drawLists();
        }
        String note = playback.getScanError() != null ? "无法扫描文件夹，请重新选择目录"
                : playback.getScanning() ? "正在扫描文件夹…"
                : sources.isEmpty() ? "在「我的」选择播客文件夹" : "";
        if (!TextUtils.equals(status.getText(), note)) status.setText(note);
        status.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void drawLists() {
        boolean wide = TabletLayout.wide(ui);
        stations.removeAllViews();
        for (int index = 0; index < series.size(); index++) {
            Series group = series.get(index);
            boolean active = group == selected;
            LinearLayout station = ui.row();
            station.setPadding(ui.dp(5), ui.dp(5), ui.dp(6), ui.dp(5));
            station.setBackground(DesignHome.surface(ui, active ? colors.selected() : 0, 0, 9));
            ImageView photo = new ImageView(activity);
            photo.setImageResource(STATIONS[index % STATIONS.length]);
            photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
            photo.setBackground(ui.rounded(colors.selected(), 7));
            photo.setClipToOutline(true);
            int side = ui.dp(wide ? 37 : 32);
            station.addView(photo, new LinearLayout.LayoutParams(side, side));
            LinearLayout words = ui.column();
            words.setPadding(ui.dp(7), 0, 0, 0);
            TextView name = ui.text(group.name(), wide ? 13 : 12, active ? colors.accent() : colors.ink(), true);
            DesignHome.truncate(name);
            words.addView(name);
            words.addView(ui.text(group.entries().size() + " 集", 10, colors.muted(), false));
            station.addView(words, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
            station.setSelected(active);
            station.setOnClickListener(v -> {
                if (active) return;
                selected = group;
                visibleEpisodes = PAGE_SIZE;
                activity.settings().edit().putString("home_podcast_series", group.id()).apply();
                drawLists();
                episodes.setAlpha(.55f);
                episodes.animate().alpha(1).setDuration(Transitions.atRate(180)).setInterpolator(Transitions.EASE);
                if (!wide) stations.getChildAt(series.indexOf(group)).requestFocus();
            });
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(wide ? ShelfUi.MATCH : ui.dp(112), ShelfUi.WRAP);
            if (wide) size.bottomMargin = ui.dp(5); else size.rightMargin = ui.dp(4);
            stations.addView(station, size);
        }
        episodes.removeAllViews();
        names.clear();
        badges.clear();
        if (selected == null) {
            episodes.addView(ui.text("暂无节目", 14, colors.muted(), false));
            return;
        }
        LinearLayout heading = ui.row();
        TextView seriesName = ui.text(selected.name(), 18, colors.ink(), true);
        androidx.core.view.ViewCompat.setAccessibilityHeading(seriesName, true);
        DesignHome.truncate(seriesName);
        heading.addView(seriesName, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        heading.addView(ui.text(selected.entries().size() + " 集", 12, colors.muted(), false));
        episodes.addView(heading);
        ui.spacer(episodes, 7);
        DesignHome.divider(ui, colors, episodes);
        int end = Math.min(selected.entries().size(), visibleEpisodes);
        for (LibraryItem item : selected.entries().subList(0, end)) addEpisode(episodes, item, true);
        if (end < selected.entries().size()) {
            episodes.addView(DesignHome.action(ui, "加载更多", colors.accent(), colors.paper(), 7, v -> {
                visibleEpisodes += PAGE_SIZE;
                drawLists();
            }));
        }
    }

    private void addEpisode(LinearLayout parent, LibraryItem item, boolean observe) {
        LinearLayout row = ui.row();
        row.setPadding(ui.dp(6), ui.dp(6), ui.dp(4), ui.dp(6));
        row.setMinimumHeight(ui.dp(52));
        row.setBackground(DesignHome.surface(ui, 0, 0, 5));
        TextView play = ui.text("▶", 10, colors.accent(), true);
        play.setBackground(ui.rounded(colors.selected(), 20));
        play.setGravity(Gravity.CENTER);
        row.addView(play, new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        LinearLayout words = ui.column();
        words.setPadding(ui.dp(10), 0, ui.dp(8), 0);
        AudiobookCatalog.Preview episode = catalog.smallPreview(item);
        TextView name = ui.text(episode.getTitle(), 13, colors.ink(), true);
        DesignHome.truncate(name);
        words.addView(name);
        TextView byline = ui.text(item.getFolder(), 11, colors.muted(), false);
        DesignHome.truncate(byline);
        words.addView(byline);
        if (observe) {
            names.put(item, name);
            catalog.requestSmallPreview(item);
        }
        row.addView(words, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        LinearLayout badge = ui.row();
        setProgress(badge, playback.listenedPercent(item));
        row.addView(badge);
        if (observe) badges.put(item, badge);
        row.setOnClickListener(v -> activity.openAudioItem(item, v, ui.dp(9)));
        parent.addView(row);
        DesignHome.divider(ui, colors, parent);
    }

    private void syncProgress() {
        badges.forEach((item, badge) -> setProgress(badge, playback.listenedPercent(item)));
    }

    private void setProgress(LinearLayout badge, Integer percent) {
        if (badge.getChildCount() != 0 && java.util.Objects.equals(badge.getTag(), percent)) return;
        badge.setTag(percent);
        badge.removeAllViews();
        badge.addView(DesignHome.percentage(ui, colors, percent, "已听"));
    }

    private void syncTitles() {
        names.forEach((item, label) -> label.setText(catalog.smallPreview(item).getTitle()));
    }

    private void showRecent(View anchor) {
        LinearLayout body = ui.column();
        List<LibraryItem> recent = playback.getRecentEntries();
        if (recent.isEmpty()) body.addView(ui.text("收听节目后，最近记录会出现在这里。", 14, colors.muted(), false));
        else recent.stream().limit(6).forEach(item -> addEpisode(body, item, false));
        activity.homeModal("最近收听", body, anchor, colors);
    }

    private void search(View anchor) {
        LinearLayout body = ui.column();
        EditText field = activity.paperInput("节目或单集名称", "");
        body.addView(field);
        LinearLayout results = ui.column();
        body.addView(results);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                results.removeAllViews();
                String query = text.toString().trim().toLowerCase(Locale.ROOT);
                if (query.isEmpty()) return;
                List<LibraryItem> matched = playback.getEntries().stream().filter(item ->
                        (catalog.smallPreview(item).getTitle() + "\n" + item.getName() + "\n" + item.getFolder())
                                .toLowerCase(Locale.ROOT).contains(query)).limit(80).collect(java.util.stream.Collectors.toList());
                if (matched.isEmpty()) results.addView(ui.text("找不到匹配的节目", 14, colors.muted(), false));
                else matched.forEach(item -> addEpisode(results, item, false));
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        activity.homeModal("搜索播客", body, anchor, colors);
        activity.focusSearch(field);
    }
}
