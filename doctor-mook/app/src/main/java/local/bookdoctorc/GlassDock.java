package local.bookdoctorc;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import android.view.animation.OvershootInterpolator;
import java.util.function.IntConsumer;

final class GlassDock extends LinearLayout {
    private final DockTab[] tabs = new DockTab[ModuleTabs.LABELS.length];
    private int[] visible = {0, 1, 2, 3, 4};
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final int SIDE_OVERSCAN = 13, VERTICAL_OVERSCAN = 11;
    private static final Typeface LABEL = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface EMPHASIS = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final Matrix sheenMatrix = new Matrix();
    private final LinearGradient daySelection = new LinearGradient(0f, 0f, 0f, 1f,
            0x64f5cfb2, 0x3ac28559, Shader.TileMode.CLAMP);
    private final LinearGradient nightSelection = new LinearGradient(0f, 0f, 0f, 1f,
            0x48ebad85, 0x35bb7857, Shader.TileMode.CLAMP);
    private final RectF tray = new RectF(), indicator = new RectF(), extent = new RectF();
    private final LiquidGlass glass, lens;
    private final MotionProgress pressMotion;
    private float pressed;
    private ShelfUi ui;
    private View content;
    private boolean dirty = true;
    private ValueAnimator slide;
    private float selection;
    private int active;
    private boolean audioColors;
    private final IntConsumer changePage;
    private final Runnable hold=this::startDrag;
    private float downX,downY,lastX;
    private boolean dragging, cancelledChild;

