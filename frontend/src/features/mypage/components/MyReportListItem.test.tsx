// @vitest-environment jsdom
// MyReportListItem(#1464) — pdfUrl 유무에 따른 다운로드 버튼 활성/비활성과 클릭 시 실제
// fetch → blob → <a download> 흐름을 검증한다.
import { MemoryRouter } from 'react-router-dom';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import type { MyReportCard } from '../types';
import { MyReportListItem } from './MyReportListItem';

const server = setupServer();

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

const baseReport: MyReportCard = {
  id: 1,
  inspectionId: 10,
  facilityName: '강남 오피스타워 A동',
  roundNo: 3,
  issuedAt: '2024-03-16T10:22:00',
  fileSizeBytes: 1258291,
  gradeDots: ['RED', 'GREEN'],
  pdfUrl: '/api/reports/1/pdf/mock.pdf',
};

function renderItem(report: MyReportCard): void {
  render(
    <MemoryRouter>
      <MyReportListItem report={report} />
    </MemoryRouter>,
  );
}

describe('MyReportListItem', () => {
  it('pdfUrl이 있으면 다운로드 버튼이 활성화되고 클릭 시 PDF를 내려받는다', async () => {
    server.use(
      http.get('/api/reports/1/pdf/mock.pdf', () => HttpResponse.arrayBuffer(new ArrayBuffer(8))),
    );
    const createObjectURLSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:http://localhost/fake');
    const revokeObjectURLSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});

    renderItem(baseReport);

    const button = screen.getByRole('button', { name: /다운로드/ });
    expect(button).toHaveProperty('disabled', false);

    fireEvent.click(button);

    await waitFor(() => {
      expect(createObjectURLSpy).toHaveBeenCalled();
    });
    expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:http://localhost/fake');

    createObjectURLSpy.mockRestore();
    revokeObjectURLSpy.mockRestore();
  });

  it('pdfUrl이 null이면 다운로드 버튼이 비활성 상태를 유지한다', () => {
    renderItem({ ...baseReport, pdfUrl: null });

    const button = screen.getByRole('button', { name: /다운로드/ });
    expect(button).toHaveProperty('disabled', true);
    expect(button.getAttribute('title')).toBe('다운로드 가능한 PDF가 없습니다');
  });
});
