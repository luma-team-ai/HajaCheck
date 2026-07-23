package com.hajacheck.core.analysis.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.support.AnalysisProgressStore;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.service.DefectWriter;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * InspectionAnalysisService 단위 테스트(코드 리뷰 P2 픽스 검증) — 원자적 ANALYZING 선점,
 * ANALYZING 고착 복구, 큐 포화 시 롤백, 재분석 멱등화(소프트삭제)를 고정한다.
 * AnalysisProgressStore/InspectionAnalysisWorker를 목으로 대체해 Redis·@Async 없이 검증한다
 * (이전에는 관련 빈이 전부 {@code @Profile("!test")}로 배제돼 자동화 테스트가 전혀 없었다).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InspectionAnalysisServiceTest {

    @Mock
    private InspectionService inspectionService;
    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private DefectRepository defectRepository;
    @Mock
    private DefectWriter defectWriter;
    @Mock
    private AnalysisProgressStore progressStore;
    @Mock
    private InspectionAnalysisWorker worker;

    @InjectMocks
    private InspectionAnalysisService service;

    private static final Long USER_ID = 1L;
    private static final Long COMPANY_ID = 10L;
    private static final Long INSPECTION_ID = 100L;

    private Inspection inspectionWithStatus(InspectionStatus status) {
        Inspection inspection = Inspection.builder()
                .facilityId(5L)
                .createdBy(USER_ID)
                .assignedInspectorId(USER_ID)
                .roundNo(1)
                .inspectionDate(LocalDate.now())
                .status(status)
                .build();
        ReflectionTestUtils.setField(inspection, "id", INSPECTION_ID);
        return inspection;
    }

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
    void startAnalysis_이미ANALYZING이고_진행률캐시있으면_ALREADY_RUNNING() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.ANALYZING));
        when(progressStore.find(INSPECTION_ID)).thenReturn(Optional.of(anyProgress()));

        assertThatThrownBy(() -> service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_ALREADY_RUNNING);

        verify(worker, never()).runAsync(any(), any(), any(), any(), any());
        verify(inspectionService, never()).tryStartAnalyzing(any(), any(), any());
    }

    @Test
    void startAnalysis_ANALYZING인데_진행률캐시없으면_고착으로보고복구후재시작() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.ANALYZING));
        when(progressStore.find(INSPECTION_ID)).thenReturn(Optional.empty());
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        when(inspectionService.tryStartAnalyzing(USER_ID, COMPANY_ID, INSPECTION_ID)).thenReturn(true);

        service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID);

        // 고착 복구 — UPLOADING으로 강제 되돌린 뒤에야 원자적 선점을 시도한다.
        verify(inspectionService).advanceStatus(USER_ID, COMPANY_ID, INSPECTION_ID, InspectionStatus.UPLOADING);
        verify(defectWriter).softDeleteAllForInspection(INSPECTION_ID);
        verify(worker).runAsync(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any(),
                eq(InspectionStatus.UPLOADING));
    }

    @Test
    void startAnalysis_이미지없으면_NO_MEDIA_원자적선점은시도하지않는다() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.UPLOADING));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_NO_MEDIA);

        verify(inspectionService, never()).tryStartAnalyzing(any(), any(), any());
        verify(worker, never()).runAsync(any(), any(), any(), any(), any());
    }

    @Test
    void startAnalysis_원자적선점실패시_ALREADY_RUNNING_소프트삭제도워커도호출안함() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.UPLOADING));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        // 동시에 들어온 다른 요청이 먼저 선점했다고 가정 — 조건부 UPDATE 영향 행 0건.
        when(inspectionService.tryStartAnalyzing(USER_ID, COMPANY_ID, INSPECTION_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_ALREADY_RUNNING);

        verify(defectWriter, never()).softDeleteAllForInspection(any());
        verify(worker, never()).runAsync(any(), any(), any(), any(), any());
    }

    @Test
    void startAnalysis_정상흐름_기존하자소프트삭제후_워커에직전상태를넘긴다() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.UPLOADING));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L), image(2L)));
        when(inspectionService.tryStartAnalyzing(USER_ID, COMPANY_ID, INSPECTION_ID)).thenReturn(true);

        service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID);

        verify(defectWriter).softDeleteAllForInspection(INSPECTION_ID);
        verify(progressStore).save(any(AnalysisStatusResponse.class));
        verify(worker).runAsync(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any(),
                eq(InspectionStatus.UPLOADING));
    }

    @Test
    void startAnalysis_큐포화시_직전상태로롤백하고캐시를지운뒤_QUEUE_FULL던진다() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.UPLOADING));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        when(inspectionService.tryStartAnalyzing(USER_ID, COMPANY_ID, INSPECTION_ID)).thenReturn(true);
        org.mockito.Mockito.doThrow(new TaskRejectedException("full"))
                .when(worker).runAsync(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_QUEUE_FULL);

        // 큐잉 실패 이전에 이미 ANALYZING으로 전이됐던 것을 직전 상태(UPLOADING)로 되돌린다.
        verify(inspectionService, times(1))
                .advanceStatus(USER_ID, COMPANY_ID, INSPECTION_ID, InspectionStatus.UPLOADING);
        verify(progressStore).delete(INSPECTION_ID);
    }

    @Test
    void getStatus_진행률캐시있으면_그대로반환한다() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.ANALYZING));
        AnalysisStatusResponse cached = anyProgress();
        when(progressStore.find(INSPECTION_ID)).thenReturn(Optional.of(cached));

        AnalysisStatusResponse result = service.getStatus(USER_ID, COMPANY_ID, INSPECTION_ID);

        assertThat(result).isEqualTo(cached);
        verify(mediaRepository, never()).findByInspectionIdAndFileTypeOrderByIdAsc(anyLong(), any());
    }

    @Test
    void getStatus_캐시없고_분석된적없으면_전부대기상태로재구성한다() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.UPLOADING));
        when(progressStore.find(INSPECTION_ID)).thenReturn(Optional.empty());
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));

        AnalysisStatusResponse result = service.getStatus(USER_ID, COMPANY_ID, INSPECTION_ID);

        assertThat(result.stage()).isEqualTo("upload");
        assertThat(result.progressPercent()).isZero();
        assertThat(result.files()).hasSize(1);
        assertThat(result.files().get(0).status()).isEqualTo("waiting");
    }

    private AnalysisStatusResponse anyProgress() {
        return new AnalysisStatusResponse(
                INSPECTION_ID, "aiDetection", 50, 2, 1, List.of(), 0, 0,
                java.util.Map.of("A", 0, "B", 0, "C", 0, "D", 0, "E", 0), 0);
    }
}
