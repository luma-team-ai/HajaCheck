package com.hajacheck.auth.service;

import com.hajacheck.auth.dto.UserResponse;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인증 부가 로직 — 로그인 시각 기록·내 정보 조회.
 * (인증 자체는 AuthenticationManager/OAuth2 필터가 수행, 세션 저장은 컨트롤러.)
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;

    /**
     * 로그인 성공 후 lastLoginAt 갱신(dirty checking) 및 사용자 응답 반환.
     */
    @Transactional
    public UserResponse recordLogin(Long userId) {
        User user = findUser(userId);
        user.updateLastLogin(Instant.now());
        return UserResponse.from(user);
    }

    public UserResponse getMe(Long userId) {
        return UserResponse.from(findUser(userId));
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다."));
    }
}
