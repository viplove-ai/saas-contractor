package in.nirman.modules.attachment.service;

import in.nirman.common.BusinessException;
import in.nirman.modules.attachment.StorageProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MinIO-backed storage. The client connects lazily — on the first actual upload or URL
 * request — so the application starts and every non-attachment feature works even when
 * MinIO is down or absent (integration tests, a laptop without the container).
 */
@Component
public class MinioStorageClient implements StorageClient {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageClient.class);

    private final StorageProperties properties;
    private final MinioClient client;
    private final AtomicBoolean bucketChecked = new AtomicBoolean(false);

    public MinioStorageClient(StorageProperties properties) {
        this.properties = properties;
        this.client = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .build();
    }

    @Override
    public void put(String objectKey, InputStream content, long sizeBytes, String contentType) {
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .stream(content, sizeBytes, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            log.error("Object store put failed for key {}", objectKey, e);
            throw storeUnavailable();
        }
    }

    @Override
    public String presignedGetUrl(String objectKey, int expiryMinutes) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.bucket())
                    .object(objectKey)
                    .expiry(expiryMinutes, TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            log.error("Presigned URL failed for key {}", objectKey, e);
            throw storeUnavailable();
        }
    }

    @Override
    public byte[] get(String objectKey) {
        try (InputStream in = client.getObject(GetObjectArgs.builder()
                .bucket(properties.bucket())
                .object(objectKey)
                .build())) {
            return in.readAllBytes();
        } catch (io.minio.errors.ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return null;
            }
            log.error("Object store get failed for key {}", objectKey, e);
            throw storeUnavailable();
        } catch (Exception e) {
            log.error("Object store get failed for key {}", objectKey, e);
            throw storeUnavailable();
        }
    }

    private void ensureBucket() throws Exception {
        if (bucketChecked.get()) {
            return;
        }
        synchronized (this) {
            if (bucketChecked.get()) {
                return;
            }
            boolean exists = client.bucketExists(
                    BucketExistsArgs.builder().bucket(properties.bucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
                log.info("Created storage bucket '{}'", properties.bucket());
            }
            bucketChecked.set(true);
        }
    }

    private static BusinessException storeUnavailable() {
        return new BusinessException("storage.unavailable",
                "The file store is not reachable right now. Try again shortly.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }
}
