package local.bookdoctorc;

import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;
import java.util.List;

final class BookShelfHome {
    private static final int PAGE_SIZE = 24;

    private final MainActivity activity;
    private final ShelfUi ui;
    private final Library library;
    private final DesignHome.Palette colors;
    private final LinearLayout categories, contents;
    private final List<Library.Box> boxes;
    private final TextView heading, total;
    private Library.Box selected;
    private boolean list;
    private int limit = PAGE_SIZE;

    BookShelfHome(MainActivity activity, ShelfUi ui, Library library, LinearLayout root) {
        this.activity = activity;
        this.ui = ui;
        this.library = library;
        colors = DesignHome.books(ui.dark);
        boxes = library.boxes();
        long category = activity.settings().getLong("home_category", 0L);
        selected = boxes.stream().filter(box -> box.id() == category).findFirst()
                .orElse(boxes.isEmpty() ? null : boxes.get(0));
        list = activity.settings().getBoolean("home_book_list", false);
        DesignHome.header(activity, ui, colors, root, "我的书架", true, "搜索图书", activity::searchHomeBooks);

        boolean wide = TabletLayout.wide(ui);
        FrameLayout hero = DesignHome.hero(ui, R.drawable.mook_books_hero, wide ? 160 : 195, .66f, 0xe017263c, 14);
        LinearLayout copy = DesignHome.heroText(ui, hero, wide ? 34 : 17);
        copy.addView(ui.text("书架 · 共 " + library.bookCount() + " 本书", 12, 0xffe0eaf6, false));
        LocalDate today = LocalDate.now();
        long minutes = library.dailyTotals(today.toString(), today.plusDays(1).toString(), false)
                .getOrDefault(today.toString(), 0L) / 60_000;
        String statistic = "今日已阅读 " + minutes + " 分钟";
        SpannableString decorated = new SpannableString(statistic);
        decorated.setSpan(new ForegroundColorSpan(0xffffdbb9), 6, 6 + Long.toString(minutes).length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView title = ui.text("", wide ? 34 : 26, 0xfffffaf3, true);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        title.setText(decorated);
        copy.addView(title);
        ui.spacer(copy, 9);
        TextView recent = DesignHome.action(ui, "最近阅读  ›", 0xff354b66, 0xfff6f1ea, 8, this::showRecent);
        copy.addView(recent, new LinearLayout.LayoutParams(ShelfUi.WRAP, ShelfUi.WRAP));
        root.addView(hero);

        ui.spacer(root, 12);
        HorizontalScrollView rail = new HorizontalScrollView(activity);
        rail.setHorizontalScrollBarEnabled(false);
        categories = ui.row();
        rail.addView(categories, new HorizontalScrollView.LayoutParams(ShelfUi.WRAP, ShelfUi.WRAP));
        root.addView(rail);

        if (boxes.isEmpty()) {
            heading = total = null;
            contents = ui.column();
            root.addView(contents);
            TextView empty = ui.text("在「我的」导入 TXT 或 EPUB", 14, colors.muted(), false);
            empty.setPadding(0, ui.dp(16), 0, ui.dp(12));
            contents.addView(empty);
            return;
        }

        LinearLayout tools = ui.row();
        heading = ui.text("", wide ? 28 : 21, colors.ink(), true);
        heading.setTypeface(Typeface.create("serif", Typeface.BOLD));
        DesignHome.truncate(heading);
        androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
        tools.addView(heading, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        total = ui.text("", 12, colors.muted(), false);
        total.setPadding(0, 0, ui.dp(7), 0);
        tools.addView(total);
        TextView sort = DesignHome.action(ui, "排序", colors.accent(), colors.paper(), 8,
                anchor -> activity.sortHomeBooks(selected, anchor));
        tools.addView(sort);
        ImageToggle toggle = new ImageToggle();
        tools.addView(toggle.view);
        ui.spacer(root, 8);
        root.addView(tools);
        ui.spacer(root, 9);
        contents = ui.column();
        root.addView(contents);
        draw();
    }

    private void draw() {
        categories.removeAllViews();
        for (Library.Box box : boxes) {
            boolean current = box.id() == selected.id();
            TextView chip = DesignHome.action(ui, box.name(), current ? colors.accent() : colors.ink(),
                    current ? colors.selected() : colors.paper(), 16, v -> {
                        if (selected.id() == box.id()) return;
                        selected = box;
                        limit = PAGE_SIZE;
                        activity.settings().edit().putLong("home_category", box.id()).apply();
                        contents.animate().cancel();
                        draw();
                        contents.setAlpha(.55f);
                        contents.animate().alpha(1).setDuration(Transitions.atRate(180))
                                .setInterpolator(Transitions.EASE);
                    });
            chip.setSelected(current);
            chip.setOnLongClickListener(view -> { activity.homeCategoryMenu(box, view); return true; });
            LinearLayout.LayoutParams chipSize = new LinearLayout.LayoutParams(ShelfUi.WRAP, ShelfUi.WRAP);
            chipSize.rightMargin = ui.dp(7);
            categories.addView(chip, chipSize);
        }
        List<Library.Book> books = library.books(selected);
        heading.setText(selected.name());
        total.setText(books.size() + " 本");
        contents.removeAllViews();
        if (books.isEmpty()) {
            contents.addView(ui.text("这个分类还没有图书", 14, colors.muted(), false));
            return;
        }
        int count = Math.min(books.size(), limit);
        addBooks(contents, books.subList(0, count), list ? 1 : TabletLayout.wide(ui) ? 6 : 2, list);
        if (count < books.size()) {
            TextView more = DesignHome.action(ui, "加载更多", colors.accent(), colors.paper(), 8, v -> {
                limit += PAGE_SIZE;
                draw();
            });
            contents.addView(more);
        }
    }

    private void addBooks(LinearLayout parent, List<Library.Book> books, int columns, boolean rows) {
        for (int start = 0; start < books.size(); start += columns) {
            LinearLayout row = ui.row();
            row.setGravity(Gravity.TOP);
            for (int col = 0; col < columns; col++) {
                int i = start + col;
                LinearLayout.LayoutParams slot = new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1);
                if (col < columns - 1) slot.rightMargin = ui.dp(12);
                row.addView(i < books.size() ? book(books.get(i), rows) : new View(activity), slot);
            }
            parent.addView(row);
            ui.spacer(parent, rows ? 7 : 17);
        }
    }

    private View book(Library.Book book, boolean row) {
        LinearLayout card = row ? ui.row() : ui.column();
        FrameLayout size = new FrameLayout(activity) {
            @Override protected void onMeasure(int width, int height) {
                int w = MeasureSpec.getSize(width);
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(ui.coverHeight(w), MeasureSpec.EXACTLY));
            }
        };
        View cover = ui.bookCover(book, library.cover(book), v -> activity.readHomeBook(book, v),
                v -> activity.homeBookMenu(book, v));
        size.addView(cover, new FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH));
        size.setElevation(ui.dp(3));
        card.addView(size, new LinearLayout.LayoutParams(row ? ui.dp(42) : ShelfUi.MATCH, ShelfUi.WRAP));
        LinearLayout words = ui.column();
        if (row) words.setPadding(ui.dp(12), 0, 0, 0);
        else words.setPadding(0, ui.dp(7), 0, 0);
        card.addView(words, row ? new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1)
                : new LinearLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP));
        TextView title = ui.text(book.title(), 14, colors.ink(), true);
        DesignHome.truncate(title);
        words.addView(title);
        LinearLayout meta = ui.row();
        TextView author = ui.text(book.author(), 11, colors.muted(), false);
        DesignHome.truncate(author);
        meta.addView(author, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        TextView format = ui.text(book.epub() ? "EPUB" : "TXT", 10, colors.muted(), false);
        format.setPadding(0, 0, ui.dp(5), 0);
        meta.addView(format);
        LinearLayout progress = ui.row();
        meta.addView(progress);
        Integer percent = activity.homeBookProgress(book, result -> {
            progress.removeAllViews();
            progress.addView(DesignHome.percentage(ui, colors, result, "已读"));
        });
        progress.addView(DesignHome.percentage(ui, colors, percent, "已读"));
        words.addView(meta);
        card.setOnClickListener(v -> activity.readHomeBook(book, cover));
        card.setOnLongClickListener(v -> { activity.homeBookMenu(book, cover); return true; });
        return card;
    }

    private void showRecent(View anchor) {
        List<Library.Book> recent = library.recent();
        LinearLayout body = ui.column();
        if (recent.isEmpty()) {
            body.addView(ui.text("开始阅读后，最近读过的书会出现在这里。", 14, colors.muted(), false));
        } else {
            FrameLayout hero = DesignHome.hero(ui, R.drawable.mook_books_hero, 98, .64f, 0xdf223852, 12);
            LinearLayout words = DesignHome.heroText(ui, hero, 15);
            words.addView(ui.text("最近读过的书", 20, 0xfffffaf3, true));
            words.addView(ui.text(recent.size() + " 本 · 按最近阅读排序", 12, 0xffdbe6f3, false));
            body.addView(hero);
            ui.spacer(body, 14);
            addBooks(body, recent, 3, false);
        }
        activity.homeModal("最近阅读", body, anchor, colors);
    }

    private final class ImageToggle {
        final TextView view = DesignHome.action(ui, list ? "▤" : "⊞", colors.accent(), colors.selected(), 8, v -> {
            list = !list;
            activity.settings().edit().putBoolean("home_book_list", list).apply();
            TextView button = (TextView) v;
            button.setText(list ? "▤" : "⊞");
            button.setContentDescription(list ? "切换封面网格" : "切换列表");
            draw();
        });

        ImageToggle() {
            view.setContentDescription(list ? "切换封面网格" : "切换列表");
            view.setTextSize(18);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ui.dp(42), ShelfUi.WRAP);
            size.leftMargin = ui.dp(5);
            view.setLayoutParams(size);
        }
    }
}
