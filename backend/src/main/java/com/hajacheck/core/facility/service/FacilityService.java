package com.hajacheck.core.facility.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.facility.dto.FacilityCreateRequest;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.dto.FacilityScheduleRequest;
import com.hajacheck.core.facility.dto.FacilityStatusResponse;
import com.hajacheck.core.facility.dto.FacilityUpdateRequest;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시설물 CRUD — 모든 조회/수정/삭제는 로그인 사용자의 회사 스코프로 제한한다.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class FacilityService {

    // 시설물 목록 조회 상한(#484) — 계약(응답 배열) 은 그대로 유지한 채 무제한 반환을 막는 방어적 상한.
    // 대시보드 RECENT_LIMIT(10)·알림 LIST_LIMIT(30)·다가오는점검 UPCOMING_INSPECTIONS_MAX_LIMIT(50) 은
    // "요약/미리보기" 목적이라 작지만, 시설물 목록은 관리 대상 자산 전체를 보여주는 화면이라 그보다
    // 훨씬 크게 잡는다. 진짜 페이지네이션(Page 응답) 전환 전까지의 임시 방어값.
    private static final int FACILITY_LIST_MAX = 500;

    // 시설물 현황 목록(#540 ⑥, HAJA-378) — dDay 산출 기준 시각대(DashboardService 와 동일 관례).
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final FacilityRepository facilityRepository;
    private final CompanyScopeGuard companyScopeGuard;
    private final AuthService authService;
    private final InspectionRepository inspectionRepository;
    private final UserRepository userRepository;

    @Transactional
    public FacilityResponse create(Long userId, Long companyId, FacilityCreateRequest request) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        validateAssigneeIfPresent(userId, request.assigneeUserId());
        Facility facility = Facility.builder()
                .companyId(companyId)
                .name(request.name())
                .type(request.type())
                .address(request.address())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .builtYear(request.builtYear())
                .scale(request.scale())
                .inspectionCycleMonths(request.inspectionCycleMonths())
                .nextInspectionDueAt(request.nextInspectionDueAt())
                .initialGrade(request.initialGrade())
                .assigneeUserId(request.assigneeUserId())
                .memo(request.memo())
                .build();
        Facility saved = facilityRepository.save(facility);
        return FacilityResponse.from(saved);
    }

    public List<FacilityResponse> list(Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        List<Facility> facilities =
                facilityRepository.findByCompanyIdOrderByIdAsc(companyId, PageRequest.of(0, FACILITY_LIST_MAX));
        // #484 상한(500건)에 걸리면 나머지가 무고지로 잘린다(#502 P2) — 운영 감지를 위해 WARN 로그를 남긴다.
        // 응답 계약(List<FacilityResponse>)은 유지하고, 진짜 페이지네이션 전환 전까지의 임시 관측 수단이다.
        if (facilities.size() == FACILITY_LIST_MAX) {
            long actualCount = facilityRepository.countByCompanyId(companyId);
            log.warn("시설물 목록 상한({}) 도달 — companyId={} 실제 보유 {}건, 상한 초과분 응답에서 누락",
                    FACILITY_LIST_MAX, companyId, actualCount);
        }
        return facilities.stream()
                .map(FacilityResponse::from)
                .toList();
    }

    public FacilityResponse get(Long userId, Long companyId, Long facilityId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        return FacilityResponse.from(findCompanyFacility(companyId, facilityId));
    }

    /**
     * 시설물 현황 전용 목록(#540 ⑥, HAJA-378) — 대시보드 스타일 테이블 화면 전용 읽기 전용 조회.
     * list()와 동일한 회사 스코프·상한(#484) 정책을 재사용하되, 응답에 상태(initialGrade)·D-day·
     * 담당자명·최근 점검일을 함께 계산해 붙인다. 담당자명/최근 점검일은 N+1 을 피하기 위해
     * 배치 조회(findAllById/findLatestByFacilityIds) 후 Map 으로 조립한다
     * (DashboardService.getRecentInspections() 의 creatorNameById 패턴과 동일).
     */
    public List<FacilityStatusResponse> listStatus(Long userId, Long companyId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        List<Facility> facilities =
                facilityRepository.findByCompanyIdOrderByIdAsc(companyId, PageRequest.of(0, FACILITY_LIST_MAX));
        if (facilities.size() == FACILITY_LIST_MAX) {
            long actualCount = facilityRepository.countByCompanyId(companyId);
            log.warn("시설물 현황 목록 상한({}) 도달 — companyId={} 실제 보유 {}건, 상한 초과분 응답에서 누락",
                    FACILITY_LIST_MAX, companyId, actualCount);
        }
        if (facilities.isEmpty()) {
            return List.of();
        }

        LocalDate today = LocalDate.now(KST);
        List<Long> facilityIds = facilities.stream().map(Facility::getId).toList();

        Map<Long, LocalDate> lastInspectedByFacilityId =
                inspectionRepository.findLatestByFacilityIds(facilityIds).stream()
                        .collect(Collectors.toMap(Inspection::getFacilityId, Inspection::getInspectionDate));

        List<Long> assigneeIds = facilities.stream()
                .map(Facility::getAssigneeUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, String> assigneeNameById = assigneeIds.isEmpty()
                ? Map.of()
                : userRepository.findAllById(assigneeIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getName));

        return facilities.stream()
                .map(facility -> FacilityStatusResponse.of(
                        facility,
                        today,
                        // assigneeUserId 가 null 이면 Map.of()(불변 빈 맵)의 get(null) 이 NPE 를 던지므로
                        // (ImmutableCollections 는 null 키 조회 자체를 금지) null 키는 조회 전에 걸러낸다.
                        facility.getAssigneeUserId() == null
                                ? null : assigneeNameById.get(facility.getAssigneeUserId()),
                        lastInspectedByFacilityId.get(facility.getId())))
                .toList();
    }

    /**
     * 시설물 행 잠금(PESSIMISTIC_WRITE) — 호출부의 트랜잭션이 끝날 때까지 유지된다.
     * dev-05-02(점검 회차 생성)에서 같은 시설물에 대한 동시 회차 생성 요청을 직렬화해
     * round_no 채번 경쟁(unique(facility_id, round_no) 위반)을 막는 용도로 사용.
     * 호출 전 소유권 검증이 끝난 상태(시설물 존재 보장)를 전제하므로 반환값은 사용하지 않는다.
     */
    @Transactional
    public void lockForUpdate(Long facilityId) {
        facilityRepository.findByIdForUpdate(facilityId);
    }

    @Transactional
    public FacilityResponse update(Long userId, Long companyId, Long facilityId, FacilityUpdateRequest request) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Facility facility = findCompanyFacility(companyId, facilityId);
        validateAssigneeIfPresent(userId, request.assigneeUserId());
        facility.updateInfo(
                request.name(),
                request.type(),
                request.address(),
                request.latitude(),
                request.longitude(),
                request.builtYear(),
                request.scale(),
                request.inspectionCycleMonths(),
                request.nextInspectionDueAt(),
                request.initialGrade(),
                request.assigneeUserId(),
                request.memo());
        return FacilityResponse.from(facility);
    }

    @Transactional
    public void delete(Long userId, Long companyId, Long facilityId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        facilityRepository.delete(findCompanyFacility(companyId, facilityId));
    }

    /**
     * 점검주기 설정(dev-04-03, #268) — 회사 스코프 검증 후 엔티티 메서드로 상태전이 위임.
     * 기준일(오늘)은 서비스가 LocalDate.now() 로 산출해 엔티티에 주입한다.
     */
    @Transactional
    public FacilityResponse setSchedule(
            Long userId, Long companyId, Long facilityId, FacilityScheduleRequest request) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Facility facility = findCompanyFacility(companyId, facilityId);
        facility.updateSchedule(request.inspectionCycleMonths(), LocalDate.now());
        return FacilityResponse.from(facility);
    }

    private Facility findCompanyFacility(Long companyId, Long facilityId) {
        return facilityRepository.findByIdAndCompanyId(facilityId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FACILITY_NOT_FOUND));
    }

    /**
     * 담당자 배정 검증(#628 / HAJA-347) — inspections.assigned_inspector_id와 동일하게
     * AuthService.validateAssignableInspector 를 재사용한다. 시설물 담당자는 선택 입력(nullable)이라
     * assigneeUserId 가 없으면 검증을 건너뛴다(값이 있을 때만 활성·역할·회사·멤버십을 검증).
     */
    private void validateAssigneeIfPresent(Long userId, Long assigneeUserId) {
        if (assigneeUserId != null) {
            authService.validateAssignableInspector(userId, assigneeUserId);
        }
    }
}
