package com.hajacheck.platformadmin.service;

import com.hajacheck.admin.dto.AdminUserRoleUpdateResponse;
import com.hajacheck.admin.dto.AdminUserStatsResponse;
import com.hajacheck.admin.dto.AdminUserStatusUpdateResponse;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyStatus;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.service.DemoAccountGuard;
import com.hajacheck.counsel.entity.CounselType;
import com.hajacheck.counsel.entity.CounselorSkill;
import com.hajacheck.counsel.repository.CounselorSkillRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.service.QuotaService;
import com.hajacheck.platformadmin.dto.AdminUserSkillUpdateResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminUserCreateRequest;
import com.hajacheck.platformadmin.dto.PlatformAdminUserListResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminUserProjection;
import com.hajacheck.platformadmin.dto.PlatformAdminUserResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminUserSkillsResponse;
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
    private final QuotaService quotaService;
    private final CounselorSkillRepository counselorSkillRepository;
    private final DemoAccountGuard demoAccountGuard;

    // AdminUserService.ASSIGNABLE_ROLES(회사 관리자 전용, ADMIN/INSPECTOR/USER)와 달리 플랫폼
    // 관리자 콘솔은 COUNSELOR(상담사)도 등록/역할변경할 수 있다(#1008). PLATFORM_ADMIN은 여전히
    // 이 화면 밖의 축이라 화이트리스트에 넣지 않는다.
    private static final Set<Role> ASSIGNABLE_ROLES =
            EnumSet.of(Role.ADMIN, Role.INSPECTOR, Role.USER, Role.COUNSELOR);

    // 부여 가능한 상태 — AdminUserService.ASSIGNABLE_STATUSES 와 동일한 화이트리스트(#1492 리뷰 ⓐ).
    // user_status_type 은 ACTIVE/SUSPENDED/WAITING 3개뿐이라 실제 차단 대상은 WAITING 하나다.
    // WAITING 은 "소셜 가입 직후 아직 어느 회사에도 배선되지 않음"을 뜻하는 온보딩 상태이고,
    // 유일한 생성 경로는 User.createSocialUser(company_id 없음)다. 관리자 콘솔이 이미 회사에 소속된
    // 사용자를 WAITING 으로 되돌릴 수 있으면 company_id 가 남은 WAITING 행이 생겨,
    // (a) SessionUserRevalidationFilter 가 보호 리소스를 막는데 초대 코드 redeem 은 통과하는
    //     어정쩡한 상태가 되고,
    // (b) UserRepository#findByIdForUpdate 의 교착 안전성 근거("WAITING ⇒ company_id IS NULL")가
    //     깨진다(#1492).
    // 회사 관리자 콘솔(AdminUserService)은 이미 같은 가드를 갖고 있었는데 플랫폼 관리자 콘솔만
    // 빠져 있어 같은 우회로가 남아 있었다 — 같은 ErrorCode 로 대칭을 맞춘다.
    private static final Set<UserStatus> ASSIGNABLE_STATUSES =
            EnumSet.of(UserStatus.ACTIVE, UserStatus.SUSPENDED);

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
            // 배정 가능 기업 목록(listAssignableCompanies)이 승인(APPROVED)된 기업만 노출하는 것과
            // 정합을 맞춘다(PR머신 리뷰 P2) — 승인 대기/반려 companyId를 직접 크래프팅해도 존재 여부만
            // 확인하고 통과시키면 아직 유효하지 않은 회사에 사용자가 조기 배선된다. 리소스 존재 여부
            // 열거를 피하기 위해 미존재와 동일하게 COMPANY_NOT_FOUND로 응답한다.
            // (#1324) 가입 즉시 자동승인 이후에도 이 가드는 유효하다 — "항상 통과"가 아니다.
            // 여전히 걸리는 집합: ①V38 이전에 반려된 REJECTED 회사 ②국세청 확정 불량(FAILED)이라
            // V38 소급 승인에서 제외돼 PENDING_REVIEW 로 남은 회사. 즉 이 가드가 실제로 막는 대상이
            // "아직 심사 안 끝난 회사"에서 "승인해서는 안 되는 회사"로 바뀌었을 뿐 가치는 그대로다.
            if (company.getStatus() != CompanyStatus.APPROVED) {
                throw new BusinessException(ErrorCode.COMPANY_NOT_FOUND);
            }
            // 좌석 잔여 확인(#872 후속) — 회사를 지정해 등록하는 경로도 그 회사 좌석을 그대로
            // 채우므로, 개인 계정(companyId=null)과 달리 여기서는 검사해야 한다. 그렇지 않으면
            // 기업 관리자용 좌석 강제(AdminUserService.createUser)를 플랫폼 관리자 경로로 우회할 수 있다.
            //
            // PR머신 2차 재검토 P2 — hasAvailableSeat(advisory)+회사 행 잠금 대신, 초대코드 redeem
            // (InviteCodeService#redeem)이 이미 쓰는 QuotaService#reserveSeat를 그대로 재사용한다.
            // reserveSeat는 usage_counters 행을 잠그고 원자적 조건부 UPDATE로 좌석을 예약하므로, 이
            // 경로와 redeem 경로가 같은 잠금 대상을 공유해 서로 직렬화된다(교차 경로 좌석 초과 방지) —
            // 회사 행을 따로 잠글 필요가 없어지고, 자가 프로비저닝(REQUIRES_NEW)과 충돌해 교착을 만들
            // 위험도 함께 사라진다. 예약이 성공하면 usage_counters 미러도 갱신되어 getCurrentPlan의
            // 좌석 표시가 실제와 어긋나는 문제도 해소된다. 활성화(User 저장) 직전에 호출해야 한도 초과
            // 시 등록 자체가 롤백된다(reserveSeat javadoc 계약).
            quotaService.reserveSeat(request.companyId());
            companyName = company.getName();
        }

        String passwordHash = passwordEncoder.encode(request.password());
        User user = User.createByAdmin(
                request.email(), request.name(), request.role(), passwordHash, request.companyId());

        User saved;
        try {
            saved = platformAdminUserRepository.save(user);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.AUTH_EMAIL_DUPLICATED);
        }

        // role=COUNSELOR로 등록하면서 스킬을 함께 지정한 경우에만 배선한다 — 그 외 역할에 skill이
        // 실려 와도(프론트가 안 보내지만 방어적으로) 조용히 무시한다. 저장이 성공한 뒤라 counselor_id
        // FK가 이미 존재하는 상태에서 배정한다.
        if (saved.getRole() == Role.COUNSELOR && request.skill() != null) {
            counselorSkillRepository.save(CounselorSkill.assign(saved.getId(), request.skill()));
        }

        return PlatformAdminUserResponse.from(saved, companyName);
    }

    @Transactional
    public AdminUserRoleUpdateResponse changeRole(Long userId, Role role) {
        requireAssignableRole(role);
        User user = findUser(userId);
        // 데모 계정 자기보호(#1626) — 플랫폼 관리자 콘솔 경로도 회사 관리자 콘솔(AdminUserService)과
        // 동일하게 차단한다. 여기가 열려 있으면 회사 콘솔 가드가 이 경로로 그대로 우회된다.
        demoAccountGuard.requireNotDemoAccount(user.getEmail());
        if (user.getRole() == Role.ADMIN && role != Role.ADMIN) {
            requireNotLastCompanyAdmin(user.getCompanyId());
        }
        user.changeRole(role);
        return new AdminUserRoleUpdateResponse(user.getId(), user.getRole());
    }

    @Transactional
    public AdminUserStatusUpdateResponse changeStatus(Long userId, UserStatus status) {
        requireAssignableStatus(status);
        User user = findUser(userId);
        // 데모 계정 자기보호(#1626) — changeRole 과 동일.
        demoAccountGuard.requireNotDemoAccount(user.getEmail());
        if (user.getRole() == Role.ADMIN && status == UserStatus.SUSPENDED) {
            requireNotLastCompanyAdmin(user.getCompanyId());
        }
        user.changeStatus(status);
        return new AdminUserStatusUpdateResponse(user.getId(), user.getStatus());
    }

    // 스킬 변경 모달이 열릴 때 현재 배정을 채운다(#1001, HAJA-495). COUNSELOR가 아닌 대상을 조회하면
    // "스킬 변경" 메뉴 자체가 상담원 행에만 노출되므로 정상 흐름에서는 도달하지 않지만, 요청을 직접
    // 조작한 경우까지 대비해 changeSkill과 동일한 화이트리스트를 조회에도 강제한다.
    public PlatformAdminUserSkillsResponse getSkills(Long userId) {
        User user = findUser(userId);
        requireCounselor(user);
        List<CounselType> skills = counselorSkillRepository.findCounselTypesByCounselorId(userId);
        return new PlatformAdminUserSkillsResponse(userId, skills);
    }

    // 모달은 라디오 버튼(단일 선택)이라 "저장"은 항상 기존 배정 전체를 새 스킬 하나로 교체한다 —
    // 부분 추가/제거 개념이 없다.
    //
    // 원자성 보호(PR머신 2차 검토 P2): delete-then-insert는 기본 격리수준(READ COMMITTED)에서
    // 원자적이지 않다 — 같은 상담사를 대상으로 한 두 요청이 동시에 실행되면 각자의 DELETE가 상대의
    // 신규 INSERT를 스냅샷상 보지 못해 두 스킬 행이 함께 남을 수 있다(requireNotLastCompanyAdmin과
    // 동일 이유). 삭제·삽입 전에 대상 사용자 행을 PESSIMISTIC_WRITE로 잠가 같은 사용자에 대한 요청을
    // 직렬화한다.
    @Transactional
    public AdminUserSkillUpdateResponse changeSkill(Long userId, CounselType skill) {
        User user = findUser(userId);
        requireCounselor(user);
        platformAdminUserRepository.findByIdForUpdate(userId);
        counselorSkillRepository.deleteByCounselorId(userId);
        counselorSkillRepository.save(CounselorSkill.assign(userId, skill));
        return new AdminUserSkillUpdateResponse(userId, skill);
    }

    private void requireCounselor(User user) {
        if (user.getRole() != Role.COUNSELOR) {
            throw new BusinessException(ErrorCode.ADMIN_SKILL_TARGET_NOT_COUNSELOR);
        }
    }

    // 대상 회사의 마지막 ACTIVE ADMIN을 강등/정지하면 그 회사는 자체 관리자 콘솔 접근 수단을
    // 영구히 잃는다(AdminUserService와 동일 취지). 회사 미소속(companyId=null) 대상은 보호 대상
    // 회사 자체가 없으므로 검사하지 않는다.
    //
    // TOCTOU 방지(PR머신 2차 검토 P2): count-후-쓰기 사이에 잠금이 없으면, 활성 ADMIN이 정확히
    // 2명인 회사에서 서로 다른 ADMIN을 대상으로 한 두 요청이 동시에 count=2>1을 보고 둘 다 통과해
    // 커밋 후 활성 ADMIN이 0명이 될 수 있다. 카운트 전에 회사 행을 PESSIMISTIC_WRITE로 잠가 같은
    // 회사를 대상으로 한 요청을 직렬화한다 — 두 번째 요청은 첫 번째가 커밋한 뒤에야 잠금을 얻고,
    // 그 시점의 최신(감소한) 카운트를 보게 된다.
    private void requireNotLastCompanyAdmin(Long companyId) {
        if (companyId == null) {
            return;
        }
        companyRepository.findByIdForUpdate(companyId);
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

    // AdminUserService.requireAssignableStatus 와 동일 패턴·동일 ErrorCode. 대상 조회보다 *먼저*
    // 검사해 잘못된 상태 요청이 리소스 존재 여부를 탐지하는 수단이 되지 않게 한다.
    private void requireAssignableStatus(UserStatus status) {
        if (!ASSIGNABLE_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.ADMIN_STATUS_NOT_ASSIGNABLE);
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
