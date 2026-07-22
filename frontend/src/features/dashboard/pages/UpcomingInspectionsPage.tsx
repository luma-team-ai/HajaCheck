import { useNavigate } from 'react-router-dom';
import '../../../shared/styles/layout.css';
import { AiBriefingCard } from '../components/AiBriefingCard';
import { PendingPriorityCard } from '../components/PendingPriorityCard';
import { UpcomingInspectionCard } from '../components/UpcomingInspectionCard';
import { DASHBOARD_COLOR_CLASS } from '../colors';
import { INSPECTION_NEW_PATH } from '../constants';
import { useUpcomingInspections } from '../hooks/useUpcomingInspections';

const FACILITY_INSPECTION_CYCLE_PATH = '/facilities/inspection-cycle';

// 대시보드 "다음 점검일 도래" 전용 페이지(dev-03-02, #543) — AI 주간 브리핑(#478)과 달리 이 위젯은
// 실제 화면 자체가 없었어서 앵커스크롤이 아니라 독립 페이지로 구현한다.
export function UpcomingInspectionsPage() {
  const navigate = useNavigate();
  const { data, isLoading, isError } = useUpcomingInspections();

  const handleStartNewInspection = () => {
    navigate(INSPECTION_NEW_PATH);
  };

  const handleViewFullSchedule = () => {
    navigate(FACILITY_INSPECTION_CYCLE_PATH);
  };

  return (
    <div className="dashboard-content">
      <div className="dashboard-page-header">
        <h1 className="dashboard-page-title">다음 점검일 도래</h1>
        <button
          type="button"
          className="cursor-pointer rounded-full border-none bg-[#111] px-4.5 py-2.5 text-sm font-semibold text-white"
          onClick={handleStartNewInspection}
        >
          + 새 점검 시작
        </button>
      </div>

      {!isLoading && !isError && data && data.length > 0 && (
        <div
          className={`flex items-center justify-between gap-8 border-l-4 border-y border-r bg-neutral-50 px-5 py-5 ${DASHBOARD_COLOR_CLASS.upcomingBannerBorder} ${DASHBOARD_COLOR_CLASS.dividerBorder}`}
        >
          <div className="flex flex-col gap-1">
            <div className="flex items-center gap-2">
              {/* 이 배너는 페이지 전용 인라인 컴포넌트라 아래 텍스트가 항상 동반됨이 보장된다 —
                  공용 컴포넌트로 추출될 경우 텍스트 없이 아이콘만 쓰는 케이스가 생길 수 있으니
                  그때는 aria-hidden 전제를 재검토할 것. */}
              <span aria-hidden="true">⚠️</span>
              <p className="m-0 text-lg font-medium text-zinc-900">
                다가오는 점검 일정이 {data.length}건 있습니다
              </p>
            </div>
            <p className={`m-0 text-sm ${DASHBOARD_COLOR_CLASS.bodyText}`}>
              안전점검 누락 방지를 위한 법정 주기 기준 안내입니다.
            </p>
          </div>
          <button
            type="button"
            className={`shrink-0 cursor-pointer rounded-full border bg-white px-4 py-2 text-sm font-medium text-zinc-900 ${DASHBOARD_COLOR_CLASS.dividerBorder}`}
            onClick={handleViewFullSchedule}
          >
            전체 스케줄 보기
          </button>
        </div>
      )}

      <div className="grid grid-cols-[minmax(0,1.7fr)_minmax(0,1fr)] gap-4 items-start max-[1100px]:grid-cols-1">
        <div className="flex flex-col gap-4 min-w-0">
          <h2 className="m-0 text-lg font-medium text-zinc-900">점검일 도래 시설물</h2>

          {isLoading && <p className="dashboard-card-status">불러오는 중...</p>}
          {isError && <p className="dashboard-card-status">다음 점검일 목록을 불러오지 못했습니다.</p>}
          {!isLoading && !isError && (!data || data.length === 0) && (
            <p className="dashboard-card-status">다가오는 점검 일정이 없습니다.</p>
          )}
          {!isLoading &&
            !isError &&
            data &&
            data.map((item) => <UpcomingInspectionCard key={item.facilityId} item={item} />)}
        </div>

        <div className="flex flex-col gap-4 min-w-0">
          <PendingPriorityCard />
          <AiBriefingCard />
        </div>
      </div>
    </div>
  );
}
