package com.hajacheck.core.facility.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.User;
import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.facility.entity.FacilityPhoto;
import com.hajacheck.support.PostgresTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

// 실 PG DDL(facility_photos) 대조를 위해 임베디드 교체를 끄고 Testcontainers PostgreSQL 사용(#628/HAJA-347).
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class FacilityPhotoRepositoryTest extends PostgresTestSupport {

    @Autowired
    private FacilityPhotoRepository facilityPhotoRepository;

    @Autowired
    private TestEntityManager em;

    private int ownerSeq = 0;

    // 시설물별로 owner User 를 새로 시드한다 — 한 테스트에서 여러 시설물을 만들 때
    // users.email UNIQUE 제약과 충돌하지 않도록 매번 다른 이메일을 쓴다.
    private Long seedFacility() {
        User owner = User.createCompanyOwner(
                "photo-owner-" + (ownerSeq++) + "@haja.com", "소유자", "$2a$10$testtesttesttesttesttes");
        em.persist(owner);
        Facility facility = Facility.builder()
                .ownerId(owner.getId())
                .name("사진테스트빌딩")
                .type("BUILDING")
                .build();
        em.persist(facility);
        em.flush();
        return facility.getId();
    }

    private FacilityPhoto newPhoto(Long facilityId, String url, int sortOrder) {
        return FacilityPhoto.builder()
                .facilityId(facilityId)
                .photoUrl(url)
                .sortOrder(sortOrder)
                .build();
    }

    @Test
    void save_저장후_createdAt과id채워짐() {
        Long facilityId = seedFacility();

        FacilityPhoto saved = facilityPhotoRepository.save(newPhoto(facilityId, "https://files.example/1.jpg", 0));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getPhotoUrl()).isEqualTo("https://files.example/1.jpg");
    }

    @Test
    void findByFacilityIdOrderBySortOrderAsc_순서대로반환() {
        Long facilityId = seedFacility();
        facilityPhotoRepository.save(newPhoto(facilityId, "https://files.example/2.jpg", 1));
        facilityPhotoRepository.save(newPhoto(facilityId, "https://files.example/1.jpg", 0));

        List<FacilityPhoto> found = facilityPhotoRepository.findByFacilityIdOrderBySortOrderAsc(facilityId);

        assertThat(found).extracting(FacilityPhoto::getPhotoUrl)
                .containsExactly("https://files.example/1.jpg", "https://files.example/2.jpg");
    }

    @Test
    void findByFacilityIdInOrderByFacilityIdAscSortOrderAsc_여러시설물벌크조회() {
        Long facilityAId = seedFacility();
        Long facilityBId = seedFacility();
        facilityPhotoRepository.save(newPhoto(facilityAId, "https://files.example/a.jpg", 0));
        facilityPhotoRepository.save(newPhoto(facilityBId, "https://files.example/b.jpg", 0));

        List<FacilityPhoto> found = facilityPhotoRepository
                .findByFacilityIdInOrderByFacilityIdAscSortOrderAsc(List.of(facilityAId, facilityBId));

        assertThat(found).extracting(FacilityPhoto::getPhotoUrl)
                .containsExactly("https://files.example/a.jpg", "https://files.example/b.jpg");
    }

    @Test
    void deleteByFacilityId_해당시설물사진전부삭제() {
        Long facilityId = seedFacility();
        facilityPhotoRepository.save(newPhoto(facilityId, "https://files.example/1.jpg", 0));
        facilityPhotoRepository.save(newPhoto(facilityId, "https://files.example/2.jpg", 1));

        facilityPhotoRepository.deleteByFacilityId(facilityId);

        assertThat(facilityPhotoRepository.findByFacilityIdOrderBySortOrderAsc(facilityId)).isEmpty();
    }

    @Test
    void 시설물삭제시_사진도CASCADE로함께삭제된다() {
        Long facilityId = seedFacility();
        facilityPhotoRepository.save(newPhoto(facilityId, "https://files.example/1.jpg", 0));
        em.flush();
        em.clear();

        Facility facility = em.find(Facility.class, facilityId);
        em.remove(facility);
        em.flush();
        em.clear();

        assertThat(facilityPhotoRepository.findByFacilityIdOrderBySortOrderAsc(facilityId)).isEmpty();
    }

    @Test
    void uniqueConstraint_같은시설물_같은sortOrder_저장실패() {
        Long facilityId = seedFacility();
        facilityPhotoRepository.save(newPhoto(facilityId, "https://files.example/1.jpg", 0));
        em.flush();

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            facilityPhotoRepository.save(newPhoto(facilityId, "https://files.example/dup.jpg", 0));
            em.flush();
        });
    }
}
