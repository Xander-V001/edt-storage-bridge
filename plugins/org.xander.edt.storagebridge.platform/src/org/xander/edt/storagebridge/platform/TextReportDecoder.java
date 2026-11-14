package org.xander.edt.storagebridge.platform;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * Decodes a 1C TXT report without locale-dependent silent replacement.
 *
 * <p>Recognizes UTF-8 and UTF-16 BOMs. Without BOM only strict UTF-8 is
 * accepted. Legacy encodings require a separately validated explicit policy
 * based on an authentic Designer fixture; never guess revision numbers.
 */
public final class TextReportDecoder {
    private TextReportDecoder() {
    }

    public static String decode(byte[] data) throws CharacterCodingException {
        Objects.requireNonNull(data, "data");
        int offset = 0;
        Charset charset = StandardCharsets.UTF_8;
        if (startsWith(data, 0xEF, 0xBB, 0xBF)) {
            offset = 3;
        } else if (startsWith(data, 0xFF, 0xFE)) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (startsWith(data, 0xFE, 0xFF)) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        }
        var decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(data, offset, data.length - offset))
            .toString();
    }

    private static boolean startsWith(byte[] bytes, int a, int b) {
        return bytes.length >= 2
            && (bytes[0] & 0xff) == a
            && (bytes[1] & 0xff) == b;
    }

    private static boolean startsWith(byte[] bytes, int a, int b, int c) {
        return bytes.length >= 3
            && (bytes[0] & 0xff) == a
            && (bytes[1] & 0xff) == b
            && (bytes[2] & 0xff) == c;
    }
}
