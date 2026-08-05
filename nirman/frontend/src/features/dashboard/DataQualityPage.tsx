import {
  Alert,
  Chip,
  CircularProgress,
  MenuItem,
  Paper,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { monthToDate, useDataQuality, useSites } from './api';
import { PeriodPicker } from './PeriodPicker';
import type { QualityFinding } from './types';

/**
 * What is missing or unfinished in the records.
 *
 * <p>Every finding carries what to do about it and the evidence behind the count, because a
 * data-quality dashboard that only counts problems is one that gets ignored by the second
 * week. "Eleven days unmarked" is a complaint; "eleven days unmarked, and here they are" is a
 * task somebody can finish before lunch.</p>
 *
 * <p>Two severities and not five. A five-level scale invites arguments about whether something
 * is a three or a four, and nobody ever acts on a three.</p>
 */
export function DataQualityPage() {
  const initial = monthToDate();
  const [siteId, setSiteId] = useState('');
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);

  const sites = useSites();
  const quality = useDataQuality(siteId || undefined, from, to);

  return (
    <Stack spacing={2}>
      <Typography variant="h1">Data quality</Typography>

      <PeriodPicker from={from} to={to} onFromChange={setFrom} onToChange={setTo}>
        <TextField
          select
          label="Site"
          value={siteId}
          onChange={(e) => setSiteId(e.target.value)}
          sx={{ minWidth: 220 }}
        >
          <MenuItem value="">Every site you can see</MenuItem>
          {(sites.data ?? []).map((site) => (
            <MenuItem key={site.id} value={site.id}>
              {site.code} — {site.name}
            </MenuItem>
          ))}
        </TextField>
      </PeriodPicker>

      {quality.isLoading && <CircularProgress />}
      {quality.isError && <Alert severity="error">{apiErrorDetail(quality.error)}</Alert>}

      {quality.data && (
        <>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography color="text.secondary">{quality.data.scopeName}</Typography>
            <Chip
              label={`${quality.data.actCount} to act on`}
              color={quality.data.actCount > 0 ? 'error' : 'default'}
              variant={quality.data.actCount > 0 ? 'filled' : 'outlined'}
            />
            <Chip label={`${quality.data.watchCount} to watch`} variant="outlined" />
          </Stack>

          {quality.data.findings.length === 0 && (
            <Alert severity="success">
              Nothing missing in this window. Every day has a muster, every issue is charged to a
              work item, and no report is waiting on a signature.
            </Alert>
          )}

          {/* Act before watch: the list is a work queue, not a report. */}
          {[...quality.data.findings]
            .sort((a, b) => (a.severity === b.severity ? 0 : a.severity === 'ACT' ? -1 : 1))
            .map((finding) => (
              <FindingCard key={finding.code} finding={finding} />
            ))}

          <Alert severity="info">{quality.data.caveat}</Alert>
        </>
      )}
    </Stack>
  );
}

function FindingCard({ finding }: { finding: QualityFinding }) {
  const act = finding.severity === 'ACT';
  return (
    <Paper
      elevation={0}
      sx={{
        p: 2,
        border: 1,
        borderColor: act ? 'error.main' : 'divider',
        borderLeftWidth: 4,
      }}
    >
      <Stack direction="row" spacing={1} alignItems="center" justifyContent="space-between">
        <Typography fontWeight={600}>{finding.title}</Typography>
        <Chip
          label={`${finding.count}`}
          size="small"
          color={act ? 'error' : 'default'}
          variant={act ? 'filled' : 'outlined'}
        />
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
        {finding.detail}
      </Typography>
      <Typography variant="body2" sx={{ mt: 1 }} fontWeight={600}>
        {finding.whatToDo}
      </Typography>
      {finding.examples.length > 0 && (
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
          {finding.examples.map((example) => (
            <Chip key={example} label={example} size="small" variant="outlined" />
          ))}
        </Stack>
      )}
    </Paper>
  );
}
