package com.hajacheck.core.ai.support;

import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 프록시(FastAPI 경유) 호출 스레드풀 보호용 rate-limiter(리뷰 Critical, #582).
 *
 * <p>4개 AI 프록시 경로(nl-search·briefing·defect-explain·report)는 모두 CPU가 무거운 공유
 * FastAPI 다운스트림(RapidOCR/LLM 등)을 호출한다. 가드가 없으면 소수 사용자의 폭주가 공유 AI
 * 서버 스레드풀·업스트림 과금을 고갈시켜 전체 가용성을 떨어뜨린다. 여기서 전역 상한(모든 요청)과
 * 사용자 상한(로그인 사용자 축이 있는 nl-search·briefing)을 고정창 카운터로 적용하고, 초과 시
 * {@link ErrorCode#AUTH_TOO_MANY_REQUESTS}(429)로 즉시 중단시켜 FastAPI 호출 자체를 막는다
 * (BusinessLicenseOcrService의 전역 고정창 가드와 동일한 방어 축).
 *
 * <p>{@link RateLimiter}를 생성자로 주입받는다 — 실 구현(RedisRateLimiter)은 @Profile("!test")라
 * 테스트에선 in-memory fake(InMemoryRateLimiter)로 대체된다. IP 축은 쓰지 않는다(2026-07-17 A
 * 결정 — X-Forwarded-For 위조 가능, RateLimiter javadoc 참고): 전역 축 + 사용자 축만 사용한다.
 *
 * <p>한도는 스레드풀 보호 목적에 맞춘 상수 기본값이다(운영 프로퍼티 신설은 다른 PR과의 겹침을
 * 피하려 배제). 튜닝이 필요하면 아래 상수를 조정한다.
 */
@Slf4j
@Component
public class AiProxyRateLimiter {

    /** 사용자 축 한도: 로그인 사용자 1인당 분당 허용 AI 프록시 호출 수(nl-search·briefing). */
    private static final int USER_LIMIT_PER_MINUTE = 20;
    /** 전역 축 한도: 모든 사용자 합산 분당 허용 AI 프록시 호출 수(공유 FastAPI 스레드풀 총량 방어선). */
    private static final int GLOBAL_LIMIT_PER_MINUTE = 120;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final String GLOBAL_KEY = "rate:ai-proxy:global";
    private static final String USER_KEY_PREFIX = "rate:ai-proxy:user:";

    private final RateLimiter rateLimiter;

    public AiProxyRateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /**
     * 전역 분당 상한. 모든 AI 프록시 경로가 호출한다. 초과 시 429.
     *
     * <p>사용자 축과 함께 쓰는 경로(nl-search·briefing)에서는 {@link #checkUser(Long)}를 먼저 호출한다 —
     * {@link RateLimiter#tryAcquire}는 허용 여부와 무관하게 카운터를 증가시키므로, 특정 사용자에서 이미
     * 막힌 요청이 전역 예산을 갉아먹지 않도록 사용자 축을 먼저 검사한다(BusinessLicenseOcrService의
     * "먼저 막힌 축은 다음 축을 소모하지 않는다" 원칙과 동일).
     */
    public void checkGlobal() {
        if (!rateLimiter.tryAcquire(GLOBAL_KEY, GLOBAL_LIMIT_PER_MINUTE, WINDOW)) {
            // 전역 상한에 닿으면 정상 사용자도 429를 맞으므로(IP 축 미사용) 무증상으로 죽지 않게 경보를 남긴다.
            log.warn("AI 프록시 rate-limit 초과(전역) — limit={}/{}", GLOBAL_LIMIT_PER_MINUTE, WINDOW);
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
    }

    /**
     * 사용자 분당 상한. 로그인 사용자 축이 있는 경로(nl-search·briefing)에서 {@link #checkGlobal()}보다
     * 먼저 호출한다. 초과 시 429.
     */
    public void checkUser(Long userId) {
        if (!rateLimiter.tryAcquire(USER_KEY_PREFIX + userId, USER_LIMIT_PER_MINUTE, WINDOW)) {
            log.warn("AI 프록시 rate-limit 초과(사용자 userId={}) — limit={}/{}",
                    userId, USER_LIMIT_PER_MINUTE, WINDOW);
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
    }
}
