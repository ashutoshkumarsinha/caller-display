package com.example.sip.observability;

import com.example.sip.model.RingingEvent;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

/**
 * Structured logging correlation fields (spec §8.3).
 *
 * <p>Uses a {@link ThreadLocal} as the source of truth and mirrors into SLF4J {@link MDC}
 * when a real MDC adapter is present.
 */
public final class CallMdc {

    public static final String CALL_ID = "callId";
    public static final String EVENT_ID = "eventId";
    public static final String CALLEE = "callee";
    public static final String CALLER = "caller";
    public static final String REALM = "realm";
    public static final String PLATFORM = "platform";

    private static final ThreadLocal<Map<String, String>> CONTEXT = new ThreadLocal<>();

    private CallMdc() {
    }

    public static void put(RingingEvent event) {
        Map<String, String> map = new HashMap<>();
        map.put(CALL_ID, event.callId());
        map.put(EVENT_ID, event.eventId());
        map.put(CALLEE, event.calleeMsisdn());
        map.put(CALLER, event.callerDisplay());
        map.put(REALM, event.destinationRealm());
        CONTEXT.set(map);
        mirrorToSlf4j(map);
    }

    public static void putPlatform(String platform) {
        Map<String, String> map = CONTEXT.get();
        if (map == null) {
            map = new HashMap<>();
            CONTEXT.set(map);
        }
        if (platform != null) {
            map.put(PLATFORM, platform);
            MDC.put(PLATFORM, platform);
        }
    }

    public static String get(String key) {
        Map<String, String> map = CONTEXT.get();
        if (map != null && map.containsKey(key)) {
            return map.get(key);
        }
        return MDC.get(key);
    }

    public static Map<String, String> copy() {
        Map<String, String> map = CONTEXT.get();
        if (map != null) {
            return new HashMap<>(map);
        }
        Map<String, String> fromMdc = MDC.getCopyOfContextMap();
        return fromMdc == null ? Map.of() : new HashMap<>(fromMdc);
    }

    public static void restore(Map<String, String> context) {
        clear();
        if (context == null || context.isEmpty()) {
            return;
        }
        CONTEXT.set(new HashMap<>(context));
        mirrorToSlf4j(context);
    }

    public static void clear() {
        CONTEXT.remove();
        MDC.remove(CALL_ID);
        MDC.remove(EVENT_ID);
        MDC.remove(CALLEE);
        MDC.remove(CALLER);
        MDC.remove(REALM);
        MDC.remove(PLATFORM);
    }

    private static void mirrorToSlf4j(Map<String, String> map) {
        map.forEach(MDC::put);
    }
}
