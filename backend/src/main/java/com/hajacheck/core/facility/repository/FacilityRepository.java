package com.hajacheck.core.facility.repository;

import com.hajacheck.core.facility.entity.Facility;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityRepository extends JpaRepository<Facility, Long> {

    List<Facility> findByOwnerId(Long ownerId);

    Optional<Facility> findByIdAndOwnerId(Long id, Long ownerId);
}
