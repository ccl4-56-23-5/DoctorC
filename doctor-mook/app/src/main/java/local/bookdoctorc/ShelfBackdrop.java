package local.bookdoctorc;

import android.graphics.*;
import android.view.View;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

/** Prepare a small, blurred cabinet background between interactions, not on the first tap. */
final class ShelfBackdrop {
    private static final java.util.concurrent.Executor worker = Executors.newSingleThreadExecutor(task ->
            new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                task.run();
            }, "BookDoctorC backdrop"));
    private final View page;
    private final BooleanSupplier idle;
    private int revision;
    private Bitmap ready;
    private final Runnable capture = this::capture;

    ShelfBackdrop(View page, BooleanSupplier idle) {
        this.page = page;
        this.idle = idle;
        page.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { changed(); }
            @Override public void onViewDetachedFromWindow(View view) { page.removeCallbacks(capture); }
        });
        if (page.isAttachedToWindow()) changed();
    }

    void changed() {
        revision++;
        ready = null;
        page.removeCallbacks(capture);
        page.postDelayed(capture, 140);
    }

    Bitmap image() { return ready; }

    private void capture() {
        if (!page.isAttachedToWindow() || page.getWidth() == 0 || page.getHeight() == 0) return;
        if (!idle.getAsBoolean()) {
            page.postDelayed(capture, 160);
            return;
        }
        int token = revision;
        float scale = Math.min(.25f, 320f / page.getWidth());
        int width = Math.max(1, Math.round(page.getWidth()*scale));
        int height = Math.max(1, Math.round(page.getHeight()*scale));
        Picture commands = new Picture();
        Canvas canvas = commands.beginRecording(width,height);
        canvas.scale(scale,scale);
        page.draw(canvas);
        commands.endRecording();
        int radius = Math.max(1,Math.round(15*page.getResources().getDisplayMetrics().density*scale));
        worker.execute(() -> {
            Bitmap image = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            new Canvas(image).drawPicture(commands);
            int[] pixels = new int[width*height], temp = new int[pixels.length];
            image.getPixels(pixels,0,width,0,0,width,height);
            // Two separable box passes approximate a soft Gaussian; all work is bounded.
            for (int pass = 0; pass < 2; pass++) {
                blur(pixels,temp,width,height,radius,true);
                blur(temp,pixels,width,height,radius,false);
            }
            image.setPixels(pixels,0,width,0,0,width,height);
            image.prepareToDraw();
            page.post(() -> { if (revision == token && page.isAttachedToWindow()) ready = image; });
        });
    }

    private static void blur(int[] src,int[] dst,int w,int h,int radius,boolean horizontal) {
        int lines = horizontal ? h : w, count = horizontal ? w : h, size = radius*2+1;
        for (int line=0;line<lines;line++) {
            int red=0,green=0,blue=0;
            for (int p=-radius;p<=radius;p++) {
                int at=Math.max(0,Math.min(count-1,p));
                int c=src[horizontal?line*w+at:at*w+line];
                red+=(c>>16)&255;green+=(c>>8)&255;blue+=c&255;
            }
            for(int p=0;p<count;p++) {
                dst[horizontal?line*w+p:p*w+line]=0xff000000|(red/size<<16)|(green/size<<8)|blue/size;
                int drop=Math.max(0,p-radius), add=Math.min(count-1,p+radius+1);
                int a=src[horizontal?line*w+add:add*w+line], d=src[horizontal?line*w+drop:drop*w+line];
                red+=((a>>16)&255)-((d>>16)&255);
                green+=((a>>8)&255)-((d>>8)&255);
                blue+=(a&255)-(d&255);
            }
        }
    }
}
