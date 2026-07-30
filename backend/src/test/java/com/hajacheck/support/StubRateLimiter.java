package com.hajacheck.support;

import com.hajacheck.auth.support.RateLimiter;
import java.time.Duration;

/**
 * 판정 로직만 주입하는 RateLimiter 스텁 — "이 키는 항상 거부" 같은 시나리오 고정용.
 *
 * <p>{@link RateLimiter} 가 {@code tryAcquire} 하나뿐이던 시절엔 테스트가 람다로 바로 넘겼지만,
 * 성공 시 한도 해제({@link RateLimiter#reset})가 추가되며 더 이상 함수형 인터페이스가 아니다(#1315).
 * 호출부가 익명 클래스로 부풀지 않게 이 어댑터로 감싼다.
 *
 * <p>카운터 상태를 갖지 않으므로 {@code reset} 은 no-op 이다. 창·카운터 동작까지 검증해야 하면
 * {@link InMemoryRateLimiter} 를 쓸 것.
 */
public final class StubRateLimiter implements RateLimiter {

    /** {@link RateLimiter#tryAcquire} 와 같은 시그니처 — 기존 테스트 람다를 그대로 옮겨 담기 위한 타입. */
    @FunctionalInterface
    public interface TryAcquire {
        boolean apply(String key, int limit, Duration window);
    }

    private final TryAcquire delegate;

    private StubRateLimiter(TryAcquire delegate) {
        this.delegate = delegate;
    }

    public static StubRateLimiter of(TryAcquire delegate) {
        return new StubRateLimiter(delegate);
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        return delegate.apply(key, limit, window);
    }

    @Override
    public void reset(String key) {
        // 상태 없는 스텁 — 해제할 카운터가 없다.
    }
}
