// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { Footer } from './Footer';

afterEach(cleanup);

describe('Footer', () => {
  it('기본 컬럼과 저작권 문구를 렌더링한다', () => {
    render(<Footer />, { wrapper: MemoryRouter });

    expect(screen.getByText('제품')).not.toBeNull();
    expect(screen.getByText('시설물 정보 관리')).not.toBeNull();
    expect(screen.getByText('© 2026 FACIL.AI Inc. All rights reserved.')).not.toBeNull();
  });

  it('columns prop으로 컬럼 구성을 교체할 수 있다', () => {
    render(<Footer columns={[{ title: '커스텀', links: [{ label: '링크1', href: '/a' }] }]} />, {
      wrapper: MemoryRouter,
    });

    expect(screen.getByText('커스텀')).not.toBeNull();
    expect(screen.getByText('링크1')).not.toBeNull();
    expect(screen.queryByText('제품')).toBeNull();
  });
});
