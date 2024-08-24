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

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.api.nexus.v1.EndpointSpec;
import io.temporal.api.nexus.v1.EndpointTarget;
import io.temporal.api.operatorservice.v1.CreateNexusEndpointRequest;
import io.temporal.api.operatorservice.v1.CreateNexusEndpointResponse;
import io.temporal.client.WorkflowFailedException;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.NexusOperationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.UUID;
import org.junit.*;

public class NexusFailTest {
  final String ENDPOINT_NAME = "test-endpoint-" + UUID.randomUUID();
  Endpoint endpoint;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseExternalService(true)
          .setWorkflowTypes(TestNexus.class)
          .setNexusServiceImplementation(new GreetingServiceImpl())
          .build();

  @Before
  public void setUp() {
    endpoint = createTestEndpoint(getTestEndpointSpecBuilder(ENDPOINT_NAME));
  }

  @After
  public void tearDown() {
    testWorkflowRule
        .getTestEnvironment()
        .getOperatorServiceStubs()
        .blockingStub()
        .deleteNexusEndpoint(
            io.temporal.api.operatorservice.v1.DeleteNexusEndpointRequest.newBuilder()
                .setId(endpoint.getId())
                .setVersion(endpoint.getVersion())
                .build());
  }

  @Test
  public void failSyncOperation() {
    TestWorkflow1 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    WorkflowFailedException exception =
        Assert.assertThrows(
            WorkflowFailedException.class, () -> workflowStub.execute(ENDPOINT_NAME));
    Assert.assertTrue(exception.getCause() instanceof NexusOperationFailure);
    NexusOperationFailure nexusFailure = (NexusOperationFailure) exception.getCause();
    Assert.assertTrue(nexusFailure.getCause() instanceof ApplicationFailure);
    ApplicationFailure applicationFailure = (ApplicationFailure) nexusFailure.getCause();
    Assert.assertEquals("failed to say hello", applicationFailure.getOriginalMessage());
  }

  public static class TestNexus implements TestWorkflow1 {
    @Override
    public String execute(String endpoint) {
      NexusClient nexusClient = Workflow.newNexusClient(endpoint);
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(5))
              .build();
      GreetingService greetingService = nexusClient.newServiceStub(GreetingService.class, options);
      greetingService.fail(Workflow.getInfo().getWorkflowId());
      return "fail";
    }
  }

  private EndpointSpec.Builder getTestEndpointSpecBuilder(String name) {
    return EndpointSpec.newBuilder()
        .setName(name)
        .setDescription(Payload.newBuilder().setData(ByteString.copyFromUtf8("test endpoint")))
        .setTarget(
            EndpointTarget.newBuilder()
                .setWorker(
                    EndpointTarget.Worker.newBuilder()
                        .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                        .setTaskQueue(testWorkflowRule.getTaskQueue())));
  }

  private Endpoint createTestEndpoint(EndpointSpec.Builder spec) {
    CreateNexusEndpointResponse resp =
        testWorkflowRule
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .createNexusEndpoint(CreateNexusEndpointRequest.newBuilder().setSpec(spec).build());
    return resp.getEndpoint();
  }
}
