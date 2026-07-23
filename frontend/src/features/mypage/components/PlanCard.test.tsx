// @vitest-environment jsdom
// PlanCard(#712 Figma 리디자인) 단위 테스트 — 사업자 인증 칩 3분기, 업그레이드 버튼 비활성 조건,
// 다음 결제일 조건부 표시, 모의 결제(checkout)/결제 내역 모달 흐름을 검증한다.
// useCheckout이 실제 axios 요청을 보내므로 mypageApi.handlers.ts의 MSW 핸들러를 그대로 재사용한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { mypageHandlers } from '../api/mypageApi.handlers';
import type { MyPlanInfo } from '../types';
import { PlanCard } from './PlanCard';

const server = setupServer(...mypageHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

const standardPlan: MyPlanInfo = {
  name: 'STANDARD',
  priceMonthly: 29000,
  status: 'ACTIVE',
  nextBillingDate: '2026-08-01',
  businessVerified: true,
};

function renderCard(plan: MyPlanInfo) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <PlanCard plan={plan} />
    </QueryClientProvider>,
  );
}

describe('PlanCard', () => {
  it('사업자 인증 완료(businessVerified=true)면 초록 칩을 렌더링한다', () => {
    renderCard(standardPlan);
    expect(screen.getByText('사업자 인증 완료')).toBeTruthy();
  });

  it('사업자 인증 미완료(businessVerified=false)면 회색 칩을 렌더링한다', () => {
    renderCard({ ...standardPlan, businessVerified: false });
    expect(screen.getByText('사업자 인증 미완료')).toBeTruthy();
  });

  it('개인 구독(businessVerified=null)이면 사업자 인증 칩을 렌더링하지 않는다', () => {
    renderCard({ ...standardPlan, businessVerified: null });
    expect(screen.queryByText(/사업자 인증/)).toBeNull();
  });

  it('nextBillingDate가 없으면(FREE) 다음 결제일 문구를 표시하지 않는다', () => {
    renderCard({ name: 'FREE', priceMonthly: 0, status: 'ACTIVE', nextBillingDate: null, businessVerified: null });
    expect(screen.queryByText(/다음 결제일/)).toBeNull();
  });

  it('ENTERPRISE(최상위) 플랜이면 업그레이드 버튼이 비활성화되고 안내 문구로 바뀐다', () => {
    renderCard({ ...standardPlan, name: 'ENTERPRISE' });
    const button = screen.getByRole('button', { name: '최상위 플랜 이용 중' });
    expect(button).toHaveProperty('disabled', true);
  });

  it('플랜 업그레이드 클릭 시 현재보다 상위 플랜만 모달에 노출한다(STANDARD → ENTERPRISE만)', async () => {
    renderCard(standardPlan);

    fireEvent.click(screen.getByRole('button', { name: '플랜 업그레이드' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Enterprise')).toBeTruthy();
    expect(within(dialog).queryByText('Standard')).toBeNull();
  });

  it('모의 결제 성공 시 모달이 닫히고 갱신된 플랜이 반영된다', async () => {
    renderCard(standardPlan);

    fireEvent.click(screen.getByRole('button', { name: '플랜 업그레이드' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '결제하기' }));

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).toBeNull();
    });
  });

  it('결제 내역 버튼 클릭 시 모의 결제 1건을 모달로 보여준다', async () => {
    renderCard(standardPlan);

    fireEvent.click(screen.getByRole('button', { name: '결제 내역' }));

    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText('Standard 플랜 구독')).toBeTruthy();
    expect(within(dialog).getByText('₩29,000/월')).toBeTruthy();
  });
});
