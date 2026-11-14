package org.xander.edt.storagebridge.platform;

import java.nio.file.Path;
import java.time.Duration;

/** Common validated transport data; never selects export mode implicitly. */
interface XmlDumpConnection {
    Path executable();
    InfobaseLocation infobase();
    Duration timeout();
    String infobaseUsername();
    String infobasePassword();
}
