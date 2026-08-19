package com.hajacheck.platformadmin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.hajacheck.bizverify.scheduler.PendingBusinessReverifyWriter;
import com.hajacheck.bizverify.service.NtsVerificationOutcome;
import com.hajacheck.demo.service.DemoSeedService;
import com.hajacheck.platformadmin.dto.CompanyVerificationActionRequest;
import com.hajacheck.platformadmin.service.PlatformAdminCompanyService;
import com.hajacheck.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
    private static final String OVERRIDE_URL = "/api/platform-admin/companies/{id}/verification/override";
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
    @Autowired
    private PendingBusinessReverifyWriter reverifyWriter;
    @PersistenceContext
    private EntityManager entityManager;

    /** 자동승인(#1324) 직후 모양 — APPROVED + VERIFIED + 오너의 유효 APPROVED 멤버십까지 갖춘 회사. */
    private Company approvedCompany(LocalDate businessStartDate) {
        return approvedCompany(businessStartDate, "{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"SKIPPED\"}");
    }

    /** @param ocrRaw provenance — 무효화 취소가 VERIFIED 즉시 복원 분기를 타는지는 이 값에 달려 있다. */
    private Company approvedCompany(LocalDate businessStartDate, String ocrRaw) {
        long brn = BRN_SEQ.getAndIncrement();
        User owner = userRepository.saveAndFlush(
                User.createCompanyOwner("owner" + brn + "@haja.test", "대표", "$2a$10$hashed"));
        Company company = companyRepository.saveAndFlush(Company.createPendingReview(
                owner.getId(), "(주)킬스위치" + brn, String.valueOf(brn), "대표",
                "서울시", null, "http://files/brn.png", ocrRaw, businessStartDate));
        company.markBusinessVerified();
        company.autoApprove();
        companyRepository.saveAndFlush(company);
        owner.assignToCompany(company.getId());
        userRepository.saveAndFlush(owner);
        companyMembershipRepository.saveAndFlush(
                CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        return company;
    }

    /** 반려 + FAILED 회사 — 재검증 대상 쿼리의 {@code status <> 'REJECTED'} 에 걸려 배치가 못 집는다. */
    private Company rejectedFailedCompany() {
        long brn = BRN_SEQ.getAndIncrement();
        User owner = userRepository.saveAndFlush(
                User.createCompanyOwner("rejected" + brn + "@haja.test", "대표", "$2a$10$hashed"));
        Company company = companyRepository.saveAndFlush(Company.createPendingReview(
                owner.getId(), "(주)반려" + brn, String.valueOf(brn), "대표",
                "서울시", null, "http://files/brn.png", "{\"ntsOutcome\":\"SKIPPED\"}", START_DATE));
        company.reject(owner.getId(), "테스트 반려");
        company.markBusinessVerificationFailed();
        return companyRepository.saveAndFlush(company);
    }

    /** 데모 시드 회사(BRN + provenance 표식 이중 일치) + FAILED — 배치가 국세청 호출 전에 스킵한다. */
    private Company demoSeededFailedCompany() {
        User owner = userRepository.saveAndFlush(User.createCompanyOwner(
                "demo" + BRN_SEQ.getAndIncrement() + "@haja.test", "데모 관리자", "$2a$10$hashed"));
        Company company = companyRepository.saveAndFlush(Company.createPendingReview(
                owner.getId(), "(주)데모", DemoSeedService.DEMO_BUSINESS_NUMBER, "데모 관리자",
                "서울시", null, "http://files/brn.png",
                "{\"" + DemoSeedService.DEMO_SEED_PROVENANCE_FIELD + "\":\""
                        + DemoSeedService.DEMO_SEED_PROVENANCE_SOURCE + "\"}",
                LocalDate.of(2020, 1, 2)));
        company.markBusinessVerificationFailed();
        return companyRepository.saveAndFlush(company);
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
                .andExpect(jsonPath("$.data.effectiveMemberCount").value(1));
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
     * A-2 분기 통합 — 관리자 오조작 revoke 의 취소는 <b>VERIFIED 즉시 복원</b>이라 다음 배치 회차를
     * 기다리지 않는다. 스코프가 그 자리에서 다시 열리는지까지 실 DB 로 확인한다.
     */
    @Test
    void 왕복_관리자오조작_무효화취소는_VERIFIED로_즉시복원되고_스코프가_바로열린다() throws Exception {
        // 국세청이 실제로 확인해 준(=화이트리스트) 회사여야 즉시 복원 분기에 들어간다.
        Company company = approvedCompany(START_DATE, "{\"ntsOutcome\":\"VERIFIED\"}");
        Long companyId = company.getId();
        Long ownerId = company.getOwnerUserId();

        mockMvc.perform(post(REVOKE_URL, companyId)
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("오조작")))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        assertThat(companyMembershipRepository.existsEffectiveApprovedMembership(
                companyId, ownerId, Instant.now())).isFalse();

        mockMvc.perform(post(RESTORE_URL, companyId)
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("오조작 취소")))
                .andExpect(status().isOk())
                // PENDING 을 거치면 다음 배치(하루 1회)까지 정상 회사가 멈춘다 — 즉시 복원해야 한다.
                .andExpect(jsonPath("$.data.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.ntsOutcome").value("VERIFIED"))
                .andExpect(jsonPath("$.data.ntsVerified").value(true));

        entityManager.flush();
        entityManager.clear();
        assertThat(companyMembershipRepository.existsEffectiveApprovedMembership(
                companyId, ownerId, Instant.now())).isTrue();
    }

    // ── 강제개방 override(#1367 P1-A) ────────────────────────────────────────────────────────

    /**
     * P1-A 핵심 — 대표자 변경으로 국세청이 계속 MISMATCH 를 주는 회사는 restore(PENDING)로는 영영 열리지
     * 않는다(엔티티에 대표자명 수정 경로가 없고, 새 정책상 MISMATCH 는 자동 강등도 승격도 하지 않는다).
     * override 는 스코프를 열되 <b>배지는 켜지 않고 재검증 대상에 남겨</b> 확정 불량 시 자동 재차단한다.
     */
    @Test
    void override_스코프는열리되_배지는꺼진채_재검증대상에_남는다() throws Exception {
        Company company = approvedCompany(START_DATE);
        Long companyId = company.getId();
        Long ownerId = company.getOwnerUserId();
        mockMvc.perform(post(REVOKE_URL, companyId)
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("MISMATCH 경보로 선차단")))
                .andExpect(status().isOk());

        mockMvc.perform(post(OVERRIDE_URL, companyId)
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("등기부·현장 실물 확인 완료")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.ntsOutcome").value("ADMIN_OVERRIDE_VERIFIED"))
                // 국세청이 확인해 준 게 아니므로 "사업자 인증 완료" 배지는 켜지지 않는다.
                .andExpect(jsonPath("$.data.ntsVerified").value(false));

        entityManager.flush();
        entityManager.clear();
        // ① 스코프가 실제로 열린다.
        assertThat(companyMembershipRepository.existsEffectiveApprovedMembership(
                companyId, ownerId, Instant.now())).isTrue();
        // ② 안전장치 — 재검증 대상에 계속 남는다(화이트리스트 밖 라벨이라 두 번째 갈래에 매칭).
        assertThat(reverifyTargetIds()).contains(companyId);
    }

    /** override 의 안전장치가 실제로 동작하는지 — 국세청이 폐업을 확정하면 배치가 자동으로 다시 막는다. */
    @Test
    void override후_국세청이_확정불량을주면_배치가_자동으로_재차단한다() throws Exception {
        Company company = approvedCompany(START_DATE);
        Long companyId = company.getId();
        // 배치가 강등했거나 관리자가 선차단한 상태에서 사람이 실물 확인 후 여는 것이 override 의 전제다.
        company.markBusinessVerificationFailed();
        companyRepository.saveAndFlush(company);
        mockMvc.perform(post(OVERRIDE_URL, companyId)
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("실물 확인")))
                .andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();

        // 배치의 확정 불량 경로(markFailed)는 override 회사에 상태 가드를 두지 않는다.
        reverifyWriter.markFailed(companyId, NtsVerificationOutcome.CLOSED);
        entityManager.flush();
        entityManager.clear();

        Company reloaded = companyRepository.findById(companyId).orElseThrow();
        assertThat(reloaded.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED);
        assertThat(companyMembershipRepository.existsEffectiveApprovedMembership(
                companyId, reloaded.getOwnerUserId(), Instant.now())).isFalse();
    }

    @Test
    void override_이미검증된기업이면_409() throws Exception {
        Company company = approvedCompany(START_DATE);

        mockMvc.perform(post(OVERRIDE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("사유")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMPANY_VERIFICATION_ALREADY_VERIFIED"));
    }

    @Test
    void override_회사ADMIN이면_403() throws Exception {
        Company company = approvedCompany(START_DATE);
        User companyAdmin = saveUser("admin-403c@haja.test", Role.ADMIN, company.getId());

        mockMvc.perform(post(OVERRIDE_URL, company.getId())
                        .with(authentication(authOf(companyAdmin))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("자기 회사 강제개방 시도")))
                .andExpect(status().isForbidden());
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

    /**
     * P2-4 — 반려(REJECTED) 기업은 재검증 대상 쿼리의 {@code status <> 'REJECTED'} 에 걸려 배치가 영원히
     * 집지 못한다. 개업일자 가드만으로는 이 사각지대를 못 막는다(가드가 배치 제외 조건의 절반만 반영).
     */
    @Test
    void 복구_반려기업이면_400이고_FAILED가유지된다() throws Exception {
        Company company = rejectedFailedCompany();

        mockMvc.perform(post(RESTORE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("복구 시도")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMPANY_RESTORE_NOT_REVERIFIABLE"));

        assertThat(reload(company.getId()).getVerificationStatus())
                .isEqualTo(BusinessVerificationStatus.FAILED);
    }

    /**
     * P2-4 — 데모 시드 회사는 개업일자가 있어 기존 가드를 그대로 통과하지만, 스케줄러가 국세청 호출
     * <b>전에</b> 스킵하고 데모 자가복구는 FAILED 만 처리하므로 PENDING 에 영구 고착된다. 에러 메시지로
     * override 사용을 안내한다.
     */
    @Test
    void 복구_데모기업이면_400이고_override를_안내한다() throws Exception {
        Company company = demoSeededFailedCompany();

        mockMvc.perform(post(RESTORE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("복구 시도")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMPANY_RESTORE_NOT_REVERIFIABLE"))
                .andExpect(jsonPath("$.error.message").value(containsString("override")));

        assertThat(reload(company.getId()).getVerificationStatus())
                .isEqualTo(BusinessVerificationStatus.FAILED);
    }

    /** P2-4 — 가드는 PENDING 복귀 경로 전용이다. 관리자 무효화 취소는 배치에 의존하지 않으므로 통과한다. */
    @Test
    void 복구_관리자무효화취소는_개업일자가없어도_허용된다() throws Exception {
        Company company = approvedCompany(null, "{\"ntsOutcome\":\"VERIFIED\"}");
        mockMvc.perform(post(REVOKE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("오조작")))
                .andExpect(status().isOk());

        mockMvc.perform(post(RESTORE_URL, company.getId())
                        .with(authentication(platformAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("오조작 취소")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verificationStatus").value("VERIFIED"));
    }

    // ── 진단 응답 품질 ───────────────────────────────────────────────────────────────────────

    /**
     * P2-5 — 영향 범위는 스코프 판정과 <b>같은 모수</b>여야 한다. 단순 활성 사용자 카운트는 초대 대기·회수된
     * 멤버십 사용자까지 세어 과대 보고하고, 그 숫자로 관리자가 차단 여부를 판단하면 결정이 왜곡된다.
     */
    @Test
    void 영향구성원수는_유효멤버십기준이라_회수와미승인_사용자를_세지않는다() throws Exception {
        Company company = approvedCompany(START_DATE);
        // 회수된 멤버십 — 이미 스코프가 닫혀 있어 이 조치의 영향 대상이 아니다.
        User revoked = saveUser("revoked-member@haja.test", Role.INSPECTOR, company.getId());
        CompanyMembership revokedMembership =
                companyMembershipRepository.saveAndFlush(
                        CompanyMembership.approvedMember(company.getId(), revoked.getId()));
        revokedMembership.revoke();
        // 멤버십 자체가 없는 활성 사용자(회사 포인터만 있는 상태).
        saveUser("no-membership@haja.test", Role.USER, company.getId());
        entityManager.flush();
        entityManager.clear();

        // 활성 사용자 수는 3명(오너+회수+무멤버십)이지만 실제 영향 대상은 오너 1명뿐이다.
        mockMvc.perform(get(VERIFICATION_URL, company.getId())
                        .with(authentication(platformAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.effectiveMemberCount").value(1));
    }

    /** P2-3 — "경보만" 정책의 신호가 진단 API 까지 도달하는지(로그에만 남으면 사람에게 닿지 않는다). */
    @Test
    void 경보스탬프가_진단응답에_노출된다() throws Exception {
        Company company = approvedCompany(START_DATE);
        reverifyWriter.stampAlert(company.getId(), NtsVerificationOutcome.MISMATCH);
        entityManager.flush();
        entityManager.clear();

        mockMvc.perform(get(VERIFICATION_URL, company.getId())
                        .with(authentication(platformAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ntsLastAlertOutcome").value("MISMATCH"))
                .andExpect(jsonPath("$.data.ntsLastAlertAt").isNotEmpty())
                .andExpect(jsonPath("$.data.ntsLastAttemptAt").isNotEmpty())
                // 경보는 인가 근거 키를 바꾸지 않는다 — 상태도 그대로다.
                .andExpect(jsonPath("$.data.verificationStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.data.ntsOutcome").value("SKIPPED"));
    }

    /**
     * P2-1 (CWE-117) — 관리자 자유 입력 사유에 개행을 실으면 <b>존재하지 않는 조치를 감사 로그에 위조</b>할
     * 수 있다. 이 로그는 actor 를 담은 기록이라 부인방지가 정면으로 깨진다. 살균을 지우면 실패해야 한다.
     */
    @Test
    void 감사로그의_사유는_제어문자가_살균된다() throws Exception {
        Company company = approvedCompany(START_DATE);
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(PlatformAdminCompanyService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        try {
            mockMvc.perform(post(REVOKE_URL, company.getId())
                            .with(authentication(platformAdmin())).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("정상 사유\r\n2026-08-20 WARN 위조된-감사-라인")))
                    .andExpect(status().isOk());
        } finally {
            serviceLogger.detachAppender(appender);
        }

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message).doesNotContain("\r").doesNotContain("\n"));
        // 살균은 로그 전용이다 — DB 감사 기록에는 원문이 그대로 남아야 한다(Jackson 이 이스케이프 저장).
        assertThat(reload(company.getId()).getBusinessRegistrationOcrRaw())
                .contains("위조된-감사-라인");
    }
}
