import { vi } from 'vitest';

// 모듈 로드 시점(어떤 테스트도 아직 모킹하기 전)의 순정 구현을 한 번만 붙잡아 둔다. mockChartContainerSize를
// 여러 테스트 파일의 beforeEach에서 반복 호출해도, 직전 테스트가 심어둔 mock을 "원본"으로 잘못 참조해
// 자기 자신을 무한 재귀 호출하는 것을 방지한다.
const nativeGetBoundingClientRect = Element.prototype.getBoundingClientRect;

/**
 * recharts <ResponsiveContainer>는 jsdom에 ResizeObserver가 없으면 컨테이너 크기 계산 자체를
 * 건너뛰어 실제 svg 콘텐츠(선/막대/파이 조각)를 렌더링하지 않는다. LineChart/BarChart/PieChart
 * 테스트가 공통으로 호출해 컨테이너 크기를 모킹한다 — 실제 recharts DOM이 렌더링되는지 확인하기 위함
 * (테스트 전용 유틸이라 index.ts에서 export하지 않음).
 */
export function mockChartContainerSize(width = 500, height = 300) {
  class MockResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }

  vi.stubGlobal('ResizeObserver', MockResizeObserver);

  // ResponsiveContainer 루트 div에만 크기를 부여한다. 모든 엘리먼트에 일괄 적용하면 Legend 등
  // 내부에서 별도로 측정하는 하위 엘리먼트까지 컨테이너와 같은 크기로 부풀어, recharts가 legend
  // 높이만큼 플롯 영역을 깎아 실제 차트(Pie 등)가 0 크기로 렌더링되는 부작용이 생긴다.
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.classList.contains('recharts-responsive-container')) {
      return {
        width,
        height,
        top: 0,
        left: 0,
        right: width,
        bottom: height,
        x: 0,
        y: 0,
        toJSON() {
          return {};
        },
      } as DOMRect;
    }
    return nativeGetBoundingClientRect.call(this);
  });
}
