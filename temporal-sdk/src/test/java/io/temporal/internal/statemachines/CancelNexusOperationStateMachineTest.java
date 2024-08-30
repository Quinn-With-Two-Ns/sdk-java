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

package io.temporal.internal.statemachines;

import static io.temporal.internal.statemachines.TestHistoryBuilder.assertCommand;
import static org.junit.Assert.*;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.RequestCancelNexusOperationCommandAttributes;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.NexusOperationCancelRequestedEventAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.AfterClass;
import org.junit.Test;

public class CancelNexusOperationStateMachineTest {
  private WorkflowStateMachines stateMachines;

  private static final List<
          StateMachine<
              CancelNexusOperationStateMachine.State,
              CancelNexusOperationStateMachine.ExplicitEvent,
              CancelNexusOperationStateMachine>>
      stateMachineList = new ArrayList<>();

  private WorkflowStateMachines newStateMachines(TestEntityManagerListenerBase listener) {
    return new WorkflowStateMachines(listener, (stateMachineList::add));
  }

  @AfterClass
  public static void generateCoverage() {
    List<
            Transition<
                CancelNexusOperationStateMachine.State,
                TransitionEvent<CancelNexusOperationStateMachine.ExplicitEvent>>>
        missed =
            CancelNexusOperationStateMachine.STATE_MACHINE_DEFINITION.getUnvisitedTransitions(
                stateMachineList);
    if (!missed.isEmpty()) {
      CommandsGeneratePlantUMLStateDiagrams.writeToFile(
          "test",
          CancelNexusOperationStateMachine.class,
          CancelNexusOperationStateMachine.STATE_MACHINE_DEFINITION.asPlantUMLStateDiagramCoverage(
              stateMachineList));
      fail(
          "CancelNexusOperationStateMachine is missing test coverage for the following transitions:\n"
              + missed);
    }
  }

  @Test
  public void testCancelNexusOperationStateMachine() {
    class TestListener extends TestEntityManagerListenerBase {
      @Override
      protected void buildWorkflow(AsyncWorkflowBuilder<Void> builder) {
        RequestCancelNexusOperationCommandAttributes attributes =
            RequestCancelNexusOperationCommandAttributes.newBuilder()
                .setScheduledEventId(4)
                .build();
        builder
            .add((v) -> stateMachines.requestCancelNexusOperation(attributes))
            .<Optional<Payloads>>add1((r, c) -> stateMachines.completeWorkflow(Optional.empty()));
      }
    }
    /*
        1: EVENT_TYPE_WORKFLOW_EXECUTION_STARTED
        2: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        3: EVENT_TYPE_WORKFLOW_TASK_STARTED
        4: EVENT_TYPE_WORKFLOW_TASK_COMPLETED
        5: EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED
        6: EVENT_TYPE_WORKFLOW_TASK_SCHEDULED
        7: EVENT_TYPE_WORKFLOW_TASK_STARTED
    */
    TestHistoryBuilder h =
        new TestHistoryBuilder()
            .add(EventType.EVENT_TYPE_WORKFLOW_EXECUTION_STARTED)
            .addWorkflowTask()
            .add(
                EventType.EVENT_TYPE_NEXUS_OPERATION_CANCEL_REQUESTED,
                NexusOperationCancelRequestedEventAttributes.newBuilder()
                    .setScheduledEventId(4)
                    .build())
            .addWorkflowTaskScheduledAndStarted();
    {
      TestEntityManagerListenerBase listener = new TestListener();
      stateMachines = newStateMachines(listener);
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 1);
      assertCommand(CommandType.COMMAND_TYPE_REQUEST_CANCEL_NEXUS_OPERATION, commands);
      assertEquals(
          4,
          commands.get(0).getRequestCancelNexusOperationCommandAttributes().getScheduledEventId());
    }
    {
      List<Command> commands = h.handleWorkflowTaskTakeCommands(stateMachines, 2);
      assertEquals(1, commands.size());
      assertEquals(
          CommandType.COMMAND_TYPE_COMPLETE_WORKFLOW_EXECUTION, commands.get(0).getCommandType());
    }
  }
}
