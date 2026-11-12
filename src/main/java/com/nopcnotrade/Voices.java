package com.nopcnotrade;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** One sound event per default dialogue line: "<key>_<number>", e.g. emerald_3. */
public class Voices {
    static final Map<String, SoundEvent> MAP = new HashMap<>();   // only lines that really have a clip
    static final Map<String, Integer> TICKS = new HashMap<>();    // real clip length in ticks

    public static void register() {
        for (var e : Talk.DEFAULTS.entrySet())
            for (int i = 0; i < e.getValue().size(); i++) {
                String name = e.getKey() + "_" + (i + 1);
                Identifier id = Identifier.fromNamespaceAndPath(NoPcNoTrade.MODID, name);
                SoundEvent ev = Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
                int ticks = clipTicks("/assets/" + NoPcNoTrade.MODID + "/sounds/voice/" + name + ".ogg");
                if (ticks > 0) {
                    MAP.put(e.getKey() + "|" + e.getValue().get(i), ev);
                    TICKS.put(e.getKey() + "|" + e.getValue().get(i), ticks);
                }
            }
        // "DON'T WALK AWAY!" is also used as a shout when the target leaves: reuse the same recording.
        alias("flee", "DON'T WALK AWAY!", "recognize", "DON'T WALK AWAY!");
    }

    static void alias(String key, String text, String fromKey, String fromText) {
        SoundEvent ev = MAP.get(fromKey + "|" + fromText);
        if (ev == null) return;
        MAP.put(key + "|" + text, ev);
        TICKS.put(key + "|" + text, TICKS.get(fromKey + "|" + fromText));
    }

    /** Voice clip for this exact default line, or null (no recording yet, or an edited/custom line). */
    public static SoundEvent find(String key, String text) { return MAP.get(key + "|" + text); }

    /** How long the line takes: the real clip length when there is one, otherwise an estimate from the text. */
    public static int durationTicks(String key, String text) {
        Integer t = TICKS.get(key + "|" + text);
        if (t != null) return t;
        return Math.max(40, Math.min(120, 30 + text.length() * 2));
    }

    /** Length of an .ogg Vorbis file in ticks (20/s), or -1 if the file does not exist / cannot be read. */
    static int clipTicks(String resource) {
        try (InputStream in = Voices.class.getResourceAsStream(resource)) {
            if (in == null) return -1;
            byte[] b = in.readAllBytes();
            int rateOff = -1;
            for (int i = 0; i < Math.min(b.length - 16, 200); i++)
                if (b[i] == 1 && b[i + 1] == 'v' && b[i + 2] == 'o' && b[i + 3] == 'r' && b[i + 4] == 'b'
                        && b[i + 5] == 'i' && b[i + 6] == 's') { rateOff = i + 12; break; }
            if (rateOff < 0) return 60;   // exists but unreadable header: assume 3 seconds
            long rate = le(b, rateOff, 4);
            int last = -1;
            for (int i = b.length - 14; i >= 0; i--)
                if (b[i] == 'O' && b[i + 1] == 'g' && b[i + 2] == 'g' && b[i + 3] == 'S') { last = i; break; }
            if (last < 0 || rate <= 0) return 60;
            long granule = le(b, last + 6, 8);
            return Math.max(10, (int) Math.ceil(granule * 20.0 / rate));
        } catch (Exception ex) {
            return -1;
        }
    }

    private static long le(byte[] b, int off, int n) {
        long r = 0;
        for (int i = n - 1; i >= 0; i--) r = (r << 8) | (b[off + i] & 0xFF);
        return r;
    }
}
