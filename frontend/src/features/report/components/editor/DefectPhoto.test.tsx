// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Defect } from '../../../inspection/types';
import { DefectPhoto, groupDefectsByMedia } from './DefectPhoto';
import { PhotosSectionPreview } from './PhotosSectionPreview';

/**
 * #1333 — 리포트에 사진만 나오고 탐지 하자 박스가 안 그려지던 회귀, 그리고 같은 사진이 하자 수만큼
 * 중복되던 문제를 고정한다.
 */
function defect(
  id: number,
  mediaId: number | null,
  bbox: { x: number; y: number; width: number; height: number } | null,
  imageUrl = `/media/${mediaId}/thumb`,
): Defect {
  return {
    id, type: '균열', grade: 'C', status: 'DETECTED', confidence: 0.9,
    bbox, summary: '', mediaId, imageUrl,
  } as unknown as Defect;
}

const BOX_SELECTOR = 'span[aria-hidden="true"].absolute';

afterEach(() => cleanup());

describe('groupDefectsByMedia', () => {
  it('같은 사진의 하자를 한 그룹으로 묶고 첫 등장 순서를 유지한다', () => {
    const groups = groupDefectsByMedia([
      defect(1, 101, { x: 0.32, y: 0.1, width: 0.05, height: 0.71 }),
      defect(2, 101, { x: 0.55, y: 0.4, width: 0.1, height: 0.2 }),
      defect(3, 103, { x: 0.12, y: 0.6, width: 0.2, height: 0.2 }),
    ]);

    // 하자 3건 → 사진 2장(이전에는 3장으로 101이 중복됐다)
    expect(groups).toHaveLength(2);
    expect(groups[0].mediaId).toBe(101);
    expect(groups[0].defects.map((d) => d.id)).toEqual([1, 2]);
    expect(groups[1].mediaId).toBe(103);
    expect(groups[1].defects.map((d) => d.id)).toEqual([3]);
  });

  it('mediaId가 없는 하자는 서로 묶지 않는다', () => {
    const groups = groupDefectsByMedia([
      defect(1, null, { x: 0.1, y: 0.1, width: 0.1, height: 0.1 }, '/manual/1'),
      defect(2, null, { x: 0.2, y: 0.2, width: 0.1, height: 0.1 }, '/manual/2'),
    ]);

    expect(groups).toHaveLength(2);
    expect(groups.every((group) => group.mediaId === null)).toBe(true);
  });
});

describe('DefectPhoto', () => {
  it('정규화 좌표를 %로 환산해 박스를 얹는다', () => {
    const { container } = render(
      <DefectPhoto
        group={{ mediaId: 101, imageUrl: '/media/101/thumb', defects: [defect(1, 101, { x: 0.25, y: 0.5, width: 0.1, height: 0.2 })] }}
        alt="사진"
        fallback={<div>이미지 없음</div>}
      />,
    );

    fireEvent.load(screen.getByRole('img'));
    const box = container.querySelector(BOX_SELECTOR) as HTMLElement;
    expect(box.style.left).toBe('25%');
    expect(box.style.top).toBe('50%');
    expect(box.style.width).toBe('10%');
    expect(box.style.height).toBe('20%');
  });

  it('bbox가 없거나 폭·높이가 0이면 박스를 만들지 않는다', () => {
    const { container } = render(
      <DefectPhoto
        group={{
          mediaId: 101,
          imageUrl: '/media/101/thumb',
          defects: [defect(1, 101, null), defect(2, 101, { x: 0.1, y: 0.1, width: 0, height: 0.2 })],
        }}
        alt="사진"
        fallback={<div>이미지 없음</div>}
      />,
    );

    fireEvent.load(screen.getByRole('img'));
    expect(screen.getByRole('img')).toBeTruthy();
    expect(container.querySelectorAll(BOX_SELECTOR)).toHaveLength(0);
  });

  it('이미지가 없으면 fallback을 그린다', () => {
    render(
      <DefectPhoto
        group={{ mediaId: 101, imageUrl: null, defects: [] }}
        alt="사진"
        fallback={<div>이미지 없음</div>}
      />,
    );

    expect(screen.getByText('이미지 없음')).toBeTruthy();
    expect(screen.queryByRole('img')).toBeNull();
  });

  // 박스는 이미지를 감싼 w-fit 컨테이너 기준 %로 배치된다 — 이미지에 object-cover(크롭)를 쓰면
  // 좌표가 어긋나므로, 크롭 클래스가 다시 끼어드는 회귀를 여기서 막는다.
  it('이미지에 object-cover를 쓰지 않는다', () => {
    const { container } = render(
      <DefectPhoto
        group={{ mediaId: 101, imageUrl: '/media/101/thumb', defects: [] }}
        alt="사진"
        imageClassName="w-full rounded-md"
        fallback={<div>이미지 없음</div>}
      />,
    );

    expect(container.querySelector('img')?.className).not.toContain('object-cover');
  });

  it('이미지 로드 전에는 로딩 대지만 표시하고 bbox를 그리지 않는다', () => {
    const { container } = render(
      <DefectPhoto
        group={{ mediaId: 101, imageUrl: '/media/101/thumb', defects: [defect(1, 101, { x: 0.25, y: 0.5, width: 0.1, height: 0.2 })] }}
        alt="사진"
        fallback={<div>이미지 없음</div>}
      />,
    );

    expect(screen.getByRole('status', { name: '사진 로딩 중' })).toBeTruthy();
    expect(container.querySelectorAll(BOX_SELECTOR)).toHaveLength(0);

    fireEvent.load(screen.getByRole('img'));

    expect(screen.queryByRole('status', { name: '사진 로딩 중' })).toBeNull();
    expect(container.querySelectorAll(BOX_SELECTOR)).toHaveLength(1);
  });

  it('이미지가 마운트 시점에 이미 캐시되어 complete=true, naturalWidth>0이면 onLoad 없이도 loaded 상태가 된다', () => {
    const originalComplete = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'complete');
    const originalNaturalWidth = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'naturalWidth');

    Object.defineProperty(HTMLImageElement.prototype, 'complete', {
      configurable: true,
      get() {
        return true;
      },
    });
    Object.defineProperty(HTMLImageElement.prototype, 'naturalWidth', {
      configurable: true,
      get() {
        return 800;
      },
    });

    try {
      const { container } = render(
        <DefectPhoto
          group={{ mediaId: 101, imageUrl: '/media/101/thumb', defects: [defect(1, 101, { x: 0.25, y: 0.5, width: 0.1, height: 0.2 })] }}
          alt="사진"
          fallback={<div>이미지 없음</div>}
        />,
      );

      // fireEvent.load를 호출하지 않아도 이미지가 캐시되어 로딩 대지가 사라지고 bbox가 배치된다
      expect(screen.queryByRole('status', { name: '사진 로딩 중' })).toBeNull();
      expect(container.querySelectorAll(BOX_SELECTOR)).toHaveLength(1);
    } finally {
      if (originalComplete) Object.defineProperty(HTMLImageElement.prototype, 'complete', originalComplete);
      if (originalNaturalWidth) Object.defineProperty(HTMLImageElement.prototype, 'naturalWidth', originalNaturalWidth);
    }
  });
});

