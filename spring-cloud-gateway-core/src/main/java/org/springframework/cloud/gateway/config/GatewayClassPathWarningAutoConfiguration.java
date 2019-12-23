package org.springframework.cloud.gateway.config;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Configuration;

/**
 * 用于检查项目是否正确导入 spring-boot-starter-webflux 依赖
 */
@Configuration
@AutoConfigureBefore(GatewayAutoConfiguration.class)
public class GatewayClassPathWarningAutoConfiguration {

	private static final Log log = LogFactory.getLog(GatewayClassPathWarningAutoConfiguration.class);
	private static final String BORDER = "\n\n**********************************************************\n\n";

	/**
	 * 检查系统配置了spring-boot-starter-web，如果有，提示删除
	 */
	@Configuration
	@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
	protected static class SpringMvcFoundOnClasspathConfiguration {

		public SpringMvcFoundOnClasspathConfiguration() {
			log.warn(BORDER + "Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway at this time. " +
					"Please remove spring-boot-starter-web dependency." + BORDER);
		}

	}

	/**
	 * 检查系统中是否配置了spring-boot-starter-webflux，没有的话提示
	 */
	@Configuration
	@ConditionalOnMissingClass("org.springframework.web.reactive.DispatcherHandler")
	protected static class WebfluxMissingFromClasspathConfiguration {

		public WebfluxMissingFromClasspathConfiguration() {
			log.warn(BORDER + "Spring Webflux is missing from the classpath, which is required for Spring Cloud Gateway at this time. " +
					"Please add spring-boot-starter-webflux dependency." + BORDER);
		}

	}
}
