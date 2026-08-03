package com.hajacheck.core.defect.service;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.core.defect.dto.DefectCreateRequest;
import com.hajacheck.core.defect.dto.DefectDetailItem;
import com.hajacheck.core.defect.dto.DefectRevisionRequest;
import com.hajacheck.core.defect.dto.DeletedDefectItem;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectRevision;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectRevisionRepository;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
    private final CompanyScopeGuard companyScopeGuard;
    private final MediaRepository mediaRepository;
    private final UserRepository userRepository;

    /**
     * 점검 회차별 하자 목록 조회(검수·뷰어 공용).
     *
     * @param userId          요청 사용자 id
     * @param companyId       요청 사용자의 회사 id
     * @param inspectionId    점검 회차 id
     * @return 하자 목록(deleted=false만, id 오름차순)
     * @throws BusinessException 점검 회차 미존재 또는 타인 소유 (404 INSPECTION_NOT_FOUND)
     */
    public List<DefectDetailItem> getDefectsByInspection(
            Long userId, Long companyId, Long inspectionId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        // 소유권 검증
        inspectionService.getInspection(userId, companyId, inspectionId);

        // 하자 조회(deleted=false만)
        List<Defect> defects = defectRepository.findByInspectionIdAndNotDeleted(inspectionId);
        return defects.stream()
                .map(DefectDetailItem::from)
                .toList();
    }

    /**
     * 오탐 삭제된 하자 목록(#1399) — 결과 뷰어의 "삭제된 하자" 접이식 목록·되살리기용.
     *
     * <p>검수자가 오탐으로 지운 것만 반환한다({@link DefectRepository#findDeletedByReviewer}) —
     * 재분석 때 통째로 밀린 구버전까지 되살리기 후보로 보이면 유령 하자가 부활한다.
     *
     * @return 삭제 하자 + 사유·일시·삭제자(id 오름차순, 없으면 빈 목록)
     * @throws BusinessException 점검 회차 미존재 또는 타인 소유 (404)
     */
    public List<DeletedDefectItem> getDeletedDefects(Long userId, Long companyId, Long inspectionId) {
        companyScopeGuard.requireEffectiveMembership(userId, companyId);
        // 소유권 검증 — 미존재/타인 소유는 여기서 404
        inspectionService.getInspection(userId, companyId, inspectionId);

        List<Defect> deleted = defectRepository.findDeletedByReviewer(inspectionId);
        if (deleted.isEmpty()) {
            return List.of();
        }

        // 삭제 이력을 한 번에 읽어 N+1을 피한다. 복구 후 재삭제가 가능해 하자당 여러 건이 나올 수
        // 있으므로 최신순 목록에서 하자별 첫 건(=가장 최근 삭제)만 남긴다.
        List<Long> defectIds = deleted.stream().map(Defect::getId).toList();
        Map<Long, DefectRevision> latestDeleteByDefectId = new HashMap<>();
        for (DefectRevision revision : defectRevisionRepository
                .findByDefectIdInAndFieldChangedAndNewValueOrderByCreatedAtDesc(
                        defectIds, DefectRevision.FIELD_IS_DELETED, "true")) {
            latestDeleteByDefectId.putIfAbsent(revision.getDefectId(), revision);
        }

        // 삭제자 이름도 한 번에 조회(DefectService의 userRepository.findById(...).map(User::getName) 패턴을
        // 목록용으로 배치화). 탈퇴 등으로 못 찾으면 null로 남긴다.
        Map<Long, String> userNameById = userRepository.findAllById(
                        latestDeleteByDefectId.values().stream()
                                .map(DefectRevision::getRevisedBy)
                                .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(User::getId, User::getName));

        return deleted.stream()
                .map(defect -> {
                    DefectRevision revision = latestDeleteByDefectId.get(defect.getId());
                    return new DeletedDefectItem(
                            DefectDetailItem.from(defect),
                            revision == null ? null : revision.getReason(),
                            revision == null ? null : revision.getCreatedAt(),
                            revision == null ? null : userNameById.get(revision.getRevisedBy()));
                })
                .toList();
    }

    /**
     * 수동 하자 생성 — 점검자가 분석 결과 누락된 하자를 직접 등록(FR-4, HAJA-344).
     *
     * @param requesterUserId 요청 사용자 id
     * @param companyId       요청 사용자의 회사 id
     * @param inspectionId    점검 회차 id
     * @param request         요청 (type 필수, bbox 선택적 모두-또는-무, grade 선택적)
     * @return 생성된 하자
     * @throws BusinessException 점검 회차 미존재/타인 소유 (404), bbox 불완전/type 누락 (400),
     *                           분석 진행 중(ANALYZING) (409), 분석 실패(FAILED) — 재분석 시 하자
     *                           삭제될 수 있음 (409, PR머신 리뷰 3차 P1)
     */
    @Transactional
    public DefectDetailItem createManualDefect(
            Long requesterUserId, Long companyId, Long inspectionId, DefectCreateRequest request) {
        companyScopeGuard.requireEffectiveMembership(requesterUserId, companyId);
        // 소유권 검증
        Inspection inspection = inspectionService.getOwnedInspectionEntity(requesterUserId, companyId, inspectionId);

        // 코드 리뷰 P1(머신 검수 2차) — ANALYZING 중 수동 하자가 끼면, 워커가 첫 탐지 성공 시 호출하는
        // softDeleteAllForInspectionThenSave(DefectWriter)가 방금 추가된 이 하자까지 통째로 비삭제
        // 대상에 포함시켜 지운다. InspectionAnalysisService.hasExistingDefects 사전 체크·원자적 UPDATE의
        // NOT EXISTS는 분석 "시작 시점"만 지키므로, 시작 이후(ANALYZING 동안) 끼어드는 이 경로는 그
        // 자체로 막아야 한다.
        if (inspection.getStatus() == InspectionStatus.ANALYZING) {
            throw new BusinessException(ErrorCode.ANALYSIS_ALREADY_RUNNING);
        }
        // PR머신 리뷰 3차 P1 — FAILED도 같은 이유로 막는다. FAILED 재분석의 원자적 선점(
        // InspectionRepository#startAnalyzingIfNotRunning)은 "비삭제 하자 없음" 요건을 FAILED에 한해
        // 건너뛰는데, 그 전제가 "FAILED에 남은 하자는 전부 이번에 실패한 실행이 만든 AI 결과뿐"이다.
        // 이 가드가 없으면 그 전제가 거짓이 될 수 있다 — 점검자가 FAILED 회차를 보고 누락 하자를
        // 수동 등록한 뒤 재분석을 돌리면, 워커의 softDeleteAllForInspectionThenSave(비삭제 하자 전체
        // 대상)가 그 사람 하자까지 무보상으로 지운다. fail-closed 가드(InspectionAnalysisService
        // #hasExistingDefects)가 원래 막으려던 바로 그 유실이 FAILED 바이패스로 다시 열리는 것을
        // 여기서 원천 차단한다.
        if (inspection.getStatus() == InspectionStatus.FAILED) {
            throw new BusinessException(ErrorCode.DEFECT_CREATE_BLOCKED_ANALYSIS_FAILED);
        }

        // bbox 검증: 4개 모두 지정되거나 모두 미지정
        boolean hasBboxX = request.getBboxX() != null;
        boolean hasBboxY = request.getBboxY() != null;
        boolean hasBboxW = request.getBboxW() != null;
        boolean hasBboxH = request.getBboxH() != null;

        if ((hasBboxX || hasBboxY || hasBboxW || hasBboxH) &&
                !(hasBboxX && hasBboxY && hasBboxW && hasBboxH)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // bbox 4개가 모두 지정되면 mediaId도 필수 (정규화 좌표가 어느 이미지에 속하는지 식별 필수)
        if (hasBboxX && hasBboxY && hasBboxW && hasBboxH && request.getMediaId() == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        // mediaId 검증(지정 시에만) — 해당 점검 회차 소속 사진인지 확인(DefectService.actionMediaId와 동일 패턴)
        if (request.getMediaId() != null) {
            mediaRepository.findByIdAndInspectionId(request.getMediaId(), inspectionId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.MEDIA_NOT_FOUND));
        }

        // 하자 생성
        // confidence=1.0은 수동 생성 sentinel(스키마 마이그레이션 없이 AI/사람 구분 회피) — AI/사람 구분 컬럼 추가 검토는 #644
        Defect defect = Defect.builder()
                .inspectionId(inspectionId)
                .mediaId(request.getMediaId())
                .type(request.getType())
                .bboxX(request.getBboxX())
                .bboxY(request.getBboxY())
                .bboxW(request.getBboxW())
                .bboxH(request.getBboxH())
                .confidence(1.0)
                .grade(request.getGrade())
                .build();

        Defect saved = defectRepository.save(defect);
        return DefectDetailItem.from(saved);
    }

    /**
     * 하자 검수 — 등급 조정 또는 오탐 삭제(soft delete).
     *
     * @param revisedByUserId 검수자 사용자 id
     * @param companyId       검수자의 회사 id
     * @param defectId        하자 id
     * @param request         요청 (grade 또는 isDeleted 정확히 하나 + reason)
     * @return 검수 반영된 하자
     * @throws BusinessException 하자 미존재/타인 소유 (404), 입력 오류 (400), RESOLVED 상태 (409)
     */
    @Transactional
    public DefectDetailItem reviewDefect(
            Long revisedByUserId, Long companyId, Long defectId, DefectRevisionRequest request) {
        companyScopeGuard.requireEffectiveMembership(revisedByUserId, companyId);
        // 하자 로드
        Defect defect = defectRepository.findById(defectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DEFECT_NOT_FOUND));

        // 소유권 검증 — 점검 회차를 통해 확인, 미존재/타인 소유면 404 DEFECT_NOT_FOUND로 통일
        try {
            inspectionService.getInspection(revisedByUserId, companyId, defect.getInspectionId());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.INSPECTION_NOT_FOUND || e.getErrorCode() == ErrorCode.FACILITY_NOT_FOUND) {
                throw new BusinessException(ErrorCode.DEFECT_NOT_FOUND);
            }
            throw e;
        }

        // grade와 isDeleted 정확히 하나만 지정 확인.
        // isDeleted는 true(오탐 삭제)/false(복구) 둘 다 유효한 값이므로(#1399) null 여부로 판정한다 —
        // 예전엔 "false = 지정 안 함"으로 취급해 복구 요청이 '둘 다 미지정' 400에 걸렸다.
        boolean hasGrade = request.getGrade() != null;
        boolean hasDeleted = request.getDeleted() != null;
        if (hasGrade == hasDeleted) {
            // 둘 다 지정 또는 둘 다 미지정
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
        } else if (request.getDeleted()) {
            // 오탐 삭제
            oldValue = String.valueOf(defect.isDeleted());
            // 이미 삭제된 경우 중복 삭제 요청은 INVALID_STATE_TRANSITION
            if (defect.isDeleted()) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
            }
            newValue = "true";
            fieldChanged = DefectRevision.FIELD_IS_DELETED;

            defect.softDelete();
        } else {
            // 오탐 삭제 복구(#1399) — 삭제도 이력도 그대로 살아 있으므로 플래그만 되돌리면 된다.
            oldValue = String.valueOf(defect.isDeleted());
            // 삭제되지 않은 하자의 복구 요청은 무의미하다(삭제의 중복 요청과 같은 취급).
            if (!defect.isDeleted()) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
            }
            // 검수자가 지운 것만 되살린다 — 재분석 때 통째로 밀린 구버전(is_deleted 이력이 없다)을
            // 되살리면 이미 대체된 유령 하자가 화면·통계에 부활한다. 목록 조회와 같은 기준이지만,
            // 목록에 안 뜨는 id로 직접 요청이 들어올 수 있으므로 여기서도 독립적으로 막는다.
            boolean deletedByReviewer = defectRevisionRepository
                    .findByDefectIdInAndFieldChangedAndNewValueOrderByCreatedAtDesc(
                            List.of(defectId), DefectRevision.FIELD_IS_DELETED, "true")
                    .stream().findAny().isPresent();
            if (!deletedByReviewer) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
            }
            // 재분석으로 대체된 세대는 되살리지 않는다(#1401) — 검수자가 지운 이력이 남아 있어도
            // 그 하자는 이미 새 분석 결과로 교체됐다. 되살리면 구회차 하자가 일반 목록·건수·
            // 등급분포·보고서에 부활한다. 목록 쿼리와 같은 기준이지만 목록에 안 뜨는 id로 직접
            // 요청이 올 수 있어 여기서도 독립 검증한다.
            boolean supersededByReanalysis = !defectRevisionRepository
                    .findByDefectIdInAndFieldChangedAndNewValueOrderByCreatedAtDesc(
                            List.of(defectId), DefectRevision.FIELD_REANALYSIS_SUPERSEDED, "true")
                    .isEmpty();
            if (supersededByReanalysis) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION);
            }
            newValue = "false";
            fieldChanged = DefectRevision.FIELD_IS_DELETED;

            defect.restore();
        }

        // 변경 저장
        defectRepository.save(defect);

        // 이력 기록
        DefectRevision revision = DefectRevision.record(
                defectId,
                revisedByUserId,
                fieldChanged,
                oldValue,
                newValue,
                request.getReason());
        defectRevisionRepository.save(revision);

        return DefectDetailItem.from(defect);
    }
}
