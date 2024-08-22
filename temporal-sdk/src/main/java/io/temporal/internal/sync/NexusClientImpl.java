package io.temporal.internal.sync;

import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.Functions;
import io.temporal.workflow.NexusClient;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusOperationStub;

class NexusClientImpl implements NexusClient {
  private final String service;
  private final String endpoint;
  private final WorkflowOutboundCallsInterceptor workflowOutboundInterceptor;
  private final Functions.Proc1<String> assertReadOnly;

  public NexusClientImpl(
      String endpoint,
      String service,
      WorkflowOutboundCallsInterceptor workflowOutboundInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    this.endpoint = endpoint;
    this.service = service;
    this.workflowOutboundInterceptor = workflowOutboundInterceptor;
    this.assertReadOnly = assertReadOnly;
  }

  @Override
  public String getEndpoint() {
    return endpoint;
  }

  @Override
  public String getService() {
    return service;
  }

  @Override
  public NexusOperationStub newUntypedNexusOperationStub(
      String operation, NexusOperationOptions options) {
    return new NexusOperationStubImpl(
        this, operation, options, workflowOutboundInterceptor, assertReadOnly);
  }
}
