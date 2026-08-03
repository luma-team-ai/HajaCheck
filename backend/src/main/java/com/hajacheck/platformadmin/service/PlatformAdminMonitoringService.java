package com.hajacheck.platformadmin.service;

import com.hajacheck.core.analysis.dto.AnalysisStatusResponse;
import com.hajacheck.core.analysis.support.AnalysisProgressStore;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.core.media.repository.MediaRepository.InspectionMediaCountProjection;
import com.hajacheck.platformadmin.dto.AnalysisJobQueueItemResponse;
import com.hajacheck.platformadmin.dto.AnalysisJobQueueResponse;
import com.hajacheck.platformadmin.dto.AnalysisJobQueueSummaryResponse;
import com.hajacheck.platformadmin.dto.AnalysisJobStatus;
import com.hajacheck.platformadmin.dto.ErrorLogItemResponse;
import com.hajacheck.platformadmin.dto.ServerHealthItemResponse;
import com.hajacheck.platformadmin.dto.ServerHealthStatus;
import com.hajacheck.platformadmin.dto.ServerResourceUsageResponse;
import com.hajacheck.platformadmin.dto.SystemMonitoringResponse;
import com.hajacheck.platformadmin.support.ErrorLogStore;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 플랫폼 관리자 콘솔 — 시스템 모니터링(#728). companyId 스코프 없이 플랫폼 인프라 상태를 다룬다
 * (PlatformAdminServiceStatsService 와 동일하게 PLATFORM_ADMIN 인가는 SecurityConfig 가 강제).
 *
 * <p>#1408부터 분석 잡 큐가 {@code inspections}/{@code media} 를 조회하지만, 서비스 레벨에
 * {@code @Transactional} 을 붙이지 않는다(코드 리뷰 P1, 2차 회귀) — 붙이면 {@link #getMonitoring()}
 * 하나의 트랜잭션이 {@link #checkAiServerHealth()} 의 외부 HTTP 호출까지 감싸게 되어, AI 서버가
 * 느려지거나 다운되면 그동안 DB 커넥션이 점유된 채 대기한다(관리자가 이 페이지를 반복 새로고침할
 * 가능성이 높은 바로 그 시점에 커넥션 풀 고갈 위험). {@link InspectionRepository#findRecentOrderByCreatedAtDesc}
 * 가 {@code facility} 를 join fetch 로 이미 즉시 로딩하므로, 리포지토리 호출(Spring Data JPA 자체가
 * 메서드 단위로 트랜잭션 경계를 갖는다) 이후에는 지연 로딩 접근이 없어 서비스 레벨 트랜잭션이 없어도
 * 안전하다.
 *
 * <p><b>Actuator 를 HTTP 로 재호출하지 않는다</b> — {@link HealthEndpoint}/{@link MetricsEndpoint} 빈을
 * Java API 로 직접 호출한다. HTTP show-details/show-components 설정은 웹 노출 계층(HealthEndpointWebExtension)
 * 에서만 필터링될 뿐 이 Java API 레벨에는 적용되지 않으므로, "db" 같은 컴포넌트도 항상 그대로 접근 가능하다.
 *
 * <p>⚠️ 이 두 빈은 {@code @ConditionalOnAvailableEndpoint} 라 어느 기술(web/jmx)에도 노출되지 않으면
 * 아예 생성되지 않는다 — application.yml 의 {@code management.endpoints.web.exposure.include: health, metrics}
 * 가 이 서비스의 전제 조건이다. 그 설정을 줄이면(예: metrics 제거) 앱 기동 자체가 실패한다.
 */
@Slf4j
@Service
public class PlatformAdminMonitoringService {

    private static final String AI_SERVER_HEALTH_PATH = "/health";
    private static final int ERROR_LOG_LIMIT = 50;
    private static final String DB_HEALTH_COMPONENT = "db";

    // 분석 잡 큐(#1408) — 전체 이력이 아니라 최근 N건만 반환. 프론트가 recordedAt 기준 "최근 1일"
    // 필터를 자체적으로 하므로, 백엔드는 과도하게 커지지 않을 상한만 둔다.
    private static final int JOB_QUEUE_LIMIT = 200;
    private static final Set<InspectionStatus> IN_PROGRESS_STATUSES =
            EnumSet.of(InspectionStatus.CREATED, InspectionStatus.UPLOADING, InspectionStatus.ANALYZING);
    private static final DateTimeFormatter RECORDED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // 코드 리뷰 P2 — AnalysisProgressStore는 단건 조회만 지원해(벌크 조회 없음) 완료 건마다 개별
    // 왕복이 든다. AnalysisStatusResponse#updatedAt 캐시 TTL(6h, AnalysisJobQueueItemResponse
    // javadoc 참고)을 이미 지난 게 확실한 건은 캐시 미스가 뻔하므로 조회 자체를 건너뛰고 바로
    // null(대시 표시)로 처리해 불필요한 Redis 왕복을 줄인다.
    private static final Duration PROGRESS_CACHE_TTL = Duration.ofHours(6);

    private final HealthEndpoint healthEndpoint;
    private final MetricsEndpoint metricsEndpoint;
    private final RestClient aiServerHealthCheckRestClient;
    private final ErrorLogStore errorLogStore;
    private final InspectionRepository inspectionRepository;
    private final MediaRepository mediaRepository;
    private final AnalysisProgressStore analysisProgressStore;

    public PlatformAdminMonitoringService(
            HealthEndpoint healthEndpoint,
            MetricsEndpoint metricsEndpoint,
            RestClient aiServerHealthCheckRestClient,
            ErrorLogStore errorLogStore,
            InspectionRepository inspectionRepository,
            MediaRepository mediaRepository,
            AnalysisProgressStore analysisProgressStore) {
        this.healthEndpoint = healthEndpoint;
        this.metricsEndpoint = metricsEndpoint;
        this.aiServerHealthCheckRestClient = aiServerHealthCheckRestClient;
        this.errorLogStore = errorLogStore;
        this.inspectionRepository = inspectionRepository;
        this.mediaRepository = mediaRepository;
        this.analysisProgressStore = analysisProgressStore;
    }

    public SystemMonitoringResponse getMonitoring() {
        return new SystemMonitoringResponse(
                getServerHealth(),
                getJobQueue(),
                getResourceUsage(),
                errorLogStore.recent(ERROR_LOG_LIMIT));
    }

    private AnalysisJobQueueResponse getJobQueue() {
        List<Inspection> inspections =
                inspectionRepository.findRecentOrderByCreatedAtDesc(PageRequest.of(0, JOB_QUEUE_LIMIT));
        if (inspections.isEmpty()) {
            return AnalysisJobQueueResponse.empty();
        }

        List<Long> inspectionIds = inspections.stream().map(Inspection::getId).toList();
        Map<Long, Long> imageCountByInspectionId = mediaRepository.countGroupByInspectionIds(inspectionIds).stream()
                .collect(Collectors.toMap(
                        InspectionMediaCountProjection::getInspectionId,
                        InspectionMediaCountProjection::getCnt));

        List<AnalysisJobQueueItemResponse> jobs = inspections.stream()
                .map(inspection -> toJobQueueItem(inspection, imageCountByInspectionId))
                .toList();

        long inProgress = jobs.stream().filter(job -> job.status() == AnalysisJobStatus.IN_PROGRESS).count();
        long completed = jobs.stream().filter(job -> job.status() == AnalysisJobStatus.COMPLETED).count();

        return new AnalysisJobQueueResponse(new AnalysisJobQueueSummaryResponse(inProgress, completed, 0), jobs);
    }

    private AnalysisJobQueueItemResponse toJobQueueItem(Inspection inspection, Map<Long, Long> imageCountByInspectionId) {
        AnalysisJobStatus status = IN_PROGRESS_STATUSES.contains(inspection.getStatus())
                ? AnalysisJobStatus.IN_PROGRESS
                : AnalysisJobStatus.COMPLETED;
        int imageCount = imageCountByInspectionId.getOrDefault(inspection.getId(), 0L).intValue();
        Facility facility = inspection.getFacility();

        return new AnalysisJobQueueItemResponse(
                "job-" + inspection.getId(),
                facility == null ? null : facility.getAddress(),
                imageCount,
                status,
                durationLabel(inspection, status),
                inspection.getCreatedAt().format(RECORDED_AT_FORMATTER));
    }

    // #1408 — IN_PROGRESS는 지금까지 흐른 시간(now - createdAt), COMPLETED는 진행률 캐시(Redis TTL 6h)에
    // 남아있는 마지막 하트비트(updatedAt) - createdAt. 캐시가 만료됐으면 계산할 방법이 없어 null(대시 표시)로
    // 폴백한다 — 컬럼 추가 없이는 원천적으로 복구 불가하다는 점을 사용자 확인 완료(#1408 이슈 코멘트).
    private String durationLabel(Inspection inspection, AnalysisJobStatus status) {
        LocalDateTime createdAt = inspection.getCreatedAt();
        if (status == AnalysisJobStatus.IN_PROGRESS) {
            return formatDuration(Duration.between(createdAt, LocalDateTime.now()));
        }

        if (Duration.between(createdAt, LocalDateTime.now()).compareTo(PROGRESS_CACHE_TTL) > 0) {
            return null;
        }

        Optional<AnalysisStatusResponse> progress = analysisProgressStore.find(inspection.getId());
        if (progress.isEmpty() || progress.get().updatedAt() == null) {
            return null;
        }
        Instant createdAtInstant = createdAt.atZone(ZoneId.systemDefault()).toInstant();
        return formatDuration(Duration.between(createdAtInstant, progress.get().updatedAt()));
    }

    private String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0, duration.getSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private List<ServerHealthItemResponse> getServerHealth() {
        HealthComponent root = healthEndpoint.health();
        ServerHealthStatus apiStatus = mapStatus(root.getStatus());
        ServerHealthStatus dbStatus = mapStatus(componentStatus(root, DB_HEALTH_COMPONENT));
        ServerHealthStatus aiStatus = checkAiServerHealth();

        return List.of(
                new ServerHealthItemResponse("api-server", "API 서버", apiStatus, null),
                new ServerHealthItemResponse("ai-analysis-server", "AI 분석 서버", aiStatus, null),
                new ServerHealthItemResponse("db", "DB", dbStatus, null));
    }

    private Status componentStatus(HealthComponent root, String componentName) {
        if (root instanceof CompositeHealth composite) {
            HealthComponent component = composite.getComponents().get(componentName);
            if (component != null) {
                return component.getStatus();
            }
        }
        // DataSource 헬스 인디케이터가 등록되지 않은 예외적 상황(테스트 프로파일 등) 대비 폴백 — 전체 상태로 대체.
        return root.getStatus();
    }

    private ServerHealthStatus mapStatus(Status status) {
        if (Status.UP.equals(status)) {
            return ServerHealthStatus.HEALTHY;
        }
        if (Status.DOWN.equals(status)) {
            return ServerHealthStatus.DOWN;
        }
        // OUT_OF_SERVICE/UNKNOWN 등 — 완전히 죽지는 않았지만 정상도 아닌 상태로 취급.
        return ServerHealthStatus.DEGRADED;
    }

    private ServerHealthStatus checkAiServerHealth() {
        try {
            aiServerHealthCheckRestClient.get()
                    .uri(AI_SERVER_HEALTH_PATH)
                    .retrieve()
                    .toBodilessEntity();
            return ServerHealthStatus.HEALTHY;
        } catch (ResourceAccessException e) {
            // 커넥션 실패/타임아웃 — ai-server 자체가 응답하지 못하는 상태.
            log.warn("AI 서버 헬스체크 연결 실패", e);
            return ServerHealthStatus.DOWN;
        } catch (RestClientResponseException e) {
            // 서버는 응답했으나 비정상 상태코드 — 완전 다운은 아니라고 본다.
            log.warn("AI 서버 헬스체크 비정상 응답: {}", e.getStatusCode());
            return ServerHealthStatus.DEGRADED;
        } catch (RestClientException e) {
            log.warn("AI 서버 헬스체크 실패", e);
            return ServerHealthStatus.DOWN;
        }
    }

    private ServerResourceUsageResponse getResourceUsage() {
        double cpuUsage = clampPercent(metricValue("system.cpu.usage", List.of()) * 100);
        double memoryUsed = metricValue("jvm.memory.used", List.of("area:heap"));
        double memoryMax = metricValue("jvm.memory.max", List.of("area:heap"));
        double memoryUsage = memoryMax > 0 ? clampPercent(memoryUsed / memoryMax * 100) : 0;
        return new ServerResourceUsageResponse(round1(cpuUsage), round1(memoryUsage), round1(diskUsagePercent()));
    }

    // Actuator/Micrometer 기본 자동구성은 disk.free/disk.total "메트릭"을 등록하지 않는다(디스크는
    // DiskSpaceHealthIndicator로만 노출되는 헬스 컴포넌트라 metricsEndpoint.metric()으로는 항상 조회
    // 실패→0 폴백됨, PR #766 리뷰 지적 — 디스크 게이지가 항상 0%로 표시되던 결함). File API로 앱이
    // 실행 중인 파일시스템의 사용률을 직접 계산한다.
    private double diskUsagePercent() {
        File workingDir = new File(".");
        long total = workingDir.getTotalSpace();
        long usable = workingDir.getUsableSpace();
        if (total <= 0) {
            return 0;
        }
        return clampPercent((double) (total - usable) / total * 100);
    }

    private double metricValue(String name, List<String> tags) {
        try {
            MetricsEndpoint.MetricDescriptor descriptor = metricsEndpoint.metric(name, tags);
            if (descriptor == null || descriptor.getMeasurements().isEmpty()) {
                return 0;
            }
            Double value = descriptor.getMeasurements().get(0).getValue();
            return value == null ? 0 : value;
        } catch (Exception e) {
            // 일부 OS/컨테이너 환경에서 system.cpu.usage 등이 -1(미지원)로 오거나 메트릭 자체가 없을 수 있다
            // (#728) — 화면 전체를 에러로 만들지 않고 0으로 폴백한다.
            log.debug("메트릭 조회 실패: {}", name, e);
            return 0;
        }
    }

    // 음수(미지원 플랫폼의 -1 등)·100 초과를 방어적으로 0~100 범위로 자른다.
    private double clampPercent(double value) {
        return Math.max(0, Math.min(100, value));
    }

    private double round1(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
