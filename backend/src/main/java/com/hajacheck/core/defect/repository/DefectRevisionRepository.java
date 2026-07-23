package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.DefectRevision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DefectRevisionRepository extends JpaRepository<DefectRevision, Long> {

    // 하자 상세 화면 활동 기록 타임라인(HAJA-314) — owner 스코프 검증은 DefectService에서 defectId
    // 조회로 먼저 수행하므로, 여기서는 defectId 단순 조회만 담당한다(findByIdAndCompanyId 재사용 패턴).
    Page<DefectRevision> findByDefectIdOrderByCreatedAtDesc(Long defectId, Pageable pageable);
}
