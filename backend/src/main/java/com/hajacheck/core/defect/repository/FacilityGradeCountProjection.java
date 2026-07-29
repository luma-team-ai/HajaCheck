package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.DefectGrade;

public interface FacilityGradeCountProjection {
    Long getFacilityId();

    DefectGrade getGrade();

    Long getCnt();
}
