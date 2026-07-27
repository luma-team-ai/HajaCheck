package com.hajacheck.core.facility.service;

import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.facility.dto.InspectionNotificationSettingRequest;
import com.hajacheck.core.facility.dto.InspectionNotificationSettingResponse;
import com.hajacheck.core.facility.entity.InspectionNotificationSetting;
import com.hajacheck.core.facility.repository.InspectionNotificationSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자·시설별 점검 알림 설정 조회/저장(#540 ③). 시설물 소유권 검증은 {@link FacilityService#get}에
 * 위임한다 — 회사 소유가 아니면 FACILITY_NOT_FOUND로 통일 응답한다(InspectionService.getInspection과
 * 동일 원칙, cross-company IDOR 방지). 조회 결과가 없으면(사용자가 한 번도 설정하지 않은 시설물)
 * {@link InspectionNotificationSettingResponse#defaults()}를 반환해 프론트가 항상 유효한 값을 받게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InspectionNotificationSettingService {

    private final InspectionNotificationSettingRepository settingRepository;
    private final FacilityService facilityService;
    private final CompanyScopeGuard companyScopeGuard;

    public InspectionNotificationSettingResponse get(Long userId, Long companyId, Long facilityId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        facilityService.get(userId, companyId, facilityId);
        return settingRepository.findByUserIdAndFacilityId(userId, facilityId)
                .map(InspectionNotificationSettingResponse::from)
                .orElseGet(InspectionNotificationSettingResponse::defaults);
    }

    @Transactional
    public InspectionNotificationSettingResponse save(
            Long userId, Long companyId, Long facilityId, InspectionNotificationSettingRequest request) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        facilityService.get(userId, companyId, facilityId);

        // DB notify_before_days 는 smallint(엔티티 Short) — API 계약은 Integer 라 경계에서 명시 변환한다.
        Short notifyBeforeDays = request.notifyBeforeDays().shortValue();
        InspectionNotificationSetting setting = settingRepository.findByUserIdAndFacilityId(userId, facilityId)
                .orElseGet(() -> settingRepository.save(InspectionNotificationSetting.builder()
                        .userId(userId)
                        .facilityId(facilityId)
                        .notifyBeforeEnabled(request.notifyBeforeEnabled())
                        .notifyBeforeDays(notifyBeforeDays)
                        .warnOnOverdueEnabled(request.warnOnOverdueEnabled())
                        .build()));
        setting.update(request.notifyBeforeEnabled(), notifyBeforeDays, request.warnOnOverdueEnabled());
        return InspectionNotificationSettingResponse.from(setting);
    }
}