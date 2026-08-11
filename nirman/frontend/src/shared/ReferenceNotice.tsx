import { Alert } from '@mui/material';
import type { UseQueryResult } from '@tanstack/react-query';
import { apiErrorDetail } from './apiClient';

/**
 * Why a picker has nothing in it.
 *
 * <p>A select drawn from reference data that has not loaded, or that the organisation never
 * had, looks exactly like a select the user has not opened yet: empty, silent, and apparently
 * their fault. A storekeeper stood at the gate with a lorry waiting spent an evening deciding
 * whether the app was broken or he was, when the answer was that the material list had never
 * been filled in for his organisation.</p>
 *
 * <p>So: say which of the two it is. A refused or failed request says so and repeats what the
 * server said; an empty catalogue says it is empty and who fills it. Neither is a state the
 * user can fix by tapping the box again, which is exactly why neither may be silent.</p>
 *
 * <p>Draws nothing while the query is in flight, and nothing once there is something to
 * choose — the common case is not worth a line of chrome.</p>
 */
export function ReferenceNotice({
  query,
  what,
  whoFillsIt = 'An administrator adds them under master data.',
}: {
  query: Pick<UseQueryResult<unknown[]>, 'data' | 'isLoading' | 'isError' | 'error'>;
  /** The thing being chosen, lower case and plural: "materials", "units", "expense heads". */
  what: string;
  whoFillsIt?: string;
}) {
  if (query.isLoading) {
    return null;
  }
  if (query.isError) {
    return (
      <Alert severity="error">
        The {what} could not be loaded, so the list is empty. {apiErrorDetail(query.error)}
      </Alert>
    );
  }
  if (query.data && query.data.length === 0) {
    return (
      <Alert severity="warning">
        No {what} are set up yet, so there is nothing to choose. {whoFillsIt}
      </Alert>
    );
  }
  return null;
}
