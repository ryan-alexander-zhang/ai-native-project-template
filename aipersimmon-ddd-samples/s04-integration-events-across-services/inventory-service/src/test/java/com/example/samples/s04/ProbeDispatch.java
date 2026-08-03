package com.example.samples.s04;

import com.aipersimmon.ddd.cqrs.Command;
import com.aipersimmon.ddd.cqrs.CommandContext;
import com.aipersimmon.ddd.cqrs.CommandInterceptor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;

@TestConfiguration(proxyBeanMethods = false)
class ProbeDispatch {

  @Bean
  @Order(400)
  CommandInterceptor probe() {
    return new CommandInterceptor() {
      @Override
      public <R> R intercept(Command<R> command, CommandContext context, Invocation<R> invocation) {
        System.out.println("PROBE in  = " + command);
        R result = invocation.proceed();
        System.out.println("PROBE out = " + result);
        return result;
      }

      @Override
      public int order() {
        return 400;
      }
    };
  }
}
