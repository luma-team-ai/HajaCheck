package com.hajacheck.core.dashboard.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.support.PostgresTestSupport;
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
 * GET /api/dashboard/upcoming-inspections MVC 통합 테스트(dev-03-02).
 * FacilityControllerTest 와 동일하게 전역 시큐리티 필터체인 때문에
 * @SpringBootTest+MockMvc(+PostgresTestSupport) 로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DashboardControllerTest extends PostgresTestSupport {

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

    private User saveUser(String email) {
        User user = userRepository.saveAndFlush(User.builder()
                .email(email)
                .name("기업사용자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        Company company = companyRepository.saveAndFlush(Company.createPendingReview(
                user.getId(), email + " 회사", "TEST-" + user.getId(), "대표자",
                "서울", null, "https://example.com/brn", "{\"source\":\"TEST\"}"));
        company.markBusinessVerified();
        company.approve(user.getId());
        companyRepository.saveAndFlush(company);
        companyMembershipRepository.saveAndFlush(
                CompanyMembership.approvedOwner(company.getId(), user.getId()));
        user.assignToCompany(company.getId());
        return userRepository.saveAndFlush(user);
    }

    private void saveFacilityWithDueAt(Long ownerId, String name, LocalDate nextInspectionDueAt) {
        facilityRepository.save(Facility.builder()
                .companyId(userRepository.findById(ownerId).orElseThrow().getCompanyId())
                .name(name)
                .type("BUILDING")
                .inspectionCycleMonths(6)
                .nextInspectionDueAt(nextInspectionDueAt)
                .build());
    }

    private Long saveFacility(Long companyId, String name) {
        return saveFacility(companyId, name, "BUILDING");
    }

    private Long saveFacility(Long companyId, String name, String type) {
        return facilityRepository.saveAndFlush(
                Facility.builder().companyId(companyId).name(name).type(type).build()).getId();
    }

    // 점검 회차 생성 — assigned_inspector_id 는 DB 트리거(trg_inspections_check_assigned_inspector_company)가
    // "승인된 회사 소속 INSPECTOR/ADMIN"만 허용하므로, owner(role=USER)를 그대로 담당자로 못 쓴다.
    // InspectionRepositoryTest.seedOwner 와 동일하게 역할 INSPECTOR + approvedOwner 멤버십으로 별도 시드한다.
    private Long saveInspectorMember(Long companyId, String email) {
        User inspector = userRepository.saveAndFlush(User.builder()
                .email(email)
                .name("점검담당자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .companyId(companyId)
                .build());
        companyMembershipRepository.saveAndFlush(CompanyMembership.approvedOwner(companyId, inspector.getId()));
        return inspector.getId();
    }

    private void saveInspection(Long facilityId, Long createdBy, Long assignedInspectorId, int roundNo,
                                 LocalDate inspectionDate, InspectionStatus status) {
        inspectionRepository.saveAndFlush(Inspection.builder()
                .facilityId(facilityId)
                .createdBy(createdBy)
                .assignedInspectorId(assignedInspectorId)
                .roundNo(roundNo)
                .inspectionDate(inspectionDate)
                .status(status)
                .build());
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    @Test
    void 다가오는점검조회_본인시설_200_오름차순() throws Exception {
        User owner = saveUser("owner-upcoming1@haja.com");
        LocalDate today = LocalDate.now();
        saveFacilityWithDueAt(owner.getId(), "10일후시설", today.plusDays(10));
        saveFacilityWithDueAt(owner.getId(), "3일후시설", today.plusDays(3));

        mockMvc.perform(get("/api/dashboard/upcoming-inspections")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].facilityName").value("3일후시설"))
                .andExpect(jsonPath("$.data[1].facilityName").value("10일후시설"));
    }

    @Test
    void 다가오는점검조회_소유시설없으면_200_빈배열() throws Exception {
        User owner = saveUser("owner-upcoming2@haja.com");

        mockMvc.perform(get("/api/dashboard/upcoming-inspections")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 다가오는점검조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/dashboard/upcoming-inspections"))
                .andExpect(status().isUnauthorized());
    }

    // ── GET /api/dashboard/recent-inspections/search(신규) — "최근 점검 전체보기" ──

    @Test
    void 최근점검검색_필터없으면_200_페이지응답_totalElements일치() throws Exception {
        User owner = saveUser("owner-search1@haja.com");
        Long companyId = owner.getCompanyId();
        Long inspectorId = saveInspectorMember(companyId, "inspector-search1@haja.com");
        Long facilityId = saveFacility(companyId, "검색테스트빌딩");
        saveInspection(facilityId, owner.getId(), inspectorId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED);
        saveInspection(facilityId, owner.getId(), inspectorId, 2, LocalDate.of(2026, 7, 10), InspectionStatus.REPORTED);

        mockMvc.perform(get("/api/dashboard/recent-inspections/search")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                // inspectionDate desc 정렬 — 7/10 회차가 먼저.
                .andExpect(jsonPath("$.data.content[0].status").value("완료"));
    }

    @Test
    void 최근점검검색_상태라벨필터_완료만조회() throws Exception {
        User owner = saveUser("owner-search2@haja.com");
        Long companyId = owner.getCompanyId();
        Long inspectorId = saveInspectorMember(companyId, "inspector-search2@haja.com");
        Long facilityId = saveFacility(companyId, "검색테스트빌딩2");
        saveInspection(facilityId, owner.getId(), inspectorId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED);
        saveInspection(facilityId, owner.getId(), inspectorId, 2, LocalDate.of(2026, 7, 2), InspectionStatus.REPORTED);

        mockMvc.perform(get("/api/dashboard/recent-inspections/search")
                        .param("status", "완료")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].status").value("완료"));
    }

    @Test
    void 최근점검검색_시설물명검색어매칭() throws Exception {
        User owner = saveUser("owner-search3@haja.com");
        Long companyId = owner.getCompanyId();
        Long inspectorId = saveInspectorMember(companyId, "inspector-search3@haja.com");
        Long facilityA = saveFacility(companyId, "강남빌딩");
        Long facilityB = saveFacility(companyId, "서초타워");
        saveInspection(facilityA, owner.getId(), inspectorId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED);
        saveInspection(facilityB, owner.getId(), inspectorId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED);

        mockMvc.perform(get("/api/dashboard/recent-inspections/search")
                        .param("query", "강남")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].facilityName").value("강남빌딩"));
    }

    @Test
    void 최근점검검색_시설물종류필터_컴파운드값도접두매칭() throws Exception {
        User owner = saveUser("owner-search-type@haja.com");
        Long companyId = owner.getCompanyId();
        Long inspectorId = saveInspectorMember(companyId, "inspector-search-type@haja.com");
        Long buildingId = saveFacility(companyId, "신규건물", "건물-긴급-1개월");
        Long bridgeId = saveFacility(companyId, "한강대교", "교량-정기-4개월");
        saveInspection(buildingId, owner.getId(), inspectorId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED);
        saveInspection(bridgeId, owner.getId(), inspectorId, 1, LocalDate.of(2026, 7, 1), InspectionStatus.CREATED);

        mockMvc.perform(get("/api/dashboard/recent-inspections/search")
                        .param("facilityType", "건물")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].facilityName").value("신규건물"));
    }

    @Test
    void 최근점검검색_페이지네이션_size로건수제한() throws Exception {
        User owner = saveUser("owner-search4@haja.com");
        Long companyId = owner.getCompanyId();
        Long inspectorId = saveInspectorMember(companyId, "inspector-search4@haja.com");
        Long facilityId = saveFacility(companyId, "페이지네이션빌딩");
        for (int i = 1; i <= 3; i++) {
            saveInspection(facilityId, owner.getId(), inspectorId, i, LocalDate.of(2026, 7, i), InspectionStatus.CREATED);
        }

        mockMvc.perform(get("/api/dashboard/recent-inspections/search")
                        .param("size", "2")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    void 최근점검검색_타사데이터제외() throws Exception {
        User owner = saveUser("owner-search5@haja.com");
        User stranger = saveUser("stranger-search5@haja.com");
        Long strangerInspectorId = saveInspectorMember(stranger.getCompanyId(), "inspector-search5b@haja.com");
        Long strangerFacilityId = saveFacility(stranger.getCompanyId(), "타사시설");
        saveInspection(strangerFacilityId, stranger.getId(), strangerInspectorId, 1,
                LocalDate.of(2026, 7, 1), InspectionStatus.CREATED);

        mockMvc.perform(get("/api/dashboard/recent-inspections/search")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void 최근점검검색_잘못된상태라벨_400() throws Exception {
        User owner = saveUser("owner-search6@haja.com");

        mockMvc.perform(get("/api/dashboard/recent-inspections/search")
                        .param("status", "존재하지않는상태")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 최근점검검색_미인증_401() throws Exception {
        mockMvc.perform(get("/api/dashboard/recent-inspections/search"))
                .andExpect(status().isUnauthorized());
    }
}
