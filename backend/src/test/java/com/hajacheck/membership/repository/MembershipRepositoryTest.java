package com.hajacheck.membership.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
    private PlanRepository planRepository;
    @Autowired
    private UserPlanRepository userPlanRepository;
    @Autowired
    private UsageCounterRepository usageCounterRepository;

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
        userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));

        Optional<UserPlan> found = userPlanRepository
                .findFirstByUserIdAndStatusOrderByStartedAtDesc(user.getId(), UserPlanStatus.ACTIVE);

        assertThat(found).isPresent();
        assertThat(found.get().getPlanId()).isEqualTo(plan.getId());
        assertThat(found.get().getCompanyId()).isNull();
    }

    @Test
    void userPlan_회사구독_findFirstByCompanyIdAndStatus_ACTIVE조회() {
        Plan plan = savePlan(PlanName.ENTERPRISE);
        userPlanRepository.save(UserPlan.forCompany(999L, plan.getId()));

        Optional<UserPlan> found = userPlanRepository
                .findFirstByCompanyIdAndStatusOrderByStartedAtDesc(999L, UserPlanStatus.ACTIVE);

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

        UsageCounter saved = usageCounterRepository.save(
                UsageCounter.create(userPlan.getId(), LocalDate.now(), 786, 4, 12, 1, 0, 2));

        assertThat(saved.getPeriod()).isEqualTo(YearMonth.now().atDay(1));

        Optional<UsageCounter> found = usageCounterRepository
                .findByUserPlanIdAndPeriod(userPlan.getId(), YearMonth.now().atDay(1));
        assertThat(found).isPresent();
        assertThat(found.get().getAnalyzedImageCount()).isEqualTo(786);
    }

    @Test
    void user_회사소속조회_findByCompanyId_countByCompanyId() {
        Long companyId = 777L;
        saveUser("member1@haja.com", companyId);
        saveUser("member2@haja.com", companyId);
        saveUser("other@haja.com", null);

        assertThat(userRepository.findByCompanyId(companyId)).hasSize(2);
        assertThat(userRepository.countByCompanyId(companyId)).isEqualTo(2);
    }
}
