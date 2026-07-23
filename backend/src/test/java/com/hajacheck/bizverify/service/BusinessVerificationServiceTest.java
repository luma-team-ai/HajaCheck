package com.hajacheck.bizverify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.hajacheck.bizverify.config.BizVerifyProperties;
import com.hajacheck.bizverify.dto.BusinessVerificationRequest;
import com.hajacheck.bizverify.dto.BusinessVerificationResponse;
import com.hajacheck.bizverify.dto.BusinessVerificationResult;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.support.InMemoryRateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * BusinessVerificationService 단위테스트(#648) — 판정→응답 매핑·rate-limit·개인정보 미로깅을 고정한다.
 * NtsBusinessVerifyClient 자체의 status+validate 조합 판정은 NtsBusinessVerifyClientTest 가 담당하므로
 * 여기서는 mock 으로 결과만 주입한다.
 */
@ExtendWith(MockitoExtension.class)
class BusinessVerificationServiceTest {

    private static final String BRN = "123-45-67890";
    private static final String REP = "김민수";
    private static final LocalDate START = LocalDate.of(2020, 1, 1);

    private Instant now;
    private InMemoryRateLimiter rateLimiter;
    private BizVerifyProperties bizVerifyProperties;

    @Mock
    private NtsBusinessVerifyClient ntsBusinessVerifyClient;

    private BusinessVerificationService service;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-07-22T00:00:00Z");
        rateLimiter = new InMemoryRateLimiter(() -> now);
        bizVerifyProperties = new BizVerifyProperties();

        service = new BusinessVerificationService(ntsBusinessVerifyClient, rateLimiter, bizVerifyProperties);
    }

    private static BusinessVerificationRequest request() {
        return new BusinessVerificationRequest(BRN, REP, START);
    }

    // ---------- 판정 → 응답 매핑(5+1종) ----------

    @Test
    void verify_VERIFIED_매핑() {
        when(ntsBusinessVerifyClient.verifyRealtime(eq("1234567890"), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);

        BusinessVerificationResponse response = service.verify(request());

        assertThat(response.result()).isEqualTo(BusinessVerificationResult.VERIFIED);
        assertThat(response.message()).isNotBlank();
    }

    @Test
    void verify_NOT_REGISTERED_매핑() {
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.NOT_REGISTERED);

        assertThat(service.verify(request()).result()).isEqualTo(BusinessVerificationResult.NOT_REGISTERED);
    }

    @Test
    void verify_MISMATCH_매핑() {
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.MISMATCH);

        assertThat(service.verify(request()).result()).isEqualTo(BusinessVerificationResult.MISMATCH);
    }

    @Test
    void verify_SUSPENDED_매핑() {
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.SUSPENDED);

        assertThat(service.verify(request()).result()).isEqualTo(BusinessVerificationResult.SUSPENDED);
    }

    @Test
    void verify_CLOSED_매핑() {
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.CLOSED);

        assertThat(service.verify(request()).result()).isEqualTo(BusinessVerificationResult.CLOSED);
    }

    @Test
    void verify_SKIPPED은_UNAVAILABLE로_번역된다() {
        // 내부 fail-open 용어(SKIPPED)를 그대로 노출하지 않고 외부 대면 용어(UNAVAILABLE)로 번역한다.
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.SKIPPED);

        assertThat(service.verify(request()).result()).isEqualTo(BusinessVerificationResult.UNAVAILABLE);
    }

    // ---------- 사업자등록번호 정규화 ----------

    @Test
    void verify_하이픈포함번호는_정규화되어_클라이언트에_전달된다() {
        // BRN = "123-45-67890" → 정규화 후 "1234567890"(하이픈 제거, 숫자 10자리).
        when(ntsBusinessVerifyClient.verifyRealtime(eq("1234567890"), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);

        service.verify(request());

        verify(ntsBusinessVerifyClient).verifyRealtime(eq("1234567890"), eq(REP), eq(START));
    }

    // ---------- rate-limit(전역, 분당+일일 두 축, IP 축 미사용) ----------

    @Test
    void verify_분당상한_초과시_429_AUTH_TOO_MANY_REQUESTS() {
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);
        int limit = bizVerifyProperties.getRateLimit().getGlobalLimit();
        for (int i = 0; i < limit; i++) {
            service.verify(request());
        }

        assertThatThrownBy(() -> service.verify(request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
    }

    @Test
    void verify_분당상한초과시_국세청클라이언트는_호출되지_않는다() {
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);
        int limit = bizVerifyProperties.getRateLimit().getGlobalLimit();
        for (int i = 0; i < limit; i++) {
            service.verify(request());
        }

        try {
            service.verify(request());
        } catch (BusinessException ignored) {
            // 기대된 429 — 아래 호출 횟수 검증이 핵심.
        }

        verify(ntsBusinessVerifyClient, org.mockito.Mockito.times(limit))
                .verifyRealtime(anyString(), anyString(), any());
    }

    @Test
    void verify_분당창이_지나면_상한이_리셋된다() {
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);
        int limit = bizVerifyProperties.getRateLimit().getGlobalLimit();
        for (int i = 0; i < limit; i++) {
            service.verify(request());
        }

        now = now.plus(bizVerifyProperties.getRateLimit().getGlobalWindow()).plusSeconds(1);

        assertThat(service.verify(request()).result()).isEqualTo(BusinessVerificationResult.VERIFIED);
    }

    @Test
    void verify_일일캡_초과시_429_AUTH_TOO_MANY_REQUESTS() {
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);
        bizVerifyProperties.getRateLimit().setDailyLimit(3);
        for (int i = 0; i < 3; i++) {
            service.verify(request());
        }

        assertThatThrownBy(() -> service.verify(request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
    }

    @Test
    void verify_분당캡은_통과했지만_일일캡에서_막힌다() {
        // 분당 상한만으로는 지속 반복 시 일일 국세청 호출량이 무제한이 되는 케이스 재현(BusinessLicenseOcr
        // 선례와 동일) — 분당 캡(기본 10)은 여유롭게 두고 일일 캡만 낮춰 재현한다.
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);
        bizVerifyProperties.getRateLimit().setDailyLimit(2);
        int perMinuteLimit = bizVerifyProperties.getRateLimit().getGlobalLimit();
        assertThat(perMinuteLimit).as("분당 캡이 일일 캡보다 넉넉해야 이 케이스가 성립한다").isGreaterThan(2);

        service.verify(request());
        service.verify(request());

        assertThatThrownBy(() -> service.verify(request()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        assertThat(rateLimiter.tryAcquire("rate:business-verification:global", perMinuteLimit, Duration.ofMinutes(1)))
                .as("분당 캡은 아직 여유가 있어야 한다")
                .isTrue();
    }

    @Test
    void verify_일일창이_지나면_일일캡이_리셋된다() {
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);
        bizVerifyProperties.getRateLimit().setDailyLimit(2);
        service.verify(request());
        service.verify(request());
        assertThatThrownBy(() -> service.verify(request())).isInstanceOf(BusinessException.class);

        now = now.plus(bizVerifyProperties.getRateLimit().getDailyWindow()).plusSeconds(1);

        assertThat(service.verify(request()).result()).isEqualTo(BusinessVerificationResult.VERIFIED);
    }

    // ---------- 개인정보 미로깅(가능한 범위에서 확인) ----------

    @Test
    void verify_로그에_사업자번호_대표자명_개업일자_평문이_남지_않는다() {
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.MISMATCH);

        Logger logger = (Logger) LoggerFactory.getLogger(BusinessVerificationService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            service.verify(request());
        } finally {
            logger.detachAppender(appender);
        }

        String normalizedBrn = BRN.replace("-", "");
        for (ILoggingEvent event : appender.list) {
            String message = event.getFormattedMessage();
            assertThat(message).doesNotContain(BRN).doesNotContain(normalizedBrn)
                    .doesNotContain(REP).doesNotContain(START.toString());
        }
    }
}
