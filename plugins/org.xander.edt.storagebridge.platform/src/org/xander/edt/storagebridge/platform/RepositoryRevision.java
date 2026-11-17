package org.xander.edt.storagebridge.platform;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * A future parsed revision. Parsing is NOT implemented until actual 1C TXT
 * report fixtures have been obtained and verified against Designer.
 */
public record RepositoryRevision(long number, OffsetDateTime committedAt,
        String author, String comment, List<String> changedObjects) {

    public RepositoryRevision {
        if (number < 1) {
            throw new IllegalArgumentException("Repository revision must be positive");
        }
        author = Objects.requireNonNull(author, "author");
        comment = Objects.requireNonNull(comment, "comment");
        changedObjects = List.copyOf(changedObjects);
    }
}
