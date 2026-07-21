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
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.support.PostgresTestSupport;
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
 * 같은 회사(companyId) 소속으로 스코핑된다 — 관련 테스트는 회사별로 별도 companyId를 명시한다.
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
    private ObjectMapper objectMapper;

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
        User admin = saveUser("관리자", "admin@haja.com", Role.ADMIN, 10L);
        saveUser("일반사용자", "user1@haja.com", Role.USER, 10L);

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
        User admin = saveUser("관리자", "admin-a@haja.com", Role.ADMIN, 11L);
        saveUser("같은회사", "same-company@haja.com", Role.USER, 11L);
        saveUser("다른회사", "other-company@haja.com", Role.USER, 99L);

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
        User admin = saveUser("관리자", "admin@haja.com", Role.ADMIN, 12L);
        saveUser("박지민", "jimin@haja.com", Role.USER, 12L);
        saveUser("최서준", "seojun@haja.com", Role.USER, 12L);

        mockMvc.perform(get("/api/admin/users")
                        .param("keyword", "지민")
                        .with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].email").value("jimin@haja.com"));
    }

    @Test
    void 사용자목록조회_활성구독없으면_plan_FREE로_표시() throws Exception {
        User admin = saveUser("관리자", "admin2@haja.com", Role.ADMIN, 13L);
        saveUser("무구독자", "nosub@haja.com", Role.USER, 13L);

        mockMvc.perform(get("/api/admin/users")
                        .param("keyword", "무구독자")
                        .with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].plan").value("FREE"));
    }

    @Test
    void 사용자목록조회_키워드의_LIKE와일드카드는_리터럴로_취급된다() throws Exception {
        User admin = saveUser("관리자", "admin-wildcard@haja.com", Role.ADMIN, 14L);
        // 언더바(_)는 LIKE에서 "임의의 한 글자" 와일드카드다 — 이스케이프하지 않으면 아래 두 계정이
        // 모두 "jimin_kim" 검색에 매칭된다(jiminXkim의 X가 _와 매칭).
        saveUser("검색대상", "jimin_kim@haja.com", Role.USER, 14L);
        saveUser("오탐후보", "jiminXkim@haja.com", Role.USER, 14L);

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
        User admin = saveUser("관리자", "admin7@haja.com", Role.ADMIN, 42L);
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
        assertThat(saved.getCompanyId()).isEqualTo(42L);
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
        User admin = saveUser("관리자", "admin8@haja.com", Role.ADMIN, 1L);
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
        User admin = saveUser("관리자", "admin9@haja.com", Role.ADMIN, 1L);
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
    void 역할변경_관리자_200과변경된역할반환() throws Exception {
        User admin = saveUser("관리자", "admin3@haja.com", Role.ADMIN, 20L);
        User target = saveUser("일반사용자", "target1@haja.com", Role.USER, 20L);
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
        User admin = saveUser("관리자", "admin4@haja.com", Role.ADMIN, 21L);
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
        User admin = saveUser("관리자", "admin-role-cross@haja.com", Role.ADMIN, 22L);
        User otherCompanyTarget = saveUser("타회사사용자", "other-role-target@haja.com", Role.USER, 88L);
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
        User admin = saveUser("관리자", "admin5@haja.com", Role.ADMIN, 23L);
        User target = saveUser("정지대상", "target3@haja.com", Role.USER, 23L);
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
    void 상태변경_다른회사소속이면_404_USER_NOT_FOUND() throws Exception {
        User admin = saveUser("관리자", "admin-status-cross@haja.com", Role.ADMIN, 24L);
        User otherCompanyTarget = saveUser("타회사사용자2", "other-status-target@haja.com", Role.USER, 89L);
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
        User admin = saveUser("관리자", "admin6@haja.com", Role.ADMIN, 25L);
        User target = saveUser("대상", "target4@haja.com", Role.USER, 25L);

        mockMvc.perform(patch("/api/admin/users/{id}/status", target.getId())
                        .with(authentication(authOf(admin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
