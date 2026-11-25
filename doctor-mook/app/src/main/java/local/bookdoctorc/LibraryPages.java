package local.bookdoctorc;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.view.*;
import android.widget.*;
import java.util.*;

final class LibraryPages {
    private final MainActivity activity;
    private final ShelfUi ui;
    private final Library library;

    LibraryPages(MainActivity activity,ShelfUi ui,Library library){
        this.activity=activity;this.ui=ui;this.library=library;
    }

    void settings(LinearLayout page){
        page.addView(ui.text("我的",25,ui.ink,true));
        ui.spacer(page,5);
        page.addView(ui.text("个人设置",13,ui.secondary,false));
        ui.spacer(page,17);
        LinearLayout sourceColumn = page, readingColumn = page;
        if (TabletLayout.wide(ui)) {
            LinearLayout columns = ui.row();
            columns.setGravity(Gravity.TOP);
            sourceColumn = ui.column();
            readingColumn = ui.column();
            var left = new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1);
            left.setMarginEnd(ui.dp(24));
            columns.addView(sourceColumn, left);
            columns.addView(readingColumn, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
            page.addView(columns);
        }
        LinearLayout modules = card(sourceColumn, "启动与模块");
        activity.moduleSettings(modules);
        ui.spacer(sourceColumn, 12);
        LinearLayout sources = card(sourceColumn, "文件");
        settingsRow(sources, R.drawable.setting_import, "导入图书", "TXT、EPUB", activity::pickBooksFromSettings);
        settingsRow(sources, R.drawable.setting_podcast_folder, "播客文件夹", activity.selectedDirectory(false), v -> activity.pickAudioFolder());
        settingsRow(sources, R.drawable.setting_podcast_folder, "CLM 内容口令", "用于播放 CLM 2", this::clmPassphrase);
        settingsRow(sources, R.drawable.setting_music_folder, "音乐文件夹", activity.selectedDirectory(true), v -> activity.pickMusicFolder());
        settingsRow(sources, R.drawable.setting_transfer, "用 FileDoctorC 迁移", "选择要迁移的模块及其源文件和记录", activity::migrationSettings);
        if (sourceColumn == readingColumn) ui.spacer(readingColumn, 12);
        LinearLayout reading=card(readingColumn,"阅读");
        settingsRow(reading, R.drawable.setting_theme,
                "主题模式", activity.themeLabel(), activity::chooseMode);
        settingsRow(reading, R.drawable.setting_contents,
                "目录规则", "TXT 标题识别", this::tocRules);
        settingsRow(reading, R.drawable.setting_cleanup,
                "替换与净化规则", "批量修改源文件", this::rules);
        ui.spacer(readingColumn,12);
        LinearLayout libraryGroup=card(readingColumn,"书架资料");
        settingsRow(libraryGroup, R.drawable.setting_bookmark,
                "书签", "继续阅读与标记", this::bookmarks);
        settingsRow(libraryGroup, R.drawable.setting_backup,
                "备份与恢复", "本地 ZIP", this::backup);
    }

    private LinearLayout card(LinearLayout parent,String label){
        LinearLayout box=ui.column();
        box.setPadding(ui.dp(13),ui.dp(10),ui.dp(13),ui.dp(7));
        box.setBackground(ui.rounded(ui.dark?0xff3e3026:0xfff9ebd7,17));
        if(!label.isEmpty()) box.addView(ui.text(label,12,ui.secondary,true));
        parent.addView(box,new LinearLayout.LayoutParams(ShelfUi.MATCH,ShelfUi.WRAP));
        return box;
    }

