package com.hajacheck.platformadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hajacheck.platformadmin.dto.SystemMonitoringResponse;
import com.hajacheck.platformadmin.support.ErrorLogStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.metrics.MetricsEndpoint;
import org.springframework.web.client.RestClient;

/**
 * PR #766 2차 리뷰 지적(P2) 회귀 테스트 — 디스크 사용률이 항상 0%로 표시되던 결함
 * (Actuator/Micrometer 기본 자동구성에는 disk.free/disk.total 미터가 없음)을 File API 기반
 * 계산으로 고정한다.
 */
class PlatformAdminMonitoringServiceTest {

    @Test
    void 디스크_사용률은_0보다_큰_값을_반환한다() {
        HealthEndpoint healthEndpoint = mock(HealthEndpoint.class);
        when(healthEndpoint.health()).thenReturn(Health.up().build());

        MetricsEndpoint metricsEndpoint = mock(MetricsEndpoint.class);
        when(metricsEndpoint.metric(any(), any())).thenReturn(null);

        RestClient restClient = mock(RestClient.class, Answers.RETURNS_DEEP_STUBS);

        ErrorLogStore errorLogStore = mock(ErrorLogStore.class);
        when(errorLogStore.recent(anyInt())).thenReturn(List.of());

        PlatformAdminMonitoringService service =
                new PlatformAdminMonitoringService(healthEndpoint, metricsEndpoint, restClient, errorLogStore);

        SystemMonitoringResponse response = service.getMonitoring();

        assertThat(response.resourceUsage().diskUsagePercent()).isGreaterThan(0);
    }
}
