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
});
