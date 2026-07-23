package com.hajacheck.auth.repository;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    // 플랫폼 관리자 콘솔 — 사용자 등록 모달의 기업명 selectbox(#576). 심사 승인된 기업만 배정
    // 가능하게 한다 — 승인 대기/반려 기업에 사용자를 배선하면 그 회사 데이터 자체가 아직 유효하지 않다.
    List<Company> findByStatusOrderByNameAsc(CompanyStatus status);

    boolean existsByBusinessRegistrationNumber(String businessRegistrationNumber);

    Optional<Company> findByBusinessRegistrationNumber(String businessRegistrationNumber);

    Optional<Company> findByBusinessRegistrationNumberAndRepresentativeName(
            String businessRegistrationNumber, String representativeName);

    Optional<Company> findByBusinessRegistrationNumberAndName(
            String businessRegistrationNumber, String name);
}
