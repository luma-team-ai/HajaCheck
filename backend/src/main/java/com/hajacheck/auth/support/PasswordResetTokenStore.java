package com.hajacheck.auth.support;

import java.time.Duration;
import java.util.Optional;

/**
 * 비밀번호 재설정 토큰 전용 저장소 — 발급·소비를 각각 <b>단일 원자 연산</b>으로 제공한다(#194).
 *
 * <p><b>왜 범용 {@link TokenStore} 를 쓰지 않는가</b>: 재설정 토큰의 발급은 ①새 토큰 저장 ②이전 토큰해시
 * 조회·삭제 ③인덱스 갱신의 다단계 연산이고, 이 셋이 <b>한 덩어리로 원자적</b>이어야 한다. TokenStore.issue 로
 * ①을 먼저 하고 인덱스 갱신을 뒤이어 호출하면 그 사이가 열려, 동시 요청 시 <b>나중에 발송된 메일의 링크가
 * 죽는다</b>(먼저 발급된 토큰이 살아남음 — 사용자는 최신 메일을 눌렀는데 "유효하지 않은 토큰"). 발송 버튼
 * 더블클릭으로 도달 가능한 실 버그다. 그래서 ①②③을 하나의 Lua 로 합쳤고, 그러려면 토큰 저장이 이 컴포넌트
 * 안에 있어야 한다. {@link TokenStore} 인터페이스는 <b>변경하지 않았다</b>(가입 상태 토큰이 그대로 사용).
 *
 * <p>인터페이스로 분리한 이유: Redis 구현체는 test 프로파일에서 뜨지 않으므로(StringRedisTemplate 빈 부재)
 * 테스트는 in-memory fake 로 대체한다(InMemoryTokenStore 선례).
 */
public interface PasswordResetTokenStore {

    /**
     * 토큰을 발급하면서 <b>같은 사용자의 이전 토큰을 무효화</b>한다(동시 다발 링크 방지). 원자적.
     *
     * <p>저장 키는 {@code sha256(token)} 이며(원문 미저장), 반환값만 원문 토큰이다 — 원문은 메일로만 나간다.
     * 인덱스 TTL 은 토큰 TTL 과 동일하게 걸린다(안 걸면 인덱스만 영구 잔존해 키가 샌다).
     *
     * @return 원문 토큰(메일 링크에 실을 값)
     */
    String issueAndRotate(long userId, Duration ttl);

    /**
     * 토큰을 소비한다(1회용) — 조회·삭제와 인덱스 정리(compare-and-delete)가 한 번에 원자적으로 일어난다.
     *
     * <p>인덱스 정리는 인덱스가 <b>방금 소비한 토큰</b>을 가리킬 때만 한다. 무조건 지우면 아직 만료 안 된
     * 구토큰 소비가 현재 유효 토큰의 인덱스를 날려 다음 발급의 무효화가 실패한다.
     *
     * @return 토큰 소유자 userId. 토큰이 무효/만료/이미 사용됨이면 empty(세 경우를 구분하지 않는다).
     */
    Optional<Long> consume(String token);
}
