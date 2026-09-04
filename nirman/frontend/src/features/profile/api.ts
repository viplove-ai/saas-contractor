import { useMutation, useQuery } from '@tanstack/react-query';
import { apiClient } from '../../shared/apiClient';
import type { SessionUser } from '../../shared/session';
import { clearSignature, setSignature } from '../auth/api';

/**
 * The member's own signature: the file into storage, then the link onto his account.
 *
 * <p>In that order, for the reason the staff papers give — a link to a file that is not there
 * is a blank box on a letter. The picture arrives already cropped to the standard shape (see
 * {@code shared/signatureImage.ts}), so nothing is resized here; it is sent as the PNG it is,
 * because a JPEG pass fringes a pen stroke.</p>
 */
export function useSetSignature() {
  return useMutation({
    mutationFn: async (signature: Blob): Promise<SessionUser> => {
      const form = new FormData();
      form.append('file', signature, 'signature.png');
      const uploaded = await apiClient.post<{ id: string }>('/attachments', form, {
        params: { ownerEntityType: 'USER_SIGNATURE', kind: 'PHOTO' },
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      return setSignature(uploaded.data.id);
    },
  });
}

export function useClearSignature() {
  return useMutation({ mutationFn: clearSignature });
}

/**
 * A signed link to the signature on file, for showing it back to its owner.
 *
 * <p>Cached for half the link's own life, as every other attachment link in the app is.</p>
 */
export function useSignatureUrl(attachmentId: string | null | undefined) {
  return useQuery({
    queryKey: ['attachments', attachmentId ?? '', 'url'] as const,
    queryFn: async () =>
      (await apiClient.get<{ url: string; fileName: string }>(`/attachments/${attachmentId}/url`))
        .data,
    enabled: Boolean(attachmentId),
    staleTime: 5 * 60_000,
    gcTime: 5 * 60_000,
  });
}
