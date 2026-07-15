import { describe, expect, it } from 'vitest';
import { NAV_ITEMS } from './navItems';

// 사이드바 마이페이지 활성화 회귀 방지 (HAJA-185, #212)
describe('NAV_ITEMS — 마이페이지', () => {
  it('마이페이지 항목은 서브메뉴(내 정보/내 점검 이력·보고서/내 플랜)를 갖는다', () => {
    const mypage = NAV_ITEMS.find((item) => item.label === '마이페이지');

    expect(mypage?.subItems).toBeDefined();
    expect(mypage?.subItems?.map((sub) => sub.label)).toEqual([
      '내 정보',
      '내 점검 이력·보고서',
      '내 플랜',
    ]);
  });

  it('내 플랜만 활성화되어 /mypage/plan으로 연결된다', () => {
    const mypage = NAV_ITEMS.find((item) => item.label === '마이페이지');
    const myPlan = mypage?.subItems?.find((sub) => sub.label === '내 플랜');
    const others = mypage?.subItems?.filter((sub) => sub.label !== '내 플랜') ?? [];

    expect(myPlan?.isActive).toBe(true);
    expect(myPlan?.path).toBe('/mypage/plan');
    expect(others.every((sub) => sub.isActive === false)).toBe(true);
  });
});
