package io.temporal.internal.nexus;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import io.nexusrpc.Operation;
import io.nexusrpc.OperationNotFoundException;
import io.nexusrpc.OperationUnsuccessfulException;
import io.nexusrpc.ServiceDefinition;
import io.nexusrpc.handler.*;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.*;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.worker.NexusTask;
import io.temporal.internal.worker.NexusTaskHandler;
import io.temporal.worker.TypeAlreadyRegisteredException;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class NexusTaskHandlerImpl implements NexusTaskHandler {
  private final DataConverter dataConverter;
  private final String namespace;
  private final String taskQueue;

  private final ServiceHandler.Builder
          serviceHandlerBuilder = ServiceHandler.newBuilder();

  private ServiceHandler serviceHandler;

  public NexusTaskHandlerImpl(String namespace, String taskQueue, DataConverter dataConverter) {
    this.namespace = namespace;
    this.taskQueue = taskQueue;
    this.dataConverter = dataConverter;
    serviceHandlerBuilder.setSerializer(new PayloadSerializer(dataConverter));
  }

  @Override
  public boolean isAnyOperationSupported() {
    // TODO: This is a temporary implementation. Maybe need to seperate the concept of the handler out of the
    serviceHandler = serviceHandlerBuilder.build();
    return !serviceHandler.getInstances().isEmpty();
  }

  @Override
  public Result handle(NexusTask task, Scope metricsScope) {
    Request request = task.getResponse().getRequest();
    switch (request.getVariantCase()) {
      case START_OPERATION:
        StartOperationResponse startResponse = handleStartOperation(request.getStartOperation());
        return new Result(Response.newBuilder().setStartOperation(startResponse).build());
      case CANCEL_OPERATION:
        CancelOperationResponse cancelResponse = handleCancelledOperation(request.getCancelOperation());
        return new Result(Response.newBuilder().setCancelOperation(cancelResponse).build());
      default:
        throw new IllegalArgumentException("Unknown request type: " + request.getVariantCase());
    }
  }

  private CancelOperationResponse handleCancelledOperation(
      CancelOperationRequest task) {
    OperationContext context = OperationContext.newBuilder()
            .setService(task.getService())
            .setOperation(task.getOperation()).build();

    OperationCancelDetails operationCancelDetails = OperationCancelDetails.newBuilder()
            .setOperationId(task.getOperationId()).build();

    try {
      serviceHandler.cancelOperation(context, operationCancelDetails);
    } catch (UnrecognizedOperationException e) {
      throw new RuntimeException(e);
    } catch (OperationNotFoundException e) {
      throw new RuntimeException(e);
    }
    return CancelOperationResponse.newBuilder().build();
  }

  private StartOperationResponse handleStartOperation(StartOperationRequest task) {
    // TODO(quinn) set cancellation and headers
    OperationContext.Builder context = OperationContext.newBuilder()
            .setService(task.getService())
            .setOperation(task.getOperation());

    OperationStartDetails.Builder operationStartDetails =  OperationStartDetails.newBuilder()
            .setCallbackUrl(task.getCallback())
            .setRequestId(task.getRequestId());
    task.getCallbackHeaderMap().forEach(operationStartDetails::putCallbackHeader);

    HandlerInputContent.Builder input = HandlerInputContent.newBuilder()
            .setDataStream(task.getPayload().getData().newInput());
    task.getPayload().getMetadataMap().forEach((k, v) -> {
      input.putHeader(k, v.toString());
    });

    StartOperationResponse.Builder startResponseBuilder = StartOperationResponse.newBuilder();
    try {
      OperationStartResult<HandlerResultContent> result = serviceHandler.startOperation(context.build(), operationStartDetails.build(), input.build());
      if (result.isSync()) {
        StartOperationResponse.Sync.Builder sync = StartOperationResponse.Sync.newBuilder();
        dataConverter.toPayload(result.getSyncResult()).ifPresent(sync::setPayload);
        startResponseBuilder.setSyncSuccess(sync.build());
      } else {
        startResponseBuilder.setAsyncSuccess(StartOperationResponse.Async.newBuilder().setOperationId(result.getAsyncOperationId()).build());
      }
    } catch (UnrecognizedOperationException e) {
      // TODO: Handle this
      throw new RuntimeException(e);
    } catch (OperationUnsuccessfulException e) {
      startResponseBuilder.setOperationError(
              UnsuccessfulOperationError.newBuilder()
                      .setOperationState(e.getState().toString())
                      .setFailure(Failure.newBuilder()
                              .setMessage(e.getFailureInfo().getMessage())
                              .setDetails(ByteString.copyFromUtf8(e.getFailureInfo().getDetailsJson()))
                              .putAllMetadata(e.getFailureInfo().getMetadata()).build())
                      .build());
    }
    return startResponseBuilder.build();
  }

  public void registerNexusServiceImplementations(Object[] nexusServiceImplementation) {
    for (Object nexusService : nexusServiceImplementation) {
      registerNexusService(nexusService);
    }
  }

  private void registerNexusService(Object nexusService) {
    if (nexusService instanceof Class) {
      throw new IllegalArgumentException("Nexus service object instance expected, not the class");
    }
    ServiceImplInstance instance = ServiceImplInstance.fromInstance(nexusService);
    //TODO(quinn) this should handle duplicates by throwing an exception and not silently ignoring
    //TODO(quinn) this should be thread safe
    serviceHandlerBuilder.addInstance(instance);
  }
}
