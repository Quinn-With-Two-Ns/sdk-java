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

package io.temporal.internal.sync;

import com.google.common.base.Defaults;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.failure.TemporalFailure;
import io.temporal.workflow.*;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Optional;

class NexusOperationStubImpl implements NexusOperationStub {
  private final NexusClient client;
  private final String service;
  private final String operation;
  private final NexusOperationOptions options;
  private final WorkflowOutboundCallsInterceptor outboundCallsInterceptor;
  private final CompletablePromise<Optional<String>> execution;

  private final Functions.Proc1<String> assertReadOnly;

  NexusOperationStubImpl(
      NexusClient client,
      String service,
      String operation,
      NexusOperationOptions options,
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    this.client = client;
    this.service = service;
    this.operation = operation;
    this.options = options;
    this.outboundCallsInterceptor = outboundCallsInterceptor;
    this.execution = Workflow.newPromise();
    this.execution.handle((ex, failure) -> null);
    this.assertReadOnly = assertReadOnly;
  }

  @Override
  public Promise<Optional<String>> getExecution() {
    // We create a new Promise here because we want it to be registered with the Runner
    CompletablePromise<Optional<String>> result = Workflow.newPromise();
    result.completeFrom(this.execution);
    return result;
  }

  @Override
  public NexusOperationOptions getOptions() {
    return options;
  }

  @Override
  public <R> R execute(Class<R> resultClass, Object arg) {
    return execute(resultClass, resultClass, arg);
  }

  @Override
  public <R> R execute(Class<R> resultClass, Type resultType, Object arg) {
    assertReadOnly.apply("schedule nexus operation");
    Promise<R> result = executeAsync(resultClass, resultType, arg);
    if (AsyncInternal.isAsync()) {
      AsyncInternal.setAsyncResult(result);
      return Defaults.defaultValue(resultClass);
    }
    try {
      return result.get();
    } catch (TemporalFailure e) {
      // Reset stack to the current one. Otherwise it is very confusing to see a stack of
      // an event handling method.
      e.setStackTrace(Thread.currentThread().getStackTrace());
      throw e;
    }
  }

  @Override
  public <R> Promise<R> executeAsync(Class<R> resultClass, Object arg) {
    return executeAsync(resultClass, resultClass, arg);
  }

  @Override
  public <R> Promise<R> executeAsync(Class<R> resultClass, Type resultType, Object arg) {
    assertReadOnly.apply("schedule nexus operation");
    WorkflowOutboundCallsInterceptor.ExecuteNexusOperationOutput<R> result =
        outboundCallsInterceptor.executeNexusOperation(
            new WorkflowOutboundCallsInterceptor.ExecuteNexusOperationInput<>(
                client.getEndpoint(),
                service,
                operation,
                resultClass,
                resultType,
                arg,
                options,
                Collections.emptyMap()));
    execution.completeFrom(result.getOperationExecution());
    return result.getResult();
  }
}
