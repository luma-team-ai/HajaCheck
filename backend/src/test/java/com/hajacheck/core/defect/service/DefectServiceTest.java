package com.hajacheck.core.defect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.hajacheck.core.defect.dto.DefectResponse;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefectServiceTest {

    @Mock
    private DefectRepository defectRepository;
    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private FacilityService facilityService;

    @InjectMocks
    private DefectService service;

    private static Defect defectOf(Long id, Long inspectionId) {
        Defect defect = Defect.builder()
                .inspectionId(inspectionId)
                .type(DefectType.CRACK)
                .bboxX(10.0)
                .bboxY(20.0)
                .bboxW(50.0)
                .bboxH(60.0)
                .confidence(0.95)
                .grade(DefectGrade.A)
                .status(DefectStatus.DETECTED)
                .reviewed(false)
                .deleted(false)
                .crackWidthMm(2.5)
                .crackLengthMm(100.0)
                .build();
        setId(defect, id);
        return defect;
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

    private static FacilityResponse ownedFacility() {
        return new FacilityResponse(1L, 100L, "테스트 시설물", "BUILDING", null,
                null, null, null, null, null, null, LocalDateTime.of(2020, 1, 1, 0, 0), null);
    }

    private static void setId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void getDefect_본인시설하자_200_정상조회() {
        Long requesterUserId = 100L;
        Long defectId = 1L;
        Long inspectionId = 10L;
        Long facilityId = 1L;

        Defect defect = defectOf(defectId, inspectionId);
        Inspection inspection = inspectionOf(inspectionId, facilityId);

        when(defectRepository.findById(defectId)).thenReturn(Optional.of(defect));
        when(inspectionRepository.findById(inspectionId)).thenReturn(Optional.of(inspection));
        when(facilityService.get(requesterUserId, facilityId)).thenReturn(ownedFacility());

        DefectResponse response = service.getDefect(requesterUserId, defectId);

        assertThat(response).isNotNull();
        assertThat(response.id()).isEqualTo(defectId);
        assertThat(response.inspectionId()).isEqualTo(inspectionId);
        assertThat(response.type()).isEqualTo(DefectType.CRACK);
        assertThat(response.confidence()).isEqualTo(0.95);
        assertThat(response.grade()).isEqualTo(DefectGrade.A);
        assertThat(response.status()).isEqualTo(DefectStatus.DETECTED);
        assertThat(response.facilityType()).isEqualTo("BUILDING");
    }

    @Test
    void getDefect_없는하자_404_DEFECT_NOT_FOUND() {
        Long requesterUserId = 100L;
        Long defectId = 999999L;

        when(defectRepository.findById(defectId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDefect(requesterUserId, defectId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.DEFECT_NOT_FOUND));
    }

    @Test
    void getDefect_타인시설하자_404_FACILITY_NOT_FOUND() {
        Long requesterUserId = 100L;
        Long stranger = 999L;
        Long defectId = 1L;
        Long inspectionId = 10L;
        Long facilityId = 1L;

        Defect defect = defectOf(defectId, inspectionId);
        Inspection inspection = inspectionOf(inspectionId, facilityId);

        when(defectRepository.findById(defectId)).thenReturn(Optional.of(defect));
        when(inspectionRepository.findById(inspectionId)).thenReturn(Optional.of(inspection));
        when(facilityService.get(requesterUserId, facilityId))
                .thenThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND));

        assertThatThrownBy(() -> service.getDefect(requesterUserId, defectId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
    }
}
