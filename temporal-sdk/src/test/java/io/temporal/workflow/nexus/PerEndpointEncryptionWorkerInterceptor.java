package io.temporal.workflow.nexus;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.*;

/**
 * Worker interceptor for per-endpoint Nexus encryption.
 *
 * <p>Two interception points:
 *
 * <ul>
 *   <li><b>Caller workflow (outbound)</b>: Before a Nexus operation executes, sets the codec's
 *       thread-local key from the endpoint name. This ensures the codec encrypts the Nexus
 *       operation input with the correct per-endpoint key.
 *   <li><b>Async workflow (inbound)</b>: When a workflow started by a Nexus handler begins, reads
 *       the endpoint from a header (injected by {@link PerEndpointEncryptionClientInterceptor}) and
 *       sets the thread-local. This ensures the codec encrypts the workflow result with the
 *       endpoint key.
 * </ul>
 */
public class PerEndpointEncryptionWorkerInterceptor extends WorkerInterceptorBase {

  @Override
  public WorkflowInboundCallsInterceptor interceptWorkflow(WorkflowInboundCallsInterceptor next) {
    return new WorkflowInboundCallsInterceptorBase(next) {
      @Override
      public void init(WorkflowOutboundCallsInterceptor outboundCalls) {
        next.init(
            new WorkflowOutboundCallsInterceptorBase(outboundCalls) {
              @Override
              public <R> ExecuteNexusOperationOutput<R> executeNexusOperation(
                  ExecuteNexusOperationInput<R> input) {
                // Set the key from the endpoint BEFORE the input is serialized.
                PerEndpointEncryptionCodec.setCurrentKeyId(input.getEndpoint());
                return super.executeNexusOperation(input);
              }
            });
      }

      @Override
      public WorkflowOutput execute(WorkflowInput input) {
        // If this workflow was started by a Nexus handler, the client interceptor injected
        // the endpoint into the header. Read it and set the thread-local.
        Payload endpointPayload =
            input
                .getHeader()
                .getValues()
                .get(PerEndpointEncryptionClientInterceptor.ENDPOINT_HEADER_KEY);
        if (endpointPayload != null) {
          String endpoint =
              DefaultDataConverter.newDefaultInstance()
                  .fromPayload(endpointPayload, String.class, String.class);
          PerEndpointEncryptionCodec.setCurrentKeyId(endpoint);
        }
        return super.execute(input);
      }
    };
  }
}
