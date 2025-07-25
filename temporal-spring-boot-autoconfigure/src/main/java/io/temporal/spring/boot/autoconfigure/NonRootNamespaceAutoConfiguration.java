package io.temporal.spring.boot.autoconfigure;

import com.google.common.base.MoreObjects;
import io.temporal.spring.boot.autoconfigure.properties.NamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.NonRootNamespaceProperties;
import io.temporal.spring.boot.autoconfigure.properties.TemporalProperties;
import io.temporal.spring.boot.autoconfigure.template.WorkersTemplate;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;

@Configuration
@EnableConfigurationProperties(TemporalProperties.class)
@AutoConfigureAfter({RootNamespaceAutoConfiguration.class, ServiceStubsAutoConfiguration.class})
@ConditionalOnBean(ServiceStubsAutoConfiguration.class)
@Conditional(NamespacesPresentCondition.class)
@ConditionalOnExpression(
    "${spring.temporal.test-server.enabled:false} || '${spring.temporal.connection.target:}'.length() > 0")
public class NonRootNamespaceAutoConfiguration {

  protected static final Logger log =
      LoggerFactory.getLogger(NonRootNamespaceAutoConfiguration.class);

  @Bean
  public static NonRootBeanPostProcessor nonRootBeanPostProcessor(
      @Lazy TemporalProperties properties) {
    return new NonRootBeanPostProcessor(properties);
  }

  @Bean
  public static NonRootNamespaceEventListener nonRootNamespaceEventListener(
      @Lazy TemporalProperties temporalProperties,
      @Nullable @Lazy List<WorkersTemplate> workersTemplates) {
    return new NonRootNamespaceEventListener(temporalProperties, workersTemplates);
  }

  public static class NonRootNamespaceEventListener
      implements ApplicationListener<ApplicationContextEvent>, ApplicationContextAware {

    private final TemporalProperties temporalProperties;
    private final List<WorkersTemplate> workersTemplates;
    private ApplicationContext applicationContext;

    public NonRootNamespaceEventListener(
        TemporalProperties temporalProperties, List<WorkersTemplate> workersTemplates) {
      this.temporalProperties = temporalProperties;
      this.workersTemplates = workersTemplates;
    }

    @Override
    public void onApplicationEvent(ApplicationContextEvent event) {
      if (event.getApplicationContext() == this.applicationContext) {
        if (event instanceof ContextRefreshedEvent) {
          onStart();
        }
      } else if (event instanceof ContextClosedEvent) {
        onStop();
      }
    }

    private void onStart() {
      this.executeByNamespace(
          (nonRootNamespaceProperties, workersTemplate) -> {
            String namespace = nonRootNamespaceProperties.getNamespace();
            Boolean startWorkers =
                Optional.of(nonRootNamespaceProperties)
                    .map(NonRootNamespaceProperties::getStartWorkers)
                    .orElse(temporalProperties.getStartWorkers());
            startWorkers = MoreObjects.firstNonNull(startWorkers, Boolean.TRUE);
            if (!startWorkers) {
              log.info("skip start workers for non-root namespace [{}]", namespace);
              return;
            }

            workersTemplate
                .getWorkers()
                .forEach(
                    worker ->
                        log.debug(
                            "register worker :[{}] in worker queue [{}]",
                            worker.getTaskQueue(),
                            namespace));
            workersTemplate.getWorkerFactory().start();
            log.info("started workers for non-root namespace [{}]", namespace);
          });
    }

    private void onStop() {
      this.executeByNamespace(
          (nonRootNamespaceProperties, workersTemplate) -> {
            log.info("shutdown workers for non-root namespace");
            workersTemplate.getWorkerFactory().shutdown();
          });
    }

    private void executeByNamespace(
        BiConsumer<NonRootNamespaceProperties, WorkersTemplate> consumer) {
      if (temporalProperties.getNamespaces() == null) {
        return;
      }
      for (WorkersTemplate workersTemplate : workersTemplates) {
        NamespaceProperties namespaceProperties = workersTemplate.getNamespaceProperties();
        if (namespaceProperties instanceof NonRootNamespaceProperties) {
          NonRootNamespaceProperties nonRootNamespaceProperties =
              (NonRootNamespaceProperties) namespaceProperties;
          consumer.accept(nonRootNamespaceProperties, workersTemplate);
        }
      }
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
      this.applicationContext = applicationContext;
    }
  }
}
