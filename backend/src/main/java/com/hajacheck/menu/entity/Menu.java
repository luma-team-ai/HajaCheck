package com.hajacheck.menu.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 사이드바/관리자 메뉴 트리 노드 — DDL menus 테이블(#385) 대응.
 * 자기참조 트리이지만 parentId는 단순 FK 값 컬럼으로만 보유한다(트리 조립은 MenuService가
 * 전체 목록을 한 번에 읽어 메모리에서 구성 — 노드 수가 적어 재귀 쿼리/양방향 연관관계가 불필요).
 * 이 이슈(#1003) 범위는 조회 전용이라 created_by/updated_by/created_at/updated_at은 매핑하지 않는다
 * (관리자 편집 API는 후속 과제).
 */
@Entity
@Getter
@Table(name = "menus")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Menu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "menu_type", columnDefinition = "menu_node_type", nullable = false)
    private MenuType menuType;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(length = 500)
    private String path;

    @Column(name = "active_path_pattern", length = 500)
    private String activePathPattern;

    @Column(name = "icon_key", length = 100)
    private String iconKey;

    @Column(name = "icon_url", length = 500)
    private String iconUrl;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_visible", nullable = false)
    private boolean visible;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;

    @Column(name = "opens_new_tab", nullable = false)
    private boolean opensNewTab;
}
