package com.hajacheck.platformadmin.support;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.filter.ThresholdFilter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.hajacheck.platformadmin.dto.ErrorLogItemResponse;
import com.hajacheck.platformadmin.dto.ErrorLogLevel;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
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
 *
 * <p><b>비동기 적재(PR #766 리뷰 지적)</b>: {@code append()} → {@link ErrorLogStore#record}는 Redis
 * 왕복(LPUSH+LTRIM)을 동기로 수행한다. logback {@code AppenderBase#doAppend}는 인스턴스 단위
 * synchronized라, 이 appender를 ROOT 로거에 직접 붙이면 장애로 WARN/ERROR가 폭주하면서 Redis가
 * 느려질 때 애플리케이션 전역의 모든 로깅 스레드가 이 락에 직렬화된다 — 부가 모니터링 기능이 장애
 * 상황에서 오히려 가용성을 악화시키는 셈이다. logback 내장 {@link AsyncAppender}로 감싸 전용 소비
 * 스레드+bounded 큐를 두고, 큐가 가득 차면(neverBlock) 요청 스레드를 막지 않고 드롭한다(부가 기능이라
 * 손실 허용) — 실제 Redis 호출(이 클래스의 append())은 여전히 여기서 하되, 호출 스레드만 분리된다.
 *
 * <p><b>WARN/ERROR 우선 보존(PR #766 2차 리뷰 지적)</b>: {@link AsyncAppender}를 ROOT에 그대로 붙이면
 * 애플리케이션 전역의 INFO 이상 모든 이벤트가 이 큐로 유입돼(실제 레벨 필터는 이 클래스의
 * {@code append()}에서 뒤늦게 적용됨) 대량 INFO 트래픽이 큐 압력을 만들고, neverBlock으로 큐가
 * 포화되는 순간(=장애로 로그가 폭주하는 바로 그 시점) 정작 보여줘야 할 WARN/ERROR까지 드롭될 수 있다.
 * {@link ThresholdFilter}(level=WARN)를 asyncAppender에 걸어 WARN/ERROR만 큐에 진입시키고,
 * {@code setDiscardingThreshold(0)}로 "큐 80% 참에 낮은 레벨부터 자동 버림" 완화 로직도 끈다(어차피
 * 큐에는 WARN/ERROR만 들어오므로 낮은 레벨을 버릴 대상 자체가 없다는 것을 명시).
 *
 * <p><b>개인정보 마스킹(PR #766 리뷰 지적)</b>: 애플리케이션 전역 {@code log.warn}/{@code log.error}
 * 메시지를 그대로 캡처하므로, 예외 메시지에 이메일·전화번호·사업자등록번호 등이 섞여 있으면 마스킹 없이
 * {@code GET /api/platform-admin/monitoring} API로 노출될 수 있다(CLAUDE.md "개인정보는 로그에 평문으로
 * 남기지 않는다" 규칙). {@link #maskSensitiveInfo} 로 저장 직전에 마스킹한다.
 */
@Component
public class RedisErrorLogAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    private static final List<String> EXCLUDED_LOGGER_PREFIXES = List.of(
            "io.lettuce",
            "org.springframework.data.redis",
            RedisErrorLogAppender.class.getPackageName());

    // 장애 시 요청 스레드가 Redis 왕복에 블로킹되지 않도록 소비 스레드를 분리하는 bounded 큐 크기.
    private static final int ASYNC_QUEUE_SIZE = 500;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    // 사업자등록번호(000-00-00000) — 전화번호(01X-XXXX-XXXX)와 자릿수 패턴이 달라 순서 무관하게 매칭.
    private static final Pattern BUSINESS_REG_NO_PATTERN = Pattern.compile("\\d{3}-\\d{2}-\\d{5}");
    private static final Pattern PHONE_PATTERN = Pattern.compile("01[016789]-?\\d{3,4}-?\\d{4}");

    private final ErrorLogStore errorLogStore;

    public RedisErrorLogAppender(ErrorLogStore errorLogStore) {
        this.errorLogStore = errorLogStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void attachToRootLogger() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        setContext(root.getLoggerContext());
        start();

        ThresholdFilter warnAndAboveOnly = new ThresholdFilter();
        warnAndAboveOnly.setLevel(Level.WARN.toString());
        warnAndAboveOnly.start();

        AsyncAppender asyncAppender = new AsyncAppender();
        asyncAppender.setContext(root.getLoggerContext());
        asyncAppender.setName("platformAdminErrorLogAsyncAppender");
        asyncAppender.setQueueSize(ASYNC_QUEUE_SIZE);
        // WARN/ERROR만 큐에 넣는다 — INFO 이하 대량 트래픽이 큐 압력을 만들어 WARN/ERROR를 밀어내는 것을 방지.
        asyncAppender.addFilter(warnAndAboveOnly);
        // 큐에는 이제 WARN/ERROR만 들어오므로 "낮은 레벨부터 자동 버림" 완화 로직은 대상이 없다 — 명시적으로 끈다.
        asyncAppender.setDiscardingThreshold(0);
        // 큐가 가득 차도 요청 스레드를 막지 않고 즉시 드롭한다(neverBlock, logback 1.1.10+) — 부가 기능은
        // 손실을 허용하되 애플리케이션 로깅/요청 처리를 절대 지연시켜서는 안 된다.
        asyncAppender.setNeverBlock(true);
        asyncAppender.addAppender(this);
        asyncAppender.start();
        root.addAppender(asyncAppender);
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
                    maskSensitiveInfo(event.getFormattedMessage()));
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

    private String maskSensitiveInfo(String message) {
        String masked = EMAIL_PATTERN.matcher(message).replaceAll("***@***");
        masked = BUSINESS_REG_NO_PATTERN.matcher(masked).replaceAll("***-**-*****");
        masked = PHONE_PATTERN.matcher(masked).replaceAll("***-****-****");
        return masked;
    }
}
