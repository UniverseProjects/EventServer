package com.universeprojects.eventserver;

public class Config {
    public static String getString(String key, String defaultValue) {
        String value = System.getProperty(key);
        if(value != null) return value;
        value = System.getenv(key);
        if(value != null) return value;
        return defaultValue;
    }

    public static Integer getInteger(String key, Integer defaultValue) {
        final String string = Config.getString(key, null);
        if(string == null) {
            return defaultValue;
        }
        return Integer.parseInt(string);
    }

    public static int getInt(String key, int defaultValue) {
        final String string = Config.getString(key, null);
        if(string == null) {
            return defaultValue;
        }
        return Integer.parseInt(string);
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        final String string = Config.getString(key, null);
        if(string == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(string);
    }

    public static <T extends Enum<T>> T getEnum(String key, Class<T> enumClass, T defaultValue) {
        final String string = Config.getString(key, null);
        if(string == null) {
            return defaultValue;
        }
        return Enum.valueOf(enumClass, string);
    }
}
