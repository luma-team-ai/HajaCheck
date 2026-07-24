package com.hajacheck.core.defect.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.AuthService;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.dto.DefectActionResultRequest;
import com.hajacheck.core.defect.dto.DefectResponse;
import com.hajacheck.core.defect.dto.DefectRevisionResponse;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectRevision;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectRevisionRepository;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하자 목록·상세 조회 및 상태 전이(HAJA-26) — 모든 조회/변경은 로그인 사용자의 회사가 소유한
 * facilities.company_id 범위로만 제한한다(cross-company IDOR 방지, facility 도메인과 동일 원칙).
 * 상태 전이 규칙(정방향 순서 강제, 역행/건너뛰기는 사유 필수)은 Defect#changeStatus 가 담당하고,
 * 전이가 성공하면 defect_revisions에 append-only 이력을 남긴다(PRD FR-4).
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefectService {

    private final DefectRepository defectRepository;
    private final DefectRevisionRepository defectRevisionRepository;
    private final CompanyScopeGuard companyScopeGuard;
    private final AuthService authService;
    private final MediaRepository mediaRepository;
    private final UserRepository userRepository;

    public PageResponse<DefectResponse> list(
            Long userId, Long companyId, DefectType type, DefectGrade grade,
            DefectStatus status, Pageable pageable) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Page<Defect> page = defectRepository.findPageByCompanyIdAndFilters(companyId, type, grade, status, pageable);
        return PageResponse.from(page.map(DefectResponse::from));
    }

    /**
     * 조치 결과 등록(HAJA-393/#725, "조치 완료 등록" 버튼) — 담당자는 #690과 동일 자격 조건
     * (authService.validateAssignableInspector, 활성·INSPECTOR/ADMIN·유효 승인 멤버십)으로 검증하고,
     * 조치 후 사진은 같은 점검 소속 media인지 확인해(findByIdAndInspectionId) 둘 다 cross-company
     * IDOR을 차단한다. 상태전이(RESOLVED)는 Defect#registerActionResult 가 changeStatus() 규칙을
     * 재사용해 처리하므로, 여기서는 기존 updateStatus()와 동일하게 defect_revisions에 이력만 남긴다.
     */
    @Transactional
    public DefectResponse registerActionResult(
            Long userId, Long companyId, Long defectId, DefectActionResultRequest request) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Defect defect = defectRepository.findByIdAndCompanyId(defectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        authService.validateAssignableInspector(userId, request.actionAssigneeId());
        mediaRepository.findByIdAndInspectionId(request.actionMediaId(), defect.getInspectionId())
                .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND));

        DefectStatus previousStatus = defect.getStatus();
        defect.registerActionResult(
                request.actionMediaId(), request.actionContent(), request.actionDate(), request.actionAssigneeId());
        defectRevisionRepository.save(DefectRevision.record(
                defect.getId(), userId, "status", previousStatus.name(), DefectStatus.RESOLVED.name(), null));

        String actionAssigneeName = userRepository.findById(request.actionAssigneeId())
                .map(User::getName)
                .orElse(null);
        return DefectResponse.from(defect, actionAssigneeName);
    }

    public DefectResponse get(Long userId, Long companyId, Long defectId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        Defect defect = defectRepository.findByIdAndCompanyId(defectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        // 조치 결과 등록(HAJA-393/#725) 이후 하자 상세 모달을 다시 열었을 때도 담당자 이름이
        // 채워지도록, actionAssigneeId가 있으면 조회한다(list()는 요약형이라 이 조회를 생략).
        String actionAssigneeName = defect.getActionAssigneeId() == null ? null
                : userRepository.findById(defect.getActionAssigneeId()).map(User::getName).orElse(null);
        return DefectResponse.from(defect, actionAssigneeName);
    }

    /**
     * 하자 활동 기록 타임라인 조회(HAJA-314) — findByIdAndCompanyId로 회사 범위를 먼저 검증해
     * cross-company IDOR을 차단한 뒤에만 defect_revisions를 조회한다(get()과 동일 원칙).
     */
    public PageResponse<DefectRevisionResponse> getRevisions(
            Long userId, Long companyId, Long defectId, Pageable pageable) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        defectRepository.findByIdAndCompanyId(defectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        Page<DefectRevision> page = defectRevisionRepository.findByDefectIdOrderByCreatedAtDesc(defectId, pageable);
        return PageResponse.from(page.map(DefectRevisionResponse::from));
    }

    @Transactional
    public DefectResponse updateStatus(
            Long revisedByUserId, Long companyId, Long defectId, DefectStatus status, String reason) {
        companyScopeGuard.requireEffectiveMembership(revisedByUserId, companyId);
        Defect defect = defectRepository.findByIdAndCompanyId(defectId, companyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));
        DefectStatus previousStatus = defect.getStatus();
        defect.changeStatus(status, reason);
        defectRevisionRepository.save(DefectRevision.record(
                defect.getId(), revisedByUserId, "status", previousStatus.name(), status.name(), reason));
        return DefectResponse.from(defect);
    }

}
