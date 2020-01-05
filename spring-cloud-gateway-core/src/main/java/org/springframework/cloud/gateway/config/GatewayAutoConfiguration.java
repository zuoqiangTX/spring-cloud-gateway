/*
 * Copyright 2013-2018 the original author or authors.
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

package org.springframework.cloud.gateway.config;

import com.netflix.hystrix.HystrixObservableCommand;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnEnabledEndpoint;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.web.reactive.HttpHandlerAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.cloud.gateway.actuate.GatewayControllerEndpoint;
import org.springframework.cloud.gateway.filter.*;
import org.springframework.cloud.gateway.filter.factory.*;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.factory.rewrite.ModifyResponseBodyGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.headers.ForwardedHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.RemoveHopByHopHeadersFilter;
import org.springframework.cloud.gateway.filter.headers.XForwardedHeadersFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.PrincipalNameKeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.handler.FilteringWebHandler;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.handler.predicate.*;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.util.StringUtils;
import org.springframework.validation.Validator;
import org.springframework.web.filter.reactive.HiddenHttpMethodFilter;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientOptions;
import reactor.ipc.netty.options.ClientProxyOptions;
import reactor.ipc.netty.resources.PoolResources;
import rx.RxReactiveStreams;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.function.Consumer;

import static org.springframework.cloud.gateway.config.HttpClientProperties.Pool.PoolType.DISABLED;
import static org.springframework.cloud.gateway.config.HttpClientProperties.Pool.PoolType.FIXED;

/**
 * gateway 核心自动配置类
 *
 * @author Spencer Gibb
 */
@Configuration
/**
 * 通过 spring.cloud.gateway.enabled 配置网关的开启与关闭。默认开启
 */
@ConditionalOnProperty(name = "spring.cloud.gateway.enabled", matchIfMissing = true)
@EnableConfigurationProperties
@AutoConfigureBefore(HttpHandlerAutoConfiguration.class)
@AutoConfigureAfter({GatewayLoadBalancerClientAutoConfiguration.class,
		GatewayClassPathWarningAutoConfiguration.class})
@ConditionalOnClass(DispatcherHandler.class)
public class GatewayAutoConfiguration {

	/**
	 * netty 配置
	 */
	@Configuration
	@ConditionalOnClass(HttpClient.class)
	protected static class NettyConfiguration {
		@Bean// 1.2
		@ConditionalOnMissingBean
		public HttpClient httpClient(@Qualifier("nettyClientOptions") Consumer<? super HttpClientOptions.Builder> options) {
			/**
			 * 创建一个类型为 reactor.ipc.netty.http.client.HttpClient 的 Bean 对象。该 HttpClient 使用 Netty 实现的 Client 。
			 */
			return HttpClient.create(options);
		}

		@Bean// 1.1
		public Consumer<? super HttpClientOptions.Builder> nettyClientOptions(HttpClientProperties properties) {
			return opts -> {

				if (properties.getConnectTimeout() != null) {
					opts.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeout());
				}

				// configure ssl
				HttpClientProperties.Ssl ssl = properties.getSsl();
				opts.sslHandshakeTimeoutMillis(ssl.getHandshakeTimeoutMillis());
				opts.sslCloseNotifyFlushTimeoutMillis(ssl.getCloseNotifyFlushTimeoutMillis());
				opts.sslCloseNotifyReadTimeoutMillis(ssl.getCloseNotifyReadTimeoutMillis());
				X509Certificate[] trustedX509Certificates = ssl
						.getTrustedX509CertificatesForTrustManager();
				if (trustedX509Certificates.length > 0) {
					opts.sslSupport(sslContextBuilder -> {
						sslContextBuilder.trustManager(trustedX509Certificates);
					});
				} else if (ssl.isUseInsecureTrustManager()) {
					opts.sslSupport(sslContextBuilder -> {
						sslContextBuilder
								.trustManager(InsecureTrustManagerFactory.INSTANCE);
					});
				}

				// configure pool resources
				HttpClientProperties.Pool pool = properties.getPool();

				if (pool.getType() == DISABLED) {
					opts.disablePool();
				} else if (pool.getType() == FIXED) {
					PoolResources poolResources = PoolResources.fixed(pool.getName(),
							pool.getMaxConnections(), pool.getAcquireTimeout());
					opts.poolResources(poolResources);
				} else {
					PoolResources poolResources = PoolResources.elastic(pool.getName());
					opts.poolResources(poolResources);
				}


				// configure proxy if proxy host is set.
				HttpClientProperties.Proxy proxy = properties.getProxy();
				if (StringUtils.hasText(proxy.getHost())) {
					opts.proxy(typeSpec -> {
						ClientProxyOptions.Builder builder = typeSpec
								.type(ClientProxyOptions.Proxy.HTTP)
								.host(proxy.getHost());

						PropertyMapper map = PropertyMapper.get();

						map.from(proxy::getPort)
								.whenNonNull()
								.to(builder::port);
						map.from(proxy::getUsername)
								.whenHasText()
								.to(builder::username);
						map.from(proxy::getPassword)
								.whenHasText()
								.to(password -> builder.password(s -> password));
						map.from(proxy::getNonProxyHostsPattern)
								.whenHasText()
								.to(builder::nonProxyHosts);

						return builder;
					});
				}
			};
		}

