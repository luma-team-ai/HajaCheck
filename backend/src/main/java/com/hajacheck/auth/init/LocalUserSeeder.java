package com.hajacheck.auth.init;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 개발 전용 시드 유저 — 전역 인증 강제(anyRequest().authenticated()) 이후
 * 기능별 풀스택 개발자가 로컬에서 세션 없이 막히지 않도록 자체(email/password) 로그인 가능한
 * ADMIN 계정을 기동 시 자동 생성한다.
 *
 * <p>{@code @Profile("local")} 로 제한 — docker/prod 프로파일에서는 절대 동작하지 않는다.
 * 멱등(이미 있으면 스킵)이라 반복 기동해도 중복 생성되지 않는다.
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalUserSeeder implements ApplicationRunner {

    private static final String SEED_EMAIL = "dev@hajacheck.local";
    private static final String SEED_PASSWORD = "dev1234";
    private static final String SEED_NAME = "로컬개발관리자";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.findByEmail(SEED_EMAIL).isPresent()) {
            log.info("로컬 시드 유저 이미 존재 — 생성 스킵 (email={})", SEED_EMAIL);
            return;
        }

        User seedUser = User.builder()
                .email(SEED_EMAIL)
                .name(SEED_NAME)
                .role(Role.ADMIN)
                .passwordHash(passwordEncoder.encode(SEED_PASSWORD))
                .status(UserStatus.ACTIVE)
                .build();
        userRepository.save(seedUser);

        // 더미 자격증명(로컬 전용, 실서비스 미노출) — 로그 평문 출력 OK.
        log.info("로컬 시드 유저: {} / {}", SEED_EMAIL, SEED_PASSWORD);
    }
}
