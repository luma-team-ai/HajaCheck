package com.hajacheck.core.analysis.scheduler;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.core.analysis.service.InspectionAnalysisService;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * StuckAnalysisReaper 단위 테스트(코드 리뷰 P2 10차) — ANALYZING 회차를 훑어 회차별 고착 판정을
 * InspectionAnalysisService.reapIfStuck에 위임하는지, 한 건 실패가 배치 전체를 멈추지 않는지 고정한다.
 * 고착 판정 자체(하트비트 기준)는 InspectionAnalysisService/그 단위 테스트의 책임이라 여기서는 목으로 둔다.
 */
@ExtendWith(MockitoExtension.class)
class StuckAnalysisReaperTest {

    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private InspectionAnalysisService analysisService;

    @InjectMocks
    private StuckAnalysisReaper reaper;

    private Inspection analyzing(Long id) {
        Inspection inspection = Inspection.builder()
                .facilityId(5L)
                .createdBy(1L)
                .assignedInspectorId(1L)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 1))
                .status(InspectionStatus.ANALYZING)
                .build();
        ReflectionTestUtils.setField(inspection, "id", id);
        return inspection;
    }

    @Test
    void reapStuckAnalyses_ANALYZING회차마다_reapIfStuck에위임한다() {
        when(inspectionRepository.findByStatus(InspectionStatus.ANALYZING))
                .thenReturn(List.of(analyzing(1L), analyzing(2L), analyzing(3L)));

        reaper.reapStuckAnalyses();

        verify(analysisService).reapIfStuck(1L);
        verify(analysisService).reapIfStuck(2L);
        verify(analysisService).reapIfStuck(3L);
    }

    @Test
    void reapStuckAnalyses_한건복원이예외를던져도_나머지회차는계속처리한다() {
        when(inspectionRepository.findByStatus(InspectionStatus.ANALYZING))
                .thenReturn(List.of(analyzing(1L), analyzing(2L)));
        when(analysisService.reapIfStuck(1L)).thenThrow(new RuntimeException("복원 실패"));

        reaper.reapStuckAnalyses();

        // 1L이 던져도 2L은 처리된다(회차별 실패 격리).
        verify(analysisService).reapIfStuck(2L);
    }

    @Test
    void reapStuckAnalyses_ANALYZING회차가없으면_아무것도하지않는다() {
        when(inspectionRepository.findByStatus(InspectionStatus.ANALYZING)).thenReturn(List.of());

        reaper.reapStuckAnalyses();

        verify(analysisService, never()).reapIfStuck(eq(1L));
    }
}
