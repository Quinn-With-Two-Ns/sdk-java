package io.temporal.internal.worker;

import com.uber.m3.tally.Scope;
import io.temporal.api.nexus.v1.HandlerError;
import io.temporal.api.nexus.v1.Response;
import javax.annotation.Nullable;

public interface NexusTaskHandler {

  /** True if this handler handles at least one nexus service. */
  boolean isAnyOperationSupported();

  NexusTaskHandler.Result handle(NexusTask task, Scope metricsScope);

  class Result {
    @Nullable private final Response response;
    @Nullable private final HandlerError handlerError;

    public Result(Response response) {
      this.response = response;
      handlerError = null;
    }

    public Result(HandlerError handlerError) {
      this.handlerError = handlerError;
      response = null;
    }

    @Nullable
    public Response getResponse() {
      return null;
    }

    @Nullable
    public HandlerError getHandlerError() {
      return null;
    }
  }
}