describe('PhotosSectionPreview', () => {
  it('사진 1장에 하자 여러 건을 박스로 함께 표시한다', () => {
    const groups = groupDefectsByMedia([
      defect(1, 101, { x: 0.32, y: 0.1, width: 0.05, height: 0.71 }),
      defect(2, 101, { x: 0.55, y: 0.4, width: 0.1, height: 0.2 }),
      defect(3, 103, { x: 0.12, y: 0.6, width: 0.2, height: 0.2 }),
    ]);
    const { container } = render(<PhotosSectionPreview photoGroups={groups} />);

    screen.getAllByRole('img').forEach((img) => fireEvent.load(img));
    // 사진은 2장, 박스는 3개
    expect(screen.getAllByRole('img')).toHaveLength(2);
    expect(container.querySelectorAll(BOX_SELECTOR)).toHaveLength(3);
    expect(screen.getByText(/2장 · 하자 3건/)).toBeTruthy();
    expect(screen.getByText('하자 2건')).toBeTruthy();
  });

  it('사진이 없으면 안내 문구를 보여준다', () => {
    render(<PhotosSectionPreview photoGroups={[]} />);
    expect(screen.getByText('점검 촬영 축소본이 없습니다.')).toBeTruthy();
  });

  it('하자가 1건인 사진에도 하자 1건 문구를 표기한다', () => {
    const groups = groupDefectsByMedia([
      defect(1, 101, { x: 0.32, y: 0.1, width: 0.05, height: 0.71 }),
    ]);
    render(<PhotosSectionPreview photoGroups={groups} />);

    fireEvent.load(screen.getByRole('img'));
    expect(screen.getByText('하자 1건')).toBeTruthy();
  });

  it('결함 사진이 10장 이상이면 9장 단위로 페이지네이션한다', () => {
    const groups = Array.from({ length: 10 }, (_, index) =>
      groupDefectsByMedia([
        defect(index + 1, index + 1, { x: 0.1, y: 0.1, width: 0.1, height: 0.1 }),
      ])[0],
    );
    render(<PhotosSectionPreview photoGroups={groups} />);

    expect(screen.getAllByRole('img')).toHaveLength(9);
    expect(screen.getByText('1')).toBeTruthy();
    expect(screen.getByText('/ 2')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '결함 사진 다음 페이지' }));

    expect(screen.getAllByRole('img')).toHaveLength(1);
    expect(screen.getByText('2')).toBeTruthy();
    expect(screen.getByRole('button', { name: '결함 사진 다음 페이지' }).hasAttribute('disabled')).toBe(true);
  });
});
