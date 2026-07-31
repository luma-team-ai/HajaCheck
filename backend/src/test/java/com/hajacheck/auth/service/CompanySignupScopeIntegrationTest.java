package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hajacheck.auth.dto.CompanySignupRequest;
import com.hajacheck.auth.dto.CompanySignupResponse;
import com.hajacheck.auth.entity.BusinessVerificationStatus;
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
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가입 직후 <b>회사 스코프가 실제로 열리는지</b>를 실 PostgreSQL(Testcontainers)에서 검증한다(#1324).
 *
 * <p><b>왜 이 테스트가 필요한가</b> — 스코프 판정은 앱과 DB 두 곳에서 각각 강제된다:
 * ①앱: {@code CompanyScopeGuard.requireEffectiveMembership}
 * (→ {@code CompanyMembershipRepository.existsEffectiveApprovedMembership})
 * ②DB: {@code trg_inspections_check_assigned_inspector_company} 트리거.
 * 둘은 같은 불변식(회사 APPROVED + VERIFIED + 양쪽 유효 APPROVED 멤버십 + {@code users.company_id} 일치)을
 * 요구하므로, 앱 레이어만 고치면 점검 생성 시 <b>DB 예외로 터진다</b>. 목(mock) 단위 테스트로는 트리거를
 * 절대 통과 검증할 수 없어 실 PG 통합 테스트로 고정한다.
 *
 * <p>테스트 프로파일은 국세청 API 키가 없어 진위확인이 {@code SKIPPED}(fail-open) 로 떨어진다 —
 * "전면 자동승인"(진위확인 결과와 무관하게 승인)이 실제로 동작하는지를 그대로 재현하는 조건이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CompanySignupScopeIntegrationTest extends PostgresTestSupport {

    @Autowired
    private CompanySignupService companySignupService;
    @Autowired
    private CompanyScopeGuard companyScopeGuard;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FacilityRepository facilityRepository;
    @Autowired
    private InspectionRepository inspectionRepository;
    @PersistenceContext
    private EntityManager entityManager;

    private CompanySignupResponse signup(String email, String brn, String companyName) {
        MockMultipartFile file = new MockMultipartFile(
                "businessRegistrationFile", "brn.png", MediaType.IMAGE_PNG_VALUE, "PNGDATA".getBytes());
        return companySignupService.signup(new CompanySignupRequest(
                email, "pass1234", companyName, brn, "김민수",
                LocalDate.of(2020, 1, 1), "서울시 강남구 테헤란로 1", "10층", true, true, file));
    }

    @Test
    void 가입직후_회사는_APPROVED이고_VERIFIED다() {
        CompanySignupResponse response = signup("scope-a@haja.test", "111-11-11111", "(주)스코프A");

        Company company = companyRepository.findById(response.companyId()).orElseThrow();
        assertThat(company.getStatus()).isEqualTo(CompanyStatus.APPROVED);
        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
        assertThat(company.getVerifiedAt()).isNotNull();
        assertThat(company.getReviewedAt()).isNotNull();
        // 사람 심사자 없음 = 시스템 자동승인.
        assertThat(company.getReviewedBy()).isNull();
    }

    @Test
    void 가입직후_오너에게_유효APPROVED멤버십과_회사포인터가_모두있다() {
        CompanySignupResponse response = signup("scope-b@haja.test", "222-22-22222", "(주)스코프B");
        Company company = companyRepository.findById(response.companyId()).orElseThrow();
        User owner = userRepository.findById(company.getOwnerUserId()).orElseThrow();

        CompanyMembership membership = companyMembershipRepository
                .findByCompanyIdAndUserId(company.getId(), owner.getId()).orElseThrow();

        assertThat(membership.getStatus()).isEqualTo(CompanyMembershipStatus.APPROVED);
        assertThat(membership.isEffectiveAt(Instant.now())).isTrue();
        // 스코프 쿼리·DB 트리거가 요구하는 users.company_id = memberships.company_id 일치.
        assertThat(owner.getCompanyId()).isEqualTo(company.getId());
        assertThat(owner.getStatus()).isEqualTo(UserStatus.ACTIVE);
        // 담당자 배정 자격(트리거의 inspector.role in (INSPECTOR, ADMIN))도 함께 충족한다(#636).
        assertThat(owner.getRole()).isEqualTo(Role.ADMIN);
    }

    @Test
    void 가입직후_CompanyScopeGuard가_통과한다() {
        CompanySignupResponse response = signup("scope-c@haja.test", "333-33-33333", "(주)스코프C");
        Company company = companyRepository.findById(response.companyId()).orElseThrow();

        assertThatCode(() -> companyScopeGuard
                .requireEffectiveMembership(company.getOwnerUserId(), company.getId()))
                .doesNotThrowAnyException();
    }

    @Test
    void 가입직후_점검생성과_담당자배정이_DB트리거까지_통과한다() {
        CompanySignupResponse response = signup("scope-d@haja.test", "444-44-44444", "(주)스코프D");
        Company company = companyRepository.findById(response.companyId()).orElseThrow();
        Long ownerId = company.getOwnerUserId();

        Facility facility = facilityRepository.saveAndFlush(Facility.builder()
                .companyId(company.getId())
                .name("스코프검증빌딩")
                .type("BUILDING")
                .build());

        // created_by = assigned_inspector_id = 오너. 트리거는 생성자·담당자 양쪽의 유효 멤버십과
        // 회사 APPROVED+VERIFIED 를 모두 요구하므로, 셋 중 하나라도 빠지면 여기서 예외가 난다.
        Inspection inspection = inspectionRepository.saveAndFlush(Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(ownerId)
                .assignedInspectorId(ownerId)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 30))
                .status(InspectionStatus.CREATED)
                .build());

        assertThat(inspection.getId()).isNotNull();
    }

    @Test
    void 가입시_국세청진위확인_결과가_ocrRaw에_영속된다() throws Exception {
        CompanySignupResponse response = signup("scope-e@haja.test", "666-66-66666", "(주)스코프E");
        Company company = companyRepository.findById(response.companyId()).orElseThrow();

        // 자동승인은 진위확인 결과와 무관하게 VERIFIED 를 만든다 → companies 행만으로는 "국세청이 확인해
        // 준 회사"와 "확인하지 못한 회사"를 구분할 수 없다. provenance 를 jsonb 에 남겨야 사후 집계가 된다.
        // 테스트 프로파일은 국세청 키가 없어 SKIPPED(fail-open) 가 나온다.
        JsonNode raw = new ObjectMapper().readTree(company.getBusinessRegistrationOcrRaw());
        assertThat(raw.get("ntsOutcome").asText()).isEqualTo("SKIPPED");
        assertThat(raw.get("source").asText()).isEqualTo("MANUAL_INPUT");
        assertThat(raw.get("ntsCheckedAt").asText()).isNotBlank();

        // 개인정보(사업자번호·대표자명·이메일)는 절대 남기지 않는다. 부분 문자열 검사 대신 **키 집합을
        // 고정**한다 — 값에 담긴 숫자가 우연히 겹쳐 깨지지 않고(ntsCheckedAt 의 나노초가 사업자번호와
        // 겹쳐 실패한 전례), PII 가 새 키로 추가되면 그 자체로 실패하므로 감지력이 더 강하다.
        assertThat(raw.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("source", "ntsOutcome", "ntsCheckedAt");
    }

    @Test
    void 운영집계쿼리로_국세청검증을_증명할수없는_회사를_셀_수_있다() {
        signup("scope-f@haja.test", "777-77-77777", "(주)스코프F");

        // "국세청 검증을 증명할 수 없는 회사"를 찾는 단일 쿼리(신규 가입 + V38 소급분 공통 키).
        // 테스트 프로파일은 전건 SKIPPED 이므로 방금 가입한 회사가 반드시 잡혀야 한다.
        Long unprovable = (Long) entityManager.createNativeQuery("""
                select count(*) from companies
                 where business_registration_ocr_raw->>'ntsOutcome' not in ('VERIFIED', 'LEGACY_VERIFIED')
                    or business_registration_ocr_raw->>'ntsOutcome' is null
                """).getSingleResult();

        assertThat(unprovable).isPositive();
    }

    @Test
    void 자동승인만된_회사는_사업자인증배지가_켜지지_않는다() {
        // #1324 P1-3 종단 검증 — 자동승인은 verification_status 를 VERIFIED 로 만들지만(스코프 개방),
        // 사용자 대면 "사업자 인증 완료" 배지는 provenance 기준이라 꺼져 있어야 한다.
        // 테스트 프로파일은 국세청 키가 없어 SKIPPED(fail-open) 로 통과한다 = 검증된 적 없음.
        CompanySignupResponse response = signup("scope-g@haja.test", "555-11-22222", "(주)스코프G");
        Company company = companyRepository.findById(response.companyId()).orElseThrow();

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
        assertThat(company.isNtsVerified())
                .as("국세청이 확인해 주지 않았는데 '사업자 인증 완료'를 표시하면 허위 표시다")
                .isFalse();
    }

    /**
     * 음성 대조군(cross-tenant) — 자동승인으로 승인된 회사가 늘어난 만큼, 회사 스코프 가드가 <b>다른
     * 회사</b>에 대해서는 여전히 닫혀 있어야 한다. 이 단언이 없으면 "모두 APPROVED" 상태에서 스코프
     * 가드가 사실상 무력화되는 회귀(IDOR)를 놓친다.
     */
    @Test
    void 다른회사_오너는_남의회사_스코프에_접근할수없다() {
        CompanySignupResponse a = signup("tenant-a@haja.test", "888-88-88888", "(주)테넌트A");
        CompanySignupResponse b = signup("tenant-b@haja.test", "999-99-99999", "(주)테넌트B");
        Company companyA = companyRepository.findById(a.companyId()).orElseThrow();
        Company companyB = companyRepository.findById(b.companyId()).orElseThrow();

        // 자기 회사는 열린다(전제 — 두 회사 모두 정상 자동승인됐다).
        assertThatCode(() -> companyScopeGuard
                .requireEffectiveMembership(companyA.getOwnerUserId(), companyA.getId()))
                .doesNotThrowAnyException();
        assertThatCode(() -> companyScopeGuard
                .requireEffectiveMembership(companyB.getOwnerUserId(), companyB.getId()))
                .doesNotThrowAnyException();

        // 교차 접근은 양방향 모두 차단된다.
        assertThatThrownBy(() -> companyScopeGuard
                .requireEffectiveMembership(companyA.getOwnerUserId(), companyB.getId()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> companyScopeGuard
                .requireEffectiveMembership(companyB.getOwnerUserId(), companyA.getId()))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 음성 대조군 — 자동승인·멤버십 발급이 없는 회사(= #1324 이전 데이터 모양)에서는 스코프가 닫혀 있고
     * 점검 생성이 DB 트리거에 막힌다. 이 테스트가 없으면 위 통과 단언들이 "원래 통과했을 수도 있는" 것과
     * 구분되지 않는다(회귀 감지력 확보).
     */
    @Test
    void 자동승인전_모양의회사는_스코프가닫혀있고_점검생성이_트리거에막힌다() {
        User owner = userRepository.saveAndFlush(
                User.createCompanyOwner("legacy@haja.test", "옛대표", "$2a$hashed"));
        Company legacy = companyRepository.saveAndFlush(Company.createPendingReview(
                owner.getId(), "(주)레거시", "555-55-55555", "옛대표",
                "서울시", null, "/files/legacy.png", "{\"source\":\"TEST\"}", LocalDate.of(2019, 1, 1)));
        owner.assignToCompany(legacy.getId());
        userRepository.saveAndFlush(owner);

        // ① 앱 레이어 — 회사 미승인 + 멤버십 없음 → FORBIDDEN.
        assertThatThrownBy(() -> companyScopeGuard
                .requireEffectiveMembership(owner.getId(), legacy.getId()))
                .isInstanceOf(BusinessException.class);

        // ② DB 레이어 — 트리거가 점검 생성을 거부한다.
        Facility facility = facilityRepository.saveAndFlush(Facility.builder()
                .companyId(legacy.getId())
                .name("레거시빌딩")
                .type("BUILDING")
                .build());

        assertThatThrownBy(() -> inspectionRepository.saveAndFlush(Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(owner.getId())
                .assignedInspectorId(owner.getId())
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 30))
                .status(InspectionStatus.CREATED)
                .build()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("effective membership");
    }
}
