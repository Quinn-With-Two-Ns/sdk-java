package io.temporal.internal.activity;

import com.google.protobuf.util.Timestamps;
import io.temporal.api.common.v1.Header;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.workflowservice.v1.PollActivityTaskQueueResponseOrBuilder;
import io.temporal.common.Priority;
import io.temporal.internal.common.ProtoConverters;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.workflow.Functions;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nonnull;

final class ActivityInfoImpl implements ActivityInfoInternal {
  private final String namespace;
  private final String activityTaskQueue;
  private final PollActivityTaskQueueResponseOrBuilder response;
  private final boolean local;
  private final Functions.Proc completionHandle;

  ActivityInfoImpl(
      PollActivityTaskQueueResponseOrBuilder response,
      @Nonnull String namespace,
      @Nonnull String activityTaskQueue,
      boolean local,
      Functions.Proc completionHandle) {
    this.response = Objects.requireNonNull(response);
    this.namespace = Objects.requireNonNull(namespace);
    this.activityTaskQueue = Objects.requireNonNull(activityTaskQueue);
    this.local = local;
    this.completionHandle = completionHandle;
  }

  @Override
  public byte[] getTaskToken() {
    return response.getTaskToken().toByteArray();
  }

  @Override
  public String getWorkflowId() {
    return response.getWorkflowExecution().getWorkflowId();
  }

  @Override
  public String getRunId() {
    return response.getWorkflowExecution().getRunId();
  }

  @Override
  public String getActivityId() {
    return response.getActivityId();
  }

  @Override
  public String getActivityType() {
    return response.getActivityType().getName();
  }

  @Override
  public long getScheduledTimestamp() {
    return Timestamps.toMillis(response.getScheduledTime());
  }

  @Override
  public long getStartedTimestamp() {
    return Timestamps.toMillis(response.getStartedTime());
  }

  @Override
  public long getCurrentAttemptScheduledTimestamp() {
    return Timestamps.toMillis(response.getCurrentAttemptScheduledTime());
  }

  @Override
  public Duration getScheduleToCloseTimeout() {
    return ProtobufTimeUtils.toJavaDuration(response.getScheduleToCloseTimeout());
  }

  @Override
  public Duration getStartToCloseTimeout() {
    return ProtobufTimeUtils.toJavaDuration(response.getStartToCloseTimeout());
  }

  @Nonnull
  @Override
  public Duration getHeartbeatTimeout() {
    return ProtobufTimeUtils.toJavaDuration(response.getHeartbeatTimeout());
  }

  @Override
  public Optional<Payloads> getHeartbeatDetails() {
    if (response.hasHeartbeatDetails()) {
      return Optional.of(response.getHeartbeatDetails());
    } else {
      return Optional.empty();
    }
  }

  @Override
  public String getWorkflowType() {
    return response.getWorkflowType().getName();
  }

  @Override
  @Deprecated
  public String getWorkflowNamespace() {
    return getNamespace();
  }

  @Override
  @Deprecated
  public String getActivityNamespace() {
    return getNamespace();
  }

  @Override
  public String getNamespace() {
    return namespace;
  }

  @Override
  public String getActivityTaskQueue() {
    return activityTaskQueue;
  }

  @Override
  public int getAttempt() {
    return response.getAttempt();
  }

  @Override
  public boolean isLocal() {
    return local;
  }

  @Override
  public Priority getPriority() {
    return ProtoConverters.fromProto(response.getPriority());
  }

  @Override
  public Functions.Proc getCompletionHandle() {
    return completionHandle;
  }

  @Override
  public Optional<Payloads> getInput() {
    if (response.hasInput()) {
      return Optional.of(response.getInput());
    }
    return Optional.empty();
  }

  @Override
  public Optional<Header> getHeader() {
    if (response.hasHeader()) {
      return Optional.of(response.getHeader());
    }
    return Optional.empty();
  }

  @Override
  public String toString() {
    return "WorkflowInfo{"
        + ", workflowId="
        + getWorkflowId()
        + ", runId="
        + getRunId()
        + ", activityId="
        + getActivityId()
        + ", activityType="
        + getActivityType()
        + ", scheduledTimestamp="
        + getScheduledTimestamp()
        + ", startedTimestamp="
        + getStartedTimestamp()
        + ", currentAttemptScheduledTimestamp="
        + getCurrentAttemptScheduledTimestamp()
        + ", scheduleToCloseTimeout="
        + getScheduleToCloseTimeout()
        + ", startToCloseTimeout="
        + getStartToCloseTimeout()
        + ", heartbeatTimeout="
        + getHeartbeatTimeout()
        + ", heartbeatDetails="
        + getHeartbeatDetails()
        + ", workflowType="
        + getWorkflowType()
        + ", namespace="
        + getNamespace()
        + ", attempt="
        + getAttempt()
        + ", isLocal="
        + isLocal()
        + "taskToken="
        + Base64.getEncoder().encodeToString(getTaskToken())
        + '}';
  }
}
