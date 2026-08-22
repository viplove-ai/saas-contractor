import AddIcon from '@mui/icons-material/Add';
import DescriptionOutlinedIcon from '@mui/icons-material/DescriptionOutlined';
import MenuBookIcon from '@mui/icons-material/MenuBook';
import ReceiptLongIcon from '@mui/icons-material/ReceiptLong';
import StraightenIcon from '@mui/icons-material/Straighten';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Divider,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { formatAmount } from '../../shared/formatters';
import { ImportTenderDialog } from './ImportTenderDialog';
import { useBillingProjects } from './api';
import type { BillStatus, BillingProjectSummary } from './types';

/*
  The door into billing.

  Tenders imported only to bill and tenders the whole system runs stand in one list, because a
  tender imported only to bill is still a tender and the engineer looking for it should not
  have to remember which way it was set up. The chip says which it is; the filter is there for
  when that distinction is the thing being looked for, the way the deleted list works on the
  projects screen.

  Each card answers the question somebody actually arrives with: is there anything of mine
  waiting here, and where did this tender's billing get to.
*/

const BILL_STATUS_COLOR: Record<BillStatus, 'default' | 'warning' | 'info' | 'success'> = {
  DRAFT: 'warning',
  SUBMITTED: 'info',
  CHECKED: 'info',
  PASSED: 'success',
};

export function BillingProjectsPage() {
  const projects = useBillingProjects();
  const [importOpen, setImportOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [billingOnly, setBillingOnly] = useState(false);

  const rows = useMemo(() => {
    const all = projects.data ?? [];
    const needle = search.trim().toLowerCase();
    return all.filter((project) => {
      if (billingOnly && !project.billingOnly) return false;
      if (needle === '') return true;
      return (
        project.code.toLowerCase().includes(needle) ||
        project.name.toLowerCase().includes(needle) ||
        (project.agreementNo ?? '').toLowerCase().includes(needle)
      );
    });
  }, [projects.data, search, billingOnly]);

  if (projects.isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}>
        <CircularProgress />
      </Box>
    );
  }

  const total = (projects.data ?? []).length;
  const waiting = (projects.data ?? []).reduce((sum, p) => sum + p.unbilledSheets, 0);

  return (
    <Stack spacing={2} sx={{ p: 2, pb: 10 }}>
      <Stack direction="row" alignItems="center" spacing={1}>
        <Typography variant="h6" sx={{ flex: 1 }}>
          Billing
        </Typography>
        {waiting > 0 && (
          <Chip
            size="small"
            color="warning"
            label={`${waiting} sheet${waiting === 1 ? '' : 's'} waiting`}
          />
        )}
      </Stack>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => setImportOpen(true)}>
          Bill a tender
        </Button>
        <Button startIcon={<MenuBookIcon />} component={Link} to="/billing/vault">
          Reference documents
        </Button>
      </Stack>

      {total > 0 && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems="center">
          <TextField
            size="small"
            label="Search"
            placeholder="Code, name or agreement no."
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            sx={{ flex: 1, width: '100%' }}
          />
          {/*
            A toggle rather than another value in a picker: billing-only is not a stage a
            tender passes through, it is a different kind of record — the same reason the
            projects screen keeps deleted on its own switch.
          */}
          <Chip
            label="Billing only"
            color={billingOnly ? 'primary' : 'default'}
            variant={billingOnly ? 'filled' : 'outlined'}
            onClick={() => setBillingOnly((current) => !current)}
          />
        </Stack>
      )}

      {total === 0 && (
        <Alert severity="info">
          No tenders yet. Upload a NIT and its schedule of quantities becomes a tender you can
          bill against.
        </Alert>
      )}
      {total > 0 && rows.length === 0 && (
        <Alert severity="info">Nothing matches that.</Alert>
      )}

      <Stack component="ul" spacing={1.5} sx={{ listStyle: 'none', p: 0, m: 0 }}>
        {rows.map((project) => (
          <BillingProjectCard key={project.id} project={project} />
        ))}
      </Stack>

      <ImportTenderDialog open={importOpen} onClose={() => setImportOpen(false)} />
    </Stack>
  );
}

