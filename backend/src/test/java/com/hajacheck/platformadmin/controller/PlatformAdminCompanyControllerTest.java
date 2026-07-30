package com.hajacheck.platformadmin.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyStatus;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 관리자 콘솔 — 사용자 등록 모달의 기업명 selectbox 후보 목록(#576, PR #626 후속 요구사항).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlatformAdminCompanyControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;

    private static final AtomicLong BRN_SEQ = new AtomicLong(9_200_000_000L);

    private Company saveCompany(String name, CompanyStatus status) {
        long brn = BRN_SEQ.getAndIncrement();
        User owner = userRepository.save(User.builder()
                .email("owner" + brn + "@haja.com")
                .name("대표")
                .role(Role.ADMIN)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        Company company = companyRepository.save(Company.createPendingReview(
                owner.getId(), name, String.valueOf(brn), "김대표",
                "서울시", null, "http://files/brn.png", "{}"));
        if (status == CompanyStatus.APPROVED) {
            company.markBusinessVerified();
            company.approve(owner.getId());
            companyRepository.saveAndFlush(company);
        }
        return company;
    }

    private UsernamePasswordAuthenticationToken platformAdminAuth() {
        User user = userRepository.save(User.builder()
                .email("company-list-pa@haja.com")
                .name("플랫폼관리자")
                .role(Role.PLATFORM_ADMIN)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 기업목록조회_승인된기업만_이름순으로반환된다() throws Exception {
        saveCompany("나은건설", CompanyStatus.APPROVED);
        saveCompany("가나건설", CompanyStatus.APPROVED);
        saveCompany("승인대기건설", CompanyStatus.PENDING_REVIEW);

        mockMvc.perform(get("/api/platform-admin/companies").with(authentication(platformAdminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("가나건설"))
                .andExpect(jsonPath("$.data[1].name").value("나은건설"));
    }

    @Test
    void 기업목록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/platform-admin/companies"))
                .andExpect(status().isUnauthorized());
    }
}
