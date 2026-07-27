package com.hajacheck.core.facility.repository;

import com.hajacheck.core.facility.entity.InspectionNotificationSetting;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InspectionNotificationSettingRepository
        extends JpaRepository<InspectionNotificationSetting, Long> {

    Optional<InspectionNotificationSetting> findByUserIdAndFacilityId(Long userId, Long facilityId);

    /**
     * INSPECTION_DUE 배치(#540)의 페이지별 N+1 방지용 배치 조회 — userId IN (...) AND facilityId IN (...)
     * 조건이라 정확한 (userId, facilityId) 페어 매칭은 아니다(교차 매칭 가능). 호출부가 반환된 행을
     * (userId, facilityId) 정확 일치로 다시 걸러 사용해야 한다(InspectionDueNotificationScheduler 참고).
     */
    List<InspectionNotificationSetting> findAllByUserIdInAndFacilityIdIn(List<Long> userIds, List<Long> facilityIds);
}