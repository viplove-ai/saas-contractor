import { EvidencePhotoField } from '../../shared/EvidencePhotoField';

/**
 * The bill photograph, on the expense form.
 *
 * <p>The wording, and nothing else: the picking, the compression and the preview are
 * {@link EvidencePhotoField}'s, which does the same job for the receipt that proves a payment.
 * The two were one component copied, until the payment screen needed it — and a second copy of
 * the compression rules is a second answer to "how big may a photograph be", which is the kind
 * of disagreement nobody notices until a site phone runs out of quota.</p>
 */
export function BillPhotoField({
  file,
  onPick,
}: {
  file: File | null;
  onPick: (file: File | null) => void;
}) {
  return (
    <EvidencePhotoField
      file={file}
      onPick={onPick}
      label="Photograph the bill"
      changeLabel="Change photograph"
    />
  );
}
