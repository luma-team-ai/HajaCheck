package com.hajacheck.menu.entity;

import com.hajacheck.auth.entity.Role;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** MenuRoleAccess 복합키(menu_id, role) — DDL menu_role_access PK 대응. */
@Getter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class MenuRoleAccessId implements Serializable {

    private Long menuId;
    private Role role;
}
