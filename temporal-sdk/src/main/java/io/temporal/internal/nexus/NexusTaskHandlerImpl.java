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

package io.temporal.internal.nexus;

import com.google.protobuf.ByteString;
import com.uber.m3.tally.Scope;
import io.nexusrpc.OperationUnsuccessfulException;
import io.nexusrpc.handler.*;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.*;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.worker.NexusTask;
import io.temporal.internal.worker.NexusTaskHandler;
import io.temporal.worker.TypeAlreadyRegisteredException;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

public class NexusTaskHandlerImpl implements NexusTaskHandler {
  private final DataConverter dataConverter;
  private final String namespace;
  private final String taskQueue;
  private final WorkflowClient client;
  private ServiceHandler serviceHandler;
  private final Map<String, ServiceImplInstance> serviceImplInstances =
      Collections.synchronizedMap(new HashMap<>());

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

  public NexusTaskHandlerImpl(
      WorkflowClient client, String namespace, String taskQueue, DataConverter dataConverter) {
    this.client = client;
    this.namespace = namespace;
    this.taskQueue = taskQueue;
    this.dataConverter = dataConverter;
  }

  @Override
  public boolean start() {
    if (serviceImplInstances.isEmpty()) {
      return false;
    }
    ServiceHandler.Builder serviceHandlerBuilder =
        ServiceHandler.newBuilder().setSerializer(new PayloadSerializer(dataConverter));
    serviceImplInstances.forEach((name, instance) -> serviceHandlerBuilder.addInstance(instance));
    serviceHandler = serviceHandlerBuilder.build();
    return true;
  }

  @Override
  public Result handle(NexusTask task, Scope metricsScope) {
    Request request = task.getResponse().getRequest();
    Map<String, String> headers = task.getResponse().getRequest().getHeaderMap();
    if (headers != null) {
      headers = Collections.emptyMap();
    }

    OperationContext.Builder ctx = OperationContext.newBuilder();
    headers.forEach(ctx::putHeader);
    OperationMethodCanceller canceller = new OperationMethodCanceller();
    ctx.setMethodCanceller(canceller);

    ScheduledFuture<?> timeoutTask = null;
    try {
      String timeoutString = headers.get("Request-Timeout");
      if (timeoutString != null) {
        try {
          Duration timeout = Duration.parse(timeoutString);
          timeoutTask =
              scheduler.schedule(
                  () -> canceller.cancel("timeout"),
                  timeout.toMillis(),
                  java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (DateTimeParseException e) {
          return new Result(
              HandlerError.newBuilder()
                  .setErrorType("BAD_REQUEST")
                  .setFailure(
                      Failure.newBuilder().setMessage("cannot parse request timeout").build())
                  .build());
        }
      }

      CurrentNexusOperationContext.set(
          new NexusOperationContextImpl(taskQueue, client, metricsScope));

      switch (request.getVariantCase()) {
        case START_OPERATION:
          StartOperationResponse startResponse =
              handleStartOperation(ctx, request.getStartOperation());
          return new Result(Response.newBuilder().setStartOperation(startResponse).build());
        case CANCEL_OPERATION:
          CancelOperationResponse cancelResponse =
              handleCancelledOperation(ctx, request.getCancelOperation());
          return new Result(Response.newBuilder().setCancelOperation(cancelResponse).build());
        default:
          return new Result(
              HandlerError.newBuilder()
                  .setErrorType("NOT_IMPLEMENTED")
                  .setFailure(Failure.newBuilder().setMessage("unknown request type").build())
                  .build());
      }
    } catch (OperationHandlerException e) {
      return new Result(
          HandlerError.newBuilder()
              .setErrorType(e.getErrorType().toString())
              .setFailure(
                  Failure.newBuilder()
                      .setMessage(e.getFailureInfo().getMessage())
                      .setDetails(ByteString.copyFromUtf8(e.getFailureInfo().getDetailsJson()))
                      .putAllMetadata(e.getFailureInfo().getMetadata())
                      .build())
              .build());
    } catch (Exception e) {
      return new Result(
          HandlerError.newBuilder()
              .setErrorType("INTERNAL")
              .setFailure(
                  Failure.newBuilder()
                      .setMessage("internal error")
                      .setDetails(ByteString.copyFromUtf8(e.toString()))
                      .build())
              .build());
    } finally {
      canceller.cancel("");
      if (timeoutTask != null) {
        timeoutTask.cancel(false);
      }
      CurrentNexusOperationContext.unset();
    }
  }

  private CancelOperationResponse handleCancelledOperation(
      OperationContext.Builder ctx, CancelOperationRequest task) {
    ctx.setService(task.getService()).setOperation(task.getOperation());

    OperationCancelDetails operationCancelDetails =
        OperationCancelDetails.newBuilder().setOperationId(task.getOperationId()).build();

    serviceHandler.cancelOperation(ctx.build(), operationCancelDetails);

    return CancelOperationResponse.newBuilder().build();
  }

  private StartOperationResponse handleStartOperation(
      OperationContext.Builder ctx, StartOperationRequest task) {
    ctx.setService(task.getService()).setOperation(task.getOperation());

    OperationStartDetails.Builder operationStartDetails =
        OperationStartDetails.newBuilder()
            .setCallbackUrl(task.getCallback())
            .setRequestId(task.getRequestId());
    task.getCallbackHeaderMap().forEach(operationStartDetails::putCallbackHeader);

    HandlerInputContent.Builder input =
        HandlerInputContent.newBuilder().setDataStream(task.getPayload().getData().newInput());
    task.getPayload().getMetadataMap().forEach((k, v) -> input.putHeader(k, v.toStringUtf8()));

    StartOperationResponse.Builder startResponseBuilder = StartOperationResponse.newBuilder();
    try {
      OperationStartResult<HandlerResultContent> result =
          serviceHandler.startOperation(ctx.build(), operationStartDetails.build(), input.build());
      if (result.isSync()) {
        StartOperationResponse.Sync.Builder sync = StartOperationResponse.Sync.newBuilder();
        Payload.Builder payload =
            Payload.newBuilder()
                .setData(ByteString.copyFrom(result.getSyncResult().getDataBytes()));
        result
            .getSyncResult()
            .getHeaders()
            .forEach((k, v) -> payload.putMetadata(k, ByteString.copyFromUtf8(v)));
        sync.setPayload(payload.build());
        startResponseBuilder.setSyncSuccess(sync.build());
      } else {
        startResponseBuilder.setAsyncSuccess(
            StartOperationResponse.Async.newBuilder()
                .setOperationId(result.getAsyncOperationId())
                .build());
      }
    } catch (OperationUnsuccessfulException e) {
      // TODO: Copy failure details?
      startResponseBuilder.setOperationError(
          UnsuccessfulOperationError.newBuilder()
              .setOperationState(e.getState().toString().toLowerCase())
              .setFailure(
                  Failure.newBuilder()
                      .setMessage(e.getFailureInfo().getMessage())
                      .putAllMetadata(e.getFailureInfo().getMetadata())
                      .build())
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
    if (serviceImplInstances.put(instance.getDefinition().getName(), instance) != null) {
      throw new TypeAlreadyRegisteredException(
          instance.getDefinition().getName(),
          "\""
              + instance.getDefinition().getName()
              + "\" service type is already registered with the worker");
    }
  }
}
