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

import static io.temporal.internal.sync.WorkflowInternal.getWorkflowOutboundInterceptor;

import io.temporal.common.interceptors.WorkflowOutboundCallsInterceptor;
import io.temporal.workflow.Functions;
import io.temporal.workflow.NexusClient;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusOperationStub;
import java.lang.reflect.Proxy;

class NexusClientImpl implements NexusClient {
  private final String endpoint;
  private final WorkflowOutboundCallsInterceptor workflowOutboundInterceptor;
  private final Functions.Proc1<String> assertReadOnly;

  public NexusClientImpl(
      String endpoint,
      WorkflowOutboundCallsInterceptor workflowOutboundInterceptor,
      Functions.Proc1<String> assertReadOnly) {
    this.endpoint = endpoint;
    this.workflowOutboundInterceptor = workflowOutboundInterceptor;
    this.assertReadOnly = assertReadOnly;
  }

  @Override
  public String getEndpoint() {
    return endpoint;
  }

  @Override
  public <T> T newServiceStub(Class<T> serviceInterface) {
    return newServiceStub(serviceInterface, NexusOperationOptions.getDefaultInstance());
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T newServiceStub(Class<T> serviceInterface, NexusOperationOptions options) {
    return (T)
        Proxy.newProxyInstance(
            serviceInterface.getClassLoader(),
            new Class<?>[] {serviceInterface, StubMarker.class, AsyncInternal.AsyncMarker.class},
            new NexusServiceInvocationHandler(
                this,
                serviceInterface,
                options,
                getWorkflowOutboundInterceptor(),
                WorkflowInternal::assertNotReadOnly));
  }

  @Override
  public NexusOperationStub newUntypedNexusOperationStub(
      String service, String operation, NexusOperationOptions options) {
    return new NexusOperationStubImpl(
        this, service, operation, options, workflowOutboundInterceptor, assertReadOnly);
  }
}
