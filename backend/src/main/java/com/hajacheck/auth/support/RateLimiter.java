package com.hajacheck.auth.support;

import java.time.Duration;

/**
 * 고정 창(fixed window) 카운터 rate-limit 추상화.
 *
 * <p>인터페이스로 분리한 이유: Redis 구현체는 test 프로파일에서 뜨지 않으므로(StringRedisTemplate 빈 부재)
 * 테스트는 in-memory fake 로 대체한다({@link TokenStore}/InMemoryTokenStore 선례와 동일). 이렇게 해야
 * 컨텍스트가 깨지지 않으면서 429 동작을 테스트로 고정할 수 있다.
 *
 * <p>⚠️ IP 축은 쓰지 않는다(2026-07-17 A 결정). nginx 가 {@code X-Forwarded-For} 에 클라 제공값을 덧붙이고
 * 스프링이 첫 항목을 클라 IP 로 채택해 헤더 위조로 무력화되며, 실제 엣지가 레포 밖 host nginx 라
 * 레포만 고쳐선 완결되지 않는다. 이메일 축 + 전역 상한만 사용한다.
 */
public interface RateLimiter {

    /**
     * {@code window} 동안 {@code limit} 회까지 허용한다. 호출 시 카운터를 1 증가시킨다(허용 여부와 무관).
     *
     * @return 한도 내면 true, 초과면 false
     */
    boolean tryAcquire(String key, int limit, Duration window);

    /**
     * 카운터를 즉시 비운다(창 자체를 없앤다) — 다음 {@link #tryAcquire} 가 새 창을 연다.
     *
     * <p><b>용도: "시도 한도"가 목적을 다한 시점의 해제.</b> 비밀번호 변경이 성공하는 순간 호출자가
     * 현재 비밀번호를 안다는 사실이 증명되므로, 실패 시도를 세던 카운터를 계속 들고 있을 이유가 없다
     * (#1315). 오히려 들고 있으면 정상 사용자가 자기 계정의 다음 변경을 스스로 막는다.
     *
     * <p>⚠️ <b>"자격증명 확인 성공" 같은 명확한 근거 없이 호출하면 안 된다</b> — 인증 이전 경로에서
     * 호출되면 공격자가 카운터를 지워가며 무제한 시도할 수 있어 rate-limit 이 통째로 무력화된다.
     *
     * <p>멱등이다: 키가 없어도 아무 일도 일어나지 않는다.
     */
    void reset(String key);
}
