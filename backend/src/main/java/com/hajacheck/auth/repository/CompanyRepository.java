package com.hajacheck.auth.repository;

import com.hajacheck.auth.entity.Company;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    boolean existsByBusinessRegistrationNumber(String businessRegistrationNumber);

    Optional<Company> findByBusinessRegistrationNumber(String businessRegistrationNumber);

    Optional<Company> findByBusinessRegistrationNumberAndRepresentativeName(
            String businessRegistrationNumber, String representativeName);

    Optional<Company> findByBusinessRegistrationNumberAndName(
            String businessRegistrationNumber, String name);
}
