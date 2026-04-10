package io.temporal.workflow.nexus;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.nexus.Nexus;
import io.temporal.payload.codec.PayloadCodec;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/**
 * A simulated per-endpoint encryption codec that demonstrates how to select different encryption
 * keys based on the Nexus endpoint being called.
 *
 * <p>This codec uses a simple prefix-based transformation (not real encryption) to keep focus on
 * the Nexus wiring pattern rather than cryptographic details. The design is asymmetric:
 *
 * <ul>
 *   <li>{@code encode()}: Auto-detects the key ID by checking (1) the Nexus operation context if on
 *       a handler thread, (2) the thread-local set by {@link EncryptionKeyContextPropagator} if on
 *       a workflow thread, or (3) a default key. This means handler and workflow code need zero
 *       awareness of encryption.
 *   <li>{@code decode()}: Reads the key ID from the payload's own metadata ({@code
 *       encryption-key-id} field). This is self-describing and does not depend on any external
 *       context, which is necessary because handler-side input deserialization occurs before
 *       handler code runs.
 * </ul>
 */
public class PerEndpointEncryptionCodec implements PayloadCodec {

  static final String METADATA_ENCRYPTED = "encrypted";
  static final ByteString METADATA_ENCRYPTED_VALUE = ByteString.copyFromUtf8("true");
  static final String METADATA_KEY_ID = "encryption-key-id";
  static final String ENC_PREFIX = "ENC:";

  private static final ThreadLocal<String> CURRENT_KEY_ID = new ThreadLocal<>();

  private final String defaultKeyId;

  /**
   * @param defaultKeyId the default key ID used when neither Nexus context nor thread-local is
   *     available (e.g., on the test thread or internal SDK serialization paths)
   */
  public PerEndpointEncryptionCodec(String defaultKeyId) {
    this.defaultKeyId = defaultKeyId;
  }

  /** Sets the current encryption key ID on the calling thread (used by ContextPropagator). */
  public static void setCurrentKeyId(String keyId) {
    CURRENT_KEY_ID.set(keyId);
  }

  /** Clears the current encryption key ID on the calling thread. */
  public static void clearCurrentKeyId() {
    CURRENT_KEY_ID.remove();
  }

  /** Returns the current key ID for the calling thread, or null if not set. */
  static String getCurrentKeyId() {
    return CURRENT_KEY_ID.get();
  }

  @Nonnull
  @Override
  public List<Payload> encode(@Nonnull List<Payload> payloads) {
    return payloads.stream().map(this::encodePayload).collect(Collectors.toList());
  }

  @Nonnull
  @Override
  public List<Payload> decode(@Nonnull List<Payload> payloads) {
    return payloads.stream().map(this::decodePayload).collect(Collectors.toList());
  }

  /**
   * Resolves the key ID to use for encoding. Checks sources in priority order:
   *
   * <ol>
   *   <li>Nexus operation context (available on handler threads during serialization)
   *   <li>Thread-local (set by {@link EncryptionKeyContextPropagator} on workflow threads)
   *   <li>Default key (fallback for test threads and internal SDK operations)
   * </ol>
   */
  private String resolveKeyId() {
    // 1. Check if we're on a Nexus handler thread — the endpoint is the key ID
    if (Nexus.isInOperationHandler()) {
      return Nexus.getOperationContext().getInfo().getEndpoint();
    }

    // 2. Check thread-local (set by ContextPropagator for workflow threads)
    String threadLocalKey = CURRENT_KEY_ID.get();
    if (threadLocalKey != null) {
      return threadLocalKey;
    }

    // 3. Default key
    return defaultKeyId;
  }

  private Payload encodePayload(Payload payload) {
    String keyId = resolveKeyId();

    // Simulated encryption: prefix data with "ENC:<keyId>:" to make it visibly non-plaintext.
    // Use toBuilder() to preserve existing metadata (e.g., "encoding: json/plain").
    ByteString prefix = ByteString.copyFromUtf8(ENC_PREFIX + keyId + ":");
    ByteString encodedData = prefix.concat(payload.getData());

    return payload.toBuilder()
        .putMetadata(METADATA_ENCRYPTED, METADATA_ENCRYPTED_VALUE)
        .putMetadata(METADATA_KEY_ID, ByteString.copyFromUtf8(keyId))
        .setData(encodedData)
        .build();
  }

  private Payload decodePayload(Payload payload) {
    // Self-describing: check our encryption marker, then read key-id from metadata.
    ByteString encryptedMeta = payload.getMetadataOrDefault(METADATA_ENCRYPTED, null);
    if (encryptedMeta == null || !encryptedMeta.equals(METADATA_ENCRYPTED_VALUE)) {
      return payload;
    }

    ByteString keyIdBytes = payload.getMetadataOrDefault(METADATA_KEY_ID, null);
    if (keyIdBytes == null) {
      throw new IllegalStateException("Encrypted payload missing encryption-key-id metadata");
    }
    String keyId = keyIdBytes.toStringUtf8();

    // Reverse the simulated encryption: strip the "ENC:<keyId>:" prefix
    String expectedPrefix = ENC_PREFIX + keyId + ":";
    ByteString expectedPrefixBytes = ByteString.copyFromUtf8(expectedPrefix);
    ByteString data = payload.getData();
    if (!data.startsWith(expectedPrefixBytes)) {
      throw new IllegalStateException(
          "Encrypted payload data does not start with expected prefix: " + expectedPrefix);
    }
    ByteString decodedData = data.substring(expectedPrefixBytes.size());

    return payload.toBuilder()
        .removeMetadata(METADATA_ENCRYPTED)
        .removeMetadata(METADATA_KEY_ID)
        .setData(decodedData)
        .build();
  }
}
