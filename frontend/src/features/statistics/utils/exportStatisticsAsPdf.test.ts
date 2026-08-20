// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mockAddImage = vi.fn();
const mockAddPage = vi.fn();
const mockSave = vi.fn();
// A4 두 변의 mm 길이 — exportStatisticsAsPdf.ts의 A4_SHORT_SIDE_MM/A4_LONG_SIDE_MM과 동기화된 상수.
const A4_SHORT_SIDE_MM = 210;
const A4_LONG_SIDE_MM = 297;
// 여백(10mm) — exportStatisticsAsPdf.ts의 MARGIN_MM=10과 동기화된 상수.
const MARGIN_MM = 10;

// jsPDF 생성자에 넘어온 orientation을 기억해뒀다가 그 방향에 맞는 페이지 크기를 반환하는 목.
// #1732부터는 캡처 비율에 따라 orientation을 landscape/portrait 중 골라 문서를 만들므로,
// 예전처럼 페이지 크기를 landscape로 하드코딩하면 portrait 케이스를 검증할 수 없다.
let lastConstructedOrientation: 'landscape' | 'portrait' | undefined;

class MockJsPDF {
  addImage = mockAddImage;
  addPage = mockAddPage;
  save = mockSave;
  internal: { pageSize: { getWidth: () => number; getHeight: () => number } };

  constructor(options: { orientation: 'landscape' | 'portrait' }) {
    lastConstructedOrientation = options.orientation;
    const pageWidth = options.orientation === 'landscape' ? A4_LONG_SIDE_MM : A4_SHORT_SIDE_MM;
    const pageHeight = options.orientation === 'landscape' ? A4_SHORT_SIDE_MM : A4_LONG_SIDE_MM;
    this.internal = {
      pageSize: {
        getWidth: () => pageWidth,
        getHeight: () => pageHeight,
      },
    };
  }
}

vi.mock('jspdf', () => ({
  default: MockJsPDF,
}));

// html2canvas-pro가 반환하는 캡처 캔버스 자체를 흉내내는 목 — width/height와 toDataURL만
// 있으면 된다(#1732부터는 페이지를 자르지 않으므로 drawImage/2D 컨텍스트 목이 더 이상 필요 없다).
const mockHtml2CanvasCall = vi.fn();
vi.mock('html2canvas-pro', () => ({
  default: (...args: unknown[]) => mockHtml2CanvasCall(...args),
}));

const mockToDataURL = vi.fn().mockReturnValue('data:image/png;base64,capture');

function mockHtml2Canvas(canvasWidth: number, canvasHeight: number) {
  mockHtml2CanvasCall.mockResolvedValue({
    width: canvasWidth,
    height: canvasHeight,
    toDataURL: mockToDataURL,
  });
}

// exportStatisticsAsPdf.ts의 pickOrientation/scale 계산과 같은 공식으로 기대값을 구한다 —
// 좌표를 하드코딩하는 대신 공식으로 재계산해, 여백/A4 상수가 바뀌어도 테스트가 같이 맞는다.
function expectedPlacement(canvasWidth: number, canvasHeight: number, orientation: 'landscape' | 'portrait') {
  const pageWidth = orientation === 'landscape' ? A4_LONG_SIDE_MM : A4_SHORT_SIDE_MM;
  const pageHeight = orientation === 'landscape' ? A4_SHORT_SIDE_MM : A4_LONG_SIDE_MM;
  const scale = Math.min((pageWidth - MARGIN_MM * 2) / canvasWidth, (pageHeight - MARGIN_MM * 2) / canvasHeight);
  const width = canvasWidth * scale;
  const height = canvasHeight * scale;
  return {
    pageWidth,
    pageHeight,
    width,
    height,
    x: (pageWidth - width) / 2,
    y: (pageHeight - height) / 2,
  };
}

