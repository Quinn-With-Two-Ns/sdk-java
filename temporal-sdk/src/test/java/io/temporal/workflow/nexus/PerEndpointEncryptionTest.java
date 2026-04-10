package io.temporal.workflow.nexus;

import static org.junit.Assert.*;

import com.google.protobuf.ByteString;
import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.history.v1.History;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.converter.CodecDataConverter;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.internal.nexus.OperationTokenUtil;
import io.temporal.internal.nexus.WorkflowRunOperationToken;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;

/**
 * Demonstrates per-endpoint encryption for Nexus operations.
 *
 * <p>Shows how a caller can encrypt payloads with a key specific to the Nexus endpoint, and how the
 * handler manages decryption and re-encryption for both synchronous and asynchronous
 * (workflow-backed) operation paths — with zero encryption awareness in handler or workflow code.
 *
 * <p>Key design points:
 *
 * <ul>
 *   <li>The {@link PerEndpointEncryptionCodec} auto-detects the endpoint from Nexus context on
 *       handler threads, or from the thread-local set by the {@link
 *       PerEndpointEncryptionInterceptor} on workflow threads. Falls back to a default key for
 *       other contexts.
 *   <li>{@code decode()} reads the key ID from payload metadata (self-describing), so handler-side
 *       input deserialization works before handler code runs.
 *   <li>Nexus handler code is pure business logic — no encryption concerns.
 * </ul>
 */
public class PerEndpointEncryptionTest extends BaseNexusTest {

