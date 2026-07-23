package com.hajacheck.core.facility.service;

import com.hajacheck.auth.service.CompanyScopeGuard;
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

    private final FacilityRepository facilityRepository;
    private final CompanyScopeGuard companyScopeGuard;

    @Transactional
    public FacilityResponse create(Long userId, Long companyId, FacilityCreateRequest request) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
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
                .build();
        return FacilityResponse.from(facilityRepository.save(facility));
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
        facility.updateInfo(
                request.name(),
                request.type(),
                request.address(),
                request.latitude(),
                request.longitude(),
                request.builtYear(),
                request.scale(),
                request.inspectionCycleMonths(),
                request.nextInspectionDueAt());
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
}
