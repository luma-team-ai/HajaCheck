package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * V25 마이그레이션(#1050)이 PR머신 P1 지적(prod에 기존 중복 데이터가 있으면 UNIQUE INDEX 생성 자체가
 * 실패해 배포 시 앱 부팅이 죽는 위험, #531 계열)에 대해 스스로 방어하는지를 실제 PostgreSQL로 증명한다.
 *
 * <p>V1 baseline만 적용한 신선한 DB에 (user_id, facilityId, nextInspectionDueAt, kind)가 동일한 중복
 * notifications 행 3건 + kind가 다른 별개 행 1건을 직접 심어둔 뒤 V25를 그대로 적용한다. V25가 인덱스
 * 생성 전에 자체 DELETE로 중복을 정리하므로, 이 시나리오에서도 마이그레이션이 예외 없이 성공해야 하고
 * (기존 400일 윈도우 구현이 문서화했던 실제 회귀 케이스를 재현), 정리 후에는 그 키 조합이 정확히 1건만
 * 남아야 하며, kind가 다른 별개 행은 정리 대상이 아니므로 그대로 남아야 한다. 마지막으로 인덱스 자체가
 * 실제로 만들어졌는지도 카탈로그로 확인한다.
 *
 * <p>Flyway 전체 체인이 아니라 V1(baseline)만 적용하는 이유: 이 시나리오를 재현하는 데 필요한 스키마는
 * users/notifications/notification_type뿐이고, V1이 이미 전부 제공한다 — 중간 버전을 전부 거칠 필요가
 * 없어 이 테스트의 목적(V25 자체 방어 로직 검증)에 집중한다.
 */
class V25NotificationDedupeCleanupMigrationTest {

    private static final String CONTAINER_ROOT = "/tmp/";

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startAndSeed() throws Exception {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("db/migration/V1__baseline_schema.sql"),
                        CONTAINER_ROOT + "V1__baseline_schema.sql");
        postgres.start();
        runPsql(postgres, "V1__baseline_schema.sql");

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    insert into users (email, name, password_hash)
                    values ('v25-dedupe-cleanup@haja.test', 'V25정리테스트유저', '<password-hash-placeholder>')
                    """);
            // 같은 (user_id, facilityId=99, nextInspectionDueAt=2026-08-01, kind=OVERDUE) 키로 3건을
            // 심어 "기존 400일 윈도우 구현의 문서화된 한계로 이미 prod에 쌓였을 수 있는" 중복을 재현한다.
            for (int i = 0; i < 3; i++) {
                statement.execute("""
                        insert into notifications (user_id, type, payload_json)
                        select u.id, 'INSPECTION_DUE'::notification_type,
                               '{"facilityId":99,"facilityName":"중복시설","nextInspectionDueAt":"2026-08-01","kind":"OVERDUE"}'::jsonb
                        from users u where u.email = 'v25-dedupe-cleanup@haja.test'
                        """);
            }
            // 키가 다른(kind=DUE) 별개 행 — 정리 대상이 아니므로 그대로 살아남아야 한다.
            statement.execute("""
                    insert into notifications (user_id, type, payload_json)
                    select u.id, 'INSPECTION_DUE'::notification_type,
                           '{"facilityId":99,"facilityName":"중복시설","nextInspectionDueAt":"2026-08-01","kind":"DUE"}'::jsonb
                    from users u where u.email = 'v25-dedupe-cleanup@haja.test'
                    """);
        }
    }

    @AfterAll
    static void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void 기존_중복데이터가_있어도_V25가_정리후_인덱스_생성에_성공한다() throws Exception {
        postgres.copyFileToContainer(
                MountableFile.forClasspathResource(
                        "db/migration/V25__inspection_due_notification_dedupe_unique_index.sql"),
                CONTAINER_ROOT + "V25__inspection_due_notification_dedupe_unique_index.sql");

        // 예외 없이 성공해야 한다 — 실패하면 runPsql이 여기서 바로 IllegalStateException을 던진다.
        runPsql(postgres, "V25__inspection_due_notification_dedupe_unique_index.sql");

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            ResultSet overdueRows = statement.executeQuery("""
                    select count(*) as c from notifications
                    where payload_json ->> 'kind' = 'OVERDUE' and (payload_json ->> 'facilityId')::bigint = 99
                    """);
            overdueRows.next();
            assertThat(overdueRows.getInt("c"))
                    .as("같은 키의 OVERDUE 중복 3건이 정리 후 정확히 1건만 남아야 한다")
                    .isEqualTo(1);

            ResultSet dueRows = statement.executeQuery("""
                    select count(*) as c from notifications
                    where payload_json ->> 'kind' = 'DUE' and (payload_json ->> 'facilityId')::bigint = 99
                    """);
            dueRows.next();
            assertThat(dueRows.getInt("c"))
                    .as("kind가 다른(DUE) 별개 행은 정리 대상이 아니므로 그대로 남아있어야 한다")
                    .isEqualTo(1);

            ResultSet indexCheck = statement.executeQuery("""
                    select count(*) as c from pg_indexes
                    where indexname = 'uq_notifications_inspection_due_dedupe'
                    """);
            indexCheck.next();
            assertThat(indexCheck.getInt("c"))
                    .as("정리 후 유니크 인덱스가 실제로 생성돼 있어야 한다")
                    .isEqualTo(1);
        }
    }

    private static void runPsql(PostgreSQLContainer<?> container, String fileName) throws Exception {
        Container.ExecResult result = container.execInContainer(
                "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", container.getUsername(),
                "--dbname", container.getDatabaseName(),
                "--file", CONTAINER_ROOT + fileName);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(
                    "psql failed for %s (exit=%d)%nstdout:%n%s%nstderr:%n%s".formatted(
                            fileName, result.getExitCode(), result.getStdout(), result.getStderr()));
        }
    }
}
