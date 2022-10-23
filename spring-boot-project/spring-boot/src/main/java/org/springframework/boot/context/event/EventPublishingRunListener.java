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

package org.springframework.boot.context.event;

import java.time.Duration;
import java.util.EventObject;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.ConfigurableBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ErrorHandler;

/**
 * {@link SpringApplicationRunListener} to publish {@link SpringApplicationEvent}s.
 * <p>
 * Uses an internal {@link ApplicationEventMulticaster} for the events that are fired
 * before the context is actually refreshed.
 *
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Artsiom Yudovin
 * @author Brian Clozel
 * @author Chris Bono
 * @since 1.0.0
 */
public class EventPublishingRunListener implements SpringApplicationRunListener, Ordered {

	private final SpringApplication application;

	private final String[] args;

	private final SimpleApplicationEventMulticaster initialMulticaster;

	public EventPublishingRunListener(SpringApplication application, String[] args) {
		this.application = application;
		this.args = args;
		this.initialMulticaster = new SimpleApplicationEventMulticaster();
		for (ApplicationListener<?> listener : application.getListeners()) {
			// 初始化广播器
			this.initialMulticaster.addApplicationListener(listener);
		}
	}

	@Override
	public int getOrder() {
		return 0;
	}

	@Override
	public void starting(ConfigurableBootstrapContext bootstrapContext) {
		/**
		 * 广播器 initialMulticaster 这个类的作用就是
		 * multicastEvent(): 将给定的应用程序事件多播到适当的侦听器,其为 Spring 的方法
		 *
		 * 创建 ApplicationStartingEvent 事件对象,事件对象的构建方法中,将 SpringApplication 设置 source 属性
		 * source: 事件最初发生的对象
		 * @see EventObject#source
		 *
		 * 这个类工作方式有点抽象
		 *  1、首先他会广播一个事件
		 *  	对应代码 for (ApplicationListener<?> listener : getApplicationListeners(event, type)) {
		 *  	getApplicationListeners(event, type) 干了两件事件,首先传了两个参数
		 * 		这两个参数就是事件类型, 意思告诉所有的监听器现在有了一个 type 类型的 event, 你们感兴趣不?
		 * 	2、告诉所有的监听器
		 *		getApplicationListeners 告诉所有的监听器(遍历所有的监听器)
		 * 		然后监听器会接受到这个事件,继而监听器会判断这个事件自己是否感兴趣
		 * 		关键监听器如何知道自己是否感兴趣? Spring 做的比较复杂
		 * 		主要有两个步骤来确定:
		 * 			第一个步骤: 两个方法确定
		 *  			smartListener.supportsEventType(eventType)
		 *  			smartListener.supportsSourceType(sourceType)
		 * 				上面两个方法可以简单理解通过传入一个事件类型返回一个 boolean
		 * 				任意一个返回 false 表示这个监听器对 eventType 的事件不敢兴趣
		 * 				如果感兴趣会被 add 到一个 List 当中,再后续的代码中依次执行方法调用
		 * 			第二个步骤: 在监听器回调的时候,还是可以进行事件类型判断的
		 *				如果事件类型不感兴趣上面都不执行就可以
		 * 	3、获得所有对这个事件感兴趣的监听器,遍历执行其 onApplicationEvent() 方法,处理监听器对应事件
		 *		这里的代码传入了一个 ApplicationstartingEvent 的事件过去
		 * 		那么在 SpringBoot 当中定义的11个监听器哪些监听器对这个事件感兴趣呢?
		 * 		或者换句话说哪些监听器订阅了这个事件呢?
		 *  4、initialMulticaster 可以看到是 simpleApplicationEventMulticaster 类型的对象
		 *		主要两个方法,一个是广播事件,一个执行 listener的onApplicationEvent() 方法
		 *
		 * 整个判断及执行都在下边的方法执行
		 * @see SimpleApplicationEventMulticaster#multicastEvent(org.springframework.context.ApplicationEvent)
		 */
		this.initialMulticaster.multicastEvent(
				new ApplicationStartingEvent(bootstrapContext, this.application, this.args)
		);
	}

	@Override
	public void environmentPrepared(ConfigurableBootstrapContext bootstrapContext, ConfigurableEnvironment environment) {
		this.initialMulticaster.multicastEvent(
				new ApplicationEnvironmentPreparedEvent(bootstrapContext, this.application, this.args, environment)
		);
	}

	@Override
	public void contextPrepared(ConfigurableApplicationContext context) {
		this.initialMulticaster.multicastEvent(
				new ApplicationContextInitializedEvent(this.application, this.args, context)
		);
	}

	@Override
	public void contextLoaded(ConfigurableApplicationContext context) {
		for (ApplicationListener<?> listener : this.application.getListeners()) {
			if (listener instanceof ApplicationContextAware) {
				((ApplicationContextAware) listener).setApplicationContext(context);
			}
			context.addApplicationListener(listener);
		}
		this.initialMulticaster.multicastEvent(
				new ApplicationPreparedEvent(this.application, this.args, context)
		);
	}

	@Override
	public void started(ConfigurableApplicationContext context, Duration timeTaken) {
		context.publishEvent(new ApplicationStartedEvent(this.application, this.args, context, timeTaken));
		AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
	}

	@Override
	public void ready(ConfigurableApplicationContext context, Duration timeTaken) {
		context.publishEvent(new ApplicationReadyEvent(this.application, this.args, context, timeTaken));
		AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
	}

	@Override
	public void failed(ConfigurableApplicationContext context, Throwable exception) {
		ApplicationFailedEvent event = new ApplicationFailedEvent(this.application, this.args, context, exception);
		if (context != null && context.isActive()) {
			// Listeners have been registered to the application context so we should
			// use it at this point if we can
			context.publishEvent(event);
		}
		else {
			// An inactive context may not have a multicaster so we use our multicaster to
			// call all of the context's listeners instead
			if (context instanceof AbstractApplicationContext) {
				for (ApplicationListener<?> listener : ((AbstractApplicationContext) context)
						.getApplicationListeners()) {
					this.initialMulticaster.addApplicationListener(listener);
				}
			}
			this.initialMulticaster.setErrorHandler(new LoggingErrorHandler());
			this.initialMulticaster.multicastEvent(event);
		}
	}

	private static class LoggingErrorHandler implements ErrorHandler {

		private static final Log logger = LogFactory.getLog(EventPublishingRunListener.class);

		@Override
		public void handleError(Throwable throwable) {
			logger.warn("Error calling ApplicationEventListener", throwable);
		}

	}

}
