import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { useAuth } from '../auth/AuthContext';
import { useCorrectFieldMaterial, useMaterials } from './api';

/**
 * The other half of naming a material at the gate: saying the name was wrong.
 *
 * <p>Without it, "celment" sits on every picker until somebody in the office notices, and the
 * man who typed it works around his own mistake by naming a second row — which is the split
 * balance the naming rule exists to prevent, arrived at from the inside.</p>
 *
 * <p>The name and nothing else. Not the rate, not the tax, and not the unit: changing what a
 * material is measured in re-reads every quantity ever booked against it, so fifty bags become
 * fifty kilogrammes without a single stock row moving.</p>
 */
export function CorrectMaterialNameDialog({
  open,
  materialId,
  onClose,
}: {
  open: boolean;
  materialId: string;
  onClose: () => void;
}) {
  const { hasPermission } = useAuth();
  const materials = useMaterials();
  const correct = useCorrectFieldMaterial();

  const material = (materials.data ?? []).find((row) => row.id === materialId);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      setName(material?.name ?? '');
      setError(null);
    }
  }, [open, material?.name]);

  /*
    Whether saving puts the row back in front of the office. Only the field's correction does:
    the office editing its own catalogue is the office deciding, and it does not send the row
    to itself. Said before the change is typed rather than after it is saved — a supervisor who
    finds out from a flag appearing is a supervisor who stops correcting names.
  */
  const reopens = !hasPermission('masterdata:write');

  const submit = async () => {
    if (!material) {
      return;
    }
    setError(null);
    try {
      await correct.mutateAsync({ id: material.id, name: name.trim(), version: material.version });
      onClose();
    } catch (caught) {
      setError(apiErrorDetail(caught));
    }
  };

  const changed = name.trim().length > 0 && name.trim() !== material?.name;

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>What this material is called</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {reopens && (
            <Alert severity="info">
              The office reads the new name before it is settled. Nothing else on the material
              changes — not its rate, not what it is measured in.
            </Alert>
          )}
          <TextField
            label="Name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            helperText="As the site calls it. A name the catalogue already holds is refused — two rows for one cement split its stock into two balances."
            autoFocus
          />
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="contained"
          color="secondary"
          disabled={!changed || correct.isPending}
          onClick={() => void submit()}
        >
          {correct.isPending ? 'Saving…' : 'Save the name'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
