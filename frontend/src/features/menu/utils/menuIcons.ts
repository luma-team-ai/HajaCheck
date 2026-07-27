import dashboardIcon from '../../../assets/brand/sidenav-dashboard.svg';
import facilitiesIcon from '../../../assets/brand/sidenav-facilities.svg';
import inspectionsIcon from '../../../assets/brand/sidenav-inspections.svg';
import defectsIcon from '../../../assets/brand/sidenav-defects.svg';
import reportsIcon from '../../../assets/brand/sidenav-reports.svg';
import supportIcon from '../../../assets/brand/sidenav-support.svg';
import mypageIcon from '../../../assets/brand/sidenav-mypage.svg';
import statisticsIcon from '../../../assets/brand/sidenav-statistics.svg';
import settingsIcon from '../../../assets/brand/sidenav-settings.svg';
import adminIcon from '../../../assets/brand/sidenav-admin.svg';

// menus.icon_key(DB 시드값)와 1:1로 맞춘 번들 SVG 매핑(#1003).
export const MENU_ICON_MAP: Record<string, string> = {
  dashboard: dashboardIcon,
  facilities: facilitiesIcon,
  inspections: inspectionsIcon,
  defects: defectsIcon,
  reports: reportsIcon,
  support: supportIcon,
  mypage: mypageIcon,
  statistics: statisticsIcon,
  settings: settingsIcon,
  admin: adminIcon,
};

// 알 수 없는(또는 아직 매핑에 없는) icon_key에 대한 폴백 — 새 메뉴가 프론트 배포 전에 먼저 시드되는
// 경우에도(관리자 편집 API는 후속 과제) 아이콘이 빈 채로 깨지지 않게 한다.
export const DEFAULT_MENU_ICON = dashboardIcon;
