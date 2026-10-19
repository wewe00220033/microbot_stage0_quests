package net.runelite.client.plugins.microbot.tradeunlock;

import java.awt.event.KeyEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.input.InputArbiter;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2PathApi;
import net.runelite.client.plugins.microbot.util.walker.Rs2RouteRequest;
import net.runelite.client.plugins.microbot.util.walker.Rs2RouteResult;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

/**
 * Stage 0.2, targeting Microbot 878c2eff875c6d22d1bf245581af8c34cf953fe2.
 * Bank-supplied only: no GE purchases, combat gathering, or credential handling.
 * Run exclusively: other automation must not share the walker or inventory.
 * Quest progress is read from the real varp/varbit sources, not invented varbits.
 */
@Singleton
public final class TradeUnlockScript extends Script {
    public enum State {
        VALIDATING_ANCHOR, SELECTING_QUEST, OPENING_BANK, DEPOSITING_INVENTORY,
        PLANNING_SUPPLIES, WITHDRAWING_SUPPLIES, PREPARING_ITEMS,
        WALKING_TO_OBJECTIVE, ASCENDING_JULIET, DESCENDING_JULIET,
        TALKING_TO_NPC, HANDLING_DIALOGUE, WATCHING_CUTSCENE,
        VERIFYING_QUEST, RETURNING_TO_ANCHOR, BANKING_REWARDS, COMPLETED, FAILED
    }

    private enum Task {
        COOK(Quest.COOKS_ASSISTANT, 1, 2),
        ROMEO(Quest.ROMEO__JULIET, 5, 100),
        SHEEP(Quest.SHEEP_SHEARER, 1, 21),
        GOBLIN(Quest.GOBLIN_DIPLOMACY, 5, 6);

        private final Quest quest;
        private final int points;
        private final int terminal;
        Task(Quest quest, int points, int terminal) {
            this.quest = quest;
            this.points = points;
            this.terminal = terminal;
        }
    }

    private enum Objective {
        COOK("Cook", point(3206, 3214, 0)),
        ROMEO_START("Romeo", point(3211, 3422, 0)),
        JULIET_LETTER("Juliet", point(3158, 3427, 1)),
        ROMEO_LETTER("Romeo", point(3211, 3422, 0)),
        LAWRENCE("Father Lawrence", point(3254, 3483, 0)),
        APOTHECARY("Apothecary", point(3195, 3405, 0)),
        JULIET_POTION("Juliet", point(3158, 3427, 1)),
        ROMEO_END("Romeo", point(3211, 3422, 0)),
        FRED("Fred the Farmer", point(3190, 3273, 0)),
        GENERALS("General Bentnoze", point(2958, 3512, 0));

        private final String npc;
        private final WorldPoint tile;
        Objective(String npc, WorldPoint tile) { this.npc = npc; this.tile = tile; }
    }

    public static final WorldPoint ANCHOR = point(3164, 3487, 0);
    private static final WorldPoint STAIRS_DOWNSTAIRS = point(3157, 3435, 0);
    private static final WorldPoint STAIRS_UPSTAIRS = point(3156, 3435, 1);
    private static final long STUCK_MS = 8000;
    private static final long CUTSCENE_MS = 180000;
    private static final Task[] TASKS = Task.values();

    @Inject private ConfigManager configManager;
    private final AtomicBoolean inCycle = new AtomicBoolean();
    private volatile boolean active;
    private volatile State state = State.FAILED;
    private volatile Status status = new Status("Stopped", "None", "None", 0, 0, 0, 0, "");
    private TradeUnlockConfig config;
    private ExecutorService walkingExecutor;
    private volatile WalkJob walkJob;
    private Task task;
    private Objective objective;
    private Objective dialogueOwner;
    private Snapshot last;
    private int questIndex;
    private int baselineQp;
    private int expectedGain;
    private int creditedGain;
    private int questStartQp;
    private final boolean[] credited = new boolean[4];
    private final Map<Integer, Integer> requirements = new LinkedHashMap<>();
    private final Map<String, Integer> attempts = new LinkedHashMap<>();
    private String progress = "";
    private String account = "";
    private String failure = "";
    private String action = "Starting";
    private long startedAt;
    private long enteredAt;
    private long offlineSince;
    private long cutsceneArmedUntil;
    private long cutsceneStartedAt;
    private long pausedAt;
    private WorldPoint station = ANCHOR;
    private WorldPoint lastWalkTile;
    private long lastMovedAt;
    private int walkRetries;
    private boolean initialized;
    private boolean suppliesReady;
    private boolean recovery;
    private boolean oldDisableTeleports;
    private boolean ownsWalkerSettings;
    private boolean closingFinalBank;

