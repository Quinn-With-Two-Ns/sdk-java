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

import io.nexusrpc.OperationException;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationStartResult;
import io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor;
import io.temporal.common.interceptors.NexusOperationOutboundCallsInterceptor;

public class RootNexusOperationInboundCallsInterceptor
    implements NexusOperationInboundCallsInterceptor {
  private final OperationHandler<Object, Object> operationInterceptor;

  RootNexusOperationInboundCallsInterceptor(OperationHandler<Object, Object> operationInterceptor) {
    this.operationInterceptor = operationInterceptor;
  }

  @Override
  public void init(NexusOperationOutboundCallsInterceptor outboundCalls) {
    CurrentNexusOperationContext.get().setOutboundInterceptor(outboundCalls);
  }

  @Override
  public StartOperationOutput startOperation(StartOperationInput input) throws OperationException {
    OperationStartResult result =
        operationInterceptor.start(
            input.getOperationContext(), input.getStartDetails(), input.getInput());
    return new StartOperationOutput(result);
  }

  @Override
  public CancelOperationOutput cancelOperation(CancelOperationInput input) {
    operationInterceptor.cancel(input.getOperationContext(), input.getCancelDetails());
    return new CancelOperationOutput();
  }
}
