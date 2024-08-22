package io.temporal.workflow;

import io.temporal.common.Experimental;

@Experimental
public interface NexusClient {
  String getEndpoint();

  String getService();
  /**
   * Creates untyped client stub that can be used to execute a Nexus operation.
   *
   * @param operation name of the operation to start.
   * @param options options passed to the nexus operation.
   */
  @Experimental
  NexusOperationStub newUntypedNexusOperationStub(String operation, NexusOperationOptions options);
}
