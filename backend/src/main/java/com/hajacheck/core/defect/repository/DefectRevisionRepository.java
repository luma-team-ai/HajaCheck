package com.hajacheck.core.defect.repository;

import com.hajacheck.core.defect.entity.DefectRevision;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DefectRevisionRepository extends JpaRepository<DefectRevision, Long> {
}
