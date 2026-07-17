package com.hajacheck.support;

import com.hajacheck.auth.support.PasswordResetTokenIndex;
import com.hajacheck.auth.support.TokenNamespaces;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트용 in-memory 보조 인덱스 — RedisPasswordResetTokenIndex(@Profile("!test")) 대체.
 *
 * <p>Redis 구현이 Lua 로 이전 토큰 키를 지우듯, 여기서는 {@link InMemoryTokenStore} 의 저장 토큰을 지운다.
 *
 * <p>⚠️ <b>원자성은 재현하지 않는다</b>: Redis 구현의 원자성(Lua 단일 실행)은 동시 인터리브를 막는 성질이라
 * in-memory fake 로는 검증 불가하다(fake 는 synchronized 로 직렬화될 뿐). 원자성은 <b>설계로만</b> 보장되며
 * 테스트 미검증 항목이다. 여기서 검증하는 건 "이전 토큰 무효화"·"compare-and-delete" 의 <b>의미</b>다.
 */
public class InMemoryPasswordResetTokenIndex implements PasswordResetTokenIndex {

    private final Map<Long, String> index = new ConcurrentHashMap<>();
    private final InMemoryTokenStore tokenStore;

    public InMemoryPasswordResetTokenIndex(InMemoryTokenStore tokenStore) {
        this.tokenStore = tokenStore;
    }

    @Override
    public synchronized void rotate(long userId, String newTokenHash, Duration ttl) {
        String previous = index.get(userId);
        if (previous != null && !previous.equals(newTokenHash)) {
            tokenStore.invalidateByStorageToken(TokenNamespaces.PASSWORD_RESET, previous);
        }
        index.put(userId, newTokenHash);
    }

    @Override
    public synchronized void clearIfMatches(long userId, String tokenHash) {
        // compare-and-delete — 구토큰 소비가 현재 유효 토큰의 인덱스를 날리면 안 된다.
        index.remove(userId, tokenHash);
    }

    /** 검증용 — 현재 인덱스가 가리키는 토큰해시. */
    public synchronized String currentTokenHash(long userId) {
        return index.get(userId);
    }
}
