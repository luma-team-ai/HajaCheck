// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PlatformAdminPlaceholderPage } from './PlatformAdminPlaceholderPage';

afterEach(cleanup);

describe('PlatformAdminPlaceholderPage', () => {
  it('title을 제목과 안내문에 반영해 렌더한다', () => {
    render(<PlatformAdminPlaceholderPage title="사용자 관리" />);

    expect(screen.getByRole('heading', { name: '사용자 관리' })).not.toBeNull();
    expect(screen.getByText('사용자 관리 기능은 준비 중입니다.')).not.toBeNull();
  });
});
