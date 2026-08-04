package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.DefectRevision;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DefectRevisionRepository extends JpaRepository<DefectRevision, Long> {

    // 하자 상세 화면 활동 기록 타임라인(HAJA-314) — owner 스코프 검증은 DefectService에서 defectId
    // 조회로 먼저 수행하므로, 여기서는 defectId 단순 조회만 담당한다(findByIdAndCompanyId 재사용 패턴).
    Page<DefectRevision> findByDefectIdOrderByCreatedAtDesc(Long defectId, Pageable pageable);

    // 이미지 단위 그룹 활동 기록 조회(#1556) — DefectService#getRevisions가 resolveActionGroup으로
    // 구한 그룹 전체 defectId를 한 번에 조회한다. mediaId가 없는 하자는 그룹 크기 1이라
    // findByDefectIdOrderByCreatedAtDesc와 동일하게 동작한다.
    Page<DefectRevision> findByDefectIdInOrderByCreatedAtDesc(Collection<Long> defectIds, Pageable pageable);

    // 오탐 삭제 목록(#1399)의 사유·삭제자·일시 — 하자 여러 건의 삭제 이력을 한 번에 읽어 N+1을 피한다.
    // 복구 후 재삭제가 가능하므로 한 하자에 여러 건이 나올 수 있다 → 최신순으로 받아 첫 건만 쓴다.
    List<DefectRevision> findByDefectIdInAndFieldChangedAndNewValueOrderByCreatedAtDesc(
            Collection<Long> defectIds, String fieldChanged, String newValue);
}
