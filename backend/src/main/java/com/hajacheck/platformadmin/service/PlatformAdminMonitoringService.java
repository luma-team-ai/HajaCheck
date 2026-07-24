package com.hajacheck.platformadmin.service;

import com.hajacheck.platformadmin.dto.AnalysisJobQueueResponse;
import com.hajacheck.platformadmin.dto.ErrorLogItemResponse;
import com.hajacheck.platformadmin.dto.ServerHealthItemResponse;
import com.hajacheck.platformadmin.dto.ServerHealthStatus;
import com.hajacheck.platformadmin.dto.ServerResourceUsageResponse;
import com.hajacheck.platformadmin.dto.SystemMonitoringResponse;
import com.hajacheck.platformadmin.support.ErrorLogStore;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 플랫폼 관리자 콘솔 — 시스템 모니터링(#728). companyId 스코프 없이 플랫폼 인프라 상태를 다룬다
 * (PlatformAdminServiceStatsService 와 동일하게 PLATFORM_ADMIN 인가는 SecurityConfig 가 강제).
 *
 * <p>DB 접근이 없어(Actuator/Redis/RestClient 만 사용) {@code @Transactional} 을 붙이지 않는다
 * (AiProxyService 와 동일 이유).
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

    private final HealthEndpoint healthEndpoint;
    private final MetricsEndpoint metricsEndpoint;
    private final RestClient aiServerHealthCheckRestClient;
    private final ErrorLogStore errorLogStore;

    public PlatformAdminMonitoringService(
            HealthEndpoint healthEndpoint,
            MetricsEndpoint metricsEndpoint,
            RestClient aiServerHealthCheckRestClient,
            ErrorLogStore errorLogStore) {
        this.healthEndpoint = healthEndpoint;
        this.metricsEndpoint = metricsEndpoint;
        this.aiServerHealthCheckRestClient = aiServerHealthCheckRestClient;
        this.errorLogStore = errorLogStore;
    }

    public SystemMonitoringResponse getMonitoring() {
        return new SystemMonitoringResponse(
                getServerHealth(),
                // 분석 잡 큐(#728 범위 제외) — AnalysisJobQueueResponse#empty() javadoc 참고.
                AnalysisJobQueueResponse.empty(),
                getResourceUsage(),
                errorLogStore.recent(ERROR_LOG_LIMIT));
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
        double diskFree = metricValue("disk.free", List.of());
        double diskTotal = metricValue("disk.total", List.of());
        double diskUsage = diskTotal > 0 ? clampPercent((diskTotal - diskFree) / diskTotal * 100) : 0;
        return new ServerResourceUsageResponse(round1(cpuUsage), round1(memoryUsage), round1(diskUsage));
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