function BillingProjectCard({ project }: { project: BillingProjectSummary }) {
  const hasWork = project.unbilledSheets > 0 || project.draftSheets > 0;

  return (
    <Paper component="li" elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
      <Stack spacing={1.5}>
        <Stack direction="row" alignItems="flex-start" justifyContent="space-between" spacing={1}>
          <Box
            component={Link}
            to={`/billing/${project.id}/sheets`}
            sx={{ textDecoration: 'none', color: 'inherit', minWidth: 0, flex: 1 }}
          >
            <Typography fontWeight={700}>{project.code}</Typography>
            {/* noWrap needs the parent allowed to shrink, hence minWidth: 0 above. */}
            <Typography variant="body2" color="text.secondary" noWrap>
              {project.name}
            </Typography>
          </Box>
          {project.billingOnly && (
            <Chip size="small" variant="outlined" label="Billing only" />
          )}
        </Stack>

        {(project.agreementNo || project.contractorName) && (
          <Typography variant="caption" color="text.secondary" noWrap>
            {[project.agreementNo, project.contractorName].filter(Boolean).join(' · ')}
          </Typography>
        )}

        <Divider />

        <Stack direction="row" spacing={2} justifyContent="space-between" alignItems="baseline">
          <Metric
            icon={<DescriptionOutlinedIcon fontSize="inherit" />}
            label="Schedule"
            value={`${project.boqItemCount} item${project.boqItemCount === 1 ? '' : 's'}`}
          />
          <Metric
            icon={<StraightenIcon fontSize="inherit" />}
            label="Waiting"
            value={
              project.unbilledSheets === 0 ? '—' : `${project.unbilledSheets} sheet${
                project.unbilledSheets === 1 ? '' : 's'}`
            }
            emphasis={project.unbilledSheets > 0}
          />
          <Metric
            icon={<ReceiptLongIcon fontSize="inherit" />}
            label="Billed to date"
            value={
              project.grossBilledToDate === null
                ? '—'
                : formatAmount(Number(project.grossBilledToDate))
            }
            align="flex-end"
          />
        </Stack>

        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          {project.lastBillTitle && project.lastBillStatus && (
            <Chip
              size="small"
              color={BILL_STATUS_COLOR[project.lastBillStatus]}
              variant={project.lastBillStatus === 'PASSED' ? 'filled' : 'outlined'}
              label={`${project.lastBillTitle} · ${project.lastBillStatus.toLowerCase()}`}
            />
          )}
          {project.billCount === 0 && (
            <Chip size="small" variant="outlined" label="No bills yet" />
          )}
          {project.draftSheets > 0 && (
            <Chip
              size="small"
              color="warning"
              variant="outlined"
              label={`${project.draftSheets} unsigned`}
            />
          )}
          {/* Only worth saying once there is work to bill — before that it is not yet a gap. */}
          {!project.agreementRecorded && hasWork && (
            <Chip size="small" color="info" variant="outlined" label="Tender details needed" />
          )}
        </Stack>

        <Stack direction="row" spacing={1}>
          <Button size="small" component={Link} to={`/billing/${project.id}/sheets`}>
            Measurements
          </Button>
          <Button size="small" component={Link} to={`/billing/${project.id}/bills`}>
            Bills
          </Button>
        </Stack>
      </Stack>
    </Paper>
  );
}

function Metric({
  icon,
  label,
  value,
  emphasis,
  align = 'flex-start',
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  emphasis?: boolean;
  align?: 'flex-start' | 'flex-end';
}) {
  return (
    <Stack spacing={0.25} alignItems={align} sx={{ minWidth: 0 }}>
      <Stack direction="row" spacing={0.5} alignItems="center" sx={{ color: 'text.secondary' }}>
        <Box sx={{ display: 'flex', fontSize: 14 }}>{icon}</Box>
        <Typography variant="caption">{label}</Typography>
      </Stack>
      <Typography
        variant="body2"
        fontWeight={emphasis ? 700 : 600}
        color={emphasis ? 'warning.main' : 'text.primary'}
        noWrap
      >
        {value}
      </Typography>
    </Stack>
  );
}
