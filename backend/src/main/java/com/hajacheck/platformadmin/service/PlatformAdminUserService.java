package com.hajacheck.platformadmin.service;

import com.hajacheck.admin.dto.AdminUserRoleUpdateResponse;
import com.hajacheck.admin.dto.AdminUserStatsResponse;
import com.hajacheck.admin.dto.AdminUserStatusUpdateResponse;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.platformadmin.dto.PlatformAdminUserCreateRequest;
import com.hajacheck.platformadmin.dto.PlatformAdminUserListResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminUserProjection;
import com.hajacheck.platformadmin.dto.PlatformAdminUserResponse;
import com.hajacheck.platformadmin.repository.PlatformAdminUserRepository;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랫폼 관리자 콘솔 — 사용자 관리(#576). AdminUserService(#405, 회사 관리자 전용)와 동일한
 * 도메인 규칙(배정 가능 역할 화이트리스트, 마지막 ADMIN 보호)을 재사용하되 companyId 스코프를
 * 걷어낸 버전이다 — 기존 AdminUserService는 무수정으로 남긴다(이슈 범위).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformAdminUserService {

    private final PlatformAdminUserRepository platformAdminUserRepository;
    private final CompanyRepository companyRepository;
    private final PasswordEncoder passwordEncoder;

    // AdminUserService.ASSIGNABLE_ROLES와 동일한 화이트리스트 — 플랫폼 관리자도 COUNSELOR 등
    // 이 화면 밖의 Role은 배정할 수 없다.
    private static final Set<Role> ASSIGNABLE_ROLES = EnumSet.of(Role.ADMIN, Role.INSPECTOR, Role.USER);

    public PlatformAdminUserListResponse list(String keyword, Role role, PlanName plan, UserStatus status,
                                               Pageable pageable) {
        String likeKeyword = normalizeKeyword(keyword);

        Page<PlatformAdminUserProjection> page = platformAdminUserRepository.search(
                likeKeyword, role != null, role, status != null, status, plan != null, plan,
                plan == PlanName.FREE, UserPlanStatus.ACTIVE, Role.PLATFORM_ADMIN, pageable);

        List<PlatformAdminUserResponse> content = page.getContent().stream()
                .map(PlatformAdminUserResponse::from)
                .toList();

        return new PlatformAdminUserListResponse(
                content, pageable.getPageNumber(), pageable.getPageSize(), page.getTotalElements(), buildStats());
    }

    @Transactional
    public PlatformAdminUserResponse createUser(PlatformAdminUserCreateRequest request) {
        requireAssignableRole(request.role());
        if (platformAdminUserRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED);
        }

        String companyName = null;
        if (request.companyId() != null) {
            Company company = companyRepository.findById(request.companyId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.COMPANY_NOT_FOUND));
            companyName = company.getName();
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = User.createByAdmin(
                request.email(), request.name(), request.role(), passwordHash, request.companyId());

        try {
            User saved = platformAdminUserRepository.save(user);
            return PlatformAdminUserResponse.from(saved, companyName);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED);
        }
    }

    @Transactional
    public AdminUserRoleUpdateResponse changeRole(Long userId, Role role) {
        requireAssignableRole(role);
        User user = findUser(userId);
        if (user.getRole() == Role.ADMIN && role != Role.ADMIN) {
            requireNotLastCompanyAdmin(user.getCompanyId());
        }
        user.changeRole(role);
        return new AdminUserRoleUpdateResponse(user.getId(), user.getRole());
    }

    @Transactional
    public AdminUserStatusUpdateResponse changeStatus(Long userId, UserStatus status) {
        User user = findUser(userId);
        if (user.getRole() == Role.ADMIN && status == UserStatus.SUSPENDED) {
            requireNotLastCompanyAdmin(user.getCompanyId());
        }
        user.changeStatus(status);
        return new AdminUserStatusUpdateResponse(user.getId(), user.getStatus());
    }

    // 대상 회사의 마지막 ACTIVE ADMIN을 강등/정지하면 그 회사는 자체 관리자 콘솔 접근 수단을
    // 영구히 잃는다(AdminUserService와 동일 취지). 회사 미소속(companyId=null) 대상은 보호 대상
    // 회사 자체가 없으므로 검사하지 않는다.
    private void requireNotLastCompanyAdmin(Long companyId) {
        if (companyId == null) {
            return;
        }
        long remainingActiveAdmins =
                platformAdminUserRepository.countByCompanyIdAndRoleAndStatus(companyId, Role.ADMIN, UserStatus.ACTIVE);
        if (remainingActiveAdmins <= 1) {
            throw new BusinessException(ErrorCode.ADMIN_PROTECTED_ACCOUNT);
        }
    }

    private void requireAssignableRole(Role role) {
        if (!ASSIGNABLE_ROLES.contains(role)) {
            throw new BusinessException(ErrorCode.ADMIN_ROLE_NOT_ASSIGNABLE);
        }
    }

    // PLATFORM_ADMIN 자신은 이 화면의 관리 대상이 아니다(목록에서도 항상 제외) — 그런 id로 직접
    // 요청해도 회사 소속 사용자와 동일하게 "존재하지 않음"으로 응답한다(리소스 존재 여부 열거 방지).
    private User findUser(Long userId) {
        User user = platformAdminUserRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        if (user.getRole() == Role.PLATFORM_ADMIN) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

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

    private AdminUserStatsResponse buildStats() {
        long total = platformAdminUserRepository.countByRoleNot(Role.PLATFORM_ADMIN);
        long active = platformAdminUserRepository.countByStatusAndRoleNot(UserStatus.ACTIVE, Role.PLATFORM_ADMIN);
        long suspended = platformAdminUserRepository.countByStatusAndRoleNot(UserStatus.SUSPENDED, Role.PLATFORM_ADMIN);

        // 롤링 7일 윈도우 — AdminUserService.buildStats와 동일 계약(달력 주 대신 조회 시각 기준).
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime weekAgo = now.minusDays(7);
        LocalDateTime twoWeeksAgo = now.minusDays(14);

        long newThisWeek = platformAdminUserRepository
                .countByCreatedAtGreaterThanEqualAndCreatedAtLessThanAndRoleNot(weekAgo, now, Role.PLATFORM_ADMIN);
        long newLastWeek = platformAdminUserRepository
                .countByCreatedAtGreaterThanEqualAndCreatedAtLessThanAndRoleNot(twoWeeksAgo, weekAgo, Role.PLATFORM_ADMIN);

        double growthRate = calculateGrowthRate(newThisWeek, newLastWeek);

        return new AdminUserStatsResponse(total, active, suspended, newThisWeek, growthRate);
    }

    private double calculateGrowthRate(long newThisWeek, long newLastWeek) {
        if (newLastWeek == 0) {
            return newThisWeek == 0 ? 0 : 100;
        }
        double rate = ((double) (newThisWeek - newLastWeek) / newLastWeek) * 100;
        return Math.round(rate * 10) / 10.0;
    }
}
