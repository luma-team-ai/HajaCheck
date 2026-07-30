package com.hajacheck.core.ai.support;

import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AI 프록시(FastAPI 경유) 호출에 대한 도착률(arrival-rate) 상한(리뷰 Critical, #582).
 *
 * <p>4개 AI 프록시 경로(nl-search·briefing·defect-explain·report)는 모두 CPU가 무거운 공유
 * FastAPI 다운스트림(RapidOCR/LLM 등)을 호출한다. 여기서 전역 축(모든 요청)과 사용자 축(로그인
 * 사용자별)에 고정창 카운터를 걸어 요청 도착률을 낮춘다. 전역 축은 다시 분당·일일 두 창으로 나눈다
 * (OCR #557 선례 = 분+일 이중 캡: 분당은 순간 버스트, 일일은 유료 호출 총량 방어).
 *
 * <p><b>이것은 도착률(arrival-rate) 상한이지 동시성(concurrency) 하드캡이 아니다.</b> 지속 부하를
 * 낮춰 공유 FastAPI 스레드풀·업스트림 과금 고갈 <i>리스크를 완화</i>하지만, 순간 동시 in-flight
 * 요청 수를 절대값으로 막지는 않는다. 진짜 bounded-concurrency(세마포어 기반 in-flight 상한)는
 * 후속 이슈다. 초과 시 {@link ErrorCode#AUTH_TOO_MANY_REQUESTS}(429)로 즉시 중단해 FastAPI
 * 호출 자체를 막는다.
 *
 * <p>{@link RateLimiter}를 생성자로 주입받는다 — 실 구현(RedisRateLimiter)은 @Profile("!test")라
 * 테스트에선 in-memory fake(InMemoryRateLimiter)로 대체된다. IP 축은 쓰지 않는다(2026-07-17 A
 * 결정 — X-Forwarded-For 위조 가능, RateLimiter javadoc 참고): 전역 축 + 사용자 축만 사용한다.
 *
 * <p>한도는 상수 기본값이다(운영 프로퍼티 신설은 다른 PR과의 겹침을 피하려 배제, config화는 후속
 * 이슈). 튜닝이 필요하면 아래 상수를 조정한다.
 */
@Slf4j
@Component
public class AiProxyRateLimiter {

    /** 사용자 축 한도: 로그인 사용자 1인당 분당 허용 AI 프록시 호출 수. */
    private static final int USER_LIMIT_PER_MINUTE = 20;

    /**
     * 전역 분당 한도: 모든 사용자 합산 분당 허용 AI 프록시 호출 수.
     *
     * <p>근거(보수적 도착률 상한): read-timeout이 최대 150s이므로 Little의 법칙으로 지속 상태의
     * 평균 in-flight ≈ 도착률 × 체류시간 = (60/min ÷ 60s = 1 req/s) × 150s ≈ 150. Tomcat 기본
     * max-threads(200) 이내를 목표로 60/min으로 보수 설정한다(이전 120은 in-flight ~300 추정치로
     * 스레드풀 여유를 넘겨 하향, 리뷰 P2-B). 어디까지나 도착률 기준 추정이며 동시성 하드캡은 아니다.
     */
    private static final int GLOBAL_LIMIT_PER_MINUTE = 60;

    /** 전역 일일 한도: HF 유료 호출 상한 방어선(튜닝 가능). 분당 캡만으로는 지속 반복 시 일일 총량이 무제한이 된다. */
    private static final int GLOBAL_LIMIT_PER_DAY = 2000;

    private static final Duration MINUTE_WINDOW = Duration.ofMinutes(1);
    private static final Duration DAY_WINDOW = Duration.ofDays(1);

    private static final String GLOBAL_MINUTE_KEY = "rate:ai-proxy:global";
    private static final String GLOBAL_DAILY_KEY = "rate:ai-proxy:daily";
    private static final String USER_KEY_PREFIX = "rate:ai-proxy:user:";

    private final RateLimiter rateLimiter;

    public AiProxyRateLimiter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /**
     * 전역 도착률 상한(분당 → 일일 순서). 모든 AI 프록시 경로가 호출한다. 초과 시 429.
     *
     * <p>분당 축에서 먼저 막히면 일일 카운터는 소모하지 않는다(BusinessLicenseOcrService의 "먼저
     * 막힌 축은 다음 축을 소모하지 않는다" 원칙과 동일 — {@link RateLimiter#tryAcquire}는 허용
     * 여부와 무관하게 카운터를 증가시키므로 순서가 뒤집히면 막힌 요청이 다른 축 예산을 갉아먹는다).
     *
     * <p>사용자 축과 함께 쓰는 경로에서는 {@link #checkUser(Long)}를 이보다 먼저 호출한다(같은 이유).
     */
    public void checkGlobal() {
        if (!rateLimiter.tryAcquire(GLOBAL_MINUTE_KEY, GLOBAL_LIMIT_PER_MINUTE, MINUTE_WINDOW)) {
            // 전역 상한에 닿으면 정상 사용자도 429를 맞으므로(IP 축 미사용) 무증상으로 죽지 않게 경보를 남긴다.
            log.warn("AI 프록시 rate-limit 초과(전역 분당) — limit={}/{}", GLOBAL_LIMIT_PER_MINUTE, MINUTE_WINDOW);
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
        if (!rateLimiter.tryAcquire(GLOBAL_DAILY_KEY, GLOBAL_LIMIT_PER_DAY, DAY_WINDOW)) {
            // 일일 절대 캡 — 유료 LLM 다운스트림 총량 방어선(분당 캡만으로는 일일 총량이 무제한).
            log.warn("AI 프록시 rate-limit 초과(전역 일일) — limit={}/{}", GLOBAL_LIMIT_PER_DAY, DAY_WINDOW);
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
    }

    /**
     * 사용자 분당 상한. 로그인 사용자 축이 있는 모든 프록시 경로(nl-search·briefing·defect-explain·
     * report)에서 {@link #checkGlobal()}보다 먼저 호출한다. 초과 시 429.
     */
    public void checkUser(Long userId) {
        if (!rateLimiter.tryAcquire(USER_KEY_PREFIX + userId, USER_LIMIT_PER_MINUTE, MINUTE_WINDOW)) {
            log.warn("AI 프록시 rate-limit 초과(사용자 userId={}) — limit={}/{}",
                    userId, USER_LIMIT_PER_MINUTE, MINUTE_WINDOW);
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
    }
}
