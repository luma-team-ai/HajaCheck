import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import { NAV_ITEMS } from '../../constants';

export function Sidebar() {
  const [isCollapsed, setIsCollapsed] = useState(false);

  const handleToggleCollapse = () => {
    setIsCollapsed((prev) => !prev);
  };

  return (
    <aside className={`sidebar${isCollapsed ? ' sidebar--collapsed' : ''}`}>
      <div className="sidebar-header">
        {!isCollapsed && <span className="sidebar-logo">HajaCheck</span>}
        <button
          type="button"
          className="sidebar-collapse-btn"
          onClick={handleToggleCollapse}
          aria-label={isCollapsed ? '사이드바 펼치기' : '사이드바 접기'}
        >
          {isCollapsed ? '»' : '«'}
        </button>
      </div>

      <nav className="sidebar-nav">
        <ul className="sidebar-nav-list">
          {NAV_ITEMS.map((item) =>
            item.subItems ? (
              <li key={item.label} className="sidebar-nav-group">
                <span className="sidebar-nav-label sidebar-nav-label--expanded">{item.label}</span>
                <ul className="sidebar-subnav-list">
                  {item.subItems.map((sub) =>
                    sub.isActive ? (
                      <li key={sub.label}>
                        <NavLink
                          to={sub.path}
                          className={({ isActive }) =>
                            `sidebar-subnav-link${isActive ? ' sidebar-subnav-link--active' : ''}`
                          }
                        >
                          {sub.label}
                        </NavLink>
                      </li>
                    ) : (
                      <li key={sub.label}>
                        <span className="sidebar-subnav-link sidebar-subnav-link--disabled" aria-disabled="true">
                          {sub.label}
                        </span>
                      </li>
                    ),
                  )}
                </ul>
              </li>
            ) : (
              <li key={item.label}>
                <span className="sidebar-nav-label sidebar-nav-label--disabled" aria-disabled="true">
                  {item.label}
                </span>
              </li>
            ),
          )}
        </ul>
      </nav>

      <button type="button" className="sidebar-logout-btn" aria-disabled="true">
        로그아웃
      </button>
    </aside>
  );
}
