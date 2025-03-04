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

package io.temporal.workflow;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowDescribe {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestInitWorkflow.class).build();

  @Test
  public void testWorkflowDescribe() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue());
    assertEquals(testWorkflowRule.getTaskQueue(), result);
    WorkflowExecutionDescription description = WorkflowStub.fromTyped(workflowStub).describe();
    assertEquals(testWorkflowRule.getTaskQueue(), description.getTaskQueue());
    assertEquals("TestWorkflow1", description.getWorkflowType());
    assertEquals(WorkflowStub.fromTyped(workflowStub).getExecution(), description.getExecution());
  }

  public static class TestInitWorkflow implements TestWorkflow1 {
    @Override
    public String execute(String taskQueue) {
      return taskQueue;
    }
  }
}
