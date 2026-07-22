// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useOutsideDismiss } from './useOutsideDismiss';

describe('useOutsideDismiss', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('컨테이너 바깥 mousedown 시 onDismiss를 호출한다', () => {
    const onDismiss = vi.fn();
    const { result } = renderHook(() => useOutsideDismiss<HTMLDivElement>(onDismiss));

    const container = document.createElement('div');
    document.body.appendChild(container);
    // @ts-expect-error - RefObject.current는 읽기 전용 타입이지만 테스트에서 DOM에 직접 붙인다
    result.current.current = container;

    const outside = document.createElement('div');
    document.body.appendChild(outside);

    act(() => {
      outside.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    });

    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('컨테이너 안쪽 mousedown은 onDismiss를 호출하지 않는다', () => {
    const onDismiss = vi.fn();
    const { result } = renderHook(() => useOutsideDismiss<HTMLDivElement>(onDismiss));

    const container = document.createElement('div');
    const inner = document.createElement('button');
    container.appendChild(inner);
    document.body.appendChild(container);
    // @ts-expect-error - 테스트 전용 DOM 연결
    result.current.current = container;

    act(() => {
      inner.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    });

    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('Escape 키 입력 시 onDismiss를 호출한다', () => {
    const onDismiss = vi.fn();
    renderHook(() => useOutsideDismiss<HTMLDivElement>(onDismiss));

    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });

    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it('enabled=false면 리스너를 등록하지 않는다', () => {
    const onDismiss = vi.fn();
    renderHook(() => useOutsideDismiss<HTMLDivElement>(onDismiss, false));

    act(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    });

    expect(onDismiss).not.toHaveBeenCalled();
  });

  it('onDismiss가 없으면 리스너를 등록하지 않는다', () => {
    renderHook(() => useOutsideDismiss<HTMLDivElement>(undefined));

    expect(() => {
      document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    }).not.toThrow();
  });
});
