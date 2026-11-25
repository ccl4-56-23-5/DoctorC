package local.bookdoctorc;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.File;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** App-private originals are decoded once, off the first-open UI path. */
final class BookCoverCache {
    private static final LruCache<String, Bitmap> images = new LruCache<>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getAllocationByteCount(); }
    };
    private static final java.util.concurrent.Executor worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String,List<Consumer<Bitmap>>> pending = new HashMap<>();

    static void load(File file, Consumer<Bitmap> ready) {
        if (file == null) return;
        // The caller always uses a private local file, never SAF/network metadata.
        String key = file.getPath()+":"+file.lastModified()+":"+file.length();
        Bitmap cached = images.get(key);
        if (cached != null) { ready.accept(cached); return; }
        List<Consumer<Bitmap>> listeners = pending.get(key);
        if (listeners != null) { listeners.add(ready); return; }
        listeners = new ArrayList<>();
        listeners.add(ready);
        pending.put(key,listeners);
        worker.execute(() -> {
            Bitmap image = null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(),options);
                if (options.outWidth > 0 && options.outHeight > 0) {
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth,options.outHeight)/(options.inSampleSize*2) >= 768) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    image = BitmapFactory.decodeFile(file.getPath(),options);
                    if (image != null) image.prepareToDraw();
                }
            } catch (RuntimeException ignored) { /* Keep the illustrated cover if a local image is corrupt. */ }
            Bitmap result = image;
            main.post(() -> {
                if (result != null) images.put(key,result);
                List<Consumer<Bitmap>> waiting = pending.remove(key);
                if (result != null && waiting != null) for (Consumer<Bitmap> callback : waiting) callback.accept(result);
            });
        });
    }
}
