package com.hajacheck.platformadmin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.admin.dto.AdminUserRoleUpdateRequest;
import com.hajacheck.admin.dto.AdminUserStatusUpdateRequest;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.platformadmin.dto.PlatformAdminUserCreateRequest;
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
 * 플랫폼 관리자 콘솔 — 사용자 관리(#576) MVC 통합 테스트. AdminUserControllerTest(#405)와 동일 패턴
 * (@SpringBootTest+MockMvc — 전역 시큐리티 필터체인의 "/api/platform-admin/**" hasRole(PLATFORM_ADMIN)를
 * 실제로 태워야 함) 이지만, 이 화면의 핵심 계약은 정반대다: **다른 회사 소속이어도 전부 조회/수정된다.**
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlatformAdminUserControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private static final AtomicLong BRN_SEQ = new AtomicLong(9_100_000_000L);

    private Company saveCompany() {
        return saveCompany(com.hajacheck.auth.entity.CompanyStatus.APPROVED);
    }

    private Company saveCompany(com.hajacheck.auth.entity.CompanyStatus status) {
        long brn = BRN_SEQ.getAndIncrement();
        User owner = saveUser("대표", "owner" + brn + "@haja.com", Role.ADMIN);
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), "(주)테스트" + brn, String.valueOf(brn), "김대표",
                "서울시 강남구", null, "http://files/brn.png", "{}"));
        if (status == com.hajacheck.auth.entity.CompanyStatus.APPROVED) {
            company.markBusinessVerified();
            company.approve(owner.getId());
            companyRepository.saveAndFlush(company);
        }
        return company;
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
    void 목록조회_플랫폼관리자_다른회사사용자도_전부보인다() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa1@haja.com", Role.PLATFORM_ADMIN);
        Company company = saveCompany();
        Company otherCompany = saveCompany();
        saveUser("같은회사", "pa-same@haja.com", Role.USER, company.getId());
        saveUser("다른회사", "pa-other@haja.com", Role.USER, otherCompany.getId());
        saveUser("개인계정", "pa-individual@haja.com", Role.USER);

        // saveCompany()가 만드는 owner 유저(회사당 1명, companyId=null인 개인 ADMIN)도 전사 목록에 함께
        // 잡힌다 — company/otherCompany 2곳 owner 2명 + 아래 명시 3명 = 5명.
        mockMvc.perform(get("/api/platform-admin/users").with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(5))
                .andExpect(jsonPath("$.data.stats.totalMembers").value(5));
    }

    @Test
    void 목록조회_PLATFORM_ADMIN자신은_목록에서제외된다() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa2@haja.com", Role.PLATFORM_ADMIN);
        User anotherPlatformAdmin = saveUser("동료플랫폼관리자", "pa2-peer@haja.com", Role.PLATFORM_ADMIN);
        saveUser("일반사용자", "pa2-user@haja.com", Role.USER);

        mockMvc.perform(get("/api/platform-admin/users").with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("pa2-user@haja.com"));
    }

    @Test
    void 목록조회_기업명으로검색된다() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa3@haja.com", Role.PLATFORM_ADMIN);
        Company company = companyRepository.save(Company.createPendingReview(
                saveUser("대표3", "owner-pa3@haja.com", Role.ADMIN).getId(),
                "그린타워시설관리", String.valueOf(BRN_SEQ.getAndIncrement()), "김대표",
                "서울시", null, "http://files/brn.png", "{}"));
        saveUser("소속직원", "pa3-member@haja.com", Role.USER, company.getId());
        saveUser("무관계정", "pa3-unrelated@haja.com", Role.USER);

        mockMvc.perform(get("/api/platform-admin/users")
                        .param("keyword", "그린타워")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("pa3-member@haja.com"))
                .andExpect(jsonPath("$.data.content[0].companyName").value("그린타워시설관리"));
    }

    @Test
    void 목록조회_개인계정은_companyName이_null() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa4@haja.com", Role.PLATFORM_ADMIN);
        saveUser("개인계정", "pa4-individual@haja.com", Role.USER);

        mockMvc.perform(get("/api/platform-admin/users")
                        .param("keyword", "pa4-individual")
                        .with(authentication(authOf(platformAdmin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].companyId").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].companyName").doesNotExist());
    }

    @Test
    void 목록조회_회사ADMIN이면_403_FORBIDDEN() throws Exception {
        Company company = saveCompany();
        User companyAdmin = saveUser("회사관리자", "pa5-admin@haja.com", Role.ADMIN, company.getId());

        mockMvc.perform(get("/api/platform-admin/users").with(authentication(authOf(companyAdmin))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 목록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/platform-admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void 사용자등록_companyId지정시_해당회사로배선() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa6@haja.com", Role.PLATFORM_ADMIN);
        Company company = saveCompany();
        PlatformAdminUserCreateRequest request =
                new PlatformAdminUserCreateRequest("pa6-new@haja.com", "password1", "신규사용자", Role.USER, company.getId());

        mockMvc.perform(post("/api/platform-admin/users")
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.email").value("pa6-new@haja.com"))
                .andExpect(jsonPath("$.data.companyId").value(company.getId()));

        User saved = userRepository.findByEmail("pa6-new@haja.com").orElseThrow();
        assertThat(saved.getCompanyId()).isEqualTo(company.getId());
    }

    @Test
    void 사용자등록_companyId없으면_개인계정으로등록() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa7@haja.com", Role.PLATFORM_ADMIN);
        PlatformAdminUserCreateRequest request =
                new PlatformAdminUserCreateRequest("pa7-new@haja.com", "password1", "개인신규", Role.USER, null);

        mockMvc.perform(post("/api/platform-admin/users")
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.companyId").doesNotExist());

        User saved = userRepository.findByEmail("pa7-new@haja.com").orElseThrow();
        assertThat(saved.getCompanyId()).isNull();
    }

    @Test
    void 사용자등록_존재하지않는companyId면_404_COMPANY_NOT_FOUND() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa8@haja.com", Role.PLATFORM_ADMIN);
        PlatformAdminUserCreateRequest request =
                new PlatformAdminUserCreateRequest("pa8-new@haja.com", "password1", "실패대상", Role.USER, 999_999L);

        mockMvc.perform(post("/api/platform-admin/users")
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMPANY_NOT_FOUND"));

        assertThat(userRepository.findByEmail("pa8-new@haja.com")).isEmpty();
    }

    @Test
    void 사용자등록_승인대기companyId면_404_COMPANY_NOT_FOUND() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa8b@haja.com", Role.PLATFORM_ADMIN);
        Company pendingCompany = saveCompany(com.hajacheck.auth.entity.CompanyStatus.PENDING_REVIEW);
        PlatformAdminUserCreateRequest request = new PlatformAdminUserCreateRequest(
                "pa8b-new@haja.com", "password1", "실패대상2", Role.USER, pendingCompany.getId());

        mockMvc.perform(post("/api/platform-admin/users")
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMPANY_NOT_FOUND"));

        assertThat(userRepository.findByEmail("pa8b-new@haja.com")).isEmpty();
    }

    @Test
    void 사용자등록_이메일중복이면_409() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa9@haja.com", Role.PLATFORM_ADMIN);
        saveUser("기존사용자", "pa9-dup@haja.com", Role.USER);
        PlatformAdminUserCreateRequest request =
                new PlatformAdminUserCreateRequest("pa9-dup@haja.com", "password1", "중복", Role.USER, null);

        mockMvc.perform(post("/api/platform-admin/users")
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH_EMAIL_DUPLICATED"));
    }

    @Test
    void 사용자등록_화이트리스트밖역할이면_400() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa10@haja.com", Role.PLATFORM_ADMIN);
        PlatformAdminUserCreateRequest request =
                new PlatformAdminUserCreateRequest("pa10-new@haja.com", "password1", "상담원시도", Role.COUNSELOR, null);

        mockMvc.perform(post("/api/platform-admin/users")
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ADMIN_ROLE_NOT_ASSIGNABLE"));
    }

    @Test
    void 사용자등록_회사ADMIN이면_403() throws Exception {
        Company company = saveCompany();
        User companyAdmin = saveUser("회사관리자", "pa11-admin@haja.com", Role.ADMIN, company.getId());
        PlatformAdminUserCreateRequest request =
                new PlatformAdminUserCreateRequest("pa11-new@haja.com", "password1", "차단대상", Role.USER, null);

        mockMvc.perform(post("/api/platform-admin/users")
                        .with(authentication(authOf(companyAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 역할변경_다른회사소속사용자도_변경할수있다() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa12@haja.com", Role.PLATFORM_ADMIN);
        Company otherCompany = saveCompany();
        User target = saveUser("타회사사용자", "pa12-target@haja.com", Role.USER, otherCompany.getId());
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.INSPECTOR));

        mockMvc.perform(patch("/api/platform-admin/users/{id}/role", target.getId())
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("INSPECTOR"));
    }

    @Test
    void 역할변경_대상사용자없으면_404() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa13@haja.com", Role.PLATFORM_ADMIN);
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.INSPECTOR));

        mockMvc.perform(patch("/api/platform-admin/users/{id}/role", 999_999L)
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test
    void 역할변경_대상이_PLATFORM_ADMIN이면_404() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa14@haja.com", Role.PLATFORM_ADMIN);
        User anotherPlatformAdmin = saveUser("동료", "pa14-peer@haja.com", Role.PLATFORM_ADMIN);
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.USER));

        mockMvc.perform(patch("/api/platform-admin/users/{id}/role", anotherPlatformAdmin.getId())
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
    }

    @Test
    void 역할변경_회사의마지막활성ADMIN강등시도는_409() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa15@haja.com", Role.PLATFORM_ADMIN);
        Company company = saveCompany();
        User lastAdmin = saveUser("마지막관리자", "pa15-admin@haja.com", Role.ADMIN, company.getId());
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.USER));

        mockMvc.perform(patch("/api/platform-admin/users/{id}/role", lastAdmin.getId())
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_PROTECTED_ACCOUNT"));

        User unchanged = userRepository.findById(lastAdmin.getId()).orElseThrow();
        assertThat(unchanged.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void 역할변경_회사에다른활성ADMIN이남아있으면_강등가능() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa16@haja.com", Role.PLATFORM_ADMIN);
        Company company = saveCompany();
        saveUser("남는관리자", "pa16-remaining@haja.com", Role.ADMIN, company.getId());
        User target = saveUser("강등대상", "pa16-target@haja.com", Role.ADMIN, company.getId());
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.USER));

        mockMvc.perform(patch("/api/platform-admin/users/{id}/role", target.getId())
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    void 역할변경_화이트리스트밖역할이면_400() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa17@haja.com", Role.PLATFORM_ADMIN);
        User target = saveUser("대상", "pa17-target@haja.com", Role.USER);
        String body = objectMapper.writeValueAsString(new AdminUserRoleUpdateRequest(Role.COUNSELOR));

        mockMvc.perform(patch("/api/platform-admin/users/{id}/role", target.getId())
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("ADMIN_ROLE_NOT_ASSIGNABLE"));
    }

    @Test
    void 상태변경_다른회사소속사용자도_변경할수있다() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa18@haja.com", Role.PLATFORM_ADMIN);
        Company otherCompany = saveCompany();
        User target = saveUser("타회사사용자", "pa18-target@haja.com", Role.USER, otherCompany.getId());
        String body = objectMapper.writeValueAsString(new AdminUserStatusUpdateRequest(UserStatus.SUSPENDED));

        mockMvc.perform(patch("/api/platform-admin/users/{id}/status", target.getId())
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
    }

    @Test
    void 상태변경_회사의마지막활성ADMIN정지시도는_409() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa19@haja.com", Role.PLATFORM_ADMIN);
        Company company = saveCompany();
        User lastAdmin = saveUser("마지막관리자2", "pa19-admin@haja.com", Role.ADMIN, company.getId());
        String body = objectMapper.writeValueAsString(new AdminUserStatusUpdateRequest(UserStatus.SUSPENDED));

        mockMvc.perform(patch("/api/platform-admin/users/{id}/status", lastAdmin.getId())
                        .with(authentication(authOf(platformAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ADMIN_PROTECTED_ACCOUNT"));
    }

    @Test
    void 상태변경_회사ADMIN이면_403() throws Exception {
        Company company = saveCompany();
        User companyAdmin = saveUser("회사관리자", "pa20-admin@haja.com", Role.ADMIN, company.getId());
        User target = saveUser("대상", "pa20-target@haja.com", Role.USER, company.getId());
        String body = objectMapper.writeValueAsString(new AdminUserStatusUpdateRequest(UserStatus.SUSPENDED));

        mockMvc.perform(patch("/api/platform-admin/users/{id}/status", target.getId())
                        .with(authentication(authOf(companyAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void 세션재검증_정지된계정의_기존세션은_401로_차단된다() throws Exception {
        User platformAdmin = saveUser("플랫폼관리자", "pa21@haja.com", Role.PLATFORM_ADMIN);
        User target = saveUser("정지대상세션", "pa21-target@haja.com", Role.PLATFORM_ADMIN);
        UsernamePasswordAuthenticationToken staleAuth = authOf(target);

        // target 자신은 PLATFORM_ADMIN이라 이 엔드포인트의 관리 대상이 아니므로(404), UserRepository로
        // 직접 상태를 바꿔 세션 재검증 필터만 검증한다.
        target.changeStatus(UserStatus.SUSPENDED);
        userRepository.saveAndFlush(target);

        mockMvc.perform(get("/api/platform-admin/users").with(authentication(staleAuth)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
