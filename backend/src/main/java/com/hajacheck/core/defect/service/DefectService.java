package com.hajacheck.core.defect.service;

import com.hajacheck.core.defect.dto.DefectResponse;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하자 목록·상세 조회 및 상태 전이(HAJA-30) — 모든 조회/변경은 owner(로그인 사용자)가 소유한
 * facilities.owner_id 범위로만 제한한다(cross-owner IDOR 방지, facility 도메인과 동일 원칙).
 * 상태 전이 순서(신규→검수확정→조치대기→조치중→조치완료) 강제는 Defect#changeStatus 가 담당한다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefectService {

    private final DefectRepository defectRepository;

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

    @Transactional
    public DefectResponse updateStatus(Long ownerId, Long defectId, DefectStatus status) {
        Defect defect = defectRepository.findByIdAndOwnerId(defectId, ownerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        defect.changeStatus(status);
        return DefectResponse.from(defect);
    }
}
