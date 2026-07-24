package com.hajacheck.core.inspection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.InspectionDefectCountProjection;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.dto.InspectionCreateRequest;
import com.hajacheck.core.inspection.dto.InspectionListItemResponse;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InspectionServiceTest {

    @Mock
    private InspectionRepository inspectionRepository;
    @Mock
    private FacilityService facilityService;
    @Mock
    private AuthService authService;
    @Mock
    private CompanyScopeGuard companyScopeGuard;
    @Mock
    private DefectRepository defectRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private InspectionService service;

    private static FacilityResponse ownedFacility() {
        return new FacilityResponse(1L, "테스트 시설물", "BUILDING", null,
                null, null, null, null, null, null, LocalDateTime.of(2020, 1, 1, 0, 0), null,
                null, null, null);
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

    // InspectionRepositoryImpl 이 fetch join 으로 facility 를 채워서 반환하므로, list() 테스트는
    // facility 연관관계까지 리플렉션으로 세팅해야 InspectionListItemResponse.from() 이 정상 매핑된다
    // (DefectServiceTest.existingDefect() 와 동일 사유).
    private static Inspection inspectionWithFacility(Long id, Long facilityId, String facilityName,
                                                       Long assignedInspectorId, InspectionStatus status) {
        Facility facility = Facility.builder().companyId(100L).name(facilityName).type("BUILDING").build();
        ReflectionTestUtils.setField(facility, "id", facilityId);

        Inspection inspection = Inspection.builder()
                .facilityId(facilityId)
                .createdBy(300L)
                .assignedInspectorId(assignedInspectorId)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 20))
                .status(status)
                .build();
        setId(inspection, id);
        ReflectionTestUtils.setField(inspection, "facility", facility);
        return inspection;
    }

    @Test
    void createInspection_최초회차_round_no1로생성() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(0);
        when(inspectionRepository.saveAndFlush(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));

        InspectionResponse response = service.createInspection(request, 100L, 300L);

        assertThat(response.roundNo()).isEqualTo(1);
        assertThat(response.facilityId()).isEqualTo(1L);
        assertThat(response.createdBy()).isEqualTo(300L);
        assertThat(response.assignedInspectorId()).isEqualTo(200L);
        assertThat(response.status()).isEqualTo(InspectionStatus.CREATED);
        verify(companyScopeGuard).requireEffectiveMembership(300L, 100L);
        verify(facilityService).get(300L, 100L, 1L);
        verify(facilityService).lockForUpdate(1L);
    }

    @Test
    void createInspection_기존회차있음_다음회차번호로생성() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(3);
        when(inspectionRepository.saveAndFlush(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));

        InspectionResponse response = service.createInspection(request, 100L, 300L);

        assertThat(response.roundNo()).isEqualTo(4);
    }

    @Test
    void createInspection_시설물소유권없음_예외전파되고저장안됨() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(eq(300L), eq(999L), eq(1L)))
                .thenThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND));

        assertThatThrownBy(() -> service.createInspection(request, 999L, 300L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
        verify(inspectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInspection_배정담당자가점검자또는관리자아님또는타회사소속_예외전파되고저장안됨() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        doThrow(new BusinessException(ErrorCode.AUTH_INVALID_INSPECTOR))
                .when(authService).validateAssignableInspector(300L, 200L);

        assertThatThrownBy(() -> service.createInspection(request, 100L, 300L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
        verify(inspectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInspection_점검일이시설물등록일이전_예외전파되고저장안됨() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2019, 12, 31), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());

        assertThatThrownBy(() -> service.createInspection(request, 100L, 300L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_DATE_INVALID));
        verify(inspectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInspection_점검일이너무먼미래_예외전파되고저장안됨() {
        InspectionCreateRequest request =
                new InspectionCreateRequest(1L, LocalDate.now().plusYears(2), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());

        assertThatThrownBy(() -> service.createInspection(request, 100L, 300L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_DATE_INVALID));
        verify(inspectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInspection_회차채번동시성경쟁으로unique위반_INSPECTION_ROUND_CONFLICT로변환() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(0);
        when(inspectionRepository.saveAndFlush(any(Inspection.class)))
                .thenThrow(new DataIntegrityViolationException("could not execute statement",
                        new ConstraintViolationException("duplicate key value violates unique constraint",
                                new SQLException("duplicate key"), "inspections_facility_id_round_no_key")));

        assertThatThrownBy(() -> service.createInspection(request, 100L, 300L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_ROUND_CONFLICT));
    }

    @Test
    void createInspection_회차unique위반이아닌무결성위반_원예외그대로전파() {
        // 배정 검증과 save 사이에 FK 대상이 삭제되는 등 round_no 와 무관한 무결성 위반은 "재시도" 안내로
        // 오분류하지 않고 그대로 전파해야 GlobalExceptionHandler 로그에 실제 원인이 남는다.
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(0);
        DataIntegrityViolationException fkViolation = new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("insert or update violates foreign key constraint",
                        new SQLException("fk violation"), "fk_inspections_assigned_inspector_id"));
        when(inspectionRepository.saveAndFlush(any(Inspection.class))).thenThrow(fkViolation);

        assertThatThrownBy(() -> service.createInspection(request, 100L, 300L))
                .isSameAs(fkViolation);
    }

    @Test
    void getInspection_존재하지않는ID_INSPECTION_NOT_FOUND() {
        when(inspectionRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInspection(300L, 100L, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_NOT_FOUND));
    }

    @Test
    void getInspection_본인소유시설물의점검_정상반환() {
        Inspection inspection = inspectionOf(10L, 1L);
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        lenient().when(facilityService.get(anyLong(), anyLong(), anyLong())).thenReturn(ownedFacility());

        InspectionResponse response = service.getInspection(300L, 100L, 10L);

        assertThat(response.id()).isEqualTo(10L);
        verify(facilityService).get(300L, 100L, 1L);
    }

    @Test
    void getInspection_타인소유시설물의점검_INSPECTION_NOT_FOUND로통일() {
        Inspection inspection = inspectionOf(10L, 1L);
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        when(facilityService.get(300L, 999L, 1L)).thenThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND));

        // FACILITY_NOT_FOUND를 INSPECTION_NOT_FOUND로 통일 — IDOR 열거 방지
        assertThatThrownBy(() -> service.getInspection(300L, 999L, 10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_NOT_FOUND));
    }

    @Test
    void list_owner스코프로위임_필터그대로전달_시설물명담당자명하자건수포함매핑() {
        Pageable pageable = PageRequest.of(0, 20);
        Inspection inspection = inspectionWithFacility(10L, 1L, "테스트빌딩", 200L, InspectionStatus.ANALYZED);
        Page<Inspection> page = new PageImpl<>(List.of(inspection), pageable, 1);
        when(inspectionRepository.findPageByCompanyIdAndFilters(
                eq(100L), eq(1L), eq(InspectionStatus.ANALYZED), any(Pageable.class)))
                .thenReturn(page);
        when(defectRepository.countGroupByInspectionId(List.of(10L)))
                .thenReturn(List.of(countProjection(10L, 3L)));
        User inspector = User.builder().name("김점검").build();
        ReflectionTestUtils.setField(inspector, "id", 200L);
        when(userRepository.findAllById(List.of(200L))).thenReturn(List.of(inspector));

        PageResponse<InspectionListItemResponse> response =
                service.list(300L, 100L, 1L, InspectionStatus.ANALYZED, pageable);

        assertThat(response.content()).hasSize(1);
        InspectionListItemResponse item = response.content().get(0);
        assertThat(item.id()).isEqualTo(10L);
        assertThat(item.facilityId()).isEqualTo(1L);
        assertThat(item.facilityName()).isEqualTo("테스트빌딩");
        assertThat(item.assignedInspectorId()).isEqualTo(200L);
        assertThat(item.assignedInspectorName()).isEqualTo("김점검");
        assertThat(item.status()).isEqualTo(InspectionStatus.ANALYZED);
        assertThat(item.defectCount()).isEqualTo(3L);
        verify(companyScopeGuard).requireEffectiveMembership(300L, 100L);
        verify(inspectionRepository)
                .findPageByCompanyIdAndFilters(100L, 1L, InspectionStatus.ANALYZED, pageable);
    }

    @Test
    void list_결과없으면빈페이지_하자레포사용자레포조회안함() {
        Pageable pageable = PageRequest.of(0, 20);
        when(inspectionRepository.findPageByCompanyIdAndFilters(
                eq(100L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PageResponse<InspectionListItemResponse> response = service.list(300L, 100L, null, null, pageable);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        verify(defectRepository, never()).countGroupByInspectionId(any());
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void list_하자담당자정보없으면기본값() {
        Pageable pageable = PageRequest.of(0, 20);
        Inspection inspection = inspectionWithFacility(10L, 1L, "테스트빌딩", 200L, InspectionStatus.CREATED);
        when(inspectionRepository.findPageByCompanyIdAndFilters(
                eq(100L), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(inspection), pageable, 1));
        when(defectRepository.countGroupByInspectionId(List.of(10L))).thenReturn(List.of());
        when(userRepository.findAllById(List.of(200L))).thenReturn(List.of());

        PageResponse<InspectionListItemResponse> response = service.list(300L, 100L, null, null, pageable);

        InspectionListItemResponse item = response.content().get(0);
        assertThat(item.defectCount()).isZero();
        assertThat(item.assignedInspectorName()).isEqualTo("-");
    }

    @Test
    void list_회사없는사용자_FORBIDDEN예외() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(300L, null);

        assertThatThrownBy(() -> service.list(300L, null, null, null, PageRequest.of(0, 10)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        verify(inspectionRepository, never()).findPageByCompanyIdAndFilters(any(), any(), any(), any());
    }

    private static InspectionDefectCountProjection countProjection(Long inspectionId, long cnt) {
        return new InspectionDefectCountProjection() {
            @Override
            public Long getInspectionId() {
                return inspectionId;
            }

            @Override
            public long getCnt() {
                return cnt;
            }
        };
    }
}
