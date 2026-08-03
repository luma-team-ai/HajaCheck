import { useState } from 'react';
import type { ManualSectionType } from '../../types';
import { MANUAL_SECTION_LABELS } from '../../utils/sectionOrder';

interface AddSectionMenuProps {
  /** 이미 추가된 수동 섹션 타입 — 표준서식은 각 1개씩만 두므로 중복 추가를 막는다.
   * 단, 'location-drawing-photos'는 실 서식에서 위치도/전경사진(1)/전경사진(2)/종ㆍ평면도/현황도처럼
   * 제목이 다른 여러 페이지로 나뉘므로 예외적으로 여러 번 추가할 수 있다(#1409). */
  existingTypes: ManualSectionType[];
  onAdd: (type: ManualSectionType, title?: string) => void;
}

// 'overview-form'ㆍ'inspection-result-repair'ㆍ'member-condition-repair'는 여기서 뺀다(#1409) —
// 각각 고정 섹션과 제목이 완전히 같거나('overview-form' = "기본현황" = 고정 overview 라벨),
// 고정 섹션이 이미 표로 렌더링하는 문구와 그대로 겹친다('inspection-result-repair' =
// "상태평가 결과 및 보수ㆍ보강" = detail 표의 회색 헤더 행, exportReportToPdf.ts 참고).
// 새로 추가하면 PDF에 같은 제목/문구가 두 번 나온다. 타입ㆍ렌더 로직ㆍ데이터는 그대로 둔다 —
// 이미 이 타입으로 저장된 기존 보고서는 편집기ㆍPDF 양쪽에서 계속 정상 렌더링된다(데이터 유실 없음).
const OPTIONS: ManualSectionType[] = [
  'submission',
  'participants',
  'safety-assessment',
  'field-test',
  'facility-status',
  'location-drawing-photos',
];

// 실 서식에서 이 타입의 페이지는 뭉뚱그린 "위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도" 한 제목이 아니라
// 페이지마다 다른 제목을 쓴다 — 추가할 때 제목을 고르게 한다(#1409).
const LOCATION_DRAWING_PHOTOS_TITLE_PRESETS = [
  '위치도',
  '전경 사진(1)',
  '전경 사진(2)',
  '종ㆍ평면도',
  '현황도',
] as const;
const CUSTOM_TITLE_OPTION = '직접 입력';

