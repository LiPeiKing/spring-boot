/*
 * Copyright 2012-2021 the original author or authors.
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

import java.io.IOException;
import java.util.List;

import org.springframework.boot.context.config.ConfigData.Option;
import org.springframework.boot.context.config.ConfigData.PropertySourceOptions;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginTrackedResource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;

/**
 * {@link ConfigDataLoader} for {@link Resource} backed locations.
 *
 * @author Phillip Webb
 * @author Madhura Bhave
 * @since 2.4.0
 */
public class StandardConfigDataLoader implements ConfigDataLoader<StandardConfigDataResource> {

	private static final PropertySourceOptions PROFILE_SPECIFIC = PropertySourceOptions.always(Option.PROFILE_SPECIFIC);

	private static final PropertySourceOptions NON_PROFILE_SPECIFIC = PropertySourceOptions.ALWAYS_NONE;

	@Override
	public ConfigData load(ConfigDataLoaderContext context, StandardConfigDataResource resource)
			throws IOException, ConfigDataNotFoundException {
		// 如果资源是一个空的目录，则返回一个空的配置数据
		if (resource.isEmptyDirectory()) {
			return ConfigData.EMPTY;
		}
		// 资源不存在则报错
		ConfigDataResourceNotFoundException.throwIfDoesNotExist(resource, resource.getResource());
		// 获取资源中的 标准配置数据引用
		StandardConfigDataReference reference = resource.getReference();
		// 将资源转为OriginTrackedWritableResource类型
		Resource originTrackedResource = OriginTrackedResource.of(resource.getResource(),
				Origin.from(reference.getConfigDataLocation()));
		// 格式化资源的名字
		String name = String.format("Config resource '%s' via location '%s'", resource,
				reference.getConfigDataLocation());
		// 通过标准配置数据引用里面的 属性源解析器 来加载资源，返回的结果就是 PropertySource
		List<PropertySource<?>> propertySources = reference.getPropertySourceLoader().load(name, originTrackedResource);
		PropertySourceOptions options = (resource.getProfile() != null) ? PROFILE_SPECIFIC : NON_PROFILE_SPECIFIC;
		// 然后创建一个配置数据对象，传入属性源
		return new ConfigData(propertySources, options);
	}

}
