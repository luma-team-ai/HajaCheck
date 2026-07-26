package com.hajacheck.core.inspection.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.support.PostgresTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * GET /api/inspections MVC 통합 테스트 — #878(HAJA-452) 하자 조건(자연어) 필터 확장 쿼리 파라미터 바인딩
 * 검증. DefectControllerTest 와 동일하게 전역 시큐리티 필터체인이 ClientRegistrationRepository 를 요구해
 * @SpringBootTest+MockMvc(+PostgresTestSupport) 로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InspectionControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @PersistenceContext
    private EntityManager entityManager;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanyMembershipRepository companyMembershipRepository;
    @Autowired
    private FacilityRepository facilityRepository;
    @Autowired
    private InspectionRepository inspectionRepository;
    @Autowired
    private DefectRepository defectRepository;

    // HAJA-25 배정 검증 트리거(trg_inspections_check_assigned_inspector_company)가 assigned_inspector_id에
    // 승인+검증된 회사 소속 INSPECTOR/ADMIN 역할을 요구한다(DefectControllerTest.saveOwner와 동일 픽스처).
    private User saveOwner(String email) {
        User owner = userRepository.saveAndFlush(User.builder()
                .email(email)
                .name("소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build());

        Company company = Company.createPendingReview(
                owner.getId(), "테스트회사-" + owner.getId(), "REG-" + owner.getId(), "대표자",
                "서울시 강남구", null, "https://files.example.com/registration.png", "{}");
        companyRepository.saveAndFlush(company);
        company.markBusinessVerified();
        company.approve(owner.getId());
        companyRepository.saveAndFlush(company);

        companyMembershipRepository.saveAndFlush(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        userRepository.saveAndFlush(owner);

        return owner;
    }

    private Facility saveFacility(Long ownerId) {
        return facilityRepository.save(Facility.builder()
                .companyId(userRepository.findById(ownerId).orElseThrow().getCompanyId())
                .name("테스트빌딩")
                .type("BUILDING")
                .address("서울시 강남구")
                .build());
    }

    private Inspection saveInspection(Long facilityId, Long ownerId, int roundNo, InspectionStatus status) {
        Inspection saved = inspectionRepository.save(Inspection.builder()
                .facilityId(facilityId)
                .createdBy(ownerId)
                .assignedInspectorId(ownerId)
                .roundNo(roundNo)
                .inspectionDate(LocalDate.of(2026, 7, roundNo))
                .status(status)
                .build());
        // InspectionRepositoryImpl.findPageByCompanyIdAndFilters가 fetch join으로 facility를 채워서
        // 반환하는데, 같은 영속성 컨텍스트에서 MockMvc가 곧바로 조회하면 Hibernate가 이미 관리 중인
        // Inspection 엔티티에 그 연관관계를 재적용하지 않아 facility가 null로 남는다(DefectControllerTest.
        // saveDefect와 동일 사유) — InspectionService.list()가 inspection.getFacility().getName()을
        // 호출하므로 flush+clear로 컨텍스트를 비워 이후 컨트롤러 호출이 DB에서 fresh하게 join fetch되도록 한다.
        entityManager.flush();
        entityManager.clear();
        return saved;
    }

    private void saveDefect(Long inspectionId, DefectType type, DefectGrade grade, DefectStatus status) {
        defectRepository.save(Defect.builder()
                .inspectionId(inspectionId)
                .type(type)
                .confidence(0.9)
                .grade(grade)
                .status(status)
                .reviewed(false)
                .deleted(false)
                .build());
        // MockMvc 호출이 같은 영속성 컨텍스트에서 fetch join으로 fresh하게 재조회하도록 flush+clear
        // (DefectControllerTest.saveDefect와 동일 사유).
        entityManager.flush();
        entityManager.clear();
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 점검목록조회_필터없음_본인회사전체반환() throws Exception {
        User owner = saveOwner("owner1@haja.com");
        Facility facility = saveFacility(owner.getId());
        saveInspection(facility.getId(), owner.getId(), 1, InspectionStatus.CREATED);

        mockMvc.perform(get("/api/inspections").with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(1));
    }

    @Test
    void 점검목록조회_하자유형필터_해당유형하자가진점검만반환() throws Exception {
        User owner = saveOwner("owner2@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection withCrack = saveInspection(facility.getId(), owner.getId(), 1, InspectionStatus.ANALYZED);
        Inspection withSpalling = saveInspection(facility.getId(), owner.getId(), 2, InspectionStatus.ANALYZED);
        saveDefect(withCrack.getId(), DefectType.CRACK, null, DefectStatus.DETECTED);
        saveDefect(withSpalling.getId(), DefectType.SPALLING, null, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/inspections").param("defectType", "CRACK")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(withCrack.getId()));
    }

    @Test
    void 점검목록조회_하자유형복수파라미터_배열내OR매칭() throws Exception {
        User owner = saveOwner("owner3@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection withCrack = saveInspection(facility.getId(), owner.getId(), 1, InspectionStatus.ANALYZED);
        Inspection withSpalling = saveInspection(facility.getId(), owner.getId(), 2, InspectionStatus.ANALYZED);
        Inspection withLeak = saveInspection(facility.getId(), owner.getId(), 3, InspectionStatus.ANALYZED);
        saveDefect(withCrack.getId(), DefectType.CRACK, null, DefectStatus.DETECTED);
        saveDefect(withSpalling.getId(), DefectType.SPALLING, null, DefectStatus.DETECTED);
        saveDefect(withLeak.getId(), DefectType.LEAK_EFFLORESCENCE, null, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/inspections")
                        .param("defectType", "CRACK", "SPALLING")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2));
    }

    @Test
    void 점검목록조회_하자조건복수파라미터AND_같은하자전부만족해야매칭() throws Exception {
        User owner = saveOwner("owner4@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection matching = saveInspection(facility.getId(), owner.getId(), 1, InspectionStatus.ANALYZED);
        Inspection nonMatching = saveInspection(facility.getId(), owner.getId(), 2, InspectionStatus.ANALYZED);
        // matching: 하나의 하자가 CRACK이면서 동시에 D등급
        saveDefect(matching.getId(), DefectType.CRACK, DefectGrade.D, DefectStatus.DETECTED);
        // nonMatching: CRACK이지만 A등급(불일치)
        saveDefect(nonMatching.getId(), DefectType.CRACK, DefectGrade.A, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/inspections")
                        .param("defectType", "CRACK")
                        .param("defectGrade", "D")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(matching.getId()));
    }

    @Test
    void 점검목록조회_하자조건미매칭_빈페이지() throws Exception {
        User owner = saveOwner("owner5@haja.com");
        Facility facility = saveFacility(owner.getId());
        Inspection inspection = saveInspection(facility.getId(), owner.getId(), 1, InspectionStatus.ANALYZED);
        saveDefect(inspection.getId(), DefectType.CRACK, null, DefectStatus.DETECTED);

        mockMvc.perform(get("/api/inspections").param("defectStatus", "RESOLVED")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 점검목록조회_잘못된하자유형값_400_INVALID_INPUT() throws Exception {
        User owner = saveOwner("owner6@haja.com");

        mockMvc.perform(get("/api/inspections").param("defectType", "NOT_A_TYPE")
                        .with(csrf()).with(authentication(authOf(owner))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 점검목록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/inspections").with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
