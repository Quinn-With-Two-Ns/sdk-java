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

package io.temporal.workflow.updateTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.*;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Async;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows.WorkflowWithUpdate;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class SpeculativeUpdateTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestUpdateWorkflowImpl.class)
          .setActivityImplementations(new TestActivities.TestActivitiesImpl())
          .setUseExternalService(true)
          .build();

  @Test(timeout = 60000)
  public void speculativeUpdateRejected() {
    String workflowId = UUID.randomUUID().toString();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    WorkflowWithUpdate workflow = workflowClient.newWorkflowStub(WorkflowWithUpdate.class, options);
    // To execute workflow client.execute() would do. But we want to start workflow and immediately
    // return.
    WorkflowExecution execution = WorkflowClient.start(workflow::execute);

    workflow.update(3, "test value");
    // This update is going to be rejected, the resulting workflow task will not appear in history
    assertThrows(WorkflowUpdateException.class, () -> workflow.update(0, "reject"));

    assertThrows(WorkflowUpdateException.class, () -> workflow.update(0, "reject"));
    // Create more events to make sure the server persists the workflow tasks
    workflow.update(12, "test value");
    // This update is going to be rejected, the resulting workflow task will appear in history
    assertThrows(WorkflowUpdateException.class, () -> workflow.update(0, "reject"));

    assertThrows(WorkflowUpdateException.class, () -> workflow.update(0, "reject"));

    workflow.complete();
    String result =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(execution, Optional.empty())
            .getResult(String.class);
    assertEquals("", result);
  }

  public static class TestUpdateWorkflowImpl implements WorkflowWithUpdate {
    String state = "initial";
    CompletablePromise<Void> promise = Workflow.newPromise();

    private final TestActivities.VariousTestActivities activities =
        Workflow.newActivityStub(
            TestActivities.VariousTestActivities.class,
            ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(200))
                .build());

    @Override
    public String execute() {
      promise.get();
      return "";
    }

    @Override
    public String getState() {
      return state;
    }

    @Override
    public String update(Integer index, String value) {
      Random random = Workflow.newRandom();
      for (int i = 0; i <= index; i++) {
        int choice = random.nextInt(3);
        if (choice == 0) {
          Async.function(activities::sleepActivity, new Long(10000), 0);
        } else if (choice == 1) {
          Workflow.getVersion("test version " + i, Workflow.DEFAULT_VERSION, 1);
        } else {
          Workflow.newTimer(Duration.ofMillis(10));
        }
      }
      return value;
    }

    @Override
    public void updateValidator(Integer index, String value) {
      if (value.equals("reject")) {
        throw new RuntimeException("Rejecting update");
      }
    }

    @Override
    public void complete() {
      promise.complete(null);
    }

    @Override
    public void completeValidator() {}
  }
}
