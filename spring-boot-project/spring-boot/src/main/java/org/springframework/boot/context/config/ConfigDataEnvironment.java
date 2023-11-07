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

package org.springframework.boot.context.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;

import org.springframework.boot.BootstrapRegistry.InstanceSupplier;
import org.springframework.boot.BootstrapRegistry.Scope;
import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.DefaultPropertiesPropertySource;
import org.springframework.boot.context.config.ConfigDataEnvironmentContributors.BinderOption;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.log.LogMessage;
import org.springframework.util.StringUtils;

/**
 * Wrapper around a {@link ConfigurableEnvironment} that can be used to import and apply
 * {@link ConfigData}. Configures the initial set of
 * {@link ConfigDataEnvironmentContributors} by wrapping property sources from the Spring
 * {@link Environment} and adding the initial set of locations.
 * <p>
 * The initial locations can be influenced via the {@link #LOCATION_PROPERTY},
 * {@value #ADDITIONAL_LOCATION_PROPERTY} and {@value #IMPORT_PROPERTY} properties. If no
 * explicit properties are set, the {@link #DEFAULT_SEARCH_LOCATIONS} will be used.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 */
class ConfigDataEnvironment {

	/**
	 * Property used override the imported locations.
	 */
	// 覆盖默认位置的配置文件，也就是使默认的配置路径下的配置文件失效，有两种用法
	// 1、配置一个目录，会自动读取目录下的 以 application 开头以 .properties /.yaml /.yml 结尾的配置文件，
	//    并且如果目录下不存在满足条件的配置文件，配置路径前面加不加optional关键字都不会报错，会正常启动，这种情况会当做没有配置文件启动，哪怕默认位置也有配置文件
	//
	// 2、直接配置一个配置文件，配置文件可以用任意名称作为配置文件的名字，不用必须是 appplication开头
	//    但是直接配置的配置文件，如果不存在，会报错，需要加上optional关键字，才能正常启动，这种情况会当做没有配置文件启动，哪怕默认位置也有配置文件
	static final String LOCATION_PROPERTY = "spring.config.location";

	/**
	 * Property used to provide additional locations to import.
	 */
	// 使用指定位置目录下的application.properties配置文件，默认位置的配置文件也生效，也是有两种用法
	// 1、配置一个目录，会自动读取目录下的 以 application 开头以 .properties /.yaml /.yml 结尾的配置文件，
	//    并且如果目录下不存在满足条件的配置文件，配置路径前面加不加optional关键字都不会报错，会正常启动，这种情况只有默认位置的配置文件生效(前提默认位置也有配置文件，否则也是无配置文件启动)
	//
	// 2、直接配置一个配置文件，配置文件可以用任意名称作为配置文件的名字，不用必须是 appplication开头
	//    但是直接配置的配置文件，如果不存在，会报错，需要加上optional关键字，才能正常启动，这种情况只有默认位置的配置文件生效(前提默认位置也有配置文件，否则也是无配置文件启动)
	static final String ADDITIONAL_LOCATION_PROPERTY = "spring.config.additional-location";

	/**
	 * Property used to provide additional locations to import.
	 */
	// 导入指定配置文件，和spring.config.additional-location的用法一模一样，可能有不一样的地方，我没找到，有大佬看到可以支出
	// 不过一般spring.config.import多配置在配置文件中，而spring.config.additional-location多用于命令行
	static final String IMPORT_PROPERTY = "spring.config.import";

	/**
	 * Property used to determine what action to take when a
	 * {@code ConfigDataNotFoundAction} is thrown.
	 * @see ConfigDataNotFoundAction
	 */
	// 是一个全局的配置，如果指定的配置文件不存在如何处理，该配置可以配置为 fail 或者 ignore，默认为fail
	// fail 找不到对应的配置文件则报错，中断启动
	// ignore 找不到对应的配置文件忽略，继续启动
	static final String ON_NOT_FOUND_PROPERTY = "spring.config.on-not-found";

	/**
	 * Default search locations used if not {@link #LOCATION_PROPERTY} is found.
	 */
	// 默认的配置文件所在目录路径，下面的静态代码块是向其中加入的配置文件目录路径
	// 注意，只会读取配置路径目录下面的以 application 开头以 .properties /.yaml /.yml 结尾的配置文件
	static final ConfigDataLocation[] DEFAULT_SEARCH_LOCATIONS;

