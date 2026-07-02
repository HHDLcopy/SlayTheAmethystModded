package com.evacipated.cardcrawl.modthespire;

import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ByteArrayMapClassPath {
    protected final Map<String, Info> classes = new LinkedHashMap<String, Info>();

    public void addClass(String name, URL url, byte[] classfile) {
        classes.put(name, new Info(url, classfile));
    }

    static final class Info {
        final URL url;
        final byte[] classfile;

        Info(URL url, byte[] classfile) {
            this.url = url;
            this.classfile = classfile;
        }
    }
}
