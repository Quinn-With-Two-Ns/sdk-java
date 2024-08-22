package io.temporal.internal.nexus;

import com.google.protobuf.ByteString;
import io.nexusrpc.Serializer;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;
import java.lang.reflect.Type;
import java.util.Optional;
import javax.annotation.Nullable;

public class PayloadSerializer implements Serializer {
  DataConverter dataConverter;

  public PayloadSerializer(DataConverter dataConverter) {
    this.dataConverter = dataConverter;
  }

  @Override
  public Content serialize(@Nullable Object o) {
    Optional<Payload> payload = dataConverter.toPayload(o);
    Content.Builder content = Content.newBuilder();
    if (payload.isPresent()) {
      content.setData(payload.get().getData().toByteArray());
      payload.get().getMetadataMap().forEach((k, v) -> content.putHeader(k, v.toString()));
    }
    return content.build();
  }

  @Override
  public @Nullable Object deserialize(Content content, Type type) {
    Payload.Builder payload = Payload.newBuilder();
    payload.setData(ByteString.copyFrom(content.getData()));
    content.getHeaders().forEach((k, v) -> payload.putMetadata(k, ByteString.copyFromUtf8(v)));
    return dataConverter.fromPayload(payload.build(), type.getClass(), type);
  }
}
