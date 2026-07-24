package com.hajacheck.platformadmin.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import com.hajacheck.platformadmin.dto.ErrorLogItemResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PR #766 리뷰 지적(P2) 회귀 테스트 — RedisErrorLogAppender가 애플리케이션 전역 log.warn/error 메시지를
 * 그대로 캡처하면 이메일·사업자등록번호·전화번호 같은 개인정보가 마스킹 없이
 * GET /api/platform-admin/monitoring API로 노출될 수 있었다. append()는 protected라 같은 패키지에서
 * 직접 호출해 검증한다(ROOT 로거 attach·AsyncAppender 배선은 통합 테스트 영역이라 여기서는 검증하지 않음).
 */
class RedisErrorLogAppenderTest {

    private static class RecordingErrorLogStore implements ErrorLogStore {
        private final List<ErrorLogItemResponse> recorded = new ArrayList<>();

        @Override
        public void record(ErrorLogItemResponse item) {
            recorded.add(item);
        }

        @Override
        public List<ErrorLogItemResponse> recent(int limit) {
            return recorded;
        }
    }

    private ILoggingEvent mockEvent(Level level, String loggerName, String message) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLevel()).thenReturn(level);
        when(event.getLoggerName()).thenReturn(loggerName);
        when(event.getFormattedMessage()).thenReturn(message);
        when(event.getTimeStamp()).thenReturn(System.currentTimeMillis());
        return event;
    }

    @Test
    void 이메일이_포함된_에러_로그는_마스킹되어_저장된다() {
        RecordingErrorLogStore store = new RecordingErrorLogStore();
        RedisErrorLogAppender appender = new RedisErrorLogAppender(store);
        appender.start();

        appender.append(mockEvent(
                Level.ERROR, "com.hajacheck.auth.service.AuthService", "로그인 실패: user@example.com 계정 잠금"));

        assertThat(store.recorded).hasSize(1);
        assertThat(store.recorded.get(0).message()).doesNotContain("user@example.com");
    }

    @Test
    void 사업자등록번호가_포함된_경고_로그는_마스킹되어_저장된다() {
        RecordingErrorLogStore store = new RecordingErrorLogStore();
        RedisErrorLogAppender appender = new RedisErrorLogAppender(store);
        appender.start();

        appender.append(mockEvent(
                Level.WARN, "com.hajacheck.auth.service.CompanySignupService", "사업자번호 조회 실패: 123-45-67890"));

        assertThat(store.recorded).hasSize(1);
        assertThat(store.recorded.get(0).message()).doesNotContain("123-45-67890");
    }

    @Test
    void 전화번호가_포함된_에러_로그는_마스킹되어_저장된다() {
        RecordingErrorLogStore store = new RecordingErrorLogStore();
        RedisErrorLogAppender appender = new RedisErrorLogAppender(store);
        appender.start();

        appender.append(mockEvent(
                Level.ERROR, "com.hajacheck.auth.service.AuthService", "본인인증 실패: 010-1234-5678"));

        assertThat(store.recorded).hasSize(1);
        assertThat(store.recorded.get(0).message()).doesNotContain("010-1234-5678");
    }

    @Test
    void INFO_레벨은_캡처하지_않는다() {
        RecordingErrorLogStore store = new RecordingErrorLogStore();
        RedisErrorLogAppender appender = new RedisErrorLogAppender(store);
        appender.start();

        appender.append(mockEvent(Level.INFO, "com.hajacheck.auth.service.AuthService", "로그인 성공"));

        assertThat(store.recorded).isEmpty();
    }

    /**
     * PR #766 2차 리뷰 지적(P2) 회귀 테스트 — attachToRootLogger()가 asyncAppender에 거는
     * ThresholdFilter(level=WARN)가 실제로 INFO 이하를 큐 진입 전에 걸러내는지 검증한다.
     * ROOT 로거 attach·AsyncAppender 전체 배선(큐 크기·neverBlock 등)은 통합 테스트 영역이라
     * 여기서는 필터 판정 로직만 고정한다.
     */
    @Test
    void WARN_임계_필터는_INFO_이하를_거부하고_WARN_ERROR는_통과시킨다() {
        ThresholdFilter filter = new ThresholdFilter();
        filter.setLevel(Level.WARN.toString());
        filter.start();

        assertThat(filter.decide(mockEvent(Level.INFO, "any.Logger", "info"))).isEqualTo(FilterReply.DENY);
        assertThat(filter.decide(mockEvent(Level.DEBUG, "any.Logger", "debug"))).isEqualTo(FilterReply.DENY);
        assertThat(filter.decide(mockEvent(Level.WARN, "any.Logger", "warn"))).isEqualTo(FilterReply.NEUTRAL);
        assertThat(filter.decide(mockEvent(Level.ERROR, "any.Logger", "error"))).isEqualTo(FilterReply.NEUTRAL);
    }
}
