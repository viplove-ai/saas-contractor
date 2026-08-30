import { Tab, Tabs } from '@mui/material';
import { Link, useLocation } from 'react-router-dom';

/**
 * The three views of the treasury, and which one you are looking at.
 *
 * <p>They answer different questions and none subsumes another. The dashboard is the
 * contract's view — what each job has locked up and when it comes back. The FDR register is
 * the bank's — what certificates the company holds, and how much of that is free to bid with.
 * An FDR appears on both and is one row on the second whether it is pledged to a contract, to
 * a tender that was refused, or to nothing at all.</p>
 *
 * <p>Site floats is the third, and it is here rather than beside the expense register because
 * it answers the treasury's own question and not the expense register's. Every other screen in
 * the app asks what a job cost. This asks where the company's cash is standing right now — and
 * the answer includes some thousands of rupees that are not in a bank, not with a department
 * and not with a supplier, but in the pocket of a man on a site.</p>
 */
export function TreasuryTabs() {
  const { pathname } = useLocation();
  const current = pathname.startsWith('/treasury/fdrs')
    ? '/treasury/fdrs'
    : pathname.startsWith('/treasury/floats')
      ? '/treasury/floats'
      : '/treasury';

  return (
    <Tabs value={current} variant="scrollable" scrollButtons="auto">
      <Tab label="Deposits by contract" value="/treasury" component={Link} to="/treasury" />
      <Tab
        label="Fixed deposits"
        value="/treasury/fdrs"
        component={Link}
        to="/treasury/fdrs"
      />
      <Tab
        label="Site floats"
        value="/treasury/floats"
        component={Link}
        to="/treasury/floats"
      />
    </Tabs>
  );
}
