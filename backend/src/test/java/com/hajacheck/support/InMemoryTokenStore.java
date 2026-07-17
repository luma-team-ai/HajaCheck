package com.hajacheck.support;

import com.hajacheck.auth.support.TokenStore;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트용 in-memory TokenStore — test 프로파일은 RedisAutoConfiguration 을 제외해
 * RedisTokenStore(@Profile("!test"))가 뜨지 않으므로 이 fake 로 대체한다. TTL 은 검증 대상이 아니라 무시.
 *
 * <p>비밀번호 재설정 토큰은 {@link InMemoryPasswordResetTokenStore} 가 담당한다(만료·무효화 검증이 필요한 쪽).
 */
public class InMemoryTokenStore implements TokenStore {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public String issue(String namespace, String value, Duration ttl) {
        String token = UUID.randomUUID().toString().replace("-", "");
        store.put(key(namespace, token), value);
        return token;
    }

    @Override
    public Optional<String> peek(String namespace, String token) {
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(key(namespace, token)));
    }

    @Override
    public Optional<String> consume(String namespace, String token) {
        if (token == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.remove(key(namespace, token)));
    }

    private String key(String namespace, String token) {
        return namespace + ":" + token;
    }
}
