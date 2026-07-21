package com.hajacheck.core.facility.scheduler;

import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 점검 예정일이 도래한 시설물의 소유자에게 INSPECTION_DUE 알림을 발행하는 일 배치(NOTI-01, #425).
 *
 * <p>매일 06:00(KST) 실행. {@code next_inspection_due_at <= 오늘}인(overdue 포함) 시설물을 전 owner 순회하며
 * 같은 날 이미 같은 시설물로 발행된 알림은 건너뛴다(멱등). 소유자별·시설물별로 실패를 격리해 한 건의 실패가
 * 배치 전체를 멈추지 않게 하고, 이 코드베이스 최초의 {@code @Scheduled} 잡이라 운영 관측용 요약 로그를 남긴다.
 *
 * <p>⚠️ 이 메서드/클래스에는 {@code @Transactional}을 붙이지 않는다 —
 * {@link NotificationService#notify}가 자체 트랜잭션을 가져 시설물별로 독립 커밋되게 하려는 의도적 설계다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InspectionDueNotificationScheduler {

    private final FacilityRepository facilityRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    public void notifyFacilitiesDueToday() {
        LocalDate today = LocalDate.now(clock);
        List<Facility> due = facilityRepository.findAllByNextInspectionDueAtLessThanEqual(today);

        int published = 0;
        int skipped = 0;
        int failed = 0;

        Map<Long, List<Facility>> byOwner = due.stream()
                .collect(Collectors.groupingBy(Facility::getOwnerId));

        for (Map.Entry<Long, List<Facility>> entry : byOwner.entrySet()) {
            Long ownerId = entry.getKey();
            List<Facility> facilities = entry.getValue();

            Set<Long> alreadyNotified;
            try {
                alreadyNotified = notificationRepository
                        .findAllByUserIdAndTypeAndCreatedAtBetween(
                                ownerId, NotificationType.INSPECTION_DUE,
                                today.atStartOfDay(), today.plusDays(1).atStartOfDay())
                        .stream()
                        .map(n -> InspectionDueNotificationPayload.extractFacilityId(n.getPayloadJson()))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
            } catch (Exception e) {
                // 이 owner의 기존 알림 조회 자체가 실패하면 멱등성을 보장할 수 없으므로 중복 발행을 피해 skip.
                log.warn("INSPECTION_DUE 기존 알림 조회 실패 — ownerId={} 스킵 exception={}",
                        ownerId, e.getClass().getSimpleName());
                continue;
            }

            for (Facility facility : facilities) {
                if (alreadyNotified.contains(facility.getId())) {
                    skipped++;
                    continue;
                }
                try {
                    notificationService.notify(ownerId, NotificationType.INSPECTION_DUE,
                            InspectionDueNotificationPayload.serialize(facility));
                    published++;
                } catch (Exception e) {
                    // 시설물 1건 실패를 격리 — 같은 owner의 나머지 시설물 처리는 계속한다.
                    failed++;
                    log.warn("INSPECTION_DUE 알림 발행 실패 — facilityId={} exception={}",
                            facility.getId(), e.getClass().getSimpleName());
                }
            }
        }

        log.info("INSPECTION_DUE 알림 배치 완료 — 대상 {}건, 발행 {}건, 스킵 {}건, 실패 {}건",
                due.size(), published, skipped, failed);
    }
}
