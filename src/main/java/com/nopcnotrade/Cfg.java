package com.nopcnotrade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

 /** JSON config at config/nopcnotrade.json. Edit and restart. Missing dialogue keys are re-added automatically. */
public class Cfg {
    public static class Data {
        public String targetUsername = "PCGuy";
        public int radius = 24, maxCrowd = 12, crowdSeconds = 25;
        /** Each line may be said at most this many times per crowd/protest. */
        public int maxTimesPerLine = 2;
        /** Minimum pause between ambient recognition lines. */
        public int secondsBetweenCrowdLines = 4;
        public Map<String, List<String>> dialogue = new LinkedHashMap<>();
    }
    public static Data d = new Data();

    public static void load() {
        Path f = FabricLoader.getInstance().getConfigDir().resolve("nopcnotrade.json");
        Gson g = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (Files.exists(f)) {
                Data x = g.fromJson(Files.readString(f), Data.class);
                if (x != null) d = x;
            }
        } catch (Exception ex) {
            System.err.println("[nopcnotrade] bad config, using defaults: " + ex);
        }
        if (d.dialogue == null) d.dialogue = new LinkedHashMap<>();
        for (var e : Talk.DEFAULTS.entrySet()) d.dialogue.putIfAbsent(e.getKey(), new ArrayList<>(e.getValue()));
        d.radius = Math.max(8, Math.min(64, d.radius));
        d.maxCrowd = Math.max(1, Math.min(40, d.maxCrowd));
        d.crowdSeconds = Math.max(5, d.crowdSeconds);
        d.secondsBetweenCrowdLines = Math.max(2, Math.min(30, d.secondsBetweenCrowdLines));
        d.maxTimesPerLine = Math.max(1, d.maxTimesPerLine);
        try { Files.writeString(f, g.toJson(d)); } catch (Exception ignored) {}
    }
}
