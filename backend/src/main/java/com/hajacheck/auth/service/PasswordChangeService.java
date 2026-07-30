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
 * javadoc 에 기록된 기존 한계). 전 기기 무효화는 별도 이슈.
 *
 * <p>⚠️ 비밀번호 평문·해시는 로그·예외 메시지·응답 어디에도 남기지 않는다(userId 만 기록).
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PasswordChangeService {

    private static final String RATE_LIMIT_USER_KEY_PREFIX = "rate:password-change:user:";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RateLimiter rateLimiter;
    private final AuthProperties authProperties;

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

        log.info("비밀번호 변경 완료 — userId={}", userId);
    }

    /**
     * 사용자(userId) 축 하나만 사용한다 — 이미 인증된 요청이라 계정 열거 축(이메일)도, 익명 트래픽을
     * 받아내는 전역 상한도 필요 없다. IP 축 미사용 사유는 {@code RateLimiter} javadoc 참조.
     *
     * <p>성공·실패를 가리지 않고 카운트한다(고정 창 카운터의 의미 그대로). 자기 계정에만 걸리므로
     * 타 사용자에게 영향이 없고, 공격자가 남의 카운터를 소모시켜 변경을 막는 DoS 도 성립하지 않는다.
     */
    private void enforceRateLimit(Long userId) {
        AuthProperties.PasswordChangeRateLimit limits = authProperties.getPasswordChangeRateLimit();
        if (!rateLimiter.tryAcquire(RATE_LIMIT_USER_KEY_PREFIX + userId,
                limits.getUserLimit(), limits.getUserWindow())) {
            log.warn("비밀번호 변경 rate-limit 초과 — userId={}", userId);
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
    }
}
