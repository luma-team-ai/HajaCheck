package com.hajacheck.support;

import com.hajacheck.invitecode.support.InviteCodeKeys;
import com.hajacheck.invitecode.support.InviteCodeStore;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트용 in-memory InviteCodeStore — test 프로파일은 RedisAutoConfiguration을 제외해
 * RedisInviteCodeStore(@Profile("!test"))가 뜨지 않으므로 이 fake로 대체한다. TTL은 검증 대상이 아니라 무시.
 * InviteCodeKeys.canonicalize로 정규화해 실제 구현과 동일하게 대시·대소문자를 무시한다.
 */
public class InMemoryInviteCodeStore implements InviteCodeStore {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public boolean issueIfAbsent(String code, String companyId, Duration ttl) {
        return store.putIfAbsent(InviteCodeKeys.canonicalize(code), companyId) == null;
    }

    @Override
    public Optional<String> peek(String code) {
        return Optional.ofNullable(store.get(InviteCodeKeys.canonicalize(code)));
    }

    @Override
    public void delete(String code) {
        store.remove(InviteCodeKeys.canonicalize(code));
    }

    @Override
    public Optional<String> consumeIfPresent(String code) {
        return Optional.ofNullable(store.remove(InviteCodeKeys.canonicalize(code)));
    }
}