	// 加入的默认路径，
	// 需要注意的是，下面加入的目录路径前面都有，optional关键字，
	// 如果路径前加optional，则说明该配置可有可以无，不加则说明必须有，没有会报错
	static {
		List<ConfigDataLocation> locations = new ArrayList<>();
		// 类路径下，或者类路径下的config目录，就是我们常见的resource下
		locations.add(ConfigDataLocation.of("optional:classpath:/;optional:classpath:/config/"));

		// 当前项目根目录下，或者当前项目跟目录下的，config目录下
		locations.add(ConfigDataLocation.of("optional:file:./;optional:file:./config/;optional:file:./config/*/"));
		DEFAULT_SEARCH_LOCATIONS = locations.toArray(new ConfigDataLocation[0]);
	}

	// 空路径，后续解析各个配置下的配置文件，如果不存在配置文件，则返回这个空路径
	private static final ConfigDataLocation[] EMPTY_LOCATIONS = new ConfigDataLocation[0];

	// 配置文件位置数据模板，后面将所有的配置文件的位置都会根据这个常量解析成ConfigDataLocation类型
	private static final Bindable<ConfigDataLocation[]> CONFIG_DATA_LOCATION_ARRAY = Bindable
			.of(ConfigDataLocation[].class);

	private static final Bindable<List<String>> STRING_LIST = Bindable.listOf(String.class);

	private static final BinderOption[] ALLOW_INACTIVE_BINDING = {};

	private static final BinderOption[] DENY_INACTIVE_BINDING = { BinderOption.FAIL_ON_BIND_TO_INACTIVE_SOURCE };

	private final DeferredLogFactory logFactory;

	private final Log logger;

	private final ConfigDataNotFoundAction notFoundAction;

	private final ConfigurableBootstrapContext bootstrapContext;

	private final ConfigurableEnvironment environment;

	private final ConfigDataLocationResolvers resolvers;

	private final Collection<String> additionalProfiles;

	private final ConfigDataEnvironmentUpdateListener environmentUpdateListener;

	private final ConfigDataLoaders loaders;

	private final ConfigDataEnvironmentContributors contributors;

	/**
	 * Create a new {@link ConfigDataEnvironment} instance.
	 * @param logFactory the deferred log factory
	 * @param bootstrapContext the bootstrap context
	 * @param environment the Spring {@link Environment}.
	 * @param resourceLoader {@link ResourceLoader} to load resource locations
	 * @param additionalProfiles any additional profiles to activate
	 * @param environmentUpdateListener optional
	 * {@link ConfigDataEnvironmentUpdateListener} that can be used to track
	 * {@link Environment} updates.
	 */
	ConfigDataEnvironment(DeferredLogFactory logFactory, ConfigurableBootstrapContext bootstrapContext,
			ConfigurableEnvironment environment, ResourceLoader resourceLoader, Collection<String> additionalProfiles,
			ConfigDataEnvironmentUpdateListener environmentUpdateListener) {
		// 将环境对象(environment)转换成Binder类型，便于配置的操作
		Binder binder = Binder.get(environment);
		// 2.4版本后配置文件进行了大变革，这里和旧的配置有关，此处用不到，不用关系
		UseLegacyConfigProcessingException.throwIfRequested(binder);
		this.logFactory = logFactory;
		this.logger = logFactory.getLog(getClass());
		// 全局配置，没有找到对应的配置文件该如何操作，默认是失败，如果spring.config.on-not-found的配置文ignore，则会忽略，无论配置文件前面是否有添加optional
		this.notFoundAction = binder.bind(ON_NOT_FOUND_PROPERTY, ConfigDataNotFoundAction.class)
				.orElse(ConfigDataNotFoundAction.FAIL);
		this.bootstrapContext = bootstrapContext;
		this.environment = environment;
		// 初始化配置文件路径解析器集合对象
		this.resolvers = createConfigDataLocationResolvers(logFactory, bootstrapContext, binder, resourceLoader);
		// 附加配置文件，为空
		this.additionalProfiles = additionalProfiles;
		// 环境更新监听器，传入的environmentUpdateListener为空，初始化一个空的监听器
		this.environmentUpdateListener = (environmentUpdateListener != null) ? environmentUpdateListener : ConfigDataEnvironmentUpdateListener.NONE;
		// 初始化配置文件加载器集合对象
		this.loaders = new ConfigDataLoaders(logFactory, bootstrapContext, resourceLoader.getClassLoader());
		// 初始化配置文件贡献者集合对象 **重要**
		// 配置数据环境贡献者 -> ConfigDataEnvironmentContributor
		this.contributors = createContributors(binder);
	}

