package org.xander.edt.storagebridge.platform;

import java.util.Arrays;
import java.util.Objects;

/**
 * Raw report bytes. 1C report TXT encoding/format is platform-dependent:
 * no fabricated version extraction or heuristic object-list parsing.
 */
public final class RepositoryHistoryReport {
    private final byte[] raw;
    private final long beginVersion;
    private final Long endVersion;

    public RepositoryHistoryReport(byte[] raw, long beginVersion, Long endVersion) {
        this.raw = Objects.requireNonNull(raw, "raw").clone();
        if (raw.length == 0) {
            throw new IllegalArgumentException("Empty report cannot prove repository status");
        }
        this.beginVersion = beginVersion;
        this.endVersion = endVersion;
    }

    public byte[] bytes() {
        return raw.clone();
    }

    public long beginVersion() {
        return beginVersion;
    }

    public Long endVersion() {
        return endVersion;
    }

    public int byteCount() {
        return raw.length;
    }

    @Override
    public String toString() {
        return "RepositoryHistoryReport[bytes=" + raw.length + ", unparsed]";
    }
}