    private void settingsRow(LinearLayout group, int asset,
                             String title, String subtitle, java.util.function.Consumer<View> open) {
        LinearLayout item = ui.row();
        item.setMinimumHeight(ui.dp(63));
        item.setPaddingRelative(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6));
        item.setBackground(PaperMenu.press(ui, Color.TRANSPARENT, 12));
        ImageView icon = new ImageView(activity);
        icon.setImageResource(asset);
        icon.setBackground(ui.rounded(ui.dark ? 0xff614a39 : 0xffefe0c9, 11));
        int inset = ui.dp(3);
        icon.setPadding(inset, inset, inset, inset);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(icon, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)));

        LinearLayout words = ui.column();
        words.addView(ui.text(title, 16, ui.ink, true));
        TextView detail = ui.text(subtitle, 12, ui.secondary, false);
        detail.setPadding(0, ui.dp(2), 0, 0);
        words.addView(detail);
        LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1);
        text.setMarginStart(ui.dp(12));
        text.setMarginEnd(ui.dp(8));
        item.addView(words, text);
        ImageView next = new ImageView(activity);
        next.setImageResource(R.drawable.setting_next);
        next.setImageTintList(ColorStateList.valueOf(ui.accent));
        next.setScaleType(ImageView.ScaleType.FIT_CENTER);
        next.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(next, new LinearLayout.LayoutParams(ui.dp(12), ui.dp(16)));
        if (Build.VERSION.SDK_INT >= 28) item.setScreenReaderFocusable(true);
        item.setOnClickListener(open::accept);
        group.addView(item, new LinearLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP));
    }

    private void clmPassphrase(View anchor) {
        PaperMenu menu = new PaperMenu(ui, "", "CLM 内容口令", "");
        menu.body.addView(ui.text("输入制作这批 CLM 时使用的口令。口令仅保存在此设备。", 14, ui.secondary, false));
        EditText input = new EditText(activity);
        input.setSingleLine();
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setSaveEnabled(false);
        input.setHint("输入内容口令");
        input.setTextColor(ui.ink);
        input.setHintTextColor(ui.secondary);
        menu.body.addView(input, new LinearLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP));
        View save = ui.text("保存口令", 16, ui.accent, true);
        save.setMinimumHeight(ui.dp(48));
        menu.body.addView(save, new LinearLayout.LayoutParams(ShelfUi.MATCH, ShelfUi.WRAP));
        save.setOnClickListener(v -> {
            if (input.length() == 0) {
                input.setError("请输入口令");
                return;
            }
            try {
                ClmPassphrase.save(activity, input.getText().toString());
                input.setText("");
                menu.select(() -> activity.clmPassphraseChanged("CLM 内容口令已保存"));
            } catch (Exception failure) {
                activity.toast("无法保存到此设备，请重试");
            }
        });
        menu.danger("清除本机口令", "下次打开 CLM 时需要重新设置", () -> {
            try {
                ClmPassphrase.clear(activity);
                activity.clmPassphraseChanged("已清除 CLM 内容口令");
            } catch (Exception failure) {
                activity.toast("无法清除口令，请重试");
            }
        });
        menu.showNear(anchor);
    }

    private void tocRules(View anchor){
        PaperMenu menu = new PaperMenu(ui, "", "目录规则", "");
        LinearLayout page = menu.body;
        page.addView(ui.text("勾选 TXT 的章节标题样式。只识别独立标题行，卷下的章节可折叠。EPUB 使用书内目录。",14,ui.secondary,false));
        ui.spacer(page,13);
        LinearLayout choices=card(page,"标题格式");
        for(TxtContents.Kind kind:TxtContents.Kind.values()){
            CheckBox check=new CheckBox(activity);
            check.setText(kind.label+(kind.volume?"　·　卷":""));
            check.setTextSize(16);
            check.setTextColor(ui.ink);
            check.setButtonTintList(ColorStateList.valueOf(ui.accent));
            check.setMinHeight(ui.dp(49));
            check.setChecked((TxtContents.mask(activity)&kind.bit())!=0);
            check.setOnCheckedChangeListener((view,enabled)->{
                int mask=TxtContents.mask(activity);
                activity.settings().edit().putInt(TxtContents.KEY,enabled?mask|kind.bit():mask&~kind.bit()).apply();
            });
            choices.addView(check);
        }
        menu.showNear(anchor);
    }

    private void rules(View anchor) {
        PaperMenu menu = new PaperMenu(ui, "", "替换与净化规则", "");
        drawRules(menu.body, null);
        menu.showNear(anchor);
    }

    void readerRules(ReadingUi reading, View anchor) {
        LinearLayout page = ui.column();
        drawRules(page, reading);
        activity.panel("替换净化规则", page, anchor, true);
    }

    private void drawRules(LinearLayout page, ReadingUi reading){
        page.removeAllViews();
        page.addView(ui.text("按文字逐处查找。替换文字留空即可删除命中的内容。",14,ui.secondary,false));
        ui.spacer(page,10);
        page.addView(ui.action("＋ 新建规则",()->editRule(page,null,reading)));
        List<Library.Rule> rules=library.rules();
        if(rules.isEmpty()) page.addView(ui.text("还没有规则",14,ui.secondary,false));
        else{
            LinearLayout group=card(page,"已保存规则");
            for(Library.Rule rule:rules){
                LinearLayout line=ui.row();
                TextView label=ui.text(rule.name(),16,ui.ink,true);
                line.addView(label,new LinearLayout.LayoutParams(0,ui.dp(45),1));
                label.setGravity(Gravity.CENTER_VERTICAL);
                label.setOnClickListener(v->editRule(page,rule,reading));
                TextView apply = ui.action("应用", null);
                apply.setOnClickListener(anchor -> {
                    if (reading == null) chooseBook(rule, anchor);
                    else apply(rule, reading.book, reading);
                });
                line.addView(apply);
                line.addView(ui.action("编辑",()->editRule(page,rule,reading)));
                group.addView(line);
                group.addView(ui.divider());
            }
        }
    }

    private void editRule(LinearLayout page, Library.Rule rule, ReadingUi reading){
        EditText name=activity.input("规则名称",rule==null?"":rule.name());
        EditText find=activity.input("查找文字",rule==null?"":rule.find());
        EditText replace=activity.input("替换文字，留空则删除",rule==null?"":rule.replace());
        find.setSingleLine(false);find.setMaxLines(3);
        replace.setSingleLine(false);replace.setMaxLines(3);
        LinearLayout fields=ui.column();
        fields.addView(name);fields.addView(find);fields.addView(replace);
        var dialog=activity.dialog().setTitle(rule==null?"新建规则":"编辑规则").setView(activity.padded(fields))
                .setNegativeButton("取消",null).setPositiveButton("保存",null);
        if(rule!=null) dialog.setNeutralButton("删除",(d,w)->{library.removeRule(rule.id());drawRules(page, reading);});
        var window=dialog.create();
        window.setOnShowListener(d->window.getButton(-1).setOnClickListener(v->{
            String needle=find.getText().toString();
            if(needle.isEmpty()){find.setError("请输入要查找的文字");return;}
            String label=name.getText().toString().trim();
            if(label.isEmpty()) label=needle.length()>20?needle.substring(0,20):needle;
            library.saveRule(new Library.Rule(rule==null?0:rule.id(),label,needle,replace.getText().toString()));
            window.dismiss();
            drawRules(page, reading);
        }));
        window.show();
    }

    private void chooseBook(Library.Rule rule, View anchor){
        List<Library.Book> books=library.allBooks();
        if(books.isEmpty()){activity.toast("请先导入书籍");return;}
        LinearLayout page=ui.column();
        EditText filter=activity.input("按书名或文件名筛选","");
        page.addView(filter);
        LinearLayout list=ui.column();
        page.addView(list);
        java.util.function.Consumer<String> draw=query->{
            list.removeAllViews();
            String key=query.trim().toLowerCase(Locale.ROOT);
            for(Library.Book book:books){
                if(!book.title().toLowerCase(Locale.ROOT).contains(key) && !book.name().toLowerCase(Locale.ROOT).contains(key))continue;
                settingsRow(list, R.drawable.setting_import, book.title(), book.name(), v -> apply(rule,book,null));
            }
            if(list.getChildCount()==0)list.addView(ui.text("没有匹配的书",14,ui.secondary,false));
        };
        filter.addTextChangedListener(new android.text.TextWatcher(){
            @Override public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence text,int start,int before,int count){draw.accept(text.toString());}
            @Override public void afterTextChanged(android.text.Editable text){}
        });
        draw.accept("");
        activity.panel("选择要修改的书",page,anchor,true);
    }

    private void apply(Library.Rule rule, Library.Book book, ReadingUi reading){
        if(!activity.sourceFiles().canWrite(book.uri())){
            activity.toast("此文件夹没有写权限，请重新授权可写目录");
            return;
        }
        activity.job("查找文字…",()->BatchReplace.prepare(activity.sourceFiles(),book,rule),batch->{
            if(batch.count==0){batch.close();activity.toast("没有匹配的文字");return;}
            var prompt=activity.dialog().setTitle("命中 "+batch.count+" 处")
                    .setMessage("确认后写回《"+book.title()+"》的源文件，并保留修改前备份。")
                    .setNegativeButton("取消",(d,w)->batch.close())
                    .setPositiveButton("写回源文件",(d,w)->activity.job("正在校验并写回…",()->{
                        try (batch) {
                            batch.save();
                            return reading == null ? null : ReaderDocument.open(activity, activity.sourceFiles(), book);
                        }
                    }, revised -> {
                        if (revised != null) reading.replace(revised);
                        activity.closePanel();
                        activity.toast("已替换 " + batch.count + " 处");
                    })).create();
            prompt.setOnCancelListener(d->batch.close());
            prompt.show();
        });
    }

    private void bookmarks(View anchor){
        LinearLayout page=ui.column();
        drawBookmarks(page);
        activity.panel("书签",page,anchor,true);
    }

    private void drawBookmarks(LinearLayout page){
        page.removeAllViews();
        List<Library.Bookmark> marks=library.bookmarks();
        if(marks.isEmpty()){page.addView(ui.text("在阅读页的「选项」中添加书签。",14,ui.secondary,false));return;}
        for(var bookmark:marks){
            Library.Book book=library.book(bookmark.book());
            if(book==null) continue;
            LinearLayout line=ui.row();
            LinearLayout words=ui.column();
            words.setPadding(0,ui.dp(9),0,ui.dp(9));
            words.addView(ui.text(bookmark.label(),16,ui.ink,true));
            words.addView(ui.text(book.title(),13,ui.secondary,false));
            words.setOnClickListener(v -> activity.openBookmark(book, bookmark, words));
            line.addView(words,new LinearLayout.LayoutParams(0,ShelfUi.WRAP,1));
            line.addView(ui.action("移除",()->{library.removeBookmark(bookmark.id());drawBookmarks(page);}));
            page.addView(line);
            page.addView(ui.divider());
        }
    }

    private void backup(View anchor){
        LinearLayout page=ui.column();
        page.addView(ui.text("将书架、书签、阅读时长、规则和封面保存为 ZIP。书籍正文继续由原位置管理。",14,ui.secondary,false));
        ui.spacer(page,14);
        LinearLayout list=card(page,"本地存储");
        settingsRow(list, R.drawable.setting_backup, "创建本地备份", "保存到你指定的文件夹", v -> activity.beginLocalBackup());
        settingsRow(list, R.drawable.setting_restore, "恢复本地备份", "合并书架记录和设置", v -> activity.restoreLocalBackup(v));
        activity.panel("备份与恢复",page,anchor,true);
    }

    void contents(ReadingUi reader,View anchor){
        LinearLayout page=ui.column();
        Set<Integer> folded=new HashSet<>();
        List<ReaderDocument.Toc> entries=reader.document.contents();
        for(int i=0;i<entries.size();i++) if(entries.get(i).group()) folded.add(i);
        for(int i=0;i<entries.size();i++){
            if(entries.get(i).chapter()!=reader.index() || entries.get(i).group())continue;
            int level=entries.get(i).level();
            for(int k=i-1;k>=0 && level>0;k--) if(entries.get(k).level()<level && entries.get(k).group()){
                folded.remove(k);level=entries.get(k).level();
            }
            break;
        }
        drawContents(reader,page,entries,folded);
        activity.panel("目录",page,anchor,true);
    }

    private void drawContents(ReadingUi reader,LinearLayout page,List<ReaderDocument.Toc> entries,Set<Integer> folded){
        page.removeAllViews();
        int hiddenDepth=-1;
        for(int i=0;i<entries.size();i++){
            final int key=i;
            ReaderDocument.Toc toc=entries.get(i);
            if(hiddenDepth>=0 && toc.level()>hiddenDepth) continue;
            hiddenDepth=-1;
            LinearLayout row=ui.row();
            row.setPadding(ui.dp(Math.min(5,toc.level())*14+6),0,ui.dp(6),0);
            row.setMinimumHeight(ui.dp(48));
            boolean closed=folded.contains(key);
            TextView title=ui.text((toc.group()?(closed?"▸  ":"▾  "):"")+toc.title(),15,
                    toc.chapter()==reader.index()?ui.accent:ui.ink,toc.group()||toc.chapter()==reader.index());
            title.setMaxLines(2);
            title.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(title,new LinearLayout.LayoutParams(0,ui.dp(48),1));
            Runnable jump=()->{activity.closePanel();reader.show(toc.chapter(),0,toc.fragment());};
            if(toc.group()){
                title.setOnClickListener(v->{if(!folded.add(key))folded.remove(key);drawContents(reader,page,entries,folded);});
                if(toc.chapter()>=0)row.addView(ui.action("打开",jump));
                if(closed)hiddenDepth=toc.level();
            }else if(toc.chapter()>=0)title.setOnClickListener(v->jump.run());
            page.addView(row);
        }
    }
}
