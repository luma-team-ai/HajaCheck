import { useNavigate } from 'react-router-dom';
import { POLICY_TABS, type PolicyDocType } from '../constants';

interface PolicyTabsProps {
  activeDoc: PolicyDocType;
}

export function PolicyTabs({ activeDoc }: PolicyTabsProps) {
  const navigate = useNavigate();

  return (
    <div className="policy-tablist" role="tablist">
      {POLICY_TABS.map((tab) => (
        <button
          key={tab.docType}
          type="button"
          role="tab"
          aria-selected={activeDoc === tab.docType}
          className={`policy-tab${activeDoc === tab.docType ? ' policy-tab--active' : ''}`}
          onClick={() => navigate(tab.href)}
        >
          {tab.label}
        </button>
      ))}
    </div>
  );
}
