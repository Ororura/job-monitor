package com.ororura.jobmonitor.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.UUID;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class WebConfiguration implements WebMvcConfigurer {
  static String csrf(HttpServletRequest request) {
    HttpSession session = request.getSession();
    synchronized (session) {
      String token = (String) session.getAttribute("webCsrf");
      if (token == null) {
        token = UUID.randomUUID().toString();
        session.setAttribute("webCsrf", token);
      }
      return token;
    }
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry
        .addInterceptor(
            new HandlerInterceptor() {
              @Override
              public boolean preHandle(
                  HttpServletRequest request, HttpServletResponse response, Object handler) {
                if ("POST".equals(request.getMethod())
                    && !csrf(request).equals(request.getParameter("csrf"))) {
                  throw new WebForbiddenException();
                }
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Referrer-Policy", "no-referrer");
                response.setHeader("Cache-Control", "no-store");
                response.addHeader("Vary", "HX-Request");
                return true;
              }
            })
        .addPathPatterns("/", "/searches/**", "/vacancies/**", "/collections/**", "/settings");
  }
}
