import type { ReactNode } from 'react';
import { useState } from 'react';
import type { FacilityOverviewHistoryItem } from './FacilityInspectionHistoryItem';
import { FacilityInspectionHistoryItem } from './FacilityInspectionHistoryItem';

// 등급 배지 팔레트(A 초록 → E 빨강) — Figma dev mode 마크업의 D등급 배지
// (bg-orange-100/30 outline-orange-200 + bg-orange-500 점)를 등급별로 일반화.
const GRADE_BADGE_CLASS: Record<string, string> = {
  A: 'bg-green-100/30 outline-green-200',
  B: 'bg-lime-100/30 outline-lime-200',
  C: 'bg-yellow-100/30 outline-yellow-200',
  D: 'bg-orange-100/30 outline-orange-200',
  E: 'bg-red-100/30 outline-red-200',
};
const GRADE_DOT_CLASS: Record<string, string> = {
  A: 'bg-green-500',
  B: 'bg-lime-500',
  C: 'bg-yellow-500',
  D: 'bg-orange-500',
  E: 'bg-red-500',
};
const DEFAULT_GRADE_BADGE_CLASS = 'bg-neutral-100 outline-neutral-300';
const DEFAULT_GRADE_DOT_CLASS = 'bg-neutral-400';

const TABS = [
  { key: 'overview', label: '개요' },
  { key: 'history', label: '점검 이력' },
  { key: 'defects', label: '하자 현황' },
  { key: 'documents', label: '문서' },
] as const;

type TabKey = (typeof TABS)[number]['key'];

export interface FacilityOverviewPanelProps {
  facilityName: string;
  /** "업무시설 · 서울 강남구 · 준공 2008" 형태로 호출부가 조합해 전달 */
  metaLine: string;
  /** null/undefined면 등급 배지를 표시하지 않는다 */
  grade?: string | null;
  totalRounds: number | string;
  cumulativeDefectCount: number | string;
  unresolvedDefectCount: number | string;
  /** "다음 점검일" D-day 배지 — feature마다 계산 로직이 달라 호출부가 렌더링해 전달 */
  nextInspectionBadge: ReactNode;
  history: FacilityOverviewHistoryItem[];
  onEditInfo?: () => void;
  onNewInspection?: () => void;
  /** 기본 "+ 새 점검" — 호출부 맥락에 따라 문구를 바꿀 수 있게 */
  newInspectionLabel?: string;
}

// 시설물 상세 / 점검(회차) 생성 화면이 공유하는 패널(shared) — Figma
// "hajaCheck Facility Detail - Fixed Images"(node-id 1-1401) dev mode 마크업 기준.
// 두 feature가 동일 레이아웃을 쓰기로 해 feature 로컬이 아니라 shared에 둔다.
export function FacilityOverviewPanel({
  facilityName,
  metaLine,
  grade,
  totalRounds,
  cumulativeDefectCount,
  unresolvedDefectCount,
  nextInspectionBadge,
  history,
  onEditInfo,
  onNewInspection,
  newInspectionLabel = '+ 새 점검',
}: FacilityOverviewPanelProps) {
  const [activeTab, setActiveTab] = useState<TabKey>('history');

  return (
    <div className="flex flex-col items-start justify-start self-stretch px-8 pt-8 pb-32">
      <div className="flex flex-col items-start justify-start self-stretch overflow-hidden rounded-[20px] bg-white outline outline-1 outline-offset-[-1px] outline-neutral-300/20">
        <div className="relative h-48 self-stretch bg-neutral-100">
          <div className="absolute inset-x-0 bottom-0 flex items-center justify-between gap-4 border-t border-neutral-200 bg-white/70 px-8 py-4 backdrop-blur-[10px]">
            <div className="flex flex-wrap items-center gap-4">
              <h1 className="m-0 text-3xl leading-9 font-medium text-zinc-900">{facilityName}</h1>
              {grade && (
                <span
                  className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 outline outline-1 outline-offset-[-1px] ${GRADE_BADGE_CLASS[grade] ?? DEFAULT_GRADE_BADGE_CLASS}`}
                >
                  <span
                    className={`size-2 rounded-full ${GRADE_DOT_CLASS[grade] ?? DEFAULT_GRADE_DOT_CLASS}`}
                    aria-hidden="true"
                  />
                  <span className="text-xs font-semibold tracking-wide text-zinc-900">{grade}</span>
                </span>
              )}
              {metaLine && <p className="m-0 text-base leading-6 text-neutral-600">{metaLine}</p>}
            </div>

            <div className="flex items-center gap-3">
              <button
                type="button"
                onClick={onEditInfo}
                className="rounded-xl bg-white px-5 py-2 text-base font-normal text-zinc-900 outline outline-1 outline-offset-[-1px] outline-neutral-300/30"
              >
                정보 수정
              </button>
              <button
                type="button"
                onClick={onNewInspection}
                className="rounded-xl bg-zinc-900 px-5 py-2 text-base font-normal text-white"
              >
                {newInspectionLabel}
              </button>
            </div>
          </div>
        </div>

        <div className="flex flex-col items-start justify-start gap-10 self-stretch p-8">
          <div className="flex items-center justify-between gap-2 self-stretch border-t border-b border-neutral-300/30 py-6">
            <StatCell label="점검 회차" value={totalRounds} />
            <div className="h-12 w-px bg-neutral-300/30" />
            <StatCell label="누적 하자" value={cumulativeDefectCount} />
            <div className="h-12 w-px bg-neutral-300/30" />
            <StatCell label="미조치" value={unresolvedDefectCount} />
            <div className="h-12 w-px bg-neutral-300/30" />
            <div className="flex flex-col items-start justify-start gap-1 px-4">
              <span className="text-base font-medium text-neutral-600">다음 점검일</span>
              {nextInspectionBadge}
            </div>
          </div>

          <div className="inline-flex items-center gap-1 rounded-full bg-zinc-200/40 p-1">
            {TABS.map((tab) => (
              <button
                key={tab.key}
                type="button"
                onClick={() => setActiveTab(tab.key)}
                aria-current={activeTab === tab.key ? 'page' : undefined}
                className={`cursor-pointer rounded-full px-6 py-2 text-base font-medium ${
                  activeTab === tab.key
                    ? 'bg-white text-zinc-900 shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)] outline outline-1 outline-offset-[-1px] outline-neutral-300/20'
                    : 'text-neutral-600'
                }`}
              >
                {tab.label}
              </button>
            ))}
          </div>

          {activeTab === 'history' ? (
            history.length > 0 ? (
              <div className="relative flex w-full flex-col gap-10 pl-6">
                <div className="absolute top-2 bottom-2 left-[11px] w-px bg-neutral-300/40" aria-hidden="true" />
                {history.map((item, index) => (
                  <FacilityInspectionHistoryItem key={item.id} item={item} expanded={index === 0} />
                ))}
              </div>
            ) : (
              <p className="m-0 text-base text-neutral-600">점검 이력이 없습니다.</p>
            )
          ) : (
            <p className="m-0 text-base text-neutral-600">준비 중인 화면입니다.</p>
          )}
        </div>
      </div>
    </div>
  );
}

function StatCell({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="flex flex-col items-start justify-start gap-1 px-4">
      <span className="text-base font-medium text-neutral-600">{label}</span>
      <span className="text-5xl leading-[52.8px] font-semibold text-zinc-900">{value}</span>
    </div>
  );
}
