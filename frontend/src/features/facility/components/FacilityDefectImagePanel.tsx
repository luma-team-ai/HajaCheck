import { useState } from 'react';
import { buildDefectImagePlaceholder } from '../utils/defectImagePlaceholder';
import type { FacilityDefectDetail } from '../types';

type ImageTab = 'original' | 'overlay';

type Props = {
  defect: FacilityDefectDetail;
  onCompareClick: () => void;
};

const TAB_LABEL: Record<ImageTab, string> = { original: '원본', overlay: '오버레이' };
const IMAGE_TABS: ImageTab[] = ['original', 'overlay'];
const NO_IMAGE_PLACEHOLDER = buildDefectImagePlaceholder('이미지 없음');
const LOCATION_PLACEHOLDER = '—';
const GRADE_PLACEHOLDER = '-'; // FacilityGradeBadge의 미분류 표기와 동일한 문자('-')로 맞춘다

// 좌측 하자 이미지 패널 — 이미지+배지, 하단 원본/오버레이/회차비교 탭, 위치 정보(dev-04-02, #489).
// "원본"은 defect.imageUrl 단독, "오버레이"는 실 탐지 bbox(0~1 정규화 좌표, backend DefectResponse.
// bboxX/Y/W/H) 위치에 absolute box를 얹는다(#1369 — 이전엔 좌표를 무시한 고정 SVG라 모든 하자가
// 항상 같은 위치에 마킹됐다). bbox 없으면(미탐지/구버전 데이터) 박스를 그리지 않는다 —
// defect 기능의 DefectImageViewer.tsx와 동일한 hasBbox 가드 패턴(feature 간 직접 import 금지라
// 패턴만 복제).
// "회차비교" 탭은 별도 화면(/facilities/:id/compare)으로 이동하는 링크라 activeTab 상태에는 포함하지 않는다.
export function FacilityDefectImagePanel({ defect, onCompareClick }: Props) {
  const [activeTab, setActiveTab] = useState<ImageTab>('overlay');
  const hasBbox =
    defect.bboxX != null && defect.bboxY != null && defect.bboxW != null && defect.bboxH != null;

  return (
    <div className="flex flex-col gap-3">
      <div className="relative overflow-hidden rounded-2xl border border-border">
        <img
          src={defect.imageUrl ?? NO_IMAGE_PLACEHOLDER}
          alt={`${defect.defectType} 하자 이미지`}
          className="aspect-[4/3] w-full object-cover"
        />
        {activeTab === 'overlay' && hasBbox && (
          <div
            aria-label="AI 감지 영역"
            className="absolute border-2 border-red-500 bg-red-500/20"
            style={{
              top: `${(defect.bboxY as number) * 100}%`,
              left: `${(defect.bboxX as number) * 100}%`,
              width: `${(defect.bboxW as number) * 100}%`,
              height: `${(defect.bboxH as number) * 100}%`,
            }}
          />
        )}
        <span className="absolute left-3 top-3 rounded-full bg-black/65 px-2.5 py-1 text-xs font-bold text-white">
          {defect.defectType} · {defect.grade ?? GRADE_PLACEHOLDER} · 신뢰도 {defect.confidencePercent}%
        </span>
      </div>

      <div className="flex gap-2" role="tablist" aria-label="하자 이미지 보기 방식">
        {IMAGE_TABS.map((tab) => (
          <button
            key={tab}
            type="button"
            role="tab"
            aria-selected={activeTab === tab}
            onClick={() => setActiveTab(tab)}
            className={`rounded-full px-4 py-1.5 text-sm font-semibold ${
              activeTab === tab
                ? 'bg-heading text-surface'
                : 'border border-border bg-surface text-text-default'
            }`}
          >
            {TAB_LABEL[tab]}
          </button>
        ))}
        <button
          type="button"
          role="tab"
          aria-selected={false}
          onClick={onCompareClick}
          className="rounded-full border border-border bg-surface px-4 py-1.5 text-sm font-semibold text-text-default"
        >
          회차비교
        </button>
      </div>

      <div className="flex flex-col gap-2">
        <h3 className="m-0 text-sm font-bold text-heading">위치 정보</h3>
        <div className="flex items-center gap-2 rounded-xl bg-surface-muted px-4 py-3">
          <span
            aria-hidden="true"
            className="inline-flex h-8 w-8 items-center justify-center rounded-full bg-surface text-heading"
          >
            📍
          </span>
          <span className="text-sm font-semibold text-heading">
            {defect.location ?? LOCATION_PLACEHOLDER}
          </span>
        </div>
      </div>
    </div>
  );
}