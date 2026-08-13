package com.hajacheck.auth.service;

import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.dto.PasswordChangeRequest;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그인 후 본인 비밀번호 변경(#1315 / HAJA-601).
 *
 * <p>비로그인 이메일 링크 재설정({@link PasswordResetService})과의 차이: 저쪽의 안전성은 "메일함 소유
 * 증명"에서 나오지만, 이쪽은 <b>세션 + 현재 비밀번호 확인</b>에서 나온다. 세션만으로는 부족하다 —
 * 세션이 탈취된 상황(XSS·기기 방치)에서 비밀번호까지 바꿀 수 있으면 임시 접근이 <b>영구 계정 인수</b>로
 * 승격되기 때문이다. 따라서 아래 셋은 어느 하나라도 빠지면 이 엔드포인트의 보안 근거가 사라진다:
 * <ol>
 *   <li>대상은 <b>세션 principal 의 userId 로만</b> 식별한다 — 호출부가 요청 바디의 값을 넘기면 IDOR.</li>
 *   <li><b>현재 비밀번호</b>를 반드시 확인한다(재인증 관문).</li>
 *   <li>그 관문에 <b>rate-limit</b> 을 건다 — 시도 횟수 제한이 없으면 관문이 사실상 없는 것과 같다.</li>
 * </ol>
 *
 * <p>⚠️ <b>세션 무효화는 이 서비스의 책임이 아니다</b>(웹 계층 관심사) — 컨트롤러가 이 메서드가 반환
 * (= 트랜잭션 커밋)한 뒤 {@code SessionTerminator} 로 현재 세션을 끝낸다. 그리고 <b>현재 세션만</b>
 * 끝난다: 다른 기기 세션은 살아 있다(non-indexed Redis 세션이라
 * {@code FindByIndexNameSessionRepository} 빈이 없고 주입하면 기동 실패 — PasswordResetService
 * javadoc 에 기록된 기존 한계). <b>전 기기 무효화는 후속 이슈 #1318</b> 이며, 아래 rate-limit 한계
 * (자기계정 봉쇄)의 근본 해결이기도 하다.
 *
 * <p>⚠️ 비밀번호 평문·해시는 로그·예외 메시지·응답 어디에도 남기지 않는다(userId 만 기록).
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PasswordChangeService {

    private static final String RATE_LIMIT_USER_KEY_PREFIX = "rate:password-change:user:";
    // 429 를 맞은 횟수를 세는 별도 축(사용자 축과 키가 달라 서로 소모하지 않는다) — 침해 의심 경보 전용.
    private static final String BREACH_ALERT_KEY_PREFIX = "rate:password-change:breach:user:";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final AuthProperties authProperties;
    private final DemoAccountGuard demoAccountGuard;

    /**
     * 현재 비밀번호를 확인한 뒤 새 비밀번호로 교체한다.
     *
     * <p><b>검사 순서에 의미가 있다</b>: rate-limit → 계정 조회 → 비밀번호 보유 → 현재 비밀번호 일치 →
     * 신·구 동일. ①rate-limit 을 맨 앞에 두어야 무차별 대입이 매 시도마다 bcrypt 비교를 유발하지 못하고
     * (계산 자원 소모), ②"신·구 동일" 검사를 현재 비밀번호 확인 <b>뒤</b>에 두어야 현재 비밀번호를 모르는
     * 호출자가 "새 비밀번호로 넣어본 값이 현재 비밀번호인지" 를 400/401 차이로 알아내지 못한다
     * (순서를 뒤집으면 그 자체가 오라클이 된다).
     *
     * @param userId 세션 principal 의 userId — <b>요청 바디에서 온 값을 넘기면 안 된다</b>(IDOR).
     */
    @Transactional
    public void changePassword(Long userId, PasswordChangeRequest request) {
        enforceRateLimit(userId);

        User user = userRepository.findById(userId)
                // 인증된 세션의 principal 이므로 정상 경로에선 항상 존재한다(SessionUserRevalidationFilter 가
                // 매 요청 재조회해 미존재를 401 로 끊는다). 방어적 처리.
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        // 데모 계정 자기보호(#1626) — 데모 세션이 비밀번호를 바꾸면 서버 보관 크레덴셜과 어긋나
        // 다음 방문자의 원클릭 로그인이 통째로 깨진다. 현재 비밀번호 검사보다 앞에 두어 409 로 즉시
        // 차단한다(데모 크레덴셜은 서버만 아는 값이라 이 순서가 오라클이 되지 않는다 — DemoAccountGuard).
        demoAccountGuard.requireNotDemoAccount(user.getEmail());

        // 소셜 전용 계정(passwordHash=null) — CustomUserDetailsService 가 비밀번호 로그인을 명시적으로
        // 금지한 계정이다. 여기서 비밀번호를 심어주면 그 계층 규칙을 이 경로가 말없이 뒤집는다
        // (PasswordResetService.isPasswordResettable 과 동일 논리).
        if (!user.hasPassword()) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_NOT_SET);
        }
        // 심층방어 — 정지 계정은 SessionUserRevalidationFilter 가 매 요청 401 로 끊으므로 정상 배선에선
        // 도달하지 않는다. 그래도 남기는 이유: 필터의 예외 경로가 늘어나거나 이 서비스가 다른 진입점
        // (배치·내부 호출)에서 재사용될 때, "정지 계정은 비밀번호 로그인 불가"(CustomUserDetailsService)
        // 규칙이 조용히 뚫리는 것을 막는다. 재설정 경로도 같은 조건으로 차단한다.
        if (user.isSuspended()) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_SUSPENDED);
        }

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            // 감사 로그 — 세션 탈취 후 비밀번호 대입 시도를 탐지할 유일한 축이다. userId 만 남긴다.
            log.warn("비밀번호 변경 실패(현재 비밀번호 불일치) — userId={}", userId);
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_UNCHANGED);
        }

        user.changePassword(passwordEncoder.encode(request.newPassword()));

        // 성공 = 호출자가 현재 비밀번호를 안다는 증명 → 실패 시도를 세던 한도는 목적을 다했다. 즉시 해제해
        // 정상 사용자가 "오타 몇 번 뒤 성공"한 직후 자기 계정의 재변경을 스스로 막는 일을 없앤다.
        // 무차별 대입 방어력은 줄지 않는다: 여기 도달하려면 이미 현재 비밀번호를 맞혀야 한다.
        // ⚠️ 트랜잭션 커밋 전에 지운다(afterCommit 콜백을 쓰지 않는다) — 뒤이어 커밋이 실패하면 변경 없이
        // 카운터만 풀리지만, 그 카운터를 되찾는 쪽도 이미 현재 비밀번호를 아는 사람이라 공격자에게 주는
        // 이득이 없다. InviteCodeService 가 afterCommit 을 쓰는 이유(1회용 코드가 영구 소각됨)와 달리
        // 여기서 잃는 것은 "남은 시도 횟수"뿐이라 복잡도를 더할 실익이 없다.
        rateLimiter.reset(RATE_LIMIT_USER_KEY_PREFIX + userId);

        log.info("비밀번호 변경 완료 — userId={}", userId);
    }

    /**
     * 사용자(userId) 축 하나만 사용한다 — 이미 인증된 요청이라 계정 열거 축(이메일)도, 익명 트래픽을
     * 받아내는 전역 상한도 필요 없다. IP 축 미사용 사유는 {@code RateLimiter} javadoc 참조.
     *
     * <p>성공·실패를 가리지 않고 카운트하되 <b>성공 시에는 즉시 해제</b>한다(위 changePassword 참조).
     *
     * <p>⚠️ <b>알려진 한계 — 자기계정 봉쇄(self-account DoS)</b>: 축이 {@code userId} 라, 이 엔드포인트가
     * 방어 대상으로 삼는 상황(세션 탈취)에서 <b>공격자와 피해자가 같은 카운터를 공유</b>한다. 공격자가
     * 창마다 한도를 소모시키면 피해자는 계속 429 를 맞아 <b>비밀번호를 바꿔 접근을 회수하지 못한다</b> —
     * 타 사용자에게 영향이 없을 뿐, "DoS 가 성립하지 않는다"는 말은 틀렸다. 정확히 복구 경로를 봉쇄하는
     * 형태의 DoS 다. <b>탈출 경로</b>는 이 축과 무관한 비로그인 이메일 재설정
     * ({@code POST /api/auth/password-reset-request} → 메일 링크; rate-limit 축이 이메일·전역이라 별개).
     * <b>근본 해결은 전 기기 세션 무효화(#1318)</b> — 탈취 세션을 끊어야 공격자가 카운터를 소모할 수단이
     * 사라진다. 한도를 키우는 건 해결이 아니다(브루트포스 방어를 포기하면서 봉쇄 시점만 늦춘다).
     */
    private void enforceRateLimit(Long userId) {
        AuthProperties.PasswordChangeRateLimit limits = authProperties.getPasswordChangeRateLimit();
        if (rateLimiter.tryAcquire(RATE_LIMIT_USER_KEY_PREFIX + userId,
                limits.getUserLimit(), limits.getUserWindow())) {
            return;
        }
        logRateLimitExceeded(userId, limits);
        throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
    }

    /**
     * 429 를 <b>단발</b>(정상 사용자의 오타 연타)과 <b>반복</b>(세션 탈취 후 대입 또는 자기계정 봉쇄
     * 진행 중)으로 구분해 남긴다. 429 횟수를 세는 별도 축을 하나 더 두고, 그 축까지 넘치면 ERROR 로
     * 승격한다 — 모든 429 를 같은 WARN 으로 남기면 실제 침해가 잡음에 묻혀 아무도 알아채지 못한다.
     * (경보 축은 사용자 축과 키가 달라 서로의 한도를 소모하지 않고, 변경 성공 시에도 지우지 않는다 —
     * 지우면 공격자가 성공 한 번으로 자기 흔적을 덮을 수 있다.)
     *
     * <p>⚠️ 어느 경로든 비밀번호 평문·해시는 남기지 않는다. 식별자는 userId 뿐이다.
     */
    private void logRateLimitExceeded(Long userId, AuthProperties.PasswordChangeRateLimit limits) {
        boolean repeated = !rateLimiter.tryAcquire(BREACH_ALERT_KEY_PREFIX + userId,
                limits.getBreachAlertThreshold(), limits.getBreachAlertWindow());
        if (repeated) {
            log.error("비밀번호 변경 rate-limit 반복 초과 — 세션 탈취 후 비밀번호 대입 또는 변경 봉쇄 의심."
                            + " userId={} threshold={}/{}",
                    userId, limits.getBreachAlertThreshold(), limits.getBreachAlertWindow());
            return;
        }
        log.warn("비밀번호 변경 rate-limit 초과 — userId={} limit={}/{}",
                userId, limits.getUserLimit(), limits.getUserWindow());
    }
}
