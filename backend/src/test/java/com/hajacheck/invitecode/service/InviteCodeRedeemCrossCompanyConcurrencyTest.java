package com.hajacheck.invitecode.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.DomainStateTransitionException;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * #1492 — <b>같은 사용자</b>가 서로 다른 두 회사의 초대 코드를 동시에 redeem 할 때의 정합성 검증.
 *
 * <p>{@code QuotaEnforcementConcurrencyTest} 의 좌석 경합 테스트는 "한 회사"의 좌석 한도만 본다
 * (경합 축 = usage_counters 한 행). 여기서는 경합 축이 <b>users 한 행</b>이고 좌석 행은 서로 달라
 * {@code QuotaService.reserveSeat} 의 행 잠금이 두 요청을 직렬화하지 못한다.
 *
 * <p>⚠️ 클래스 레벨 {@code @Transactional} 금지 — 워커 스레드마다 독립 트랜잭션이어야 실제 경합이
 * 재현된다(같은 이유로 커밋된 데이터는 {@link #tearDown()} 에서 직접 정리한다).
 *
 * <h2>수정 전 실측(20회 반복, 전 회차 동일)</h2>
 * <ul>
 *   <li>APPROVED 멤버십 행 수 = <b>1</b> — 부분 UQ {@code uq_company_memberships_approved_user}(V1)가
 *       두 번째 INSERT 를 막는다. 이슈 본문이 서술한 "APPROVED 2행"은 발생하지 않는다.</li>
 *   <li>두 회사 {@code usage_counters.seat_count} 합계 = <b>2</b>(승자 회사만 대표 1 + 초대 1,
 *       패자 회사는 집계 행 자체가 롤백) — <b>좌석 누수 없음</b>.</li>
 *   <li>{@code users.company_id} = 승자 회사, {@code users.status} = ACTIVE — 멤버십과 정합.</li>
 *   <li>패자 트랜잭션 반환 = raw {@code DataIntegrityViolationException}
 *       (root {@code org.postgresql.util.PSQLException}, constraint
 *       {@code uq_company_memberships_approved_user}). 도메인 예외가 아니다.
 *       {@code GlobalExceptionHandler} 의 제약명 매핑 덕에 HTTP 는 500 이 아니라
 *       409({@code AUTH_APPROVED_MEMBERSHIP_CONFLICT})로 나간다.</li>
 * </ul>
 *
 * <p>즉 <b>정합성 결함(좌석·멤버십 누수)은 재현되지 않았고</b>, 남은 결함은 "동시 요청의 실패 경로가
 * 서비스 계층 도메인 규칙이 아니라 DB 제약 위반에 의존한다"는 점이다. #1492 의 수정
 * ({@code UserRepository#findByIdForUpdate} 로 사용자 단위 직렬화)은 그 경로를 순차 실행과 동일한
 * 도메인 거부({@code User#requireWaiting} → {@code INVALID_STATE_TRANSITION} 409)로 수렴시킨다 —
 * 아래 테스트가 그 계약을 고정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class InviteCodeRedeemCrossCompanyConcurrencyTest extends PostgresTestSupport {

    @Autowired
    private InviteCodeService inviteCodeService;
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

    private final List<Long> companyIds = new ArrayList<>();
    private final List<Long> ownerIds = new ArrayList<>();
    private final List<Long> userPlanIds = new ArrayList<>();
    private Long waitingUserId;

    @BeforeEach
    void setUp() {
        // STANDARD(max_seats=3) — 두 회사 모두 대표 1석만 쓰고 있어 좌석 한도는 이 시나리오의 제약이
        // 아니다(좌석이 모자라 실패하는 것과 구분하기 위해 일부러 여유를 둔다).
        Plan standard = planRepository.findByName(PlanName.STANDARD).orElseThrow();
        for (int i = 0; i < 2; i++) {
            Long ownerId = createCompanyWithOwner(i);
            userPlanIds.add(userPlanRepository.save(
                    UserPlan.forCompany(companyIds.get(i), standard.getId())).getId());
            ownerIds.add(ownerId);
        }

        waitingUserId = userRepository.save(User.createSocialUser(
                SocialProvider.KAKAO, "invite-race-social-" + System.nanoTime(),
                "invite-race-" + System.nanoTime() + "@haja.test", "동시redeem대기자")).getId();
    }

    private Long createCompanyWithOwner(int index) {
        User owner = userRepository.save(User.builder()
                .email("invite-race-owner-" + System.nanoTime() + "-" + index + "@haja.test")
                .name("초대경합대표" + index)
                .role(Role.ADMIN)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());
        String brn = "irbrn-" + (System.nanoTime() % 10_000_000_000L) + index;
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)초대경합" + index, brn,
                "김대표", "서울시 강남구", null, "http://files/brn.png", "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        company = companyRepository.save(company);
        owner.assignToCompany(company.getId());
        userRepository.save(owner);
        companyIds.add(company.getId());
        return owner.getId();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from company_memberships where user_id = ?", waitingUserId);
        for (Long companyId : companyIds) {
            jdbcTemplate.update("delete from company_memberships where company_id = ?", companyId);
        }
        for (Long userPlanId : userPlanIds) {
            jdbcTemplate.update("delete from usage_counters where user_plan_id = ?", userPlanId);
            jdbcTemplate.update("delete from user_plans where id = ?", userPlanId);
        }
        // circular FK(companies.owner_user_id ↔ users.company_id) — company_id 를 먼저 끊는다.
        jdbcTemplate.update("update users set company_id = null where id = ?", waitingUserId);
        for (Long ownerId : ownerIds) {
            jdbcTemplate.update("update users set company_id = null where id = ?", ownerId);
        }
        for (Long companyId : companyIds) {
            jdbcTemplate.update("delete from user_plans where company_id = ?", companyId);
            jdbcTemplate.update("delete from companies where id = ?", companyId);
        }
        jdbcTemplate.update("delete from users where id = ?", waitingUserId);
        for (Long ownerId : ownerIds) {
            jdbcTemplate.update("delete from users where id = ?", ownerId);
        }
        companyIds.clear();
        ownerIds.clear();
        userPlanIds.clear();
        waitingUserId = null;
    }

    @Test
    void 서로다른_두회사_초대코드를_한사용자가_동시에_redeem해도_좌석과_멤버십이_한쪽에만_생긴다() throws Exception {
        String codeA = inviteCodeService.issue(companyIds.get(0)).code();
        String codeB = inviteCodeService.issue(companyIds.get(1)).code();
        List<String> codes = List.of(codeA, codeB);

        List<Outcome> outcomes = runConcurrently(2,
                index -> inviteCodeService.redeem(codes.get(index), waitingUserId));

        long succeeded = outcomes.stream().filter(Outcome::success).count();
        int approvedMemberships = jdbcTemplate.queryForObject(
                "select count(*) from company_memberships where user_id = ? and status = 'APPROVED'",
                Integer.class, waitingUserId);
        int seatA = seatCountOf(userPlanIds.get(0));
        int seatB = seatCountOf(userPlanIds.get(1));
        int seatSum = seatA + seatB;
        Long finalCompanyId = jdbcTemplate.queryForObject(
                "select company_id from users where id = ?", Long.class, waitingUserId);
        String finalStatus = jdbcTemplate.queryForObject(
                "select status::text from users where id = ?", String.class, waitingUserId);

        System.out.println("[#1492 실측] 성공 스레드 수=" + succeeded
                + " / APPROVED 멤버십 행 수=" + approvedMemberships
                + " / seat_count A=" + seatA + " B=" + seatB + " 합계=" + seatSum
                + " / users.company_id=" + finalCompanyId
                + " / users.status=" + finalStatus);
        outcomes.forEach(outcome -> System.out.println("[#1492 실측] 스레드 결과: " + outcome.describe()));

        // 좌석 집계 행은 예약이 실제로 일어난 회사에만 생긴다(승자 = 대표 1석 + 초대 1석 = 2).
        // 양쪽 회사가 모두 좌석을 잡았다면(누수) 합계가 4가 된다.
        assertThat(succeeded).as("성공은 정확히 한 회사 쪽 1건이어야 한다").isEqualTo(1);
        assertThat(approvedMemberships).as("사용자당 APPROVED 멤버십은 1행").isEqualTo(1);
        assertThat(seatSum).as("좌석 예약은 승자 회사에만 반영(승자 회사 대표 1 + 초대 1)").isEqualTo(2);
        assertThat(finalCompanyId).as("users.company_id 는 APPROVED 멤버십 회사와 같아야 한다")
                .isEqualTo(jdbcTemplate.queryForObject(
                        "select company_id from company_memberships where user_id = ? and status = 'APPROVED'",
                        Long.class, waitingUserId));
        assertThat(finalStatus).isEqualTo("ACTIVE");

        // #1492 수정의 핵심 계약 — 패자의 실패는 DB 제약 위반(raw DataIntegrityViolationException)이
        // 아니라 순차 실행과 동일한 도메인 거부여야 한다(User#requireWaiting → 409
        // INVALID_STATE_TRANSITION). 사용자 행 잠금을 제거하면(사보타주) 여기서 다시 빨개진다.
        Outcome failed = outcomes.stream().filter(outcome -> !outcome.success()).findFirst().orElseThrow();
        assertThat(failed.thrown())
                .as("패자 반환 예외: %s", failed.describe())
                .isInstanceOf(DomainStateTransitionException.class);
    }

    private int seatCountOf(Long userPlanId) {
        Integer seatCount = jdbcTemplate.queryForObject(
                "select coalesce(sum(seat_count), 0) from usage_counters where user_plan_id = ?",
                Integer.class, userPlanId);
        return seatCount == null ? 0 : seatCount;
    }

    private List<Outcome> runConcurrently(int threadCount, IndexedAction action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            int index = i;
            Callable<Outcome> task = () -> {
                ready.countDown();
                start.await();
                try {
                    action.run(index);
                    return new Outcome(index, true, null);
                } catch (Throwable t) {
                    return new Outcome(index, false, t);
                }
            };
            futures.add(executor.submit(task));
        }
        ready.await();
        start.countDown();

        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> future : futures) {
            outcomes.add(future.get(60, TimeUnit.SECONDS));
        }
        executor.shutdown();
        return outcomes;
    }

    private record Outcome(int index, boolean success, Throwable thrown) {
        String describe() {
            if (success) {
                return "index=" + index + " SUCCESS";
            }
            Throwable root = thrown;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return "index=" + index + " FAILED " + thrown.getClass().getName()
                    + " / root=" + root.getClass().getName()
                    + " / message=" + String.valueOf(thrown.getMessage()).replaceAll("\\s+", " ");
        }
    }

    @FunctionalInterface
    private interface IndexedAction {
        void run(int index) throws Exception;
    }
}
