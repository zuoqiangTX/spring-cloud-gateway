/*
 * Copyright 2013-2017 the original author or authors.
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
 *
 */

package org.springframework.cloud.gateway.route;

import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.validation.annotation.Validated;

import javax.validation.Valid;
import javax.validation.ValidationException;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.util.StringUtils.tokenizeToStringArray;

/**
 * @author Spencer Gibb
 */
@Validated
public class RouteDefinition {
	@NotEmpty
	/**
	 * ID 编号，唯一。
	 */
	private String id = UUID.randomUUID().toString();

	@NotEmpty
	@Valid
	/**
	 * 路由断言
	 * 请求通过 predicates 判断是否匹配。在 Route 里，PredicateDefinition 转换成 Predicate 。
	 */
	private List<PredicateDefinition> predicates = new ArrayList<>();

	@Valid
	/**
	 * 过滤器数组
	 * 在 Route 里，FilterDefinition 转换成 GatewayFilter 。
	 */
	private List<FilterDefinition> filters = new ArrayList<>();

	@NotNull
	/**
	 * 路由向的 URI 。
	 */
	private URI uri;

	/**
	 * order 属性，顺序。当请求匹配到多个路由时，使用顺序小的。
	 */
	private int order = 0;

	public RouteDefinition() {
	}

	/**
	 * 格式  ${id}=${uri},${predicates[0]},${predicates[1]}...${predicates[n]}
	 *
	 * @param text
	 */
	public RouteDefinition(String text) {
		int eqIdx = text.indexOf('=');
		if (eqIdx <= 0) {
			throw new ValidationException("Unable to parse RouteDefinition text '" + text + "'" +
					", must be of the form name=value");
		}

		//第一个字符串为id
		setId(text.substring(0, eqIdx));

		//第二个字符串是uri
		String[] args = tokenizeToStringArray(text.substring(eqIdx + 1), ",");

		setUri(URI.create(args[0]));

		//第三个以后的字符串是断言数组
		for (int i = 1; i < args.length; i++) {
			this.predicates.add(new PredicateDefinition(args[i]));
		}
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public List<PredicateDefinition> getPredicates() {
		return predicates;
	}

	public void setPredicates(List<PredicateDefinition> predicates) {
		this.predicates = predicates;
	}

	public List<FilterDefinition> getFilters() {
		return filters;
	}

	public void setFilters(List<FilterDefinition> filters) {
		this.filters = filters;
	}

	public URI getUri() {
		return uri;
	}

	public void setUri(URI uri) {
		this.uri = uri;
	}

	public int getOrder() {
		return order;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		RouteDefinition routeDefinition = (RouteDefinition) o;
		return Objects.equals(id, routeDefinition.id) &&
				Objects.equals(predicates, routeDefinition.predicates) &&
				Objects.equals(order, routeDefinition.order) &&
				Objects.equals(uri, routeDefinition.uri);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, predicates, uri);
	}

	@Override
	public String toString() {
		return "RouteDefinition{" +
				"id='" + id + '\'' +
				", predicates=" + predicates +
				", filters=" + filters +
				", uri=" + uri +
				", order=" + order +
				'}';
	}
}
