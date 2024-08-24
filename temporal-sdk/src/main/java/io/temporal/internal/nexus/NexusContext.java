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

package io.temporal.internal.nexus;

import io.temporal.client.WorkflowClient;
import io.temporal.common.Experimental;

@Experimental
public class NexusContext {
  private static final ThreadLocal<NexusContext> currentNexusContext = new ThreadLocal<>();

  public static NexusContext get() {
    return currentNexusContext.get();
  }

  public static void set(NexusContext context) {
    currentNexusContext.set(context);
  }

  public static void remove() {
    currentNexusContext.remove();
  }

  public WorkflowClient getWorkflowClient() {
    return workflowClient;
  }

  public String getTaskQueue() {
    return taskQueue;
  }

  private final WorkflowClient workflowClient;
  private final String taskQueue;

  public NexusContext(WorkflowClient workflowClient, String taskQueue) {
    this.workflowClient = workflowClient;
    this.taskQueue = taskQueue;
  }
}
