package com.hajacheck.auth.security;

import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 소셜 로그인 사용자 로드 — 제공자별 attribute 파싱 후 신규/기존 분기.
 * 신규면 User.createSocialUser 로 가입시키고, 통합 principal {@link LoginUser} 반환.
 * (attribute 파싱·upsert 로직은 {@link #processOAuth2User}로 분리해 단위 테스트 가능하게 함.)
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

    @Transactional
    public LoginUser processOAuth2User(String registrationId, Map<String, Object> attributes) {
        OAuth2Attributes parsed = OAuth2Attributes.of(registrationId, attributes);

        User user = userRepository
                .findBySocialProviderAndSocialId(parsed.provider(), parsed.socialId())
                .orElseGet(() -> userRepository.save(User.createSocialUser(
                        parsed.provider(), parsed.socialId(), parsed.email(), parsed.name())));

        return new LoginUser(user, attributes);
    }

    /**
     * 제공자별 raw attribute → 공통 사용자 식별 정보로 정규화.
     */
    private record OAuth2Attributes(SocialProvider provider, String socialId, String email, String name) {

        @SuppressWarnings("unchecked")
        static OAuth2Attributes of(String registrationId, Map<String, Object> attributes) {
            if ("kakao".equalsIgnoreCase(registrationId)) {
                String socialId = String.valueOf(attributes.get("id"));
                Map<String, Object> account = (Map<String, Object>) attributes.get("kakao_account");
                String email = account == null ? null : (String) account.get("email");
                String name = null;
                if (account != null) {
                    Map<String, Object> profile = (Map<String, Object>) account.get("profile");
                    if (profile != null) {
                        name = (String) profile.get("nickname");
                    }
                }
                return new OAuth2Attributes(SocialProvider.KAKAO, socialId, email,
                        name == null ? "카카오사용자" : name);
            }
            if ("google".equalsIgnoreCase(registrationId)) {
                String socialId = (String) attributes.get("sub");
                String email = (String) attributes.get("email");
                String name = (String) attributes.get("name");
                return new OAuth2Attributes(SocialProvider.GOOGLE, socialId, email,
                        name == null ? "구글사용자" : name);
            }
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("unsupported_provider"), "지원하지 않는 소셜 로그인: " + registrationId);
        }
    }
}
