package com.hajacheck.core.inspection.service;

import com.hajacheck.auth.service.AuthService;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.dto.InspectionCreateRequest;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InspectionService {

    // 점검일 상한 — 원거리 미래 날짜(연도 오타 등 비정상 입력) 방어용 여유폭. 정식 "점검 예약" 정책이
    // 확정되면 재조정 필요(현재 PRD 는 사전 예약 스케줄링을 명시하지 않음).
    private static final int MAX_FUTURE_MONTHS = 12;

    private final InspectionRepository inspectionRepository;
    private final FacilityService facilityService;
    private final AuthService authService;

    @Transactional
    public InspectionResponse createInspection(InspectionCreateRequest request, Long creatorUserId) {
        // 시설물 선택 검증 — 본인 소유 시설물만 회차 생성 가능(PRD FR-1 권한 매트릭스: 시설물 등록·조회 "본인 소유만").
        // FacilityService.get()이 미존재/타인소유 모두 FACILITY_NOT_FOUND로 던지므로 그대로 검증에 사용(dev 브랜치 기존 구현).
        FacilityResponse facility = facilityService.get(creatorUserId, request.facilityId());

        // 담당자 배정 검증 — users.status=ACTIVE AND role IN (INSPECTOR, ADMIN) + 요청자와 동일 회사(table_design.md §inspections).
        authService.validateAssignableInspector(creatorUserId, request.assignedInspectorId());

        validateInspectionDate(request.inspectionDate(), facility);

        // 회차 채번 동시성 경쟁 방지 — 같은 시설물에 대한 동시 생성 요청을 행 잠금으로 직렬화한 뒤 max+1 을 읽는다.
        facilityService.lockForUpdate(request.facilityId());
        int nextRoundNo = inspectionRepository.findMaxRoundNoByFacilityId(request.facilityId()) + 1;

        Inspection inspection = Inspection.builder()
                .facilityId(request.facilityId())
                .createdBy(creatorUserId)
                .assignedInspectorId(request.assignedInspectorId())
                .roundNo(nextRoundNo)
                .inspectionDate(request.inspectionDate())
                .build();

        try {
            return InspectionResponse.from(inspectionRepository.save(inspection));
        } catch (DataIntegrityViolationException e) {
            // advisory lock 이 정상 경로는 모두 막지만, 방어적으로 unique(facility_id, round_no) 위반이
            // 그대로 500 으로 노출되지 않도록 통일된 비즈니스 예외로 변환한다.
            throw new BusinessException(ErrorCode.INSPECTION_ROUND_CONFLICT);
        }
    }

    public InspectionResponse getInspection(Long requesterUserId, Long inspectionId) {
        Inspection inspection = inspectionRepository.findById(inspectionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INSPECTION_NOT_FOUND));
        // 소유권 검증 — 본인 소유 시설물의 점검만 조회 가능(IDOR 방지). 미존재/타인소유 모두
        // FacilityService.get()이 FACILITY_NOT_FOUND로 통일 응답.
        facilityService.get(requesterUserId, inspection.getFacilityId());
        return InspectionResponse.from(inspection);
    }

    private void validateInspectionDate(LocalDate inspectionDate, FacilityResponse facility) {
        if (inspectionDate.isBefore(facility.createdAt().toLocalDate())) {
            throw new BusinessException(ErrorCode.INSPECTION_DATE_INVALID);
        }
        if (inspectionDate.isAfter(LocalDate.now().plusMonths(MAX_FUTURE_MONTHS))) {
            throw new BusinessException(ErrorCode.INSPECTION_DATE_INVALID);
        }
    }
}
