package com.hajacheck.core.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.support.FileStorageService;
import com.hajacheck.core.ai.dto.DetectedDefectItem;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.support.AnalysisProgressStore;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.service.DefectWriter;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * InspectionAnalysisWorker 단위 테스트(코드 리뷰 P2 픽스 검증) — 전체 실패 시 ANALYZED로
 * 오인 전이하지 않는지, 부분 성공 시에는 정상 완료 처리되는지, 등급·위험 균열 집계가 맞는지 고정한다.
 * {@code @Async}는 프록시를 거쳐야 적용되므로 이 단위 테스트에서 runAsync()를 직접 호출하면
 * 동기 실행된다(의도된 동작 — 테스트 목적에 맞음).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InspectionAnalysisWorkerTest {

    @Mock
    private InspectionService inspectionService;
    @Mock
    private FileStorageService fileStorage;
    @Mock
    private AiProxyService aiProxyService;
    @Mock
    private DefectWriter defectWriter;
    @Mock
    private AnalysisProgressStore progressStore;

    @InjectMocks
    private InspectionAnalysisWorker worker;

    private static final Long USER_ID = 1L;
    private static final Long COMPANY_ID = 10L;
    private static final Long INSPECTION_ID = 100L;

    private Media image(Long id) {
        Media media = Media.builder()
                .inspectionId(INSPECTION_ID)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("orig/" + id)
                .mimeSignatureVerified(true)
                .mimeType("image/jpeg")
                .build();
        ReflectionTestUtils.setField(media, "id", id);
        return media;
    }

    @Test
    void runAsync_전체이미지실패시_ANALYZED로전이하지않고직전상태로되돌린다() {
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString())).thenThrow(new RuntimeException("AI 서버 다운"));

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L), image(2L)),
                InspectionStatus.UPLOADING);

        verify(inspectionService, never())
                .advanceStatus(USER_ID, COMPANY_ID, INSPECTION_ID, InspectionStatus.ANALYZED);
        verify(inspectionService)
                .advanceStatus(USER_ID, COMPANY_ID, INSPECTION_ID, InspectionStatus.UPLOADING);
        verify(defectWriter, never()).saveAll(argThatNonEmpty());

        ArgumentCaptor<AnalysisStatusResponse> captor = ArgumentCaptor.forClass(AnalysisStatusResponse.class);
        verify(progressStore, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        AnalysisStatusResponse last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.stage()).isNotEqualTo("done");
        assertThat(last.failedCount()).isEqualTo(2);
        assertThat(last.detectedDefectCount()).isZero();
    }

    @Test
    void runAsync_일부만성공해도_ANALYZED로정상전이한다() {
        Media ok = image(1L);
        Media fail = image(2L);
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString()))
                .thenReturn(List.of(detection("CRACK", "A")))
                .thenThrow(new RuntimeException("타임아웃"));
        when(defectWriter.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(ok, fail), InspectionStatus.UPLOADING);

        verify(inspectionService)
                .advanceStatus(USER_ID, COMPANY_ID, INSPECTION_ID, InspectionStatus.ANALYZED);

        ArgumentCaptor<AnalysisStatusResponse> captor = ArgumentCaptor.forClass(AnalysisStatusResponse.class);
        verify(progressStore, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        AnalysisStatusResponse last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(last.stage()).isEqualTo("done");
        assertThat(last.failedCount()).isEqualTo(1);
        assertThat(last.detectedDefectCount()).isEqualTo(1);
    }

    @Test
    void runAsync_등급별집계와_위험진행성균열카운트가정확하다() {
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString())).thenReturn(List.of(
                detection("CRACK", "E"),      // 위험 균열(D/E) 카운트에 포함
                detection("CRACK", "A"),      // 경미 — 위험 카운트 제외
                detection("SPALLING", "D")    // CRACK이 아니므로 위험 카운트 제외
        ));
        when(defectWriter.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L)), InspectionStatus.UPLOADING);

        ArgumentCaptor<AnalysisStatusResponse> captor = ArgumentCaptor.forClass(AnalysisStatusResponse.class);
        verify(progressStore, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        AnalysisStatusResponse last = captor.getAllValues().get(captor.getAllValues().size() - 1);

        assertThat(last.detectedDefectCount()).isEqualTo(3);
        assertThat(last.riskyCrackCount()).isEqualTo(1);
        assertThat(last.severityDistribution().get("E")).isEqualTo(1);
        assertThat(last.severityDistribution().get("A")).isEqualTo(1);
        assertThat(last.severityDistribution().get("D")).isEqualTo(1);
    }

    @Test
    void runAsync_전체이미지실패시_기존하자를소프트삭제하지않는다() {
        // 코드 리뷰 P2 — 검수 완료된 회차를 재분석하다 AI 서버가 전면 다운되는 경우, 기존(사람이
        // 검수한) 하자를 지워버리면 새 탐지가 하나도 없으니 영구 유실된다. 소프트삭제는 첫 탐지
        // 성공 시에만 지연 실행되므로, 전체 실패면 한 번도 호출되지 않아야 한다.
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString())).thenThrow(new RuntimeException("AI 서버 다운"));

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L), image(2L)),
                InspectionStatus.UPLOADING);

        verify(defectWriter, never()).softDeleteAllForInspection(any());
    }

    @Test
    void runAsync_첫탐지성공시점에_기존하자를딱한번만소프트삭제한다() {
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString())).thenReturn(List.of(detection("CRACK", "A")));
        when(defectWriter.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L), image(2L), image(3L)),
                InspectionStatus.UPLOADING);

        verify(defectWriter, org.mockito.Mockito.times(1)).softDeleteAllForInspection(INSPECTION_ID);
    }

    @Test
    void runAsync_소프트삭제는_새하자저장보다항상먼저실행된다() {
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString())).thenReturn(List.of(detection("CRACK", "A")));
        when(defectWriter.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L)), InspectionStatus.UPLOADING);

        InOrder inOrder = Mockito.inOrder(defectWriter);
        inOrder.verify(defectWriter).softDeleteAllForInspection(INSPECTION_ID);
        inOrder.verify(defectWriter).saveAll(any());
    }

    @Test
    void runAsync_첫이미지실패후_두번째이미지에서성공하면_그때소프트삭제한다() {
        // 소프트삭제는 detect() 자체가 성공한 시점(빈 탐지 결과도 성공으로 침)에 트리거된다 —
        // 실패한 이미지에서는 절대 트리거되지 않는다는 걸 순서로 고정한다.
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString()))
                .thenThrow(new RuntimeException("첫 이미지 실패"))
                .thenReturn(List.of(detection("CRACK", "A")));
        when(defectWriter.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L), image(2L)),
                InspectionStatus.UPLOADING);

        verify(defectWriter, org.mockito.Mockito.times(1)).softDeleteAllForInspection(INSPECTION_ID);
    }

    private DetectedDefectItem detection(String type, String grade) {
        return new DetectedDefectItem(type, 0.1, 0.1, 0.2, 0.2, 0.9, grade);
    }

    @SuppressWarnings("unchecked")
    private List<Defect> argThatNonEmpty() {
        return org.mockito.ArgumentMatchers.argThat(list -> list != null && !list.isEmpty());
    }
}
