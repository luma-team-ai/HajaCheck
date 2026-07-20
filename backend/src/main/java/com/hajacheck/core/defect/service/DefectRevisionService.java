package com.hajacheck.core.defect.service;

import com.hajacheck.core.defect.dto.DefectDetailItem;
import com.hajacheck.core.defect.dto.DefectRevisionRequest;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectRevision;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectRevisionRepository;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검수 기능(오탐 수정·등급 조정) 서비스 — defect_revisions append-only 이력 관리.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefectRevisionService {

    private final DefectRepository defectRepository;
    private final DefectRevisionRepository defectRevisionRepository;
    private final InspectionService inspectionService;

    /**
     * 점검 회차별 하자 목록 조회(검수·뷰어 공용).
     *
     * @param requesterUserId 요청 사용자 id
     * @param inspectionId    점검 회차 id
     * @return 하자 목록(deleted=false만, id 오름차순)
     * @throws BusinessException 점검 회차 미존재 또는 타인 소유 (404 INSPECTION_NOT_FOUND)
     */
    public List<DefectDetailItem> getDefectsByInspection(Long requesterUserId, Long inspectionId) {
        // 소유권 검증
        inspectionService.getInspection(requesterUserId, inspectionId);

        // 하자 조회(deleted=false만)
        List<Defect> defects = defectRepository.findByInspectionIdAndNotDeleted(inspectionId);
        return defects.stream()
                .map(DefectDetailItem::from)
                .toList();
    }

    /**
     * 하자 검수 — 등급 조정 또는 오탐 삭제(soft delete).
     *
     * @param requesterUserId 검수자 사용자 id
     * @param defectId        하자 id
     * @param request         요청 (grade 또는 isDeleted 정확히 하나 + reason)
     * @return 검수 반영된 하자
     * @throws BusinessException 하자 미존재/타인 소유 (404), 입력 오류 (400), RESOLVED 상태 (409)
     */
    @Transactional
    public DefectDetailItem reviewDefect(Long requesterUserId, Long defectId, DefectRevisionRequest request) {
        // 하자 로드
        Defect defect = defectRepository.findById(defectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));

        // 소유권 검증 — 점검 회차를 통해 확인, 미존재/타인 소유면 404 DEFECT_NOT_FOUND로 통일
        try {
            inspectionService.getInspection(requesterUserId, defect.getInspectionId());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.INSPECTION_NOT_FOUND) {
                throw new BusinessException(ErrorCode.DEFECT_NOT_FOUND);
            }
            throw e;
        }

        // grade와 isDeleted 정확히 하나만 지정 확인
        boolean hasGrade = request.getGrade() != null;
        boolean hasDeleted = request.getDeleted() != null && request.getDeleted();
        if (hasGrade == hasDeleted) {
            // 둘 다 true (둘 다 지정) 또는 둘 다 false (둘 다 미지정)
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // isDeleted=false인 경우는 400
        if (request.getDeleted() != null && !request.getDeleted()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // 변경 전 값 저장 (oldValue)
        String fieldChanged;
        String oldValue;
        String newValue;

        if (hasGrade) {
            // 등급 조정
            DefectGrade oldGrade = defect.getGrade();
            oldValue = oldGrade == null ? null : oldGrade.toString();
            newValue = request.getGrade().toString();
            fieldChanged = "grade";

            defect.review(request.getGrade());
        } else {
            // 오탐 삭제
            oldValue = "false";
            newValue = "true";
            fieldChanged = "is_deleted";

            defect.softDelete();
        }

        // 변경 저장
        defectRepository.save(defect);

        // 이력 기록
        DefectRevision revision = DefectRevision.record(
                defectId,
                requesterUserId,
                fieldChanged,
                oldValue,
                newValue,
                request.getReason());
        defectRevisionRepository.save(revision);

        return DefectDetailItem.from(defect);
    }
}
