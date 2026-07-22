import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import '../../../shared/styles/layout.css';
import { AiBriefingCard } from '../components/AiBriefingCard';
import { GradeDistributionCard } from '../components/GradeDistributionCard';
import { KpiSection } from '../components/KpiSection';
import { PendingPriorityCard } from '../components/PendingPriorityCard';
import { RecentInspectionsTable } from '../components/RecentInspectionsTable';
import { AI_WEEKLY_BRIEFING_ANCHOR_ID, AI_WEEKLY_BRIEFING_PATH, INSPECTION_NEW_PATH } from '../constants';

export function DashboardPage() {
  const navigate = useNavigate();
  const location = useLocation();

  // 스토리보드 DASH-01 A1: "새 점검 시작" → 신규 점검(회차) 생성 화면(FR-2-01 업로드)으로 이동
  const handleStartNewInspection = () => {
    navigate(INSPECTION_NEW_PATH);
  };

  // 사이드바 "AI 주간 브리핑 카드"는 별도 화면이 아니라 이 페이지 안의 AiBriefingCard 위젯을 가리킨다
  // (#478, #472와 동일한 라우트-메뉴 불일치 유형). 새 화면을 만드는 대신 전용 경로(router.tsx에 등록)로
  // 진입하면 위젯 위치로 스크롤한다 — LandingPage의 해시 앵커 스크롤과 같은 패턴.
  useEffect(() => {
    if (location.pathname !== AI_WEEKLY_BRIEFING_PATH) {
      return;
    }
    requestAnimationFrame(() => {
      document
        .getElementById(AI_WEEKLY_BRIEFING_ANCHOR_ID)
        ?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  }, [location.pathname]);

  return (
    // 공용 .dashboard-content는 다른 feature(마이페이지 등)도 재사용하므로 모서리는 이 페이지에만
    // rounded-none!(un-layered CSS override, colors.ts 관례와 동일)로 국소 적용(Figma 정합, #556 후속)
    <div className="dashboard-content rounded-none!">
      <div className="dashboard-page-header">
        <h1 className="dashboard-page-title">대시보드</h1>
        <button
          type="button"
          className="bg-[#111] text-white border-none rounded-full py-2.5 px-4.5 text-sm font-semibold cursor-pointer"
          onClick={handleStartNewInspection}
        >
          + 새 점검 시작
        </button>
      </div>

      <KpiSection />

      {/* 시안: KPI 아래는 2단 컬럼 — 좌(넓음)=등급분포+최근점검 / 우(좁음)=처리대기+AI 브리핑.
          mt-3은 KPI 섹션과의 간격을 Figma 시안처럼 더 넓게 벌리기 위한 추가 여백(#556 후속) */}
      <div className="mt-3 grid grid-cols-[minmax(0,1.7fr)_minmax(0,1fr)] gap-4 items-start max-[1100px]:grid-cols-1">
        <div className="flex flex-col gap-4 min-w-0">
          <GradeDistributionCard />
          <RecentInspectionsTable />
        </div>
        <div className="flex flex-col gap-4 min-w-0">
          <PendingPriorityCard />
          <div id={AI_WEEKLY_BRIEFING_ANCHOR_ID}>
            <AiBriefingCard />
          </div>
        </div>
      </div>
    </div>
  );
}
