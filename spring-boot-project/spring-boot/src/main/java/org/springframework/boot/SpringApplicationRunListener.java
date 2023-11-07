/*
 * Copyright 2012-2022 the original author or authors.
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

package org.springframework.boot;

import java.time.Duration;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;

/**
 * Listener for the {@link SpringApplication} {@code run} method.
 * {@link SpringApplicationRunListener}s are loaded via the {@link SpringFactoriesLoader}
 * and should declare a public constructor that accepts a {@link SpringApplication}
 * instance and a {@code String[]} of arguments. A new
 * {@link SpringApplicationRunListener} instance will be created for each run.
 *
 * @author Phillip Webb
 * @author Dave Syer
 * @author Andy Wilkinson
 * @author Chris Bono
 * @since 1.0.0
 */
public interface SpringApplicationRunListener {

	/**
	 * 首次启动run方法时立即调用。可用于非常早期的初始化。
	 */
	default void starting(ConfigurableBootstrapContext bootstrapContext) {
	}

	/**
	 * 准备好环境（Environment构建完成），但在创建ApplicationContext之前调用。
	 */
	default void environmentPrepared(ConfigurableBootstrapContext bootstrapContext,
			ConfigurableEnvironment environment) {
	}

	/**
	 * 在创建和构建ApplicationContext之后，但在加载之前调用。
	 */
	default void contextPrepared(ConfigurableApplicationContext context) {
	}

	/**
	 * ApplicationContext已加载但在刷新之前调用。
	 */
	default void contextLoaded(ConfigurableApplicationContext context) {
	}

	/**
	 * ApplicationContext已刷新，应用程序已启动，
	 * 但尚未调用CommandLineRunners和ApplicationRunners。
	 */
	default void started(ConfigurableApplicationContext context, Duration timeTaken) {
		started(context);
	}

	/**
	 * 在运行方法彻底完成之前立即调用，
	 * 刷新ApplicationContext并调用所有CommandLineRunners和ApplicationRunner。
	 */
	@Deprecated
	default void started(ConfigurableApplicationContext context) {
	}

	/**
	 * Called immediately before the run method finishes, when the application context has
	 * been refreshed and all {@link CommandLineRunner CommandLineRunners} and
	 * {@link ApplicationRunner ApplicationRunners} have been called.
	 * @param context the application context.
	 * @param timeTaken the time taken for the application to be ready or {@code null} if
	 * unknown
	 * @since 2.6.0
	 */
	default void ready(ConfigurableApplicationContext context, Duration timeTaken) {
		running(context);
	}

	/**
	 * Called immediately before the run method finishes, when the application context has
	 * been refreshed and all {@link CommandLineRunner CommandLineRunners} and
	 * {@link ApplicationRunner ApplicationRunners} have been called.
	 * @param context the application context.
	 * @since 2.0.0
	 * @deprecated since 2.6.0 for removal in 3.0.0 in favor of
	 * {@link #ready(ConfigurableApplicationContext, Duration)}
	 */
	@Deprecated
	default void running(ConfigurableApplicationContext context) {
	}

	/**
	 * 在运行应用程序时失败时调用。
	 */
	default void failed(ConfigurableApplicationContext context, Throwable exception) {
	}

}
