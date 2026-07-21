package com.hajacheck.auth.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.membership.service.PlanProvisioningService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 신규 소셜 가입 User 저장 + FREE 플랜 배정(#517) 원자 저장 전담 — 별도 빈으로 분리해 self-invocation 회피
 * (CompanyAccountWriter 와 동일 패턴).
 *
 * <p>{@link com.hajacheck.auth.security.CustomOAuth2UserService} 는 의도적으로 클래스 레벨
 * {@code @Transactional} 을 두지 않는다(외부 HTTP 를 트랜잭션 밖에 두기 위함) — 신규 가입 시에만 이 빈을 통해
 * "유저 저장 + FREE 플랜 배정"을 하나의 짧은 트랜잭션으로 묶는다. save 가 unique 위반으로 실패하면 플랜 배정
 * 이전에 트랜잭션 전체가 롤백되므로 호출부의 DataIntegrityViolationException 처리(동시 가입 경합)는 그대로 유효하다.
 */
@Component
@RequiredArgsConstructor
public class SocialAccountWriter {

    private final UserRepository userRepository;
    private final PlanProvisioningService planProvisioningService;

    @Transactional
    public User registerWithFreePlan(User newUser) {
        User saved = userRepository.save(newUser);
        planProvisioningService.ensureFreePlanForUser(saved.getId());
        return saved;
    }
}
