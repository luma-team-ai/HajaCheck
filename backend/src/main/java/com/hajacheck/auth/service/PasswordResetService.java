package com.hajacheck.auth.service;

import com.hajacheck.auth.config.AppMailProperties;
import com.hajacheck.auth.config.AuthProperties;
import com.hajacheck.auth.dto.PasswordResetLinkRequest;
import com.hajacheck.auth.dto.PasswordResetLinkResponse;
import com.hajacheck.auth.dto.PasswordResetRequest;
import com.hajacheck.auth.dto.PasswordResetResponse;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.support.PasswordResetMailDispatcher;
import com.hajacheck.auth.support.PasswordResetTokenStore;
import com.hajacheck.auth.support.RateLimiter;
import com.hajacheck.auth.support.TokenKeys;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비밀번호 찾기 — 이메일 링크 방식(#194 / HAJA-172).
 *
 * <p><b>이 기능의 안전성은 오직 "메일함 소유 증명"에서 나온다.</b> 최초 구현은 이메일 + 사업자번호(둘 다
 * 준공개 정보)만으로 resetToken 을 <b>응답에 반환</b>해 누구나 타인 계정을 탈취할 수 있었고 P1 반려됐다.
 * 따라서 아래 세 가지는 어기면 그 P1 이 그대로 재현된다:
 * <ol>
 *   <li>resetToken 은 <b>메일로만</b> — 응답 바디·로그·에러 메시지에 절대 넣지 않는다.</li>
 *   <li>1단계는 계정 존재 여부와 무관하게 <b>동일 응답 + 동일 응답시간</b>(발송은 비동기).</li>
 *   <li>토큰은 1회용·단기·추측 불가(32바이트 SecureRandom, consume, 10분 TTL).</li>
 * </ol>
 *
 * <p>⚠️ <b>세션 무효화는 범위 외</b>(A 결정, 계약 명시): 현 설정은 non-indexed 세션이라
 * {@code FindByIndexNameSessionRepository} 빈이 없고 주입하면 기동이 실패한다. "비밀번호를 바꿔도 기존
 * 세션은 살아있다"가 알려진 한계이며 별도 이슈로 뺐다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PasswordResetService {

    private static final String RATE_LIMIT_EMAIL_KEY_PREFIX = "rate:password-reset:email:";
    private static final String RATE_LIMIT_GLOBAL_KEY = "rate:password-reset:global";
    private static final String RESET_PATH = "/reset-password?token=";

    private final UserRepository userRepository;
    private final PasswordResetTokenStore tokenStore;
    private final RateLimiter rateLimiter;
    private final PasswordResetMailDispatcher mailDispatcher;
    private final PasswordEncoder passwordEncoder;
    private final AuthProperties authProperties;
    private final AppMailProperties mailProperties;

    /**
     * 1단계 — 재설정 링크 발송 요청. <b>계정 존재 여부와 무관하게 항상 200 + 동일 바디</b>를 반환한다.
     * 존재할 때만 실제로 토큰을 발급·발송하며, 그 사실은 응답 어디에도 드러나지 않는다.
     */
    public PasswordResetLinkResponse requestResetLink(PasswordResetLinkRequest request) {
        String email = request.email().trim();
        String emailHash = TokenKeys.hash(email.toLowerCase(Locale.ROOT));

        // ⚠️ rate-limit 은 계정 조회 전에, 존재 여부와 무관한 조건으로 건다(429 가 열거 단서가 되면 안 된다).
        enforceRateLimits(emailHash);

        // 존재하고 + 비밀번호 로그인 계정일 때만 발송. 여기서 분기해도 응답 바디·시점은 동일하다(발송은 @Async).
        userRepository.findByEmail(email)
                .filter(User::hasPassword)
                .ifPresent(user -> issueAndDispatch(user, emailHash));

        // 감사 로그: 이메일 해시·시각(로거가 부착). ⚠️ 이메일 원문·토큰 평문 금지.
        log.info("비밀번호 재설정 링크 요청 접수 — emailHash={}", emailHash);
        return PasswordResetLinkResponse.accepted();
    }

    /**
     * 2단계 — 토큰 검증 후 비밀번호 변경. 토큰은 조회 즉시 삭제(1회용)된다.
     *
     * <p>비밀번호 정책 위반은 DTO 검증이 먼저 걸러 INVALID_INPUT(400) 이 되므로 <b>토큰을 태우지 않는다</b>
     * (오타 한 번에 링크가 죽어 재발급을 강요당하는 UX 사고 방지).
     *
     * <p><b>트랜잭션 경계(의도된 순서)</b>: Redis consume(비가역) → DB 커밋 순이다. DB 커밋이 실패하면 토큰은
     * 이미 소비돼 사용자가 링크를 재발급받아야 하지만, 비밀번호도 바뀌지 않았으므로 안전한 실패다. 순서를
     * 뒤집으면(DB 먼저 변경 → 토큰 나중 소비) 동시 요청 2건이 <b>같은 토큰으로 모두 성공</b>하는 리플레이 창이
     * 열려 1회용 보장이 깨진다. 드문 재발급 요구를 감수하고 리플레이를 막는 쪽을 택했다.
     */
    @Transactional
    public PasswordResetResponse reset(PasswordResetRequest request) {
        // consume: 조회·삭제·인덱스 정리가 한 Lua 로 원자적 — 동시 요청에도 단 한 번만 성공한다(1회용 보장).
        Long userId = tokenStore.consume(request.token())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_RESET_TOKEN_INVALID));

        User user = userRepository.findById(userId)
                // 토큰 발급 후 계정이 삭제된 경우 — 사유를 구분해 노출하지 않는다(통일 메시지).
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_RESET_TOKEN_INVALID));
        // 소셜 전용 계정 방어(심층방어) — 1단계가 이미 걸러내지만, 발급 후 계정이 소셜 전용으로 바뀌었거나
        // 다른 경로로 토큰이 생겼을 때도 비밀번호를 심지 않는다. 사유는 노출하지 않는다(통일 메시지).
        if (!user.hasPassword()) {
            throw new BusinessException(ErrorCode.AUTH_RESET_TOKEN_INVALID);
        }
        user.changePassword(passwordEncoder.encode(request.newPassword()));

        log.info("비밀번호 재설정 완료 — userId={}", userId);
        return PasswordResetResponse.done();
    }

    /**
     * 이메일 축 → 전역 상한 순으로 검사한다. 순서가 중요하다: 이메일 축에서 이미 막힌 요청이 전역 카운터를
     * 소모하면, 한 이메일을 두드리는 공격자가 전역 상한까지 밀어 올려 정상 사용자를 막을 수 있다.
     */
    private void enforceRateLimits(String emailHash) {
        AuthProperties.PasswordResetRateLimit limits = authProperties.getPasswordResetRateLimit();

        if (!rateLimiter.tryAcquire(RATE_LIMIT_EMAIL_KEY_PREFIX + emailHash,
                limits.getEmailLimit(), limits.getEmailWindow())) {
            log.warn("비밀번호 재설정 rate-limit 초과(이메일 축) — emailHash={}", emailHash);
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }

        if (!rateLimiter.tryAcquire(RATE_LIMIT_GLOBAL_KEY, limits.getGlobalLimit(), limits.getGlobalWindow())) {
            // ⚠️ 이 WARN 이 유일한 경보 축이다 — 전역 상한에 닿으면 정상 사용자도 429 를 맞는데(IP 축 미사용),
            // 로그가 없으면 아무도 모른 채 재설정 기능이 무증상으로 죽는다. 알람을 붙일 지점.
            log.warn("비밀번호 재설정 전역 상한 도달 — 정상 사용자 요청도 차단될 수 있습니다. limit={}/{} emailHash={}",
                    limits.getGlobalLimit(), limits.getGlobalWindow(), emailHash);
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_REQUESTS);
        }
    }

    /**
     * 토큰 발급(이전 토큰 무효화 포함) + 비동기 발송. 비밀번호 로그인 계정이 존재할 때만 호출된다.
     *
     * <p>발급은 {@link PasswordResetTokenStore#issueAndRotate} <b>한 번</b>으로 끝낸다 — 저장·무효화·인덱스
     * 갱신을 따로 호출하면 그 사이가 열려, 동시 요청 시 나중에 발송된 메일의 링크가 죽는다.
     */
    private void issueAndDispatch(User user, String emailHash) {
        // 저장 키는 sha256(token) — Redis 덤프에 원문이 남지 않는다. 원문은 반환값(=메일)로만 나간다.
        String token = tokenStore.issueAndRotate(user.getId(), authProperties.getPasswordResetTtl());

        mailDispatcher.dispatch(user.getEmail(), resetLink(token), emailHash);
    }

    /**
     * 재설정 링크 조립.
     *
     * <p>⚠️ base 는 <b>설정값만</b> 쓴다({@code FRONTEND_BASE_URL}). 요청의 {@code Host}/
     * {@code X-Forwarded-Host} 나 {@code fromCurrentRequest()} 에서 유도하면, nginx 가 Host 를 그대로
     * 통과시키므로 공격자가 피해자 메일에 <b>공격자 도메인 링크</b>를 심을 수 있다(password-reset poisoning).
     * 그래서 이 클래스는 HttpServletRequest 를 아예 참조하지 않는다.
     */
    private String resetLink(String token) {
        String base = mailProperties.getFrontendBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + RESET_PATH + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }
}
