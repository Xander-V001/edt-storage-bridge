package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.junit.Assume;
import org.junit.Test;
import org.xander.edt.storagebridge.platform.InfobaseLocation;
import org.xander.edt.storagebridge.platform.SelectiveDumpRequest;
import org.xander.edt.storagebridge.platform.SelectiveDumpRunner;

/** Native Designer failure-path test with an isolated dummy executable, no 1C base. */
public final class SelectiveDumpFailureTest {
    @Test
    public void failedDesignerMustKeepExactObjectListAndDiagnostics() throws Exception {
        Assume.assumeTrue(System.getProperty("os.name")
            .toLowerCase(Locale.ROOT).contains("linux"));
        Path binDir = Files.createTempDirectory("fake-designer-bin-");
        Path executable = binDir.resolve("1cv8");
        Files.writeString(executable, """
            #!/bin/sh
            log=""
            while [ "$#" -gt 0 ]; do
              if [ "$1" = "/Out" ]; then
                shift
                log="$1"
                break
              fi
              shift
            done
            echo "Invalid selective object list" > "$log"
            echo "dummy failure" >&2
            exit 101
            """, StandardCharsets.UTF_8);
        assertTrue(executable.toFile().setExecutable(true));
        Path diagnosticDir = null;
        try {
            var request = new SelectiveDumpRequest(executable,
                new InfobaseLocation(InfobaseLocation.Kind.FILE,
                    "/no-real-infobase"),
                List.of("Document.НаграднойМатериал"), Duration.ofSeconds(10));
            SelectiveDumpRunner.DumpFailure failure = assertThrows(
                SelectiveDumpRunner.DumpFailure.class,
                () -> new SelectiveDumpRunner().export(request, () -> false));
            assertEquals(101, failure.exitCode());
            diagnosticDir = failure.diagnosticsDirectory();
            assertTrue(Files.readString(
                diagnosticDir.resolve("designer.txt"))
                .contains("Invalid selective object list"));
            assertTrue(Files.readString(
                diagnosticDir.resolve("stdout.txt"))
                .contains("dummy failure"));
            byte[] bytes = Files.readAllBytes(
                diagnosticDir.resolve("objects.txt"));
            assertEquals(0xef, bytes[0] & 0xff);
            assertEquals(0xbb, bytes[1] & 0xff);
            assertEquals(0xbf, bytes[2] & 0xff);
            assertTrue(new String(bytes, StandardCharsets.UTF_8)
                .contains("Document.НаграднойМатериал"));
            assertFalse(Files.exists(diagnosticDir.resolve("objects")));
        } finally {
            if (diagnosticDir != null) {
                deleteTree(diagnosticDir);
            }
            deleteTree(binDir);
        }
    }

    @Test
    public void zeroExitWithOnlyVersionIndexIsNotSuccessfulSelectiveDump()
            throws Exception {
        Assume.assumeTrue(System.getProperty("os.name")
            .toLowerCase(Locale.ROOT).contains("linux"));
        Path binDir = Files.createTempDirectory("fake-empty-designer-");
        Path executable = binDir.resolve("1cv8");
        Files.writeString(executable, """
            #!/bin/sh
            while [ "$#" -gt 0 ]; do
              if [ "$1" = "/DumpConfigToFiles" ]; then
                shift
                echo '<ConfigDumpInfo/>' > "$1/ConfigDumpInfo.xml"
                exit 0
              fi
              shift
            done
            exit 1
            """, StandardCharsets.UTF_8);
        assertTrue(executable.toFile().setExecutable(true));
        Path diagnosticDir = null;
        try {
            var request = new SelectiveDumpRequest(executable,
                new InfobaseLocation(InfobaseLocation.Kind.FILE,
                    "/no-real-infobase"),
                List.of("Catalog.Товары"), Duration.ofSeconds(10));
            var failure = assertThrows(
                SelectiveDumpRunner.DumpFailure.class,
                () -> new SelectiveDumpRunner().export(request, () -> false));
            diagnosticDir = failure.diagnosticsDirectory();
            assertEquals(0, failure.exitCode());
            assertTrue(Files.exists(diagnosticDir.resolve("objects.txt")));
            assertFalse(Files.exists(diagnosticDir.resolve("objects")));
        } finally {
            if (diagnosticDir != null) {
                deleteTree(diagnosticDir);
            }
            deleteTree(binDir);
        }
    }

    @Test
    public void supplementalNativeListFileKeepsPreviousIncrementalXml() throws Exception {
        Assume.assumeTrue(System.getProperty("os.name")
            .toLowerCase(Locale.ROOT).contains("linux"));
        Path bin = Files.createTempDirectory("fake-1c-completion-");
        Path nativePatch = Files.createTempDirectory("native-incremental-");
        try {
            Path executable = bin.resolve("1cv8");
            Files.writeString(executable, """
                #!/bin/sh
                destination=""
                names=""
                while [ "$#" -gt 0 ]; do
                  case "$1" in
                    /DumpConfigToFiles) shift; destination="$1" ;;
                    -listFile) shift; names="$1" ;;
                  esac
                  shift
                done
                [ -f "$names" ] || exit 32
                [ -f "$destination/OriginalChanged.xml" ] || exit 33
                echo '<NativeSupplement/>' > "$destination/AdditionalForm.xml"
                exit 0
                """, StandardCharsets.UTF_8);
            assertTrue(executable.toFile().setExecutable(true));
            Files.writeString(nativePatch.resolve("ConfigDumpInfo.xml"),
                "<ConfigDumpInfo/>");
            Files.writeString(nativePatch.resolve("OriginalChanged.xml"),
                "<NativeChangedModule/>");
            var request = new SelectiveDumpRequest(executable,
                new InfobaseLocation(InfobaseLocation.Kind.FILE,
                    "/no-real-infobase"),
                List.of("Document.Order.Form.Main", "Configuration",
                    "Language.Русский"), Duration.ofSeconds(10));
            new SelectiveDumpRunner().supplement(
                request, nativePatch, () -> false);
            assertEquals("<NativeChangedModule/>",
                Files.readString(nativePatch.resolve("OriginalChanged.xml")));
            assertTrue(Files.isRegularFile(
                nativePatch.resolve("AdditionalForm.xml")));
            assertEquals("<ConfigDumpInfo/>",
                Files.readString(nativePatch.resolve("ConfigDumpInfo.xml")));
        } finally {
            deleteTree(nativePatch);
            deleteTree(bin);
        }
    }

    @Test
    public void onlyValidNativeImportRootCanBeIncludedInSelectiveList() {
        var location = new InfobaseLocation(
            InfobaseLocation.Kind.FILE, "/no-real-infobase");
        var executable = Path.of("/opt/1cv8");
        var allowed = new SelectiveDumpRequest(executable, location,
            List.of("Document.Order", "Configuration", "Language.Русский"),
            Duration.ofSeconds(10));
        assertTrue(allowed.objectNames().contains("Configuration"));
        assertThrows(IllegalArgumentException.class, () ->
            new SelectiveDumpRequest(executable, location,
                List.of("Language"), Duration.ofSeconds(10)));
    }

    private static void deleteTree(Path directory) throws Exception {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
