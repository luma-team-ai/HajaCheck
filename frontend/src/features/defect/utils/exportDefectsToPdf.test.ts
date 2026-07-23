// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Defect } from '../types';
import { buildDefectExportRows, exportDefectsToPdf } from './exportDefectsToPdf';

const mockAutoTable = vi.fn();
const mockSave = vi.fn();
const mockAddFileToVFS = vi.fn();
const mockAddFont = vi.fn();
const mockSetFont = vi.fn();
const mockText = vi.fn();

class MockJsPDF {
  addFileToVFS = mockAddFileToVFS;
  addFont = mockAddFont;
  setFont = mockSetFont;
  text = mockText;
  save = mockSave;
}

vi.mock('jspdf', () => ({
  default: MockJsPDF,
}));

vi.mock('jspdf-autotable', () => ({
  default: (...args: unknown[]) => mockAutoTable(...args),
}));

vi.mock('pretendard/dist/public/static/alternative/Pretendard-Regular.ttf?url', () => ({
  default: 'https://example.test/Pretendard-Regular.ttf',
}));

function makeDefect(overrides: Partial<Defect> = {}): Defect {
  return {
    id: 1,
    inspectionId: 101,
    facilityId: 1,
    facilityName: '강남 오피스타워 A동',
    facilityType: '건물',
    type: 'REBAR_EXPOSURE',
    typeLabel: '철근 노출',
    grade: 'D',
    status: 'ACTION_PENDING',
    confidence: 0.92,
    reviewed: true,
    bboxX: null,
    bboxY: null,
    bboxW: null,
    bboxH: null,
    crackWidthMm: null,
    crackLengthMm: null,
    imageUrl: null,
    createdAt: '2026-07-01T09:00:00.000Z',
    ...overrides,
  };
}

describe('buildDefectExportRows', () => {
  it('선택된 하자를 화면 표와 동일한 컬럼(ID/유형/등급/시설물/상태/발견일) 순서로 변환한다', () => {
    const rows = buildDefectExportRows([
      makeDefect({ id: 7, grade: null, status: 'DETECTED' }),
    ]);

    expect(rows).toEqual([
      ['DEF-0007', '철근 노출', '-', '강남 오피스타워 A동', '신규', '26.07.01'],
    ]);
  });

  it('여러 건을 선택하면 선택 순서대로 각 행을 만든다', () => {
    const rows = buildDefectExportRows([
      makeDefect({ id: 1, status: 'ACTION_PENDING' }),
      makeDefect({ id: 2, typeLabel: '균열', grade: 'C', status: 'RESOLVED' }),
    ]);

    expect(rows).toHaveLength(2);
    expect(rows[0][0]).toBe('DEF-0001');
    expect(rows[0][4]).toBe('조치대기');
    expect(rows[1][0]).toBe('DEF-0002');
    expect(rows[1][4]).toBe('조치완료');
  });
});

describe('exportDefectsToPdf', () => {
  beforeEach(() => {
    mockAutoTable.mockClear();
    mockSave.mockClear();
    mockAddFileToVFS.mockClear();
    mockAddFont.mockClear();
    mockSetFont.mockClear();
    mockText.mockClear();

    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        blob: () => Promise.resolve(new Blob(['fake-font-bytes'])),
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('선택된 행 데이터로 autoTable을 호출하고 오늘 날짜 파일명으로 저장한다', async () => {
    const defects = [makeDefect({ id: 3, facilityName: '한강대교 북단' })];

    await exportDefectsToPdf(defects);

    expect(mockAutoTable).toHaveBeenCalledTimes(1);
    const [, options] = mockAutoTable.mock.calls[0];
    expect(options.head).toEqual([
      ['하자 ID', '유형', '등급', '시설물', '상태', '발견일'],
    ]);
    expect(options.body).toEqual(buildDefectExportRows(defects));

    expect(mockAddFont).toHaveBeenCalledWith(
      'Pretendard-Regular.ttf',
      'Pretendard',
      'normal',
    );
    expect(mockSave).toHaveBeenCalledTimes(1);
    expect(mockSave.mock.calls[0][0]).toMatch(/^하자목록_\d{8}\.pdf$/);
  });
});
