package com.example.sip.harness;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Builds isolated MicroProfile {@link Config} instances for tests (no global ConfigProvider pollution).
 */
public final class TestConfigs {

    private TestConfigs() {
    }

    public static Config fromMap(Map<String, String> values) {
        Properties props = new Properties();
        props.putAll(values);
        return new SmallRyeConfigBuilder()
                .withSources(new PropertiesConfigSource(props, "test-map", 200))
                .build();
    }

    public static Config fromProperties(String propertiesContent) {
        Properties props = new Properties();
        try {
            props.load(new StringReader(propertiesContent));
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
        Map<String, String> map = new HashMap<>();
        props.forEach((k, v) -> map.put(String.valueOf(k), String.valueOf(v)));
        return fromMap(map);
    }
}
