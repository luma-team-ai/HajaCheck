import { useNavigate } from 'react-router-dom';
import '../../../shared/styles/layout.css';
import { AiBriefingCard } from '../components/AiBriefingCard';
import { GradeDistributionCard } from '../components/GradeDistributionCard';
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
    <div className="dashboard-content">
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

      {/* 시안: KPI 아래는 2단 컬럼 — 좌(넓음)=등급분포+최근점검 / 우(좁음)=처리대기+AI 브리핑 */}
      <div className="dashboard-columns">
        <div className="dashboard-col dashboard-col--main">
          <GradeDistributionCard />
          <RecentInspectionsTable />
        </div>
        <div className="dashboard-col dashboard-col--side">
          <PendingPriorityCard />
          <AiBriefingCard />
        </div>
      </div>
    </div>
  );
}
