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

package io.temporal.workflow.nexus;

import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.NexusClient;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import io.temporal.workflow.shared.nexus.TestNexusService;
import io.temporal.workflow.shared.nexus.TestNexusServiceImpl;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class NexusCancelTest extends BaseNexusTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseExternalService(true)
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Test
  public void failSyncOperation() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(WorkflowFailedException.class, () -> workflowStub.execute(""));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
    Assert.assertEquals("failed to say hello", applicationFailure.getOriginalMessage());
  }

  @Override
  SDKTestWorkflowRule getTestWorkflowRule() {
    return testWorkflowRule;
  }

  public static class TestNexus implements TestWorkflow1 {
    @Override
    public String execute(String endpoint) {
      NexusClient nexusClient = Workflow.newNexusClient(getEndpointName());
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();
      TestNexusService testNexusService =
          nexusClient.newServiceStub(TestNexusService.class, options);
      testNexusService.fail(Workflow.getInfo().getWorkflowId());
      // Workflow will not reach this point
      return "fail";
    }
  }
}
