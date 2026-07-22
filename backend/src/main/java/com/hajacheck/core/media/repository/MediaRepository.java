package com.hajacheck.core.media.repository;

import com.hajacheck.core.media.entity.Media;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaRepository extends JpaRepository<Media, Long> {
}
