// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mockAddImage = vi.fn();
const mockAddPage = vi.fn();
const mockSave = vi.fn();
// A4 가로(landscape) 기준 — exportStatisticsAsPdf가 orientation:'landscape'로 문서를 만든다.
const PAGE_WIDTH = 297;
const PAGE_HEIGHT = 210;
// 여백(10mm) 제외 콘텐츠 영역 — exportStatisticsAsPdf.ts의 MARGIN_MM=10과 동기화된 상수.
const MARGIN_MM = 10;
const CONTENT_WIDTH_MM = PAGE_WIDTH - MARGIN_MM * 2; // 277
const CONTENT_HEIGHT_MM = PAGE_HEIGHT - MARGIN_MM * 2; // 190

class MockJsPDF {
  internal = {
    pageSize: {
      getWidth: () => PAGE_WIDTH,
      getHeight: () => PAGE_HEIGHT,
    },
  };
  addImage = mockAddImage;
  addPage = mockAddPage;
  save = mockSave;
}

vi.mock('jspdf', () => ({
  default: MockJsPDF,
}));

// html2canvas-pro가 반환하는 캡처 캔버스 자체를 흉내내는 목 — 이 함수 안에서 진짜로 필요한 건
// width/height뿐이다(내부에서 source.width만 읽고, drawImage 호출은 아래 mockDrawImage로 대체된다).
const mockHtml2CanvasCall = vi.fn();
vi.mock('html2canvas-pro', () => ({
  default: (...args: unknown[]) => mockHtml2CanvasCall(...args),
}));

function mockHtml2Canvas(canvasWidth: number, canvasHeight: number) {
  mockHtml2CanvasCall.mockResolvedValue({ width: canvasWidth, height: canvasHeight });
}

// 페이지 단위로 캔버스를 잘라 임시 <canvas>에 그리는 sliceCanvasToDataUrl은 jsdom에 없는
// 2D 캔버스 컨텍스트가 필요하므로, HTMLCanvasElement.prototype 레벨에서 통째로 목한다.
const mockDrawImage = vi.fn();
const mockSliceToDataURL = vi.fn().mockReturnValue('data:image/png;base64,slice');

