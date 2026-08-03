package com.hajacheck.platformadmin.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 관리자 콘솔 — 플랜·쿼터 관리 API(#624) MVC 통합 테스트. AdminPlanControllerTest(#507)와 동일 패턴
 * (@SpringBootTest+MockMvc — 전역 시큐리티 필터체인의 "/api/platform-admin/**" hasRole(PLATFORM_ADMIN)를
 * 실제로 태워야 함) 이지만, 이 화면의 핵심 계약은 companyId 스코프 없이 전사 사용자를 나열하는 것이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlatformAdminPlanQuotaControllerTest extends PostgresTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
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

    @Test
    void 목록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/platform-admin/plans-quota"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 목록조회_회사ADMIN이면_403() throws Exception {
        Company company = saveApprovedCompany();
        User companyAdmin = saveUser(Role.ADMIN, company.getId());
        mockMvc.perform(get("/api/platform-admin/plans-quota").with(authentication(authOf(companyAdmin))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 목록조회_다른회사사용자도_전부보이고_회사플랜값을반영한다() throws Exception {
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        Company company = saveApprovedCompany();
        userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(PlanName.STANDARD)));
        User member = saveUser(Role.USER, company.getId());
        Integer standardQuotaLimit = planRepository.findByName(PlanName.STANDARD)
                .orElseThrow().getMaxMonthlyAnalyses();

        // 생성 순서(u.id asc)상 회사 소유자(owner) 다음이 member — id 오름차순 표 정렬 계약과 정합.
        mockMvc.perform(get("/api/platform-admin/plans-quota").param("size", "50")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.content[1].email").value(member.getEmail()))
                .andExpect(jsonPath("$.data.content[1].companyId").value(company.getId()))
                .andExpect(jsonPath("$.data.content[1].companyName").value(company.getName()))
                .andExpect(jsonPath("$.data.content[1].plan").value("STANDARD"))
                .andExpect(jsonPath("$.data.content[1].quotaLimit").value(standardQuotaLimit))
                // 방금 생성한 구독이라 남은 기간이 월 주기의 대부분(≈28~31일)을 차지해 갱신임박(WARNING) 기준(7일)보다 크다.
                .andExpect(jsonPath("$.data.content[1].status").value("ACTIVE"));
    }

    @Test
    void 목록조회_기업명으로검색된다() throws Exception {
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        Company company = companyRepository.save(Company.createPendingReview(
                saveUser(Role.ADMIN, null).getId(),
                "그린타워시설관리", "BRN-624-" + SEQ.incrementAndGet(), "김대표", "서울시", null,
                "http://files/brn.png", "{}"));
        User member = saveUser(Role.USER, company.getId());
        saveUser(Role.USER, null);

        mockMvc.perform(get("/api/platform-admin/plans-quota")
                        .param("keyword", "그린타워")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value(member.getEmail()))
                .andExpect(jsonPath("$.data.content[0].companyName").value("그린타워시설관리"));
    }

    @Test
    void 목록조회_plan필터로_해당플랜구독회사사용자만_반환한다() throws Exception {
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        Company standardCompany = saveApprovedCompany();
        userPlanRepository.save(UserPlan.forCompany(standardCompany.getId(), planId(PlanName.STANDARD)));
        User standardMember = saveUser(Role.USER, standardCompany.getId());
        Company enterpriseCompany = saveApprovedCompany();
        userPlanRepository.save(UserPlan.forCompany(enterpriseCompany.getId(), planId(PlanName.ENTERPRISE)));
        saveUser(Role.USER, enterpriseCompany.getId());

        mockMvc.perform(get("/api/platform-admin/plans-quota")
                        .param("plan", "STANDARD")
                        .param("size", "50")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].email")
                        .value(org.hamcrest.Matchers.hasItem(standardMember.getEmail())))
                .andExpect(jsonPath("$.data.content[*].plan")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("STANDARD"))));
    }

    @Test
    void 목록조회_plan필터에해당하는회사가없으면_빈목록() throws Exception {
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        saveUser(Role.USER, null);

        mockMvc.perform(get("/api/platform-admin/plans-quota")
                        .param("plan", "ENTERPRISE")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 목록조회_회사미소속_개인계정은_플랜값이전부null이고_만료상태() throws Exception {
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        User individual = saveUser(Role.USER, null);

        mockMvc.perform(get("/api/platform-admin/plans-quota")
                        .param("keyword", individual.getEmail())
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].companyId").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].companyName").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].plan").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].quotaLimit").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].remainingDays").value(nullValue()))
                .andExpect(jsonPath("$.data.content[0].status").value("EXPIRED"));
    }

    @Test
    void 목록조회_PLATFORM_ADMIN자신은_목록에서제외된다() throws Exception {
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        User anotherPlatformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        User user = saveUser(Role.USER, null);

        mockMvc.perform(get("/api/platform-admin/plans-quota")
                        .param("keyword", user.getEmail())
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value(user.getEmail()));
    }

    @Test
    void 목록조회_keyword로_이메일검색() throws Exception {
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        User target = saveUser(Role.USER, null);
        saveUser(Role.USER, null);

        mockMvc.perform(get("/api/platform-admin/plans-quota")
                        .param("keyword", target.getEmail())
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value(target.getEmail()));
    }

    @Test
    void 목록조회_stats는_유효플랜있는회사사용자만_활성으로센다() throws Exception {
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        Company company = saveApprovedCompany();
        userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(PlanName.STANDARD)));
        saveUser(Role.USER, company.getId());
        saveUser(Role.USER, null); // 회사 미소속 — 활성 사용자 카운트 제외

        // saveApprovedCompany()가 만든 owner(ADMIN, 같은 회사 소속)까지 포함해 2명 — 회사 미소속 1명은 제외.
        mockMvc.perform(get("/api/platform-admin/plans-quota").param("size", "50")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.activeUsers").value(2));
    }

    @Test
    void 목록조회_stats의_평균쿼터사용률은_usage_counters기준으로계산된다() throws Exception {
        // #1407 회귀 고정 — totalQuotaUsagePercent 가 media(검수자 배정 건수) 근사치 평균이 아니라
        // usage_counters.analyzedImageCount(쿼터 차감의 진짜 원천) / plan.maxMonthlyAnalyses 기준이어야
        // 한다. media 레코드를 전혀 만들지 않고도(과거 로직이면 0%) usage_counters 만으로 50%가 나와야
        // 이 계약이 지켜진다.
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        Company company = saveApprovedCompany();
        UserPlan userPlan = userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(PlanName.STANDARD)));
        Integer standardQuotaLimit = planRepository.findByName(PlanName.STANDARD)
                .orElseThrow().getMaxMonthlyAnalyses();
        usageCounterRepository.save(UsageCounter.create(
                userPlan.getId(), YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1),
                standardQuotaLimit / 2, 0, 0, 0, 0, 0));

        mockMvc.perform(get("/api/platform-admin/plans-quota").param("size", "50")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.totalQuotaUsagePercent").value(50));
    }

    @Test
    void 목록조회_stats의_무제한플랜사용량은_평균에서제외되고_별도합계로내려온다() throws Exception {
        // #1407 후속 — ENTERPRISE(max_monthly_analyses=null)는 "사용량 ÷ 한도"가 정의되지 않아
        // totalQuotaUsagePercent 평균에는 항상 포함되지 않는다(무제한 회사 하나만 있으면 평균은 0%).
        // 그 사용량이 화면에서 사라지지 않도록 unlimitedPlanUsageTotal 로 별도 합산해 내려줘야 한다.
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        Company company = saveApprovedCompany();
        UserPlan userPlan =
                userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(PlanName.ENTERPRISE)));
        usageCounterRepository.save(UsageCounter.create(
                userPlan.getId(), YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1),
                40, 0, 0, 0, 0, 0));

        mockMvc.perform(get("/api/platform-admin/plans-quota").param("size", "50")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.totalQuotaUsagePercent").value(0))
                .andExpect(jsonPath("$.data.stats.unlimitedPlanUsageTotal").value(40));
    }

    @Test
    void 목록조회_무제한플랜행의_쿼터사용량은_usage_counters실사용량을그대로보여준다() throws Exception {
        // #1407 후속 회귀 고정 — 예전엔 표의 quotaUsed 가 media(검수자 배정 건수) 개인별 근사치라, 실제
        // 사용량이 있어도(회사 단위 usage_counters=40) 그 media 근사치가 0이면 화면엔 0으로 보였다.
        // 이제는 회사(UserPlan) 단위 usage_counters 를 그대로 보여줘야 한다 — media 레코드가 전혀
        // 없어도 40이 나와야 이 계약이 지켜진다.
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        Company company = saveApprovedCompany();
        UserPlan userPlan =
                userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(PlanName.ENTERPRISE)));
        User member = saveUser(Role.USER, company.getId());
        usageCounterRepository.save(UsageCounter.create(
                userPlan.getId(), YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1),
                40, 0, 0, 0, 0, 0));

        mockMvc.perform(get("/api/platform-admin/plans-quota").param("size", "50")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[1].email").value(member.getEmail()))
                .andExpect(jsonPath("$.data.content[1].plan").value("ENTERPRISE"))
                .andExpect(jsonPath("$.data.content[1].quotaUsed").value(40));
    }

    @Test
    void 목록조회_가입한지한달넘은FREE회사도_만료로표시되지않는다() throws Exception {
        // #1104 회귀 고정 — 과거에는 resolveRemaining이 startedAt + 1개월로 무조건 만료를 판정해(FREE
        // 제외 필터 없음), 가입한 지 한 달 넘은 FREE 회사가 전부 "만료됨"으로 표시됐다. current_period_end
        // 는 FREE 면 항상 NULL(무기한)이므로 startedAt 이 아무리 오래됐어도 EXPIRED 가 되면 안 된다.
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        Company company = saveApprovedCompany();
        UserPlan freePlan = UserPlan.forCompany(company.getId(), planId(PlanName.FREE));
        ReflectionTestUtils.setField(freePlan, "startedAt", Instant.now().minus(60, ChronoUnit.DAYS));
        userPlanRepository.save(freePlan);
        User member = saveUser(Role.USER, company.getId());

        mockMvc.perform(get("/api/platform-admin/plans-quota").param("size", "50")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[1].email").value(member.getEmail()))
                .andExpect(jsonPath("$.data.content[1].plan").value("FREE"))
                .andExpect(jsonPath("$.data.content[1].remainingDays").value(nullValue()))
                .andExpect(jsonPath("$.data.content[1].status").value("ACTIVE"));
    }

    @Test
    void 목록조회_가입한지한달넘은FREE회사도_활성사용자KPI에포함된다() throws Exception {
        // #1104 회귀 고정 — buildStats() 의 "유효(비만료) 플랜" 집계에서 FREE가 빠져 활성 사용자 KPI가
        // 실제보다 작게 나오던 버그. current_period_end == null 은 EXPIRED 가 아니므로 이제 포함돼야 한다.
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        Company company = saveApprovedCompany();
        UserPlan freePlan = UserPlan.forCompany(company.getId(), planId(PlanName.FREE));
        ReflectionTestUtils.setField(freePlan, "startedAt", Instant.now().minus(60, ChronoUnit.DAYS));
        userPlanRepository.save(freePlan);
        saveUser(Role.USER, company.getId());
        saveUser(Role.USER, null); // 회사 미소속 — 활성 사용자 카운트 제외

        // saveApprovedCompany()가 만든 owner(ADMIN, 같은 회사 소속)까지 포함해 2명.
        mockMvc.perform(get("/api/platform-admin/plans-quota").param("size", "50")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.activeUsers").value(2));
    }

    @Test
    void 목록조회_size0이면400() throws Exception {
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        mockMvc.perform(get("/api/platform-admin/plans-quota")
                        .param("size", "0")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isBadRequest());
    }

    private Company saveApprovedCompany() {
        int n = SEQ.incrementAndGet();
        User owner = saveUser(Role.ADMIN, null);
        Company company = Company.createPendingReview(
                owner.getId(), "회사" + n, "BRN-624-" + n, "대표", "서울", null,
                "https://files.example/brn.pdf", "{\"source\":\"MANUAL_INPUT\"}");
        company.markBusinessVerified();
        company.approve(owner.getId());
        company = companyRepository.save(company);
        owner.assignToCompany(company.getId());
        userRepository.save(owner);
        return company;
    }

    private User saveUser(Role role, Long companyId) {
        int n = SEQ.incrementAndGet();
        return userRepository.save(User.builder()
                .email("pq-" + n + "@haja.com")
                .name("사용자" + n)
                .role(role)
                .passwordHash("$2a$10$hashed")
                .companyId(companyId)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private void seedPlans() {
        if (planRepository.findByName(PlanName.FREE).isPresent()) {
            return;
        }
        planRepository.save(Plan.create(PlanName.FREE, 1, 30, 1, true, false, false, BigDecimal.ZERO));
        planRepository.save(Plan.create(
                PlanName.STANDARD, 10, 300, 5, false, true, true, new BigDecimal("29000.00")));
        planRepository.save(Plan.create(
                PlanName.ENTERPRISE, null, null, 50, false, true, true, new BigDecimal("99000.00")));
    }

    private Long planId(PlanName name) {
        return planRepository.findByName(name).orElseThrow().getId();
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
