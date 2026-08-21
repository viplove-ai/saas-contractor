import OpenInNewIcon from '@mui/icons-material/OpenInNew';
import { Button, Skeleton, Stack, Typography } from '@mui/material';
import { PhotoThumb } from '../../shared/PhotoThumb';
import { useAttachmentUrl } from './api';

/**
 * The least this component needs to draw a file: what to ask the server for, and what it is.
 *
 * <p>Structural rather than {@code ExpenseAttachment}, so the same preview serves the receipt
 * that proves a payment. Neither record's extra fields matter here — a size in bytes is not
 * something anybody deciding about money is reading.</p>
 */
interface PreviewableAttachment {
  id: string;
  attachmentId: string;
  fileName?: string;
  contentType?: string;
}

/**
 * The photographed bill, in front of whoever is deciding about the money.
 *
 * <p>An approver is being asked to agree to a figure he did not watch being incurred, and
 * until now the queue told him only that a bill number existed. A number is not evidence:
 * the challan photographed instead of the invoice, the thumb over the lens, the second page
 * where the first was wanted — all of them carry a bill number quite happily, and all of
 * them are obvious the moment the picture is on the screen. Caught here it costs the
 * supervisor another photograph; caught after approval it costs a re-opening.</p>
 *
 * <p>Said out loud when there is nothing attached, rather than left as an empty space. "No
 * photograph" is a fact the approver is entitled to weigh — it is the difference between a
 * bill he can check and one he is taking on trust — and an absent row reads as a screen that
 * has not finished loading.</p>
 */
export function BillPreview({
  attachments,
  emptyLabel = 'No photograph of the bill',
}: {
  attachments: PreviewableAttachment[];
  /** What the absence is called. A payment's is a receipt, not a bill. */
  emptyLabel?: string;
}) {
  if (attachments.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        {emptyLabel}
      </Typography>
    );
  }
  return (
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
      {attachments.map((attachment) => (
        <BillThumb key={attachment.id} attachment={attachment} />
      ))}
    </Stack>
  );
}

/** One attachment: a picture to tap open, or — for a PDF, which no img will draw — a link. */
function BillThumb({ attachment }: { attachment: PreviewableAttachment }) {
  const link = useAttachmentUrl(attachment.attachmentId);
  const name = attachment.fileName ?? 'Bill';

  if (link.isLoading) {
    return <Skeleton variant="rounded" width={72} height={72} />;
  }
  /*
    A link that will not load is said, not left as a broken image icon. On a site phone the
    usual cause is the signal going, and "not loading" is the difference between trying again
    and deciding the bill was never attached.
  */
  if (link.isError || !link.data) {
    return (
      <Typography variant="caption" color="text.secondary">
        Bill not loading
      </Typography>
    );
  }
  if (isPdf(attachment)) {
    return (
      <Button
        component="a"
        href={link.data.url}
        target="_blank"
        rel="noopener"
        size="small"
        variant="outlined"
        startIcon={<OpenInNewIcon />}
        sx={{ minHeight: 40 }}
      >
        Open bill
      </Button>
    );
  }
  return <PhotoThumb src={link.data.url} name={name} size={72} />;
}

/** By what the server stored, and by the name when it stored nothing — an upload from an
 *  old client carries no content type and is still a PDF. */
function isPdf(attachment: PreviewableAttachment): boolean {
  return (
    attachment.contentType === 'application/pdf' ||
    (attachment.fileName ?? '').toLowerCase().endsWith('.pdf')
  );
}
