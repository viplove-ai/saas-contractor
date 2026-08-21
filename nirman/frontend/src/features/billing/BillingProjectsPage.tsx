import { Alert, Box, Chip, CircularProgress, List, ListItemButton, ListItemText, Stack, Typography } from '@mui/material';
import { Link } from 'react-router-dom';
import { useBillingProjects } from './api';

/*
  The door into billing. Full projects and billing-only ones stand in one list on purpose: a
  tender imported only to prepare bills is still a tender, and the engineer looking for it
  should not have to know which kind it was set up as.
*/
export function BillingProjectsPage() {
  const projects = useBillingProjects();

  if (projects.isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', p: 6 }}>
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Stack spacing={2} sx={{ p: 2 }}>
      <Typography variant="h6">Billing</Typography>
      {(projects.data ?? []).length === 0 && (
        <Alert severity="info">
          No projects yet. Import a tender's NIT to prepare bills against it.
        </Alert>
      )}
      <List>
        {(projects.data ?? []).map((project) => (
          <ListItemButton
            key={project.id}
            component={Link}
            to={`/billing/${project.id}/sheets`}
            divider
          >
            <ListItemText primary={project.name} secondary={project.code} />
            {project.mode === 'BILLING_ONLY' && (
              <Chip size="small" label="Billing only" variant="outlined" />
            )}
          </ListItemButton>
        ))}
      </List>
    </Stack>
  );
}
