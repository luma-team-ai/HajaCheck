// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { FloatingPopup } from './FloatingPopup';

afterEach(cleanup);

describe('FloatingPopup', () => {
  it('links를 렌더링하고 클릭 시 각 onClick이 호출된다', () => {
    const handleLinkClick = vi.fn();
    render(
      <FloatingPopup
        onClose={vi.fn()}
        links={[{ label: '서비스 이용 방법', onClick: handleLinkClick }]}
      />,
    );

    fireEvent.click(screen.getByText('서비스 이용 방법'));

    expect(handleLinkClick).toHaveBeenCalledTimes(1);
  });

  it('닫기 버튼 클릭 시 onClose가 호출된다', () => {
    const handleClose = vi.fn();
    render(<FloatingPopup onClose={handleClose} links={[]} />);

    fireEvent.click(screen.getByLabelText('닫기'));

    expect(handleClose).toHaveBeenCalledTimes(1);
  });

  it('waitingLabel과 상담원 연결 버튼을 표시한다', () => {
    const handleConnect = vi.fn();
    render(
      <FloatingPopup
        onClose={vi.fn()}
        links={[]}
        onConnectAgent={handleConnect}
        waitingLabel="현재 대기 2팀"
      />,
    );

    expect(screen.getByText('현재 대기 2팀')).not.toBeNull();
    fireEvent.click(screen.getByText('상담원 연결하기'));
    expect(handleConnect).toHaveBeenCalledTimes(1);
  });

  it('기본값으로 화면 우하단(BottomNavBarFab 바로 위)에 고정 배치된다', () => {
    const { container } = render(<FloatingPopup onClose={vi.fn()} links={[]} />);

    expect(container.querySelector('.floating-popup--fixed')).not.toBeNull();
  });

  it('fixedPosition=false면 고정 배치 클래스가 붙지 않는다', () => {
    const { container } = render(
      <FloatingPopup onClose={vi.fn()} links={[]} fixedPosition={false} />,
    );

    expect(container.querySelector('.floating-popup--fixed')).toBeNull();
  });
});
