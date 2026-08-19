package com.hajacheck.admin.service;

import com.hajacheck.admin.dto.AdminUserCreateRequest;
import com.hajacheck.admin.dto.AdminUserListResponse;
import com.hajacheck.admin.dto.AdminUserProjection;
import com.hajacheck.admin.dto.AdminUserResponse;
import com.hajacheck.admin.dto.AdminUserRoleUpdateResponse;
import com.hajacheck.admin.dto.AdminUserStatsResponse;
import com.hajacheck.admin.dto.AdminUserStatusUpdateResponse;
import com.hajacheck.admin.repository.AdminUserRepository;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.service.DemoAccountGuard;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.service.QuotaService;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminUserService {

    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final QuotaService quotaService;
    private final DemoAccountGuard demoAccountGuard;
    private final CompanyMembershipRepository companyMembershipRepository;

    // 관리자 콘솔이 프론트에 노출하는 배정 가능 역할(ROLE_CHANGE_OPTIONS: USER/INSPECTOR/ADMIN)과 동일한
    // 화이트리스트 — COUNSELOR 등 이 화면 밖의 Role은 요청을 크래프팅해도 서버가 거부한다(리뷰 P2).
    private static final Set<Role> ASSIGNABLE_ROLES = EnumSet.of(Role.ADMIN, Role.INSPECTOR, Role.USER);

    // 관리자 콘솔이 프론트에 노출하는 배정 가능 상태(STATUS_CHANGE_OPTIONS: ACTIVE/SUSPENDED)와 동일한
    // 화이트리스트 — WAITING(#794)은 소셜 가입 전용 자동 상태라 관리자가 임의로 부여할 수 없다.
    // 허용하면 companyId는 그대로인 채 status만 WAITING이 되어 "WAITING=companyId 없음" 불변식이 깨진다.
    private static final Set<UserStatus> ASSIGNABLE_STATUSES = EnumSet.of(UserStatus.ACTIVE, UserStatus.SUSPENDED);

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
    public AdminUserResponse createUser(AdminUserCreateRequest request, Long companyId, Long requestingUserId) {
        requireCompanyId(companyId);
        requireAssignableRole(request.role());
        // 좌석 잔여 확인(#872 후속) — 초대 코드 발급과 동일하게, 관리자가 직접 등록하는 경로도
        // 좌석이 가득 찬 회사는 새 사용자를 만들지 못하게 막는다(그렇지 않으면 초대 코드 좌석
        // 강제가 이 경로로 그대로 우회된다).
        //
        // PR머신 2차 재검토 P2 — hasAvailableSeat(advisory)+회사 행 잠금 대신, 초대코드 redeem
        // (InviteCodeService#redeem)이 이미 쓰는 QuotaService#reserveSeat를 그대로 재사용한다.
        // reserveSeat는 usage_counters 행을 잠그고 원자적 조건부 UPDATE로 좌석을 예약하므로,
        // 이 경로와 redeem 경로가 같은 잠금 대상을 공유해 서로 직렬화된다(교차 경로 좌석 초과 방지) —
        // 회사 행을 따로 잠글 필요가 없어지고, 그 잠금이 자가 프로비저닝(REQUIRES_NEW)과 충돌해
        // 교착을 만들 위험도 함께 사라진다. 예약이 성공하면 usage_counters 미러도 함께 갱신되어
        // AdminPlanService#getCurrentPlan의 좌석 표시가 실제와 어긋나는 문제(PR머신 P2)도 해소된다.
        // 활성화(User 저장) 직전에 호출해야 한도 초과 시 등록 자체가 롤백된다(reserveSeat javadoc 계약).
        quotaService.reserveSeat(companyId);
        // 선검사 — 명확한 중복은 저장 전에 조기 차단(CompanySignupService와 동일 패턴).
        if (adminUserRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED);
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = User.createByAdmin(request.email(), request.name(), request.role(), passwordHash, companyId);

        User saved;
        try {
            saved = adminUserRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            // 선검사와 저장 사이의 경합(동시 등록) — unique(email) 위반.
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED);
        }

        grantEffectiveMembership(companyId, saved.getId(), requestingUserId);
        return AdminUserResponse.from(saved);
    }

    /**
     * 관리자 콘솔로 직접 등록한 계정에 회사 스코프 접근에 필요한 유효 멤버십을 발급한다(#1433).
     *
     * <p><b>왜 필요한가</b>: {@code User.createByAdmin}이 채우는 {@code users.company_id}는 조회 편의
     * 포인터일 뿐이고, 실제 인가 판정은 {@code CompanyScopeGuard.requireEffectiveMembership} →
     * {@code CompanyMembershipRepository.existsEffectiveApprovedMembership}가 {@code company_memberships}
     * 행을 기준으로 한다. 이 행이 없으면 나머지 조건(user ACTIVE · company APPROVED · verification
     * VERIFIED)을 모두 만족해도 <b>회사 스코프 API가 전부 403</b>이 된다 — 관리자 콘솔로 만든 계정이
     * 지도·보고서·통계를 하나도 못 쓰던 실제 장애의 원인이었다
     * ({@code InviteCodeService.grantEffectiveMembership} #1474와 같은 유형).
     *
     * <p><b>같은 트랜잭션이어야 하는 이유</b>: 좌석 예약({@code reserveSeat})·User 저장과 원자적으로
     * 묶여야 한다. 분리하면 "좌석은 소모되고 계정도 생겼는데 멤버십이 없어 403" 같은 부분 성공 상태가
     * 남는다. 이 메서드는 {@code createUser}의 트랜잭션 안에서만 호출된다.
     *
     * <p><b>DataIntegrityViolation 처리 범위 밖에 두는 이유</b>: 위 try 블록은 unique(email) 위반만을
     * AUTH_EMAIL_DUPLICATED로 번역하는 자리다. 여기서 저장하는 행의 키
     * {@code uk_company_memberships_company_user}는 <b>방금 생성된 신규 user_id</b>를 쓰므로 중복이
     * 구조상 불가능하다. 두 저장을 같은 catch로 묶으면 성격이 다른 제약 위반이 "이메일 중복"으로
     * 뭉개져 원인을 감춘다.
     *
     * <p><b>교훈(#1368·#1474와 동일 유형)</b>: 인가 조건은 "검사하는 코드"와 "충족시키는 코드"가 쌍으로
     * 존재해야 한다. 사용자를 만드는 경로를 추가할 때는 "누가 이 조건을 만들어 주는가"를 반드시 확인한다.
     */
    private void grantEffectiveMembership(Long companyId, Long userId, Long requestingUserId) {
        // invitedBy = 요청 관리자 — 이 경로는 승인 주체가 곧 요청 관리자라 감사 추적이 가능하다
        // (초대 코드 redeem은 발급자를 알 수 없어 null. CompanyMembership.approvedMember javadoc 참고).
        companyMembershipRepository.save(
                CompanyMembership.approvedMember(companyId, userId, requestingUserId));
    }

    @Transactional
    public AdminUserRoleUpdateResponse changeRole(Long userId, Role role, Long companyId, Long requestingUserId) {
        requireAssignableRole(role);
        User user = findUser(userId, companyId);
        // 데모 계정 자기보호(#1626) — 데모 세션(기업 ADMIN)이 콘솔에서 데모 계정 자신을 강등하면
        // 다음 방문자의 원클릭 로그인 권한이 깨진다. requireNotLastOrSelfAdmin(마지막 관리자 보호)과
        // 별개 축의 보호라 나란히 얹는다(가드 근거는 DemoAccountGuard javadoc).
        demoAccountGuard.requireNotDemoAccount(user.getEmail());
        if (user.getRole() == Role.ADMIN && role != Role.ADMIN) {
            requireNotLastOrSelfAdmin(userId, companyId, requestingUserId);
        }
        user.changeRole(role);
        return new AdminUserRoleUpdateResponse(user.getId(), user.getRole());
    }

    @Transactional
    public AdminUserStatusUpdateResponse changeStatus(Long userId, UserStatus status, Long companyId,
                                                        Long requestingUserId) {
        requireAssignableStatus(status);
        User user = findUser(userId, companyId);
        // 데모 계정 자기보호(#1626) — 정지되면 데모 로그인(CustomUserDetailsService의 정지 차단)이 깨진다.
        demoAccountGuard.requireNotDemoAccount(user.getEmail());
        if (user.getRole() == Role.ADMIN && status == UserStatus.SUSPENDED) {
            requireNotLastOrSelfAdmin(userId, companyId, requestingUserId);
        }
        user.changeStatus(status);
        return new AdminUserStatusUpdateResponse(user.getId(), user.getStatus());
    }

    // 자기 자신의 ADMIN 권한을 스스로 내려놓거나(회사가 ADMIN 콘솔 접근 수단을 즉시 잃음), 회사에 남은
    // 마지막 ACTIVE ADMIN을 강등/정지하려는 시도를 막는다 — 둘 다 되돌릴 수 없는 회사 잠금으로 이어진다(리뷰 P2).
    private void requireNotLastOrSelfAdmin(Long targetUserId, Long companyId, Long requestingUserId) {
        boolean isSelf = targetUserId.equals(requestingUserId);
        long remainingActiveAdmins =
                adminUserRepository.countByCompanyIdAndRoleAndStatus(companyId, Role.ADMIN, UserStatus.ACTIVE);
        if (isSelf || remainingActiveAdmins <= 1) {
            throw new BusinessException(ErrorCode.ADMIN_PROTECTED_ACCOUNT);
        }
    }

    private void requireAssignableRole(Role role) {
        if (!ASSIGNABLE_ROLES.contains(role)) {
            throw new BusinessException(ErrorCode.ADMIN_ROLE_NOT_ASSIGNABLE);
        }
    }

    private void requireAssignableStatus(UserStatus status) {
        if (!ASSIGNABLE_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.ADMIN_STATUS_NOT_ASSIGNABLE);
        }
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

        long newThisWeek = adminUserRepository
                .countByCompanyIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(companyId, weekAgo, now);
        long newLastWeek = adminUserRepository
                .countByCompanyIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(companyId, twoWeeksAgo, weekAgo);

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
