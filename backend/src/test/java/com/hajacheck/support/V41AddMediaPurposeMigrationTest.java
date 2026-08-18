package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V41(media.purpose 컬럼 추가 + 조치 후 사진 백필, #1641)의 컬럼/제약/백필 <b>데이터 전이 범위</b>를
 * 격리 검증한다.
 *
 * <p>V40까지 마이그레이션한 뒤 세 종류의 미디어(참조 없음 / defects.action_media_id가 가리킴 /
 * defect_action_logs.media_id에만 남아있음)를 심고 V41만 추가 적용해, 백필 UPDATE의 대상 범위가
 * 정확히 "조치 후 사진으로 실제 연결된 media"로만 좁혀지는지 고정한다.
 *
 * <p>빈 DB(신규 설치) 경로는 {@code FlywayBaselineIntegrationTest}가, 캐노니컬 DDL 기존 DB 경로는
 * {@code FlywayBaselineOnExistingDbIntegrationTest}가 각각 V41 포함 전량 적용을 이미 검증한다(양쪽 모두
 * media 0행이라 V41의 백필 UPDATE는 no-op으로 통과해야 한다 — 그 사실 자체도 여기서 함께 고정한다).
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class V41AddMediaPurposeMigrationTest {

    private static final AtomicLong BRN_SEQ = new AtomicLong(1);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v41_media_purpose")
            .withUsername("postgres");

    @BeforeEach
    void resetDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
    }

    @Test
    void 컬럼추가_기본값은_INSPECTION_SOURCE이고_참조없는미디어는_그대로_남는다() {
        migrateTo("40");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v41-unreferenced");
        long media = insertMedia(jdbc, fx.inspectionId(), "v41/unreferenced.png");

        migrateTo("41");

        assertThat(purposeOf(jdbc, media)).isEqualTo("INSPECTION_SOURCE");
    }

    @Test
    void defects_action_media_id가_가리키는_미디어는_DEFECT_ACTION으로_전환된다() {
        migrateTo("40");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v41-action-media");
        long sourceMedia = insertMedia(jdbc, fx.inspectionId(), "v41/source.png");
        long actionMedia = insertMedia(jdbc, fx.inspectionId(), "v41/action.png");
        long defectId = insertDefect(jdbc, fx.inspectionId());
        jdbc.update("update defects set action_media_id = ? where id = ?", actionMedia, defectId);

        migrateTo("41");

        // 하자와 무관한 원본 촬영 사진은 그대로 두고, action_media_id가 실제로 가리키는 사진만 전환한다.
        assertThat(purposeOf(jdbc, sourceMedia)).isEqualTo("INSPECTION_SOURCE");
        assertThat(purposeOf(jdbc, actionMedia)).isEqualTo("DEFECT_ACTION");
    }

    @Test
    void defect_action_logs이력에만남은_이전제출사진도_DEFECT_ACTION으로_전환된다() {
        // defects.action_media_id(V12)는 "최신 스냅샷"만 담는 단일 값 컬럼이라, 조치중(IN_PROGRESS)
        // 단계에서 여러 번 제출하면 이전 제출 사진은 action_media_id에서 밀려나고 defect_action_logs
        // (V32) 이력에만 남는다. 그 사진도 여전히 조치 후 사진이므로 DEFECT_ACTION으로 전환돼야 한다.
        migrateTo("40");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v41-action-log");
        long firstSubmission = insertMedia(jdbc, fx.inspectionId(), "v41/first-submission.png");
        long latestSubmission = insertMedia(jdbc, fx.inspectionId(), "v41/latest-submission.png");
        long defectId = insertDefect(jdbc, fx.inspectionId());
        // 최신 스냅샷은 두 번째 제출로 덮어써진 상태 — 첫 제출은 이제 로그에만 남아 있다.
        jdbc.update("update defects set action_media_id = ? where id = ?", latestSubmission, defectId);
        insertActionLog(jdbc, defectId, firstSubmission, fx.ownerId(), "1차 조치");
        insertActionLog(jdbc, defectId, latestSubmission, fx.ownerId(), "2차 조치");

        migrateTo("41");

        assertThat(purposeOf(jdbc, firstSubmission)).isEqualTo("DEFECT_ACTION");
        assertThat(purposeOf(jdbc, latestSubmission)).isEqualTo("DEFECT_ACTION");
    }

    @Test
    void CHECK제약은_허용된_두값외에는_거부한다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v41-check-constraint");

        assertThatThrownBy(() -> jdbc.update("""
                insert into media (inspection_id, file_type, original_url, mime_signature_verified, purpose)
                values (?, 'IMAGE'::media_file_type, 'v41/invalid.png', true, 'NOT_A_REAL_PURPOSE')
                """, fx.inspectionId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void V41은_멱등하다_재실행해도_기존행은_불변이고_대조행은_전환된다() {
        migrateTo("40");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v41-idempotent");
        long actionMedia = insertMedia(jdbc, fx.inspectionId(), "v41/idempotent-action.png");
        long defectId = insertDefect(jdbc, fx.inspectionId());
        jdbc.update("update defects set action_media_id = ? where id = ?", actionMedia, defectId);

        migrateTo("41");
        assertThat(purposeOf(jdbc, actionMedia)).isEqualTo("DEFECT_ACTION");

        // 재실행 이후 새로 심는 대조행 — 재실행이 실제로 DB에 닿았다는 것을 증명하기 위한 픽스처
        // (Flyway는 한 번만 실행하지만, prod baseline 스탬프 사고 이력이 있어 재실행 안전성을 직접 확인한다).
        long lateActionMedia = insertMedia(jdbc, fx.inspectionId(), "v41/late-action.png");
        long lateDefectId = insertDefect(jdbc, fx.inspectionId());
        jdbc.update("update defects set action_media_id = ? where id = ?", lateActionMedia, lateDefectId);

        jdbc.execute(migrationSql());

        assertThat(purposeOf(jdbc, actionMedia)).isEqualTo("DEFECT_ACTION"); // 기존 행 불변.
        assertThat(purposeOf(jdbc, lateActionMedia)).isEqualTo("DEFECT_ACTION"); // 재실행이 실제로 반영됨.
    }

    @Test
    void 미디어가없는DB에서는_V41이_컬럼만_추가하고_백필은_no_op이다() {
        migrateTo("40");
        JdbcTemplate jdbc = jdbc();
        assertThat(jdbc.queryForObject("select count(*) from media", Integer.class)).isZero();

        migrateTo("41");

        assertThat(jdbc.queryForObject("select count(*) from media", Integer.class)).isZero();
        Integer purposeColumnExists = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_name = 'media' and column_name = 'purpose'
                """, Integer.class);
        assertThat(purposeColumnExists).isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Fixture(long ownerId, long companyId, long facilityId, long inspectionId) {
    }

    /** users(오너=담당 검사자 겸용) → companies(APPROVED+VERIFIED) → membership → facilities → inspections. */
    private Fixture seedInspectionWithOwner(JdbcTemplate jdbc, String emailPrefix) {
        long owner = insertUser(jdbc, emailPrefix + "-owner@haja.test");
        long company = insertCompany(jdbc, owner, emailPrefix + " 회사");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);
        jdbc.update("""
                insert into company_memberships (company_id, user_id, status, approved_at)
                values (?, ?, 'APPROVED'::company_membership_status_type, now())
                """, company, owner);
        long facility = jdbc.queryForObject(
                "insert into facilities (company_id, name, type) values (?, ?, 'BUILDING') returning id",
                Long.class, company, emailPrefix + " 빌딩");
        long inspection = jdbc.queryForObject("""
                insert into inspections (facility_id, created_by, assigned_inspector_id, round_no, inspection_date)
                values (?, ?, ?, 1, current_date) returning id
                """, Long.class, facility, owner, owner);
        return new Fixture(owner, company, facility, inspection);
    }

    private long insertUser(JdbcTemplate jdbc, String email) {
        return jdbc.queryForObject("""
                insert into users (email, name, role, password_hash, status)
                values (?, 'V41 사용자', 'INSPECTOR'::role_type, 'test-password-hash', 'ACTIVE'::user_status_type)
                returning id
                """, Long.class, email);
    }

    private long insertCompany(JdbcTemplate jdbc, long ownerUserId, String name) {
        String brn = "V41" + BRN_SEQ.getAndIncrement();
        return jdbc.queryForObject("""
                insert into companies (owner_user_id, name, business_registration_number,
                                       representative_name, address, business_registration_file_url,
                                       status, verification_status)
                values (?, ?, ?, '대표자', '서울시', '/test/v41.png',
                        'APPROVED'::company_status_type, 'VERIFIED'::business_verification_status_type)
                returning id
                """, Long.class, ownerUserId, name, brn);
    }

    private long insertMedia(JdbcTemplate jdbc, long inspectionId, String originalUrl) {
        return jdbc.queryForObject("""
                insert into media (inspection_id, file_type, original_url, mime_signature_verified)
                values (?, 'IMAGE'::media_file_type, ?, true)
                returning id
                """, Long.class, inspectionId, originalUrl);
    }

    private long insertDefect(JdbcTemplate jdbc, long inspectionId) {
        return jdbc.queryForObject("""
                insert into defects (inspection_id, type, confidence)
                values (?, 'CRACK'::defect_type, 0.9)
                returning id
                """, Long.class, inspectionId);
    }

    private void insertActionLog(JdbcTemplate jdbc, long defectId, long mediaId, long assigneeId, String content) {
        jdbc.update("""
                insert into defect_action_logs (defect_id, media_id, phase, action_content, action_date,
                                                action_assignee_id)
                values (?, ?, 'IN_PROGRESS'::defect_action_log_phase_type, ?, current_date, ?)
                """, defectId, mediaId, content, assigneeId);
    }

    private String purposeOf(JdbcTemplate jdbc, long mediaId) {
        return jdbc.queryForObject("select purpose from media where id = ?", String.class, mediaId);
    }

    private String migrationSql() {
        try (var in = new org.springframework.core.io.ClassPathResource(
                "db/migration/V41__add_media_purpose.sql").getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(
                    "V41 마이그레이션 리소스를 읽지 못했다", e);
        }
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(target)
                .load()
                .migrate();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}
