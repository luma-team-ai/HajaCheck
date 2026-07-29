package com.hajacheck.payment.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.PlanRepository;
import com.hajacheck.membership.service.PaymentGraceService;
import com.hajacheck.membership.service.PlanDowngradeService;
import com.hajacheck.membership.service.PlanTransitionService;
import com.hajacheck.payment.config.TossPaymentsProperties;
import com.hajacheck.payment.dto.PaymentConfirmRequest;
import com.hajacheck.payment.dto.PaymentOrderResponse;
import com.hajacheck.payment.entity.Payment;
import com.hajacheck.payment.entity.PaymentStatus;
import com.hajacheck.payment.repository.PaymentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 흐름의 <b>DB 트랜잭션 전담</b> 빈(#988 / HAJA-489) — 외부 PG 호출을 하는
 * {@link PaymentService} 와 별도 빈으로 나눈다.
 *
 * <p><b>왜 나누는가(트랜잭션 경계 설계)</b>
 * <ol>
 *   <li><b>PG 호출을 트랜잭션 안에 두지 않는다.</b> 승인은 카드사 왕복이 포함돼 초 단위로 늘어질 수 있는데,
 *       그 시간 동안 DB 커넥션과 (잠금을 잡았다면) 행 잠금을 쥐고 있으면 결제 트래픽이 곧 커넥션 풀 고갈이
 *       된다. 그래서 "검증 트랜잭션 → (트랜잭션 밖) PG 호출 → 반영 트랜잭션" 3단계로 쪼갠다.</li>
 *   <li><b>승인 실패 기록이 롤백되면 안 된다.</b> 하나의 트랜잭션이었다면 PAYMENT_GATEWAY_ERROR 를 던지는
 *       순간 방금 쓴 FAILED 기록까지 롤백돼 실패 이력이 사라진다. {@link #markFailed} 를 독립 트랜잭션으로
 *       두어 실패도 반드시 남긴다.</li>
 *   <li><b>승인 기록과 플랜 전이를 분리한다.</b> {@link #markApproved}(돈이 오간 사실)를 먼저 커밋하고
 *       {@link #applyPlanTransition}(엔타이틀먼트)을 뒤이어 커밋한다. 한 트랜잭션이면 전이 단계의 예외
 *       (예: 동시 플랜 변경 경합)가 <b>승인 기록까지 롤백</b>시켜 "돈은 나갔는데 원장에 없음"이 된다.
 *       분리해 두면 최악의 경우가 {@code status=PAID && user_plan_id is null} 이라는 <b>탐지 가능한 상태</b>로
 *       남고, 같은 orderId 재요청이 PG 재호출 없이 전이만 재시도한다({@link PaymentService#confirm} 의
 *       멱등 분기). ⚠️ 이 자가 복구는 <b>일시적 실패에 한한다</b> — 소속 변경·owner 교체·활성 구독
 *       부재처럼 결정적인 원인은 재요청해도 같은 결과라 대사(#1010) 대상이다.</li>
 * </ol>
 *
 * <p>self-invocation 으로 {@code @Transactional} 프록시가 풀리는 것을 막기 위해 별도 빈으로 둔다
 * ({@code PendingBusinessReverifyWriter}·{@code CompanyAccountWriter} 와 동일한 이유).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentWriter {

    /** 주문 식별자 접두사 — 토스 규격(6~64자, 영숫자·'-'·'_')을 만족하고 운영 로그에서 식별하기 쉽게. */
    private static final String ORDER_ID_PREFIX = "haja-";

    // 결제 주기 계산 존(#1104 startNewBillingPeriod·#1145 PlanExpiryScheduler 와 동일하게 KST 고정) —
    // 어긋나면 자정 전후(KST 00~09시 = UTC 전날)에 잔여일 계산이 하루 밀린다.
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 토스페이먼츠 결제창의 최소 결제금액(원) — 이 아래로는 결제창을 열 수 없다. 인접한 가격의 두 플랜
     * 사이에서 크레딧을 차감하면 청구액이 이 아래로 내려갈 수 있어(#1146), 그 경우 이 금액으로 올린다
     * (아래 {@link #createOrder} 가드 참고).
     */
    private static final long MIN_CHARGEABLE_AMOUNT = 100L;

    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final PlanRepository planRepository;
    private final PlanTransitionService planTransitionService;
    private final PlanDowngradeService planDowngradeService;
    // 미결제 유예(#1177) 판정 — 유예 정산 결제(같은 요금제 재결제)를 허용하기 위해.
    private final PaymentGraceService paymentGraceService;
    private final TossPaymentsClient tossPaymentsClient;
    private final TossPaymentsProperties tossPaymentsProperties;

    /**
     * 주문 사전 등록(READY) — 결제창을 띄우기 전에 서버가 {@code orderId}·{@code amount} 를 확정한다.
     *
     * <p>모의 결제({@code MembershipService#checkout}, #711)가 갖고 있던 가드를 <b>돈이 움직이기 전인 이
     * 단계로</b> 전부 옮겼다: FREE 거부 · 구독 소유자 인가 · 하향 초과 확인 요구(#890) · 동일 플랜 재결제 차단.
     * 승인 단계에서 처음 거절하면 사용자가 이미 카드 인증을 마친 뒤라 UX 도 나쁘고 환불 처리도 필요해진다.
     */
    @Transactional
    public PaymentOrderResponse createOrder(Long userId, PlanName targetPlanName) {
        requireGatewayConfigured();

        if (targetPlanName == PlanName.FREE) {
            // FREE 로의 하향은 결제 흐름이 아니다(청구할 금액이 없다). 다운그레이드는 관리자 콘솔의
            // "변경 미리보기 → 명시적 확인" 경로(AdminPlanService#changePlan)가 담당한다.
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        User user = findUser(userId);
        Long companyId = user.getCompanyId();
        UserPlan current = planTransitionService.resolveCurrentUserPlan(userId, companyId);
        planTransitionService.requireSubscriptionOwner(userId, companyId, current);

        Plan targetPlan = planRepository.findByName(targetPlanName)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));

        Plan currentPlan = findPlan(current.getPlanId());
        // ⚠️ 미결제 유예 정산(#1177) — 유예 중이면 "같은 요금제로의 결제"가 <b>정상적인 정산</b>이다.
        // 유예 구독은 결제 없이 발급돼 한도가 FREE 로 묶여 있고, 이 결제가 그것을 정상 주기로 되돌린다.
        // 아래 두 가드(동일 요금제 차단·상향 전용)를 그대로 두면 유예를 해소할 방법이 결제 경로에 아예
        // 없어져 사용자는 FREE 강등 외에 선택지가 없다.
        boolean graceSettlement = current.getStatus() == UserPlanStatus.ACTIVE
                && current.getPlanId().equals(targetPlan.getId())
                && paymentGraceService.isInGracePeriod(current, currentPlan);

        // 이미 그 요금제를 쓰고 있으면 주문을 만들지 않는다. 모의 결제 시절엔 "멱등 no-op(200)" 이었지만,
        // 실결제에서 no-op 주문을 내주면 사용자가 아무것도 바뀌지 않는 데 돈을 낸다 — 청구 전에 거절하는
        // 것이 맞다. 상태 충돌이라 409(PLAN_ACTIVE_SUBSCRIPTION_CONFLICT)로 계약 안에서 표현한다.
        // (유예 정산은 "아무것도 바뀌지 않는" 결제가 아니다 — 한도가 FREE 에서 대상 요금제로 올라간다.)
        if (!graceSettlement && current.getStatus() == UserPlanStatus.ACTIVE
                && current.getPlanId().equals(targetPlan.getId())) {
            throw new BusinessException(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT);
        }

        if (!graceSettlement) {
            // 유예 정산은 현재가 == 대상가라 "상향"이 아니지만, 무결제 상태를 정산하는 것이라 결제 경로가
            // 맞다. 그 외에는 기존대로 상향만 허용한다(관리자 콘솔과 상보적 — requirePayableUpgrade javadoc).
            requirePayableUpgrade(currentPlan, targetPlan);
        }
        requireNoUnconfirmedDowngrade(companyId, currentPlan, targetPlan);

        long listPrice = toChargeableAmount(targetPlan.getPriceMonthly());
        // ⚠️ 유예 정산에는 잔여 크레딧을 주지 않는다(#1177). 유예 구독은 <b>한 번도 결제된 적이 없고</b>
        // 그 기간의 한도도 FREE 였다 — 낸 적 없는 돈을 잔여일로 환급할 근거가 없다. paidAmountCap 이
        // 이미 0을 보장하지만(연결된 PAID 결제가 없으므로), 의도를 코드로 못박아 향후 크레딧 산식이
        // 바뀌어도 "무결제 구독에 크레딧"이 새지 않게 한다(#1146 리뷰 P1-A 와 같은 취지).
        long credit = graceSettlement ? 0L : computeProratedCredit(current, currentPlan);
        // 정가 - 크레딧 이 최소 결제금액 아래로 내려가면 그 금액으로 올린다(가드 아래 javadoc). 크레딧은
        // 항상 목표 플랜가보다 작다(requirePayableUpgrade 가 현재가 < 목표가를 보장하고, 크레딧은 현재가를
        // 넘지 못한다) — 즉 raw 청구액은 통상 0보다 크고, 이 가드는 "0 초과 & 100원 미만" 구간을 끌어올린다.
        long amount = Math.max(listPrice - credit, MIN_CHARGEABLE_AMOUNT);
        // 화면에는 실제로 적용된 차감액을 보여준다 — 위 최소금액 가드로 조정된 뒤에도
        // listPrice - appliedCredit == amount 가 항상 성립해야 "정가/크레딧/실청구액" 3줄이 어긋나지 않는다.
        // 0 이상으로 클램프한다(리뷰 P1-B) — 정가 자체가 최소 결제금액보다 낮은 이상 데이터라면 그 클램프로도
        // 위 불변식을 만족시킬 수 없어(어떤 credit 을 대입해도 listPrice - credit == amount 가 안 나온다),
        // 잘못된 화면 대신 아래 requireOrderInvariant 가 즉시 표면화한다(fail-fast, 조용한 오표시 금지).
        long appliedCredit = Math.max(listPrice - amount, 0L);
        requireOrderInvariant(listPrice, appliedCredit, amount);

        // 같은 소유 주체·같은 요금제의 기존 READY 주문을 먼저 본다(리뷰 P1-B 근본 원인 차단).
        // 새 주문을 계속 찍어내면 사용자가 결제창을 두 번 열었다가 둘 다 결제해 "2회 청구 + 구독 변화 0"이
        // 될 수 있고(환불은 이번 범위 밖이라 회수 불가), 승인되지 않은 주문도 무한히 쌓인다.
        //
        // ⚠️ 조회 축이 소유 주체와 정확히 같아야 한다 — 아래 부분 유니크 인덱스와 같은 키를 쓴다.
        //    회사 구독은 (company_id, plan_id), 개인 구독은 (user_id, plan_id) where company_id is null.
        Optional<Payment> existingReady = findExistingReadyOrder(userId, companyId, targetPlan.getId());
        if (existingReady.isPresent()) {
            Payment existing = existingReady.get();
            if (isReusable(existing, userId, amount)) {
                log.info("유효한 기존 결제 주문 재사용 — orderId={} planName={}",
                        existing.getOrderId(), targetPlanName);
                // amount 는 기존 주문의 스냅샷 그대로다(isReusable 이 방금 그 스냅샷이 지금 계산한 amount와
                // 같음을 확인했으므로 재계산과 동치다) — listPrice/appliedCredit 도 지금 막 계산한 값이라
                // 세 값이 항상 같은 계산에서 나온다(리뷰 P1-B, 위 requireOrderInvariant 로 이미 검증됨).
                return PaymentOrderResponse.of(existing, listPrice, appliedCredit, amount,
                        buildOrderName(existing.getPlanName()));
            }
            // 재사용할 수 없는 잔재(만료·만료 임박·소유자 교체·가격/크레딧 드리프트)는 <b>반드시 닫고</b>
            // 진행한다. 그대로 두면 아래 부분 유니크 인덱스에 걸려 새 주문을 영영 만들지 못한다(결제 경로
            // 잠김). 가격 드리프트(TTL 안에 요금제 정가가 바뀌거나 크레딧 산정이 달라진 경우)로 닫히는
            // 것도 손해가 없다 — 아직 미결제 READY 주문이라 재견적에 비용이 들지 않는다
            // ({@code prepareConfirm} 의 TTL 주석과 같은 근거: "요금 인상 직전 주문을 쟁여뒀다가 나중에
            // 구가격으로 결제"를 막는 것과 대칭으로, 정가가 바뀌면 그 시점 가격으로 다시 견적한다).
            existing.markExpired();
            paymentRepository.saveAndFlush(existing);
            log.info("재사용 불가 결제 주문 정리 후 신규 발급 — orderId={}", existing.getOrderId());
        }

        Payment payment = paymentRepository.saveAndFlush(Payment.createOrder(
                ORDER_ID_PREFIX + UUID.randomUUID(),
                userId,
                companyId,
                targetPlan.getId(),
                targetPlanName,
                BigDecimal.valueOf(amount)));

        log.info("결제 주문 생성 — orderId={} planName={} companyId={} listPrice={} credit={} amount={}",
                payment.getOrderId(), targetPlanName, companyId, listPrice, appliedCredit, amount);
        return PaymentOrderResponse.of(payment, listPrice, appliedCredit, amount, buildOrderName(targetPlanName));
    }

    /**
     * 응답 계약 불변식(#1146 / HAJA-550 리뷰 P1-B) — {@code listPrice - credit == amount && credit >= 0}
     * 은 DTO javadoc·OpenAPI·프론트 타입이 모두 "서버가 보장"이라 명시한 값이고, 프론트는 그 전제로
     * 크레딧 줄을 무가드 렌더한다. 계산 경로가 늘어날수록(재사용·최소금액 가드) 조용히 어긋날 여지가
     * 생기므로, 반환 직전에 실제로 지켜지는지 코드로 고정한다 — 어긋나면 잘못된 화면을 내보내는 대신
     * 즉시 실패한다(fail-fast, PLAN_DATA_INVALID — {@link #toChargeableAmount} 와 같은 급의 데이터 이상).
     */
    private void requireOrderInvariant(long listPrice, long credit, long amount) {
        if (credit < 0 || listPrice - credit != amount) {
            log.error("결제 주문 금액 불변식 위반 — listPrice={} credit={} amount={}", listPrice, credit, amount);
            throw new BusinessException(ErrorCode.PLAN_DATA_INVALID);
        }
    }

    /**
     * 잔여 기간 일할 크레딧(#1146 / HAJA-550) — 상향 결제 시 현재 플랜의 남은 결제 주기를 크레딧으로
     * 차감한다(B안: 잔여 크레딧 차감 + 주기 리셋, 종료일 승계 A안은 빌링키 자동갱신이 없어 채택하지
     * 않음 — {@code PlanTransitionService#transitionTo} 가 승인 후에도 그대로 주기를 리셋한다).
     *
     * <p>잔여일·주기일수는 {@link #BILLING_ZONE}(KST) 기준 <b>달력일 수</b>로 센다({@code UserPlan}
     * 의 {@code BILLING_ZONE} 과 동일 존) — {@code Instant} 그대로 빼면 존과 무관하지만, 자정 전후
     * (KST 00~09시 = UTC 로는 여전히 전날)에 다른 존으로 날짜를 계산하면 최대 하루가 밀린다.
     *
     * <p>크레딧은 <b>원 단위로 내림</b> 한다(#1146 결정) — 사용자에게 불리하게 반올림하지 않는다.
     *
     * <p><b>상한 = 실제로 낸 돈</b>(리뷰 P1-A). 위 계산은 {@code current_period_start/end} 와 <b>라이브</b>
     * {@code plans.price_monthly} 파생치일 뿐 결제 이력을 전혀 보지 않는다 — 그대로 두면 (1) 무결제 유료
     * 구독(V27 백필 추정치·{@code payments} 도입(V20) 이전 모의 결제 시절 구독)이 정가 전액까지 차감받고,
     * (2) 플랫폼 관리자가 나중에 가격을 올리면 구가격으로 결제한 구독자의 청구액이 인상분만큼 내려가
     * <b>낸 적 없는 돈을 환급</b>하게 된다. 그래서 이 구독({@code current.getId()})에 실제로 연결된 PAID
     * 결제 합계({@link PaymentRepository#sumAmountByUserPlanIdAndStatus}, SUM 근거는 그 메서드 javadoc)로
     * 상한을 건다 — 연결된 PAID 가 없으면(무결제 구독) 크레딧은 무조건 0이다.
     *
     * @return {@code current_period_start/end} 가 없거나(FREE·주기 정보 없음) 잔여일이 0 이하(만료 경과 —
     *         #1145 강등 배치와 경합해도 안전한 방향)이거나 실제 결제액이 0(무결제 구독)이면 0
     */
    private long computeProratedCredit(UserPlan current, Plan currentPlan) {
        Instant periodEnd = current.getCurrentPeriodEnd();
        Instant periodStart = current.getCurrentPeriodStart();
        if (periodEnd == null || periodStart == null) {
            return 0L;
        }
        LocalDate endDate = periodEnd.atZone(BILLING_ZONE).toLocalDate();
        LocalDate nowDate = Instant.now().atZone(BILLING_ZONE).toLocalDate();

        long remainingDays = ChronoUnit.DAYS.between(nowDate, endDate);
        if (remainingDays <= 0) {
            return 0L;
        }
        LocalDate startDate = periodStart.atZone(BILLING_ZONE).toLocalDate();
        long totalDays = ChronoUnit.DAYS.between(startDate, endDate);
        if (totalDays <= 0) {
            // 주기 데이터가 역전된 이상 상태 — 안전하게 크레딧을 주지 않는다(정가 청구).
            return 0L;
        }
        // now > periodStart 가 정상이라 remainingDays <= totalDays 이지만, 데이터 이상으로 역전되더라도
        // 크레딧이 정가를 넘는 일이 없도록 상한을 건다.
        remainingDays = Math.min(remainingDays, totalDays);

        BigDecimal price = priceOrZero(currentPlan);
        if (price.signum() <= 0) {
            return 0L;
        }
        long rawCredit = price.multiply(BigDecimal.valueOf(remainingDays))
                .divide(BigDecimal.valueOf(totalDays), 0, RoundingMode.DOWN)
                .longValueExact();

        return Math.min(rawCredit, paidAmountCap(current.getId()));
    }

    /**
     * {@code userPlanId} 에 실제로 연결된 PAID 결제 합계를 크레딧 상한으로 반환한다(리뷰 P1-A 근거는
     * {@link #computeProratedCredit} javadoc). {@code payments.amount} 는 항상 정수 원 스냅샷이라
     * (이 클래스가 {@link #createOrder} 에서 그렇게 저장한다) {@code longValueExact} 가 안전하다.
     */
    private long paidAmountCap(Long userPlanId) {
        BigDecimal paid = paymentRepository.sumAmountByUserPlanIdAndStatus(userPlanId, PaymentStatus.PAID);
        if (paid == null || paid.signum() <= 0) {
            return 0L;
        }
        return paid.longValueExact();
    }

    /**
     * 소유 주체 기준으로 기존 READY 주문을 찾는다 — <b>부분 유니크 인덱스와 동일한 키</b>를 쓴다.
     * 축이 어긋나면 "조회로는 안 보이는데 INSERT 는 제약에 걸리는" 조합이 생긴다.
     */
    private Optional<Payment> findExistingReadyOrder(Long userId, Long companyId, Long planId) {
        return companyId != null
                ? paymentRepository.findFirstByCompanyIdAndPlanIdAndStatus(
                        companyId, planId, PaymentStatus.READY)
                : paymentRepository.findFirstByUserIdAndPlanIdAndStatusAndCompanyIdIsNull(
                        userId, planId, PaymentStatus.READY);
    }

    /**
     * 기존 READY 주문을 그대로 돌려줘도 되는가.
     *
     * <ul>
     *   <li><b>요청자 소유여야 한다</b>: 회사 축 조회는 owner 교체 후 전 owner 의 주문을 집어올 수 있다.</li>
     *   <li><b>잔여 유효시간이 충분해야 한다</b>(리뷰 P3): 1초 남은 주문을 재사용하면 사용자가 카드 인증까지
     *       마친 뒤 만료 404 를 맞는다 — 결제창에서 승인까지 보낼 시간을 남긴다.</li>
     *   <li><b>가격/크레딧 드리프트가 없어야 한다</b>(#1146 / HAJA-550 리뷰 P1-B). {@code currentAmount} 는
     *       호출부가 지금 막 다시 계산한 {@code listPrice - credit}(최소 결제금액 가드까지 적용된 값)이다.
     *       TTL(30분) 안에 대상 플랜 정가가 바뀌거나(관리자 콘솔) 크레딧 산정이 달라지면(날짜가 넘어가
     *       잔여일이 줄어드는 등) 스냅샷과 어긋나는데, 그 스냅샷을 그대로 재사용하면
     *       {@code listPrice(지금) - credit(스냅샷 역산) == amount(스냅샷)} 불변식이 깨진다 — 정가보다
     *       비싼 금액이 설명 없이 청구되거나(정가 하락) 표시 크레딧이 실제 적용액과 달라진다(정가 상승).
     *       미결제 READY 주문의 재견적은 손해가 없으므로({@code prepareConfirm} 의 TTL 주석과 같은 근거)
     *       어긋나면 재사용하지 않고 새로 견적한다.</li>
     * </ul>
     */
    private boolean isReusable(Payment existing, Long userId, long currentAmount) {
        return existing.isOwnedBy(userId)
                && existing.getRequestedAt().isAfter(reusableOrderRequestedAfter())
                && toChargeableAmount(existing.getAmount()) == currentAmount;
    }

    /**
     * 결제 경로는 <b>상향 전용</b>이다(리뷰 P2) — {@code AdminPlanService#requireNotUpgrade} 의 정확한 대칭.
     *
     * <p>관리자 콘솔이 "현재가 &gt;= 대상가"(하향·동일가)만 허용하므로, 결제 경로는 "현재가 &lt; 대상가"만
     * 허용해야 두 경로가 빈틈도 겹침도 없이 상보적이 된다. 이 가드가 없으면 <b>관리자 콘솔에서 무료인
     * 전환을 결제 경로에서는 과금</b>하게 된다 — 초과가 없는 하향(ENTERPRISE→STANDARD)이나 개인 구독
     * 하향(companyId=null 이라 하향 초과 확인이 즉시 통과)이 아무 가드에도 걸리지 않고 청구까지 갔다.
     *
     * <p>판정 기준이 티어 이름이 아니라 {@code price_monthly} 인 이유도 관리자 콘솔과 같다 — 가격은
     * 플랫폼 관리자가 바꿀 수 있어(#624) 이름 순서와 어긋날 수 있고, "돈을 더 내야 하는 변경인가"가
     * 결제 대상 여부를 가르는 유일한 기준이다. null 가격은 0원으로 본다.
     */
    private void requirePayableUpgrade(Plan currentPlan, Plan targetPlan) {
        if (priceOrZero(currentPlan).compareTo(priceOrZero(targetPlan)) >= 0) {
            throw new BusinessException(ErrorCode.PLAN_CHANGE_NOT_PAYABLE);
        }
    }

    private BigDecimal priceOrZero(Plan plan) {
        return plan.getPriceMonthly() == null ? BigDecimal.ZERO : plan.getPriceMonthly();
    }

    /**
     * 승인 호출 직전 검증 — 여기서 통과하지 못하면 <b>PG 를 호출하지 않는다</b>. 금액 대조와 플랜 전이
     * 가드를 모두 이 단계에서 다시 확인해, 돈이 움직인 뒤에 거절하는 상황을 만들지 않는다.
     *
     * <p><b>읽기 전용이다</b>(리뷰 P2 정정). 이전 구현은 만료 주문을 여기서 CANCELED 로 바꾼 뒤 곧바로
     * {@code BusinessException} 을 던졌는데, 그 예외가 RuntimeException 이라 Spring 기본 규칙으로
     * <b>이 트랜잭션이 롤백되어 취소 UPDATE 가 커밋되지 않았다</b> — 응답(404)과 재사용 제외(시간 조건)는
     * 정상이라 겉으로 드러나지 않는 데이터 결함이었다. 지금은 <b>판정만</b> 하고, 주문을 닫는 쓰기는
     * 호출부({@code PaymentService})가 별도 트랜잭션으로 수행한다({@link PaymentConfirmOutcome} 참고).
     *
     * <p>금액 불일치는 결제 주문을 FAILED 로 태우지 않는다 — 위변조 시도든 클라이언트 버그든 정상 주문
     * 하나를 못 쓰게 만들 이유가 없고, PG 호출을 막는 것으로 방어는 이미 끝났다(WARN 로깅만).
     */
    @Transactional(readOnly = true)
    public PaymentConfirmPreparation prepareConfirm(Long userId, PaymentConfirmRequest request) {
        requireGatewayConfigured();

        Payment payment = paymentRepository.findByOrderId(request.orderId())
                // 미존재·타인 소유·재확정 불가를 전부 같은 404 로 통일한다 — 상태를 구분해 주면 남의
                // orderId 를 넣어보며 실재하는 주문을 열거할 수 있다(FACILITY_NOT_FOUND 관례).
                .filter(found -> found.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));

        if (payment.isPaid()) {
            // 멱등(보안 요구 3) — 리다이렉트 새로고침·중복 전송. PG 재호출 없이 호출부가 현재 상태를 돌려준다.
            return PaymentConfirmPreparation.alreadyPaid(
                    payment.getId(), payment.isPaidWithoutPlanApplied());
        }
        if (payment.isExpired(Instant.now(), tossPaymentsProperties.getOrderTtl())) {
            // 유효시간 초과(리뷰 P2) — 금액이 스냅샷이라 시한이 없으면 요금 인상 직전 주문을 쟁여뒀다가
            // 나중에 구가격으로 결제할 수 있다. 취소 기록은 호출부가 별도 트랜잭션으로 남긴다.
            return PaymentConfirmPreparation.expired(payment.getId());
        }
        if (payment.isConfirmable()
                && payment.hasReachedConfirmAttemptLimit(tossPaymentsProperties.getMaxConfirmAttempts())) {
            // 승인 시도 상한 초과(보안 리뷰 P2) — "결과 불명이면 READY 유지" 정책 때문에 사라진
            // "1주문 = 최대 1회 PG 호출" 상한을 여기서 되살린다. 가상계좌처럼 즉시 승인되지 않는 수단은
            // 매번 불명으로 끝나므로, 상한이 없으면 같은 주문으로 우리 머천트 크레덴셜 호출을 무한 증폭할 수 있다.
            return PaymentConfirmPreparation.attemptLimitExceeded(payment.getId());
        }
        if (!payment.isConfirmable()) {
            // FAILED·CANCELED 는 재확정 대상이 아니다. 상태를 세분해 알려주지 않는다(ErrorCode javadoc).
            throw new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND);
        }

        long amount = toChargeableAmount(payment.getAmount());
        if (request.amount() == null || request.amount() != amount) {
            log.warn("결제 승인 금액 불일치 — orderId={} (PG 호출 차단)", payment.getOrderId());
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }

        // 승인 후 전이 단계에서 실패할 조건을 미리 걸러낸다(돈이 움직이기 전에).
        TransitionTarget target = resolveTransitionTarget(payment, true);
        if (target.alreadyOnTargetPlan()) {
            // ⚠️ 중복 청구 차단(리뷰 P1-B). createOrder 만 막으면 READY 주문 2건을 미리 만든 뒤 순차로
            // 결제해 "2회 청구 + 구독 변화 0"을 만들 수 있고, 환불이 범위 밖이라 회수 수단이 없다.
            // 반드시 PG 호출 전에 막아야 한다 — 승인 후에 알아차리면 이미 돈이 나간 뒤다.
            //
            // applyPlanTransition 쪽의 같은 조건은 "승인 후 경합 복구(연결만 수행)"라 역할이 다르다.
            // 거기서는 이미 청구가 끝났으므로 거절이 아니라 연결이 맞다.
            log.warn("이미 해당 요금제를 사용 중인 주문의 승인 시도 — orderId={} (PG 호출 차단)",
                    payment.getOrderId());
            throw new BusinessException(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT);
        }

        return PaymentConfirmPreparation.readyToApprove(payment.getId(), amount);
    }

    /**
     * 더 이상 쓸 수 없는 주문을 CANCELED 로 닫는다 — <b>독립 트랜잭션</b>({@code REQUIRES_NEW})이다.
     *
     * <p>{@link #prepareConfirm} 안에서 닫고 예외를 던지면 그 UPDATE 가 함께 롤백된다(리뷰 P2). 호출부는
     * 이 메서드로 취소를 <b>커밋한 뒤</b> 404 를 던진다 — 이 클래스의 다른 쓰기들과 같은 "짧은 독립
     * 트랜잭션" 원칙이다. 이미 PAID 인 행은 엔티티 가드가 막는다(경합에서 받은 돈의 기록을 지우지 않는다).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void closeUnusableOrder(Long paymentId, PaymentConfirmOutcome outcome) {
        paymentRepository.findByIdForUpdate(paymentId).ifPresentOrElse(payment -> {
            if (outcome == PaymentConfirmOutcome.ATTEMPT_LIMIT_EXCEEDED) {
                payment.markConfirmAttemptLimitExceeded();
                log.warn("승인 시도 상한 초과로 결제 주문 자동 취소 — orderId={} attempts={}",
                        payment.getOrderId(), payment.getConfirmAttemptCount());
            } else {
                payment.markExpired();
                log.info("유효시간 초과 결제 주문 자동 취소 — orderId={}", payment.getOrderId());
            }
        }, () -> log.warn("취소 대상 주문 소멸 — paymentId={}", paymentId));
    }

    /**
     * PG 승인 호출 <b>직전</b>에 시도 횟수를 올린다 — 독립 트랜잭션이라 이어지는 PG 호출이 실패하거나
     * 호출부가 예외를 던져도 이 증가는 남는다(보안 리뷰 P2). 남지 않으면 상한이 무의미해진다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordConfirmAttempt(Long paymentId) {
        paymentRepository.findByIdForUpdate(paymentId)
                .ifPresent(Payment::recordConfirmAttempt);
    }

    /**
     * 결제 원장의 현재 정산 상태를 읽는다(리뷰 P2) — PG 가 "이미 처리된 결제"로 거절했을 때 호출부가
     * <b>우리 원장 기준으로도 성사됐는지</b>를 확인하는 용도. 성사됐다면 502 가 아니라 멱등 성공으로
     * 응답해야 한다(실패로 보이면 사용자가 재결제해 중복 청구가 난다).
     */
    @Transactional(readOnly = true)
    public PaymentSettlementState resolveSettlementState(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        return new PaymentSettlementState(payment.isPaid(), payment.isPaidWithoutPlanApplied());
    }

    /**
     * PG 승인 성공 반영 — 돈이 오간 사실을 <b>먼저 독립 커밋</b>한다(클래스 javadoc §3). 같은 주문에 대한
     * 동시 승인은 {@code findByIdForUpdate} 잠금으로 직렬화되고, 뒤 트랜잭션은 앞의 커밋 결과(PAID)를 보고
     * {@link Payment#markPaid} 의 멱등 분기로 빠진다.
     */
    @Transactional
    public void markApproved(Long paymentId, TossPaymentApproval approval) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        payment.markPaid(approval.paymentKey(), approval.method(), approval.receiptUrl(),
                approval.approvedAt());
        log.info("결제 승인 반영 — orderId={} method={}", payment.getOrderId(), approval.method());
    }

    /**
     * PG 승인 실패 반영 — 독립 트랜잭션이라 호출부가 이어서 PAYMENT_GATEWAY_ERROR 를 던져도 이 기록은
     * 남는다. 이미 PAID 인 행은 건드리지 않는다({@link Payment#markFailed} 가 방어).
     */
    @Transactional
    public void markFailed(Long paymentId, String failureCode, String failureMessage) {
        paymentRepository.findByIdForUpdate(paymentId).ifPresentOrElse(
                payment -> {
                    payment.markFailed(failureCode, failureMessage);
                    log.warn("결제 승인 실패 기록 — orderId={} code={}", payment.getOrderId(), failureCode);
                },
                () -> log.warn("결제 승인 실패 반영 대상 주문 소멸 — paymentId={}", paymentId));
    }

    /**
     * 승인된 결제에 대한 플랜 전이 — <b>승인 성공 후에만</b> 호출된다(보안 요구 5). 멱등이다: 이미
     * {@code user_plan_id} 가 연결됐으면 아무것도 하지 않는다.
     *
     * <p>인가를 <b>이 트랜잭션에서 다시</b> 확인한다. 주문 생성과 승인 사이에 소유자가 바뀌었을 수 있고,
     * 그 사이 남이 결제 콜백을 대신 던지는 경로를 인가 없이 통과시키면 안 된다.
     *
     * <p><b>단, 하향 확인 게이트(#890)는 여기서 적용하지 않는다</b>(리뷰 P2). 그 게이트는 "무엇이 바뀌는지
     * 모르고 결제하지 않게" 하는 <b>결제 전 UX 가드</b>지 시스템 불변식이 아니다. 이미 청구가 끝난 뒤에
     * 그것으로 전이를 거절하면 재요청해도 같은 예외가 반복돼 {@code PAID && user_plan_id is null} 이
     * <b>영구 고착</b>된다 — 돈은 받고 요금제는 주지 않는 상태가 자가 복구되지 않는다. 확인은 승인 전
     * ({@link #prepareConfirm})에 이미 끝나 있다.
     */
    @Transactional
    public void applyPlanTransition(Long paymentId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        if (!payment.isPaid()) {
            // 승인되지 않은 결제로는 절대 플랜을 주지 않는다(보안 요구 5의 최종 방어선).
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }
        if (payment.getUserPlanId() != null) {
            return; // 이미 반영됨(동시 재요청·재시도).
        }

        TransitionTarget target = resolveTransitionTarget(payment, false);
        if (target.alreadyOnTargetPlan()) {
            // 승인과 전이 사이에 다른 경로(관리자 플랜 변경·다른 주문의 승인)로 이미 목표 플랜이 된 경우.
            // 플랜을 다시 갈아끼우면 불필요한 이력 행이 생기고 사용량 이월만 반복되므로 연결만 한다.
            payment.linkUserPlan(target.current().getId());
            return;
        }

        UserPlan renewed = planTransitionService.transitionTo(
                payment.getUserId(), payment.getCompanyId(), target.current(), target.targetPlan());
        payment.linkUserPlan(renewed.getId());
        log.info("결제 승인에 따른 플랜 전이 완료 — orderId={} planName={}",
                payment.getOrderId(), payment.getPlanName());
    }

    /**
     * 전이 대상 확정 + 전이 가드 재검증. {@link #prepareConfirm}(승인 전)과 {@link #applyPlanTransition}
     * (승인 후)이 <b>같은 판정</b>을 쓰도록 한 곳에 모은다 — 두 곳이 갈라지면 "승인 전엔 통과했는데 승인
     * 후에 거절"되는 최악의 조합이 생긴다.
     *
     * @param enforceDowngradeGate 하향 확인 게이트(#890) 적용 여부. <b>승인 전에만 {@code true}</b>다 —
     *                             승인 후에 이 게이트로 거절하면 재요청해도 같은 예외가 반복돼
     *                             "결제됨 + 요금제 없음"이 영구 고착된다({@link #applyPlanTransition} javadoc).
     */
    private TransitionTarget resolveTransitionTarget(Payment payment, boolean enforceDowngradeGate) {
        User user = findUser(payment.getUserId());
        if (!Objects.equals(user.getCompanyId(), payment.getCompanyId())) {
            // 주문 생성 이후 소속이 바뀐 주문 — 다른 구독에 결제를 붙이면 엉뚱한 회사가 요금제를 받는다.
            // 승인 후 단계라면 이건 돈을 받아 놓고 반영하지 못하는 상태라 운영이 즉시 알아야 한다.
            if (!enforceDowngradeGate) {
                log.error("승인 완료된 결제의 소속 불일치로 플랜 반영 불가 — orderId={} (수동 대사 필요)",
                        payment.getOrderId());
            }
            throw new BusinessException(ErrorCode.PAYMENT_FORBIDDEN);
        }

        Long companyId = payment.getCompanyId();
        UserPlan current = planTransitionService.resolveCurrentUserPlan(payment.getUserId(), companyId);
        planTransitionService.requireSubscriptionOwner(payment.getUserId(), companyId, current);

        Plan targetPlan = planRepository.findByName(payment.getPlanName())
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
        if (current.getStatus() == UserPlanStatus.ACTIVE && current.getPlanId().equals(targetPlan.getId())) {
            // ⚠️ 미결제 유예 정산(#1177)은 "이미 그 요금제"가 아니다 — 요금제 이름만 같을 뿐 지금은 결제
            // 없이 발급돼 FREE 한도로 묶여 있고, 이 결제가 정상 주기(startNewBillingPeriod)를 시작시킨다.
            // 여기서 alreadyOnTargetPlan=true 로 판정하면 승인 전에는 409 로 결제 자체가 막히고(유예를
            // 해소할 방법이 사라진다), 승인 후에는 결제만 연결되고 주기·한도가 그대로 남아 <b>돈은 받고
            // 요금제는 주지 않는</b> 상태가 된다.
            if (!paymentGraceService.isInGracePeriod(current, findPlan(current.getPlanId()))) {
                return new TransitionTarget(current, targetPlan, true);
            }
        }

        if (enforceDowngradeGate) {
            requireNoUnconfirmedDowngrade(companyId, findPlan(current.getPlanId()), targetPlan);
        }
        return new TransitionTarget(current, targetPlan, false);
    }

    /**
     * <b>재사용</b> 가능한 READY 주문의 최소 생성 시각 — 만료 판정보다 {@code reuseMinRemaining} 만큼
     * 더 보수적이다(리뷰 P3). 잔여 유효시간이 얼마 남지 않은 주문을 재사용하면 사용자가 결제창에서 카드
     * 인증까지 마친 뒤 만료 404 를 맞기 때문에, 결제창에서 보낼 시간이 남은 주문만 재사용한다.
     */
    private Instant reusableOrderRequestedAfter() {
        return Instant.now()
                .minus(tossPaymentsProperties.getOrderTtl())
                .plus(tossPaymentsProperties.getOrderReuseMinRemaining());
    }

    /**
     * 하향 초과 확인 요구(#890) — 셀프 결제 화면에는 "무엇이 바뀌는지" 확인 단계가 없으므로, 하향으로
     * 한도를 넘게 되면 거절하고 관리자 콘솔의 미리보기 경로로 유도한다. 좌석뿐 아니라 시설물 초과도 함께
     * 본다(시설물 읽기전용은 상태 컬럼이 아니라 계산 판정이라, 플랜 행이 바뀌는 순간 즉시 뒤집힌다).
     */
    private void requireNoUnconfirmedDowngrade(Long companyId, Plan currentPlan, Plan targetPlan) {
        if (companyId == null) {
            return; // 개인 구독은 좌석·회사 시설물 개념이 없다.
        }
        if (planDowngradeService.preview(companyId, currentPlan, targetPlan).exists()) {
            throw new BusinessException(ErrorCode.PLAN_DOWNGRADE_CONFIRMATION_REQUIRED);
        }
    }

    private Plan findPlan(Long planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_DATA_INVALID));
    }

    private void requireGatewayConfigured() {
        if (!tossPaymentsClient.isConfigured()) {
            // 시크릿 미설정 — 빈 문자열로 PG 인증을 시도하거나 NPE 로 500 이 새게 두지 않고 결제 진입점에서
            // 명확히 fail-close 한다(TossPaymentsProperties javadoc 의 트레이드오프 참고).
            log.error("결제 시크릿 미설정 — 결제 기능 비활성(앱 기동은 막지 않는다)");
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_ERROR);
        }
    }

    /**
     * 청구 금액 확정 — 원화는 소수부가 없고 PG 결제창·승인 API 도 정수를 요구한다. 요금제 가격이 0 이하이거나
     * 소수부를 가지면 청구할 수 없는 데이터이므로 조용히 반올림하지 않고 데이터 오류로 표면화한다.
     */
    private long toChargeableAmount(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new BusinessException(ErrorCode.PLAN_DATA_INVALID);
        }
        try {
            return price.stripTrailingZeros().longValueExact();
        } catch (ArithmeticException e) {
            throw new BusinessException(ErrorCode.PLAN_DATA_INVALID);
        }
    }

    private String buildOrderName(PlanName planName) {
        return tossPaymentsProperties.getOrderNamePrefix() + " " + planName.name() + " 플랜 구독";
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }

    private record TransitionTarget(UserPlan current, Plan targetPlan, boolean alreadyOnTargetPlan) {
    }
}
