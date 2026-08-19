package com.hajacheck.core.report.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.core.report.config.ReportPdfCleanupProperties;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.entity.ReportStatus;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.core.report.support.ReportPdfStorage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * OrphanReportPdfCleanupScheduler 단위 테스트(#1653 P3) — StuckAnalysisReaperTest와 동일 패턴.
 * 실제 파일시스템 정리 동작(오래된 파일만 삭제 등)은 LocalReportPdfStorageTest 책임이라, 여기서는
 * "어느 reportId를 어떤 keepStorageKey로 정리 위임하는지"의 배선과 개별 실패 격리만 고정한다.
 *
 * <p>ReportPdfCleanupProperties(#1680)는 단순 값 홀더라 mock 대신 실제 인스턴스를 수동 생성자
 * 주입한다(@InjectMocks 생성자 주입은 @Mock/@Spy로 선언된 필드만 후보로 삼아, 목이 아닌 값 객체는
 * null로 주입돼 getRetentionDays() 호출 시 NPE가 난다).
 */
@ExtendWith(MockitoExtension.class)
class OrphanReportPdfCleanupSchedulerTest {

    @Mock
    private ReportRepository reportRepository;
    @Mock
    private ReportPdfStorage reportPdfStorage;

    private final ReportPdfCleanupProperties reportPdfCleanupProperties = new ReportPdfCleanupProperties();

    private OrphanReportPdfCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new OrphanReportPdfCleanupScheduler(reportRepository, reportPdfStorage, reportPdfCleanupProperties);
    }

    private Report finalizedReport(Long id, String pdfUrl) {
        Report report = Report.draft(1L, 1, "{}", 100L);
        ReflectionTestUtils.setField(report, "id", id);
        ReflectionTestUtils.setField(report, "status", ReportStatus.FINALIZED);
        ReflectionTestUtils.setField(report, "pdfUrl", pdfUrl);
        return report;
    }

    @Test
    void cleanupOrphanPdfs_FINALIZED보고서는확정PDF만보존대상으로넘긴다() {
        when(reportPdfStorage.listReportIdsWithStoredFiles()).thenReturn(List.of(10L));
        when(reportRepository.findById(10L))
                .thenReturn(Optional.of(finalizedReport(10L, "/api/reports/10/pdf/keep.pdf")));
        when(reportPdfStorage.deleteOrphans(eq(10L), eq("keep.pdf"), any())).thenReturn(2);

        scheduler.cleanupOrphanPdfs();

        verify(reportPdfStorage).deleteOrphans(eq(10L), eq("keep.pdf"), any());
    }

    @Test
    void cleanupOrphanPdfs_DRAFT로방치된보고서는전부삭제후보로넘긴다() {
        Report draft = Report.draft(1L, 1, "{}", 100L);
        ReflectionTestUtils.setField(draft, "id", 11L);
        when(reportPdfStorage.listReportIdsWithStoredFiles()).thenReturn(List.of(11L));
        when(reportRepository.findById(11L)).thenReturn(Optional.of(draft));

        scheduler.cleanupOrphanPdfs();

        verify(reportPdfStorage).deleteOrphans(eq(11L), isNull(), any());
    }

    @Test
    void cleanupOrphanPdfs_보고서가삭제되었거나존재하지않으면전부삭제후보로넘긴다() {
        when(reportPdfStorage.listReportIdsWithStoredFiles()).thenReturn(List.of(12L));
        when(reportRepository.findById(12L)).thenReturn(Optional.empty());

        scheduler.cleanupOrphanPdfs();

        verify(reportPdfStorage).deleteOrphans(eq(12L), isNull(), any());
    }

    @Test
    void cleanupOrphanPdfs_저장소에파일없으면아무일도하지않는다() {
        when(reportPdfStorage.listReportIdsWithStoredFiles()).thenReturn(List.of());

        scheduler.cleanupOrphanPdfs();

        verify(reportPdfStorage, never()).deleteOrphans(any(), any(), any());
    }

    @Test
    void cleanupOrphanPdfs_한건실패해도나머지는계속처리한다() {
        when(reportPdfStorage.listReportIdsWithStoredFiles()).thenReturn(List.of(20L, 21L));
        when(reportRepository.findById(20L)).thenThrow(new RuntimeException("boom"));
        when(reportRepository.findById(21L)).thenReturn(Optional.empty());

        scheduler.cleanupOrphanPdfs();

        verify(reportPdfStorage).deleteOrphans(eq(21L), isNull(), any());
    }

    @Test
    void cleanupOrphanPdfs_설정된유예기간이cutoff계산에반영된다() {
        reportPdfCleanupProperties.setRetentionDays(14);
        when(reportPdfStorage.listReportIdsWithStoredFiles()).thenReturn(List.of(30L));
        when(reportRepository.findById(30L)).thenReturn(Optional.empty());

        scheduler.cleanupOrphanPdfs();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(reportPdfStorage).deleteOrphans(eq(30L), isNull(), cutoffCaptor.capture());
        Instant expectedCutoff = Instant.now().minus(14, ChronoUnit.DAYS);
        assertThat(cutoffCaptor.getValue()).isCloseTo(expectedCutoff, within(5, ChronoUnit.SECONDS));
    }
}
