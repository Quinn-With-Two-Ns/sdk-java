package io.temporal.client;

import com.google.common.base.Preconditions;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflow.v1.WorkflowExecutionInfo;
import io.temporal.common.SearchAttributes;
import io.temporal.common.converter.DataConverter;
import io.temporal.internal.common.ProtobufTimeUtils;
import io.temporal.internal.common.SearchAttributesUtil;
import io.temporal.payload.context.WorkflowSerializationContext;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** WorkflowExecutionMetadata contains information about a workflow execution. */
public class WorkflowExecutionMetadata {
  private final @Nonnull WorkflowExecutionInfo info;
  private final @Nonnull DataConverter dataConverter;

  public WorkflowExecutionMetadata(
      @Nonnull WorkflowExecutionInfo info, @Nonnull DataConverter dataConverter) {
    this.info = Preconditions.checkNotNull(info, "info");
    this.dataConverter = Preconditions.checkNotNull(dataConverter, "dataConverter");
  }

  @Nonnull
  public WorkflowExecution getExecution() {
    return info.getExecution();
  }

  @Nonnull
  public String getWorkflowType() {
    return info.getType().getName();
  }

  @Nonnull
  public String getTaskQueue() {
    return info.getTaskQueue();
  }

  @Nonnull
  public Instant getStartTime() {
    return ProtobufTimeUtils.toJavaInstant(info.getStartTime());
  }

  @Nonnull
  public Instant getExecutionTime() {
    return ProtobufTimeUtils.toJavaInstant(info.getExecutionTime());
  }

  @Nullable
  public Instant getCloseTime() {
    return info.hasCloseTime() ? ProtobufTimeUtils.toJavaInstant(info.getCloseTime()) : null;
  }

  @Nonnull
  public WorkflowExecutionStatus getStatus() {
    return info.getStatus();
  }

  public long getHistoryLength() {
    return info.getHistoryLength();
  }

  @Nullable
  public String getParentNamespace() {
    return info.hasParentExecution() ? info.getParentNamespaceId() : null;
  }

  @Nullable
  public WorkflowExecution getParentExecution() {
    return info.hasParentExecution() ? info.getParentExecution() : null;
  }

  @Nullable
  public WorkflowExecution getRootExecution() {
    return info.hasRootExecution() ? info.getRootExecution() : null;
  }

  @Nullable
  public String getFirstRunId() {
    return info.getFirstRunId();
  }

  @Nullable
  public Duration getExecutionDuration() {
    return info.hasExecutionDuration()
        ? ProtobufTimeUtils.toJavaDuration(info.getExecutionDuration())
        : null;
  }

  /**
   * @deprecated use {@link #getTypedSearchAttributes} instead.
   */
  @Deprecated
  @Nonnull
  public Map<String, List<?>> getSearchAttributes() {
    return Collections.unmodifiableMap(SearchAttributesUtil.decode(info.getSearchAttributes()));
  }

  /** Get search attributes as a typed set. */
  @Nonnull
  public SearchAttributes getTypedSearchAttributes() {
    return SearchAttributesUtil.decodeTyped(info.getSearchAttributes());
  }

  @Nullable
  public <T> Object getMemo(String key, Class<T> valueClass) {
    return getMemo(key, valueClass, valueClass);
  }

  @Nullable
  public <T> T getMemo(String key, Class<T> valueClass, Type genericType) {
    Payload memo = info.getMemo().getFieldsMap().get(key);
    if (memo == null) {
      return null;
    }
    return dataConverter
        .withContext(
            new WorkflowSerializationContext(
                info.getParentNamespaceId(), info.getExecution().getWorkflowId()))
        .fromPayload(memo, valueClass, genericType);
  }

  @Nonnull
  public WorkflowExecutionInfo getWorkflowExecutionInfo() {
    return info;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    WorkflowExecutionMetadata that = (WorkflowExecutionMetadata) o;
    return info.equals(that.info);
  }

  @Override
  public int hashCode() {
    return Objects.hash(info);
  }
}
