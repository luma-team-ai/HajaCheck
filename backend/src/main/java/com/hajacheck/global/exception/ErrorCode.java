package com.hajacheck.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * ErrorCode 네이밍: {도메인}_{원인} — SpringBoot_코드_컨벤션.md §5
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

    // 인증(auth)
    // 로그인 실패는 계정 열거 방지를 위해 id/pw/미존재/잠금 구분 없이 이 코드로 통일 응답.
    AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    // 정지 계정 명시 응답용(예약) — 로그인 경로는 위 통일 정책을 따른다.
    AUTH_ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "정지된 계정입니다."),

    // 기업 인증(회원가입·아이디/비밀번호 찾기) — 검증 실패는 절대 401 금지(400/404/409만).
    AUTH_EMAIL_DUPLICATED(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다."),
    AUTH_BUSINESS_NUMBER_DUPLICATED(HttpStatus.CONFLICT, "이미 등록된 사업자등록번호입니다."),
    // 계정 열거 방지: 아이디 찾기 무매칭은 이 코드로 통일.
    AUTH_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "일치하는 계정을 찾을 수 없습니다."),
    // 비밀번호 찾기 관련 — 현재 미사용(엔드포인트 P1 제외). 보안질문 방식 후속에서 재사용 예정(#194 / HAJA-172).
    AUTH_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "입력하신 정보와 일치하는 계정을 찾을 수 없습니다."),
    AUTH_RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 재설정 토큰입니다."),
    AUTH_SIGNUP_TOKEN_INVALID(HttpStatus.NOT_FOUND, "유효하지 않은 가입 상태 토큰입니다."),

    // 파일 업로드(사업자등록증)
    FILE_REQUIRED(HttpStatus.BAD_REQUEST, "파일이 필요합니다."),
    FILE_INVALID_TYPE(HttpStatus.BAD_REQUEST, "허용되지 않는 파일 형식입니다. (JPG, PNG, PDF 만 가능)"),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "파일 용량이 너무 큽니다. (최대 10MB)"),
    FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 업로드에 실패했습니다."),

    // 마이페이지 — 내 플랜·사용량·좌석(HAJA-177)
    PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, "활성 구독을 찾을 수 없습니다."),
    PLAN_FORBIDDEN(HttpStatus.FORBIDDEN, "구독 소유자만 요청할 수 있습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    // user_plans.plan_id 가 가리키는 요금제가 없는 데이터 정합성 오류(FK not-null 이라 정상 운영에선 발생 불가) — 500.
    PLAN_DATA_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "요금제 데이터에 오류가 있습니다."),
    // 시설물(facility)
    // 미존재/타인 소유 모두 이 코드로 통일 응답 — 리소스 존재 여부 열거(cross-owner IDOR) 방지.
    FACILITY_NOT_FOUND(HttpStatus.NOT_FOUND, "시설물을 찾을 수 없습니다."),

    // 도메인별 예시 — 각 담당이 추가
    DEFECT_NOT_FOUND(HttpStatus.NOT_FOUND, "하자를 찾을 수 없습니다."),
    AI_JOB_TIMEOUT(HttpStatus.INTERNAL_SERVER_ERROR, "AI 분석 요청이 시간 초과되었습니다.");

    private final HttpStatus status;
    private final String message;
}
