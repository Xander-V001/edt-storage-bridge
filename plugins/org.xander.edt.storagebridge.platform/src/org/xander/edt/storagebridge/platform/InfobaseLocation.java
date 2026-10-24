package org.xander.edt.storagebridge.platform;

import java.util.Objects;

/** Address of an existing 1C infobase; this class never binds it to a storage. */
public record InfobaseLocation(Kind kind, String address) {
    public enum Kind { FILE, SERVER }

    public InfobaseLocation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(address, "address");
        address = address.trim();
        if (address.isBlank() || address.indexOf('\n') >= 0 || address.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Infobase address is missing or malformed");
        }
    }

    /** Documented 1C command-line switch. */
    public String switchName() {
        return kind == Kind.FILE ? "/F" : "/S";
    }

    @Override
    public String toString() {
        return "InfobaseLocation[" + kind + ", address=REDACTED]";
    }
}
