package com.hajacheck.auth.repository;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    // 플랫폼 관리자 콘솔 — 사용자 등록 모달의 기업명 selectbox(#576). 심사 승인된 기업만 배정
    // 가능하게 한다 — 승인 대기/반려 기업에 사용자를 배선하면 그 회사 데이터 자체가 아직 유효하지 않다.
    List<Company> findByStatusOrderByNameAsc(CompanyStatus status);

    // 플랫폼 관리자 콘솔 — 회사별 마지막 ADMIN 보호(PR머신 2차 검토 P2). count-후-쓰기 사이 TOCTOU를
    // 막기 위해 대상 회사 행을 먼저 잠가 같은 회사를 대상으로 한 동시 강등/정지 요청을 직렬화한다
    // (FacilityRepository#findByIdForUpdate와 동일 패턴 — 값 자체는 쓰지 않고 잠금 획득 용도).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Company c where c.id = :id")
    Optional<Company> findByIdForUpdate(@Param("id") Long id);

    boolean existsByBusinessRegistrationNumber(String businessRegistrationNumber);

    Optional<Company> findByBusinessRegistrationNumber(String businessRegistrationNumber);

    Optional<Company> findByBusinessRegistrationNumberAndRepresentativeName(
            String businessRegistrationNumber, String representativeName);

    Optional<Company> findByBusinessRegistrationNumberAndName(
            String businessRegistrationNumber, String name);
}
