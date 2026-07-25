package com.hajacheck.invitecode.support;

import java.time.Duration;
import java.util.Optional;

/**
 * 초대 코드 저장소 추상화(#794) — TokenStore와 동일한 이유로 인터페이스로 분리한다: test 프로파일은
 * RedisAutoConfiguration을 제외해 StringRedisTemplate 빈이 없으므로, RedisInviteCodeStore(@Profile("!test"))는
 * 테스트 컨텍스트에 뜨지 않고 in-memory fake(InMemoryInviteCodeStore, test 소스셋)로 대체된다.
 */
public interface InviteCodeStore {

    /** 코드가 아직 없을 때만(SETNX) companyId 값을 ttl 동안 저장한다. 이미 존재하면 false(발급 측 재시도 유도). */
    boolean issueIfAbsent(String code, String companyId, Duration ttl);

    /** 삭제 없이 값만 조회한다(revoke의 소유 확인용). */
    Optional<String> peek(String code);

    /** 존재하면 삭제한다(소유 확인 후 폐기). 미존재는 무시(멱등). */
    void delete(String code);

    /** 조회와 동시에 삭제한다(redeem — 1회용 소비, 동시 redeem 경합에서 한쪽만 성공). */
    Optional<String> consumeIfPresent(String code);
}
