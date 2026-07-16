package com.hajacheck.membership.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * plans/user_plans/usage_counters 엔티티 매핑 + 커스텀 쿼리 검증(HAJA-177).
 * 실 PostgreSQL(Testcontainers)에 ddl-auto=validate 로 붙어 @JdbcTypeCode(NAMED_ENUM) 매핑을 검증한다
 * (UserRepositoryTest 와 동일 패턴 — PostgresTestSupport 참고).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class MembershipRepositoryTest extends PostgresTestSupport {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;
    @Autowired
    private UsageCounterRepository usageCounterRepository;
    @Autowired
    private TestEntityManager em;

    private Plan savePlan(PlanName name) {
        return planRepository.save(Plan.create(name, 10, 1000, 3, false, true, false, BigDecimal.valueOf(99000)));
    }

    private User saveUser(String email, Long companyId) {
        return userRepository.save(User.builder()
                .email(email)
                .name("사용자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .companyId(companyId)
                .status(UserStatus.ACTIVE)
                .build());
    }

    /**
     * user_plans.company_id / users.company_id 는 DDL 상 companies 테이블 FK 이므로,
     * 리터럴 id 대신 실제 Company 행을 먼저 저장하고 그 id 를 사용해야 한다(FK 위반 방지).
     * businessRegistrationNumber 는 unique 라 호출부마다 다른 값을 넘겨야 한다.
     */
    private Company saveCompany(String ownerEmail, String businessRegistrationNumber) {
        User owner = saveUser(ownerEmail, null);
        return companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)테스트", businessRegistrationNumber, "김대표",
                "서울시 강남구", null, "http://files/brn.png", "{}"));
    }

    @Test
    void plan_저장및name으로조회() {
        savePlan(PlanName.STANDARD);

        Optional<Plan> found = planRepository.findByName(PlanName.STANDARD);

        assertThat(found).isPresent();
        assertThat(found.get().getMaxSeats()).isEqualTo(3);
        assertThat(found.get().getPriceMonthly()).isEqualByComparingTo(BigDecimal.valueOf(99000));
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void plan_무제한한도는_null그대로저장() {
        Plan enterprise = planRepository.save(
                Plan.create(PlanName.ENTERPRISE, null, null, 100, false, true, true, BigDecimal.valueOf(500000)));

        Plan found = planRepository.findById(enterprise.getId()).orElseThrow();

        assertThat(found.getMaxFacilities()).isNull();
        assertThat(found.getMaxMonthlyAnalyses()).isNull();
    }

    @Test
    void userPlan_개인구독_findFirstByUserIdAndStatus_ACTIVE조회() {
        Plan plan = savePlan(PlanName.STANDARD);
        User user = saveUser("individual@haja.com", null);
        userPlanRepository.saveAndFlush(UserPlan.forUser(user.getId(), plan.getId()));
        em.clear();

        Optional<UserPlan> found = userPlanRepository
                .findFirstByUserIdAndStatusOrderByStartedAtDesc(user.getId(), UserPlanStatus.ACTIVE);

        assertThat(found).isPresent();
        assertThat(found.get().getPlanId()).isEqualTo(plan.getId());
        assertThat(found.get().getPlan().getName()).isEqualTo(PlanName.STANDARD);
        assertThat(found.get().getCompanyId()).isNull();
    }

    @Test
    void userPlan_회사구독_findFirstByCompanyIdAndStatus_ACTIVE조회() {
        Plan plan = savePlan(PlanName.ENTERPRISE);
        Company company = saveCompany("companyplan-owner@haja.com", "1111111111");
        userPlanRepository.save(UserPlan.forCompany(company.getId(), plan.getId()));

        Optional<UserPlan> found = userPlanRepository
                .findFirstByCompanyIdAndStatusOrderByStartedAtDesc(company.getId(), UserPlanStatus.ACTIVE);

        assertThat(found).isPresent();
        assertThat(found.get().getUserId()).isNull();
    }

    @Test
    void userPlan_requestUpgrade_상태전이후_재조회하면_UPGRADE_REQUESTED() {
        Plan plan = savePlan(PlanName.STANDARD);
        User user = saveUser("upgrade@haja.com", null);
        UserPlan saved = userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));

        saved.requestUpgrade();
        userPlanRepository.flush();

        UserPlan reloaded = userPlanRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(UserPlanStatus.UPGRADE_REQUESTED);
    }

    @Test
    void usageCounter_period는_해당월1일로정규화() {
        Plan plan = savePlan(PlanName.STANDARD);
        User user = saveUser("usage@haja.com", null);
        UserPlan userPlan = userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));

        UsageCounter saved = usageCounterRepository.saveAndFlush(
                UsageCounter.create(userPlan.getId(), LocalDate.now(), 786, 4, 12, 1, 0, 2));

        assertThat(saved.getPeriod()).isEqualTo(YearMonth.now().atDay(1));

        em.clear();
        Optional<UsageCounter> found = usageCounterRepository
                .findByUserPlanIdAndPeriod(userPlan.getId(), YearMonth.now().atDay(1));
        assertThat(found).isPresent();
        assertThat(found.get().getAnalyzedImageCount()).isEqualTo(786);
        assertThat(found.get().getUserPlan().getPlan().getName()).isEqualTo(PlanName.STANDARD);
    }

    @Test
    void user_회사소속_활성사용자만조회_findByCompanyIdAndStatus() {
        Company company = saveCompany("seats-owner@haja.com", "2222222222");
        Long companyId = company.getId();
        saveUser("member1@haja.com", companyId);
        saveUser("member2@haja.com", companyId);
        saveUser("other@haja.com", null);
        userRepository.save(User.builder()
                .email("suspended@haja.com")
                .name("정지된사용자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .companyId(companyId)
                .status(UserStatus.SUSPENDED)
                .build());

        List<User> activeMembers = userRepository.findByCompanyIdAndStatus(companyId, UserStatus.ACTIVE);

        // 정지(SUSPENDED) 구성원은 좌석 과다집계·PII 노출 방지를 위해 제외되어야 한다.
        assertThat(activeMembers).hasSize(2);
        assertThat(activeMembers).extracting(User::getEmail)
                .containsExactlyInAnyOrder("member1@haja.com", "member2@haja.com");
    }
}
