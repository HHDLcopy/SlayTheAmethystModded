package io.stamethyst.compatmod.core;

public final class StartupCacheRuntimeConfig {
    private static final String PATCH_CACHE_CURRENT_PROP = "amethyst.mts.patch_cache.current";

    private StartupCacheRuntimeConfig() {
    }

    public static boolean isPatchCacheCurrent() {
        return readBooleanSystemProperty(PATCH_CACHE_CURRENT_PROP, false);
    }

    public static boolean isCacheFeatureEnabled(String propertyName, boolean defaultValue) {
        return isPatchCacheCurrent() && readBooleanSystemProperty(propertyName, defaultValue);
    }

    public static boolean readBooleanSystemProperty(String key, boolean defaultValue) {
        String configured = System.getProperty(key);
        if (configured == null) {
            return defaultValue;
        }
        configured = configured.trim();
        if (configured.length() == 0) {
            return defaultValue;
        }
        if ("false".equalsIgnoreCase(configured)
            || "0".equals(configured)
            || "off".equalsIgnoreCase(configured)
            || "no".equalsIgnoreCase(configured)) {
            return false;
        }
        if ("true".equalsIgnoreCase(configured)
            || "1".equals(configured)
            || "on".equalsIgnoreCase(configured)
            || "yes".equalsIgnoreCase(configured)) {
            return true;
        }
        return defaultValue;
    }
}
