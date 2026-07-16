package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 운영 증분 경로(v0.3 → HAJA-25)를 실제 psql autocommit으로 적용한 뒤 JPA 전체 스키마를 validate한다.
 * CREATE INDEX CONCURRENTLY를 포함하므로 JDBC 트랜잭션 기반 SQL 실행기로 대체하지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class Ha25IncrementalMigrationTest {

    private static final String MIGRATION_ROOT = "db/migrations/";
    private static final String CONTAINER_ROOT = "/tmp/";

    private static final String EXTERNAL_URL = System.getenv("HA25_MIGRATION_POSTGRES_URL");
    private static final String EXTERNAL_USERNAME = System.getenv("HA25_MIGRATION_POSTGRES_USERNAME");
    private static final String EXTERNAL_PASSWORD = System.getenv("HA25_MIGRATION_POSTGRES_PASSWORD");

    private static final PostgreSQLContainer<?> POSTGRES = useExternalDatabase()
            ? null
            : createMigratedContainer();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> POSTGRES == null ? EXTERNAL_URL : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username",
                () -> POSTGRES == null ? EXTERNAL_USERNAME : POSTGRES.getUsername());
        registry.add("spring.datasource.password",
                () -> POSTGRES == null ? EXTERNAL_PASSWORD : POSTGRES.getPassword());
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Test
    void v03PlusHa25Migration_matchesAllJpaMappings() {
        // ApplicationContext 시작 시 Hibernate validate가 전체 Entity를 대조한다.
        assertThat(POSTGRES == null || POSTGRES.isRunning()).isTrue();
    }

    private static boolean useExternalDatabase() {
        return EXTERNAL_URL != null && !EXTERNAL_URL.isBlank();
    }

    private static PostgreSQLContainer<?> createMigratedContainer() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
                .withDatabaseName("hajacheck")
                .withUsername("postgres")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + "HajaCheck_script_v0.3.sql"),
                        CONTAINER_ROOT + "HajaCheck_script_v0.3.sql")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + "20260716_01_ha25_expand.sql"),
                        CONTAINER_ROOT + "20260716_01_ha25_expand.sql")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + "20260716_02_ha25_finalize.sql"),
                        CONTAINER_ROOT + "20260716_02_ha25_finalize.sql")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + "20260716_03_ha25_verify.sql"),
                        CONTAINER_ROOT + "20260716_03_ha25_verify.sql");
        postgres.start();

        runPsql(postgres, "HajaCheck_script_v0.3.sql");
        runPsql(postgres, "20260716_01_ha25_expand.sql");
        runPsql(postgres, "20260716_01_ha25_expand.sql");
        runPsql(postgres, "20260716_02_ha25_finalize.sql");
        runPsql(postgres, "20260716_02_ha25_finalize.sql");
        runPsql(postgres, "20260716_03_ha25_verify.sql");
        return postgres;
    }

    private static void runPsql(PostgreSQLContainer<?> postgres, String fileName) {
        try {
            Container.ExecResult result = postgres.execInContainer(
                    "psql",
                    "-X",
                    "--set", "ON_ERROR_STOP=1",
                    "--username", postgres.getUsername(),
                    "--dbname", postgres.getDatabaseName(),
                    "--file", CONTAINER_ROOT + fileName);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(
                        "psql failed for %s (exit=%d)%nstdout:%n%s%nstderr:%n%s"
                                .formatted(fileName, result.getExitCode(),
                                        result.getStdout(), result.getStderr()));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot execute psql for " + fileName, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing psql for " + fileName, exception);
        }
    }
}
