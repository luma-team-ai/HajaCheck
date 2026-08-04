// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const mockAddImage = vi.fn();
const mockAddPage = vi.fn();
const mockSave = vi.fn();
const PAGE_WIDTH = 210;
const PAGE_HEIGHT = 297;

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

const mockToDataURL = vi.fn().mockReturnValue('data:image/png;base64,fake');

function mockHtml2Canvas(canvasWidth: number, canvasHeight: number) {
  vi.doMock('html2canvas', () => ({
    default: vi.fn().mockResolvedValue({
      width: canvasWidth,
      height: canvasHeight,
      toDataURL: mockToDataURL,
    }),
  }));
}

describe('exportComparisonReportAsPdf', () => {
  beforeEach(() => {
    vi.resetModules();
    mockAddImage.mockClear();
    mockAddPage.mockClear();
    mockSave.mockClear();
    mockToDataURL.mockClear();
  });

  afterEach(() => {
    vi.doUnmock('html2canvas');
  });

  it('캡처 이미지가 한 페이지 높이 이내면 페이지를 추가하지 않는다', async () => {
    // 캡처 비율(1000:800)을 A4 폭(210mm)에 맞추면 이미지 높이는 168mm로 297mm 한 페이지 이내다.
    mockHtml2Canvas(1000, 800);
    const { exportComparisonReportAsPdf: exportFn } = await import('./exportComparisonReportAsPdf');

    await exportFn(document.createElement('div'), '1');

    expect(mockAddImage).toHaveBeenCalledTimes(1);
    expect(mockAddImage).toHaveBeenCalledWith('data:image/png;base64,fake', 'PNG', 0, 0, PAGE_WIDTH, expect.any(Number));
    expect(mockAddPage).not.toHaveBeenCalled();
    expect(mockSave).toHaveBeenCalledTimes(1);
    expect(mockSave.mock.calls[0][0]).toMatch(/^회차간비교_1_\d{8}\.pdf$/);
  });

  it('캡처 이미지가 한 페이지보다 길면 남은 높이만큼 페이지를 나눠 그린다', async () => {
    // 캡처 비율(1000:2500)을 A4 폭에 맞추면 이미지 높이는 525mm ≈ 297mm 페이지 2개 분량 → addPage 1회.
    mockHtml2Canvas(1000, 2500);
    const { exportComparisonReportAsPdf: exportFn } = await import('./exportComparisonReportAsPdf');

    await exportFn(document.createElement('div'), '2');

    expect(mockAddPage).toHaveBeenCalledTimes(1);
    expect(mockAddImage).toHaveBeenCalledTimes(2);

    // 같은 이미지를 두 번째 페이지에 그릴 때는 이미 보여준 첫 페이지 분량(pageHeight)만큼
    // 위로 밀어 그린다 — 페이지는 자기 영역 밖(y<0)을 그리지 않으므로 다음 구간만 보인다.
    const [, , , firstY] = mockAddImage.mock.calls[0];
    const [, , , secondY] = mockAddImage.mock.calls[1];
    expect(firstY).toBe(0);
    expect(secondY).toBe(-PAGE_HEIGHT);
  });
});
