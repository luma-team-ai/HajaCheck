package com.hajacheck.core.defect.service;

import com.hajacheck.core.defect.dto.DefectResponse;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.repository.InspectionRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하자(Defect) 조회 서비스. 소유권 검증(IDOR 방지)을 위해 {@code InspectionService}와 동일한
 * 패턴을 적용: 하자가 속한 점검 → 점검이 속한 시설물 → 시설물 소유권 검증.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefectService {

    private final DefectRepository defectRepository;
    private final InspectionRepository inspectionRepository;
    private final FacilityService facilityService;

    /**
     * 하자를 단건 조회한다. 요청자가 소유한 시설물의 하자만 조회 가능(IDOR 방지).
     *
     * @param requesterUserId 요청 사용자 ID
     * @param defectId        조회할 하자 ID
     * @return 하자 정보
     * @throws BusinessException DEFECT_NOT_FOUND — 하자 미존재
     * @throws BusinessException FACILITY_NOT_FOUND — 하자가 속한 시설물이 미존재하거나 타인 소유
     */
    public DefectResponse getDefect(Long requesterUserId, Long defectId) {
        Defect defect = defectRepository.findById(defectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));

        // 소유권 검증 — 하자의 점검 → 점검의 시설물 소유권 확인(InspectionService.getInspection() 패턴 적용)
        Inspection inspection = inspectionRepository.findById(defect.getInspectionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        // 미존재/타인소유 모두 FacilityService.get()이 FACILITY_NOT_FOUND로 통일 응답 → 그대로 전파
        var facility = facilityService.get(requesterUserId, inspection.getFacilityId());

        return DefectResponse.from(defect, facility.type());
    }
}
