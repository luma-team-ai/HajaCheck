package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DefectRepositoryCustom {
    Page<Defect> findPageByOwnerIdAndFilters(
            Long ownerId, DefectType type, DefectGrade grade, DefectStatus status, Pageable pageable);
}
