package com.databridge.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TaskCoordinatorSettingsTest {
    @Test
    void topicPrefixIsStablePerTaskAndDifferentBetweenTasks() throws Exception {
        var database = new DriverManagerDataSource();
        var settings = new Properties();
        settings.setProperty("topic.prefix", "databridge");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (var coordinator = new TaskCoordinator(database, database, settings, 10)) {
            assertEquals(coordinator.settingsFor(first).getProperty("topic.prefix"),
                    coordinator.settingsFor(first).getProperty("topic.prefix"));
            assertNotEquals(coordinator.settingsFor(first).getProperty("topic.prefix"),
                    coordinator.settingsFor(second).getProperty("topic.prefix"));
            var changedSettings = new Properties();
            changedSettings.setProperty("topic.prefix", "different_after_restart");
            try (var restarted = new TaskCoordinator(database, database, changedSettings, 10)) {
                assertEquals(coordinator.settingsFor(first).getProperty("topic.prefix"),
                        restarted.settingsFor(first).getProperty("topic.prefix"),
                        "changing deployment config must not silently reset an active CDC offset");
            }
        }
    }
}
