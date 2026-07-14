package com.hajacheck.auth.support;

import java.time.Duration;
import java.util.Optional;

/**
 * 불투명 토큰 발급/조회/소비 추상화 — 가입 상태 토큰(peek, 장기), 비밀번호 재설정 토큰(consume, 단기)에 사용.
 *
 * <p>네임스페이스 분리: 계약이 두 종류의 Redis 키(auth:signup-status:{t}, auth:password-reset:{t})를
 * 명시하므로, 토큰 혼용(가입 토큰을 재설정에 오용)을 막기 위해 namespace 를 파라미터로 받는다.
 * (핸드오프의 issue(value,ttl) 시그니처를 namespace 포함으로 확장 — 토큰 혼동 방지 목적, 보고서에 명시.)
 *
 * <p>test 프로파일은 RedisAutoConfiguration 을 제외하므로 RedisTokenStore(@Profile("!test"))가 뜨지 않고,
 * 테스트는 in-memory fake 로 대체한다(이 인터페이스 추상화의 이유).
 */
public interface TokenStore {

    /** 불투명 랜덤 토큰을 발급하고 value 를 ttl 동안 저장한 뒤 토큰을 반환한다. */
    String issue(String namespace, String value, Duration ttl);

    /** 소비하지 않고 값만 조회한다(가입 상태 조회 등 반복 조회용). */
    Optional<String> peek(String namespace, String token);

    /** 값을 조회하며 즉시 삭제한다(단일 사용 — 비밀번호 재설정 토큰). */
    Optional<String> consume(String namespace, String token);
}
