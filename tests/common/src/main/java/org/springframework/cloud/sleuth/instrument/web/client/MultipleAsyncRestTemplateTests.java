/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.instrument.web.client;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.assertj.core.api.BDDAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.instrument.async.LazyTraceExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.AsyncClientHttpRequest;
import org.springframework.http.client.AsyncClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.AsyncConfigurerSupport;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.AsyncRestTemplate;

import static org.assertj.core.api.BDDAssertions.then;

/**
 * @author Marcin Grzejszczak
 */
@ContextConfiguration(classes = { MultipleAsyncRestTemplateTests.TestConfig.class,
		MultipleAsyncRestTemplateTests.CustomExecutorConfig.class,
		MultipleAsyncRestTemplateTests.ControllerConfig.class })
@DirtiesContext
public abstract class MultipleAsyncRestTemplateTests {

	private static final Log log = LogFactory.getLog(MultipleAsyncRestTemplateTests.class);

	@Autowired
	@Qualifier("customAsyncRestTemplate")
	AsyncRestTemplate asyncRestTemplate;

	@Autowired
	AsyncConfigurer executor;

	Executor wrappedExecutor;

	@Autowired
	Tracer tracer;

	@LocalServerPort
	int port;

	@BeforeEach
	public void setup() {
		this.wrappedExecutor = this.executor.getAsyncExecutor();
	}

	@Test
	public void should_start_context_with_custom_async_client() throws Exception {
		then(this.asyncRestTemplate).isNotNull();
	}

	@Test
	public void should_pass_tracing_context_with_custom_async_client() throws Exception {
		Span span = this.tracer.nextSpan().name("foo");
		try (Tracer.SpanInScope ws = this.tracer.withSpan(span.start())) {
			String result = this.asyncRestTemplate.getForEntity("http://localhost:" + this.port + "/foo", String.class)
					.get().getBody();
			BDDAssertions.then(span.context().traceId()).isEqualTo(result);
		}
		finally {
			span.end();
		}

		BDDAssertions.then(this.tracer.currentSpan()).isNull();
	}

	@Test
	public void should_start_context_with_custom_executor() throws Exception {
		then(this.executor).isNotNull();
		then(this.wrappedExecutor).isInstanceOf(LazyTraceExecutor.class);

		BDDAssertions.then(this.tracer.currentSpan()).isNull();
	}

	@Test
	public void should_inject_traced_executor_that_passes_tracing_context() throws Exception {
		Span span = this.tracer.nextSpan().name("foo");
		AtomicBoolean executed = new AtomicBoolean(false);
		try (Tracer.SpanInScope ws = this.tracer.withSpan(span.start())) {
			this.wrappedExecutor.execute(() -> {
				Span currentSpan = this.tracer.currentSpan();
				log.info("Current span " + currentSpan);
				BDDAssertions.then(currentSpan).isNotNull();
				String currentTraceId = currentSpan.context().traceId();
				String initialTraceId = span.context().traceId();
				log.info("Hello from runnable before trace id check. Initial [" + initialTraceId + "] current ["
						+ currentTraceId + "]");
				then(currentTraceId).isEqualTo(initialTraceId);
				executed.set(true);
				log.info("Hello from runnable");
			});
		}
		finally {
			span.end();
		}

		Awaitility.await().atMost(10L, TimeUnit.SECONDS).untilAsserted(() -> {
			then(executed.get()).isTrue();
		});
		BDDAssertions.then(this.tracer.currentSpan()).isNull();
	}

	@EnableAutoConfiguration
	// tag::custom_async_rest_template[]
	@Configuration(proxyBeanMethods = false)
	public static class TestConfig {

		@Bean(name = "customAsyncRestTemplate")
		public AsyncRestTemplate traceAsyncRestTemplate() {
			return new AsyncRestTemplate(asyncClientFactory(), clientHttpRequestFactory());
		}

		private ClientHttpRequestFactory clientHttpRequestFactory() {
			ClientHttpRequestFactory clientHttpRequestFactory = new CustomClientHttpRequestFactory();
			// CUSTOMIZE HERE
			return clientHttpRequestFactory;
		}

		private AsyncClientHttpRequestFactory asyncClientFactory() {
			AsyncClientHttpRequestFactory factory = new CustomAsyncClientHttpRequestFactory();
			// CUSTOMIZE HERE
			return factory;
		}

	}
	// end::custom_async_rest_template[]

	// tag::custom_executor[]
	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EnableAsync
	// add the infrastructure role to ensure that the bean gets auto-proxied
	@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
	public static class CustomExecutorConfig extends AsyncConfigurerSupport {

		@Autowired
		BeanFactory beanFactory;

		@Override
		public Executor getAsyncExecutor() {
			ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
			// CUSTOMIZE HERE
			executor.setCorePoolSize(7);
			executor.setMaxPoolSize(42);
			executor.setQueueCapacity(11);
			executor.setThreadNamePrefix("MyExecutor-");
			// DON'T FORGET TO INITIALIZE
			executor.initialize();
			return new LazyTraceExecutor(this.beanFactory, executor);
		}

	}
	// end::custom_executor[]

	@Configuration(proxyBeanMethods = false)
	public static class ControllerConfig {

		@Bean
		MyRestController myRestController(Tracer tracer) {
			return new MyRestController(tracer);
		}

	}

}

class CustomClientHttpRequestFactory implements ClientHttpRequestFactory {

	private final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

	@Override
	public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
		return this.factory.createRequest(uri, httpMethod);
	}

}

class CustomAsyncClientHttpRequestFactory implements AsyncClientHttpRequestFactory {

	private final SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

	CustomAsyncClientHttpRequestFactory() {
		this.factory.setTaskExecutor(new SimpleAsyncTaskExecutor());
	}

	@Override
	public AsyncClientHttpRequest createAsyncRequest(URI uri, HttpMethod httpMethod) throws IOException {
		return this.factory.createAsyncRequest(uri, httpMethod);
	}

}

@RestController
class MyRestController {

	private final Tracer tracer;

	MyRestController(Tracer tracer) {
		this.tracer = tracer;
	}

	@RequestMapping("/foo")
	String foo() {
		return this.tracer.currentSpan().context().traceId();
	}

}
