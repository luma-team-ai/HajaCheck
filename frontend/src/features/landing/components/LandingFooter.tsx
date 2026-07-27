import brandLogo from '../../../assets/brand/sidenav-brand-logo.png';
import { FOOTER_LINKS } from '../constants';

export function LandingFooter() {
  return (
    <footer className="landing-footer">
      <div className="landing-footer-top">
        <div>
          <img className="landing-logo-image" src={brandLogo} alt="HajaCheck" />
          <p className="landing-footer-tagline">
            데이터와 AI 기술로 시설물 관리의 새로운
            <br />
            기준을 제시합니다.
          </p>
        </div>
        <nav className="landing-footer-columns" aria-label="푸터 링크">
          {FOOTER_LINKS.map((column) => (
            <div key={column.title} className="landing-footer-column">
              <h4>{column.title}</h4>
              <ul>
                {column.links.map((link) => (
                  <li key={link.href}>
                    <a href={link.href}>{link.label}</a>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>
      </div>
      <div className="landing-footer-bottom">© 2026 HAJA. All rights reserved.</div>
    </footer>
  );
}
