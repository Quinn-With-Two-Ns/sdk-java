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

import com.google.common.base.Preconditions;
import io.temporal.api.common.v1.Callback;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.TaskReachability;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.common.Experimental;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

final class NexusWorkflowClientWrapper implements WorkflowClient {
  private final WorkflowClient internal;
  private final String requestId;
  private final String callbackUrl;
  private final Map<String, String> callbackHeaders;
  private final String taskQueue;

  public NexusWorkflowClientWrapper(
      WorkflowClient workflowClient,
      String requestId,
      String callbackUrl,
      Map<String, String> callbackHeaders,
      String taskQueue) {
    Preconditions.checkNotNull(workflowClient, "delegate client is not null");
    this.internal = workflowClient;
    this.requestId = requestId;
    this.callbackUrl = callbackUrl;
    this.callbackHeaders = callbackHeaders;
    this.taskQueue = taskQueue;
  }

  public static WorkflowClient newInstance(WorkflowServiceStubs service) {
    return WorkflowClient.newInstance(service);
  }

  public static WorkflowClient newInstance(
      WorkflowServiceStubs service, WorkflowClientOptions options) {
    return WorkflowClient.newInstance(service, options);
  }

  @Override
  public WorkflowStub newUntypedWorkflowStub(
      String workflowId, Optional<String> runId, Optional<String> workflowType) {
    return internal.newUntypedWorkflowStub(workflowId, runId, workflowType);
  }

  @Override
  public WorkflowStub newUntypedWorkflowStub(
      WorkflowExecution execution, Optional<String> workflowType) {
    return internal.newUntypedWorkflowStub(execution, workflowType);
  }

  @Override
  public ActivityCompletionClient newActivityCompletionClient() {
    return internal.newActivityCompletionClient();
  }

  @Override
  public BatchRequest newSignalWithStartRequest() {
    return internal.newSignalWithStartRequest();
  }

  @Override
  public WorkflowExecution signalWithStart(BatchRequest signalWithStartBatch) {
    return internal.signalWithStart(signalWithStartBatch);
  }

  @Override
  public Stream<WorkflowExecutionMetadata> listExecutions(@Nullable String query) {
    return internal.listExecutions(query);
  }

  @Override
  public Stream<HistoryEvent> streamHistory(@Nonnull String workflowId) {
    return internal.streamHistory(workflowId);
  }

  @Override
  public Stream<HistoryEvent> streamHistory(@Nonnull String workflowId, @Nullable String runId) {
    return internal.streamHistory(workflowId, runId);
  }

  @Override
  public WorkflowExecutionHistory fetchHistory(@Nonnull String workflowId) {
    return internal.fetchHistory(workflowId);
  }

  @Override
  public WorkflowExecutionHistory fetchHistory(@Nonnull String workflowId, @Nullable String runId) {
    return internal.fetchHistory(workflowId, runId);
  }

  @Override
  @Experimental
  public void updateWorkerBuildIdCompatability(
      @Nonnull String taskQueue, @Nonnull BuildIdOperation operation) {
    internal.updateWorkerBuildIdCompatability(taskQueue, operation);
  }

  @Override
  @Experimental
  public WorkerBuildIdVersionSets getWorkerBuildIdCompatability(@Nonnull String taskQueue) {
    return internal.getWorkerBuildIdCompatability(taskQueue);
  }

  @Override
  @Experimental
  public WorkerTaskReachability getWorkerTaskReachability(
      @Nonnull Iterable<String> buildIds,
      @Nonnull Iterable<String> taskQueues,
      TaskReachability reachability) {
    return internal.getWorkerTaskReachability(buildIds, taskQueues, reachability);
  }

  @Override
  public Object getInternal() {
    return internal.getInternal();
  }

  @Override
  public WorkflowClientOptions getOptions() {
    return internal.getOptions();
  }

  @Override
  public WorkflowServiceStubs getWorkflowServiceStubs() {
    return internal.getWorkflowServiceStubs();
  }

  @Override
  public <T> T newWorkflowStub(Class<T> workflowInterface, WorkflowOptions options) {
    WorkflowOptions.Builder nexusWorkflowOptions =
        WorkflowOptions.newBuilder(options)
            .setRequestID(requestId)
            .setCallbacks(
                Arrays.asList(
                    Callback.newBuilder()
                        .setNexus(
                            Callback.Nexus.newBuilder()
                                .setUrl(callbackUrl)
                                .putAllHeader(callbackHeaders)
                                .build())
                        .build()));
    if (options.getTaskQueue() == null) {
      nexusWorkflowOptions.setTaskQueue(taskQueue);
    }
    return internal.newWorkflowStub(workflowInterface, nexusWorkflowOptions.build());
  }

  @Override
  public <T> T newWorkflowStub(Class<T> workflowInterface, String workflowId) {
    return internal.newWorkflowStub(workflowInterface, workflowId);
  }

  @Override
  public <T> T newWorkflowStub(
      Class<T> workflowInterface, String workflowId, Optional<String> runId) {
    return internal.newWorkflowStub(workflowInterface, workflowId, runId);
  }

  @Override
  public WorkflowStub newUntypedWorkflowStub(String workflowId) {
    return internal.newUntypedWorkflowStub(workflowId);
  }

  @Override
  public WorkflowStub newUntypedWorkflowStub(String workflowType, WorkflowOptions options) {
    return internal.newUntypedWorkflowStub(workflowType, options);
  }
}
