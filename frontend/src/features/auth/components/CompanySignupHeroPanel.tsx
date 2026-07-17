import brandMark from '../../../assets/brand/brand-mark.png';
import heroBackground from '../../../assets/brand/landing-hero-ai-scan.jpg';

// 기업 회원가입 좌측 브랜드 패널(Figma node 50-63) — 랜딩 히어로와 동일 SVG 배경을 재사용하고
// 그 위에 CSS 그라디언트 스크림을 얹어 흰 텍스트 대비를 확보한다(별도 스크림 에셋 생성 금지, #292).
// 좁은 화면에서는 숨김 처리(폼 우선 노출), 고정 px 대신 데스크톱 기준 상대 폭(45%) 사용.
export function CompanySignupHeroPanel() {
  return (
    <section className="relative hidden w-[45%] shrink-0 flex-col justify-start overflow-hidden p-12 text-white lg:flex">
      <img
        src={heroBackground}
        alt=""
        aria-hidden="true"
        className="absolute inset-0 h-full w-full object-cover"
      />
      <div
        className="absolute inset-0 bg-gradient-to-b from-black/75 via-black/55 to-black/80"
        aria-hidden="true"
      />

      <div className="relative flex items-center gap-2">
        <img src={brandMark} alt="" className="h-[25px] w-[25px] object-contain" />
        <span className="text-[28px] font-semibold tracking-tight text-white">HajaCheck</span>
      </div>
      <p className="relative m-0 mt-3 text-base font-medium text-white/80">
        AI 기반 시설물 결함 검수 플랫폼
      </p>
    </section>
  );
}
