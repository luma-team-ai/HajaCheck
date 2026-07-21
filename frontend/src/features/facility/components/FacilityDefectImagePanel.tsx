import { useState } from 'react';
import { buildDefectOverlayMarkingImage } from '../utils/defectImagePlaceholder';
import type { FacilityDefectDetail } from '../types';

type ImageTab = 'original' | 'overlay';

type Props = {
  defect: FacilityDefectDetail;
  onCompareClick: () => void;
};

const TAB_LABEL: Record<ImageTab, string> = { original: '원본', overlay: '오버레이' };
const IMAGE_TABS: ImageTab[] = ['original', 'overlay'];
const OVERLAY_MARKING_URL = buildDefectOverlayMarkingImage();

// 좌측 하자 이미지 패널 — 이미지+배지+확대 아이콘, 하단 원본/오버레이/회차비교 탭, 위치 정보(dev-04-02, #489).
// "원본"은 defect.imageUrl 단독, "오버레이"는 같은 이미지 위에 마킹 레이어(overlay marking)를 absolute로
// 추가 렌더한다 — 실 하자 사진 자산이 없어 이미지 2벌 대신 CSS 레이어 토글로 구현했다.
// "회차비교" 탭은 별도 화면(/facilities/:id/compare)으로 이동하는 링크라 activeTab 상태에는 포함하지 않는다.
export function FacilityDefectImagePanel({ defect, onCompareClick }: Props) {
  const [activeTab, setActiveTab] = useState<ImageTab>('overlay');

  return (
    <div className="flex flex-col gap-3">
      <div className="relative overflow-hidden rounded-2xl border border-border">
        <img
          src={defect.imageUrl}
          alt={`${defect.defectType} 하자 이미지`}
          className="aspect-[4/3] w-full object-cover"
        />
        {activeTab === 'overlay' && (
          <img
            src={OVERLAY_MARKING_URL}
            alt=""
            aria-hidden="true"
            className="absolute inset-0 aspect-[4/3] w-full object-cover"
          />
        )}
        <span className="absolute left-3 top-3 rounded-full bg-black/65 px-2.5 py-1 text-xs font-bold text-white">
          {defect.defectType} · {defect.grade} · 신뢰도 {defect.confidencePercent}%
        </span>
        <button
          type="button"
          aria-label="이미지 확대"
          className="absolute bottom-3 right-3 inline-flex h-9 w-9 items-center justify-center rounded-full bg-black/60 text-white"
        >
          <span aria-hidden="true">🔍</span>
        </button>
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
          <span className="text-sm font-semibold text-heading">{defect.location}</span>
        </div>
      </div>
    </div>
  );
}