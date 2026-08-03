package com.hajacheck.platformadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.support.AnalysisProgressStore;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.core.media.repository.MediaRepository.InspectionMediaCountProjection;
import com.hajacheck.platformadmin.dto.AnalysisJobQueueItemResponse;
import com.hajacheck.platformadmin.dto.AnalysisJobStatus;
import com.hajacheck.platformadmin.dto.SystemMonitoringResponse;
import com.hajacheck.platformadmin.support.ErrorLogStore;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

/**
 * PR #766 2차 리뷰 지적(P2) 회귀 테스트 — 디스크 사용률이 항상 0%로 표시되던 결함
 * (Actuator/Micrometer 기본 자동구성에는 disk.free/disk.total 미터가 없음)을 File API 기반
 * 계산으로 고정한다.
 *
 * <p>#1408 — 분석 잡 큐 실데이터 매핑(상태/이미지 카운트/소요시간) 케이스를 추가한다.
 */
class PlatformAdminMonitoringServiceTest {

    private final HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
    private final MetricsEndpoint metricsEndpoint = mock(MetricsEndpoint.class);
    private final RestClient restClient = mock(RestClient.class, Answers.RETURNS_DEEP_STUBS);
    private final ErrorLogStore errorLogStore = mock(ErrorLogStore.class);
    private final InspectionRepository inspectionRepository = mock(InspectionRepository.class);
    private final MediaRepository mediaRepository = mock(MediaRepository.class);
    private final AnalysisProgressStore analysisProgressStore = mock(AnalysisProgressStore.class);

    @Test
    void 디스크_사용률은_0보다_큰_값을_반환한다() {
        when(healthEndpoint.health()).thenReturn(Health.up().build());
        when(metricsEndpoint.metric(any(), any())).thenReturn(null);
        when(errorLogStore.recent(anyInt())).thenReturn(List.of());
        when(inspectionRepository.findRecentOrderByCreatedAtDesc(any())).thenReturn(List.of());

        SystemMonitoringResponse response = service().getMonitoring();

        assertThat(response.resourceUsage().diskUsagePercent()).isGreaterThan(0);
    }

    @Test
    void CREATED_UPLOADING_ANALYZING_회차는_진행중으로_집계된다() {
        when(healthEndpoint.health()).thenReturn(Health.up().build());
        when(metricsEndpoint.metric(any(), any())).thenReturn(null);
        when(errorLogStore.recent(anyInt())).thenReturn(List.of());

        Inspection created = inspectionOf(1L, InspectionStatus.CREATED, "힐스테이트 광교 102동");
        Inspection uploading = inspectionOf(2L, InspectionStatus.UPLOADING, "래미안 블레스티지");
        Inspection analyzing = inspectionOf(3L, InspectionStatus.ANALYZING, "자이 더 샵");
        when(inspectionRepository.findRecentOrderByCreatedAtDesc(any()))
                .thenReturn(List.of(created, uploading, analyzing));
        when(mediaRepository.countGroupByInspectionIds(any())).thenReturn(List.of());

        SystemMonitoringResponse response = service().getMonitoring();

        assertThat(response.jobQueue().summary().inProgress()).isEqualTo(3);
        assertThat(response.jobQueue().summary().completed()).isEqualTo(0);
        assertThat(response.jobQueue().jobs())
                .allSatisfy(job -> assertThat(job.status()).isEqualTo(AnalysisJobStatus.IN_PROGRESS));
    }

    @Test
    void ANALYZED_REVIEWED_REPORTED_회차는_완료로_집계된다() {
        when(healthEndpoint.health()).thenReturn(Health.up().build());
        when(metricsEndpoint.metric(any(), any())).thenReturn(null);
        when(errorLogStore.recent(anyInt())).thenReturn(List.of());

        Inspection analyzed = inspectionOf(1L, InspectionStatus.ANALYZED, "힐스테이트 광교 102동");
        Inspection reviewed = inspectionOf(2L, InspectionStatus.REVIEWED, "래미안 블레스티지");
        Inspection reported = inspectionOf(3L, InspectionStatus.REPORTED, "자이 더 샵");
        when(inspectionRepository.findRecentOrderByCreatedAtDesc(any()))
                .thenReturn(List.of(analyzed, reviewed, reported));
        when(mediaRepository.countGroupByInspectionIds(any())).thenReturn(List.of());
        when(analysisProgressStore.find(anyLong())).thenReturn(Optional.empty());

        SystemMonitoringResponse response = service().getMonitoring();

        assertThat(response.jobQueue().summary().completed()).isEqualTo(3);
        assertThat(response.jobQueue().summary().inProgress()).isEqualTo(0);
        assertThat(response.jobQueue().summary().failed()).isEqualTo(0);
        assertThat(response.jobQueue().jobs())
                .allSatisfy(job -> assertThat(job.status()).isEqualTo(AnalysisJobStatus.COMPLETED));
    }

