package com.hajacheck.core.inspection.repository;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface InspectionRepositoryCustom {
    Page<Inspection> findPageByCompanyIdAndFilters(
            Long companyId, Long facilityId, InspectionStatus status, Pageable pageable);
}
