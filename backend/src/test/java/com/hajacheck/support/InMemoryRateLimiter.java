package com.hajacheck.support;

import com.hajacheck.auth.support.RateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 테스트용 in-memory RateLimiter — test 프로파일은 RedisAutoConfiguration 을 제외해
 * RedisRateLimiter(@Profile("!test"))가 뜨지 않으므로 이 fake 로 대체한다(InMemoryTokenStore 선례).
 *
 * <p>Redis Lua 구현과 동일한 고정 창 의미를 재현한다: 첫 요청에서 창이 열리고, 창이 지나면 리셋.
 * 시계를 주입할 수 있어 창 만료도 대기 없이 검증 가능하다.
 */
public class InMemoryRateLimiter implements RateLimiter {

    private static final class Window {
        private int count;
        private Instant expiresAt;
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Supplier<Instant> clock;

    public InMemoryRateLimiter() {
        this(Instant::now);
    }

    public InMemoryRateLimiter(Supplier<Instant> clock) {
        this.clock = clock;
    }

    @Override
    public synchronized boolean tryAcquire(String key, int limit, Duration window) {
        Instant now = clock.get();
        Window current = windows.get(key);
        if (current == null || !now.isBefore(current.expiresAt)) {
            current = new Window();
            current.expiresAt = now.plus(window);
            windows.put(key, current);
        }
        current.count++;
        return current.count <= limit;
    }

    /**
     * 특정 키의 카운터만 비운다(RedisRateLimiter 의 DEL 과 동일 의미) — 다음 tryAcquire 가 새 창을 연다.
     * 비밀번호 변경 성공 시 한도 해제(#1315)가 이 경로를 쓴다.
     */
    @Override
    public synchronized void reset(String key) {
        windows.remove(key);
    }

    /** 테스트 간 상태 격리용 — 전체 비우기(인터페이스에 없는 fake 전용 메서드). */
    public void reset() {
        windows.clear();
    }
}
