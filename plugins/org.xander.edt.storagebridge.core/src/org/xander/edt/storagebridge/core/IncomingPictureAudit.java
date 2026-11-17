package org.xander.edt.storagebridge.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * READ-ONLY three-stage diagnostic for a native CommonPicture's binary
 * payload: Designer XML -> EDT imported staging -> live EDT project.
 * Does not copy files, normalize images, change a merge decision or declare
 * a repository revision applied. A report is user-local and confidential.
 */
public final class IncomingPictureAudit {
    private static final Set<String> EXTENSIONS =
        Set.of(".png", ".svg", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".webp");
    private IncomingPictureAudit() {}

    public static String inspect(Path designerXml, Path importedEdt,
            Path liveEdt, String edtOwner) throws IOException {
        if (edtOwner == null || !edtOwner.matches("CommonPictures/[^/\\\\.][^/\\\\]*")
                || edtOwner.contains("..")) {
            throw new IOException("Not a valid top-level CommonPicture owner");
        }
        String name = edtOwner.substring("CommonPictures/".length());
        Path pictureXml = designerXml.resolve("CommonPictures")
            .resolve(name).resolve("Ext/Picture");
        Path pictureStage = importedEdt.resolve("src/CommonPictures").resolve(name);
        Path pictureLive = liveEdt.resolve("src/CommonPictures").resolve(name);
        int sourceCount = imageCount(pictureXml);
        int stageCount = imageCount(pictureStage);
        int liveCount = imageCount(pictureLive);
        List<String> notes = new ArrayList<>();
        notes.add("1C Designer XML images: " + sourceCount);
        notes.add("Converted EDT images: " + stageCount);
        notes.add("Live EDT images: " + liveCount);
        if (stageCount > 0 && liveCount == 0) {
            notes.add("Stage has an image but EDT does not: check the native merge "
                + "selection and image-resource application, NOT only the XML dump.");
        } else if (sourceCount > 0 && stageCount == 0) {
            notes.add("1C XML has an image but converted EDT does not: inspect the "
                + "native IImportService result and image conversion.");
        } else if (sourceCount == 0) {
            notes.add("No recognized XML image found under this optional standard "
                + "path. This does not prove the source picture is empty.");
        } else {
            notes.add("Image counts alone do not establish equal image content.");
        }
        return String.join("; ", notes);
    }

    private static int imageCount(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory)) {
            throw new IOException("Unexpected picture resource path");
        }
        int count = 0;
        try (Stream<Path> files = Files.walk(directory, 8)) {
            for (Path file : files.toList()) {
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("Symbolic image in resource tree");
                }
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                String fn = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                for (String extension : EXTENSIONS) {
                    if (fn.endsWith(extension)) {
                        count++;
                        break;
                    }
                }
            }
        }
        return count;
    }
}
