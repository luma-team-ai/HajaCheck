package com.hajacheck.membership.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마이페이지 — 내 플랜·사용량·좌석 MVC 통합 테스트(HAJA-177). AuthControllerTest 와 동일하게
 * 전역 시큐리티 필터체인(oauth2Login 포함)이 ClientRegistrationRepository 를 요구해
 * @SpringBootTest+MockMvc(+PostgresTestSupport) 로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MembershipControllerTest extends PostgresTestSupport {

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

    private Plan savePlan() {
        return planRepository.save(Plan.create(PlanName.STANDARD, 10, 1000, 3, false, true, false,
                BigDecimal.valueOf(99000)));
    }

    private User saveUser(String email, Long companyId) {
        return userRepository.save(User.builder()
                .email(email)
                .name("기업사용자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .companyId(companyId)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 내플랜조회_개인구독_200() throws Exception {
        Plan plan = savePlan();
        User user = saveUser("individual@haja.com", null);
        UserPlan userPlan = userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));
        usageCounterRepository.save(UsageCounter.create(
                userPlan.getId(), YearMonth.now().atDay(1), 786, 4, 12, 1, 0, 2));

        mockMvc.perform(get("/api/me/plan").with(authentication(authOf(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.plan.name").value("STANDARD"))
                .andExpect(jsonPath("$.data.plan.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.limits.maxSeats").value(3))
                .andExpect(jsonPath("$.data.usage.analyzedImageCount").value(786))
                .andExpect(jsonPath("$.data.usage.facilityCount").value(4));
    }

    @Test
    void 내플랜조회_활성구독없음_404_PLAN_NOT_FOUND() throws Exception {
        User user = saveUser("noplan@haja.com", null);

        mockMvc.perform(get("/api/me/plan").with(authentication(authOf(user))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("PLAN_NOT_FOUND"));
    }

    @Test
    void 내플랜조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/me/plan"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 좌석조회_회사소속_멤버목록() throws Exception {
        Plan plan = savePlan();
        User owner = saveUser("owner@haja.com", null);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)하자체크", "1234567890", "김민수", "서울시 강남구", null,
                "http://files/brn.png", "{}"));
        owner.assignToCompany(company.getId());
        User member = saveUser("member@haja.com", company.getId());
        userPlanRepository.save(UserPlan.forCompany(company.getId(), plan.getId()));

        mockMvc.perform(get("/api/me/seats").with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.used").value(2))
                .andExpect(jsonPath("$.data.limit").value(3))
                .andExpect(jsonPath("$.data.members.length()").value(2));
    }

    @Test
    void 좌석조회_개인계정_본인1건() throws Exception {
        Plan plan = savePlan();
        User user = saveUser("solo@haja.com", null);
        userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));

        mockMvc.perform(get("/api/me/seats").with(authentication(authOf(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.used").value(1))
                .andExpect(jsonPath("$.data.members[0].email").value("solo@haja.com"));
    }

    @Test
    void 업그레이드문의_소유자본인_200_UPGRADE_REQUESTED() throws Exception {
        Plan plan = savePlan();
        User user = saveUser("upgrade@haja.com", null);
        userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));

        mockMvc.perform(post("/api/me/plan/upgrade-inquiry").with(csrf()).with(authentication(authOf(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UPGRADE_REQUESTED"));
    }

    @Test
    void 업그레이드문의_회사구독_소유자아니면_403_PLAN_FORBIDDEN() throws Exception {
        Plan plan = savePlan();
        User owner = saveUser("owner2@haja.com", null);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)하자체크2", "9876543210", "박영희", "서울시 서초구", null,
                "http://files/brn2.png", "{}"));
        owner.assignToCompany(company.getId());
        User staff = saveUser("staff@haja.com", company.getId());
        userPlanRepository.save(UserPlan.forCompany(company.getId(), plan.getId()));

        mockMvc.perform(post("/api/me/plan/upgrade-inquiry").with(csrf()).with(authentication(authOf(staff))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PLAN_FORBIDDEN"));
    }

    @Test
    void 업그레이드문의_이미요청상태_멱등_200() throws Exception {
        Plan plan = savePlan();
        User user = saveUser("idempotent@haja.com", null);
        UserPlan userPlan = userPlanRepository.save(UserPlan.forUser(user.getId(), plan.getId()));
        userPlan.requestUpgrade();
        userPlanRepository.flush();

        mockMvc.perform(post("/api/me/plan/upgrade-inquiry").with(csrf()).with(authentication(authOf(user))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UPGRADE_REQUESTED"));
    }
}
