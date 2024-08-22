package io.temporal.workflow;

import io.temporal.common.Experimental;
import java.time.Duration;
import java.util.Objects;

@Experimental
public final class NexusOperationOptions {
  public static NexusOperationOptions.Builder newBuilder() {
    return new NexusOperationOptions.Builder();
  }

  public static NexusOperationOptions.Builder newBuilder(NexusOperationOptions options) {
    return new NexusOperationOptions.Builder(options);
  }

  public static NexusOperationOptions getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  private static final NexusOperationOptions DEFAULT_INSTANCE;

  static {
    DEFAULT_INSTANCE = NexusOperationOptions.newBuilder().build();
  }

  public static final class Builder {
    private Duration scheduleToCloseTimeout;

    public NexusOperationOptions.Builder setScheduleToCloseTimeout(
        Duration scheduleToCloseTimeout) {
      this.scheduleToCloseTimeout = scheduleToCloseTimeout;
      return this;
    }

    private Builder() {}

    private Builder(NexusOperationOptions options) {
      if (options == null) {
        return;
      }
      this.scheduleToCloseTimeout = options.getScheduleToCloseTimeout();
    }

    public NexusOperationOptions build() {
      return new NexusOperationOptions(scheduleToCloseTimeout);
    }
  }

  private NexusOperationOptions(Duration startToCloseTimeout) {
    this.scheduleToCloseTimeout = startToCloseTimeout;
  }

  private Duration scheduleToCloseTimeout;

  public Duration getScheduleToCloseTimeout() {
    return scheduleToCloseTimeout;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    NexusOperationOptions that = (NexusOperationOptions) o;
    return Objects.equals(scheduleToCloseTimeout, that.scheduleToCloseTimeout);
  }

  @Override
  public int hashCode() {
    return Objects.hash(scheduleToCloseTimeout);
  }

  @Override
  public String toString() {
    return "NexusOperationOptions{" + "scheduleToCloseTimeout=" + scheduleToCloseTimeout + '}';
  }
}
