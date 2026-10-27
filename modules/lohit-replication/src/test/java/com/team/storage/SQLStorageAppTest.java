package com.team.storage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SQLStorageAppTest {
    @Test void rejectsBlankNodeId() {
        SQLStorageApp app = new SQLStorageApp("jdbc:mysql://localhost:3306/distributed_storage", "root", "");
        assertThrows(IllegalArgumentException.class, () -> app.addNode(" ", "north", 100));
    }
    @Test void rejectsNonPositiveCapacity() {
        SQLStorageApp app = new SQLStorageApp("jdbc:mysql://localhost:3306/distributed_storage", "root", "");
        assertThrows(IllegalArgumentException.class, () -> app.addNode("node-1", "north", 0));
    }
}
