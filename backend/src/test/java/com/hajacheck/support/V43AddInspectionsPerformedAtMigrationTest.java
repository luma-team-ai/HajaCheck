package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
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
 * V43(inspections.performed_at 컬럼 추가 + 백필, #1667)의 컬럼/백필 <b>데이터 전이 범위</b>를 격리
 * 검증한다(V41AddMediaPurposeMigrationTest와 동일한 target(N)/target(N+1) 대조 패턴).
 *
 * <p>백필 우선순위: 회차의 INSPECTION_SOURCE 미디어 중 (2000-01-01 ~ now() 범위의) min(captured_at) →
 * 없으면 min(created_at) → 미디어가 아예 없으면 null 유지. DEFECT_ACTION 미디어는 백필 대상에서
 * 제외한다(V41 purpose 구분과 동일 원칙 — 조치 후 사진은 실제 점검 수행 이후 등록될 수 있다). 범위
 * 가드(PR머신 리뷰 P2, #1673)는 라이브 경로(MediaWriter.resolveCandidate의 EXIF_SANITY_FLOOR)와 동일한
 * 이상값 정의를 재현한다.
 *
 * <p>빈 DB(신규 설치) 경로는 {@code FlywayBaselineIntegrationTest}가, 캐노니컬 DDL 기존 DB 경로는
 * {@code FlywayBaselineOnExistingDbIntegrationTest}가 각각 V43 포함 전량 적용을 이미 검증한다.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class V43AddInspectionsPerformedAtMigrationTest {

    private static final AtomicLong BRN_SEQ = new AtomicLong(1);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v43_performed_at")
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
    void 컬럼추가_기존회차는_performed_at이_null이다_미디어없음() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v43-no-media");

        migrateTo("43");

        assertThat(performedAtOf(jdbc, fx.inspectionId())).isNull();
    }

    @Test
    void 백필_INSPECTION_SOURCE미디어의_captured_at최솟값을_우선사용한다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v43-captured-at");
        LocalDateTime earlier = LocalDateTime.of(2026, 8, 10, 9, 0);
        LocalDateTime later = LocalDateTime.of(2026, 8, 10, 15, 0);
        insertMedia(jdbc, fx.inspectionId(), "v43/later.png", "INSPECTION_SOURCE", later, null);
        insertMedia(jdbc, fx.inspectionId(), "v43/earlier.png", "INSPECTION_SOURCE", earlier, null);

        migrateTo("43");

        assertThat(performedAtOf(jdbc, fx.inspectionId())).isEqualTo(earlier);
    }

    @Test
    void 백필_captured_at이없으면_created_at최솟값으로대체된다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v43-created-at-fallback");
        LocalDateTime earlierUploaded = LocalDateTime.of(2026, 8, 10, 9, 0);
        LocalDateTime laterUploaded = LocalDateTime.of(2026, 8, 10, 15, 0);
        insertMedia(jdbc, fx.inspectionId(), "v43/first-upload.png", "INSPECTION_SOURCE", null, earlierUploaded);
        insertMedia(jdbc, fx.inspectionId(), "v43/second-upload.png", "INSPECTION_SOURCE", null, laterUploaded);

        migrateTo("43");

        assertThat(performedAtOf(jdbc, fx.inspectionId())).isEqualTo(earlierUploaded);
    }

    @Test
    void 백필_조치후사진DEFECT_ACTION은_대상에서제외된다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v43-defect-action-excluded");
        LocalDateTime defectActionCapturedAt = LocalDateTime.of(2026, 8, 10, 6, 0); // 가장 이르지만 조치 사진.
        LocalDateTime sourceCapturedAt = LocalDateTime.of(2026, 8, 10, 9, 0);
        insertMedia(jdbc, fx.inspectionId(), "v43/action.png", "DEFECT_ACTION", defectActionCapturedAt, null);
        insertMedia(jdbc, fx.inspectionId(), "v43/source.png", "INSPECTION_SOURCE", sourceCapturedAt, null);

        migrateTo("43");

        // 원본 촬영사진 시각만 반영 — 더 이른 조치 후 사진 시각에 끌려가지 않는다.
        assertThat(performedAtOf(jdbc, fx.inspectionId())).isEqualTo(sourceCapturedAt);
    }

    // ── EXIF 이상값 방어(PR머신 리뷰 P2, #1673) ──────────────────────────────
    // MediaWriter.resolveCandidate(라이브 경로)는 captured_at이 2000-01-01 이전이거나 업로드 시각보다
    // 미래면 이상값으로 보고 업로드 시각으로 폴백한다. 백필(V43)도 동일 경계(2000-01-01 ~ now())를
    // 재현해야 라이브/백필이 같은 의미론을 갖는다 — 아래 테스트는 그 정합을 고정한다.

    @Test
    void 백필_captured_at이1999년이전이면_이상값으로보고created_at으로대체된다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v43-bogus-past");
        LocalDateTime bogusPast = LocalDateTime.of(1999, 12, 31, 23, 59); // MediaWriter.EXIF_SANITY_FLOOR 미만.
        LocalDateTime uploadedAt = LocalDateTime.of(2026, 8, 10, 9, 0);
        insertMedia(jdbc, fx.inspectionId(), "v43/bogus-past.png", "INSPECTION_SOURCE", bogusPast, uploadedAt);

        migrateTo("43");

        // 이상값(1999년)이 첫 서브쿼리의 범위 가드(>= 2000-01-01)에 걸려 min(captured_at) 후보에서
        // 제외되므로 created_at 폴백으로 넘어간다 — 라이브 경로(MediaWriter.resolveCandidate)와 동일.
        assertThat(performedAtOf(jdbc, fx.inspectionId())).isEqualTo(uploadedAt);
    }

    @Test
    void 백필_captured_at이미래면_이상값으로보고created_at으로대체된다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v43-bogus-future");
        LocalDateTime bogusFuture = LocalDateTime.of(2099, 1, 1, 0, 0); // 마이그레이션 실행 시점 now() 이후.
        LocalDateTime uploadedAt = LocalDateTime.of(2026, 8, 10, 9, 0);
        insertMedia(jdbc, fx.inspectionId(), "v43/bogus-future.png", "INSPECTION_SOURCE", bogusFuture, uploadedAt);

        migrateTo("43");

        assertThat(performedAtOf(jdbc, fx.inspectionId())).isEqualTo(uploadedAt);
    }

    @Test
    void 백필_captured_at이정확히2000년경계면_이상값이아니라그값그대로사용된다() {
        // 라이브(MediaWriter.EXIF_SANITY_FLOOR)·백필(V43 range guard) 양쪽 다 경계값 자체는 유효로
        // 취급한다(>=, isBefore 미해당) — 두 경로가 같은 경계 판정을 공유하는지 고정.
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v43-boundary-floor");
        LocalDateTime floor = LocalDateTime.of(2000, 1, 1, 0, 0);
        insertMedia(jdbc, fx.inspectionId(), "v43/floor.png", "INSPECTION_SOURCE", floor, null);

        migrateTo("43");

        assertThat(performedAtOf(jdbc, fx.inspectionId())).isEqualTo(floor);
    }

    @Test
    void 백필_이상값과정상EXIF값이섞이면_범위가드통과한정상값의최솟값을사용한다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v43-mixed-bogus-normal");
        LocalDateTime bogus = LocalDateTime.of(1970, 1, 1, 0, 0); // 산술적으로는 더 이르지만 범위 밖.
        LocalDateTime normal = LocalDateTime.of(2026, 8, 10, 9, 0);
        insertMedia(jdbc, fx.inspectionId(), "v43/bogus.png", "INSPECTION_SOURCE", bogus, null);
        insertMedia(jdbc, fx.inspectionId(), "v43/normal.png", "INSPECTION_SOURCE", normal, null);

        migrateTo("43");

        // 범위 가드가 min() 계산 전에 이상값을 걸러내므로, 산술적 최솟값(1970년)이 아니라 범위를
        // 통과한 값 중 최솟값(정상 EXIF 1건뿐이므로 그 값 그대로)이 쓰인다.
        assertThat(performedAtOf(jdbc, fx.inspectionId())).isEqualTo(normal);
    }

    @Test
    void V43은_멱등하다_재실행해도_이미세팅된값은_불변이다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspectionWithOwner(jdbc, "v43-idempotent");
        LocalDateTime capturedAt = LocalDateTime.of(2026, 8, 10, 9, 0);
        insertMedia(jdbc, fx.inspectionId(), "v43/source.png", "INSPECTION_SOURCE", capturedAt, null);

        migrateTo("43");
        assertThat(performedAtOf(jdbc, fx.inspectionId())).isEqualTo(capturedAt);

        // 재실행 이후 같은 회차에 더 이른 미디어가 새로 심겨도, 백필은 이미 채워진 행을 다시 계산하지
        // 않는다(WHERE performed_at is null) — 이후로는 애플리케이션(MediaWriter)이 담당하는 영역이다.
        insertMedia(jdbc, fx.inspectionId(), "v43/even-earlier.png", "INSPECTION_SOURCE",
                LocalDateTime.of(2026, 8, 10, 6, 0), null);
        jdbc.execute(migrationSql());

        assertThat(performedAtOf(jdbc, fx.inspectionId())).isEqualTo(capturedAt);
    }

    @Test
    void 미디어가없는DB에서는_V43이_컬럼만_추가하고_백필은_no_op이다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();
        assertThat(jdbc.queryForObject("select count(*) from media", Integer.class)).isZero();

        migrateTo("43");

        Integer performedAtColumnExists = jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_name = 'inspections' and column_name = 'performed_at'
                """, Integer.class);
        assertThat(performedAtColumnExists).isEqualTo(1);
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
                values (?, 'V43 사용자', 'INSPECTOR'::role_type, 'test-password-hash', 'ACTIVE'::user_status_type)
                returning id
                """, Long.class, email);
    }

    private long insertCompany(JdbcTemplate jdbc, long ownerUserId, String name) {
        String brn = "V43" + BRN_SEQ.getAndIncrement();
        return jdbc.queryForObject("""
                insert into companies (owner_user_id, name, business_registration_number,
                                       representative_name, address, business_registration_file_url,
                                       status, verification_status)
                values (?, ?, ?, '대표자', '서울시', '/test/v43.png',
                        'APPROVED'::company_status_type, 'VERIFIED'::business_verification_status_type)
                returning id
                """, Long.class, ownerUserId, name, brn);
    }

    // captured_at/createdAtOverride 둘 다 null이면 created_at은 DB 기본값(now())으로 채워진다.
    private void insertMedia(JdbcTemplate jdbc, long inspectionId, String originalUrl, String purpose,
                              LocalDateTime capturedAt, LocalDateTime createdAtOverride) {
        Timestamp capturedAtTs = capturedAt == null ? null : Timestamp.valueOf(capturedAt);
        if (createdAtOverride == null) {
            jdbc.update("""
                    insert into media (inspection_id, file_type, original_url, mime_signature_verified,
                                        purpose, captured_at)
                    values (?, 'IMAGE'::media_file_type, ?, true, ?, ?)
                    """, inspectionId, originalUrl, purpose, capturedAtTs);
        } else {
            jdbc.update("""
                    insert into media (inspection_id, file_type, original_url, mime_signature_verified,
                                        purpose, captured_at, created_at)
                    values (?, 'IMAGE'::media_file_type, ?, true, ?, ?, ?)
                    """, inspectionId, originalUrl, purpose, capturedAtTs, Timestamp.valueOf(createdAtOverride));
        }
    }

    private LocalDateTime performedAtOf(JdbcTemplate jdbc, long inspectionId) {
        Timestamp ts = jdbc.queryForObject(
                "select performed_at from inspections where id = ?", Timestamp.class, inspectionId);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private String migrationSql() {
        try (var in = new org.springframework.core.io.ClassPathResource(
                "db/migration/V43__add_inspections_performed_at.sql").getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(
                    "V43 마이그레이션 리소스를 읽지 못했다", e);
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
