package com.hajacheck.auth.support;

import java.time.Duration;

/**
 * 사용자별 "현재 유효한 비밀번호 재설정 토큰" 보조 인덱스.
 *
 * <p><b>왜 필요한가</b>: {@link TokenStore} 의 Redis 레이아웃은 {@code key=토큰 → value=userId} 단방향이라
 * userId 로 그 사용자의 토큰을 역추적할 수 없다. 재발급 시 이전 링크를 무효화하려면(동시 다발 링크 방지)
 * 역방향 인덱스가 있어야 한다. {@code KEYS}/{@code SCAN} 순회는 운영 Redis 를 블로킹하므로 금지.
 *
 * <p>인터페이스로 분리한 이유: Redis 구현체는 test 프로파일에서 뜨지 않으므로(StringRedisTemplate 빈 부재)
 * 테스트는 in-memory fake 로 대체한다({@link TokenStore}/InMemoryTokenStore 선례와 동일).
 */
public interface PasswordResetTokenIndex {

    /**
     * 인덱스를 새 토큰해시로 교체하고, 인덱스가 가리키던 <b>이전 토큰을 무효화</b>한다.
     *
     * <p>⚠️ 구현은 <b>원자적</b>이어야 한다. ①이전 토큰해시 조회 ②이전 토큰 키 삭제 ③인덱스 갱신이
     * 인터리브되면 이전 토큰이 살아남거나(무효화 실패) 방금 발급한 토큰이 지워진다(정상 링크 사망).
     *
     * @param ttl 인덱스 TTL — <b>토큰 TTL 과 동일해야 한다</b>. 안 걸면 토큰만 만료되고 인덱스가 영구 잔존해 키가 샌다.
     */
    void rotate(long userId, String newTokenHash, Duration ttl);

    /**
     * 인덱스가 가리키는 값이 {@code tokenHash} 와 같을 때만 인덱스를 지운다(compare-and-delete).
     *
     * <p>⚠️ 무조건 삭제하면, 아직 만료 안 된 구토큰을 소비할 때 <b>현재 유효한 토큰의 인덱스</b>가 날아가
     * 다음 발급의 이전 토큰 무효화가 실패한다.
     */
    void clearIfMatches(long userId, String tokenHash);
}