describe('exportStatisticsAsPdf', () => {
  beforeEach(() => {
    mockAddImage.mockClear();
    mockAddPage.mockClear();
    mockSave.mockClear();
    mockHtml2CanvasCall.mockReset();
    mockDrawImage.mockClear();
    mockSliceToDataURL.mockClear();
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({
      drawImage: mockDrawImage,
    } as unknown as CanvasRenderingContext2D);
    vi.spyOn(HTMLCanvasElement.prototype, 'toDataURL').mockImplementation(mockSliceToDataURL);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('캡처 이미지가 한 페이지 콘텐츠 영역 높이 이내면 페이지를 추가하지 않고, 10mm 여백 좌표로 그린다', async () => {
    // 캔버스 폭을 콘텐츠 폭(277mm)과 같게 잡아 pxPerMm=1로 계산을 단순화한다.
    // 높이(150px)가 콘텐츠 높이(190mm)보다 작아 한 페이지에 다 들어간다.
    mockHtml2Canvas(CONTENT_WIDTH_MM, 150);
    const { exportStatisticsAsPdf: exportFn } = await import('./exportStatisticsAsPdf');

    await exportFn(document.createElement('div'));

    expect(mockAddPage).not.toHaveBeenCalled();
    expect(mockAddImage).toHaveBeenCalledTimes(1);
    expect(mockAddImage).toHaveBeenCalledWith(
      'data:image/png;base64,slice',
      'PNG',
      MARGIN_MM,
      MARGIN_MM,
      CONTENT_WIDTH_MM,
      150,
    );
    expect(mockSave).toHaveBeenCalledTimes(1);
    expect(mockSave.mock.calls[0][0]).toMatch(/^통계리포트_\d{8}\.pdf$/);
  });

  it('콘텐츠가 한 페이지보다 길면 콘텐츠 영역 높이만큼 캔버스를 잘라 페이지를 나누고, 마지막 조각은 남은 높이만큼만 그린다', async () => {
    // pxPerMm=1이 되도록 폭을 277로 고정 — 콘텐츠 높이(190)+150 = 340px:
    // 1페이지는 190px(콘텐츠 영역 가득), 2페이지는 남은 150px만(늘려 그리지 않음).
    mockHtml2Canvas(CONTENT_WIDTH_MM, CONTENT_HEIGHT_MM + 150);
    const { exportStatisticsAsPdf: exportFn } = await import('./exportStatisticsAsPdf');

    await exportFn(document.createElement('div'));

    expect(mockAddPage).toHaveBeenCalledTimes(1);
    expect(mockAddImage).toHaveBeenCalledTimes(2);
    expect(mockAddImage).toHaveBeenNthCalledWith(
      1,
      'data:image/png;base64,slice',
      'PNG',
      MARGIN_MM,
      MARGIN_MM,
      CONTENT_WIDTH_MM,
      CONTENT_HEIGHT_MM,
    );
    expect(mockAddImage).toHaveBeenNthCalledWith(
      2,
      'data:image/png;base64,slice',
      'PNG',
      MARGIN_MM,
      MARGIN_MM,
      CONTENT_WIDTH_MM,
      150,
    );

    // drawImage로 캔버스를 자르는 소스 y 오프셋도 겹치거나 비지 않고 이어져야 한다.
    expect(mockDrawImage).toHaveBeenCalledTimes(2);
    // drawImage(source, sx, sy, sWidth, sHeight, dx, dy, dWidth, dHeight) — 캡처 원본에서
    // 잘라올 세로 구간은 sy(인덱스 2)·sHeight(인덱스 4)에 실린다.
    const [, , firstSourceY, , firstSliceHeightPx] = mockDrawImage.mock.calls[0];
    const [, , secondSourceY, , secondSliceHeightPx] = mockDrawImage.mock.calls[1];
    expect(firstSourceY).toBe(0);
    expect(firstSliceHeightPx).toBe(CONTENT_HEIGHT_MM);
    expect(secondSourceY).toBe(CONTENT_HEIGHT_MM);
    expect(secondSliceHeightPx).toBe(150);
  });

  it('html2canvas-pro가 실패하면 에러를 그대로 전파한다', async () => {
    mockHtml2CanvasCall.mockRejectedValue(new Error('capture failed'));
    const { exportStatisticsAsPdf: exportFn } = await import('./exportStatisticsAsPdf');

    await expect(exportFn(document.createElement('div'))).rejects.toThrow('capture failed');
    expect(mockSave).not.toHaveBeenCalled();
  });

  it('data-export-ignore="true"가 붙은 요소를 onclone에서 visibility:hidden 처리하는 콜백을 html2canvas-pro에 넘긴다', async () => {
    mockHtml2Canvas(CONTENT_WIDTH_MM, 100);
    const { exportStatisticsAsPdf: exportFn } = await import('./exportStatisticsAsPdf');
    const node = document.createElement('div');

    await exportFn(node);

    expect(mockHtml2CanvasCall).toHaveBeenCalledTimes(1);
    const [calledNode, options] = mockHtml2CanvasCall.mock.calls[0] as [
      HTMLElement,
      { onclone: (doc: Document) => void },
    ];
    expect(calledNode).toBe(node);

    // "내보내기" 버튼(data-export-ignore="true")은 리포트에 찍히면 부자연스러우므로 캡처에서
    // 뺀다 — ignoreElements(클론에서 제거)가 아니라 onclone에서 visibility만 숨겨서, 버튼이
    // 속한 우측 정렬 flex 행의 폭이 줄어들며 남은 필터 드롭다운 위치가 화면과 달라지지
    // 않게 한다(레이아웃 재배치 방지).
    const clonedDoc = document.implementation.createHTMLDocument();
    const exportButton = clonedDoc.createElement('button');
    exportButton.dataset.exportIgnore = 'true';
    clonedDoc.body.appendChild(exportButton);
    const filterDropdown = clonedDoc.createElement('div');
    clonedDoc.body.appendChild(filterDropdown);

    options.onclone(clonedDoc);

    expect(exportButton.style.visibility).toBe('hidden');
    // 조회 조건(기간·시설물 필터)은 화면과 동일한 자리에 그대로 남아야 한다.
    expect(filterDropdown.style.visibility).not.toBe('hidden');
  });
});
