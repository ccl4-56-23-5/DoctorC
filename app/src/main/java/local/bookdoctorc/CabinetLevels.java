package local.bookdoctorc;

import android.graphics.*;
import android.widget.LinearLayout;

/** One wooden case, with a full-width bay for each box. */
final class CabinetLevels extends LinearLayout {
    static final int HEADER_HEIGHT = 33;
    static final int SPINE_HEIGHT = 83;
    static final int FLOOR_HEIGHT = 9;

    private final ShelfUi ui;
    private final Paint wood = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shadow = new Paint();
    private final Rect plank = new Rect();
    CabinetLevels(ShelfUi ui) {
        super(ui.context);
        this.ui = ui;
        setOrientation(VERTICAL);
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(14));
        setBackground(ui.oakFrame(12));
        setElevation(ui.dp(5));
        setWillNotDraw(false);
        if (ui.dark) wood.setColorFilter(new LightingColorFilter(0xffbda486, 0xff0c0703));
    }

    @Override protected void onDraw(Canvas canvas) {
        drawJoinery(canvas);
    }

    private void drawJoinery(Canvas canvas) {
        int inset = getPaddingLeft();
        int header = ui.dp(HEADER_HEIGHT);
        int floor = ui.dp(FLOOR_HEIGHT);
        int spines = ui.dp(SPINE_HEIGHT);
        for (int i = 0; i < getChildCount(); i++) {
            ShelfUi.BoxTile box = (ShelfUi.BoxTile) getChildAt(i);
            int top = box.getTop();
            wood.setColor(ui.paper);
            plank.set(inset, top, getWidth() - inset, top + header);
            canvas.drawRect(plank, wood);
            shadow.setColor(ui.dark ? 0x30fff4de : 0x52fff4de);
            canvas.drawRect(inset, top, getWidth() - inset, top + ui.dp(1), shadow);
            shadow.setColor(ui.dark ? 0x5b1b1109 : 0x583c210e);
            canvas.drawRect(inset, plank.bottom - ui.dp(2), getWidth() - inset, plank.bottom, shadow);
            int base = top + header + spines;
            plank.set(inset, top + header, getWidth() - inset, base);
            canvas.drawRect(plank, wood);
            shadow.setColor(ui.dark ? 0x57251811 : 0x302b150b);
            canvas.drawRect(plank.left, plank.top, plank.right, plank.bottom, shadow);
            plank.set(inset, base, getWidth() - inset, base + floor);
            canvas.drawRect(plank, wood);
            shadow.setColor(ui.dark ? 0x73301b0e : 0x73472913);
            canvas.drawRect(inset, base - ui.dp(1), getWidth() - inset, base, shadow);
        }
    }

    SurfaceCapture captureBay(ShelfUi.BoxTile box, Rect visible) {
        return new SurfaceCapture(this, visible.width(), visible.height(), canvas -> {
        canvas.translate(-box.getLeft() - visible.left, -box.getTop() - visible.top);
        getBackground().draw(canvas);
        drawJoinery(canvas);
        Rect label = new Rect(0, 0, box.lettering.getWidth(), box.lettering.getHeight());
        offsetDescendantRectToMyCoords(box.lettering, label);
        canvas.translate(label.left, label.top);
        box.lettering.draw(canvas);
        });
    }
}
