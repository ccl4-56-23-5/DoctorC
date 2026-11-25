package local.bookdoctorc;

import android.content.SharedPreferences;
import java.util.Arrays;

final class ModuleTabs {
    static final int BOOKS = 0, PODCAST = 1, MUSIC = 2, HISTORY = 3, PROFILE = 4;
    static final String[] LABELS = {"书架", "播客", "音乐", "记录", "我的"};
    static final int[] ICONS = {R.drawable.nav_books, R.drawable.nav_podcast, R.drawable.nav_music,
            R.drawable.nav_history, R.drawable.nav_profile};
    static final int ALL = 7;
    private final SharedPreferences prefs;
    private int mask;
    private int startup;

    ModuleTabs(SharedPreferences prefs) {
        this.prefs = prefs;
        mask = prefs.getInt("enabled_modules", ALL) & ALL;
        if (mask == 0) mask = ALL;
        startup = preferred(prefs.getInt("startup_module", BOOKS));
    }

    int mask() { return mask; }
    int startup() { return startup; }
    int[] modules() { return filtered(3); }
    int[] dock() { return filtered(5); }
    private int[] filtered(int length) {
        return Arrays.stream(new int[]{BOOKS, PODCAST, MUSIC, HISTORY, PROFILE}, 0, length)
                .filter(this::contains).toArray();
    }
    boolean contains(int page) {
        return page >= BOOKS && page <= PROFILE && (page >= HISTORY || (mask & (1 << page)) != 0);
    }
    int preferred(int candidate) {
        return candidate >= BOOKS && candidate <= MUSIC && contains(candidate) ? candidate : Integer.numberOfTrailingZeros(mask);
    }
    boolean enabled(int value) {
        int valid = value & ALL;
        if (valid == 0) return false;
        mask = valid;
        startup = preferred(startup);
        prefs.edit().putInt("enabled_modules", mask).putInt("startup_module", startup).apply();
        return true;
    }
    void startup(int page) {
        startup = preferred(page);
        prefs.edit().putInt("startup_module", startup).apply();
    }
}
