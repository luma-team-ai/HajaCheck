import defaultCompanyIcon from '../../../assets/brand/sidenav-default-avatar.svg';

// 기업 기본 이미지 — 프로젝트에 기업 전용 로고 에셋이 없어(사용자 확인) 사이드바 프로필 기본
// 아바타(SideNavBar 로그아웃 버튼 위, sidenav-default-avatar.svg)를 그대로 재사용한다(사용자 지시).
// 개인(회사 미소속) 사용자는 companyName이 없어 아이콘 자체를 그리지 않는다.
export function CompanyAvatar({ companyName }: { companyName: string | null }) {
  if (!companyName) {
    return null;
  }

  return (
    <img
      className="h-6 w-6 shrink-0 rounded-full border border-border object-contain p-0.5"
      src={defaultCompanyIcon}
      alt=""
    />
  );
}
