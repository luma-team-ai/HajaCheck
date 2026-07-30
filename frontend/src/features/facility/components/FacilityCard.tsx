import {
  FACILITY_CARD_GRADE_DOT_COLOR,
  FACILITY_CARD_UPCOMING_BADGE_BG,
} from '../facilityCardGradeDotColors';
import type { Facility } from '../types';
import { deriveInspectionCycleStatus } from '../utils/inspectionCycleStatus';
import { formatLastInspectedAt } from '../utils/formatLastInspectedAt';

type Props = {
  facility: Facility;
  // latestDefectId — 하자 오버레이 직행(HAJA-434 갭1) 라우팅 판단용, 하자 없으면 null.
  onSelect: (id: number, latestDefectId: number | null) => void;
};

// 시설물 카드(HAJA-368/#671, Figma "hajaCheck Facility List - Fixed Images") — 기존
// FacilityTable(순수 텍스트 테이블)을 대체하는 카드형 그리드 항목. 전체 카드를 버튼으로 렌더링해
// 클릭 시 시설물 상세(또는 하자 오버레이)로 이동한다(TicketCard.tsx의 전체-카드-버튼 패턴과 동일).
export function FacilityCard({ facility, onSelect }: Props) {
  const dueStatus = deriveInspectionCycleStatus(facility.nextInspectionDueAt);
  const showUpcomingBadge = dueStatus.kind === 'upcoming';
  const lastInspectedLabel = formatLastInspectedAt(facility.lastInspectedAt);

  const subtitleParts = [
    facility.type,
    facility.address ?? '주소 미등록',
    facility.builtYear != null ? `준공 ${facility.builtYear}` : null,
  ].filter((part): part is string => Boolean(part));

  // code-reviewer P2 — 카드 전체를 버튼으로 감싸면서 aria-label을 이름만으로 두면 등급·다음
  // 점검일·최근 점검일 등 시각적으로는 보이는 정보가 스크린리더에서 사라진다. 카드 내용을
  // 요약한 라벨을 명시적으로 조립해 접근성 손실 없이 전체-카드-클릭 UX를 유지한다.
  const ariaLabel = [
    facility.name,
    subtitleParts.join(', '),
    facility.initialGrade ? `${facility.initialGrade} 등급` : null,
    showUpcomingBadge ? `다음 점검일 ${dueStatus.label}` : null,
    `하자 ${facility.defectCount}건`,
    lastInspectedLabel ? `최근 점검 ${lastInspectedLabel}` : '점검 이력 없음',
  ]
    .filter(Boolean)
    .join(', ');

  return (
    <button
      type="button"
      aria-label={ariaLabel}
      onClick={() => onSelect(facility.id, facility.latestDefectId)}
      className="flex w-full flex-col overflow-hidden rounded-2xl border border-border bg-surface text-left shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)] transition-colors hover:bg-surface-sunken"
    >
      <div className="relative h-48 w-full shrink-0 bg-neutral-100">
        {facility.thumbnailUrl ? (
          <img
            src={facility.thumbnailUrl}
            alt={facility.name}
            className="h-full w-full object-cover"
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center text-xs text-text-muted">
            사진 없음
          </div>
        )}

        {facility.initialGrade && (
          <span
            className="absolute right-3 top-3 inline-flex items-center gap-1.5 rounded-full bg-white/90 px-2.5 py-1 text-xs font-medium text-zinc-900 shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)] backdrop-blur-[2px]"
          >
            <span
              className="h-2 w-2 rounded-full"
              style={{ backgroundColor: FACILITY_CARD_GRADE_DOT_COLOR[facility.initialGrade] }}
              aria-hidden="true"
            />
            {facility.initialGrade} 등급
          </span>
        )}

        {showUpcomingBadge && (
          <span
            className="absolute bottom-3 left-3 inline-flex items-center rounded-full px-2.5 py-1 text-xs font-medium text-white shadow-[0px_1px_2px_0px_rgba(0,0,0,0.05)]"
            style={{ backgroundColor: FACILITY_CARD_UPCOMING_BADGE_BG }}
          >
            다음 점검일 {dueStatus.label}
          </span>
        )}
      </div>

      <div className="flex flex-col gap-1 border-t border-border p-5">
        <p className="m-0 text-xl font-medium text-heading">{facility.name}</p>
        <p className="m-0 text-sm text-text-muted">{subtitleParts.join(' · ')}</p>

        <div className="mt-3 flex items-center justify-between border-t border-border/50 pt-4">
          <span
            className="inline-flex items-center rounded-full bg-surface-sunken px-2.5 py-1 text-xs font-medium text-text"
          >
            하자 {facility.defectCount}건
          </span>
          <span className="text-sm text-text-muted">
            {lastInspectedLabel ? `최근 점검 ${lastInspectedLabel}` : '점검 이력 없음'}
          </span>
        </div>
      </div>
    </button>
  );
}
