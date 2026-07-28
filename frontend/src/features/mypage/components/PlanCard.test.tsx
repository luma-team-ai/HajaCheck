// @vitest-environment jsdom
// PlanCard(#712 Figma 리디자인 → 토스페이먼츠 결제창 연동 #989/HAJA-490, 코드 리뷰 P2 픽스로
// 2단계 흐름 도입) 단위 테스트 — 사업자 인증 칩 3분기, 업그레이드 버튼 비활성 조건, 다음 결제일
// 조건부 표시, 결제(주문 생성→금액 확인→결제창)/결제 내역 모달 흐름을 검증한다.
// useCreatePlanOrder가 실제 axios 요청(주문 생성)을 보내므로 mypageApi.handlers.ts의 MSW
// 핸들러를 그대로 재사용한다.
//
// 토스페이먼츠 SDK 로더(shared/lib/tossPayments/loadTossPaymentsSdk)는 그 자체로 모듈 스코프
// 싱글턴(sdkPromise)을 갖고 있어(loadTossPaymentsSdk.test.ts가 그 싱글턴 자체를 별도로 검증한다),
// 이 파일에서 실 로더를 그대로 쓰면 "어떤 테스트를 먼저 실행했는지"에 결과가 좌우되는 순서
// 의존성이 생긴다(코드 리뷰 P3 지적). 그래서 이 파일은 실 로더 대신 loadTossPaymentsSdk 모듈
// 자체를 목으로 교체해(vi.importActual로 TossClientKeyMissingError 클래스는 실물 그대로 재노출)
// 각 테스트가 mockResolvedValueOnce/mockRejectedValueOnce로 자신의 시나리오를 독립적으로
// 통제하게 한다 — 공유 상태가 전혀 없으므로 테스트 실행 순서와 무관하게 항상 같은 결과가 나온다.
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { mypageHandlers } from '../api/mypageApi.handlers';
import { TossClientKeyMissingError } from '../../../shared/lib/tossPayments/loadTossPaymentsSdk';
import type { MyPlanInfo } from '../types';
import { PlanCard } from './PlanCard';

const requestPaymentMock = vi.fn();
const paymentMock = vi.fn(() => ({ requestPayment: requestPaymentMock }));
const loadTossPaymentsSdkMock = vi.fn();

vi.mock('../../../shared/lib/tossPayments/loadTossPaymentsSdk', async (importOriginal) => {
  const actual =
    await importOriginal<typeof import('../../../shared/lib/tossPayments/loadTossPaymentsSdk')>();
  return {
    ...actual,
    loadTossPaymentsSdk: () => loadTossPaymentsSdkMock(),
  };
});

const server = setupServer(...mypageHandlers);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => {
  server.resetHandlers();
  cleanup();
  requestPaymentMock.mockReset();
  paymentMock.mockClear();
  loadTossPaymentsSdkMock.mockReset();
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

async function openCheckoutAndSelectPlan() {
  fireEvent.click(screen.getByRole('button', { name: '플랜 업그레이드' }));
  const dialog = await screen.findByRole('dialog');
  fireEvent.click(within(dialog).getByRole('button', { name: '결제하기' }));
  return dialog;
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

  it('결제하기 클릭 시 주문을 생성하고 서버가 계산한 실 금액을 확인 화면에 보여준다(결제창은 아직 열지 않음)', async () => {
    renderCard(standardPlan);
    const dialog = await openCheckoutAndSelectPlan();

    // STANDARD → ENTERPRISE 유일 후보, MSW 핸들러(CHECKOUT_PLAN_PRICE) 기준 59,000원.
    // MSW 목은 잔여 기간 크레딧(#1146, HAJA-550)을 재현하지 않아(listPrice === amount, credit 0)
    // "크레딧" 줄은 렌더되지 않고 정가/실 결제 금액 2줄만 보인다(PlanCheckoutModal의 credit > 0 가드).
    expect(await within(dialog).findByText('Enterprise 플랜')).toBeTruthy();
    expect(within(dialog).getByText('정가')).toBeTruthy();
    expect(within(dialog).getByText('실 결제 금액')).toBeTruthy();
    expect(within(dialog).getAllByText('₩59,000/월')).toHaveLength(2);
    expect(within(dialog).queryByText('잔여 기간 크레딧')).toBeNull();
    expect(within(dialog).getByText('이 금액으로 결제를 진행할까요?')).toBeTruthy();
    expect(loadTossPaymentsSdkMock).not.toHaveBeenCalled();
    expect(requestPaymentMock).not.toHaveBeenCalled();
  });

  it('확인 단계에서 취소를 누르면 결제창을 요청하지 않고 모달만 닫는다(주문 취소 API 호출 없음)', async () => {
    renderCard(standardPlan);
    const dialog = await openCheckoutAndSelectPlan();
    await within(dialog).findByText('Enterprise 플랜');

    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(loadTossPaymentsSdkMock).not.toHaveBeenCalled();
    expect(requestPaymentMock).not.toHaveBeenCalled();
  });

  it('확인 단계에서 결제 진행 클릭 시 토스페이먼츠 결제창(카드)을 요청한다', async () => {
    requestPaymentMock.mockResolvedValue(undefined);
    loadTossPaymentsSdkMock.mockResolvedValue({ payment: paymentMock });

    renderCard(standardPlan);
    const dialog = await openCheckoutAndSelectPlan();
    await within(dialog).findByText('Enterprise 플랜');

    fireEvent.click(within(dialog).getByRole('button', { name: '결제 진행' }));

    await waitFor(() => expect(requestPaymentMock).toHaveBeenCalledTimes(1));
    expect(requestPaymentMock).toHaveBeenCalledWith(
      expect.objectContaining({
        method: 'CARD',
        amount: { currency: 'KRW', value: 59000 },
        orderId: expect.stringMatching(/^order_mock_/),
        orderName: 'hajaCheck ENTERPRISE 플랜 구독',
        successUrl: expect.stringContaining('/payments/success'),
        failUrl: expect.stringContaining('/payments/fail'),
      }),
    );
  });

  it('토스 클라이언트 키 미설정 시 결제 진행 클릭에서 명확한 에러를 보여준다(조용한 실패 금지)', async () => {
    loadTossPaymentsSdkMock.mockRejectedValue(new TossClientKeyMissingError());

    renderCard(standardPlan);
    const dialog = await openCheckoutAndSelectPlan();
    await within(dialog).findByText('Enterprise 플랜');

    fireEvent.click(within(dialog).getByRole('button', { name: '결제 진행' }));

    await screen.findByText('결제 서비스 설정이 완료되지 않았습니다. 관리자에게 문의해 주세요.');
    expect(requestPaymentMock).not.toHaveBeenCalled();
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
