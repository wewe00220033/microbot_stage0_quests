package net.runelite.client.plugins.microbot.totallevel;

import java.awt.event.KeyEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Constants;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldArea;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.IScript;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.globval.enums.InterfaceTab;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2PathApi;
import net.runelite.client.plugins.microbot.util.walker.Rs2RouteRequest;
import net.runelite.client.plugins.microbot.util.walker.Rs2RouteResult;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

/**
 * Stage 0.3, API target 878c2eff875c6d22d1bf245581af8c34cf953fe2.
 *
 * Run this plugin exclusively. It uses only F2P activities and no purchases.
 * Prerequisites: Stage 0.2 Cook's Assistant complete; a usable axe (inventory,
 * equipment or bank), tinderbox and small fishing net available in the bank.
 * All inventory is banked at startup; do not bring items you cannot deposit.
 * Route: Lumbridge castle bank -> west/north-west trees -> bank, alternating
 * with Lumbridge swamp small-net fishing -> castle range -> castle bank.
 * Basic logs remain a fallback when level-appropriate oaks/willows are absent.
 * Optional Draynor woodcutting is opt-in (dark-wizard risk); the bank anchor,
 * small-net fishing and range remain Lumbridge. Configuration is not a route
 * safety guarantee; combat/low health triggers a bounded bank evacuation.
 * Random input variation is not ban protection and does not make botting safe.
 *
 * Client-thread hook only observes/latches a verified total >= 100. All waits,
 * interactions, planning and walking run on workers. A cancelled walker keeps
 * input ownership until its worker acknowledges cancellation. Completion never
 * depends on a click result, an estimated XP threshold or the bank succeeding.
 */
@Singleton
public final class TotalLevelScript extends Script implements IScript {
    public enum State {
        INITIALIZING, WALKING_TO_BANK, OPENING_BANK, DEPOSITING_ITEMS,
        WITHDRAWING_TOOLS, CLOSING_BANK, STARTING_BLOCK, WALKING_TO_TREES,
        CHOPPING, SELECTING_FIRE_TILE, WALKING_TO_FIRE_TILE, LIGHTING_LOGS,
        WALKING_TO_FISHING_SPOT, FISHING, WALKING_TO_RANGE, COOKING,
        MICRO_BREAK, ROTATION_BREAK, COMPLETED, FAILED
    }
    private enum Activity { WOODCUTTING_FIREMAKING, FISHING_COOKING }
    private enum BankReason { INITIAL, BATCH, ROTATION, RECOVERY }

    public static final int TARGET_TOTAL = 100;
    public static final WorldPoint BANK_ANCHOR = point(3208, 3220, 2);
    private static final WorldPoint TREES = point(3190, 3245, 0);
    private static final WorldPoint DRAYNOR_TREES = point(3088, 3230, 0);
    private static final WorldPoint FISHING = point(3243, 3152, 0);
    private static final WorldPoint RANGE = point(3208, 3214, 0);
    private static final long STUCK_MS = 8000;
    private static final long NO_PROGRESS_MS = 180000;
    private static final int MAX_ATTEMPTS = 12;
    private static final int MAX_WALK_RETRIES = 4;
    private static final Skill[] SKILLS = {Skill.WOODCUTTING, Skill.FIREMAKING, Skill.FISHING, Skill.COOKING};
    // Inventory axes do not require the Attack level needed to equip them.
    private static final int[] AXES = {ItemID.RUNE_AXE, ItemID.ADAMANT_AXE, ItemID.MITHRIL_AXE,
        ItemID.BLACK_AXE, ItemID.STEEL_AXE, ItemID.IRON_AXE, ItemID.BRONZE_AXE};
    private static final int[] AXE_LEVELS = {41, 31, 21, 11, 6, 1, 1};

    @Inject private ConfigManager configManager;
    private final AtomicBoolean inCycle = new AtomicBoolean();
    private final AtomicBoolean goalObserved = new AtomicBoolean();
    private final AtomicBoolean changedAccount = new AtomicBoolean();
    private volatile boolean active;
    private volatile String account = "";
    private volatile int observedTotal;
    private volatile State state = State.FAILED;
    private volatile WalkJob walkJob;
    private volatile Status status = new Status("STOPPED", "None", 0, 0, new int[4], 0, 0, 0, 0, "Enable plugin to start");
    private TotalLevelConfig config;
    private ExecutorService walkingExecutor;
    private volatile Snapshot last;
    private Activity activity;
    private BankReason bankReason;
    private State resumeState;
    private final Map<Integer, Integer> tools = new LinkedHashMap<>();
    private final Map<String, Integer> attempts = new LinkedHashMap<>();
    private final Map<WorldPoint, Long> rejectedFireTiles = new LinkedHashMap<>();
    private String failure = "";
    private String action = "Starting";
    private String progress = "";
    private int baselineTotal;
    private long baselineXp;
    private int completedBlocks;
    private int batchSize;
    private boolean preflightDone;
    private boolean toolsPlanned;
    private boolean quantitySelected;
    private boolean awaitingFire;
    private boolean ownsWalkerSettings;
    private boolean oldDisableTeleports;
    private WorldPoint woodAnchor = TREES;
    private WorldPoint fireTile;
    private int fireLog;
    private int fireBeforeCount;
    private int fireBeforeXp;
    private long fireAttemptAt;
    private long startedAt;
    private long stoppedAt;
    private long enteredAt;
    private long blockDeadline;
    private long nextMicroBreak;
    private long breakDeadline;
    private long nextNoise;
    private long lastProgressAt;
    private long lastActionAt;
    private long pausedAt;
    private long offlineSince;
    private WorldPoint station;
    private WorldPoint lastWalkTile;
    private long lastMovedAt;
    private int walkRetries;

