package com.hajacheck.platformadmin.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.hajacheck.platformadmin.dto.ErrorLogItemResponse;
import com.hajacheck.platformadmin.dto.ErrorLogLevel;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 플랫폼 관리자 시스템 모니터링(#728) "최근 에러 로그" — 애플리케이션 전역에서 이미 발생하는
 * {@code log.warn}/{@code log.error} 호출(예: AiProxyService 의 AI 서버 연결 실패, GlobalExceptionHandler
 * 의 미처리 예외)을 그대로 캡처해 {@link ErrorLogStore}에 적재한다. 별도 계측 없이 이슈가 요구한
 * "API 5xx·분석 실패 최근 N건"을 충족한다.
 *
 * <p>logback-spring.xml 대신 {@link ApplicationReadyEvent} 시점에 ROOT 로거에 프로그래밍 방식으로
 * attach 한다 — 컨텍스트가 완전히 뜬 뒤라 {@link ErrorLogStore} 빈(StringRedisTemplate 의존)을 그대로
 * 주입받아 쓸 수 있다(레포 기존 Redis 컴포넌트와 동일하게 Spring 빈으로 관리).
 *
 * <p><b>재귀 방지</b>: Redis/Lettuce 클라이언트 자체가 SLF4J 로 ERROR/WARN 을 남기면(예: 연결 장애),
 * 그 로그를 다시 이 appender 가 잡아 {@link ErrorLogStore#record}(Redis 구현이면 다시 Redis 호출)를
 * 유발해 무한루프/폭주로 번질 수 있다 — 해당 패키지 접두사는 캡처 대상에서 제외한다.
 * append() 전체도 try/catch 로 감싸 저장 실패가 애플리케이션 로깅 자체를 깨지 않게 한다(fail-silent —
 * 이 부가 기능이 핵심 경로를 방해해서는 안 된다).
 */
@Component
public class RedisErrorLogAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    private static final List<String> EXCLUDED_LOGGER_PREFIXES = List.of(
            "io.lettuce",
            "org.springframework.data.redis",
            RedisErrorLogAppender.class.getPackageName());

    private final ErrorLogStore errorLogStore;

    public RedisErrorLogAppender(ErrorLogStore errorLogStore) {
        this.errorLogStore = errorLogStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void attachToRootLogger() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        setContext(root.getLoggerContext());
        start();
        root.addAppender(this);
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event.getLevel() != Level.ERROR && event.getLevel() != Level.WARN) {
            return;
        }
        String loggerName = event.getLoggerName();
        if (EXCLUDED_LOGGER_PREFIXES.stream().anyMatch(loggerName::startsWith)) {
            return;
        }
        try {
            ErrorLogItemResponse item = new ErrorLogItemResponse(
                    UUID.randomUUID().toString(),
                    TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(event.getTimeStamp())),
                    event.getLevel() == Level.ERROR ? ErrorLogLevel.ERROR : ErrorLogLevel.WARN,
                    simpleServiceName(loggerName),
                    event.getFormattedMessage());
            errorLogStore.record(item);
        } catch (Exception e) {
            // 여기서 log.* 를 호출하면 이 appender 로 재귀할 수 있어 System.err 로만 남긴다(fail-silent).
            System.err.println("RedisErrorLogAppender: 에러 로그 적재 실패 - " + e.getMessage());
        }
    }

    private String simpleServiceName(String loggerName) {
        int lastDot = loggerName.lastIndexOf('.');
        return lastDot == -1 ? loggerName : loggerName.substring(lastDot + 1);
    }
}
