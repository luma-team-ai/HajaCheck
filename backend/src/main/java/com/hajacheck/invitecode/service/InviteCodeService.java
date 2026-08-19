package com.hajacheck.invitecode.service;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.dto.UserResponse;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.invitecode.dto.InviteCodeIssueResponse;
import com.hajacheck.invitecode.support.InviteCodeGenerator;
import com.hajacheck.invitecode.support.InviteCodeStore;
import com.hajacheck.membership.service.QuotaService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 사용자 초대 코드(#794) — 기업 관리자가 발급(회사 스코프) → WAITING 상태 소셜 가입 계정이 redeem해
 * company_id 배선 + ACTIVE 전환. 코드 자체는 InviteCodeStore(Redis)에만 존재하는 1회용·TTL 180초 값이라
 * DB 테이블을 두지 않는다. StringRedisTemplate을 직접 쓰지 않고 InviteCodeStore 인터페이스를 통해서만
 * 접근한다 — test 프로파일은 RedisAutoConfiguration을 제외해 StringRedisTemplate 빈이 없기 때문
 * (TokenStore와 동일한 이유, InMemoryInviteCodeStore가 test 프로파일에서 대체).
 */
@Service
@RequiredArgsConstructor
public class InviteCodeService {

    // SecureRandom 6자리 코드는 충돌 확률이 극히 낮지만(31^6 ≈ 8.8억), 동시 발급이 겹칠 가능성에 대비해
    // issueIfAbsent(SETNX)로 원자적 선점 후 실패하면 재시도한다 — 한 회사의 코드를 다른 회사가 덮어쓰는 것을 방지.
    private static final int MAX_ISSUE_ATTEMPTS = 5;
    private static final String REDEEM_RATE_LIMIT_KEY_PREFIX = "rate:invite-code-redeem:user:";

    private final InviteCodeStore inviteCodeStore;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final CompanyMembershipRepository companyMembershipRepository;
    private final AuthService authService;
    private final AuthProperties authProperties;
    private final RateLimiter rateLimiter;
    private final QuotaService quotaService;

    /**
     * 발급 — 요청 관리자 소속 회사(loginUser.companyId)로 스코프된 코드를 만든다(ADMIN 전용, 컨트롤러가 role 강제).
     *
     * <p>좌석 잔여 선검사(#857) — 코드를 생성·저장하기 *전에* {@link QuotaService#hasAvailableSeat}로 판정한다.
     * 여유가 없으면 기존 {@code PLAN_SEAT_QUOTA_EXCEEDED}를 그대로 던지고 {@code inviteCodeStore}에는 아무
     * 부작용도 남기지 않는다 — redeem 시점에야 실패를 알아 관리자가 아무 신호도 못 받던 문제(초대 대상만
     * WAITING에 갇힘)를 발급 시점으로 앞당긴다. 이 검사는 advisory이므로 {@link #redeem}의
     * {@code reserveSeat}(최종 방어선)는 그대로 유지한다 — 발급~redeem 사이 경합으로 여기를 통과해도
     * redeem에서 다시 막힐 수 있다.
     */
    public InviteCodeIssueResponse issue(Long companyId) {
        requireCompanyId(companyId);
        if (!quotaService.hasAvailableSeat(companyId)) {
            throw new BusinessException(ErrorCode.PLAN_SEAT_QUOTA_EXCEEDED);
        }
        Duration ttl = authProperties.getInviteCodeTtl();

        for (int attempt = 0; attempt < MAX_ISSUE_ATTEMPTS; attempt++) {
            String code = InviteCodeGenerator.generate();
            if (inviteCodeStore.issueIfAbsent(code, String.valueOf(companyId), ttl)) {
                return new InviteCodeIssueResponse(code, ttl.toSeconds());
            }
        }
        // 연속 5회 충돌은 사실상 불가능한 확률 — 발생하면 일시적 서버 오류로 표면화해 재시도를 유도한다.
        throw new BusinessException(ErrorCode.INTERNAL_ERROR);
    }

    /**
     * 폐기 — 발급 모달을 닫을 때 호출. 소유(발급 회사) 확인 후에만 삭제하고, 미존재·만료·타 회사 소유는
     * 모두 조용히 무시한다(멱등 삭제 + 존재 여부 열거 방지 — AdminUserService.findUser와 동일 원칙).
     */
    public void revoke(String code, Long companyId) {
        requireCompanyId(companyId);
        inviteCodeStore.peek(code)
                .filter(storedCompanyId -> storedCompanyId.equals(String.valueOf(companyId)))
                .ifPresent(storedCompanyId -> inviteCodeStore.delete(code));
    }

    /**
     * redeem — WAITING 상태 사용자가 코드를 입력해 회사 소속으로 전환한다.
     *
     * <p>Redis 코드는 이 메서드 안에서 삭제하지 않는다({@link #peek}로 존재만 확인) — DB 트랜잭션이
     * 실제로 커밋된 뒤에만 {@link TransactionSynchronization#afterCommit} 콜백으로 소비한다(PR머신 리뷰
     * P2). 먼저 소비(consumeIfPresent)해버리면 그 뒤 DB 커밋이 실패(커넥션 단절·제약 위반 등)했을 때
     * 1회용 코드만 영구 소각되고 사용자는 ACTIVE 전환 없이 WAITING에 갇힌다 — 코드의 "1회성" 계약을
     * Redis 삭제가 아니라 DB 트랜잭션 성공 여부와 일치시킨다.
     *
     * <p>⚠️ 알려진 트레이드오프: peek→커밋 사이 짧은 창에서 같은 코드로 동시에 두 redeem 요청이 들어오면
     * (같은 코드를 아는 두 사용자가 동시에 시도) 둘 다 검증을 통과해 같은 회사에 배선될 수 있다(코드
     * "1회용" 원칙의 근소한 완화). 다른 회사로의 오배선(cross-tenant)은 아니고, 발급 즉시 공유되지
     * 않는 한 실질적으로 발생하기 어려운 경합이라 트랜잭션 정합성(더 흔한 실패 모드)을 우선했다.
     *
     * <p>⚠️ rate-limit(Redis)·peek(Redis)·existsById(DB)를 @Transactional 트랜잭션 시작 *이후*에
     * 수행하는 이유(PR머신 리뷰 P3 — "왜 트랜잭션 안에 두는가" 코멘트 요청): 이 세 호출을 트랜잭션 밖으로
     * 빼려면 원자성이 필요한 DB 쓰기(activateWithInviteCode)만 별도 메서드로 분리해야 하는데, 같은 빈
     * 안에서 그 메서드를 호출하면 Spring AOP 프록시를 거치지 않아(self-invocation) @Transactional이
     * 조용히 무시된다 — 트랜잭션 없이 activateWithInviteCode가 실행되면 afterCommit 콜백 자체가 걸리지
     * 않아 이 메서드 전체의 "커밋 후에만 코드 소비" 계약(바로 위 트레이드오프 단락)이 깨진다. 안전하게
     * 분리하려면 별도 Spring 빈(예: 전용 활성화 서비스)으로 쪼개야 하는데, 이 경로의 실제 비용(로컬
     * Redis 왕복 1~2회 + PK 조회 1회)이 미미해 그 리팩터링 리스크를 감수할 실익이 낮다고 판단했다.
     */
    @Transactional
    public UserResponse redeem(String code, Long userId) {
        AuthProperties.InviteCodeRedeemRateLimit rateLimit = authProperties.getInviteCodeRedeemRateLimit();
        if (!rateLimiter.tryAcquire(REDEEM_RATE_LIMIT_KEY_PREFIX + userId,
                rateLimit.getUserLimit(), rateLimit.getUserWindow())) {
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }

        // 사용자 행 선점(#1492) — 이 트랜잭션이 끝날 때까지 같은 사용자의 다른 redeem 을 직렬화한다.
        // 아래 좌석 예약(quotaService.reserveSeat)의 잠금은 usage_counters 행 = **회사 단위**라, 같은
        // 사용자를 축으로 하는 경합을 갈라놓지 못한다. 실측된 두 축(InviteCodeRedeemCrossCompany
        // ConcurrencyTest):
        //  (a) 서로 다른 두 회사 코드 동시 redeem — 둘 다 stale WAITING 을 통과해 각자 회사 좌석을
        //      예약하지만, 부분 UQ(uq_company_memberships_approved_user)가 두 번째 멤버십 INSERT 를 막아
        //      패자가 통째로 롤백된다. 정합은 지켜지되 실패가 도메인 규칙이 아니라 제약 위반(raw
        //      DataIntegrityViolationException)으로 표면화됐다.
        //  (b) 같은 회사 코드(같은 코드 2회 제출) 동시 redeem — **DB 가 지켜주지 못한다**. 패자는
        //      usage_counters 행 잠금에서 대기하다 승자 커밋 후 재실측(measured=2)하는데, 그 시점엔
        //      멤버십 행이 이미 있어 grantEffectiveMembership 이 INSERT 가 아니라 재승인(UPDATE)으로
        //      빠져 어떤 UQ 에도 걸리지 않는다 → 둘 다 커밋되고 seat_count 만 1 부풀어 영구 이중
        //      계상된다(락 제거 실측: 성공 2 / APPROVED 1 / seat_count 3 / 실제 ACTIVE 2).
        // 이 잠금이 두 축을 모두 사용자 단위로 직렬화한다 — 뒤늦은 요청은 승자 커밋 후의 최신 상태
        // (ACTIVE)를 읽어 바로 아래 requireWaiting() 에서 순차 실행과 동일하게 거부된다(409
        // INVALID_STATE_TRANSITION).
        // ⚠️ 잠금 순서는 users → usage_counters 로 고정한다. 이 레포에는 역순(usage_counters → users)
        // 경로가 실재하며, 교착이 없는 근거는 UserRepository#findByIdForUpdate javadoc 의 불변식에
        // 걸려 있다 — 이 호출 위치를 옮기기 전에 반드시 그 javadoc 을 읽을 것.
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 코드 조회(peek)보다 WAITING 검사를 *먼저* 한다 — 근거 정정(#1492 리뷰 P3).
        // 이전 주석은 "순서를 바꾸면 코드가 소각된다"고 적었지만 틀렸다: 실제 코드 소비는 아래
        // afterCommit 콜백이라 peek 이 앞서도 코드는 타지 않는다. 이 순서의 진짜 효용은 **열거 방지**다 —
        // 이미 ACTIVE 인 계정(또는 탈취 세션)이 redeem 을 반복 호출하면서 응답만 보고 임의의 6자리 값이
        // 유효한 초대 코드인지 떠보는 오라클로 쓰지 못하게 한다. WAITING 검사가 앞에 있으면 어떤 코드를
        // 넣든 상태 전이 거부(409 INVALID_STATE_TRANSITION)로 통일돼 코드 유효성이 새지 않는다.
        // ⚠️ 순서를 바꾸면(peek 먼저) AUTH_INVITE_CODE_INVALID 와 INVALID_STATE_TRANSITION 이 갈리면서
        // 그 오라클이 곧바로 열린다.
        user.requireWaiting();

        String storedCompanyId = inviteCodeStore.peek(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVITE_CODE_INVALID));

        Long companyId = Long.valueOf(storedCompanyId);
        // 발급 시점엔 존재하던 회사가 redeem 시점(최대 TTL만큼 지연)엔 삭제됐을 수 있다(PR머신 리뷰 P2
        // 추가 지적) — 코드 자체의 유효성과 동일한 통일 응답으로 처리해 회사 존재 여부를 열거하지 않는다.
        if (!companyRepository.existsById(companyId)) {
            throw new BusinessException(ErrorCode.AUTH_INVITE_CODE_INVALID);
        }

        // 좌석 한도(plans.max_seats) 강제(#843) — 활성화 직전, 같은 트랜잭션에서 1석을 예약한다.
        // 같은 트랜잭션이어야 (a) 한도 초과 시 활성화가 통째로 롤백되고 (b) 예약이 커밋될 때까지 잠금이
        // 유지돼 동시 redeem 이 같은 좌석을 이중 사용하지 못한다.
        //
        // ⚠️ 계약(#1492 리뷰 P3) — **redeem 은 companyId 가 반드시 non-null 이어야 한다**. 이 호출은
        // 위에서 잡은 users 행 FOR UPDATE 를 쥔 채 이뤄지고, reserveSeat 내부의 자가 프로비저닝
        // (PlanProvisioningService#provisionFreePlanInNewTransaction)은 REQUIRES_NEW = **별도 커넥션**이다.
        // 지금 안전한 이유는 companyId != null 이라 회사 스코프 경로(ensureFreePlanForCompany →
        // UserPlan.forCompany, user_id 를 null 로 둔다)만 타서 내부 트랜잭션이 users 를 전혀 건드리지
        // 않기 때문이다. 개인 스코프 프로비저닝(ensureFreePlanForUser → UserPlan.forUser)에 닿게 되면
        // 내부 트랜잭션이 잠긴 users 행에 FK FOR KEY SHARE 를 요구하는데 바깥 트랜잭션은
        // idle-in-transaction 이라, **PG 교착 탐지기가 잡지 못하는 자기교착(무한 대기)** 이 된다.
        quotaService.reserveSeat(companyId);

        user.activateWithInviteCode(companyId);
        grantEffectiveMembership(companyId, userId);
        UserResponse response = authService.getMe(userId);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                inviteCodeStore.consumeIfPresent(code);
            }
        });

        return response;
    }

    /**
     * 회사 스코프 접근에 필요한 유효 멤버십을 발급한다(#1474).
     *
     * <p><b>왜 필요한가</b>: {@code activateWithInviteCode}가 채우는 {@code users.company_id}는 조회 편의
     * 포인터일 뿐이고, 실제 인가 판정은 {@code CompanyScopeGuard.requireEffectiveMembership} →
     * {@code CompanyMembershipRepository.existsEffectiveApprovedMembership}가 {@code company_memberships}
     * 행을 기준으로 한다. 이 행이 없으면 나머지 3조건(user ACTIVE · company APPROVED · verification
     * VERIFIED)을 모두 만족해도 <b>회사 스코프 API가 전부 403</b>이 된다 — 초대로 합류한 사용자가 가입
     * 직후부터 아무 기능도 못 쓰던 실제 장애의 원인이었다.
     *
     * <p><b>같은 트랜잭션이어야 하는 이유</b>: 좌석 예약({@code reserveSeat})·ACTIVE 전환과 원자적으로
     * 묶여야 한다. 분리하면 "좌석은 소모됐는데 멤버십이 없어 403" 또는 "멤버십은 있는데 좌석 미반영"
     * 같은 부분 성공 상태가 남는다. 이 메서드는 {@code redeem}의 트랜잭션 안에서만 호출된다.
     *
     * <p><b>교훈(#1368과 동일 유형)</b>: 인가 조건은 "검사하는 코드"와 "충족시키는 코드"가 쌍으로
     * 존재해야 한다. 조건을 추가할 때는 "누가 이 조건을 만들어 주는가"를 반드시 확인한다.
     */
    private void grantEffectiveMembership(Long companyId, Long userId) {
        companyMembershipRepository.findByCompanyIdAndUserId(companyId, userId)
                .ifPresentOrElse(
                        CompanyMembership::approveAsInvitedMember,
                        () -> companyMembershipRepository.save(
                                CompanyMembership.approvedMember(companyId, userId)));
    }

    // 초대 코드는 기업 관리자 전용 기능이라 companyId 없는 관리자(개인 회원 등)는 발급/폐기 대상이 아니다
    // (AdminUserService.requireCompanyId와 동일 방어 — ADMIN role은 항상 companyId를 갖지만 방어적으로 유지).
    private void requireCompanyId(Long companyId) {
        if (companyId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
