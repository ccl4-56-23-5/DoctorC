package local.bookdoctorc;

import android.content.Context;
import android.graphics.Typeface;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class ReaderFontFile {
    static final String PATH = "/.bookdoctorc/reader-font.ttf";
    static final String NAME_KEY = "reader_font_file_name";
    static final long LIMIT = 32L * 1024 * 1024;

    static File file(Context context) { return new File(context.getFilesDir(), "reader-font.ttf"); }

    static void install(Context context, InputStream input) throws IOException {
        File target = file(context);
        File draft = File.createTempFile("reader-font-", ".ttf", target.getParentFile());
        try {
            try (FileOutputStream out = new FileOutputStream(draft)) {
                SourceFiles.copy(input, out, LIMIT);
            }
            try (DataInputStream check = new DataInputStream(new FileInputStream(draft))) {
                int tag = check.readInt();
                if (tag != 0x00010000 && tag != 0x74727565) throw new IOException("请选择有效的 TTF 字体");
            } catch (EOFException empty) {
                throw new IOException("请选择有效的 TTF 字体", empty);
            }
            if (new Typeface.Builder(draft).build() == null) throw new IOException("无法读取此 TTF 字体");
            Files.move(draft.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(draft.toPath());
        }
    }
}
