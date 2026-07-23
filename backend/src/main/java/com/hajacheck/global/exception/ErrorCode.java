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
    // 매핑되지 않은 경로/정적 리소스(NoResourceFoundException) 전용 — 도메인 리소스 미존재는
    // 각 도메인의 {도메인}_NOT_FOUND 를 쓴다. 내부 경로 유추를 막기 위해 메시지에 경로를 담지 않는다.
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청하신 리소스를 찾을 수 없습니다."),
    // 경로는 존재하나 그 경로가 지원하지 않는 HTTP 메서드로 호출된 경우(HttpRequestMethodNotSupportedException) 전용.
    // RESOURCE_NOT_FOUND와 같은 이유로 분리 — 전용 핸들러가 없으면 아래 포괄 핸들러가 500으로 처리한다.
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),
    // 상태 전이 엔티티(@Version 낙관적 락 — HAJA-25)의 동시 갱신 충돌 통일 응답.
    CONCURRENT_UPDATE_CONFLICT(HttpStatus.CONFLICT, "다른 요청과 충돌하여 처리하지 못했습니다. 다시 시도해 주세요."),
    // Entity 도메인 메서드의 명시적 상태 전이 가드(DomainStateTransitionException) 통일 응답.
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "현재 상태에서는 처리할 수 없는 요청입니다."),

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
    // ⚠️ 보안질문 방식 폐기(#194 이메일 링크 전환)로 참조 0건 — 제거 대상(후속 정리). 이번 PR 범위 아님.
    AUTH_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "입력하신 정보와 일치하는 계정을 찾을 수 없습니다."),
    // 비밀번호 재설정 2단계 — 토큰 무효/만료/사용됨을 구분하지 않는 통일 메시지(어느 쪽인지 노출 시 열거 단서가 된다).
    AUTH_RESET_TOKEN_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 재설정 토큰입니다."),
    // 비밀번호 재설정 1단계 rate-limit(이메일 축·전역 상한) 초과 — 계정 존재 여부와 무관하게 동일 조건으로 건다.
    AUTH_TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."),
    AUTH_SIGNUP_TOKEN_INVALID(HttpStatus.NOT_FOUND, "유효하지 않은 가입 상태 토큰입니다."),
    // 점검 담당자 배정(dev-05-02) — 미존재/정지/역할 불충족 모두 통일 응답(리소스 열거 방지).
    AUTH_INVALID_INSPECTOR(HttpStatus.BAD_REQUEST, "담당자로 배정할 수 없는 사용자입니다."),
    AUTH_APPROVED_MEMBERSHIP_CONFLICT(HttpStatus.CONFLICT, "이미 승인된 회사 멤버십이 존재합니다."),
    // 국세청 진위확인(#596) — 사업자등록번호+대표자명+개업일자가 국세청 등록정보와 불일치하거나
    // 휴업/폐업/미등록으로 가입을 차단하는 경우. 진위 "불일치"는 입력 오류이므로 400(401 금지 정책 준수).
    AUTH_BUSINESS_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "사업자등록정보 진위확인에 실패했습니다. 사업자등록번호·대표자명·개업일자를 확인해 주세요."),

    // 파일 업로드(사업자등록증)
    FILE_REQUIRED(HttpStatus.BAD_REQUEST, "파일이 필요합니다."),
    FILE_INVALID_TYPE(HttpStatus.BAD_REQUEST, "허용되지 않는 파일 형식입니다. (JPG, PNG, PDF 만 가능)"),
    FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "파일 용량이 너무 큽니다. (최대 10MB)"),
    FILE_UPLOAD_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "파일 업로드에 실패했습니다."),
    // DB 행은 존재하나 디스크의 실제 파일이 유실/미생성된 경우(리뷰 P2) — IO 실패(500)가 아니라
    // 리소스 없음(404)으로 구분. FileStorageService.read()가 NoSuchFileException을 이 코드로 매핑한다.
    FILE_NOT_FOUND(HttpStatus.NOT_FOUND, "파일을 찾을 수 없습니다."),

    // 마이페이지 — 내 플랜·사용량·좌석(HAJA-177)
    PLAN_NOT_FOUND(HttpStatus.NOT_FOUND, "활성 구독을 찾을 수 없습니다."),
    PLAN_FORBIDDEN(HttpStatus.FORBIDDEN, "구독 소유자만 요청할 수 있습니다."),
    PLAN_ACTIVE_SUBSCRIPTION_CONFLICT(HttpStatus.CONFLICT, "이미 활성 구독이 존재합니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    // user_plans.plan_id 가 가리키는 요금제가 없는 데이터 정합성 오류(FK not-null 이라 정상 운영에선 발생 불가) — 500.
    PLAN_DATA_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "요금제 데이터에 오류가 있습니다."),
    // 플랫폼 관리자 "플랜 정책 설정"(#624 후속) — FREE/STANDARD/ENTERPRISE 각각 정확히 한 번씩 없는 요청(누락·중복·미지원 플랜명).
    PLAN_POLICY_INVALID(HttpStatus.BAD_REQUEST, "요금제 정책 요청이 올바르지 않습니다. FREE·STANDARD·ENTERPRISE 각각 정확히 한 번씩 포함해야 합니다."),
    // 시설물(facility)
    // 미존재/타인 소유 모두 이 코드로 통일 응답 — 리소스 존재 여부 열거(cross-owner IDOR) 방지.
    FACILITY_NOT_FOUND(HttpStatus.NOT_FOUND, "시설물을 찾을 수 없습니다."),

    // 점검 회차(inspection) — dev-05-02
    INSPECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "점검 회차를 찾을 수 없습니다."),
    // PESSIMISTIC_WRITE 행 잠금으로 직렬화하지만, 방어적으로 unique(facility_id, round_no) 위반을 그대로 500 노출하지 않고 통일 응답.
    INSPECTION_ROUND_CONFLICT(HttpStatus.CONFLICT, "다른 요청과 충돌하여 점검 회차를 생성하지 못했습니다. 다시 시도해 주세요."),
    // 점검일 도메인 검증 — 시설물 등록일 이전이거나 지나치게 먼 미래는 비정상 입력으로 간주.
    INSPECTION_DATE_INVALID(HttpStatus.BAD_REQUEST, "점검일이 올바르지 않습니다."),

    // 촬영 데이터(미디어) 업로드(dev-05-03)
    MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "미디어를 찾을 수 없습니다."),
    MEDIA_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "한 번에 업로드할 수 있는 파일 수를 초과했습니다."),

    // 상담(counsel)
    COUNSEL_SESSION_ASSIGNMENT_CONFLICT(HttpStatus.CONFLICT, "이미 상담 세션이 배정된 티켓입니다."),

    // 알림(notification) — FR-9, HAJA-274. 미존재/타인 소유 모두 통일 응답(cross-user IDOR 방지).
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),

    // 관리자 콘솔 — 사용자 관리(#405, 리뷰 P2)
    // 자기 자신을 강등/정지하거나, 회사에 남은 마지막 ADMIN을 강등/정지하려는 시도를 통일 응답으로 차단.
    ADMIN_PROTECTED_ACCOUNT(HttpStatus.CONFLICT, "자기 자신 또는 회사의 마지막 관리자는 변경할 수 없습니다."),
    // 프론트 역할 선택지(USER/INSPECTOR/ADMIN) 밖의 Role(예: COUNSELOR)을 서버가 그대로 수락하지 않도록 화이트리스트 강제.
    ADMIN_ROLE_NOT_ASSIGNABLE(HttpStatus.BAD_REQUEST, "부여할 수 없는 역할입니다."),

    // 플랫폼 관리자 콘솔 — 사용자 관리(#576). 사용자 등록 시 지정한 companyId가 존재하지 않는 경우.
    COMPANY_NOT_FOUND(HttpStatus.NOT_FOUND, "기업을 찾을 수 없습니다."),

    // 도메인별 예시 — 각 담당이 추가
    DEFECT_NOT_FOUND(HttpStatus.NOT_FOUND, "하자를 찾을 수 없습니다."),
    AI_JOB_TIMEOUT(HttpStatus.INTERNAL_SERVER_ERROR, "AI 분석 요청이 시간 초과되었습니다."),

    // AI 분석 실행/상태(dev-05-04)
    ANALYSIS_NO_MEDIA(HttpStatus.BAD_REQUEST, "분석할 이미지가 없습니다."),
    ANALYSIS_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 분석이 진행 중입니다."),
    ANALYSIS_QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "분석 요청이 많아 잠시 후 다시 시도해 주세요."),
    // 코드 리뷰 P1 — 검수 완료(REVIEWED)·보고서화(REPORTED) 회차는 재분석을 허용하지 않는다(제품
    // 결정). 재분석은 소프트삭제로 기존(사람이 검수한) 하자를 지우므로, 최종 상태 회차에서 허용하면
    // 무보상 데이터 유실 표면이 된다.
    ANALYSIS_NOT_ALLOWED(HttpStatus.CONFLICT, "검수 완료 또는 보고서화된 회차는 재분석할 수 없습니다."),

    // AI 서버(FastAPI) 인증 프록시(#228) — 연결/타임아웃/응답형식 3종
    AI_SERVER_UNREACHABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 서버에 연결할 수 없습니다."),
    AI_SERVER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "AI 서버 응답이 지연되고 있습니다."),
    AI_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "AI 서버 응답을 처리할 수 없습니다."),
    // AI 서버 응답 4xx/5xx 구분(#334 P3) — 4xx 는 요청 자체가 거부된 것으로 보아 400, 5xx 는 업스트림 장애로 502.
    AI_REQUEST_REJECTED(HttpStatus.BAD_REQUEST, "AI 서버가 요청을 거부했습니다."),
    AI_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "AI 서버에서 오류가 발생했습니다."),
    // 하자 자연어 검색(HAJA-120) 공개 게이트웨이 — 점검자 역할은 통과했으나 has_ai_addon=true인
    // 활성 플랜(개인 또는 APPROVED+VERIFIED 회사와 유효한 승인 멤버십)을 만족하지 못한 요청.
    AI_ADDON_REQUIRED(HttpStatus.FORBIDDEN, "AI 기능을 사용할 수 없는 요금제입니다."),

    // 국세청 사업자 진위확인 외부 호출(#596) — fail-open 정책이라 최종 사용자 응답으로는 던지지 않고
    // 구조화 로깅(경보)용으로만 사용한다. 이 코드가 발생하면 진위확인을 스킵하고 가입은 그대로 진행한다
    // (verification_status=PENDING). HttpStatus 는 AI_* 와 동일 계열로 부여(실제 응답엔 미노출).
    NTS_SERVER_UNREACHABLE(HttpStatus.SERVICE_UNAVAILABLE, "국세청 진위확인 서버에 연결할 수 없습니다."),
    NTS_SERVER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "국세청 진위확인 서버 응답이 지연되고 있습니다."),
    NTS_INVALID_RESPONSE(HttpStatus.BAD_GATEWAY, "국세청 진위확인 서버 응답을 처리할 수 없습니다."),
    // 국세청이 HTTP 4xx/5xx로 응답(서버에 도달했으나 거부 — 예: 인증키 만료/오류). "서버 다운"과 구분한다.
    NTS_REQUEST_REJECTED(HttpStatus.BAD_GATEWAY, "국세청 진위확인 서버가 요청을 거부했습니다."),

    // 보고서(report) — #446 / HAJA-283
    // 미존재/타인 소유(점검 소유권 불일치) 모두 이 코드로 통일 응답 — 리소스 존재 여부 열거(cross-owner IDOR) 방지.
    REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "보고서를 찾을 수 없습니다."),
    // AI 서버 연결/타임아웃/형식 오류는 AiProxyService가 이미 BusinessException으로 던지므로 이 코드로
    // 매핑될 일이 없다 — envelope.success()=false(AI 서버가 응답은 했으나 보고서 생성 자체를 거부한 경우)만 해당.
    REPORT_GENERATION_FAILED(HttpStatus.BAD_GATEWAY, "보고서 생성에 실패했습니다."),
    // 같은 inspectionId에 대한 동시 초안 생성이 같은 nextVersion을 계산해 저장을 시도하면 uk_reports_inspection_version
    // 유니크 제약이 두 번째 저장을 막는다 — 이 경합은 재시도로 해소 가능하므로 500이 아닌 409로 표면화한다(#455 P2-1).
    REPORT_VERSION_CONFLICT(HttpStatus.CONFLICT, "이미 동일 버전의 보고서가 생성 중입니다. 잠시 후 다시 시도해 주세요."),
    // finalize 요청의 pdfUrl이 이 보고서용 업로드 엔드포인트 형식(/api/reports/{id}/pdf/{storageKey})을
    // 따르지 않으면 거부 — 임의 문자열/타 보고서 pdfUrl로 확정을 시도하는 것을 차단한다(#455 P2-2).
    REPORT_PDF_URL_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 보고서 PDF 경로입니다."),

    // RAG 문서 관리(#22 / HAJA-35) — 플랫폼 관리자 콘솔 법규·지침 PDF 업로드 + 임베딩 파이프라인
    RAG_DOCUMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "RAG 문서를 찾을 수 없습니다."),
    // PDF가 손상되었거나(스캔 이미지만 있는 등) 텍스트 레이어가 없어 PDFBox가 본문을 추출하지 못한 경우.
    RAG_TEXT_EXTRACTION_FAILED(HttpStatus.BAD_REQUEST, "PDF에서 텍스트를 추출할 수 없습니다.");

    private final HttpStatus status;
    private final String message;
}
