package com.hajacheck.core.facility.scheduler;

import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.notification.entity.Notification;
import com.hajacheck.notification.entity.NotificationType;
import com.hajacheck.notification.repository.NotificationRepository;
import com.hajacheck.notification.service.NotificationService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 점검 예정일이 도래한 시설물의 소유자에게 INSPECTION_DUE 알림을 발행하는 일 배치(NOTI-01, #425).
 *
 * <p>매일 06:00(KST) 실행. {@code next_inspection_due_at <= 오늘}인(overdue 포함) 시설물을 페이지 단위로 순회하며,
 * 각 시설물의 <b>현재 도래일</b>로 이미 INSPECTION_DUE가 발행됐으면 건너뛴다(멱등, 도래일당 1회). 도래일 값이
 * 바뀌지 않는 한(=재스케줄 전까지) overdue 시설물이 매일 재알림되는 스팸이 발생하지 않는다. 소유자별·시설물별로
 * 실패를 격리해 한 건의 실패가 배치 전체를 멈추지 않게 하고, 이 코드베이스 최초의 {@code @Scheduled} 잡이라
 * 운영 관측용 요약 로그를 남긴다.
 *
 * <p>⚠️ 이 메서드/클래스에는 {@code @Transactional}을 붙이지 않는다 —
 * {@link NotificationService#notify}가 자체 트랜잭션을 가져 시설물별로 독립 커밋되게 하려는 의도적 설계다.
 *
 * <p>⚠️ <b>단일 인스턴스 실행 전제</b>: 멱등성은 DB 유니크 제약이 아니라 애플리케이션 레벨 read-then-write
 * (기존 알림 조회 → 없는 것만 발행)로 보장된다. 따라서 다중 인스턴스로 스케일아웃하면 레플리카마다 각자 발화해
 * <b>확정적으로 중복 발행</b>된다. 스케일아웃 시점에는 ShedLock 같은 분산 락 또는 (user_id, type, facility_id, 도래일)
 * DB 유니크 제약 도입이 선행돼야 한다.
 *
 * <p>⚠️ <b>스캔 비용 증가(후속 이슈)</b>: {@code next_inspection_due_at} 무인덱스 + overdue 시설물이
 * 재스케줄 전까지 매일 재조회 대상에 영구 잔류해, 배치 스캔 비용이 시간에 따라 증가한다. 근본 해결은 DB 인덱스
 * (부분 인덱스 권장) 또는 keyset 페이징 — DDL 소유자 조율이 필요해 이번 PR 범위 밖, 후속 이슈로 분리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InspectionDueNotificationScheduler {

    /** 전역 대상 시설물을 한 번에 다 로딩하지 않도록 페이지 단위로 끊어 순회한다. */
    private static final int PAGE_SIZE = 200;

    private final FacilityRepository facilityRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    public void notifyFacilitiesDueToday() {
        LocalDate today = LocalDate.now(clock);

        long totalTargets = 0;
        BatchCounts totals = BatchCounts.ZERO;

        int pageNumber = 0;
        Slice<Facility> page;
        do {
            page = facilityRepository.findAllByNextInspectionDueAtLessThanEqualOrderByIdAsc(
                    today, PageRequest.of(pageNumber, PAGE_SIZE));
            List<Facility> facilities = page.getContent();
            totalTargets += facilities.size();
            if (!facilities.isEmpty()) {
                totals = totals.plus(processPage(facilities, pageNumber));
            }
            pageNumber++;
        } while (page.hasNext());

        log.info("INSPECTION_DUE 알림 배치 완료 — 대상 {}건, 발행 {}건, 스킵 {}건, 실패 {}건",
                totalTargets, totals.published(), totals.skipped(), totals.failed());
    }

    private BatchCounts processPage(List<Facility> facilities, int pageNumber) {
        Set<Long> ownerIds = facilities.stream()
                .map(Facility::getOwnerId)
                .collect(Collectors.toSet());

        // owner당 1쿼리(N+1) 대신, 이 페이지에 등장하는 모든 owner의 기존 알림을 한 번에 조회해
        // owner별 이미-발행 dedupe 키 집합을 만든다.
        Map<Long, Set<String>> alreadyByOwner;
        try {
            alreadyByOwner = notificationRepository
                    .findAllByUserIdInAndType(ownerIds, NotificationType.INSPECTION_DUE)
                    .stream()
                    .collect(Collectors.groupingBy(
                            Notification::getUserId,
                            Collectors.mapping(
                                    n -> InspectionDueNotificationPayload.extractDedupeKey(n.getPayloadJson()),
                                    Collectors.filtering(Objects::nonNull, Collectors.toSet()))));
        } catch (Exception e) {
            // 1회 배치 조회라 owner별로 나눠 처리할 수 없다 — 멱등성 보장 불가한 이 페이지 전체를 스킵하고 다음 페이지로.
            log.warn("INSPECTION_DUE 기존 알림 배치 조회 실패 — page={} 전체 스킵 exception={}",
                    pageNumber, e.getClass().getSimpleName());
            return new BatchCounts(0, 0, facilities.size());
        }

        int published = 0;
        int skipped = 0;
        int failed = 0;
        for (Facility facility : facilities) {
            Set<String> already = alreadyByOwner.getOrDefault(facility.getOwnerId(), Set.of());
            if (already.contains(InspectionDueNotificationPayload.dedupeKeyOf(facility))) {
                skipped++;
                continue;
            }
            try {
                notificationService.notify(facility.getOwnerId(), NotificationType.INSPECTION_DUE,
                        InspectionDueNotificationPayload.serialize(facility));
                published++;
            } catch (Exception e) {
                // 시설물 1건 실패를 격리 — 같은 owner의 나머지 시설물 처리는 계속한다.
                failed++;
                log.warn("INSPECTION_DUE 알림 발행 실패 — facilityId={} exception={}",
                        facility.getId(), e.getClass().getSimpleName());
            }
        }
        return new BatchCounts(published, skipped, failed);
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
