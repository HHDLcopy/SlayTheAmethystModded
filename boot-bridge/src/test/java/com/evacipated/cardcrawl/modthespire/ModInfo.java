package com.evacipated.cardcrawl.modthespire;

import java.net.URL;

public final class ModInfo {
    public final String ID;
    public final URL jarURL;

    public ModInfo(String id, URL jarUrl) {
        this.ID = id;
        this.jarURL = jarUrl;
    }
}
