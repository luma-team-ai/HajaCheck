package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.DefectType;

public interface InspectionTypeCountProjection {
    DefectType getType();

    Long getCnt();
}
