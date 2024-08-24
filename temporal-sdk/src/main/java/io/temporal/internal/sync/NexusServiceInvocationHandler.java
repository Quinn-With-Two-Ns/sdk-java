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

import io.nexusrpc.Operation;
import io.nexusrpc.ServiceDefinition;
import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.Functions;
import io.temporal.workflow.NexusClient;
import io.temporal.workflow.NexusOperationOptions;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public class NexusServiceInvocationHandler implements InvocationHandler {
  private final ServiceDefinition serviceDef;
  private final WorkflowOutboundCallsInterceptor outboundCallsInterceptor;
  private final Functions.Proc1<String> assertReadOnly;
  private final NexusClient client;
  private final NexusOperationOptions operationOptions;
  Class<?> serviceInterface;

  NexusServiceInvocationHandler(
      NexusClient client,
      Class<?> serviceInterface,
      NexusOperationOptions operationOptions,
      WorkflowOutboundCallsInterceptor outboundCallsInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    this.client = client;
    this.serviceInterface = serviceInterface;
    this.serviceDef = ServiceDefinition.fromClass(serviceInterface);
    this.operationOptions = operationOptions;
    this.outboundCallsInterceptor = outboundCallsInterceptor;
    this.assertReadOnly = assertReadOnly;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    Operation opAnnotation = method.getAnnotation(Operation.class);
    String opName = !opAnnotation.name().equals("") ? opAnnotation.name() : method.getName();
    return new NexusOperationStubImpl(
            client,
            this.serviceDef.getName(),
            opName,
            operationOptions,
            outboundCallsInterceptor,
            assertReadOnly)
        .execute(method.getReturnType(), method.getGenericReturnType(), args[0]);
  }
}
