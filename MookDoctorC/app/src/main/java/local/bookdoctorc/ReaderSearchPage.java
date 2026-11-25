package local.bookdoctorc;

import android.content.Context;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.InputFilter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class ReaderSearchPage {
    private final MainActivity activity;
    private final ShelfUi ui;
    private final ReadingUi reading;
    private final EditText query;
    private final LinearLayout results;
    private volatile int request;
    final LinearLayout view;

    ReaderSearchPage(MainActivity activity, ShelfUi ui, ReadingUi reading) {
        this.activity = activity;
        this.ui = ui;
        this.reading = reading;
        view = ui.column();
        LinearLayout controls = ui.row();
        query = activity.input("搜索本书正文", "");
        query.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        query.setSingleLine();
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            search();
            return true;
        });
        controls.addView(query, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        controls.addView(ui.action("搜索", this::search));
        view.addView(controls);
        ui.spacer(view, 12);
        results = ui.column();
        view.addView(results);
    }

    void close() { request++; }

    void focus() { activity.focusSearch(query); }

    private void search() {
        String needle = query.getText().toString().strip();
        if (needle.isEmpty()) {
            query.setError("请输入搜索文字");
            return;
        }
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(query.getWindowToken(), 0);
        int token = ++request;
        message("正在搜索正文…");
        reading.search(needle, () -> token != request, found -> {
            if (token != request) return;
            if (found.hits().isEmpty()) {
                message("没有找到匹配的正文");
                return;
            }
            message(found.more() ? "显示前 " + found.hits().size() + " 条结果" : found.hits().size() + " 条结果");
            for (ReaderSearch.Hit hit : found.hits()) {
                LinearLayout item = ui.column();
                item.setPadding(ui.dp(5), ui.dp(10), ui.dp(5), ui.dp(10));
                item.addView(ui.text(reading.document.chapters.get(hit.chapter()).title(), 14, ui.accent, true));
                TextView excerpt = ui.text(hit.preview(), 15, ui.ink, false);
                excerpt.setMaxLines(3);
                item.addView(excerpt);
                item.setOnClickListener(v -> {
                    activity.readSearchHit(reading, hit);
                });
                results.addView(item);
                results.addView(ui.divider());
            }
        });
    }

    private void message(String text) {
        results.removeAllViews();
        results.addView(ui.text(text, 13, ui.secondary, false));
    }
}
