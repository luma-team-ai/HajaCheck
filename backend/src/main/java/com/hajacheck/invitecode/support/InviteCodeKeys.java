package com.hajacheck.invitecode.support;

/**
 * 초대 코드 Redis 키 레이아웃(#794) — SpringBoot_코드_컨벤션.md §8 콜론 규약.
 * 값 = 발급 회사의 companyId(문자열). 키 자체가 코드 원문이라 별도 해시가 필요 없다
 * (토큰류와 달리 단독으로 계정 탈취 수단이 아니라 "회사 소속 배선" 1회권일 뿐이므로 RedisTokenStore와
 * 성격이 달라 별도 클래스로 분리했다).
 *
 * <p>키 조립 전 대시·공백 제거 + 대문자화로 정규화한다 — 발급 응답(InviteCodeGenerator)은 "XXX-XXX"
 * 표시용 포맷을 쓰지만, 초대 코드 입력 화면(6칸 분리 입력 UI)이 대시 없이 "XXXXXX"만 이어붙여 보낼 수
 * 있어 발급 시점과 redeem 시점의 원문 표기가 달라도 같은 Redis 키로 수렴하게 한다.
 */
public final class InviteCodeKeys {

    private static final String PREFIX = "invite-code:";

    private InviteCodeKeys() {
    }

    public static String key(String code) {
        return PREFIX + canonicalize(code);
    }

    private static String canonicalize(String code) {
        return code.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }
}
