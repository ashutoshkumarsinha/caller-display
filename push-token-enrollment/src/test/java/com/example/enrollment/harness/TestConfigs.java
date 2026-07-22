package com.example.enrollment.harness;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;

import java.util.Map;
import java.util.Properties;

/**
 * Isolated MP Config for tests.
 */
public final class TestConfigs {

    private TestConfigs() {
    }

    public static Config fromMap(Map<String, String> values) {
        Properties props = new Properties();
        props.putAll(values);
        return new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(props, "test-map", 200))
                .build();
    }
}
