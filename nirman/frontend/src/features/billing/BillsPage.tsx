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
  Divider,
  List,
  ListItemButton,
  ListItemText,
  Paper,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import DownloadIcon from '@mui/icons-material/Download';
import HelpOutlineIcon from '@mui/icons-material/HelpOutline';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { AgreementDialog } from './AgreementDialog';
import { BillingGuide } from './BillingGuide';
import {
  downloadBillExcel,
  useAgreement,
  useBill,
  useBills,
  useCreateBill,
  useDecideBill,
  useUnbilled,
} from './api';

/*
  The bill series, and the queue that feeds it.

  Two things this screen has to make obvious, because they are the two the spreadsheet could
  not. What is measured and not yet paid for — the queue — and what a passed bill said, which
  is a snapshot and does not move when more work is measured behind it.
*/
export function BillsPage() {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();

  const agreement = useAgreement(projectId);
  const unbilled = useUnbilled(projectId);
  const bills = useBills(projectId);
  const createBill = useCreateBill();
  const decideBill = useDecideBill();

  const [cutoff, setCutoff] = useState(() => new Date().toISOString().slice(0, 10));
  const [openBillId, setOpenBillId] = useState<string | null>(null);
  const [agreementOpen, setAgreementOpen] = useState(false);
  const [guideOpen, setGuideOpen] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const openBill = useBill(openBillId ?? undefined);

  const prepare = async () => {
    setError(null);
    // The tender's own details are asked once, at its first bill, and the server refuses
    // without them — so ask here rather than letting the refusal be the way he finds out.
    if (!agreement.data) {
      setAgreementOpen(true);
      return;
    }
    try {
      const bill = await createBill.mutateAsync({ projectId: projectId ?? '', cutoffDate: cutoff });
      setOpenBillId(bill.id);
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  };

  const decide = async (action: string) => {
    if (!openBillId) return;
    setError(null);
    try {
      await decideBill.mutateAsync({ id: openBillId, action });
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  };

  if (unbilled.isLoading || bills.isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}>
        <CircularProgress />
      </Box>
    );
  }

  const queue = unbilled.data;

  return (
    <Stack spacing={2} sx={{ p: 2, pb: 10 }}>
      <Tabs value={1} onChange={(_, value) => value === 0 && navigate(`/billing/${projectId}/sheets`)}>
        <Tab label="Measurements" />
        <Tab label="Bills" />
      </Tabs>

      {error && <Alert severity="error">{error}</Alert>}

      <Button startIcon={<HelpOutlineIcon />} size="small" onClick={() => setGuideOpen(true)}>
        How billing works
      </Button>

      <Paper sx={{ p: 2 }}>
        <Stack spacing={1.5}>
          <Stack direction="row" alignItems="center">
            <Typography variant="subtitle1" sx={{ flex: 1 }}>
              Measured, not yet billed
            </Typography>
            <Typography variant="h6" sx={{ fontVariantNumeric: 'tabular-nums' }}>
              ₹{Number(queue?.totalValue ?? 0).toLocaleString('en-IN')}
            </Typography>
          </Stack>

          {(queue?.overClaimed.length ?? 0) > 0 && (
            <Alert severity="warning">
              Measured past the contract quantity on {queue?.overClaimed.join(', ')}. A deviation
              is the department's to decide — this is only saying so out loud.
            </Alert>
          )}

          {(queue?.items.length ?? 0) === 0 ? (
            <Alert severity="info">
              Nothing waiting. Sign a measurement sheet and it will appear here.
            </Alert>
          ) : (
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Item</TableCell>
                  <TableCell align="right">Unbilled</TableCell>
                  <TableCell align="right">Rate</TableCell>
                  <TableCell align="right">Value</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {queue?.items.map((item) => (
                  <TableRow key={item.boqItemId}>
                    <TableCell>
                      <Typography variant="body2">{item.itemNumber}</Typography>
                      <Typography variant="caption" color="text.secondary">
                        {item.description.slice(0, 48)} · {item.sheetCount} sheet(s)
                      </Typography>
                    </TableCell>
                    <TableCell align="right">{Number(item.quantityUnbilled).toFixed(2)}</TableCell>
                    <TableCell align="right">{Number(item.rate).toFixed(2)}</TableCell>
                    <TableCell align="right">
                      {Number(item.valueUnbilled).toLocaleString('en-IN')}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}

          <Divider />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems="center">
            <TextField
              label="Bill up to"
              type="date"
              size="small"
              value={cutoff}
              onChange={(event) => setCutoff(event.target.value)}
              InputLabelProps={{ shrink: true }}
            />
            <Button
              variant="contained"
              disabled={createBill.isPending || (queue?.sheetCount ?? 0) === 0}
              onClick={() => void prepare()}
            >
              Prepare bill
            </Button>
          </Stack>
        </Stack>
      </Paper>

      <Typography variant="subtitle1">Bills</Typography>
      {(bills.data ?? []).length === 0 && (
        <Alert severity="info">No bills prepared on this tender yet.</Alert>
      )}
      <List>
        {(bills.data ?? []).map((bill) => (
          <ListItemButton key={bill.id} divider onClick={() => setOpenBillId(bill.id)}>
            <ListItemText
              primary={bill.title}
              secondary={`Up to ${bill.cutoffDate}${bill.revision > 0 ? ` · revision ${bill.revision}` : ''}`}
            />
            <Stack direction="row" spacing={1} alignItems="center">
              {bill.grossWorkDone && (
                <Typography variant="body2" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                  ₹{Number(bill.grossWorkDone).toLocaleString('en-IN')}
                </Typography>
              )}
              <Chip
                size="small"
                variant="outlined"
                color={bill.status === 'PASSED' ? 'success' : 'warning'}
                label={bill.status}
              />
            </Stack>
          </ListItemButton>
        ))}
      </List>

      <Dialog open={Boolean(openBillId)} onClose={() => setOpenBillId(null)} fullWidth maxWidth="md">
        <DialogTitle>{openBill.data?.title ?? 'Bill'}</DialogTitle>
        <DialogContent dividers>
          {openBill.isLoading ? (
            <CircularProgress />
          ) : (
            <Stack spacing={2}>
              {openBill.data?.frozen && (
                <Alert severity="success">
                  Passed. These figures are what was paid — measuring more work moves the next
                  bill, never this one.
                </Alert>
              )}
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Item</TableCell>
                    <TableCell align="right">Since previous</TableCell>
                    <TableCell align="right">Up to date</TableCell>
                    <TableCell align="right">Rate</TableCell>
                    <TableCell align="right">Amount since</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {openBill.data?.items.map((item) => (
                    <TableRow key={item.boqItemId}>
                      <TableCell>{item.itemNumber}</TableCell>
                      <TableCell align="right">{Number(item.qtySincePrevious).toFixed(2)}</TableCell>
                      <TableCell align="right">{Number(item.qtyToDate).toFixed(2)}</TableCell>
                      <TableCell align="right">{Number(item.rate).toFixed(2)}</TableCell>
                      <TableCell align="right">
                        {Number(item.amountSince).toLocaleString('en-IN')}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              <Stack direction="row" justifyContent="space-between">
                <Typography variant="subtitle2">Value since previous bill</Typography>
                <Typography variant="subtitle2">
                  ₹{Number(openBill.data?.valueSincePrevious ?? 0).toLocaleString('en-IN')}
                </Typography>
              </Stack>
              <Stack direction="row" justifyContent="space-between">
                <Typography variant="subtitle1">Gross work done to date</Typography>
                <Typography variant="subtitle1">
                  ₹{Number(openBill.data?.grossWorkDone ?? 0).toLocaleString('en-IN')}
                </Typography>
              </Stack>
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button
            startIcon={<DownloadIcon />}
            disabled={downloading || !openBill.data}
            onClick={() => {
              if (!openBill.data) return;
              setDownloading(true);
              setError(null);
              void downloadBillExcel(openBill.data.id, openBill.data.title)
                .catch((caught) => setError(apiErrorDetail(caught)))
                .finally(() => setDownloading(false));
            }}
          >
            Download Excel
          </Button>
          <Box sx={{ flex: 1 }} />
          {openBill.data?.status === 'DRAFT' && (
            <Button onClick={() => void decide('SUBMIT')}>Submit</Button>
          )}
          {openBill.data?.status === 'SUBMITTED' && (
            <Button onClick={() => void decide('CHECK')}>Mark checked</Button>
          )}
          {(openBill.data?.status === 'SUBMITTED' || openBill.data?.status === 'CHECKED') && (
            <Button variant="contained" onClick={() => void decide('PASS')}>
              Pass bill
            </Button>
          )}
          <Button onClick={() => setOpenBillId(null)}>Close</Button>
        </DialogActions>
      </Dialog>

      <BillingGuide open={guideOpen} onClose={() => setGuideOpen(false)} />

      <AgreementDialog
        projectId={projectId ?? ''}
        open={agreementOpen}
        onClose={() => setAgreementOpen(false)}
        onSaved={() => {
          setAgreementOpen(false);
          void prepare();
        }}
      />
    </Stack>
  );
}
