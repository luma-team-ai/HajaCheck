import type { ReactNode } from 'react';
import { ChatbotButton } from './ChatbotButton';
import { Sidebar } from './Sidebar';
import { TopBar } from './TopBar';

type Props = {
  children: ReactNode;
  // TopBar breadcrumb 현재 위치 — 페이지별로 다름(HAJA-185). 미지정 시 TopBar 기본값('대시보드') 사용
  currentLabel?: string;
};

export function DashboardLayout({ children, currentLabel }: Props) {
  return (
    <div className="dashboard-layout">
      <Sidebar />
      <div className="dashboard-main">
        <TopBar currentLabel={currentLabel} />
        <main className="dashboard-content">{children}</main>
      </div>
      <ChatbotButton />
    </div>
  );
}