    public synchronized boolean run(TotalLevelConfig newConfig) {
        if (active || inCycle.get() || (walkJob != null && walkJob.running)) {
            Microbot.log("[TotalLevel] Previous worker is still stopping; re-enable shortly.");
            return false;
        }
        config = newConfig;
        woodAnchor = newConfig.useDraynorTrees() ? DRAYNOR_TREES : TREES;
        last = null;
        activity = Activity.WOODCUTTING_FIREMAKING;
        bankReason = BankReason.INITIAL;
        resumeState = null;
        account = failure = progress = "";
        tools.clear(); attempts.clear(); rejectedFireTiles.clear();
        preflightDone = toolsPlanned = quantitySelected = awaitingFire = false;
        goalObserved.set(false); changedAccount.set(false);
        observedTotal = baselineTotal = completedBlocks = 0;
        baselineXp = stoppedAt = pausedAt = offlineSince = 0;
        blockDeadline = breakDeadline = nextMicroBreak = nextNoise = lastActionAt = 0;
        fireTile = null; fireLog = 0;
        walkJob = null; walkRetries = 0; station = null;
        batchSize = Rs2Random.between(8, 15);
        walkingExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "TotalLevel-walker");
            thread.setDaemon(true);
            return thread;
        });
        oldDisableTeleports = Rs2Walker.disableTeleports;
        Rs2Walker.disableTeleports = true;
        ownsWalkerSettings = true;
        startedAt = enteredAt = lastProgressAt = now();
        state = State.INITIALIZING;
        action = "Validate account, total and prerequisites";
        active = true;
        publish();
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(
            this::cycle, 0, Rs2Random.between(250, 551), TimeUnit.MILLISECONDS);
        return true;
    }

    public Status getStatus() { return status; }
    public State getState() {
        if (changedAccount.get()) { return State.FAILED; }
        return goalObserved.get() ? State.COMPLETED : state;
    }

    /** Called by the plugin on GameTick. Never blocks or emits game input. */
    public void onGameTick() {
        if (!active || !Microbot.isLoggedIn() || Microbot.getClient().getLocalPlayer() == null) { return; }
        String name = Microbot.getClient().getLocalPlayer().getName();
        if (name == null) { return; }
        if (!account.isEmpty() && !account.equalsIgnoreCase(name)) {
            changedAccount.set(true);
            WalkJob job = walkJob;
            if (job != null) { job.cancel(); }
            publish();
            return;
        }
        if (account.isEmpty()) { account = name; }
        int total = Microbot.getClient().getTotalLevel();
        observedTotal = total;
        if (total >= TARGET_TOTAL) {
            goalObserved.set(true);
            WalkJob job = walkJob;
            if (job != null) { job.cancel(); }
            // Public FSM/overlay report COMPLETED immediately, even if a worker is finishing
            // its cancellable randomized wait. No cleanup or game input runs on this thread.
            publish();
        }
    }

    private void cycle() {
        if (!active || !inCycle.compareAndSet(false, true)) { return; }
        try {
            if (changedAccount.get()) { fail("Account changed; stopped without further inputs."); return; }
            if (goalObserved.get()) { completeGoal(); return; }
            if (!Microbot.isLoggedIn()) {
                cancelWalk();
                if (offlineSince == 0) { offlineSince = now(); }
                if (now() - offlineSince >= 90000) { fail("Disconnected for 90 seconds."); }
                return;
            }
            boolean reconnected = offlineSince != 0;
            // Read authoritative total before pause/break handling; never postpone a completed goal.
            Snapshot snapshot = snapshot();
            if (snapshot == null) { return; }
            offlineSince = 0;
            last = snapshot;
            if (!account.isEmpty() && !account.equalsIgnoreCase(snapshot.name)) {
                changedAccount.set(true);
                fail("Account changed while training."); return;
            }
            account = snapshot.name;
            observedTotal = snapshot.total;
            if (baselineTotal == 0) { baselineTotal = snapshot.total; baselineXp = snapshot.skillXp(); }
            if (snapshot.total >= TARGET_TOTAL) { goalObserved.set(true); completeGoal(); return; }
            if (!super.run() || !canInput()) {
                cancelWalk();
                if (pausedAt == 0) { pausedAt = now(); }
                return;
            }
            boolean resuming = pausedAt != 0;
            if (resuming) { shiftTimers(now() - pausedAt); pausedAt = 0; }
            if (resuming || reconnected) { resumeCancelledRoute(snapshot); }
            if (snapshot.hp <= 0) { fail("Death detected; goal not certified."); return; }
            if (snapshot.instanced) { fail("Instanced area is unsupported; no recovery route attempted."); return; }
            if (state == State.INITIALIZING) { initialize(snapshot); return; }

            String signature = Arrays.toString(snapshot.xp) + snapshot.items;
            if (!signature.equals(progress)) {
                attempts.clear(); progress = signature; lastProgressAt = now();
            }
            // Health and deadlines preempt a walking worker as well as an idle training state.
            if (snapshot.hp * 100 / Math.max(1, snapshot.maxHp) <= 60 && bankReason != BankReason.RECOVERY) {
                evacuate("Health at or below 60%; aborting non-combat training.", snapshot); return;
            }
            if (Rs2Player.isInCombat() && bankReason != BankReason.RECOVERY) {
                evacuate("Combat detected on a non-combat route.", snapshot); return;
            }
            if (now() - startedAt > TimeUnit.MINUTES.toMillis(config.maximumRuntimeMinutes())
                    && bankReason != BankReason.RECOVERY) {
                evacuate("Maximum training runtime exceeded.", snapshot); return;
            }
            if (blockDeadline != 0 && now() >= blockDeadline && bankReason != BankReason.RECOVERY
                    && bankReason != BankReason.ROTATION && state != State.ROTATION_BREAK) {
                returnForBank(BankReason.ROTATION, snapshot, "20-to-35-minute block ended; return and deposit");
                return;
            }
            if (config.enableCrashRecovery() && !inExpectedZone(snapshot.tile)) {
                if (bankReason == BankReason.RECOVERY) { fail("Displaced again during recovery."); }
                else { evacuate("Outside expected 15-tile route/activity corridor.", snapshot); }
                return;
            }
            long stateLimit = isTrainingState(state) || state == State.MICRO_BREAK ? Long.MAX_VALUE
                : TimeUnit.MINUTES.toMillis(state == State.ROTATION_BREAK ? 12 : 6);
            if (now() - enteredAt > stateLimit) {
                if (bankReason == BankReason.RECOVERY || isBankState()) { fail("State deadline exceeded: " + state); }
                else { evacuate("State deadline exceeded: " + state, snapshot); }
                return;
            }
            if (walkJob != null) { monitorWalk(snapshot); return; }
            if (bankReason == BankReason.RECOVERY && snapshot.hp * 100 / Math.max(1, snapshot.maxHp) <= 60
                    && !Rs2Inventory.getInventoryFood().isEmpty()) {
                input("Eat existing cooked food during evacuation", Rs2Player::useFood); return;
            }
            if (state == State.MICRO_BREAK && now() < breakDeadline) { return; }
            if (state == State.ROTATION_BREAK && now() < breakDeadline) { return; }
            if (handleDialogue()) { return; }
            if (isTrainingState(state) && now() - lastProgressAt > NO_PROGRESS_MS) {
                evacuate("No observed inventory/XP progress for three minutes in " + state, snapshot); return;
            }
            if (safeForVariation(snapshot)) {
                if (now() >= nextMicroBreak) {
                    resumeState = state;
                    breakDeadline = now() + randomSeconds(config.microBreakMinSeconds(), config.microBreakMaxSeconds());
                    transition(State.MICRO_BREAK, "Natural idle pause (no clicks until timer expires)");
                    return;
                }
                if (now() >= nextNoise) { variation(); return; }
            }
            switch (state) {
                case WALKING_TO_BANK:
                    if (near(snapshot.tile, BANK_ANCHOR, 3)) { transition(State.OPENING_BANK, "Observed castle bank anchor"); }
                    else { beginWalk(BANK_ANCHOR, 3, true, snapshot); }
                    break;
                case OPENING_BANK:
                    if (!near(snapshot.tile, BANK_ANCHOR, 8)) { transition(State.WALKING_TO_BANK, "Reacquire bank anchor"); }
                    else if (Rs2Bank.isOpen()) { toolsPlanned = false; transition(State.DEPOSITING_ITEMS, "Bank visibly open"); }
                    else { input("Open Lumbridge castle bank", Rs2Bank::openBank); }
                    break;
                case DEPOSITING_ITEMS:
                    if (!Rs2Bank.isOpen()) { transition(State.OPENING_BANK, "Bank closed before deposit"); }
                    else if (!snapshot.items.isEmpty()) { input("Deposit all inventory", Rs2Bank::depositAll); }
                    else if (bankReason == BankReason.ROTATION || bankReason == BankReason.RECOVERY) {
                        transition(State.CLOSING_BANK, "Empty inventory verifies deposit");
                    } else { transition(State.WITHDRAWING_TOOLS, "Inventory deposit verified"); }
                    break;
                case WITHDRAWING_TOOLS: withdrawTools(snapshot); break;
                case CLOSING_BANK: closeBank(snapshot); break;
                case STARTING_BLOCK: startBlock(snapshot); break;
                case WALKING_TO_TREES:
                    if (near(snapshot.tile, woodAnchor, 8)) { transition(State.CHOPPING, "Trees area reached"); }
                    else { beginWalk(woodAnchor, 5, true, snapshot); }
                    break;
                case CHOPPING: chop(snapshot); break;
                case SELECTING_FIRE_TILE: selectFireTile(snapshot); break;
                case WALKING_TO_FIRE_TILE:
                    if (snapshot.tile.equals(fireTile)) { transition(State.LIGHTING_LOGS, "Exact firemaking tile observed"); }
                    else { beginWalk(fireTile, 0, false, snapshot); }
                    break;
                case LIGHTING_LOGS: burn(snapshot); break;
                case WALKING_TO_FISHING_SPOT:
                    if (near(snapshot.tile, FISHING, 7)) { transition(State.FISHING, "Swamp net area reached"); }
                    else { beginWalk(FISHING, 5, true, snapshot); }
                    break;
                case FISHING: fish(snapshot); break;
                case WALKING_TO_RANGE:
                    if (near(snapshot.tile, RANGE, 3)) { quantitySelected = false; transition(State.COOKING, "Castle kitchen reached"); }
                    else { beginWalk(RANGE, 3, true, snapshot); }
                    break;
                case COOKING: cook(snapshot); break;
                case MICRO_BREAK:
                    if (now() >= breakDeadline) {
                        nextMicroBreak = now() + Rs2Random.between(300000, 600001);
                        transition(resumeState, "Micro-break finished; resume from observed state");
                    }
                    break;
                case ROTATION_BREAK:
                    if (now() >= breakDeadline) {
                        activity = activity == Activity.WOODCUTTING_FIREMAKING
                            ? Activity.FISHING_COOKING : Activity.WOODCUTTING_FIREMAKING;
                        bankReason = BankReason.INITIAL;
                        blockDeadline = 0;
                        transition(State.OPENING_BANK, "Load next activity after bank break");
                    }
                    break;
                default: break;
            }
        } catch (RuntimeException exception) {
            if (active && !goalObserved.get()) { fail("Unexpected failure: " + exception); }
        } finally {
            publish();
            inCycle.set(false);
        }
    }

    private void initialize(Snapshot snapshot) {
        if (!validConfig(config)) { fail("Invalid break/runtime settings; check configured ranges and min <= max."); return; }
        baselineTotal = snapshot.total;
        baselineXp = snapshot.skillXp();
        station = snapshot.tile;
        // The Stage 0.2 pipeline completes this quest; its range is otherwise locked.
        if (snapshot.cookQuest < 2) {
            fail("Cook's Assistant must be completed (Stage 0.2) to use the castle range."); return;
        }
        if (Rs2PathApi.isInWilderness(snapshot.tile)) {
            fail("Refusing to start in the Wilderness."); return;
        }
        transition(State.WALKING_TO_BANK, "Bank initial inventory; preflight both activities");
    }

    private void withdrawTools(Snapshot snapshot) {
        if (!Rs2Bank.isOpen()) { transition(State.OPENING_BANK, "Reopen bank before loading tools"); return; }
        if (!Rs2Bank.hasWithdrawAsItem()) { input("Set unnoted withdrawal mode", Rs2Bank::setWithdrawAsItem); return; }
        if (!toolsPlanned) {
            int axe = chooseAxe(snapshot);
            if (!preflightDone) {
                if (axe < 0 || Rs2Bank.count(ItemID.TINDERBOX) + snapshot.count(ItemID.TINDERBOX) < 1
                        || Rs2Bank.count(ItemID.NET) + snapshot.count(ItemID.NET) < 1) {
                    fail("Bank a usable axe, tinderbox and small fishing net before starting."); return;
                }
                preflightDone = true;
            }
            tools.clear();
            if (activity == Activity.WOODCUTTING_FIREMAKING) {
                if (axe < 0) { fail("No usable axe in bank or equipment."); return; }
                if (axe != 0) { tools.put(axe, 1); }
                tools.put(ItemID.TINDERBOX, 1);
            } else { tools.put(ItemID.NET, 1); }
            for (Map.Entry<Integer, Integer> entry : tools.entrySet()) {
                if (Rs2Bank.count(entry.getKey()) + snapshot.count(entry.getKey()) < entry.getValue()) {
                    fail("Missing required bank tool, item ID " + entry.getKey()); return;
                }
            }
            toolsPlanned = true;
        }
        for (Map.Entry<Integer, Integer> entry : tools.entrySet()) {
            int missing = entry.getValue() - snapshot.count(entry.getKey());
            if (missing > 0) {
                int id = entry.getKey();
                input("Withdraw tool " + id, () -> Rs2Bank.withdrawX(id, missing));
                return;
            }
        }
        transition(State.CLOSING_BANK, "Required unnoted tool quantities observed");
    }

    private int chooseAxe(Snapshot snapshot) {
        for (int i = 0; i < AXES.length; i++) {
            if (snapshot.levels[0] < AXE_LEVELS[i]) { continue; }
            if (snapshot.equipment.contains(AXES[i])) { return 0; }
            if (snapshot.count(AXES[i]) + Rs2Bank.count(AXES[i]) > 0) { return AXES[i]; }
        }
        return -1;
    }

    private void closeBank(Snapshot snapshot) {
        if (Rs2Bank.isOpen()) { input("Close bank", Rs2Bank::closeBank); return; }
        if (bankReason == BankReason.RECOVERY) { fail(failure + " Inventory deposited at Lumbridge bank."); return; }
        if (bankReason == BankReason.ROTATION) {
            completedBlocks++;
            blockDeadline = 0;
            breakDeadline = now() + randomSeconds(config.rotationBreakMinSeconds(), config.rotationBreakMaxSeconds());
            transition(State.ROTATION_BREAK, "Rotation bank deposit verified; short bank break");
            return;
        }
        transition(State.STARTING_BLOCK, "Closed bank observed");
    }

    private void startBlock(Snapshot snapshot) {
        if (!near(snapshot.tile, BANK_ANCHOR, 8)) { evacuate("Left bank before block setup.", snapshot); return; }
        if (blockDeadline == 0) {
            blockDeadline = now() + randomBlockMillis();
            nextMicroBreak = now() + Rs2Random.between(300000, 600001);
            nextNoise = now() + Rs2Random.between(45000, 150001);
            journal("New activity block; duration seconds=" + (blockDeadline - now()) / 1000);
        }
        bankReason = BankReason.BATCH;
        awaitingFire = false; fireTile = null; fireLog = 0;
        batchSize = Rs2Random.between(8, 15);
        transition(activity == Activity.WOODCUTTING_FIREMAKING ? State.WALKING_TO_TREES : State.WALKING_TO_FISHING_SPOT,
            "Start/resume " + activity);
    }

    private void chop(Snapshot snapshot) {
        if (snapshot.logs() >= batchSize || snapshot.usedSlots >= 28) {
            if (snapshot.logs() == 0) { evacuate("Full inventory without burnable logs.", snapshot); }
            else { transition(State.SELECTING_FIRE_TILE, "Collected log batch; train Firemaking"); }
            return;
        }
        if (!hasAxe(snapshot) || snapshot.count(ItemID.TINDERBOX) == 0) {
            evacuate("Axe/tinderbox missing while woodcutting.", snapshot); return;
        }
        if (snapshot.animating || snapshot.moving || now() - lastActionAt < 4200) { return; }
        int tier = logTier(snapshot.levels[0], snapshot.levels[1]);
        TileObject tree = findTree(tier, snapshot.tile);
        if (tree == null) { failedAttempt("No loaded usable tree in Lumbridge woodcutting area"); return; }
        // Prove a closed-loop route to beside a distant tree before interacting.
        if (!near(snapshot.tile, tree.getWorldLocation(), 3)) {
            beginWalk(tree.getWorldLocation(), 2, false, snapshot); return;
        }
        input("Chop loaded tree " + tree.getId(), () -> Rs2GameObject.interact(tree, "Chop down", false));
    }

    private TileObject findTree(int tier, WorldPoint player) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            List<TileObject> trees = Rs2GameObject.<TileObject>getAll(object -> {
                if (!near(object.getWorldLocation(), woodAnchor, 30)) { return false; }
                ObjectComposition definition = composition(object);
                return definition != null && treeTier(normal(definition.getName())) <= tier
                    && treeTier(normal(definition.getName())) > 0 && hasAction(definition.getActions(), "Chop down");
            });
            return trees.stream().min(Comparator.<TileObject>comparingInt(object -> {
                ObjectComposition definition = composition(object);
                return definition == null ? 0 : -treeTier(normal(definition.getName()));
            }).thenComparingInt(object -> object.getWorldLocation().distanceTo2D(player))).orElse(null);
        }).orElse(null);
    }

    private void selectFireTile(Snapshot snapshot) {
        fireLog = nextLog(snapshot);
        if (fireLog == 0) {
            if (snapshot.logs() != 0) { evacuate("Inventory logs exceed current Firemaking level.", snapshot); return; }
            batchSize = Rs2Random.between(8, 15);
            transition(State.CHOPPING, "Observed log batch consumed; resume Woodcutting"); return;
        }
        if (snapshot.count(ItemID.TINDERBOX) == 0) { evacuate("Tinderbox missing.", snapshot); return; }
        rejectedFireTiles.entrySet().removeIf(entry -> entry.getValue() <= now());
        fireTile = findFireTile(snapshot.tile);
        if (fireTile == null) { failedAttempt("No clear reachable firemaking tile with a clear west step"); return; }
        transition(State.WALKING_TO_FIRE_TILE, "Choose validated outdoor tile, westward step clear");
    }

    private WorldPoint findFireTile(WorldPoint player) {
        List<WorldPoint> candidates = Microbot.getClientThread().runOnClientThreadOptional(() -> {
            List<WorldPoint> result = new ArrayList<>();
            for (int x = -7; x <= 7; x++) {
                for (int y = -7; y <= 7; y++) {
                    WorldPoint candidate = point(player.getX() + x, player.getY() + y, 0);
                    if (near(candidate, woodAnchor, 25) && !rejectedFireTiles.containsKey(candidate)
                            && validFireTile(candidate)) { result.add(candidate); }
                }
            }
            return result;
        }).orElse(Collections.emptyList());
        candidates.sort(Comparator.comparingInt(candidate -> candidate.distanceTo2D(player)));
        for (WorldPoint candidate : candidates) {
            if (!canInput()) { return null; }
            // The off-thread walking worker will prove the complete route before emitting input.
            // Prefer the current tile; otherwise choose the nearest geometrically valid candidate.
            if (candidate.equals(player) || Rs2GameObject.canReach(candidate, 1, 1, 1, 1)) { return candidate; }
        }
        return null;
    }

    private boolean validFireTile(WorldPoint point) {
        WorldView view = Microbot.getClient().getTopLevelWorldView();
        if (view == null || point.getPlane() != 0 || !near(point, woodAnchor, 25)) { return false; }
        WorldPoint west = point(point.getX() - 1, point.getY(), 0);
        if (!Rs2Tile.isWalkable(point) || !Rs2Tile.isWalkable(west)
                || !new WorldArea(point, 1, 1).canTravelInDirection(view, -1, 0)) { return false; }
        return emptyObjectTile(view, point) && emptyObjectTile(view, west);
    }

    private static boolean emptyObjectTile(WorldView view, WorldPoint point) {
        LocalPoint local = LocalPoint.fromWorld(view, point);
        if (local == null) { return false; }
        Tile tile = view.getScene().getTiles()[point.getPlane()][local.getSceneX()][local.getSceneY()];
        if ((view.getTileSettings()[point.getPlane()][local.getSceneX()][local.getSceneY()]
                & Constants.TILE_FLAG_UNDER_ROOF) != 0) { return false; }
        if (tile == null || tile.getWallObject() != null || tile.getDecorativeObject() != null
                || tile.getGroundObject() != null) { return false; }
        if (tile.getGameObjects() != null) {
            for (TileObject object : tile.getGameObjects()) { if (object != null) { return false; } }
        }
        return true;
    }

    private void burn(Snapshot snapshot) {
        if (awaitingFire) {
            if (fireSucceeded(fireBeforeCount, snapshot.count(fireLog), fireBeforeXp, snapshot.xp[1])) {
                awaitingFire = false;
                transition(State.SELECTING_FIRE_TILE, "Log consumption and Firemaking XP verified");
            } else if (now() - fireAttemptAt > 25000) {
                rejectedFireTiles.put(fireTile, now() + 120000);
                awaitingFire = false;
                failedAttempt("Fire did not consume a log and award XP");
                transition(State.SELECTING_FIRE_TILE, "Fire attempt timed out; select another clear tile");
            }
            return;
        }
        if (snapshot.count(fireLog) == 0) { transition(State.SELECTING_FIRE_TILE, "Recheck remaining logs"); return; }
        if (!snapshot.tile.equals(fireTile) || snapshot.moving) { transition(State.SELECTING_FIRE_TILE, "Revalidate exact fire position"); return; }
        boolean valid = Microbot.getClientThread().runOnClientThreadOptional(() -> validFireTile(fireTile)).orElse(false);
        if (!valid) { transition(State.SELECTING_FIRE_TILE, "Tile changed before fire attempt"); return; }
        if (snapshot.animating) { return; }
        if (!Rs2Tab.isCurrentTab(InterfaceTab.INVENTORY)) {
            input("Restore Inventory before Firemaking", () -> Rs2Tab.switchTo(InterfaceTab.INVENTORY)); return;
        }
        fireBeforeCount = snapshot.count(fireLog);
        fireBeforeXp = snapshot.xp[1];
        fireAttemptAt = now();
        awaitingFire = true;
        input("Use tinderbox on log " + fireLog, () -> Rs2Inventory.combine(ItemID.TINDERBOX, fireLog));
    }

    private void fish(Snapshot snapshot) {
        if (snapshot.usedSlots >= 28) {
            if (snapshot.rawFish() == 0) { evacuate("Full inventory without raw net fish.", snapshot); }
            else { transition(State.WALKING_TO_RANGE, "Fish inventory full; cook at castle range"); }
            return;
        }
        if (snapshot.count(ItemID.NET) == 0) { evacuate("Small fishing net missing.", snapshot); return; }
        if (snapshot.animating || snapshot.moving || now() - lastActionAt < 5000) { return; }
        Rs2NpcModel spot = Microbot.getClientThread().runOnClientThreadOptional(() -> Rs2Npc.getNpcs(npc ->
            near(npc.getWorldLocation(), FISHING, 18) && npc.getComposition() != null
                && hasAction(npc.getComposition().getActions(), "Net"))
            .min(Comparator.comparingInt(npc -> npc.getWorldLocation().distanceTo2D(snapshot.tile))).orElse(null)).orElse(null);
        if (spot == null) { failedAttempt("No loaded small-net fishing spot in Lumbridge swamp"); return; }
        if (!near(snapshot.tile, spot.getWorldLocation(), 3)) {
            beginWalk(spot.getWorldLocation(), 2, false, snapshot); return;
        }
        input("Net current fishing spot", () -> Rs2Npc.interact(spot, "Net"));
    }

    private void cook(Snapshot snapshot) {
        int raw = snapshot.count(ItemID.RAW_SHRIMP) > 0 ? ItemID.RAW_SHRIMP
            : snapshot.count(ItemID.RAW_ANCHOVIES) > 0 ? ItemID.RAW_ANCHOVIES : 0;
        if (raw == 0) {
            returnForBank(BankReason.BATCH, snapshot, "All raw fish consumed, including any burns; bank products"); return;
        }
        if (Rs2Widget.isProductionWidgetOpen()) {
            if (!quantitySelected) {
                input("Select cooking quantity All", () -> {
                    Widget all = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                        Widget widget = Microbot.getClient().getWidget(InterfaceID.Skillmulti.ALL);
                        return widget != null && !widget.isHidden()
                            && (hasAction(widget.getActions(), "All") || normal(widget.getText()).equals("all")) ? widget : null;
                    }).orElse(null);
                    boolean clicked = all != null && Rs2Widget.clickWidget(all);
                    if (clicked) { quantitySelected = true; }
                    return clicked;
                });
                return;
            }
            String product = raw == ItemID.RAW_SHRIMP ? "shrimps" : "anchovies";
            int cooked = raw == ItemID.RAW_SHRIMP ? ItemID.SHRIMP : ItemID.ANCHOVIES;
            Widget button = cookingButton(raw, cooked, product);
            if (button == null) { failedAttempt("Unrecognized cooking choice for " + product); return; }
            readThen("Cook all " + product, () -> Rs2Widget.clickWidget(button));
            return;
        }
        if (snapshot.animating || snapshot.moving || now() - lastActionAt < 5000) { return; }
        TileObject range = Rs2GameObject.<TileObject>getAll(object -> object.getId() == ObjectID.COOKSQUESTRANGE
            && near(object.getWorldLocation(), RANGE, 10)).stream()
            .min(Comparator.comparingInt(object -> object.getWorldLocation().distanceTo2D(snapshot.tile))).orElse(null);
        if (range == null) { failedAttempt("Castle cooking range is not loaded"); return; }
        if (!near(snapshot.tile, range.getWorldLocation(), 3)) {
            beginWalk(range.getWorldLocation(), 2, false, snapshot); return;
        }
        if (!Rs2Tab.isCurrentTab(InterfaceTab.INVENTORY)) {
            input("Restore Inventory before Cooking", () -> Rs2Tab.switchTo(InterfaceTab.INVENTORY)); return;
        }
        quantitySelected = false;
        String rawName = raw == ItemID.RAW_SHRIMP ? "Raw shrimps" : "Raw anchovies";
        input("Use " + rawName + " on castle range", () -> Rs2Inventory.useUnNotedItemOnObject(rawName, range));
    }

    /** Match the live recipe's item/text AND a live Cook action, never a guessed menu index. */
    private Widget cookingButton(int raw, int cooked, String product) {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            Widget root = Microbot.getClient().getWidget(InterfaceID.Skillmulti.UNIVERSE);
            List<Widget> widgets = new ArrayList<>();
            flatten(root, widgets, Collections.newSetFromMap(new IdentityHashMap<Widget, Boolean>()));
            for (Widget widget : widgets) {
                if (hasAction(widget.getActions(), "Cook") && recipeMatches(widget, raw, cooked, product,
                        Collections.newSetFromMap(new IdentityHashMap<Widget, Boolean>()))) { return widget; }
            }
            return null;
        }).orElse(null);
    }

    private static boolean recipeMatches(Widget widget, int raw, int cooked, String product, Set<Widget> seen) {
        if (widget == null || widget.isHidden() || !seen.add(widget)) { return false; }
        if (widget.getItemId() == raw || widget.getItemId() == cooked
                || normal(widget.getName()).contains(product) || normal(widget.getText()).contains(product)) { return true; }
        Widget[][] groups = {widget.getChildren(), widget.getDynamicChildren(), widget.getStaticChildren(), widget.getNestedChildren()};
        for (Widget[] children : groups) {
            if (children != null) {
                for (Widget child : children) { if (recipeMatches(child, raw, cooked, product, seen)) { return true; } }
            }
        }
        return false;
    }

    private static void flatten(Widget widget, List<Widget> output, Set<Widget> seen) {
        if (widget == null || widget.isHidden() || !seen.add(widget)) { return; }
        Widget[][] groups = {widget.getChildren(), widget.getDynamicChildren(), widget.getStaticChildren(), widget.getNestedChildren()};
        for (Widget[] children : groups) { if (children != null) { for (Widget child : children) { flatten(child, output, seen); } } }
        output.add(widget); // Prefer the deepest actionable recipe, not an enclosing container.
    }

    private boolean handleDialogue() {
        if (!Rs2Dialogue.isInDialogue()) { return false; }
        if (Rs2Dialogue.hasContinue()) {
            readThen("Read and continue game message", () -> {
                if (!Rs2Dialogue.hasContinue()) { return false; }
                Rs2Dialogue.clickContinue(); return true;
            });
        } else {
            // Never select unknown NPC/tutorial/random-event options. Escape is not an option index.
            readThen("Close unexpected dialogue without choosing options", () -> {
                Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE); return true;
            });
        }
        return true;
    }

    private boolean safeForVariation(Snapshot snapshot) {
        return (state == State.CHOPPING || state == State.FISHING)
            && !snapshot.moving && !snapshot.animating && !snapshot.combat
            && now() - lastActionAt >= 4500 && !Rs2Bank.isOpen() && !Rs2Widget.isProductionWidgetOpen();
    }

    private void variation() {
        nextNoise = now() + Rs2Random.between(45000, 150001);
        List<Integer> choices = new ArrayList<>();
        if (config.cameraVariation()) { choices.add(0); }
        if (config.tabPeeking()) { choices.add(1); }
        if (config.mouseOffscreen()) { choices.add(2); }
        if (choices.isEmpty() || !canInput()) { return; }
        int choice = choices.get(Rs2Random.between(0, choices.size()));
        if (choice == 0) {
            input("Occasional camera yaw variation", () -> {
                Rs2Camera.setYaw((Rs2Camera.getYaw() + Rs2Random.between(120, 651)) % 2048); return true;
            });
        } else if (choice == 1) {
            input("Peek at a non-interactive tab", () -> Rs2Tab.switchTo(
                Rs2Random.between(0, 2) == 0 ? InterfaceTab.SKILLS : InterfaceTab.EQUIPMENT));
            Global.sleep(Rs2Random.between(1800, 4201));
            if (canInput()) { input("Restore Inventory tab", () -> Rs2Tab.switchTo(InterfaceTab.INVENTORY)); }
        } else {
            input("Move pointer off canvas without clicking", () -> {
                int[] dimensions = Microbot.getClientThread().runOnClientThreadOptional(() -> new int[] {
                    Microbot.getClient().getCanvasWidth(), Microbot.getClient().getCanvasHeight()}).orElse(null);
                if (dimensions == null || dimensions[0] < 1 || dimensions[1] < 1) { return false; }
                // Pinned VirtualMouse.move(Point) forwards raw coordinates to AwtEmitter; no clamp.
                if (Microbot.naturalMouse != null) { Microbot.naturalMouse.moveOffScreen(); }
                else { Microbot.getMouse().move(-1, Rs2Random.between(0, dimensions[1])); }
                return true;
            });
        }
    }

    private void returnForBank(BankReason reason, Snapshot snapshot, String message) {
        cancelWalk();
        bankReason = reason;
        awaitingFire = false;
        toolsPlanned = false;
        walkRetries = 0;
        station = snapshot.tile;
        transition(State.WALKING_TO_BANK, message);
    }

    private void evacuate(String reason, Snapshot snapshot) {
        if (failure.isEmpty()) { failure = reason; }
        returnForBank(BankReason.RECOVERY, snapshot, "Evacuate then fail: " + reason);
    }

    private void beginWalk(WorldPoint base, int radius, boolean useJitter, Snapshot snapshot) {
        if (base == null || walkJob != null || !canInput()) { return; }
        if (Rs2Widget.isProductionWidgetOpen()) {
            input("Close production interface before travel", () -> { Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE); return true; });
            return;
        }
        WorldPoint target = useJitter ? jitter(base) : base;
        WalkJob job = new WalkJob(snapshot.tile, target, radius);
        walkJob = job;
        lastWalkTile = snapshot.tile;
        lastMovedAt = now();
        action = "Walk to " + base;
        job.future = walkingExecutor.submit(job);
    }

    private void monitorWalk(Snapshot snapshot) {
        WalkJob job = walkJob;
        if (job == null) { return; }
        if (!job.running && job.future != null && job.future.isDone()) {
            walkJob = null;
            if (job.error != null) {
                if (bankReason == BankReason.RECOVERY) { fail("Evacuation route failed: " + job.error); }
                else { evacuate("Route failed: " + job.error, snapshot); }
            } else if (!job.cancelled) {
                if (near(snapshot.tile, job.target, job.radius)) { walkRetries = 0; }
                else if (++walkRetries > MAX_WALK_RETRIES) {
                    if (bankReason == BankReason.RECOVERY) { fail("Repeated early evacuation walker exits."); }
                    else { evacuate("Repeated early walker exits before destination.", snapshot); }
                }
            }
            return;
        }
        if (!snapshot.tile.equals(lastWalkTile)) { lastWalkTile = snapshot.tile; lastMovedAt = now(); }
        else if (now() - lastMovedAt >= STUCK_MS) {
            if (++walkRetries > MAX_WALK_RETRIES) {
                if (bankReason == BankReason.RECOVERY) { fail("Evacuation exhausted eight-second walk retries."); }
                else { evacuate("Walk exhausted eight-second stall retries.", snapshot); }
                return;
            }
            Microbot.log("[TotalLevel] Eight-second stall; cancel/replan attempt " + walkRetries);
            job.cancel();
            Rs2Walker.setTarget(null);
            lastMovedAt = now();
        }
    }

    private final class WalkJob implements Runnable {
        private final WorldPoint origin;
        private final WorldPoint target;
        private final int radius;
        private volatile List<WorldPoint> corridor;
        private volatile boolean running;
        private volatile boolean cancelled;
        private volatile String error;
        private volatile Future<?> future;
        private WalkJob(WorldPoint origin, WorldPoint target, int radius) {
            this.origin = origin; this.target = target; this.radius = radius;
            corridor = Collections.singletonList(origin);
        }
        @Override public void run() {
            running = true;
            try {
                if (cancelled || !canInput()) { return; }
                Rs2RouteResult route = Rs2PathApi.plan(Rs2RouteRequest.to(origin, target).withBankItems(false));
                if (cancelled || !canInput()) { return; }
                if (!route.isSearchCompleted() || !route.isTargetReached(radius) || route.getPath().isEmpty()) {
                    error = "No complete route to " + target + " (radius " + radius + ")"; return;
                }
                if (route.getPath().stream().anyMatch(Rs2PathApi::isInWilderness)) {
                    error = "Refusing a Wilderness route"; return;
                }
                if (route.getTransportSteps().stream().anyMatch(step -> step.getTransport().isPresent()
                        && step.getTransport().get().isTeleport())) {
                    error = "Refusing a teleport route"; return;
                }
                corridor = Collections.unmodifiableList(new ArrayList<>(route.getPath()));
                if (!cancelled && canInput()) { Rs2Walker.walkTo(target, radius); actionDelay(); }
            } catch (RuntimeException exception) {
                if (!cancelled && active && !goalObserved.get()) { error = exception.toString(); }
            } finally { running = false; }
        }
        private void cancel() { cancelled = true; Future<?> current = future; if (current != null) { current.cancel(true); } }
    }

    private void cancelWalk() {
        WalkJob job = walkJob;
        if (job != null) { job.cancel(); }
        Rs2Walker.setTarget(null);
        // Do not discard a running job: main worker must not click while it is unwinding.
    }

    private boolean inExpectedZone(WorldPoint tile) {
        WalkJob job = walkJob;
        if (job != null && !job.cancelled) { return inCorridor(tile, job.corridor, 15); }
        if (state == State.WALKING_TO_BANK) { return near(tile, station, 15) || near(tile, BANK_ANCHOR, 15); }
        // After the worker acknowledges arrival there is one poll with no WalkJob, before
        // the FSM processes the destination. Both endpoints must remain authorized then.
        if (state == State.WALKING_TO_TREES) { return near(tile, station, 15) || near(tile, woodAnchor, 45); }
        if (state == State.WALKING_TO_FISHING_SPOT) { return near(tile, station, 15) || near(tile, FISHING, 33); }
        if (state == State.WALKING_TO_RANGE) { return near(tile, station, 15) || near(tile, RANGE, 15); }
        if (isBankState() || state == State.ROTATION_BREAK || state == State.STARTING_BLOCK) {
            return near(tile, BANK_ANCHOR, 15);
        }
        State effective = state == State.MICRO_BREAK ? resumeState : state;
        if (effective == State.CHOPPING || effective == State.SELECTING_FIRE_TILE
                || effective == State.WALKING_TO_FIRE_TILE || effective == State.LIGHTING_LOGS) {
            return near(tile, woodAnchor, 45);
        }
        if (effective == State.FISHING) { return near(tile, FISHING, 33); }
        if (effective == State.COOKING) { return near(tile, RANGE, 15); }
        return near(tile, station, 15);
    }

    private void input(String label, BooleanSupplier operation) {
        if (!canInput() || walkJob != null || !failedAttempt(label)) { return; }
        action = label;
        lastActionAt = now();
        if (canInput()) { operation.getAsBoolean(); actionDelay(); }
    }

    private void readThen(String label, BooleanSupplier operation) {
        Global.sleep(Rs2Random.between(1800, 4201));
        if (canInput()) { input(label, operation); }
    }

    private boolean failedAttempt(String label) {
        if (!active || goalObserved.get()) { return false; }
        int count = attempts.getOrDefault(label, 0) + 1;
        attempts.put(label, count);
        if (count > MAX_ATTEMPTS) {
            if (bankReason == BankReason.RECOVERY || isBankState() || last == null) {
                fail("Repeated action/no-target failure: " + label);
            } else { evacuate("Repeated action/no-target failure: " + label, last); }
            return false;
        }
        return true;
    }

    private void transition(State next, String reason) {
        if (!active || goalObserved.get() || changedAccount.get()) { return; }
        State previous = state;
        state = next; enteredAt = now(); action = reason;
        if (last != null) { station = last.tile; }
        if (isTrainingState(next)) { lastProgressAt = now(); }
        Microbot.log("[TotalLevel] " + previous + " -> " + next + " | " + reason);
        journal(reason); publish();
    }

    private void completeGoal() {
        if (!active || changedAccount.get() || !goalObserved.get()) { return; }
        cancelWalk();
        // Completion preempts breaks and bank trips. Never wait for a final deposit.
        boolean gathering = isTrainingState(state) || state == State.MICRO_BREAK;
        state = State.COMPLETED;
        failure = "";
        action = "Verified client total=" + Math.max(observedTotal, last == null ? 0 : last.total)
            + "; stopped immediately, inventory not necessarily banked";
        publish();
        journal(action);
        Microbot.log("[TotalLevel] COMPLETED | " + action);
        if (gathering) { haltOwnActivity(); }
        shutdown();
    }

    /** One terminal stop-click, only when no walker or human owns input. No bank trip. */
    private void haltOwnActivity() {
        try {
            WalkJob job = walkJob;
            if (!active || changedAccount.get() || (job != null && job.running) || !Microbot.isLoggedIn()
                    || Microbot.pauseAllScripts.get() || InputArbiter.isHuman()) { return; }
            Snapshot current = snapshot();
            if (current == null || !account.equalsIgnoreCase(current.name) || current.total < TARGET_TOTAL) { return; }
            // Clicking the minimap cancels an ongoing gathering/processing action. Choose only
            // an adjacent, collision-proven tile; do not open dialogs or resume a make-all batch.
            WorldPoint stop = Microbot.getClientThread().runOnClientThreadOptional(() -> {
                WorldView view = Microbot.getClient().getTopLevelWorldView();
                if (view == null) { return null; }
                int[][] directions = {{0, 1}, {1, 0}, {0, -1}, {-1, 0}};
                for (int[] direction : directions) {
                    WorldPoint candidate = point(current.tile.getX() + direction[0], current.tile.getY() + direction[1], current.tile.getPlane());
                    if (Rs2Tile.isWalkable(candidate) && new WorldArea(current.tile, 1, 1)
                            .canTravelInDirection(view, direction[0], direction[1])) { return candidate; }
                }
                return null;
            }).orElse(null);
            if (active && stop != null && !changedAccount.get() && !Microbot.pauseAllScripts.get() && !InputArbiter.isHuman()) {
                Rs2Walker.walkMiniMap(stop);
                actionDelay();
            }
        } catch (RuntimeException exception) {
            Microbot.log("[TotalLevel] Goal verified; terminal stop-click unavailable: " + exception);
        }
    }

    private void fail(String reason) {
        if (!active) { return; }
        if (goalObserved.get() && !changedAccount.get()) { completeGoal(); return; }
        // Only completeGoal may certify success from an authoritative total.
        state = State.FAILED;
        failure = reason; action = reason;
        Microbot.log("[TotalLevel] FAILED | " + reason);
        journal(reason);
        shutdown();
    }

    @Override public synchronized void shutdown() {
        if (active && goalObserved.get() && !changedAccount.get()) {
            state = State.COMPLETED;
            failure = "";
            action = "Verified total >= 100; stopped, inventory not necessarily banked";
            journal(action);
        }
        if (active && state != State.COMPLETED && state != State.FAILED) {
            state = goalObserved.get() && !changedAccount.get() ? State.COMPLETED : State.FAILED;
            action = state == State.COMPLETED ? "Verified total >= 100; stopped" : "Plugin stopped before completion";
            if (state == State.FAILED) { failure = action; } else { failure = ""; }
            journal(action);
        }
        active = false;
        if (stoppedAt == 0) { stoppedAt = now(); }
        cancelWalk();
        if (walkingExecutor != null) { walkingExecutor.shutdownNow(); }
        boolean wasPaused = Microbot.pauseAllScripts.get();
        try { super.shutdown(); }
        finally {
            if (wasPaused) { Microbot.pauseAllScripts.set(true); }
            if (ownsWalkerSettings) { Rs2Walker.disableTeleports = oldDisableTeleports; ownsWalkerSettings = false; }
            publish();
        }
    }

    private void resumeCancelledRoute(Snapshot snapshot) {
        WalkJob job = walkJob;
        if (job != null && job.cancelled && inCorridor(snapshot.tile, job.corridor, 15)) {
            // Pause/disconnect can leave the character halfway through an authorized route.
            // Rebase only inside that original corridor, never blindly authorize displacement.
            station = snapshot.tile;
            lastWalkTile = snapshot.tile;
            lastMovedAt = now();
        }
    }

    private void shiftTimers(long elapsed) {
        startedAt += elapsed; enteredAt += elapsed; lastProgressAt += elapsed;
        if (blockDeadline != 0) { blockDeadline += elapsed; }
        if (breakDeadline != 0) { breakDeadline += elapsed; }
        if (nextMicroBreak != 0) { nextMicroBreak += elapsed; }
        if (nextNoise != 0) { nextNoise += elapsed; }
        if (fireAttemptAt != 0) { fireAttemptAt += elapsed; }
        lastMovedAt += elapsed;
    }

    private void journal(String reason) {
        if (account.isEmpty()) { return; }
        try {
            configManager.setConfiguration(TotalLevelConfig.GROUP,
                "checkpoint_" + account.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_"),
                Instant.now() + " | " + state + " | " + activity + " | total=" + observedTotal
                    + " | levels=" + (last == null ? "[]" : Arrays.toString(last.levels))
                    + " | " + reason.replace('\n', ' '));
        } catch (RuntimeException exception) { Microbot.log("[TotalLevel] Journal write failed: " + exception); }
    }

    private void publish() {
        Snapshot snapshot = last;
        State visibleState = getState();
        int total = Math.max(observedTotal, snapshot == null ? 0 : snapshot.total);
        long end = stoppedAt == 0 ? now() : stoppedAt;
        String detail = visibleState == State.COMPLETED
            ? "Verified client total=" + total + "; stopped, inventory not necessarily banked"
            : changedAccount.get() ? "Account changed; stopping without further inputs"
            : failure.isEmpty() ? action : failure;
        status = new Status(visibleState.name(), activity == null ? "None" : activity.name()
                + (activity == Activity.WOODCUTTING_FIREMAKING && woodAnchor.equals(DRAYNOR_TREES) ? " (Draynor)" : " (Lumbridge)"),
            total, baselineTotal, snapshot == null ? new int[4] : snapshot.levels,
            snapshot == null ? 0 : Math.max(0, snapshot.skillXp() - baselineXp),
            startedAt == 0 ? 0 : Math.max(0, end - startedAt),
            active && !goalObserved.get() && blockDeadline != 0 ? Math.max(0, blockDeadline - now()) : 0,
            completedBlocks, detail);
    }

    public static final class Status {
        public final String state;
        public final String activity;
        public final int total;
        public final int baselineTotal;
        public final int woodcutting;
        public final int firemaking;
        public final int fishing;
        public final int cooking;
        public final long gainedXp;
        public final long runtimeMillis;
        public final long blockRemainingMillis;
        public final int completedBlocks;
        public final String detail;
        private Status(String state, String activity, int total, int baselineTotal, int[] levels,
                long gainedXp, long runtimeMillis, long blockRemainingMillis, int completedBlocks, String detail) {
            this.state = state; this.activity = activity; this.total = total; this.baselineTotal = baselineTotal;
            woodcutting = levels[0]; firemaking = levels[1]; fishing = levels[2]; cooking = levels[3];
            this.gainedXp = gainedXp; this.runtimeMillis = runtimeMillis;
            this.blockRemainingMillis = blockRemainingMillis; this.completedBlocks = completedBlocks; this.detail = detail;
        }
    }

    private Snapshot snapshot() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (!Microbot.isLoggedIn() || Microbot.getClient().getLocalPlayer() == null) { return null; }
            ItemContainer inventory = Microbot.getClient().getItemContainer(InventoryID.INVENTORY);
            WorldView view = Microbot.getClient().getTopLevelWorldView();
            if (inventory == null || view == null) { return null; }
            Snapshot snapshot = new Snapshot();
            snapshot.name = Microbot.getClient().getLocalPlayer().getName();
            if (snapshot.name == null) { return null; }
            snapshot.tile = Microbot.getClient().getLocalPlayer().getWorldLocation();
            snapshot.total = Microbot.getClient().getTotalLevel();
            snapshot.hp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
            snapshot.maxHp = Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
            snapshot.cookQuest = Microbot.getClient().getVarpValue(VarPlayerID.COOKQUEST);
            snapshot.instanced = view.isInstance();
            snapshot.animating = Rs2Player.isAnimating();
            snapshot.moving = Rs2Player.isMoving();
            snapshot.combat = Rs2Player.isInCombat();
            for (int i = 0; i < SKILLS.length; i++) {
                snapshot.levels[i] = Microbot.getClient().getRealSkillLevel(SKILLS[i]);
                snapshot.xp[i] = Microbot.getClient().getSkillExperience(SKILLS[i]);
            }
            for (Item item : inventory.getItems()) {
                if (item.getId() >= 0 && item.getQuantity() > 0) {
                    snapshot.items.merge(item.getId(), item.getQuantity(), Integer::sum); snapshot.usedSlots++;
                }
            }
            ItemContainer equipment = Microbot.getClient().getItemContainer(InventoryID.EQUIPMENT);
            if (equipment != null) {
                for (Item item : equipment.getItems()) { if (item.getId() >= 0) { snapshot.equipment.add(item.getId()); } }
            }
            return snapshot;
        }).orElse(null);
    }

    private static final class Snapshot {
        private String name;
        private WorldPoint tile;
        private int total;
        private int hp;
        private int maxHp;
        private int cookQuest;
        private int usedSlots;
        private boolean instanced;
        private boolean animating;
        private boolean moving;
        private boolean combat;
        private final int[] levels = new int[4];
        private final int[] xp = new int[4];
        private final Map<Integer, Integer> items = new LinkedHashMap<>();
        private final Set<Integer> equipment = new HashSet<>();
        private int count(int id) { return items.getOrDefault(id, 0); }
        private int logs() { return count(ItemID.LOGS) + count(ItemID.OAK_LOGS) + count(ItemID.WILLOW_LOGS); }
        private int rawFish() { return count(ItemID.RAW_SHRIMP) + count(ItemID.RAW_ANCHOVIES); }
        private long skillXp() { long total = 0; for (int value : xp) { total += value; } return total; }
    }

    private static boolean validConfig(TotalLevelConfig config) {
        return config.microBreakMinSeconds() >= 5 && config.microBreakMinSeconds() <= 120
            && config.microBreakMaxSeconds() <= 180
            && config.microBreakMinSeconds() <= config.microBreakMaxSeconds()
            && config.rotationBreakMinSeconds() >= 10 && config.rotationBreakMinSeconds() <= 300
            && config.rotationBreakMaxSeconds() <= 600
            && config.rotationBreakMinSeconds() <= config.rotationBreakMaxSeconds()
            && config.maximumRuntimeMinutes() >= 30 && config.maximumRuntimeMinutes() <= 720;
    }
    private boolean canInput() { return active && !goalObserved.get() && !changedAccount.get()
        && !Thread.currentThread().isInterrupted() && Microbot.isLoggedIn()
        && !Microbot.pauseAllScripts.get() && !InputArbiter.isHuman(); }
    private static boolean hasAction(String[] actions, String wanted) {
        if (actions == null) { return false; }
        for (String action : actions) { if (normal(action).equals(normal(wanted))) { return true; } }
        return false;
    }
    private static ObjectComposition composition(TileObject object) {
        ObjectComposition composition = Microbot.getClient().getObjectDefinition(object.getId());
        return composition != null && composition.getImpostorIds() != null ? composition.getImpostor() : composition;
    }
    private static boolean hasAxe(Snapshot snapshot) {
        for (int i = 0; i < AXES.length; i++) {
            if (snapshot.levels[0] >= AXE_LEVELS[i] && (snapshot.count(AXES[i]) > 0 || snapshot.equipment.contains(AXES[i]))) { return true; }
        }
        return false;
    }
    private static int logTier(int wc, int fm) { return Math.min(wc, fm) >= 30 ? 30 : Math.min(wc, fm) >= 15 ? 15 : 1; }
    private static int treeTier(String name) { return "willow".equals(name) ? 30 : "oak".equals(name) ? 15 : "tree".equals(name) ? 1 : 0; }
    private static int nextLog(Snapshot snapshot) {
        if (snapshot.levels[1] >= 30 && snapshot.count(ItemID.WILLOW_LOGS) > 0) { return ItemID.WILLOW_LOGS; }
        if (snapshot.levels[1] >= 15 && snapshot.count(ItemID.OAK_LOGS) > 0) { return ItemID.OAK_LOGS; }
        return snapshot.count(ItemID.LOGS) > 0 ? ItemID.LOGS : 0;
    }
    private static boolean fireSucceeded(int beforeCount, int afterCount, int beforeXp, int afterXp) {
        return afterCount < beforeCount && afterXp > beforeXp;
    }
    private boolean isBankState() { return state == State.OPENING_BANK || state == State.DEPOSITING_ITEMS
        || state == State.WITHDRAWING_TOOLS || state == State.CLOSING_BANK; }
    private static boolean isTrainingState(State state) { return state == State.CHOPPING || state == State.LIGHTING_LOGS
        || state == State.SELECTING_FIRE_TILE || state == State.FISHING || state == State.COOKING; }
    private WorldPoint jitter(WorldPoint base) {
        if (!config.enableJitter()) { return base; }
        WorldPoint candidate = point(base.getX() + Rs2Random.between(-1, 2), base.getY() + Rs2Random.between(-1, 2), base.getPlane());
        return Rs2Tile.isWalkable(candidate) ? candidate : base;
    }
    private static boolean inCorridor(WorldPoint tile, List<WorldPoint> route, int radius) {
        for (WorldPoint candidate : route) { if (near(tile, candidate, radius)) { return true; } }
        return false;
    }
    private static boolean near(WorldPoint a, WorldPoint b, int radius) { return a != null && b != null
        && a.getPlane() == b.getPlane() && a.distanceTo2D(b) <= radius; }
    private static long randomBlockMillis() { return Rs2Random.between(20 * 60000, 35 * 60000 + 1); }
    private static long randomSeconds(int min, int max) { return Rs2Random.between(min * 1000, max * 1000 + 1); }
    private static void actionDelay() { Global.sleep(Rs2Random.between(1200, 2801)); }
    private static long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
    private static WorldPoint point(int x, int y, int plane) { return new WorldPoint(x, y, plane); }
    private static String normal(String value) { return value == null ? "" : value.replaceAll("<[^>]*>", "")
        .replace('\u00a0', ' ').replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT); }
}
