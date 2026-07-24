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
    // 세대 토큰(코드 리뷰 P1) — 대부분의 테스트는 펜싱과 무관하므로 findGeneration을 스텁하지
    // 않는다(기본값 Optional.empty() → isCurrentGeneration이 "유효함"으로 보수적으로 판단해 기존
    // 동작이 그대로 유지된다). 펜싱 자체를 검증하는 테스트만 findGeneration을 별도로 스텁한다.
    private static final String GENERATION = "gen-1";

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
                InspectionStatus.UPLOADING, GENERATION);

        verify(inspectionService, never())
                .advanceStatus(USER_ID, COMPANY_ID, INSPECTION_ID, InspectionStatus.ANALYZED);
        verify(inspectionService)
                .advanceStatus(USER_ID, COMPANY_ID, INSPECTION_ID, InspectionStatus.UPLOADING);
        verify(defectWriter, never()).saveAll(argThatNonEmpty());

        ArgumentCaptor<AnalysisStatusResponse> captor = ArgumentCaptor.forClass(AnalysisStatusResponse.class);
        verify(progressStore, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        AnalysisStatusResponse last = captor.getAllValues().get(captor.getAllValues().size() - 1);
        // 코드 리뷰 P2 — "failed"여야 프론트 폴링이 멈춘다(useAnalysisStatus는 'done'만 보던 걸
        // 'done'|'failed' 둘 다 보도록 고쳤다). "aiDetection"으로 두면 무한 폴링에 걸린다.
        assertThat(last.stage()).isEqualTo("failed");
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

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(ok, fail), InspectionStatus.UPLOADING, GENERATION);

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

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L)), InspectionStatus.UPLOADING, GENERATION);

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
                InspectionStatus.UPLOADING, GENERATION);

        verify(defectWriter, never()).softDeleteAllForInspectionThenSave(any(), any());
    }

    @Test
    void runAsync_첫탐지성공시점에_기존하자를딱한번만소프트삭제하고_나머지는saveAll만쓴다() {
        // 코드 리뷰 P2(잔여 창) — 소프트삭제와 "첫" 저장이 DefectWriter 쪽 한 트랜잭션(
        // softDeleteAllForInspectionThenSave)으로 묶였다. 두 번째 이미지부터는 이미 정리가
        // 끝났으니 saveAll만 호출된다.
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString())).thenReturn(List.of(detection("CRACK", "A")));
        when(defectWriter.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L), image(2L), image(3L)),
                InspectionStatus.UPLOADING, GENERATION);

        verify(defectWriter, org.mockito.Mockito.times(1))
                .softDeleteAllForInspectionThenSave(eq(INSPECTION_ID), any());
        verify(defectWriter, org.mockito.Mockito.times(2)).saveAll(any());
    }

    @Test
    void runAsync_소프트삭제결합호출은_두번째이미지저장보다먼저실행된다() {
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString())).thenReturn(List.of(detection("CRACK", "A")));
        when(defectWriter.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L), image(2L)),
                InspectionStatus.UPLOADING, GENERATION);

        InOrder inOrder = Mockito.inOrder(defectWriter);
        inOrder.verify(defectWriter).softDeleteAllForInspectionThenSave(eq(INSPECTION_ID), any());
        inOrder.verify(defectWriter).saveAll(any());
    }

    @Test
    void runAsync_첫이미지실패후_두번째이미지에서성공하면_그때소프트삭제한다() {
        // 소프트삭제(결합 호출)는 detect() + saveAll() 자체가 성공한 시점에 트리거된다 —
        // 실패한 이미지에서는 절대 트리거되지 않는다는 걸 순서로 고정한다.
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString()))
                .thenThrow(new RuntimeException("첫 이미지 실패"))
                .thenReturn(List.of(detection("CRACK", "A")));
        when(defectWriter.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L), image(2L)),
                InspectionStatus.UPLOADING, GENERATION);

        verify(defectWriter, org.mockito.Mockito.times(1))
                .softDeleteAllForInspectionThenSave(eq(INSPECTION_ID), any());
    }

    @Test
    void runAsync_detect는성공하지만saveAll이모두실패하면_소프트삭제자체가일어나지않는다() {
        // 코드 리뷰 P2(잔여 창) — 소프트삭제+저장을 한 트랜잭션(softDeleteAllForInspectionThenSave)
        // 으로 묶었으니, saveAll 부분에서 던져진 예외로 트랜잭션 전체가 롤백된다는 전제 아래,
        // 워커 입장에서는 이 호출 자체가 예외를 던진 것으로 관측된다 — successCount는 늘지 않고
        // 이 이미지는 실패로 격리된다(전체 실패 롤백 분기로 이어짐, 별도 테스트로 고정됨).
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString())).thenReturn(List.of(detection("CRACK", "A")));
        org.mockito.Mockito.doThrow(new RuntimeException("제약 위반"))
                .when(defectWriter).softDeleteAllForInspectionThenSave(any(), any());

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L)), InspectionStatus.UPLOADING, GENERATION);

        verify(inspectionService, never())
                .advanceStatus(USER_ID, COMPANY_ID, INSPECTION_ID, InspectionStatus.ANALYZED);
    }

    @Test
    void runAsync_세대토큰이바뀌면_이후쓰기와상태전이없이_첫번째쓰기시점에조용히중단한다() {
        // 코드 리뷰 P1(워커 펜싱) — 진행률 저장소에 기록된 "현재" 세대 토큰이 이 실행이 받은 토큰과
        // 다르면(다른 실행이 재선점해 자신이 추월당함) DB에 더 이상 쓰지 않고 즉시 중단해야 한다.
        // detect()는 성공해서 쓰기 직전까지 도달하지만, 그 직전 토큰 확인에서 걸려 소프트삭제/저장
        // 어느 쪽도 호출되지 않고, 두 번째 이미지는 시도조차 하지 않는다(즉시 return).
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString())).thenReturn(List.of(detection("CRACK", "A")));
        when(progressStore.findGeneration(INSPECTION_ID)).thenReturn(java.util.Optional.of("other-gen-추월함"));

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L), image(2L)),
                InspectionStatus.UPLOADING, GENERATION);

        verify(defectWriter, never()).softDeleteAllForInspectionThenSave(any(), any());
        verify(defectWriter, never()).saveAll(any());
        verify(inspectionService, never()).advanceStatus(any(), any(), any(), any());
        verify(aiProxyService, org.mockito.Mockito.times(1)).detectDefects(anyString());
    }

    @Test
    void runAsync_세대토큰저장소가비어있으면_펜싱정보없음으로보고정상진행한다() {
        // fail-soft(코드 리뷰 P1) — findGeneration이 비어있으면(TTL 만료·Redis 장애 등) "펜싱 정보
        // 없음"으로 보수적으로 계속 진행한다. 이 저장소 장애가 정상 진행 중인 분석 잡까지 막으면 안 된다.
        when(fileStorage.read(anyString())).thenReturn(new byte[] {1});
        when(aiProxyService.detectDefects(anyString())).thenReturn(List.of(detection("CRACK", "A")));
        when(defectWriter.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        when(progressStore.findGeneration(INSPECTION_ID)).thenReturn(java.util.Optional.empty());

        worker.runAsync(USER_ID, COMPANY_ID, INSPECTION_ID, List.of(image(1L)), InspectionStatus.UPLOADING, GENERATION);

        verify(inspectionService)
                .advanceStatus(USER_ID, COMPANY_ID, INSPECTION_ID, InspectionStatus.ANALYZED);
    }

    private DetectedDefectItem detection(String type, String grade) {
        return new DetectedDefectItem(type, 0.1, 0.1, 0.2, 0.2, 0.9, grade);
    }

    @SuppressWarnings("unchecked")
    private List<Defect> argThatNonEmpty() {
        return org.mockito.ArgumentMatchers.argThat(list -> list != null && !list.isEmpty());
    }
}
