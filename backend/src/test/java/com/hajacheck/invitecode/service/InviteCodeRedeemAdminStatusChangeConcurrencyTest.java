package com.hajacheck.invitecode.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.platformadmin.service.PlatformAdminUserService;
import com.hajacheck.support.PostgresTestSupport;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * #1492 (PR머신 2차 검수 P2) — <b>초대 코드 redeem 과 플랫폼 관리자 상태 변경이 같은 users 행에
 * 동시에 쓰는</b> 축을 고정한다.
 *
 * <p>{@link InviteCodeRedeemCrossCompanyConcurrencyTest} 는 "redeem vs redeem" 축만 본다. 여기서
 * 보는 축은 반대편이다: {@code PlatformAdminUserService#changeStatus}/{@code changeRole} 은 같은
 * users 행을 더티 변경해 커밋하는데, 이 PR 이전에는 <b>잠금 없는 조회</b>로 엔티티를 읽었다.
 *
 * <h2>왜 목으로는 재현되지 않는가</h2>
 * 결함이 <b>Hibernate 더티 UPDATE 의 컬럼 집합 × PostgreSQL 행 잠금 대기</b>의 상호작용이기 때문이다:
 * <ul>
 *   <li>{@code User} 엔티티에는 {@code @DynamicUpdate} 도 {@code @Version} 도 없다 → Hibernate 는
 *       <b>로드 시점 스냅샷 기준으로 전 컬럼 UPDATE</b> 를 날리고, 낙관적 락으로도 걸리지 않는다.</li>
 *   <li>READ COMMITTED 에서 그 UPDATE 가 다른 트랜잭션의 행 잠금에 걸려 대기하다 풀리면,
 *       {@code where id = ?} 는 최신 행 버전에도 여전히 매칭되므로 <b>stale 스냅샷 값이 그대로
 *       적용</b>된다(전형적 lost update).</li>
 * </ul>
 * 그래서 실 PostgreSQL(Testcontainers) + 독립 트랜잭션 2개가 필수다.
 *
 * <h2>결정론적 인터리빙 — 타이밍에 기대지 않는다</h2>
 * "두 스레드를 동시에 쏘고 운을 기대는" 방식은 어느 쪽이 먼저 users 행을 잡는지에 따라 red/green 이
 * 흔들린다(관리자가 먼저 이기면 redeem 이 도메인 거부로 끝나 결함이 드러나지 않는다). 그래서 아래
 * 순서를 <b>DB 잠금으로 강제</b>한다:
 * <ol>
 *   <li>테스트가 별도 커넥션으로 {@code usage_counters} 당월 행을 {@code for update} 로 잡는다.</li>
 *   <li>redeem 스레드 시작 → users 행을 먼저 잠근 뒤(#1492 본 PR) {@code QuotaService#reserveSeat}
 *       안의 좌석 행 잠금에서 <b>멈춘다</b>. 즉 redeem 은 users 잠금을 <b>쥔 채</b> 대기한다.</li>
 *   <li>관리자 스레드 시작 → 픽스 전이면 잠금 없이 stale 스냅샷(WAITING·{@code company_id=null})을
 *       읽고 커밋 flush 에서 users 행 잠금 대기, 픽스 후면 조회 자체가 잠금 대기.</li>
 *   <li>테스트가 좌석 행 잠금을 놓는다 → redeem 이 커밋({@code ACTIVE} + {@code company_id}) →
 *       관리자 트랜잭션이 진행된다.</li>
 * </ol>
 * 픽스 전에는 이 시점에 관리자 UPDATE 가 {@code company_id} 를 {@code null} 로 덮어써
 * "APPROVED 멤버십·예약 좌석은 남았는데 {@code users.company_id} 만 사라진" 상태가 된다.
 *
 * <p><b>사보타주 확인</b>: {@code PlatformAdminUserService#changeStatus} 의
 * {@code findUserForUpdate(userId)} 를 {@code findUser(userId)} 로 되돌리면 이 테스트는 red 가 된다
 * (users.company_id = null, 좌석을 예약한 회사에 배선된 사용자 1명 vs seat_count 2).
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional} 금지 — 워커 스레드마다 독립 트랜잭션이어야 실제 경합이
 * 재현된다(커밋된 데이터는 {@link #tearDown()} 에서 직접 정리한다).
 */
@SpringBootTest
@ActiveProfiles("test")
class InviteCodeRedeemAdminStatusChangeConcurrencyTest extends PostgresTestSupport {

    // QuotaService#currentPeriod 와 동일 계약(집계 기간은 KST 월 1일 고정) — 테스트가 잠글 행을
    // 서비스가 잠글 행과 정확히 일치시키기 위해 같은 기준을 쓴다.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private InviteCodeService inviteCodeService;
    @Autowired
    private PlatformAdminUserService platformAdminUserService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DataSource dataSource;

    private Long companyId;
    private Long ownerId;
    private Long userPlanId;
    private Long waitingUserId;

    @BeforeEach
    void setUp() {
        // STANDARD(max_seats=3) — 대표 1석만 쓰고 있어 좌석 한도는 이 시나리오의 제약이 아니다.
        Plan standard = planRepository.findByName(PlanName.STANDARD).orElseThrow();

        User owner = userRepository.save(User.builder()
                .email("invite-admin-race-owner-" + System.nanoTime() + "@haja.test")
                .name("초대관리자경합대표")
                .role(Role.ADMIN)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());
        String brn = "iabrn-" + (System.nanoTime() % 10_000_000_000L);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)초대관리자경합", brn,
                "김대표", "서울시 강남구", null, "http://files/brn.png", "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        company = companyRepository.save(company);
        owner.assignToCompany(company.getId());
        userRepository.save(owner);

        this.ownerId = owner.getId();
        this.companyId = company.getId();
        this.userPlanId = userPlanRepository.save(UserPlan.forCompany(companyId, standard.getId())).getId();
        this.waitingUserId = userRepository.save(User.createSocialUser(
                SocialProvider.KAKAO, "invite-admin-race-social-" + System.nanoTime(),
                "invite-admin-race-" + System.nanoTime() + "@haja.test", "동시redeem대기자")).getId();

        // 좌석 집계 행을 미리 만들어 둔다 — QuotaService#lockPeriodRow 가 잠글 바로 그 행을 테스트가
        // 선점해야 redeem 을 "users 잠금 보유 + 좌석 행 대기" 상태로 정확히 세워둘 수 있다.
        // (없으면 서비스가 스스로 INSERT 해버려 테스트가 잠글 대상이 사라진다.)
        jdbcTemplate.update("""
                insert into usage_counters
                    (user_plan_id, period, analyzed_image_count, facility_count,
                     analysis_request_count, seat_count, counsel_ticket_count, pdf_generation_count)
                values (?, ?, 0, 0, 0, 1, 0, 0)
                on conflict (user_plan_id, period) do nothing
                """, userPlanId, java.sql.Date.valueOf(currentPeriod()));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from company_memberships where user_id = ?", waitingUserId);
        jdbcTemplate.update("delete from company_memberships where company_id = ?", companyId);
        jdbcTemplate.update("delete from usage_counters where user_plan_id = ?", userPlanId);
        jdbcTemplate.update("delete from user_plans where id = ?", userPlanId);
        // circular FK(companies.owner_user_id ↔ users.company_id) — company_id 를 먼저 끊는다.
        jdbcTemplate.update("update users set company_id = null where id in (?, ?)", waitingUserId, ownerId);
        jdbcTemplate.update("delete from user_plans where company_id = ?", companyId);
        jdbcTemplate.update("delete from companies where id = ?", companyId);
        jdbcTemplate.update("delete from users where id in (?, ?)", waitingUserId, ownerId);
    }

    @Test
    void 초대코드_redeem_커밋직후_관리자_상태변경이_flush돼도_users_company_id를_덮어쓰지_않는다() throws Exception {
        String code = inviteCodeService.issue(companyId).code();
        ExecutorService executor = Executors.newFixedThreadPool(2);

        Outcome redeemOutcome;
        Outcome adminOutcome;
        try {
            Future<Outcome> redeemFuture;
            Future<Outcome> adminFuture;

            try (Connection blocker = dataSource.getConnection()) {
                blocker.setAutoCommit(false);
                lockSeatCounterRow(blocker);

                // ① redeem — users 행을 잠근 뒤 좌석 행 잠금에서 멈춘다(users 잠금 보유 상태로 대기).
                redeemFuture = executor.submit(runCatching(
                        () -> inviteCodeService.redeem(code, waitingUserId)));
                awaitLockWaiters(1);

                // ② 관리자 상태 변경 — 픽스 전이면 stale 스냅샷을 읽고 flush 에서, 픽스 후면 잠금
                //    조회 자체에서 users 행을 기다린다. 어느 쪽이든 "대기 중"이 되어야 한다.
                adminFuture = executor.submit(runCatching(
                        () -> platformAdminUserService.changeStatus(waitingUserId, UserStatus.SUSPENDED)));
                awaitLockWaiters(2);

                // ③ 좌석 행을 놓아 redeem 을 먼저 커밋시킨다 → 그 다음 관리자 트랜잭션이 진행된다.
                blocker.rollback();
            }

            redeemOutcome = redeemFuture.get(60, TimeUnit.SECONDS);
            adminOutcome = adminFuture.get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        Long finalCompanyId = jdbcTemplate.queryForObject(
                "select company_id from users where id = ?", Long.class, waitingUserId);
        String finalStatus = jdbcTemplate.queryForObject(
                "select status::text from users where id = ?", String.class, waitingUserId);
        Long membershipCompanyId = jdbcTemplate.queryForObject(
                "select company_id from company_memberships where user_id = ? and status = 'APPROVED'",
                Long.class, waitingUserId);
        int approvedMemberships = jdbcTemplate.queryForObject(
                "select count(*) from company_memberships where user_id = ? and status = 'APPROVED'",
                Integer.class, waitingUserId);
        int seatCount = jdbcTemplate.queryForObject(
                "select coalesce(sum(seat_count), 0) from usage_counters where user_plan_id = ?",
                Integer.class, userPlanId);
        int wiredUsers = jdbcTemplate.queryForObject(
                "select count(*) from users where company_id = ?", Integer.class, companyId);

        System.out.println("[#1492 실측/관리자경합] redeem=" + redeemOutcome.describe()
                + " / changeStatus=" + adminOutcome.describe()
                + " / users.company_id=" + finalCompanyId + " / users.status=" + finalStatus
                + " / APPROVED 멤버십=" + approvedMemberships + "(company_id=" + membershipCompanyId + ")"
                + " / seat_count=" + seatCount + " / 회사 배선 사용자 수=" + wiredUsers);

        // 두 요청 모두 성공해야 한다 — redeem 은 관리자보다 먼저 users 를 잠갔고(WAITING 통과),
        // 관리자는 그 커밋 뒤에 SUSPENDED 를 적용한다. 어느 한쪽이 실패하면 인터리빙 자체가
        // 의도대로 서지 않은 것이므로 여기서 먼저 드러난다.
        assertThat(redeemOutcome.thrown()).as("redeem 실패: %s", redeemOutcome.describe()).isNull();
        assertThat(adminOutcome.thrown()).as("changeStatus 실패: %s", adminOutcome.describe()).isNull();

        // PR머신 2차 검수가 요구한 핵심 단언 — 성공한 redeem 의 users.company_id 는 APPROVED 멤버십의
        // company_id 와 일치해야 한다. 잠금을 되돌리면 관리자 flush 가 stale null 로 덮어써 red 가 된다.
        assertThat(approvedMemberships).as("사용자당 APPROVED 멤버십은 1행").isEqualTo(1);
        assertThat(membershipCompanyId).isEqualTo(companyId);
        assertThat(finalCompanyId)
                .as("users.company_id 는 APPROVED 멤버십의 company_id 와 같아야 한다(덮어쓰기 = null)")
                .isEqualTo(membershipCompanyId);

        // 좌석 정합 — 예약된 좌석 수(대표 1 + 초대 1)와 그 회사에 실제 배선된 사용자 수가 같아야 한다.
        // 덮어쓰기가 일어나면 좌석은 2로 남는데 배선 사용자는 대표 1명뿐이라 1석이 영구히 뜬다.
        assertThat(seatCount).as("대표 1 + 초대 1").isEqualTo(2);
        assertThat(wiredUsers).as("예약 좌석 수와 회사에 배선된 사용자 수가 일치해야 한다").isEqualTo(seatCount);

        // 관리자 쓰기도 유실되지 않아야 한다 — 잠금은 "덮어쓰기 방지"이지 "관리자 변경 무효화"가 아니다.
        assertThat(finalStatus).as("관리자 상태 변경은 그대로 적용된다").isEqualTo("SUSPENDED");
    }

    private LocalDate currentPeriod() {
        return YearMonth.now(KST).atDay(1);
    }

    private void lockSeatCounterRow(Connection blocker) throws Exception {
        try (PreparedStatement statement = blocker.prepareStatement(
                "select id from usage_counters where user_plan_id = ? and period = ? for update")) {
            statement.setLong(1, userPlanId);
            statement.setDate(2, java.sql.Date.valueOf(currentPeriod()));
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).as("잠글 좌석 집계 행이 존재해야 한다").isTrue();
            }
        }
    }

    /**
     * 잠금 대기 중인 백엔드 수가 {@code expected} 이상이 될 때까지 기다린다 — {@code Thread.sleep}
     * 상수로 인터리빙을 "짐작"하지 않고 DB 상태로 확인한다(느린 CI 에서도 안정적).
     */
    private void awaitLockWaiters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity "
                            + "where datname = current_database() and wait_event_type = 'Lock'",
                    Integer.class);
            if (waiting != null && waiting >= expected) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("잠금 대기 중인 트랜잭션이 " + expected + "개가 되지 않았다 — 인터리빙 실패");
    }

    private Callable<Outcome> runCatching(ThrowingRunnable action) {
        return () -> {
            try {
                action.run();
                return new Outcome(null);
            } catch (Throwable t) {
                return new Outcome(t);
            }
        };
    }

    private record Outcome(Throwable thrown) {
        String describe() {
            if (thrown == null) {
                return "SUCCESS";
            }
            Throwable root = thrown;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return "FAILED " + thrown.getClass().getName() + " / root=" + root.getClass().getName()
                    + " / message=" + String.valueOf(thrown.getMessage()).replaceAll("\\s+", " ");
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