// 백엔드가 만들 수 없는 표준서식 섹션을 content_json 수동 섹션으로 추가하는 진입점.
export function AddSectionMenu({ existingTypes, onAdd }: AddSectionMenuProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [showTitlePicker, setShowTitlePicker] = useState(false);
  const [selectedPreset, setSelectedPreset] = useState<string>(LOCATION_DRAWING_PHOTOS_TITLE_PRESETS[0]);
  const [customTitle, setCustomTitle] = useState('');

  const available = OPTIONS.filter(
    (type) => type === 'location-drawing-photos' || !existingTypes.includes(type),
  );

  if (available.length === 0) return null;

  const closeAll = () => {
    setIsOpen(false);
    setShowTitlePicker(false);
    setSelectedPreset(LOCATION_DRAWING_PHOTOS_TITLE_PRESETS[0]);
    setCustomTitle('');
  };

  const handleSelect = (type: ManualSectionType) => {
    if (type === 'location-drawing-photos') {
      setShowTitlePicker(true);
      return;
    }
    onAdd(type);
    closeAll();
  };

  const isCustom = selectedPreset === CUSTOM_TITLE_OPTION;
  const confirmDisabled = isCustom && customTitle.trim().length === 0;

  const confirmLocationDrawingPhotosAdd = () => {
    const title = isCustom ? customTitle.trim() : selectedPreset;
    if (!title) return;
    onAdd('location-drawing-photos', title);
    closeAll();
  };

  return (
    <div className="relative self-start">
      <button
        type="button"
        onClick={() => (isOpen ? closeAll() : setIsOpen(true))}
        className="rounded-full border border-dashed border-border px-4 py-2 text-sm font-medium text-text-default transition hover:bg-surface-muted cursor-pointer"
      >
        + 서식 섹션 추가
      </button>
      {isOpen && !showTitlePicker && (
        <div className="absolute bottom-full left-0 z-20 mb-2 flex max-h-80 w-72 flex-col gap-1 overflow-y-auto rounded-xl border border-border bg-surface p-2 shadow-xl">
          {available.map((type) => (
            <button
              key={type}
              type="button"
              onClick={() => handleSelect(type)}
              className="flex items-center justify-between rounded-lg px-3.5 py-2.5 text-left text-sm font-medium text-heading transition hover:bg-surface-muted cursor-pointer"
            >
              <span>{MANUAL_SECTION_LABELS[type]}</span>
              {type === "location-drawing-photos" && (
                <svg
                  className="size-4 text-zinc-400 shrink-0"
                  viewBox="0 0 20 20"
                  fill="currentColor"
                  aria-hidden="true"
                >
                  <path
                    fillRule="evenodd"
                    d="M7.21 14.77a.75.75 0 01.02-1.06L11.168 10 7.23 6.03a.75.75 0 111.06-1.06l4.5 4.5a.75.75 0 010 1.06l-4.5 4.5a.75.75 0 01-1.06.02z"
                    clipRule="evenodd"
                  />
                </svg>
              )}
            </button>
          ))}
        </div>
      )}
      {showTitlePicker && (
        <div className="absolute bottom-full left-0 z-20 mb-2 flex w-72 flex-col gap-3.5 rounded-xl border border-border bg-surface p-4 shadow-xl">
          <div className="flex items-center justify-between">
            <span className="text-xs font-bold tracking-wide text-heading">
              위치도ㆍ도면 섹션
            </span>
            <button
              type="button"
              onClick={() => setShowTitlePicker(false)}
              className="inline-flex items-center gap-1 text-xs text-text-muted hover:text-heading transition cursor-pointer"
            >
              <svg
                className="size-3.5"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                aria-hidden="true"
              >
                <path d="m15 18-6-6 6-6" />
              </svg>
              뒤로
            </button>
          </div>

          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium text-text-muted">
              페이지 제목 선택
            </span>
            <div className="relative">
              <select
                aria-label="섹션 제목 선택"
                value={selectedPreset}
                onChange={(event) => setSelectedPreset(event.target.value)}
                className="w-full appearance-none rounded-lg border border-border bg-surface pl-3.5 pr-9 py-2.5 text-sm font-medium text-heading shadow-2xs outline-none transition cursor-pointer hover:border-zinc-300 focus:border-primary focus:ring-2 focus:ring-primary/20"
              >
                {LOCATION_DRAWING_PHOTOS_TITLE_PRESETS.map((preset) => (
                  <option key={preset} value={preset}>
                    {preset}
                  </option>
                ))}
                <option value={CUSTOM_TITLE_OPTION}>{CUSTOM_TITLE_OPTION}</option>
              </select>
              <div className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-zinc-400">
                <svg
                  className="size-4"
                  viewBox="0 0 20 20"
                  fill="currentColor"
                  aria-hidden="true"
                >
                  <path
                    fillRule="evenodd"
                    d="M5.23 7.21a.75.75 0 011.06.02L10 11.168l3.71-3.938a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z"
                    clipRule="evenodd"
                  />
                </svg>
              </div>
            </div>
          </label>

          {isCustom && (
            <input
              aria-label="섹션 제목 직접 입력"
              value={customTitle}
              onChange={(event) => setCustomTitle(event.target.value)}
              placeholder="예: 종단면도"
              className="w-full rounded-lg border border-border bg-surface px-3.5 py-2.5 text-sm text-heading shadow-2xs outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20"
            />
          )}

          <div className="flex justify-end gap-2 pt-1">
            <button
              type="button"
              onClick={closeAll}
              className="rounded-full border border-border bg-surface px-3.5 py-1.5 text-xs font-semibold text-text-muted transition hover:bg-surface-muted cursor-pointer"
            >
              취소
            </button>
            <button
              type="button"
              onClick={confirmLocationDrawingPhotosAdd}
              disabled={confirmDisabled}
              className="rounded-full bg-primary px-4 py-1.5 text-xs font-bold text-surface shadow-2xs transition hover:bg-heading disabled:cursor-not-allowed disabled:opacity-40 cursor-pointer"
            >
              추가
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
