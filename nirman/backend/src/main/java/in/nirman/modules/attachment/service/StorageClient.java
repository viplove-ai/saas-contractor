package in.nirman.modules.attachment.service;

import java.io.InputStream;

/**
 * The five object-store operations the application needs, so nothing outside this module
 * knows MinIO exists. An S3 endpoint satisfies the same client.
 */
public interface StorageClient {

    void put(String objectKey, InputStream content, long sizeBytes, String contentType);

    /** A short-lived GET URL. Issued only after the service layer re-checks site access. */
    String presignedGetUrl(String objectKey, int expiryMinutes);

    /**
     * The object itself, for the one case a link will not do: a picture this server draws
     * into a PDF it is rendering. Null when the store has no such object.
     */
    byte[] get(String objectKey);
}
