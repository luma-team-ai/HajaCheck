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

    expect(await screen.findByText(/상담원과 연결됐습니다/)).toBeTruthy();
    expect(screen.getByText('내 상담 이력에서 확인하기')).toBeTruthy();
  });
});
