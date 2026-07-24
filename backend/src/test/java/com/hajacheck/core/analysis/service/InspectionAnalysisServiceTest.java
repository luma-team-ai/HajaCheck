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
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
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
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * InspectionAnalysisService 단위 테스트(코드 리뷰 P1/P2 픽스 검증) — 원자적 ANALYZING 선점,
 * ANALYZING 고착 복구, 큐 포화 시 롤백을 고정한다. 기존 하자 소프트삭제(재분석 멱등화)는 더 이상
 * 이 클래스의 책임이 아니라 {@link InspectionAnalysisWorker}로 옮겨졌으므로(P2 —
 * InspectionAnalysisWorkerTest 참고) 이 파일은 더 이상 DefectWriter를 목킹하지 않는다.
 * AnalysisProgressStore/InspectionAnalysisWorker를 목으로 대체해 Redis·@Async 없이 검증한다
 * (이전에는 관련 빈이 전부 {@code @Profile("!test")}로 배제돼 자동화 테스트가 전혀 없었다).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InspectionAnalysisServiceTest {

    @Mock
    private InspectionService inspectionService;
    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private DefectRepository defectRepository;
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

        verify(worker, never()).runAsync(any(), any(), any(), any(), any(), any());
        verify(inspectionService, never()).tryStartAnalyzing(any(), any(), any(), any());
    }

    @Test
    void startAnalysis_ANALYZING인데_진행률캐시없고_Redis정상이면_고착으로보고복구후재시작() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.ANALYZING));
        when(progressStore.find(INSPECTION_ID)).thenReturn(Optional.empty());
        when(progressStore.isAvailable()).thenReturn(true);
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        when(inspectionService.tryStartAnalyzing(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any())).thenReturn(true);

        service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID);

        // 고착 복구 — UPLOADING으로 강제 되돌린 뒤에야 원자적 선점을 시도한다.
        verify(inspectionService).advanceStatus(USER_ID, COMPANY_ID, INSPECTION_ID, InspectionStatus.UPLOADING);
        verify(worker).runAsync(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any(),
                eq(InspectionStatus.UPLOADING), any());
    }

    @Test
    void startAnalysis_ANALYZING인데_캐시없고_Redis자체가불안정하면_ALREADY_RUNNING_이중워커안함() {
        // 코드 리뷰 P2(사용자 확인 완료) — find()가 fail-soft라 "캐시 진짜 없음"과 "Redis 장애로
        // 못 읽음"을 Optional만으로는 구분 못한다. isAvailable()==false(Redis 자체가 불안정)면
        // 실제로는 다른 워커가 살아서 돌고 있을 수 있으니 고착으로 오판해 재시작하지 않고 보수적으로
        // ALREADY_RUNNING을 던진다 — 정상 진행 중인 잡에 대해 이중 워커가 뜨는 걸 막는다.
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.ANALYZING));
        when(progressStore.find(INSPECTION_ID)).thenReturn(Optional.empty());
        when(progressStore.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_ALREADY_RUNNING);

        verify(inspectionService, never()).advanceStatus(any(), any(), any(), any());
        verify(worker, never()).runAsync(any(), any(), any(), any(), any(), any());
    }

    @Test
    void startAnalysis_ANALYZING인데_캐시하트비트가오래됐으면_고착으로보고복구후재시작() {
        // 코드 리뷰 P2(사용자 확인 완료) — 워커가 JVM 재기동·OOM 등으로 크래시해도 진행률 캐시는
        // TTL 6시간 동안 살아있다. "캐시 부재"만 고착으로 보면 이 경우를 못 잡는다 — 캐시가
        // 있어도 하트비트(updatedAt)가 임계값(5분)보다 오래됐으면 고착으로 봐야 한다.
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.ANALYZING));
        when(progressStore.find(INSPECTION_ID))
                .thenReturn(Optional.of(progressAsOf(java.time.Instant.now().minus(java.time.Duration.ofMinutes(10)))));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        when(inspectionService.tryStartAnalyzing(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any())).thenReturn(true);

        service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID);

        verify(inspectionService).advanceStatus(USER_ID, COMPANY_ID, INSPECTION_ID, InspectionStatus.UPLOADING);
        verify(worker).runAsync(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any(),
                eq(InspectionStatus.UPLOADING), any());
    }

    @Test
    void startAnalysis_ANALYZING인데_캐시하트비트가신선하면_ALREADY_RUNNING_재시작안함() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.ANALYZING));
        when(progressStore.find(INSPECTION_ID))
                .thenReturn(Optional.of(progressAsOf(java.time.Instant.now().minus(java.time.Duration.ofSeconds(30)))));

        assertThatThrownBy(() -> service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_ALREADY_RUNNING);

        verify(inspectionService, never()).advanceStatus(any(), any(), any(), any());
        verify(worker, never()).runAsync(any(), any(), any(), any(), any(), any());
    }

    @Test
    void startAnalysis_REVIEWED회차는_ANALYSIS_NOT_ALLOWED로거부하고_아무것도호출안함() {
        startAnalysis_최종상태회차는_재분석거부됨(InspectionStatus.REVIEWED);
    }

    @Test
    void startAnalysis_REPORTED회차는_ANALYSIS_NOT_ALLOWED로거부하고_아무것도호출안함() {
        startAnalysis_최종상태회차는_재분석거부됨(InspectionStatus.REPORTED);
    }

    private void startAnalysis_최종상태회차는_재분석거부됨(InspectionStatus finalStatus) {
        // 코드 리뷰 P1(제품 결정) — 검수 완료(REVIEWED)·보고서화(REPORTED) 회차는 재분석을
        // 허용하지 않는다. 허용하면 워커가 첫 탐지 성공 시 사람이 검수한 기존 하자를 소프트삭제하고
        // 상태를 ANALYZED로 되돌려버려(역행) 보고서 확정 워크플로우가 깨진다.
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(finalStatus));

        assertThatThrownBy(() -> service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_NOT_ALLOWED);

        verify(mediaRepository, never()).findByInspectionIdAndFileTypeOrderByIdAsc(any(), any());
        verify(inspectionService, never()).tryStartAnalyzing(any(), any(), any(), any());
        verify(inspectionService, never()).advanceStatus(any(), any(), any(), any());
        verify(progressStore, never()).save(any());
        verify(worker, never()).runAsync(any(), any(), any(), any(), any(), any());
    }

    @Test
    void startAnalysis_ANALYZED인데_하자가하나라도있으면_fail_closed로재분석거부한다() {
        // 코드 리뷰 P1 5차(fail-closed) — "사람이 손댄 하자"를 revision/sentinel로 추론하던 방식이
        // 그 판정을 남기지 않는 입력 경로(수동 하자 추가 등)로 계속 뚫렸다. AI/사람 구분 컬럼(#644)
        // 전까지는 하자가 존재하면 재분석 자체를 거부한다 — 판정 방식이 아니라 "하자 존재"만 본다.
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.ANALYZED));
        when(defectRepository.existsByInspectionIdAndDeletedFalse(INSPECTION_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_NOT_ALLOWED);

        verify(mediaRepository, never()).findByInspectionIdAndFileTypeOrderByIdAsc(any(), any());
        verify(inspectionService, never()).tryStartAnalyzing(any(), any(), any(), any());
        verify(worker, never()).runAsync(any(), any(), any(), any(), any(), any());
    }

    @Test
    void startAnalysis_ANALYZED인데_하자가하나도없으면_정상진행한다() {
        // 재분석으로 유실될 하자가 없으므로(예: 이전 분석이 아무 하자도 못 찾음) 재분석을 허용한다.
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.ANALYZED));
        when(defectRepository.existsByInspectionIdAndDeletedFalse(INSPECTION_ID)).thenReturn(false);
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        when(inspectionService.tryStartAnalyzing(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any())).thenReturn(true);

        service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID);

        verify(worker).runAsync(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any(),
                eq(InspectionStatus.ANALYZED), any());
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

        verify(inspectionService, never()).tryStartAnalyzing(any(), any(), any(), any());
        verify(worker, never()).runAsync(any(), any(), any(), any(), any(), any());
    }

    @Test
    void startAnalysis_회사별동시실행상한초과시_ANALYSIS_COMPANY_CONCURRENCY_LIMIT으로거부하고_선점시도안함() {
        // 코드 리뷰 P2 4차(noisy-neighbor) — 한 회사가 이미 상한(2건)만큼 ANALYZING 중이면, 공유
        // 실행기 큐에 넣기 전에(tryStartAnalyzing 호출 전에) 먼저 거부해야 한다 — 안 그러면 이
        // 회사 요청이 계속 큐를 채워 다른 회사 요청까지 밀어낸다.
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.UPLOADING));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        when(inspectionRepository.countByFacilityCompanyIdAndStatus(COMPANY_ID, InspectionStatus.ANALYZING))
                .thenReturn(2L);

        assertThatThrownBy(() -> service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_COMPANY_CONCURRENCY_LIMIT);

        verify(inspectionService, never()).tryStartAnalyzing(any(), any(), any(), any());
        verify(worker, never()).runAsync(any(), any(), any(), any(), any(), any());
    }

    @Test
    void startAnalysis_회사별동시실행상한미만이면_정상진행한다() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.UPLOADING));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        when(inspectionRepository.countByFacilityCompanyIdAndStatus(COMPANY_ID, InspectionStatus.ANALYZING))
                .thenReturn(1L);
        when(inspectionService.tryStartAnalyzing(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any())).thenReturn(true);

        service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID);

        verify(worker).runAsync(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any(),
                eq(InspectionStatus.UPLOADING), any());
    }

    @Test
    void startAnalysis_원자적선점실패시_ALREADY_RUNNING_워커호출안함() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.UPLOADING));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        // 동시에 들어온 다른 요청이 먼저 선점했다고 가정 — 조건부 UPDATE 영향 행 0건.
        when(inspectionService.tryStartAnalyzing(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any())).thenReturn(false);

        assertThatThrownBy(() -> service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.ANALYSIS_ALREADY_RUNNING);

        verify(progressStore, never()).save(any());
        verify(worker, never()).runAsync(any(), any(), any(), any(), any(), any());
    }

    @Test
    void startAnalysis_정상흐름_워커에직전상태를넘긴다() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.UPLOADING));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L), image(2L)));
        when(inspectionService.tryStartAnalyzing(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any())).thenReturn(true);

        service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID);

        verify(progressStore).save(any(AnalysisStatusResponse.class));
        verify(worker).runAsync(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any(),
                eq(InspectionStatus.UPLOADING), any());
    }

    @Test
    void startAnalysis_P1_원자적선점직후_무거운작업없이곧바로진행률캐시를기록한다() {
        // 코드 리뷰 P1 — 예전엔 tryStartAnalyzing 성공과 progressStore.save 사이에 소프트삭제
        // (DB 트랜잭션, 수십~수백 ms)가 끼어 있어, 그 창에서 다른 요청이 "ANALYZING인데 캐시가
        // 없다"를 관측하고 고착으로 오판해 강제 복구 후 재선점(이중 워커 실행)까지 갈 수 있었다.
        // 지금은 선점 성공 직후 진행률 캐시 기록까지 사이에 인메모리 리스트 구성 외에 아무 것도
        // 없다는 것을 실행 순서로 고정한다 — worker.runAsync는 그 다음에야 호출된다.
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.UPLOADING));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        when(inspectionService.tryStartAnalyzing(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any())).thenReturn(true);

        service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID);

        InOrder inOrder = Mockito.inOrder(inspectionService, progressStore, worker);
        inOrder.verify(inspectionService).tryStartAnalyzing(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any());
        inOrder.verify(progressStore).save(any(AnalysisStatusResponse.class));
        inOrder.verify(worker).runAsync(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any(), any(), any());
    }

    @Test
    void startAnalysis_선점마다_새세대토큰을발급해서저장소에기록한다() {
        // 코드 리뷰 P1(워커 펜싱) — 정상 선점이든 고착 복구 후 재선점이든, tryStartAnalyzing이
        // 성공할 때마다 새 세대 토큰이 발급·기록돼야 한다. 이 토큰이 없으면(또는 재사용되면) 추월당한
        // 워커를 스스로 중단시킬 방법이 없다(InspectionAnalysisWorker 참고).
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.UPLOADING));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        when(inspectionService.tryStartAnalyzing(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any())).thenReturn(true);

        service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID);

        org.mockito.ArgumentCaptor<String> generationCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(progressStore).saveGeneration(eq(INSPECTION_ID), generationCaptor.capture());
        assertThat(generationCaptor.getValue()).isNotBlank();
        // 워커에도 저장소에 기록한 것과 "같은" 토큰이 전달돼야 스스로 비교할 수 있다.
        verify(worker).runAsync(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any(),
                eq(InspectionStatus.UPLOADING), eq(generationCaptor.getValue()));
    }

    @Test
    void startAnalysis_고착복구로재선점하면_새세대토큰이발급된다() {
        // 코드 리뷰 P1 — stuckReason(review 문구 그대로) 검증: ANALYZING 고착 복구 경로도 일반
        // 선점과 동일하게 tryStartAnalyzing 성공 이후 새 세대 토큰을 발급해야 한다. 이게 없으면
        // 하트비트 오탐으로 살아있는 원본 워커를 펜싱할 수단이 없다.
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.ANALYZING));
        when(progressStore.find(INSPECTION_ID))
                .thenReturn(Optional.of(progressAsOf(java.time.Instant.now().minus(java.time.Duration.ofMinutes(10)))));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        when(inspectionService.tryStartAnalyzing(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any())).thenReturn(true);

        service.startAnalysis(USER_ID, COMPANY_ID, INSPECTION_ID);

        verify(progressStore).saveGeneration(eq(INSPECTION_ID), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void startAnalysis_큐포화시_직전상태로롤백하고캐시를지운뒤_QUEUE_FULL던진다() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.UPLOADING));
        when(mediaRepository.findByInspectionIdAndFileTypeOrderByIdAsc(INSPECTION_ID, MediaFileType.IMAGE))
                .thenReturn(List.of(image(1L)));
        when(inspectionService.tryStartAnalyzing(eq(USER_ID), eq(COMPANY_ID), eq(INSPECTION_ID), any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new TaskRejectedException("full"))
                .when(worker).runAsync(any(), any(), any(), any(), any(), any());

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
    void getStatus_캐시가오래된진행중상태면_failed로표면화하되나머지값은보존한다() {
        // 코드 리뷰 P2(사용자 확인 완료) — GET은 읽기 전용이라 DB/Redis를 고치지 않는다. stage만
        // "failed"로 바꿔 프론트 폴링이 멈추고 재시도 버튼이 뜨게 한다(useAnalysisStatus 참고).
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.ANALYZING));
        AnalysisStatusResponse stale =
                progressAsOf(java.time.Instant.now().minus(java.time.Duration.ofMinutes(10)));
        when(progressStore.find(INSPECTION_ID)).thenReturn(Optional.of(stale));

        AnalysisStatusResponse result = service.getStatus(USER_ID, COMPANY_ID, INSPECTION_ID);

        assertThat(result.stage()).isEqualTo("failed");
        assertThat(result.progressPercent()).isEqualTo(stale.progressPercent());
        assertThat(result.analyzedFileCount()).isEqualTo(stale.analyzedFileCount());
        verify(inspectionService, never()).advanceStatus(any(), any(), any(), any());
        verify(progressStore, never()).save(any());
        verify(progressStore, never()).delete(any());
    }

    @Test
    void getStatus_캐시가오래됐어도_이미done이면_그대로둔다() {
        when(inspectionService.getOwnedInspectionEntity(USER_ID, COMPANY_ID, INSPECTION_ID))
                .thenReturn(inspectionWithStatus(InspectionStatus.ANALYZED));
        AnalysisStatusResponse oldDone = new AnalysisStatusResponse(
                INSPECTION_ID, "done", 100, 2, 2, List.of(), 3, 1,
                java.util.Map.of("A", 0, "B", 0, "C", 1, "D", 1, "E", 1), 0,
                java.time.Instant.now().minus(java.time.Duration.ofHours(1)));
        when(progressStore.find(INSPECTION_ID)).thenReturn(Optional.of(oldDone));

        AnalysisStatusResponse result = service.getStatus(USER_ID, COMPANY_ID, INSPECTION_ID);

        assertThat(result).isEqualTo(oldDone);
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
        return progressAsOf(java.time.Instant.now());
    }

    private AnalysisStatusResponse progressAsOf(java.time.Instant updatedAt) {
        return new AnalysisStatusResponse(
                INSPECTION_ID, "aiDetection", 50, 2, 1, List.of(), 0, 0,
                java.util.Map.of("A", 0, "B", 0, "C", 0, "D", 0, "E", 0), 0, updatedAt);
    }
}
