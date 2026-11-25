package local.bookdoctorc;

import android.content.res.Configuration;

final class TabletLayout {
    private TabletLayout() {}

    static boolean wide(ShelfUi ui) {
        Configuration window = ui.context.getResources().getConfiguration();
        return window.screenWidthDp >= 840 && window.screenWidthDp > window.screenHeightDp;
    }

    static int contentWidth(ShelfUi ui, int compact, int expanded) {
        int width = ui.context.getResources().getConfiguration().screenWidthDp;
        int limit = wide(ui) ? expanded : compact;
        return width > limit ? ui.dp(limit) : ShelfUi.MATCH;
    }
}
