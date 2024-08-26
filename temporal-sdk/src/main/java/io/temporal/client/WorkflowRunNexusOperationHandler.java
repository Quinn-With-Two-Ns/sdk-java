/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.client;

import io.nexusrpc.OperationInfo;
import io.nexusrpc.OperationNotFoundException;
import io.nexusrpc.OperationUnsuccessfulException;
import io.nexusrpc.handler.*;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.common.Experimental;
import io.temporal.internal.nexus.NexusContext;

/** WorkflowRunNexusOperationHandler is a OperationHandler that will trigger a workflow run */
@Experimental
public class WorkflowRunNexusOperationHandler<T, R> implements OperationHandler<T, R> {

  private final WorkflowMethodFactory<T, R> startMethod;

  private WorkflowRunNexusOperationHandler(WorkflowMethodFactory<T, R> startMethod) {
    this.startMethod = startMethod;
  }

  /**
   * Maps a workflow method to a nexus operation handler.
   *
   * @param startMethod returns the workflow method reference to call
   * @return Operation handler to be used as an {@link OperationImpl}
   */
  public static <T, R> WorkflowRunNexusOperationHandler<T, R> fromWorkflowMethod(
      WorkflowMethodFactory<T, R> startMethod) {
    return new WorkflowRunNexusOperationHandler<>(startMethod);
  }

  @Override
  public OperationStartResult<R> start(
      OperationContext ctx, OperationStartDetails operationStartDetails, T input)
      throws OperationUnsuccessfulException {
    NexusContext nexusCtx = NexusContext.get();
    WorkflowClient client =
        new NexusWorkflowClientWrapper(
            nexusCtx.getWorkflowClient(),
            operationStartDetails.getRequestId(),
            operationStartDetails.getCallbackUrl(),
            operationStartDetails.getCallbackHeaders(),
            nexusCtx.getTaskQueue());

    NexusStartWorkflowRequest nexusStartWorkflowRequest =
        new NexusStartWorkflowRequest(
            operationStartDetails.getRequestId(),
            operationStartDetails.getCallbackUrl(),
            operationStartDetails.getCallbackHeaders(),
            NexusContext.get().getTaskQueue());

    WorkflowExecution exec =
        WorkflowClientInternalImpl.startNexus(
            nexusStartWorkflowRequest,
            startMethod.apply(ctx, operationStartDetails, client, input),
            input);
    return OperationStartResult.async(exec.getWorkflowId());
  }

  @Override
  public R fetchResult(
      OperationContext operationContext, OperationFetchResultDetails operationFetchResultDetails) {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public OperationInfo fetchInfo(
      OperationContext operationContext, OperationFetchInfoDetails operationFetchInfoDetails) {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public void cancel(
      OperationContext operationContext, OperationCancelDetails operationCancelDetails)
      throws OperationNotFoundException {
    WorkflowClient client = NexusContext.get().getWorkflowClient();
    client.newUntypedWorkflowStub(operationCancelDetails.getOperationId()).cancel();
  }
}
