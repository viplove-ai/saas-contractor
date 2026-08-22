import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MobileStepper,
  Stack,
  Typography,
} from '@mui/material';
import { useState } from 'react';

/*
  Billing, explained to the man who has to do it, in the order he does it.

  It is a stepper rather than an overlay tour on purpose. An overlay has to know where every
  button sits, so it breaks silently the first time a screen is rearranged and is then teaching
  the wrong thing — worse than teaching nothing. This says what to do and why, works before he
  has opened any of the screens, works with no signal, and can be read again at three in the
  afternoon without starting a workflow.

  Each step carries one "watch out" line, because the questions an engineer actually asks in a
  handover are never "which button" — they are "what happens if I got it wrong" and "why won't
  it let me".
*/

interface Step {
  title: string;
  what: string;
  watch: string;
}

const STEPS: Step[] = [
  {
    title: '1. Print the blank sheets',
    what:
      'On the Measurements tab, tap Print blank sheets and download the PDF. Print it, and get '
      + 'a set bound with carbon copies. One book works for every tender — you write the item '
      + 'number on the page yourself.',
    watch:
      'Every sheet is numbered and the app refuses a number it has already seen. So start each '
      + 'print run where the last one ended, and never print the same numbers twice.',
  },
  {
    title: '2. Measure on site, on paper',
    what:
      'Tape in hand, one item per page. Fill Nos, ×, and whichever of L, B and H apply. Write '
      + 'what each row comes to in the Qty column, and the block total at the foot. Sign and '
      + 'date the page.',
    watch:
      'Put a dash where a dimension does not apply — never leave it blank and never write 0. '
      + 'A linear item has no breadth, and a zero would say the work had none.',
  },
  {
    title: '3. Enter the page in the app',
    what:
      'Measurements → New measurement sheet. Pick the item, type the sheet number and the '
      + 'rows. The boxes shown change with the item’s unit: cum asks for L, B and H, sqm '
      + 'for L and B, metre for L only.',
    watch:
      'Use the copy-row button — most rows differ from the one above by a single dimension. '
      + 'Tick Deduction for an opening taken out; it goes in as a minus.',
  },
  {
    title: '4. Type your written total, and sign',
    what:
      'At the foot, type the total you worked out by hand on the paper. The app adds the rows '
      + 'up itself and compares. If they agree, Save & sign.',
    watch:
      'If they disagree it will not let you sign, and it tells you the difference. That is the '
      + 'check working: one of the rows is wrong, or your total is. Find it before signing.',
  },
  {
    title: '5. Prepare the bill',
    what:
      'Bills tab. The top of the screen shows everything measured and not yet billed, with its '
      + 'value. Set the date to bill up to, and tap Prepare bill.',
    watch:
      'The first bill of a tender asks once for the agreement number, contractor, who measured, '
      + 'the rate adjustments, and which schedule of rates it was let under. It offers what the '
      + 'tender notice said, so you are confirming rather than typing. Later bills never ask '
      + 'again.',
  },
  {
    title: '6. Download the Excel',
    what:
      'Open the bill and tap Download Excel. You get the whole workbook — Front Page, '
      + 'Measurement Book, Abstract of Cost, Bill Form (CPWA-26), Recovery Statement and '
      + 'Deviation Statement — with the formulas live and the sheets linked to each other.',
    watch:
      'You can download before the bill is passed. The file says DRAFT in its name, and that is '
      + 'the copy to take to the department to get your measurements checked.',
  },
  {
    title: '7. Submit, and pass',
    what:
      'Submit → the office marks it checked → Pass. Passing freezes the figures: that is the '
      + 'record of what was paid.',
    watch:
      'After passing, nothing on that bill can change. Found an error? Enter a fresh sheet with '
      + 'minus rows, dated when you found it, and it goes on the next bill. That is how the '
      + 'department expects a correction to be made.',
  },
];

interface Props {
  open: boolean;
  onClose: () => void;
}

export function BillingGuide({ open, onClose }: Props) {
  const [step, setStep] = useState(0);
  // Clamped rather than asserted: an index out of range should show the first step, not throw
  // a blank dialog at somebody who only wanted to read the instructions.
  const current = STEPS[Math.min(Math.max(step, 0), STEPS.length - 1)] ?? STEPS[0]!;
  const isLast = step >= STEPS.length - 1;

  const close = () => {
    setStep(0);
    onClose();
  };

  return (
    <Dialog open={open} onClose={close} fullWidth maxWidth="sm">
      <DialogTitle>How billing works</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={2} sx={{ minHeight: 260 }}>
          <Typography variant="subtitle1" fontWeight={600}>
            {current.title}
          </Typography>
          <Typography variant="body1">{current.what}</Typography>
          <Alert severity="info" icon={<CheckCircleIcon fontSize="inherit" />}>
            {current.watch}
          </Alert>
          <Box sx={{ flex: 1 }} />
          <Typography variant="caption" color="text.secondary">
            Paper to bill in seven steps. You can reopen this any time from the Bills tab.
          </Typography>
        </Stack>
      </DialogContent>
      <MobileStepper
        variant="dots"
        steps={STEPS.length}
        position="static"
        activeStep={step}
        sx={{ bgcolor: 'transparent' }}
        nextButton={
          isLast ? (
            <Button size="small" onClick={close}>
              Done
            </Button>
          ) : (
            <Button size="small" onClick={() => setStep((s) => s + 1)}>
              Next
            </Button>
          )
        }
        backButton={
          <Button size="small" disabled={step === 0} onClick={() => setStep((s) => s - 1)}>
            Back
          </Button>
        }
      />
      <DialogActions>
        <Button onClick={close}>Close</Button>
      </DialogActions>
    </Dialog>
  );
}
