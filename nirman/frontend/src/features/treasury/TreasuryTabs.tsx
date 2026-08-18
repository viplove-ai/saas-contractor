import { Tab, Tabs } from '@mui/material';
import { Link, useLocation } from 'react-router-dom';

/**
 * The two halves of the treasury, and which one you are looking at.
 *
 * <p>They answer different questions and neither subsumes the other. The dashboard is the
 * contract's view — what each job has locked up and when it comes back. The register is the
 * bank's — what certificates the company holds, and how much of that is free to bid with. An
 * FDR appears on both and is one row on the second whether it is pledged to a contract, to a
 * tender that was refused, or to nothing at all.</p>
 */
export function TreasuryTabs() {
  const { pathname } = useLocation();
  const current = pathname.startsWith('/treasury/fdrs') ? '/treasury/fdrs' : '/treasury';

  return (
    <Tabs value={current} variant="scrollable" scrollButtons="auto">
      <Tab label="Deposits by contract" value="/treasury" component={Link} to="/treasury" />
      <Tab
        label="Fixed deposits"
        value="/treasury/fdrs"
        component={Link}
        to="/treasury/fdrs"
      />
    </Tabs>
  );
}
