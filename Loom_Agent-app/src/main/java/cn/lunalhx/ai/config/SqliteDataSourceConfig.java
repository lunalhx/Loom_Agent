package cn.lunalhx.ai.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "loom.agent.persistence", name = "mode", havingValue = "sqlite", matchIfMissing = true)
public class SqliteDataSourceConfig {

    @Bean
    public DataSource dataSource(PersistenceProperties properties) {
        Path dataDir = Path.of(properties.getDataDir()).toAbsolutePath().normalize();
        Path database = dataDir.resolve("loom-agent.db");
        prepareDataDirectory(dataDir);

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqliteConfig.setBusyTimeout(properties.getBusyTimeoutMs());
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);

        SQLiteDataSource sqliteDataSource = new SQLiteDataSource(sqliteConfig);
        sqliteDataSource.setUrl("jdbc:sqlite:" + database);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDataSource(sqliteDataSource);
        hikariConfig.setPoolName("LoomAgent-SQLite");
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setMaximumPoolSize(properties.getMaxPoolSize());
        hikariConfig.setConnectionTimeout(properties.getBusyTimeoutMs() + 1000L);
        hikariConfig.setMaxLifetime(0);
        hikariConfig.setConnectionTestQuery("SELECT 1");

        HikariDataSource dataSource = new HikariDataSource(hikariConfig);
        restrictPermissions(database, "rw-------");
        return dataSource;
    }

    @Bean
    public FlywayMigrationStrategy sqliteFlywayMigrationStrategy(PersistenceProperties properties) {
        return flyway -> {
            Path lockFile = Path.of(properties.getDataDir()).toAbsolutePath().normalize().resolve(".migration.lock");
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                flyway.migrate();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to lock SQLite migrations: " + lockFile, e);
            }
        };
    }

    private static void prepareDataDirectory(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
            restrictPermissions(dataDir, "rwx------");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create Loom Agent data directory: " + dataDir, e);
        }
    }

    private static void restrictPermissions(Path path, String permissions) {
        try {
            if (Files.exists(path)) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }
}
