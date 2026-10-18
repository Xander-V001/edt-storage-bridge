package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.*;
import java.nio.file.Path;
import org.junit.Test;
import org.xander.edt.storagebridge.core.EdtPathObjectMapper;

public class EdtPathObjectMapperTest {
    @Test
    public void documentModulesAndFormRemainSameMetadataObject() {
        assertEquals("Documents/Документ1",
            EdtPathObjectMapper.topObject(Path.of(
                "src/Documents/Документ1/Forms/ФормаДокумента/Module.bsl")));
        assertEquals("Documents/Документ1",
            EdtPathObjectMapper.topObject(Path.of(
                "src/Documents/Документ1/Документ1.mdo")));
        assertEquals("Documents/Документ1",
            EdtPathObjectMapper.topObject(Path.of(
                "src/Documents/Документ1.mdo")));
    }

    @Test
    public void otherObjectsAndConfigurationAreMappedConservatively() {
        assertEquals("Catalogs/Товары",
            EdtPathObjectMapper.topObject(Path.of(
                "src/Catalogs/Товары/Forms/ФормаЭлемента/Form.form")));
        assertEquals("CommonModules/Тест",
            EdtPathObjectMapper.topObject(Path.of(
                "src/CommonModules/Тест/Module.bsl")));
        assertEquals("Configuration/Configuration",
            EdtPathObjectMapper.topObject(Path.of(
                "src/Configuration/Configuration.mdo")));
    }

    @Test
    public void unknownAndOutsidePathsAreNotMisclassified() {
        assertNull(EdtPathObjectMapper.topObject(Path.of(".project")));
        assertNull(EdtPathObjectMapper.topObject(Path.of("DT-INF/PROJECT.PMF")));
        assertNull(EdtPathObjectMapper.topObject(Path.of("../other/src/Configuration")));
        assertNull(EdtPathObjectMapper.topObject(Path.of("src/Documents")));
    }
}
