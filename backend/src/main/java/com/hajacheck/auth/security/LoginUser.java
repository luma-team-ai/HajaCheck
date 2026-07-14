package com.hajacheck.auth.security;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * 자체 로그인(UserDetails)과 소셜 로그인(OAuth2User)을 통합하는 단일 principal.
 * 컨트롤러에서 {@code @AuthenticationPrincipal LoginUser} 로 주입 가능.
 *
 * CredentialsContainer 구현: 인증 성공 후 ProviderManager 가 eraseCredentials() 를 호출해
 * password 를 제거 → Redis 세션에 자격증명(passwordHash)이 직렬화되지 않는다.
 */
@Getter
public class LoginUser implements UserDetails, OAuth2User, CredentialsContainer {

    private final Long userId;
    private final String email;
    // eraseCredentials() 로 null 처리해야 하므로 final 이 아니다.
    private String password;
    private final Role role;
    private final boolean suspended;
    private final transient Map<String, Object> attributes;

    /** 자체 로그인용 — attributes 없음. */
    public LoginUser(User user) {
        this(user, Map.of());
    }

    /** 소셜 로그인용 — OAuth2 attributes 포함. */
    public LoginUser(User user, Map<String, Object> attributes) {
        this.userId = user.getId();
        this.email = user.getEmail();
        this.password = user.getPasswordHash();
        this.role = user.getRole();
        this.suspended = user.isSuspended();
        this.attributes = attributes == null ? Map.of() : attributes;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    // ── UserDetails ──
    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !suspended;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    // ── OAuth2User ──
    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public String getName() {
        // OAuth2User.getName() 은 principal 식별자 — userId 를 사용.
        return String.valueOf(userId);
    }

    // ── CredentialsContainer ──
    @Override
    public void eraseCredentials() {
        this.password = null;
    }
}
