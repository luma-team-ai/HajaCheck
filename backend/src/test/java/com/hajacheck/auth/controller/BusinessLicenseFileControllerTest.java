package com.hajacheck.auth.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.support.PostgresTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import com.hajacheck.support.PngTestFixtures;

/** 사업자등록증 보호 파일 엔드포인트가 시큐리티 필터체인에서 인증을 요구하는지 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BusinessLicenseFileControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private FileStorageService fileStorage;

    private String storedStorageKey;

    @AfterEach
    void tearDown() {
        if (storedStorageKey != null) {
            fileStorage.delete(storedStorageKey);
            storedStorageKey = null;
        }
        companyMembershipRepository.deleteAll();
        java.util.List<User> users = userRepository.findAll();
        users.forEach(user -> user.assignToCompany(null));
        userRepository.saveAllAndFlush(users);
        companyRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void 사업자등록증조회_비로그인_401() throws Exception {
        mockMvc.perform(get("/api/companies/{companyId}/business-license/{storageKey}",
                        10L, "business-registration/license.png"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 요청 URL은 capture-the-rest 매핑의 실제 HTTP 경로다. Spring은 이 URL의
     * {*storageKey} 값에 선행 slash를 보존하므로 서비스 정규화가 없으면 404가 된다.
     */
    @Test
    void 사업자등록증조회_실제HTTP_captureTheRest선행slash_정상다운로드_200() throws Exception {
        User owner = userRepository.saveAndFlush(User.builder()
                .email("business-license-download-owner@haja.com")
                .name("사업자등록증 소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        FileStorageService.StoredFile stored = fileStorage.storeBytes(
                PngTestFixtures.realPng(), "image/png", "business-registration",
                java.util.List.of("image/png"), 1024);
        storedStorageKey = stored.storageKey();
        Company company = companyRepository.saveAndFlush(Company.createPendingReview(
                owner.getId(), "다운로드테스트회사", "REG-DOWNLOAD-" + owner.getId(), "대표자",
                "서울시", null, stored.url(), "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        companyRepository.saveAndFlush(company);
        companyMembershipRepository.saveAndFlush(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        userRepository.saveAndFlush(owner);

        LoginUser principal = new LoginUser(owner);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        String actualHttpPath = "/api/companies/" + company.getId()
                + "/business-license/" + stored.storageKey();
        mockMvc.perform(get(actualHttpPath).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PngTestFixtures.realPng()));
    }

    @Test
    void 사업자등록증조회_비멤버인증사용자_동일storageKey_403_파일바이트미반환() throws Exception {
        User owner = userRepository.saveAndFlush(User.builder()
                .email("business-license-owner-forbidden@haja.com")
                .name("사업자등록증 소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        FileStorageService.StoredFile stored = fileStorage.storeBytes(
                PngTestFixtures.realPng(), "image/png", "business-registration",
                java.util.List.of("image/png"), 1024);
        storedStorageKey = stored.storageKey();
        Company company = companyRepository.saveAndFlush(Company.createPendingReview(
                owner.getId(), "비멤버차단테스트회사", "REG-FORBIDDEN-" + owner.getId(), "대표자",
                "서울시", null, stored.url(), "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        companyRepository.saveAndFlush(company);
        companyMembershipRepository.saveAndFlush(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        userRepository.saveAndFlush(owner);

        User outsider = userRepository.saveAndFlush(User.builder()
                .email("business-license-outsider@haja.com")
                .name("비멤버 사용자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        LoginUser principal = new LoginUser(outsider);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());

        mockMvc.perform(get("/api/companies/" + company.getId()
                        + "/business-license/" + stored.storageKey())
                        .with(authentication(auth)))
                .andExpect(status().isForbidden())
                // 파일 바이트(진짜 PNG의 IHDR 청크 표식)가 응답에 절대 섞이면 안 된다.
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("IHDR"))));
    }
}
