package local.bookdoctorc;

import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import com.example.clmplayer.LibraryItem;
import java.util.List;
import java.util.Locale;

final class AudiobookPage {
    static final int PHOTO_INK = 0xfff7f8ff, PHOTO_MUTED = 0xffe1e8fb, PHOTO_ACCENT = 0xffd2dfff;

    record Colors(int paper, int card, int ink, int muted, int accent, int border, int control) {
        static Colors forMode(boolean dark) {
            return dark
                    ? new Colors(0xff171b2d, 0xff20263c, 0xffedf0ff, 0xffb1badb, 0xffb9c4ff, 0xff3c466c, 0xff303b65)
                    : new Colors(0xffedf0fa, 0xfff8f9ff, 0xff242d50, 0xff576289, 0xff4658a4, 0xffccd3ed, 0xffdfe5ff);
        }

    }

    private final ShelfUi ui;
    private final ClmPlayback playback;
    private final Colors colors;
    final LinearLayout view;
    final View background;
    private final LinearLayout transport, tools;
    private boolean settled;
    private final TextView album, track, elapsed, length, chapters;
    private final ImageButton play, previous, next;
    private final SeekBar seek;
    final TurntableDeck deck;
    private final Runnable observer = this::refresh;
    private AudiobookCatalog.Preview selectionPreview;
    private Bitmap entranceCover;
    private boolean revealingSelection, dragging, showingPause;
    private long displayedElapsed = -1, displayedLength = Long.MIN_VALUE;

    AudiobookPage(MainActivity activity, ShelfUi ui, ClmPlayback playback) {
        this.ui = ui;
        this.playback = playback;
        view = ui.column();
        background = new View(activity);
        colors = Colors.forMode(ui.dark);
        background.setBackground(ui.room(ShelfUi.Room.PLAYER));
        var screen = activity.getResources().getConfiguration();
        boolean landscape = screen.screenWidthDp >= 600 && screen.screenWidthDp > screen.screenHeightDp;
        LinearLayout console = landscape ? ui.row() : ui.column();
        console.setGravity(landscape ? Gravity.TOP : Gravity.CENTER_HORIZONTAL);
        console.setPadding(0, ui.dp(5), 0, ui.dp(14));
        view.addView(console);

        int deckHeight = ui.dp(Math.max(170, Math.min(TabletLayout.wide(ui) ? 420 : 340, screen.screenHeightDp * (TabletLayout.wide(ui) ? .55f : .405f))));
        deck = new TurntableDeck(ui);
        console.addView(deck, new LinearLayout.LayoutParams(landscape ? 0 : ShelfUi.MATCH,
                deckHeight, landscape ? 1 : 0));

        LinearLayout controls = ui.column();
        controls.setPadding(ui.dp(5), ui.dp(4), ui.dp(5), 0);
        LinearLayout.LayoutParams controlsSize = new LinearLayout.LayoutParams(landscape ? 0 : ShelfUi.MATCH,
                ShelfUi.WRAP, landscape ? 1 : 0);
        if (landscape) controlsSize.setMarginStart(ui.dp(12));
        console.addView(controls, controlsSize);
        LinearLayout metadata = ui.column();
        metadata.setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(4));
        metadata.setBackground(ui.rounded(0x9e18253a, 15));
        controls.addView(metadata);
        album = metadataText(29, PHOTO_INK, true);
        album.setTypeface(Typeface.create("serif", Typeface.BOLD));
        album.setLetterSpacing(-.018f);
        if (Build.VERSION.SDK_INT >= 28) album.setAccessibilityHeading(true);
        metadata.addView(album);
        track = metadataText(15, PHOTO_MUTED, false);
        track.setPadding(0, ui.dp(6), 0, 0);
        metadata.addView(track);
        ui.spacer(metadata, 4);

