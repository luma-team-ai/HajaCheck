// @vitest-environment jsdom
// RagDocumentsPage 통합 테스트 — 실제 useRagDocuments/useReEmbedRagDocument 훅 + MSW
// ragDocumentHandlers를 통해 목록 렌더·재임베딩·업로드 폼 클라이언트 검증을 확인한다.
//
// 업로드 폼의 실제 multipart 파일 파트 HTTP 라운드트립은 msw+jsdom+undici 환경 한계로 이 테스트
// 러너에서 안정적으로 재현되지 않는다(authApi.buildCompanySignupFormData.test.ts와 동일 이유,
// FormData 변환 로직 자체는 ragDocumentApi.test.ts에서 별도 검증) — 여기서는 파일 미선택 시
// 클라이언트 검증만 확인하고, 실제 제출 성공 경로는 다루지 않는다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { ragDocumentHandlers } from '../api/ragDocumentApi.handlers';
import { RagDocumentsPage } from './RagDocumentsPage';

const server = setupServer(...ragDocumentHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <RagDocumentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('RagDocumentsPage (통합 테스트)', () => {
  it('목록을 불러와 문서 제목과 임베딩 상태를 렌더링한다', async () => {
    renderPage();

    expect(await screen.findByText('시설물의 안전관리에 관한 특별법')).toBeTruthy();
    expect(screen.getByText('균열 하자 보수 지침')).toBeTruthy();
    expect(screen.getAllByText('완료').length).toBeGreaterThan(0);
    // "재임베딩 필요"는 상단 통계 카드 라벨과 FAILED 문서 행 상태 양쪽에 나타난다(getAllByText로 스코프).
    expect(screen.getAllByText('재임베딩 필요').length).toBeGreaterThan(0);
  });

  it('재임베딩 버튼을 누르면 해당 문서 행의 상태가 완료로 갱신된다', async () => {
    renderPage();

    const titleCell = await screen.findByText('균열 하자 보수 지침');
    const row = titleCell.closest('tr');
    if (!row) {
      throw new Error('문서 행을 찾을 수 없습니다');
    }
    expect(within(row).getByText('재임베딩 필요')).toBeTruthy();

    // mock 데이터의 FAILED 문서를 재임베딩 — MSW 핸들러가 즉시 DONE으로 갱신해 반환한다.
    fireEvent.click(within(row).getByRole('button', { name: '재임베딩' }));

    expect(await within(row).findByText('완료')).toBeTruthy();
  });

  it('파일을 고르기 전엔 제목 등 메타데이터 입력·제출 버튼이 보이지 않는다(Figma 디자인 — 드롭존만 노출)', async () => {
    renderPage();

    await screen.findByText('시설물의 안전관리에 관한 특별법');
    expect(screen.queryByLabelText('제목')).toBeNull();
    expect(screen.queryByRole('button', { name: '업로드 및 임베딩 실행' })).toBeNull();
  });

  it('파일 선택 후 제목을 비워둔 채 제출하면 클라이언트 검증 메시지를 보여준다', async () => {
    renderPage();

    await screen.findByText('시설물의 안전관리에 관한 특별법');
    const file = new File(['%PDF-1.4'], 'law.pdf', { type: 'application/pdf' });
    fireEvent.change(screen.getByLabelText('PDF 파일'), { target: { files: [file] } });

    fireEvent.click(await screen.findByRole('button', { name: '업로드 및 임베딩 실행' }));

    expect(await screen.findByText('제목은 필수입니다.')).toBeTruthy();
  });
});
