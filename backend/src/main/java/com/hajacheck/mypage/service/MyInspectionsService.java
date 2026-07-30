package com.hajacheck.mypage.service;

import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.InspectionDefectCountProjection;
import com.hajacheck.core.defect.repository.InspectionGradeProjection;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.core.report.support.ReportPdfStorage;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.mypage.dto.MyInspectionRowResponse;
import com.hajacheck.mypage.dto.MyInspectionsSummaryResponse;
import com.hajacheck.mypage.dto.MyReportRowResponse;
import com.hajacheck.mypage.support.PeriodFilter;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마이페이지 — 내 점검 이력 / 보고서(#844, HAJA-442). "내 점검"의 정의는 assignedInspectorId 또는
 * createdBy가 요청자 본인인 회차이며(handoff §2 공통), 회사 스코프는 기존 CompanyScopeGuard /
 * InspectionService.list(...)와 동일 원칙을 따른다(요청자가 회사를 이동해도 과거 소속 회사의
 * 점검이 현재 컨텍스트에 섞이지 않도록 facility.companyId로 추가 스코핑).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyInspectionsService {

    // 보고서 목록 무제한 반환 방지 상한(handoff §2-3) — 페이지네이션이 없는 화면이라 최신순으로 이
    // 건수까지만 반환한다. 행마다 PDF 파일 stat I/O가 발생하므로(fileSizeBytes) 성능 방어를 겸한다.
    private static final int MY_REPORTS_MAX = 100;

    // 조회 기간 필터의 "오늘" 기준 존 — MembershipService의 usage_counters/nextBillingDate 존과 동일하게 고정.
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    // period=ALL(하한 없음)일 때 findMyFinalizedReports에 넘길 사실상-무제한 하한값. LocalDateTime.MIN은
    // PostgreSQL timestamp 유효범위(4713 BC~294276 AD)를 벗어나 "timestamp out of range"로 즉시 실패하므로
    // 유효범위 안의 충분히 과거인 값(서비스 운영 개시 이전)을 대신 쓴다.
    private static final LocalDateTime UNBOUNDED_ISSUED_AT_FROM = LocalDateTime.of(1970, 1, 1, 0, 0);

    private static final Set<InspectionStatus> REVIEW_CONFIRMED_STATUSES =
            EnumSet.of(InspectionStatus.REVIEWED, InspectionStatus.REPORTED);
    private static final Set<InspectionStatus> IN_PROGRESS_STATUSES =
            EnumSet.of(InspectionStatus.CREATED, InspectionStatus.UPLOADING, InspectionStatus.ANALYZING);

    private final InspectionRepository inspectionRepository;
    private final DefectRepository defectRepository;
    private final ReportRepository reportRepository;
    private final CompanyScopeGuard companyScopeGuard;
    private final ReportPdfStorage reportPdfStorage;

    public MyInspectionsSummaryResponse getSummary(Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);

        long participatedCount = inspectionRepository.countMine(companyId, userId);
        long reviewConfirmedCount =
                inspectionRepository.countMineByStatusIn(companyId, userId, REVIEW_CONFIRMED_STATUSES);
        long inProgressCount = inspectionRepository.countMineByStatusIn(companyId, userId, IN_PROGRESS_STATUSES);
        long issuedReportCount = reportRepository.countMyFinalizedReports(userId, companyId, ReportStatus.FINALIZED);

        return new MyInspectionsSummaryResponse(
                participatedCount, reviewConfirmedCount, issuedReportCount, inProgressCount);
    }

    public PageResponse<MyInspectionRowResponse> getInspections(
            Long userId, Long companyId, String periodCode, Pageable pageable) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        LocalDate periodFrom = resolvePeriodFrom(periodCode);

        Page<Inspection> page =
                inspectionRepository.findMyInspectionsPage(userId, companyId, periodFrom, pageable);

        List<Long> inspectionIds = page.getContent().stream().map(Inspection::getId).toList();
        Map<Long, Long> defectCountByInspectionId = inspectionIds.isEmpty() ? Map.of()
                : defectRepository.countGroupByInspectionId(inspectionIds).stream()
                        .collect(Collectors.toMap(
                                InspectionDefectCountProjection::getInspectionId,
                                InspectionDefectCountProjection::getCnt));

        return PageResponse.from(page.map(inspection -> MyInspectionRowResponse.from(
                inspection,
                inspection.getFacility().getName(),
                defectCountByInspectionId.getOrDefault(inspection.getId(), 0L),
                userId)));
    }

    public List<MyReportRowResponse> getReports(Long userId, Long companyId, String periodCode) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        LocalDate periodFrom = resolvePeriodFrom(periodCode);
        // ReportRepository.findMyFinalizedReports는 issuedAtFrom을 not-null로 받는다(리포지토리 주석 참고 —
        // "(:param is null or ...)" 패턴이 PG 확장 프로토콜에서 named enum이 아닌 타입에도 실패해 우회함).
        LocalDateTime issuedAtFrom = periodFrom == null ? UNBOUNDED_ISSUED_AT_FROM : periodFrom.atStartOfDay();

        List<Report> reports = reportRepository.findMyFinalizedReports(
                userId, companyId, ReportStatus.FINALIZED, issuedAtFrom, PageRequest.of(0, MY_REPORTS_MAX));

        List<Long> inspectionIds = reports.stream().map(Report::getInspectionId).distinct().toList();
        Map<Long, Set<DefectGrade>> gradesByInspectionId = inspectionIds.isEmpty() ? Map.of()
                : defectRepository.findDistinctGradesByInspectionIds(inspectionIds).stream()
                        .collect(Collectors.groupingBy(
                                InspectionGradeProjection::getInspectionId,
                                Collectors.mapping(InspectionGradeProjection::getGrade, Collectors.toSet())));

        return reports.stream()
                .map(report -> MyReportRowResponse.from(
                        report,
                        report.getInspection().getFacility().getName(),
                        report.getInspection().getRoundNo(),
                        gradesByInspectionId.getOrDefault(report.getInspectionId(), Set.of()),
                        resolveFileSizeBytes(report)))
                .toList();
    }

    private LocalDate resolvePeriodFrom(String periodCode) {
        return PeriodFilter.fromCode(periodCode).cutoffDate(LocalDate.now(KST)).orElse(null);
    }

    // PDF가 없거나 조회 실패하면 예외를 던지지 않고 null(handoff §2-3) — 목록 전체가 죽으면 안 된다.
    private Long resolveFileSizeBytes(Report report) {
        String storageKey = extractStorageKey(report.getPdfUrl());
        if (storageKey == null) {
            return null;
        }
        try {
            return reportPdfStorage.load(report.getId(), storageKey).contentLength();
        } catch (Exception e) {
            log.warn("마이페이지 보고서 PDF 크기 조회 실패(reportId={})", report.getId(), e);
            return null;
        }
    }

    // pdfUrl 형식: "/api/reports/{id}/pdf/{storageKey}"(ReportController.uploadPdf) — 마지막 경로
    // 세그먼트가 storageKey다.
    private static String extractStorageKey(String pdfUrl) {
        if (pdfUrl == null || pdfUrl.isBlank()) {
            return null;
        }
        int idx = pdfUrl.lastIndexOf('/');
        if (idx < 0 || idx == pdfUrl.length() - 1) {
            return null;
        }
        return pdfUrl.substring(idx + 1);
    }
}
