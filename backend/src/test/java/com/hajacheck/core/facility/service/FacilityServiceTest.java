package com.hajacheck.core.facility.service;

import com.hajacheck.auth.service.CompanyScopeGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.core.facility.dto.FacilityCreateRequest;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.dto.FacilityScheduleRequest;
import com.hajacheck.core.facility.dto.FacilityUpdateRequest;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class FacilityServiceTest {

    @Mock
    private FacilityRepository facilityRepository;
    @Mock
    private CompanyScopeGuard companyScopeGuard;

    @InjectMocks
    private FacilityService facilityService;

    private static final Long OWNER_ID = 1L;
    private static final Long USER_ID = 101L;

    private Facility existingFacility() {
        return Facility.builder()
                .companyId(OWNER_ID)
                .name("기존시설")
                .type("BUILDING")
                .address("서울시 강남구")
                .build();
    }

    private FacilityCreateRequest createRequest() {
        return new FacilityCreateRequest(
                "테스트빌딩", "BUILDING", "서울시 강남구", null, null, 2010, "지상5층", 12, null);
    }

    @Test
    void create_등록_소유자와입력값으로저장() {
        when(facilityRepository.save(any(Facility.class))).thenAnswer(inv -> inv.getArgument(0));

        FacilityResponse response = facilityService.create(USER_ID, OWNER_ID, createRequest());

        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertThat(captor.getValue().getCompanyId()).isEqualTo(OWNER_ID);
        assertThat(captor.getValue().getName()).isEqualTo("테스트빌딩");
        assertThat(response.name()).isEqualTo("테스트빌딩");
    }

    @Test
    void list_목록조회_소유자스코프로위임() {
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(existingFacility()));

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("기존시설");
        verify(facilityRepository).findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class));
    }

    @Test
    void list_목록조회_상한초과시상한개수만반환() {
        List<Facility> capped = List.of(existingFacility(), existingFacility());
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(capped);

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result).hasSize(2);
        ArgumentCaptor<PageRequest> pageableCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(facilityRepository).findByCompanyIdOrderByIdAsc(eq(OWNER_ID), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(500);
        // 상한 미도달이면 무고지 truncation 감지용 countByCompanyId 를 호출할 필요가 없다(#502 P2).
        verify(facilityRepository, never()).countByCompanyId(any());
    }

    @Test
    void list_상한정확히도달시_실제보유건수를조회해WARN로그근거를남긴다() {
        List<Facility> maxed = java.util.Collections.nCopies(500, existingFacility());
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(maxed);
        when(facilityRepository.countByCompanyId(OWNER_ID)).thenReturn(650L);

        List<FacilityResponse> result = facilityService.list(USER_ID, OWNER_ID);

        assertThat(result).hasSize(500);
        // 응답 계약(List)은 그대로 500건 — 무고지 truncation 감지는 로그로만 이뤄지므로, 최소한 실제
        // 보유 건수(countByCompanyId)를 조회했는지로 "감지 로직이 탔다"를 검증한다(#502 P2).
        verify(facilityRepository).countByCompanyId(OWNER_ID);
    }

    @Test
    void get_존재하는본인시설_반환() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));

        FacilityResponse response = facilityService.get(USER_ID, OWNER_ID, 10L);

        assertThat(response.name()).isEqualTo("기존시설");
    }

    @Test
    void get_없는시설_FACILITY_NOT_FOUND예외() {
        when(facilityRepository.findByIdAndCompanyId(999L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facilityService.get(USER_ID, OWNER_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
    }

    @Test
    void get_타인소유시설_FACILITY_NOT_FOUND예외() {
        // findByIdAndCompanyId 는 소유자 스코프라 타인 소유는 조회 자체가 빈 값으로 온다(cross-owner IDOR 방지).
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facilityService.get(USER_ID, OWNER_ID, 10L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void update_본인시설_필드갱신() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        FacilityUpdateRequest request = new FacilityUpdateRequest(
                "수정된빌딩", "APARTMENT", "서울시 서초구", null, null, 2015, "지상10층", 6, null);

        FacilityResponse response = facilityService.update(USER_ID, OWNER_ID, 10L, request);

        assertThat(response.name()).isEqualTo("수정된빌딩");
        assertThat(response.type()).isEqualTo("APARTMENT");
        assertThat(response.address()).isEqualTo("서울시 서초구");
        assertThat(response.inspectionCycleMonths()).isEqualTo(6);
    }

    @Test
    void update_없는시설_FACILITY_NOT_FOUND예외() {
        when(facilityRepository.findByIdAndCompanyId(999L, OWNER_ID)).thenReturn(Optional.empty());
        FacilityUpdateRequest request = new FacilityUpdateRequest(
                "수정된빌딩", "APARTMENT", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> facilityService.update(USER_ID, OWNER_ID, 999L, request))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void delete_본인시설_저장소에서삭제() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));

        facilityService.delete(USER_ID, OWNER_ID, 10L);

        verify(facilityRepository, times(1)).delete(facility);
    }

    @Test
    void delete_없는시설_FACILITY_NOT_FOUND예외_삭제호출없음() {
        when(facilityRepository.findByIdAndCompanyId(999L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> facilityService.delete(USER_ID, OWNER_ID, 999L))
                .isInstanceOf(BusinessException.class);
        verify(facilityRepository, never()).delete(any(Facility.class));
    }

    @Test
    void lockForUpdate_행잠금조회위임() {
        facilityService.lockForUpdate(10L);

        verify(facilityRepository).findByIdForUpdate(10L);
    }

    @Test
    void setSchedule_본인시설_다음점검일산출저장() {
        Facility facility = existingFacility();
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.of(facility));
        FacilityScheduleRequest request = new FacilityScheduleRequest(6);

        FacilityResponse response = facilityService.setSchedule(USER_ID, OWNER_ID, 10L, request);

        assertThat(response.inspectionCycleMonths()).isEqualTo(6);
        assertThat(response.nextInspectionDueAt()).isEqualTo(LocalDate.now().plusMonths(6));
    }

    @Test
    void setSchedule_없는시설_FACILITY_NOT_FOUND예외() {
        when(facilityRepository.findByIdAndCompanyId(999L, OWNER_ID)).thenReturn(Optional.empty());
        FacilityScheduleRequest request = new FacilityScheduleRequest(12);

        assertThatThrownBy(() -> facilityService.setSchedule(USER_ID, OWNER_ID, 999L, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
    }

    @Test
    void setSchedule_타인소유시설_FACILITY_NOT_FOUND예외() {
        // findByIdAndCompanyId 는 소유자 스코프라 타인 소유는 조회 자체가 빈 값으로 온다(cross-owner IDOR 방지).
        when(facilityRepository.findByIdAndCompanyId(10L, OWNER_ID)).thenReturn(Optional.empty());
        FacilityScheduleRequest request = new FacilityScheduleRequest(12);

        assertThatThrownBy(() -> facilityService.setSchedule(USER_ID, OWNER_ID, 10L, request))
                .isInstanceOf(BusinessException.class);
    }
    @Test
    void list_회사없는사용자_FORBIDDEN예외() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(USER_ID, null);
        assertThatThrownBy(() -> facilityService.list(USER_ID, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }
}
