package org.xander.edt.storagebridge.core.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;
import org.xander.edt.storagebridge.core.ProjectSnapshot;

public class ProjectSnapshotTest {

    @Test
    public void acceptsProjectMetadata() {
        ProjectSnapshot snapshot = new ProjectSnapshot("Salary", "C:/EDT/Salary");
        assertEquals("Salary", snapshot.name());
        assertEquals("C:/EDT/Salary", snapshot.location());
    }

    @Test
    public void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
            () -> new ProjectSnapshot(" ", "C:/EDT/Salary"));
    }

    @Test
    public void rejectsBlankLocation() {
        assertThrows(IllegalArgumentException.class,
            () -> new ProjectSnapshot("Salary", ""));
    }
}
