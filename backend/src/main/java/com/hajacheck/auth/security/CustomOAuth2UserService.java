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
            //  - 없으면 = 원인별 분기(아래 resolveRegisterFailure).
            return userRepository.findBySocialProviderAndSocialId(parsed.provider(), parsed.socialId())
                    .map(this::requireActive)
                    .orElseThrow(() -> resolveRegisterFailure(parsed, e));
        }
    }

    /**
     * save 실패인데 (provider, socialId) 재조회도 없을 때의 원인 판별.
     *  - 실제(검증된) 이메일: users.email 유니크 충돌 = 다른 계정이 이미 같은 이메일 보유 → email_already_registered.
     *  - placeholder 이메일: socialId 기반 유일이라 이메일 충돌일 수 없음 → 교차 중복 검사 대상에서 제외.
     *    이 경우의 제약 위반은 원인 미상이므로 오탐성 email_already_registered 대신 별도 코드로 노출한다(#199).
     */
    private OAuth2AuthenticationException resolveRegisterFailure(OAuth2Attributes parsed,
                                                                DataIntegrityViolationException e) {
        if (parsed.placeholderEmail()) {
            return new OAuth2AuthenticationException(
                    new OAuth2Error("registration_failed"), "소셜 가입 처리 중 오류가 발생했습니다.", e);
        }
        return new OAuth2AuthenticationException(
                new OAuth2Error("email_already_registered"), "이미 다른 계정에 등록된 이메일입니다.", e);
    }

    /**
     * 제공자별 raw attribute → 공통 사용자 식별 정보로 정규화.
     *
     * 이메일 정책(#199 — 카카오 이메일 동의항목은 비즈니스 앱 전환[사업자등록]+검수가 있어야 켜져 개인 앱은 수집 불가):
     * 이메일을 필수로 받지 않는다. email 이 없거나 미검증(신뢰 불가)이면 인증을 차단하지 않고
     * placeholder 이메일 {@code {provider}_{socialId}@social.local} 로 대체해 가입시킨다(DB 스키마 변경 없이,
     * users.email NOT NULL + UNIQUE 유지). socialId 는 provider 별 유일하므로 placeholder 도 유일 → 충돌 없음.
     * 검증된(is_email_verified/email_verified == true) 이메일만 실제 값으로 저장한다.
     * {@code placeholderEmail} 플래그로 이후 교차 중복 검사 대상에서 placeholder 를 제외한다.
     */
    private record OAuth2Attributes(SocialProvider provider, String socialId, String email, String name,
                                    boolean placeholderEmail) {

        @SuppressWarnings("unchecked")
        static OAuth2Attributes of(String registrationId, Map<String, Object> attributes) {
            if ("kakao".equalsIgnoreCase(registrationId)) {
                String socialId = String.valueOf(attributes.get("id"));
                Map<String, Object> account = (Map<String, Object>) attributes.get("kakao_account");
                String email = account == null ? null : (String) account.get("email");
                Object verified = account == null ? null : account.get("is_email_verified");
                String name = null;
                Map<String, Object> profile =
                        account == null ? null : (Map<String, Object>) account.get("profile");
                if (profile != null) {
                    name = (String) profile.get("nickname");
                }
                return resolveEmail(SocialProvider.KAKAO, socialId, email, verified,
                        name == null ? "카카오사용자" : name);
            }
            if ("google".equalsIgnoreCase(registrationId)) {
                String socialId = (String) attributes.get("sub");
                String email = (String) attributes.get("email");
                Object verified = attributes.get("email_verified");
                String name = (String) attributes.get("name");
                return resolveEmail(SocialProvider.GOOGLE, socialId, email, verified,
                        name == null ? "구글사용자" : name);
            }
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("unsupported_provider"), "지원하지 않는 소셜 로그인: " + registrationId);
        }

        /**
         * placeholder 이메일 도메인 — 변경 금지: 반드시 라우팅 불가한 예약 도메인(RFC 6761 {@code .local})을 유지한다.
         * 실도메인으로 바꾸면 실제 사용자의 검증 이메일과 placeholder 가 겹칠 수 있어 email UNIQUE 충돌 →
         * 서로 다른 사람이 한 계정으로 dedup 되는 교차계정(계정 탈취) 위험이 생긴다(#199).
         */
        private static final String PLACEHOLDER_EMAIL_DOMAIN = "@social.local";

        /**
         * 검증된 이메일만 신뢰. 없거나 미검증이면 placeholder 로 대체(#199).
         *
         * 방어심층(#199 보안): email 필수 가드를 제거해 (socialProvider, socialId) 가 유일한 신원 앵커가 됐으므로,
         * socialId 가 신뢰 불가(null/공백/문자열 "null")면 placeholder 가 {@code {provider}_null@social.local} 로
         * 붕괴해 서로 다른 사용자가 동일 앵커로 dedup·교차 로그인될 수 있다 → 진입부에서 차단한다.
         */
        private static OAuth2Attributes resolveEmail(SocialProvider provider, String socialId,
                                                     String email, Object verified, String name) {
            requireValidSocialId(socialId);
            if (email != null && Boolean.TRUE.equals(verified)) {
                return new OAuth2Attributes(provider, socialId, email, name, false);
            }
            return new OAuth2Attributes(provider, socialId, placeholderEmail(provider, socialId), name, true);
        }

        /**
         * socialId 신원 앵커 유효성 — null·공백·문자열 "null"(kakao id 누락 시 String.valueOf(null))이면 인증 차단.
         */
        private static void requireValidSocialId(String socialId) {
            if (socialId == null || socialId.isBlank() || "null".equalsIgnoreCase(socialId.trim())) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_request"), "소셜 사용자 식별자를 확인할 수 없습니다.");
            }
        }

        private static String placeholderEmail(SocialProvider provider, String socialId) {
            return provider.name().toLowerCase() + "_" + socialId + PLACEHOLDER_EMAIL_DOMAIN;
        }
    }
}
