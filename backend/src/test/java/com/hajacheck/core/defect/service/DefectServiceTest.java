package com.hajacheck.core.defect.service;

import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.defect.dto.DefectActionResultRequest;
import com.hajacheck.core.defect.dto.DefectResponse;
import com.hajacheck.core.defect.dto.DefectRevisionResponse;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectRevision;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectRevisionRepository;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.DomainValidationException;
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

    @Mock
    private DefectRevisionRepository defectRevisionRepository;
    @Mock
    private CompanyScopeGuard companyScopeGuard;
    @Mock
    private AuthService authService;
    @Mock
    private MediaRepository mediaRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private DefectService defectService;

    private static final Long COMPANY_ID = 1L;
    private static final Long USER_ID = 2L;

    // Defect/Inspection/Facility 모두 @NoArgsConstructor(PROTECTED)+@Builder라 리플렉션으로 id를 채운다
    // (FacilityServiceTest와 달리 응답 DTO가 연관관계(inspection.facility)까지 타고 들어가므로
    // 세 엔티티를 함께 구성해야 한다).
    private Defect existingDefect(Long facilityId) {
        return existingDefect(facilityId, DefectStatus.DETECTED);
    }

    private Defect existingDefect(Long facilityId, DefectStatus status) {
        Facility facility = Facility.builder()
                .companyId(COMPANY_ID)
                .name("테스트빌딩")
                .type("BUILDING")
                .build();
        ReflectionTestUtils.setField(facility, "id", facilityId);

        Inspection inspection = Inspection.builder()
                .facilityId(facilityId)
                .createdBy(USER_ID)
                .assignedInspectorId(USER_ID)
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
                .status(status)
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
        when(defectRepository.findPageByCompanyIdAndFilters(
                eq(COMPANY_ID), eq(DefectType.CRACK), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        PageResponse<DefectResponse> response =
                defectService.list(USER_ID, COMPANY_ID, DefectType.CRACK, null, null, pageable);

        assertThat(response.content()).hasSize(1);
        assertThat(response.totalElements()).isEqualTo(1);
        assertThat(response.content().get(0).facilityId()).isEqualTo(5L);
        verify(defectRepository).findPageByCompanyIdAndFilters(COMPANY_ID, DefectType.CRACK, null, null, pageable);
    }

    @Test
    void list_결과없으면빈페이지() {
        Pageable pageable = PageRequest.of(0, 20);
        when(defectRepository.findPageByCompanyIdAndFilters(
                eq(COMPANY_ID), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PageResponse<DefectResponse> response =
                defectService.list(USER_ID, COMPANY_ID, null, null, null, pageable);

        assertThat(response.content()).isEmpty();
        assertThat(response.totalElements()).isZero();
    }

    @Test
    void get_본인소유하자_반환() {
        Defect defect = existingDefect(5L);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));

        DefectResponse response = defectService.get(USER_ID, COMPANY_ID, 10L);

        assertThat(response.id()).isEqualTo(10L);
        assertThat(response.facilityId()).isEqualTo(5L);
        assertThat(response.facilityName()).isEqualTo("테스트빌딩");
        assertThat(response.typeLabel()).isEqualTo("균열");
    }

    @Test
    void get_없는하자_DEFECT_NOT_FOUND예외() {
        when(defectRepository.findByIdAndCompanyId(999L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> defectService.get(USER_ID, COMPANY_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DEFECT_NOT_FOUND));
    }

    @Test
    void get_mediaId있으면_썸네일엔드포인트로imageUrl조립() {
        Defect defect = existingDefect(5L);
        ReflectionTestUtils.setField(defect, "mediaId", 42L);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));

        DefectResponse response = defectService.get(USER_ID, COMPANY_ID, 10L);

        assertThat(response.imageUrl()).isEqualTo("/api/media/42/thumbnail");
    }

    @Test
    void get_mediaId없으면_imageUrlNull() {
        Defect defect = existingDefect(5L);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));

        DefectResponse response = defectService.get(USER_ID, COMPANY_ID, 10L);

        assertThat(response.imageUrl()).isNull();
    }

    @Test
    void get_타인소유하자_DEFECT_NOT_FOUND예외() {
        // findByIdAndCompanyId 는 소유자 스코프라 타인 소유는 조회 자체가 빈 값으로 온다(cross-owner IDOR 방지).
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> defectService.get(USER_ID, COMPANY_ID, 10L))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DEFECT_NOT_FOUND));
    }

    @Test
    void get_조치담당자있으면이름조회해서포함() {
        Defect defect = existingDefect(5L, DefectStatus.RESOLVED);
        ReflectionTestUtils.setField(defect, "actionAssigneeId", 200L);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));
        User assignee = User.builder().name("김현수").build();
        ReflectionTestUtils.setField(assignee, "id", 200L);
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));

        DefectResponse response = defectService.get(USER_ID, COMPANY_ID, 10L);

        assertThat(response.actionAssigneeName()).isEqualTo("김현수");
    }

    @Test
    void get_조치담당자없으면이름조회안함() {
        Defect defect = existingDefect(5L);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));

        DefectResponse response = defectService.get(USER_ID, COMPANY_ID, 10L);

        assertThat(response.actionAssigneeName()).isNull();
        verify(userRepository, never()).findById(any());
    }

    // ── HAJA-393/#725: 조치 결과 등록 ──

    private DefectActionResultRequest actionResultRequest() {
        return new DefectActionResultRequest(50L, "균열 부위 보수 완료", LocalDate.of(2026, 7, 24), 200L);
    }

    @Test
    void registerActionResult_IN_PROGRESS에서_RESOLVED전이및필드저장_이력기록() {
        Defect defect = existingDefect(5L, DefectStatus.IN_PROGRESS);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));
        Media media = Media.builder().inspectionId(100L).build();
        ReflectionTestUtils.setField(media, "id", 50L);
        when(mediaRepository.findByIdAndInspectionId(50L, 100L)).thenReturn(Optional.of(media));
        User assignee = User.builder().name("김현수").build();
        ReflectionTestUtils.setField(assignee, "id", 200L);
        when(userRepository.findById(200L)).thenReturn(Optional.of(assignee));

        DefectResponse response =
                defectService.registerActionResult(USER_ID, COMPANY_ID, 10L, actionResultRequest());

        assertThat(response.status()).isEqualTo(DefectStatus.RESOLVED);
        assertThat(response.actionContent()).isEqualTo("균열 부위 보수 완료");
        assertThat(response.actionDate()).isEqualTo(LocalDate.of(2026, 7, 24));
        assertThat(response.actionAssigneeId()).isEqualTo(200L);
        assertThat(response.actionAssigneeName()).isEqualTo("김현수");
        assertThat(response.actionPhotoUrl()).isEqualTo("/api/media/50/thumbnail");
        assertThat(defect.getStatus()).isEqualTo(DefectStatus.RESOLVED);
        verify(authService).validateAssignableInspector(USER_ID, 200L);
        verify(defectRevisionRepository).save(argThat(revision ->
                revision.getDefectId().equals(10L)
                        && revision.getRevisedBy().equals(USER_ID)
                        && revision.getFieldChanged().equals("status")
                        && revision.getOldValue().equals("IN_PROGRESS")
                        && revision.getNewValue().equals("RESOLVED")));
    }

    @Test
    void registerActionResult_담당자자격없음_예외전파되고저장안됨() {
        Defect defect = existingDefect(5L, DefectStatus.IN_PROGRESS);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));
        doThrow(new BusinessException(ErrorCode.AUTH_INVALID_INSPECTOR))
                .when(authService).validateAssignableInspector(USER_ID, 200L);

        assertThatThrownBy(() -> defectService.registerActionResult(USER_ID, COMPANY_ID, 10L, actionResultRequest()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
        assertThat(defect.getStatus()).isEqualTo(DefectStatus.IN_PROGRESS);
        verify(defectRevisionRepository, never()).save(any());
    }

    @Test
    void registerActionResult_조치후사진이타점검소속_MEDIA_NOT_FOUND예외() {
        // 다른 점검의 media를 mediaId로 넘기면(또는 없는 media) IDOR 차단 — findByIdAndInspectionId가
        // 같은 inspectionId 조건이라 빈 값이 온다.
        Defect defect = existingDefect(5L, DefectStatus.IN_PROGRESS);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));
        when(mediaRepository.findByIdAndInspectionId(50L, 100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> defectService.registerActionResult(USER_ID, COMPANY_ID, 10L, actionResultRequest()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.MEDIA_NOT_FOUND));
        assertThat(defect.getStatus()).isEqualTo(DefectStatus.IN_PROGRESS);
        verify(defectRevisionRepository, never()).save(any());
    }

    @Test
    void registerActionResult_타인소유하자_DEFECT_NOT_FOUND예외() {
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> defectService.registerActionResult(USER_ID, COMPANY_ID, 10L, actionResultRequest()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.DEFECT_NOT_FOUND));
        verify(authService, never()).validateAssignableInspector(any(), any());
    }

    @Test
    void registerActionResult_IN_PROGRESS아닌상태에서호출_순서건너뛴완료차단() {
        // "조치 완료 등록" 폼에는 사유 입력란이 없다 — changeStatus()가 정방향 한 단계(IN_PROGRESS→RESOLVED)만
        // 사유 없이 허용하므로, 그 외 상태(DETECTED 등)에서 호출하면 DomainValidationException으로 자연히 막힌다.
        Defect defect = existingDefect(5L, DefectStatus.DETECTED);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));
        Media media = Media.builder().inspectionId(100L).build();
        ReflectionTestUtils.setField(media, "id", 50L);
        when(mediaRepository.findByIdAndInspectionId(50L, 100L)).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> defectService.registerActionResult(USER_ID, COMPANY_ID, 10L, actionResultRequest()))
                .isInstanceOf(DomainValidationException.class);
        assertThat(defect.getStatus()).isEqualTo(DefectStatus.DETECTED);
        verify(defectRevisionRepository, never()).save(any());
    }

    @Test
    void registerActionResult_이미RESOLVED인하자_예외전파() {
        Defect defect = existingDefect(5L, DefectStatus.RESOLVED);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));
        Media media = Media.builder().inspectionId(100L).build();
        ReflectionTestUtils.setField(media, "id", 50L);
        when(mediaRepository.findByIdAndInspectionId(50L, 100L)).thenReturn(Optional.of(media));

        assertThatThrownBy(() -> defectService.registerActionResult(USER_ID, COMPANY_ID, 10L, actionResultRequest()))
                .isInstanceOf(com.hajacheck.global.exception.DomainStateTransitionException.class);
        verify(defectRevisionRepository, never()).save(any());
    }

    @Test
    void updateStatus_정상전이_다음상태로변경() {
        Defect defect = existingDefect(5L);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));

        DefectResponse response =
                defectService.updateStatus(USER_ID, COMPANY_ID, 10L, DefectStatus.CONFIRMED, null);

        assertThat(response.status()).isEqualTo(DefectStatus.CONFIRMED);
        assertThat(defect.getStatus()).isEqualTo(DefectStatus.CONFIRMED);
        verify(defectRevisionRepository).save(argThat(revision ->
                revision.getDefectId().equals(10L)
                        && revision.getRevisedBy().equals(USER_ID)
                        && revision.getFieldChanged().equals("status")
                        && revision.getOldValue().equals("DETECTED")
                        && revision.getNewValue().equals("CONFIRMED")));
    }

    @Test
    void updateStatus_사유없는건너뛰기요청_DomainValidationException() {
        // existingDefect 는 DETECTED 상태 — CONFIRMED를 건너뛰고 ACTION_PENDING을 사유 없이 요청하면 거부된다.
        Defect defect = existingDefect(5L);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));

        assertThatThrownBy(() ->
                defectService.updateStatus(USER_ID, COMPANY_ID, 10L, DefectStatus.ACTION_PENDING, null))
                .isInstanceOf(DomainValidationException.class);
        assertThat(defect.getStatus()).isEqualTo(DefectStatus.DETECTED);
    }

    @Test
    void updateStatus_사유있는건너뛰기요청_허용및이력기록() {
        Defect defect = existingDefect(5L);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));

        DefectResponse response =
                defectService.updateStatus(
                        USER_ID, COMPANY_ID, 10L, DefectStatus.ACTION_PENDING, "경미한 하자라 검수확정 생략");

        assertThat(response.status()).isEqualTo(DefectStatus.ACTION_PENDING);
        verify(defectRevisionRepository).save(argThat(revision ->
                revision.getOldValue().equals("DETECTED")
                        && revision.getNewValue().equals("ACTION_PENDING")
                        && revision.getReason().equals("경미한 하자라 검수확정 생략")));
    }

    @Test
    void updateStatus_타인소유하자_DEFECT_NOT_FOUND예외() {
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                defectService.updateStatus(USER_ID, COMPANY_ID, 10L, DefectStatus.CONFIRMED, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DEFECT_NOT_FOUND));
    }

    @Test
    void getRevisions_본인소유_이력페이지반환() {
        Defect defect = existingDefect(5L);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.of(defect));
        Pageable pageable = PageRequest.of(0, 20);
        DefectRevision revision = DefectRevision.record(
                10L, USER_ID, "status", "DETECTED", "CONFIRMED", null);
        ReflectionTestUtils.setField(revision, "id", 1L);
        when(defectRevisionRepository.findByDefectIdOrderByCreatedAtDesc(10L, pageable))
                .thenReturn(new PageImpl<>(List.of(revision), pageable, 1));

        PageResponse<DefectRevisionResponse> response =
                defectService.getRevisions(USER_ID, COMPANY_ID, 10L, pageable);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).fieldChanged()).isEqualTo("status");
        assertThat(response.content().get(0).newValue()).isEqualTo("CONFIRMED");
    }

    @Test
    void getRevisions_없는하자_DEFECT_NOT_FOUND예외() {
        Pageable pageable = PageRequest.of(0, 20);
        when(defectRepository.findByIdAndCompanyId(999L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> defectService.getRevisions(USER_ID, COMPANY_ID, 999L, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DEFECT_NOT_FOUND));
    }

    // IDOR 방지 회귀 테스트(필수) — 타 소유자 하자의 활동기록 조회는 404.
    @Test
    void getRevisions_타인소유하자_DEFECT_NOT_FOUND예외() {
        Pageable pageable = PageRequest.of(0, 20);
        when(defectRepository.findByIdAndCompanyId(10L, COMPANY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> defectService.getRevisions(USER_ID, COMPANY_ID, 10L, pageable))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.DEFECT_NOT_FOUND));
    }
    @Test
    void list_회사없는사용자_FORBIDDEN예외() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(USER_ID, null);
        assertThatThrownBy(() ->
                defectService.list(USER_ID, null, null, null, null, PageRequest.of(0, 10)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }
}
