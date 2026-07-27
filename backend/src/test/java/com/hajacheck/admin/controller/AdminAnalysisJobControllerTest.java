package com.hajacheck.admin.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
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
 * 관리자 AI 분석 현황 모니터링(신규) MVC 통합 테스트. AdminUserControllerTest와 동일 패턴 —
 * 전역 시큐리티 필터체인(SecurityConfig의 "/api/admin/**" hasRole(ADMIN))을 실제로 태워야 하므로
 * @SpringBootTest+MockMvc(+PostgresTestSupport)로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminAnalysisJobControllerTest extends PostgresTestSupport {

    @Autowired
    private MockMvc mockMvc;
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

    private static final AtomicLong BRN_SEQ = new AtomicLong(9_100_000_000L);

    // DashboardControllerTest와 동일 패턴 — 회사를 "승인" 상태로 만들어야 Inspection 저장 시
    // DB 트리거(check_inspection_assigned_inspector_company, "assigned_inspector_id는 승인된
    // 회사 소속의 유효 멤버여야 한다")를 통과한다. AdminUserControllerTest는 Inspection을 저장하지
    // 않아 이 승인 절차가 필요 없었다.
    private Company saveCompany() {
        long brn = BRN_SEQ.getAndIncrement();
        User owner = saveUser("대표", "owner" + brn + "@haja.com", Role.ADMIN, null);
        Company company = companyRepository.saveAndFlush(Company.createPendingReview(
                owner.getId(), "(주)테스트", String.valueOf(brn), "김대표",
                "서울시 강남구", null, "http://files/brn.png", "{}"));
        company.markBusinessVerified();
        company.approve(owner.getId());
        return companyRepository.saveAndFlush(company);
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

    // 점검 담당자로 배정하려면 role=INSPECTOR/ADMIN이면서 승인된 회사의 CompanyMembership이
    // 있어야 한다(DB 트리거, DashboardControllerTest.saveInspectorMember와 동일 이유).
    private User saveInspectorMember(Long companyId, String email) {
        User inspector = saveUser("검사자", email, Role.INSPECTOR, companyId);
        companyMembershipRepository.saveAndFlush(CompanyMembership.approvedOwner(companyId, inspector.getId()));
        return inspector;
    }

    private Facility saveFacility(Long companyId, String name) {
        return facilityRepository.save(Facility.builder()
                .companyId(companyId).name(name).type("BUILDING").build());
    }

    private Inspection saveInspection(Long facilityId, Long inspectorId, InspectionStatus status) {
        return saveInspection(facilityId, inspectorId, 1, status);
    }

    // (facility_id, round_no) unique 제약 — 같은 시설물에 여러 회차를 만드는 테스트는 round_no를 받는다.
    private Inspection saveInspection(Long facilityId, Long inspectorId, int roundNo, InspectionStatus status) {
        return inspectionRepository.save(Inspection.builder()
                .facilityId(facilityId).createdBy(inspectorId).assignedInspectorId(inspectorId)
                .roundNo(roundNo).inspectionDate(LocalDate.of(2026, 7, 1)).status(status).build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 분석현황조회_관리자_200_회사소속점검을상태별로분류해반환() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin@haja.com", Role.ADMIN, company.getId());
        User inspector = saveInspectorMember(company.getId(), "inspector@haja.com");
        Facility facility = saveFacility(company.getId(), "테스트빌딩");
        saveInspection(facility.getId(), inspector.getId(), 1, InspectionStatus.CREATED);
        saveInspection(facility.getId(), inspector.getId(), 2, InspectionStatus.ANALYZING);
        saveInspection(facility.getId(), inspector.getId(), 3, InspectionStatus.ANALYZED);

        mockMvc.perform(get("/api/admin/analysis-jobs").with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(3))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    void 분석현황조회_status파라미터로_ANALYZING만필터링() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin2@haja.com", Role.ADMIN, company.getId());
        User inspector = saveInspectorMember(company.getId(), "inspector2@haja.com");
        Facility facility = saveFacility(company.getId(), "테스트빌딩2");
        saveInspection(facility.getId(), inspector.getId(), 1, InspectionStatus.CREATED);
        Inspection analyzing = saveInspection(facility.getId(), inspector.getId(), 2, InspectionStatus.ANALYZING);
        saveInspection(facility.getId(), inspector.getId(), 3, InspectionStatus.ANALYZED);

        mockMvc.perform(get("/api/admin/analysis-jobs")
                        .param("status", "ANALYZING")
                        .with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].jobId").value(analyzing.getId()))
                .andExpect(jsonPath("$.data.content[0].status").value("ANALYZING"))
                .andExpect(jsonPath("$.data.content[0].inspectorName").value("검사자"));
    }

    @Test
    void 분석현황조회_ANALYZED이상은_COMPLETED로분류된다() throws Exception {
        Company company = saveCompany();
        User admin = saveUser("관리자", "admin3@haja.com", Role.ADMIN, company.getId());
        User inspector = saveInspectorMember(company.getId(), "inspector3@haja.com");
        Facility facility = saveFacility(company.getId(), "테스트빌딩3");
        saveInspection(facility.getId(), inspector.getId(), InspectionStatus.ANALYZED);

        mockMvc.perform(get("/api/admin/analysis-jobs")
                        .param("status", "COMPLETED")
                        .with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.content[0].progressPercent").doesNotExist());
    }

    @Test
    void 분석현황조회_다른회사점검은보이지않는다() throws Exception {
        Company company = saveCompany();
        Company otherCompany = saveCompany();
        User admin = saveUser("관리자", "admin4@haja.com", Role.ADMIN, company.getId());
        User inspector = saveInspectorMember(company.getId(), "inspector4@haja.com");
        User otherInspector = saveInspectorMember(otherCompany.getId(), "other-inspector4@haja.com");
        Facility facility = saveFacility(company.getId(), "우리회사시설");
        Facility otherFacility = saveFacility(otherCompany.getId(), "타사시설");
        saveInspection(facility.getId(), inspector.getId(), InspectionStatus.ANALYZING);
        saveInspection(otherFacility.getId(), otherInspector.getId(), InspectionStatus.ANALYZING);

        mockMvc.perform(get("/api/admin/analysis-jobs").with(authentication(authOf(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].facilityName").value("우리회사시설"));
    }

    @Test
    void 분석현황조회_요청관리자에게회사가없으면_403_FORBIDDEN() throws Exception {
        User admin = saveUser("무소속관리자", "no-company-admin@haja.com", Role.ADMIN, null);

        mockMvc.perform(get("/api/admin/analysis-jobs").with(authentication(authOf(admin))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 분석현황조회_일반검사자면_403_FORBIDDEN() throws Exception {
        Company company = saveCompany();
        User inspector = saveUser("검사자", "inspector-only@haja.com", Role.INSPECTOR, company.getId());

        mockMvc.perform(get("/api/admin/analysis-jobs").with(authentication(authOf(inspector))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void 분석현황조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/admin/analysis-jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }
}
