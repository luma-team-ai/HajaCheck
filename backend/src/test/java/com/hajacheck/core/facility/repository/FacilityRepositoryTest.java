package com.hajacheck.core.facility.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.User;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.support.PostgresTestSupport;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

// 실 PG DDL(facilities) 대조를 위해 임베디드 교체를 끄고 Testcontainers PostgreSQL 사용 (리뷰 P2).
// facilities.owner_id 는 users(id) FK 이므로, 시설물 저장 전 owner User 를 먼저 시드한다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class FacilityRepositoryTest extends PostgresTestSupport {

    @Autowired
    private FacilityRepository facilityRepository;

    @Autowired
    private TestEntityManager em;

    // FK(owner_id → users) 충족용 owner User 시드 후 생성된 id 반환.
    private Long seedOwner(String email) {
        User owner = User.createCompanyOwner(email, "소유자", "$2a$10$testtesttesttesttesttes");
        em.persist(owner);
        em.flush();
        return owner.getId();
    }

    private Facility newFacility(Long ownerId, String name) {
        return Facility.builder()
                .ownerId(ownerId)
                .name(name)
                .type("BUILDING")
                .address("서울시 강남구")
                .build();
    }

    @Test
    void save_저장후_createdAt과id채워짐() {
        Long ownerId = seedOwner("owner-a@haja.com");

        Facility saved = facilityRepository.save(newFacility(ownerId, "테스트빌딩"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getName()).isEqualTo("테스트빌딩");
    }

    @Test
    void findByOwnerId_소유자별목록_본인시설만반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long otherOwnerId = seedOwner("owner-b@haja.com");
        facilityRepository.save(newFacility(ownerId, "1번소유자시설A"));
        facilityRepository.save(newFacility(ownerId, "1번소유자시설B"));
        facilityRepository.save(newFacility(otherOwnerId, "2번소유자시설"));

        List<Facility> found = facilityRepository.findByOwnerId(ownerId);

        assertThat(found).hasSize(2)
                .extracting(Facility::getName)
                .containsExactlyInAnyOrder("1번소유자시설A", "1번소유자시설B");
    }

    @Test
    void findByIdAndOwnerId_본인소유_반환() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Facility saved = facilityRepository.save(newFacility(ownerId, "테스트빌딩"));

        Optional<Facility> found = facilityRepository.findByIdAndOwnerId(saved.getId(), ownerId);

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("테스트빌딩");
    }

    @Test
    void findByIdAndOwnerId_타인소유_빈값() {
        Long ownerId = seedOwner("owner-a@haja.com");
        Long otherOwnerId = seedOwner("owner-b@haja.com");
        Facility saved = facilityRepository.save(newFacility(ownerId, "테스트빌딩"));

        Optional<Facility> found = facilityRepository.findByIdAndOwnerId(saved.getId(), otherOwnerId);

        assertThat(found).isEmpty();
    }

    @Test
    void findByIdAndOwnerId_없는id_빈값() {
        Long ownerId = seedOwner("owner-a@haja.com");

        Optional<Facility> found = facilityRepository.findByIdAndOwnerId(999L, ownerId);

        assertThat(found).isEmpty();
    }
}