	protected ConfigDataLocationResolvers createConfigDataLocationResolvers(DeferredLogFactory logFactory,
			ConfigurableBootstrapContext bootstrapContext, Binder binder, ResourceLoader resourceLoader) {
		// 创建一个ConfigDataLocationResolvers对象
		return new ConfigDataLocationResolvers(logFactory, bootstrapContext, binder, resourceLoader);
	}

	private ConfigDataEnvironmentContributors createContributors(Binder binder) {
		this.logger.trace("Building config data environment contributors");
		// 所有的配置都存在环境对象中(environment)，而环境对象中在前面文章中我们也出现过，会存在PropertySource集合，而后面出现的配置文件，最终也会解析成PropertySource对象，存入环境中的PropertySource集合
		// 环境中PropertySource集合已存在的:
		// 1、ConfigurationPropertySourcesPropertySource {name='configurationProperties'}
		// 2、SimpleCommandLinePropertySource {name='commandLineArgs'}
		// 3、StubPropertySource {name='servletConfigInitParams'}
		// 4、StubPropertySource {name='servletContextInitParams'}
		// 5、PropertiesPropertySource {name='systemProperties'}
		// 6、OriginAwareSystemEnvironmentPropertySource {name='systemEnvironment'}
		// 7、RandomValuePropertySource {name='random'}
		MutablePropertySources propertySources = this.environment.getPropertySources();

		// ConfigDataEnvironmentContributor还区分类型
		// + ROOT：根贡献者，本身提供任何配置，但是有的贡献者都在root贡献者里存储
		// + EXISTING：已存在贡献者，在初始化配置文件之前，也就是创建配置数据环境贡献者集合对象之前就存在的配置，比如上图中已经存在的属性源对象PropertySource，解析成的贡献者
		// + INITIAL_IMPORT：初始导入贡献者，在配置数据环境贡献者集合对象创建完成后，由spring.config.location，spring.config.additional-location，spring.config.import，指定的路径或者默认的配置路径，此贡献者用于解析出其他贡献者，本身不提供任何配置
		// + UNBOUND_IMPORT：未导入完成贡献者，已经解析的贡献者，但是本身可能会解析出更多贡献者
		// + BOUND_IMPORT：导入完成贡献者，解析完成的贡献者，里面已经有属性源
		// + EMPTY_LOCATION：空的贡献者
		List<ConfigDataEnvironmentContributor> contributors = new ArrayList<>(propertySources.size() + 10);
		PropertySource<?> defaultPropertySource = null;
		for (PropertySource<?> propertySource : propertySources) {
			if (DefaultPropertiesPropertySource.hasMatchingName(propertySource)) {
				defaultPropertySource = propertySource;
			}
			else {
				this.logger.trace(LogMessage.format("Creating wrapped config data contributor for '%s'",
						propertySource.getName()));
				// 将环境中已有的属性源PropertySource解析为已存在贡献者
				contributors.add(ConfigDataEnvironmentContributor.ofExisting(propertySource));
			}
		}
		// 将启动参数中的spring.config.location，spring.config.additional-location，spring.config.import
		// 等配置指定的路径解析成初始导入贡献者
		contributors.addAll(getInitialImportContributors(binder));
		if (defaultPropertySource != null) {
			this.logger.trace("Creating wrapped config data contributor for default property source");
			contributors.add(ConfigDataEnvironmentContributor.ofExisting(defaultPropertySource));
		}
		// 将所有的贡献者传入，创建一个贡献者集合对象
		return createContributors(contributors);
	}

	protected ConfigDataEnvironmentContributors createContributors(
			List<ConfigDataEnvironmentContributor> contributors) {
		// 调用重载构造方法
		return new ConfigDataEnvironmentContributors(this.logFactory, this.bootstrapContext, contributors);
	}

	ConfigDataEnvironmentContributors getContributors() {
		return this.contributors;
	}

