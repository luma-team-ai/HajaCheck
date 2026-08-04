// @vitest-environment jsdom
// 상담 챗봇(#20, HAJA-33) 통합 테스트 — 실제 useChatBot 훅 + MSW counselHandlers.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { MemoryRouter } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { counselHandlers, mockScenarioRoots } from '../api/counselApi.handlers';
import { ChatBotPage } from './ChatBotPage';

const server = setupServer(...counselHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

function renderPage() {
  render(
    <MemoryRouter>
      <ChatBotPage />
    </MemoryRouter>,
  );
}

describe('ChatBotPage', () => {
  it('최상위 버튼 목록을 불러와 보여준다', async () => {
    renderPage();

    expect(await screen.findByText(mockScenarioRoots[0].buttonLabel)).toBeTruthy();
    expect(screen.getByText(mockScenarioRoots[1].buttonLabel)).toBeTruthy();
  });

  it('상담원 연결 리프를 선택하면 티켓을 생성하고 안내를 보여준다', async () => {
    renderPage();

    fireEvent.click(await screen.findByText(mockScenarioRoots[0].buttonLabel));
    fireEvent.click(await screen.findByText('상담원 연결'));

    expect(await screen.findByText(/상담원 연결을 요청했습니다/)).toBeTruthy();
  });

  it('#1434: 경로 안내 오버라이드가 있는 노드는 문구가 교체되고 바로가기 버튼이 붙는다', async () => {
    renderPage();

    fireEvent.click(await screen.findByText(mockScenarioRoots[1].buttonLabel));

    expect(
      await screen.findByText('[마이페이지 > 결제 정보]에서 결제 내역을 확인하거나 플랜을 변경하실 수 있습니다.'),
    ).toBeTruthy();
    const link = screen.getByRole('link', { name: '내 플랜' });
    expect(link.getAttribute('href')).toBe('/mypage/plan');
  });
});
