package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.DefectActionLog;
import com.hajacheck.core.defect.entity.DefectStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DefectActionLogRepository extends JpaRepository<DefectActionLog, Long> {

    // 조회 스코프 검증(findByIdAndCompanyId)은 DefectService에서 defectId 조회로 먼저 수행하므로
    // 여기서는 defectId+phase 단순 조회만 담당한다(DefectRevisionRepository와 동일 패턴).
    List<DefectActionLog> findByDefectIdAndPhaseOrderByCreatedAtDesc(Long defectId, DefectStatus phase);
}
