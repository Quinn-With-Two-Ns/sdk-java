/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package com.uber.cadence.internal.dispatcher;

import com.uber.cadence.WorkflowExecution;
import com.uber.cadence.WorkflowIdReusePolicy;
import com.uber.cadence.WorkflowType;
import com.uber.cadence.client.WorkflowExecutionAlreadyStartedException;
import com.uber.cadence.client.WorkflowFailureException;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.converter.DataConverter;
import com.uber.cadence.internal.common.InternalUtils;
import com.uber.cadence.internal.common.WorkflowExecutionUtils;
import com.uber.cadence.internal.generic.GenericWorkflowClientExternal;
import com.uber.cadence.internal.generic.QueryWorkflowParameters;
import com.uber.cadence.internal.generic.StartWorkflowExecutionParameters;
import com.uber.cadence.internal.worker.CheckedExceptionWrapper;
import com.uber.cadence.internal.worker.GenericWorkflowClientExternalImpl;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.SignalExternalWorkflowParameters;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.WorkflowMethod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class WorkflowExternalInvocationHandler implements InvocationHandler {

    private static final ThreadLocal<AtomicReference<WorkflowExecution>> asyncResult = new ThreadLocal<>();
    private final GenericWorkflowClientExternal genericClient;
    private final WorkflowOptions options;
    private final DataConverter dataConverter;
    private final AtomicReference<WorkflowExecution> execution = new AtomicReference<>();
    private String workflowType;

    public static void initAsyncInvocation() {
        if (asyncResult.get() != null) {
            throw new IllegalStateException("already in asyncStart invocation");
        }
        asyncResult.set(new AtomicReference<>());
    }

    public static WorkflowExecution getAsyncInvocationResult() {
        try {
            AtomicReference<WorkflowExecution> reference = asyncResult.get();
            if (reference == null) {
                throw new IllegalStateException("initAsyncInvocation wasn't called");
            }
            WorkflowExecution result = reference.get();
            if (result == null) {
                throw new IllegalStateException("Only methods of a stub created through WorkflowClient.newWorkflowStub " +
                        "can be used as a parameter to the asyncStart.");
            }
            return result;
        } finally {
            asyncResult.remove();
        }
    }

    public WorkflowExternalInvocationHandler(GenericWorkflowClientExternalImpl genericClient, WorkflowExecution execution, DataConverter dataConverter) {
        if (execution == null || execution.getWorkflowId() == null || execution.getWorkflowId().isEmpty()) {
            throw new IllegalArgumentException("null or empty workflowId");
        }
        this.genericClient = genericClient;
        this.execution.set(execution);
        this.options = null;
        this.dataConverter = dataConverter;
    }

    public WorkflowExternalInvocationHandler(GenericWorkflowClientExternal genericClient, WorkflowOptions options, DataConverter dataConverter) {
        this.genericClient = genericClient;
        this.options = options;
        this.dataConverter = dataConverter;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
        WorkflowMethod workflowMethod = method.getAnnotation(WorkflowMethod.class);
        QueryMethod queryMethod = method.getAnnotation(QueryMethod.class);
        SignalMethod signalMethod = method.getAnnotation(SignalMethod.class);
        int count = (workflowMethod == null ? 0 : 1) + (queryMethod == null ? 0 : 1) + (signalMethod == null ? 0 : 1);
        if (count > 1) {
            throw new IllegalArgumentException(method + " must contain at most one annotation " +
                    "from @WorkflowMethod, @QueryMethod or @SignalMethod");
        }
        if (workflowMethod != null) {
            // We do allow duplicated calls if policy is not AllowDuplicate. Semantic is to wait for result.
            if (execution.get() != null) { // stub is reused
                if (options.getWorkflowIdReusePolicy() == WorkflowIdReusePolicy.AllowDuplicate) {
                    throw new WorkflowExecutionAlreadyStartedException(
                            "Cannot call @WorkflowMethod more than once per stub instance", execution.get(), workflowType, null);
                }
                return waitForWorkflowResult(method);
            }
            return startWorkflow(method, workflowMethod, args);
        }
        if (execution.get() == null) {
            throw new IllegalStateException("Workflow not started yet");
        }
        if (queryMethod != null) {
            return queryWorkflow(method, queryMethod, args);
        }
        if (signalMethod != null) {
            signalWorkflow(method, signalMethod, args);
            return null;
        }
        throw new IllegalArgumentException(method + " is not annotated with @WorkflowMethod or @QueryMethod");
    }

    private void signalWorkflow(Method method, SignalMethod signalMethod, Object[] args) {
        if (method.getReturnType() != Void.TYPE) {
            throw new IllegalArgumentException("Signal method must have void return type: " + method);
        }

        String signalName = signalMethod.name();
        if (signalName.isEmpty()) {
            signalName = InternalUtils.getSimpleName(method);
        }
        SignalExternalWorkflowParameters signalParameters = new SignalExternalWorkflowParameters();
        WorkflowExecution exe = execution.get();
        signalParameters.setRunId(exe.getRunId());
        signalParameters.setWorkflowId(exe.getWorkflowId());
        signalParameters.setSignalName(signalName);
        byte[] input = dataConverter.toData(args);
        signalParameters.setInput(input);
        genericClient.signalWorkflowExecution(signalParameters);
    }

    private Object queryWorkflow(Method method, QueryMethod queryMethod, Object[] args) {
        if (method.getReturnType() == Void.TYPE) {
            throw new IllegalArgumentException("Query method cannot have void return type: " + method);
        }
        String queryType = queryMethod.name();
        if (queryType.isEmpty()) {
            queryType = InternalUtils.getSimpleName(method);
        }
        QueryWorkflowParameters p = new QueryWorkflowParameters();
        p.setInput(dataConverter.toData(args));
        p.setQueryType(queryType);
        p.setRunId(execution.get().getRunId());
        p.setWorkflowId(execution.get().getWorkflowId());
        byte[] queryResult = genericClient.queryWorkflow(p);
        return dataConverter.fromData(queryResult, method.getReturnType());
    }

    private Object startWorkflow(Method method, WorkflowMethod workflowMethod, Object[] args) {
        String workflowName = workflowMethod.name();
        if (workflowName.isEmpty()) {
            workflowType = InternalUtils.getSimpleName(method);
        } else {
            workflowType = workflowName;
        }
        StartWorkflowExecutionParameters parameters = new StartWorkflowExecutionParameters();
        parameters.setExecutionStartToCloseTimeoutSeconds(options.getExecutionStartToCloseTimeoutSeconds());
        parameters.setTaskList(options.getTaskList());
        parameters.setTaskStartToCloseTimeoutSeconds(options.getTaskStartToCloseTimeoutSeconds());
        parameters.setWorkflowType(new WorkflowType().setName(workflowType));
        parameters.setWorkflowIdReusePolicy(options.getWorkflowIdReusePolicy());
        parameters.setChildPolicy(options.getChildPolicy());
        if (options.getWorkflowId() == null) {
            parameters.setWorkflowId(UUID.randomUUID().toString());
        } else {
            parameters.setWorkflowId(options.getWorkflowId());
        }
        byte[] input = dataConverter.toData(args);
        parameters.setInput(input);
        try {
            execution.set(genericClient.startWorkflow(parameters));
        } catch (WorkflowExecutionAlreadyStartedException e) {
            // We do allow duplicated calls if policy is not AllowDuplicate. Semantic is to wait for result.
            if (options.getWorkflowIdReusePolicy() == WorkflowIdReusePolicy.AllowDuplicate) {
                throw e;
            }
            return waitForWorkflowResult(method);
        }
        AtomicReference<WorkflowExecution> async = asyncResult.get();
        if (async != null) {
            async.set(execution.get());
            return null;
        }
        return waitForWorkflowResult(method);
    }

    private Object waitForWorkflowResult(Method method) {
        try {
            byte[] resultValue = WorkflowExecutionUtils.getWorkflowExecutionResult(
                    genericClient.getService(), genericClient.getDomain(), execution.get(), workflowType,
                    options.getExecutionStartToCloseTimeoutSeconds(), TimeUnit.SECONDS);
            return dataConverter.fromData(resultValue, method.getReturnType());
        } catch (WorkflowExecutionFailedException e) {
            Class<Throwable> causeClass = null;
            try {
                causeClass = (Class<Throwable>) Class.forName(e.getReason());
            } catch (Exception ee) {
                RuntimeException failure = new RuntimeException("Failed to deserialize workflow failure cause. " +
                        "Reason field is expected to contain a failure cause class name", ee);
                throw new WorkflowFailureException(execution.get(), workflowType, e.getDecisionTaskCompletedEventId(), failure);
            }
            Throwable cause = dataConverter.fromData(e.getDetails(), causeClass);
            throw new WorkflowFailureException(execution.get(), workflowType, e.getDecisionTaskCompletedEventId(), cause);
        } catch (Exception e) {
            throw CheckedExceptionWrapper.throwWrapped(e);
        }
    }
}
