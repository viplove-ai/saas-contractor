import { Alert, Box, Divider, Paper, Stack, Typography } from '@mui/material';
import { formatAmount } from '../../shared/formatters';
import type { MaterialPosition } from './types';

/**
 * Material as three figures that add up, which is the Phase 6 exit criterion drawn rather
 * than described.
 *
 * <p>The layout is the argument. Opening, received and consumed are set out as an equation
 * that lands on the inventory value the stores actually hold, so a reader can check it on the
 * screen instead of taking it on trust — and the residual is printed even when it is zero,
 * because a reconciliation you only see when it fails is one nobody believes when it passes.</p>
 *
 * <p><b>Purchased sits below the rule, deliberately.</b> It comes from the bills, not the
 * ledger, and it is not a fourth term of the same sum: material becomes cost when it is
 * issued, so adding purchases to consumption would count the same cement twice and overstate
 * the project by the value of its own stockyard.</p>
 */
export function MaterialPositionCard({ material }: { material: MaterialPosition }) {
  return (
    <Paper elevation={0} sx={{ p: 2, border: 1, borderColor: 'divider' }}>
      <Typography fontWeight={600} gutterBottom>
        Material
      </Typography>

      <Stack
        direction="row"
        spacing={2}
        alignItems="flex-end"
        flexWrap="wrap"
        useFlexGap
        sx={{ mb: 1 }}
      >
        <Term label="Opening" value={material.openingValue} />
        <Operator>+</Operator>
        <Term label="Received" value={material.received} />
        <Operator>−</Operator>
        <Term label="Consumed" value={material.consumed} />
        <Operator>=</Operator>
        <Term label="In store now" value={material.inventoryValue} strong />
        {material.residual !== 0 && (
          <>
            <Operator>+</Operator>
            <Term label="Unexplained" value={material.residual} />
          </>
        )}
      </Stack>

      <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
        <Term label="Issued" value={material.issued} small />
        {/*
          Wastage is its own figure rather than part of consumption. Both leave the store and
          both are cost, but a site losing eight per cent of its cement to breakage needs that
          as a number it can see.
        */}
        <Term label="Wasted" value={material.wasted} small />
      </Stack>

      <Divider sx={{ my: 1.5 }} />

      <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap>
        <Term label="Purchased (from bills)" value={material.purchased} small />
        <Term label="Billed, not yet received" value={material.purchasedNotReceived} small />
      </Stack>

      <Alert severity={material.reconciles ? 'info' : 'warning'} sx={{ mt: 1.5 }}>
        {material.note}
      </Alert>
    </Paper>
  );
}

function Term({
  label,
  value,
  strong,
  small,
}: {
  label: string;
  value: number;
  strong?: boolean;
  small?: boolean;
}) {
  return (
    <Box>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography
        fontWeight={strong ? 700 : 600}
        sx={{ fontSize: small ? '0.95rem' : '1.1rem' }}
      >
        {formatAmount(value)}
      </Typography>
    </Box>
  );
}

function Operator({ children }: { children: string }) {
  return (
    <Typography color="text.secondary" sx={{ fontSize: '1.25rem', pb: 0.5 }} aria-hidden>
      {children}
    </Typography>
  );
}
