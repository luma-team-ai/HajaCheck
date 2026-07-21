package com.hajacheck.admin.service;

import com.hajacheck.admin.dto.AdminUserCreateRequest;
import com.hajacheck.admin.dto.AdminUserListResponse;
import com.hajacheck.admin.dto.AdminUserProjection;
import com.hajacheck.admin.dto.AdminUserResponse;
import com.hajacheck.admin.dto.AdminUserRoleUpdateResponse;
import com.hajacheck.admin.dto.AdminUserStatsResponse;
import com.hajacheck.admin.dto.AdminUserStatusUpdateResponse;
import com.hajacheck.admin.repository.AdminUserRepository;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlanStatus;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminUserListResponse list(Long companyId, String keyword, Role role, PlanName plan, UserStatus status,
                                       Pageable pageable) {
        requireCompanyId(companyId);
        String likeKeyword = normalizeKeyword(keyword);

        Page<AdminUserProjection> page = adminUserRepository.search(
                companyId, likeKeyword, role != null, role, status != null, status, plan != null, plan,
                plan == PlanName.FREE, UserPlanStatus.ACTIVE, pageable);

        List<AdminUserResponse> content = page.getContent().stream()
                .map(AdminUserResponse::from)
                .toList();

        return new AdminUserListResponse(
                content, pageable.getPageNumber(), pageable.getPageSize(), page.getTotalElements(),
                buildStats(companyId));
    }

    @Transactional
    public AdminUserResponse createUser(AdminUserCreateRequest request, Long companyId) {
        requireCompanyId(companyId);
        // 선검사 — 명확한 중복은 저장 전에 조기 차단(CompanySignupService와 동일 패턴).
        if (adminUserRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = User.createByAdmin(request.email(), request.name(), request.role(), passwordHash, companyId);

        try {
            User saved = adminUserRepository.save(user);
            return AdminUserResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 선검사와 저장 사이의 경합(동시 등록) — unique(email) 위반.
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED);
        }
    }

    @Transactional
    public AdminUserRoleUpdateResponse changeRole(Long userId, Role role, Long companyId) {
        User user = findUser(userId, companyId);
        user.changeRole(role);
        return new AdminUserRoleUpdateResponse(user.getId(), user.getRole());
    }

    @Transactional
    public AdminUserStatusUpdateResponse changeStatus(Long userId, UserStatus status, Long companyId) {
        User user = findUser(userId, companyId);
        user.changeStatus(status);
        return new AdminUserStatusUpdateResponse(user.getId(), user.getStatus());
    }

    // 다른 회사 소속 userId를 넘겨도 "존재하지 않음"과 동일하게 응답한다 — 리소스 존재 여부 열거
    // 방지(FacilityService 등 기존 cross-owner 패턴과 동일, AuthService.validateAssignableInspector 주석 참고).
    private User findUser(Long userId, Long companyId) {
        requireCompanyId(companyId);
        return adminUserRepository.findByIdAndCompanyId(userId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    // 이 화면은 기업 관리자 전용이라 companyId 없는 관리자(개인 회원 등)는 접근 대상이 아니다 —
    // 플랫폼 관리자용 전사 조회는 별도 화면/엔드포인트로 만들 예정(사용자 지시).
    private void requireCompanyId(Long companyId) {
        if (companyId == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
    }

    // 사용자가 검색어에 SQL LIKE 와일드카드(%, _)를 리터럴로 입력해도(예: 이메일 로컬파트에 '_'가
    // 있는 사용자를 검색) 와일드카드로 해석되지 않도록 이스케이프한다 — 백슬래시부터 먼저 이스케이프해야
    // 뒤이어 삽입하는 이스케이프 문자와 충돌하지 않는다. 리포지토리 쿼리의 `escape '\'`와 짝을 이룬다.
    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String escaped = keyword.trim().toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private AdminUserStatsResponse buildStats(Long companyId) {
        long total = adminUserRepository.countByCompanyId(companyId);
        long active = adminUserRepository.countByCompanyIdAndStatus(companyId, UserStatus.ACTIVE);
        long suspended = adminUserRepository.countByCompanyIdAndStatus(companyId, UserStatus.SUSPENDED);

        // 주간 신규가입 비교는 달력 주(월요일 시작) 대신 지금 시각 기준 롤링 7일 윈도우를 쓴다 —
        // 어느 요일에 조회하든 정의가 흔들리지 않는 결정적 집계를 위해서다.
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime weekAgo = now.minusDays(7);
        LocalDateTime twoWeeksAgo = now.minusDays(14);

        long newThisWeek = adminUserRepository.countByCompanyIdAndCreatedAtBetween(companyId, weekAgo, now);
        long newLastWeek = adminUserRepository.countByCompanyIdAndCreatedAtBetween(companyId, twoWeeksAgo, weekAgo);

        double growthRate = calculateGrowthRate(newThisWeek, newLastWeek);

        return new AdminUserStatsResponse(total, active, suspended, newThisWeek, growthRate);
    }

    // 직전 주 신규가입이 0건이면 나눗셈이 불가능하다 — 이번 주도 0건이면 변화없음(0%), 1건이라도
    // 있으면 비교 기준이 없는 신규 증가이므로 100%로 표현한다(계약: 소수 없는 이산 정책).
    private double calculateGrowthRate(long newThisWeek, long newLastWeek) {
        if (newLastWeek == 0) {
            return newThisWeek == 0 ? 0 : 100;
        }
        double rate = ((double) (newThisWeek - newLastWeek) / newLastWeek) * 100;
        return Math.round(rate * 10) / 10.0;
    }
}
