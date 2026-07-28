package com.hajacheck.membership.scheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.config.PlanExpiryProperties;
import com.hajacheck.membership.config.PlanExpiryProperties.Mode;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.entity.UserPlanStatus;
import com.hajacheck.membership.repository.UserPlanRepository;
import com.hajacheck.membership.service.PlanExpiryResult;
import com.hajacheck.membership.service.PlanExpiryWriter;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.service.NotificationService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * PlanExpiryScheduler 단위 테스트(#1145 / HAJA-549). {@code InspectionDueNotificationSchedulerTest}·
 * {@code PendingBusinessReverifySchedulerTest} 와 같이 고정 Clock 을 수동 주입하고, 협력자는 Mockito
 * mock 을 직접 생성자 주입한다(@InjectMocks 미사용).
 *
 * <p>여기서 고정하는 계약은 <b>스케줄러의 통제 로직</b>이다 — 4중 안전장치(enabled·mode·not-before·
 * max-per-run)·건별 장애 격리와 예외 분류·알림 발행·keyset 순회. 실제 전이(만료→FREE 발급·사용량
 * 이월·좌석 정지)와 멱등성은 실 PostgreSQL 이 필요하므로 {@code PlanExpiryIntegrationTest} 가 담당한다.
 */
class PlanExpirySchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    // 2026-07-27T15:30Z = 2026-07-28T00:30 KST — KST 00:00~09:00 구간(UTC 로는 전날)이라
    // 존을 잘못 잡으면 판정이 하루 어긋나는 구간이다(#1104 백필 주석의 그 함정).
    private static final Instant NOW = Instant.parse("2026-07-27T15:30:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, KST);

    private UserPlanRepository userPlanRepository;
    private PlanExpiryWriter planExpiryWriter;
    private NotificationService notificationService;
    private PlanExpiryProperties properties;
    private PlanExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        userPlanRepository = mock(UserPlanRepository.class);
        planExpiryWriter = mock(PlanExpiryWriter.class);
        notificationService = mock(NotificationService.class);
        properties = new PlanExpiryProperties();
        // 운영 기본값은 "비활성 + DRY_RUN" 이다 — 대부분의 테스트는 실제 강등 동작을 보므로 둘 다 켠다.
        properties.setEnabled(true);
        properties.setMode(Mode.ENFORCE);
        scheduler = new PlanExpiryScheduler(
                userPlanRepository, planExpiryWriter, notificationService, properties, FIXED);
    }

    private PlanExpiryResult downgraded(Long recipientUserId) {
        return PlanExpiryResult.downgraded(
                recipientUserId, PlanName.STANDARD, 900L, null, NOW.minusSeconds(3600), List.of(7L, 8L));
    }

    private void stubTargets(long totalCount, List<Long> firstPage) {
        when(userPlanRepository.countExpiryTargets(any(), any(), any())).thenReturn(totalCount);
        when(userPlanRepository.findExpiryTargetIds(any(), any(), any(), eq(0L), any()))
                .thenReturn(firstPage);
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(PlanExpiryScheduler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(PlanExpiryScheduler.class)).detachAppender(appender);
    }

    private ListAppender<ILoggingEvent> runCapturingLogs() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            scheduler.expireOverduePlans();
        } finally {
            detachAppender(appender);
        }
        return appender;
    }

    private boolean loggedAt(ListAppender<ILoggingEvent> appender, Level level, String fragment) {
        return appender.list.stream()
                .anyMatch(event -> event.getLevel() == level
                        && event.getFormattedMessage().contains(fragment));
    }

    // ── 안전장치 1: enabled ──────────────────────────────────────────────

    @Test
    @DisplayName("enabled=false면 대상 조회조차 하지 않고 아무것도 강등하지 않는다")
    void 기동스위치_꺼져있으면_완전_noop() {
        properties.setEnabled(false);

        scheduler.expireOverduePlans();

        // "조회는 하되 바꾸지 않는다"가 아니라 조회 자체를 하지 않아야 한다(#1145 §4).
        verifyNoInteractions(userPlanRepository);
        verifyNoInteractions(planExpiryWriter);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("Properties 기본값은 비활성 + DRY_RUN + 유예 0일 + 상한 50이며, 강등은 enabled·ENFORCE 둘 다여야 일어난다")
    void 기본값_고정() {
        PlanExpiryProperties defaults = new PlanExpiryProperties();

        assertThat(defaults.isEnabled())
                .as("기본 활성이면 프리플라이트 전에 기존 유료 회사가 일괄 강등된다(#1145 §2-1)")
                .isFalse();
        assertThat(defaults.getMode())
                .as("enabled 를 켜자마자 곧바로 강등되면 안 된다 — 관찰 단계를 기본값으로 둔다")
                .isEqualTo(Mode.DRY_RUN);
        assertThat(defaults.getGracePeriod()).isEqualTo(Duration.ZERO);
        assertThat(defaults.getNotBefore()).isNull();
        assertThat(defaults.getMaxPerRun()).isEqualTo(50);
        assertThat(defaults.isEnforcing()).isFalse();

        defaults.setEnabled(true);
        assertThat(defaults.isEnforcing())
                .as("enabled 만으로는 강등되지 않는다")
                .isFalse();
        defaults.setMode(Mode.ENFORCE);
        assertThat(defaults.isEnforcing()).isTrue();
    }

    // ── 안전장치 2: DRY_RUN ─────────────────────────────────────────────

    @Test
    @DisplayName("DRY_RUN이면 대상을 조회해 id 목록만 로그로 남기고 단 한 건도 강등하지 않는다")
    void dryRun이면_강등하지_않고_대상만_보고한다() {
        properties.setMode(Mode.DRY_RUN);
        stubTargets(2L, List.of(11L, 12L));

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        verifyNoInteractions(planExpiryWriter);
        verifyNoInteractions(notificationService);
        assertThat(loggedAt(appender, Level.WARN, "DRY_RUN"))
                .as("관찰 모드로 돌고 있다는 사실이 로그에 드러나야 운영자가 승격 시점을 판단할 수 있다")
                .isTrue();
        // 운영자가 결제 이력과 대조할 수 있도록 대상 id 가 실제로 찍혀야 한다.
        assertThat(loggedAt(appender, Level.WARN, "11")).isTrue();
        assertThat(loggedAt(appender, Level.WARN, "12")).isTrue();
    }

    @Test
    @DisplayName("DRY_RUN이라도 상한 초과면 대상 목록을 뽑지 않고 먼저 중단한다")
    void dryRun에서도_상한초과가_우선한다() {
        properties.setMode(Mode.DRY_RUN);
        properties.setMaxPerRun(1);
        when(userPlanRepository.countExpiryTargets(any(), any(), any())).thenReturn(5L);

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        verify(userPlanRepository, never()).findExpiryTargetIds(any(), any(), any(), anyLong(), any());
        assertThat(loggedAt(appender, Level.ERROR, "1회 상한")).isTrue();
    }

    // ── 안전장치 3: not-before 컷오프 ────────────────────────────────────

    @Test
    @DisplayName("not-before가 설정되면 조회 조건으로 그대로 전달된다(과거 백필 구간을 쿼리 단계에서 배제)")
    void notBefore가_조회조건으로_전달된다() {
        Instant cutoff = Instant.parse("2026-08-01T00:00:00Z");
        properties.setNotBefore(cutoff);
        stubTargets(1L, List.of(1L));
        when(planExpiryWriter.expireToFreePlan(anyLong(), any())).thenReturn(downgraded(7L));

        scheduler.expireOverduePlans();

        ArgumentCaptor<Instant> notBefore = ArgumentCaptor.forClass(Instant.class);
        verify(userPlanRepository).countExpiryTargets(any(), any(), notBefore.capture());
        assertThat(notBefore.getValue()).isEqualTo(cutoff);
        verify(userPlanRepository).findExpiryTargetIds(any(), any(), eq(cutoff), eq(0L), any());
    }

    @Test
    @DisplayName("not-before가 비어 있으면 null로 전달되어 제한 없이 조회한다(하위 호환)")
    void notBefore가_없으면_null로_전달된다() {
        stubTargets(1L, List.of(1L));
        when(planExpiryWriter.expireToFreePlan(anyLong(), any())).thenReturn(downgraded(7L));

        scheduler.expireOverduePlans();

        verify(userPlanRepository).countExpiryTargets(any(), any(), isNull());
        verify(userPlanRepository).findExpiryTargetIds(any(), any(), isNull(), eq(0L), any());
    }

    // ── 안전장치 4: max-per-run ─────────────────────────────────────────

    @Test
    @DisplayName("대상이 1회 상한을 넘으면 단 한 건도 강등하지 않고 ERROR 로그 후 중단한다(부분 강등 금지)")
    void 상한초과시_0건처리_ERROR로그() {
        properties.setMaxPerRun(50);
        when(userPlanRepository.countExpiryTargets(any(), any(), any())).thenReturn(51L);

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        // 대상 목록 조회조차 하지 않는다 — 부분 강등이 남을 여지를 원천 차단.
        verify(userPlanRepository, never()).findExpiryTargetIds(any(), any(), any(), anyLong(), any());
        verifyNoInteractions(planExpiryWriter);
        verifyNoInteractions(notificationService);
        assertThat(loggedAt(appender, Level.ERROR, "1회 상한"))
                .as("상한 초과는 정상 운영이 아니라 사고 신호라 ERROR로 표면화해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("대상 건수가 상한과 같으면(경계) 정상 처리한다")
    void 상한과_같으면_처리한다() {
        properties.setMaxPerRun(2);
        stubTargets(2L, List.of(11L, 12L));
        when(planExpiryWriter.expireToFreePlan(anyLong(), any())).thenReturn(downgraded(7L));

        scheduler.expireOverduePlans();

        verify(planExpiryWriter, times(2)).expireToFreePlan(anyLong(), any());
    }

    @Test
    @DisplayName("사전 건수 검사 이후 대상이 늘어나도 1회 상한을 넘겨 강등하지 않는다(2차 방어)")
    void 순회중에도_상한을_넘기지_않는다() {
        properties.setMaxPerRun(2);
        // count 는 상한 이내(2)로 통과시키고, 실제 목록은 그보다 많은 3건을 돌려준다
        // (사전 검사와 순회 사이에 새로 만료된 구독이 끼어든 상황).
        when(userPlanRepository.countExpiryTargets(any(), any(), any())).thenReturn(2L);
        when(userPlanRepository.findExpiryTargetIds(any(), any(), any(), eq(0L), any()))
                .thenReturn(List.of(1L, 2L, 3L));
        when(planExpiryWriter.expireToFreePlan(anyLong(), any())).thenReturn(downgraded(7L));

        scheduler.expireOverduePlans();

        verify(planExpiryWriter, times(2)).expireToFreePlan(anyLong(), any());
        verify(planExpiryWriter, never()).expireToFreePlan(eq(3L), any());
    }

    // ── 설정값 검증(기동 실패) ───────────────────────────────────────────

    @Test
    @DisplayName("유예 기간이 음수면 기동이 실패한다 — 기준시각이 미래가 되어 유효한 구독까지 강등된다")
    void 음수_유예는_기동실패() {
        PlanExpiryProperties invalid = new PlanExpiryProperties();
        invalid.setGracePeriod(Duration.ofDays(-30));

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            assertThat(validator.validate(invalid))
                    .as("부호 오타(-P30D)가 조용히 통과하면 아직 유효한 유료 구독이 대상이 된다")
                    .isNotEmpty();

            invalid.setGracePeriod(Duration.ZERO);
            assertThat(validator.validate(invalid)).isEmpty();
        }
    }

    @Test
    @DisplayName("1회 상한이 0 이하이면 기동이 실패한다")
    void 상한_0이하는_기동실패() {
        PlanExpiryProperties invalid = new PlanExpiryProperties();
        invalid.setMaxPerRun(0);

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(invalid)).isNotEmpty();
        }
    }

    // ── 대상 판정 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("대상 상태 집합은 ACTIVE + UPGRADE_REQUESTED다 — 업그레이드 문의로 만료를 회피할 수 없다")
    void 대상은_ACTIVE와_UPGRADE_REQUESTED다() {
        stubTargets(1L, List.of(1L));
        when(planExpiryWriter.expireToFreePlan(anyLong(), any())).thenReturn(downgraded(7L));

        scheduler.expireOverduePlans();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UserPlanStatus>> statuses =
                ArgumentCaptor.forClass(Collection.class);
        verify(userPlanRepository).countExpiryTargets(statuses.capture(), any(), any());
        assertThat(statuses.getValue())
                .as("QuotaService#findLivePlan 이 유료 한도를 계속 주는 상태 집합과 정확히 같아야 한다 — "
                        + "ACTIVE 만 보면 upgrade-inquiry 1회 호출로 만료 강제를 영구 회피할 수 있다")
                .containsExactlyInAnyOrder(UserPlanStatus.ACTIVE, UserPlanStatus.UPGRADE_REQUESTED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UserPlanStatus>> listStatuses =
                ArgumentCaptor.forClass(Collection.class);
        verify(userPlanRepository)
                .findExpiryTargetIds(listStatuses.capture(), any(), any(), anyLong(), any());
        assertThat(listStatuses.getValue())
                .containsExactlyInAnyOrder(UserPlanStatus.ACTIVE, UserPlanStatus.UPGRADE_REQUESTED);
    }

    @Test
    @DisplayName("대상 0건이면 강등도 알림도 없다")
    void 대상없으면_아무일도_없다() {
        when(userPlanRepository.countExpiryTargets(any(), any(), any())).thenReturn(0L);

        scheduler.expireOverduePlans();

        verify(userPlanRepository, never()).findExpiryTargetIds(any(), any(), any(), anyLong(), any());
        verifyNoInteractions(planExpiryWriter);
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("만료 판정 기준시각은 now - gracePeriod 이며 Instant 그대로 비교한다(KST 새벽 구간에서도 날짜로 잘리지 않는다)")
    void 기준시각은_now빼기유예_그대로다() {
        properties.setGracePeriod(Duration.ofDays(3));
        when(userPlanRepository.countExpiryTargets(any(), any(), any())).thenReturn(0L);

        scheduler.expireOverduePlans();

        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        verify(userPlanRepository).countExpiryTargets(any(), threshold.capture(), any());
        // NOW 는 KST 2026-07-28T00:30(=UTC 전날 15:30) — 존을 잘못 타 날짜로 절삭하면 값이 달라진다.
        assertThat(threshold.getValue()).isEqualTo(NOW.minus(Duration.ofDays(3)));
    }

    @Test
    @DisplayName("유예 0일(기본)이면 기준시각이 곧 현재 시각이다 — 만료 즉시 강등")
    void 유예0일이면_기준시각은_현재시각() {
        when(userPlanRepository.countExpiryTargets(any(), any(), any())).thenReturn(0L);

        scheduler.expireOverduePlans();

        ArgumentCaptor<Instant> threshold = ArgumentCaptor.forClass(Instant.class);
        verify(userPlanRepository).countExpiryTargets(any(), threshold.capture(), any());
        assertThat(threshold.getValue()).isEqualTo(NOW);
    }

    // ── 알림·장애 격리 ───────────────────────────────────────────────────

    @Test
    @DisplayName("강등 1건마다 PLAN_EXPIRED 알림을 정확히 1건 발행한다")
    void 강등시_알림_1건_발행() {
        stubTargets(1L, List.of(42L));
        when(planExpiryWriter.expireToFreePlan(eq(42L), any())).thenReturn(downgraded(7L));

        scheduler.expireOverduePlans();

        verify(notificationService, times(1))
                .notify(eq(7L), eq(NotificationType.PLAN_EXPIRED), anyString());
    }

    @Test
    @DisplayName("writer가 스킵을 반환하면(다른 경로가 먼저 전이) 알림을 발행하지 않는다")
    void 스킵이면_알림없음() {
        stubTargets(1L, List.of(42L));
        when(planExpiryWriter.expireToFreePlan(eq(42L), any()))
                .thenReturn(PlanExpiryResult.skipped("결제 주기가 갱신됨"));

        scheduler.expireOverduePlans();

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("한 건의 실패가 나머지 건 처리를 막지 않는다(건별 독립 트랜잭션)")
    void 한건_실패해도_나머지_계속() {
        stubTargets(3L, List.of(1L, 2L, 3L));
        when(planExpiryWriter.expireToFreePlan(eq(1L), any())).thenReturn(downgraded(7L));
        when(planExpiryWriter.expireToFreePlan(eq(2L), any()))
                .thenThrow(new RuntimeException("전이 실패"));
        when(planExpiryWriter.expireToFreePlan(eq(3L), any())).thenReturn(downgraded(8L));

        assertThatCode(() -> scheduler.expireOverduePlans()).doesNotThrowAnyException();

        verify(planExpiryWriter).expireToFreePlan(eq(3L), any());
        verify(notificationService).notify(eq(7L), eq(NotificationType.PLAN_EXPIRED), anyString());
        verify(notificationService).notify(eq(8L), eq(NotificationType.PLAN_EXPIRED), anyString());
        // 실패한 2번 건은 강등되지 않았으므로 알림도 나가지 않는다(총 2건).
        verify(notificationService, times(2)).notify(anyLong(), any(), anyString());
    }

    // ── 예외 분류(리뷰 P2-1 / P2-2) ─────────────────────────────────────

    @Test
    @DisplayName("활성 구독 경합(PLAN_ACTIVE_SUBSCRIPTION_CONFLICT)만 조용히 skip한다")
    void 활성구독_경합은_스킵() {
        stubTargets(2L, List.of(1L, 2L));
        when(planExpiryWriter.expireToFreePlan(eq(1L), any()))
                .thenThrow(new BusinessException(ErrorCode.PLAN_ACTIVE_SUBSCRIPTION_CONFLICT));
        when(planExpiryWriter.expireToFreePlan(eq(2L), any())).thenReturn(downgraded(7L));

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        verify(notificationService, times(1))
                .notify(eq(7L), eq(NotificationType.PLAN_EXPIRED), anyString());
        assertThat(loggedAt(appender, Level.INFO, "활성 구독 경합")).isTrue();
        assertThat(loggedAt(appender, Level.WARN, "실패 1건"))
                .as("경합은 실패가 아니라 스킵이다")
                .isFalse();
    }

    @Test
    @DisplayName("경합이 아닌 도메인 예외는 실패로 집계하고 ErrorCode를 로그에 남긴다(영구 실패 진단 가능)")
    void 도메인예외는_errorCode를_남긴다() {
        stubTargets(1L, List.of(1L));
        // FREE(max_seats=1)로 내릴 때 ACTIVE ADMIN 이 남지 않는 회사 — 매일 밤 같은 예외로 영구 실패한다.
        when(planExpiryWriter.expireToFreePlan(eq(1L), any()))
                .thenThrow(new BusinessException(ErrorCode.ADMIN_PROTECTED_ACCOUNT));

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        assertThat(loggedAt(appender, Level.WARN, ErrorCode.ADMIN_PROTECTED_ACCOUNT.name()))
                .as("ErrorCode 를 버리면 운영자가 로그만으로 영구 실패의 원인을 좁힐 수 없다")
                .isTrue();
        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("예상 밖 무결성 위반은 조용한 skip이 아니라 실패로 집계하고 원인 메시지를 남긴다")
    void 예상밖_무결성위반은_실패로_집계() {
        stubTargets(1L, List.of(1L));
        when(planExpiryWriter.expireToFreePlan(eq(1L), any()))
                .thenThrow(new DataIntegrityViolationException("fk_usage_counters_user_plan"));

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        assertThat(loggedAt(appender, Level.WARN, "예상 밖 무결성 제약 위반"))
                .as("사용량 이월·좌석 정지의 무결성 위반까지 '경합 스킵'으로 삼키면 진짜 데이터 결함이 "
                        + "매일 밤 INFO 한 줄로 사라진다")
                .isTrue();
        assertThat(loggedAt(appender, Level.INFO, "활성 구독 경합")).isFalse();
    }

    @Test
    @DisplayName("실패가 하나라도 있으면 회차 요약을 WARN 이상으로 남긴다")
    void 실패가_있으면_요약을_WARN으로() {
        stubTargets(1L, List.of(1L));
        when(planExpiryWriter.expireToFreePlan(eq(1L), any()))
                .thenThrow(new RuntimeException("전이 실패"));

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        assertThat(loggedAt(appender, Level.WARN, "배치 완료"))
                .as("실패가 INFO 요약에 묻히면 매일 밤 반복되는 영구 실패를 아무도 눈치채지 못한다")
                .isTrue();
    }

    @Test
    @DisplayName("실패가 없으면 회차 요약은 INFO로 남긴다")
    void 정상회차_요약은_INFO() {
        stubTargets(1L, List.of(1L));
        when(planExpiryWriter.expireToFreePlan(eq(1L), any())).thenReturn(downgraded(7L));

        ListAppender<ILoggingEvent> appender = runCapturingLogs();

        assertThat(loggedAt(appender, Level.INFO, "배치 완료")).isTrue();
        assertThat(loggedAt(appender, Level.WARN, "배치 완료")).isFalse();
    }

    @Test
    @DisplayName("알림 발행이 실패해도 이미 커밋된 강등을 되돌리지 않고 배치는 계속된다")
    void 알림실패해도_배치는_계속() {
        stubTargets(2L, List.of(1L, 2L));
        when(planExpiryWriter.expireToFreePlan(anyLong(), any())).thenReturn(downgraded(7L));
        doThrow(new RuntimeException("알림 발행 실패")).doNothing()
                .when(notificationService).notify(anyLong(), any(), anyString());

        assertThatCode(() -> scheduler.expireOverduePlans()).doesNotThrowAnyException();

        verify(planExpiryWriter).expireToFreePlan(eq(2L), any());
    }

    // ── 순회 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("페이지가 꽉 차면 마지막 id를 기준(keyset)으로 다음 페이지를 이어 조회한다")
    void keyset_페이징으로_다음페이지를_읽는다() {
        int pageSize = 50;
        List<Long> firstPage = LongStream.rangeClosed(1, pageSize).boxed().toList();
        properties.setMaxPerRun(200);
        when(userPlanRepository.countExpiryTargets(any(), any(), any())).thenReturn(52L);
        when(userPlanRepository.findExpiryTargetIds(any(), any(), any(), eq(0L), any()))
                .thenReturn(firstPage);
        when(userPlanRepository.findExpiryTargetIds(any(), any(), any(), eq((long) pageSize), any()))
                .thenReturn(List.of(51L, 52L));
        when(planExpiryWriter.expireToFreePlan(anyLong(), any())).thenReturn(downgraded(7L));

        scheduler.expireOverduePlans();

        // offset 페이징이었다면 처리로 결과집합이 앞으로 당겨져 51·52가 통째로 누락됐을 자리다.
        verify(planExpiryWriter).expireToFreePlan(eq(51L), any());
        verify(planExpiryWriter).expireToFreePlan(eq(52L), any());
        verify(planExpiryWriter, times(52)).expireToFreePlan(anyLong(), any());
    }
}
