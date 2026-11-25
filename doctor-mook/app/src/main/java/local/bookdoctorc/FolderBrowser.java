package local.bookdoctorc;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;

final class FolderBrowser {
    enum Mode {
        BOOKS("导入书籍", "选择 TXT 或 EPUB"), COVER("导入封面", "选择本机图片"),
        BACKUP("恢复本地备份", "选择 MookDoctorC 的 ZIP 备份");
        final String title, detail;
        Mode(String title,String detail) { this.title=title; this.detail=detail; }
    }
    record Item(Uri uri, String id, String name, boolean directory, long size) {}
    private final MainActivity activity;
    private final ShelfUi ui;
    private final Mode mode;
    private final ContentResolver resolver;
    private final BiConsumer<Set<Uri>,String> onChoose;
    final LinearLayout view;
    private final TextView location, all, done, status;
    private final ListView list;
    private final Adapter adapter = new Adapter();
    private final ArrayDeque<Item> path = new ArrayDeque<>();
    private final Map<Uri,String> selected = new LinkedHashMap<>();
    private final Set<Uri> brushed = new HashSet<>();
    private Uri tree;
    private List<Item> entries = List.of();
    private boolean active = true, selecting, adding;
    private float downY;
    private int downRow;
    private int generation;

    FolderBrowser(MainActivity activity, ShelfUi ui, Mode mode, BiConsumer<Set<Uri>,String> onChoose) {
        this.activity=activity;
        this.ui=ui;
        this.mode=mode;
        this.onChoose=onChoose;
        resolver=activity.getContentResolver();
        view=ui.column();
        LinearLayout heading=ui.row();
        heading.addView(ui.text(mode.title,24,ui.ink,true),new LinearLayout.LayoutParams(0,ShelfUi.WRAP,1));
        heading.addView(ui.action("授权目录",activity::requestFolder));
        view.addView(heading);
        view.addView(ui.text(mode.detail,13,ui.secondary,false));
        ui.spacer(view,12);
        LinearLayout tools=ui.row();
        location=ui.text("授权位置",14,ui.ink,true);
        location.setSingleLine();
        location.setEllipsize(TextUtils.TruncateAt.START);
        tools.addView(location,new LinearLayout.LayoutParams(0,ShelfUi.WRAP,1));
        all=ui.action("全选本层",this::selectCurrent);
        tools.addView(all);
        view.addView(tools);
        list=new ListView(activity);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setSelector(android.R.color.transparent);
        list.setVerticalScrollBarEnabled(false);
        list.setOnItemClickListener((parent,row,i,id)->pick(entries.get(i)));
        list.setOnItemLongClickListener((parent,row,i,id)->{
            if (mode!=Mode.BOOKS || entries.get(i).directory) return false;
            beginBrush(i);
            return true;
        });
        list.setOnTouchListener(this::brush);
        view.addView(list,new LinearLayout.LayoutParams(ShelfUi.MATCH,0,1));
        status=ui.text("",13,ui.secondary,false);
        view.addView(status,new LinearLayout.LayoutParams(ShelfUi.MATCH,ShelfUi.WRAP));
        done=ui.action("导入",this::finishSelection);
        done.setBackground(ui.rounded(ui.dark ? 0xff514233 : 0xffead4b6,13));
        if (mode==Mode.BOOKS) view.addView(done,new LinearLayout.LayoutParams(ShelfUi.MATCH,ui.dp(52)));
        showRoots();
    }

    void dispose() { active=false; }

    private void showRoots() {
        tree=null;
        path.clear();
        int request=++generation;
        List<Uri> roots=new ArrayList<>();
        for (UriPermission p:resolver.getPersistedUriPermissions()) {
            if (p.isReadPermission() && DocumentsContract.isTreeUri(p.getUri())) roots.add(p.getUri());
        }
        activity.io(()->{
            List<Item> result=new ArrayList<>();
            for (Uri root:roots) {
                try { result.add(rootItem(root)); } catch (IOException | RuntimeException ignored) {}
            }
            return result;
        }, items->{
            if (!active || request!=generation) return;
            setEntries(items, "授权位置");
            status.setText(items.isEmpty() ? "先授权包含书籍或图片的文件夹，之后可直接在这里浏览。" : "");
        });
    }

