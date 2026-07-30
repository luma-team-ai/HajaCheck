export type MyInspectionsTab = 'HISTORY' | 'REPORTS';

type Props = {
  activeTab: MyInspectionsTab;
  onChange: (tab: MyInspectionsTab) => void;
};

const TABS: { value: MyInspectionsTab; label: string }[] = [
  { value: 'HISTORY', label: '점검 이력' },
  { value: 'REPORTS', label: '내 보고서' },
];

// 내 점검 이력/보고서 페이지 내부 전용 탭 — 라우트 이동 없이 로컬 state로만 전환한다(HAJA-366, #668).
// 기존 PolicyTabs(features/policy)는 라우트 이동형이라 이 화면(단일 페이지 내 뷰 전환)에는
// 맞지 않아 새로 만든다. pill 스타일: 트랙(연회색) 안에서 활성 탭만 흰 배경 + shadow.
export function MyInspectionsTabs({ activeTab, onChange }: Props) {
  return (
    <div
      role="tablist"
      aria-label="내 점검 이력 / 보고서 보기 전환"
      className="inline-flex w-fit gap-1 rounded-full bg-surface-muted p-1"
    >
      {TABS.map((tab) => {
        const isActive = tab.value === activeTab;
        return (
          <button
            key={tab.value}
            type="button"
            role="tab"
            aria-selected={isActive}
            className={`rounded-full px-4 py-1.5 text-sm font-semibold transition-colors duration-150 ${
              isActive ? 'bg-white text-heading shadow-sm' : 'text-text-muted hover:text-text-default'
            }`}
            onClick={() => onChange(tab.value)}
          >
            {tab.label}
          </button>
        );
      })}
    </div>
  );
}
