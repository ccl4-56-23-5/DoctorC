package local.bookdoctorc;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;

final class BookInfoEditor {
    record CoverDraft(File file) implements Closeable {
        static CoverDraft write(File directory, Bitmap image) throws IOException {
            File file = File.createTempFile("book-cover-draft-", ".jpg", directory);
            try {
                SourceFiles.storeCover(file, image);
                if (file.length() == 0) throw new IOException("无法保存封面预览");
                return new CoverDraft(file);
            } catch (IOException | RuntimeException error) {
                file.delete();
                throw error;
            } finally {
                image.recycle();
            }
        }

        @Override public void close() { file.delete(); }
    }

    final Library.Book book;
    final LinearLayout view;
    private final ShelfUi ui;
    private final File originalCover;
    private final FrameLayout preview;
    private final TextView coverStatus;
    private final EditText title, author, description;
    private CoverDraft pendingCover;
    private boolean saved;

    BookInfoEditor(MainActivity activity, ShelfUi ui, Library.Book book, File originalCover, Bundle draft) {
        this.ui = ui;
        this.book = book;
        this.originalCover = originalCover;
        view = ui.column();
        view.setPadding(0, ui.dp(4), 0, ui.dp(20));

        LinearLayout coverSection = ui.row();
        coverSection.setGravity(Gravity.CENTER_VERTICAL);
        coverSection.setPadding(ui.dp(13), ui.dp(14), ui.dp(13), ui.dp(14));
        coverSection.setBackground(PaperMenu.outline(ui, ui.dark ? 0xff352b24 : 0xfffdf5e7, 18));
        preview = new FrameLayout(activity);
        preview.setElevation(ui.dp(3));
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        int coverWidth = activity.getResources().getConfiguration().screenWidthDp < 360 ? 68 : 88;
        coverSection.addView(preview, new LinearLayout.LayoutParams(ui.dp(coverWidth),
                ui.dp(Math.round(coverWidth / ShelfUi.COVER_RATIO))));

        LinearLayout coverControls = ui.column();
        LinearLayout.LayoutParams controls = new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1);
        controls.leftMargin = ui.dp(14);
        coverSection.addView(coverControls, controls);
        coverControls.addView(ui.text("书籍封面", 17, ui.ink, true));
        coverStatus = ui.text("", 12, ui.secondary, false);
        coverControls.addView(coverStatus);
        ui.spacer(coverControls, 7);
        View importCover = PaperMenu.option(ui, PaperMenu.Icon.IMAGE, "从本机导入", () -> {});
        importCover.setOnClickListener(anchor -> activity.importCover(this, anchor));
        coverControls.addView(importCover);
        ui.spacer(coverControls, 5);
        view.addView(coverSection);
        ui.spacer(view, 19);

        title = field(activity, "标题", "输入书籍标题", draft == null ? book.title() : draft.getString("title", book.title()), 240, false);
        author = field(activity, "作者", "填写作者（可选）", draft == null ? book.author() : draft.getString("author", book.author()), 240, false);
        description = field(activity, "内容简介", "填写简介（可选）",
                draft == null ? book.description() : draft.getString("description", book.description()), 12000, true);
        TextView source = ui.text("原文件 · " + book.name(), 12, ui.secondary, false);
        source.setMaxLines(2);
        source.setEllipsize(android.text.TextUtils.TruncateAt.END);
        view.addView(source);

        if (draft != null) {
            String name = draft.getString("cover", "");
            if (name.startsWith("book-cover-draft-") && name.equals(new File(name).getName())) {
                File file = new File(activity.getCacheDir(), name);
                if (file.isFile()) pendingCover = new CoverDraft(file);
            }
        }
        refreshCover();
    }

    private EditText field(MainActivity activity, String label, String hint, String text, int limit, boolean multiline) {
        TextView name = ui.text(label, 14, ui.ink, true);
        view.addView(name);
        ui.spacer(view, 5);
        EditText edit = activity.paperInput(hint, text);
        edit.setId(View.generateViewId());
        name.setLabelFor(edit.getId());
        edit.setFilters(new InputFilter[]{new InputFilter.LengthFilter(Math.max(limit, text.length()))});
        if (multiline) {
            edit.setSingleLine(false);
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            edit.setGravity(Gravity.TOP | Gravity.START);
            edit.setMinLines(4);
        }
        view.addView(edit, new LinearLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP));
        ui.spacer(view, 14);
        return edit;
    }

    String title() { return title.getText().toString().trim(); }
    String author() { return author.getText().toString().trim(); }
    String description() { return description.getText().toString().trim(); }
    File cover() { return pendingCover == null ? null : pendingCover.file(); }

    boolean validate() {
        if (!title().isBlank()) return true;
        title.requestFocus();
        title.setError("请输入书籍标题");
        return false;
    }

    boolean changed() {
        return !saved && (pendingCover != null || !title().equals(book.title())
                || !author().equals(book.author()) || !description().equals(book.description()));
    }

    void useCover(CoverDraft cover) {
        close();
        pendingCover = cover;
        refreshCover();
    }

    private void refreshCover() {
        preview.removeAllViews();
        preview.addView(ui.coverPreview(book, pendingCover == null ? originalCover : pendingCover.file()),
                new FrameLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.MATCH));
        coverStatus.setText(pendingCover == null ? "当前封面" : "已选新封面，待保存");
    }

    void markSaved() {
        saved = true;
        close();
    }

    Bundle state() {
        if (saved) return null;
        Bundle state = new Bundle();
        state.putLong("id", book.id());
        state.putString("title", title.getText().toString());
        state.putString("author", author.getText().toString());
        state.putString("description", description.getText().toString());
        if (pendingCover != null) state.putString("cover", pendingCover.file().getName());
        return state;
    }

    void close() {
        if (pendingCover != null) pendingCover.close();
        pendingCover = null;
    }
}
