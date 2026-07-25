package com.hajacheck.auth.entity;

/**
 * 사용자 계정 상태 — DDL user_status_type (활성/정지/초대 대기).
 * WAITING(#794): 소셜 가입 직후 company_id가 없는 상태. 초대 코드를 redeem해 회사에 배선되면 ACTIVE로 전환된다.
 * 로그인(세션 생성)은 허용되지만 보호된 리소스 접근은 SessionUserRevalidationFilter가 차단한다.
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    WAITING
}
