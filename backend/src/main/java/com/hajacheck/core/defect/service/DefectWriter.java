package com.hajacheck.core.defect.service;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectRevision;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.defect.repository.DefectRevisionRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Defect 원자 저장 전담(dev-05-04) — MediaWriter와 동일한 이유로 별도 빈 분리(self-invocation 회피).
 * InspectionAnalysisService가 이미지 1장의 FastAPI 호출(트랜잭션 밖 네트워크 IO)을 마친 뒤 이 빈을
 * 호출해 그 이미지의 탐지 결과만 짧은 트랜잭션으로 커밋한다 — 회차 전체를 한 트랜잭션으로 묶으면
 * 이미지 수십 장의 네트워크 호출 동안 DB 커넥션을 붙잡아두게 된다.
 */
@Component
@RequiredArgsConstructor
public class DefectWriter {

    private final DefectRepository defectRepository;
    private final DefectRevisionRepository defectRevisionRepository;

    @Transactional
    public List<Defect> saveAll(List<Defect> defects) {
        return defectRepository.saveAll(defects);
    }

    /**
     * 재분석 멱등화(dev-05-04, 코드 리뷰 P2 픽스) — 같은 회차를 다시 분석하기 전에 직전 AI 탐지
     * 결과를 소프트삭제한다. 이게 없으면 (동시 요청 경쟁이든, 완료된 회차에 재요청이든) 같은 이미지의
     * 하자가 append만 되어 배로 쌓이고 detectedDefectCount·등급 분포·리포트 수치가 부풀려진다.
     *
     * <p>점검자가 이미 검수(등급 조정 등)한 하자도 함께 삭제된다 — 재분석은 "이 회차를 처음부터
     * 다시 분석"하는 동작으로 취급한다(부분 재분석은 별도 스코프). 삭제는 소프트 삭제라 defects.
     * is_deleted=false 필터를 쓰는 모든 조회에서 자동 제외되고, defect_revisions 이력은 그대로 남는다.
     *
     * <p>재분석 시 워커가 첫 탐지에 성공한 이미지에서 호출한다 — 이번 이미지의 새 하자 저장과 한
     * 트랜잭션으로 묶어 원자화한다(코드 리뷰 P2 잔여 창 픽스). saveAll에서 예외(제약 위반 등)가 나면
     * 트랜잭션 전체가 롤백돼 방금 커밋됐어야 할 소프트삭제도 함께 취소된다 — 검수 완료된 기존
     * 하자가 "삭제는 됐는데 새 데이터는 하나도 안 들어간" 상태로 유실되는 걸 막는다. 반대로 저장부터
     * 하고 별도 트랜잭션으로 삭제하면, 방금 저장한 새 하자까지 삭제 대상(전체 비삭제 행)에 휩쓸려
     * 지워진다 — 그래서 순서를 바꾸거나 트랜잭션을 나누지 않는다. 두 번째 이미지부터는
     * {@link #saveAll}만 쓰면 된다(이미 정리 끝).
     */
    @Transactional
    public List<Defect> softDeleteAllForInspectionThenSave(
            Long requesterUserId, Long inspectionId, List<Defect> newDefects) {
        // 세대 교체 마커(#1401) — 비삭제분만 지우면 "검수자가 이미 오탐 삭제한 하자"는 그대로 남아
        // is_deleted 이력을 유지하고, 그러면 되살리기가 그 구회차 하자를 현재 세대의 오탐 삭제와
        // 구분하지 못해 유령 하자가 부활한다. 삭제 여부와 무관하게 **이 회차의 모든 기존 하자**에
        // 대체 표시를 남긴다(revised_by = 재분석을 실행한 사용자).
        List<Defect> all = defectRepository.findByInspectionId(inspectionId);
        defectRevisionRepository.saveAll(all.stream()
                .map(defect -> DefectRevision.record(
                        defect.getId(), requesterUserId,
                        DefectRevision.FIELD_REANALYSIS_SUPERSEDED, "false", "true", null))
                .toList());

        List<Defect> existing = all.stream().filter(defect -> !defect.isDeleted()).toList();
        existing.forEach(Defect::softDelete);
        defectRepository.saveAll(existing);
        return defectRepository.saveAll(newDefects);
    }
}
