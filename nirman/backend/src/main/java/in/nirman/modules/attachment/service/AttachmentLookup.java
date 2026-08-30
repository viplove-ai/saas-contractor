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

    /**
     * Stores a file this system generated itself, already claimed by the record that asked
     * for it.
     *
     * <p>The second write here, and the one that needed a reason. {@code AttachmentService}
     * takes a {@code MultipartFile} because everything it had ever been asked to store came
     * off somebody's device — a challan photographed at the gate, a scan of a passbook — and
     * every check it runs is a check on an upload: the size a handset might send, the content
     * types a browser is allowed to offer, the site the uploader may reach. None of those
     * questions has an answer when the bytes are a PDF this system rendered a moment ago out
     * of figures it already holds, and dressing a generated document up as a multipart upload
     * to get it past checks that do not apply would be a lie told to our own code.</p>
     *
     * <p>Claimed in the same act rather than in a second call. A generated document has an
     * owner from the moment it exists — nothing generates one speculatively — and leaving it
     * unclaimed for even one statement would open the window in which
     * {@code AttachmentService.delete} would happily discard it.</p>
     *
     * @param ownerEntityType the same discriminator an upload carries, so a generated
     *                        document is found by exactly the queries that find an uploaded one
     */
    FileInfo store(byte[] content, String fileName, String contentType,
                   String ownerEntityType, UUID ownerEntityId);

    /**
     * Throws the file away with the record that was carrying it.
     *
     * <p>The mirror of {@link #claimFor}, and it exists because claiming is what makes
     * {@code AttachmentService.delete} refuse: once a record owns a file, the uploader can no
     * longer discard it, and without this the owning record could be deleted while the file
     * behind it stayed downloadable for ever by anybody holding its id. Only the owner may
     * do it — the id is checked against the one passed in — so this cannot be used to reach
     * a file belonging to something else.</p>
     *
     * <p>The row is soft-deleted, which is what stops another signed link being minted; the
     * object itself stays in the store, exactly as {@code AttachmentService.delete} leaves
     * it, and an orphan sweep is still a later phase's task.</p>
     *
     * @throws in.nirman.common.BusinessException 409 if the file belongs to another record
     */
    void discardFor(UUID attachmentId, UUID ownerEntityId);
}
