package com.hajacheck.core.defect.service;

import com.hajacheck.core.defect.dto.DefectResponse;
import com.hajacheck.core.defect.dto.DefectRevisionResponse;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectRevision;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectRevisionRepository;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하자 목록·상세 조회 및 상태 전이(HAJA-26) — 모든 조회/변경은 owner(로그인 사용자)가 소유한
 * facilities.owner_id 범위로만 제한한다(cross-owner IDOR 방지, facility 도메인과 동일 원칙).
 * 상태 전이 규칙(정방향 순서 강제, 역행/건너뛰기는 사유 필수)은 Defect#changeStatus 가 담당하고,
 * 전이가 성공하면 defect_revisions에 append-only 이력을 남긴다(PRD FR-4).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefectService {

    private final DefectRepository defectRepository;
    private final DefectRevisionRepository defectRevisionRepository;

    public PageResponse<DefectResponse> list(
            Long ownerId, DefectType type, DefectGrade grade, DefectStatus status, Pageable pageable) {
        Page<Defect> page = defectRepository.findPageByOwnerIdAndFilters(ownerId, type, grade, status, pageable);
        return PageResponse.from(page.map(DefectResponse::from));
    }

    public DefectResponse get(Long ownerId, Long defectId) {
        Defect defect = defectRepository.findByIdAndOwnerId(defectId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        return DefectResponse.from(defect);
    }

    /**
     * 하자 활동 기록 타임라인 조회(HAJA-314) — findByIdAndOwnerId로 소유권을 먼저 검증해
     * cross-owner IDOR을 차단한 뒤에만 defect_revisions를 조회한다(get()과 동일 원칙).
     */
    public PageResponse<DefectRevisionResponse> getRevisions(Long ownerId, Long defectId, Pageable pageable) {
        defectRepository.findByIdAndOwnerId(defectId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        Page<DefectRevision> page = defectRevisionRepository.findByDefectIdOrderByCreatedAtDesc(defectId, pageable);
        return PageResponse.from(page.map(DefectRevisionResponse::from));
    }

    @Transactional
    public DefectResponse updateStatus(Long ownerId, Long defectId, DefectStatus status, String reason) {
        Defect defect = defectRepository.findByIdAndOwnerId(defectId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        DefectStatus previousStatus = defect.getStatus();
        defect.changeStatus(status, reason);
        defectRevisionRepository.save(DefectRevision.record(
                defect.getId(), ownerId, "status", previousStatus.name(), status.name(), reason));
        return DefectResponse.from(defect);
    }
}