	private List<ConfigDataEnvironmentContributor> getInitialImportContributors(Binder binder) {
		// 导入贡献者集合，收集后面由启动参数创建的贡献者
		List<ConfigDataEnvironmentContributor> initialContributors = new ArrayList<>();

		// IMPORT_PROPERTY就是spring.config.import，EMPTY_LOCATIONS就是一个空的路径对象
		// 从启动参数里获取spring.config.import配置，如果有，则创建一个配置路径对象ConfigDataLocation，没有就使用空的配置路径对象
		addInitialImportContributors(initialContributors, bindLocations(binder, IMPORT_PROPERTY, EMPTY_LOCATIONS));

		// ADDITIONAL_LOCATION_PROPERTY就是spring.config.additional，EMPTY_LOCATIONS就是一个空的路径对象
		// 从启动参数里获取spring.config.additional配置，如果有，则创建一个配置路径对象ConfigDataLocation，没有就使用空的配置路径对象
		addInitialImportContributors(initialContributors, bindLocations(binder, ADDITIONAL_LOCATION_PROPERTY, EMPTY_LOCATIONS));

		// LOCATION_PROPERTY就是spring.config.location，
		// DEFAULT_SEARCH_LOCATIONS里就是默认的路径对象，里面有两个路径对象，optional:classpath:/;optional:classpath:/config/和optional:file:./;optional:file:./config/;optional:file:./config/*/
		// 从启动参数里获取spring.config.location配置，如果有，则创建一个配置路径对象ConfigDataLocation，没有就使用默认的配置路径对象
		addInitialImportContributors(initialContributors, bindLocations(binder, LOCATION_PROPERTY, DEFAULT_SEARCH_LOCATIONS));
		return initialContributors;
	}

	/**
	 * 构建路径对象
	 * @param binder	由环境对象解析而来，里面有所有的系统环境变量，jvm环境变量，启动参数变量
	 * @param propertyName	要从环境中获取的key
	 * @param other	如果获取不到，则返回的默认对象
	 * @return
	 */
	private ConfigDataLocation[] bindLocations(Binder binder, String propertyName, ConfigDataLocation[] other) {
		return binder.bind(propertyName, CONFIG_DATA_LOCATION_ARRAY).orElse(other);
	}

	/**
	 *
	 * @param initialContributors	收集贡献者
	 * @param locations	要解析为贡献者的路径对象
	 */
	private void addInitialImportContributors(List<ConfigDataEnvironmentContributor> initialContributors,
			ConfigDataLocation[] locations) {
		for (int i = locations.length - 1; i >= 0; i--) {
			initialContributors.add(createInitialImportContributor(locations[i]));
		}
	}

	// 将传入的配置数据路径对象转为 初始导入贡献者
	private ConfigDataEnvironmentContributor createInitialImportContributor(ConfigDataLocation location) {
		this.logger.trace(LogMessage.format("Adding initial config data import from location '%s'", location));
		return ConfigDataEnvironmentContributor.ofInitialImport(location);
	}

	/**
	 * Process all contributions and apply any newly imported property sources to the
	 * {@link Environment}.
	 */
	void processAndApply() {
		// 创建一个数据导入器，数据导入器专门用于解析贡献者中的路径，到具体解析时在看， 实例化过程就不看了
		ConfigDataImporter importer = new ConfigDataImporter(this.logFactory, this.notFoundAction, this.resolvers,
				this.loaders);
		registerBootstrapBinder(this.contributors, null, DENY_INACTIVE_BINDING);

		// 第一阶段，解析初始导入贡献者
		ConfigDataEnvironmentContributors contributors = processInitial(this.contributors, importer);

		// 创建一个配置环境激活上下文，激活上下文和云平台以及profiles的关系，此时创建的只推断云平台，因为我们是本地debug，没有云平台相关配置，其中的cloudPlatform属性为空
		ConfigDataActivationContext activationContext = createActivationContext(
				contributors.getBinder(null, BinderOption.FAIL_ON_BIND_TO_INACTIVE_SOURCE));

		// 第二阶段，和云平台相关配置，由于上面解析cloudPlatform为null，所以贡献者没有任何变化，我们也不用看，哪怕有云配置相关配置，解析步骤也和第一阶段一样
		contributors = processWithoutProfiles(contributors, importer, activationContext);

		// 对配置环境激活上下文进行处理，这次从环境中获取了了profiles相关的配置
		activationContext = withProfiles(contributors, activationContext);

		// 第三阶段，解析启动参数中的 spring.profiles.active 以及，主配置文件中的 spring.profiles.group，解析流和第三阶段一样
		contributors = processWithProfiles(contributors, importer, activationContext);

		// 所有的贡献者都被解析出来，并且每个贡献者的属性源也被解析出来，该方法就是将贡献者中的属性源，添加到环境中
		applyToEnvironment(contributors, activationContext, importer.getLoadedLocations(),
				importer.getOptionalLocations());
	}

