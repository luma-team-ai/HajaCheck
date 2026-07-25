package com.hajacheck.invitecode.service;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.dto.UserResponse;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.invitecode.dto.InviteCodeIssueResponse;
import com.hajacheck.invitecode.support.InviteCodeGenerator;
import com.hajacheck.invitecode.support.InviteCodeStore;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final InviteCodeStore inviteCodeStore;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final AuthService authService;
    private final AuthProperties authProperties;

    /** 발급 — 요청 관리자 소속 회사(loginUser.companyId)로 스코프된 코드를 만든다(ADMIN 전용, 컨트롤러가 role 강제). */
    public InviteCodeIssueResponse issue(Long companyId) {
        requireCompanyId(companyId);
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

    /** redeem — WAITING 상태 사용자가 코드를 입력해 회사 소속으로 전환한다. */
    @Transactional
    public UserResponse redeem(String code, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // Redis 코드를 소비하기 *전에* WAITING 여부부터 확인한다(PR머신 리뷰 P2). 순서를 바꾸면 이미
        // ACTIVE인 계정의 redeem 시도(실수/악의 모두)가 상태전이 예외를 맞기 전에 코드를 태워버려,
        // 그 코드를 받은 정당한 WAITING 사용자가 못 쓰게 된다(griefing) — DB 커밋 실패 시에도 코드만
        // 소실되고 사용자는 재발급 전까지 WAITING에 갇힌다.
        user.requireWaiting();

        // consumeIfPresent: 조회와 삭제를 한 번에 — 동시에 같은 코드가 두 번 redeem되는 경합에서 한쪽만 성공한다
        // (RedisTokenStore.consume과 동일 원자성 패턴).
        String storedCompanyId = inviteCodeStore.consumeIfPresent(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_INVITE_CODE_INVALID));

        Long companyId = Long.valueOf(storedCompanyId);
        // 발급 시점엔 존재하던 회사가 redeem 시점(최대 TTL만큼 지연)엔 삭제됐을 수 있다(PR머신 리뷰 P2
        // 추가 지적) — 코드 자체의 유효성과 동일한 통일 응답으로 처리해 회사 존재 여부를 열거하지 않는다.
        if (!companyRepository.existsById(companyId)) {
            throw new BusinessException(ErrorCode.AUTH_INVITE_CODE_INVALID);
        }

        user.activateWithInviteCode(companyId);
        return authService.getMe(userId);
    }

    // 초대 코드는 기업 관리자 전용 기능이라 companyId 없는 관리자(개인 회원 등)는 발급/폐기 대상이 아니다
    // (AdminUserService.requireCompanyId와 동일 방어 — ADMIN role은 항상 companyId를 갖지만 방어적으로 유지).
    private void requireCompanyId(Long companyId) {
        if (companyId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }
}
