package com.hajacheck.auth.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 토큰 저장 키 파생(순수 함수) 검증 — Redis 없이 "저장 키가 토큰 원문이 아님"을 고정한다.
 * (테스트 환경은 RedisTokenStore 가 뜨지 않으므로 파생 로직을 순수 함수로 분리해 여기서 검증한다.)
 */
class TokenKeysTest {

    @Test
    void sha256_해시는_결정적이고_원문과_다르다() {
        String token = "some-opaque-token";

        assertThat(TokenKeys.hash(token))
                .isEqualTo(TokenKeys.hash(token))
                .isNotEqualTo(token)
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void sha256_알려진_벡터와_일치한다() {
        // NIST 표준 벡터 — 해시 구현이 조용히 바뀌면(예: 인코딩 변경) 기존 in-flight 토큰이 전부 깨지므로 고정.
        assertThat(TokenKeys.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void 서로_다른_토큰은_다른_해시를_만든다() {
        assertThat(TokenKeys.hash("token-a")).isNotEqualTo(TokenKeys.hash("token-b"));
    }

    @Test
    void password_reset_네임스페이스는_해시_저장_키를_쓴다() {
        String token = "raw-reset-token";

        String storage = TokenKeys.storageToken(TokenNamespaces.PASSWORD_RESET, token);

        assertThat(TokenKeys.isHashedNamespace(TokenNamespaces.PASSWORD_RESET)).isTrue();
        // 최초 P1(토큰 원문 노출) 재발 방지의 심층방어 — Redis 덤프에 원문이 남지 않아야 한다.
        assertThat(storage).isNotEqualTo(token).isEqualTo(TokenKeys.hash(token));
    }

    @Test
    void signup_status_네임스페이스는_원문_저장_키를_유지한다() {
        // 회귀 방지: TTL 30일 in-flight 가입 토큰이 있어 해시로 바꾸면 기존 승인대기 사용자의 상태조회가 깨진다.
        String token = "raw-signup-token";

        assertThat(TokenKeys.isHashedNamespace(TokenNamespaces.SIGNUP_STATUS)).isFalse();
        assertThat(TokenKeys.storageToken(TokenNamespaces.SIGNUP_STATUS, token)).isEqualTo(token);
    }
}
