package com.hajacheck.mypage.controller;

import static org.hamcrest.Matchers.nullValue;
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
import com.hajacheck.core.report.entity.GroundingCheckResultTestFactory;
import com.hajacheck.core.report.entity.GroundingCheckTarget;
import com.hajacheck.core.report.entity.GroundingRequestContext;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.repository.ReportRepository;
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
 * 마이페이지 "내 점검 이력 / 보고서" API MVC 통합 테스트(#844). MembershipControllerTest와 동일하게
 * 전역 시큐리티 필터체인(oauth2Login 포함)이 ClientRegistrationRepository를 요구해
 * @SpringBootTest+MockMvc(+PostgresTestSupport)로 검증하고, 트랜잭션 롤백으로 테스트 간 격리한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MyInspectionsControllerTest extends PostgresTestSupport {

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
    private ReportRepository reportRepository;

    private User seedOwner(String email) {
        User owner = userRepository.save(User.builder()
                .email(email)
                .name("소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        // saveAndFlush로 각 단계를 즉시 반영한다 — 그렇지 않으면 Hibernate가 한 플러시에서 INSERT를
        // UPDATE보다 먼저 실행해(뒤에 나오는 inspections INSERT가 company/user UPDATE보다 앞서 큐잉됨)
        // HAJA-25 배정 검증 트리거가 아직 반영되지 않은(PENDING_REVIEW/company_id=null) 상태로 검증해
        // 실패한다(DefectControllerTest.saveOwner와 동일 이유·동일 픽스처).
        owner = userRepository.saveAndFlush(owner);
        Company company = Company.createPendingReview(
                owner.getId(), "마이페이지테스트회사-" + owner.getId(), "REG-" + owner.getId(), "대표자",
                "서울시", null, "https://files.example/business.pdf", "{}");
        companyRepository.saveAndFlush(company);
        company.markBusinessVerified();
        company.approve(owner.getId());
        companyRepository.saveAndFlush(company);
        companyMembershipRepository.saveAndFlush(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        return userRepository.saveAndFlush(owner);
    }

    private Facility seedFacility(User owner, String name) {
        return facilityRepository.saveAndFlush(Facility.builder()
                .companyId(owner.getCompanyId())
                .name(name)
                .type("BUILDING")
                .address("서울시 강남구")
                .build());
    }

    private Inspection seedInspection(
            Facility facility, Long createdBy, Long assignedInspectorId, int roundNo, InspectionStatus status) {
        return inspectionRepository.saveAndFlush(Inspection.builder()
                .facilityId(facility.getId())
                .createdBy(createdBy)
                .assignedInspectorId(assignedInspectorId)
                .roundNo(roundNo)
                .inspectionDate(LocalDate.of(2026, 7, 1))
                .status(status)
                .build());
    }

    // draft → grounding 통과 → finalize 전체 도메인 흐름을 거쳐 FINALIZED 보고서를 만든다.
    private Report seedFinalizedReport(Long inspectionId, int version, Long createdBy) {
        Report report = Report.draft(inspectionId, version, "{}", createdBy);
        GroundingRequestContext context = report.captureGroundingRequestContext();
        GroundingCheckTarget target = GroundingCheckTarget.capture(context, report.getContentJson());
        report.recordGroundingResult(GroundingCheckResultTestFactory.passed(target, null), createdBy);
        report.finalizeReport("/api/reports/pending/pdf/test-storage-key.pdf", createdBy);
        return reportRepository.saveAndFlush(report);
    }

    private UsernamePasswordAuthenticationToken authOf(User user) {
        LoginUser principal = new LoginUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    // ── 미인증 ──

    @Test
    void 요약조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/me/inspections/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void 목록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/me/inspections")).andExpect(status().isUnauthorized());
    }

    @Test
    void 보고서목록조회_미인증_401() throws Exception {
        mockMvc.perform(get("/api/me/reports")).andExpect(status().isUnauthorized());
    }

    // ── 요약(summary) ──

    @Test
    void 요약조회_참여검수확정발급진행중_4종집계() throws Exception {
        User owner = seedOwner("summary-owner@haja.com");
        Facility facility = seedFacility(owner, "테스트빌딩");
        Inspection reviewed = seedInspection(facility, owner.getId(), owner.getId(), 1, InspectionStatus.REVIEWED);
        seedInspection(facility, owner.getId(), owner.getId(), 2, InspectionStatus.REPORTED);
        seedInspection(facility, owner.getId(), owner.getId(), 3, InspectionStatus.CREATED);
        seedInspection(facility, owner.getId(), owner.getId(), 4, InspectionStatus.ANALYZED); // 검수대기 — 어느 쪽에도 미포함
        seedFinalizedReport(reviewed.getId(), 1, owner.getId());
        // 같은 트랜잭션(영속성 컨텍스트) 안에서 방금 저장한 엔티티를 그대로 재사용하면 join fetch가
        // 이미 관리 중인 엔티티에 재적용되지 않아 facility가 null로 남는다 — clear로 다음 조회가
        // DB에서 fresh하게 join fetch되도록 한다(DefectControllerTest와 동일 사유).
        entityManager.clear();

        mockMvc.perform(get("/api/me/inspections/summary").with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.participatedCount").value(4))
                .andExpect(jsonPath("$.data.reviewConfirmedCount").value(2))
                .andExpect(jsonPath("$.data.inProgressCount").value(1))
                .andExpect(jsonPath("$.data.issuedReportCount").value(1));
    }

    // ── 목록(inspections) ──

    @Test
    void 목록조회_회사스코프내담당자거나등록자인점검만_역할과상태매핑() throws Exception {
        User owner = seedOwner("list-owner@haja.com");
        User stranger = seedOwner("list-stranger@haja.com");
        Facility facility = seedFacility(owner, "테스트빌딩");
        Facility strangerFacility = seedFacility(stranger, "타인빌딩");
        seedInspection(facility, owner.getId(), owner.getId(), 1, InspectionStatus.REVIEWED);
        seedInspection(strangerFacility, stranger.getId(), stranger.getId(), 1, InspectionStatus.REVIEWED);
        entityManager.clear();

        mockMvc.perform(get("/api/me/inspections")
                        .param("period", "ALL")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].facilityName").value("테스트빌딩"))
                .andExpect(jsonPath("$.data.content[0].role").value("INSPECTOR"))
                .andExpect(jsonPath("$.data.content[0].status").value("REVIEW_DONE"))
                .andExpect(jsonPath("$.data.content[0].inspectionDate").value("2026-07-01"));
    }

    @Test
    void 목록조회_인식불가period값_400_INVALID_INPUT() throws Exception {
        User owner = seedOwner("period-owner@haja.com");

        mockMvc.perform(get("/api/me/inspections")
                        .param("period", "2M")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void 목록조회_페이지크기적용() throws Exception {
        User owner = seedOwner("page-owner@haja.com");
        Facility facility = seedFacility(owner, "테스트빌딩");
        for (int i = 1; i <= 3; i++) {
            seedInspection(facility, owner.getId(), owner.getId(), i, InspectionStatus.CREATED);
        }
        entityManager.clear();

        mockMvc.perform(get("/api/me/inspections")
                        .param("page", "0")
                        .param("size", "2")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content.length()").value(2));
    }

    // ── 보고서(reports) ──

    @Test
    void 보고서목록조회_FINALIZED만_DRAFT는제외_PDF조회실패시크기는null() throws Exception {
        User owner = seedOwner("report-owner@haja.com");
        Facility facility = seedFacility(owner, "보고서빌딩");
        Inspection inspection = seedInspection(facility, owner.getId(), owner.getId(), 1, InspectionStatus.REPORTED);
        seedFinalizedReport(inspection.getId(), 1, owner.getId());
        reportRepository.saveAndFlush(Report.draft(inspection.getId(), 2, "{}", owner.getId()));
        entityManager.clear();

        mockMvc.perform(get("/api/me/reports")
                        .param("period", "ALL")
                        .with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].facilityName").value("보고서빌딩"))
                // 실제 PDF 파일이 로컬 스토리지에 없으므로 FILE_NOT_FOUND → 예외 대신 null로 흡수돼야 한다.
                .andExpect(jsonPath("$.data[0].fileSizeBytes").value(nullValue()));
    }

    @Test
    void 보고서목록조회_타사스코프는제외() throws Exception {
        User owner = seedOwner("report-owner-2@haja.com");
        User stranger = seedOwner("report-stranger@haja.com");
        Facility facility = seedFacility(owner, "보고서빌딩");
        Facility strangerFacility = seedFacility(stranger, "타인보고서빌딩");
        Inspection myInspection =
                seedInspection(facility, owner.getId(), owner.getId(), 1, InspectionStatus.REPORTED);
        Inspection strangerInspection =
                seedInspection(strangerFacility, stranger.getId(), stranger.getId(), 1, InspectionStatus.REPORTED);
        seedFinalizedReport(myInspection.getId(), 1, owner.getId());
        seedFinalizedReport(strangerInspection.getId(), 1, stranger.getId());
        entityManager.clear();

        mockMvc.perform(get("/api/me/reports").with(authentication(authOf(owner))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }
}
