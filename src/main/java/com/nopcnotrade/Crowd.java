package com.nopcnotrade;

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

    // The protest "script": one villager spots the target, calls the others, they gather around him,
    // then they protest one at a time. If the target walks away they chase him and shout after him.
    enum Phase { IDLE, SPOTTED, CALLING, GATHERING, PROTEST }
    static Phase phase = Phase.IDLE;
    static UUID spotter = null;
    static long phaseTick = 0;                 // server tick the current phase started
    static final Set<UUID> arrived = new HashSet<>(), reacted = new HashSet<>();
    static long lastFleeLine = -10000;         // game time
    static int chantTurn = 0;

    // All dialogue goes through this one queue, so only one villager speaks at a time.
    // level 0 = ambient chatter, 1 = story script, 2 = direct reaction to the player (jumps the queue).
    private record QueuedSpeech(Villager speaker, String key, String text, int level,
                                Villager replier, String replyKey, String replyText) {
        QueuedSpeech(Villager speaker, String key, String text, int level) {
            this(speaker, key, text, level, null, null, null);
        }
    }
    static final Deque<QueuedSpeech> speechQueue = new ArrayDeque<>();
    static long nextSpeechTick = 0, speechStartTick = -1000;   // server ticks
    static long lastCrowdLine = -10000;                        // game time
    static final int SPEECH_GAP = 12;                          // silence between two lines (ticks)

    // One floating text display per speaking villager. It follows the villager's head
    // and stays visible for the duration of the spoken line.
    private static final class ActiveSpeechBubble {
        final Villager villager;
        final UUID displayId;
        final long expiresAt;
        final long startedAt;

        ActiveSpeechBubble(Villager villager, UUID displayId, long startedAt, long expiresAt) {
            this.villager = villager;
            this.displayId = displayId;
            this.startedAt = startedAt;
            this.expiresAt = expiresAt;
        }
    }
    static final Map<UUID, ActiveSpeechBubble> speechBubbles = new HashMap<>();

    static boolean isTarget(ServerPlayer p) {
        return p.getName().getString().equalsIgnoreCase(Cfg.d.targetUsername.trim());
    }


    /** Runs every tick for speech/emotes; the crowd logic itself runs every 0.5s. Movement is vanilla Brain only. */
    public static void tick(MinecraftServer s) {
        int tc = s.getTickCount();
        tickSpeechBubbles();
        processSpeech(tc);   // every tick, so replies to the player are not delayed
        if (tc % 10 != 0) return;

        String name = Cfg.d.targetUsername.trim();
        if (name.isEmpty()) return;
        ServerPlayer p = null;
        for (ServerPlayer sp : s.getPlayerList().getPlayers())
            if (isTarget(sp)) { p = sp; break; }

        if (p == null || p.isSpectator() || !(p.level() instanceof ServerLevel lv)) {
            if (!active.isEmpty() || !speechQueue.isEmpty() || phase != Phase.IDLE) resetAll(tc);
            return;
        }

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
                    arrived.remove(id);
                    it.remove();
                }
            }
            calm.values().removeIf(t -> t <= now);
            if (active.isEmpty() && phase != Phase.IDLE) endProtest();
        }

        List<Villager> vs = lv.getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(Cfg.d.radius),
                v -> v.isAlive() && !v.isSleeping());
        if (vs.isEmpty()) return;
        final ServerPlayer targetPlayer = p;
        vs.sort(Comparator.comparingDouble(v -> v.distanceToSqr(targetPlayer)));

        Villager spot = null;
        if (spotter != null)
            for (Villager v : vs) if (v.getUUID().equals(spotter)) { spot = v; break; }

        // 1) Story: one villager recognises the target, then calls the others.
        switch (phase) {
            case IDLE -> {
                if (sec && active.isEmpty()) {
                    for (Villager v : vs) {
                        if (calm.containsKey(v.getUUID())) continue;
                        spotter = v.getUUID();
                        active.put(spotter, now + Cfg.d.crowdSeconds * 20L + 100);
                        reacted.add(spotter);
                        used.clear();
                        phase = Phase.SPOTTED;
                        phaseTick = tc;
                        v.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(targetPlayer, true));
                        hop(v);
                        say(lv, v, "spot");
                        lastCrowdLine = now;
                        break;
                    }
                }
            }
            case SPOTTED -> {
                if (spot == null) {
                    active.remove(spotter);
                    endProtest();
                } else if (speechIdle(tc) && tc - phaseTick >= 30) {
                    say(lv, spot, "shout");   // the call: now the others come
                    hop(spot);
                    lastCrowdLine = now;
                    phase = Phase.CALLING;
                    phaseTick = tc;
                }
            }
            case CALLING -> {
                if (speechIdle(tc) && tc - phaseTick >= 20) {
                    phase = Phase.GATHERING;
                    phaseTick = tc;
                }
            }
            default -> { }
        }

        // 2) Gradual recruitment once the call has been made: at most 3 new villagers per second.
        if (sec && phase.ordinal() >= Phase.CALLING.ordinal()) {
            int add = 0;
            for (Villager v : vs) {
                if (active.size() >= Cfg.d.maxCrowd || add >= 3) break;
                if (active.containsKey(v.getUUID()) || calm.containsKey(v.getUUID())) continue;
                active.put(v.getUUID(), now + Cfg.d.crowdSeconds * 20L);
                add++;
            }
        }

        List<Villager> crowd = new ArrayList<>();
        for (Villager v : vs) if (active.containsKey(v.getUUID())) crowd.add(v);
        double closest = crowd.isEmpty() ? 0 : Math.sqrt(crowd.get(0).distanceToSqr(targetPlayer));
        boolean fleeing = (phase == Phase.GATHERING || phase == Phase.PROTEST) && closest > 5.5;
        // They run when called and when the target tries to get away, and walk once they are around him.
        float speed = fleeing ? 1.1f : (phase == Phase.CALLING || phase == Phase.GATHERING ? 1.0f : 0.85f);

        // 3) Movement: everybody turns to look at the target and walks to their own spot around him.
        for (Villager v : crowd) {
            UUID id = v.getUUID();
            int h = id.hashCode();
            double ang = ((h & 0xFFFF) / 65536.0) * Math.PI * 2;
            double rad = 1.8 + ((h >>> 16) & 3) * 0.7;
            Vec3 slot = p.position().add(Math.cos(ang) * rad, 0, Math.sin(ang) * rad);
            double d2 = v.distanceToSqr(slot);
            var brain = v.getBrain();
            brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(p, true));
            if (phase == Phase.SPOTTED) continue;   // the first one just stands there staring

            if (d2 <= 2.25) {
                // Let the villager stop naturally once it reaches its assigned place.
                arrived.add(id);
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
                    brain.setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(slot, speed, 1));
                    commandedSlots.put(id, slot);
                    lastPathUpdate.put(id, (long) tc);
                }
            }
        }

        // 4) Once about half of them are around the target (or after 8s) the protest starts.
        if (phase == Phase.GATHERING && tc - phaseTick >= 60) {
            int here = 0;
            for (Villager v : crowd) if (arrived.contains(v.getUUID())) here++;
            if ((!crowd.isEmpty() && here * 2 >= crowd.size()) || tc - phaseTick >= 160) {
                phase = Phase.PROTEST;
                phaseTick = tc;
            }
        }

        // 5) If the target walks away, the nearest villager shouts after him (and the crowd runs after him).
        if (sec && fleeing && now - lastFleeLine >= 140) {
            lastFleeLine = now;
            lastCrowdLine = now;
            Villager shouter = crowd.get(0);
            queueSpeech(shouter, "flee", pickUnique(lv, "flee"), 2);
            processSpeech(tc);
        }

        // 6) Protest lines, strictly one villager at a time and never the same sentence twice:
        //    first each villager reacts once, then they alternate chants, question/answer pairs and comments.
        if (phase == Phase.PROTEST && sec && !crowd.isEmpty() && speechIdle(tc)
                && now - lastCrowdLine >= Cfg.d.secondsBetweenCrowdLines * 20L) {
            Villager who = null;
            for (Villager v : crowd)
                if (arrived.contains(v.getUUID()) && !reacted.contains(v.getUUID())) { who = v; break; }
            boolean done = false;
            if (who != null) {
                reacted.add(who.getUUID());
                sayText(lv, who, "recognize", pickUnique(lv, "recognize"));
                done = true;
            } else {
                int turn = chantTurn++ % 3;
                who = crowd.get(lv.random.nextInt(crowd.size()));
                if (turn == 1 && crowd.size() >= 2) {
                    String[] ex = pickExchange(lv);
                    if (ex != null) {
                        Villager other = who;
                        for (int tries = 0; tries < 8 && other == who; tries++)
                            other = crowd.get(lv.random.nextInt(crowd.size()));
                        if (other != who) {
                            queueExchange(who, ex[0], other, ex[1]);
                            done = true;
                        }
                    }
                }
                if (!done) sayText(lv, who, turn == 0 ? "chant" : "recognize",
                        pickUnique(lv, turn == 0 ? "chant" : "recognize"));
            }
            lastCrowdLine = now;
        }
    }

    static void resetAll(int tc) {
        active.clear();
        calm.clear();
        commandedSlots.clear();
        lastPathUpdate.clear();
        speechQueue.clear();
        nextSpeechTick = tc;
        endProtest();
    }

    static void endProtest() {
        phase = Phase.IDLE;
        spotter = null;
        arrived.clear();
        reacted.clear();
        chantTurn = 0;
    }

    static void hop(Villager v) {
        if (v.onGround()) v.getJumpControl().jump();
    }

    static boolean speechIdle(int tc) {
        return speechQueue.isEmpty() && tc >= nextSpeechTick;
    }

    /** Starts at most one queued line. It waits for the previous clip to really finish (known clip length),
     *  except a direct reaction to the player, which may cut in after one second. */
    static void processSpeech(int tc) {
        while (!speechQueue.isEmpty()) {
            QueuedSpeech head = speechQueue.peekFirst();
            Villager speaker = head.speaker();
            if (speaker == null || !speaker.isAlive() || !(speaker.level() instanceof ServerLevel lv)) {
                speechQueue.removeFirst();
                continue;
            }
            if (tc < nextSpeechTick && !(head.level() >= 2 && tc >= speechStartTick + 20)) return;
            speechQueue.removeFirst();
            playSpeechNow(lv, speaker, head.key(), head.text());
            if (head.replier() != null && head.replier().isAlive())   // the other villager answers right after
                speechQueue.addFirst(new QueuedSpeech(head.replier(), head.replyKey(), head.replyText(), 1));
            speechStartTick = tc;
            nextSpeechTick = tc + Voices.durationTicks(head.key(), head.text()) + SPEECH_GAP;
            return;
        }
    }

    static void queueSpeech(Villager speaker, String key, String text, int level) {
        if (speaker == null || !speaker.isAlive() || text == null || text.isBlank()) return;
        // Bound the queue so rapid player interactions cannot create minutes of stale dialogue.
        if (speechQueue.size() >= 40) return;
        QueuedSpeech q = new QueuedSpeech(speaker, key, text, level);
        if (level >= 2) {
            speechQueue.removeIf(x -> x.level() == 0);   // drop stale ambient chatter
            speechQueue.addFirst(q);
        } else {
            speechQueue.addLast(q);
        }
    }

    static String pickLimited(ServerLevel lv, String key) {
        return pickUnique(lv, key);
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
        sayPriority(lv, villager, key(player.getMainHandItem()));   // answers right away

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
                sayText(lv, chanter, "chant", chant);   // queued after the answer, one at a time
            }
        }
        return InteractionResult.SUCCESS_SERVER;
    }

    /** Head shake only; the sound comes from the voice line itself, so nothing talks over anything. */
    static void refuse(ServerLevel lv, Villager villager) {
        villager.setUnhappyCounter(40);
    }

    /** A question from one villager and the answer of another one, always back to back. */
    static void queueExchange(Villager asker, String askText, Villager replier, String replyText) {
        if (asker == null || replier == null || !asker.isAlive() || !replier.isAlive()) return;
        if (speechQueue.size() >= 38) return;
        speechQueue.addLast(new QueuedSpeech(asker, "ask", askText, 0, replier, "answer", replyText));
    }

    static final Map<String, String> lastLine = new HashMap<>();

    /** A line that has not been said yet in this protest: two villagers never say the same sentence.
     *  When every line of a key has been used, the key starts over (but not with the line just said). */
    static String pickUnique(ServerLevel lv, String key) {
        List<String> all = Talk.lines(key);
        if (all.isEmpty()) return Talk.pick(key, lv.random);
        List<String> pool = new ArrayList<>();
        for (String line : all) if (used.getOrDefault(key + "|" + line, 0) < 1) pool.add(line);
        if (pool.isEmpty()) {
            used.keySet().removeIf(k -> k.startsWith(key + "|"));
            pool.addAll(all);
            if (pool.size() > 1) pool.remove(lastLine.get(key));
        }
        String r = pool.get(lv.random.nextInt(pool.size()));
        used.put(key + "|" + r, 1);
        lastLine.put(key, r);
        return r;
    }

    /** A matching question/answer pair (ask_N goes with answer_N), unused in this protest, or null. */
    static String[] pickExchange(ServerLevel lv) {
        List<String> asks = Talk.lines("ask"), answers = Talk.lines("answer");
        int n = Math.min(asks.size(), answers.size());
        List<Integer> pool = new ArrayList<>();
        for (int i = 0; i < n; i++) if (used.getOrDefault("ask|" + asks.get(i), 0) < 1) pool.add(i);
        if (pool.isEmpty()) return null;
        int i = pool.get(lv.random.nextInt(pool.size()));
        used.put("ask|" + asks.get(i), 1);
        return new String[]{asks.get(i), answers.get(i)};
    }

    /** Story line: spoken in order, never over another line. */
    static void say(ServerLevel lv, Villager villager, String key) {
        queueSpeech(villager, key, pickUnique(lv, key), 1);
    }

    /** Ambient chatter: dropped if something more important comes up. */
    static void sayText(ServerLevel lv, Villager villager, String key, String text) {
        queueSpeech(villager, key, text, 0);
    }

    /** Direct reaction to the player: jumps the queue and starts without waiting for the next crowd update. */
    static void sayPriority(ServerLevel lv, Villager villager, String key) {
        queueSpeech(villager, key, pickUnique(lv, key), 2);
        processSpeech(lv.getServer().getTickCount());
    }

    private static String snbtString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
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
            // A speech bubble is cosmetic and must never interrupt gameplay.
        }
    }

    /** Shows the actual spoken sentence above the villager instead of sending it to chat. */
    static void showSpeechBubble(ServerLevel lv, Villager villager, String key, String text) {
        ActiveSpeechBubble old = speechBubbles.remove(villager.getUUID());
        if (old != null) {
            Entity previous = lv.getEntity(old.displayId);
            if (previous != null) previous.discard();
        }

        UUID displayId = UUID.randomUUID();
        double x = villager.getX();
        double y = villager.getY() + villager.getBbHeight() + 0.55;
        double z = villager.getZ();
        String nbt = "{UUID:" + intArray(displayId) + ",Tags:[\"nopc_speech\"],billboard:\"center\","
                + "text:{text:" + snbtString(text) + ",color:\"white\",bold:true},"
                + "background:1073741824,shadow:1b,alignment:\"center\",line_width:220,text_opacity:255b}";
        runCommand(lv, String.format(Locale.ROOT,
                "summon minecraft:text_display %.3f %.3f %.3f %s", x, y, z, nbt));

        long now = lv.getGameTime();
        long duration = Math.max(30, Voices.durationTicks(key, text)) + 8L;
        speechBubbles.put(villager.getUUID(),
                new ActiveSpeechBubble(villager, displayId, now, now + duration));
    }

    /** Keeps each speech bubble attached to its villager and removes it when the line finishes. */
    static void tickSpeechBubbles() {
        for (var it = speechBubbles.entrySet().iterator(); it.hasNext();) {
            ActiveSpeechBubble bubble = it.next().getValue();
            Villager villager = bubble.villager;
            if (!villager.isAlive() || !(villager.level() instanceof ServerLevel lv)) {
                it.remove();
                continue;
            }

            Entity display = lv.getEntity(bubble.displayId);
            long now = lv.getGameTime();
            if (now >= bubble.expiresAt || (display == null && now - bubble.startedAt > 5)) {
                if (display != null) display.discard();
                it.remove();
                continue;
            }
            if (display != null) {
                display.setPos(villager.getX(),
                        villager.getY() + villager.getBbHeight() + 0.55,
                        villager.getZ());
            }
        }
    }

    /** Removes leftover speech displays when the server stops. */
    public static void cleanup(MinecraftServer s) {
        speechBubbles.clear();
        try {
            s.getCommands().performPrefixedCommand(
                    s.createCommandSourceStack().withSuppressedOutput(), "kill @e[tag=nopc_speech]");
        } catch (Exception ignored) {
        }
    }

    static void playSpeechNow(ServerLevel lv, Villager villager, String key, String text) {
        showSpeechBubble(lv, villager, key, text);
        boolean calmLine = key.equals("spot") || key.equals("recognize") || key.equals("shout") || key.equals("chant")
                || key.equals("ask") || key.equals("answer");
        if (!calmLine) villager.setUnhappyCounter(40);               // shakes his head
        if (key.equals("shout") || key.equals("chant") || key.equals("flee")) hop(villager);

        SoundEvent voice = Voices.find(key, text);
        if (voice != null) lv.playSound(null, villager.blockPosition(), voice, SoundSource.VOICE, 2f, 1f);
        else lv.playSound(null, villager.blockPosition(),
                key.equals("spot") || key.equals("recognize") || key.equals("ask") || key.equals("answer")
                        ? SoundEvents.VILLAGER_AMBIENT : SoundEvents.VILLAGER_NO,
                SoundSource.NEUTRAL, 1f, 0.8f + lv.random.nextFloat() * 0.4f);
    }
}
