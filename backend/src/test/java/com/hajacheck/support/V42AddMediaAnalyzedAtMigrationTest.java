package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V42(media.analyzed_at 컬럼 추가 + 증분 분석 대상 백필, #1654)의 컬럼/백필 <b>데이터 전이 경계</b>를
 * 격리 검증한다.
 *
 * <p>이 이슈의 핵심 회귀는 "분석 완료 후 추가 업로드된 사진이 영구 미분석으로 남는" 것이었다 — 백필
 * UPDATE가 그 경계(그 회차의 마지막 하자 생성 시각 이후 업로드분은 analyzed_at을 null로 남겨야 함)를
 * 정확히 지키는지가 이 테스트의 핵심이다. V41AddMediaPurposeMigrationTest와 동일한 패턴(V41까지
 * 마이그레이션 → 픽스처 심기 → V42만 추가 적용)을 따른다.
 *
 * <p>빈 DB(신규 설치) 경로는 {@code FlywayBaselineIntegrationTest}가, 캐노니컬 DDL 기존 DB 경로는
 * {@code FlywayBaselineOnExistingDbIntegrationTest}가 각각 V42 포함 전량 적용을 이미 검증한다(양쪽 모두
 * media 0행이라 V42의 백필 UPDATE는 no-op으로 통과해야 한다).
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class V42AddMediaAnalyzedAtMigrationTest {

    private static final AtomicLong BRN_SEQ = new AtomicLong(1);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v42_media_analyzed_at")
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
    void 컬럼추가_기본값은_null이다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspection(jdbc, "v42-column", "UPLOADING");
        long media = insertMedia(jdbc, fx.inspectionId(), "v42/column.png");

        migrateTo("42");

        assertThat(analyzedAtOf(jdbc, media)).isNull();
        Integer columnExists = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_name = 'media' and column_name = 'analyzed_at'
                """, Integer.class);
        assertThat(columnExists).isEqualTo(1);
    }

    @Test
    void ANALYZED회차의_하자보유미디어는_analyzedAt이created_at으로백필된다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspection(jdbc, "v42-with-defect", "ANALYZED");
        long media = insertMedia(jdbc, fx.inspectionId(), "v42/with-defect.png");
        insertDefect(jdbc, fx.inspectionId(), media, LocalDateTime.of(2026, 8, 1, 10, 0));

        migrateTo("42");

        assertThat(analyzedAtOf(jdbc, media)).isNotNull();
    }

    @Test
    void ANALYZED회차의_하자없는미디어도_마지막하자생성시각이전업로드면_백필된다() {
        // AI가 하자를 못 찾은 사진(그 회차 정상 분석 대상이었음)도 분석됐다고 봐야 한다 — 판정 기준은
        // "그 회차의 마지막 하자 생성 시각 이전 업로드".
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspection(jdbc, "v42-clean-before", "ANALYZED");
        long cleanMedia = insertMedia(jdbc, fx.inspectionId(), "v42/clean-before.png");
        setMediaCreatedAt(jdbc, cleanMedia, LocalDateTime.of(2026, 8, 1, 9, 0));
        long defectMedia = insertMedia(jdbc, fx.inspectionId(), "v42/defect.png");
        insertDefect(jdbc, fx.inspectionId(), defectMedia, LocalDateTime.of(2026, 8, 1, 10, 0));

        migrateTo("42");

        assertThat(analyzedAtOf(jdbc, cleanMedia)).isNotNull();
    }

    @Test
    void 마지막하자생성시각이후에업로드된사진은_analyzedAt이null로남는다_핵심회귀경계() {
        // #1654 핵심 — 분석 완료 후 추가 업로드된 원본 사진은 영구 미분석으로 남아선 안 된다(운영
        // 실사례 m473/m474(insp44) 등). 백필은 이런 사진을 "이미 분석됨"으로 잘못 표시하면 안 되고,
        // null로 남겨 증분 분석 대상이 되게 해야 한다.
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspection(jdbc, "v42-after-last-defect", "ANALYZED");
        long defectMedia = insertMedia(jdbc, fx.inspectionId(), "v42/defect.png");
        insertDefect(jdbc, fx.inspectionId(), defectMedia, LocalDateTime.of(2026, 8, 1, 10, 0));
        long uploadedAfter = insertMedia(jdbc, fx.inspectionId(), "v42/uploaded-after.png");
        setMediaCreatedAt(jdbc, uploadedAfter, LocalDateTime.of(2026, 8, 2, 9, 0)); // 마지막 하자 이후.

        migrateTo("42");

        assertThat(analyzedAtOf(jdbc, defectMedia)).isNotNull();
        assertThat(analyzedAtOf(jdbc, uploadedAfter)).isNull(); // 증분 분석 대상으로 남아야 한다.
    }

    @Test
    void 하자가하나도없는ANALYZED회차는_전체미디어가null로남는다() {
        // 분석이 0건을 찾은 회차 — "마지막 하자 생성 시각" 자체가 없으므로 어떤 조건도 만족 못 하고
        // 전량 null로 남는다. 이 경우도 결과적으로 안전하다: 다음 트리거 시 전체가 "미분석"으로 잡혀
        // 증분 경로가 사실상 최초 분석과 동일하게 동작한다(worker의 append-only는 기존 하자가 없으므로 무해).
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspection(jdbc, "v42-zero-defect", "ANALYZED");
        long media = insertMedia(jdbc, fx.inspectionId(), "v42/zero-defect.png");

        migrateTo("42");

        assertThat(analyzedAtOf(jdbc, media)).isNull();
    }

    @Test
    void REVIEWED와REPORTED회차도_ANALYZED와동일하게백필대상이다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture reviewed = seedInspection(jdbc, "v42-reviewed", "REVIEWED");
        long reviewedMedia = insertMedia(jdbc, reviewed.inspectionId(), "v42/reviewed.png");
        insertDefect(jdbc, reviewed.inspectionId(), reviewedMedia, LocalDateTime.of(2026, 8, 1, 10, 0));

        Fixture reported = seedInspection(jdbc, "v42-reported", "REPORTED");
        long reportedMedia = insertMedia(jdbc, reported.inspectionId(), "v42/reported.png");
        insertDefect(jdbc, reported.inspectionId(), reportedMedia, LocalDateTime.of(2026, 8, 1, 10, 0));

        migrateTo("42");

        assertThat(analyzedAtOf(jdbc, reviewedMedia)).isNotNull();
        assertThat(analyzedAtOf(jdbc, reportedMedia)).isNotNull();
    }

    @Test
    void 분석전회차_CREATED_UPLOADING_ANALYZING는_하자여부와무관하게null로남는다() {
        // ANALYZED 미만 상태는 "분석 진행/완료 회차"가 아니므로 백필 대상에서 제외한다 — 이런 회차의
        // 미디어는 애초에 첫 분석을 아직 거치지 않은 게 사실이다.
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspection(jdbc, "v42-not-analyzed", "UPLOADING");
        long media = insertMedia(jdbc, fx.inspectionId(), "v42/not-analyzed.png");
        insertDefect(jdbc, fx.inspectionId(), media, LocalDateTime.of(2026, 8, 1, 10, 0));

        migrateTo("42");

        assertThat(analyzedAtOf(jdbc, media)).isNull();
    }

    @Test
    void DEFECT_ACTION미디어는_분석대상이아니므로_조건을만족해도null로남는다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspection(jdbc, "v42-defect-action", "ANALYZED");
        long defectMedia = insertMedia(jdbc, fx.inspectionId(), "v42/source.png");
        insertDefect(jdbc, fx.inspectionId(), defectMedia, LocalDateTime.of(2026, 8, 1, 10, 0));
        long actionMedia = insertMedia(jdbc, fx.inspectionId(), "v42/action.png");
        setMediaPurpose(jdbc, actionMedia, "DEFECT_ACTION");
        setMediaCreatedAt(jdbc, actionMedia, LocalDateTime.of(2026, 8, 1, 9, 0)); // 마지막 하자 이전 업로드라도.

        migrateTo("42");

        assertThat(analyzedAtOf(jdbc, actionMedia)).isNull();
    }

    @Test
    void V42는_멱등하다_재실행해도_동일결과를낸다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspection(jdbc, "v42-idempotent", "ANALYZED");
        long defectMedia = insertMedia(jdbc, fx.inspectionId(), "v42/defect.png");
        insertDefect(jdbc, fx.inspectionId(), defectMedia, LocalDateTime.of(2026, 8, 1, 10, 0));
        long afterMedia = insertMedia(jdbc, fx.inspectionId(), "v42/after.png");
        setMediaCreatedAt(jdbc, afterMedia, LocalDateTime.of(2026, 8, 2, 9, 0));

        migrateTo("42");
        LocalDateTime firstRun = analyzedAtOf(jdbc, defectMedia);
        assertThat(firstRun).isNotNull();
        assertThat(analyzedAtOf(jdbc, afterMedia)).isNull();

        jdbc.execute(migrationSql());

        assertThat(analyzedAtOf(jdbc, defectMedia)).isEqualTo(firstRun); // 기존 값 불변.
        assertThat(analyzedAtOf(jdbc, afterMedia)).isNull(); // 재실행해도 여전히 증분 분석 대상.
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Fixture(long ownerId, long companyId, long facilityId, long inspectionId) {
    }

    private Fixture seedInspection(JdbcTemplate jdbc, String emailPrefix, String status) {
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
                insert into inspections (facility_id, created_by, assigned_inspector_id, round_no,
                                         inspection_date, status)
                values (?, ?, ?, 1, current_date, ?::inspection_status_type) returning id
                """, Long.class, facility, owner, owner, status);
        return new Fixture(owner, company, facility, inspection);
    }

    private long insertUser(JdbcTemplate jdbc, String email) {
        return jdbc.queryForObject("""
                insert into users (email, name, role, password_hash, status)
                values (?, 'V42 사용자', 'INSPECTOR'::role_type, 'test-password-hash', 'ACTIVE'::user_status_type)
                returning id
                """, Long.class, email);
    }

    private long insertCompany(JdbcTemplate jdbc, long ownerUserId, String name) {
        String brn = "V42" + BRN_SEQ.getAndIncrement();
        return jdbc.queryForObject("""
                insert into companies (owner_user_id, name, business_registration_number,
                                       representative_name, address, business_registration_file_url,
                                       status, verification_status)
                values (?, ?, ?, '대표자', '서울시', '/test/v42.png',
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

    private void insertDefect(JdbcTemplate jdbc, long inspectionId, long mediaId, LocalDateTime createdAt) {
        jdbc.update("""
                insert into defects (inspection_id, media_id, type, confidence, created_at)
                values (?, ?, 'CRACK'::defect_type, 0.9, ?)
                """, inspectionId, mediaId, createdAt);
    }

    private void setMediaCreatedAt(JdbcTemplate jdbc, long mediaId, LocalDateTime createdAt) {
        jdbc.update("update media set created_at = ? where id = ?", createdAt, mediaId);
    }

    private void setMediaPurpose(JdbcTemplate jdbc, long mediaId, String purpose) {
        jdbc.update("update media set purpose = ? where id = ?", purpose, mediaId);
    }

    private LocalDateTime analyzedAtOf(JdbcTemplate jdbc, long mediaId) {
        return jdbc.queryForObject("select analyzed_at from media where id = ?", LocalDateTime.class, mediaId);
    }

    private String migrationSql() {
        try (var in = new org.springframework.core.io.ClassPathResource(
                "db/migration/V42__add_media_analyzed_at.sql").getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(
                    "V42 마이그레이션 리소스를 읽지 못했다", e);
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