  private static final String DEFAULT_KEY_ID = "default-test-key";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(CallerWorkflow.class, AsyncOperationWorkflowImpl.class)
          .setNexusServiceImplementation(new EncryptionNexusServiceImpl())
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setDataConverter(
                      new CodecDataConverter(
                          DefaultDataConverter.newDefaultInstance(),
                          Collections.singletonList(new PerEndpointEncryptionCodec(DEFAULT_KEY_ID)),
                          true))
                  .setInterceptors(new PerEndpointEncryptionClientInterceptor())
                  .build())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new PerEndpointEncryptionWorkerInterceptor())
                  .build())
          .build();

  @Override
  protected SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  // -- Nexus Service Interface --

  @Service
  public interface EncryptionNexusService {
    @Operation
    String syncOperation(String input);

    @Operation
    String asyncOperation(String input);
  }

  // -- Workflow Interfaces --

  @WorkflowInterface
  public interface TestCallerWorkflow {
    @WorkflowMethod
    String execute(String input);
  }

  @WorkflowInterface
  public interface AsyncOperationWorkflow {
    @WorkflowMethod
    String execute(String input);
  }

  // -- Caller Workflow: pure business logic, no encryption awareness --

  public static class CallerWorkflow implements TestCallerWorkflow {
    @Override
    public String execute(String input) {
      NexusOperationOptions operationOptions =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .build();
      NexusServiceOptions serviceOptions =
          NexusServiceOptions.newBuilder()
              .setEndpoint(getEndpointName())
              .setOperationOptions(operationOptions)
              .build();
      EncryptionNexusService stub =
          Workflow.newNexusServiceStub(EncryptionNexusService.class, serviceOptions);

      String syncResult = stub.syncOperation(input);
      String asyncResult = stub.asyncOperation(input);
      return syncResult + "|" + asyncResult;
    }
  }

  // -- Async Operation Workflow: pure business logic, no encryption awareness --

  public static class AsyncOperationWorkflowImpl implements AsyncOperationWorkflow {
    @Override
    public String execute(String input) {
      return "async:" + input;
    }
  }

  // -- Nexus Handler: pure business logic, no encryption awareness --

  @ServiceImpl(service = EncryptionNexusService.class)
  public static class EncryptionNexusServiceImpl {

    @OperationImpl
    public OperationHandler<String, String> syncOperation() {
      return OperationHandler.sync((ctx, details, input) -> "sync:" + input);
    }

    @OperationImpl
    public OperationHandler<String, String> asyncOperation() {
      return WorkflowRunOperation.fromWorkflowMethod(
          (context, details, input) ->
              Nexus.getOperationContext()
                      .getWorkflowClient()
                      .newWorkflowStub(
                          AsyncOperationWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId("encryption-async-" + details.getRequestId())
                              .setTaskQueue(Nexus.getOperationContext().getInfo().getTaskQueue())
                              .build())
                  ::execute);
    }
  }

  // -- Tests --

  @Test
  public void testSyncAndAsyncEncryption() {
    TestCallerWorkflow workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestCallerWorkflow.class);
    String result = workflowStub.execute("hello");

    String[] parts = result.split("\\|");
    assertEquals(2, parts.length);
    assertEquals("sync:hello", parts[0]);
    assertEquals("async:hello", parts[1]);
  }

  @Test
  public void testPayloadsEncryptedWithCorrectKeys() {
    // Verify that payloads are encrypted AND that the correct per-endpoint key is used.
    String expectedEndpointKey = "test-endpoint-" + testWorkflowRule.getTaskQueue();

    TestCallerWorkflow workflowStub =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TestCallerWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId("encryption-wire-test")
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                    .build());
    String result = workflowStub.execute("secret-data");
    assertEquals("sync:secret-data|async:secret-data", result);

    // --- Verify caller workflow history ---
    History callerHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory("encryption-wire-test").getHistory();

    String asyncWorkflowId = null;
    boolean foundEncryptedPayload = false;

    for (HistoryEvent event : callerHistory.getEventsList()) {
      if (event.hasWorkflowExecutionStartedEventAttributes()) {
        Payload inputPayload =
            event.getWorkflowExecutionStartedEventAttributes().getInput().getPayloads(0);
        assertPayloadIsEncrypted(inputPayload, "CallerWorkflow input");
        foundEncryptedPayload = true;
      }
      if (event.hasNexusOperationScheduledEventAttributes()) {
        Payload nexusInput = event.getNexusOperationScheduledEventAttributes().getInput();
        assertPayloadIsEncrypted(nexusInput, "NexusOperationScheduled input");
        // Verify the Nexus operation input uses the endpoint key, not the default key.
        String nexusInputKeyId =
            nexusInput
                .getMetadataOrThrow(PerEndpointEncryptionCodec.METADATA_KEY_ID)
                .toStringUtf8();
        assertEquals(
            "Nexus operation input must use endpoint key, not default",
            expectedEndpointKey,
            nexusInputKeyId);
        foundEncryptedPayload = true;
      }
      if (event.hasWorkflowExecutionCompletedEventAttributes()) {
        Payload resultPayload =
            event.getWorkflowExecutionCompletedEventAttributes().getResult().getPayloads(0);
        assertPayloadIsEncrypted(resultPayload, "CallerWorkflow result");
        foundEncryptedPayload = true;
      }
      // Find the async workflow ID from the operation token
      if (event.hasNexusOperationStartedEventAttributes()) {
        String opToken = event.getNexusOperationStartedEventAttributes().getOperationToken();
        if (opToken != null && !opToken.isEmpty()) {
          WorkflowRunOperationToken token =
              OperationTokenUtil.loadWorkflowRunOperationToken(opToken);
          asyncWorkflowId = token.getWorkflowId();
        }
      }
    }
    assertTrue("Should have found encrypted payloads in caller history", foundEncryptedPayload);

    // --- Verify async workflow uses the ENDPOINT key, not the default key ---
    assertNotNull("Should have found async workflow ID from operation token", asyncWorkflowId);

    History asyncHistory =
        testWorkflowRule.getWorkflowClient().fetchHistory(asyncWorkflowId).getHistory();

    boolean foundAsyncResult = false;
    for (HistoryEvent event : asyncHistory.getEventsList()) {
      if (event.hasWorkflowExecutionCompletedEventAttributes()) {
        Payload resultPayload =
            event.getWorkflowExecutionCompletedEventAttributes().getResult().getPayloads(0);
        assertPayloadIsEncrypted(resultPayload, "AsyncWorkflow result");

        // This is the critical assertion: the async workflow's result must be encrypted
        // with the endpoint-specific key, proving the interceptor carried it through.
        String keyId =
            resultPayload
                .getMetadataOrThrow(PerEndpointEncryptionCodec.METADATA_KEY_ID)
                .toStringUtf8();
        assertEquals(
            "Async workflow result must use endpoint key, not default", expectedEndpointKey, keyId);
        foundAsyncResult = true;
      }
    }
    assertTrue("Should have found async workflow result in history", foundAsyncResult);
  }

  @Test
  public void testPerEndpointKeySelection() {
    PerEndpointEncryptionCodec codec = new PerEndpointEncryptionCodec(DEFAULT_KEY_ID);

    Payload testPayload =
        Payload.newBuilder().setData(ByteString.copyFromUtf8("test-data")).build();

    PerEndpointEncryptionCodec.clearCurrentKeyId();
    Payload encodedDefault = codec.encode(Collections.singletonList(testPayload)).get(0);

    PerEndpointEncryptionCodec.setCurrentKeyId("my-endpoint");
    Payload encodedEndpoint = codec.encode(Collections.singletonList(testPayload)).get(0);
    PerEndpointEncryptionCodec.clearCurrentKeyId();

    assertNotEquals(encodedDefault.getData(), encodedEndpoint.getData());

    assertEquals(
        DEFAULT_KEY_ID,
        encodedDefault
            .getMetadataOrThrow(PerEndpointEncryptionCodec.METADATA_KEY_ID)
            .toStringUtf8());
    assertEquals(
        "my-endpoint",
        encodedEndpoint
            .getMetadataOrThrow(PerEndpointEncryptionCodec.METADATA_KEY_ID)
            .toStringUtf8());

    assertEquals(
        testPayload.getData(),
        codec.decode(Collections.singletonList(encodedDefault)).get(0).getData());
    assertEquals(
        testPayload.getData(),
        codec.decode(Collections.singletonList(encodedEndpoint)).get(0).getData());
  }

  @Test
  public void testCodecDecodeIsSelfDescribing() {
    PerEndpointEncryptionCodec codec = new PerEndpointEncryptionCodec(DEFAULT_KEY_ID);

    PerEndpointEncryptionCodec.setCurrentKeyId("some-endpoint");
    Payload original =
        Payload.newBuilder().setData(ByteString.copyFromUtf8("sensitive-data")).build();
    Payload encoded = codec.encode(Collections.singletonList(original)).get(0);
    PerEndpointEncryptionCodec.clearCurrentKeyId();

    assertTrue(encoded.getData().toStringUtf8().startsWith("ENC:some-endpoint:"));

    assertNull(PerEndpointEncryptionCodec.getCurrentKeyId());
    Payload decoded = codec.decode(Collections.singletonList(encoded)).get(0);
    assertEquals(original.getData(), decoded.getData());
  }

  // -- Helpers --

  private void assertPayloadIsEncrypted(Payload payload, String context) {
    String data = payload.getData().toStringUtf8();
    assertTrue(
        context + ": payload should be encrypted (contain ENC: prefix) but was: " + data,
        data.contains(PerEndpointEncryptionCodec.ENC_PREFIX));
  }
}
