package com.hajacheck.core.facility.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.core.facility.entity.Facility;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class FacilityRepositoryTest {

    @Autowired
    private FacilityRepository facilityRepository;

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
        Facility saved = facilityRepository.save(newFacility(1L, "테스트빌딩"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getName()).isEqualTo("테스트빌딩");
    }

    @Test
    void findByOwnerId_소유자별목록_본인시설만반환() {
        facilityRepository.save(newFacility(1L, "1번소유자시설A"));
        facilityRepository.save(newFacility(1L, "1번소유자시설B"));
        facilityRepository.save(newFacility(2L, "2번소유자시설"));

        List<Facility> found = facilityRepository.findByOwnerId(1L);

        assertThat(found).hasSize(2)
                .extracting(Facility::getName)
                .containsExactlyInAnyOrder("1번소유자시설A", "1번소유자시설B");
    }

    @Test
    void findByIdAndOwnerId_본인소유_반환() {
        Facility saved = facilityRepository.save(newFacility(1L, "테스트빌딩"));

        Optional<Facility> found = facilityRepository.findByIdAndOwnerId(saved.getId(), 1L);

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("테스트빌딩");
    }

    @Test
    void findByIdAndOwnerId_타인소유_빈값() {
        Facility saved = facilityRepository.save(newFacility(1L, "테스트빌딩"));

        Optional<Facility> found = facilityRepository.findByIdAndOwnerId(saved.getId(), 2L);

        assertThat(found).isEmpty();
    }

    @Test
    void findByIdAndOwnerId_없는id_빈값() {
        Optional<Facility> found = facilityRepository.findByIdAndOwnerId(999L, 1L);

        assertThat(found).isEmpty();
    }
}
