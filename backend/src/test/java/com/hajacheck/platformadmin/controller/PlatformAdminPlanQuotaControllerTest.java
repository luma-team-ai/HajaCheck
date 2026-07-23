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
