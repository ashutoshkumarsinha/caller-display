package com.example.enrollment;

import com.example.enrollment.api.PushTokenResource;
import com.example.enrollment.config.EnrollmentConfig;
import com.example.enrollment.metrics.EnrollmentMetrics;
import com.example.enrollment.security.EnrollmentAuthFilter;
import com.example.enrollment.service.EnrollmentService;
import com.example.enrollment.spml.SpmlClient;
import com.example.enrollment.spml.SpmlClientFactory;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import java.util.HashSet;
import java.util.Set;

/**
 * JAX-RS application root for push token enrollment.
 */
@ApplicationPath("/")
public class EnrollmentApplication extends Application {

    private final EnrollmentConfig config;
    private final EnrollmentService service;
    private final EnrollmentMetrics metrics;

    public EnrollmentApplication() {
        config = EnrollmentConfig.fromEnvironment();
        metrics = new EnrollmentMetrics();
        SpmlClient spml = SpmlClientFactory.create(config);
        service = new EnrollmentService(config, spml, metrics);
    }

    @Override
    public Set<Object> getSingletons() {
        Set<Object> beans = new HashSet<>();
        beans.add(new PushTokenResource(service, config));
        beans.add(new EnrollmentAuthFilter(config));
        return beans;
    }
}
