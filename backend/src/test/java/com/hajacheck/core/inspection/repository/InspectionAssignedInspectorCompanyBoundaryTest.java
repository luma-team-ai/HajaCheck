package com.hajacheck.core.inspection.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.support.PostgresTestSupport;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.ActiveProfiles;

/**
 * trg_inspections_check_assigned_inspector_company(HAJA-25 P2 DB 레벨 방어)가 실제 PostgreSQL에서
 * 회사 경계를 강제하는지 런타임 INSERT로 검증한다. verify.sql은 트리거의 구조적 존재만 확인하므로,
 * 이 테스트는 실제 거부/허용 동작을 별도로 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class InspectionAssignedInspectorCompanyBoundaryTest extends PostgresTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private InspectionRepository inspectionRepository;

    @Autowired
    private TestEntityManager em;

    private Long seedFacility(Long ownerId, String name) {
        Facility facility = Facility.builder().ownerId(ownerId).name(name).type("BUILDING").build();
        em.persist(facility);
        em.flush();
        return facility.getId();
    }

    private User seedCompanyUser(String email, String companyName, String businessRegistrationNumber) {
        User owner = userRepository.saveAndFlush(
                User.createCompanyOwner(email, "owner", "<password-hash-placeholder>"));
        Company company = companyRepository.saveAndFlush(Company.createPendingReview(
                owner.getId(), companyName, businessRegistrationNumber, "owner", "Seoul", null,
                "https://files.example/registration.pdf", "{\"source\":\"TEST\"}"));
        owner.assignToCompany(company.getId());
        return userRepository.saveAndFlush(owner);
    }

    private Inspection newInspection(Long facilityId, Long createdBy, Long assignedInspectorId) {
        return Inspection.builder()
                .facilityId(facilityId)
                .createdBy(createdBy)
                .assignedInspectorId(assignedInspectorId)
                .roundNo(1)
                .inspectionDate(LocalDate.of(2026, 7, 17))
                .status(InspectionStatus.CREATED)
                .build();
    }

    @Test
    void crossCompanyAssignment_rejectedByTrigger() {
        User ownerA = seedCompanyUser("boundary-owner-a@haja.test", "boundary company A", "HA25-BOUNDARY-A");
        User ownerB = seedCompanyUser("boundary-owner-b@haja.test", "boundary company B", "HA25-BOUNDARY-B");
        Long facilityId = seedFacility(ownerA.getId(), "경계테스트빌딩A");

        assertThatThrownBy(() -> inspectionRepository.saveAndFlush(
                newInspection(facilityId, ownerA.getId(), ownerB.getId())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("must belong to the same company");
    }

    @Test
    void sameCompanyDifferentUsersAssignment_allowed() {
        User owner = seedCompanyUser("boundary-owner-c@haja.test", "boundary company C", "HA25-BOUNDARY-C");
        User member = userRepository.saveAndFlush(
                User.createCompanyOwner("boundary-member-c@haja.test", "member", "<password-hash-placeholder>"));
        member.assignToCompany(owner.getCompanyId());
        member = userRepository.saveAndFlush(member);
        Long facilityId = seedFacility(owner.getId(), "동일회사빌딩");

        Inspection saved = inspectionRepository.saveAndFlush(
                newInspection(facilityId, owner.getId(), member.getId()));

        assertThat(saved.getId()).isNotNull();
    }

    @Test
    void bothCompanyLessSelfAssignment_allowed() {
        User individual = userRepository.saveAndFlush(
                User.createCompanyOwner("boundary-individual@haja.test", "individual", "<password-hash-placeholder>"));
        Long facilityId = seedFacility(individual.getId(), "개인빌딩");

        Inspection saved = inspectionRepository.saveAndFlush(
                newInspection(facilityId, individual.getId(), individual.getId()));

        assertThat(saved.getId()).isNotNull();
    }
}
