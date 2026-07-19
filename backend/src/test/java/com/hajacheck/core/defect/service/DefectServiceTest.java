package com.hajacheck.core.defect.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.core.defect.dto.DefectResponse;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.DomainStateTransitionException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DefectServiceTest {

    @Mock
    private DefectRepository defectRepository;

    @InjectMocks
    private DefectService defectService;

    private static final Long OWNER_ID = 1L;

    // Defect/Inspection/Facility 모두 @NoArgsConstructor(PROTECTED)+@Builder라 리플렉션으로 id를 채운다
    // (FacilityServiceTest와 달리 응답 DTO가 연관관계(inspection.facility)까지 타고 들어가므로
    // 세 엔티티를 함께 구성해야 한다).
    private Defect existingDefect(Long facilityId) {
        Facility facility = Facility.builder()
                .ownerId(OWNER_ID)
                .name("테스트빌딩")
                .type("BUILDING")
                .build();
        ReflectionTestUtils.setField(facility, "id", facilityId);

        Inspection inspection = Inspection.builder()
                .facilityId(facilityId)
                .createdBy(OWNER_ID)
                .assignedInspectorId(OWNER_ID)
                .roundNo(1)
                .inspectionDate(LocalDate.now())
                .status(InspectionStatus.CREATED)
                .build();
        ReflectionTestUtils.setField(inspection, "id", 100L);
        ReflectionTestUtils.setField(inspection, "facility", facility);

        Defect defect = Defect.builder()
                .inspectionId(100L)
                .type(DefectType.CRACK)
                .confidence(0.9)
                .grade(DefectGrade.C)
                .status(DefectStatus.DETECTED)
                .reviewed(false)
                .deleted(false)
                .build();
        ReflectionTestUtils.setField(defect, "id", 10L);
        ReflectionTestUtils.setField(defect, "inspection", inspection);
        return defect;
    }

    @Test
    void list_owner스코프로위임_필터그대로전달() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<Defect> page = new PageImpl<>(List.of(existingDefect(5L)), pageable, 1);
        when(defectRepository.findPageByOwnerIdAndFilters(
                eq(OWNER_ID), eq(DefectType.CRACK), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        PageResponse<DefectResponse> response =
                defectService.list(OWNER_ID, DefectType.CRACK, null, null, pageable);

        assertThat(response.content()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.content().get(0).facilityId()).isEqualTo(5L);
        verify(defectRepository).findPageByOwnerIdAndFilters(OWNER_ID, DefectType.CRACK, null, null, pageable);
    }

    @Test
    void list_결과없으면빈페이지() {
        Pageable pageable = PageRequest.of(0, 20);
        when(defectRepository.findPageByOwnerIdAndFilters(
                eq(OWNER_ID), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PageResponse<DefectResponse> response = defectService.list(OWNER_ID, null, null, null, pageable);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    @Test
    void get_본인소유하자_반환() {
        Defect defect = existingDefect(5L);
        when(defectRepository.findByIdAndOwnerId(10L, OWNER_ID)).thenReturn(Optional.of(defect));

        DefectResponse response = defectService.get(OWNER_ID, 10L);

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.facilityId()).isEqualTo(5L);
        assertThat(response.facilityName()).isEqualTo("테스트빌딩");
        assertThat(response.typeLabel()).isEqualTo("균열");
    }

    @Test
    void get_없는하자_DEFECT_NOT_FOUND예외() {
        when(defectRepository.findByIdAndOwnerId(999L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> defectService.get(OWNER_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DEFECT_NOT_FOUND));
    }

    @Test
    void get_타인소유하자_DEFECT_NOT_FOUND예외() {
        // findByIdAndOwnerId 는 소유자 스코프라 타인 소유는 조회 자체가 빈 값으로 온다(cross-owner IDOR 방지).
        when(defectRepository.findByIdAndOwnerId(10L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> defectService.get(OWNER_ID, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DEFECT_NOT_FOUND));
    }

    @Test
    void updateStatus_정상전이_다음상태로변경() {
        Defect defect = existingDefect(5L);
        when(defectRepository.findByIdAndOwnerId(10L, OWNER_ID)).thenReturn(Optional.of(defect));

        DefectResponse response = defectService.updateStatus(OWNER_ID, 10L, DefectStatus.CONFIRMED);

        assertThat(response.status()).isEqualTo(DefectStatus.CONFIRMED);
        assertThat(defect.getStatus()).isEqualTo(DefectStatus.CONFIRMED);
    }

    @Test
    void updateStatus_역행요청_DomainStateTransitionException() {
        // existingDefect 는 DETECTED 상태 — CONFIRMED를 건너뛰고 ACTION_PENDING을 요청하면 스킵 전이라 거부된다.
        Defect defect = existingDefect(5L);
        when(defectRepository.findByIdAndOwnerId(10L, OWNER_ID)).thenReturn(Optional.of(defect));

        assertThatThrownBy(() -> defectService.updateStatus(OWNER_ID, 10L, DefectStatus.ACTION_PENDING))
                .isInstanceOf(DomainStateTransitionException.class);
        assertThat(defect.getStatus()).isEqualTo(DefectStatus.DETECTED);
    }

    @Test
    void updateStatus_타인소유하자_DEFECT_NOT_FOUND예외() {
        when(defectRepository.findByIdAndOwnerId(10L, OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> defectService.updateStatus(OWNER_ID, 10L, DefectStatus.CONFIRMED))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DEFECT_NOT_FOUND));
    }
}
