package com.hajacheck.admin.service;

import com.hajacheck.admin.dto.AdminPlanChangePreviewResponse;
import com.hajacheck.admin.dto.AdminPlanCatalogResponse;
import com.hajacheck.admin.dto.AdminPlanHistoryEntry;
import com.hajacheck.admin.dto.AdminPlanHistoryResponse;
import com.hajacheck.admin.dto.AdminPlanQuotaMember;
import com.hajacheck.admin.dto.AdminPlanQuotaResponse;
import com.hajacheck.admin.dto.AdminPlanQuotaStats;
import com.hajacheck.admin.dto.AdminPlanResponse;
import com.hajacheck.admin.dto.AdminScheduledPlanChangeResponse;
import com.hajacheck.admin.repository.AdminPlanRepository;
import com.hajacheck.admin.repository.AdminUserRepository;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.dto.DowngradeOverflow;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.ScheduledPlanChange;
import com.hajacheck.membership.entity.ScheduledPlanChangeStatus;
import com.hajacheck.membership.entity.UsageCounter;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.repository.ScheduledPlanChangeRepository;
import com.hajacheck.membership.repository.UsageCounterRepository;
import com.hajacheck.membership.service.PaymentGraceService;
import com.hajacheck.membership.service.PlanDowngradeService;
import com.hajacheck.membership.service.PlanTransitionService;
import com.hajacheck.membership.service.ScheduledPlanChangeCanceller;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 콘솔 — 플랜·쿼터 관리(FR-8-A, #507 / HAJA-258, frontend #508 대응). 기업 관리자(ADMIN)가 자기
 * 회사(company_id)의 요금제를 조회·변경하고 이번 달 사용량(월 분석 장수 등)을 확인한다.
 *
 * <p><b>인가</b>: ADMIN role 자체는 SecurityConfig 의 URL 매처("/api/admin/**" → hasRole(ADMIN))가 필터
 * 단계에서 강제한다(프론트 가드는 UX 용, 백엔드가 최종 방어선). 이 서비스는 그 위에 <b>회사 스코프</b>를 얹는다:
 * companyId 가 없으면(개인 회원 등) FORBIDDEN. (#887) 원래는 그 회사에 유효한 승인 {@code CompanyMembership}
 * 이 없으면(§2.6 "미승인 멤버십은 상속 대상 아님") 추가로 PLAN_NOT_FOUND 를 던졌으나, 당시 가입 경로
 * ({@code CompanyAccountWriter.createAccount()})가 멤버십 행을 만들지 않아 모든 기업 관리자가 이 화면에서
 * 그 예외를 그대로 맞는 결손이 있었다 — 정식 승인 플로우 배선(#363)까지는 users.company_id 만으로 회사
 * 스코프를 인정하는 방어적 완화를 적용한다({@link #resolveInheritedCompanyId} 참고).
 *
 * <p>(#1324 이후) 가입 경로는 이제 오너 APPROVED 멤버십을 발급하고 기존 회사도 V37 로 소급 발급됐다 —
 * 완화의 원인은 해소됐지만 게이트 복원 자체는 #363 범위로 남긴다(여기서 조이면 멤버십 없는 잔여 계정이
 * 즉시 404 가 되므로 별도 사이클에서 실측 후 처리).
 *
 * <p><b>플랜 변경 이력</b>: table_design.md §user_plans 가 규정한 대로 "기존 ACTIVE 를 EXPIRED 로 내리고 신규를
 * ACTIVE 로 올리는" 단일 트랜잭션 전이로 처리한다 — user_plans 행 자체가 "언제·어느 요금제에서·어느 요금제로"의
 * 변경 이력이 되며 {@code getHistory} 로 조회한다. (변경 주체 user_id 까지 남기려면 별도 audit 컬럼/테이블이
 * 필요하며, 이는 canonical DDL + 증분 마이그레이션 + 스키마 패리티 검증을 수반하는 스키마 변경이라 별도 과제.)
 *
 * <p>사용량(usage_counters)은 <b>읽기 전용</b>으로만 다룬다 — 카운터 증가는 QuotaInterceptor 의 원자적 조건부
 * UPDATE 책임이며(#337) 여기서 read-modify-write 로 되돌리지 않는다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AdminPlanService {

    private final AdminPlanRepository adminPlanRepository;
    private final AdminUserRepository adminUserRepository;
    private final PlanRepository planRepository;
    private final UsageCounterRepository usageCounterRepository;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final MediaRepository mediaRepository;
    private final PlanDowngradeService planDowngradeService;
    private final PlanTransitionService planTransitionService;
    private final ScheduledPlanChangeRepository scheduledPlanChangeRepository;
    private final ScheduledPlanChangeCanceller scheduledPlanChangeCanceller;
    // 미결제 유예(#1177) 판정 단일 소스 — 예약 생성 차단·미리보기 한도 기준을 실행 경로와 일치시킨다.
    private final PaymentGraceService paymentGraceService;

    /** 제공 요금제 카탈로그(변경 선택지) — 회사 스코프와 무관한 참조 데이터라 ADMIN 이면 조회 가능. */
    public AdminPlanCatalogResponse getPlanCatalog() {
        List<Plan> plans = planRepository.findAll();
        plans.sort(Comparator.comparing(Plan::getId));
        return AdminPlanCatalogResponse.from(plans);
    }

    /** 현재 회사 구독 + 이번 달 사용량 조회. */
    public AdminPlanResponse getCurrentPlan(Long adminUserId) {
        Long companyId = resolveInheritedCompanyId(adminUserId);
        UserPlan userPlan = resolveCurrentCompanyPlan(companyId);
        Plan plan = findPlan(userPlan.getPlanId());
        LocalDate period = currentPeriod();
        UsageCounter usage = usageCounterRepository
                .findByUserPlanIdAndPeriod(userPlan.getId(), period)
                .orElse(null);
        // ⚠️ 한도·기능은 "실제로 적용 중인" 요금제 기준이다(#1177) — 유예 중이면 FREE. 하향을 신청한
        // 바로 그 화면이라, 구독 요금제 기준으로 내보내면 owner 가 좌석이 정지된 화면에서 "좌석 5"를 본다.
        return AdminPlanResponse.from(userPlan, plan, usage, period,
                findPendingScheduledChange(userPlan.getId()),
                paymentGraceService.resolveEffectivePlan(userPlan, plan));
    }

    /**
     * 회사 구독의 요금제 변경 — 기존 ACTIVE(또는 UPGRADE_REQUESTED) 구독을 EXPIRED 로 내리고 새 ACTIVE 구독을
     * 발급한다(단일 트랜잭션). 대상 요금제가 현재와 같고 이미 ACTIVE 면 멱등 no-op(200).
     *
     * <p><b>인가</b>: 회사 플랜을 즉시 발급(플랫폼 승인 없이)하는 동작이라, {@link
     * com.hajacheck.membership.service.MembershipService#requestUpgrade} 와 동일하게 <b>회사 소유자(owner)</b>
     * 로 한정한다 — 승인된 멤버십만으로는 허용하지 않는다(그 외 ADMIN 멤버는 요청/조회만 가능).
     *
     * <p><b>⚠️ 이 화면은 하향·FREE 전환 전용이다(#988).</b> 실결제 도입 전에는 이 경로가 상향까지 처리했지만,
     * 그대로 두면 <b>결제를 통째로 우회하는 무결제 승격 경로</b>가 된다 — 결제 주체(구독 소유자)와 이 API 의
     * 인가 주체({@link #requireCompanyOwner})가 <b>같은 사람</b>이고, 기업 owner 는 가입 시 ADMIN 이 자동
     * 부여되므로(#636) 결제 대상자 전원이 이 우회로를 갖는다. 따라서 상향
     * (현재 {@code price_monthly} &lt; 대상 {@code price_monthly})은
     * {@link ErrorCode#PLAN_UPGRADE_REQUIRES_PAYMENT} 로 거절하고, 상향은 결제 경로
     * ({@code POST /api/me/plan/orders} → {@code /api/me/payments/confirm})로만 처리한다.
     *
     * @param keepUserIds 하향으로 좌석이 넘칠 때 관리자가 직접 유지할 구성원(#890 Phase 2). 비어 있으면
     *                    (null 포함) 기존 동작(id 오름차순 자동 선정) — 검증·정합 보장은
     *                    {@code PlanDowngradeService#preview(Long, Plan, Plan, List)} javadoc 참고.
     */
    @Transactional
    public AdminPlanResponse changePlan(
            Long adminUserId, PlanName targetPlanName, boolean overflowConfirmed, List<Long> keepUserIds) {
        Long companyId = resolveInheritedCompanyId(adminUserId);
        requireCompanyOwner(companyId, adminUserId);
        UserPlan current = resolveCurrentCompanyPlan(companyId);
        Plan targetPlan = planRepository.findByName(targetPlanName)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));

        // 대상이 현재와 동일 + 이미 ACTIVE → 변경 없음(불필요한 이력 행/한도 리셋 방지).
        if (current.getStatus() == UserPlanStatus.ACTIVE
                && current.getPlanId().equals(targetPlan.getId())) {
            return buildResponseWithUsage(current, targetPlan);
        }

        // 현재 플랜은 "상향/하향" 판정에 쓰이므로 멱등 조기반환 뒤에 조회한다(재검토 P3).
        Plan currentPlan = findPlan(current.getPlanId());

        // 무결제 승격 차단(#988) — 쓰기가 시작되기 전에, 하향 확인 게이트보다도 먼저 판정한다.
        requireNotUpgrade(currentPlan, targetPlan);

        // ⚠️ preview 는 여기서 "정확히 한 번만" 호출하고 그 결과를 아래 confirmOverflow 판정과
        // applyOverflow 양쪽에 그대로 재사용한다(재검토 F-7/F-9). overflowConfirmed=true 로 확인을
        // 생략한 요청이라도 검증(스코프·좌석 한도·ACTIVE ADMIN)이 여기서 항상 먼저 끝나 있어야
        // 아래 만료(expire)/신규발급(saveAndFlush) 이후로 검증이 밀리지 않는다 — 확인 없는 요청에서
        // 플랜만 바뀌고 정지는 안 되는 중간 상태가 절대 남으면 안 되기 때문이다(트랜잭션 롤백에
        // 기대지 않고 호출 순서로 보장한다).
        DowngradeOverflow overflow = planDowngradeService.preview(companyId, currentPlan, targetPlan, keepUserIds);
        if (!overflowConfirmed && overflow.exists()) {
            throw new BusinessException(ErrorCode.PLAN_DOWNGRADE_CONFIRMATION_REQUIRED);
        }

        // 즉시 변경이 시작됐으므로 이 구독에 걸린 하향 예약(#1105)은 의미를 잃는다 — 남겨 두면 한 달 뒤
        // 스케줄러가 "이미 반영된 하향"을 한 번 더 실행하려 들고, 그 예약은 옛 user_plan_id 에 매달린 채
        // PENDING 으로 남아 owner 가 취소할 수도 없는 유령이 된다.
        //
        // ⚠️ 이 호출은 반드시 아래 expire()/applyOverflow() <b>이전</b>이어야 한다(리뷰 P2-1). 예약 실행
        // 배치는 scheduled_plan_changes → user_plans → users 순으로 잠그는데, 여기서 순서를 뒤집으면
        // (user_plans → users → scheduled_plan_changes) 사용자 요청과 배치 사이에 ABBA 교착이 생긴다 —
        // lock_timeout 3000ms 는 PostgreSQL 기본 deadlock_timeout(1s)보다 길어 방어가 되지 않는다.
        // 취소는 아래 신규 발급 결과와 무관하고 실패 시 트랜잭션 전체가 롤백되므로 앞으로 옮겨도 부작용이
        // 없다. PlanTransitionService#transitionTo 도 같은 위치에서 호출한다.
        scheduledPlanChangeCanceller.cancelOnTransition(current.getId(), "구독이 즉시 변경돼 예약이 무효화됨");

        // 기존 구독 만료 후 신규 ACTIVE 발급 — 부분 UQ(uq_user_plans_active_company: ACTIVE 최대 1건)를
        // 만족하도록 만료 UPDATE 를 먼저 flush 한 뒤 INSERT 한다.
        current.expire();
        adminPlanRepository.saveAndFlush(current);

        UserPlan renewed = UserPlan.forCompany(companyId, targetPlan.getId());
        // 결제 없는 플랜 변경이므로 결제일이 밀리지 않도록 기존 결제 주기를 그대로 승계한다(#1104) —
        // 결제 승인 전이(PlanTransitionService#transitionTo)의 리셋과 반대 규칙. 단, 대상이 FREE(하향)
        // 면 currentPeriodEnd 는 승계하지 않고 NULL(무기한)로 둔다(리뷰 P1 — requireNotUpgrade 와 동일하게
        // plans.price_monthly 기준으로 유료 여부를 판정, priceOrZero 헬퍼를 그대로 재사용해 두 판정이
        // 어긋나지 않도록 한다).
        renewed.carryOverBillingPeriod(current, priceOrZero(targetPlan).signum() > 0);
        UserPlan saved;
        try {
            // try 범위를 이 한 줄로 좁힌다(리뷰 P3) — 아래 좌석 정지 flush 에서 나온 무결성 위반까지
            // "이미 활성 구독 존재"로 오분류하지 않기 위해서다.
            saved = adminPlanRepository.saveAndFlush(renewed);
        } catch (DataIntegrityViolationException e) {
            // 동시 플랜 변경 경합 — 다른 트랜잭션이 이미 새 ACTIVE 를 만들어 부분 UQ 위반.
            throw new BusinessException(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT);
        }

        // 당월 사용량 이월(#851) — 신규 구독 발급과 같은 트랜잭션에서. 이월하지 않으면 플랜을 바꾸는 것만으로
        // usage_counters 행이 새로 열려 월 분석 한도가 0 으로 리셋되고, 한도 강제(#843)가 왕복 변경 한 번에
        // 무력화된다. 결제 경로(PlanTransitionService#transitionTo)와 반드시 같은 규칙을 적용해야 한다 —
        // 한쪽만 이월하면 "관리자 콘솔로 바꾸면 한도가 리셋된다"는 우회로가 남는다.
        planTransitionService.carryOverUsage(current.getId(), saved.getId());

        // 초과 좌석 정지는 신규 구독 발급과 같은 트랜잭션에서 — 플랜만 내려가고 정지가 안 되면 한도가
        // 조용히 무력화된다. 위에서 이미 계산해 둔 overflow 를 그대로 적용할 뿐 재계산하지 않는다.
        //
        // ⚠️ 알려진 한계(리뷰 P2, 후속 이슈 #913): 이 트랜잭션은 users/user_plans 를 잠그지 않고, preview 가
        // 읽는 건 잠금 없는 스냅샷이다. 같은 시각 다른 트랜잭션이 "구 플랜" 기준으로 좌석을 활성화하면
        // (QuotaService#reserveSeat 는 구 userPlan 의 usage_counters 행을 잠그므로 이 트랜잭션과
        // 직렬화되지 않는다) 커밋 후 새 플랜 한도를 넘는 인원이 남을 수 있다. reserveSeat 는 신규
        // 활성화만 막으므로 그 초과는 스스로 해소되지 않는다.
        planDowngradeService.applyOverflow(companyId, targetPlan, overflow);
        return buildResponseWithUsage(saved, targetPlan);
    }

    /**
     * 플랜 하향 <b>예약</b> — 지금 신청하되 실제 적용은 현재 결제 주기가 끝나는 시점
     * ({@code user_plans.current_period_end}, #1104)에 스케줄러가 수행한다(#1105 / HAJA-526).
     *
     * <p><b>왜 필요한가</b>: {@link #changePlan} 은 즉시 전이라 하향을 신청하는 순간 초과 좌석이 그 자리에서
     * {@code SUSPENDED} 된다. 이미 낸 요금 기간이 남아 있어도 권한이 바로 내려가므로, 통상의 SaaS 관례
     * ("다음 결제 주기부터 적용")와 어긋난다. 이 경로는 <b>예약 행만 만들고 좌석·구독을 전혀 건드리지
     * 않는다</b>.
     *
     * <p><b>인가</b>: {@link #changePlan} 과 동일하게 회사 owner 한정이다 — 예약은 결국 같은 하향을
     * 실행하므로 권한 경계가 더 넓으면 안 된다.
     *
     * <p><b>신청 시점에 검증하는 것</b>(실행 시점에 다시 하지만, 한 달 뒤에야 실패를 알게 하지 않는다):
     * <ul>
     *   <li>하향인가 — 상향·동일 가격은 {@link ErrorCode#PLAN_SCHEDULE_NOT_DOWNGRADE}. 상향은 결제
     *       경로 전용이고(#988) 예약 개념 자체가 없다.</li>
     *   <li><b>이미 미결제 유예 중은 아닌가</b> — 유예 중이면
     *       {@link ErrorCode#PLAN_SCHEDULE_PAYMENT_PENDING}(#1177). 유예 중 구독은 이미 FREE 한도로
     *       동작하는 <b>미정산 상태</b>라, 거기서 또 하향을 예약하면 유예가 유예를 낳는 사슬이 생긴다
     *       (STANDARD 유예 → 만료일에 BASIC 유예 → …). 어느 단계에서도 결제는 일어나지 않고 한도는 계속
     *       FREE 라 이득은 없지만, 청구되지 않는 유료 이름의 구독이 무한히 이어지는 상태 자체를 만들지
     *       않는다. 유예에서 벗어나는 정상 경로는 <b>결제</b>(정상 주기 시작)나 <b>즉시 FREE 변경</b>
     *       ({@link #changePlan})이며 둘 다 막지 않는다.</li>
     *   <li>적용 시각이 있는가 — {@code current_period_end} 가 없으면(무기한 구독)
     *       {@link ErrorCode#PLAN_SCHEDULE_PERIOD_END_MISSING}.</li>
     *   <li>초과 자원 확인 — {@code confirmOverflow} 없이는
     *       {@link ErrorCode#PLAN_DOWNGRADE_CONFIRMATION_REQUIRED}. 예약 경로에서 <b>더</b> 중요하다:
     *       실제 정지는 한 달 뒤 사람 없는 스케줄러가 수행하므로, 신청 시점에 결과를 인지시키지 못하면
     *       아무도 모르는 사이 계정이 정지된다.</li>
     *   <li>유지 대상 선택의 유효성 — {@code PlanDowngradeService#preview} 가 회사 스코프·좌석 한도·
     *       ACTIVE ADMIN 잔존을 검증한다(부작용 없음).</li>
     * </ul>
     *
     * <p><b>중복 예약</b>은 부분 UQ({@code uq_scheduled_plan_changes_pending})가 DB 레벨에서 막는다 —
     * 조회 선검사도 하지만 그것만으로는 동시 요청을 막지 못하므로 무결성 위반을
     * {@link ErrorCode#PLAN_SCHEDULED_CHANGE_EXISTS} 로 번역하는 쪽이 최종 방어선이다.
     */
    @Transactional
    public AdminScheduledPlanChangeResponse scheduleChange(
            Long adminUserId, PlanName targetPlanName, boolean overflowConfirmed, List<Long> keepUserIds) {
        Long companyId = resolveInheritedCompanyId(adminUserId);
        requireCompanyOwner(companyId, adminUserId);
        UserPlan current = resolveCurrentCompanyPlan(companyId);
        Plan targetPlan = planRepository.findByName(targetPlanName)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
        Plan currentPlan = findPlan(current.getPlanId());

        // 하향만 예약 대상이다 — 판정 기준은 requireNotUpgrade 와 같은 plans.price_monthly(그쪽 javadoc
        // 참고: 가격은 플랫폼 관리자가 바꿀 수 있어 티어 이름 순서와 어긋날 수 있다). 동일 가격 전환도
        // 거절한다: 추가 청구가 없어 지금 바꾸면 그만이라, 굳이 기간을 기다릴 이유가 없다.
        if (priceOrZero(targetPlan).compareTo(priceOrZero(currentPlan)) >= 0) {
            throw new BusinessException(ErrorCode.PLAN_SCHEDULE_NOT_DOWNGRADE);
        }

        // ⚠️ 킬 스위치(#1177) — 유료 대상 예약은 프론트에 안내 UI(결제 마감·유예 배너·결제 유도)가
        // 붙기 전까지 닫아 둔다. 그 공백에서 API 로 직접 진입하면 사용자는 안내를 한 번도 받지 못한 채
        // 유예 진입 시점의 좌석 정지와 만료 시점의 FREE 강등을 맞는다. 닫혀 있는 동안의 응답은 기존과
        // 동일한 PLAN_SCHEDULE_PAID_TARGET_UNSUPPORTED 라 프론트의 현행 안내가 그대로 동작한다.
        if (priceOrZero(targetPlan).signum() > 0 && !paymentGraceService.isPaidTargetScheduleEnabled()) {
            throw new BusinessException(ErrorCode.PLAN_SCHEDULE_PAID_TARGET_UNSUPPORTED);
        }

        // ⚠️ 유예 중 구독에는 새 예약을 걸 수 없다(#1177) — 위 javadoc 의 "유예 사슬" 참고. 판정은
        // PaymentGraceService 단일 소스를 쓴다(여기서 조건을 흉내내면 엔타이틀먼트 판정·강등 배치와
        // 갈라진다).
        if (paymentGraceService.isPaymentPending(current)) {
            throw new BusinessException(ErrorCode.PLAN_SCHEDULE_PAYMENT_PENDING);
        }

        Instant effectiveAt = current.getCurrentPeriodEnd();
        if (effectiveAt == null) {
            // 무기한 구독(FREE 등)은 "다음 결제 주기"가 없어 실행 기준일을 정할 수 없다. 위 하향 판정에서
            // 대부분 먼저 걸리지만, 유료인데 주기가 비어 있는 데이터 이상도 여기서 표면화한다.
            throw new BusinessException(ErrorCode.PLAN_SCHEDULE_PERIOD_END_MISSING);
        }

        // 선검사 — 동시 요청은 아래 부분 UQ 가 최종적으로 막는다. 그래도 여기서 먼저 보는 이유는 단일
        // 요청에서 409 를 정확한 코드로 돌려주기 위해서다.
        if (scheduledPlanChangeRepository
                .findFirstByUserPlanIdAndStatus(current.getId(), ScheduledPlanChangeStatus.PENDING)
                .isPresent()) {
            throw new BusinessException(ErrorCode.PLAN_SCHEDULED_CHANGE_EXISTS);
        }

        // ⚠️ 미리보기 기준은 "적용 직후 실제로 적용될 한도" 여야 한다(#1177). 유료 대상은 적용 시점에
        // 미결제 유예로 들어가고 그 동안 한도는 FREE 다(PaymentGraceService#resolveEffectivePlan) —
        // 여기서 STANDARD 한도로 미리보기를 만들면 (1) 관리자가 확인한 정지 인원과 실제 정지 인원이 크게
        // 달라지고 (2) 저장되는 confirmed_seat_overflow 가 과소 계상돼 실행 시점 초과 방어
        // (PLAN_SCHEDULE_CONFIRMED_OVERFLOW_EXCEEDED)에 매번 걸려 예약이 통째로 FAILED 로 죽는다.
        // 실행 경로(ScheduledPlanChangeWriter)와 반드시 같은 기준을 써야 한다.
        Plan entitlementPlan = scheduleEntitlementPlan(targetPlan);
        DowngradeOverflow overflow =
                planDowngradeService.preview(companyId, currentPlan, entitlementPlan, keepUserIds);
        if (!overflowConfirmed && overflow.exists()) {
            throw new BusinessException(ErrorCode.PLAN_DOWNGRADE_CONFIRMATION_REQUIRED);
        }

        // ⚠️ 확인받은 정지 규모를 예약 행에 함께 저장한다(리뷰 P2-4) — confirmOverflow 는 "이 미리보기"에
        // 대한 동의이지 백지수표가 아니다. 예약은 한 달 가까이 보관되므로 그 사이 구성원이 늘면 실행 시점
        // 정지 인원이 동의받은 수를 크게 넘어설 수 있고, 대상이 FREE(1석)라 폭발 반경이 특히 크다.
        // 실행 시점에 이 값과 대조해 초과하면 적용하지 않는다(ScheduledPlanChangeWriter).
        int confirmedSeatOverflow = overflow.seatUserIdsToSuspend().size();
        ScheduledPlanChange scheduled = ScheduledPlanChange.schedule(
                current.getId(), targetPlan.getId(), effectiveAt, keepUserIds, confirmedSeatOverflow,
                adminUserId);
        try {
            scheduled = scheduledPlanChangeRepository.saveAndFlush(scheduled);
        } catch (DataIntegrityViolationException e) {
            // 동시 예약 경합 — 부분 UQ(uq_scheduled_plan_changes_pending) 위반.
            throw new BusinessException(ErrorCode.PLAN_SCHEDULED_CHANGE_EXISTS);
        }
        return AdminScheduledPlanChangeResponse.of(scheduled, targetPlan);
    }

    /**
     * 대기 중인 하향 예약 취소(#1105) — 인가는 {@link #scheduleChange} 와 동일(회사 owner 한정)하고,
     * 대상은 <b>요청자의 회사 현재 구독</b>에 걸린 예약으로 한정한다. 클라이언트가 예약 id 를 넘기지
     * 않으므로 cross-company IDOR 표면 자체가 없다.
     *
     * <p>취소 성공 여부는 조회가 아니라 <b>조건부 UPDATE 의 갱신 행 수</b>로 판정한다 — 동시에 두 번
     * 취소해도 한쪽만 성공하고 다른 쪽은 {@link ErrorCode#PLAN_SCHEDULED_CHANGE_NOT_FOUND} 를 받는다.
     * (선조회는 응답 본문을 만들기 위한 것일 뿐 판정 근거가 아니다.)
     *
     * @return 취소된 예약의 스냅샷 — 프론트가 "무엇이 취소됐는지"를 재조회 없이 표시할 수 있게 한다.
     */
    @Transactional
    public AdminScheduledPlanChangeResponse cancelScheduledChange(Long adminUserId) {
        Long companyId = resolveInheritedCompanyId(adminUserId);
        requireCompanyOwner(companyId, adminUserId);
        UserPlan current = resolveCurrentCompanyPlan(companyId);
        ScheduledPlanChange pending = scheduledPlanChangeRepository
                .findFirstByUserPlanIdAndStatus(current.getId(), ScheduledPlanChangeStatus.PENDING)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_SCHEDULED_CHANGE_NOT_FOUND));
        int canceled = scheduledPlanChangeRepository
                .cancelPendingByUserPlanId(current.getId(), "신청자가 취소함(userId=" + adminUserId + ")");
        if (canceled == 0) {
            // 선조회와 갱신 사이에 다른 요청/배치가 먼저 처리한 경우 — 최종 판정은 갱신 행 수다.
            throw new BusinessException(ErrorCode.PLAN_SCHEDULED_CHANGE_NOT_FOUND);
        }
        // ⚠️ pending 은 벌크 UPDATE 를 우회한 1차 캐시 스냅샷이라 status 가 아직 PENDING 이다
        // (cancelPendingByUserPlanId 는 clearAutomatically 를 쓰지 않는다 — 그 이유는 리포지토리 javadoc).
        // 그래서 응답 상태는 엔티티에서 읽지 않고 CANCELED 로 명시한다.
        return AdminScheduledPlanChangeResponse.canceled(pending, findPlan(pending.getTargetPlanId()));
    }

    /**
     * 요금제 변경 미리보기(#890) — 이 요금제로 내리면 누가 정지되고 시설물 몇 개가 읽기 전용이 되는지
     * <b>부작용 없이</b> 계산한다. 인가는 {@link #changePlan} 과 동일(회사 owner 한정)하게 맞춘다 —
     * 구성원 이름·이메일을 돌려주므로 조회 권한을 변경 권한보다 넓게 두면 안 된다.
     *
     * @param keepUserIds 관리자가 유지를 검토 중인 구성원 선택(#890 Phase 2) — 실제 변경({@link #changePlan})
     *                    에 넘길 값과 동일하게 넘겨야 미리보기·실제 결과가 어긋나지 않는다. 비어 있으면
     *                    (null 포함) 기존 동작(id 오름차순 자동 선정).
     */
    public AdminPlanChangePreviewResponse previewChange(
            Long adminUserId, PlanName targetPlanName, List<Long> keepUserIds) {
        Long companyId = resolveInheritedCompanyId(adminUserId);
        requireCompanyOwner(companyId, adminUserId);
        Plan targetPlan = planRepository.findByName(targetPlanName)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));

        Plan currentPlan = findPlan(resolveCurrentCompanyPlan(companyId).getPlanId());
        DowngradeOverflow overflow = planDowngradeService.preview(companyId, currentPlan, targetPlan, keepUserIds);
        List<AdminPlanChangePreviewResponse.SuspendTarget> targets =
                userRepository.findAllById(overflow.seatUserIdsToSuspend()).stream()
                        .sorted(Comparator.comparing(User::getId))
                        .map(u -> new AdminPlanChangePreviewResponse.SuspendTarget(
                                u.getId(), u.getName(), u.getEmail()))
                        .toList();
        return AdminPlanChangePreviewResponse.of(targetPlanName, overflow, targets);
    }

    /** 회사 구독 변경 이력(최신 순, 페이지 단위). */
    public AdminPlanHistoryResponse getHistory(Long adminUserId, int page, int size) {
        Long companyId = resolveInheritedCompanyId(adminUserId);
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size);
        Page<AdminPlanHistoryEntry> result = adminPlanRepository.findHistoryByCompanyId(companyId, pageable);
        return new AdminPlanHistoryResponse(result.getContent(), page, size, result.getTotalElements());
    }

    /**
     * 회사 소속 활성 멤버별 이번 달 쿼터 사용 현황(#507, frontend PlanQuotaPage.tsx). 회사에 활성 구독이
     * 없으면(PLAN_NOT_FOUND) 목록 조회 자체는 실패시키지 않고 plan/quotaLimit 을 전부 null 로 반환한다 —
     * getCurrentPlan/getHistory 와 달리 이 화면은 "구독 없음"도 정상 상태로 보여줘야 하는 목록 뷰이기 때문이다.
     *
     * <p><b>미결제 유예(#1177)</b>: {@code quotaLimit}·{@code totalQuotaUsagePercent} 는 <b>실효 요금제</b>
     * (유예 중이면 FREE) 기준이고, {@code planName} 은 <b>구독 요금제</b> 그대로다 —
     * {@code AdminPlanItem#of} 와 같은 원칙(정체성은 구독 요금제, 엔타이틀먼트는 실효 요금제).
     */
    public AdminPlanQuotaResponse getPlanQuota(Long adminUserId, int page, int size, String keyword) {
        Long companyId = resolveInheritedCompanyId(adminUserId);

        Plan companyPlan = null;
        Integer companyQuotaLimit = null;
        int totalQuotaUsagePercent = 0;
        try {
            UserPlan current = resolveCurrentCompanyPlan(companyId);
            companyPlan = findPlan(current.getPlanId());
            // ⚠️ 한도·사용률은 "실제로 적용 중인" 요금제 기준이다(#1177) — 미결제 유예 중이면 FREE.
            // 구독 요금제로 내보내면 이 화면이 STANDARD 한도(1000)를 보여주는데 실제 차단은 FREE
            // 기준(50)이라 남은 양도 사용률 퍼센트도 어긋난다(MyPlanResponse·AdminPlanItem·
            // MembershipService#getSeats 와 같은 규칙 — 화면 숫자와 서버 차단 기준이 갈라지면 안 된다).
            //
            // 반면 <b>요금제 이름은 구독 요금제 그대로</b> 둔다(companyPlan 을 바꾸지 않는 이유) —
            // AdminPlanItem#of 에서 세운 원칙과 같다: 정체성(이름·가격)은 구독한 요금제, 엔타이틀먼트만
            // 실효 요금제. 유예 중에도 사용자가 구독한 것은 여전히 STANDARD 이고, 결제하면 그 요금제가 된다.
            companyQuotaLimit = paymentGraceService.resolveEffectivePlan(current, companyPlan)
                    .getMaxMonthlyAnalyses();
            LocalDate period = currentPeriod();
            UsageCounter usage = usageCounterRepository
                    .findByUserPlanIdAndPeriod(current.getId(), period)
                    .orElse(null);
            totalQuotaUsagePercent = computeUsagePercent(usage, companyQuotaLimit);
        } catch (BusinessException e) {
            if (e.getErrorCode() != ErrorCode.PLAN_NOT_FOUND) {
                throw e;
            }
            // 활성 구독 없음 — 아래 content 는 plan/quotaLimit null 로, companyPlan 도 null 로 응답한다.
        }

        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size);
        Page<User> members = adminUserRepository.searchActiveMembers(
                companyId, UserStatus.ACTIVE, buildLikePattern(keyword), pageable);

        Map<Long, Long> usedByMember = mediaUsageByInspector(members.getContent());

        String planName = companyPlan == null ? null : companyPlan.getName().name();
        Integer quotaLimit = companyQuotaLimit;
        List<AdminPlanQuotaMember> content = members.getContent().stream()
                .map(u -> new AdminPlanQuotaMember(
                        u.getId(),
                        u.getName(),
                        u.getEmail(),
                        planName,
                        usedByMember.getOrDefault(u.getId(), 0L).intValue(),
                        quotaLimit))
                .toList();

        long activeUsers = adminUserRepository.countByCompanyIdAndStatus(companyId, UserStatus.ACTIVE);
        AdminPlanQuotaStats stats = new AdminPlanQuotaStats(activeUsers, totalQuotaUsagePercent, planName);

        return new AdminPlanQuotaResponse(content, page, size, members.getTotalElements(), stats);
    }

    private AdminPlanResponse buildResponseWithUsage(UserPlan userPlan, Plan plan) {
        LocalDate period = currentPeriod();
        UsageCounter usage = usageCounterRepository
                .findByUserPlanIdAndPeriod(userPlan.getId(), period)
                .orElse(null);
        // ⚠️ 한도·기능은 "실제로 적용 중인" 요금제 기준이다(#1177) — 유예 중이면 FREE. 하향을 신청한
        // 바로 그 화면이라, 구독 요금제 기준으로 내보내면 owner 가 좌석이 정지된 화면에서 "좌석 5"를 본다.
        return AdminPlanResponse.from(userPlan, plan, usage, period,
                findPendingScheduledChange(userPlan.getId()),
                paymentGraceService.resolveEffectivePlan(userPlan, plan));
    }

    /**
     * 이 구독에 걸린 대기 예약(#1105) — 없으면 {@code null}. 적용·취소·실패한 예약은 노출하지 않는다
     * (화면이 "예약 있음"으로 오인하면 안 된다).
     */
    private AdminScheduledPlanChangeResponse findPendingScheduledChange(Long userPlanId) {
        return scheduledPlanChangeRepository
                .findFirstByUserPlanIdAndStatus(userPlanId, ScheduledPlanChangeStatus.PENDING)
                .map(change -> AdminScheduledPlanChangeResponse.of(change, findPlan(change.getTargetPlanId())))
                .orElse(null);
    }

    // 요청 관리자의 회사를 확정한다. companyId 없음(개인 회원 등) = 회사 관리 대상 아님(FORBIDDEN).
    //
    // (#887) 원래는 여기서 유효 승인 CompanyMembership 을 추가로 요구했다(§2.6 "미승인 멤버십은 상속
    // 대상 아님"). 그런데 당시 가입 경로(CompanyAccountWriter.createAccount())는 가입 시 멤버십 행을
    // 아예 만들지 않고 users.company_id 만 채웠으므로, 그 게이트를 유지하면 dev 의 모든 기업 관리자가
    // 이 화면에서 PLAN_NOT_FOUND(404) 를 그대로 맞았다. 정식 해결(승인 엔드포인트 배선)은 #363 에서
    // 별도 Critical 사이클로 처리하고, 그 전까지는 users.company_id 만으로 회사 스코프를 인정한다 —
    // "활성 플랜 없음"은 resolveCurrentCompanyPlan/getPlanQuota 가 이미 별도로 (PLAN_NOT_FOUND 또는
    // null 값) 처리하므로 여기서 미승인 멤버십과 混同될 일은 없다.
    //
    // (#1324) 가입 시 오너 APPROVED 멤버십이 발급되고 기존 회사도 V37 로 소급 발급됐다 — 완화의 원인은
    // 사라졌으나 게이트 복원은 #363 범위로 유지한다(잔여 무멤버십 계정 실측이 선행돼야 한다).
    private Long resolveInheritedCompanyId(Long adminUserId) {
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Long companyId = admin.getCompanyId();
        if (companyId == null) {
            throw new BusinessException(ErrorCode.PLAN_FORBIDDEN);
        }
        return companyId;
    }

    /**
     * 관리자 콘솔 플랜 변경에서 <b>상향을 거절</b>한다(#988) — 이 경로에는 청구가 없으므로 상향을 허용하면
     * 결제 흐름 전체가 우회된다({@link #changePlan} javadoc).
     *
     * <p><b>판정 기준은 {@code plans.price_monthly}</b>다. 티어 이름의 순서(FREE&lt;STANDARD&lt;ENTERPRISE)나
     * 한도 비교가 아니라 <b>"돈을 더 내야 하는 변경인가"</b>가 결제 우회 여부를 가르는 유일한 기준이고,
     * 가격은 플랫폼 관리자가 변경할 수 있어(#624 {@code Plan#updatePolicy}) 이름 순서와 어긋날 수 있기
     * 때문이다. null 가격은 0(무료)으로 본다 — FREE 시드가 그렇다.
     *
     * <p>같은 가격끼리의 전환은 막지 않는다. 추가 청구가 발생하지 않아 우회로가 아니고, 하향 확인
     * 게이트(#890)가 뒤에서 한도 초과를 따로 막는다.
     */
    private void requireNotUpgrade(Plan currentPlan, Plan targetPlan) {
        BigDecimal currentPrice = priceOrZero(currentPlan);
        BigDecimal targetPrice = priceOrZero(targetPlan);
        if (currentPrice.compareTo(targetPrice) < 0) {
            throw new BusinessException(ErrorCode.PLAN_UPGRADE_REQUIRES_PAYMENT);
        }
    }

    private BigDecimal priceOrZero(Plan plan) {
        return plan.getPriceMonthly() == null ? BigDecimal.ZERO : plan.getPriceMonthly();
    }

    /**
     * 예약이 적용된 <b>직후에 실제로 적용될 한도</b>의 요금제(#1177) — 유료 대상이면 FREE(미결제 유예 중
     * 한도), 무료 대상이면 대상 요금제 그대로.
     *
     * <p>{@code ScheduledPlanChangeWriter} 의 같은 이름 헬퍼와 <b>반드시 같은 규칙</b>이어야 한다. 신청
     * 시점 미리보기와 실행 시점 적용이 다른 한도를 보면 관리자가 확인한 정지 인원과 실제 결과가 어긋나고,
     * 실행 시점 초과 방어({@code PLAN_SCHEDULE_CONFIRMED_OVERFLOW_EXCEEDED})가 정상 예약을 죽인다.
     */
    private Plan scheduleEntitlementPlan(Plan targetPlan) {
        return priceOrZero(targetPlan).signum() > 0 ? paymentGraceService.freePlan() : targetPlan;
    }

    // 요금제 즉시 변경(하향·FREE 전환)은 회사 소유자만 허용 — requestUpgrade 와 인가 기준을 일치시킨다.
    private void requireCompanyOwner(Long companyId, Long adminUserId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_FORBIDDEN));
        if (!adminUserId.equals(company.getOwnerUserId())) {
            throw new BusinessException(ErrorCode.PLAN_FORBIDDEN);
        }
    }

    private UserPlan resolveCurrentCompanyPlan(Long companyId) {
        return adminPlanRepository
                .findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(companyId, UserPlanStatus.ACTIVE)
                .or(() -> adminPlanRepository.findFirstByCompanyIdAndStatusOrderByStartedAtDescIdDesc(
                        companyId, UserPlanStatus.UPGRADE_REQUESTED))
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }

    private Plan findPlan(Long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
    }

    private LocalDate currentPeriod() {
        // 사용량 집계(usage_counters.period) 존과 일치 — 서버 기본 타임존에 의존하지 않고 KST 로 고정(마이페이지와 동일).
        return YearMonth.now(ZoneId.of("Asia/Seoul")).atDay(1);
    }

    // AdminUserService#buildLikePattern 과 동일한 이스케이프 규칙(각 화면이 자기 검색 계약을 독립적으로 유지).
    private String buildLikePattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String escaped = keyword.trim().toLowerCase()
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    // 페이지에 표시할 멤버들의 "이번 달 분석 이미지 장수" 근사치 — MediaRepository 문서 참고(정확한 값은
    // usage_counters 회사 단위 집계뿐이라, 여기서는 화면 표의 멤버별 분포 표시용으로만 쓴다).
    // PR#525 머신 리뷰 P3: 아래 창은 currentPeriod()로 KST 기준 월 경계를 명시적으로 고정하는데,
    // Media.createdAt(@CreatedDate LocalDateTime, 무존)은 JVM 기본 타임존으로 기록된다 — 배포
    // 환경(backend/Dockerfile:48 `-Duser.timezone=Asia/Seoul`)이 JVM 기본존을 KST로 고정하므로
    // 두 값은 실제로 같은 기준(KST)이다. 이 고정이 깨지면(예: Dockerfile 없이 로컬/다른 배포 방식으로
    // 기동) 월 경계에서 근사치가 최대 수 시간 어긋날 수 있다.
    private Map<Long, Long> mediaUsageByInspector(List<User> members) {
        if (members.isEmpty()) {
            return Map.of();
        }
        List<Long> memberIds = members.stream().map(User::getId).toList();
        LocalDate period = currentPeriod();
        LocalDateTime from = period.atStartOfDay();
        LocalDateTime to = period.plusMonths(1).atStartOfDay();
        return mediaRepository.countByAssignedInspectorInAndCreatedAtBetween(memberIds, from, to).stream()
                .collect(Collectors.toMap(
                        MediaRepository.InspectorMediaCount::getInspectorId,
                        MediaRepository.InspectorMediaCount::getMediaCount));
    }

    private int computeUsagePercent(UsageCounter usage, Integer quotaLimit) {
        if (usage == null || quotaLimit == null || quotaLimit <= 0) {
            return 0;
        }
        return (int) Math.min(100, Math.round(usage.getAnalyzedImageCount() * 100.0 / quotaLimit));
    }
}