    @Test
    void 완료_회차는_진행률_캐시가_있으면_소요시간을_계산한다() {
        when(healthEndpoint.health()).thenReturn(Health.up().build());
        when(metricsEndpoint.metric(any(), any())).thenReturn(null);
        when(errorLogStore.recent(anyInt())).thenReturn(List.of());

        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 3, 10, 0, 0);
        Inspection analyzed = inspectionOf(1L, InspectionStatus.ANALYZED, "힐스테이트 광교 102동", createdAt);
        when(inspectionRepository.findRecentOrderByCreatedAtDesc(any())).thenReturn(List.of(analyzed));
        when(mediaRepository.countGroupByInspectionIds(any())).thenReturn(List.of());

        Instant updatedAt = createdAt.plusMinutes(2).plusSeconds(45)
                .atZone(java.time.ZoneId.systemDefault()).toInstant();
        AnalysisStatusResponse progress = new AnalysisStatusResponse(
                1L, "done", 100, 10, 10, List.of(), 0, 0, java.util.Map.of(), 0, updatedAt);
        when(analysisProgressStore.find(1L)).thenReturn(Optional.of(progress));

        SystemMonitoringResponse response = service().getMonitoring();

        AnalysisJobQueueItemResponse job = response.jobQueue().jobs().get(0);
        assertThat(job.durationLabel()).isEqualTo("02:45");
    }

    @Test
    void 완료_회차는_진행률_캐시가_TTL만료로_없으면_소요시간이_null이다() {
        when(healthEndpoint.health()).thenReturn(Health.up().build());
        when(metricsEndpoint.metric(any(), any())).thenReturn(null);
        when(errorLogStore.recent(anyInt())).thenReturn(List.of());

        Inspection analyzed = inspectionOf(1L, InspectionStatus.ANALYZED, "힐스테이트 광교 102동");
        when(inspectionRepository.findRecentOrderByCreatedAtDesc(any())).thenReturn(List.of(analyzed));
        when(mediaRepository.countGroupByInspectionIds(any())).thenReturn(List.of());
        when(analysisProgressStore.find(1L)).thenReturn(Optional.empty());

        SystemMonitoringResponse response = service().getMonitoring();

        assertThat(response.jobQueue().jobs().get(0).durationLabel()).isNull();
    }

    @Test
    void 시설물_위치는_facilities_address를_그대로_쓴다() {
        when(healthEndpoint.health()).thenReturn(Health.up().build());
        when(metricsEndpoint.metric(any(), any())).thenReturn(null);
        when(errorLogStore.recent(anyInt())).thenReturn(List.of());

        Inspection analyzed = inspectionOf(1L, InspectionStatus.ANALYZED, "경기도 수원시 영통구 광교로 102");
        when(inspectionRepository.findRecentOrderByCreatedAtDesc(any())).thenReturn(List.of(analyzed));
        when(mediaRepository.countGroupByInspectionIds(any())).thenReturn(List.of());
        when(analysisProgressStore.find(1L)).thenReturn(Optional.empty());

        SystemMonitoringResponse response = service().getMonitoring();

        assertThat(response.jobQueue().jobs().get(0).facilityAddress())
                .isEqualTo("경기도 수원시 영통구 광교로 102");
        assertThat(response.jobQueue().jobs().get(0).id()).isEqualTo("job-1");
    }

    @Test
    void 이미지_장수는_회차별_media_개수를_그대로_반환한다() {
        when(healthEndpoint.health()).thenReturn(Health.up().build());
        when(metricsEndpoint.metric(any(), any())).thenReturn(null);
        when(errorLogStore.recent(anyInt())).thenReturn(List.of());

        Inspection created = inspectionOf(1L, InspectionStatus.CREATED, "힐스테이트 광교 102동");
        when(inspectionRepository.findRecentOrderByCreatedAtDesc(any())).thenReturn(List.of(created));

        InspectionMediaCountProjection projection = mock(InspectionMediaCountProjection.class);
        when(projection.getInspectionId()).thenReturn(1L);
        when(projection.getCnt()).thenReturn(42L);
        when(mediaRepository.countGroupByInspectionIds(any())).thenReturn(List.of(projection));

        SystemMonitoringResponse response = service().getMonitoring();

        assertThat(response.jobQueue().jobs().get(0).imageCount()).isEqualTo(42);
    }

    private PlatformAdminMonitoringService service() {
        return new PlatformAdminMonitoringService(healthEndpoint, metricsEndpoint, restClient, errorLogStore,
                inspectionRepository, mediaRepository, analysisProgressStore);
    }

    private Inspection inspectionOf(Long id, InspectionStatus status, String facilityAddress) {
        return inspectionOf(id, status, facilityAddress, LocalDateTime.now().minusMinutes(1));
    }

    private Inspection inspectionOf(Long id, InspectionStatus status, String facilityAddress, LocalDateTime createdAt) {
        Facility facility = Facility.builder()
                .companyId(1L)
                .name("테스트빌딩")
                .type("BUILDING")
                .address(facilityAddress)
                .build();
        ReflectionTestUtils.setField(facility, "id", 100L + id);

        Inspection inspection = Inspection.builder()
                .facilityId(100L + id)
                .createdBy(1L)
                .assignedInspectorId(1L)
                .roundNo(1)
                .inspectionDate(LocalDate.now())
                .status(status)
                .build();
        ReflectionTestUtils.setField(inspection, "id", id);
        ReflectionTestUtils.setField(inspection, "facility", facility);
        ReflectionTestUtils.setField(inspection, "createdAt", createdAt);
        return inspection;
    }
}
