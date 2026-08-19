import { useEffect, useRef, useState } from 'react';
import { Button } from '../../../shared/components/Button/Button';

export type PeriodOptionValue = '3m' | '6m' | '1y';
export type FacilityOptionValue = string;

export const PERIOD_OPTIONS: { label: string; value: PeriodOptionValue }[] = [
  { label: '최근 3개월', value: '3m' },
  { label: '최근 6개월', value: '6m' },
  { label: '최근 1년', value: '1y' },
];

export interface FacilityOptionItem {
  id: string;
  name: string;
}

export const DEFAULT_FACILITY_OPTIONS: FacilityOptionItem[] = [
  { id: 'all', name: '전체 시설물' },
  { id: '1', name: '여의도 파크센터' },
  { id: '2', name: '강남 오피스타워' },
  { id: '3', name: '한강대교 북단' },
  { id: '4', name: '판교 테크노밸리' },
  { id: '5', name: '수원 스마트팩토리' },
];

interface StatisticsFilterBarProps {
  selectedPeriod: PeriodOptionValue;
  onPeriodChange: (period: PeriodOptionValue) => void;
  selectedFacility: FacilityOptionValue;
  onFacilityChange: (facility: FacilityOptionValue) => void;
  facilityOptions?: FacilityOptionItem[];
  onExport: () => void;
  isExporting?: boolean;
}

