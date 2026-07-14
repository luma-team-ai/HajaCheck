package com.hajacheck.auth.security;

import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * 소셜 로그인 사용자 로드 — 제공자별 attribute 파싱 후 신규/기존 분기.
 * 신규면 User.createSocialUser 로 가입시키고, 통합 principal {@link LoginUser} 반환.
 *
 * 트랜잭션 전략: super.loadUser(외부 HTTP) 이후 "단일 엔티티 find-or-create" 만 수행하므로
 * 메서드 전체를 감싸는 @Transactional 을 두지 않는다(외부 HTTP 를 트랜잭션에 포함시키지 않기 위함,
 * 또한 self-invocation 으로 무효화되던 기존 @Transactional 제거). 각 JpaRepository 호출이 개별
 * 트랜잭션이라, 동시 가입 경합으로 save 가 실패해도 그 트랜잭션만 롤백되고 재조회는 오염되지 않는다.
 *
 * attribute 파싱·upsert 로직은 {@link #processOAuth2User}로 분리해 단위 테스트 가능하게 함.
 */
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        return processOAuth2User(registrationId, oAuth2User.getAttributes());
    }

    public LoginUser processOAuth2User(String registrationId, Map<String, Object> attributes) {
        OAuth2Attributes parsed = OAuth2Attributes.of(registrationId, attributes);
        User user = findOrRegister(parsed);
        return new LoginUser(user, attributes);
    }

    private User findOrRegister(OAuth2Attributes parsed) {
        return userRepository.findBySocialProviderAndSocialId(parsed.provider(), parsed.socialId())
                .map(this::requireActive)
                .orElseGet(() -> register(parsed));
    }

    /**
     * 정지 계정의 소셜 로그인 우회 차단 — 자체 로그인(LockedException)과 동일한 정지 정책.
     * 필터단 예외라 OAuth2FailureHandler 로 흐른다.
     */
    private User requireActive(User user) {
        if (user.isSuspended()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("account_suspended"), "정지된 계정입니다.");
        }
        return user;
    }

    private User register(OAuth2Attributes parsed) {
        try {
            return userRepository.save(User.createSocialUser(
                    parsed.provider(), parsed.socialId(), parsed.email(), parsed.name()));
        } catch (DataIntegrityViolationException e) {
            // (provider, socialId) 재조회로 원인 구분:
            //  - 있으면 = 동시 가입 경합 → 먼저 저장된 레코드 반환(정지 계정은 차단).
            //  - 없으면 = email 유니크 충돌(다른 계정이 이미 같은 email 보유) → 명확히 실패.
            return userRepository.findBySocialProviderAndSocialId(parsed.provider(), parsed.socialId())
                    .map(this::requireActive)
                    .orElseThrow(() -> new OAuth2AuthenticationException(
                            new OAuth2Error("email_already_registered"),
                            "이미 다른 계정에 등록된 이메일입니다.", e));
        }
    }

    /**
     * 제공자별 raw attribute → 공통 사용자 식별 정보로 정규화.
     * email 미제공/미검증 등은 {@link OAuth2AuthenticationException} 으로 던져 OAuth2FailureHandler 로 흐르게 한다
     * (필터단 예외라 GlobalExceptionHandler 가 잡지 못함 → 500 방지).
     */
    private record OAuth2Attributes(SocialProvider provider, String socialId, String email, String name) {

        @SuppressWarnings("unchecked")
        static OAuth2Attributes of(String registrationId, Map<String, Object> attributes) {
            if ("kakao".equalsIgnoreCase(registrationId)) {
                String socialId = String.valueOf(attributes.get("id"));
                Map<String, Object> account = (Map<String, Object>) attributes.get("kakao_account");
                String email = account == null ? null : (String) account.get("email");
                Object verified = account == null ? null : account.get("is_email_verified");
                // 카카오는 이메일 미동의(null)·미검증이 가능 → 신뢰 불가 시 인증 실패 처리.
                if (email == null || !Boolean.TRUE.equals(verified)) {
                    throw new OAuth2AuthenticationException(
                            new OAuth2Error("invalid_email"), "카카오 이메일이 없거나 검증되지 않았습니다.");
                }
                String name = null;
                Map<String, Object> profile = (Map<String, Object>) account.get("profile");
                if (profile != null) {
                    name = (String) profile.get("nickname");
                }
                return new OAuth2Attributes(SocialProvider.KAKAO, socialId, email,
                        name == null ? "카카오사용자" : name);
            }
            if ("google".equalsIgnoreCase(registrationId)) {
                String socialId = (String) attributes.get("sub");
                String email = (String) attributes.get("email");
                Object verified = attributes.get("email_verified");
                // 카카오와 정책 일치: email 이 없거나 email_verified 미검증이면 신뢰 불가 → 인증 실패.
                if (email == null || !Boolean.TRUE.equals(verified)) {
                    throw new OAuth2AuthenticationException(
                            new OAuth2Error("invalid_email"), "구글 이메일이 없거나 검증되지 않았습니다.");
                }
                String name = (String) attributes.get("name");
                return new OAuth2Attributes(SocialProvider.GOOGLE, socialId, email,
                        name == null ? "구글사용자" : name);
            }
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("unsupported_provider"), "지원하지 않는 소셜 로그인: " + registrationId);
        }
    }
}
