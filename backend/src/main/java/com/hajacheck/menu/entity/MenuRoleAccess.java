package com.hajacheck.menu.entity;

import com.hajacheck.auth.entity.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 역할별 메뉴 노출 매핑 — DDL menu_role_access(#385) 대응. 매핑 행 존재 = 해당 role에 노출
 * (can_view 컬럼 없음). GROUP 메뉴는 DB 트리거(trg_menu_role_access_reject_group)가 직접 매핑을
 * 거부한다 — 허용된 자식이 하나라도 있으면 MenuService가 부모 GROUP을 자동으로 포함시킨다.
 */
@Entity
@Getter
@Table(name = "menu_role_access")
@IdClass(MenuRoleAccessId.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuRoleAccess {

    @Id
    @Column(name = "menu_id")
    private Long menuId;

    @Id
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "role_type")
    private Role role;

    @Column(name = "created_by")
    private Long createdBy;
}
