import { zodResolver } from '@hookform/resolvers/zod';
import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormGroup,
  FormHelperText,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { useEffect, useState } from 'react';
import { Controller, useForm } from 'react-hook-form';
import { apiErrorDetail } from '../../shared/apiClient';
import { hasSiteScopedRole, ROLE_LABEL, seesAllSites } from '../../shared/roles';
import { useAdminSites, useCreateUser, useRoles, useUpdateUser } from './api';
import { generatePassword } from './password';
import { userFormSchema, type UserForm } from './schema';
import type { AdminSite, AdminUser } from './types';

interface Props {
  open: boolean;
  /** Null onboards someone new; a user edits that member. */
  user: AdminUser | null;
  onClose: () => void;
}

/**
 * Onboarding and editing one member.
 *
 * <p>Role is a set, because on a small contractor's staff one man wears several hats — the
 * site engineer who also keeps the books, the owner who supervises a site himself. The
 * server has always stored roles many-to-many and grants the union of their permissions;
 * this dialog used to force the choice down to one, which quietly stripped the other roles
 * off anyone who was edited.</p>
 *
 * <p>The site picker appears as soon as one site-scoped role is ticked. If a company-wide
 * role is ticked as well, the postings no longer decide what they see — the token carries
 * the ALL-sites claim — so the helper text says so rather than letting the picker imply a
 * fence that is not there. The postings are still worth keeping: they are what names
 * someone on a site, and they are what is left if the company-wide role is taken away.</p>
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
      roleCodes: ['SUPERVISOR'],
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
            // Every role held, not the first one: reopening the dialog on a member must not
            // be a way to lose the hats it does not show.
            roleCodes: user.roles.length > 0 ? user.roles : ['SUPERVISOR'],
            siteIds: user.siteIds,
          }
        : {
            mode: 'create',
            username: '',
            fullName: '',
            email: '',
            mobile: '',
            temporaryPassword: generatePassword(),
            roleCodes: ['SUPERVISOR'],
            siteIds: [],
          },
    );
  }, [open, user, reset]);

  const selectedRoles = watch('roleCodes');
  const showSites = hasSiteScopedRole(selectedRoles);
  const companyWide = seesAllSites(selectedRoles);

  /** Sites this member is named on, which the register grants and this screen cannot take. */
  const posted = (sites.data ?? [])
    .filter((site) => postOn(site, user?.id) !== null)
    .map((site) => site.id);

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    // Postings are meaningless once no site-scoped role is left, and sending them would
    // leave stale rows behind when someone stops being a supervisor. The sites he is named
    // on go with the request whatever the switches say — the server refuses to drop them,
    // and sending a set that omits them would only turn a save into an error message.
    const siteIds = hasSiteScopedRole(values.roleCodes)
      ? [...new Set([...values.siteIds, ...posted])]
      : posted;
    try {
      if (user) {
        await updateUser.mutateAsync({
          id: user.id,
          fullName: values.fullName,
          email: values.email,
          mobile: values.mobile,
          version: user.version,
          roleCodes: values.roleCodes,
          siteIds,
        });
      } else {
        await createUser.mutateAsync({
          username: values.username,
          fullName: values.fullName,
          email: values.email,
          mobile: values.mobile,
          temporaryPassword: values.temporaryPassword,
          roleCodes: values.roleCodes,
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
            name="roleCodes"
            render={({ field }) => (
              <Stack spacing={0.5}>
                <Typography variant="subtitle2">Roles</Typography>
                <FormGroup>
                  {(roles.data ?? []).map((role) => {
                    const checked = field.value.includes(role.code);
                    return (
                      <FormControlLabel
                        key={role.code}
                        control={
                          <Checkbox
                            checked={checked}
                            onChange={() =>
                              field.onChange(
                                checked
                                  ? field.value.filter((code) => code !== role.code)
                                  : [...field.value, role.code],
                              )
                            }
                          />
                        }
                        label={ROLE_LABEL[role.code] ?? role.name}
                      />
                    );
                  })}
                </FormGroup>
                <FormHelperText error={!!errors.roleCodes}>
                  {errors.roleCodes?.message ??
                    (companyWide
                      ? 'Sees the whole company, whatever the site postings say. What they may do is every permission of every role ticked.'
                      : 'Sees and works on assigned sites only. What they may do is every permission of every role ticked.')}
                </FormHelperText>
              </Stack>
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
                    /*
                      Two different facts meet on this switch. The sites register names the
                      engineers and supervisors of a site; these postings say who may open
                      it, and that includes people the register never names — a store keeper,
                      an accountant sent to count. Naming somebody on a site grants them
                      the site — that sync already runs — but this screen could take the site
                      back off the very engineer named on it, leaving the register saying he
                      runs KSN-A and the door shut to him. So a site he is named on is shown
                      as held rather than as a choice, and the server refuses it too.
                    */
                    const post = postOn(site, user?.id);
                    const checked = field.value.includes(site.id) || post !== null;
                    return (
                      <FormControlLabel
                        key={site.id}
                        disabled={post !== null}
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
                        label={
                          post === null
                            ? `${site.code} — ${site.name}`
                            : `${site.code} — ${site.name} · ${post} here, change it on the Sites screen`
                        }
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

                    Unless he also holds a company-wide role, in which case he sees every
                    site regardless and the warning would be a lie.
                  */}
                  {(sites.data?.length ?? 0) > 0 && field.value.length === 0 && !companyWide && (
                    <Alert severity="warning" sx={{ mt: 1 }}>
                      Not posted anywhere yet. They can sign in, but will see no sites and no
                      workers until you post them here or name them on a site.
                    </Alert>
                  )}
                  {companyWide && (
                    <FormHelperText>
                      A company-wide role is ticked too, so they already reach every site.
                      Postings here only decide which sites they can be named on.
                    </FormHelperText>
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

/**
 * What this member is called on a site, or null if nothing.
 *
 * <p>Read off the sites register rather than asked for separately: the register is already
 * loaded to draw this list, and it carries both names.</p>
 */
function postOn(site: AdminSite, userId: string | undefined): string | null {
  if (!userId) {
    return null;
  }
  // Both, when he is the engineer and the supervisor of a small job — a site may name him
  // twice and the switch has to explain why it will not move either way.
  const held = [
    site.siteEngineerIds.includes(userId) ? 'Site engineer' : null,
    site.supervisorIds.includes(userId) ? 'Supervisor' : null,
  ].filter((post): post is string => post !== null);
  return held.length === 0 ? null : held.join(' and ');
}