	private ConfigDataEnvironmentContributors processInitial(ConfigDataEnvironmentContributors contributors,
			ConfigDataImporter importer) {
		this.logger.trace("Processing initial config data environment contributors without activation context");
		contributors = contributors.withProcessedImports(importer, null);
		registerBootstrapBinder(contributors, null, DENY_INACTIVE_BINDING);
		return contributors;
	}

	private ConfigDataActivationContext createActivationContext(Binder initialBinder) {
		this.logger.trace("Creating config data activation context from initial contributions");
		try {
			return new ConfigDataActivationContext(this.environment, initialBinder);
		}
		catch (BindException ex) {
			if (ex.getCause() instanceof InactiveConfigDataAccessException) {
				throw (InactiveConfigDataAccessException) ex.getCause();
			}
			throw ex;
		}
	}

	private ConfigDataEnvironmentContributors processWithoutProfiles(ConfigDataEnvironmentContributors contributors,
			ConfigDataImporter importer, ConfigDataActivationContext activationContext) {
		this.logger.trace("Processing config data environment contributors with initial activation context");
		contributors = contributors.withProcessedImports(importer, activationContext);
		registerBootstrapBinder(contributors, activationContext, DENY_INACTIVE_BINDING);
		return contributors;
	}

	private ConfigDataActivationContext withProfiles(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext) {
		this.logger.trace("Deducing profiles from current config data environment contributors");
		Binder binder = contributors.getBinder(activationContext,
				(contributor) -> !contributor.hasConfigDataOption(ConfigData.Option.IGNORE_PROFILES),
				BinderOption.FAIL_ON_BIND_TO_INACTIVE_SOURCE);
		try {
			Set<String> additionalProfiles = new LinkedHashSet<>(this.additionalProfiles);
			additionalProfiles.addAll(getIncludedProfiles(contributors, activationContext));
			Profiles profiles = new Profiles(this.environment, binder, additionalProfiles);
			return activationContext.withProfiles(profiles);
		}
		catch (BindException ex) {
			if (ex.getCause() instanceof InactiveConfigDataAccessException) {
				throw (InactiveConfigDataAccessException) ex.getCause();
			}
			throw ex;
		}
	}

