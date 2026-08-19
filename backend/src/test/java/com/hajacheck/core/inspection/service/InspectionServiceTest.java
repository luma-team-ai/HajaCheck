package com.hajacheck.core.inspection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.InspectionDefectCountProjection;
import com.hajacheck.core.defect.repository.InspectionGradeCountProjection;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.dto.InspectionCreateRequest;
import com.hajacheck.core.inspection.dto.InspectionListItemResponse;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.entity.InspectionType;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.core.inspection.repository.InspectionSearchCriteria;
import com.hajacheck.core.report.entity.ReportStatus;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.service.QuotaService;
import java.lang.reflect.Field;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
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
    // #890 — 플랜 하향으로 한도를 넘긴 시설물은 읽기 전용이라 신규 점검 생성이 막힌다. 이 클래스의
    // 관심사는 그 게이트가 아니므로 "읽기 전용 아님"(기본 false)으로 두고 원래 시나리오만 검증한다.
    @Mock
    private QuotaService quotaService;
    // #1702 — 회차 시프트 시 DRAFT 보고서 회차 스냅샷 재동기화를 위해 InspectionService가 직접 참조한다.
    @Mock
    private ReportRepository reportRepository;

    @InjectMocks
    private InspectionService service;

    private static FacilityResponse ownedFacility() {
        return new FacilityResponse(1L, "테스트 시설물", "BUILDING", null,
                null, null, null, null, null, null, LocalDateTime.of(2020, 1, 1, 0, 0), null,
                null, null, null, null, null, null);
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
        return inspectionWithFacility(id, facilityId, facilityName, assignedInspectorId, status, InspectionType.REGULAR);
    }

    private static Inspection inspectionWithFacility(Long id, Long facilityId, String facilityName,
                                                       Long assignedInspectorId, InspectionStatus status,
                                                       InspectionType type) {
        Facility facility = Facility.builder().companyId(100L).name(facilityName).type("BUILDING").build();
        ReflectionTestUtils.setField(facility, "id", facilityId);

        Inspection inspection = Inspection.builder()
                .facilityId(facilityId)
                .createdBy(300L)
                .assignedInspectorId(assignedInspectorId)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 20))
                .status(status)
                .type(type)
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
        assertThat(response.type()).isEqualTo(InspectionType.REGULAR);
        verify(companyScopeGuard).requireEffectiveMembership(300L, 100L);
        verify(facilityService).get(300L, 100L, 1L);
        verify(facilityService).lockForUpdate(1L);
    }

    @Test
    void createInspection_기존회차있음_다음회차번호로생성() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(3);
        // 기존 3회차가 모두 새 점검일 이전 = 삽입 위치가 맨 뒤(4)라 시프트 없이 그대로 채번된다(#1702).
        when(inspectionRepository.countByFacilityIdAndInspectionDateLessThanEqual(1L, LocalDate.of(2026, 7, 20)))
                .thenReturn(3L);
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
    void createInspection_점검일이시설물등록일보다훨씬이전이어도_정상생성됨() {
        // 팀 피드백(2026-08-01)으로 시설물 등록일 하한을 없앴다 — 시설물이 시스템에 "등록된" 시점과
        // "실제로 존재하기 시작한" 시점은 다르므로, 과거 이력(마이그레이션·소급 입력 등)을 막지 않는다.
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2019, 12, 31), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(0);
        when(inspectionRepository.saveAndFlush(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));

        InspectionResponse response = service.createInspection(request, 100L, 300L);

        assertThat(response.roundNo()).isEqualTo(1);
    }

    @Test
    void createInspection_점검일이미래_예외전파되고저장안됨() {
        InspectionCreateRequest request =
                new InspectionCreateRequest(1L, LocalDate.now().plusDays(1), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());

        assertThatThrownBy(() -> service.createInspection(request, 100L, 300L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_DATE_INVALID));
        verify(inspectionRepository, never()).saveAndFlush(any());
    }

    @Test
    void createInspection_점검일이오늘_정상생성됨() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.now(), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(0);
        when(inspectionRepository.saveAndFlush(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));

        InspectionResponse response = service.createInspection(request, 100L, 300L);

        assertThat(response.roundNo()).isEqualTo(1);
    }

    // #1702 — #1291이 "기존 최신 회차보다 이전 점검일"을 통째로 거부하던 걸 대체한다. 소급 회차는
    // 이제 거부가 아니라 삽입 위치 계산 + 뒤 회차 시프트로 처리되고, roundNo 오름차순=점검일 순서
    // 불변식은 그 재정렬로 유지된다.
    @Test
    void createInspection_점검일이기존최신회차보다이전_거부하지않고중간회차로삽입된다() {
        // 기존 3회차(7/10, 7/25, 7/28)가 있는 상태에서 7/20을 소급 입력 → 7/10 뒤, 7/25 앞 = 2회차 자리.
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(3);
        when(inspectionRepository.countByFacilityIdAndInspectionDateLessThanEqual(1L, LocalDate.of(2026, 7, 20)))
                .thenReturn(1L);
        when(inspectionRepository.saveAndFlush(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));

        InspectionResponse response = service.createInspection(request, 100L, 300L);

        assertThat(response.roundNo()).isEqualTo(2);
    }

    // 시프트는 반드시 2단계여야 한다 — unique(facility_id, round_no)가 non-deferrable이라 단일
    // `round_no + 1` UPDATE는 행 단위 제약 검사에서 중간 충돌한다(InspectionRepository 주석 참고).
    @Test
    void createInspection_소급회차삽입_뒤회차를2단계오프셋UPDATE로시프트한다() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(3);
        when(inspectionRepository.countByFacilityIdAndInspectionDateLessThanEqual(1L, LocalDate.of(2026, 7, 20)))
                .thenReturn(1L);
        when(inspectionRepository.saveAndFlush(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));

        service.createInspection(request, 100L, 300L);

        InOrder inOrder = inOrder(facilityService, inspectionRepository, reportRepository);
        // TOCTOU 방지 — 삽입 위치 계산·시프트는 전부 시설물 행 잠금 뒤에서 일어나야 한다(#1291 P1 회귀선).
        inOrder.verify(facilityService).lockForUpdate(1L);
        inOrder.verify(inspectionRepository).countByFacilityIdAndInspectionDateLessThanEqual(
                1L, LocalDate.of(2026, 7, 20));
        inOrder.verify(inspectionRepository).shiftRoundNoToStagingRange(
                1L, 2, InspectionService.ROUND_NO_SHIFT_OFFSET);
        inOrder.verify(inspectionRepository).settleShiftedRoundNo(
                1L, InspectionService.ROUND_NO_SHIFT_OFFSET);
        // 밀린 회차의 DRAFT 보고서 스냅샷도 같은 트랜잭션에서 현재 회차로 재동기화된다(FINALIZED는 동결).
        inOrder.verify(reportRepository).syncDraftRoundNoToInspection(1L, ReportStatus.DRAFT.name());
    }

    // 같은 날짜는 기존 회차 "뒤"에 붙인다 — 삽입 위치 계산이 `<=`라 동일 날짜 기존 회차까지 세어진다
    // (동일 날짜 여러 회차 시 최신 등록분을 최근으로 취급하는 기존 정렬 정책과 정합).
    @Test
    void createInspection_점검일이기존최신회차와같음_맨뒤에붙고시프트없음() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 25), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L)).thenReturn(3);
        when(inspectionRepository.countByFacilityIdAndInspectionDateLessThanEqual(1L, LocalDate.of(2026, 7, 25)))
                .thenReturn(3L);
        when(inspectionRepository.saveAndFlush(any(Inspection.class))).thenAnswer(inv -> inv.getArgument(0));

        InspectionResponse response = service.createInspection(request, 100L, 300L);

        assertThat(response.roundNo()).isEqualTo(4);
        // 맨 뒤에 붙는 가장 흔한 경로에서는 시프트 UPDATE를 아예 실행하지 않는다.
        verify(inspectionRepository, never()).shiftRoundNoToStagingRange(anyLong(), anyInt(), anyInt());
        verify(inspectionRepository, never()).settleShiftedRoundNo(anyLong(), anyInt());
        verify(reportRepository, never()).syncDraftRoundNoToInspection(anyLong(), any());
    }

    // 2단계 시프트는 "실제 회차 번호 < 오프셋"을 전제로 한다. 전제가 깨지면 조용히 데이터를 망가뜨리는
    // 대신 즉시 실패해야 한다(실무상 도달 불가한 방어선).
    @Test
    void createInspection_회차번호가시프트오프셋을넘으면_즉시실패하고저장안됨() {
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(inspectionRepository.findMaxRoundNoByFacilityId(1L))
                .thenReturn(InspectionService.ROUND_NO_SHIFT_OFFSET);

        assertThatThrownBy(() -> service.createInspection(request, 100L, 300L))
                .isInstanceOf(IllegalStateException.class);
        verify(inspectionRepository, never()).saveAndFlush(any());
        verify(inspectionRepository, never()).shiftRoundNoToStagingRange(anyLong(), anyInt(), anyInt());
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
        assertThat(response.type()).isEqualTo(InspectionType.REGULAR);
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
    void advanceStatus_전이된Inspection엔티티를반환한다() {
        // #494/#495 — InspectionAnalysisWorker가 ANALYZED 전이 직후 이 반환값의
        // createdBy/assignedInspectorId/roundNo로 ANALYSIS_DONE/REVIEW_PENDING 알림을 발행한다.
        Inspection inspection = Inspection.builder()
                .facilityId(1L)
                .createdBy(100L)
                .assignedInspectorId(200L)
                .roundNo(3)
                .inspectionDate(LocalDate.of(2026, 7, 20))
                .status(InspectionStatus.ANALYZING)
                .build();
        setId(inspection, 10L);
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());

        Inspection result = service.advanceStatus(300L, 100L, 10L, InspectionStatus.ANALYZED);

        assertThat(result.getStatus()).isEqualTo(InspectionStatus.ANALYZED);
        assertThat(result.getCreatedBy()).isEqualTo(100L);
        assertThat(result.getAssignedInspectorId()).isEqualTo(200L);
        assertThat(result.getRoundNo()).isEqualTo(3);
    }

    private Inspection analyzedInspection() {
        Inspection inspection = Inspection.builder()
                .facilityId(1L)
                .status(InspectionStatus.ANALYZED)
                .build();
        setId(inspection, 10L);
        return inspection;
    }

    @Test
    void confirmReview_ANALYZED이고미확정하자없으면REVIEWED로원자적전이한다() {
        Inspection inspection = analyzedInspection();
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(defectRepository.existsByInspectionIdAndDeletedFalseAndReviewedFalse(10L))
                .thenReturn(false);
        when(inspectionRepository.confirmReviewIfAnalyzed(10L, InspectionStatus.REVIEWED, InspectionStatus.ANALYZED))
                .thenReturn(1);

        service.confirmReview(300L, 100L, 10L);

        verify(inspectionRepository)
                .confirmReviewIfAnalyzed(10L, InspectionStatus.REVIEWED, InspectionStatus.ANALYZED);
    }

    // PR머신 리뷰 P1 회귀 방지 — 등급 수정(Defect.review)만으로도 reviewed=true가 되고 status는
    // DETECTED로 남는다(status는 changeStatus로만 바뀜). status 기준으로 막던 예전 버그는 이
    // 정상 시나리오에서 confirmReview를 항상 거부했다.
    @Test
    void confirmReview_하자status가DETECTED로남아있어도reviewed가전부true면성공한다() {
        Inspection inspection = analyzedInspection();
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(defectRepository.existsByInspectionIdAndDeletedFalseAndReviewedFalse(10L))
                .thenReturn(false);
        when(inspectionRepository.confirmReviewIfAnalyzed(10L, InspectionStatus.REVIEWED, InspectionStatus.ANALYZED))
                .thenReturn(1);

        service.confirmReview(300L, 100L, 10L);

        verify(inspectionRepository)
                .confirmReviewIfAnalyzed(10L, InspectionStatus.REVIEWED, InspectionStatus.ANALYZED);
    }

    @Test
    void confirmReview_원자적UPDATE가0건이면INSPECTION_ROUND_CONFLICT() {
        // 사전 체크(ANALYZED 확인) 이후 다른 요청(재분석 선점 등)이 먼저 상태를 바꾼 경합 상황.
        Inspection inspection = analyzedInspection();
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(defectRepository.existsByInspectionIdAndDeletedFalseAndReviewedFalse(10L))
                .thenReturn(false);
        when(inspectionRepository.confirmReviewIfAnalyzed(10L, InspectionStatus.REVIEWED, InspectionStatus.ANALYZED))
                .thenReturn(0);

        assertThatThrownBy(() -> service.confirmReview(300L, 100L, 10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_ROUND_CONFLICT));
    }

    @Test
    void confirmReview_이미REVIEWED면멱등하게아무것도안한다() {
        Inspection inspection = Inspection.builder().facilityId(1L).status(InspectionStatus.REVIEWED).build();
        setId(inspection, 10L);
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());

        service.confirmReview(300L, 100L, 10L);

        assertThat(inspection.getStatus()).isEqualTo(InspectionStatus.REVIEWED);
        verify(defectRepository, never()).existsByInspectionIdAndDeletedFalseAndReviewedFalse(any());
        verify(inspectionRepository, never()).confirmReviewIfAnalyzed(any(), any(), any());
    }

    @Test
    void confirmReview_이미REPORTED면멱등하게아무것도안한다() {
        Inspection inspection = Inspection.builder().facilityId(1L).status(InspectionStatus.REPORTED).build();
        setId(inspection, 10L);
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());

        service.confirmReview(300L, 100L, 10L);

        assertThat(inspection.getStatus()).isEqualTo(InspectionStatus.REPORTED);
    }

    @Test
    void confirmReview_ANALYZED이전상태면INSPECTION_REVIEW_NOT_READY() {
        Inspection inspection = Inspection.builder().facilityId(1L).status(InspectionStatus.UPLOADING).build();
        setId(inspection, 10L);
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());

        assertThatThrownBy(() -> service.confirmReview(300L, 100L, 10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_REVIEW_NOT_READY));
    }

    @Test
    void confirmReview_미확정하자가남아있으면INSPECTION_REVIEW_INCOMPLETE() {
        Inspection inspection = analyzedInspection();
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(defectRepository.existsByInspectionIdAndDeletedFalseAndReviewedFalse(10L))
                .thenReturn(true);

        assertThatThrownBy(() -> service.confirmReview(300L, 100L, 10L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSPECTION_REVIEW_INCOMPLETE));
        assertThat(inspection.getStatus()).isEqualTo(InspectionStatus.ANALYZED);
        verify(inspectionRepository, never()).confirmReviewIfAnalyzed(any(), any(), any());
    }

    // ── revertStuckAnalyzing(리퍼 전용, 리뷰 P1 픽스 #1654) ──
    // 되돌릴 대상 상태가 기존 하자 유무로 갈린다: 하자 있으면(증분 분석 ANALYZED→ANALYZING 고착)
    // ANALYZED로, 하자 없으면(첫 분석 고착) 기존대로 UPLOADING으로.

    private Inspection analyzingInspection() {
        Inspection inspection = Inspection.builder().facilityId(1L).status(InspectionStatus.ANALYZING).build();
        setId(inspection, 10L);
        return inspection;
    }

    @Test
    void revertStuckAnalyzing_기존하자가있으면_증분분석고착으로보고ANALYZED로되돌린다() {
        // 배경 — 예전엔 무조건 UPLOADING으로 되돌렸는데, 증분 분석(ANALYZED 회차에 새로 업로드된
        // 미분석 사진만 처리 중)이 고착되면 UPLOADING으로 떨어진 회차가 "미분석 사진 없이 하자만
        // 있는" 상태로 보여 InspectionAnalysisService.startAnalysis의 강제 전체 재분석 fail-closed
        // 가드에 걸려 영구 정지했다.
        Inspection inspection = analyzingInspection();
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        when(defectRepository.existsByInspectionIdAndDeletedFalse(10L)).thenReturn(true);

        InspectionStatus reverted = service.revertStuckAnalyzing(10L);

        assertThat(inspection.getStatus()).isEqualTo(InspectionStatus.ANALYZED);
        // 핫픽스(#1670 후속) — 반환값을 InspectionAnalysisService.startAnalysis 인라인 복구가
        // statusBeforeAnalysis로 그대로 쓴다(중복 구현 금지). 실제로 되돌린 상태와 일치해야 한다.
        assertThat(reverted).isEqualTo(InspectionStatus.ANALYZED);
    }

    @Test
    void revertStuckAnalyzing_기존하자가없으면_첫분석고착으로보고UPLOADING으로되돌린다() {
        Inspection inspection = analyzingInspection();
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));
        when(defectRepository.existsByInspectionIdAndDeletedFalse(10L)).thenReturn(false);

        InspectionStatus reverted = service.revertStuckAnalyzing(10L);

        assertThat(inspection.getStatus()).isEqualTo(InspectionStatus.UPLOADING);
        assertThat(reverted).isEqualTo(InspectionStatus.UPLOADING);
    }

    @Test
    void revertStuckAnalyzing_ANALYZING이아니면_상태를건드리지않고현재상태를그대로반환한다() {
        // 멱등 — 그 사이 정상 완료됐거나 다른 경로가 이미 정리한 경우. 핫픽스(#1670 후속) — 반환값이
        // 이제 InspectionAnalysisService.startAnalysis의 statusBeforeAnalysis로 그대로 쓰이므로,
        // no-op이어도 "현재 상태"를 정확히 돌려줘야 그 이후 가드(ANALYSIS_ALLOWED_SOURCE_STATUSES 등)가
        // 올바르게 판단한다.
        Inspection inspection = analyzedInspection();
        when(inspectionRepository.findById(10L)).thenReturn(Optional.of(inspection));

        InspectionStatus reverted = service.revertStuckAnalyzing(10L);

        assertThat(inspection.getStatus()).isEqualTo(InspectionStatus.ANALYZED);
        assertThat(reverted).isEqualTo(InspectionStatus.ANALYZED);
        verify(defectRepository, never()).existsByInspectionIdAndDeletedFalse(any());
    }

    @Test
    void revertStuckAnalyzing_회차를찾지못하면_null을반환한다() {
        when(inspectionRepository.findById(999L)).thenReturn(Optional.empty());

        InspectionStatus reverted = service.revertStuckAnalyzing(999L);

        assertThat(reverted).isNull();
        verify(defectRepository, never()).existsByInspectionIdAndDeletedFalse(any());
    }

    @Test
    void list_owner스코프로위임_필터그대로전달_시설물명담당자명하자건수포함매핑() {
        Pageable pageable = PageRequest.of(0, 20);
        Inspection inspection = inspectionWithFacility(10L, 1L, "테스트빌딩", 200L, InspectionStatus.ANALYZED, InspectionType.DETAILED);
        Page<Inspection> page = new PageImpl<>(List.of(inspection), pageable, 1);
        when(inspectionRepository.findPageByCompanyIdAndFilters(
                eq(criteria(100L, 1L, InspectionStatus.ANALYZED, null, null, null)), any(Pageable.class)))
                .thenReturn(page);
        when(defectRepository.countGroupByInspectionId(List.of(10L)))
                .thenReturn(List.of(countProjection(10L, 3L)));
        when(defectRepository.countGroupByInspectionIdAndGrade(List.of(10L)))
                .thenReturn(List.of(gradeCountProjection(10L, DefectGrade.B, 2L), gradeCountProjection(10L, DefectGrade.C, 1L)));
        User inspector = User.builder().name("김점검").build();
        ReflectionTestUtils.setField(inspector, "id", 200L);
        when(userRepository.findAllById(List.of(200L))).thenReturn(List.of(inspector));

        PageResponse<InspectionListItemResponse> response =
                service.list(300L, 100L, 1L, InspectionStatus.ANALYZED, null, null, null, pageable);

        assertThat(response.content()).hasSize(1);
        InspectionListItemResponse item = response.content().get(0);
        assertThat(item.id()).isEqualTo(10L);
        assertThat(item.facilityId()).isEqualTo(1L);
        assertThat(item.facilityName()).isEqualTo("테스트빌딩");
        assertThat(item.assignedInspectorId()).isEqualTo(200L);
        assertThat(item.assigneeName()).isEqualTo("김점검");
        assertThat(item.status()).isEqualTo(InspectionStatus.ANALYZED);
        assertThat(item.type()).isEqualTo(InspectionType.DETAILED);
        assertThat(item.defectCount()).isEqualTo(3L);
        assertThat(item.gradeDistribution())
                .containsExactlyInAnyOrderEntriesOf(Map.of("A", 0L, "B", 2L, "C", 1L, "D", 0L, "E", 0L));
        verify(companyScopeGuard).requireEffectiveMembership(300L, 100L);
        verify(inspectionRepository)
                .findPageByCompanyIdAndFilters(
                        criteria(100L, 1L, InspectionStatus.ANALYZED, null, null, null), pageable);
    }

    @Test
    void list_하자조건필터_레포지토리에그대로전달() {
        // #878(HAJA-452) — nl-search 필터(defectType/defectGrade/defectStatus)가 그대로
        // repository 호출에 실려 전달되는지 확인(EXISTS 서브쿼리 자체는 InspectionRepositoryTest에서 검증).
        Pageable pageable = PageRequest.of(0, 20);
        List<DefectType> types = List.of(DefectType.CRACK, DefectType.SPALLING);
        List<DefectGrade> grades = List.of(DefectGrade.D, DefectGrade.E);
        List<DefectStatus> statuses = List.of(DefectStatus.DETECTED);
        when(inspectionRepository.findPageByCompanyIdAndFilters(
                eq(criteria(100L, null, null, types, grades, statuses)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        service.list(300L, 100L, null, null, types, grades, statuses, pageable);

        verify(inspectionRepository)
                .findPageByCompanyIdAndFilters(criteria(100L, null, null, types, grades, statuses), pageable);
    }

    @Test
    void list_결과없으면빈페이지_하자레포사용자레포조회안함() {
        Pageable pageable = PageRequest.of(0, 20);
        when(inspectionRepository.findPageByCompanyIdAndFilters(
                eq(criteria(100L, null, null, null, null, null)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PageResponse<InspectionListItemResponse> response =
                service.list(300L, 100L, null, null, null, null, null, pageable);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
        verify(defectRepository, never()).countGroupByInspectionId(any());
        verify(defectRepository, never()).countGroupByInspectionIdAndGrade(any());
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void list_하자담당자정보없으면기본값() {
        Pageable pageable = PageRequest.of(0, 20);
        Inspection inspection = inspectionWithFacility(10L, 1L, "테스트빌딩", 200L, InspectionStatus.CREATED);
        when(inspectionRepository.findPageByCompanyIdAndFilters(
                eq(criteria(100L, null, null, null, null, null)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(inspection), pageable, 1));
        when(defectRepository.countGroupByInspectionId(List.of(10L))).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionIdAndGrade(List.of(10L))).thenReturn(List.of());
        when(userRepository.findAllById(List.of(200L))).thenReturn(List.of());

        PageResponse<InspectionListItemResponse> response =
                service.list(300L, 100L, null, null, null, null, null, pageable);

        InspectionListItemResponse item = response.content().get(0);
        assertThat(item.defectCount()).isZero();
        assertThat(item.assigneeName()).isEqualTo("-");
    }

    @Test
    void list_점검에하자가없으면등급분포전부0() {
        Pageable pageable = PageRequest.of(0, 20);
        Inspection inspection = inspectionWithFacility(10L, 1L, "테스트빌딩", 200L, InspectionStatus.CREATED);
        when(inspectionRepository.findPageByCompanyIdAndFilters(
                eq(criteria(100L, null, null, null, null, null)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(inspection), pageable, 1));
        when(defectRepository.countGroupByInspectionId(List.of(10L))).thenReturn(List.of());
        when(defectRepository.countGroupByInspectionIdAndGrade(List.of(10L))).thenReturn(List.of());
        when(userRepository.findAllById(List.of(200L))).thenReturn(List.of());

        PageResponse<InspectionListItemResponse> response =
                service.list(300L, 100L, null, null, null, null, null, pageable);

        InspectionListItemResponse item = response.content().get(0);
        assertThat(item.gradeDistribution())
                .containsExactlyInAnyOrderEntriesOf(Map.of("A", 0L, "B", 0L, "C", 0L, "D", 0L, "E", 0L));
    }

    @Test
    void list_회사없는사용자_FORBIDDEN예외() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(300L, null);

        assertThatThrownBy(() -> service.list(300L, null, null, null, null, null, null, PageRequest.of(0, 10)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        verify(inspectionRepository, never())
                .findPageByCompanyIdAndFilters(any(InspectionSearchCriteria.class), any(Pageable.class));
    }

    private static InspectionSearchCriteria criteria(
            Long companyId, Long facilityId, InspectionStatus status,
            List<DefectType> defectTypes, List<DefectGrade> defectGrades, List<DefectStatus> defectStatuses) {
        return new InspectionSearchCriteria(
                companyId,
                facilityId,
                status == null ? null : List.of(status),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                defectTypes,
                defectGrades,
                defectStatuses);
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

    private static InspectionGradeCountProjection gradeCountProjection(Long inspectionId, DefectGrade grade, long cnt) {
        return new InspectionGradeCountProjection() {
            @Override
            public Long getInspectionId() {
                return inspectionId;
            }

            @Override
            public DefectGrade getGrade() {
                return grade;
            }

            @Override
            public long getCnt() {
                return cnt;
            }
        };
    }

    @Test
    void createInspection_시설물이_플랜하향으로_읽기전용이면_생성차단되고_저장안됨() {
        // #890 — 하향으로 한도를 넘긴 시설물은 조회·기존 점검 이력은 살리되 신규 점검 생성만 막는다.
        // 차단 시 회차 INSERT 가 시도조차 되지 않아야 한다(부작용 부재).
        InspectionCreateRequest request = new InspectionCreateRequest(1L, LocalDate.of(2026, 7, 20), 200L);
        when(facilityService.get(300L, 100L, 1L)).thenReturn(ownedFacility());
        when(quotaService.isFacilityReadOnly(100L, 1L)).thenReturn(true);

        assertThatThrownBy(() -> service.createInspection(request, 100L, 300L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLAN_FACILITY_QUOTA_EXCEEDED));

        verify(inspectionRepository, never()).saveAndFlush(any(Inspection.class));
        verify(facilityService, never()).lockForUpdate(anyLong());
    }
}
