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
import io.nexusrpc.handler.*;
import io.temporal.common.Experimental;
import io.temporal.internal.nexus.CurrentNexusOperationContext;
import io.temporal.internal.nexus.NexusOperationContextImpl;

/**
 * WorkflowRunNexusOperationHandler can be used to create OperationHandlers that will trigger a
 * workflow run
 */
@Experimental
public final class WorkflowRunNexusOperationHandler {

  /**
   * Maps a workflow method to a nexus operation handler.
   *
   * @param startMethod returns the workflow method reference to call
   * @return Operation handler to be used as an {@link OperationImpl}
   */
  public static <T, R> OperationHandler<T, R> fromWorkflowMethod(
      WorkflowMethodFactory<T, R> startMethod) {
    return new RunWorkflowOperation<>(
        (OperationContext context, OperationStartDetails details, WorkflowClient client, T input) ->
            WorkflowClient.start(startMethod.apply(context, details, client, input), input));
  }

  /**
   * Maps a workflow execution to a nexus operation handler.
   *
   * @param executionFactory returns the workflow execution that will be mapped to the call
   * @return Operation handler to be used as an {@link OperationImpl}
   */
  public static <T, R> OperationHandler<T, R> fromWorkflowExecution(
      WorkflowExecutionFactory<T> executionFactory) {
    return new RunWorkflowOperation<>(executionFactory);
  }

  /** Prohibits instantiation. */
  private WorkflowRunNexusOperationHandler() {}

  private static class RunWorkflowOperation<T, R> implements OperationHandler<T, R> {
    private final WorkflowExecutionFactory<T> executionFactory;

    private RunWorkflowOperation(WorkflowExecutionFactory<T> executionFactory) {
      this.executionFactory = executionFactory;
    }

    @Override
    public OperationStartResult<R> start(
        OperationContext ctx, OperationStartDetails operationStartDetails, T input) {
      NexusOperationContextImpl nexusCtx = CurrentNexusOperationContext.get();
      WorkflowClient client =
          new NexusWorkflowClientWrapper(
              nexusCtx.getWorkflowClient(),
              operationStartDetails.getRequestId(),
              operationStartDetails.getCallbackUrl(),
              operationStartDetails.getCallbackHeaders(),
              nexusCtx.getTaskQueue());

      return OperationStartResult.async(
          executionFactory.apply(ctx, operationStartDetails, client, input).getWorkflowId());
    }

    @Override
    public R fetchResult(
        OperationContext operationContext,
        OperationFetchResultDetails operationFetchResultDetails) {
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
      WorkflowClient client = CurrentNexusOperationContext.get().getWorkflowClient();
      client.newUntypedWorkflowStub(operationCancelDetails.getOperationId()).cancel();
    }
  }
}
