package com.hajacheck.platformadmin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.CompanyMembershipStatus;
import com.hajacheck.auth.entity.CompanyStatus;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.support.PostgresTestSupport;
import java.util.Map;
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
 * 플랫폼 관리자 콘솔 — 사용자 등록 모달의 기업명 selectbox 후보 목록(#576, PR #626 후속 요구사항) +
 * 기업 가입 승인/반려(#363).
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
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private ObjectMapper objectMapper;

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

    // 실제 가입 흐름(CompanyAccountWriter, #363)은 오너를 PENDING 멤버십으로 남긴다 — 승인/반려
    // 테스트는 이 전제(오너 멤버십 존재)를 재현해야 한다.
    private void savePendingOwnerMembership(Company company) {
        companyMembershipRepository.save(
                CompanyMembership.invite(company.getId(), company.getOwnerUserId(), company.getOwnerUserId(), null));
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

    @Test
    void 기업승인_검증완료_심사대기기업_200_및_오너companyId배선() throws Exception {
        Company company = saveCompany("승인대상건설", CompanyStatus.PENDING_REVIEW);
        company.markBusinessVerified();
        companyRepository.saveAndFlush(company);
        savePendingOwnerMembership(company);

        mockMvc.perform(post("/api/platform-admin/companies/{id}/approve", company.getId())
                        .with(authentication(platformAdminAuth())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        User owner = userRepository.findById(company.getOwnerUserId()).orElseThrow();
        assertThat(owner.getCompanyId()).isEqualTo(company.getId());
        CompanyMembership membership = companyMembershipRepository
                .findByCompanyIdAndUserId(company.getId(), company.getOwnerUserId()).orElseThrow();
        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.APPROVED);
    }

    // 사업자등록정보 검증(VERIFIED)이 끝나지 않은 회사는 승인할 수 없다 — Company.approve() 불변식.
    @Test
    void 기업승인_사업자검증미완료면_409() throws Exception {
        Company company = saveCompany("미검증건설", CompanyStatus.PENDING_REVIEW);
        savePendingOwnerMembership(company);

        mockMvc.perform(post("/api/platform-admin/companies/{id}/approve", company.getId())
                        .with(authentication(platformAdminAuth())).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));

        User owner = userRepository.findById(company.getOwnerUserId()).orElseThrow();
        assertThat(owner.getCompanyId()).isNull();
    }

    @Test
    void 기업승인_존재하지않는기업_404() throws Exception {
        mockMvc.perform(post("/api/platform-admin/companies/{id}/approve", 999_999L)
                        .with(authentication(platformAdminAuth())).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMPANY_NOT_FOUND"));
    }

    @Test
    void 기업반려_심사대기기업_200_및_사유저장_오너companyId는_그대로null() throws Exception {
        Company company = saveCompany("반려대상건설", CompanyStatus.PENDING_REVIEW);
        savePendingOwnerMembership(company);
        String body = objectMapper.writeValueAsString(Map.of("reason", "제출 서류 불일치"));

        mockMvc.perform(post("/api/platform-admin/companies/{id}/reject", company.getId())
                        .with(authentication(platformAdminAuth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.rejectionReason").value("제출 서류 불일치"));

        User owner = userRepository.findById(company.getOwnerUserId()).orElseThrow();
        assertThat(owner.getCompanyId()).isNull();
        CompanyMembership membership = companyMembershipRepository
                .findByCompanyIdAndUserId(company.getId(), company.getOwnerUserId()).orElseThrow();
        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.REJECTED);
    }
}
