package com.hajacheck.auth.security;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자체(email/password) 로그인용 UserDetailsService.
 * 미존재·소셜전용(passwordHash=null) 계정은 UsernameNotFoundException,
 * 정지 계정은 LockedException 을 던진다. (둘 다 AuthenticationException →
 * 로그인 실패는 GlobalExceptionHandler 에서 AUTH_INVALID_CREDENTIALS 로 통일 매핑.)
 */
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("인증 실패"));

        // 소셜 전용 계정은 비밀번호 로그인 불가.
        if (user.getPasswordHash() == null) {
            throw new UsernameNotFoundException("인증 실패");
        }
        if (user.isSuspended()) {
            throw new LockedException("계정 정지");
        }
        return new LoginUser(user);
    }
}
