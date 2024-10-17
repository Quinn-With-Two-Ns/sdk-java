package io.temporal.internal.nexus;

import io.nexusrpc.handler.OperationInterceptor;

public class NexusInterceptorConverter implements OperationInterceptor {
    private final io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor interceptor;

    public NexusInterceptorConverter(
        io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public io.nexusrpc.handler.OperationStartResult<Object> start(
        io.nexusrpc.handler.OperationContext context,
        io.nexusrpc.handler.OperationStartDetails details,
        Object param) {
        io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor.StartOperationInput input =
            new io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor.StartOperationInput(
                context, details, (io.nexusrpc.handler.HandlerInputContent) param);
        io.temporal.common.interceptors.NexusOperationInboundCallsInterceptor.StartOperationOutput output =
            interceptor.startOperation(input);
        return output.getResult();
    }
}