    void granted(Uri uri) {
        activity.io(()->rootItem(uri),item->{
            if (!active) return;
            tree=uri;
            path.clear();
            path.addLast(item);
            load();
        });
    }

    boolean back() {
        if (path.isEmpty()) return false;
        path.removeLast();
        if (path.isEmpty()) showRoots(); else load();
        return true;
    }

    private Item rootItem(Uri root) throws IOException {
        String id=DocumentsContract.getTreeDocumentId(root);
        Uri document=DocumentsContract.buildDocumentUriUsingTree(root,id);
        return new Item(root,id,activity.sourceFiles().displayName(document),true,0);
    }

    private void load() {
        Item parent=path.peekLast();
        if (parent==null) { showRoots(); return; }
        Uri root=tree;
        int request=++generation;
        String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE};
        activity.io(()->{
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(root,parent.id);
            List<Item> items=new ArrayList<>();
            try (Cursor rows=resolver.query(children,projection,null,null,null)) {
                if (rows==null) throw new IOException("无法访问此目录");
                while (rows.moveToNext()) {
                    String id=rows.getString(0), name=rows.getString(1), mime=rows.getString(2);
                    if (name==null) continue;
                    boolean directory=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    if (!directory && !eligible(name,mime)) continue;
                    items.add(new Item(DocumentsContract.buildDocumentUriUsingTree(root,id),id,name,directory,rows.getLong(3)));
                }
            }
            items.sort(Comparator.comparing((Item a)->!a.directory).thenComparing(Item::name,String.CASE_INSENSITIVE_ORDER));
            return items;
        },items->{
            if (!active || request!=generation) return;
            setEntries(items,parent.name);
            status.setText(items.isEmpty() ? "此目录没有可选文件" : "");
        });
    }

    private boolean eligible(String name,String mime) {
        String lower=name.toLowerCase(Locale.ROOT);
        return switch (mode) {
            case BOOKS -> lower.endsWith(".txt") || lower.endsWith(".epub");
            case COVER -> (mime!=null && mime.startsWith("image/")) || lower.matches(".*\\.(jpe?g|png|webp|gif|bmp|heic)$");
            case BACKUP -> lower.endsWith(".zip");
        };
    }

    private void setEntries(List<Item> items,String title) {
        entries=items;
        location.setText(path.isEmpty()?title:"‹  "+title);
        location.setOnClickListener(v->back());
        all.setVisibility(mode==Mode.BOOKS && tree!=null?View.VISIBLE:View.GONE);
        adapter.notifyDataSetChanged();
        list.setSelection(0);
        refreshControls();
    }

    private void pick(Item item) {
        if (item.directory) {
            if (tree==null) tree=item.uri;
            path.addLast(item);
            load();
        } else if (mode!=Mode.BOOKS) {
            onChoose.accept(Set.of(item.uri),"");
        } else {
            set(item,!selected.containsKey(item.uri));
            adapter.notifyDataSetChanged();
            refreshControls();
        }
    }

    private void set(Item item,boolean check) {
        if (check) selected.put(item.uri,path.isEmpty()?"":path.getLast().name);
        else selected.remove(item.uri);
    }

    private void selectCurrent() {
        boolean allSelected=true;
        for (Item item:entries) if (!item.directory && !selected.containsKey(item.uri)) allSelected=false;
        for (Item item:entries) if (!item.directory) set(item,!allSelected);
        adapter.notifyDataSetChanged();
        refreshControls();
    }

    private void finishSelection() {
        if (selected.isEmpty()) return;
        Set<String> parents=new HashSet<>(selected.values());
        String name=parents.size()==1 ? parents.iterator().next() : "";
        onChoose.accept(new LinkedHashSet<>(selected.keySet()),name);
    }

    private void beginBrush(int index) {
        if (index<0 || index>=entries.size() || entries.get(index).directory) return;
        selecting=true;
        adding=!selected.containsKey(entries.get(index).uri);
        brushed.clear();
        list.getParent().requestDisallowInterceptTouchEvent(true);
        applyBrush(index);
    }

    private void applyBrush(int index) {
        if (index<0 || index>=entries.size()) return;
        Item item=entries.get(index);
        if (item.directory || !brushed.add(item.uri)) return;
        set(item,adding);
        adapter.notifyDataSetChanged();
        refreshControls();
    }

    private boolean brush(View view,MotionEvent event) {
        if (mode!=Mode.BOOKS) return false;
        int row=list.pointToPosition((int)event.getX(),(int)event.getY());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                selecting=false;
                downY=event.getY();
                downRow=event.getX() >= list.getWidth()-ui.dp(56)?row:-1;
            }
            case MotionEvent.ACTION_MOVE -> {
                if (!selecting && downRow>=0 && Math.abs(event.getY()-downY)>ViewConfiguration.get(activity).getScaledTouchSlop()) {
                    beginBrush(downRow);
                    if (selecting) {
                        MotionEvent cancel=MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        list.onTouchEvent(cancel);
                        cancel.recycle();
                    }
                }
                if (selecting) {
                    int prior=list.pointToPosition((int)event.getX(),(int)downY);
                    if (prior>=0 && row>=0) {
                        for (int i=Math.min(prior,row);i<=Math.max(prior,row);i++) applyBrush(i);
                    } else applyBrush(row);
                    downY=event.getY();
                    if (event.getY()<ui.dp(35)) list.smoothScrollBy(-ui.dp(38),90);
                    else if (event.getY()>list.getHeight()-ui.dp(35)) list.smoothScrollBy(ui.dp(38),90);
                    return true;
                }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (selecting) {
                    selecting=false;
                    list.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                }
            }
        }
        return false;
    }

    private void refreshControls() {
        done.setText(selected.isEmpty()?"选择书籍":"导入 "+selected.size()+" 本");
        done.setEnabled(!selected.isEmpty());
        done.setAlpha(selected.isEmpty()?.55f:1);
        boolean eligible=false;
        for (Item item:entries) if (!item.directory) eligible=true;
        all.setEnabled(eligible);
        all.setAlpha(eligible?1:.5f);
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount(){return entries.size();}
        @Override public Item getItem(int index){return entries.get(index);}
        @Override public long getItemId(int index){return index;}
        @Override public View getView(int index,View reused,ViewGroup parent) {
            Item item=getItem(index);
            LinearLayout row=ui.row();
            row.setPadding(ui.dp(12),0,ui.dp(12),0);
            row.setMinimumHeight(ui.dp(54));
            boolean checked=selected.containsKey(item.uri);
            row.setBackground(ui.rounded(checked?(ui.dark?0xff574432:0xffeddcc2):android.graphics.Color.TRANSPARENT,12));
            TextView icon=ui.text(item.directory?"▰":"▤",19,ui.accent,true);
            row.addView(icon,new LinearLayout.LayoutParams(ui.dp(33),ShelfUi.WRAP));
            TextView name=ui.text(item.name,15,ui.ink,false);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name,new LinearLayout.LayoutParams(0,ShelfUi.WRAP,1));
            TextView marker=ui.text(item.directory?"›":mode==Mode.BOOKS?(checked?"✓":"○"):"",19,ui.accent,true);
            marker.setGravity(Gravity.CENTER);
            row.addView(marker,new LinearLayout.LayoutParams(ui.dp(39),ShelfUi.WRAP));
            row.setContentDescription(item.name+(item.directory?"，文件夹":checked?"，已选中":""));
            return row;
        }
    }
}