        seek = new SeekBar(activity);
        seek.setContentDescription("播放进度");
        seek.setMax(1000);
        seek.setPaddingRelative(ui.dp(10), 0, ui.dp(10), 0);
        seek.setThumbTintList(ColorStateList.valueOf(PHOTO_ACCENT));
        seek.setProgressTintList(ColorStateList.valueOf(PHOTO_ACCENT));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x86abb8d8));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar control, int value, boolean fromUser) {
                if (!fromUser) return;
                long position = playback.getDuration() * value / 1000;
                if (dragging) showTime(position, playback.getDuration());
                else playback.seekTo(position);
            }
            @Override public void onStartTrackingTouch(SeekBar control) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar control) {
                dragging = false;
                playback.seekTo(playback.getDuration() * control.getProgress() / 1000);
            }
        });
        metadata.addView(seek, new LinearLayout.LayoutParams(ShelfUi.MATCH, ui.dp(38)));
        LinearLayout times = ui.row();
        times.setPadding(ui.dp(10), 0, ui.dp(10), ui.dp(2));
        elapsed = timeLabel();
        length = timeLabel();
        length.setGravity(Gravity.END);
        times.addView(elapsed, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        times.addView(length, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        metadata.addView(times);
        ui.spacer(controls, 11);

        transport = ui.row();
        transport.setGravity(Gravity.CENTER);
        transport.setClipChildren(false);
        transport.setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5));
        transport.setBackground(LiquidGlass.group(transport, background, ui.dark, () -> settled));
        previous = transportButton("上一集", Symbol.PREVIOUS, false, playback::previous);
        play = transportButton("播放", Symbol.PLAY, true, playback::toggle);
        next = transportButton("下一集", Symbol.NEXT, false, playback::next);
        transport.addView(previous, new LinearLayout.LayoutParams(ui.dp(49), ui.dp(49)));
        LinearLayout.LayoutParams primary = new LinearLayout.LayoutParams(ui.dp(60), ui.dp(60));
        primary.setMargins(ui.dp(23), 0, ui.dp(23), 0);
        transport.addView(play, primary);
        transport.addView(next, new LinearLayout.LayoutParams(ui.dp(49), ui.dp(49)));
        controls.addView(transport);

        ui.spacer(controls, 12);
        tools = ui.row();
        tools.setPadding(ui.dp(5), ui.dp(3), ui.dp(5), ui.dp(3));
        tools.setBackground(LiquidGlass.group(tools, background, ui.dark, () -> settled));
        chapters = utility("章节目录", Symbol.LIST, activity::showAudioChapters);
        tools.addView(chapters, new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        View split = new View(activity);
        split.setBackgroundColor(colors.border());
        tools.addView(split, new LinearLayout.LayoutParams(ui.dp(1), ui.dp(19)));
        tools.addView(utility("播放设置", Symbol.SETTINGS, activity::showAudioSettings),
                new LinearLayout.LayoutParams(0, ShelfUi.WRAP, 1));
        controls.addView(tools);

        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { attach(); }
            @Override public void onViewDetachedFromWindow(View view) {
                playback.removeObserver(observer);
            }
        });
        refresh();
    }

    private void attach() {
        playback.addObserver(observer);
        refresh();
    }

    void prepare(LibraryItem selection, Bitmap entranceCover) {
        revealingSelection = selection != null;
        selectionPreview = selection == null ? null : playback.getCatalog().preview(selection);
        this.entranceCover = entranceCover;
        if (selection != null) deck.prepareSelection();
        refresh();
    }

    void settled(boolean value) {
        if (settled == value) return;
        settled = value;
        deck.settled(value);
        transport.invalidate();
        tools.invalidate();
    }

    void playSelection(LibraryItem selection) {
        if (selection == null || !revealingSelection) return;
        revealingSelection = false;
        playback.open(selection);
        refresh();
    }

    private void refresh() {
        String openingTitle = revealingSelection ? selectionPreview.getTitle() : playback.getOpeningTitle();
        if (openingTitle == null) {
            selectionPreview = null;
            entranceCover = null;
        }
        String title = playback.getAlbumTitle();
        boolean ready = openingTitle == null && title != null;
        List<String> names = playback.getTrackNames();
        int index = playback.getSelected();
        updateText(album, openingTitle != null ? openingTitle : ready ? title : "暂无音轨");
        String message = openingTitle == null ? playback.getError() : "正在读取音轨…";
        if (message == null && playback.getBusy()) message = "正在读取音轨…";
        if (message == null && ready && index < names.size()) message = names.get(index);
        updateText(track, message == null ? "" : message);
        track.setVisibility(message == null ? View.GONE : View.VISIBLE);
        Bitmap preview = entranceCover != null ? entranceCover
                : selectionPreview == null ? null : selectionPreview.getCover();
        deck.update(ready ? playback.getCover() : preview, ready && playback.getPlaying());
        enabled(previous, ready && index > 0 && !playback.getBusy());
        enabled(play, ready && (!playback.getBusy() || playback.getWantsPlayback()));
        enabled(next, ready && !playback.getBusy());
        enabled(chapters, ready && !playback.getBusy());
        boolean pause = ready && playback.getWantsPlayback();
        if (showingPause != pause) {
            showingPause = pause;
            play.setContentDescription(pause ? "暂停" : "播放");
            play.setImageDrawable(glyph(pause ? Symbol.PAUSE : Symbol.PLAY, 24, primaryInk()));
        }
        seek.setEnabled(ready && playback.getCanSeek());
        if (!dragging) {
            long duration = ready ? playback.getDuration() : 0;
            seek.setProgress(duration <= 0 ? 0 : (int) (1000 * playback.getPosition() / duration));
            showTime(ready ? playback.getPosition() : 0, duration);
        }
    }

    private static void updateText(TextView view, String text) {
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private static void enabled(View action, boolean active) {
        action.setEnabled(active);
        action.setAlpha(active ? 1 : .44f);
    }

    private void showTime(long position, long lengthMs) {
        long seconds = position / 1000;
        if (displayedElapsed != seconds) {
            displayedElapsed = seconds;
            elapsed.setText(timestamp(seconds));
        }
        long duration = lengthMs > 0 ? lengthMs / 1000 : -1;
        if (displayedLength != duration) {
            displayedLength = duration;
            length.setText(duration >= 0 ? timestamp(duration) : "—");
        }
    }

    private static String timestamp(long seconds) {
        if (seconds >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private TextView timeLabel() {
        TextView label = ui.text("", 12, PHOTO_MUTED, false);
        label.setTypeface(Typeface.MONOSPACE);
        label.setSingleLine(true);
        return label;
    }

    private TextView metadataText(int size, int color, boolean strong) {
        TextView label = ui.text("", size, color, strong);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        return label;
    }

    private GradientDrawable gradient(GradientDrawable.Orientation direction, int top, int bottom, int radius) {
        GradientDrawable background = new GradientDrawable(direction, new int[]{top, bottom});
        background.setCornerRadius(ui.dp(radius));
        return background;
    }

    private int primaryInk() { return ui.dark ? 0xff19264d : 0xfff2f5ff; }

    private ImageButton transportButton(String label, Symbol symbol, boolean primary, Runnable click) {
        ImageButton action = new ImageButton(ui.context);
        action.setContentDescription(label);
        action.setImageDrawable(glyph(symbol, primary ? 24 : 18, primary ? primaryInk() : colors.ink()));
        action.setScaleType(ImageView.ScaleType.CENTER);
        action.setPadding(0, 0, 0, 0);
        action.setOnClickListener(v -> click.run());
        action.setElevation(ui.dp(primary ? 3 : 1));
        if (primary) {
            GradientDrawable ring = gradient(GradientDrawable.Orientation.TL_BR,
                    ui.dark ? 0xffeef3ff : 0xffbdcafa, ui.dark ? 0xff6375a7 : 0xff324580, 40);
            GradientDrawable cap = gradient(GradientDrawable.Orientation.TOP_BOTTOM,
                    ui.dark ? 0xffd2dcff : 0xff6479bc, ui.dark ? 0xff90a5e2 : 0xff344783, 40);
            android.graphics.drawable.LayerDrawable dial = new android.graphics.drawable.LayerDrawable(
                    new Drawable[]{ring, cap});
            int inset = ui.dp(2);
            dial.setLayerInset(1, inset, inset, inset, inset);
            action.setBackground(press(ui, dial, 40));
        } else {
            action.setBackground(press(ui, ui.rounded(Color.TRANSPARENT, 32), 32));
        }
        return action;
    }

    private TextView utility(String label, Symbol symbol, View.OnClickListener click) {
        TextView action = button(ui, colors, label, () -> {});
        action.setTextSize(13);
        action.setSingleLine(false);
        action.setMaxLines(2);
        action.setGravity(Gravity.CENTER);
        action.setMinHeight(ui.dp(44));
        action.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
        action.setCompoundDrawablePadding(ui.dp(8));
        action.setCompoundDrawablesRelativeWithIntrinsicBounds(glyph(symbol, 15, colors.accent()), null, null, null);
        action.setBackground(press(ui, ui.rounded(Color.TRANSPARENT, 24), 24));
        action.setOnClickListener(click);
        return action;
    }

    static TextView button(ShelfUi ui, Colors colors, String label, Runnable click) {
        TextView action = ui.action(label, click);
        action.setTextColor(colors.accent());
        action.setSingleLine(true);
        action.setEllipsize(TextUtils.TruncateAt.END);
        action.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        action.setBackground(press(ui, ui.rounded(Color.TRANSPARENT, 11), 11));
        return action;
    }

    static GradientDrawable surface(ShelfUi ui, Colors colors) {
        var background = ui.rounded(colors.card(), 17);
        background.setStroke(ui.dp(1), colors.border());
        return background;
    }

    static void onPhoto(TextView label, ShelfUi ui, int color) {
        label.setTextColor(color);
        label.setShadowLayer(ui.dp(3), 0, ui.dp(1), 0xf009121f);
    }

    private static RippleDrawable press(ShelfUi ui, Drawable background, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(ui.dark ? 0x40cad4fa : 0x2d415697),
                background, ui.rounded(Color.WHITE, radius));
    }

    private Drawable glyph(Symbol symbol, int size, int ink) {
        return new PlayerGlyph(symbol, ui.dp(size), ink);
    }

    private enum Symbol { PLAY, PAUSE, PREVIOUS, NEXT, SETTINGS, LIST }

    private static final class PlayerGlyph extends Drawable {
        private final Symbol symbol;
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();

        PlayerGlyph(Symbol symbol, int size, int ink) {
            this.symbol = symbol;
            this.size = size;
            paint.setColor(ink);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(1.7f);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            canvas.scale(bounds.width() / 24f, bounds.height() / 24f);
            paint.setStyle(Paint.Style.STROKE);
            switch (symbol) {
                case PLAY -> triangle(canvas, 8, 20, 5, 19);
                case PAUSE -> {
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(6, 5, 10, 19, 1, 1, paint);
                    canvas.drawRoundRect(14, 5, 18, 19, 1, 1, paint);
                }
                case PREVIOUS -> {
                    canvas.drawLine(5, 5, 5, 19, paint);
                    triangle(canvas, 19, 7, 5, 19);
                }
                case NEXT -> {
                    canvas.drawLine(19, 5, 19, 19, paint);
                    triangle(canvas, 5, 17, 5, 19);
                }
                case SETTINGS -> {
                    canvas.drawLine(4, 8, 20, 8, paint);
                    canvas.drawLine(4, 16, 20, 16, paint);
                    canvas.drawRoundRect(8, 5, 11, 11, 1, 1, paint);
                    canvas.drawRoundRect(14, 13, 17, 19, 1, 1, paint);
                }
                case LIST -> {
                    for (int y = 6; y <= 18; y += 6) {
                        canvas.drawPoint(4, y, paint);
                        canvas.drawLine(8, y, 20, y, paint);
                    }
                }
            }
            canvas.restore();
        }

        private void triangle(Canvas canvas, float back, float tip, float top, float bottom) {
            paint.setStyle(Paint.Style.FILL);
            shape.rewind();
            shape.moveTo(back, top);
            shape.lineTo(tip, (top + bottom) / 2);
            shape.lineTo(back, bottom);
            shape.close();
            canvas.drawPath(shape, paint);
        }

    }

}
