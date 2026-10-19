package com.databridge.config;

import com.databridge.task.TaskCoordinator;
import com.databridge.task.TaskRecovery;
import com.databridge.task.TaskRepository;
import com.databridge.verify.Verifier;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RuntimeConfiguration {
    @Bean(name = "sourceDataSource", destroyMethod = "close")
    DataSource sourceDataSource(
            @Value("${databridge.source.jdbc-url}") String url,
            @Value("${databridge.source.username}") String username,
            @Value("${databridge.source.password}") String password) {
        return dataSource("databridge-source", url, username, password);
    }

    @Bean(name = "targetDataSource", destroyMethod = "close")
    @Primary
    DataSource targetDataSource(
            @Value("${databridge.target.jdbc-url}") String url,
            @Value("${databridge.target.username}") String username,
            @Value("${databridge.target.password}") String password) {
        return dataSource("databridge-target", url, username, password);
    }

    @Bean(destroyMethod = "close")
    TaskCoordinator taskCoordinator(
            @Qualifier("sourceDataSource") DataSource source,
            @Qualifier("targetDataSource") DataSource target,
            @Value("${databridge.mysql.host}") String mysqlHost,
            @Value("${databridge.mysql.port}") int mysqlPort,
            @Value("${databridge.mysql.database}") String mysqlDatabase,
            @Value("${databridge.mysql.server-id}") int serverId,
            @Value("${databridge.source.username}") String sourceUsername,
            @Value("${databridge.source.password}") String sourcePassword,
            @Value("${databridge.target.jdbc-url}") String targetUrl,
            @Value("${databridge.target.username}") String targetUsername,
            @Value("${databridge.target.password}") String targetPassword,
            @Value("${databridge.copy-batch-size}") int batchSize) {
        if (serverId <= 0 || mysqlPort <= 0 || mysqlPort > 65535) {
            throw new IllegalArgumentException("Invalid MySQL port or server ID");
        }
        var settings = new Properties();
        settings.setProperty("database.hostname", mysqlHost);
        settings.setProperty("database.port", Integer.toString(mysqlPort));
        settings.setProperty("database.user", sourceUsername);
        settings.setProperty("database.password", sourcePassword);
        settings.setProperty("database.server.id", Integer.toString(serverId));
        settings.setProperty("database.include.list", mysqlDatabase);
        settings.setProperty("offset.storage.jdbc.connection.url", targetUrl);
        settings.setProperty("offset.storage.jdbc.connection.user", targetUsername);
        settings.setProperty("offset.storage.jdbc.connection.password", targetPassword);
        settings.setProperty("schema.history.internal.jdbc.connection.url", targetUrl);
        settings.setProperty("schema.history.internal.jdbc.connection.user", targetUsername);
        settings.setProperty("schema.history.internal.jdbc.connection.password", targetPassword);
        return new TaskCoordinator(source, target, settings, batchSize);
    }

    @Bean
    TaskRepository taskRepository(
            @Qualifier("sourceDataSource") DataSource source,
            @Qualifier("targetDataSource") DataSource target) {
        return new TaskRepository(source, target);
    }

    @Bean
    TaskRecovery taskRecovery(TaskRepository repository, TaskCoordinator coordinator) {
        return new TaskRecovery(repository, coordinator);
    }

    @Bean
    Verifier verifier(
            @Qualifier("sourceDataSource") DataSource source,
            @Qualifier("targetDataSource") DataSource target) {
        return new Verifier(source, target);
    }

    private static HikariDataSource dataSource(
            String name, String url, String username, String password) {
        var dataSource = new HikariDataSource();
        dataSource.setPoolName(name);
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(5);
        return dataSource;
    }
}
