import AddIcon from '@mui/icons-material/Add';
import HelpOutlineIcon from '@mui/icons-material/HelpOutline';
import PrintIcon from '@mui/icons-material/Print';
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  List,
  ListItemButton,
  ListItemText,
  Stack,
  Tab,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { BillingGuide } from './BillingGuide';
import { downloadBlankSheets, useSheets } from './api';

/*
  Every sheet on a project, newest first, with the unbilled ones first-class because that is
  the question the engineer actually has: what have I measured that has not been paid for.
*/
export function SheetRegisterPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const sheets = useSheets(projectId);

  // Printing blank paper. The serials are permanent and never reprinted, so the dialog asks
  // where this run starts rather than guessing — the duplicate guard in the register is only
  // meaningful if a sheet number exists on exactly one piece of paper.
  const [guideOpen, setGuideOpen] = useState(false);
  const [printOpen, setPrintOpen] = useState(false);
  const [from, setFrom] = useState('1');
  const [count, setCount] = useState('20');
  const [printing, setPrinting] = useState(false);
  const [printError, setPrintError] = useState<string | null>(null);

  const print = async () => {
    setPrinting(true);
    setPrintError(null);
    try {
      await downloadBlankSheets(Number(from), Number(count));
      setPrintOpen(false);
    } catch (caught) {
      setPrintError(apiErrorDetail(caught));
    } finally {
      setPrinting(false);
    }
  };

  if (sheets.isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}>
        <CircularProgress />
      </Box>
    );
  }

  const rows = sheets.data ?? [];

  return (
    <Stack spacing={2} sx={{ p: 2, pb: 10 }}>
      <Tabs value={0} onChange={(_, value) => value === 1 && navigate(`/billing/${projectId}/bills`)}>
        <Tab label="Measurements" />
        <Tab label="Bills" />
      </Tabs>

      <Button startIcon={<HelpOutlineIcon />} size="small" onClick={() => setGuideOpen(true)}>
        How billing works
      </Button>

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <Button
          variant="contained"
          startIcon={<AddIcon />}
          component={Link}
          to={`/billing/sheets/new?projectId=${projectId}`}
          sx={{ flex: 1 }}
        >
          New measurement sheet
        </Button>
        <Button
          variant="outlined"
          startIcon={<PrintIcon />}
          onClick={() => setPrintOpen(true)}
          sx={{ flex: 1 }}
        >
          Print blank sheets
        </Button>
      </Stack>

      {rows.length === 0 && (
        <Alert severity="info">
          Nothing measured yet. Record a sheet from the engineer's page and sign it, and it will
          be waiting for the next bill.
        </Alert>
      )}

      <List>
        {rows.map((sheet) => (
          <ListItemButton
            key={sheet.id}
            component={Link}
            to={`/billing/sheets/${sheet.id}?projectId=${projectId}`}
            divider
          >
            <ListItemText
              primary={`${sheet.itemNumber} · ${Number(sheet.claimedQuantity).toFixed(2)}`}
              secondary={
                `${sheet.sheetSerial ? sheet.sheetSerial + ' · ' : ''}${sheet.measuredOn}` +
                (sheet.locationNote ? ` · ${sheet.locationNote}` : '')
              }
            />
            <Stack direction="row" spacing={1}>
              {!sheet.totalsAgree && <Chip size="small" color="warning" label="Totals differ" />}
              <Chip
                size="small"
                variant="outlined"
                color={sheet.raBillId ? 'default' : sheet.status === 'SIGNED' ? 'success' : 'warning'}
                label={sheet.raBillId ? 'Billed' : sheet.status === 'SIGNED' ? 'Signed' : 'Draft'}
              />
            </Stack>
          </ListItemButton>
        ))}
      </List>

      <BillingGuide open={guideOpen} onClose={() => setGuideOpen(false)} />

      <Dialog open={printOpen} onClose={() => setPrintOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle>Print blank measurement sheets</DialogTitle>
        <DialogContent dividers>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <Typography variant="body2" color="text.secondary">
              One generic sheet for every tender — the item number is written on the paper and
              the project and item are chosen here when the page is entered.
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Each sheet is numbered, and the register refuses a number it has already seen. So
              start this run where the last one ended, and never reprint a number.
            </Typography>
            {printError && <Alert severity="error">{printError}</Alert>}
            <Stack direction="row" spacing={2}>
              <TextField
                label="Start at sheet no."
                type="number"
                value={from}
                onChange={(event) => setFrom(event.target.value)}
                sx={{ flex: 1 }}
              />
              <TextField
                label="How many"
                type="number"
                value={count}
                onChange={(event) => setCount(event.target.value)}
                helperText="20 for a trial"
                sx={{ flex: 1 }}
              />
            </Stack>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPrintOpen(false)}>Cancel</Button>
          <Button variant="contained" disabled={printing} onClick={() => void print()}>
            Download PDF
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}