	private Collection<? extends String> getIncludedProfiles(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext) {
		PlaceholdersResolver placeholdersResolver = new ConfigDataEnvironmentContributorPlaceholdersResolver(
				contributors, activationContext, null, true);
		Set<String> result = new LinkedHashSet<>();
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			ConfigurationPropertySource source = contributor.getConfigurationPropertySource();
			if (source != null && !contributor.hasConfigDataOption(ConfigData.Option.IGNORE_PROFILES)) {
				Binder binder = new Binder(Collections.singleton(source), placeholdersResolver);
				binder.bind(Profiles.INCLUDE_PROFILES, STRING_LIST).ifBound((includes) -> {
					if (!contributor.isActive(activationContext)) {
						InactiveConfigDataAccessException.throwIfPropertyFound(contributor, Profiles.INCLUDE_PROFILES);
						InactiveConfigDataAccessException.throwIfPropertyFound(contributor,
								Profiles.INCLUDE_PROFILES.append("[0]"));
					}
					result.addAll(includes);
				});
			}
		}
		return result;
	}

	private ConfigDataEnvironmentContributors processWithProfiles(ConfigDataEnvironmentContributors contributors,
			ConfigDataImporter importer, ConfigDataActivationContext activationContext) {
		this.logger.trace("Processing config data environment contributors with profile activation context");
		contributors = contributors.withProcessedImports(importer, activationContext);
		registerBootstrapBinder(contributors, activationContext, ALLOW_INACTIVE_BINDING);
		return contributors;
	}

	private void registerBootstrapBinder(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext, BinderOption... binderOptions) {
		this.bootstrapContext.register(Binder.class, InstanceSupplier
				.from(() -> contributors.getBinder(activationContext, binderOptions)).withScope(Scope.PROTOTYPE));
	}

	private void applyToEnvironment(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext, Set<ConfigDataLocation> loadedLocations,
			Set<ConfigDataLocation> optionalLocations) {
		// 检查一些错误的配置，比如spring.profile.active，不能出现在非主配置文件中
		checkForInvalidProperties(contributors);
		// 检查非默认位置的 配置文件是否都加载过
		checkMandatoryLocations(contributors, activationContext, loadedLocations, optionalLocations);
		// 获取环境中的 属性源集合对象
		MutablePropertySources propertySources = this.environment.getPropertySources();
		// 将贡献者中的属性源都添加到环境的的属性源集合对象，也是核销方法
		applyContributor(contributors, activationContext, propertySources);
		// 将默认的属性源移动到最后面
		DefaultPropertiesPropertySource.moveToEnd(propertySources);
		// 获取profile
		Profiles profiles = activationContext.getProfiles();
		this.logger.trace(LogMessage.format("Setting default profiles: %s", profiles.getDefault()));

		// 设置环境中的默认profiles
		this.environment.setDefaultProfiles(StringUtils.toStringArray(profiles.getDefault()));
		this.logger.trace(LogMessage.format("Setting active profiles: %s", profiles.getActive()));

		// 设置环境中的 激活的profiles
		this.environment.setActiveProfiles(StringUtils.toStringArray(profiles.getActive()));

		// 发送环境更新事件，其实前面我们写到过，没有这个属性为空
		this.environmentUpdateListener.onSetProfiles(profiles);
	}

	private void applyContributor(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext, MutablePropertySources propertySources) {
		this.logger.trace("Applying config data environment contributions");
		// 遍历贡献者
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			PropertySource<?> propertySource = contributor.getPropertySource();
			// 将角色为BOUND_IMPORT并且属性源不为空的贡献者的属性源加入 属性源集合对象中
			if (contributor.getKind() == ConfigDataEnvironmentContributor.Kind.BOUND_IMPORT && propertySource != null) {
				if (!contributor.isActive(activationContext)) {
					this.logger.trace(
							LogMessage.format("Skipping inactive property source '%s'", propertySource.getName()));
				}
				else {
					this.logger
							.trace(LogMessage.format("Adding imported property source '%s'", propertySource.getName()));
					propertySources.addLast(propertySource);
					this.environmentUpdateListener.onPropertySourceAdded(propertySource, contributor.getLocation(),
							contributor.getResource());
				}
			}
		}
	}

	private void checkForInvalidProperties(ConfigDataEnvironmentContributors contributors) {
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			InvalidConfigDataPropertyException.throwOrWarn(this.logger, contributor);
		}
	}

	private void checkMandatoryLocations(ConfigDataEnvironmentContributors contributors,
			ConfigDataActivationContext activationContext, Set<ConfigDataLocation> loadedLocations,
			Set<ConfigDataLocation> optionalLocations) {
		Set<ConfigDataLocation> mandatoryLocations = new LinkedHashSet<>();
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			if (contributor.isActive(activationContext)) {
				mandatoryLocations.addAll(getMandatoryImports(contributor));
			}
		}
		for (ConfigDataEnvironmentContributor contributor : contributors) {
			if (contributor.getLocation() != null) {
				mandatoryLocations.remove(contributor.getLocation());
			}
		}
		mandatoryLocations.removeAll(loadedLocations);
		mandatoryLocations.removeAll(optionalLocations);
		if (!mandatoryLocations.isEmpty()) {
			for (ConfigDataLocation mandatoryLocation : mandatoryLocations) {
				this.notFoundAction.handle(this.logger, new ConfigDataLocationNotFoundException(mandatoryLocation));
			}
		}
	}

	private Set<ConfigDataLocation> getMandatoryImports(ConfigDataEnvironmentContributor contributor) {
		List<ConfigDataLocation> imports = contributor.getImports();
		Set<ConfigDataLocation> mandatoryLocations = new LinkedHashSet<>(imports.size());
		for (ConfigDataLocation location : imports) {
			if (!location.isOptional()) {
				mandatoryLocations.add(location);
			}
		}
		return mandatoryLocations;
	}

}
