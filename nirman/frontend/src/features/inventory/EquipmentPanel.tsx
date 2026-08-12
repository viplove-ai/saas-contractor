import {
  Alert,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { useState } from 'react';
import { apiErrorDetail } from '../../shared/apiClient';
import { RecordTable, type RecordColumn } from '../../shared/RecordTable';
import { useAuth } from '../auth/AuthContext';
import {
  useAddEquipment,
  useDecideEquipment,
  useDeleteEquipment,
  useEquipment,
  useUpdateEquipment,
  useVendors,
} from './api';
import type {
  Equipment,
  EquipmentCondition,
  EquipmentOwnership,
  EquipmentStatus,
} from './types';

const CONDITION_LABEL: Record<EquipmentCondition, string> = {
  WORKING: 'Working',
  IDLE: 'Idle',
  UNDER_REPAIR: 'Under repair',
};

const OWNERSHIP_LABEL: Record<EquipmentOwnership, string> = {
  OWNED: 'Ours',
  HIRED: 'Hired',
};

interface Draft {
  name: string;
  assetCode: string;
  quantity: string;
  ownership: EquipmentOwnership;
  condition: EquipmentCondition;
  supplierId: string;
  remarks: string;
}

function emptyDraft(): Draft {
  return {
    name: '',
    assetCode: '',
    quantity: '1',
    ownership: 'OWNED',
    condition: 'WORKING',
    supplierId: '',
    remarks: '',
  };
}

/**
 * What plant is standing at this store.
 *
 * <p>It sits beside the stock rather than inside it because it is a different kind of fact.
 * Stock is consumed — it arrives, it is issued to a work item, and the ledger says what is
 * left — while a mixer is <em>held</em>: the same one is here in March and in June. Running
 * plant through the ledger would report a mixer as used up by the raft slab and leave the
 * store believing there is nothing to pour with.</p>
 *
 * <p><b>Anybody at the site may add a machine; only the office accepts it.</b> A supervisor
 * who cannot record the mixer in front of him will not record it at all, and the register
 * becomes a list of what the office remembered. But an entry nobody has checked is a claim,
 * and a hired breaker that went back on Tuesday must not sit here as plant for a year — so a
 * new entry waits, visibly, and says who it is waiting on.</p>
 */
export function EquipmentPanel({ storeId }: { storeId: string }) {
  const { hasPermission } = useAuth();
  const equipment = useEquipment(storeId || undefined);
  const vendors = useVendors();
  const add = useAddEquipment();
  const update = useUpdateEquipment();
  const decide = useDecideEquipment();
  const remove = useDeleteEquipment();

  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<Equipment | null>(null);
  const [draft, setDraft] = useState<Draft>(emptyDraft);
  const [actionError, setActionError] = useState<string | null>(null);

  const canAdd = hasPermission('equipment:create');
  const canDecide = hasPermission('equipment:approve');
  const canWrite = hasPermission('equipment:write');

  const openNew = () => {
    setEditing(null);
    setDraft(emptyDraft());
    setActionError(null);
    setOpen(true);
  };

  const openEdit = (machine: Equipment) => {
    setEditing(machine);
    setDraft({
      name: machine.name,
      assetCode: machine.assetCode ?? '',
      quantity: String(machine.quantity),
      ownership: machine.ownership,
      condition: machine.condition,
      supplierId: machine.supplierId ?? '',
      remarks: machine.remarks ?? '',
    });
    setActionError(null);
    setOpen(true);
  };

  // A hired machine costs money every day it stands here, so the server asks whose it is.
  // Saying so on the form beats being refused after the whole thing is typed.
  const complete =
    draft.name.trim().length > 0 &&
    Number(draft.quantity) > 0 &&
    (draft.ownership === 'OWNED' || Boolean(draft.supplierId));

  const save = async () => {
    setActionError(null);
    const input = {
      storeId,
      name: draft.name.trim(),
      assetCode: draft.assetCode.trim() || undefined,
      quantity: Number(draft.quantity),
      ownership: draft.ownership,
      condition: draft.condition,
      supplierId: draft.ownership === 'HIRED' ? draft.supplierId : undefined,
      remarks: draft.remarks.trim() || undefined,
    };
    try {
      if (editing) {
        await update.mutateAsync({ ...input, id: editing.id, version: editing.version });
      } else {
        await add.mutateAsync({ ...input, id: crypto.randomUUID() });
      }
      setOpen(false);
    } catch (error) {
      // On the dialog, not behind it: the number already on the register is the one thing
      // the person typing can do something about.
      setActionError(apiErrorDetail(error));
    }
  };

  const act = async (machine: Equipment, action: 'ACCEPT' | 'REJECT') => {
    setActionError(null);
    try {
      await decide.mutateAsync({ id: machine.id, action });
    } catch (error) {
      setActionError(apiErrorDetail(error));
    }
  };

  const drop = async (machine: Equipment) => {
    setActionError(null);
    try {
      await remove.mutateAsync(machine.id);
    } catch (error) {
      setActionError(apiErrorDetail(error));
    }
  };

  const columns: RecordColumn<Equipment>[] = [
    {
      key: 'machine',
      header: 'Machine',
      card: 'title',
      cell: (machine) => (
        <>
          <Typography fontWeight={600}>
            {machine.name}
            {machine.quantity > 1 && ` × ${machine.quantity}`}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {machine.assetCode ? machine.assetCode : 'No number on it'}
            {machine.ownership === 'HIRED' && ` · hired from ${machine.supplierName ?? '—'}`}
          </Typography>
        </>
      ),
    },
    {
      key: 'ownership',
      header: 'Whose',
      cell: (machine) => OWNERSHIP_LABEL[machine.ownership],
    },
    {
      key: 'condition',
      header: 'Condition',
      cell: (machine) => (
        <Typography
          variant="body2"
          color={machine.condition === 'UNDER_REPAIR' ? 'warning.main' : 'text.primary'}
        >
          {CONDITION_LABEL[machine.condition]}
        </Typography>
      ),
    },
    {
      key: 'status',
      header: 'On the register',
      card: 'status',
      cell: (machine) => <StatusMark status={machine.status} />,
    },
  ];

  if (canDecide || canWrite) {
    columns.push({
      key: 'actions',
      header: 'Actions',
      align: 'right',
      card: 'actions',
      cell: (machine) => (
        <>
          {canDecide && machine.status === 'PENDING' && (
            <>
              <Button size="small" onClick={() => void act(machine, 'ACCEPT')}>
                Accept
              </Button>
              <Button size="small" color="error" onClick={() => void act(machine, 'REJECT')}>
                Not here
              </Button>
            </>
          )}
          {canWrite && (
            <>
              <Button size="small" onClick={() => openEdit(machine)}>
                Edit
              </Button>
              <Button size="small" color="error" onClick={() => void drop(machine)}>
                Remove
              </Button>
            </>
          )}
        </>
      ),
    });
  }

  const rows = equipment.data ?? [];
  const waiting = rows.filter((machine) => machine.status === 'PENDING').length;

  return (
    <Stack spacing={2}>
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={2}
        justifyContent="space-between"
        alignItems={{ sm: 'center' }}
      >
        <Stack spacing={0.5}>
          <Typography color="text.secondary">
            The plant standing at this store. Not stock — a mixer is kept, not used up — so
            nothing here touches the ledger.
          </Typography>
          {waiting > 0 && (
            <Typography variant="body2" color="text.secondary">
              {waiting} entr{waiting === 1 ? 'y is' : 'ies are'} waiting for the office to
              accept {waiting === 1 ? 'it' : 'them'}.
            </Typography>
          )}
        </Stack>
        {canAdd && (
          <Button
            variant="contained"
            color="secondary"
            onClick={openNew}
            disabled={!storeId}
            sx={{ minHeight: 48 }}
          >
            Add equipment
          </Button>
        )}
      </Stack>

      {/*
        Only when the dialog is shut. While it is open the same refusal is shown inside it,
        next to the field that has to change, and printing it twice makes the copy behind the
        dialog look like a second problem.
      */}
      {actionError && !open && (
        <Alert severity="error" onClose={() => setActionError(null)}>
          {actionError}
        </Alert>
      )}
      {equipment.isLoading && <CircularProgress />}
      {equipment.isError && <Alert severity="error">{apiErrorDetail(equipment.error)}</Alert>}

      {equipment.data && (
        <RecordTable
          columns={columns}
          rows={rows}
          rowKey={(machine) => machine.id}
          ariaLabel="Equipment"
          empty={
            <Typography color="text.secondary">
              No plant recorded at this store yet.
            </Typography>
          }
        />
      )}

      <Dialog open={open} onClose={() => setOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>{editing ? 'Correct the entry' : 'Equipment at this store'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {!editing && !canDecide && (
              <Alert severity="info">
                This goes on the register once the office accepts it.
              </Alert>
            )}
            <TextField
              label="What is it"
              value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              helperText="As the site calls it — concrete mixer, needle vibrator, JCB"
              autoFocus
            />
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label="Number on it"
                value={draft.assetCode}
                onChange={(e) => setDraft({ ...draft, assetCode: e.target.value })}
                helperText="Registration or asset number, if it carries one"
                sx={{ flexGrow: 1 }}
              />
              <TextField
                label="How many"
                type="number"
                inputMode="numeric"
                value={draft.quantity}
                onChange={(e) => setDraft({ ...draft, quantity: e.target.value })}
                sx={{ maxWidth: 140 }}
              />
            </Stack>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                select
                label="Whose is it"
                value={draft.ownership}
                onChange={(e) =>
                  setDraft({ ...draft, ownership: e.target.value as EquipmentOwnership })
                }
                sx={{ flexGrow: 1 }}
              >
                <MenuItem value="OWNED">Ours</MenuItem>
                <MenuItem value="HIRED">Hired</MenuItem>
              </TextField>
              <TextField
                select
                label="Condition"
                value={draft.condition}
                onChange={(e) =>
                  setDraft({ ...draft, condition: e.target.value as EquipmentCondition })
                }
                sx={{ flexGrow: 1 }}
              >
                {(Object.keys(CONDITION_LABEL) as EquipmentCondition[]).map((condition) => (
                  <MenuItem key={condition} value={condition}>
                    {CONDITION_LABEL[condition]}
                  </MenuItem>
                ))}
              </TextField>
            </Stack>
            {/*
              Only for hired plant, and required there: the machine is costing money every
              day it stands in the yard, and the next question is always whose it is.
            */}
            {draft.ownership === 'HIRED' && (
              <TextField
                select
                label="Hired from"
                value={draft.supplierId}
                onChange={(e) => setDraft({ ...draft, supplierId: e.target.value })}
                helperText="The supplier who sent it"
              >
                {(vendors.data ?? []).map((vendor) => (
                  <MenuItem key={vendor.id} value={vendor.id}>
                    {vendor.name}
                  </MenuItem>
                ))}
              </TextField>
            )}
            <TextField
              label="Anything worth noting"
              value={draft.remarks}
              onChange={(e) => setDraft({ ...draft, remarks: e.target.value })}
              multiline
              minRows={2}
            />
            {actionError && <Alert severity="error">{actionError}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setOpen(false)}>Cancel</Button>
          <Button
            variant="contained"
            color="secondary"
            disabled={!complete || add.isPending || update.isPending}
            onClick={() => void save()}
          >
            {add.isPending || update.isPending ? 'Saving…' : editing ? 'Save' : 'Add it'}
          </Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

/**
 * Three states and three different things to say. "Pending" alone reads as a delay; what the
 * supervisor needs to know is that somebody else has to look at it.
 */
function StatusMark({ status }: { status: EquipmentStatus }) {
  if (status === 'ACCEPTED') {
    return <Chip size="small" color="success" label="On the register" />;
  }
  if (status === 'REJECTED') {
    return <Chip size="small" variant="outlined" color="error" label="Office says not here" />;
  }
  return <Chip size="small" color="warning" variant="outlined" label="Waiting on the office" />;
}
