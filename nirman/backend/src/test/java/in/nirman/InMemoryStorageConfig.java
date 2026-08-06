package in.nirman;

import in.nirman.modules.attachment.service.StorageClient;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An object store that lives in a map, for tests that upload something.
 *
 * <p>The real client talks to MinIO, which the suite deliberately does not run — the
 * application is built to start without it so every non-attachment feature works on a laptop
 * with no container. That leaves upload paths untestable, which matters now that importing a
 * tender stores its PDF.</p>
 *
 * <p>This stands in for the bucket and nothing else. The stream is drained rather than
 * ignored, because {@code AttachmentService} computes the SHA-256 checksum from it as it
 * writes: a client that never read the stream would leave that assertion vacuous.</p>
 */
@TestConfiguration
public class InMemoryStorageConfig {

    @Bean
    @Primary
    StorageClient inMemoryStorageClient() {
        return new InMemoryStorageClient();
    }

    public static class InMemoryStorageClient implements StorageClient {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public void put(String objectKey, InputStream content, long sizeBytes, String contentType) {
            try {
                objects.put(objectKey, content.readAllBytes());
            } catch (IOException e) {
                throw new IllegalStateException("could not buffer " + objectKey, e);
            }
        }

        @Override
        public String presignedGetUrl(String objectKey, int expiryMinutes) {
            return "https://storage.test/" + objectKey + "?expires=" + expiryMinutes;
        }

        public byte[] get(String objectKey) {
            return objects.get(objectKey);
        }

        public int size() {
            return objects.size();
        }
    }
}
