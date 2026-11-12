package com.nopcnotrade;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public class Crowd {
    static final Map<UUID, Long> active = new HashMap<>(), calm = new HashMap<>();
    static final Map<UUID, long[]> strike = new HashMap<>(); // {refusals, lastTick}
    static final Map<UUID, Vec3> commandedSlots = new HashMap<>();
    static final Map<UUID, Long> lastPathUpdate = new HashMap<>();
    static final Map<String, Integer> used = new HashMap<>(); // "key|line" -> times said this wave

    // All dialogue goes through this one queue. Only one voice line can start at a time.
    private record QueuedSpeech(Villager speaker, String key, String text) {}
    static final Deque<QueuedSpeech> speechQueue = new ArrayDeque<>();
    static long nextSpeechTick = 0;
    static long lastCrowdLine = -10000;

    // Short-lived floating emotes: a vanilla Display entity (item icon or text) that follows the
    // speaking villager's head, pops in, rises slowly and shrinks away. Works with vanilla clients.
    // If the display entity cannot be spawned, falls back to the old particle burst.
    static final int EMOTE_LIFE = 44;
    private static final class ActiveEmote {
        final Villager villager;
        final ParticleOptions fallback;
        final UUID displayId;
        final String look;      // "item" or "text"
        final String data;      // NBT fragment for the display
        final float scale;
        final long startedAt, expiresAt;
        long nextPulse;
        int phase = 0;          // 0 = spawned, 1 = popped in, 2 = shrinking

        ActiveEmote(Villager villager, ParticleOptions fallback, UUID displayId, String look, String data,
                    float scale, long startTick) {
            this.villager = villager;
            this.fallback = fallback;
            this.displayId = displayId;
            this.look = look;
            this.data = data;
            this.scale = scale;
            this.startedAt = startTick;
            this.expiresAt = startTick + EMOTE_LIFE;
            this.nextPulse = startTick;
        }
    }
    static final Map<UUID, ActiveEmote> emotes = new HashMap<>();

    static final ChatFormatting[] COLORS = {ChatFormatting.GREEN, ChatFormatting.AQUA, ChatFormatting.YELLOW, ChatFormatting.GOLD,
            ChatFormatting.LIGHT_PURPLE, ChatFormatting.RED, ChatFormatting.BLUE, ChatFormatting.DARK_AQUA};

    static boolean isTarget(ServerPlayer p) {
        return p.getName().getString().equalsIgnoreCase(Cfg.d.targetUsername.trim());
    }

    /** Runs every 0.5s. Villager movement is controlled only by the vanilla Brain. */
    public static void tick(MinecraftServer s) {
        int tc = s.getTickCount();
        tickEmotes();
        if (tc % 10 != 0) return;

        String name = Cfg.d.targetUsername.trim();
        if (name.isEmpty()) return;
        ServerPlayer p = null;
        for (ServerPlayer sp : s.getPlayerList().getPlayers())
            if (isTarget(sp)) { p = sp; break; }

        if (p == null || p.isSpectator() || !(p.level() instanceof ServerLevel lv)) {
            if (!active.isEmpty() || !speechQueue.isEmpty()) {
                active.clear();
                calm.clear();
                commandedSlots.clear();
                lastPathUpdate.clear();
                speechQueue.clear();
                nextSpeechTick = tc;
            }
            return;
        }

        processSpeech(tc);

        long now = lv.getGameTime();
        boolean sec = tc % 20 == 0;
        if (sec) {
            for (var it = active.entrySet().iterator(); it.hasNext();) {
                var en = it.next();
                if (en.getValue() <= now) {
                    UUID id = en.getKey();
                    calm.put(id, now + 600);
                    commandedSlots.remove(id);
                    lastPathUpdate.remove(id);
                    it.remove();
                }
            }
            calm.values().removeIf(t -> t <= now);
        }

        List<Villager> vs = lv.getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(Cfg.d.radius),
                v -> v.isAlive() && !v.isSleeping());
        if (vs.isEmpty()) return;
        final ServerPlayer targetPlayer = p;
        vs.sort(Comparator.comparingDouble(v -> v.distanceToSqr(targetPlayer)));

        if (sec) { // Gradual recruitment, at most 3 new villagers per second.
            boolean fresh = active.isEmpty();
            if (fresh) used.keySet().removeIf(k -> k.startsWith("recognize|"));
            int add = 0;
            for (Villager v : vs) {
                if (active.size() >= Cfg.d.maxCrowd || add >= 3) break;
                if (active.containsKey(v.getUUID()) || calm.containsKey(v.getUUID())) continue;
                active.put(v.getUUID(), now + Cfg.d.crowdSeconds * 20L);
                if (fresh && add == 0) {
                    say(lv, v, "shout");
                    lastCrowdLine = now;
                }
                add++;
            }
        }

        List<Villager> crowd = new ArrayList<>();
        for (Villager v : vs) if (active.containsKey(v.getUUID())) crowd.add(v);

        for (Villager v : crowd) {
            UUID id = v.getUUID();
            int h = id.hashCode();
            double ang = ((h & 0xFFFF) / 65536.0) * Math.PI * 2;
            double rad = 1.8 + ((h >>> 16) & 3) * 0.7;
            Vec3 slot = p.position().add(Math.cos(ang) * rad, 0, Math.sin(ang) * rad);
            double d2 = v.distanceToSqr(slot);
            var brain = v.getBrain();
            brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(p, true));

            if (d2 <= 2.25) {
                // Let the villager stop naturally once it reaches its assigned place.
                brain.eraseMemory(MemoryModuleType.WALK_TARGET);
                commandedSlots.remove(id);
                lastPathUpdate.remove(id);
                if (sec && v.onGround() && lv.random.nextInt(6) == 0) v.getJumpControl().jump();
            } else {
                Vec3 previous = commandedSlots.get(id);
                boolean slotMoved = previous == null || previous.distanceToSqr(slot) > 2.25;
                boolean retryPath = tc - lastPathUpdate.getOrDefault(id, 0L) >= 60;
                if (slotMoved || retryPath) {
                    // Do not call Navigation.moveTo as well: Brain/WalkTarget owns pathfinding.
                    brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(slot, 0.85f, 1));
                    commandedSlots.put(id, slot);
                    lastPathUpdate.put(id, (long) tc);
                }
            }
        }

        // Ambient recognition is deliberately slower and never floods the dialogue queue.
        if (sec && !crowd.isEmpty() && speechQueue.size() < 3
                && now - lastCrowdLine >= Cfg.d.secondsBetweenCrowdLines * 20L) {
            String text = pickLimited(lv, "recognize");
            if (text != null) {
                lastCrowdLine = now;
                sayText(lv, crowd.get(lv.random.nextInt(crowd.size())), "recognize", text);
            }
        }
    }

    /** Starts at most one queued voice line, then waits for its estimated playback time plus a pause. */
    static void processSpeech(int serverTick) {
        if (serverTick < nextSpeechTick) return;
        while (!speechQueue.isEmpty()) {
            QueuedSpeech queued = speechQueue.removeFirst();
            Villager speaker = queued.speaker();
            if (speaker == null || !speaker.isAlive() || !(speaker.level() instanceof ServerLevel lv)) continue;

            playSpeechNow(lv, speaker, queued.key(), queued.text());
            // Conservative estimate: 1.5s base + roughly 0.1s per character + 0.75s silence.
            int durationTicks = Math.max(55, Math.min(150, 45 + queued.text().length() * 2));
            nextSpeechTick = serverTick + durationTicks;
            return;
        }
    }

    static void queueSpeech(Villager speaker, String key, String text) {
        if (speaker == null || !speaker.isAlive() || text == null || text.isBlank()) return;
        // Bound the queue so rapid player interactions cannot create minutes of stale dialogue.
        if (speechQueue.size() >= 40) return;
        speechQueue.addLast(new QueuedSpeech(speaker, key, text));
    }

    static String pickLimited(ServerLevel lv, String key) {
        List<String> pool = new ArrayList<>();
        for (String line : Talk.lines(key))
            if (used.getOrDefault(key + "|" + line, 0) < Cfg.d.maxTimesPerLine) pool.add(line);
        if (pool.isEmpty()) return null;
        String result = pool.get(lv.random.nextInt(pool.size()));
        used.merge(key + "|" + result, 1, Integer::sum);
        return result;
    }

    static String key(ItemStack stack) {
        if (stack.isEmpty()) return "trade";
        Item item = stack.getItem();
        if (item == NoPcNoTrade.TOASTER) return "toaster";
        if (item == Items.EMERALD) return "emerald";
        if (item == Items.FURNACE) return "furnace";
        if (item == Items.DIRT) return "dirt";
        if (item == Items.COAL) return "coal";
        if (item == Items.REDSTONE) return "redstone";
        if (item == Items.IRON_INGOT) return "iron_ingot";
        if (item == Items.DIAMOND) return "diamond";
        return "trade";
    }

    /** Intercepts right-clicks on villagers by the target only. Items are never consumed. */
    public static InteractionResult interact(net.minecraft.world.entity.player.Player pl, net.minecraft.world.level.Level world,
                                               InteractionHand hand, net.minecraft.world.entity.Entity target) {
        if (world.isClientSide() || !(target instanceof Villager villager)
                || !(pl instanceof ServerPlayer player) || !isTarget(player)) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND || !(player.level() instanceof ServerLevel lv))
            return InteractionResult.SUCCESS_SERVER;

        long now = lv.getGameTime();
        long[] state = strike.computeIfAbsent(player.getUUID(), k -> new long[]{0, 0});
        if (now - state[1] > 1200) {
            state[0] = 0;
            used.keySet().removeIf(k -> k.startsWith("chant|"));
        }
        if (now - state[1] < 8) return InteractionResult.SUCCESS_SERVER;
        state[0]++;
        state[1] = now;

        refuse(lv, villager);
        say(lv, villager, key(player.getMainHandItem()));

        int chanters = (int) Math.min(2, (state[0] - 1) / 2);
        if (chanters > 0) {
            List<Villager> nearby = lv.getEntitiesOfClass(Villager.class, villager.getBoundingBox().inflate(16),
                    other -> other != villager && other.isAlive());
            Collections.shuffle(nearby);
            for (int i = 0; i < Math.min(chanters, nearby.size()); i++) {
                Villager chanter = nearby.get(i);
                refuse(lv, chanter);
                chanter.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(player, true));
                String chant = pickLimited(lv, "chant");
                if (chant == null) break;
                sayText(lv, chanter, "chant", chant);
            }
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    static void refuse(ServerLevel lv, Villager villager) {
        villager.setUnhappyCounter(40);
        lv.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL,
                1f, 0.9f + lv.random.nextFloat() * 0.2f);
    }

    static void say(ServerLevel lv, Villager villager, String key) {
        sayText(lv, villager, key, Talk.pick(key, lv.random));
    }

    static void sayText(ServerLevel lv, Villager villager, String key, String text) {
        queueSpeech(villager, key, text);
    }

    private static ParticleOptions fallbackParticle(String key) {
        return switch (key) {
            case "shout", "trade" -> ParticleTypes.ANGRY_VILLAGER;
            case "recognize" -> ParticleTypes.NOTE;
            case "chant" -> ParticleTypes.CRIT;
            case "emerald" -> new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.EMERALD));
            case "furnace" -> new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.FURNACE));
            case "toaster" -> new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(NoPcNoTrade.TOASTER));
            case "dirt" -> new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.DIRT));
            case "coal" -> new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.COAL));
            case "redstone" -> new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.REDSTONE));
            case "iron_ingot" -> new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.IRON_INGOT));
            case "diamond" -> new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.DIAMOND));
            default -> ParticleTypes.HAPPY_VILLAGER;
        };
    }

    private static String itemData(String id) {
        return "item:{id:\"" + id + "\",count:1},item_display:\"gui\"";
    }

    private static String textData(String text, String color) {
        return "text:{text:\"" + text + "\",color:\"" + color + "\",bold:true},background:0,shadow:1b,alignment:\"center\"";
    }

    private static String tf(float scale, float y) {
        return "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,"
                + y + "f,0f],scale:[" + scale + "f," + scale + "f," + scale + "f]}";
    }

    private static String intArray(UUID id) {
        long m = id.getMostSignificantBits(), l = id.getLeastSignificantBits();
        return "[I;" + (int) (m >> 32) + "," + (int) m + "," + (int) (l >> 32) + "," + (int) l + "]";
    }

    private static void runCommand(ServerLevel lv, String command) {
        try {
            var server = lv.getServer();
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack().withLevel(lv).withSuppressedOutput(), command);
        } catch (Exception ignored) {
            // Emotes are cosmetic: never let them break the game.
        }
    }

    static void startEmote(ServerLevel lv, Villager villager, String key) {
        // Only one emote per villager at a time.
        ActiveEmote old = emotes.remove(villager.getUUID());
        if (old != null) {
            Entity e = lv.getEntity(old.displayId);
            if (e != null) e.discard();
        }

        String look, data;
        float scale;
        switch (key) {
            case "shout" -> { look = "text"; data = textData("!!", "red"); scale = 1.5f; }
            case "trade" -> { look = "text"; data = textData("NO PC!", "red"); scale = 1.0f; }
            case "recognize" -> { look = "text"; data = textData("\u266A", "aqua"); scale = 1.8f; }
            case "chant" -> { look = "text"; data = textData("RTX", "green"); scale = 1.2f; }
            case "emerald" -> { look = "item"; data = itemData("minecraft:emerald"); scale = 0.7f; }
            case "furnace" -> { look = "item"; data = itemData("minecraft:furnace"); scale = 0.7f; }
            case "toaster" -> { look = "item"; data = itemData("nopcnotrade:toaster"); scale = 0.8f; }
            case "dirt" -> { look = "item"; data = itemData("minecraft:dirt"); scale = 0.7f; }
            case "coal" -> { look = "item"; data = itemData("minecraft:coal"); scale = 0.7f; }
            case "redstone" -> { look = "item"; data = itemData("minecraft:redstone"); scale = 0.7f; }
            case "iron_ingot" -> { look = "item"; data = itemData("minecraft:iron_ingot"); scale = 0.7f; }
            case "diamond" -> { look = "item"; data = itemData("minecraft:diamond"); scale = 0.7f; }
            default -> { look = "text"; data = textData("!", "yellow"); scale = 1.5f; }
        }

        UUID displayId = UUID.randomUUID();
        long now = lv.getGameTime();
        double x = villager.getX();
        double y = villager.getY() + villager.getBbHeight() + 0.45;
        double z = villager.getZ();
        String type = look.equals("item") ? "minecraft:item_display" : "minecraft:text_display";
        String nbt = "{UUID:" + intArray(displayId) + ",Tags:[\"nopc_emote\"],billboard:\"center\","
                + "teleport_duration:3,brightness:{sky:15,block:15}," + data + "," + tf(0.01f, 0f) + "}";
        runCommand(lv, String.format(Locale.ROOT, "summon %s %.3f %.3f %.3f %s", type, x, y, z, nbt));

        emotes.put(villager.getUUID(),
                new ActiveEmote(villager, fallbackParticle(key), displayId, look, data, scale, now));
    }

    /** Keeps each emote glued above its villager's head and plays pop-in / float / shrink animations. */
    static void tickEmotes() {
        for (var it = emotes.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            ActiveEmote emote = entry.getValue();
            Villager villager = emote.villager;
            if (!villager.isAlive() || !(villager.level() instanceof ServerLevel lv)) {
                it.remove();
                continue;
            }
            long now = lv.getGameTime();
            Entity display = lv.getEntity(emote.displayId);

            if (now >= emote.expiresAt) {
                if (display != null) display.discard();
                it.remove();
                continue;
            }

            double x = villager.getX();
            double y = villager.getY() + villager.getBbHeight() + 0.45;
            double z = villager.getZ();

            if (display == null) {
                // The display entity did not appear: use the old particle burst instead.
                if (now - emote.startedAt >= 2 && now >= emote.nextPulse) {
                    lv.sendParticles(emote.fallback, x, y + 0.03, z, 2, 0.09, 0.08, 0.09, 0.015);
                    emote.nextPulse = now + 3;
                }
                continue;
            }

            display.setPos(x, y, z);
            String sel = emote.displayId.toString();

            if (emote.phase == 0 && now - emote.startedAt >= 1) {
                // Pop in and float up.
                runCommand(lv, "data merge entity " + sel + " {start_interpolation:0,interpolation_duration:6,"
                        + tf(emote.scale, 0.18f) + "}");
                emote.phase = 1;
            } else if (emote.phase == 1 && emote.expiresAt - now <= 6) {
                // Shrink away.
                runCommand(lv, "data merge entity " + sel + " {start_interpolation:0,interpolation_duration:6,"
                        + tf(0.01f, 0.35f) + "}");
                emote.phase = 2;
            }
        }
    }

    /** Removes any leftover emote display entities (e.g. when the server stops mid-emote). */
    public static void cleanup(MinecraftServer s) {
        emotes.clear();
        try {
            s.getCommands().performPrefixedCommand(
                    s.createCommandSourceStack().withSuppressedOutput(), "kill @e[tag=nopc_emote]");
        } catch (Exception ignored) {
        }
    }

    static void playSpeechNow(ServerLevel lv, Villager villager, String key, String text) {
        startEmote(lv, villager, key);
        int n = Math.floorMod(villager.getUUID().hashCode(), 100);
        MutableComponent message = Component.literal("[Villager #" + n + "]: ")
                .withStyle(COLORS[n % COLORS.length], ChatFormatting.BOLD)
                .append(Component.literal(text).withStyle(ChatFormatting.WHITE));
        for (ServerPlayer sp : lv.players())
            if (sp.distanceToSqr(villager) < 1024) sp.sendSystemMessage(message);

        SoundEvent voice = Voices.find(key, text);
        if (voice != null) lv.playSound(null, villager.blockPosition(), voice, SoundSource.VOICE, 2f, 1f);
        else lv.playSound(null, villager.blockPosition(), SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL,
                1f, 0.8f + lv.random.nextFloat() * 0.4f);
    }
}
