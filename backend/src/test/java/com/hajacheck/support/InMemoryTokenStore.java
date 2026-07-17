package com.hajacheck.support;

import com.hajacheck.auth.support.TokenKeys;
import com.hajacheck.auth.support.TokenStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 테스트용 in-memory TokenStore — test 프로파일은 RedisAutoConfiguration 을 제외해
 * RedisTokenStore(@Profile("!test"))가 뜨지 않으므로 이 fake 로 대체한다.
 *
 * <p>저장 키는 실 구현과 동일하게 {@link TokenKeys#storageToken} 을 거친다(password-reset=해시, 그 외=원문)
 * — 보조 인덱스가 "토큰해시로 이전 토큰을 지우는" 동작을 fake 에서도 재현하려면 파생이 일치해야 한다.
 *
 * <p>TTL: 만료 토큰 거부를 Redis 없이 검증하려고 시계를 주입할 수 있게 했다. 기본값은 시스템 시계라
 * 기존 가입 상태 토큰 테스트(TTL 30일)에는 영향이 없다(테스트 수명 내 만료 불가).
 */
public class InMemoryTokenStore implements TokenStore {

    private record Entry(String value, Instant expiresAt) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Supplier<Instant> clock;

    public InMemoryTokenStore() {
        this(Instant::now);
    }

    public InMemoryTokenStore(Supplier<Instant> clock) {
        this.clock = clock;
    }

    @Override
    public String issue(String namespace, String value, Duration ttl) {
        String token = UUID.randomUUID().toString().replace("-", "");
        store.put(key(namespace, token), new Entry(value, clock.get().plus(ttl)));
        return token;
    }

    @Override
    public Optional<String> peek(String namespace, String token) {
        if (token == null) {
            return Optional.empty();
        }
        return liveEntry(key(namespace, token)).map(Entry::value);
    }

    @Override
    public Optional<String> consume(String namespace, String token) {
        if (token == null) {
            return Optional.empty();
        }
        String key = key(namespace, token);
        Optional<String> value = liveEntry(key).map(Entry::value);
        store.remove(key);
        return value;
    }

    /**
     * 보조 인덱스 fake 가 이전 토큰을 무효화할 때 사용 — Redis 구현이 Lua 로 {@code DEL auth:{ns}:{해시}}
     * 하는 것과 같은 동작. 인자는 <b>저장 토큰</b>(해시)이지 원문이 아니다.
     */
    void invalidateByStorageToken(String namespace, String storageToken) {
        store.remove(namespace + ":" + storageToken);
    }

    /** TTL 이 지난 항목은 없는 것으로 취급(Redis 만료와 동일 의미) + 지연 삭제. */
    private Optional<Entry> liveEntry(String key) {
        Entry entry = store.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.get().isBefore(entry.expiresAt())) {
            store.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    private String key(String namespace, String token) {
        return namespace + ":" + TokenKeys.storageToken(namespace, token);
    }
}
