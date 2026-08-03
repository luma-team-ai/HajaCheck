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
        className="rounded-full border border-dashed border-border px-4 py-2 text-sm font-medium text-text-default transition hover:bg-surface-muted"
      >
        + 서식 섹션 추가
      </button>
      {isOpen && !showTitlePicker && (
        <div className="absolute bottom-full left-0 z-10 mb-2 flex max-h-80 w-72 flex-col gap-1 overflow-y-auto rounded-lg border border-border bg-surface p-1.5 shadow-lg">
          {available.map((type) => (
            <button
              key={type}
              type="button"
              onClick={() => handleSelect(type)}
              className="rounded-md px-3 py-2 text-left text-sm text-text-default transition hover:bg-surface-muted"
            >
              {MANUAL_SECTION_LABELS[type]}
            </button>
          ))}
        </div>
      )}
      {showTitlePicker && (
        <div className="absolute bottom-full left-0 z-10 mb-2 flex w-72 flex-col gap-3 rounded-lg border border-border bg-surface p-3 shadow-lg">
          <label className="flex flex-col gap-1.5">
            <span className="text-xs font-medium tracking-wide text-text-muted">섹션 제목</span>
            <select
              aria-label="섹션 제목 선택"
              value={selectedPreset}
              onChange={(event) => setSelectedPreset(event.target.value)}
              className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text-default outline-none focus:border-primary focus:ring-2 focus:ring-primary/10"
            >
              {LOCATION_DRAWING_PHOTOS_TITLE_PRESETS.map((preset) => (
                <option key={preset} value={preset}>
                  {preset}
                </option>
              ))}
              <option value={CUSTOM_TITLE_OPTION}>{CUSTOM_TITLE_OPTION}</option>
            </select>
          </label>
          {isCustom && (
            <input
              aria-label="섹션 제목 직접 입력"
              value={customTitle}
              onChange={(event) => setCustomTitle(event.target.value)}
              placeholder="예: 종단면도"
              className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-text-default outline-none focus:border-primary focus:ring-2 focus:ring-primary/10"
            />
          )}
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={closeAll}
              className="rounded-full px-3 py-1.5 text-xs font-medium text-text-muted transition hover:bg-surface-muted"
            >
              취소
            </button>
            <button
              type="button"
              onClick={confirmLocationDrawingPhotosAdd}
              disabled={confirmDisabled}
              className="rounded-full bg-primary px-3 py-1.5 text-xs font-medium text-white transition disabled:cursor-not-allowed disabled:opacity-50"
            >
              추가
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
