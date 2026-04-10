package io.temporal.workflow.nexus;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.ActivityCompletionClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptor;
import io.temporal.common.interceptors.WorkflowClientCallsInterceptorBase;
import io.temporal.common.interceptors.WorkflowClientInterceptor;
import io.temporal.nexus.Nexus;
import java.util.Optional;

/**
 * Client interceptor for per-endpoint Nexus encryption.
 *
 * <p>When a workflow is started from a Nexus operation handler, injects the endpoint name into the
 * workflow's header. The {@link PerEndpointEncryptionWorkerInterceptor} reads it on the workflow
 * thread and sets the codec's thread-local key, ensuring the async workflow result is encrypted
 * with the correct per-endpoint key.
 */
public class PerEndpointEncryptionClientInterceptor implements WorkflowClientInterceptor {

  static final String ENDPOINT_HEADER_KEY = "x-encryption-endpoint";

  @Override
  public WorkflowClientCallsInterceptor workflowClientCallsInterceptor(
      WorkflowClientCallsInterceptor next) {
    return new WorkflowClientCallsInterceptorBase(next) {
      @Override
      public WorkflowStartOutput start(WorkflowStartInput input) {
        // If we're on a Nexus handler thread, inject the endpoint into the workflow header.
        if (Nexus.isInOperationHandler()) {
          String endpoint = Nexus.getOperationContext().getInfo().getEndpoint();
          input
              .getHeader()
              .getValues()
              .put(
                  ENDPOINT_HEADER_KEY,
                  DefaultDataConverter.newDefaultInstance().toPayload(endpoint).get());
        }
        return super.start(input);
      }
    };
  }

  @Override
  @Deprecated
  public WorkflowStub newUntypedWorkflowStub(
      String workflowType, WorkflowOptions options, WorkflowStub next) {
    return next;
  }

  @Override
  @Deprecated
  public WorkflowStub newUntypedWorkflowStub(
      WorkflowExecution execution, Optional<String> workflowType, WorkflowStub next) {
    return next;
  }

  @Override
  public ActivityCompletionClient newActivityCompletionClient(ActivityCompletionClient next) {
    return next;
  }
}
