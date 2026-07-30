package com.hajacheck.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
