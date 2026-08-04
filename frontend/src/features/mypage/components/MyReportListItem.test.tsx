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

  // 레거시 pdfUrl(프로토콜 없는 "localhost:8080/..." 포맷, #1186/#1235)도 정규화를 거쳐
  // 같은 origin의 /api 경로로 fetch해야 한다 — code-reviewer P2 픽스(#1464).
  it('레거시 localhost 포맷 pdfUrl은 정규화된 경로로 fetch해 다운로드한다', async () => {
    let requestedPath: string | null = null;
    server.use(
      http.get('/api/reports/1/pdf/legacy.pdf', ({ request }) => {
        requestedPath = new URL(request.url).pathname;
        return HttpResponse.arrayBuffer(new ArrayBuffer(8));
      }),
    );
    const createObjectURLSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:http://localhost/fake');
    const revokeObjectURLSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});

    renderItem({ ...baseReport, pdfUrl: 'localhost:8080/api/reports/1/pdf/legacy.pdf' });

    fireEvent.click(screen.getByRole('button', { name: /다운로드/ }));

    await waitFor(() => {
      expect(requestedPath).toBe('/api/reports/1/pdf/legacy.pdf');
    });

    createObjectURLSpy.mockRestore();
    revokeObjectURLSpy.mockRestore();
  });

  // 다운로드 fetch가 실패하면(네트워크 오류·non-2xx) 조용히 무시하지 않고 AlertModal로
  // 사용자에게 실패를 알려야 한다 — code-reviewer P2 픽스(#1464).
  it('다운로드 fetch가 실패하면 AlertModal로 실패를 알린다', async () => {
    server.use(http.get('/api/reports/1/pdf/mock.pdf', () => HttpResponse.error()));

    renderItem(baseReport);

    fireEvent.click(screen.getByRole('button', { name: /다운로드/ }));

    expect(
      await screen.findByText('PDF 다운로드에 실패했습니다. 잠시 후 다시 시도해주세요.'),
    ).toBeTruthy();
    expect(screen.getByRole('heading', { name: '다운로드 실패' })).toBeTruthy();
  });
});
