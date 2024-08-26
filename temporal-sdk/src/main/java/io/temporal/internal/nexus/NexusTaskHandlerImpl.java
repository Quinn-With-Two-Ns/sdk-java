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
import com.google.protobuf.InvalidProtocolBufferException;
import com.uber.m3.tally.Scope;
import io.nexusrpc.OperationNotFoundException;
import io.nexusrpc.OperationUnsuccessfulException;
import io.nexusrpc.handler.*;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.*;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.worker.NexusTask;
import io.temporal.internal.worker.NexusTaskHandler;
import io.temporal.worker.TypeAlreadyRegisteredException;
import java.util.*;

public class NexusTaskHandlerImpl implements NexusTaskHandler {
  private final DataConverter dataConverter;
  private final String namespace;
  private final String taskQueue;

  private final WorkflowClient client;

  private ServiceHandler serviceHandler;

  private final Map<String, ServiceImplInstance> serviceImplInstances =
      Collections.synchronizedMap(new HashMap<>());

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
    serviceImplInstances.forEach(
        (name, instance) -> {
          serviceHandlerBuilder.addInstance(instance);
        });
    serviceHandler = serviceHandlerBuilder.build();
    return true;
  }

  @Override
  public Result handle(NexusTask task, Scope metricsScope) {
    Request request = task.getResponse().getRequest();
    try {
      NexusContext.set(new NexusContext(client, taskQueue));
      switch (request.getVariantCase()) {
        case START_OPERATION:
          StartOperationResponse startResponse = handleStartOperation(request.getStartOperation());
          return new Result(Response.newBuilder().setStartOperation(startResponse).build());
        case CANCEL_OPERATION:
          CancelOperationResponse cancelResponse =
              handleCancelledOperation(request.getCancelOperation());
          return new Result(Response.newBuilder().setCancelOperation(cancelResponse).build());
        default:
          return new Result(
              HandlerError.newBuilder()
                  .setErrorType("NOT_IMPLEMENTED")
                  .setFailure(Failure.newBuilder().setMessage("unknown request type").build())
                  .build());
      }
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
      NexusContext.remove();
    }
  }

  private CancelOperationResponse handleCancelledOperation(CancelOperationRequest task) {
    OperationContext context =
        OperationContext.newBuilder()
            .setService(task.getService())
            .setOperation(task.getOperation())
            .build();

    OperationCancelDetails operationCancelDetails =
        OperationCancelDetails.newBuilder().setOperationId(task.getOperationId()).build();

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
    OperationContext.Builder context =
        OperationContext.newBuilder()
            .setService(task.getService())
            .setOperation(task.getOperation());

    OperationStartDetails.Builder operationStartDetails =
        OperationStartDetails.newBuilder()
            .setCallbackUrl(task.getCallback())
            .setRequestId(task.getRequestId());
    task.getCallbackHeaderMap().forEach(operationStartDetails::putCallbackHeader);

    HandlerInputContent.Builder input =
        HandlerInputContent.newBuilder().setDataStream(task.getPayload().toByteString().newInput());

    StartOperationResponse.Builder startResponseBuilder = StartOperationResponse.newBuilder();
    try {
      OperationStartResult<HandlerResultContent> result =
          serviceHandler.startOperation(
              context.build(), operationStartDetails.build(), input.build());
      if (result.isSync()) {
        StartOperationResponse.Sync.Builder sync = StartOperationResponse.Sync.newBuilder();
        sync.setPayload(Payload.parseFrom(result.getSyncResult().getDataBytes()));
        startResponseBuilder.setSyncSuccess(sync.build());
      } else {
        startResponseBuilder.setAsyncSuccess(
            StartOperationResponse.Async.newBuilder()
                .setOperationId(result.getAsyncOperationId())
                .build());
      }
    } catch (UnrecognizedOperationException e) {
      // TODO: Handle this
      throw new RuntimeException(e);
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
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
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
