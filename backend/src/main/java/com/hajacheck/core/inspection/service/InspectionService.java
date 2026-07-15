package com.hajacheck.core.inspection.service;

import com.hajacheck.auth.service.AuthService;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.dto.InspectionCreateRequest;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InspectionService {

    private final InspectionRepository inspectionRepository;
    private final FacilityService facilityService;
    private final AuthService authService;

    @Transactional
    public InspectionResponse createInspection(InspectionCreateRequest request, Long creatorUserId) {
        // 시설물 선택 검증 — 본인 소유 시설물만 회차 생성 가능(PRD FR-1 권한 매트릭스: 시설물 등록·조회 "본인 소유만").
        // FacilityService.get()이 미존재/타인소유 모두 FACILITY_NOT_FOUND로 던지므로 그대로 검증에 사용(dev 브랜치 기존 구현).
        facilityService.get(creatorUserId, request.facilityId());

        // 담당자 배정 검증 — users.status=ACTIVE AND role IN (INSPECTOR, ADMIN)(table_design.md §inspections).
        authService.validateAssignableInspector(request.assignedInspectorId());

        int nextRoundNo = inspectionRepository.findMaxRoundNoByFacilityId(request.facilityId()) + 1;

        Inspection inspection = Inspection.builder()
                .facilityId(request.facilityId())
                .createdBy(creatorUserId)
                .assignedInspectorId(request.assignedInspectorId())
                .roundNo(nextRoundNo)
                .inspectionDate(request.inspectionDate())
                .build();

        return InspectionResponse.from(inspectionRepository.save(inspection));
    }

    public InspectionResponse getInspection(Long inspectionId) {
        return inspectionRepository.findById(inspectionId)
                .map(InspectionResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSPECTION_NOT_FOUND));
    }
}
