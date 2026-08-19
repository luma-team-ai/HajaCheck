package com.hajacheck.core.report.scheduler;

import com.hajacheck.core.report.config.ReportPdfCleanupProperties;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.core.report.support.ReportPdfStorage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 고아 보고서 PDF 정리 배치(#1653 P3) — StuckAnalysisReaper/RagEmbeddingStaleReconciler와 동일 패턴.
 *
 * <p>finalize 전 업로드({@code POST /api/reports/{id}/pdf}) 후 확정하지 않고 방치된 파일이나, 재업로드로
 * 남은 이전 파일은 어느 {@link Report#getPdfUrl()}에도 참조되지 않는다. {@code ReportService.deleteDraftReport}
 * (#1653)는 삭제 시점에 그 보고서의 저장 파일을 즉시 정리하지만, 초안을 삭제도 확정도 하지 않고 방치하는
 * 경우까지는 커버하지 못한다 — 이 배치가 주기적으로 저장소 전체를 훑어, "확정 PDF로 참조되지 않고 +
 * 유예기간(retention)이 지난" 파일만 제거한다.
 *
 * <p>⚠️ 단일 인스턴스 실행 전제(StuckAnalysisReaper와 동일). {@link ReportPdfStorage#deleteOrphans}는
 * 참조되지 않는 파일만 지우는 멱등 연산이라 중복 실행이 데이터를 손상시키지 않는다. 보고서 1건 처리
 * 실패를 격리해 한 건 실패가 배치 전체를 멈추지 않게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanReportPdfCleanupScheduler {

    // 하루 1회면 충분 — 즉시 정리는 deleteDraftReport가 이미 처리하고, 이 배치는 "방치된" 파일을
    // 겨냥하므로 촘촘히 돌 필요가 없다. 최초 실행은 기동 직후 부하를 피해 5분 뒤부터.
    private static final long CLEANUP_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final long CLEANUP_INITIAL_DELAY_MS = 5 * 60 * 1000L;

    private final ReportRepository reportRepository;
    private final ReportPdfStorage reportPdfStorage;
    // 유예기간(일)은 hajacheck.report.pdf-cleanup.retention-days로 외부화(#1680) — 기본 7일은
    // ReportPdfCleanupProperties에서 관리한다(운영 env로 조정 가능).
    private final ReportPdfCleanupProperties reportPdfCleanupProperties;

    @Scheduled(fixedDelay = CLEANUP_INTERVAL_MS, initialDelay = CLEANUP_INITIAL_DELAY_MS)
    public void cleanupOrphanPdfs() {
        List<Long> reportIds = reportPdfStorage.listReportIdsWithStoredFiles();
        if (reportIds.isEmpty()) {
            return;
        }
        Instant cutoff = Instant.now().minus(reportPdfCleanupProperties.getRetentionDays(), ChronoUnit.DAYS);
        int totalRemoved = 0;
        int failed = 0;
        for (Long reportId : reportIds) {
            try {
                totalRemoved += reportPdfStorage.deleteOrphans(reportId, resolveKeepStorageKey(reportId), cutoff);
            } catch (Exception e) {
                failed++;
                log.warn("고아 PDF 정리 개별 처리 실패 — reportId={} exception={}",
                        reportId, e.getClass().getSimpleName());
            }
        }
        log.info("고아 PDF 정리 배치 — 대상 {}건 중 {}개 파일 삭제, {}건 처리 실패", reportIds.size(), totalRemoved, failed);
    }

    /**
     * FINALIZED이고 소프트 삭제되지 않은 보고서라면 pdfUrl이 가리키는 storageKey만 보존 대상이다.
     * 그 외(보고서 없음·소프트 삭제됨·아직 DRAFT로 방치됨)는 보존할 파일이 없다(null — 전부 삭제 후보).
     */
    private String resolveKeepStorageKey(Long reportId) {
        Optional<Report> found = reportRepository.findById(reportId);
        if (found.isEmpty()) {
            return null;
        }
        Report report = found.get();
        if (report.getDeletedAt() != null || report.getStatus() != ReportStatus.FINALIZED) {
            return null;
        }
        return extractStorageKey(reportId, report.getPdfUrl());
    }

    private static String extractStorageKey(Long reportId, String pdfUrl) {
        if (!StringUtils.hasText(pdfUrl)) {
            return null;
        }
        String expectedPrefix = "/api/reports/%d/pdf/".formatted(reportId);
        if (!pdfUrl.startsWith(expectedPrefix) || pdfUrl.length() == expectedPrefix.length()) {
            return null;
        }
        return pdfUrl.substring(expectedPrefix.length());
    }
}
