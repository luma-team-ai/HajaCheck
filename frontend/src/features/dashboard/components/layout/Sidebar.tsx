import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import { NAV_ITEMS } from '../../constants';
import { NavIcon } from './NavIcon';

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
          <NavIcon name="sidebar-toggle" />
        </button>
      </div>

      <nav className="sidebar-nav">
        <ul className="sidebar-nav-list">
          {NAV_ITEMS.map((item) =>
            item.subItems ? (
              <li key={item.label} className="sidebar-nav-group">
                <span className="sidebar-nav-label sidebar-nav-label--expanded">
                  <NavIcon name={item.icon} />
                  {!isCollapsed && <span className="sidebar-nav-text">{item.label}</span>}
                  {!isCollapsed && (
                    <span className="sidebar-nav-chevron">
                      <NavIcon name="chevron-down" />
                    </span>
                  )}
                </span>
                {!isCollapsed && (
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
                )}
              </li>
            ) : (
              <li key={item.label}>
                <span
                  className="sidebar-nav-label sidebar-nav-label--disabled"
                  aria-disabled="true"
                  title={isCollapsed ? item.label : undefined}
                >
                  <NavIcon name={item.icon} />
                  {!isCollapsed && <span className="sidebar-nav-text">{item.label}</span>}
                </span>
              </li>
            ),
          )}
        </ul>
      </nav>

      <button type="button" className="sidebar-logout-btn" aria-disabled="true">
        <NavIcon name="logout" />
        {!isCollapsed && <span className="sidebar-nav-text">로그아웃</span>}
      </button>
    </aside>
  );
}
