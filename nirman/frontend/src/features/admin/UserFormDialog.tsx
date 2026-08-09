import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormHelperText,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { isSiteScopedRole, ROLE_LABEL } from '../../shared/roles';
import { useAdminSites, useCreateUser, useRoles, useUpdateUser } from './api';
import { generatePassword } from './password';
import { userFormSchema, type UserForm } from './schema';
import type { AdminUser } from './types';

interface Props {
  open: boolean;
  /** Null onboards someone new; a user edits that member. */
  user: AdminUser | null;
  onClose: () => void;
}

/**
 * Onboarding and editing one member.
 *
 * <p>Role is a single choice, not a set. The server takes a list, but a contractor's staff
 * are one thing each — a man is the site engineer or he is the supervisor — and a checkbox
 * grid invites the mistake of granting both.</p>
 *
 * <p>The site picker appears only for the two site-scoped roles. For an admin or an
 * accountant it would be a lie: their token carries the ALL-sites claim, so postings would
 * change nothing about what they can see.</p>
 */
export function UserFormDialog({ open, user, onClose }: Props) {
  const editing = user !== null;
  const roles = useRoles();
  const sites = useAdminSites('');
  const createUser = useCreateUser();
  const updateUser = useUpdateUser();
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    control,
    register,
    handleSubmit,
    reset,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<UserForm>({
    resolver: zodResolver(userFormSchema),
    defaultValues: {
      mode: 'create',
      username: '',
      fullName: '',
      email: '',
      mobile: '',
      temporaryPassword: '',
      roleCode: 'SUPERVISOR',
      siteIds: [],
    },
  });

  // The dialog stays mounted between openings, so each opening reloads the member it is
  // about — otherwise the second member edited would open on the first one's values.
  useEffect(() => {
    if (!open) {
      return;
    }
    setServerError(null);
    reset(
      user
        ? {
            mode: 'edit',
            username: user.username,
            fullName: user.fullName,
            email: user.email ?? '',
            mobile: user.mobile ?? '',
            temporaryPassword: '',
            roleCode: user.roles[0] ?? 'SUPERVISOR',
            siteIds: user.siteIds,
          }
        : {
            mode: 'create',
            username: '',
            fullName: '',
            email: '',
            mobile: '',
            temporaryPassword: generatePassword(),
            roleCode: 'SUPERVISOR',
            siteIds: [],
          },
    );
  }, [open, user, reset]);

  const showSites = isSiteScopedRole(watch('roleCode'));

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    // Postings are meaningless for the company-wide roles, and sending them would leave
    // stale rows behind if someone were promoted from supervisor to admin.
    const siteIds = isSiteScopedRole(values.roleCode) ? values.siteIds : [];
    try {
      if (user) {
        await updateUser.mutateAsync({
          id: user.id,
          fullName: values.fullName,
          email: values.email,
          mobile: values.mobile,
          version: user.version,
          roleCodes: [values.roleCode],
          siteIds,
        });
      } else {
        await createUser.mutateAsync({
          username: values.username,
          fullName: values.fullName,
          email: values.email,
          mobile: values.mobile,
          temporaryPassword: values.temporaryPassword,
          roleCodes: [values.roleCode],
          siteIds,
        });
      }
      onClose();
    } catch (error) {
      setServerError(apiErrorDetail(error));
    }
  });

  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{editing ? `Edit ${user.username}` : 'Onboard a member'}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {serverError && <Alert severity="error">{serverError}</Alert>}

          <TextField
            label="Username"
            autoComplete="off"
            autoCapitalize="none"
            disabled={editing}
            error={!!errors.username}
            helperText={
              errors.username?.message ??
              (editing
                ? 'A username is permanent; onboard a new member instead of renaming one.'
                : 'What they type to sign in. It cannot be changed later.')
            }
            {...register('username')}
          />

          <TextField
            label="Full name"
            error={!!errors.fullName}
            helperText={errors.fullName?.message}
            {...register('fullName')}
          />
          <TextField
            label="Email (optional)"
            type="email"
            autoComplete="off"
            error={!!errors.email}
            helperText={errors.email?.message}
            {...register('email')}
          />
          <TextField
            label="Mobile"
            autoComplete="off"
            inputMode="tel"
            error={!!errors.mobile}
            helperText={errors.mobile?.message ?? 'How you reach them when they cannot sign in.'}
            {...register('mobile')}
          />

          {!editing && (
            <Controller
              control={control}
              name="temporaryPassword"
              render={({ field }) => (
                <Stack spacing={1}>
                  <TextField
                    {...field}
                    label="First-time password"
                    error={!!errors.temporaryPassword}
                    helperText={
                      errors.temporaryPassword?.message ??
                      'Hand this to them. They must change it the first time they sign in.'
                    }
                  />
                  <Button
                    size="small"
                    onClick={() => field.onChange(generatePassword())}
                    sx={{ alignSelf: 'flex-start' }}
                  >
                    Generate another
                  </Button>
                </Stack>
              )}
            />
          )}

          <Controller
            control={control}
            name="roleCode"
            render={({ field }) => (
              <TextField
                {...field}
                select
                label="Role"
                error={!!errors.roleCode}
                helperText={
                  errors.roleCode?.message ??
                  (showSites
                    ? 'Sees and works on assigned sites only.'
                    : 'Sees the whole company, whatever the site postings say.')
                }
              >
                {(roles.data ?? []).map((role) => (
                  <MenuItem key={role.code} value={role.code}>
                    {ROLE_LABEL[role.code] ?? role.name}
                  </MenuItem>
                ))}
              </TextField>
            )}
          />

          {showSites && (
            <Controller
              control={control}
              name="siteIds"
              render={({ field }) => (
                <Stack spacing={0.5}>
                  <Typography variant="subtitle2">Sites</Typography>
                  {(sites.data ?? []).map((site) => {
                    const checked = field.value.includes(site.id);
                    return (
                      <FormControlLabel
                        key={site.id}
                        control={
                          <Switch
                            checked={checked}
                            onChange={() =>
                              field.onChange(
                                checked
                                  ? field.value.filter((id) => id !== site.id)
                                  : [...field.value, site.id],
                              )
                            }
                          />
                        }
                        label={`${site.code} — ${site.name}`}
                      />
                    );
                  })}
                  {sites.data?.length === 0 && (
                    <FormHelperText>
                      No sites yet. Add one on the Sites screen, then post them to it.
                    </FormHelperText>
                  )}
                  {/*
                    Not an error: an engineer has to exist before the site he runs can be
                    created, so "no posting yet" is a real intermediate state. It is still
                    worth saying out loud, because until he is posted he signs in to an empty
                    app — nothing in any site picker and a dead "Take on a worker" button.
                  */}
                  {(sites.data?.length ?? 0) > 0 && field.value.length === 0 && (
                    <Alert severity="warning" sx={{ mt: 1 }}>
                      Not posted anywhere yet. They can sign in, but will see no sites and no
                      workers until you post them here or name them on a site.
                    </Alert>
                  )}
                </Stack>
              )}
            />
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="contained" color="secondary" onClick={submit} disabled={isSubmitting}>
          {editing ? 'Save changes' : 'Onboard member'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
