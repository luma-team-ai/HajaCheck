package com.hajacheck.auth.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 단방향 해시(순수 함수) 검증 — Redis 없이 "저장 키/로그 식별자가 원문이 아님"을 고정한다.
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
    void 서로_다른_입력은_다른_해시를_만든다() {
        assertThat(TokenKeys.hash("token-a")).isNotEqualTo(TokenKeys.hash("token-b"));
    }
}
