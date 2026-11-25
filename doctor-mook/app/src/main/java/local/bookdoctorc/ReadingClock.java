package local.bookdoctorc;

import android.os.*;
import java.time.*;

final class ReadingClock {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Library library;
    private Library.Book book;
    private final Runnable tick = this::checkpoint;
    private long wall, started;
    private boolean running;

    ReadingClock(Library library, Library.Book book) { this.library = library; this.book = book; }

    void updateBook(Library.Book updated) { book = updated; }

    void start() {
        if (running) return;
        running = true;
        wall = System.currentTimeMillis();
        started = SystemClock.elapsedRealtime();
        handler.postDelayed(tick, 15000);
    }

    void stop() {
        if (!running) return;
        checkpoint();
        running = false;
        handler.removeCallbacks(tick);
    }

    private void checkpoint() {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        long elapsed = Math.max(0, Math.min(60000, now - started));
        long start = wall;
        long end = start + elapsed;
        ZoneId zone = ZoneId.systemDefault();
        while (start < end) {
            LocalDate date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate();
            long midnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
            long stop = Math.min(end, midnight);
            library.addReadingTime(book, date.toString(), stop - start);
            start = stop;
        }
        wall = System.currentTimeMillis();
        started = now;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 15000);
    }
}
