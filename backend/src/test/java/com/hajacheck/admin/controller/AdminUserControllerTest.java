package com.hajacheck.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.admin.dto.AdminUserCreateRequest;
import com.hajacheck.admin.dto.AdminUserRoleUpdateRequest;
import com.hajacheck.admin.dto.AdminUserStatusUpdateRequest;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.support.PostgresTestSupport;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 사용자 관리(#405) MVC 통합 테스트. MembershipControllerTest 와 동일 패턴 —
 * 전역 시큐리티 필터체인(SecurityConfig 의 "/api/admin/**" hasRole(ADMIN)) 을 실제로 태워야 하므로
 * @SpringBootTest+MockMvc(+PostgresTestSupport) 로 검증한다.
 *
 * <p>이 화면은 기업 관리자 전용이라(플랫폼 관리자 화면은 별도 예정) 모든 조회/변경은 요청 관리자와
 * 같은 회사(companyId) 소속으로 스코핑된다 — 관련 테스트는 회사별로 별도 Company 행을 만들어 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminUserControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private ObjectMapper objectMapper;

    // users.company_id / companies.business_registration_number 는 DDL 상 FK/unique 이므로
    // 리터럴 companyId 대신 실제 Company 행을 먼저 저장하고 그 id 를 써야 한다(FK 위반 방지,
    // MembershipRepositoryTest.saveCompany 와 동일 패턴). 사업자번호는 호출마다 겹치지 않도록 시퀀스로 발급.
    private static final AtomicLong BRN_SEQ = new AtomicLong(9_000_000_000L);

    private Company saveCompany() {
        long brn = BRN_SEQ.getAndIncrement();
        User owner = saveUser("대표", "owner" + brn + "@haja.com", Role.ADMIN);
        return companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)테스트", String.valueOf(brn), "김대표",
                "서울시 강남구", null, "http://files/brn.png", "{}"));
    }

    private User saveUser(String name, String email, Role role) {
        return saveUser(name, email, role, null);
    }

    private User saveUser(String name, String email, Role role, Long companyId) {
        return userRepository.save(User.builder()
                .email(email)
                .name(name)
                .role(role)
                .companyId(companyId)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 사용자목록조회_관리자_200_같은회사통계와함께반환() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin@haja.com", Role.ADMIN, company.getId());
        saveUser("일반사용자", "user1@haja.com", Role.USER, company.getId());

        mockMvc.perform(get("/api/admin/users").with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.stats.totalMembers").value(2))
                .andExpect(jsonPath("$.data.stats.active").value(2))
                .andExpect(jsonPath("$.data.stats.suspended").value(0));
    }

    @Test
    void 사용자목록조회_다른회사사용자는보이지않는다() throws Exception {
        Company company = saveCompany();
        Company otherCompany = saveCompany();
        User admin = saveUser("관리자", "admin-a@haja.com", Role.ADMIN, company.getId());
        saveUser("같은회사", "same-company@haja.com", Role.USER, company.getId());
        saveUser("다른회사", "other-company@haja.com", Role.USER, otherCompany.getId());

        mockMvc.perform(get("/api/admin/users").with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2)) // admin 본인 + same-company
                .andExpect(jsonPath("$.data.stats.totalMembers").value(2));
    }

    @Test
    void 사용자목록조회_요청관리자에게회사가없으면_403_FORBIDDEN() throws Exception {
        User admin = saveUser("무소속관리자", "no-company-admin@haja.com", Role.ADMIN);

        mockMvc.perform(get("/api/admin/users").with(authentication(authOf(admin))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 사용자목록조회_키워드검색() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin@haja.com", Role.ADMIN, company.getId());
        saveUser("박지민", "jimin@haja.com", Role.USER, company.getId());
        saveUser("최서준", "seojun@haja.com", Role.USER, company.getId());

        mockMvc.perform(get("/api/admin/users")
                        .param("keyword", "지민")
                        .with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("jimin@haja.com"));
    }

    @Test
    void 사용자목록조회_활성구독없으면_plan_FREE로_표시() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin2@haja.com", Role.ADMIN, company.getId());
        saveUser("무구독자", "nosub@haja.com", Role.USER, company.getId());

        mockMvc.perform(get("/api/admin/users")
                        .param("keyword", "무구독자")
                        .with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].plan").value("FREE"));
    }

    @Test
    void 사용자목록조회_키워드의_LIKE와일드카드는_리터럴로_취급된다() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin-wildcard@haja.com", Role.ADMIN, company.getId());
        // 언더바(_)는 LIKE에서 "임의의 한 글자" 와일드카드다 — 이스케이프하지 않으면 아래 두 계정이
        // 모두 "jimin_kim" 검색에 매칭된다(jiminXkim의 X가 _와 매칭).
        saveUser("검색대상", "jimin_kim@haja.com", Role.USER, company.getId());
        saveUser("오탐후보", "jiminXkim@haja.com", Role.USER, company.getId());

        mockMvc.perform(get("/api/admin/users")
                        .param("keyword", "jimin_kim")
                        .with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("jimin_kim@haja.com"));
    }

    @Test
    void 사용자목록조회_일반사용자면_403_FORBIDDEN() throws Exception {
        User normalUser = saveUser("일반사용자", "notadmin@haja.com", Role.USER);

        mockMvc.perform(get("/api/admin/users").with(authentication(authOf(normalUser))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 사용자목록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 사용자등록_관리자_201과등록한관리자의company로배선() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin7@haja.com", Role.ADMIN, company.getId());
        AdminUserCreateRequest request =
                new AdminUserCreateRequest("newbie@haja.com", "password1", "새사용자", Role.USER);

        mockMvc.perform(post("/api/admin/users")
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.email").value("newbie@haja.com"))
                .andExpect(jsonPath("$.data.name").value("새사용자"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.plan").value("FREE"));

        User saved = userRepository.findByEmail("newbie@haja.com").orElseThrow();
        assertThat(saved.getCompanyId()).isEqualTo(company.getId());
    }

    @Test
    void 사용자등록_요청관리자에게회사가없으면_403_FORBIDDEN() throws Exception {
        User admin = saveUser("무소속관리자", "no-company-admin2@haja.com", Role.ADMIN);
        AdminUserCreateRequest request =
                new AdminUserCreateRequest("orphan@haja.com", "password1", "고아사용자", Role.USER);

        mockMvc.perform(post("/api/admin/users")
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 사용자등록_이메일중복이면_409() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin8@haja.com", Role.ADMIN, company.getId());
        saveUser("기존사용자", "dup@haja.com", Role.USER);
        AdminUserCreateRequest request =
                new AdminUserCreateRequest("dup@haja.com", "password1", "중복사용자", Role.USER);

        mockMvc.perform(post("/api/admin/users")
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_DUPLICATED"));
    }

    @Test
    void 사용자등록_비밀번호형식불충족이면_400() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin9@haja.com", Role.ADMIN, company.getId());
        AdminUserCreateRequest request =
                new AdminUserCreateRequest("weakpw@haja.com", "short", "약한비번", Role.USER);

        mockMvc.perform(post("/api/admin/users")
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 사용자등록_일반사용자면_403_FORBIDDEN() throws Exception {
        User normalUser = saveUser("일반사용자", "notadmin3@haja.com", Role.USER);
        AdminUserCreateRequest request =
                new AdminUserCreateRequest("blocked@haja.com", "password1", "차단대상", Role.USER);

        mockMvc.perform(post("/api/admin/users")
                        .with(authentication(authOf(normalUser))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 사용자등록_화이트리스트밖역할이면_400_ADMIN_ROLE_NOT_ASSIGNABLE() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin10@haja.com", Role.ADMIN, company.getId());
        AdminUserCreateRequest request =
                new AdminUserCreateRequest("counselor@haja.com", "password1", "상담원시도", Role.COUNSELOR);

        mockMvc.perform(post("/api/admin/users")
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ADMIN_ROLE_NOT_ASSIGNABLE"));

        assertThat(userRepository.findByEmail("counselor@haja.com")).isEmpty();
    }

    @Test
    void 역할변경_관리자_200과변경된역할반환() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin3@haja.com", Role.ADMIN, company.getId());
        User target = saveUser("일반사용자", "target1@haja.com", Role.USER, company.getId());
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.INSPECTOR));

        mockMvc.perform(patch("/api/admin/users/{id}/role", target.getId())
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(target.getId()))
                .andExpect(jsonPath("$.data.role").value("INSPECTOR"));
    }

    @Test
    void 역할변경_대상사용자없으면_404() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin4@haja.com", Role.ADMIN, company.getId());
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.INSPECTOR));

        mockMvc.perform(patch("/api/admin/users/{id}/role", 999_999L)
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test
    void 역할변경_다른회사소속이면_404_USER_NOT_FOUND() throws Exception {
        Company company = saveCompany();
        Company otherCompany = saveCompany();
        User admin = saveUser("관리자", "admin-role-cross@haja.com", Role.ADMIN, company.getId());
        User otherCompanyTarget = saveUser("타회사사용자", "other-role-target@haja.com", Role.USER, otherCompany.getId());
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.INSPECTOR));

        mockMvc.perform(patch("/api/admin/users/{id}/role", otherCompanyTarget.getId())
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

        User unchanged = userRepository.findById(otherCompanyTarget.getId()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void 역할변경_화이트리스트밖역할이면_400_ADMIN_ROLE_NOT_ASSIGNABLE() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin-role-wl@haja.com", Role.ADMIN, company.getId());
        User target = saveUser("대상", "target-role-wl@haja.com", Role.USER, company.getId());
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.COUNSELOR));

        mockMvc.perform(patch("/api/admin/users/{id}/role", target.getId())
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ADMIN_ROLE_NOT_ASSIGNABLE"));

        User unchanged = userRepository.findById(target.getId()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void 역할변경_관리자가_자기자신을_강등시도하면_409_ADMIN_PROTECTED_ACCOUNT() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin-self-demote@haja.com", Role.ADMIN, company.getId());
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.USER));

        mockMvc.perform(patch("/api/admin/users/{id}/role", admin.getId())
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_PROTECTED_ACCOUNT"));

        User unchanged = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void 역할변경_회사에_활성ADMIN이_요청자뿐이면_다른관리자강등도_409() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin-last-active@haja.com", Role.ADMIN, company.getId());
        // 이미 SUSPENDED라 실질적으로 콘솔에 접근 못 하는 관리자 — 그래도 강등 순간엔 활성 ADMIN이 0명이 된다.
        User suspendedAdmin = saveUser("정지된관리자", "admin-suspended@haja.com", Role.ADMIN, company.getId());
        suspendedAdmin.changeStatus(UserStatus.SUSPENDED);
        userRepository.saveAndFlush(suspendedAdmin);
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.USER));

        mockMvc.perform(patch("/api/admin/users/{id}/role", suspendedAdmin.getId())
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_PROTECTED_ACCOUNT"));

        User unchanged = userRepository.findById(suspendedAdmin.getId()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void 역할변경_일반사용자면_403_FORBIDDEN() throws Exception {
        User normalUser = saveUser("일반사용자", "notadmin2@haja.com", Role.USER);
        User target = saveUser("대상", "target2@haja.com", Role.USER);
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.INSPECTOR));

        mockMvc.perform(patch("/api/admin/users/{id}/role", target.getId())
                        .with(authentication(authOf(normalUser))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void 상태변경_관리자_200과변경된상태반환() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin5@haja.com", Role.ADMIN, company.getId());
        User target = saveUser("정지대상", "target3@haja.com", Role.USER, company.getId());
        String body = objectMapper.writeValueAsString(new AdminUserStatusUpdateRequest(UserStatus.SUSPENDED));

        mockMvc.perform(patch("/api/admin/users/{id}/status", target.getId())
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(target.getId()))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @Test
    void 상태변경_관리자가_자기자신을_정지시도하면_409_ADMIN_PROTECTED_ACCOUNT() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin-self-suspend@haja.com", Role.ADMIN, company.getId());
        String body = objectMapper.writeValueAsString(new AdminUserStatusUpdateRequest(UserStatus.SUSPENDED));

        mockMvc.perform(patch("/api/admin/users/{id}/status", admin.getId())
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_PROTECTED_ACCOUNT"));

        User unchanged = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void 상태변경_다른회사소속이면_404_USER_NOT_FOUND() throws Exception {
        Company company = saveCompany();
        Company otherCompany = saveCompany();
        User admin = saveUser("관리자", "admin-status-cross@haja.com", Role.ADMIN, company.getId());
        User otherCompanyTarget = saveUser("타회사사용자2", "other-status-target@haja.com", Role.USER, otherCompany.getId());
        String body = objectMapper.writeValueAsString(new AdminUserStatusUpdateRequest(UserStatus.SUSPENDED));

        mockMvc.perform(patch("/api/admin/users/{id}/status", otherCompanyTarget.getId())
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

        User unchanged = userRepository.findById(otherCompanyTarget.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void 상태변경_status값없으면_400() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin6@haja.com", Role.ADMIN, company.getId());
        User target = saveUser("대상", "target4@haja.com", Role.USER, company.getId());

        mockMvc.perform(patch("/api/admin/users/{id}/status", target.getId())
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // SessionUserRevalidationFilter(#405 리뷰 P1) — 정지/강등이 DB에는 반영돼도, 대상이 들고 있던
    // "이전" Authentication(=정지/강등 전 스냅샷, 실제 서비스에서는 Redis에 저장된 기존 세션에 해당)을
    // 그대로 재사용하면 필터가 요청 시점에 DB 최신 상태로 재검증해 즉시 차단해야 한다.

    @Test
    void 세션재검증_정지된계정의_기존세션은_401로_차단된다() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin-revalidate1@haja.com", Role.ADMIN, company.getId());
        User target = saveUser("정지대상세션", "target-revalidate1@haja.com", Role.ADMIN, company.getId());
        UsernamePasswordAuthenticationToken staleAuth = authOf(target); // 정지되기 전 스냅샷

        String suspendBody = objectMapper.writeValueAsString(new AdminUserStatusUpdateRequest(UserStatus.SUSPENDED));
        mockMvc.perform(patch("/api/admin/users/{id}/status", target.getId())
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suspendBody))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/users").with(authentication(staleAuth)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 세션재검증_강등된관리자의_기존세션은_403으로_차단된다() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin-revalidate2@haja.com", Role.ADMIN, company.getId());
        User target = saveUser("강등대상세션", "target-revalidate2@haja.com", Role.ADMIN, company.getId());
        UsernamePasswordAuthenticationToken staleAuth = authOf(target); // 아직 ADMIN이던 시절 스냅샷

        String demoteBody = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.USER));
        mockMvc.perform(patch("/api/admin/users/{id}/role", target.getId())
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(demoteBody))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/users").with(authentication(staleAuth)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }
}
