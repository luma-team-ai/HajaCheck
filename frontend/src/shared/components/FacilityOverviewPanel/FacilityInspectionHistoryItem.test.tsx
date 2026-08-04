// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { FacilityOverviewHistoryItem } from './FacilityInspectionHistoryItem';
import { FacilityInspectionHistoryItem } from './FacilityInspectionHistoryItem';

afterEach(cleanup);

const baseItem: FacilityOverviewHistoryItem = {
  id: 8,
  roundNo: 8,
  inspectionDate: '2026-06-21',
  inspectorName: '이엔지',
  status: '검수완료',
  imageCount: 214,
  defectGradeBreakdown: [{ grade: 'C', count: 8 }],
};

describe('FacilityInspectionHistoryItem', () => {
  // #1549 회귀고정 — 이전엔 회색 <div>(src 없음)만 항상 렌더링돼 실제 사진이 절대 안 보였다.
  it('썸네일 URL이 있으면 실제 이미지를 렌더링한다(#1549)', () => {
    render(
      <FacilityInspectionHistoryItem
        item={{
          ...baseItem,
          additionalImageCount: 212,
          thumbnailUrls: ['/api/media/801/thumbnail', '/api/media/802/thumbnail'],
        }}
        expanded
      />,
    );

    const first = screen.getByRole('img', { name: '8회차 점검 사진 1' }) as HTMLImageElement;
    const second = screen.getByRole('img', { name: '8회차 점검 사진 2' }) as HTMLImageElement;
    expect(first.src).toContain('/api/media/801/thumbnail');
    expect(second.src).toContain('/api/media/802/thumbnail');
  });

  // 썸네일 URL이 없는 슬롯(방어적 케이스)은 깨진 이미지 대신 기존 회색 플레이스홀더를 유지한다.
  it('썸네일 URL이 없으면 회색 플레이스홀더로 대체한다', () => {
    render(
      <FacilityInspectionHistoryItem
        item={{ ...baseItem, additionalImageCount: 212, thumbnailUrls: [] }}
        expanded
      />,
    );

    expect(screen.queryByRole('img', { name: /점검 사진/ })).toBeNull();
  });

  // #1549 회귀고정 — 이전엔 "+N"이 onClick 없는 <div>라 클릭해도 아무 반응이 없었다.
  it('"+N" 클릭 시 onViewMoreClick을 회차 점검 id와 함께 호출한다(#1549)', () => {
    const handleViewMore = vi.fn();
    render(
      <FacilityInspectionHistoryItem
        item={{ ...baseItem, additionalImageCount: 212, thumbnailUrls: [] }}
        expanded
        onViewMoreClick={handleViewMore}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '+212' }));

    expect(handleViewMore).toHaveBeenCalledWith(8);
  });

  it('imageCount가 0이면 썸네일 행 자체를 렌더링하지 않는다', () => {
    render(<FacilityInspectionHistoryItem item={{ ...baseItem, imageCount: 0 }} expanded />);

    expect(screen.queryByRole('img', { name: /점검 사진/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^\+/ })).toBeNull();
  });

  // 회귀고정(#1575) — 미리보기 2장(THUMBNAIL_PREVIEW_COUNT) 이하인 회차는 useFacilityInspectionOverview.ts
  // 매퍼가 additionalImageCount를 undefined로 둔다. 과거엔 이게 사진 행 전체를 가리는 조건과
  // 하나로 묶여 있어, 이미지가 1~2장뿐인 회차는 실제 사진이 있어도 안 보이는 버그가 있었다
  // ("서초 브릿지" 이미지 1장 사례로 발견).
  it('이미지가 1장뿐이라 additionalImageCount가 없어도 그 1장은 렌더링한다(#1575)', () => {
    render(
      <FacilityInspectionHistoryItem
        item={{ ...baseItem, imageCount: 1, thumbnailUrls: ['/api/media/901/thumbnail'] }}
        expanded
      />,
    );

    const photo = screen.getByRole('img', { name: '8회차 점검 사진 1' }) as HTMLImageElement;
    expect(photo.src).toContain('/api/media/901/thumbnail');
    expect(screen.queryByRole('button', { name: /^\+/ })).toBeNull();
  });
});
