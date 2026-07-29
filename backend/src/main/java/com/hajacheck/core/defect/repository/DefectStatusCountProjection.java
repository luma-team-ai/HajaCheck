package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.DefectStatus;

public interface DefectStatusCountProjection {
    DefectStatus getStatus();

    Long getCnt();
}