export function StatisticsFilterBar({
  selectedPeriod,
  onPeriodChange,
  selectedFacility,
  onFacilityChange,
  facilityOptions = DEFAULT_FACILITY_OPTIONS,
  onExport,
  isExporting = false,
}: StatisticsFilterBarProps) {
  const [isOpenPeriod, setIsOpenPeriod] = useState(false);
  const [isOpenFacility, setIsOpenFacility] = useState(false);
  const [isExportingSuccess, setIsExportingSuccess] = useState(false);

  const containerRef = useRef<HTMLDivElement>(null);

  // 외부 클릭 및 Escape 키 입력 시 드롭다운 닫기
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsOpenPeriod(false);
        setIsOpenFacility(false);
      }
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setIsOpenPeriod(false);
        setIsOpenFacility(false);
      }
    }

    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, []);

  const handleExportClick = () => {
    onExport();
    setIsExportingSuccess(true);
    setTimeout(() => setIsExportingSuccess(false), 2000);
  };

  const currentPeriodLabel =
    PERIOD_OPTIONS.find((opt) => opt.value === selectedPeriod)?.label ?? '최근 6개월';

  const currentFacilityName =
    facilityOptions.find((opt) => opt.id === selectedFacility)?.name ?? '전체 시설물';

  return (
    <div ref={containerRef} className="flex flex-wrap items-center gap-3">
      {/* 1. 기간 선택 커스텀 팝오버 드롭다운 (완전 직각/각진 모서리) */}
      <div className="relative inline-block text-left">
        <button
          type="button"
          onClick={() => {
            setIsOpenPeriod((prev) => !prev);
            setIsOpenFacility(false);
          }}
          aria-haspopup="listbox"
          aria-expanded={isOpenPeriod}
          aria-label="조회 기간 선택"
          className="inline-flex items-center gap-2.5 rounded-none! border border-zinc-200 bg-white px-4 py-2.5 text-sm font-medium text-zinc-800 shadow-2xs hover:border-zinc-300 hover:bg-zinc-50 focus:outline-hidden transition-all cursor-pointer"
        >
          <svg className="h-4 w-4 text-zinc-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.8}>
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"
            />
          </svg>
          <span>{currentPeriodLabel}</span>
          <svg
            className={`h-3.5 w-3.5 text-zinc-400 transition-transform duration-150 ${
              isOpenPeriod ? 'rotate-180' : ''
            }`}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={2}
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
          </svg>
        </button>

        {isOpenPeriod && (
          <div
            role="listbox"
            aria-label="조회 기간 목록"
            className="absolute left-0 top-full z-50 mt-1.5 w-44 rounded-none! border border-zinc-200 bg-white p-1.5 shadow-lg flex flex-col gap-0.5 animate-in fade-in zoom-in-95"
          >
            {PERIOD_OPTIONS.map((opt) => {
              const isSelected = selectedPeriod === opt.value;
              return (
                <button
                  key={opt.value}
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  onClick={() => {
                    onPeriodChange(opt.value);
                    setIsOpenPeriod(false);
                  }}
                  className={`flex w-full cursor-pointer items-center justify-between rounded-none! px-3 py-2 text-left text-sm font-medium transition-colors ${
                    isSelected
                      ? 'bg-zinc-100 font-semibold text-zinc-900'
                      : 'text-zinc-700 hover:bg-zinc-50 hover:text-zinc-900'
                  }`}
                >
                  <span>{opt.label}</span>
                  {isSelected && (
                    <svg className="h-4 w-4 text-zinc-900" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                    </svg>
                  )}
                </button>
              );
            })}
          </div>
        )}
      </div>

      {/* 2. 시설물 필터 커스텀 팝오버 드롭다운 (완전 직각/각진 모서리) */}
      <div className="relative inline-block text-left">
        <button
          type="button"
          onClick={() => {
            setIsOpenFacility((prev) => !prev);
            setIsOpenPeriod(false);
          }}
          aria-haspopup="listbox"
          aria-expanded={isOpenFacility}
          aria-label="시설물 범위 선택"
          className="inline-flex items-center gap-2.5 rounded-none! border border-zinc-200 bg-white px-4 py-2.5 text-sm font-medium text-zinc-800 shadow-2xs hover:border-zinc-300 hover:bg-zinc-50 focus:outline-hidden transition-all cursor-pointer"
        >
          {/* Figma 시안 아이콘: 깔때기형 3단 필터 아이콘 */}
          <svg className="h-4 w-4 text-zinc-800" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
            <path strokeLinecap="round" strokeLinejoin="round" d="M3 6h18M6 12h12M10 18h4" />
          </svg>
          <span>{currentFacilityName}</span>
          <svg
            className={`h-3.5 w-3.5 text-zinc-400 transition-transform duration-150 ${
              isOpenFacility ? 'rotate-180' : ''
            }`}
            fill="none"
            viewBox="0 0 24 24"
            stroke="currentColor"
            strokeWidth={2}
          >
            <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
          </svg>
        </button>

        {isOpenFacility && (
          <div
            role="listbox"
            aria-label="시설물 목록"
            className="absolute left-0 top-full z-50 mt-1.5 w-52 max-h-60 overflow-y-auto rounded-none! border border-zinc-200 bg-white p-1.5 shadow-lg flex flex-col gap-0.5 animate-in fade-in zoom-in-95"
          >
            {facilityOptions.map((opt) => {
              const isSelected = selectedFacility === opt.id;
              return (
                <button
                  key={opt.id}
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  onClick={() => {
                    onFacilityChange(opt.id);
                    setIsOpenFacility(false);
                  }}
                  className={`flex w-full cursor-pointer items-center justify-between rounded-none! px-3 py-2 text-left text-sm font-medium transition-colors ${
                    isSelected
                      ? 'bg-zinc-100 font-semibold text-zinc-900'
                      : 'text-zinc-700 hover:bg-zinc-50 hover:text-zinc-900'
                  }`}
                >
                  <span className="truncate">{opt.name}</span>
                  {isSelected && (
                    <svg className="h-4 w-4 shrink-0 text-zinc-900" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}>
                      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                    </svg>
                  )}
                </button>
              );
            })}
          </div>
        )}
      </div>

      {/* 3. 내보내기 버튼 (Figma 시안: rounded-full 둥근 알약 형태)
          data-export-ignore — exportStatisticsAsPdf가 html2canvas-pro onclone에서 이 버튼만
          visibility:hidden 처리해 캡처에서 뺀다(#1692, 리포트 안에 액션 버튼이 찍히는 건
          부자연스러움). shared Button은 ButtonHTMLAttributes를 그대로 spread하므로(...rest)
          data-* 속성이 <button> DOM에 그대로 전달된다. */}
      <Button
        variant="secondary"
        size="md"
        onClick={handleExportClick}
        disabled={isExporting}
        className="rounded-full! px-4.5! py-2.5! text-sm! font-medium! border-zinc-200! hover:bg-zinc-50! cursor-pointer shadow-2xs!"
        aria-label="통계 데이터 내보내기"
        data-export-ignore="true"
      >
        <svg className="mr-1.5 h-4 w-4 inline-block text-zinc-700" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
          />
        </svg>
        {isExporting ? '내보내는 중...' : isExportingSuccess ? '완료!' : '내보내기'}
      </Button>
    </div>
  );
}
