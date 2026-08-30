import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Stack,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { PRINT_SECTIONS, PRINT_SECTION_HINT, PRINT_SECTION_LABEL, type PrintSection } from './types';

/**
 * What to put on the printed copy.
 *
 * <p>One report goes to three different readers and they are not entitled to the same document.
 * The department wants the work and the conditions; the muster roll carries names and wages and
 * what the day cost is the firm's own business. Until now the only way to send a shortened copy
 * was to print the whole thing and put a pen through it, which is how a wage bill ends up in a
 * department's file.</p>
 *
 * <p>Everything is ticked when it opens. The whole report is the normal thing to print and the
 * dialog should cost one button press to get past — a list that starts empty makes the common
 * case the laborious one.</p>
 *
 * <p><b>The omission is printed.</b> The server names every dropped section in a line at the
 * foot of the page, and the dialog says so, because that is the condition on which offering the
 * choice is honest at all: a copy with no plant table and nothing saying so reads as a site that
 * had no plant, and the department would be right to treat that as the report's answer.</p>
 */
export function PrintDprDialog({
  open,
  dprNumber,
  busy,
  onPrint,
  onClose,
}: {
  open: boolean;
  dprNumber: string;
  busy: boolean;
  onPrint: (sections: PrintSection[]) => void;
  onClose: () => void;
}) {
  const [chosen, setChosen] = useState<PrintSection[]>([...PRINT_SECTIONS]);

  const toggle = (section: PrintSection) =>
    setChosen((current) =>
      current.includes(section)
        ? current.filter((one) => one !== section)
        : PRINT_SECTIONS.filter((one) => one === section || current.includes(one)),
    );

  const omitted = PRINT_SECTIONS.filter((section) => !chosen.includes(section));

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>Print {dprNumber}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" gutterBottom>
          The date, the site, the conditions and the signatures are on every copy. Everything
          else is yours to choose.
        </Typography>

        <Stack sx={{ mt: 1 }}>
          {PRINT_SECTIONS.map((section) => (
            <FormControlLabel
              key={section}
              sx={{ alignItems: 'flex-start', mb: 1, mr: 0 }}
              control={
                <Checkbox
                  checked={chosen.includes(section)}
                  onChange={() => toggle(section)}
                  sx={{ pt: 0.5 }}
                />
              }
              label={
                <>
                  <Typography>{PRINT_SECTION_LABEL[section]}</Typography>
                  <Typography variant="body2" color="text.secondary">
                    {PRINT_SECTION_HINT[section]}
                  </Typography>
                </>
              }
            />
          ))}
        </Stack>

        {omitted.length > 0 && (
          <Alert severity="info">
            The page will say it is an extract and name what was left out
            {' — '}
            {omitted.map((section) => PRINT_SECTION_LABEL[section]).join(', ')}. A copy that
            quietly dropped a section would read as a day that had none.
          </Alert>
        )}
        {/*
          Nothing ticked prints the whole form rather than a letterhead over two signature
          lines, and the dialog says so instead of disabling the button — a button that cannot
          be pressed and does not say why is a dialog somebody closes and reopens.
        */}
        {chosen.length === 0 && (
          <Alert severity="warning" sx={{ mt: 1 }}>
            Nothing is ticked, so the whole report will be printed.
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" disabled={busy} onClick={() => onPrint(chosen)}>
          {busy ? 'Preparing…' : 'Print'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
