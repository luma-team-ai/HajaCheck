package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
 * V41(#1522 누락된 "AI 주간 브리핑 카드" 메뉴 복구)을 격리 검증한다.
 *
 * <p>V35 가 seed 한 대시보드 자식은 {@code DASHBOARD_OVERVIEW(39)}·{@code DASHBOARD_UPCOMING_INSPECTIONS(40)}
 * 뿐이고 id 41 이 결번이었다(V36 주석 실측 "menus 29행"과 일치). 그 탓에 실서버 사이드바에서
 * 링크가 사라졌고, 프론트는 목·폴백에만 남아 로컬과 실서버가 갈렸다.
 *
 * <p>이 테스트가 고정하는 것:
 * <ol>
 *   <li>V40 까지만 적용한 상태에서 해당 메뉴가 <b>실제로 없다</b>(= 회귀 원인 재현)</li>
 *   <li>V41 적용 후 메뉴 행 + 부모 연결 + 역할 3종이 <b>형제 메뉴와 동일 규약</b>으로 생긴다</li>
 *   <li>V41 을 <b>두 번 적용해도</b> 중복 행이 생기지 않는다(멱등)</li>
 *   <li>id 41 이 이미 다른 코드에 선점된 환경에서는 <b>PK 충돌로 죽지 않고</b> 건너뛴다</li>
 * </ol>
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class V41RestoreAiWeeklyBriefingMenuMigrationTest {

    private static final String CODE = "DASHBOARD_AI_WEEKLY_BRIEFING";
    private static final String PATH = "/dashboard/ai-weekly-briefing";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v41_briefing_menu")
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
    void V40까지는_주간브리핑메뉴가_없다() {
        migrateTo("40");

        assertThat(countByCode(jdbc())).isZero();
    }

    @Test
    void V41적용후_메뉴행과_부모연결과_역할3종이_형제메뉴와_동일규약으로_생긴다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();

        var row = jdbc.queryForMap(
                "select id, name, menu_type::text as menu_type, path, icon_key, sort_order,"
                        + " is_visible, is_enabled, opens_new_tab, parent_id"
                        + " from menus where code = ?",
                CODE);

        assertThat(row.get("id")).isEqualTo(41L);
        assertThat(row.get("name")).isEqualTo("AI 주간 브리핑 카드");
        assertThat(row.get("menu_type")).isEqualTo("INTERNAL");
        assertThat(row.get("path")).isEqualTo(PATH);
        assertThat(row.get("icon_key")).isEqualTo("dashboard");
        assertThat(row.get("is_visible")).isEqualTo(true);
        assertThat(row.get("is_enabled")).isEqualTo(true);
        assertThat(row.get("opens_new_tab")).isEqualTo(false);

        // 부모는 대시보드 그룹. 형제(39·40)와 같은 부모여야 사이드바 같은 그룹에 붙는다.
        Long dashboardId = jdbc.queryForObject(
                "select id from menus where code = 'DASHBOARD'", Long.class);
        assertThat(row.get("parent_id")).isEqualTo(dashboardId);

        // sort_order 는 형제 뒤(전체현황 10 → 다음점검일 20 → 주간브리핑 30).
        assertThat(row.get("sort_order")).isEqualTo(30);
        Integer upcomingSort = jdbc.queryForObject(
                "select sort_order from menus where code = 'DASHBOARD_UPCOMING_INSPECTIONS'", Integer.class);
        assertThat((Integer) row.get("sort_order")).isGreaterThan(upcomingSort);

        // 역할 접근은 형제와 동일 집합이어야 한다 — 여기가 어긋나면 특정 역할에게만 메뉴가 사라진다.
        assertThat(rolesOf(jdbc, CODE))
                .containsExactlyInAnyOrder("USER", "INSPECTOR", "ADMIN")
                .isEqualTo(rolesOf(jdbc, "DASHBOARD_UPCOMING_INSPECTIONS"));
    }

    @Test
    void V41을_두번적용해도_중복행이_생기지_않는다() {
        migrateTo("41");
        JdbcTemplate jdbc = jdbc();

        applyV41Again(jdbc);

        assertThat(countByCode(jdbc)).isEqualTo(1);
        assertThat(rolesOf(jdbc, CODE)).hasSize(3);
    }

    @Test
    void id41이_다른코드에_선점된_환경에서는_충돌없이_건너뛴다() {
        migrateTo("40");
        JdbcTemplate jdbc = jdbc();

        // V35 가 explicit id 를 쓰면서 setval 을 하지 않아, 이론상 41 이 다른 행에 갈 수 있다.
        // 그 환경에서 V41 이 PK 충돌로 기동을 막으면 안 된다(#531 류 사고 재발 방지).
        // icon_key/icon_url 은 ck_menus_icon_single, INTERNAL 은 path 필수(ck_menus_path_by_type).
        jdbc.update("insert into menus (id, code, name, menu_type, path, icon_key, sort_order)"
                + " overriding system value"
                + " values (41, 'SQUATTER_MENU', '선점 메뉴', 'INTERNAL', '/squatter', 'dashboard', 99)");

        applyV41Again(jdbc);

        assertThat(countByCode(jdbc)).isZero();
        assertThat(jdbc.queryForObject(
                "select code from menus where id = 41", String.class)).isEqualTo("SQUATTER_MENU");
    }

    private void applyV41Again(JdbcTemplate jdbc) {
        jdbc.execute(readMigration());
    }

    private String readMigration() {
        try (var in = getClass().getClassLoader()
                .getResourceAsStream("db/migration/V41__restore_ai_weekly_briefing_menu.sql")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private int countByCode(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select count(*) from menus where code = ?", Integer.class, CODE);
    }

    private List<String> rolesOf(JdbcTemplate jdbc, String code) {
        return jdbc.queryForList(
                "select mra.role::text from menu_role_access mra"
                        + " join menus m on m.id = mra.menu_id where m.code = ?",
                String.class, code);
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
