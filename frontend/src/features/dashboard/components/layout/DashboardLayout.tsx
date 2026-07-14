import type { ReactNode } from 'react';
import { ChatbotButton } from './ChatbotButton';
import { Sidebar } from './Sidebar';
import { TopBar } from './TopBar';

type Props = {
  children: ReactNode;
};

export function DashboardLayout({ children }: Props) {
  return (
    <div className="dashboard-layout">
      <Sidebar />
      <div className="dashboard-main">
        <TopBar />
        <main className="dashboard-content">{children}</main>
      </div>
      <ChatbotButton />
    </div>
  );
}
