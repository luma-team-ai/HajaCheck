package com.hajacheck.support;

import com.hajacheck.auth.support.PasswordResetTokenStore;
import com.hajacheck.auth.support.TokenKeys;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 테스트용 in-memory 재설정 토큰 저장소 — RedisPasswordResetTokenStore(@Profile("!test")) 대체.
 *
 * <p>Redis 구현과 같은 의미를 재현한다: 저장 키 = {@code sha256(token)}, 발급 시 이전 토큰 무효화,
 * 소비 시 compare-and-delete. 만료를 Redis 없이 검증하려고 시계를 주입할 수 있다.
 *
 * <p>⚠️ <b>원자성은 재현하지 않는다</b>: Redis 구현의 원자성(Lua 단일 실행)은 동시 인터리브를 막는 성질이라
 * in-memory fake 로는 검증할 수 없다(fake 는 synchronized 로 직렬화될 뿐). 원자성은 <b>설계로만</b> 보장되며
 * 테스트 미검증 항목이다 — 대신 "발급이 단일 Redis 왕복"임을 RedisPasswordResetTokenStoreTest 가 고정한다.
 */
public class InMemoryPasswordResetTokenStore implements PasswordResetTokenStore {

    private record Entry(long userId, Instant expiresAt) {
    }

    /** key = sha256(token) — 실 구현과 동일하게 원문을 저장하지 않는다. */
    private final Map<String, Entry> tokens = new HashMap<>();
    private final Map<Long, String> index = new HashMap<>();
    private final Supplier<Instant> clock;

    public InMemoryPasswordResetTokenStore() {
        this(Instant::now);
    }

    public InMemoryPasswordResetTokenStore(Supplier<Instant> clock) {
        this.clock = clock;
    }

    @Override
    public synchronized String issueAndRotate(long userId, Duration ttl) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String hash = TokenKeys.hash(token);

        String previous = index.get(userId);
        if (previous != null && !previous.equals(hash)) {
            tokens.remove(previous);
        }
        tokens.put(hash, new Entry(userId, clock.get().plus(ttl)));
        index.put(userId, hash);
        return token;
    }

    @Override
    public synchronized Optional<Long> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hash = TokenKeys.hash(token);
        Entry entry = tokens.remove(hash);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.get().isBefore(entry.expiresAt())) {
            // 만료 — Redis 였다면 키가 이미 사라졌을 시점.
            index.remove(entry.userId(), hash);
            return Optional.empty();
        }
        // compare-and-delete: 인덱스가 방금 소비한 토큰을 가리킬 때만 제거.
        index.remove(entry.userId(), hash);
        return Optional.of(entry.userId());
    }

    /** 검증용 — 현재 인덱스가 가리키는 토큰해시. */
    public synchronized String currentTokenHash(long userId) {
        return index.get(userId);
    }
}
