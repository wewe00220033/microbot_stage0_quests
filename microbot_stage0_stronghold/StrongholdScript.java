/*
 * Copyright (c) 2019, FlaxOnEm <flax.on.em@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.microbot.stronghold;

import java.awt.event.KeyEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2ActiveRouteStatus;
import net.runelite.client.plugins.microbot.util.walker.Rs2PathApi;
import net.runelite.client.plugins.microbot.util.walker.Rs2RouteResult;
import net.runelite.client.plugins.microbot.util.walker.Rs2TransportEdge;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.walker.WalkerState;
import net.runelite.client.plugins.microbot.util.walker.door.Rs2DoorGeometry;

/**
 * Targets Microbot commit 878c2eff875c6d22d1bf245581af8c34cf953fe2.
 * Run only one automation plugin per client. No sleeps or walking on the client thread.
 * A restart away from the anchor deliberately evacuates instead of resuming reward claims.
 */
public final class StrongholdScript extends Script {
    public enum State {
        VALIDATING_ANCHOR,
        WALKING_TO_STRONGHOLD,
        INTERACTING_ENTRANCE,
        TRAVERSING_FLOOR_1,
        TRAVERSING_FLOOR_2,
        TRAVERSING_FLOOR_3,
        TRAVERSING_FLOOR_4,
        CLAIMING_REWARDS,
        HOMING_TO_BANK,
        DEPOSITING_LOOT,
        COMPLETED,
        FAILED
    }

    private static final WorldPoint ANCHOR = point(3165, 3489);
    private static final WorldPoint ENTRANCE = point(3081, 3420);
    private static final WorldPoint ENTRANCE_STAND = point(3081, 3421);
    private static final int COINS = 995;
    private static final int FANCY_BOOTS = 9005;
    private static final int[] REWARD_BITS = {
        VarbitID.SOS_EMOTE_FLAP, VarbitID.SOS_EMOTE_DOH,
        VarbitID.SOS_EMOTE_IDEA, VarbitID.SOS_EMOTE_STAMP
    };
    private static final int[] REWARD_IDS = {
        ObjectID.SOS_WAR_CHEST, ObjectID.SOS_FAM_SACK,
        ObjectID.SOS_PEST_CHEST, ObjectID.SOS_DEATH_PRAM
    };
    private static final WorldPoint[] REWARDS = {
        point(1907, 5222), point(2021, 5216), point(2144, 5280), point(2344, 5214)
    };
    private static final WorldPoint[] REWARD_STANDS = {
        point(1906, 5222), point(2021, 5218), point(2144, 5281), point(2343, 5215)
    };
    private static final int[] DOWN_IDS = {
        ObjectID.SOS_WAR_LADD_DOWN, ObjectID.SOS_FAM_LADD_DOWN, ObjectID.SOS_PEST_LADD_DOWN
    };
    private static final WorldPoint[] DOWN_OBJECTS = {
        point(1902, 5222), point(2026, 5218), point(2148, 5284)
    };
    private static final WorldPoint[] DOWN_STANDS = {
        point(1903, 5222), point(2026, 5219), point(2147, 5284)
    };
    private static final State[] FLOOR_STATES = {
        State.TRAVERSING_FLOOR_1, State.TRAVERSING_FLOOR_2,
        State.TRAVERSING_FLOOR_3, State.TRAVERSING_FLOOR_4
    };
    private static final int[] COIN_TOTALS = {2000, 5000, 10000};
    // Inclusive bounds, taken from the pinned Stronghold quest helper.
    private static final int[][] FLOOR_BOUNDS = {
        {1855, 5184, 1920, 5248}, {1983, 5184, 2048, 5248},
        {2111, 5248, 2176, 5310}, {2304, 5184, 2367, 5248}
    };
    private static final Map<String, String> SECURITY_ANSWERS = securityAnswers();

    @Inject
    private ConfigManager configManager;

    private volatile State state = State.FAILED;
    private volatile boolean active;
    private final AtomicBoolean inCycle = new AtomicBoolean();
    private final AtomicBoolean surfaceIdle = new AtomicBoolean(true);
    private StrongholdConfig config;
    private ExecutorService walkingExecutor;
    private Future<?> surfaceTask;
    private boolean oldDisableTeleports;
    private boolean ownsWalkerSettings;
    private long startedAt;
    private long enteredAt;
    private long offlineSince;
    private long baselineCoins;
    private boolean validated;
    private boolean rewardsVerified;
    private boolean descentPending;
    private boolean bankDepositIssued;
    private boolean fancySelected;
    private volatile String surfaceError;
    private String account = "";
    private String failure = "";
    private String rememberedAnswer;
    private String lastDialogue = "";
    private String actionKey = "";
    private int actionAttempts;
    private WorldPoint recoveryOrigin;
    private Boolean warExitAtReward;
    private Door pendingDoor;
    private WorldPoint pendingDoorFrom;
    private long pendingDoorAt;
    private WorldPoint pendingTransport;
    private long pendingTransportAt;
    private Rs2RouteResult plannedRoute;

    // Navigation is worker-owned. The background surface walker only reads its target.
    private WorldPoint destination;
    private WorldPoint walkTarget;
    private WorldPoint stepTarget;
    private WorldPoint previousTile;
    private long lastMovedAt;
    private long plannedAt;
    private List<WorldPoint> plannedPath = Collections.emptyList();

