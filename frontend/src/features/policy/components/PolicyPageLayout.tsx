import { LandingFooter } from '../../landing/components/LandingFooter';
import { LandingHeader } from '../../landing/components/LandingHeader';
import '../../landing/landing.css';
import '../policy.css';
import { POLICY_TABS, type PolicyDocType } from '../constants';
import { PolicyContent } from './PolicyContent';
import { PolicyTabs } from './PolicyTabs';

interface PolicyPageLayoutProps {
  activeDoc: PolicyDocType;
}

export function PolicyPageLayout({ activeDoc }: PolicyPageLayoutProps) {
  const activeTab = POLICY_TABS.find((tab) => tab.docType === activeDoc);

  return (
    <div className="landing">
      <LandingHeader />

      <section className="policy-page">
        <PolicyTabs activeDoc={activeDoc} />
        <h1 className="policy-title">{activeTab?.label}</h1>
        {activeTab && <PolicyContent mdPath={activeTab.mdPath} />}
      </section>

      <LandingFooter />
    </div>
  );
}
