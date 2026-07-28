package com.hajacheck.core.facility.scheduler;

import com.hajacheck.auth.service.CompanyOwnerLookupService;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.entity.InspectionNotificationSetting;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.facility.repository.InspectionNotificationSettingRepository;
import com.hajacheck.core.facility.scheduler.InspectionDueNotificationPayload.Kind;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import com.hajacheck.notification.service.NotificationService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 점검 예정일 관련 알림(사전/당일/경과)을 발행하는 일 배치(NOTI-01, #425 / 알림설정 게이팅, #540 ③ /
 * DB 유니크 제약 기반 멱등성 전환, #1050).
 *
 * <p>매일 06:00(KST) 실행. {@code next_inspection_due_at <= 오늘 + MAX_NOTIFY_BEFORE_DAYS}인 시설물을
 * 페이지 단위로 순회하며, 시설물 소유자(회사 소유자)의 {@link InspectionNotificationSetting}(사용자·시설
 * 조합별 알림설정)을 조회해 다음 세 게이트를 적용한다(#540 ③, 3종 분리는 사람검수 P2 #1032 — 상호
 * 배타적, 같은 시설물이 한 배치에서 둘 이상 동시에 만족할 수 없다):
 * <ul>
 *   <li>{@link Kind#BEFORE} — notifyBeforeEnabled 이고 {@code dueAt - notifyBeforeDays <= 오늘 < dueAt}
 *       (사전 알림 창 안, 아직 당일 아님).</li>
 *   <li>{@link Kind#DUE} — {@code dueAt == 오늘}(예정일 당일). notifyBeforeEnabled 설정과 <b>무관하게
 *       항상 발행</b>된다 — main의 기존 배포 동작({@code dueAt <= 오늘}이면 무조건 당일 발행)을 복원한
 *       것이다(사람검수 P2 #1032 최우선 회귀 수정). BEFORE와 DUE를 예전처럼 같은 Kind로 묶으면 dedupe
 *       키가 dueAt 기준 불변이라, 사전창에서 이미 1회 발행된 뒤 당일에도 같은 키로 스킵돼 "당일 발행"
 *       자체가 사라지는 회귀가 있었다.</li>
 *   <li>{@link Kind#OVERDUE} — warnOnOverdueEnabled 이고 {@code dueAt < 오늘}(예정일 경과).</li>
 * </ul>
 * 설정 행이 없는(사용자가 한 번도 저장한 적 없는) 시설물은 DB 컬럼 기본값과 동일한 기본값
 * (notifyBeforeEnabled=true, notifyBeforeDays=7, warnOnOverdueEnabled=true — HAJA-498/V21)으로
 * 취급한다(InspectionNotificationSettingResponse.defaults()와 동일 값 — 두 곳이 어긋나면 "설정 미저장
 * 상태"의 실제 배치 동작과 조회 API 응답이 서로 달라진다). 단, {@link Kind#DUE} 게이트는 이 설정과
 * 무관하게 항상 통과한다(위 참고).
 *
 * <p>⚠️ warnOnOverdueEnabled 기본값 이력(HAJA-498): #540 ③ 최초 도입 시 false로 시작했다가, "예정일이
 * 지난 시설물은 설정을 켜지 않는 한 더 이상 알림이 발행되지 않는" 회귀가 발견됐다. 기존에는
 * {@code dueAt <= 오늘}이면 당일/연체 구분 없이 항상 발행했었기 때문이다. Polalise 승인(옵션1)으로
 * V21에서 컬럼 DEFAULT와 이 폴백값을 true로 되돌려 원래 동작을 복원했다.
 *
 * <p><b>멱등성 = DB 유니크 제약 기반(#1050)</b>. V25 마이그레이션이 {@code notifications} 테이블에
 * {@code (user_id, payload_json->>'facilityId', payload_json->>'nextInspectionDueAt',
 * payload_json->>'kind')} 조합의 부분 유니크 인덱스({@code uq_notifications_inspection_due_dedupe},
 * {@code WHERE type='INSPECTION_DUE'})를 추가했다. 이제 이 배치는 <b>사전 조회 없이 바로 발행을
 * 시도</b>하고, DB가 unique violation({@link DataIntegrityViolationException})을 던지면 "이미
 * 발행됨"으로 간주해 조용히 skip한다({@code com.hajacheck.core.inspection.service.InspectionService}의
 * 회차 채번 unique violation 처리와
 * 동일한 catch 패턴 — 다만 거긴 폴백 응답이고 여긴 skip이 맞다). kind를 dedupe 키에 포함한 이유는
 * 여전히 유효하다 — BEFORE·DUE·OVERDUE는 같은 (facilityId, dueAt) 조합에 대해서도 서로 다른 정보
 * 전달 시점이라, 한쪽을 보냈다고 다른 쪽까지 막히면 안 된다(#540 ③ 코드리뷰 지적, 3종 분리는 사람검수
 * P2 #1032).
 *
 * <p>⚠️ <b>레거시(kind 없는) payload 사각지대</b>: 위 유니크 인덱스는 {@code payload_json->>'kind'}가
 * NULL인 행을 원자적으로 방어하지 못한다(PostgreSQL unique index는 NULL을 서로 다른 값으로 취급).
 * {@code kind} 필드가 없는 #540 이전 저장분이 이 사각지대에 해당하므로, 이 배치는 DB insert를
 * 시도하기 전에 먼저 {@link NotificationRepository#findLegacyKindLessInspectionDueByUserIdIn}로
 * 좁힌 애플리케이션 레벨 체크를 한 번 거친다(#1056이 고친 "구 payload가 kind 없이 DUE/OVERDUE 둘 다로
 * 매칭돼야 한다"는 하위호환 규칙은 여기서도 그대로 적용 — {@link InspectionDueNotificationPayload
 * #extractDedupeKey} 참고). 이 조회 대상은 #540 배포 이후로 늘어나지 않는 유한 집합이라(신규 발행은
 * 항상 kind를 채움) 날짜 윈도우 없이 조회해도 무제한 누적 문제가 재발하지 않는다.
 *
 * <p>⚠️ 이 메서드/클래스에는 {@code @Transactional}을 붙이지 않는다 —
 * {@link NotificationService#notify}가 자체 트랜잭션({@code REQUIRES_NEW})을 가져 시설물별로 독립
 * 커밋되게 하려는 의도적 설계다. 이 덕분에 한 시설물의 unique violation(트랜잭션 rollback)이 다른
 * 시설물의 발행에 영향을 주지 않는다.
 *
 * <p>✅ <b>다중 인스턴스 스케일아웃 안전(#1050의 핵심 이득)</b>: 멱등성이 이제 DB 유니크 제약(원자적
 * 연산)으로 보장되므로, 여러 인스턴스가 동시에 같은 시설물을 처리해도 먼저 커밋한 쪽만 성공하고
 * 나머지는 unique violation으로 skip된다 — 더 이상 read-then-write 레이스가 없다. (레거시 kind-null
 * 사각지대 체크는 여전히 조회 기반이라 그 좁은 범위에서는 이론상 레이스가 남지만, 대상 집합이 #540
 * 이전 데이터로 고정돼 있어 신규 트래픽에는 영향이 없다.)
 *
 * <p>스캔 비용: {@code next_inspection_due_at}는 V9 마이그레이션(#509)에서 부분 인덱스가 추가돼 있어
 * (idx_facilities_next_inspection_due_at) 이 조건의 범위 스캔 자체는 인덱스를 탄다. #540 ③으로 스캔
 * 상한이 "오늘"에서 "오늘 + 최대 365일(notify_before_days 상한)"로 넓어져, 알림설정과 무관하게 도래일이
 * 향후 1년 내인 시설물까지 매일 페이지 조회 대상에 포함된다(대부분은 게이트 조건 불충족으로 skipped
 * 처리). overdue 시설물도 기존과 동일하게 재스케줄 전까지 매일 재조회 대상에 잔류한다 — 근본적으로
 * 스캔 폭을 줄이려면 알림설정을 DB 쿼리 조건에 조인하는 구조가 필요하나, 사용자별로 값이 달라 단순
 * 조인만으로는 안 되고 별도 설계가 필요해 이번 범위 밖(후속 이슈로 분리).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InspectionDueNotificationScheduler {

    /** 전역 대상 시설물을 한 번에 다 로딩하지 않도록 페이지 단위로 끊어 순회한다. */
    private static final int PAGE_SIZE = 200;

    // 사전 알림 최대 창(#540 ③) — inspection_notification_settings.notify_before_days 체크 제약
    // (1~365)의 상한과 동일. 이보다 넓게 스캔해도 어차피 어떤 설정으로도 도달할 수 없는 대상이라 의미가 없다.
    private static final int MAX_NOTIFY_BEFORE_DAYS = 365;

    // 알림설정 행이 없을 때(사용자가 한 번도 저장한 적 없는 시설물) 적용하는 기본값 — DB 컬럼 기본값
    // 및 InspectionNotificationSettingResponse.defaults()와 반드시 동일해야 한다.
    private static final boolean DEFAULT_NOTIFY_BEFORE_ENABLED = true;
    private static final int DEFAULT_NOTIFY_BEFORE_DAYS = 7;
    private static final boolean DEFAULT_WARN_ON_OVERDUE_ENABLED = true;

    // V25 마이그레이션이 만든 dedupe 유니크 인덱스명(#1050) — DataIntegrityViolationException의 원인
    // 메시지가 이 이름을 포함하는지로 "예상된 dedupe 충돌"과 "그 외 무결성 위반"을 구분한다. 인덱스명이
    // 바뀌면 이 상수도 함께 갱신해야 한다.
    private static final String DEDUPE_UNIQUE_INDEX_NAME = "uq_notifications_inspection_due_dedupe";

    private final FacilityRepository facilityRepository;
    private final InspectionNotificationSettingRepository notificationSettingRepository;
    private final CompanyOwnerLookupService companyOwnerLookupService;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    public void notifyFacilitiesDueToday() {
        LocalDate today = LocalDate.now(clock);
        LocalDate maxScanDueAt = today.plusDays(MAX_NOTIFY_BEFORE_DAYS);

        long totalTargets = 0;
        BatchCounts totals = BatchCounts.ZERO;

        int pageNumber = 0;
        Slice<Facility> page;
        do {
            page = facilityRepository.findAllByNextInspectionDueAtLessThanEqualOrderByIdAsc(
                    maxScanDueAt, PageRequest.of(pageNumber, PAGE_SIZE));
            List<Facility> facilities = page.getContent();
            totalTargets += facilities.size();
            if (!facilities.isEmpty()) {
                totals = totals.plus(processPage(facilities, pageNumber, today));
            }
            pageNumber++;
        } while (page.hasNext());

        log.info("INSPECTION_DUE 알림 배치 완료 — 대상 {}건, 발행 {}건, 스킵 {}건, 실패 {}건",
                totalTargets, totals.published(), totals.skipped(), totals.failed());
    }

    private BatchCounts processPage(List<Facility> facilities, int pageNumber, LocalDate today) {
        Set<Long> companyIds = facilities.stream()
                .map(Facility::getCompanyId)
                .collect(Collectors.toSet());
        Map<Long, Long> ownerUserIdByCompany = companyOwnerLookupService.findOwnerUserIds(companyIds);
        Set<Long> ownerUserIds = Set.copyOf(ownerUserIdByCompany.values());

        // 레거시(kind 없음) payload dedupe 체크(#1050) — V25 유니크 인덱스가 못 잡는 좁은 사각지대만
        // 대상으로 한다(#540 배포 이후로 늘어나지 않는 유한 집합 — NotificationRepository 참고).
        Map<Long, Set<String>> legacyKeysByOwner;
        try {
            legacyKeysByOwner = notificationRepository
                    .findLegacyKindLessInspectionDueByUserIdIn(ownerUserIds)
                    .stream()
                    .collect(Collectors.groupingBy(
                            Notification::getUserId,
                            Collectors.flatMapping(
                                    n -> InspectionDueNotificationPayload.extractDedupeKey(n.getPayloadJson()).stream(),
                                    Collectors.toSet())));
        } catch (Exception e) {
            // 1회 배치 조회라 owner별로 나눠 처리할 수 없다 — 멱등성 보장 불가한 이 페이지 전체를 스킵하고 다음 페이지로.
            log.warn("INSPECTION_DUE 레거시 알림 조회 실패 — page={} 전체 스킵 exception={}",
                    pageNumber, e.getClass().getSimpleName());
            return new BatchCounts(0, 0, facilities.size());
        }

        // 알림설정 배치 조회(#540 ③) — 이 페이지의 (소유자, 시설물) 조합에 대한 설정 행을 한 번에 가져와
        // N+1을 방지한다. findAllByUserIdInAndFacilityIdIn 은 IN×IN 이라 정확한 페어 매칭이 아니므로,
        // settingKey(userId, facilityId) 로 정확히 재매칭한 뒤 사용한다.
        Map<String, InspectionNotificationSetting> settingByKey;
        try {
            List<Long> facilityIds = facilities.stream().map(Facility::getId).toList();
            settingByKey = notificationSettingRepository
                    .findAllByUserIdInAndFacilityIdIn(List.copyOf(ownerUserIds), facilityIds)
                    .stream()
                    .collect(Collectors.toMap(
                            s -> settingKey(s.getUserId(), s.getFacilityId()),
                            s -> s));
        } catch (Exception e) {
            // 설정 조회 실패는 배치를 막지 않는다 — 이 페이지는 전부 기본값(하위호환)으로 게이팅한다.
            log.warn("알림설정 배치 조회 실패 — page={} 기본값으로 대체 exception={}",
                    pageNumber, e.getClass().getSimpleName());
            settingByKey = Map.of();
        }

        int published = 0;
        int skipped = 0;
        int failed = 0;
        for (Facility facility : facilities) {
            Long recipientUserId = ownerUserIdByCompany.get(facility.getCompanyId());
            if (recipientUserId == null) {
                failed++;
                log.warn("INSPECTION_DUE 회사 소유자 조회 실패 — facilityId={} companyId={}",
                        facility.getId(), facility.getCompanyId());
                continue;
            }
            LocalDate dueAt = facility.getNextInspectionDueAt();
            if (dueAt == null) {
                // 쿼리 조건(<= maxScanDueAt)상 이론적으로 발생하지 않지만 방어적으로 스킵.
                continue;
            }

            InspectionNotificationSetting setting = settingByKey.get(settingKey(recipientUserId, facility.getId()));
            // notify_before_days 는 엔티티상 Short(DB smallint) — 게이팅 계산은 int 로 명시 변환해 수행한다.
            boolean notifyBeforeEnabled = setting != null
                    ? setting.isNotifyBeforeEnabled() : DEFAULT_NOTIFY_BEFORE_ENABLED;
            int notifyBeforeDays = setting != null
                    ? setting.getNotifyBeforeDays().intValue() : DEFAULT_NOTIFY_BEFORE_DAYS;
            boolean warnOnOverdueEnabled = setting != null
                    ? setting.isWarnOnOverdueEnabled() : DEFAULT_WARN_ON_OVERDUE_ENABLED;
            Kind kind = resolveKind(dueAt, today, notifyBeforeEnabled, notifyBeforeDays, warnOnOverdueEnabled);
            if (kind == null) {
                skipped++;
                continue;
            }

            Set<String> legacyKeys = legacyKeysByOwner.getOrDefault(recipientUserId, Set.of());
            if (legacyKeys.contains(InspectionDueNotificationPayload.dedupeKeyOf(facility, kind))) {
                skipped++;
                continue;
            }

            // #1050 — 조회 기반 dedupe 대신 바로 발행을 시도하고, DB 유니크 인덱스가 unique violation을
            // 던지면 "이미 발행됨"으로 간주해 skip한다(InspectionService.createInspection의 회차 채번
            // unique violation 처리와 동일한 catch 패턴). notify()가 REQUIRES_NEW로 독립 트랜잭션을 열어
            // 커밋하므로, 여기서 던져지는 예외는 이 시설물의 발행 시도만 롤백시키고 다른 시설물에는
            // 영향을 주지 않는다.
            try {
                notificationService.notify(recipientUserId, NotificationType.INSPECTION_DUE,
                        InspectionDueNotificationPayload.serialize(facility, kind));
                published++;
            } catch (DataIntegrityViolationException e) {
                // JPA는 유니크 위반과 FK/NOT NULL/CHECK 위반 등 모든 무결성 오류를 이 예외 하나로
                // 뭉뚱그리므로(하위 타입으로 세분화되지 않음), 제약명을 직접 확인해 dedupe 인덱스 위반만
                // "이미 발행됨"으로 skip 처리한다. 그 외(예: user_id FK 정합성 깨짐)는 실제 발행 실패이므로
                // failed로 집계하고 warn으로 표면화한다(코드리뷰 P1/P2 반영 — 조용한 알림 유실 방지).
                String cause = e.getMostSpecificCause().getMessage();
                if (cause != null && cause.contains(DEDUPE_UNIQUE_INDEX_NAME)) {
                    skipped++;
                } else {
                    failed++;
                    log.warn("INSPECTION_DUE 알림 발행 시 예상 밖 무결성 제약 위반(dedupe 인덱스 아님) — "
                            + "facilityId={} kind={} exception={}",
                            facility.getId(), kind, cause);
                }
            } catch (Exception e) {
                // 시설물 1건 실패를 격리 — 같은 owner의 나머지 시설물 처리는 계속한다.
                failed++;
                log.warn("INSPECTION_DUE 알림 발행 실패 — facilityId={} exception={}",
                        facility.getId(), e.getClass().getSimpleName());
            }
        }
        return new BatchCounts(published, skipped, failed);
    }

    /**
     * dueAt/today/알림설정으로부터 발행할 알림 종류를 정한다(#540 ③, 3종 분리는 사람검수 P2 #1032).
     * 세 게이트는 상호 배타적이다(OVERDUE는 dueAt &lt; today, DUE는 dueAt == today, BEFORE는
     * dueAt &gt; today) — 같은 시설물이 한 번의 배치 실행에서 둘 이상을 동시에 만족할 수 없다. 어느
     * 쪽도 만족하지 못하면 null(이번 실행에서는 발행 대상 아님).
     *
     * <p>⚠️ DUE(dueAt == today)는 notifyBeforeEnabled 값과 무관하게 항상 반환한다 — main의 기존
     * 배포 동작({@code dueAt <= today}면 무조건 당일 발행)을 그대로 복원해야 하기 때문이다(사람검수
     * P2 #1032). 사전창(BEFORE)만 notifyBeforeEnabled 게이팅의 대상이다.
     */
    private static Kind resolveKind(LocalDate dueAt, LocalDate today, boolean notifyBeforeEnabled,
            int notifyBeforeDays, boolean warnOnOverdueEnabled) {
        if (dueAt.isBefore(today)) {
            return warnOnOverdueEnabled ? Kind.OVERDUE : null;
        }
        if (dueAt.isEqual(today)) {
            return Kind.DUE;
        }
        // dueAt > today
        if (notifyBeforeEnabled && !dueAt.minusDays(notifyBeforeDays).isAfter(today)) {
            return Kind.BEFORE;
        }
        return null;
    }

    private static String settingKey(Long userId, Long facilityId) {
        return userId + ":" + facilityId;
    }

    /** 페이지별 처리 결과 누적용(발행/스킵/실패 건수). */
    private record BatchCounts(int published, int skipped, int failed) {

        private static final BatchCounts ZERO = new BatchCounts(0, 0, 0);

        private BatchCounts plus(BatchCounts other) {
            return new BatchCounts(
                    published + other.published,
                    skipped + other.skipped,
                    failed + other.failed);
        }
    }
}
