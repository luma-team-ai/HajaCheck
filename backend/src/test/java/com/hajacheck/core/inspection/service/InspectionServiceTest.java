package com.hajacheck.core.inspection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.service.AuthService;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.dto.InspectionCreateRequest;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InspectionServiceTest {

    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private FacilityService facilityService;
    @Mock
    private AuthService authService;

    @InjectMocks
    private InspectionService service;

    private static FacilityResponse ownedFacility() {
        return new FacilityResponse(1L, 100L, "테스트 시설물", "BUILDING", null,
                null, null, null, null, null, null, null, null);
    }

    @Test
    void createInspection_최초회차_round_no1로생성() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(0);
        when(inspectionRepository.save(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));

        InspectionResponse response = service.createInspection(request, 100L);

        assertThat(response.roundNo()).isEqualTo(1);
        assertThat(response.facilityId()).isEqualTo(1L);
        assertThat(response.createdBy()).isEqualTo(100L);
        assertThat(response.assignedInspectorId()).isEqualTo(200L);
        assertThat(response.status()).isEqualTo(InspectionStatus.CREATED);
    }

    @Test
    void createInspection_기존회차있음_다음회차번호로생성() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(3);
        when(inspectionRepository.save(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));

        InspectionResponse response = service.createInspection(request, 100L);

        assertThat(response.roundNo()).isEqualTo(4);
    }

    @Test
    void createInspection_시설물소유권없음_예외전파되고저장안됨() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(eq(999L), eq(1L)))
                .thenThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND));

        assertThatThrownBy(() -> service.createInspection(request, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
        verify(inspectionRepository, never()).save(any());
    }

    @Test
    void createInspection_배정담당자가점검자또는관리자아님_예외전파되고저장안됨() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(100L, 1L)).thenReturn(ownedFacility());
        doThrow(new BusinessException(ErrorCode.AUTH_INVALID_INSPECTOR))
                .when(authService).validateAssignableInspector(200L);

        assertThatThrownBy(() -> service.createInspection(request, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
        verify(inspectionRepository, never()).save(any());
    }

    @Test
    void getInspection_존재하지않는ID_INSPECTION_NOT_FOUND() {
        when(inspectionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInspection(999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_NOT_FOUND));
    }
}
