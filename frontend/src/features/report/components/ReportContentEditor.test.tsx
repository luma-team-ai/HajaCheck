// @vitest-environment jsdom
import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ReportContentEditor } from './ReportContentEditor';
import type { ReportContent } from '../types';

afterEach(() => {
  cleanup();
});

const mockContent: ReportContent = {
  overview: {
    purpose: '점검 목적',
    facility_summary: '시설물 개요',
    scope: '점검 범위',
  },
  summary: {
    overall_opinion: '종합 의견',
    total_count: 1,
    count_by_grade: { A: 1 },
    key_findings: ['발견 1'],
  },
  detail: {
    items: [
      {
        defect_type: '균열',
        location: '1층 외벽',
        severity_grade: 'A',
        description: '외벽 마감 균열',
        cause: '건조 수축',
      },
    ],
  },
  recommendation: {
    items: [
      {
        target: '1층 외벽',
        method: '보수',
        priority: '상',
        legal_basis: '건축물관리법 제10조',
        legal_basis_verified: true,
      },
    ],
    monitoring_points: ['균열 진행 여부'],
  },
};

describe('ReportContentEditor', () => {
  it('renders recommendation items with (검증됨) label when legal_basis_verified is true', () => {
    render(<ReportContentEditor content={mockContent} onChange={() => {}} readOnly={false} />);
    expect(screen.getByText('법적 근거 (검증됨)')).not.toBeNull();
  });

  it('resets legal_basis_verified to false when legal_basis text is edited', () => {
    const handleChange = vi.fn();
    render(<ReportContentEditor content={mockContent} onChange={handleChange} readOnly={false} />);

    const textarea = screen.getByDisplayValue('건축물관리법 제10조');
    fireEvent.change(textarea, { target: { value: '건축물관리법 제12조' } });

    expect(handleChange).toHaveBeenCalledWith(
      expect.objectContaining({
        recommendation: expect.objectContaining({
          items: [
            expect.objectContaining({
              legal_basis: '건축물관리법 제12조',
              legal_basis_verified: false,
            }),
          ],
        }),
      }),
    );
  });

  it('renders narrative fields as non-resizable editable text inputs until finalized', () => {
    render(<ReportContentEditor content={mockContent} onChange={() => {}} readOnly={false} />);

    const purposeTextarea = screen.getByLabelText('점검 목적') as HTMLTextAreaElement;
    expect(purposeTextarea.readOnly).toBe(false);
    expect(purposeTextarea.className).toContain('resize-none');
    expect(purposeTextarea.className).not.toContain('resize-y');
  });

  it('서식 섹션 추가 메뉴는 버튼 아래가 아니라 위쪽으로 펼쳐 선택성을 확보한다', () => {
    render(<ReportContentEditor content={mockContent} onChange={() => {}} readOnly={false} />);

    fireEvent.click(screen.getByRole('button', { name: '+ 서식 섹션 추가' }));

    const menuItem = screen.getByRole('button', { name: '안전성평가 결과' });
    const menu = menuItem.parentElement as HTMLElement;
    expect(menu.className).toContain('bottom-full');
    expect(menu.className).not.toContain('top-full');
  });

  it('사진 섹션 제목은 DnD 헤더에서만 한 번 렌더링한다', () => {
    render(<ReportContentEditor content={mockContent} onChange={() => {}} readOnly={false} />);

    expect(screen.getAllByText('부위별 사진')).toHaveLength(1);
  });
});
