/*
 * Copyright 2018-2024 Alibaba Group Holding Ltd. and contributors.
 * Licensed under the Apache License, Version 2.0.
 *
 * Modified for SlayTheAmethyst: factory method that constructs
 * ArthasBootstrap without Netty port binding.
 */
package com.taobao.arthas.core.server;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public final class ArthasBootstrapCompat {

    private ArthasBootstrapCompat() {}

    /**
     * Create a fully-initialised ArthasBootstrap via its original constructor.
     * When no port configuration is present in {@code featureMap}, the
     * constructor's {@code bind()} skips Netty server creation and only
     * performs {@code shellServer.listen()} + {@code SpyAPI.init()}.
     *
     * @return the singleton ArthasBootstrap (also accessible via
     *         {@code ArthasBootstrap.getInstance()})
     */
    public static ArthasBootstrap createWithoutNetty(
            Instrumentation inst,
            Map<String, String> featureMap) throws Throwable {

        Map<String, String> decorated = new HashMap<String, String>();
        for (Map.Entry<String, String> e : featureMap.entrySet()) {
            decorated.put("arthas." + e.getKey(), e.getValue());
        }

        Constructor<?> ctor = ArthasBootstrap.class.getDeclaredConstructor(
            Instrumentation.class, Map.class);
        ctor.setAccessible(true);
        ArthasBootstrap bs = (ArthasBootstrap) ctor.newInstance(
            inst, decorated);

        Field singleton = ArthasBootstrap.class.getDeclaredField(
            "arthasBootstrap");
        singleton.setAccessible(true);
        singleton.set(null, bs);

        log("created: isBind=" + bs.isBind()
            + " shellServer=" + bs.getShellServer());
        return bs;
    }

    static void log(String msg) {
        try {
            PrintWriter w = new PrintWriter(new FileWriter(
                "/data/data/io.stamethyst/files/arthas-bridge.log", true));
            w.println("[Compat] " + msg);
            w.flush();
            w.close();
        } catch (Exception ignored) {}
    }
}