    GlassDock(Context context, ShelfUi palette, IntConsumer changePage) {
        super(context);
        this.changePage=changePage;
        ui = palette;
        setWillNotDraw(false);
        setGravity(Gravity.CENTER);
        setElevation(ui.dp(5));
        setClipChildren(false);
        setClipToPadding(false);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                int x = ui.dp(SIDE_OVERSCAN), y = ui.dp(VERTICAL_OVERSCAN);
                outline.setRoundRect(x, y, getWidth() - x, getHeight() - y, (getHeight() - y * 2) / 2f);
                outline.setAlpha(.22f);
            }
        });
        glass = new LiquidGlass(this, LiquidGlass.Profile.ACTION_GROUP);
        lens = new LiquidGlass(this, LiquidGlass.Profile.THUMB);
        pressMotion = new MotionProgress(220, fraction -> {
            pressed = fraction;
            invalidate();
        });
        setPadding(ui.dp(SIDE_OVERSCAN + 5), ui.dp(VERTICAL_OVERSCAN + 4),
                ui.dp(SIDE_OVERSCAN + 5), ui.dp(VERTICAL_OVERSCAN + 4));
        for (int i = 0; i < tabs.length; i++) {
            tabs[i] = new DockTab(context, i);
            addView(tabs[i], new LayoutParams(0, LayoutParams.MATCH_PARENT, 1));
        }
    }

    private final class DockTab extends LinearLayout {
        private final ImageView icon;
        private final TextView label;

        DockTab(Context context, int module) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            setContentDescription(ModuleTabs.LABELS[module]);
            setFocusable(true);
            setOnClickListener(v -> changePage.accept(module));

            icon = new ImageView(context);
            icon.setImageResource(ModuleTabs.ICONS[module]);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            int iconSize = ui.dp(TabletLayout.wide(ui) ? 24 : 22);
            addView(icon, new LayoutParams(iconSize, iconSize));

            label = ui.text(ModuleTabs.LABELS[module], 11, ui.secondary, false);
            label.setTypeface(LABEL);
            label.setIncludeFontPadding(false);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LayoutParams textSize = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            textSize.topMargin = ui.dp(4);
            addView(label, textSize);
        }

        @Override public CharSequence getAccessibilityClassName() {
            return Button.class.getName();
        }

        float groupHeight() {
            return label.getBottom() - icon.getTop();
        }
    }

    static int trayHeight(ShelfUi ui) {
        float scale = ui.context.getResources().getConfiguration().fontScale;
        return (TabletLayout.wide(ui) ? 65 : 61) + Math.round(11 * Math.max(0, scale - 1));
    }

    void bind(ShelfUi palette, View source, int selected, boolean animate, int[] ordered) {
        if (!java.util.Arrays.equals(visible, ordered)) {
            visible = ordered.clone();
            for (int index = 0; index < tabs.length; index++) {
                tabs[index].setVisibility(indexOf(index) < 0 ? GONE : VISIBLE);
            }
            animate = false;
        }
        ui = palette;
        content = source;
        select(selected, animate);
        backdropChanged();
    }

    void select(int selected, boolean animated) {
        active = selected;
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setSelected(i == active);
            tabs[i].label.setTypeface(i == active ? EMPHASIS : LABEL);
        }
        if (dragging) return;
        animateSelection(Math.max(0, indexOf(selected)), animated, pressed > 0);
    }

    private void animateSelection(float target, boolean animated, boolean spring) {
        if (slide != null) slide.cancel();
        if (!animated || !ValueAnimator.areAnimatorsEnabled()) {
            selection = target;
            colorLabels();
            invalidate();
            return;
        }
        slide = ValueAnimator.ofFloat(selection, target);
        slide.setInterpolator(spring ? new OvershootInterpolator(.75f) : Transitions.EASE);
        slide.setDuration(Transitions.atRate(265));
        slide.addUpdateListener(value -> {
            selection = (float) value.getAnimatedValue();
            colorLabels();
            invalidate();
        });
        slide.start();
    }

    void useAudioColors(boolean value) {
        audioColors = value;
        colorLabels();
        backdropChanged();
    }

    private void colorLabels() {
        int inactive = ui.dark ? 0xffbec9d9 : audioColors ? 0xff50607a : ui.secondary;
        int accent = ui.dark ? 0xffffd1a6 : 0xff8a4222;
        for (int i : visible) {
            float fill = Math.max(0, 1 - Math.abs(selection - indexOf(i)));
            int color = ShelfUi.mix(inactive, accent, fill);
            tabs[i].label.setTextColor(color);
            tabs[i].icon.setColorFilter(color);
        }
    }

    void backdropChanged() {
        dirty = true;
        invalidate();
    }

    private void startDrag(){
        dragging=true;
        if(slide!=null)slide.cancel();
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        pressMotion.move(1, null);
        animateSelection(position(lastX), true, false);
    }

    private int indexOf(int module) {
        for (int slot = 0; slot < visible.length; slot++) if (visible[slot] == module) return slot;
        return -1;
    }

    private float position(float x) {
        int first = tabs[visible[0]].getLeft();
        float cell = (tabs[visible[visible.length - 1]].getRight() - first) / (float) visible.length;
        return cell == 0 ? selection : Math.max(0, Math.min(visible.length - 1, (x - first) / cell - .5f));
    }

    private void updateDrag(float x) {
        if (slide != null) slide.cancel();
        selection = position(x);
        colorLabels();
        invalidate();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            if (event.getX() < ui.dp(SIDE_OVERSCAN) || event.getX() >= getWidth() - ui.dp(SIDE_OVERSCAN)
                    || event.getY() < ui.dp(VERTICAL_OVERSCAN) || event.getY() >= getHeight() - ui.dp(VERTICAL_OVERSCAN)) return false;
            removeCallbacks(hold);
            dragging=false;cancelledChild=false;
            downX=lastX=event.getX();downY=event.getY();
            postDelayed(hold,ViewConfiguration.getLongPressTimeout());
        }else if(action==MotionEvent.ACTION_MOVE){
            lastX=event.getX();
            if(!dragging && Math.hypot(event.getX()-downX,event.getY()-downY)>ViewConfiguration.get(getContext()).getScaledTouchSlop()){
                removeCallbacks(hold);
            }
        }
        if(dragging){
            if(!cancelledChild){
                MotionEvent cancel=MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                cancelledChild=true;
            }
            if(action==MotionEvent.ACTION_MOVE)updateDrag(event.getX());
            if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_POINTER_DOWN){
                removeCallbacks(hold);
                boolean inside = event.getX() >= 0 && event.getX() <= getWidth()
                        && event.getY() >= -ui.dp(12) && event.getY() <= getHeight() + ui.dp(12);
                int target = action == MotionEvent.ACTION_UP && inside ? visible[Math.min(visible.length - 1, Math.round(position(event.getX())))] : active;
                dragging=false;
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                if (target != active) changePage.accept(target);
                animateSelection(Math.max(0, indexOf(active)), true, true);
                pressMotion.move(0, null);
            }
            return true;
        }
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_POINTER_DOWN)removeCallbacks(hold);
        if(cancelledChild && action!=MotionEvent.ACTION_DOWN)return true;
        return super.dispatchTouchEvent(event);
    }


    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float outerX = ui.dp(SIDE_OVERSCAN), outerY = ui.dp(VERTICAL_OVERSCAN);
        tray.set(outerX, outerY, getWidth() - outerX, getHeight() - outerY);
        // The Coolapk home tray grows by four times its 4 dp content padding.
        float expansion = ui.dp(8) * pressed;
        float vertical = expansion * tray.height() / tray.width();
        tray.inset(-expansion, -vertical);
        drawGlass(canvas, glass, tray, dirty, -1);

        float first = tabs[visible[0]].getLeft();
        float cell = (tabs[visible[visible.length - 1]].getRight() - first) / (float) visible.length;
        float center = first + cell * (selection + .5f);
        DockTab cellView = tabs[visible[0]];
        boolean wide = TabletLayout.wide(ui);
        float halfWidth = Math.max(0, Math.min(cell - ui.dp(6), ui.dp(wide ? 73 : 64)) * .5f)
                + ui.dp(5) * pressed;
        float halfHeight = Math.min(cellView.getHeight() - ui.dp(5),
                Math.max(ui.dp(46), cellView.groupHeight() + ui.dp(11))) * .5f + ui.dp(2) * pressed;
        float side = tray.left + ui.dp(4) + halfWidth;
        center = Math.max(side, Math.min(tray.right - ui.dp(4) - halfWidth, center));
        float centerY = (cellView.getTop() + cellView.getBottom()) * .5f;
        indicator.set(center - halfWidth, centerY - halfHeight, center + halfWidth, centerY + halfHeight);
        float radius = Math.min(Math.min(indicator.width(), indicator.height()) * .5f,
                ui.dp(wide ? 17 : 15) + ui.dp(3) * pressed);
        if (pressed > 0) {
            extent.set(indicator);
            extent.inset(-ui.dp(1), -ui.dp(1));
            int saved = canvas.saveLayerAlpha(extent, Math.round(235 * pressed));
            drawGlass(canvas, lens, indicator, dirty, radius);
            canvas.restoreToCount(saved);
        }
        LinearGradient sheen = ui.dark ? nightSelection : daySelection;
        sheenMatrix.setScale(1f, indicator.height());
        sheenMatrix.postTranslate(0f, indicator.top);
        sheen.setLocalMatrix(sheenMatrix);
        paint.setColor(Color.WHITE);
        paint.setShader(sheen);
        canvas.drawRoundRect(indicator, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, ui.dp(1) * .52f));
        paint.setColor(ui.dark ? 0x52f2bf91 : 0x36ae704b);
        canvas.drawRoundRect(indicator, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        dirty = false;
    }

    private void drawGlass(Canvas canvas, LiquidGlass material, RectF bounds, boolean refresh, float corner) {
        int x = Math.round(bounds.left), y = Math.round(bounds.top);
        material.draw(canvas, this, content, x, y, Math.round(bounds.right) - x,
                Math.round(bounds.bottom) - y, ui.dark, refresh, corner);
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(hold);
        if (slide != null) slide.cancel();
        pressMotion.stop();
        pressMotion.set(0);
        dragging = false;
        glass.release();
        lens.release();
        super.onDetachedFromWindow();
    }
}
