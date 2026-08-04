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

  it('additionalImageCount가 없으면 썸네일 행 자체를 렌더링하지 않는다', () => {
    render(<FacilityInspectionHistoryItem item={baseItem} expanded />);

    expect(screen.queryByRole('img', { name: /점검 사진/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^\+/ })).toBeNull();
  });
});
