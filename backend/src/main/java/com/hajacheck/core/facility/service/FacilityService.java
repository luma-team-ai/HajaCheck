package com.hajacheck.core.facility.service;

import com.hajacheck.core.facility.dto.FacilityCreateRequest;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.dto.FacilityUpdateRequest;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시설물 CRUD — 모든 조회/수정/삭제는 owner(로그인 사용자) 스코프로 제한한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class FacilityService {

    private final FacilityRepository facilityRepository;

    @Transactional
    public FacilityResponse create(Long ownerId, FacilityCreateRequest request) {
        Facility facility = Facility.builder()
                .ownerId(ownerId)
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

    public List<FacilityResponse> list(Long ownerId) {
        return facilityRepository.findByOwnerId(ownerId).stream()
                .map(FacilityResponse::from)
                .toList();
    }

    public FacilityResponse get(Long ownerId, Long facilityId) {
        return FacilityResponse.from(findOwnedFacility(ownerId, facilityId));
    }

    @Transactional
    public FacilityResponse update(Long ownerId, Long facilityId, FacilityUpdateRequest request) {
        Facility facility = findOwnedFacility(ownerId, facilityId);
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
    public void delete(Long ownerId, Long facilityId) {
        facilityRepository.delete(findOwnedFacility(ownerId, facilityId));
    }

    private Facility findOwnedFacility(Long ownerId, Long facilityId) {
        return facilityRepository.findByIdAndOwnerId(facilityId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.FACILITY_NOT_FOUND));
    }
}
