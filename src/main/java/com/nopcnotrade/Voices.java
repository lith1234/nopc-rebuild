package com.nopcnotrade;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import java.util.HashMap;
import java.util.Map;

/** One sound event per default dialogue line: "<key>_<number>", e.g. emerald_3. */
public class Voices {
    static final Map<String, SoundEvent> MAP = new HashMap<>();

    public static void register() {
        for (var e : Talk.DEFAULTS.entrySet())
            for (int i = 0; i < e.getValue().size(); i++) {
                Identifier id = Identifier.fromNamespaceAndPath(NoPcNoTrade.MODID, e.getKey() + "_" + (i + 1));
                MAP.put(e.getKey() + "|" + e.getValue().get(i),
                        Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id)));
            }
    }

    /** Voice clip for this exact default line, or null (edited/custom lines have no clip). */
    public static SoundEvent find(String key, String text) { return MAP.get(key + "|" + text); }
}
