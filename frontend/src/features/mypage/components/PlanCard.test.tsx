// @vitest-environment jsdom
// PlanCard(#712 Figma 리디자인 → 토스페이먼츠 결제창 연동 #989/HAJA-490) 단위 테스트 — 사업자 인증
// 칩 3분기, 업그레이드 버튼 비활성 조건, 다음 결제일 조건부 표시, 결제(usePlanCheckout)/결제 내역
// (usePayments) 모달 흐름을 검증한다. usePlanCheckout이 실제 axios 요청(주문 생성)을 보내므로
// mypageApi.handlers.ts의 MSW 핸들러를 그대로 재사용한다. 토스페이먼츠 SDK 자체는 실 네트워크
// 호출(<script> 주입)을 일으키므로 모듈을 목으로 교체해 우리 쪽 연동 로직만 검증한다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { mypageHandlers } from '../api/mypageApi.handlers';
import type { MyPlanInfo } from '../types';
import { PlanCard } from './PlanCard';

const requestPaymentMock = vi.fn();
const paymentMock = vi.fn(() => ({ requestPayment: requestPaymentMock }));
const loadTossPaymentsMock = vi.fn(async (clientKey: string) => {
  void clientKey;
  return { payment: paymentMock };
});

vi.mock('@tosspayments/tosspayments-sdk', () => ({
  ANONYMOUS: '@@ANONYMOUS',
  loadTossPayments: (clientKey: string) => loadTossPaymentsMock(clientKey),
}));

const server = setupServer(...mypageHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  requestPaymentMock.mockReset();
  paymentMock.mockClear();
  loadTossPaymentsMock.mockClear();
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

  // 클라이언트 키 미설정 — 완료 기준 "클라이언트 키 미설정" 테스트. loadTossPaymentsSdk의 모듈
  // 스코프 싱글턴이 이 파일 안에서 재사용되므로(성공 로드가 캐시되면 이후 빈 키로도 그 캐시를
  // 반환), 이 테스트를 성공 로드 테스트보다 먼저 실행해 미설정 상태를 정확히 재현한다.
  it('토스 클라이언트 키 미설정 시 결제 진입점에서 명확한 에러를 보여준다(조용한 실패 금지)', async () => {
    vi.stubEnv('VITE_TOSS_CLIENT_KEY', '');
    renderCard(standardPlan);

    fireEvent.click(screen.getByRole('button', { name: '플랜 업그레이드' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '결제하기' }));

    await screen.findByText('결제 서비스 설정이 완료되지 않았습니다. 관리자에게 문의해 주세요.');
    expect(loadTossPaymentsMock).not.toHaveBeenCalled();

    vi.unstubAllEnvs();
  });

  it('결제하기 클릭 시 주문을 생성하고 토스페이먼츠 결제창(카드)을 요청한다', async () => {
    vi.stubEnv('VITE_TOSS_CLIENT_KEY', 'test-client-key');
    requestPaymentMock.mockResolvedValue(undefined);

    renderCard(standardPlan);

    fireEvent.click(screen.getByRole('button', { name: '플랜 업그레이드' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: '결제하기' }));

    await waitFor(() => expect(requestPaymentMock).toHaveBeenCalledTimes(1));

    expect(requestPaymentMock).toHaveBeenCalledWith(
      expect.objectContaining({
        method: 'CARD',
        amount: { currency: 'KRW', value: 59000 }, // STANDARD → ENTERPRISE 유일 후보
        orderId: expect.stringMatching(/^order_mock_/),
        orderName: 'hajaCheck ENTERPRISE 플랜 구독',
        successUrl: expect.stringContaining('/payments/success'),
        failUrl: expect.stringContaining('/payments/fail'),
      }),
    );

    vi.unstubAllEnvs();
  });

  it('결제 내역 버튼 클릭 시 결제 내역을 최신순으로 보여준다', async () => {
    renderCard(standardPlan);

    fireEvent.click(screen.getByRole('button', { name: '결제 내역' }));

    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('Standard 플랜 구독')).toBeTruthy();
    expect(within(dialog).getByText('₩29,000')).toBeTruthy();
    expect(within(dialog).getByText(/2026-07-01 · 결제 완료/)).toBeTruthy();
  });
});
