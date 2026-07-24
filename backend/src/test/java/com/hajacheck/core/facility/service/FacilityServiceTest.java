package com.hajacheck.core.facility.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.facility.dto.FacilityCreateRequest;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.dto.FacilityScheduleRequest;
import com.hajacheck.core.facility.dto.FacilityStatusResponse;
import com.hajacheck.core.facility.dto.FacilityUpdateRequest;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.entity.FacilityInitialGrade;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.ZoneId;
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

    @Mock
    private AuthService authService;

    @Mock
    private InspectionRepository inspectionRepository;

    @Mock
    private UserRepository userRepository;

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

    // 리플렉션으로 id 를 채워 Map 조립(facilityId 기준)이 검증 가능하게 한다 — Facility.id 는
    // @GeneratedValue라 빌더로 직접 설정할 수 없다(FacilityResponse.from 등 기존 테스트는 id 검증을
    // 하지 않아 문제되지 않았지만, listStatus 는 facility.getId() 로 Map 조회를 하므로 필요).
    private Facility facilityWithId(Long id, String name, FacilityInitialGrade grade,
                                     LocalDate nextInspectionDueAt, Long assigneeUserId) {
        Facility facility = Facility.builder()
                .companyId(OWNER_ID)
                .name(name)
                .type("BUILDING")
                .address("서울시 강남구")
                .initialGrade(grade)
                .nextInspectionDueAt(nextInspectionDueAt)
                .assigneeUserId(assigneeUserId)
                .build();
        setId(facility, id);
        return facility;
    }

    private void setId(Facility facility, Long id) {
        try {
            Field idField = Facility.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(facility, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private FacilityCreateRequest createRequest() {
        return new FacilityCreateRequest(
                "테스트빌딩", "BUILDING", "서울시 강남구", null, null, 2010, "지상5층", 12, null,
                null, null, null);
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
                "수정된빌딩", "APARTMENT", "서울시 서초구", null, null, 2015, "지상10층", 6, null,
                null, null, null);

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
                "수정된빌딩", "APARTMENT", null, null, null, null, null, null, null,
                null, null, null);

        assertThatThrownBy(() -> facilityService.update(USER_ID, OWNER_ID, 999L, request))
                .isInstanceOf(BusinessException.class);
    }

    // ── 시설물 등록 필드 확장(#628 / HAJA-347) ──
    // 대표 사진(photoUrls)은 Polalise DDL 검토 후 별도 후속으로 반영 예정(#632) — 이번 범위 테스트 제외.

    @Test
    void create_초기등급담당자메모_함께저장() {
        when(facilityRepository.save(any(Facility.class))).thenAnswer(inv -> inv.getArgument(0));
        FacilityCreateRequest request = new FacilityCreateRequest(
                "테스트빌딩", "BUILDING", "서울시 강남구", null, null, 2010, "지상5층", 12, null,
                FacilityInitialGrade.B, 5L, "1층 로비 CCTV 사각지대 있음");

        FacilityResponse response = facilityService.create(USER_ID, OWNER_ID, request);

        verify(authService).validateAssignableInspector(USER_ID, 5L);
        ArgumentCaptor<Facility> captor = ArgumentCaptor.forClass(Facility.class);
        verify(facilityRepository).save(captor.capture());
        assertThat(captor.getValue().getInitialGrade()).isEqualTo(FacilityInitialGrade.B);
        assertThat(captor.getValue().getAssigneeUserId()).isEqualTo(5L);
        assertThat(captor.getValue().getMemo()).isEqualTo("1층 로비 CCTV 사각지대 있음");
        assertThat(response.initialGrade()).isEqualTo(FacilityInitialGrade.B);
        assertThat(response.assigneeUserId()).isEqualTo(5L);
        assertThat(response.memo()).isEqualTo("1층 로비 CCTV 사각지대 있음");
    }

    @Test
    void create_담당자없음_담당자검증호출안함() {
        when(facilityRepository.save(any(Facility.class))).thenAnswer(inv -> inv.getArgument(0));

        facilityService.create(USER_ID, OWNER_ID, createRequest());

        verify(authService, never()).validateAssignableInspector(any(), any());
    }

    @Test
    void create_배정불가담당자_AUTH_INVALID_INSPECTOR예외_저장호출없음() {
        doThrow(new BusinessException(ErrorCode.AUTH_INVALID_INSPECTOR))
                .when(authService).validateAssignableInspector(USER_ID, 999L);
        FacilityCreateRequest request = new FacilityCreateRequest(
                "테스트빌딩", "BUILDING", null, null, null, null, null, null, null,
                null, 999L, null);

        assertThatThrownBy(() -> facilityService.create(USER_ID, OWNER_ID, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_INVALID_INSPECTOR));
        verify(facilityRepository, never()).save(any());
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

    // ── 시설물 현황 전용 목록(#540 ⑥, HAJA-378) ──

    @Test
    void listStatus_회사시설없으면_빈목록_배치조회미호출() {
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of());

        List<FacilityStatusResponse> result = facilityService.listStatus(USER_ID, OWNER_ID);

        assertThat(result).isEmpty();
        verify(inspectionRepository, never()).findLatestByFacilityIds(any());
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void listStatus_담당자와점검이력있는시설_모든필드조립() {
        // dDay는 서비스가 KST 기준(FacilityService.KST)으로 산출하므로, CI(UTC 러너)에서
        // 시스템 기본 zone(LocalDate.now())으로 만들면 자정 전후 9시간 구간에서 하루 어긋난다 —
        // 같은 KST로 맞춰야 CI/로컬 무관하게 결정론적으로 통과한다.
        Facility facility = facilityWithId(
                10L, "테스트빌딩", FacilityInitialGrade.C,
                LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(5), 5L);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));

        Inspection lastInspection = Inspection.builder()
                .facilityId(10L)
                .createdBy(USER_ID)
                .assignedInspectorId(5L)
                .roundNo(2)
                .inspectionDate(LocalDate.now().minusDays(3))
                .status(InspectionStatus.CREATED)
                .build();
        when(inspectionRepository.findLatestByFacilityIds(List.of(10L))).thenReturn(List.of(lastInspection));

        User assignee = User.builder()
                .email("assignee@haja.com").name("담당자김").role(Role.INSPECTOR)
                .passwordHash("$2a$10$hashed").status(UserStatus.ACTIVE).build();
        setUserId(assignee, 5L);
        when(userRepository.findAllById(List.of(5L))).thenReturn(List.of(assignee));

        List<FacilityStatusResponse> result = facilityService.listStatus(USER_ID, OWNER_ID);

        assertThat(result).hasSize(1);
        FacilityStatusResponse status = result.get(0);
        assertThat(status.facilityId()).isEqualTo(10L);
        assertThat(status.facilityName()).isEqualTo("테스트빌딩");
        assertThat(status.initialGrade()).isEqualTo(FacilityInitialGrade.C);
        assertThat(status.dDay()).isEqualTo(5L);
        assertThat(status.assigneeUserId()).isEqualTo(5L);
        assertThat(status.assigneeName()).isEqualTo("담당자김");
        assertThat(status.lastInspectedAt()).isEqualTo(LocalDate.now().minusDays(3));
    }

    @Test
    void listStatus_담당자없고점검이력없는시설_null필드로반환_에러없음() {
        Facility facility = facilityWithId(11L, "미배정시설", null, null, null);
        when(facilityRepository.findByCompanyIdOrderByIdAsc(eq(OWNER_ID), any(PageRequest.class)))
                .thenReturn(List.of(facility));
        when(inspectionRepository.findLatestByFacilityIds(List.of(11L))).thenReturn(List.of());

        List<FacilityStatusResponse> result = facilityService.listStatus(USER_ID, OWNER_ID);

        assertThat(result).hasSize(1);
        FacilityStatusResponse status = result.get(0);
        assertThat(status.facilityId()).isEqualTo(11L);
        assertThat(status.initialGrade()).isNull();
        assertThat(status.nextInspectionDueAt()).isNull();
        assertThat(status.dDay()).isNull();
        assertThat(status.assigneeUserId()).isNull();
        assertThat(status.assigneeName()).isNull();
        assertThat(status.lastInspectedAt()).isNull();
        // 담당자 배정된 시설이 하나도 없으면 배치 사용자 조회 자체를 생략한다(불필요 쿼리 방지).
        verify(userRepository, never()).findAllById(any());
    }

    @Test
    void listStatus_회사스코프검증_먼저호출() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(USER_ID, OWNER_ID);

        assertThatThrownBy(() -> facilityService.listStatus(USER_ID, OWNER_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        verify(facilityRepository, never()).findByCompanyIdOrderByIdAsc(any(), any());
    }

    private void setUserId(User user, Long id) {
        try {
            Field idField = User.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(user, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
