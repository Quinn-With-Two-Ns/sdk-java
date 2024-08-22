package io.temporal.failure;

import io.temporal.common.Experimental;

/** <b>This exception is expected to be thrown only by the Temporal framework code.</b> */
@Experimental
public final class NexusOperationFailure extends TemporalFailure {
  private final long scheduledEventId;
  private final String endpoint;
  private final String service;
  // Operation name.
  private final String operation;
  private final String operationId;

  public NexusOperationFailure(
      long scheduledEventId,
      String endpoint,
      String service,
      String operation,
      String operationId,
      Throwable cause) {
    super(getMessage(scheduledEventId, endpoint, service, operation, operationId), null, cause);
    this.scheduledEventId = scheduledEventId;
    this.endpoint = endpoint;
    this.service = service;
    this.operation = operation;
    this.operationId = operationId;
  }

  public static String getMessage(
      long scheduledEventId,
      String endpoint,
      String service,
      String operation,
      String operationId) {
    return "Nexus Operation with operation='"
        + operation
        + "'. "
        + "scheduledEventId="
        + scheduledEventId;
  }

  public long getScheduledEventId() {
    return scheduledEventId;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public String getService() {
    return service;
  }

  public String getOperation() {
    return operation;
  }

  public String getOperationId() {
    return operationId;
  }
}
