package local.bookdoctorc;

import local.bookdoctorc.reader.charset.CharsetDetector;
import local.bookdoctorc.reader.charset.CharsetMatch;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.*;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

record TextSource(String text, Charset charset, byte[] bom) {
    private static final Pattern XML_ENCODING = Pattern.compile(
            "(?i)^\\s*<\\?xml[^>]*?encoding\\s*=\\s*['\"]([a-z0-9_.-]+)['\"]");

    static TextSource decode(byte[] bytes, boolean markup) throws IOException {
        if (starts(bytes, 0xef, 0xbb, 0xbf)) return decode(bytes, StandardCharsets.UTF_8, 3);
        if (starts(bytes, 0xff, 0xfe)) return decode(bytes, StandardCharsets.UTF_16LE, 2);
        if (starts(bytes, 0xfe, 0xff)) return decode(bytes, StandardCharsets.UTF_16BE, 2);
        if (markup) {
            if (starts(bytes, 0, 0x3c)) return decode(bytes, StandardCharsets.UTF_16BE, 0);
            if (starts(bytes, 0x3c, 0)) return decode(bytes, StandardCharsets.UTF_16LE, 0);
            String head = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.ISO_8859_1);
            Matcher declaration = XML_ENCODING.matcher(head);
            if (declaration.find()) {
                try { return decode(bytes, Charset.forName(declaration.group(1)), 0); }
                catch (IllegalArgumentException error) { throw new IOException("不支持该 EPUB 的文字编码", error); }
            }
            return decode(bytes, StandardCharsets.UTF_8, 0);
        }
        try { return decode(bytes, StandardCharsets.UTF_8, 0); }
        catch (CharacterCodingException ignored) {
            byte[] sample = Arrays.copyOf(bytes, Math.min(bytes.length, 65536));
            CharsetMatch[] matches = new CharsetDetector().setText(sample).detectAll();
            for (CharsetMatch match : matches) {
                String name = match.getName();
                if (match.getConfidence() < 20 || name.startsWith("UTF-16") || name.startsWith("UTF-32")) continue;
                try { return decode(bytes, Charset.forName(name), 0); }
                catch (IllegalArgumentException | CharacterCodingException invalid) { /* Try the next match. */ }
            }
            return decode(bytes, Charset.forName("GB18030"), 0);
        }
    }

    private static TextSource decode(byte[] bytes, Charset charset, int start) throws CharacterCodingException {
        CharBuffer value = charset.newDecoder().decode(ByteBuffer.wrap(bytes, start, bytes.length - start));
        return new TextSource(value.toString(), charset, Arrays.copyOf(bytes, start));
    }

    byte[] encode(String value) throws IOException {
        try {
            ByteBuffer encoded = charset.newEncoder().encode(CharBuffer.wrap(value));
            byte[] bytes = Arrays.copyOf(bom, bom.length + encoded.remaining());
            encoded.get(bytes, bom.length, encoded.remaining());
            return bytes;
        } catch (CharacterCodingException error) {
            throw new IOException("原文件编码不能保存新输入的字符", error);
        }
    }

    void checkRoundTrip(byte[] original) throws IOException {
        if (!Arrays.equals(original, encode(text))) {
            throw new IOException("该编码不能逐字节保留原文，已停止编辑");
        }
    }

    String lineEndings(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        return text.contains("\r\n") ? normalized.replace("\n", "\r\n") : normalized;
    }

    private static boolean starts(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 255) != prefix[i]) return false;
        return true;
    }
}
