package com.hajacheck.core.facility.service;

import com.hajacheck.auth.service.CompanyOwnerLookupService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.facility.dto.InspectionNotificationSettingRequest;
import com.hajacheck.core.facility.dto.InspectionNotificationSettingResponse;
import com.hajacheck.core.facility.entity.InspectionNotificationSetting;
import com.hajacheck.core.facility.repository.InspectionNotificationSettingRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자·시설별 점검 알림 설정 조회/저장(#540 ③). 시설물 소유권 검증은 {@link FacilityService#get}에
 * 위임한다 — 회사 소유가 아니면 FACILITY_NOT_FOUND로 통일 응답한다(InspectionService.getInspection과
 * 동일 원칙, cross-company IDOR 방지). 조회 결과가 없으면(사용자가 한 번도 설정하지 않은 시설물)
 * {@link InspectionNotificationSettingResponse#defaults()}를 반환해 프론트가 항상 유효한 값을 받게 한다.
 *
 * <p>⚠️ 저장/조회 키 = 항상 "수신자(회사 소유자)"(PR머신 P2 #1032/HAJA-498 후속). 인가는
 * {@code userId}(요청자, 회사 구성원이면 누구나)로 검증하지만, 실제 DB 행의 키는 항상
 * {@link CompanyOwnerLookupService}로 구한 회사 소유자 ID를 쓴다.
 * {@link com.hajacheck.core.facility.scheduler.InspectionDueNotificationScheduler}가 항상 회사
 * 소유자 ID로만 설정을 조회하므로, 요청자 ID로 저장하면(소유자가 아닌 구성원이 저장한 경우) 스케줄러가
 * 그 행을 절대 찾지 못해 항상 기본값으로 게이팅되는 반면, GET은 저장자 본인 키로 그대로 돌려줘 화면상
 * "저장된 것처럼" 보이는 불일치가 생겼다 — "누가 바꾸든 그 시설물의 알림 수신자 기준 설정 1개"로
 * 일원화해 스케줄러의 조회 로직과 정확히 맞춘다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InspectionNotificationSettingService {

    private final InspectionNotificationSettingRepository settingRepository;
    private final FacilityService facilityService;
    private final CompanyScopeGuard companyScopeGuard;
    private final CompanyOwnerLookupService companyOwnerLookupService;

    public InspectionNotificationSettingResponse get(Long userId, Long companyId, Long facilityId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        facilityService.get(userId, companyId, facilityId);
        Long ownerUserId = resolveOwnerUserId(companyId);
        return settingRepository.findByUserIdAndFacilityId(ownerUserId, facilityId)
                .map(InspectionNotificationSettingResponse::from)
                .orElseGet(InspectionNotificationSettingResponse::defaults);
    }

    @Transactional
    public InspectionNotificationSettingResponse save(
            Long userId, Long companyId, Long facilityId, InspectionNotificationSettingRequest request) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        facilityService.get(userId, companyId, facilityId);
        Long ownerUserId = resolveOwnerUserId(companyId);

        // DB notify_before_days 는 smallint(엔티티 Short) — API 계약은 Integer 라 경계에서 명시 변환한다.
        Short notifyBeforeDays = request.notifyBeforeDays().shortValue();
        InspectionNotificationSetting setting = settingRepository.findByUserIdAndFacilityId(ownerUserId, facilityId)
                .orElseGet(() -> settingRepository.save(InspectionNotificationSetting.builder()
                        .userId(ownerUserId)
                        .facilityId(facilityId)
                        .notifyBeforeEnabled(request.notifyBeforeEnabled())
                        .notifyBeforeDays(notifyBeforeDays)
                        .warnOnOverdueEnabled(request.warnOnOverdueEnabled())
                        .build()));
        setting.update(request.notifyBeforeEnabled(), notifyBeforeDays, request.warnOnOverdueEnabled());
        return InspectionNotificationSettingResponse.from(setting);
    }

    /**
     * 회사 소유자 ID를 조회한다 — 이 값이 곧 알림 수신자이자 설정 저장/조회 키다. companyScopeGuard가
     * 이미 유효 멤버십을 검증했으므로 회사 자체는 존재가 보장되지만, 소유자 매핑이 데이터 정합성 문제로
     * 비어 있는 극단 상황을 방어적으로 COMPANY_NOT_FOUND로 통일 처리한다(FacilityService.get의
     * FACILITY_NOT_FOUND 통일 응답과 동일 원칙).
     */
    private Long resolveOwnerUserId(Long companyId) {
        Long ownerUserId = companyOwnerLookupService.findOwnerUserIds(Set.of(companyId)).get(companyId);
        if (ownerUserId == null) {
            throw new BusinessException(ErrorCode.COMPANY_NOT_FOUND);
        }
        return ownerUserId;
    }
}
