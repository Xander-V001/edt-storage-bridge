package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.Test;
import org.xander.edt.storagebridge.core.IncomingPictureAudit;

/** Offline unit tests: no 1C executable, Git, or user project required. */
public final class IncomingPictureAuditTest {
    @Test
    public void identifiesImagePresentAfterConversionButMissingAfterMerge()
            throws Exception {
        Path xml = Files.createTempDirectory("1c-picture-source-");
        Path stage = Files.createTempDirectory("1c-picture-stage-");
        Path live = Files.createTempDirectory("1c-picture-live-");
        try {
            Files.createDirectories(xml.resolve(
                "CommonPictures/ФорумСервиса/Ext/Picture"));
            Files.write(xml.resolve(
                "CommonPictures/ФорумСервиса/Ext/Picture/Picture.png"),
                new byte[] {(byte) 137, 80, 78, 71});
            Files.createDirectories(stage.resolve(
                "src/CommonPictures/ФорумСервиса"));
            Files.write(stage.resolve(
                "src/CommonPictures/ФорумСервиса/Picture.png"),
                new byte[] {(byte) 137, 80, 78, 71});
            Files.createDirectories(live.resolve(
                "src/CommonPictures/ФорумСервиса"));
            String result = IncomingPictureAudit.inspect(
                xml, stage, live, "CommonPictures/ФорумСервиса");
            assertTrue(result.contains("1C Designer XML images: 1"));
            assertTrue(result.contains("Converted EDT images: 1"));
            assertTrue(result.contains("Live EDT images: 0"));
            assertTrue(result.contains("Stage has an image but EDT does not"));
        } finally {
            delete(xml); delete(stage); delete(live);
        }
    }

    @Test
    public void detectsMissingImageInXmlToEdtConversion()
            throws Exception {
        Path xml = Files.createTempDirectory("1c-picture-source-");
        Path stage = Files.createTempDirectory("1c-picture-stage-");
        Path live = Files.createTempDirectory("1c-picture-live-");
        try {
            Files.createDirectories(xml.resolve(
                "CommonPictures/Test/Ext/Picture"));
            Files.write(xml.resolve(
                "CommonPictures/Test/Ext/Picture/Picture.svg"), new byte[] {1, 2, 3});
            assertTrue(IncomingPictureAudit.inspect(
                xml, stage, live, "CommonPictures/Test")
                .contains("1C XML has an image but converted EDT does not"));
            assertThrows(IOException.class, () ->
                IncomingPictureAudit.inspect(xml, stage, live, "../../secrets"));
        } finally {
            delete(xml); delete(stage); delete(live);
        }
    }

    private static void delete(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
