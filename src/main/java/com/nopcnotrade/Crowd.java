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

    // Short-lived animated emotes rendered with vanilla particles above the speaking villager.
    private static final class ActiveEmote {
        final Villager villager;
        final ParticleOptions particle;
        final long expiresAt;
        long nextPulse;

        ActiveEmote(Villager villager, ParticleOptions particle, long startTick) {
            this.villager = villager;
            this.particle = particle;
            this.expiresAt = startTick + 26;
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

    static void startEmote(ServerLevel lv, Villager villager, String key) {
        ParticleOptions particle = switch (key) {
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
        long now = lv.getGameTime();
        emotes.put(villager.getUUID(), new ActiveEmote(villager, particle, now));
    }

    /** Emit a small particle burst every 3 ticks so the emote appears to float above the villager. */
    static void tickEmotes() {
        for (var it = emotes.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            ActiveEmote emote = entry.getValue();
            Villager villager = emote.villager;
            if (!villager.isAlive() || !(villager.level() instanceof ServerLevel lv)
                    || lv.getGameTime() >= emote.expiresAt) {
                it.remove();
                continue;
            }
            long now = lv.getGameTime();
            if (now < emote.nextPulse) continue;
            double x = villager.getX();
            double y = villager.getY() + villager.getBbHeight() + 0.48;
            double z = villager.getZ();
            lv.sendParticles(emote.particle, x, y, z, 2, 0.09, 0.08, 0.09, 0.015);
            emote.nextPulse = now + 3;
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
