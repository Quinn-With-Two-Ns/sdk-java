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
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import io.temporal.workflow.shared.nexus.TestNexusService;
import io.temporal.workflow.shared.nexus.TestNexusServiceImpl;
import java.time.Duration;
import java.util.UUID;
import org.junit.*;

public class NexusWorkflowTest {
  static final String ENDPOINT_NAME = "test-endpoint-" + UUID.randomUUID();
  Endpoint endpoint;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setUseExternalService(true)
          .setWorkflowTypes(TestNexus.class, TestWorkflow1Impl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
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
  public void startWorkflowOperation() {
    TestWorkflows.TestWorkflow2 workflowStub =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflows.TestWorkflow2.class);
    String result = workflowStub.execute(testWorkflowRule.getTaskQueue(), "Nexus");
    Assert.assertEquals("Hello from workflow: Nexus", result);
  }

  public static class TestNexus implements TestWorkflows.TestWorkflow2 {
    @Override
    public String execute(String taskQueue, String arg2) {
      NexusClient nexusClient = Workflow.newNexusClient(ENDPOINT_NAME);
      NexusOperationOptions options =
          NexusOperationOptions.newBuilder()
              .setScheduleToCloseTimeout(Duration.ofSeconds(10))
              .build();
      TestNexusService testNexusService =
          nexusClient.newServiceStub(TestNexusService.class, options);
      return testNexusService.runWorkflow(arg2);
    }
  }

  public static class TestWorkflow1Impl implements TestWorkflows.TestWorkflow1 {

    @Override
    public String execute(String arg) {
      return "Hello from workflow: " + arg;
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
