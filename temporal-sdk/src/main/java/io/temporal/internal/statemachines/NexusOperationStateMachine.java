package io.temporal.internal.statemachines;

import io.temporal.api.command.v1.Command;
import io.temporal.api.command.v1.ScheduleNexusOperationCommandAttributes;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.failure.v1.CanceledFailureInfo;
import io.temporal.api.failure.v1.Failure;
import io.temporal.api.failure.v1.NexusOperationFailureInfo;
import io.temporal.api.history.v1.*;
import io.temporal.workflow.Functions;
import java.util.Optional;

public class NexusOperationStateMachine
    extends EntityStateMachineInitialCommand<
        NexusOperationStateMachine.State,
        NexusOperationStateMachine.ExplicitEvent,
        NexusOperationStateMachine> {
  private static final String JAVA_SDK = "JavaSDK";
  private static final String NEXUS_OPERATION_CANCELED_MESSAGE = "Nexus operation canceled";

  private ScheduleNexusOperationCommandAttributes scheduleAttributes;
  private final Functions.Proc2<Optional<String>, Failure> startedCallback;

  private final Functions.Proc2<Optional<Payload>, Failure> completionCallback;
  private final String endpoint;
  private final String service;
  private final String operation;

  public boolean isCancellable() {
    return State.SCHEDULE_COMMAND_CREATED == getState();
  }

  public void cancel() {
    if (!isFinalState()) {
      explicitEvent(ExplicitEvent.CANCEL);
    }
  }

  enum ExplicitEvent {
    SCHEDULE,
    CANCEL
  }

  enum State {
    CREATED,
    SCHEDULE_COMMAND_CREATED,
    SCHEDULED_EVENT_RECORDED,
    STARTED,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELED,
  }

  public static final StateMachineDefinition<
          NexusOperationStateMachine.State,
          NexusOperationStateMachine.ExplicitEvent,
          NexusOperationStateMachine>
      STATE_MACHINE_DEFINITION =
          StateMachineDefinition
              .<NexusOperationStateMachine.State, NexusOperationStateMachine.ExplicitEvent,
                  NexusOperationStateMachine>
                  newInstance(
                      "Nexus",
                      State.CREATED,
                      State.COMPLETED,
                      State.FAILED,
                      State.TIMED_OUT,
                      State.CANCELED)
              .add(
                  State.CREATED,
                  ExplicitEvent.SCHEDULE,
                  State.SCHEDULE_COMMAND_CREATED,
                  NexusOperationStateMachine::createScheduleNexusTaskCommand)
              .add(
                  State.SCHEDULE_COMMAND_CREATED,
                  CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION,
                  State.SCHEDULE_COMMAND_CREATED)
              .add(
                  State.SCHEDULE_COMMAND_CREATED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_SCHEDULED,
                  State.SCHEDULED_EVENT_RECORDED,
                  NexusOperationStateMachine::setInitialCommandEventId)
              .add(
                  State.SCHEDULE_COMMAND_CREATED,
                  ExplicitEvent.CANCEL,
                  State.CANCELED,
                  NexusOperationStateMachine::cancelNexusOperationCommand)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED,
                  State.COMPLETED,
                  NexusOperationStateMachine::notifyCompleted)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED,
                  State.FAILED,
                  NexusOperationStateMachine::notifyFailed)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED,
                  State.CANCELED,
                  NexusOperationStateMachine::notifyCanceled)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT,
                  State.TIMED_OUT,
                  NexusOperationStateMachine::notifyTimedOut)
              .add(
                  State.SCHEDULED_EVENT_RECORDED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED,
                  State.STARTED,
                  NexusOperationStateMachine::notifyStarted)
              .add(
                  State.STARTED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_COMPLETED,
                  State.COMPLETED,
                  NexusOperationStateMachine::notifyCompleted)
              .add(
                  State.STARTED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_FAILED,
                  State.FAILED,
                  NexusOperationStateMachine::notifyFailed)
              .add(
                  State.STARTED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_CANCELED,
                  State.CANCELED,
                  NexusOperationStateMachine::notifyCanceled)
              .add(
                  State.STARTED,
                  EventType.EVENT_TYPE_NEXUS_OPERATION_TIMED_OUT,
                  State.TIMED_OUT,
                  NexusOperationStateMachine::notifyTimedOut);

  private void cancelNexusOperationCommand() {
    cancelCommand();
    Failure canceledFailure =
        Failure.newBuilder()
            .setSource(JAVA_SDK)
            .setCanceledFailureInfo(CanceledFailureInfo.getDefaultInstance())
            .build();
    NexusOperationFailureInfo nexusFailureInfo =
        NexusOperationFailureInfo.newBuilder()
            .setEndpoint(endpoint)
            .setService(service)
            .setOperation(operation)
            .setScheduledEventId(getInitialCommandEventId())
            .build();
    Failure failure =
        Failure.newBuilder()
            .setNexusOperationExecutionFailureInfo(nexusFailureInfo)
            .setCause(canceledFailure)
            .setMessage(NEXUS_OPERATION_CANCELED_MESSAGE)
            .build();
    startedCallback.apply(Optional.empty(), failure);
    completionCallback.apply(Optional.empty(), failure);
  }

  private void notifyStarted() {
    // TODO(quinn) add some check to prevent duplicate calls if notifyStarted is called for of the
    // sync path
    if (currentEvent.getEventType() != EventType.EVENT_TYPE_NEXUS_OPERATION_STARTED) {
      startedCallback.apply(Optional.empty(), null);
    }
    startedCallback.apply(
        Optional.of(currentEvent.getNexusOperationStartedEventAttributes().getOperationId()), null);
  }

  private void notifyCompleted() {
    notifyStarted();
    NexusOperationCompletedEventAttributes attributes =
        currentEvent.getNexusOperationCompletedEventAttributes();
    completionCallback.apply(Optional.of(attributes.getResult()), null);
  }

  private void notifyFailed() {
    notifyStarted();
    NexusOperationFailedEventAttributes attributes =
        currentEvent.getNexusOperationFailedEventAttributes();
    completionCallback.apply(Optional.empty(), attributes.getFailure());
  }

  private void notifyCanceled() {
    notifyStarted();
    NexusOperationCanceledEventAttributes attributes =
        currentEvent.getNexusOperationCanceledEventAttributes();
    completionCallback.apply(Optional.empty(), attributes.getFailure());
  }

  private void notifyTimedOut() {
    notifyStarted();
    NexusOperationTimedOutEventAttributes attributes =
        currentEvent.getNexusOperationTimedOutEventAttributes();
    completionCallback.apply(Optional.empty(), attributes.getFailure());
  }

  /**
   * @param attributes attributes used to schedule the nexus operation
   * @param completionCallback one of ActivityTaskCompletedEvent, ActivityTaskFailedEvent,
   *     ActivityTaskTimedOutEvent, ActivityTaskCanceledEvents
   * @param commandSink sink to send commands
   * @return an instance of ActivityCommands
   */
  public static NexusOperationStateMachine newInstance(
      ScheduleNexusOperationCommandAttributes attributes,
      Functions.Proc2<Optional<String>, Failure> startedCallback,
      Functions.Proc2<Optional<Payload>, Failure> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    return new NexusOperationStateMachine(
        attributes, startedCallback, completionCallback, commandSink, stateMachineSink);
  }

  private NexusOperationStateMachine(
      ScheduleNexusOperationCommandAttributes attributes,
      Functions.Proc2<Optional<String>, Failure> startedCallback,
      Functions.Proc2<Optional<Payload>, Failure> completionCallback,
      Functions.Proc1<CancellableCommand> commandSink,
      Functions.Proc1<StateMachine> stateMachineSink) {
    super(STATE_MACHINE_DEFINITION, commandSink, stateMachineSink);
    this.scheduleAttributes = attributes;
    this.operation = attributes.getOperation();
    this.service = attributes.getService();
    this.endpoint = attributes.getEndpoint();
    this.startedCallback = startedCallback;
    this.completionCallback = completionCallback;
    explicitEvent(ExplicitEvent.SCHEDULE);
  }

  public void createScheduleNexusTaskCommand() {
    addCommand(
        Command.newBuilder()
            .setCommandType(CommandType.COMMAND_TYPE_SCHEDULE_NEXUS_OPERATION)
            .setScheduleNexusOperationCommandAttributes(scheduleAttributes)
            .build());
    scheduleAttributes = null; // avoiding retaining large input for the duration of the operation
  }
}
