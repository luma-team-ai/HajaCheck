package com.hajacheck.platformadmin.controller;

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
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 관리자 콘솔 — 서비스 통계 API(#633) MVC 통합 테스트. PlatformAdminPlanQuotaControllerTest 와 동일 패턴
 * (@SpringBootTest+MockMvc — 전역 시큐리티 필터체인의 "/api/platform-admin/**" hasRole(PLATFORM_ADMIN)를
 * 실제로 태워야 함).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlatformAdminStatsControllerTest extends PostgresTestSupport {

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

    @Test
    void 조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/platform-admin/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 조회_회사ADMIN이면_403() throws Exception {
        Company company = saveApprovedCompany();
        User companyAdmin = saveUser(Role.ADMIN, company.getId());
        mockMvc.perform(get("/api/platform-admin/stats").with(authentication(authOf(companyAdmin))))
                .andExpect(status().isForbidden());
    }

    @Test
    void 조회_PLATFORM_ADMIN이면_200이고_계약필드를모두반환한다() throws Exception {
        seedPlans();
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);
        Company company = saveApprovedCompany();
        userPlanRepository.save(UserPlan.forCompany(company.getId(), planId(PlanName.STANDARD)));

        mockMvc.perform(get("/api/platform-admin/stats").with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.kpi.totalSubscribers").value(1))
                .andExpect(jsonPath("$.data.subscriberTrend.length()").value(6))
                .andExpect(jsonPath("$.data.analysisRequestTrend.length()").value(6))
                .andExpect(jsonPath("$.data.monthlySummary.length()").value(6))
                .andExpect(jsonPath("$.data.planDistribution[0].plan").value("STANDARD"))
                .andExpect(jsonPath("$.data.planDistribution[0].percent").value(100))
                .andExpect(jsonPath("$.data.counselTypeDistribution").isArray())
                .andExpect(jsonPath("$.data.counselTypeDistribution.length()").value(0));
    }

    @Test
    void 조회_구독회사없으면_가입자0에플랜분포는빈배열() throws Exception {
        User platformAdmin = saveUser(Role.PLATFORM_ADMIN, null);

        mockMvc.perform(get("/api/platform-admin/stats").with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kpi.totalSubscribers").value(0))
                .andExpect(jsonPath("$.data.planDistribution").isArray())
                .andExpect(jsonPath("$.data.planDistribution.length()").value(0));
    }

    private Company saveApprovedCompany() {
        int n = SEQ.incrementAndGet();
        User owner = saveUser(Role.ADMIN, null);
        Company company = Company.createPendingReview(
                owner.getId(), "회사" + n, "BRN-633-" + n, "대표", "서울", null,
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
                .email("stats-" + n + "@haja.com")
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
