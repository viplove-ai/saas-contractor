import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios';
import {
  API_BASE_URL,
  clearCachedSession,
  isNetworkFailure,
  refreshSession,
  tokenStorage,
} from './session';

export const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30_000,
  headers: { 'Content-Type': 'application/json' },
});

let accessToken: string | null = null;

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

apiClient.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (accessToken) {
    config.headers.Authorization = `Bearer ${accessToken}`;
  }
  config.headers['X-Correlation-Id'] = crypto.randomUUID();
  return config;
});

/**
 * A 401 triggers exactly one refresh attempt, shared by every request that raced into the
 * failure. Without the shared promise, a dashboard firing eight queries at once would
 * rotate the refresh token eight times — and the second rotation of the same token reads
 * as theft to the server, which then revokes the whole family. The promise now lives in
 * {@link refreshSession} itself, so the session revalidation on reconnect shares it too.
 *
 * <p>How the refresh fails decides whether the session survives. A refusal from the server
 * is final and the device is signed out on the spot. A refresh that got no answer at all is
 * a phone with no signal, and it must leave the stored token exactly where it is: the
 * request that provoked this is retried later by the queue, and throwing the credential
 * away here would strand every record already waiting on the device.</p>
 */
apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as InternalAxiosRequestConfig & { _retried?: boolean };
    if (error.response?.status !== 401 || original._retried) {
      return Promise.reject(error);
    }
    original._retried = true;
    try {
      const session = await refreshSession();
      setAccessToken(session.accessToken);
      return apiClient(original);
    } catch (refreshError) {
      if (isNetworkFailure(refreshError)) {
        // The network failure is the truer answer than the 401 that provoked it: it is what
        // tells the queue to stop the pass rather than work down the list collecting the
        // same timeout once per record.
        return Promise.reject(refreshError);
      }
      setAccessToken(null);
      tokenStorage.clear();
      clearCachedSession();
      window.location.assign('/login?reason=rejected');
      return Promise.reject(refreshError);
    }
  },
);

/** Shape of every error body the backend returns. Mirrors ApiError on the server. */
export interface ApiErrorBody {
  type: string;
  title: string;
  status: number;
  detail: string;
  correlationId: string;
  errors?: { field: string; message: string }[];
}

/** The human-readable message for an API failure, with a network fallback. */
export function apiErrorDetail(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const body = error.response?.data as ApiErrorBody | undefined;
    if (body?.detail) {
      return body.detail;
    }
    if (!error.response) {
      return 'No connection. Check the network and try again.';
    }
  }
  return 'Something went wrong. Try again.';
}