    public synchronized boolean start(StrongholdConfig newConfig) {
        if (active || inCycle.get() || !surfaceIdle.get()) {
            Microbot.log("[Stronghold] Previous worker is still stopping; enable the plugin again shortly.");
            return false;
        }
        config = newConfig;
        failure = "";
        account = "";
        validated = false;
        rewardsVerified = false;
        descentPending = false;
        bankDepositIssued = false;
        fancySelected = false;
        surfaceError = null;
        rememberedAnswer = null;
        lastDialogue = "";
        actionKey = "";
        actionAttempts = 0;
        offlineSince = 0;
        baselineCoins = 0;
        recoveryOrigin = null;
        warExitAtReward = null;
        pendingDoor = null;
        pendingTransport = null;
        plannedRoute = null;
        destination = null;
        walkTarget = null;
        stepTarget = null;
        plannedPath = Collections.emptyList();
        previousTile = null;
        oldDisableTeleports = Rs2Walker.disableTeleports;
        Rs2Walker.disableTeleports = true;
        ownsWalkerSettings = true;
        walkingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Stronghold-surface-walker");
            thread.setDaemon(true);
            return thread;
        });
        active = true;
        startedAt = now();
        state = State.VALIDATING_ANCHOR;
        enteredAt = startedAt;
        Microbot.log("[Stronghold] START -> " + state);
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(
            this::cycle, 0, Rs2Random.between(250, 550), TimeUnit.MILLISECONDS);
        return true;
    }

    public State getState() {
        return state;
    }

    private void cycle() {
        if (!active || !inCycle.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!super.run() || !active) {
                return;
            }
            if (!Microbot.isLoggedIn()) {
                if (offlineSince == 0) {
                    offlineSince = now();
                } else if (now() - offlineSince > 90000) {
                    finish(State.FAILED, "Disconnected for more than 90 seconds.");
                }
                return;
            }
            offlineSince = 0;
            Snapshot snapshot = snapshot();
            if (snapshot == null) {
                return;
            }
            if (!account.isEmpty() && !account.equalsIgnoreCase(snapshot.name)) {
                finish(State.FAILED, "Account changed while the pipeline was active.");
                return;
            }
            account = snapshot.name;
            if (snapshot.hp <= 0) {
                finish(State.FAILED, "Death detected; rewards are not certified.");
                return;
            }
            // First closed-loop check on every eligible iteration, before any input.
            if (config.enableCrashRecovery() && !expectedZone(snapshot.tile)) {
                if (state == State.HOMING_TO_BANK) {
                    finish(State.FAILED, "Moved outside the recovery route.");
                } else {
                    recover("Player left the expected state zone.", snapshot.tile);
                }
                return;
            }
            if (now() - startedAt > TimeUnit.MINUTES.toMillis(40)) {
                finish(State.FAILED, "Overall 40-minute deadline exceeded.");
                return;
            }
            if (now() - enteredAt > stateBudget()) {
                if (state == State.HOMING_TO_BANK || state == State.DEPOSITING_LOOT) {
                    finish(State.FAILED, "Recovery/banking deadline exceeded.");
                } else {
                    recover("State deadline exceeded: " + state, snapshot.tile);
                }
                return;
            }
            // Never overlap a cancelled blocking walk with manual inputs.
            if (surfaceTask != null && !surfaceTask.isDone()) {
                monitorSurface(snapshot.tile);
                return;
            }
            if (!surfaceIdle.get()) {
                return;
            }
            if (surfaceError != null) {
                String error = surfaceError;
                surfaceError = null;
                if (state == State.HOMING_TO_BANK) {
                    finish(State.FAILED, "Recovery walker failed: " + error);
                } else {
                    recover("Surface walker failed: " + error, snapshot.tile);
                }
                return;
            }
            if (snapshot.hp * 100 / Math.max(1, snapshot.maxHp) <= 75
                    && (floor(snapshot.tile) >= 0 || state == State.WALKING_TO_STRONGHOLD)) {
                if (Rs2Player.useFood()) {
                    interactionDelay();
                    return;
                } else if (state != State.HOMING_TO_BANK) {
                    recover("Low health with no usable food.", snapshot.tile);
                    return;
                }
                // Without food, continue evacuation instead of idling in combat.
            }
            if (Rs2Dialogue.isInDialogue()) {
                handleDialogue(snapshot);
                return;
            }
            switch (state) {
                case VALIDATING_ANCHOR:
                    validate(snapshot);
                    break;
                case WALKING_TO_STRONGHOLD:
                    if (near(snapshot.tile, ENTRANCE_STAND, 4)) {
                        transition(State.INTERACTING_ENTRANCE, "Entrance reached.");
                    } else {
                        navigate(ENTRANCE_STAND, snapshot.tile, 3);
                    }
                    break;
                case INTERACTING_ENTRANCE:
                    if (floor(snapshot.tile) == 0) {
                        transition(State.TRAVERSING_FLOOR_1, "Observed entry into Vault of War.");
                    } else {
                        interactAt(ObjectID.SOS_DUNG_ENT_OPEN, "Climb-down", ENTRANCE,
                            ENTRANCE_STAND, snapshot.tile);
                    }
                    break;
                case TRAVERSING_FLOOR_1:
                    traverse(0, snapshot);
                    break;
                case TRAVERSING_FLOOR_2:
                    traverse(1, snapshot);
                    break;
                case TRAVERSING_FLOOR_3:
                    traverse(2, snapshot);
                    break;
                case TRAVERSING_FLOOR_4:
                    if (navigate(REWARD_STANDS[3], snapshot.tile, 1)) {
                        transition(State.CLAIMING_REWARDS, "Cradle room reached.");
                    }
                    break;
                case CLAIMING_REWARDS:
                    claimBoots(snapshot);
                    break;
                case HOMING_TO_BANK:
                    home(snapshot);
                    break;
                case DEPOSITING_LOOT:
                    deposit(snapshot);
                    break;
                case COMPLETED:
                case FAILED:
                    active = false;
                    break;
                default:
                    throw new IllegalStateException("Unhandled state: " + state);
            }
        } catch (RuntimeException exception) {
            // An unexpected API failure is not evidence that an input succeeded.
            finish(State.FAILED, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        } finally {
            if (active && !Thread.currentThread().isInterrupted()) {
                interactionDelay();
            }
            inCycle.set(false);
        }
    }

    private void validate(Snapshot snapshot) {
        if (!near(snapshot.tile, ANCHOR, 15)) {
            recover("Start location is not the GE anchor.", snapshot.tile);
            return;
        }
        for (int index = 0; index < 3; index++) {
            if (snapshot.claimed[index]) {
                finish(State.FAILED, "A coin reward was already claimed. This stage requires a fresh reward run.");
                return;
            }
        }
        if (snapshot.claimed[3] || snapshot.boots > 0) {
            finish(State.FAILED, "Stronghold completion already exists; refusing to certify another 10,000 GP.");
        } else if (Rs2Inventory.emptySlotCount() < 2) {
            finish(State.FAILED, "Keep two inventory slots free for coins and boots.");
        } else if (Rs2Inventory.getInventoryFood().size() < 4) {
            finish(State.FAILED, "Bring at least four recognized food items; the lower floors are dangerous.");
        } else if (Rs2Bank.isOpen()) {
            Rs2Bank.closeBank();
            interactionDelay();
        } else {
            baselineCoins = snapshot.coins;
            validated = true;
            transition(State.WALKING_TO_STRONGHOLD, "Fresh rewards and supplies verified.");
        }
    }

    private void traverse(int index, Snapshot snapshot) {
        int observedFloor = floor(snapshot.tile);
        if (observedFloor == index + 1 && descentPending && snapshot.claimed[index]) {
            transition(FLOOR_STATES[index + 1], "Observed floor transition.");
            return;
        }
        if (observedFloor != index) {
            recover("Unexpected floor during traversal.", snapshot.tile);
            return;
        }
        if (!snapshot.claimed[index]) {
            interactAt(REWARD_IDS[index], index == 1 ? "Search" : "Open", REWARDS[index],
                REWARD_STANDS[index], snapshot.tile);
            return;
        }
        if (snapshot.coins < baselineCoins + COIN_TOTALS[index]) {
            recover("Emote unlocked but expected coin total is missing.", snapshot.tile);
            return;
        }
        descentPending = true;
        interactAt(DOWN_IDS[index], "Climb-down", DOWN_OBJECTS[index], DOWN_STANDS[index], snapshot.tile);
    }

    private void claimBoots(Snapshot snapshot) {
        if (!validated || !snapshot.claimed[0] || !snapshot.claimed[1] || !snapshot.claimed[2]
                || snapshot.coins < baselineCoins + 10000) {
            recover("The three coin rewards are not verified.", snapshot.tile);
        } else if (snapshot.boots > 0 && snapshot.claimed[3]) {
            rewardsVerified = true;
            recoveryOrigin = snapshot.tile;
            transition(State.HOMING_TO_BANK, "Verified 10,000 GP and Fancy boots in inventory.");
        } else {
            interactAt(REWARD_IDS[3], "Search", REWARDS[3], REWARD_STANDS[3], snapshot.tile);
        }
    }

    private void home(Snapshot snapshot) {
        int currentFloor = floor(snapshot.tile);
        if (currentFloor == 3) {
            if (near(snapshot.tile, point(2358, 5215), 3)) {
                interactAt(ObjectID.SOS_DEATH_LADD_UP, "Climb-up", point(2358, 5216),
                    point(2358, 5215), snapshot.tile);
            } else {
                // The end-room Bone Chain returns directly to the surface.
                interactAt(ObjectID.SOS_DEATH_ROPE_UP, "Climb-up", point(2350, 5215),
                    point(2349, 5215), snapshot.tile);
            }
        } else if (currentFloor == 2) {
            interactAt(ObjectID.SOS_PEST_LADD_UP, "Climb-up", point(2122, 5252),
                point(2122, 5251), snapshot.tile);
        } else if (currentFloor == 1) {
            interactAt(ObjectID.SOS_FAM_LADD_UP, "Climb-up", point(2041, 5245),
                point(2042, 5245), snapshot.tile);
        } else if (currentFloor == 0) {
            if (warExitAtReward == null) {
                warExitAtReward = snapshot.tile.distanceTo2D(point(1913, 5226))
                    < snapshot.tile.distanceTo2D(point(1859, 5243));
            }
            WorldPoint stand = warExitAtReward ? point(1913, 5225) : point(1859, 5243);
            WorldPoint object = warExitAtReward ? point(1913, 5226) : point(1859, 5244);
            interactAt(ObjectID.SOS_WAR_LADD_UP, "Climb-up", object, stand, snapshot.tile);
        } else if (near(snapshot.tile, ANCHOR, 5)) {
            transition(State.DEPOSITING_LOOT, "GE bank anchor reached.");
        } else {
            navigate(ANCHOR, snapshot.tile, 4);
        }
    }

    private void deposit(Snapshot snapshot) {
        if (!near(snapshot.tile, ANCHOR, 10)) {
            recover("Moved away from the GE during banking.", snapshot.tile);
        } else if (!Rs2Bank.isOpen()) {
            if (bankDepositIssued && snapshot.inventoryEmpty && snapshot.equipmentEmpty) {
                finish(rewardsVerified && failure.isEmpty() ? State.COMPLETED : State.FAILED,
                    failure.isEmpty() ? "Rewards deposited; ready for the next stage." : failure);
            } else {
                Rs2Bank.openBank();
                interactionDelay();
            }
        } else if (!snapshot.inventoryEmpty) {
            Rs2Bank.depositAll();
            interactionDelay();
        } else if (!snapshot.equipmentEmpty) {
            Rs2Bank.depositEquipment();
            interactionDelay();
        } else {
            bankDepositIssued = true;
            Rs2Bank.closeBank();
            interactionDelay();
        }
    }

    private void interactAt(int id, String action, WorldPoint objectAt, WorldPoint stand, WorldPoint player) {
        if (!navigate(stand, player, 1)) {
            return;
        }
        TileObject object = Rs2GameObject.<TileObject>getAll(candidate -> candidate.getId() == id
                && near(candidate.getWorldLocation(), objectAt, 2)).stream()
            .min(Comparator.comparingInt((TileObject candidate) -> candidate.getWorldLocation().distanceTo2D(objectAt))
                .thenComparingInt(candidate -> candidate.getWorldLocation().getX())
                .thenComparingInt(candidate -> candidate.getWorldLocation().getY()))
            .orElse(null);
        if (object == null || !near(player, object.getWorldLocation(), 4)) {
            recordAttempt(id + ":missing", player);
            return;
        }
        if (!Rs2GameObject.hasAction(object, action)) {
            recover("Expected action '" + action + "' is missing on object " + id, player);
            return;
        }
        if (!Rs2Tile.isTileObjectReachable(object)) {
            resetPlan();
            return;
        }
        if (!recordAttempt(id + ":" + action, player) || !canInput()) {
            return;
        }
        Rs2GameObject.interact(object, action);
        interactionDelay();
        resetPlan();
    }

    private boolean recordAttempt(String key, WorldPoint player) {
        if (!key.equals(actionKey)) {
            actionKey = key;
            actionAttempts = 0;
        }
        if (++actionAttempts > 12) {
            if (state == State.HOMING_TO_BANK) {
                finish(State.FAILED, "Repeated evacuation interaction failed: " + key);
            } else {
                recover("Repeated interaction failed: " + key + ". Check account 2FA and inventory space.", player);
            }
            return false;
        }
        return true;
    }

    /**
     * Surface travel uses the full webwalker on its own cancellable worker.
     * Dungeon travel uses planner snapshots plus walkStep for door-free approaches.
     * Calling full walkTo inside the dungeon would let its built-in door handler
     * consume security questions before this script can apply its reading/safety rules.
     */
    private boolean navigate(WorldPoint base, WorldPoint player, int radius) {
        base = resolveStand(base, player);
        if (near(player, base, radius) && (floor(player) < 0
                || Rs2Tile.getReachableTilesFromTile(player, radius + 3).containsKey(base))) {
            cancelNavigation();
            return true;
        }
        if (Rs2Player.isAnimating() && !Rs2Player.isMoving()) {
            return false;
        }
        if (!base.equals(destination)) {
            cancelNavigation();
            destination = base;
            walkTarget = jitter(base, player, false);
            previousTile = player;
            lastMovedAt = now();
        }
        if (floor(player) < 0) {
            if (surfaceIdle.get() && (surfaceTask == null || surfaceTask.isDone())) {
                final WorldPoint target = walkTarget;
                surfaceTask = walkingExecutor.submit(() -> {
                    surfaceIdle.set(false);
                    try {
                        if (canInput()) {
                            Rs2Walker.walkTo(target, Math.max(1, radius - 1));
                            interactionDelay();
                        }
                    } catch (RuntimeException exception) {
                        if (active && !Thread.currentThread().isInterrupted()) {
                            surfaceError = exception.toString();
                        }
                    } finally {
                        surfaceIdle.set(true);
                    }
                });
            }
            return false;
        }
        if (pendingTransport != null) {
            if (near(player, pendingTransport, 3) || now() - pendingTransportAt >= 8000) {
                resetPlan();
                lastMovedAt = now();
            } else {
                return false;
            }
        }
        if (pendingDoor != null) {
            WallObject wall = (WallObject) pendingDoor.object;
            WorldPoint wallTile = wall.getWorldLocation();
            if (Rs2DoorGeometry.playerBeyondWallFace(wall.getOrientationA(), wallTile, pendingDoorFrom, player)
                    || Rs2DoorGeometry.playerBeyondWallFace(wall.getOrientationB(), wallTile, pendingDoorFrom, player)) {
                resetPlan();
                lastMovedAt = now();
            } else if (now() - pendingDoorAt < 8000 || Rs2Player.isMoving()) {
                return false;
            } else {
                resetPlan();
            }
        }
        if (!player.equals(previousTile)) {
            previousTile = player;
            lastMovedAt = now();
        } else if (now() - lastMovedAt >= 8000 && !Rs2Dialogue.isInDialogue()) {
            Microbot.log("[Stronghold] No tile movement for 8 seconds; replanning dungeon walk.");
            resetPlan();
            lastMovedAt = now();
        }
        if (plannedPath.isEmpty()) {
            if (plannedAt == 0) {
                Rs2Walker.setTarget(walkTarget);
                plannedAt = now();
                return false;
            }
            Rs2ActiveRouteStatus status = Rs2PathApi.getActiveRouteStatus();
            if (!status.isReady() || !status.getTargets().contains(walkTarget)) {
                if (now() - plannedAt > 20000) {
                    resetPlan();
                }
                return false;
            }
            List<WorldPoint> path = status.getRawPath();
            if (path.isEmpty() || !near(path.get(path.size() - 1), walkTarget, 2)) {
                recover("Dungeon planner did not reach the requested destination.", player);
                return false;
            }
            plannedPath = new ArrayList<>(path);
            plannedRoute = Rs2PathApi.getActiveRoute().orElse(null);
            if (plannedRoute == null || !plannedRoute.getPath().equals(plannedPath)) {
                resetPlan();
                return false;
            }
        }
        int nearest = 0;
        for (int index = 1; index < plannedPath.size(); index++) {
            if (player.distanceTo2D(plannedPath.get(index)) < player.distanceTo2D(plannedPath.get(nearest))) {
                nearest = index;
            }
        }
        Rs2TransportEdge transport = null;
        int transportIndex = plannedPath.size() - 1;
        for (int index = nearest; index + 1 < plannedPath.size(); index++) {
            Rs2TransportEdge edge = plannedRoute.getTransportEdge(plannedPath.get(index), plannedPath.get(index + 1))
                .orElse(null);
            if (edge != null) {
                transport = edge;
                transportIndex = index;
                break;
            }
        }
        final List<WorldPoint> route = plannedPath;
        final int startIndex = nearest;
        final int scanEnd = transportIndex;
        Door door = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            List<TileObject> objects = Rs2GameObject.<TileObject>getAll(candidate -> isStrongholdDoor(candidate.getId()));
            objects.sort(Comparator.comparingInt(TileObject::getId)
                .thenComparingInt(candidate -> candidate.getWorldLocation().getX())
                .thenComparingInt(candidate -> candidate.getWorldLocation().getY()));
            for (int index = startIndex; index < scanEnd; index++) {
                for (TileObject object : objects) {
                    if (object instanceof WallObject && Rs2DoorGeometry.wallDoorTouchesSegment(
                            (WallObject) object, route.get(index), route.get(index + 1))) {
                        return new Door(object, route.get(index));
                    }
                }
            }
            return null;
        }).orElse(null);
        WorldPoint approach = door != null ? door.before : transport != null ? transport.getOrigin() : walkTarget;
        if (door == null && transport != null && near(player, approach, 1) && !Rs2Player.isMoving()) {
            if (floor(transport.getDestination()) != floor(player) || !isShortcut(transport.getObjectId())) {
                recover("Planner selected an unsupported dungeon transport: " + transport.getDisplayInfo(), player);
                return false;
            }
            TileObject object = Rs2GameObject.findObjectByIdAndDistance(transport.getObjectId(), 4);
            if (object != null && Rs2GameObject.hasAction(object, transport.getAction()) && canInput()
                    && recordAttempt("shortcut:" + transport.getObjectId(), player)) {
                Rs2GameObject.interact(object, transport.getAction());
                interactionDelay();
                WorldPoint expected = transport.getDestination();
                resetPlan();
                pendingTransport = expected;
                pendingTransportAt = now();
            }
            return false;
        }
        if (door != null && near(player, approach, 1)
                && near(player, door.object.getWorldLocation(), 2)) {
            if (!Rs2Player.isMoving() && canInput()) {
                String action = Rs2GameObject.hasAction(door.object, "Open") ? "Open" : "Pass-through";
                if (!Rs2GameObject.hasAction(door.object, action)) {
                    recover("Unrecognized Stronghold door action.", player);
                    return false;
                }
                if (!recordAttempt("door:" + door.object.getId() + ":" + door.object.getWorldLocation(), player)) {
                    return false;
                }
                Rs2GameObject.interact(door.object, action);
                interactionDelay();
                resetPlan();
                pendingDoor = door;
                pendingDoorFrom = player;
                pendingDoorAt = now();
            }
        } else {
            if (stepTarget == null || !near(stepTarget, approach, 1)) {
                stepTarget = jitter(approach, player, true);
            }
            WalkerState result = Rs2Walker.walkStep(stepTarget, 0);
            if (result == WalkerState.UNREACHABLE || result == WalkerState.EXIT) {
                resetPlan();
            }
        }
        return false;
    }

    private void monitorSurface(WorldPoint player) {
        if (!player.equals(previousTile)) {
            previousTile = player;
            lastMovedAt = now();
        } else if (now() - lastMovedAt >= 8000) {
            Microbot.log("[Stronghold] No tile movement for 8 seconds; cancelling and reissuing walkTo.");
            surfaceTask.cancel(true);
            Rs2Walker.setTarget(null);
            lastMovedAt = now();
            // A subsequent cycle reissues only after the old worker's finally block ran.
        }
    }

    private WorldPoint resolveStand(WorldPoint base, WorldPoint player) {
        if (floor(player) < 0 || !near(base, player, 8) || Rs2Tile.isWalkable(base)) {
            return base;
        }
        List<WorldPoint> candidates = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                WorldPoint candidate = new WorldPoint(base.getX() + dx, base.getY() + dy, base.getPlane());
                if (Rs2Tile.isWalkable(candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates.stream().min(Comparator.comparingInt((WorldPoint candidate) ->
                Math.abs(candidate.getX() - base.getX()) + Math.abs(candidate.getY() - base.getY()))
            .thenComparingInt(WorldPoint::getX).thenComparingInt(WorldPoint::getY)).orElse(base);
    }

    private WorldPoint jitter(WorldPoint base, WorldPoint player, boolean requireReachable) {
        WorldPoint candidate = config.enableJitter()
            ? new WorldPoint(base.getX() + Rs2Random.between(-1, 2),
                base.getY() + Rs2Random.between(-1, 2), base.getPlane()) : base;
        if (floor(player) < 0) {
            return candidate;
        }
        boolean walkable = Rs2Tile.isWalkable(candidate);
        if (walkable && Rs2Tile.getReachableTilesFromTile(base, 4).containsKey(candidate)
                && (!requireReachable || Rs2Tile.getReachableTilesFromTile(player, 64).containsKey(candidate))) {
            return candidate;
        }
        return base;
    }

    private void handleDialogue(Snapshot snapshot) {
        if (state == State.HOMING_TO_BANK && !failure.isEmpty()) {
            if (canInput() && Rs2Dialogue.isInDialogue()) {
                Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
                interactionDelay();
            }
            return;
        }
        String text = normalize(Rs2Dialogue.getDialogueText());
        String question = normalize(Rs2Dialogue.getQuestion());
        if (!text.isEmpty()) {
            lastDialogue = text;
        }
        String answer = findAnswer(text + " " + question);
        if (answer != null) {
            rememberedAnswer = answer;
        }
        if (Rs2Dialogue.hasSelectAnOption()) {
            List<String> options = optionTexts();
            String choice = null;
            if (state == State.CLAIMING_REWARDS) {
                choice = options.stream().filter(option -> normalize(option).matches(".*\\bfancy boots\\b.*"))
                    .findFirst().orElse(null);
            }
            if (choice == null && state == State.CLAIMING_REWARDS && fancySelected
                    && (lastDialogue.contains("sure") || lastDialogue.contains("fancy boots"))) {
                choice = options.stream().filter(option -> normalize(option).startsWith("yes"))
                    .findFirst().orElse(null);
            }
            if (choice == null && rememberedAnswer != null) {
                final String expected = normalize(rememberedAnswer);
                choice = options.stream().filter(option -> normalize(option).equals(expected)).findFirst().orElse(null);
            }
            if (choice == null && descentPending && (lastDialogue.contains("danger")
                    || lastDialogue.contains("next level") || lastDialogue.contains("descend"))) {
                choice = options.stream().filter(option -> normalize(option).startsWith("yes"))
                    .findFirst().orElse(null);
            }
            if (choice == null) {
                finish(State.FAILED, "Unknown dialogue; no answer guessed. Question=" + question
                    + "; context=" + lastDialogue + "; options=" + options);
                return;
            }
            final String selected = choice;
            final List<String> before = options;
            readingDelay();
            if (canInput() && Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasSelectAnOption()
                    && before.equals(optionTexts())
                    && question.equals(normalize(Rs2Dialogue.getQuestion()))) {
                boolean clicked = Rs2Dialogue.clickOption(selected, true);
                if (clicked && state == State.CLAIMING_REWARDS
                        && normalize(selected).matches(".*\\bfancy boots\\b.*")) {
                    fancySelected = true;
                }
                if (clicked) {
                    rememberedAnswer = null;
                }
                pendingDoorAt = now();
                interactionDelay();
            }
        } else if (Rs2Dialogue.hasContinue()) {
            readingDelay();
            if (canInput() && Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasContinue()
                    && text.equals(normalize(Rs2Dialogue.getDialogueText()))) {
                Rs2Dialogue.clickContinue();
                pendingDoorAt = now();
                interactionDelay();
            }
        }
    }

    private List<String> optionTexts() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            List<String> result = new ArrayList<>();
            for (Widget option : Rs2Dialogue.getDialogueOptions()) {
                result.add(option.getText());
            }
            return result;
        }).orElse(Collections.emptyList());
    }

    private static String findAnswer(String question) {
        for (Map.Entry<String, String> entry : SECURITY_ANSWERS.entrySet()) {
            if (question.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean expectedZone(WorldPoint tile) {
        switch (state) {
            case VALIDATING_ANCHOR:
                return near(tile, ANCHOR, 15);
            case WALKING_TO_STRONGHOLD:
                return inRectangle(tile, 3060, 3400, 3190, 3510, 15);
            case INTERACTING_ENTRANCE:
                return near(tile, ENTRANCE, 15) || inFloor(tile, 0, 15);
            case TRAVERSING_FLOOR_1:
            case TRAVERSING_FLOOR_2:
            case TRAVERSING_FLOOR_3:
            case TRAVERSING_FLOOR_4:
                int index = state.ordinal() - State.TRAVERSING_FLOOR_1.ordinal();
                return inFloor(tile, index, 15)
                    || (descentPending && index < 3 && inFloor(tile, index + 1, 15));
            case CLAIMING_REWARDS:
                return inFloor(tile, 3, 15);
            case HOMING_TO_BANK:
                for (int floorIndex = 0; floorIndex < 4; floorIndex++) {
                    if (inFloor(tile, floorIndex, 15)) {
                        return true;
                    }
                }
                int x = recoveryOrigin != null && floor(recoveryOrigin) < 0 ? recoveryOrigin.getX() : 3081;
                int y = recoveryOrigin != null && floor(recoveryOrigin) < 0 ? recoveryOrigin.getY() : 3421;
                return inRectangle(tile, Math.min(x, 3060) - 32, Math.min(y, 3400) - 32,
                    Math.max(x, 3190) + 32, Math.max(y, 3510) + 32, 15);
            case DEPOSITING_LOOT:
                return near(tile, ANCHOR, 15);
            case COMPLETED:
            case FAILED:
                return true;
            default:
                return false;
        }
    }

    private void recover(String reason, WorldPoint location) {
        if (failure.isEmpty()) {
            failure = reason;
        }
        rewardsVerified = false;
        recoveryOrigin = location;
        transition(State.HOMING_TO_BANK, "Recovery: " + reason);
    }

    private void transition(State next, String reason) {
        State previous = state;
        cancelNavigation();
        state = next;
        enteredAt = now();
        actionKey = "";
        actionAttempts = 0;
        descentPending = false;
        rememberedAnswer = null;
        lastDialogue = "";
        Microbot.log("[Stronghold] " + previous + " -> " + next + " | " + reason);
        journal(reason);
    }

    private void finish(State terminal, String reason) {
        if (!active) {
            return;
        }
        if (terminal == State.FAILED && failure.isEmpty()) {
            failure = reason;
        }
        transition(terminal, reason);
        shutdown();
    }

    private void journal(String reason) {
        if (!account.isEmpty()) {
            String key = "checkpoint_" + account.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
            try {
                configManager.setConfiguration(StrongholdConfig.GROUP, key,
                    Instant.now() + " | account=" + account + " | state=" + state
                        + " | verified=" + rewardsVerified + " | baselineCoins=" + baselineCoins
                        + " | reason=" + reason.replace('\n', ' '));
            } catch (RuntimeException exception) {
                Microbot.log("[Stronghold] Checkpoint persistence failed: " + exception);
            }
        }
    }

    private void resetPlan() {
        Rs2Walker.setTarget(null);
        plannedPath = Collections.emptyList();
        plannedAt = 0;
        stepTarget = null;
        pendingDoor = null;
        pendingTransport = null;
        plannedRoute = null;
    }

    private void cancelNavigation() {
        if (surfaceTask != null && !surfaceTask.isDone()) {
            surfaceTask.cancel(true);
        }
        resetPlan();
        destination = null;
        walkTarget = null;
    }

    @Override
    public synchronized void shutdown() {
        if (active && state != State.COMPLETED && state != State.FAILED) {
            State previous = state;
            state = State.FAILED;
            failure = "Plugin stopped before completion.";
            Microbot.log("[Stronghold] " + previous + " -> FAILED | " + failure);
            journal(failure);
        }
        active = false;
        cancelNavigation();
        if (walkingExecutor != null) {
            walkingExecutor.shutdownNow();
        }
        super.shutdown();
        if (ownsWalkerSettings) {
            Rs2Walker.disableTeleports = oldDisableTeleports;
            ownsWalkerSettings = false;
        }
    }

    private boolean canInput() {
        return active && !Thread.currentThread().isInterrupted() && Microbot.isLoggedIn()
            && !Microbot.pauseAllScripts.get() && !InputArbiter.isHuman();
    }

    private long stateBudget() {
        switch (state) {
            case TRAVERSING_FLOOR_1:
            case TRAVERSING_FLOOR_2:
            case TRAVERSING_FLOOR_3:
            case TRAVERSING_FLOOR_4:
            case HOMING_TO_BANK:
                return TimeUnit.MINUTES.toMillis(10);
            default:
                return TimeUnit.MINUTES.toMillis(2);
        }
    }

    private Snapshot snapshot() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (Microbot.getClient().getLocalPlayer() == null) {
                return null;
            }
            Snapshot result = new Snapshot();
            result.tile = Microbot.getClient().getLocalPlayer().getWorldLocation();
            result.name = Microbot.getClient().getLocalPlayer().getName();
            if (result.name == null) {
                return null;
            }
            result.hp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
            result.maxHp = Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
            for (int index = 0; index < REWARD_BITS.length; index++) {
                result.claimed[index] = Microbot.getClient().getVarbitValue(REWARD_BITS[index]) != 0;
            }
            ItemContainer inventory = Microbot.getClient().getItemContainer(InventoryID.INVENTORY);
            ItemContainer equipment = Microbot.getClient().getItemContainer(InventoryID.EQUIPMENT);
            if (inventory == null || equipment == null) {
                return null;
            }
            result.inventoryEmpty = empty(inventory);
            result.equipmentEmpty = empty(equipment);
            for (Item item : inventory.getItems()) {
                if (item.getId() == COINS) {
                    result.coins += item.getQuantity();
                } else if (item.getId() == FANCY_BOOTS) {
                    result.boots += item.getQuantity();
                }
            }
            return result;
        }).orElse(null);
    }

    private static boolean empty(ItemContainer container) {
        for (Item item : container.getItems()) {
            if (item.getId() >= 0 && item.getQuantity() > 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isShortcut(int id) {
        return id == ObjectID.SOS_WAR_PORTAL || id == ObjectID.SOS_FAM_PORTAL
            || id == ObjectID.SOS_PEST_PORTAL || id == ObjectID.SOS_DEATH_PORTAL
            || id == ObjectID.SOS_FAM_ROPE_UP || id == ObjectID.SOS_PEST_ROPE_UP || id == 1734;
    }

    private static boolean isStrongholdDoor(int id) {
        return id == ObjectID.SOS_WAR_DOOR_FACE || id == ObjectID.SOS_WAR_DOOR_FACE_MIRR
            || id == ObjectID.SOS_FAM_DOOR_FACE || id == ObjectID.SOS_FAM_DOOR_FACE_MIRR
            || id == ObjectID.SOS_PEST_DOOR_FACE || id == ObjectID.SOS_PEST_DOOR_FACE_MIRR
            || id == ObjectID.SOS_DEATH_DOOR_FACE || id == ObjectID.SOS_DEATH_DOOR_FACE_MIRR;
    }

    private static WorldPoint point(int x, int y) {
        return new WorldPoint(x, y, 0);
    }

    private static boolean near(WorldPoint first, WorldPoint second, int radius) {
        return first != null && second != null && first.getPlane() == second.getPlane()
            && first.distanceTo2D(second) <= radius;
    }

    private static boolean inRectangle(WorldPoint point, int minX, int minY, int maxX, int maxY, int margin) {
        return point != null && point.getPlane() == 0 && point.getX() >= minX - margin
            && point.getX() <= maxX + margin && point.getY() >= minY - margin && point.getY() <= maxY + margin;
    }

    private static boolean inFloor(WorldPoint point, int index, int margin) {
        int[] bounds = FLOOR_BOUNDS[index];
        return inRectangle(point, bounds[0], bounds[1], bounds[2], bounds[3], margin);
    }

    private static int floor(WorldPoint point) {
        for (int index = 0; index < FLOOR_BOUNDS.length; index++) {
            if (inFloor(point, index, 0)) {
                return index;
            }
        }
        return -1;
    }

    private static long now() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("(?i)<br\\s*/?>", " ")
            .replaceAll("<[^>]*>", "").replace('\u00a0', ' ').replace('\u2019', '\'')
            .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static void interactionDelay() {
        // Global.sleep is inherited by Script; Microbot.sleep does not exist in this revision.
        sleep(Rs2Random.between(1200, 2800));
    }

    private static void readingDelay() {
        sleep(Rs2Random.between(1800, 4200));
    }

    private static final class Snapshot {
        private WorldPoint tile;
        private String name;
        private int hp;
        private int maxHp;
        private long coins;
        private int boots;
        private boolean inventoryEmpty;
        private boolean equipmentEmpty;
        private final boolean[] claimed = new boolean[4];
    }

    private static final class Door {
        private final TileObject object;
        private final WorldPoint before;

        private Door(TileObject object, WorldPoint before) {
            this.object = object;
            this.before = before;
        }
    }

    // Question/answer data adapted from the pinned upstream StrongholdAnswer enum.
    // The retained BSD notice above applies to that upstream-derived data.
    private static Map<String, String> securityAnswers() {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("randomly selected for a prize", "No way! I'm reporting you to Jagex!");
        answers.put("logged in while i'm out of the room", "No.");
        answers.put("remove a hijacker", "Use the Account Recovery System.");
        answers.put("make you a player moderator", "Don't give them the information and send an 'Abuse report'.");
        answers.put("watching a stream", "Report the stream as a scam. Real Jagex streams have a 'verified' mark.");
        answers.put("do a difficult quest", "Don't give them my password.");
        answers.put("who can i give my password", "Nobody.");
        answers.put("how do i set up two-factor", "Through account settings on oldschool.runescape.com.");
        answers.put("example of a good bank pin", "The birthday of a famous person or event.");
        answers.put("check your stats", "Don't give out your password to anyone. Not even close friends.");
        answers.put("comment on the forum post", "Report the player for phishing.");
        answers.put("invitation via in- game chat or email", "Report the incident and do not click any links.");
        answers.put("how do i set a bank pin", "Talk to any banker.");
        answers.put("moderator asks me for my account details", "Politely tell them no, then use the 'Report Abuse' button.");
        answers.put("after you answer the question", "Read the text and follow the advice given.");
        answers.put("prevent me from saying my pin", "No.");
        answers.put("that sound?", "No way! You'll just take my gold for your own! Reported!");
        answers.put("verify my identity", "Delete it - it's a fake!");
        answers.put("website claims", "Nothing, it's a fake.");
        answers.put("react?", "Don't share your information and report the player.");
        answers.put("make you a member for free", "Don't tell them anything and click the 'Report Abuse' button.");
        answers.put("okay to buy an old school", "No, you should never buy an account.");
        answers.put("invitation via social media", "Report the incident and do not click any links.");
        answers.put("where is it safe", "Only on the Old School RuneScape website.");
        answers.put("best way to secure your account", "Two-factor authentication on your account and your registered email.");
        answers.put("share my account with", "Nobody.");
        answers.put("purchase items and/or gold", "Do not visit the website and report the player who messaged you.");
        answers.put("whose responsibility", "Me.");
        answers.put("someone to level your account", "No, you should never allow anyone to use your account.");
        answers.put("account is compromised", "Secure my device and reset my password.");
        answers.put("respond?", "Decline the offer and report that player.");
        answers.put("password backwards", "Don't type in my password backwards and report the player.");
        answers.put("keylogger or virus", "Virus scan my device then change my password.");
        answers.put("registered email secure", "Set up two-factor authentication with my email provider.");
        answers.put("characteristic of a secure password", "It's never reused on other websites or accounts.");
        answers.put("lose out! what do you say?", "Nope, you're tricking me into going somewhere dangerous.");
        answers.put("not through responding to emails.", "No.");
        return Collections.unmodifiableMap(answers);
    }
}
