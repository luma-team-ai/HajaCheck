package com.hajacheck.platformadmin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.platformadmin.dto.CompanyVerificationActionRequest;
import com.hajacheck.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회사 검증 무효화 킬스위치 + 복구 왕복(#1367) 통합 테스트.
 *
 * <p><b>왜 실 PostgreSQL(Testcontainers) MVC 통합인가</b> — 세 가지가 목(mock)으로는 검증되지 않는다:
 * <ul>
 *   <li><b>인가 경계</b>: {@code "/api/platform-admin/**" → hasRole(PLATFORM_ADMIN)} 는 필터체인이
 *       강제한다. 회사 관리자(ROLE_ADMIN)에게 킬스위치가 열리면 남의 회사도 아닌 <b>자기 회사</b>를
 *       스스로 잠그거나(그리고 못 푼다) 하는 경로가 생긴다 — "/api/admin/**" 와 절대 겹치지 않는다는
 *       설계 §6 원칙의 회귀 방지.</li>
 *   <li><b>스코프 실차단</b>: 무효화가 실제로 전 구성원을 막는지는 스코프 판정 쿼리
 *       ({@code existsEffectiveApprovedMembership})를 실 DB 에서 돌려야 알 수 있다.</li>
 *   <li><b>왕복 완성</b>: 복구된 회사가 재검증 대상에 다시 잡히는지는 jsonb 연산자를 쓰는 네이티브
 *       쿼리({@code findNtsReverifyTargets})라 H2 로 검증할 수 없다.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PlatformAdminCompanyVerificationControllerTest extends PostgresTestSupport {

    private static final String REVOKE_URL = "/api/platform-admin/companies/{id}/verification/revoke";
    private static final String RESTORE_URL = "/api/platform-admin/companies/{id}/verification/restore";
    private static final String VERIFICATION_URL = "/api/platform-admin/companies/{id}/verification";
    private static final LocalDate START_DATE = LocalDate.of(2020, 1, 1);
    private static final AtomicLong BRN_SEQ = new AtomicLong(9_300_000_000L);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @PersistenceContext
    private EntityManager entityManager;

    /** 자동승인(#1324) 직후 모양 — APPROVED + VERIFIED + 오너의 유효 APPROVED 멤버십까지 갖춘 회사. */
    private Company approvedCompany(LocalDate businessStartDate) {
        long brn = BRN_SEQ.getAndIncrement();
        User owner = userRepository.saveAndFlush(
                User.createCompanyOwner("owner" + brn + "@haja.test", "대표", "$2a$10$hashed"));
        Company company = companyRepository.saveAndFlush(Company.createPendingReview(
                owner.getId(), "(주)킬스위치" + brn, String.valueOf(brn), "대표",
                "서울시", null, "http://files/brn.png",
                "{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"SKIPPED\"}", businessStartDate));
        company.markBusinessVerified();
        company.autoApprove();
        companyRepository.saveAndFlush(company);
        owner.assignToCompany(company.getId());
        userRepository.saveAndFlush(owner);
        companyMembershipRepository.saveAndFlush(
                CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        return company;
    }

    private User saveUser(String email, Role role, Long companyId) {
        User user = User.builder()
                .email(email)
                .name("사용자")
                .role(role)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .companyId(companyId)
                .build();
        return userRepository.saveAndFlush(user);
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private UsernamePasswordAuthenticationToken platformAdmin() {
        return authOf(saveUser("pa" + BRN_SEQ.getAndIncrement() + "@haja.test", Role.PLATFORM_ADMIN, null));
    }

    private String body(String reason) throws Exception {
        return objectMapper.writeValueAsString(new CompanyVerificationActionRequest(reason));
    }

    private Company reload(Long companyId) {
        entityManager.flush();
        entityManager.clear();
        return companyRepository.findById(companyId).orElseThrow();
    }

    private List<Long> reverifyTargetIds() {
        entityManager.flush();
        entityManager.clear();
        return companyRepository.findNtsReverifyTargets(PageRequest.of(0, 50))
                .stream().map(Company::getId).toList();
    }

    // ── 인가 경계 ────────────────────────────────────────────────────────────────────────────

    @Test
    void 무효화_회사ADMIN이면_403이고_상태는_그대로다() throws Exception {
        Company company = approvedCompany(START_DATE);
        User companyAdmin = saveUser("admin-403@haja.test", Role.ADMIN, company.getId());

        mockMvc.perform(post(REVOKE_URL, company.getId())
                        .with(authentication(authOf(companyAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("자기 회사 무효화 시도")))
                .andExpect(status().isForbidden());

        assertThat(reload(company.getId()).getVerificationStatus())
                .isEqualTo(BusinessVerificationStatus.VERIFIED);
    }

    @Test
    void 복구_회사ADMIN이면_403() throws Exception {
        Company company = approvedCompany(START_DATE);
        User companyAdmin = saveUser("admin-403b@haja.test", Role.ADMIN, company.getId());

        mockMvc.perform(post(RESTORE_URL, company.getId())
                        .with(authentication(authOf(companyAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("사유")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 검증상태조회_일반사용자면_403_미인증이면_401() throws Exception {
        Company company = approvedCompany(START_DATE);
        User normalUser = saveUser("user-403@haja.test", Role.USER, company.getId());

        mockMvc.perform(get(VERIFICATION_URL, company.getId())
                        .with(authentication(authOf(normalUser))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(VERIFICATION_URL, company.getId()))
                .andExpect(status().isUnauthorized());
    }

    // ── 조회 ────────────────────────────────────────────────────────────────────────────────

    @Test
    void 검증상태조회_차단판단근거를_반환한다() throws Exception {
        Company company = approvedCompany(START_DATE);

        mockMvc.perform(get(VERIFICATION_URL, company.getId())
                        .with(authentication(platformAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyId").value(company.getId()))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.verificationStatus").value("VERIFIED"))
                // 자동승인은 국세청 확인 없이 인가 플래그만 올린다 — provenance 는 SKIPPED, 배지는 꺼짐.
                .andExpect(jsonPath("$.data.ntsOutcome").value("SKIPPED"))
                .andExpect(jsonPath("$.data.ntsVerified").value(false))
                .andExpect(jsonPath("$.data.hasBusinessStartDate").value(true))
                .andExpect(jsonPath("$.data.activeMemberCount").value(1));
    }

    @Test
    void 검증상태조회_없는회사면_404() throws Exception {
        mockMvc.perform(get(VERIFICATION_URL, 99_999_999L)
                        .with(authentication(platformAdmin())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("COMPANY_NOT_FOUND"));
    }

    // ── 무효화(킬스위치) ─────────────────────────────────────────────────────────────────────

    @Test
    void 무효화_FAILED로전이하고_provenance에_직전판정과사유가남는다() throws Exception {
        Company company = approvedCompany(START_DATE);

        mockMvc.perform(post(REVOKE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("사칭 신고 접수 — 실제 사업장 미확인")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.ntsOutcome").value("ADMIN_REVOKED"))
                .andExpect(jsonPath("$.data.ntsVerified").value(false));

        Company reloaded = reload(company.getId());
        assertThat(reloaded.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED);
        assertThat(reloaded.ntsOutcome()).contains("ADMIN_REVOKED");
        // 무효화는 회사 승인 상태(status)를 건드리지 않는다 — 차단은 검증 플래그가 한다.
        assertThat(reloaded.getStatus()).isEqualTo(company.getStatus());
    }

    @Test
    void 무효화_사유가공백이면_400이고_상태불변() throws Exception {
        Company company = approvedCompany(START_DATE);

        mockMvc.perform(post(REVOKE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ")))
                .andExpect(status().isBadRequest());

        assertThat(reload(company.getId()).getVerificationStatus())
                .isEqualTo(BusinessVerificationStatus.VERIFIED);
    }

    @Test
    void 무효화_두번하면_409이고_최초사유가보존된다() throws Exception {
        Company company = approvedCompany(START_DATE);
        mockMvc.perform(post(REVOKE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("최초 사유")))
                .andExpect(status().isOk());

        mockMvc.perform(post(REVOKE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("나중 사유")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMPANY_VERIFICATION_ALREADY_REVOKED"));

        assertThat(reload(company.getId()).getBusinessRegistrationOcrRaw()).contains("최초 사유");
    }

    /**
     * 무효화가 <b>실제로</b> 회사 스코프를 닫는지 — 오너를 포함한 전 구성원 기준.
     * 멤버십 행은 그대로 두므로(의도적), 이 단언이 깨지면 차단 수단 자체가 사라진다.
     */
    @Test
    void 무효화후_오너를포함한_전구성원의_스코프판정이_false가된다() throws Exception {
        Company company = approvedCompany(START_DATE);
        Long ownerId = company.getOwnerUserId();
        User member = saveUser("member-scope@haja.test", Role.INSPECTOR, company.getId());
        companyMembershipRepository.saveAndFlush(
                CompanyMembership.approvedMember(company.getId(), member.getId()));
        entityManager.flush();
        entityManager.clear();

        // 전제 — 무효화 전에는 둘 다 열려 있다.
        assertThat(companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), ownerId, Instant.now())).isTrue();
        assertThat(companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), member.getId(), Instant.now())).isTrue();

        mockMvc.perform(post(REVOKE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("사칭 신고 접수")))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        assertThat(companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), ownerId, Instant.now())).isFalse();
        assertThat(companyMembershipRepository.existsEffectiveApprovedMembership(
                company.getId(), member.getId(), Instant.now())).isFalse();
        // 멤버십 행 자체는 회수하지 않는다 — 회수하면 복권 코드가 없어 복구가 반쪽이 된다.
        assertThat(companyMembershipRepository
                .findByCompanyIdAndUserId(company.getId(), member.getId())).isPresent();
    }

    // ── 복구(왕복) ───────────────────────────────────────────────────────────────────────────

    /** 이 PR 의 핵심 계약 — 무효화 → 복구 → <b>재검증 배치가 다시 집는다</b>(자가치유 회복). */
    @Test
    void 왕복_무효화후_복구하면_PENDING이되고_재검증대상에_다시포함된다() throws Exception {
        Company company = approvedCompany(START_DATE);
        Long companyId = company.getId();

        mockMvc.perform(post(REVOKE_URL, companyId)
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("사칭 의심")))
                .andExpect(status().isOk());
        // FAILED 는 재검증 대상 두 갈래 어디에도 걸리지 않는다 = 자가치유 없음(그래서 복구가 필요하다).
        assertThat(reverifyTargetIds()).doesNotContain(companyId);

        mockMvc.perform(post(RESTORE_URL, companyId)
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("비영리 고유번호증 확인 — 오탐")))
                .andExpect(status().isOk())
                // VERIFIED 직행 금지 — 관리자는 국세청 판정을 대신하지 않는다.
                .andExpect(jsonPath("$.data.verificationStatus").value("PENDING"))
                .andExpect(jsonPath("$.data.ntsOutcome").value("ADMIN_RESTORED"))
                .andExpect(jsonPath("$.data.ntsVerified").value(false));

        // 왕복 완성 — 다음 05:30 회차가 국세청에 다시 물어 재판정한다.
        assertThat(reverifyTargetIds()).contains(companyId);
    }

    @Test
    void 복구_배치가강등한_FAILED도_되돌릴수있다() throws Exception {
        // 실사고 재현 — 배치(markBusinessVerificationFailed)가 만든 FAILED 는 앱 경로로 되돌릴 수 없어
        // 6일간 회사 스코프가 닫힌 채 수동 SQL 로 복구해야 했다.
        Company company = approvedCompany(START_DATE);
        company.markBusinessVerificationFailed();
        companyRepository.saveAndFlush(company);

        mockMvc.perform(post(RESTORE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("MISMATCH 오탐 소명 완료")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationStatus").value("PENDING"));

        assertThat(reverifyTargetIds()).contains(company.getId());
    }

    @Test
    void 복구_무효화상태가아니면_409이고_상태불변() throws Exception {
        Company company = approvedCompany(START_DATE);

        mockMvc.perform(post(RESTORE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("사유")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMPANY_VERIFICATION_NOT_REVOKED"));

        // 정상 회사를 "복구"한다며 PENDING 으로 낮추면 오히려 스코프가 닫힌다 — 그 역효과를 막는다.
        assertThat(reload(company.getId()).getVerificationStatus())
                .isEqualTo(BusinessVerificationStatus.VERIFIED);
    }

    /**
     * restore 안전장치(#1329) — 개업일자가 없으면 재검증 대상 쿼리
     * ({@code business_start_date IS NOT NULL})가 영원히 잡지 못해 PENDING 으로 되돌리는 순간 회사 스코프가
     * <b>영구 폐쇄</b>된다("복구했다"고 착각한 채 방치하게 되므로 FAILED 로 두는 것보다 나쁘다).
     */
    @Test
    void 복구_개업일자가없으면_400이고_FAILED가유지된다() throws Exception {
        Company company = approvedCompany(null);
        mockMvc.perform(post(REVOKE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("사칭 의심")))
                .andExpect(status().isOk());

        mockMvc.perform(post(RESTORE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("복구 시도")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("COMPANY_RESTORE_REQUIRES_BUSINESS_START_DATE"));

        Company reloaded = reload(company.getId());
        assertThat(reloaded.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED);
        assertThat(reloaded.ntsOutcome()).contains("ADMIN_REVOKED");
        // 되돌렸다면 여기 잡혔어야 하지만 잡히지 않는다 = 400 으로 막은 이유 그 자체.
        assertThat(reverifyTargetIds()).doesNotContain(company.getId());
    }
}
