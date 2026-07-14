import { useNavigate } from 'react-router-dom';
import { AiBriefingCard } from '../components/AiBriefingCard';
import { GradeDistributionCard } from '../components/GradeDistributionCard';
import { DashboardLayout } from '../components/layout/DashboardLayout';
import { KpiSection } from '../components/KpiSection';
import { PendingPriorityCard } from '../components/PendingPriorityCard';
import { RecentInspectionsTable } from '../components/RecentInspectionsTable';
import { INSPECTION_NEW_PATH } from '../constants';
import '../dashboard.css';

export function DashboardPage() {
  const navigate = useNavigate();

  // 스토리보드 DASH-01 A1: "새 점검 시작" → 신규 점검(회차) 생성 화면(FR-2-01 업로드)으로 이동
  const handleStartNewInspection = () => {
    navigate(INSPECTION_NEW_PATH);
  };

  return (
    <DashboardLayout>
      <div className="dashboard-page-header">
        <h1 className="dashboard-page-title">대시보드</h1>
        <button
          type="button"
          className="dashboard-new-inspection-btn"
          onClick={handleStartNewInspection}
        >
          + 새 점검 시작
        </button>
      </div>

      <KpiSection />

      <div className="dashboard-row">
        <GradeDistributionCard />
        <PendingPriorityCard />
      </div>

      <RecentInspectionsTable />

      <AiBriefingCard />
    </DashboardLayout>
  );
}