    public synchronized boolean start(TradeUnlockConfig newConfig) {
        if (active || inCycle.get() || (walkJob != null && walkJob.running)) {
            Microbot.log("[TradeUnlock] Previous worker is still stopping; re-enable shortly.");
            return false;
        }
        config = newConfig;
        task = null;
        objective = null;
        dialogueOwner = null;
        last = null;
        questIndex = 0;
        baselineQp = expectedGain = creditedGain = questStartQp = 0;
        Arrays.fill(credited, false);
        requirements.clear();
        attempts.clear();
        progress = account = failure = "";
        initialized = suppliesReady = recovery = closingFinalBank = false;
        offlineSince = pausedAt = cutsceneArmedUntil = cutsceneStartedAt = 0;
        walkRetries = 0;
        walkJob = null;
        station = ANCHOR;
        walkingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "TradeUnlock-walker");
            thread.setDaemon(true);
            return thread;
        });
        oldDisableTeleports = Rs2Walker.disableTeleports;
        Rs2Walker.disableTeleports = true;
        ownsWalkerSettings = true;
        startedAt = enteredAt = now();
        state = State.VALIDATING_ANCHOR;
        active = true;
        action = "Validate anchor and quest state";
        publish();
        Microbot.log("[TradeUnlock] START -> " + state);
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(
            this::cycle, 0, Rs2Random.between(250, 550), TimeUnit.MILLISECONDS);
        return true;
    }

    public Status getStatus() { return status; }
    public State getState() { return state; }

    private void cycle() {
        if (!active || !inCycle.compareAndSet(false, true)) { return; }
        try {
            if (!Microbot.isLoggedIn()) {
                cancelWalk();
                if (offlineSince == 0) { offlineSince = now(); }
                if (now() - offlineSince > 90000) { finish(false, "Disconnected for 90 seconds."); }
                return;
            }
            offlineSince = 0;
            if (!super.run() || !canInput()) {
                cancelWalk();
                if (pausedAt == 0) { pausedAt = now(); }
                return;
            }
            if (pausedAt != 0) {
                long elapsed = now() - pausedAt;
                enteredAt += elapsed;
                startedAt += elapsed;
                if (cutsceneArmedUntil != 0) { cutsceneArmedUntil += elapsed; }
                if (cutsceneStartedAt != 0) { cutsceneStartedAt += elapsed; }
                pausedAt = 0;
            }
            Snapshot snapshot = snapshot();
            if (snapshot == null) { return; }
            last = snapshot;
            if (!account.isEmpty() && !account.equalsIgnoreCase(snapshot.name)) {
                finish(false, "Account changed while the pipeline was active.");
                return;
            }
            account = snapshot.name;
            if (snapshot.hp <= 0) { finish(false, "Death detected; no completion certified."); return; }
            String signature = Arrays.toString(snapshot.stages) + snapshot.items;
            if (!signature.equals(progress)) { attempts.clear(); progress = signature; }

            boolean scene = authorizedCutscene(snapshot);
            if (config.enableCrashRecovery() && !scene && !inExpectedZone(snapshot.tile)) {
                if (recovery && state == State.RETURNING_TO_ANCHOR && walkJob == null) {
                    finish(false, "Displaced again during recovery.");
                } else {
                    evacuate("Outside the expected 15-tile corridor.", snapshot);
                }
                return;
            }
            if (now() - startedAt > TimeUnit.MINUTES.toMillis(90)) {
                finish(false, "Overall 90-minute deadline exceeded."); return;
            }
            if (now() - enteredAt > TimeUnit.MINUTES.toMillis(config.stateTimeoutMinutes())) {
                if (recovery || isBankState()) { finish(false, "State deadline exceeded: " + state); }
                else { evacuate("State deadline exceeded: " + state, snapshot); }
                return;
            }
            if (walkJob != null && !recovery && task != null && suppliesReady
                    && state == State.WALKING_TO_OBJECTIVE && !snapshot.done[task.ordinal()]) {
                Objective latestObjective = objectiveFor(task, stage(snapshot),
                    snapshot.count(ItemID.JULIETMESSAGE), snapshot.count(ItemID.CADAVA));
                if (latestObjective != objective) {
                    objective = latestObjective;
                    cancelWalk();
                    action = "Cancel obsolete quest-stage walk";
                }
            }
            if (walkJob != null) { monitorWalk(snapshot); return; }
            if (snapshot.questScroll) {
                readThen(() -> Rs2Widget.isWidgetVisible(InterfaceID.Questscroll.UNIVERSE)
                    && Rs2Widget.clickWidget(InterfaceID.Questscroll.CLOSE_BUTTON));
                return;
            }
            if (snapshot.hp * 100 / Math.max(1, snapshot.maxHp) <= 40 && !isBankState()) {
                if (!Rs2Inventory.getInventoryFood().isEmpty()) {
                    input("Eat food", Rs2Player::useFood);
                    return;
                }
                if (!recovery) { evacuate("Low health without food.", snapshot); return; }
            }
            if (Rs2Dialogue.isInDialogue()) {
                if (state != State.HANDLING_DIALOGUE && state != State.WATCHING_CUTSCENE) {
                    if (dialogueOwner == null && !recovery) {
                        evacuate("Unexpected dialogue outside an NPC interaction.", snapshot); return;
                    }
                    transition(State.HANDLING_DIALOGUE, "Dialogue opened");
                }
                handleDialogue(snapshot);
                return;
            }
            if (scene) {
                if (cutsceneStartedAt == 0) { cutsceneStartedAt = now(); }
                if (now() - cutsceneStartedAt > CUTSCENE_MS) {
                    finish(false, "Cutscene exceeded three minutes."); return;
                }
                if (state != State.WATCHING_CUTSCENE) {
                    transition(State.WATCHING_CUTSCENE, "Authorized Romeo & Juliet cutscene");
                }
                return;
            }
            if (state == State.HANDLING_DIALOGUE || state == State.WATCHING_CUTSCENE) {
                transition(recovery ? State.RETURNING_TO_ANCHOR : State.VERIFYING_QUEST,
                    "Dialogue/cutscene ended");
            }
            if (task != null && snapshot.done[task.ordinal()] && suppliesReady
                    && !credited[task.ordinal()] && state != State.VERIFYING_QUEST) {
                transition(State.VERIFYING_QUEST, "Server reports quest complete");
            }
            switch (state) {
                case VALIDATING_ANCHOR: validate(snapshot); break;
                case SELECTING_QUEST: selectQuest(snapshot); break;
                case OPENING_BANK:
                    if (Rs2Bank.isOpen()) { transition(State.DEPOSITING_INVENTORY, "Bank opened"); }
                    else { input("Open GE bank", Rs2Bank::openBank); }
                    break;
                case DEPOSITING_INVENTORY:
                    if (!Rs2Bank.isOpen()) { transition(State.OPENING_BANK, "Bank closed unexpectedly"); }
                    else if (!snapshot.items.isEmpty()) { input("Deposit inventory", Rs2Bank::depositAll); }
                    else { transition(State.PLANNING_SUPPLIES, "Inventory deposited"); }
                    break;
                case PLANNING_SUPPLIES: planSupplies(snapshot); break;
                case WITHDRAWING_SUPPLIES: withdraw(snapshot); break;
                case PREPARING_ITEMS: prepare(snapshot); break;
                case WALKING_TO_OBJECTIVE: travel(snapshot, false); break;
                case ASCENDING_JULIET: stairs(snapshot, true); break;
                case DESCENDING_JULIET: stairs(snapshot, false); break;
                case TALKING_TO_NPC: talk(snapshot); break;
                case VERIFYING_QUEST: verify(snapshot); break;
                case RETURNING_TO_ANCHOR: travel(snapshot, true); break;
                case BANKING_REWARDS: bankRewards(snapshot); break;
                case HANDLING_DIALOGUE:
                case WATCHING_CUTSCENE: break;
                case COMPLETED:
                case FAILED: active = false; break;
                default: throw new IllegalStateException("Unhandled state: " + state);
            }
        } catch (RuntimeException exception) {
            finish(false, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        } finally {
            publish();
            if (active && !Thread.currentThread().isInterrupted()) { actionDelay(); }
            inCycle.set(false);
        }
    }

    private void validate(Snapshot snapshot) {
        if (!near(snapshot.tile, ANCHOR, 15)) {
            evacuate("Start must be at the GE anchor.", snapshot); return;
        }
        if (!initialized) {
            baselineQp = snapshot.qp;
            for (Task candidate : TASKS) {
                if (snapshot.done[candidate.ordinal()]) { credited[candidate.ordinal()] = true; }
                else { expectedGain += candidate.points; }
            }
            initialized = true;
            journal("Baseline recorded");
        }
        transition(State.SELECTING_QUEST, "Anchor verified");
    }

    private void selectQuest(Snapshot snapshot) {
        while (questIndex < TASKS.length && snapshot.done[questIndex]) { questIndex++; }
        if (questIndex == TASKS.length) {
            task = null;
            transition(State.BANKING_REWARDS, "All four quests finished; final bank verification");
            return;
        }
        task = TASKS[questIndex];
        questStartQp = snapshot.qp;
        objective = null;
        dialogueOwner = null;
        suppliesReady = false;
        requirements.clear();
        transition(State.OPENING_BANK, "Prepare " + task.quest.getName());
    }

    private void planSupplies(Snapshot snapshot) {
        if (!Rs2Bank.isOpen()) { transition(State.OPENING_BANK, "Reopen bank"); return; }
        requirements.clear();
        int stage = stage(snapshot);
        if (task == Task.COOK) {
            need(ItemID.EGG, 1); need(ItemID.BUCKET_MILK, 1); need(ItemID.POT_FLOUR, 1);
        } else if (task == Task.ROMEO) {
            if (stage == 20 && available(ItemID.JULIETMESSAGE, snapshot) > 0) {
                need(ItemID.JULIETMESSAGE, 1);
            }
            if (stage <= 50) {
                if (stage == 50 && available(ItemID.CADAVA, snapshot) > 0) { need(ItemID.CADAVA, 1); }
                else { need(ItemID.CADAVABERRIES, 1); }
            }
        } else if (task == Task.SHEEP) {
            need(ItemID.BALL_OF_WOOL, woolNeeded(stage));
        } else if (task == Task.GOBLIN) {
            int plain = 1;
            if (stage < 4) {
                if (available(ItemID.GOBLIN_ARMOUR_ORANGE, snapshot) > 0) { need(ItemID.GOBLIN_ARMOUR_ORANGE, 1); }
                else {
                    plain++;
                    if (available(ItemID.ORANGEDYE, snapshot) > 0) { need(ItemID.ORANGEDYE, 1); }
                    else if (config.redYellowFallback()) { need(ItemID.REDDYE, 1); need(ItemID.YELLOWDYE, 1); }
                    else { need(ItemID.ORANGEDYE, 1); }
                }
            }
            if (stage < 5) {
                if (available(ItemID.GOBLIN_ARMOUR_DARKBLUE, snapshot) > 0) { need(ItemID.GOBLIN_ARMOUR_DARKBLUE, 1); }
                else { plain++; need(ItemID.BLUEDYE, 1); }
            }
            need(ItemID.GOBLIN_ARMOUR, plain);
        }
        for (Map.Entry<Integer, Integer> entry : requirements.entrySet()) {
            if (available(entry.getKey(), snapshot) < entry.getValue()) {
                finish(false, "Missing pre-banked supply: item " + entry.getKey() + ", need " + entry.getValue());
                return;
            }
        }
        transition(State.WITHDRAWING_SUPPLIES, "Bank supply quantities verified");
    }

    private void withdraw(Snapshot snapshot) {
        if (!Rs2Bank.isOpen()) { transition(State.OPENING_BANK, "Reopen bank and re-plan"); return; }
        if (!Rs2Bank.hasWithdrawAsItem()) {
            input("Set withdrawal to unnoted items", Rs2Bank::setWithdrawAsItem); return;
        }
        for (Map.Entry<Integer, Integer> entry : requirements.entrySet()) {
            int missing = entry.getValue() - snapshot.count(entry.getKey());
            if (missing > 0) {
                if (Rs2Inventory.emptySlotCount() < missing) {
                    finish(false, "Insufficient inventory slots for unnoted quest supplies."); return;
                }
                if (Rs2Bank.count(entry.getKey()) < missing) {
                    finish(false, "Bank supply changed during withdrawal: item " + entry.getKey()); return;
                }
                int id = entry.getKey();
                input("Withdraw " + missing + " of item " + id, () -> Rs2Bank.withdrawX(id, missing));
                return;
            }
        }
        input("Close bank", Rs2Bank::closeBank);
        if (!Rs2Bank.isOpen()) { transition(State.PREPARING_ITEMS, "Unnoted withdrawals observed"); }
    }

    private void prepare(Snapshot snapshot) {
        if (Rs2Bank.isOpen()) { input("Close bank", Rs2Bank::closeBank); return; }
        if (task == Task.GOBLIN) {
            int stage = stage(snapshot);
            if (stage < 4 && snapshot.count(ItemID.GOBLIN_ARMOUR_ORANGE) == 0) {
                if (snapshot.count(ItemID.ORANGEDYE) == 0) {
                    combine(snapshot, ItemID.REDDYE, ItemID.YELLOWDYE, "Mix orange dye");
                } else { combine(snapshot, ItemID.ORANGEDYE, ItemID.GOBLIN_ARMOUR, "Dye orange goblin mail"); }
                return;
            }
            if (stage < 5 && snapshot.count(ItemID.GOBLIN_ARMOUR_DARKBLUE) == 0) {
                combine(snapshot, ItemID.BLUEDYE, ItemID.GOBLIN_ARMOUR, "Dye blue goblin mail"); return;
            }
        }
        if (!travelSuppliesPresent(snapshot)) { finish(false, "Prepared inventory does not satisfy the quest stage."); return; }
        suppliesReady = true;
        objective = objectiveFor(task, stage(snapshot), snapshot.count(ItemID.JULIETMESSAGE), snapshot.count(ItemID.CADAVA));
        transition(State.WALKING_TO_OBJECTIVE, "Prepared inventory verified");
    }

    private void combine(Snapshot snapshot, int first, int second, String label) {
        if (snapshot.count(first) < 1 || snapshot.count(second) < 1) {
            finish(false, "Cannot " + label + ": required items missing."); return;
        }
        input(label, () -> Rs2Inventory.combine(first, second));
    }

    private boolean travelSuppliesPresent(Snapshot snapshot) {
        int stage = stage(snapshot);
        switch (task) {
            case COOK:
                return snapshot.count(ItemID.EGG) >= 1 && snapshot.count(ItemID.BUCKET_MILK) >= 1
                    && snapshot.count(ItemID.POT_FLOUR) >= 1;
            case ROMEO:
                return stage > 50 || snapshot.count(ItemID.CADAVABERRIES) >= 1 || snapshot.count(ItemID.CADAVA) >= 1;
            case SHEEP: return snapshot.count(ItemID.BALL_OF_WOOL) >= woolNeeded(stage);
            case GOBLIN:
                return snapshot.count(ItemID.GOBLIN_ARMOUR) >= 1
                    && (stage >= 4 || snapshot.count(ItemID.GOBLIN_ARMOUR_ORANGE) >= 1)
                    && (stage >= 5 || snapshot.count(ItemID.GOBLIN_ARMOUR_DARKBLUE) >= 1);
            default: return false;
        }
    }

    private void travel(Snapshot snapshot, boolean home) {
        WorldPoint goal = home ? ANCHOR : objective.tile;
        if (snapshot.tile.getPlane() == 1 && isJulietRoom(snapshot.tile) && goal.getPlane() == 0) {
            transition(State.DESCENDING_JULIET, "Leave Juliet's balcony"); return;
        }
        if (goal.getPlane() == 1 && snapshot.tile.getPlane() == 0) {
            if (near(snapshot.tile, STAIRS_DOWNSTAIRS, 3)) {
                transition(State.ASCENDING_JULIET, "At mansion stairs");
            } else { beginWalk(STAIRS_DOWNSTAIRS, snapshot); }
            return;
        }
        if (near(snapshot.tile, goal, home ? 4 : 3)) {
            transition(home ? State.BANKING_REWARDS : State.TALKING_TO_NPC,
                home ? "GE anchor reached" : "Objective area reached");
        } else { beginWalk(goal, snapshot); }
    }

    private void stairs(Snapshot snapshot, boolean up) {
        if (up && isJulietRoom(snapshot.tile)) {
            transition(State.WALKING_TO_OBJECTIVE, "Observed upstairs plane"); return;
        }
        if (!up && snapshot.tile.getPlane() == 0) {
            transition(recovery || (task != null && credited[task.ordinal()])
                ? State.RETURNING_TO_ANCHOR : State.WALKING_TO_OBJECTIVE, "Observed downstairs plane");
            return;
        }
        WorldPoint stand = up ? STAIRS_DOWNSTAIRS : STAIRS_UPSTAIRS;
        if (!near(snapshot.tile, stand, 2)) { beginWalk(stand, snapshot); return; }
        int id = up ? ObjectID.FAI_VARROCK_STAIRS_TALLER : ObjectID.FAI_VARROCK_STAIRS_TOP;
        TileObject object = Rs2GameObject.<TileObject>getAll(candidate -> candidate.getId() == id
            && near(candidate.getWorldLocation(), stand, 3)).stream()
            .min(Comparator.comparingInt(candidate -> candidate.getWorldLocation().distanceTo2D(stand))).orElse(null);
        if (object == null) { retry("Mansion stairs missing"); return; }
        String option = up ? "Climb-up" : "Climb-down";
        input(option + " mansion stairs", () -> Rs2GameObject.interact(object, option));
    }

    private void talk(Snapshot snapshot) {
        Objective resolved = objectiveFor(task, stage(snapshot), snapshot.count(ItemID.JULIETMESSAGE), snapshot.count(ItemID.CADAVA));
        if (resolved != objective) { objective = resolved; transition(State.WALKING_TO_OBJECTIVE, "Quest stage changed"); return; }
        Rs2NpcModel npc = Rs2Npc.getNpc(objective.npc, true);
        if (npc == null && objective == Objective.GENERALS) { npc = Rs2Npc.getNpc("General Wartface", true); }
        if (npc == null || !near(npc.getWorldLocation(), objective.tile, 15)) { retry("NPC missing: " + objective.npc); return; }
        if (!near(snapshot.tile, npc.getWorldLocation(), 4) || !Rs2Npc.hasLineOfSight(npc)) {
            beginWalk(npc.getWorldLocation(), snapshot); return;
        }
        if (objective == Objective.JULIET_POTION || objective == Objective.ROMEO_END) {
            cutsceneArmedUntil = now() + CUTSCENE_MS;
        }
        dialogueOwner = objective;
        Rs2NpcModel selected = npc;
        input("Talk-to " + npc.getName(), () -> Rs2Npc.interact(selected, "Talk-to"));
    }

    private void handleDialogue(Snapshot snapshot) {
        if (recovery) {
            input("Dismiss dialogue for evacuation", () -> { Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE); return true; });
            return;
        }
        if (Rs2Dialogue.hasSelectAnOption()) {
            List<String> before = optionTexts();
            String title = normal(Rs2Dialogue.getQuestion());
            String selected = selectOption(dialogueOwner, stage(snapshot), title, before);
            if (selected == null) {
                evacuate("Unknown dialogue; no answer guessed: " + title + " " + before, snapshot); return;
            }
            readThen(() -> Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasSelectAnOption()
                && title.equals(normal(Rs2Dialogue.getQuestion())) && before.equals(optionTexts())
                && Rs2Dialogue.clickOption(selected, true));
        } else if (Rs2Dialogue.hasContinue()) {
            String before = normal(Rs2Dialogue.getDialogueText());
            readThen(() -> Rs2Dialogue.isInDialogue() && Rs2Dialogue.hasContinue()
                && before.equals(normal(Rs2Dialogue.getDialogueText())) && continueDialogue());
        }
    }

    private boolean continueDialogue() { Rs2Dialogue.clickContinue(); return true; }

    private static String selectOption(Objective owner, int stage, String title, List<String> options) {
        if (owner == null) { return null; }
        List<String> allowed = new ArrayList<>();
        switch (owner) {
            case COOK:
                allowed.addAll(Arrays.asList("What's wrong?", "Can I help?", "Yes.", "Yes")); break;
            case ROMEO_START:
                allowed.addAll(Arrays.asList("Perhaps I could help to find her for you?", "Yes, I have seen her actually!",
                    "Yes, ok, I'll let her know.", "Yes.", "Yes")); break;
            case ROMEO_LETTER:
                allowed.addAll(Arrays.asList("Yes, I have seen her actually!", "Yes, ok, I'll let her know.")); break;
            case JULIET_LETTER:
            case LAWRENCE: allowed.add("Ok, thanks."); break;
            case APOTHECARY:
                allowed.addAll(Arrays.asList("Talk about something else.", "Talk about Romeo & Juliet.")); break;
            case JULIET_POTION: allowed.add("Ok, thanks."); break;
            case FRED:
                allowed.addAll(Arrays.asList("I need to talk to you about shearing these sheep!", "I'm looking for a quest.",
                    "Yes, okay. I can do that.", "Yes.", "Yes")); break;
            case GENERALS:
                allowed.add(stage < 4 ? "I have some orange armour here."
                    : stage == 4 ? "I have some blue armour here." : "I have some brown armour here.");
                allowed.addAll(Arrays.asList("Do you want me to pick an armour colour for you?",
                    "What about a different colour?", "So how is life for the goblins?", "Yes, Wartface looks fat"));
                if (title.contains("goblin diplomacy")) { allowed.add("Yes."); allowed.add("Yes"); }
                break;
            case ROMEO_END: break;
            default: throw new IllegalStateException("Unknown dialogue owner");
        }
        for (String expected : allowed) {
            for (String actual : options) {
                if (normal(actual).equals(normal(expected))) { return actual; }
            }
        }
        return null;
    }

    private void verify(Snapshot snapshot) {
        if (snapshot.done[task.ordinal()]) {
            if (stage(snapshot) < task.terminal || snapshot.qp < questStartQp + task.points) {
                action = "Wait for quest state and QP to agree"; return;
            }
            if (!credited[task.ordinal()]) {
                credited[task.ordinal()] = true;
                creditedGain += task.points;
                journal("Verified " + task.quest.getName() + " and its quest points");
            }
            dialogueOwner = null;
            transition(State.RETURNING_TO_ANCHOR, "Quest and QP verified; bank between quests");
        } else {
            objective = objectiveFor(task, stage(snapshot), snapshot.count(ItemID.JULIETMESSAGE), snapshot.count(ItemID.CADAVA));
            dialogueOwner = null;
            transition(State.WALKING_TO_OBJECTIVE, "Continue from authoritative quest stage");
        }
    }

    private void bankRewards(Snapshot snapshot) {
        if (!near(snapshot.tile, ANCHOR, 10)) { transition(State.RETURNING_TO_ANCHOR, "Return to bank anchor"); return; }
        if (!Rs2Bank.isOpen()) {
            if (closingFinalBank && snapshot.items.isEmpty()) {
                closingFinalBank = false;
                if (recovery) { finish(false, failure); }
                else if (allDone(snapshot)) {
                    if (snapshot.qp >= baselineQp + expectedGain && snapshot.qp >= 12) {
                        finish(true, "All four quests verified; inventory banked. QP=" + snapshot.qp);
                    } else { finish(false, "Final quest-point total is inconsistent."); }
                } else { questIndex++; transition(State.SELECTING_QUEST, "Rewards deposited; next quest"); }
            } else { input("Open GE bank for rewards", Rs2Bank::openBank); }
        } else if (!snapshot.items.isEmpty()) { input("Deposit quest rewards and leftover supplies", Rs2Bank::depositAll); }
        else {
            closingFinalBank = true;
            input("Close bank after verified deposit", Rs2Bank::closeBank);
        }
    }

    private void beginWalk(WorldPoint base, Snapshot snapshot) {
        if (walkJob != null) { return; }
        WorldPoint target = jitter(base);
        WalkJob job = new WalkJob(snapshot.tile, target);
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
            if (job.error != null) {
                walkJob = null;
                if (recovery) { finish(false, "Recovery walk failed: " + job.error); }
                else { evacuate("Walk failed: " + job.error, snapshot); }
                return;
            }
            if (!job.cancelled && near(snapshot.tile, job.target, 3)) { walkRetries = 0; }
            else if (!job.cancelled && ++walkRetries > config.maxWalkRetries()) {
                walkJob = null;
                if (recovery) { finish(false, "Repeated early walker exits during recovery."); }
                else { evacuate("Repeated early walker exits.", snapshot); }
                return;
            }
            walkJob = null;
            return;
        }
        if (!snapshot.tile.equals(lastWalkTile)) {
            lastWalkTile = snapshot.tile;
            lastMovedAt = now();
        } else if (now() - lastMovedAt >= STUCK_MS) {
            if (++walkRetries > config.maxWalkRetries()) {
                cancelWalk();
                if (recovery) { finish(false, "Evacuation exhausted walk retries."); }
                else { evacuate("Walk exhausted eight-second stall retries.", snapshot); }
                return;
            }
            Microbot.log("[TradeUnlock] Eight-second stall: cancel and reissue walkTo, attempt " + walkRetries);
            job.cancel();
            Rs2Walker.setTarget(null);
            lastMovedAt = now();
        }
    }

    private final class WalkJob implements Runnable {
        private final WorldPoint origin;
        private final WorldPoint target;
        private volatile List<WorldPoint> corridor;
        private volatile boolean running;
        private volatile boolean cancelled;
        private volatile String error;
        private Future<?> future;
        private WalkJob(WorldPoint origin, WorldPoint target) {
            this.origin = origin; this.target = target; this.corridor = Collections.singletonList(origin);
        }
        @Override public void run() {
            running = true;
            try {
                if (cancelled || !canInput()) { return; }
                Rs2RouteResult route = Rs2PathApi.plan(Rs2RouteRequest.to(origin, target).withBankItems(false));
                if (cancelled || !canInput()) { return; }
                if (!route.isSearchCompleted() || !route.isTargetReached(3) || route.getPath().isEmpty()) {
                    error = "No complete route to " + target; return;
                }
                if (route.getPath().stream().anyMatch(Rs2PathApi::isInWilderness)) {
                    error = "Refusing a Wilderness route"; return;
                }
                if (route.getTransportSteps().stream().anyMatch(step -> step.getTransport().isPresent()
                        && step.getTransport().get().isTeleport())) {
                    error = "Refusing a teleport route"; return;
                }
                corridor = Collections.unmodifiableList(new ArrayList<>(route.getPath()));
                if (!cancelled && canInput()) { Rs2Walker.walkTo(target, 2); actionDelay(); }
            } catch (RuntimeException exception) {
                if (!cancelled && active) { error = exception.toString(); }
            } finally { running = false; }
        }
        private void cancel() {
            cancelled = true;
            if (future != null) { future.cancel(true); }
        }
    }

    private void cancelWalk() {
        WalkJob job = walkJob;
        if (job != null) { job.cancel(); }
        Rs2Walker.setTarget(null);
        // Keep the job until its worker acknowledges cancellation; no overlapping manual inputs.
    }

    private boolean inExpectedZone(WorldPoint tile) {
        if (state == State.COMPLETED || state == State.FAILED) { return true; }
        if (state == State.ASCENDING_JULIET || state == State.DESCENDING_JULIET) {
            return near(tile, STAIRS_DOWNSTAIRS, 15) || isJulietRoom(tile);
        }
        WalkJob job = walkJob;
        if (job != null) {
            return recovery && job.cancelled ? near(tile, station, 15) : inCorridor(tile, job.corridor, 15);
        }
        if (isBankState() || state == State.SELECTING_QUEST || state == State.VALIDATING_ANCHOR) {
            return near(tile, ANCHOR, 15);
        }
        return near(tile, station, 15) || (objective != null && near(tile, objective.tile, 15));
    }

    private boolean authorizedCutscene(Snapshot snapshot) {
        return task == Task.ROMEO && !recovery && now() <= cutsceneArmedUntil
            && (snapshot.cutscene || snapshot.instanced)
            && (dialogueOwner == Objective.JULIET_POTION || dialogueOwner == Objective.ROMEO_END);
    }

    private boolean isBankState() {
        return state == State.OPENING_BANK || state == State.DEPOSITING_INVENTORY
            || state == State.PLANNING_SUPPLIES || state == State.WITHDRAWING_SUPPLIES
            || state == State.PREPARING_ITEMS || state == State.BANKING_REWARDS;
    }

    private void evacuate(String reason, Snapshot snapshot) {
        if (failure.isEmpty()) { failure = reason; }
        recovery = true;
        cancelWalk();
        walkRetries = 0;
        station = snapshot.tile;
        transition(State.RETURNING_TO_ANCHOR, "Recovery: " + reason);
    }

    private void transition(State next, String reason) {
        if (!active) { return; }
        State previous = state;
        state = next;
        enteredAt = now();
        action = reason;
        if (last != null) { station = last.tile; }
        Microbot.log("[TradeUnlock] " + previous + " -> " + next + " | " + reason);
        journal(reason);
        publish();
    }

    private void input(String label, BooleanSupplier operation) {
        if (!canInput() || !retry(label)) { return; }
        action = label;
        operation.getAsBoolean();
        actionDelay();
    }

    private void readThen(BooleanSupplier operation) {
        if (!retry("Dialogue " + normal(Rs2Dialogue.getQuestion()) + optionTexts()
                + normal(Rs2Dialogue.getDialogueText()))) { return; }
        Global.sleep(Rs2Random.between(1800, 4200));
        if (canInput()) { operation.getAsBoolean(); actionDelay(); }
    }

    private boolean retry(String label) {
        int count = attempts.getOrDefault(label, 0) + 1;
        attempts.put(label, count);
        if (count > 12) {
            if (recovery || last == null || isBankState()) { finish(false, "Repeated action failed: " + label); }
            else { evacuate("Repeated action failed: " + label, last); }
            return false;
        }
        return true;
    }

    private void finish(boolean success, String reason) {
        if (!active) { return; }
        if (!success && failure.isEmpty()) { failure = reason; }
        transition(success ? State.COMPLETED : State.FAILED, reason);
        shutdown();
    }

    @Override public synchronized void shutdown() {
        if (active && state != State.COMPLETED && state != State.FAILED) {
            failure = "Plugin stopped before completion.";
            transition(State.FAILED, failure);
        }
        active = false;
        cancelWalk();
        if (walkingExecutor != null) { walkingExecutor.shutdownNow(); }
        super.shutdown();
        if (ownsWalkerSettings) { Rs2Walker.disableTeleports = oldDisableTeleports; ownsWalkerSettings = false; }
        publish();
    }

    private void journal(String reason) {
        if (account.isEmpty()) { return; }
        try {
            configManager.setConfiguration(TradeUnlockConfig.GROUP,
                "checkpoint_" + account.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_"),
                Instant.now() + " | " + state + " | " + (task == null ? "None" : task.quest.getName())
                    + " | QP=" + (last == null ? 0 : last.qp) + " | stages="
                    + (last == null ? "[]" : Arrays.toString(last.stages)) + " | " + reason.replace('\n', ' '));
        } catch (RuntimeException exception) { Microbot.log("[TradeUnlock] Journal write failed: " + exception); }
    }

    private void publish() {
        status = new Status(state.name(), task == null ? "None" : task.quest.getName(),
            objective == null ? "None" : objective.npc, last == null ? 0 : last.qp,
            baselineQp, creditedGain, startedAt == 0 ? 0 : now() - startedAt,
            failure.isEmpty() ? action : failure);
    }

    public static final class Status {
        public final String state;
        public final String quest;
        public final String objective;
        public final int questPoints;
        public final int baselinePoints;
        public final int earnedPoints;
        public final long runtimeMillis;
        public final String detail;
        private Status(String state, String quest, String objective, int questPoints,
                int baselinePoints, int earnedPoints, long runtimeMillis, String detail) {
            this.state = state; this.quest = quest; this.objective = objective;
            this.questPoints = questPoints; this.baselinePoints = baselinePoints;
            this.earnedPoints = earnedPoints; this.runtimeMillis = runtimeMillis; this.detail = detail;
        }
    }

    private Snapshot snapshot() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            if (Microbot.getClient().getLocalPlayer() == null) { return null; }
            ItemContainer inventory = Microbot.getClient().getItemContainer(InventoryID.INVENTORY);
            if (inventory == null) { return null; }
            Snapshot result = new Snapshot();
            result.name = Microbot.getClient().getLocalPlayer().getName();
            if (result.name == null) { return null; }
            result.tile = Microbot.getClient().getLocalPlayer().getWorldLocation();
            result.hp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
            result.maxHp = Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
            result.qp = Microbot.getClient().getVarpValue(VarPlayerID.QP);
            result.stages[0] = Microbot.getClient().getVarpValue(VarPlayerID.COOKQUEST);
            result.stages[1] = Microbot.getClient().getVarpValue(VarPlayerID.RJQUEST);
            result.stages[2] = Microbot.getClient().getVarpValue(VarPlayerID.SHEEP);
            result.stages[3] = Microbot.getClient().getVarbitValue(VarbitID.GOBDIP_MAIN);
            for (Task candidate : TASKS) { result.done[candidate.ordinal()] = candidate.quest.getState(Microbot.getClient()) == QuestState.FINISHED; }
            for (Item item : inventory.getItems()) {
                if (item.getId() >= 0 && item.getQuantity() > 0) { result.items.merge(item.getId(), item.getQuantity(), Integer::sum); }
            }
            result.cutscene = Microbot.getClient().getVarbitValue(542) == 1;
            WorldView worldView = Microbot.getClient().getTopLevelWorldView();
            if (worldView == null) { return null; }
            result.instanced = worldView.isInstance();
            Widget widget = Microbot.getClient().getWidget(InterfaceID.Questscroll.UNIVERSE);
            result.questScroll = widget != null && !widget.isHidden();
            return result;
        }).orElse(null);
    }

    private static final class Snapshot {
        private String name;
        private WorldPoint tile;
        private int hp;
        private int maxHp;
        private int qp;
        private boolean cutscene;
        private boolean instanced;
        private boolean questScroll;
        private final int[] stages = new int[4];
        private final boolean[] done = new boolean[4];
        private final Map<Integer, Integer> items = new LinkedHashMap<>();
        private int count(int id) { return items.getOrDefault(id, 0); }
    }

    private static Objective objectiveFor(Task task, int stage, int letters, int potions) {
        switch (task) {
            case COOK:
                if (stage == 0 || stage == 1) { return Objective.COOK; } break;
            case ROMEO:
                switch (stage) {
                    case 0: return Objective.ROMEO_START;
                    case 10: return Objective.JULIET_LETTER;
                    case 20: return letters > 0 ? Objective.ROMEO_LETTER : Objective.JULIET_LETTER;
                    case 30: return Objective.LAWRENCE;
                    case 40: return Objective.APOTHECARY;
                    case 50: return potions > 0 ? Objective.JULIET_POTION : Objective.APOTHECARY;
                    case 60: return Objective.ROMEO_END;
                    default: break;
                }
                break;
            case SHEEP: if (stage >= 0 && stage <= 20) { return Objective.FRED; } break;
            case GOBLIN: if (stage >= 0 && stage <= 5) { return Objective.GENERALS; } break;
            default: break;
        }
        throw new IllegalStateException("Unsupported quest stage: " + task + "=" + stage);
    }

    private int stage(Snapshot snapshot) { return snapshot.stages[task.ordinal()]; }
    private void need(int id, int quantity) { if (quantity > 0) { requirements.merge(id, quantity, Integer::sum); } }
    private static int woolNeeded(int stage) { return stage <= 1 ? 20 : Math.max(0, 21 - stage); }
    private int available(int id, Snapshot snapshot) { return snapshot.count(id) + Rs2Bank.count(id); }
    private static boolean allDone(Snapshot snapshot) { for (boolean done : snapshot.done) { if (!done) { return false; } } return true; }
    private boolean canInput() { return active && !Thread.currentThread().isInterrupted() && Microbot.isLoggedIn()
        && !Microbot.pauseAllScripts.get() && !InputArbiter.isHuman(); }
    private static long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()); }
    private static WorldPoint point(int x, int y, int plane) { return new WorldPoint(x, y, plane); }
    private static void actionDelay() { Global.sleep(Rs2Random.between(1200, 2800)); }
    private static boolean near(WorldPoint a, WorldPoint b, int radius) { return a != null && b != null
        && a.getPlane() == b.getPlane() && a.distanceTo2D(b) <= radius; }
    private static boolean isJulietRoom(WorldPoint tile) { return tile.getPlane() == 1 && tile.getX() >= 3147
        && tile.getX() <= 3166 && tile.getY() >= 3425 && tile.getY() <= 3443; }
    private static boolean inCorridor(WorldPoint tile, List<WorldPoint> route, int radius) {
        for (WorldPoint point : route) { if (near(tile, point, radius)) { return true; } }
        return false;
    }
    private WorldPoint jitter(WorldPoint base) {
        WorldPoint candidate = config.enableJitter() ? point(base.getX() + Rs2Random.between(-1, 2),
            base.getY() + Rs2Random.between(-1, 2), base.getPlane()) : base;
        if (Rs2Tile.isWalkable(base) && !Rs2Tile.isWalkable(candidate)) { return base; }
        return candidate;
    }
    private static String normal(String value) { return value == null ? "" : value.replaceAll("(?i)<br\\s*/?>", " ")
        .replaceAll("<[^>]*>", "").replace('\u00a0', ' ').replace('\u2019', '\'').replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT); }
    private static List<String> optionTexts() {
        return Microbot.getClientThread().runOnClientThreadOptional(() -> {
            List<String> result = new ArrayList<>();
            for (Widget widget : Rs2Dialogue.getDialogueOptions()) { result.add(widget.getText()); }
            return result;
        }).orElse(Collections.emptyList());
    }
}
