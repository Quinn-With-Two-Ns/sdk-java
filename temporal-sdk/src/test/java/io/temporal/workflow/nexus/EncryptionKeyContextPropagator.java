package io.temporal.workflow.nexus;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.nexus.Nexus;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link ContextPropagator} that carries the encryption key identifier in workflow headers.
 *
 * <p>This propagator bridges the async Nexus operation path: when a Nexus handler starts a
 * workflow, this propagator auto-detects the endpoint from the Nexus operation context via {@link
 * #getCurrentContext()} (called on the handler thread), serializes it into the workflow header, and
 * restores it via {@link #setCurrentContext(Object)} (called on the workflow execution thread). The
 * codec then reads the thread-local to select the correct encryption key.
 *
 * <p>Neither the handler nor the workflow code needs any awareness of encryption. The propagator
 * automatically discovers the key ID from the Nexus context or the existing thread-local.
 */
public class EncryptionKeyContextPropagator implements ContextPropagator {

  static final String HEADER_KEY = "x-encryption-key-id";

  @Override
  public String getName() {
    return "encryption-key-propagator";
  }

  /**
   * Called on the caller/handler thread to capture context for propagation. Auto-detects the key ID
   * from: (1) Nexus operation context (on handler threads), (2) existing thread-local (on workflow
   * threads).
   */
  @Override
  public Object getCurrentContext() {
    String keyId = null;

    // Try Nexus context first (available on handler threads)
    try {
      keyId = Nexus.getOperationContext().getInfo().getEndpoint();
    } catch (Exception e) {
      // Not on a Nexus handler thread
    }

    // Fall back to thread-local (set by setCurrentContext on workflow threads)
    if (keyId == null) {
      keyId = PerEndpointEncryptionCodec.getCurrentKeyId();
    }

    if (keyId == null) {
      return Collections.emptyMap();
    }
    Map<String, String> context = new HashMap<>();
    context.put(HEADER_KEY, keyId);
    return context;
  }

  /**
   * Called on the workflow execution thread before workflow code runs. Sets the encryption key ID
   * on the thread-local so the codec can read it during {@code encode()}.
   */
  @Override
  @SuppressWarnings("unchecked")
  public void setCurrentContext(Object context) {
    if (context instanceof Map) {
      Map<String, String> contextMap = (Map<String, String>) context;
      String keyId = contextMap.get(HEADER_KEY);
      if (keyId != null) {
        PerEndpointEncryptionCodec.setCurrentKeyId(keyId);
      }
    }
  }

  @Override
  public Map<String, Payload> serializeContext(Object context) {
    if (context instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, String> contextMap = (Map<String, String>) context;
      String keyId = contextMap.get(HEADER_KEY);
      if (keyId != null) {
        Map<String, Payload> serialized = new HashMap<>();
        serialized.put(
            HEADER_KEY, DefaultDataConverter.newDefaultInstance().toPayload(keyId).get());
        return serialized;
      }
    }
    return Collections.emptyMap();
  }

  @Override
  public Object deserializeContext(Map<String, Payload> header) {
    Map<String, String> context = new HashMap<>();
    Payload keyIdPayload = header.get(HEADER_KEY);
    if (keyIdPayload != null) {
      String keyId =
          DefaultDataConverter.newDefaultInstance()
              .fromPayload(keyIdPayload, String.class, String.class);
      context.put(HEADER_KEY, keyId);
    }
    return context;
  }
}
