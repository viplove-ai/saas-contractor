import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { apiErrorDetail } from '../../shared/apiClient';
import { formatAmount, formatQuantity } from '../../shared/formatters';
import { useAuth } from '../auth/AuthContext';
import { RecordAdvanceDialog } from './RecordAdvanceDialog';
import { useVendorAccount, useVendorLabour, useVendorPayments, useVendorPurchases } from './api';
import type { VendorAccount } from './types';

/**
 * One supplier's account, in the four questions somebody actually asks about him.
 *
 * <p>What has he sent, what has he billed, what has gone out to him, and where does that
 * leave us. The last one is deliberately not a single number: the advance sitting against no
 * bill is the figure both sides forget, and a net balance is the one that reconciles against
 * nothing the supplier is holding. So the tiles show the terms, and the net comes last with
 * its sign spelled out in words.</p>
 */
export function VendorDetailPage() {
  const { vendorId = '' } = useParams();
  const { hasPermission } = useAuth();
  const account = useVendorAccount(vendorId);
  const purchases = useVendorPurchases(vendorId);
  const payments = useVendorPayments(vendorId);
  const labour = useVendorLabour(vendorId);
  const [advanceOpen, setAdvanceOpen] = useState(false);

  const canPay = hasPermission('payment:record');

  if (account.isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', py: 6 }}>
        <CircularProgress />
      </Box>
    );
  }
  if (account.isError || !account.data) {
    return <Alert severity="error">{apiErrorDetail(account.error)}</Alert>;
  }

  const figures = account.data;

  return (
    <Stack spacing={3}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        justifyContent="space-between"
        alignItems={{ sm: 'center' }}
      >
        <Stack spacing={0.5}>
          <Typography variant="h1">{figures.vendorName}</Typography>
          <Typography color="text.secondary">
            {figures.vendorCode} ·{' '}
            <Link to="/vendors" style={{ color: 'inherit' }}>
              back to the register
            </Link>
          </Typography>
        </Stack>
        {canPay && (
          <Button variant="contained" color="secondary" onClick={() => setAdvanceOpen(true)}>
            Pay an advance
          </Button>
        )}
      </Stack>

      <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
        <Figure label="Billed" value={figures.billedAmount} hint="Approved bills from him" />
        <Figure
          label="Paid against bills"
          value={figures.paidAgainstBills}
          hint="Cash out against those bills"
        />
        <Figure
          label="Advance paid"
          value={figures.advancePaid}
          hint="Cash out against no bill of his"
        />
        <Figure
          label="Outstanding"
          value={figures.outstanding}
          hint={
            figures.openBills === 0
              ? 'Nothing owed on bills raised'
              : `${figures.openBills} open bill(s)${
                  figures.oldestUnpaidDate ? `, oldest ${figures.oldestUnpaidDate}` : ''
                }`
          }
        />
      </Stack>

      <Alert severity={figures.netPosition > 0 ? 'warning' : 'info'}>{netSentence(figures)}</Alert>

      <Typography variant="h2" sx={{ fontSize: '1.25rem' }}>
        What he has sent
      </Typography>
      <Typography color="text.secondary">
        {figures.deliveryCount === 0
          ? 'No deliveries booked against him yet.'
          : `${figures.deliveryCount} deliveries, ${formatAmount(figures.purchasedValue)} priced so far.`}
      </Typography>
      {purchases.isError && <Alert severity="error">{apiErrorDetail(purchases.error)}</Alert>}
      {purchases.data && purchases.data.length > 0 && (
        <Paper elevation={0} sx={{ border: 1, borderColor: 'divider', overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Date</TableCell>
                <TableCell>Material</TableCell>
                <TableCell align="right">Quantity</TableCell>
                <TableCell align="right">Rate</TableCell>
                <TableCell align="right">Amount</TableCell>
                <TableCell>Bill</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {purchases.data.map((line) => (
                <TableRow key={`${line.receiptId}-${line.materialId}`} hover>
                  <TableCell>
                    {line.receiptDate}
                    <Typography variant="caption" color="text.secondary" display="block">
                      {line.grnNumber}
                    </Typography>
                  </TableCell>
                  <TableCell>{line.materialName ?? line.materialCode ?? '—'}</TableCell>
                  <TableCell align="right">
                    {formatQuantity(line.quantity, line.unitCode)}
                  </TableCell>
                  {/*
                    A rate that is missing is not a rate of nothing. The delivery happened and
                    is worth listing; what it lacks is the office's price against the invoice,
                    and saying so is how the row gets attended to.
                  */}
                  <TableCell align="right">
                    {line.rate === undefined ? (
                      <Typography variant="caption" color="text.secondary">
                        not priced
                      </Typography>
                    ) : (
                      formatAmount(line.rate)
                    )}
                  </TableCell>
                  <TableCell align="right">{formatAmount(line.amount)}</TableCell>
                  <TableCell>
                    {line.invoiceNumber || '—'}
                    {!line.received && (
                      <Chip size="small" label="Not checked in" sx={{ ml: 1 }} />
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      )}

      {/*
        Where his men have worked. Nothing had to be set up for him to be "used" on a site:
        he becomes used the moment a supervisor names him on a day's labour, and this is that
        record read backwards. Only shown when there is something to show — a cement dealer
        has no labour days and an empty table would imply he should.
      */}
      {labour.data && labour.data.length > 0 && (
        <>
          <Typography variant="h2" sx={{ fontSize: '1.25rem' }}>
            Where his men have worked
          </Typography>
          <Paper elevation={0} sx={{ border: 1, borderColor: 'divider', overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Date</TableCell>
                  <TableCell>Site</TableCell>
                  <TableCell align="right">Men</TableCell>
                  <TableCell align="right">Man-hours</TableCell>
                  <TableCell>He was there</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {labour.data.map((day) => (
                  <TableRow key={`${day.siteId}-${day.date}`} hover>
                    <TableCell>{day.date}</TableCell>
                    <TableCell>{day.siteCode ?? day.siteName ?? '—'}</TableCell>
                    <TableCell align="right">{day.headCount}</TableCell>
                    {/* Nobody noted hours is not nought hours, so it reads as a gap. */}
                    <TableCell align="right">{day.manHours ?? '—'}</TableCell>
                    <TableCell>
                      {day.supplierPresent ? (
                        <Chip
                          size="small"
                          color="success"
                          label={day.representativeName ?? 'Yes'}
                        />
                      ) : (
                        <Chip size="small" variant="outlined" label="No" />
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Paper>
        </>
      )}

      <Typography variant="h2" sx={{ fontSize: '1.25rem' }}>
        What has gone out to him
      </Typography>
      {payments.isError && <Alert severity="error">{apiErrorDetail(payments.error)}</Alert>}
      {payments.data?.length === 0 && (
        <Typography color="text.secondary">Nothing has been paid to him yet.</Typography>
      )}
      {payments.data && payments.data.length > 0 && (
        <Paper elevation={0} sx={{ border: 1, borderColor: 'divider', overflowX: 'auto' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>Date</TableCell>
                <TableCell>Payment</TableCell>
                <TableCell>Against</TableCell>
                <TableCell>Mode</TableCell>
                <TableCell align="right">Amount</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {payments.data.map((payment) => (
                <TableRow key={payment.id} hover>
                  <TableCell>{payment.paymentDate}</TableCell>
                  <TableCell>
                    {payment.paymentNumber}
                    {payment.referenceNumber && (
                      <Typography variant="caption" color="text.secondary" display="block">
                        {payment.referenceNumber}
                      </Typography>
                    )}
                  </TableCell>
                  {/* The absence of a bill is the fact, so it is named rather than blank. */}
                  <TableCell>
                    {payment.expenseNumber ?? (
                      <Chip size="small" color="secondary" label="Advance, no bill" />
                    )}
                  </TableCell>
                  <TableCell>{payment.paymentMode}</TableCell>
                  <TableCell align="right">{formatAmount(payment.amount)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
      )}

      <RecordAdvanceDialog
        open={advanceOpen}
        vendorId={vendorId}
        vendorName={figures.vendorName}
        onClose={() => setAdvanceOpen(false)}
      />
    </Stack>
  );
}

/**
 * The net, as a sentence rather than a signed number. "−25,000" is read as an amount owed
 * by whoever is looking at it about half the time; "we are ahead of him" is not.
 */
function netSentence(figures: VendorAccount): string {
  if (figures.netPosition > 0) {
    return `He is owed ${formatAmount(figures.netPosition)} after setting the advance off against his open bills.`;
  }
  if (figures.netPosition < 0) {
    return `We are ahead of him by ${formatAmount(-figures.netPosition)} — that much advance is sitting against no bill yet.`;
  }
  return 'The account is square: nothing owed either way.';
}

function Figure({ label, value, hint }: { label: string; value: number; hint: string }) {
  return (
    <Paper
      elevation={0}
      sx={{ p: 2, border: 1, borderColor: 'divider', minWidth: 200, flex: '1 1 200px' }}
    >
      <Typography variant="overline" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h2" sx={{ fontSize: '1.5rem' }}>
        {formatAmount(value)}
      </Typography>
      <Typography variant="caption" color="text.secondary">
        {hint}
      </Typography>
    </Paper>
  );
}