		@Bean
		public HttpClientProperties httpClientProperties() {
			return new HttpClientProperties();
		}

		@Bean// 1.3
		public NettyRoutingFilter routingFilter(HttpClient httpClient,
												ObjectProvider<List<HttpHeadersFilter>> headersFilters,
												HttpClientProperties properties) {
			return new NettyRoutingFilter(httpClient, headersFilters, properties);
		}

		@Bean// 1.4
		public NettyWriteResponseFilter nettyWriteResponseFilter(GatewayProperties properties) {
			return new NettyWriteResponseFilter(properties.getStreamingMediaTypes());
		}

		@Bean// 1.5 {@link org.springframework.cloud.gateway.filter.WebsocketRoutingFilter}
		public ReactorNettyWebSocketClient reactorNettyWebSocketClient(@Qualifier("nettyClientOptions") Consumer<? super HttpClientOptions.Builder> options) {
			return new ReactorNettyWebSocketClient(options);
		}
	}

	//TODO: remove when not needed anymore
	// either https://jira.spring.io/browse/SPR-17291 or
	// https://github.com/spring-projects/spring-boot/issues/14520 needs to be fixed
	@Bean
	public HiddenHttpMethodFilter hiddenHttpMethodFilter() {
		return new HiddenHttpMethodFilter() {
			@Override
			public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
				return chain.filter(exchange);
			}
		};
	}

	@Bean
	public RouteLocatorBuilder routeLocatorBuilder(ConfigurableApplicationContext context) {
		return new RouteLocatorBuilder(context);
	}

	@Bean
	@ConditionalOnMissingBean
	public PropertiesRouteDefinitionLocator propertiesRouteDefinitionLocator(GatewayProperties properties) {
		return new PropertiesRouteDefinitionLocator(properties);
	}

	/**
	 * 自定义的一个内存级别的map 的路由本地缓存
	 *
	 * @return
	 */
	@Bean
	/**
	 * 当我们自己没有定义的时候，才使用系统自定义内存级别的路由缓存，所以我们可以通过此时我们可以实现 RouteDefinitionRepository 接口，以实现自定义路由
	 */
	@ConditionalOnMissingBean(RouteDefinitionRepository.class)
	public InMemoryRouteDefinitionRepository inMemoryRouteDefinitionRepository() {
		return new InMemoryRouteDefinitionRepository();
	}

	/**
	 * 路由定义
	 *
	 * @param routeDefinitionLocators
	 * @return
	 */
	@Bean
	@Primary
	public RouteDefinitionLocator routeDefinitionLocator(List<RouteDefinitionLocator> routeDefinitionLocators) {
		return new CompositeRouteDefinitionLocator(Flux.fromIterable(routeDefinitionLocators));
	}

	/**
	 * 初始化 RouteLocator 路由定位器
	 *
	 * @param properties
	 * @param GatewayFilters
	 * @param predicates
	 * @param routeDefinitionLocator
	 * @return
	 */
	@Bean
	public RouteLocator routeDefinitionRouteLocator(GatewayProperties properties,
													List<GatewayFilterFactory> GatewayFilters,
													List<RoutePredicateFactory> predicates,
													RouteDefinitionLocator routeDefinitionLocator) {
		return new RouteDefinitionRouteLocator(routeDefinitionLocator, predicates, GatewayFilters, properties);
	}

	@Bean
	@Primary
	//TODO: property to disable composite?
	public RouteLocator cachedCompositeRouteLocator(List<RouteLocator> routeLocators) {
		return new CachingRouteLocator(new CompositeRouteLocator(Flux.fromIterable(routeLocators)));
	}

	/**
	 * 路由刷新监听器【实质上是执行RefreshRoutesEvent 事件】
	 *
	 * @param publisher
	 * @return
	 */
	@Bean
	public RouteRefreshListener routeRefreshListener(ApplicationEventPublisher publisher) {
		return new RouteRefreshListener(publisher);
	}

	/**
	 * FilteringWebHandler 通过创建请求对应的 Route 对应的 GatewayFilterChain【过滤器链】 进行处理。
	 *
	 * @param globalFilters
	 * @return
	 */
	@Bean
	public FilteringWebHandler filteringWebHandler(List<GlobalFilter> globalFilters) {
		return new FilteringWebHandler(globalFilters);
	}

	/**
	 * 全局跨域配置
	 *
	 * @return
	 */
	@Bean
	public GlobalCorsProperties globalCorsProperties() {
		return new GlobalCorsProperties();
	}

	/**
	 * 断言handlerMapping 用于查找匹配到 Route ，并进行处理
	 *
	 * @param webHandler
	 * @param routeLocator
	 * @param globalCorsProperties
	 * @param environment
	 * @return
	 */
	@Bean
	public RoutePredicateHandlerMapping routePredicateHandlerMapping(
			FilteringWebHandler webHandler, RouteLocator routeLocator,
			GlobalCorsProperties globalCorsProperties, Environment environment) {
		return new RoutePredicateHandlerMapping(webHandler, routeLocator,
				globalCorsProperties, environment);
	}

	// ConfigurationProperty beans

	/**
	 * gateway 配置文件配置的 RouteDefinition / FilterDefinition
	 *
	 * @return
	 */
	@Bean
	public GatewayProperties gatewayProperties() {
		return new GatewayProperties();
	}

	@Bean
	public SecureHeadersProperties secureHeadersProperties() {
		return new SecureHeadersProperties();
	}

	//******************start******************一堆自定义的过滤器 *******************end******************//
	// HttpHeaderFilter beans

	/**
	 * 转发header 过滤器 生效条件spring.cloud.gateway.forwarded.enabled
	 *
	 * @return
	 */
	@Bean
	@ConditionalOnProperty(name = "spring.cloud.gateway.forwarded.enabled", matchIfMissing = true)
	public ForwardedHeadersFilter forwardedHeadersFilter() {
		return new ForwardedHeadersFilter();
	}

	@Bean
	public RemoveHopByHopHeadersFilter removeHopByHopHeadersFilter() {
		return new RemoveHopByHopHeadersFilter();
	}

	@Bean
	@ConditionalOnProperty(name = "spring.cloud.gateway.x-forwarded.enabled", matchIfMissing = true)
	public XForwardedHeadersFilter xForwardedHeadersFilter() {
		return new XForwardedHeadersFilter();
	}

	// GlobalFilter beans
	//******************start*****************全局过滤器 GlobalFilter*******************end******************//

	@Bean// 2.1
	public AdaptCachedBodyGlobalFilter adaptCachedBodyGlobalFilter() {
		return new AdaptCachedBodyGlobalFilter();
	}

	@Bean // 2.2 RouteToRequestUrlFilter 根据匹配的 Route ，计算请求的地址。注意，这里的地址指的是 URL ，而不是 URI 。
	public RouteToRequestUrlFilter routeToRequestUrlFilter() {
		return new RouteToRequestUrlFilter();
	}

	@Bean // 2.3 RouteToRequestUrlFilter ，转发路由网关过滤器。其根据 forward:// 前缀( Scheme )过滤处理，将请求转发到当前网关实例本地接口。
	@ConditionalOnBean(DispatcherHandler.class)
	public ForwardRoutingFilter forwardRoutingFilter(ObjectProvider<DispatcherHandler> dispatcherHandler) {
		return new ForwardRoutingFilter(dispatcherHandler);
	}

	@Bean // 2.4
	public ForwardPathFilter forwardPathFilter() {
		return new ForwardPathFilter();
	}

	@Bean
	public WebSocketService webSocketService() {
		return new HandshakeWebSocketService();
	}

	@Bean
	//2.5 WebsocketRoutingFilter ，Websocket 路由网关过滤器。其根据 ws:// / wss:// 前缀( Scheme )过滤处理，代理后端 Websocket 服务，提供给客户端连接。
	public WebsocketRoutingFilter websocketRoutingFilter(WebSocketClient webSocketClient,
														 WebSocketService webSocketService,
														 ObjectProvider<List<HttpHeadersFilter>> headersFilters) {
		return new WebsocketRoutingFilter(webSocketClient, webSocketService, headersFilters);
	}

	@Bean//权重计算过滤器？？？
	public WeightCalculatorWebFilter weightCalculatorWebFilter(Validator validator, ObjectProvider<RouteLocator> routeLocator) {
		return new WeightCalculatorWebFilter(validator, routeLocator);
	}

	/*@Bean
	//TODO: default over netty? configurable
	public WebClientHttpRoutingFilter webClientHttpRoutingFilter() {
		//TODO: WebClient bean
		return new WebClientHttpRoutingFilter(WebClient.routes().build());
	}

	@Bean
	public WebClientWriteResponseFilter webClientWriteResponseFilter() {
		return new WebClientWriteResponseFilter();
	}*/

	// Predicate Factory beans

	//******************start******************一堆自定义的断言*******************end******************//
	@Bean
	public AfterRoutePredicateFactory afterRoutePredicateFactory() {
		return new AfterRoutePredicateFactory();
	}

	@Bean
	public BeforeRoutePredicateFactory beforeRoutePredicateFactory() {
		return new BeforeRoutePredicateFactory();
	}

	@Bean
	public BetweenRoutePredicateFactory betweenRoutePredicateFactory() {
		return new BetweenRoutePredicateFactory();
	}

	@Bean
	public CookieRoutePredicateFactory cookieRoutePredicateFactory() {
		return new CookieRoutePredicateFactory();
	}

	@Bean
	public HeaderRoutePredicateFactory headerRoutePredicateFactory() {
		return new HeaderRoutePredicateFactory();
	}

	@Bean
	public HostRoutePredicateFactory hostRoutePredicateFactory() {
		return new HostRoutePredicateFactory();
	}

	@Bean
	public MethodRoutePredicateFactory methodRoutePredicateFactory() {
		return new MethodRoutePredicateFactory();
	}

	@Bean
	public PathRoutePredicateFactory pathRoutePredicateFactory() {
		return new PathRoutePredicateFactory();
	}

	@Bean
	public QueryRoutePredicateFactory queryRoutePredicateFactory() {
		return new QueryRoutePredicateFactory();
	}

	@Bean
	public ReadBodyPredicateFactory readBodyPredicateFactory() {
		return new ReadBodyPredicateFactory();
	}

	@Bean
	public RemoteAddrRoutePredicateFactory remoteAddrRoutePredicateFactory() {
		return new RemoteAddrRoutePredicateFactory();
	}

	@Bean
	@DependsOn("weightCalculatorWebFilter")
	public WeightRoutePredicateFactory weightRoutePredicateFactory() {
		return new WeightRoutePredicateFactory();
	}

	@Bean
	public CloudFoundryRouteServiceRoutePredicateFactory cloudFoundryRouteServiceRoutePredicateFactory() {
		return new CloudFoundryRouteServiceRoutePredicateFactory();
	}

	// GatewayFilter Factory beans

	/**
	 * gateway 过滤器工厂
	 *
	 * @return
	 */
	@Bean
	public AddRequestHeaderGatewayFilterFactory addRequestHeaderGatewayFilterFactory() {
		return new AddRequestHeaderGatewayFilterFactory();
	}

	@Bean
	public AddRequestParameterGatewayFilterFactory addRequestParameterGatewayFilterFactory() {
		return new AddRequestParameterGatewayFilterFactory();
	}

	@Bean
	public AddResponseHeaderGatewayFilterFactory addResponseHeaderGatewayFilterFactory() {
		return new AddResponseHeaderGatewayFilterFactory();
	}

	@Configuration
	@ConditionalOnClass({HystrixObservableCommand.class, RxReactiveStreams.class})
	protected static class HystrixConfiguration {
		@Bean
		public HystrixGatewayFilterFactory hystrixGatewayFilterFactory(ObjectProvider<DispatcherHandler> dispatcherHandler) {
			return new HystrixGatewayFilterFactory(dispatcherHandler);
		}
	}

	@Bean
	public DedupeResponseHeaderGatewayFilterFactory dedupeResponseHeaderGatewayFilterFactory() {
		return new DedupeResponseHeaderGatewayFilterFactory();
	}

	@Bean
	public ModifyRequestBodyGatewayFilterFactory modifyRequestBodyGatewayFilterFactory(ServerCodecConfigurer codecConfigurer) {
		return new ModifyRequestBodyGatewayFilterFactory(codecConfigurer);
	}

	@Bean
	public ModifyResponseBodyGatewayFilterFactory modifyResponseBodyGatewayFilterFactory(ServerCodecConfigurer codecConfigurer) {
		return new ModifyResponseBodyGatewayFilterFactory(codecConfigurer);
	}

	@Bean
	public PrefixPathGatewayFilterFactory prefixPathGatewayFilterFactory() {
		return new PrefixPathGatewayFilterFactory();
	}

	@Bean
	public PreserveHostHeaderGatewayFilterFactory preserveHostHeaderGatewayFilterFactory() {
		return new PreserveHostHeaderGatewayFilterFactory();
	}

	@Bean
	public RedirectToGatewayFilterFactory redirectToGatewayFilterFactory() {
		return new RedirectToGatewayFilterFactory();
	}

	@Bean
	public RemoveRequestHeaderGatewayFilterFactory removeRequestHeaderGatewayFilterFactory() {
		return new RemoveRequestHeaderGatewayFilterFactory();
	}

	@Bean
	public RemoveResponseHeaderGatewayFilterFactory removeResponseHeaderGatewayFilterFactory() {
		return new RemoveResponseHeaderGatewayFilterFactory();
	}

	@Bean(name = PrincipalNameKeyResolver.BEAN_NAME)
	@ConditionalOnBean(RateLimiter.class)
	@ConditionalOnMissingBean(KeyResolver.class)
	public PrincipalNameKeyResolver principalNameKeyResolver() {
		return new PrincipalNameKeyResolver();
	}

	@Bean
	@ConditionalOnBean({RateLimiter.class, KeyResolver.class})
	public RequestRateLimiterGatewayFilterFactory requestRateLimiterGatewayFilterFactory(RateLimiter rateLimiter, KeyResolver resolver) {
		return new RequestRateLimiterGatewayFilterFactory(rateLimiter, resolver);
	}

	@Bean
	public RewritePathGatewayFilterFactory rewritePathGatewayFilterFactory() {
		return new RewritePathGatewayFilterFactory();
	}

	@Bean
	public RetryGatewayFilterFactory retryGatewayFilterFactory() {
		return new RetryGatewayFilterFactory();
	}

	@Bean
	public SetPathGatewayFilterFactory setPathGatewayFilterFactory() {
		return new SetPathGatewayFilterFactory();
	}

	@Bean
	public SecureHeadersGatewayFilterFactory secureHeadersGatewayFilterFactory(SecureHeadersProperties properties) {
		return new SecureHeadersGatewayFilterFactory(properties);
	}

	@Bean
	public SetRequestHeaderGatewayFilterFactory setRequestHeaderGatewayFilterFactory() {
		return new SetRequestHeaderGatewayFilterFactory();
	}

	@Bean
	public SetResponseHeaderGatewayFilterFactory setResponseHeaderGatewayFilterFactory() {
		return new SetResponseHeaderGatewayFilterFactory();
	}

	@Bean
	public RewriteResponseHeaderGatewayFilterFactory rewriteResponseHeaderGatewayFilterFactory() {
		return new RewriteResponseHeaderGatewayFilterFactory();
	}

	@Bean
	public SetStatusGatewayFilterFactory setStatusGatewayFilterFactory() {
		return new SetStatusGatewayFilterFactory();
	}

	@Bean
	public SaveSessionGatewayFilterFactory saveSessionGatewayFilterFactory() {
		return new SaveSessionGatewayFilterFactory();
	}

	@Bean
	public StripPrefixGatewayFilterFactory stripPrefixGatewayFilterFactory() {
		return new StripPrefixGatewayFilterFactory();
	}

	@Bean
	public RequestHeaderToRequestUriGatewayFilterFactory requestHeaderToRequestUriGatewayFilterFactory() {
		return new RequestHeaderToRequestUriGatewayFilterFactory();
	}

	/**
	 * 初始化 GatewayWebfluxEndpoint  提供管理网关的 HTTP API
	 */
	@Configuration
	@ConditionalOnClass(Health.class)
	protected static class GatewayActuatorConfiguration {

		@Bean
		@ConditionalOnEnabledEndpoint
		public GatewayControllerEndpoint gatewayControllerEndpoint(RouteDefinitionLocator routeDefinitionLocator, List<GlobalFilter> globalFilters,
																   List<GatewayFilterFactory> GatewayFilters, RouteDefinitionWriter routeDefinitionWriter,
																   RouteLocator routeLocator) {
			return new GatewayControllerEndpoint(routeDefinitionLocator, globalFilters, GatewayFilters, routeDefinitionWriter, routeLocator);
		}
	}

}

