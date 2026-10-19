package com.databridge.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaskApiIT {
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("databridge_source")
            .withCommand("--server-id=5420", "--log-bin=mysql-bin",
                    "--binlog-format=ROW", "--binlog-row-image=FULL");
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.11");
    static boolean prepared;

    @DynamicPropertySource
    static synchronized void properties(DynamicPropertyRegistry registry) throws Exception {
        if (!prepared) {
            mysql.start();
            postgres.start();
            try (var source = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
                 var statement = source.createStatement()) {
                statement.execute("CREATE TABLE api_items (id BIGINT PRIMARY KEY, name VARCHAR(100))");
                statement.execute("CREATE TABLE sync_probe (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                        + "marker CHAR(36) NOT NULL UNIQUE)");
                statement.execute("CREATE USER 'capture'@'%' IDENTIFIED BY 'capturepass'");
                statement.execute("GRANT SELECT ON databridge_source.* TO 'capture'@'%'");
                statement.execute("GRANT INSERT ON databridge_source.sync_probe TO 'capture'@'%'");
                statement.execute("GRANT RELOAD, SHOW DATABASES, REPLICATION SLAVE, "
                        + "REPLICATION CLIENT ON *.* TO 'capture'@'%'");
                statement.execute("INSERT INTO api_items(id, name) VALUES (1, 'initial')");
            }
            try (var target = DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                 var statement = target.createStatement()) {
                statement.execute("CREATE SCHEMA business");
                statement.execute("CREATE TABLE business.api_items "
                        + "(id BIGINT PRIMARY KEY, name VARCHAR(100))");
            }
            prepared = true;
        }
        registry.add("databridge.source.jdbc-url", mysql::getJdbcUrl);
        registry.add("databridge.source.username", () -> "capture");
        registry.add("databridge.source.password", () -> "capturepass");
        registry.add("databridge.target.jdbc-url", postgres::getJdbcUrl);
        registry.add("databridge.target.username", postgres::getUsername);
        registry.add("databridge.target.password", postgres::getPassword);
        registry.add("databridge.mysql.host", mysql::getHost);
        registry.add("databridge.mysql.port", () -> mysql.getMappedPort(3306));
        registry.add("databridge.mysql.database", () -> "databridge_source");
        registry.add("databridge.mysql.server-id", () -> 5421);
        registry.add("databridge.mysql.topic-prefix", () -> "databridge_api");
        registry.add("databridge.copy-batch-size", () -> 10);
    }

    @Autowired TestRestTemplate http;
    @Autowired TaskRepository repository;
    @Autowired TaskRecovery recovery;

    @Test
    void bootMigratesMetadataAndHttpControlsDurableTaskWithoutExposingSecrets() throws Exception {
        String root = "/api/tasks";
        ResponseEntity<JsonNode> created = http.postForEntity(root, Map.of(
                "sourceSchema", "databridge_source", "sourceTable", "api_items",
                "targetSchema", "business", "targetTable", "api_items"), JsonNode.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        UUID taskId = UUID.fromString(created.getBody().path("taskId").asText());
        String taskUrl = root + "/" + taskId;
        assertEquals("CREATED", get(taskUrl).path("status").asText());
        assertFalse(created.getBody().toString().contains("capturepass"));
        assertFalse(created.getBody().toString().contains(postgres.getPassword()));

        ResponseEntity<JsonNode> duplicate = http.postForEntity(root, Map.of(
                "sourceSchema", "databridge_source", "sourceTable", "api_items",
                "targetSchema", "business", "targetTable", "api_items"), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
                http.getForEntity(root + "/" + UUID.randomUUID(), JsonNode.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
                http.getForEntity(root + "/not-a-uuid", JsonNode.class).getStatusCode());

        assertEquals(HttpStatus.ACCEPTED,
                http.postForEntity(taskUrl + "/start", null, JsonNode.class).getStatusCode());
        awaitStatus(taskUrl, "STREAMING", Duration.ofSeconds(60));
        awaitTargetName(1, "initial", Duration.ofSeconds(15));

        String verificationUrl = taskUrl + "/verifications";
        ResponseEntity<JsonNode> verified = http.postForEntity(verificationUrl, null, JsonNode.class);
        assertEquals(HttpStatus.CREATED, verified.getStatusCode());
        assertEquals("CONSISTENT", verified.getBody().path("status").asText());
        assertEquals(1, verified.getBody().path("sourceRows").asLong());
        String runId = verified.getBody().path("runId").asText();
        assertEquals(verified.getBody(), get(verificationUrl + "/" + runId));
        assertEquals(HttpStatus.NOT_FOUND, http.getForEntity(
                verificationUrl + "/" + UUID.randomUUID(), JsonNode.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, http.getForEntity(
                verificationUrl + "/invalid-uuid", JsonNode.class).getStatusCode());
        assertFalse(verified.getBody().toString().contains("initial"));
        assertFalse(verified.getBody().toString().contains("capturepass"));

        assertEquals(HttpStatus.OK,
                http.postForEntity(taskUrl + "/pause", null, JsonNode.class).getStatusCode());
        awaitStatus(taskUrl, "PAUSED", Duration.ofSeconds(5));
        try (var source = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
             var statement = source.createStatement()) {
            statement.execute("UPDATE api_items SET name = 'changed' WHERE id = 1");
        }
        assertEquals(HttpStatus.OK,
                http.postForEntity(taskUrl + "/resume", null, JsonNode.class).getStatusCode());
        awaitTargetName(1, "changed", Duration.ofSeconds(20));
        assertEquals(HttpStatus.OK,
                http.postForEntity(taskUrl + "/stop", null, JsonNode.class).getStatusCode());
        assertEquals("STOPPED", get(taskUrl).path("status").asText());
        assertEquals(HttpStatus.CONFLICT,
                http.postForEntity(verificationUrl, null, JsonNode.class).getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                http.postForEntity(taskUrl + "/resume", null, JsonNode.class).getStatusCode());
        assertTrue(repository.recoverableTaskIds().isEmpty(), "Stopped tasks stay stopped on boot");

        // Simulate an unclean process exit after the last durable stage was STREAMING.
        try (var target = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             var update = target.prepareStatement("""
                     UPDATE databridge_meta.sync_task SET status = 'STREAMING' WHERE task_id = ?
                     """)) {
            update.setObject(1, taskId);
            assertEquals(1, update.executeUpdate());
        }
        assertEquals(1, repository.recoverableTaskIds().size());
        recovery.run(null);
        awaitStatus(taskUrl, "STREAMING", Duration.ofSeconds(45));
        try (var source = DriverManager.getConnection(mysql.getJdbcUrl(), "root", "test");
             var statement = source.createStatement()) {
            statement.execute("UPDATE api_items SET name = 'after-recovery' WHERE id = 1");
        }
        awaitTargetName(1, "after-recovery", Duration.ofSeconds(20));
        assertEquals(HttpStatus.OK,
                http.postForEntity(taskUrl + "/stop", null, JsonNode.class).getStatusCode());
    }

    private JsonNode get(String url) {
        ResponseEntity<JsonNode> response = http.getForEntity(url, JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private void awaitStatus(String url, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String actual = get(url).path("status").asText();
            if (expected.equals(actual)) {
                return;
            }
            assertFalse("FAILED".equals(actual), "Task failed: " + get(url));
            Thread.sleep(100);
        }
        throw new AssertionError("Task did not reach " + expected + ": " + get(url));
    }

    private void awaitTargetName(long id, String expected, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (var target = DriverManager.getConnection(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword());
                 var query = target.prepareStatement("SELECT name FROM business.api_items WHERE id = ?")) {
                query.setLong(1, id);
                try (var result = query.executeQuery()) {
                    if (result.next() && expected.equals(result.getString(1))) {
                        return;
                    }
                }
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Target row did not become " + expected);
    }
}