describe('exportStatisticsAsPdf', () => {
  beforeEach(() => {
    mockAddImage.mockClear();
    mockAddPage.mockClear();
    mockSave.mockClear();
    mockHtml2CanvasCall.mockReset();
    mockToDataURL.mockClear();
    lastConstructedOrientation = undefined;
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('세로로 긴 캡처(1195x1216)는 portrait 문서로 한 페이지에 가운데 정렬돼 그려진다', async () => {
    mockHtml2Canvas(1195, 1216);
    const { exportStatisticsAsPdf: exportFn } = await import('./exportStatisticsAsPdf');

    await exportFn(document.createElement('div'));

    expect(lastConstructedOrientation).toBe('portrait');
    expect(mockAddPage).not.toHaveBeenCalled();
    expect(mockAddImage).toHaveBeenCalledTimes(1);

    const expected = expectedPlacement(1195, 1216, 'portrait');
    const [dataUrl, format, x, y, width, height] = mockAddImage.mock.calls[0];
    expect(dataUrl).toBe('data:image/png;base64,capture');
    expect(format).toBe('PNG');
    expect(x).toBeCloseTo(expected.x, 5);
    expect(y).toBeCloseTo(expected.y, 5);
    expect(width).toBeCloseTo(expected.width, 5);
    expect(height).toBeCloseTo(expected.height, 5);
    // 콘텐츠 영역(190x277) 안에 들어가고, 캡처 원본 비율(가로/세로)이 유지된다.
    expect(width).toBeLessThanOrEqual(expected.pageWidth - MARGIN_MM * 2 + 1e-6);
    expect(height).toBeLessThanOrEqual(expected.pageHeight - MARGIN_MM * 2 + 1e-6);
    expect(width / height).toBeCloseTo(1195 / 1216, 5);

    expect(mockSave).toHaveBeenCalledTimes(1);
    expect(mockSave.mock.calls[0][0]).toMatch(/^통계리포트_\d{8}\.pdf$/);
  });

  it('가로로 긴 캡처(1973x1216)는 landscape 문서로 한 페이지에 가운데 정렬돼 그려진다', async () => {
    mockHtml2Canvas(1973, 1216);
    const { exportStatisticsAsPdf: exportFn } = await import('./exportStatisticsAsPdf');

    await exportFn(document.createElement('div'));

    expect(lastConstructedOrientation).toBe('landscape');
    expect(mockAddPage).not.toHaveBeenCalled();
    expect(mockAddImage).toHaveBeenCalledTimes(1);

    const expected = expectedPlacement(1973, 1216, 'landscape');
    const [, , x, y, width, height] = mockAddImage.mock.calls[0];
    expect(x).toBeCloseTo(expected.x, 5);
    expect(y).toBeCloseTo(expected.y, 5);
    expect(width).toBeCloseTo(expected.width, 5);
    expect(height).toBeCloseTo(expected.height, 5);
    expect(width / height).toBeCloseTo(1973 / 1216, 5);
  });

  it('아주 긴 캡처(1000x5000)여도 항상 1페이지로 그려지고 콘텐츠 높이 이내로 축소된다', async () => {
    mockHtml2Canvas(1000, 5000);
    const { exportStatisticsAsPdf: exportFn } = await import('./exportStatisticsAsPdf');

    await exportFn(document.createElement('div'));

    expect(mockAddPage).not.toHaveBeenCalled();
    expect(mockAddImage).toHaveBeenCalledTimes(1);

    const orientation = lastConstructedOrientation as 'landscape' | 'portrait';
    const expected = expectedPlacement(1000, 5000, orientation);
    const [, , , , width, height] = mockAddImage.mock.calls[0];
    expect(width).toBeCloseTo(expected.width, 5);
    expect(height).toBeCloseTo(expected.height, 5);
    expect(height).toBeLessThanOrEqual(expected.pageHeight - MARGIN_MM * 2 + 1e-6);
    expect(width).toBeLessThanOrEqual(expected.pageWidth - MARGIN_MM * 2 + 1e-6);
  });

  it('html2canvas-pro가 실패하면 에러를 그대로 전파한다', async () => {
    mockHtml2CanvasCall.mockRejectedValue(new Error('capture failed'));
    const { exportStatisticsAsPdf: exportFn } = await import('./exportStatisticsAsPdf');

    await expect(exportFn(document.createElement('div'))).rejects.toThrow('capture failed');
    expect(mockSave).not.toHaveBeenCalled();
  });

  it('캡처 캔버스 폭이 0이면 즉시 reject되고 PDF를 만들지 않는다', async () => {
    mockHtml2Canvas(0, 100);
    const { exportStatisticsAsPdf: exportFn } = await import('./exportStatisticsAsPdf');

    await expect(exportFn(document.createElement('div'))).rejects.toThrow(
      '화면 캡처 결과가 비어 있어 PDF를 만들 수 없습니다.',
    );
    expect(mockAddImage).not.toHaveBeenCalled();
    expect(mockAddPage).not.toHaveBeenCalled();
    expect(mockSave).not.toHaveBeenCalled();
  });

  it('캡처 캔버스 높이가 0이면 즉시 reject되고 PDF를 만들지 않는다', async () => {
    mockHtml2Canvas(100, 0);
    const { exportStatisticsAsPdf: exportFn } = await import('./exportStatisticsAsPdf');

    await expect(exportFn(document.createElement('div'))).rejects.toThrow(
      '화면 캡처 결과가 비어 있어 PDF를 만들 수 없습니다.',
    );
    expect(mockAddImage).not.toHaveBeenCalled();
    expect(mockSave).not.toHaveBeenCalled();
  });

  it('data-export-ignore="true"가 붙은 요소를 onclone에서 visibility:hidden 처리하는 콜백을 html2canvas-pro에 넘긴다', async () => {
    mockHtml2Canvas(1195, 1216);
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

  it('onclone이 클론 head에 letter-spacing:normal 무력화 스타일을 주입한다 (#1732 — 숫자+한글 혼합 텍스트 baseline 어긋남 방지)', async () => {
    mockHtml2Canvas(1195, 1216);
    const { exportStatisticsAsPdf: exportFn } = await import('./exportStatisticsAsPdf');

    await exportFn(document.createElement('div'));

    const [, options] = mockHtml2CanvasCall.mock.calls[0] as [HTMLElement, { onclone: (doc: Document) => void }];
    const clonedDoc = document.implementation.createHTMLDocument();

    options.onclone(clonedDoc);

    const injectedStyle = clonedDoc.head.querySelector('style');
    expect(injectedStyle).not.toBeNull();
    expect(injectedStyle?.textContent).toContain('letter-spacing: normal');
    expect(injectedStyle?.textContent).toContain('!important');
  });
});
