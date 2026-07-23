package com.hajacheck.core.facility.repository;

import com.hajacheck.core.facility.entity.FacilityPhoto;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityPhotoRepository extends JpaRepository<FacilityPhoto, Long> {

    List<FacilityPhoto> findByFacilityIdOrderBySortOrderAsc(Long facilityId);

    // list() 화면에서 여러 시설물의 사진을 한 번에 조회해 N+1 을 피하기 위한 벌크 조회 — 정렬 후
    // 서비스에서 facilityId 별로 그룹핑한다.
    List<FacilityPhoto> findByFacilityIdInOrderByFacilityIdAscSortOrderAsc(List<Long> facilityIds);

    // PUT(전체 교체) 시 기존 사진을 모두 지우고 요청의 photoUrls 로 다시 채운다.
    void deleteByFacilityId(Long facilityId);
}
