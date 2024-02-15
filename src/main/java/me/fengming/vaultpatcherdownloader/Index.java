package me.fengming.vaultpatcherdownloader;

import java.net.URL;
import java.util.List;

public class Index {
    public List<Mod> mods;
    public String root;

    public static class Mod {
        public String name;
        public String version;
        public String url;
    }
}
