package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.xander.edt.storagebridge.edt.EdtRootXmlGuard;

public class EdtRootXmlGuardTest {
    @Test
    public void holdsOnlyConfigurationExtAndRestoresItAfterConversion()
            throws Exception {
        Path root = Files.createTempDirectory("bridge-xml-test-");
        try {
            Path ext = Files.createDirectories(root.resolve("Ext"));
            Path home = Files.writeString(ext.resolve("HomePageWorkArea.xml"), "home");
            Path client = Files.writeString(ext.resolve("ClientApplicationInterface.xml"), "ui");
            Path catalog = Files.createDirectories(root.resolve("Catalogs"));
            Files.writeString(catalog.resolve("Products.xml"), "product");

            try (var hold = EdtRootXmlGuard.hideUnselectedRootExt(
                    root, List.of("Catalog.Products"))) {
                assertFalse(Files.exists(ext));
                assertTrue(Files.isRegularFile(catalog.resolve("Products.xml")));
                assertFalse(Files.exists(root.resolve("Ext/HomePageWorkArea.xml")));
            }
            assertEquals("home", Files.readString(home));
            assertEquals("ui", Files.readString(client));
        } finally {
            try (var entries = Files.walk(root)) {
                for (Path path : entries.sorted((a, b) -> b.compareTo(a)).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    public void cannotSuppressExplicitlyRequestedRootChanges() throws Exception {
        Path root = Files.createTempDirectory("bridge-xml-test-");
        try {
            Path ext = Files.createDirectories(root.resolve("Ext"));
            Files.writeString(ext.resolve("ClientApplicationInterface.xml"), "ui");
            assertThrows(IOException.class, () ->
                EdtRootXmlGuard.hideUnselectedRootExt(root,
                    List.of("Configuration.Payroll.ClientApplicationInterface")));
            assertTrue(Files.exists(ext.resolve("ClientApplicationInterface.xml")));
        } finally {
            try (var entries = Files.walk(root)) {
                for (Path path : entries.sorted((a, b) -> b.compareTo(a)).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    public void doesNothingWhenTopLevelExtNotPresent() throws Exception {
        Path root = Files.createTempDirectory("bridge-xml-test-");
        try (var ignored = EdtRootXmlGuard.hideUnselectedRootExt(
                root, List.of("Document.Order"))) {
            assertTrue(Files.isDirectory(root));
        } finally {
            Files.delete(root);
        }
    }
}
