package com.hajacheck.menu.repository;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.menu.entity.MenuRoleAccess;
import com.hajacheck.menu.entity.MenuRoleAccessId;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuRoleAccessRepository extends JpaRepository<MenuRoleAccess, MenuRoleAccessId> {

    List<MenuRoleAccess> findByRole(Role role);
}
