package local.bookdoctorc;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

final class ShelfUi {
    static final float COVER_RATIO = (float) (1 / Math.sqrt(2));
    static final String BOOK_FACE = "book-cover";
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int[] SPINES = {0xffb87558, 0xff798362, 0xff7193a6, 0xffc48b66, 0xff567a8f, 0xff927665};

    final Context context;
    final boolean dark;
    final int paper, ink, secondary, accent, rule;
    final Bitmap illustrations;
    private static final java.util.concurrent.Executor ARTWORK = Executors.newSingleThreadExecutor(task ->
            new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "MookDoctorC artwork"));
    private final RoomPhoto[] roomPhotos = new RoomPhoto[Room.values().length];

    ShelfUi(Context context, boolean dark, Bitmap illustrations) {
        this.context = context;
        this.dark = dark;
        DesignHome.Palette palette = DesignHome.books(dark);
        paper = palette.paper();
        ink = palette.ink();
        secondary = palette.muted();
        accent = palette.accent();
        rule = palette.rule();
        this.illustrations = illustrations;
    }

    int dp(float n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }

    TextView text(String value, int size, int color, boolean strong) {
        TextView label = new TextView(context);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setFontFeatureSettings("kern");
        if (strong) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    TextView action(String value, Runnable onClick) {
        TextView label = text(value, 15, accent, true);
        label.setGravity(Gravity.CENTER);
        label.setMinHeight(dp(48));
        label.setMinWidth(dp(44));
        label.setPadding(dp(10), 0, dp(10), 0);
        label.setOnClickListener(v -> onClick.run());
        return label;
    }

    TextView back(Runnable onClick) {
        TextView label = action("‹ 返回", onClick);
        label.setSingleLine(true);
        label.setPadding(dp(6), 0, dp(6), 0);
        label.setContentDescription("返回");
        return label;
    }

    int coverHeight(int width) { return Math.round(width / COVER_RATIO); }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    LinearLayout row() {
        LinearLayout layout = new LinearLayout(context);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    void spacer(LinearLayout list, int height) {
        list.addView(new View(context), new LinearLayout.LayoutParams(1, dp(height)));
    }

    GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    Drawable wall() { return DesignHome.books(dark).wall(); }

    enum Room {
        PLAYER(R.drawable.art_player_room_day, R.drawable.art_player_room_night);

        private final int day, night;

        Room(int day, int night) {
            this.day = day;
            this.night = night;
        }
    }

    static void prepareArtwork(Runnable task) { ARTWORK.execute(task); }

    private RoomPhoto photoFor(Room scene) {
        int slot = scene.ordinal();
        if (roomPhotos[slot] == null) roomPhotos[slot] = new RoomPhoto();
        return roomPhotos[slot];
    }

    void warmRoom(Room scene) {
        RoomPhoto photo = photoFor(scene);
        if (photo.requested) return;
        photo.requested = true;
        var resources = context.getResources();
        int asset = dark ? scene.night : scene.day;
        prepareArtwork(() -> {
            Bitmap bitmap = BitmapFactory.decodeResource(resources, asset);
            if (bitmap != null) bitmap.prepareToDraw();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> photo.accept(bitmap));
        });
    }

    boolean roomPrepared(Room scene) { return photoFor(scene).prepared; }

    Drawable room(Room scene) {
        RoomPhoto photo = photoFor(scene);
        int fallback = dark ? 0xff152232 : 0xff415465;
        RoomBackground drawable = new RoomBackground(photo.bitmap, true, fallback);
        if (photo.bitmap == null) {
            photo.waiting.add(new WeakReference<>(drawable));
            warmRoom(scene);
        }
        return drawable;
    }

    private static final class RoomPhoto {
        private final List<WeakReference<RoomBackground>> waiting = new ArrayList<>();
        private boolean requested;
        private boolean prepared;
        private Bitmap bitmap;

        private void accept(Bitmap ready) {
            bitmap = ready;
            prepared = true;
            requested = ready != null;
            for (WeakReference<RoomBackground> reference : waiting) {
                RoomBackground drawable = reference.get();
                if (drawable != null) drawable.setPhoto(ready);
            }
            waiting.clear();
        }
    }

    private static final class RoomBackground extends Drawable {
        private Bitmap photo;
        private final boolean audio;
        private final int fallback;
        private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint shade = new Paint();
        private final RectF frame = new RectF();
        private int opacity = 255;

        RoomBackground(Bitmap photo, boolean audio, int fallback) {
            this.photo = photo;
            this.audio = audio;
            this.fallback = fallback;
        }

        private void setPhoto(Bitmap photo) {
            this.photo = photo;
            onBoundsChange(getBounds());
            invalidateSelf();
        }

        @Override protected void onBoundsChange(Rect bounds) {
            if (bounds.isEmpty() || photo == null) return;
            float scale = bounds.width() / (float) photo.getWidth();
            // Keep both edge landmarks in view on phones without bending the sunlight at a band seam.
            float height = Math.max(bounds.height(), photo.getHeight() * scale);
            float top = bounds.exactCenterY() - height / 2;
            frame.set(bounds.left, top, bounds.right, top + height);
            if (audio) {
                shade.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                        new int[]{0x38101a2c, 0x00101a2c, 0x00101a2c, 0x27101a2c},
                        new float[]{0, .23f, .84f, 1}, Shader.TileMode.CLAMP));
            }
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            int saved = canvas.save();
            canvas.clipRect(bounds);
            if (opacity < 255) canvas.saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom, opacity);
            if (photo == null) canvas.drawColor(fallback);
            else {
                canvas.drawBitmap(photo, null, frame, image);
                if (audio) canvas.drawRect(bounds, shade);
            }
            canvas.restoreToCount(saved);
        }

        @Override public void setAlpha(int alpha) { opacity = alpha; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { image.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    OakFrame oakFrame(int radius) {
        return new OakFrame(dp(radius), paper, rule);
    }

    FrameLayout woodFrame(int radius) {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackground(oakFrame(radius));
        frame.setClipToOutline(true);
        frame.setElevation(dp(3));
        return frame;
    }

    View bookCover(Library.Book book, File cover, java.util.function.Consumer<View> onClick, java.util.function.Consumer<View> onLongClick) {
        FrameLayout board = coverBoard(book.title(), book.author(), art(book), cover);
        board.setOnClickListener(onClick::accept);
        board.setOnLongClickListener(v -> { onLongClick.accept(v); return true; });
        return board;
    }

    private FrameLayout coverBoard(String title, String author, int art, File cover) {
        FrameLayout board = new FrameLayout(context);
        board.setTag(BOOK_FACE);
        int tone = SPINES[art];
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{tone, mix(tone, Color.BLACK, .26f)});
        base.setCornerRadius(dp(3));
        board.setBackground(base);
        board.setClipToOutline(true);
        board.setContentDescription(title);
        board.addView(coverContent(art, cover),new FrameLayout.LayoutParams(MATCH,MATCH));
        View seam = new View(context);
        seam.setBackgroundColor(0x38000000);
        board.addView(seam, new FrameLayout.LayoutParams(dp(2), MATCH, Gravity.START));
        return board;
    }

    View bookThumbnail(Library.Reading book, File cover) {
        FrameLayout icon = new FrameLayout(context);
        View pages = new View(context) {
            private final Paint lines = new Paint();

            @Override protected void onDraw(Canvas canvas) {
                lines.setColor(dark ? 0x45805639 : 0x34805639);
                int first = dp(5), last = getHeight() - dp(4);
                for (int y = first; y < last; y += Math.max(2, dp(1))) {
                    canvas.drawLine(getWidth() - dp(5), y, getWidth() - dp(1), y, lines);
                }
            }
        };
        pages.setBackground(rounded(dark ? 0xffbba185 : 0xffe3c9a9, 4));
        pages.setElevation(dp(2));
        FrameLayout.LayoutParams stack = new FrameLayout.LayoutParams(dp(43), coverHeight(dp(42)) - dp(2));
        stack.setMargins(dp(3), dp(2), 0, 0);
        icon.addView(pages, stack);
        long seed = book.bookId() == 0 ? book.title().hashCode() : book.bookId() - 1;
        FrameLayout face = coverBoard(book.title(), book.author(), (int)Math.floorMod(seed, 6), cover);
        face.setElevation(dp(3));
        icon.addView(face, new FrameLayout.LayoutParams(dp(42), coverHeight(dp(42))));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return icon;
    }

    View coverPreview(Library.Book book, File cover) {
        return coverBoard(book.title(), book.author(), art(book), cover);
    }

    private View coverContent(int art, File cover){
        if (cover == null || !cover.isFile()) return new IllustratedCover(context, illustrations, art);
        FrameLayout frame = new FrameLayout(context);
        frame.addView(new IllustratedCover(context, illustrations, art), new FrameLayout.LayoutParams(MATCH,MATCH));
        // Do not retain an abandoned page while the single decoder is busy.
        java.lang.ref.WeakReference<FrameLayout> reference = new java.lang.ref.WeakReference<>(frame);
        BookCoverCache.load(cover, bitmap -> {
            FrameLayout target = reference.get();
            if (target == null) return;
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImageBitmap(bitmap);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            target.removeAllViews();
            target.addView(image, new FrameLayout.LayoutParams(MATCH,MATCH));
        });
        return frame;
    }


    View recent(List<Library.Book> books, Library library,
                java.util.function.BiConsumer<Library.Book, View> open, java.util.function.BiConsumer<Library.Book, View> menu) {
        FrameLayout shelf = new FrameLayout(context);
        shelf.setClipChildren(false);
        View plinth = new StandPiece(false);
        shelf.addView(plinth, new FrameLayout.LayoutParams(MATCH, dp(18), Gravity.BOTTOM));
        if (books.isEmpty()) {
            TextView empty = text("读过的书会放在这里", 14, dark ? 0xfffcf2db : 0xff51351e, false);
            empty.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams space = new FrameLayout.LayoutParams(MATCH, MATCH);
            space.bottomMargin = dp(18);
            shelf.addView(empty, space);
        } else {
            RecentRow booksRow = new RecentRow(context, books.size());
            for (Library.Book book : books) {
                booksRow.addView(bookCover(book, library.cover(book), anchor -> open.accept(book, anchor), anchor -> menu.accept(book, anchor)));
            }
            FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(MATCH, MATCH);
            rowParams.setMargins(dp(13), dp(5), dp(13), dp(14));
            shelf.addView(booksRow, rowParams);
        }
        View rail = new StandPiece(true);
        FrameLayout.LayoutParams railParams = new FrameLayout.LayoutParams(MATCH, dp(18), Gravity.BOTTOM);
        railParams.setMargins(dp(6), 0, dp(6), dp(12));
        rail.setElevation(dp(8));
        shelf.addView(rail, railParams);
        return shelf;
    }

    View recentCds(List<? extends View> cases, int emptyInk) {
        FrameLayout shelf = new FrameLayout(context);
        shelf.setClipChildren(false);
        shelf.addView(new MetalStand(false), new FrameLayout.LayoutParams(MATCH, dp(20), Gravity.BOTTOM));
        if (cases.isEmpty()) {
            TextView empty = text("播放过的节目会放在这里", 14, emptyInk, false);
            empty.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams space = new FrameLayout.LayoutParams(MATCH, MATCH);
            space.bottomMargin = dp(20);
            shelf.addView(empty, space);
        } else {
            RecentRow row = new RecentRow(context, cases.size(), true);
            for (View cover : cases) row.addView(cover);
            FrameLayout.LayoutParams rowSpace = new FrameLayout.LayoutParams(MATCH, MATCH);
            rowSpace.setMargins(dp(12), dp(6), dp(12), dp(15));
            shelf.addView(row, rowSpace);
        }
        View rail = new MetalStand(true);
        FrameLayout.LayoutParams railSpace = new FrameLayout.LayoutParams(MATCH, dp(15), Gravity.BOTTOM);
        railSpace.setMargins(dp(6), 0, dp(6), dp(13));
        rail.setElevation(dp(8));
        shelf.addView(rail, railSpace);
        return shelf;
    }

    private final class MetalStand extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean rail;
        private Shader reflection;

        MetalStand(boolean rail) {
            super(context);
            this.rail = rail;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            reflection = new LinearGradient(0, 0, 0, Math.max(1, rail ? dp(5) : h - dp(4)),
                    dark ? new int[]{0xffa1b4d2, 0xff586e91, 0xff2b3a54} :
                            new int[]{0xfffcfdff, 0xffc6d4e9, 0xff8198bc},
                    new float[]{0, .42f, 1}, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (rail) {
                paint.setColor(dark ? 0xff4e6385 : 0xffb6c8e1);
                canvas.drawRoundRect(dp(8), dp(3), dp(12), h, dp(2), dp(2), paint);
                canvas.drawRoundRect(w - dp(12), dp(3), w - dp(8), h, dp(2), dp(2), paint);
            } else {
                paint.setColor(dark ? 0x74101b2d : 0x49537098);
                canvas.drawRoundRect(dp(3), dp(4), w - dp(3), h, dp(7), dp(7), paint);
            }
            float face = rail ? dp(5) : h - dp(4);
            float radius = rail ? dp(3) : dp(6);
            paint.setColor(Color.WHITE);
            paint.setShader(reflection);
            canvas.drawRoundRect(0, 0, w, face, radius, radius, paint);
            paint.setShader(null);
            paint.setColor(dark ? 0x8ad5e4fb : 0xafffffff);
            canvas.drawRect(dp(5), dp(1), w - dp(5), dp(2), paint);
            paint.setColor(dark ? 0x79304262 : 0x586a86ab);
            canvas.drawRect(dp(4), face - dp(1), w - dp(4), face, paint);
        }
    }

    private final class StandPiece extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF surface = new RectF();
        private final Path edge = new Path();
        private final boolean rail;
        private final LightingColorFilter night = new LightingColorFilter(0xffbda486, 0xff0c0703);

        StandPiece(boolean rail) {
            super(context);
            this.rail = rail;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (rail) {
                float footing = getHeight();
                paint.setColor(dark ? 0xff957650 : 0xffca9d69);
                canvas.drawRoundRect(dp(6), dp(3), dp(11), footing, dp(2), dp(2), paint);
                canvas.drawRoundRect(getWidth() - dp(11), dp(3), getWidth() - dp(6), footing, dp(2), dp(2), paint);
            }
            surface.set(0, 0, getWidth(), rail ? dp(6) : getHeight());
            float radius = dp(rail ? 3 : 7);
            edge.rewind();
            edge.addRoundRect(surface, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(edge);
            if (dark) paint.setColorFilter(night);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(surface, dp(4), dp(4), paint);
            paint.setColorFilter(null);
            paint.setColor(rail ? 0x5bffefd5 : 0x64ffefd5);
            canvas.drawRect(0, 0, getWidth(), dp(1), paint);
            paint.setColor(rail ? 0x56422312 : 0x45533116);
            canvas.drawRect(0, surface.bottom - dp(2), getWidth(), surface.bottom, paint);
            canvas.restore();
        }
    }

    static final class BoxTile extends LinearLayout {
        final java.util.Map<Long,View> spines=new java.util.LinkedHashMap<>();
        View lettering;
        LinearLayout shelf;

        BoxTile(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setClipChildren(false);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int left = MeasureSpec.getSize(widthSpec) - shelf.getPaddingLeft() - shelf.getPaddingRight();
            boolean full = false;
            for (int i = 0; i < shelf.getChildCount(); i++) {
                View spine = shelf.getChildAt(i);
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) spine.getLayoutParams();
                left -= p.width + p.leftMargin + p.rightMargin;
                full |= left < 0;
                spine.setVisibility(full ? GONE : VISIBLE);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    TextView gildedTitle(String name, int size) {
        TextView label = new GoldLettering(context);
        label.setText(name);
        label.setTextSize(size);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(10), dp(1), dp(10), dp(1));
        return label;
    }

    View box(Library.Box box,List<Library.Book> books,java.util.function.Consumer<View> open,java.util.function.Consumer<View> options){
        BoxTile tile=new BoxTile(context);
        TextView label = gildedTitle(box.name(), 16);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        label.setMinWidth(dp(42));
        label.setMinHeight(dp(23));
        tile.lettering = label;
        FrameLayout header = new FrameLayout(context);
        FrameLayout.LayoutParams heading = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER);
        heading.setMargins(dp(4), dp(1), dp(4), dp(1));
        header.addView(label, heading);
        tile.addView(header, new LinearLayout.LayoutParams(MATCH, dp(CabinetLevels.HEADER_HEIGHT)));
        LinearLayout shelf = row();
        tile.shelf = shelf;
        shelf.setClipChildren(false);
        shelf.setGravity(Gravity.LEFT | Gravity.BOTTOM);
        shelf.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shelf.setPadding(dp(4), 0, dp(4), 0);
        tile.addView(shelf, new LinearLayout.LayoutParams(MATCH, dp(CabinetLevels.SPINE_HEIGHT)));
        int windowWidth = Math.min(TabletLayout.wide(this) ? 1120 : 720,
                context.getResources().getConfiguration().screenWidthDp);
        int shown = Math.min(Math.max(1, windowWidth / 16), books.size());
        long arrangement = box.id() * 0x9e3779b97f4a7c15L;
        int leaning = shown >= 4 ? 1 + (int) Math.floorMod(arrangement, Math.min(5, shown - 2)) : -1;
        for(int i=0;i<shown;i++){
            Library.Book book=books.get(i);
            int tone=SPINES[(int)Math.floorMod(art(book)+box.id(),SPINES.length)];
            View spine=new Spine(context,book.title(),tone);
            int thickness = 15 + (int) Math.floorMod(book.id(), 6);
            int height = dp(65 + Math.floorMod(book.title().hashCode(), 13));
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(dp(thickness), height);
            item.rightMargin = dp(1.5f);
            if (leaning >= 0 && i > 0 && i < shown - 1 && Math.floorMod(i - leaning, 8) == 0) {
                boolean leanRight = (Long.rotateLeft(arrangement, 25 + i / 8) & 1) == 0;
                int leanRoom = dp(8);
                if (leanRight) item.rightMargin += leanRoom;
                else item.leftMargin += leanRoom;
                spine.setRotation(leanRight ? 11f : -11f);
                spine.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
                    view.setPivotX(view.getWidth() * (leanRight ? 1f : 0f));
                    view.setPivotY(view.getHeight());
                });
                spine.setElevation(dp(1));
            }
            shelf.addView(spine, item);
            tile.spines.put(book.id(),spine);
        }
        spacer(tile, CabinetLevels.FLOOR_HEIGHT);
        tile.setContentDescription(box.name()+"，"+books.size()+" 本书");
        tile.setOnClickListener(open::accept);
        tile.setOnLongClickListener(v->{options.accept(v);return true;});
        return tile;
    }

    View divider() {
        View line = new View(context);
        line.setBackgroundColor(rule);
        line.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(1)));
        return line;
    }

    private int art(Library.Book book) { return (int) Math.floorMod(book.id() - 1, 6); }

    static int mix(int a, int b, float v) {
        return Color.rgb(Math.round(Color.red(a) * (1 - v) + Color.red(b) * v),
                Math.round(Color.green(a) * (1 - v) + Color.green(b) * v),
                Math.round(Color.blue(a) * (1 - v) + Color.blue(b) * v));
    }

    private final class RecentRow extends ViewGroup {
        private final int count;
        private final boolean cd;
        private final float[] scales = {1f, .79f, .68f, .57f, .47f};

        RecentRow(Context context, int count) { this(context, count, false); }

        RecentRow(Context context, int count, boolean cd) {
            super(context);
            this.count = count;
            this.cd = cd;
            setClipChildren(false);
            setClipToPadding(false);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, height);
            float sum = 0;
            for (int rank = 0; rank < count; rank++) sum += scales[rank];
            float ratio = cd ? 1f : COVER_RATIO;
            int largest = Math.min(height - dp(7), Math.round((width + overlap() * (count - 1)) / (sum * ratio)));
            for (int rank = 0; rank < count; rank++) {
                View cover = getChildAt(rank);
                int h = Math.max(1, Math.round(largest * scales[rank]));
                cover.measure(MeasureSpec.makeMeasureSpec(Math.round(h * ratio), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
                cover.setElevation(dp((count - rank) * 1.5f));
            }
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = 0;
            for (int rank = 0; rank < count; rank++) width += getChildAt(rank).getMeasuredWidth();
            width -= overlap() * (count - 1);
            int x = (getWidth() - width) / 2;
            for (int slot = 0; slot < count; slot++) {
                int rank = count < 2 ? 0 : slot == 0 ? 1 : slot == 1 ? 0 : slot;
                View cover = getChildAt(rank);
                int w = cover.getMeasuredWidth(), h = cover.getMeasuredHeight();
                int foot = getHeight() - (cd ? dp(Math.min(6, rank * 1.5f)) : 0);
                cover.layout(x, foot - h, x + w, foot);
                x += w - overlap();
            }
        }

        private int overlap() { return dp(cd ? 13 : 6); }
    }

    private static final class Spine extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final int color;
        final int[] glyphs;
        Shader sheen;

        Spine(Context context, String title, int color) {
            super(context);
            this.color = color;
            glyphs = title.codePoints().limit(5).toArray();
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            sheen = new LinearGradient(0, 0, Math.max(1, w), 0,
                    new int[]{mix(color, 0xff211b18, .29f), color, mix(color, 0xfff9e7c2, .15f),
                            mix(color, 0xff342b20, .23f)}, new float[]{0, .19f, .82f, 1}, Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            paint.setShader(sheen);
            c.drawRoundRect(0, 0, w, h, 2.5f, 2.5f, paint);
            paint.setShader(null);
            paint.setColor(0xfffff4de);
            paint.setTextAlign(Paint.Align.CENTER);
            float size = Math.min(w * .67f, Math.min(h / 6.4f, 34));
            paint.setTextSize(size);
            for (int i = 0; i < glyphs.length; i++) {
                String glyph = new String(Character.toChars(glyphs[i]));
                c.drawText(glyph, w / 2f, Math.min(h * .20f, size * 1.5f) + i * size * 1.15f, paint);
            }
        }
    }

    static int nightMode(Context c, String setting) {
        if ("dark".equals(setting)) return 1;
        if ("light".equals(setting)) return 0;
        int mode = c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES ? 1 : 0;
    }
}
