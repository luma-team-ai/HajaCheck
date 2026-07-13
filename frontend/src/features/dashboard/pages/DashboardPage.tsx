import { AiBriefingCard } from '../components/AiBriefingCard';
import { GradeDistributionCard } from '../components/GradeDistributionCard';
import { DashboardLayout } from '../components/layout/DashboardLayout';
import { KpiSection } from '../components/KpiSection';
import { PendingPriorityCard } from '../components/PendingPriorityCard';
import { RecentInspectionsTable } from '../components/RecentInspectionsTable';
import '../dashboard.css';

export function DashboardPage() {
  return (
    <DashboardLayout>
      <div className="dashboard-page-header">
        <h1 className="dashboard-page-title">대시보드</h1>
        <button type="button" className="dashboard-new-inspection-btn">
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
