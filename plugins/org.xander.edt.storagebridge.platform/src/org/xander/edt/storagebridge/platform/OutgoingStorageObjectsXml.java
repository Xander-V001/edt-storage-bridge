package org.xander.edt.storagebridge.platform;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Official classic 1C repository -Objects XML format (NOT -listFile!).
 * Produces an explicit, nonempty object selection. The default serializer
 * is NON-recursive for module-only writes; the full-object serializer
 * explicitly includes subordinate objects.
 *
 * This is a pure preview serializer. No Designer process, object capture
 * or repository commit is launched here. A separate validated mapping
 * from EDT source to classic metadata FQN is still required before use.
 */
public final class OutgoingStorageObjectsXml {
    private static final int MAX_OBJECTS = 100_000;
    private static final String XML_NS =
        "http://v8.1c.ru/8.3/config/objects";

    private OutgoingStorageObjectsXml() {}

    public static String create(Set<String> explicitClassicNames) {
        return createInternal(explicitClassicNames, false, false);
    }

    /** For full-object operations that MUST include its subordinate objects. */
    public static String createWithSubordinates(Set<String> explicitClassicNames) {
        return createInternal(explicitClassicNames, true, false);
    }

    /**
     * Configuration is a distinct XML element in the official 1C object-list
     * schema. A root capture including children is used for removal workflows.
     */
    public static String createWithRoot(Set<String> names, boolean rootChildren) {
        return createInternal(names, true, rootChildren);
    }

    private static String createInternal(Set<String> explicitClassicNames,
            boolean includeSubordinates, boolean rootChildren) {
        Objects.requireNonNull(explicitClassicNames);
        if (explicitClassicNames.isEmpty()
                || explicitClassicNames.size() > MAX_OBJECTS) {
            throw new IllegalArgumentException("Explicit object selection required");
        }
        var objects = new LinkedHashSet<String>();
        for (String name : explicitClassicNames) {
            if (name == null || name.length() > 1024
                    || !name.matches(
                        "[\\p{L}_][\\p{L}\\p{N}_]*"
                        + "(\\.[\\p{L}_][\\p{L}\\p{N}_]*)+")) {
                throw new IllegalArgumentException("Invalid classic metadata FQN");
            }
            if (name.equals("Configuration")) {
                objects.add(name);
                continue;
            }
            if (name.startsWith("Configuration.")) {
                throw new IllegalArgumentException("Use Configuration root selector");
            }
            objects.add(name);
        }
        var xml = new StringBuilder(160 + objects.size() * 94);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<Objects xmlns=\"").append(XML_NS)
            .append("\" version=\"1.0\">\n");
        for (String name : objects) {
            if (name.equals("Configuration")) {
                xml.append("  <Configuration includeChildObjects=\"")
                    .append(rootChildren ? "true" : "false")
                    .append("\"/>\n");
                continue;
            }
            // XML escaping is unnecessary by construction: the strict
            // metadata FQN alphabet contains no XML metacharacters.
            xml.append("  <Object fullName=\"").append(name)
                .append("\" includeChildObjects=\"")
                .append(includeSubordinates ? "true" : "false")
                .append("\"/>\n");
        }
        return xml.append("</Objects>\n").toString();
    }
}
