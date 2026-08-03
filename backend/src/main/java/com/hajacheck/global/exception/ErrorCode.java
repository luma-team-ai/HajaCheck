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

    // 로그인 후 비밀번호 변경(#1315) — 현재 비밀번호 불일치는 위 AUTH_INVALID_CREDENTIALS(401)를 재사용한다.
    // 아래 둘은 "인증은 됐으나 이 계정/입력으로는 변경이 성립하지 않는" 경우라 400이다(401 금지 정책 준수 —
    // 프론트 axios 가 401 을 로그인 강제 리다이렉트로 처리하므로 폼째로 튕긴다).
    // 소셜 전용 계정(password_hash=null) — 바꿀 "현재 비밀번호"가 애초에 없다. 비밀번호를 심어주면
    // CustomUserDetailsService 가 금지한 비밀번호 로그인을 이 경로가 말없이 열어주게 된다.
    AUTH_PASSWORD_NOT_SET(HttpStatus.BAD_REQUEST, "비밀번호가 설정되지 않은 계정입니다. 소셜 로그인을 이용해 주세요."),
    // 새 비밀번호가 현재 비밀번호와 같음 — 변경이 아무 효과도 없으므로 성공으로 위장하지 않고 명시적으로 거부한다.
    AUTH_PASSWORD_UNCHANGED(HttpStatus.BAD_REQUEST, "새 비밀번호가 현재 비밀번호와 같습니다."),

    // 초대 코드(#794) — 소셜 가입(WAITING) 계정이 기업 관리자가 발급한 코드를 redeem해 회사 소속으로 전환.
    // WAITING 계정이 초대 코드 redeem 외의 보호된 리소스를 요청했을 때 SessionUserRevalidationFilter가 통일 응답.
    AUTH_ACCOUNT_WAITING(HttpStatus.FORBIDDEN, "초대 코드 입력 후 이용할 수 있습니다."),
    // Redis에 없음(미발급/오입력) · 만료 · 이미 사용됨을 구분하지 않는 통일 응답(코드 열거 방지).
    AUTH_INVITE_CODE_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않거나 만료된 초대 코드입니다."),

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

    // 플랜 한도 강제(#843 / HAJA-441) — usage_counters 원자적 조건부 UPDATE 가 0행을 반환(=한도 초과)했을 때.
    // HTTP 상태는 403(FORBIDDEN)으로 통일한다: "요금제가 허용하지 않는 요청"이라는 엔타이틀먼트 판정이라
    // 같은 성격의 기존 게이트(COUNSEL_PLAN_REQUIRED·AI_ADDON_REQUIRED)와 계약을 맞춘다. 409(CONFLICT)는
    // "다시 시도하면 풀릴 수 있는 상태 충돌", 429(TOO_MANY_REQUESTS)는 "시간이 지나면 풀리는 rate-limit"
    // (AUTH_TOO_MANY_REQUESTS)을 뜻하는데, 플랜 한도는 업그레이드나 자원 정리 없이는 재시도해도 풀리지
    // 않으므로 둘 다 프론트에 잘못된 재시도 힌트를 준다.
    PLAN_FACILITY_QUOTA_EXCEEDED(HttpStatus.FORBIDDEN, "요금제의 시설물 등록 한도를 초과했습니다. 요금제를 업그레이드해 주세요."),
    PLAN_ANALYSIS_QUOTA_EXCEEDED(HttpStatus.FORBIDDEN, "요금제의 월 분석 한도를 초과했습니다. 요금제를 업그레이드해 주세요."),
    PLAN_SEAT_QUOTA_EXCEEDED(HttpStatus.FORBIDDEN, "요금제의 좌석 한도를 초과했습니다. 요금제를 업그레이드해 주세요."),

    // 플랜 하향 시 한도 초과분 확인 요구(#890) — 위 3종과 달리 409(CONFLICT)가 맞다. "요금제가 허용하지
    // 않는 요청"이 아니라 "지금 상태와 충돌하니 확인하고 다시 보내라"는 뜻이고, 실제로 호출자가 확인
    // 플래그를 실어 재시도하면 풀린다. 구체적인 정지 대상은 이 에러가 아니라 변경 미리보기 조회로 받는다
    // (에러 응답 계약이 {timestamp, code, message} 라 페이로드를 실을 수 없다).
    PLAN_DOWNGRADE_CONFIRMATION_REQUIRED(HttpStatus.CONFLICT,
            "요금제를 내리면 한도를 초과하는 구성원이 정지되고 일부 시설물이 읽기 전용이 됩니다. 변경 내용을 확인한 뒤 다시 요청해 주세요."),

    // 플랜 하향 시 유지할 구성원 직접 선택(#890 Phase 2, keepUserIds) — 선택한 id 중 이 회사의 ACTIVE
    // 구성원이 아닌 게 있을 때. PLAN_FORBIDDEN("구독 소유자만 요청할 수 있습니다")을 재사용하지 않는다 —
    // 이 상황의 호출자는 이미 정당한 owner(회사 소유자 인가는 통과)라, 그 메시지를 보여주면 "내가 owner가
    // 아니라는 건가?" 하는 무관한 혼선을 준다(보안 재검토 P3-4). 어떤 id가 문제인지는 여전히 밝히지
    // 않는다(존재 여부를 흘리지 않는 기존 관례 유지) — HttpStatus만 PLAN_FORBIDDEN과 동일(403).
    PLAN_KEEP_USER_INVALID(HttpStatus.FORBIDDEN,
            "선택한 구성원 중 일부가 유효하지 않습니다. 회사 소속 활성 구성원만 유지 대상으로 선택할 수 있습니다."),

    // 관리자 콘솔 플랜 변경(PATCH /api/admin/plan)의 상향 거절(#988). 실결제 도입으로 이 화면은
    // 하향·FREE 전환 전용이 됐다 — 결제 없이 상향을 발급하면 결제 흐름 전체가 우회되고, 그 우회로를 가진
    // 사람이 곧 결제 대상자(기업 owner = 가입 시 ADMIN 자동 부여, #636)라 실질적으로 아무도 결제하지 않게 된다.
    //
    // 403(FORBIDDEN)인 이유: PLAN_*_QUOTA_EXCEEDED 3종과 같은 "이 경로가 허용하지 않는 요청"이다.
    // 409(CONFLICT)는 "지금 상태와 충돌하니 확인하고 다시 보내라"(PLAN_DOWNGRADE_CONFIRMATION_REQUIRED)라
    // 같은 요청을 재시도하면 풀린다는 잘못된 힌트를 준다 — 이 거절은 몇 번을 재시도해도 풀리지 않고,
    // 해소 수단은 오직 결제 경로로 이동하는 것뿐이므로 메시지에 그 경로를 명시한다.
    PLAN_UPGRADE_REQUIRES_PAYMENT(HttpStatus.FORBIDDEN,
            "상위 요금제로의 변경은 결제가 필요합니다. 요금제 결제 화면에서 진행해 주세요."),

    // 위 코드의 <b>대칭</b>(#988 리뷰 P2) — 결제 경로(POST /api/me/plan/orders)에서 상향이 아닌 변경을
    // 거절할 때. 관리자 콘솔이 "현재가 >= 대상가"만 허용하므로, 결제 경로는 "현재가 < 대상가"만 허용해야
    // 두 경로가 빈틈도 겹침도 없이 정확히 상보적이 된다.
    //
    // 이 가드가 없으면 <b>관리자 콘솔에서 무료인 전환이 결제 경로에서는 한 달치 청구</b>가 된다 —
    // 특히 초과가 없는 하향(ENTERPRISE→STANDARD)이나 개인 구독 하향(companyId=null 이라 하향 초과
    // 확인이 즉시 통과)은 아무 가드에도 걸리지 않고 주문·청구까지 갔다.
    //
    // 403 인 이유는 PLAN_UPGRADE_REQUIRES_PAYMENT 와 같다: 재시도로 풀리지 않고 해소 수단이 "다른 경로로
    // 이동"뿐이다. 메시지에 그 경로(요금제 변경 화면)를 명시한다.
    PLAN_CHANGE_NOT_PAYABLE(HttpStatus.FORBIDDEN,
            "결제가 필요한 변경이 아닙니다. 하위 요금제로의 변경은 요금제 변경 화면에서 진행해 주세요."),

    // 플랜 하향 예약(#1105 / HAJA-526) — 하향을 즉시가 아니라 다음 결제 주기에 적용한다.
    //
    // 409: 이미 대기 중(PENDING) 예약이 있는데 또 예약하려 할 때. 부분 UQ(uq_scheduled_plan_changes_pending)
    // 가 DB 레벨에서도 막는다. "지금 상태와 충돌하니 확인하고 다시 보내라"라 409 가 맞다 — 기존 예약을
    // 취소하면 같은 요청이 그대로 성공하기 때문이다(PLAN_DOWNGRADE_CONFIRMATION_REQUIRED 와 같은 계열).
    PLAN_SCHEDULED_CHANGE_EXISTS(HttpStatus.CONFLICT,
            "이미 예약된 요금제 변경이 있습니다. 기존 예약을 취소한 뒤 다시 요청해 주세요."),

    // 404: 취소할 대기 예약이 없을 때. 조회 후 판정이 아니라 조건부 UPDATE 의 갱신 행 수가 0일 때 던진다
    // (동시에 두 번 취소해도 한쪽만 성공하고 다른 쪽은 이 코드를 받는다).
    PLAN_SCHEDULED_CHANGE_NOT_FOUND(HttpStatus.NOT_FOUND, "예약된 요금제 변경이 없습니다."),

    // 403: 예약 대상이 하향이 아닐 때(상향·동일 가격). 상향은 결제 경로 전용이고 즉시 적용이라 예약
    // 개념 자체가 없으며(PLAN_UPGRADE_REQUIRES_PAYMENT 와 같은 이유), 동일 가격 전환도 "기간을 기다릴
    // 이유"가 없다. 403 인 근거는 PLAN_UPGRADE_REQUIRES_PAYMENT 와 같다 — 재시도로 풀리지 않는다.
    PLAN_SCHEDULE_NOT_DOWNGRADE(HttpStatus.FORBIDDEN,
            "다음 결제 주기 적용 예약은 하위 요금제로의 변경에만 사용할 수 있습니다."),

    // 409: 현재 구독에 다음 결제일(current_period_end, #1104)이 없어 예약 실행 시점을 정할 수 없을 때.
    // FREE 등 무기한 구독이 여기 해당한다(FREE 는 애초에 더 내려갈 곳이 없어 위 코드에서 먼저 걸리지만,
    // 유료인데 주기가 비어 있는 데이터 이상도 이 코드로 표면화한다 — 500 으로 감추면 신청자가 원인을
    // 알 수 없다). 즉시 변경 경로(PATCH /api/admin/plan)로 우회할 수 있어 409 가 맞다.
    PLAN_SCHEDULE_PERIOD_END_MISSING(HttpStatus.CONFLICT,
            "현재 구독에 다음 결제일이 없어 예약할 수 없습니다. 즉시 변경을 이용해 주세요."),

    // 403: 예약 대상이 유료 요금제일 때(#1105 보안 리뷰 P1).
    //
    // ⚠️ #1177(유료→유료 하향 C안 "유예 후 강등")로 <b>더 이상 발생하지 않는다</b>. 유료 대상은 이제
    // 허용되며, 적용 시점에 "미결제 유예"(한도는 FREE, 유예 안에 결제하지 않으면 FREE 강등)로 들어가
    // "청구되지 않는 유료 한 달"이라는 원래의 우회로가 성립하지 않는다(PaymentGraceService javadoc).
    // 상수를 지우지 않고 남기는 이유: 프론트가 이 코드로 분기하는 안내 UI 를 갖고 있어(planQuota.types.ts·
    // PlanDowngradeConfirmModal.tsx) 서버·클라이언트 배포 순서에 따라 참조가 남을 수 있다. 프론트 정리
    // 후 제거 대상이다.
    PLAN_SCHEDULE_PAID_TARGET_UNSUPPORTED(HttpStatus.FORBIDDEN,
            "다음 결제 주기 적용 예약은 무료 요금제로의 변경만 지원합니다. 유료 요금제로의 변경은 요금제 변경 화면에서 즉시 진행해 주세요."),

    // 409: 현재 구독이 이미 미결제 유예 중일 때 또 하향을 예약하려는 경우(#1177). 유예 중 구독은 결제가
    // 끝나지 않은 상태라, 거기서 또 예약하면 "유예가 유예를 낳는" 사슬이 생긴다(STANDARD 유예 → 만료일에
    // BASIC 유예 → …). 어느 단계에서도 청구가 없고 한도는 계속 FREE 라 이득은 없지만, 청구되지 않는
    // 유료 이름의 구독이 무한히 이어지는 상태 자체를 만들지 않는다.
    //
    // 409 인 이유: 상태 충돌이라 <b>해소하면 같은 요청이 성공</b>한다 — 결제하거나(정상 주기 시작) 즉시
    // FREE 로 변경하면 된다(PLAN_SCHEDULED_CHANGE_EXISTS 와 같은 계열).
    PLAN_SCHEDULE_PAYMENT_PENDING(HttpStatus.CONFLICT,
            "결제가 완료되지 않은 요금제라 예약을 추가할 수 없습니다. 결제를 완료하거나 무료 요금제로 즉시 변경해 주세요."),

    // 409: 실행 시점 정지 인원이 신청 시점에 확인받은 인원 수(confirmed_seat_overflow)를 초과할 때
    // (리뷰 P2-4). confirmOverflow 는 "그 시점 미리보기"에 대한 동의라, 예약 보관 기간(최대 한 달) 사이에
    // 구성원이 늘면 동의 범위를 벗어난 대량 정지가 된다. 그때는 적용하지 않고 예약을 FAILED 로 종료해
    // 상위 요금제를 유지한다 — PLAN_SEAT_QUOTA_EXCEEDED 에 채택한 fail-safe(아무도 잘못 정지되지 않는
    // 쪽)와 같은 논리다. 사용자 요청 응답이 아니라 배치 실패 사유(failure_reason·알림)로 쓰이는 코드다.
    PLAN_SCHEDULE_CONFIRMED_OVERFLOW_EXCEEDED(HttpStatus.CONFLICT,
            "예약 신청 시 확인한 정지 인원보다 실제 정지 대상이 많아 예약을 적용하지 않았습니다. 변경 내용을 다시 확인한 뒤 예약해 주세요."),

    // 토스페이먼츠 샌드박스 결제(#988 / HAJA-489) — 주문 사전 등록 → 승인 → 플랜 전이 3단계.
    //
    // 404: orderId 로 결제 주문을 찾지 못했을 때. <b>미존재·타인 소유·재확정 불가(FAILED·CANCELED·만료)를
    // 전부 이 코드로 통일</b>한다 — 상태를 세분하거나 타인 소유를 403 으로 구분하면 남의 orderId 를 넣어보며
    // "실재하는 주문"을 열거할 수 있다. 미존재/타인 소유를 한 코드로 합치는 기존 관례
    // (FACILITY_NOT_FOUND·REPORT_NOT_FOUND·COUNSEL_TICKET_NOT_FOUND)를 그대로 따른다.
    PAYMENT_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 주문을 찾을 수 없습니다."),
    // 403: 승인이 끝난 뒤 플랜 반영 단계에서 인가가 더 이상 성립하지 않을 때만 쓰는 <b>내부 경계</b>다
    // (주문 생성 이후 소속·소유자가 바뀐 경우). 이 시점의 호출자는 이미 주문 소유자임이 확인된 상태라
    // 존재 여부 노출 문제가 없다 — 승인 전 단계의 타인 접근은 위 404 로 통일한다.
    PAYMENT_FORBIDDEN(HttpStatus.FORBIDDEN, "결제 주문에 접근할 수 없습니다."),
    // 400: 승인 요청 금액이 서버가 사전 등록한 주문 금액과 다를 때. 금액은 서버(plans.price_monthly)가
    // 정하므로 이 불일치는 클라이언트 위변조이거나 계약 불일치다 — 400(요청이 잘못됨)이 맞고, 409(재시도하면
    // 풀림)나 402가 아니다. 이 시점에서 PG 승인 호출 자체를 하지 않는다(돈이 움직이기 전에 차단).
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "결제 금액이 주문 정보와 일치하지 않습니다."),
    // 502: PG(토스페이먼츠) 승인 호출이 실패했을 때. 업스트림 장애·거절을 우리 서버의 500 으로 포장하지
    // 않는다는 기존 규칙(AI_SERVER_ERROR·NTS_REQUEST_REJECTED)과 동일 계열. 시크릿 미설정으로 결제를
    // 시작조차 할 수 없는 경우도 이 코드로 fail-close 한다(빈 문자열로 인증을 시도하거나 NPE 로 새지 않게).
    PAYMENT_GATEWAY_ERROR(HttpStatus.BAD_GATEWAY, "결제 승인 처리에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    // 202 계열의 의미를 가진 오류: PG 승인은 <b>성공</b>했는데 플랜 반영이 끝나지 않은 상태(payments 는
    // PAID, user_plan_id 는 NULL). 이 응답을 403/500 으로 돌려주면 사용자는 "결제가 실패했다"고 읽고 다시
    // 결제해 <b>중복 청구</b>로 이어진다 — 반드시 "결제는 됐다"가 전달돼야 한다.
    //
    // 409(CONFLICT)를 쓰는 이유: 재시도로 풀릴 수 있는 상태 충돌이라는 뜻이 이 상황과 정확히 맞는다
    // (일시적 원인이면 같은 orderId 재확정이 PG 재호출 없이 전이만 재시도해 해소된다). 성공(2xx)으로
    // 돌려주면 프론트가 갱신된 플랜을 기대하는데 실제로는 이전 플랜이라 화면이 거짓을 보여준다.
    PAYMENT_PLAN_APPLY_PENDING(HttpStatus.CONFLICT,
            "결제는 정상 완료되었으나 요금제 반영이 지연되고 있습니다. 중복 결제하지 마시고 잠시 후 확인해 주세요."),

    // 시설물(facility)
    // 미존재/타인 소유 모두 이 코드로 통일 응답 — 리소스 존재 여부 열거(cross-owner IDOR) 방지.
    FACILITY_NOT_FOUND(HttpStatus.NOT_FOUND, "시설물을 찾을 수 없습니다."),

    // 점검 회차(inspection) — dev-05-02
    INSPECTION_NOT_FOUND(HttpStatus.NOT_FOUND, "점검 회차를 찾을 수 없습니다."),
    // PESSIMISTIC_WRITE 행 잠금으로 직렬화하지만, 방어적으로 unique(facility_id, round_no) 위반을 그대로 500 노출하지 않고 통일 응답.
    INSPECTION_ROUND_CONFLICT(HttpStatus.CONFLICT, "다른 요청과 충돌하여 점검 회차를 생성하지 못했습니다. 다시 시도해 주세요."),
    // 점검일 도메인 검증 — 시설물 등록일 이전이거나 지나치게 먼 미래는 비정상 입력으로 간주.
    INSPECTION_DATE_INVALID(HttpStatus.BAD_REQUEST, "점검일이 올바르지 않습니다."),
    // #1291 — roundNo는 항상 생성 순서(max+1)라 점검일과 독립적이다. 새 회차의 점검일이 같은
    // 시설물의 기존 최신 회차보다 앞서면 회차 번호=시간 순서 가정이 깨진다(회차 간 비교 화면 등).
    INSPECTION_DATE_BEFORE_LATEST_ROUND(HttpStatus.BAD_REQUEST, "점검일은 최근 회차의 점검일 이후여야 합니다."),
    // 회차 간 비교(#1157) — before/after 미지정 시 최근 2개 회차로 자동 대체하는데, 점검 이력이
    // 2회 미만이면 비교 자체가 성립하지 않는다.
    INSPECTION_COMPARISON_INSUFFICIENT_ROUNDS(HttpStatus.BAD_REQUEST, "비교할 점검 회차가 2회 미만입니다."),
    // #1298 — ANALYZED 이전(CREATED/UPLOADING/ANALYZING) 회차는 AI 분석이 안 끝나 하자가 아직
    // 없다. 이 상태를 비교 대상으로 고르면 이전/이후 회차의 실제 하자가 전부 "신규"로 오분류된다.
    // availableCycles가 이미 걸러내지만, 클라이언트가 필터를 우회해 명시적으로 지정한 경우 방어.
    INSPECTION_COMPARISON_ROUND_NOT_ANALYZED(HttpStatus.BAD_REQUEST, "아직 분석되지 않은 회차는 비교할 수 없습니다."),
    // 점검 요약 진입 시 회차 검수 확정(ANALYZED→REVIEWED) — 분석이 끝나기 전(CREATED/UPLOADING/
    // ANALYZING) 상태에서는 확정할 검수 자체가 없다. REVIEWED/REPORTED는 InspectionService.confirmReview가
    // 멱등 처리(그냥 반환)하므로 이 코드까지 오지 않는다.
    INSPECTION_REVIEW_NOT_READY(HttpStatus.CONFLICT, "분석이 완료되지 않아 검수를 확정할 수 없습니다."),
    // 확정되지 않은(DETECTED) 하자가 남아있으면 검수 확정을 거부 — 프론트 "점검 요약" 버튼도 같은
    // 조건(reviewedCount === totalCount)으로 막지만, 서버도 독립적으로 재검증한다(입력 신뢰 금지).
    INSPECTION_REVIEW_INCOMPLETE(HttpStatus.CONFLICT, "검수하지 않은 하자가 남아 있어 확정할 수 없습니다."),

    // 촬영 데이터(미디어) 업로드(dev-05-03)
    MEDIA_NOT_FOUND(HttpStatus.NOT_FOUND, "미디어를 찾을 수 없습니다."),
    MEDIA_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "한 번에 업로드할 수 있는 파일 수를 초과했습니다. (최대 50장)"),
    // 시설물 대표 사진(#632/#652, HAJA-377) — 시설물당 최대 4장. 기존 보유분 + 이번 업로드가 4장을
    // 넘으면 거부한다(애플리케이션 레벨 카운트 검증).
    FACILITY_PHOTO_COUNT_EXCEEDED(HttpStatus.BAD_REQUEST, "시설물 대표 사진은 최대 4장까지 등록할 수 있습니다."),
    // PR머신 리뷰 P1(#789) — 레거시 상세이미지 폴백 세마포어가 무기한 블로킹이면 permit 대기 스레드가
    // Tomcat 워커를 점유해 전역 가용성 표면이 된다. tryAcquire(timeout) 초과 시 즉시 이 코드로 반환한다.
    MEDIA_DETAIL_GENERATION_BUSY(HttpStatus.SERVICE_UNAVAILABLE,
            "상세 이미지 생성 요청이 많아 잠시 후 다시 시도해 주세요."),

    // 상담(counsel) — 시나리오 챗봇 + 상담원 연결(FR-7, #20/HAJA-33)
    COUNSEL_SESSION_ASSIGNMENT_CONFLICT(HttpStatus.CONFLICT, "이미 상담 세션이 배정된 티켓입니다."),
    // 시나리오 노드 미존재.
    COUNSEL_SCENARIO_NOT_FOUND(HttpStatus.NOT_FOUND, "상담 시나리오를 찾을 수 없습니다."),
    // 티켓 미존재/타인 소유 통일 응답 — 리소스 존재 여부 열거(cross-user IDOR) 방지(PLAN_FORBIDDEN 관례와 정합).
    COUNSEL_TICKET_NOT_FOUND(HttpStatus.NOT_FOUND, "상담 티켓을 찾을 수 없습니다."),
    COUNSEL_TICKET_FORBIDDEN(HttpStatus.FORBIDDEN, "상담 티켓에 대한 권한이 없습니다."),
    // 상담원 연결 기능을 제공하지 않는 요금제(Plan.hasCounselorAccess=false)의 티켓 생성 시도.
    COUNSEL_PLAN_REQUIRED(HttpStatus.FORBIDDEN, "상담원 연결을 사용할 수 없는 요금제입니다."),
    // 셀프-클레임 배정 시 counselor_skills에 티켓의 counsel_type이 없는 상담사(#743/#772 이후 자격 검증).
    COUNSEL_SKILL_MISMATCH(HttpStatus.FORBIDDEN, "해당 상담 유형을 처리할 수 있는 상담사가 아닙니다."),
    // 시나리오 category → counselType 매핑 테이블에 없는 category(신규 시나리오 추가 시 매핑 갱신 누락) —
    // "시나리오 없음"과 구분되는 데이터 정합성 오류(PLAN_DATA_INVALID와 동일 성격).
    COUNSEL_TYPE_MAPPING_NOT_FOUND(HttpStatus.INTERNAL_SERVER_ERROR, "상담 유형 매핑 데이터에 오류가 있습니다."),

    // 알림(notification) — FR-9, HAJA-274. 미존재/타인 소유 모두 통일 응답(cross-user IDOR 방지).
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),

    // 관리자 콘솔 — 사용자 관리(#405, 리뷰 P2)
    // 자기 자신을 강등/정지하거나, 회사에 남은 마지막 ADMIN을 강등/정지하려는 시도를 통일 응답으로 차단.
    ADMIN_PROTECTED_ACCOUNT(HttpStatus.CONFLICT, "자기 자신 또는 회사의 마지막 관리자는 변경할 수 없습니다."),
    // 프론트 역할 선택지(USER/INSPECTOR/ADMIN) 밖의 Role(예: COUNSELOR)을 서버가 그대로 수락하지 않도록 화이트리스트 강제.
    ADMIN_ROLE_NOT_ASSIGNABLE(HttpStatus.BAD_REQUEST, "부여할 수 없는 역할입니다."),
    // 프론트 상태 선택지(ACTIVE/SUSPENDED) 밖의 상태(WAITING, #794)를 관리자 상태변경 API가 그대로
    // 수락하면 companyId는 그대로 둔 채 status만 WAITING이 되어 "WAITING=companyId 없음" 불변식이
    // 깨진다 — ADMIN_ROLE_NOT_ASSIGNABLE과 동일한 화이트리스트 강제.
    ADMIN_STATUS_NOT_ASSIGNABLE(HttpStatus.BAD_REQUEST, "부여할 수 없는 상태입니다."),
    // 플랫폼 관리자 콘솔 — 상담원 스킬 변경(#1001, HAJA-495). role=COUNSELOR가 아닌 대상에게
    // counselor_skills 행을 배선하면 배정 로직(CounselTicketService)이 참조하지 않는 죽은 데이터가 된다.
    ADMIN_SKILL_TARGET_NOT_COUNSELOR(HttpStatus.BAD_REQUEST, "상담원에게만 스킬을 배정할 수 있습니다."),

    // 플랫폼 관리자 콘솔 — 사용자 관리(#576). 사용자 등록 시 지정한 companyId가 존재하지 않는 경우.
    COMPANY_NOT_FOUND(HttpStatus.NOT_FOUND, "기업을 찾을 수 없습니다."),

    // 도메인별 예시 — 각 담당이 추가
    DEFECT_NOT_FOUND(HttpStatus.NOT_FOUND, "하자를 찾을 수 없습니다."),
    // 회차 간 대응 하자 확정(#970 갭3 / HAJA-437) — previousDefectId가 가리키는 하자가 (a) 같은 회사
    // 스코프가 아니거나(findByIdAndCompanyId 자체가 빈 값) (b) 같은 시설물이 아니거나 (c) 현재 하자보다
    // 더 이전 회차가 아닌 경우를 전부 이 코드로 통일 응답한다. 미존재/타사 소유까지 한 코드로 묶는 이유는
    // DEFECT_NOT_FOUND와 동일 — 리소스 존재 여부 열거(cross-company IDOR) 방지.
    DEFECT_PREVIOUS_DEFECT_INVALID(HttpStatus.BAD_REQUEST,
            "이전 회차 대응 하자로 지정할 수 없습니다. 같은 시설물의 더 이전 회차 하자만 지정할 수 있습니다."),
    AI_JOB_TIMEOUT(HttpStatus.INTERNAL_SERVER_ERROR, "AI 분석 요청이 시간 초과되었습니다."),

    // AI 분석 실행/상태(dev-05-04)
    ANALYSIS_NO_MEDIA(HttpStatus.BAD_REQUEST, "분석할 이미지가 없습니다."),
    ANALYSIS_ALREADY_RUNNING(HttpStatus.CONFLICT, "이미 분석이 진행 중입니다."),
    ANALYSIS_QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "분석 요청이 많아 잠시 후 다시 시도해 주세요."),
    // 코드 리뷰 P2 4차 — analysisTaskExecutor(전역 공유 풀, core=max=2·queue=20)를 한 회사가
    // 대량 요청으로 독점하면 다른 회사까지 ANALYSIS_QUEUE_FULL을 받는 noisy-neighbor 표면이라,
    // 공유 풀에 실제로 들어가기 전에 회사별 동시 실행 상한으로 먼저 막는다(AsyncConfig 참고).
    ANALYSIS_COMPANY_CONCURRENCY_LIMIT(HttpStatus.SERVICE_UNAVAILABLE,
            "동시에 진행 가능한 분석 요청 수를 초과했습니다. 진행 중인 분석이 끝난 뒤 다시 시도해 주세요."),
    // 코드 리뷰 P1 — 검수 완료(REVIEWED)·보고서화(REPORTED) 회차는 재분석을 허용하지 않는다(제품
    // 결정). 재분석은 소프트삭제로 기존(사람이 검수한) 하자를 지우므로, 최종 상태 회차에서 허용하면
    // 무보상 데이터 유실 표면이 된다.
    ANALYSIS_NOT_ALLOWED(HttpStatus.CONFLICT, "검수 완료 또는 보고서화된 회차는 재분석할 수 없습니다."),
    // PR머신 리뷰 3차/4차 P1 — FAILED 회차의 재분석 원자적 선점은 "비삭제 하자 없음" 요건을 건너뛰는데,
    // 이 예외의 전제(FAILED에 남은 하자는 전부 이번 실패한 실행이 만든 AI 결과뿐)가 성립하려면 FAILED
    // 상태에서 하자를 만들거나 손대는 모든 쓰기(생성·검수·조치등록·위치수정·회차대응확정·상태전이)를
    // 막아야 한다. 안 막으면 사람이 FAILED 회차에 등록·수정한 하자가 재분석 워커의
    // softDeleteAllForInspectionThenSave(비삭제 하자 전체 대상)에 휩쓸려 무보상 유실된다
    // (DefectInspectionWriteGuard 참고 — 원래 이름은 CREATE 전용이었으나 4차 리뷰에서 나머지 쓰기
    // 경로에도 재사용하며 이름을 일반화했다).
    DEFECT_WRITE_BLOCKED_ANALYSIS_FAILED(HttpStatus.CONFLICT,
            "분석 실패(FAILED) 회차는 재분석 시 하자가 삭제될 수 있어 하자를 추가하거나 수정할 수 없습니다. 재분석을 먼저 진행해 주세요."),

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
