package com.hajacheck.core.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.service.CompanyOwnerLookupService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.facility.dto.InspectionNotificationSettingRequest;
import com.hajacheck.core.facility.dto.InspectionNotificationSettingResponse;
import com.hajacheck.core.facility.entity.InspectionNotificationSetting;
import com.hajacheck.core.facility.repository.InspectionNotificationSettingRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * InspectionNotificationSettingService 단위 테스트(#540 ③ / PR머신 P2 #1032 회귀 고정).
 *
 * <p>핵심 검증: 저장/조회 키는 항상 "요청자"가 아니라 "그 시설물이 속한 회사의 소유자"다.
 * {@link com.hajacheck.core.facility.scheduler.InspectionDueNotificationScheduler}가 항상 회사
 * 소유자 ID로만 설정을 조회하기 때문에, 소유자가 아닌 구성원(REQUESTER)이 저장한 설정을 요청자 ID로
 * 저장하면 스케줄러가 그 행을 절대 찾지 못한다 — 이 테스트들이 그 회귀를 고정한다.
 */
@ExtendWith(MockitoExtension.class)
class InspectionNotificationSettingServiceTest {

    private static final Long REQUESTER = 300L;
    private static final Long OWNER = 100L;
    private static final Long COMPANY = 900L;
    private static final Long FACILITY = 1L;

    @Mock
    private InspectionNotificationSettingRepository settingRepository;
    @Mock
    private FacilityService facilityService;
    @Mock
    private CompanyScopeGuard companyScopeGuard;
    @Mock
    private CompanyOwnerLookupService companyOwnerLookupService;

    @InjectMocks
    private InspectionNotificationSettingService service;

    private static InspectionNotificationSettingRequest request(
            boolean notifyBeforeEnabled, int notifyBeforeDays, boolean warnOnOverdueEnabled) {
        return new InspectionNotificationSettingRequest(notifyBeforeEnabled, notifyBeforeDays, warnOnOverdueEnabled);
    }

    private void stubOwner(Long ownerUserId) {
        when(companyOwnerLookupService.findOwnerUserIds(Set.of(COMPANY)))
                .thenReturn(Map.of(COMPANY, ownerUserId));
    }

    private static InspectionNotificationSetting settingOf(
            Long userId, Long facilityId, boolean notifyBeforeEnabled, int notifyBeforeDays,
            boolean warnOnOverdueEnabled) {
        InspectionNotificationSetting setting = InspectionNotificationSetting.builder()
                .userId(userId)
                .facilityId(facilityId)
                .notifyBeforeEnabled(notifyBeforeEnabled)
                .notifyBeforeDays((short) notifyBeforeDays)
                .warnOnOverdueEnabled(warnOnOverdueEnabled)
                .build();
        ReflectionTestUtils.setField(setting, "id", 1L);
        return setting;
    }

    @Test
    void get_요청자가소유자아니어도_소유자키로조회한다() {
        // REQUESTER(회사 구성원)가 조회 요청을 하지만, 실제 DB 조회는 OWNER 키로 이뤄져야 한다.
        stubOwner(OWNER);
        when(settingRepository.findByUserIdAndFacilityId(OWNER, FACILITY))
                .thenReturn(Optional.of(settingOf(OWNER, FACILITY, false, 14, true)));

        InspectionNotificationSettingResponse response = service.get(REQUESTER, COMPANY, FACILITY);

        assertThat(response.notifyBeforeEnabled()).isFalse();
        assertThat(response.notifyBeforeDays()).isEqualTo(14);
        assertThat(response.warnOnOverdueEnabled()).isTrue();
        verify(settingRepository).findByUserIdAndFacilityId(OWNER, FACILITY);
        verify(settingRepository, never()).findByUserIdAndFacilityId(REQUESTER, FACILITY);
        verify(companyScopeGuard).requireEffectiveMembership(REQUESTER, COMPANY);
        verify(facilityService).get(REQUESTER, COMPANY, FACILITY);
    }

    @Test
    void get_설정없으면_기본값반환() {
        stubOwner(OWNER);
        when(settingRepository.findByUserIdAndFacilityId(OWNER, FACILITY)).thenReturn(Optional.empty());

        InspectionNotificationSettingResponse response = service.get(REQUESTER, COMPANY, FACILITY);

        assertThat(response).isEqualTo(InspectionNotificationSettingResponse.defaults());
    }

    @Test
    void get_회사소유자를찾을수없으면_COMPANY_NOT_FOUND() {
        when(companyOwnerLookupService.findOwnerUserIds(Set.of(COMPANY))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.get(REQUESTER, COMPANY, FACILITY))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.COMPANY_NOT_FOUND));
    }

    @Test
    void save_요청자가소유자아님_신규행이소유자키로저장된다() {
        // PR머신 P2 #1032 핵심 회귀 테스트 — 소유자가 아닌 구성원(REQUESTER)이 저장해도, 실제 저장되는
        // 행의 userId는 REQUESTER가 아니라 OWNER여야 스케줄러(settingKey(ownerUserId, facilityId))가
        // 이 행을 찾을 수 있다.
        stubOwner(OWNER);
        when(settingRepository.findByUserIdAndFacilityId(OWNER, FACILITY)).thenReturn(Optional.empty());
        when(settingRepository.save(any(InspectionNotificationSetting.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        InspectionNotificationSettingResponse response =
                service.save(REQUESTER, COMPANY, FACILITY, request(false, 30, true));

        ArgumentCaptor<InspectionNotificationSetting> captor =
                ArgumentCaptor.forClass(InspectionNotificationSetting.class);
        verify(settingRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(OWNER);
        assertThat(captor.getValue().getUserId()).isNotEqualTo(REQUESTER);
        assertThat(captor.getValue().getFacilityId()).isEqualTo(FACILITY);
        assertThat(response.notifyBeforeEnabled()).isFalse();
        assertThat(response.notifyBeforeDays()).isEqualTo(30);
        assertThat(response.warnOnOverdueEnabled()).isTrue();
    }

    @Test
    void save_소유자키로기존행이미존재_새로만들지않고갱신한다() {
        stubOwner(OWNER);
        InspectionNotificationSetting existing = settingOf(OWNER, FACILITY, true, 7, false);
        when(settingRepository.findByUserIdAndFacilityId(OWNER, FACILITY)).thenReturn(Optional.of(existing));

        InspectionNotificationSettingResponse response =
                service.save(REQUESTER, COMPANY, FACILITY, request(false, 10, true));

        verify(settingRepository, never()).save(any());
        assertThat(response.notifyBeforeEnabled()).isFalse();
        assertThat(response.notifyBeforeDays()).isEqualTo(10);
        assertThat(response.warnOnOverdueEnabled()).isTrue();
    }

    @Test
    void save_회사소유자를찾을수없으면_COMPANY_NOT_FOUND_저장시도안함() {
        when(companyOwnerLookupService.findOwnerUserIds(Set.of(COMPANY))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.save(REQUESTER, COMPANY, FACILITY, request(true, 7, true)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.COMPANY_NOT_FOUND));
        verify(settingRepository, never()).findByUserIdAndFacilityId(any(), any());
        verify(settingRepository, never()).save(any());
    }

    @Test
    void save_인가검증은요청자기준_저장키는소유자기준() {
        // companyScopeGuard(인가)는 REQUESTER로, facilityService.get(소유권 확인)도 REQUESTER로 호출돼야
        // "회사 구성원이면 조회/수정 가능"이라는 인가 원칙이 그대로 유지된다 — 저장 키만 소유자로 바뀐다.
        stubOwner(OWNER);
        when(settingRepository.findByUserIdAndFacilityId(OWNER, FACILITY)).thenReturn(Optional.empty());
        when(settingRepository.save(any(InspectionNotificationSetting.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.save(REQUESTER, COMPANY, FACILITY, request(true, 7, true));

        verify(companyScopeGuard).requireEffectiveMembership(REQUESTER, COMPANY);
        verify(facilityService).get(REQUESTER, COMPANY, FACILITY);
    }
}
