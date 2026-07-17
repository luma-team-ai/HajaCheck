package com.hajacheck.core.inspection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

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
                null, null, null, null, null, null, LocalDateTime.of(2020, 1, 1, 0, 0), null);
    }

    private static Inspection inspectionOf(Long id, Long facilityId) {
        Inspection inspection = Inspection.builder()
                .facilityId(facilityId)
                .createdBy(100L)
                .assignedInspectorId(200L)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 20))
                .status(InspectionStatus.CREATED)
                .build();
        setId(inspection, id);
        return inspection;
    }

    private static void setId(Inspection inspection, Long id) {
        try {
            Field field = Inspection.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(inspection, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void createInspection_최초회차_round_no1로생성() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(0);
        when(inspectionRepository.saveAndFlush(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));

        InspectionResponse response = service.createInspection(request, 100L);

        assertThat(response.roundNo()).isEqualTo(1);
        assertThat(response.facilityId()).isEqualTo(1L);
        assertThat(response.createdBy()).isEqualTo(100L);
        assertThat(response.assignedInspectorId()).isEqualTo(200L);
        assertThat(response.status()).isEqualTo(InspectionStatus.CREATED);
        verify(facilityService).lockForUpdate(1L);
    }

    @Test
    void createInspection_기존회차있음_다음회차번호로생성() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(3);
        when(inspectionRepository.saveAndFlush(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));

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
        verify(inspectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInspection_배정담당자가점검자또는관리자아님또는타회사소속_예외전파되고저장안됨() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(100L, 1L)).thenReturn(ownedFacility());
        doThrow(new BusinessException(ErrorCode.AUTH_INVALID_INSPECTOR))
                .when(authService).validateAssignableInspector(100L, 200L);

        assertThatThrownBy(() -> service.createInspection(request, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
        verify(inspectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInspection_점검일이시설물등록일이전_예외전파되고저장안됨() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2019, 12, 31), 200L);
        when(facilityService.get(100L, 1L)).thenReturn(ownedFacility());

        assertThatThrownBy(() -> service.createInspection(request, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_DATE_INVALID));
        verify(inspectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInspection_점검일이너무먼미래_예외전파되고저장안됨() {
        InspectionCreateRequest request =
                new InspectionCreateRequest(1L, LocalDate.now().plusYears(2), 200L);
        when(facilityService.get(100L, 1L)).thenReturn(ownedFacility());

        assertThatThrownBy(() -> service.createInspection(request, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_DATE_INVALID));
        verify(inspectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInspection_회차채번동시성경쟁으로unique위반_INSPECTION_ROUND_CONFLICT로변환() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(0);
        when(inspectionRepository.saveAndFlush(any(Inspection.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new ConstraintViolationException("duplicate key value violates unique constraint",
                                new SQLException("duplicate key"), "inspections_facility_id_round_no_key")));

        assertThatThrownBy(() -> service.createInspection(request, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_ROUND_CONFLICT));
    }

    @Test
    void createInspection_회차unique위반이아닌무결성위반_원예외그대로전파() {
        // 배정 검증과 save 사이에 FK 대상이 삭제되는 등 round_no 와 무관한 무결성 위반은 "재시도" 안내로
        // 오분류하지 않고 그대로 전파해야 GlobalExceptionHandler 로그에 실제 원인이 남는다.
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(0);
        DataIntegrityViolationException fkViolation = new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("insert or update violates foreign key constraint",
                        new SQLException("fk violation"), "fk_inspections_assigned_inspector_id"));
        when(inspectionRepository.saveAndFlush(any(Inspection.class))).thenThrow(fkViolation);

        assertThatThrownBy(() -> service.createInspection(request, 100L))
                .isSameAs(fkViolation);
    }

    @Test
    void getInspection_존재하지않는ID_INSPECTION_NOT_FOUND() {
        when(inspectionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInspection(100L, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_NOT_FOUND));
    }

    @Test
    void getInspection_본인소유시설물의점검_정상반환() {
        Inspection inspection = inspectionOf(10L, 1L);
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        lenient().when(facilityService.get(anyLong(), anyLong())).thenReturn(ownedFacility());

        InspectionResponse response = service.getInspection(100L, 10L);

        assertThat(response.id()).isEqualTo(10L);
        verify(facilityService).get(100L, 1L);
    }

    @Test
    void getInspection_타인소유시설물의점검_예외전파() {
        Inspection inspection = inspectionOf(10L, 1L);
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        when(facilityService.get(999L, 1L)).thenThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND));

        assertThatThrownBy(() -> service.getInspection(999L, 10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
    }
}
