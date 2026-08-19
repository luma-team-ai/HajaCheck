package com.hajacheck.core.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.report.entity.GroundingCheckResultTestFactory;
import com.hajacheck.core.report.entity.GroundingCheckTarget;
import com.hajacheck.core.report.entity.GroundingRequestContext;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 마이페이지 "내 보고서" 쿼리(#844) — InspectionRepositoryTest/DefectRepositoryTest와 동일한
 * users → facilities → inspections → reports 시드 순서를 따른다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class ReportRepositoryTest extends PostgresTestSupport {

    // findMyFinalizedReports 의 issuedAtFrom 은 not-null 필수(리포지토리 주석 참고) — "무제한" 의미로
    // PostgreSQL timestamp 유효범위 안의 충분히 과거인 값을 쓴다(LocalDateTime.MIN은 범위를 벗어나 실패).
    private static final LocalDateTime FAR_PAST = LocalDateTime.of(1970, 1, 1, 0, 0);

    @Autowired
    private ReportRepository reportRepository;

    @Autowired
    private TestEntityManager em;

    private Long seedOwner(String email) {
        User owner = User.builder()
                .email(email)
                .name("소유자")
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$testtesttesttesttesttes")
                .status(UserStatus.ACTIVE)
                .build();
        em.persist(owner);
        em.flush();

        Company company = Company.createPendingReview(
                owner.getId(), "테스트회사-" + owner.getId(), "REG-" + owner.getId(), "대표자",
                "서울시 강남구", null, "https://files.example.com/registration.png", "{}");
        em.persist(company);
        em.flush();
        company.markBusinessVerified();
        company.approve(owner.getId());
        em.flush();

        em.persist(CompanyMembership.approvedOwner(company.getId(), owner.getId()));
        owner.assignToCompany(company.getId());
        em.flush();

        return owner.getId();
    }

    private Long seedFacility(Long ownerId, String name) {
        Long companyId = em.find(User.class, ownerId).getCompanyId();
        Facility facility = Facility.builder().companyId(companyId).name(name).type("BUILDING").build();
        em.persist(facility);
        em.flush();
        return facility.getId();
    }

    private Long companyId(Long ownerId) {
        return em.find(User.class, ownerId).getCompanyId();
    }

    private Long seedInspection(Long facilityId, Long createdBy, Long assignedInspectorId, int roundNo) {
        Inspection inspection = Inspection.builder()
                .facilityId(facilityId)
                .createdBy(createdBy)
                .assignedInspectorId(assignedInspectorId)
                .roundNo(roundNo)
                .inspectionDate(LocalDate.of(2026, 7, 1))
                .status(InspectionStatus.REVIEWED)
                .build();
        em.persist(inspection);
        em.flush();
        return inspection.getId();
    }

    // draft → grounding 통과 → finalize 전체 도메인 흐름을 거쳐 FINALIZED 보고서를 만든다
    // (Report.builder()가 private이라 이 경로로만 유효한 FINALIZED 상태를 만들 수 있다).
    private Report finalizedReport(Long inspectionId, int version, Long createdBy) {
        Report report = Report.draft(inspectionId, 1, version, "{}", createdBy);
        GroundingRequestContext context = report.captureGroundingRequestContext();
        GroundingCheckTarget target = GroundingCheckTarget.capture(context, report.getContentJson());
        report.recordGroundingResult(GroundingCheckResultTestFactory.passed(target, null), createdBy);
        report.finalizeReport("/api/reports/pending/pdf/test-storage-key.pdf", createdBy);
        return reportRepository.save(report);
    }

    @Test
    void findMyFinalizedReports_담당자또는등록자_회사스코프내FINALIZED만반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long stranger = seedOwner("owner-b@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long strangerFacility = seedFacility(stranger, "타인빌딩");
        Long myInspection = seedInspection(facilityId, ownerId, ownerId, 1);
        Long strangerInspection = seedInspection(strangerFacility, stranger, stranger, 1);

        Report myReport = finalizedReport(myInspection, 1, ownerId);
        finalizedReport(strangerInspection, 1, stranger); // 타사 보고서 — 섞이면 안 된다
        // 같은 점검의 DRAFT 보고서 — FINALIZED만 대상이므로 제외돼야 한다
        reportRepository.save(Report.draft(myInspection, 1, 2, "{}", ownerId));

        List<Report> result = reportRepository.findMyFinalizedReports(
                ownerId, companyId(ownerId), ReportStatus.FINALIZED, FAR_PAST, PageRequest.of(0, 10));

        assertThat(result).extracting(Report::getId).containsExactly(myReport.getId());
    }

    @Test
    void findMyFinalizedReports_기간필터_issuedAtFrom이후만포함() {
        // reports 테이블엔 UPDATE 시 updated_at을 now()로 강제 재기록하는 DB 트리거
        // (trg_reports_set_updated_at)가 있어 네이티브 UPDATE로 과거 값을 백데이트할 수 없다 — 대신
        // 방금 생성된 보고서의 실제 updatedAt(≈now) 기준으로 미래/과거 컷오프를 걸어 경계 동작을 검증한다.
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, ownerId, 1);
        Report report = finalizedReport(inspectionId, 1, ownerId);

        List<Report> withFutureCutoff = reportRepository.findMyFinalizedReports(
                ownerId, companyId(ownerId), ReportStatus.FINALIZED,
                LocalDateTime.now().plusHours(1), PageRequest.of(0, 10));
        List<Report> withPastCutoff = reportRepository.findMyFinalizedReports(
                ownerId, companyId(ownerId), ReportStatus.FINALIZED,
                LocalDateTime.now().minusHours(1), PageRequest.of(0, 10));

        assertThat(withFutureCutoff).isEmpty();
        assertThat(withPastCutoff).extracting(Report::getId).containsExactly(report.getId());
    }

    @Test
    void findMyFinalizedReports_상한Pageable로건수제한() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, ownerId, 1);
        finalizedReport(inspectionId, 1, ownerId);
        finalizedReport(inspectionId, 2, ownerId);
        finalizedReport(inspectionId, 3, ownerId);

        List<Report> result = reportRepository.findMyFinalizedReports(
                ownerId, companyId(ownerId), ReportStatus.FINALIZED, FAR_PAST, PageRequest.of(0, 2));

        assertThat(result).hasSize(2);
    }

    @Test
    void countMyFinalizedReports_담당자또는등록자_회사스코프내FINALIZED건수만() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long stranger = seedOwner("owner-b@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long strangerFacility = seedFacility(stranger, "타인빌딩");
        Long myInspection = seedInspection(facilityId, ownerId, ownerId, 1);
        Long strangerInspection = seedInspection(strangerFacility, stranger, stranger, 1);

        finalizedReport(myInspection, 1, ownerId);
        finalizedReport(myInspection, 2, ownerId);
        finalizedReport(strangerInspection, 1, stranger);
        reportRepository.save(Report.draft(myInspection, 1, 3, "{}", ownerId));

        long count = reportRepository.countMyFinalizedReports(ownerId, companyId(ownerId), ReportStatus.FINALIZED);

        assertThat(count).isEqualTo(2);
    }

    @Test
    void findByInspectionIdAndDeletedAtIsNullOrderByVersionDesc_softDeletedDraft제외() {
        Long ownerId = seedOwner("owner-version@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, ownerId, 1);
        Report active = reportRepository.save(Report.draft(inspectionId, 1, 1, "{}", ownerId));
        Report deleted = Report.draft(inspectionId, 1, 2, "{}", ownerId);
        deleted.markDeleted(ownerId);
        reportRepository.save(deleted);
        em.flush();
        em.clear();

        List<Report> result = reportRepository.findByInspectionIdAndDeletedAtIsNullOrderByVersionDesc(inspectionId);

        assertThat(result).extracting(Report::getId).containsExactly(active.getId());
    }

    @Test
    void findCompanyPage와Summary_softDeletedDraft제외() {
        Long ownerId = seedOwner("owner-company-report@haja.com");
        Long facilityId = seedFacility(ownerId, "테스트빌딩");
        Long inspectionId = seedInspection(facilityId, ownerId, ownerId, 1);
        Report active = reportRepository.save(Report.draft(inspectionId, 1, 1, "{}", ownerId));
        Report deleted = Report.draft(inspectionId, 1, 2, "{}", ownerId);
        deleted.markDeleted(ownerId);
        reportRepository.save(deleted);
        em.flush();
        em.clear();

        var page = reportRepository.findCompanyPage(
                companyId(ownerId), List.of(ReportStatus.DRAFT), -1L, -1, "", FAR_PAST, PageRequest.of(0, 10));
        var summary = reportRepository.summarizeCompany(
                companyId(ownerId), ReportStatus.FINALIZED, ReportStatus.DRAFT, FAR_PAST);

        assertThat(page.getContent()).extracting(Report::getId).containsExactly(active.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(summary.getTotalCount()).isEqualTo(1);
        assertThat(summary.getDraftCount()).isEqualTo(1);
    }

    // 시설물 상세 "점검 이력" → 보고서 딥링크(#1359 후속) — 같은 시설물의 다른 회차 보고서가
    // roundNo 필터에 섞여 들어오지 않는지 검증한다.
    @Test
    void findCompanyPage_roundNo지정시_해당회차보고서만반환() {
        Long ownerId = seedOwner("owner-round-filter@haja.com");
        Long facilityId = seedFacility(ownerId, "회차필터빌딩");
        Long round1InspectionId = seedInspection(facilityId, ownerId, ownerId, 1);
        Long round2InspectionId = seedInspection(facilityId, ownerId, ownerId, 2);
        // #1702 — 회차 필터는 이제 보고서의 발급 시점 스냅샷(reports.round_no) 기준이다.
        Report round1Report = reportRepository.save(Report.draft(round1InspectionId, 1, 1, "{}", ownerId));
        reportRepository.save(Report.draft(round2InspectionId, 2, 1, "{}", ownerId));
        em.flush();
        em.clear();

        var page = reportRepository.findCompanyPage(
                companyId(ownerId), List.of(ReportStatus.DRAFT), -1L, 1, "", FAR_PAST, PageRequest.of(0, 10));

        assertThat(page.getContent()).extracting(Report::getId).containsExactly(round1Report.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }
}
