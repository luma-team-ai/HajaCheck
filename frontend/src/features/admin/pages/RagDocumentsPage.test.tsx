// @vitest-environment jsdom
// RagDocumentsPage 통합 테스트 — 실제 useRagDocuments/useReEmbedRagDocument 훅 + MSW
// ragDocumentHandlers를 통해 목록 렌더·재임베딩·업로드 폼 클라이언트 검증을 확인한다.
//
// 업로드 폼의 실제 multipart 파일 파트 HTTP 라운드트립은 msw+jsdom+undici 환경 한계로 이 테스트
// 러너에서 안정적으로 재현되지 않는다(authApi.buildCompanySignupFormData.test.ts와 동일 이유,
// FormData 변환 로직 자체는 ragDocumentApi.test.ts에서 별도 검증) — 여기서는 파일 미선택 시
// 클라이언트 검증만 확인하고, 실제 제출 성공 경로는 다루지 않는다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
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

  // MSW 핸들러의 mockRagDocuments는 모듈 스코프 상태라 테스트 간 공유된다(resetHandlers는 데이터를
  // 리셋하지 않는다) — 삭제는 되돌릴 수 없는 변경이라, 비파괴적인 "취소" 케이스를 먼저 검증하고
  // 실제로 목록에서 사라지는 "확인" 케이스를 이 파일의 마지막에 둬 이후 테스트에 영향을 주지 않게 한다.
  it('삭제 확인 다이얼로그에서 취소하면 문서가 그대로 남는다', async () => {
    renderPage();

    const titleCell = await screen.findByText('균열 하자 보수 지침');
    const row = titleCell.closest('tr');
    if (!row) {
      throw new Error('문서 행을 찾을 수 없습니다');
    }

    fireEvent.click(within(row).getByRole('button', { name: '삭제' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }));

    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByText('균열 하자 보수 지침')).toBeTruthy();
  });

  // #1412 P2 — react-query의 mutation.error는 다음 mutate까지 유지되므로, 모달 열기/닫기에서
  // resetError()를 호출하지 않으면 문서 A의 삭제 실패 에러가 아직 시도도 안 한 문서 B의 모달에
  // 그대로 노출된다. (이 케이스도 삭제가 실패해 목록이 변하지 않으므로 비파괴적이다.)
  it('문서 A 삭제 실패 후 문서 B의 삭제 모달을 열면 이전 에러가 남아 있지 않다', async () => {
    server.use(
      http.delete('/api/admin/rag-documents/:id', () =>
        HttpResponse.json({ message: '삭제에 실패했습니다' }, { status: 500 }),
      ),
    );
    renderPage();

    // 문서 A 삭제 시도 → 실패 에러가 모달에 표시된다.
    const rowA = (await screen.findByText('균열 하자 보수 지침')).closest('tr');
    if (!rowA) {
      throw new Error('문서 행을 찾을 수 없습니다');
    }
    fireEvent.click(within(rowA).getByRole('button', { name: '삭제' }));
    const dialogA = await screen.findByRole('dialog');
    fireEvent.click(within(dialogA).getByRole('button', { name: '삭제' }));
    expect(await within(dialogA).findByRole('alert')).toBeTruthy();

    // 취소로 모달을 닫고, 문서 B의 삭제 모달을 연다.
    fireEvent.click(within(dialogA).getByRole('button', { name: '취소' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());

    const rowB = screen.getByText('시설물의 안전관리에 관한 특별법').closest('tr');
    if (!rowB) {
      throw new Error('문서 행을 찾을 수 없습니다');
    }
    fireEvent.click(within(rowB).getByRole('button', { name: '삭제' }));
    const dialogB = await screen.findByRole('dialog');

    expect(within(dialogB).getByText(/시설물의 안전관리에 관한 특별법/)).toBeTruthy();
    expect(within(dialogB).queryByRole('alert')).toBeNull();

    fireEvent.click(within(dialogB).getByRole('button', { name: '취소' }));
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

  it('삭제 버튼을 누르면 확인 다이얼로그가 뜨고, 확인하면 목록에서 사라진다', async () => {
    renderPage();

    const titleCell = await screen.findByText('균열 하자 보수 지침');
    const row = titleCell.closest('tr');
    if (!row) {
      throw new Error('문서 행을 찾을 수 없습니다');
    }

    fireEvent.click(within(row).getByRole('button', { name: '삭제' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/균열 하자 보수 지침/)).toBeTruthy();

    fireEvent.click(within(dialog).getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(screen.queryByText('균열 하자 보수 지침')).toBeNull());
    expect(screen.getByText('시설물의 안전관리에 관한 특별법')).toBeTruthy();
  });
});
