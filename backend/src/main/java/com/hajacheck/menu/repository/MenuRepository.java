package com.hajacheck.menu.repository;

import com.hajacheck.menu.entity.Menu;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    List<Menu> findAllByOrderBySortOrderAscIdAsc();
}
