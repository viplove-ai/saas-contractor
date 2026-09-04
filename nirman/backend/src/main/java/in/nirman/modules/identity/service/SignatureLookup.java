package in.nirman.modules.identity.service;

import java.util.Optional;
import java.util.UUID;

/**
 * A member's signature, for the modules that print documents carrying his name.
 *
 * <p>The daily report prints "Prepared by" and "Verified by" over two names, and the names are
 * already resolved by the report module out of the identity module's repository — a boundary
 * that was crossed before this interface existed. The signature is not crossed the same way:
 * it is a file behind an id behind a user, and the report has no business knowing any of the
 * three. It asks for the picture, ready to draw, and gets it or nothing.</p>
 *
 * <p>No {@code @PreAuthorize} and no guard: the caller already passed the check that let it
 * render the document, and a signature on a document is not a disclosure to whoever may read
 * the document.</p>
 */
public interface SignatureLookup {

    /**
     * @return the member's signature as a {@code data:} URI an {@code <img>} can draw, or empty
     *         when he has not uploaded one, is not of this organisation, or the file is gone
     */
    Optional<String> signatureDataUri(UUID userId);
}
