package in.nirman.modules.attachment.service;

import java.util.UUID;

/**
 * What another module may know about a stored file, and the one thing it may do to one.
 *
 * <p>The rule this exists to keep is that no module outside {@code attachment} imports its
 * repository or its entity. A record that carries a photograph — a machine on the plant
 * register, a bill — needs two things from here and nothing else: to be told the file is real
 * and is a picture before it stores the id, and to say afterwards that the file now belongs to
 * it.</p>
 *
 * <p>{@link #claimFor} is a write, which no other {@code *Lookup} is. It is here rather than
 * in a second interface because it is the same boundary and the same one-line question — "is
 * this file mine to point at?" — asked once for reading and once for keeping. Without the
 * claim, {@code AttachmentService.delete} would happily discard a file that a saved record is
 * pointing at, since what it refuses to delete is precisely a file something has claimed.</p>
 */
public interface AttachmentLookup {

    /** A stored file, in the terms a caller about to attach one cares about. */
    record FileInfo(UUID id, String fileName, String contentType, UUID siteId, boolean image) {
    }

    /**
     * @throws in.nirman.common.BusinessException 404 if no such file belongs to this
     *                                            organisation
     */
    FileInfo require(UUID attachmentId);

    /**
     * Binds the file to the record that now carries it, so nothing can delete it out from
     * under that record.
     *
     * @throws in.nirman.common.BusinessException 409 if another record already owns it
     */
    void claimFor(UUID attachmentId, UUID ownerEntityId);
}
