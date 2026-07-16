import { useNavigate } from 'react-router-dom';
import '../../../shared/styles/layout.css';
import { AiBriefingCard } from '../components/AiBriefingCard';
import { GradeDistributionCard } from '../components/GradeDistributionCard';
import { KpiSection } from '../components/KpiSection';
import { PendingPriorityCard } from '../components/PendingPriorityCard';
import { RecentInspectionsTable } from '../components/RecentInspectionsTable';
import { INSPECTION_NEW_PATH } from '../constants';

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
          className="bg-[#111] text-white border-none rounded-[10px] py-2.5 px-4.5 text-sm font-semibold cursor-pointer"
          onClick={handleStartNewInspection}
        >
          + 새 점검 시작
        </button>
      </div>

      <KpiSection />

      {/* 시안: KPI 아래는 2단 컬럼 — 좌(넓음)=등급분포+최근점검 / 우(좁음)=처리대기+AI 브리핑 */}
      <div className="grid grid-cols-[minmax(0,1.7fr)_minmax(0,1fr)] gap-4 items-start max-[1100px]:grid-cols-1">
        <div className="flex flex-col gap-4 min-w-0">
          <GradeDistributionCard />
          <RecentInspectionsTable />
        </div>
        <div className="flex flex-col gap-4 min-w-0">
          <PendingPriorityCard />
          <AiBriefingCard />
        </div>
      </div>
    </div>
  );
}
