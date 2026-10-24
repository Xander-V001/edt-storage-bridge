package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.TextReportDecoder;

public class TextReportDecoderTest {
    @Test
    public void decodesUtf8Bom() throws Exception {
        byte[] text = "Версия 101".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = new byte[text.length + 3];
        encoded[0] = (byte) 0xEF;
        encoded[1] = (byte) 0xBB;
        encoded[2] = (byte) 0xBF;
        System.arraycopy(text, 0, encoded, 3, text.length);
        assertEquals("Версия 101", TextReportDecoder.decode(encoded));
    }

    @Test
    public void decodesUtf16LittleEndianBom() throws Exception {
        byte[] text = "Версия 101".getBytes(StandardCharsets.UTF_16LE);
        byte[] encoded = new byte[text.length + 2];
        encoded[0] = (byte) 0xFF;
        encoded[1] = (byte) 0xFE;
        System.arraycopy(text, 0, encoded, 2, text.length);
        assertEquals("Версия 101", TextReportDecoder.decode(encoded));
    }

    @Test
    public void rejectsMalformedData() {
        assertThrows(CharacterCodingException.class, () ->
            TextReportDecoder.decode(new byte[] {(byte) 0xC3, 0x28}));
    }
}
