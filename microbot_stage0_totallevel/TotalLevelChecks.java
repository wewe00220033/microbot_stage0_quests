import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.IScript;
import net.runelite.client.plugins.microbot.totallevel.TotalLevelConfig;
import net.runelite.client.plugins.microbot.totallevel.TotalLevelOverlay;
import net.runelite.client.plugins.microbot.totallevel.TotalLevelScript;
import net.runelite.client.plugins.microbot.totallevel.TotalLevelScript.State;

/** Offline deterministic logic / mocked tick checks only; no live-client assertions. */
public final class TotalLevelChecks {
    private static final Class<?> TYPE = TotalLevelScript.class;
    private static final Class<?> SNAPSHOT;
    private static int assertions;
    static {
        try { SNAPSHOT = Class.forName(TYPE.getName() + "$Snapshot"); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static Method method(String name, Class<?>... args) throws Exception {
        Method value = TYPE.getDeclaredMethod(name, args); value.setAccessible(true); return value;
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field value = type.getDeclaredField(name); value.setAccessible(true); return value;
    }
    private static void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }
    private static void check(boolean value, String label) {
        assertions++; if (!value) { throw new AssertionError(label); }
    }
    private static WorldPoint point(int x, int y, int plane) { return new WorldPoint(x,y,plane); }
    private static Object snapshot(int wc, int fm) throws Exception {
        Constructor<?> constructor = SNAPSHOT.getDeclaredConstructor(); constructor.setAccessible(true);
        Object snapshot = constructor.newInstance();
        int[] levels = (int[])field(SNAPSHOT,"levels").get(snapshot); levels[0]=wc; levels[1]=fm;
        return snapshot;
    }
    @SuppressWarnings("unchecked")
    private static Map<Integer,Integer> items(Object snapshot) throws Exception {
        return (Map<Integer,Integer>)field(SNAPSHOT,"items").get(snapshot);
    }
    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) { return null; }
        if (type == boolean.class) { return false; }
        if (type == int.class) { return 0; }
        if (type == long.class) { return 0L; }
        if (type == double.class) { return 0d; }
        if (type == float.class) { return 0f; }
        if (type == byte.class) { return (byte)0; }
        if (type == short.class) { return (short)0; }
        return (char)0;
    }
    private static Widget widget(int id, String text, String[] actions, boolean hidden, Widget... children) {
        return (Widget)Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getItemId": return id;
                case "getName": return "";
                case "getText": return text;
                case "getActions": return actions;
                case "isHidden": return hidden;
                case "getChildren": return children;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                default: return primitiveDefault(method.getReturnType());
            }
        });
    }
    public static void main(String[] args) throws Exception {
        Method tier = method("logTier",int.class,int.class);
        for (int[] row : new int[][]{{1,1,1},{14,40,1},{40,14,1},{15,15,15},{29,99,15},
                {99,29,15},{30,30,30},{99,99,30},{15,30,15}}) {
            check((Integer)tier.invoke(null,row[0],row[1]) == row[2],"Joint WC/FM level gate " + Arrays.toString(row));
        }
        Method tree=method("treeTier",String.class);
        check((Integer)tree.invoke(null,"tree")==1,"Normal tree");
        check((Integer)tree.invoke(null,"oak")==15,"Oak");
        check((Integer)tree.invoke(null,"willow")==30,"Willow");
        for (String invalid:Arrays.asList("dead tree","yew","maple tree","tree stump","magic tree","")) {
            check((Integer)tree.invoke(null,invalid)==0,"Do not chop unsupported tree " + invalid);
        }
        Method next=method("nextLog",SNAPSHOT);
        for (int level: new int[]{1,14,15,29,30,99}) {
            Object snapshot=snapshot(99,level);
            items(snapshot).put(ItemID.LOGS,3);items(snapshot).put(ItemID.OAK_LOGS,3);items(snapshot).put(ItemID.WILLOW_LOGS,3);
            check((Integer)next.invoke(null,snapshot)==(level>=30?ItemID.WILLOW_LOGS:level>=15?ItemID.OAK_LOGS:ItemID.LOGS),"Burnable log level " + level);
        }
        Object snapshot=snapshot(1,1); items(snapshot).put(ItemID.OAK_LOGS,1);
        check((Integer)next.invoke(null,snapshot)==0,"Do not burn an inaccessible oak");
        items(snapshot).put(ItemID.LOGS,1);
        check((Integer)next.invoke(null,snapshot)==ItemID.LOGS,"Regular log fallback");
        Method success=method("fireSucceeded",int.class,int.class,int.class,int.class);
        check((Boolean)success.invoke(null,4,3,0,40),"Log consumption AND XP verifies fire");
        check(!(Boolean)success.invoke(null,4,3,0,0),"Consumption alone does not verify fire");
        check(!(Boolean)success.invoke(null,4,4,0,40),"XP alone does not verify fire");
        check(!(Boolean)success.invoke(null,4,4,0,0),"Attempt alone does not verify fire");
        Method axe=method("hasAxe",SNAPSHOT);
        snapshot=snapshot(1,1); items(snapshot).put(ItemID.RUNE_AXE,1);
        check(!(Boolean)axe.invoke(null,snapshot),"Rune axe blocked at level one");
        ((int[])field(SNAPSHOT,"levels").get(snapshot))[0]=41;
        check((Boolean)axe.invoke(null,snapshot),"Inventory rune axe at 41 WC; no Attack requirement");
        snapshot=snapshot(1,1); items(snapshot).put(ItemID.BRONZE_AXE,1);
        check((Boolean)axe.invoke(null,snapshot),"Bronze axe at level one");
        snapshot=snapshot(21,1);
        @SuppressWarnings("unchecked") Set<Integer> equipped=(Set<Integer>)field(SNAPSHOT,"equipment").get(snapshot);
        equipped.add(ItemID.MITHRIL_AXE);
        check((Boolean)axe.invoke(null,snapshot),"Equipped usable axe");
        Method valid=method("validConfig",TotalLevelConfig.class);
        check((Boolean)valid.invoke(null,new TotalLevelConfig(){}),"Default config valid");
        check(!(Boolean)valid.invoke(null,new TotalLevelConfig(){ public int microBreakMinSeconds(){return 46;} }),"Reject reversed micro-break bounds");
        check(!(Boolean)valid.invoke(null,new TotalLevelConfig(){ public int microBreakMinSeconds(){return 0;} }),"Reject zero micro-break");
        check(!(Boolean)valid.invoke(null,new TotalLevelConfig(){ public int microBreakMaxSeconds(){return 181;} }),"Reject oversized micro-break");
        check(!(Boolean)valid.invoke(null,new TotalLevelConfig(){ public int rotationBreakMinSeconds(){return 121;} }),"Reject reversed bank-break bounds");
        check(!(Boolean)valid.invoke(null,new TotalLevelConfig(){ public int rotationBreakMaxSeconds(){return 601;} }),"Reject oversized bank break");
        check(!(Boolean)valid.invoke(null,new TotalLevelConfig(){ public int maximumRuntimeMinutes(){return 29;} }),"Reject short hard deadline");
        check(!(Boolean)valid.invoke(null,new TotalLevelConfig(){ public int maximumRuntimeMinutes(){return 721;} }),"Reject oversized hard deadline");
        Method block=method("randomBlockMillis");
        boolean bounds=true; Set<Long> draws=new HashSet<>();
        for(int i=0;i<1000;i++){long value=(Long)block.invoke(null);bounds&=value>=1200000&&value<=2100000;draws.add(value);}
        check(bounds,"1000 block durations within inclusive 20-to-35-minute bounds");
        check(draws.size()>900,"Block randomization produces varied durations");
        Method seconds=method("randomSeconds",int.class,int.class);
        check((Long)seconds.invoke(null,12,12)==12000,"Equal bounds supported");
        for (int i=0;i<20;i++){long value=(Long)seconds.invoke(null,12,45);check(value>=12000&&value<=45000,"Micro-break bounds "+i);}
        Method corridor=method("inCorridor",WorldPoint.class,List.class,int.class);
        List<WorldPoint> path=Arrays.asList(point(3208,3220,2),point(3210,3210,0));
        check((Boolean)corridor.invoke(null,point(3223,3220,2),path,15),"Corridor 15-tile edge accepted");
        check(!(Boolean)corridor.invoke(null,point(3224,3220,2),path,15),"16-tile deviation rejected");
        check(!(Boolean)corridor.invoke(null,point(3208,3220,1),path,15),"Wrong plane rejected");
        check(!(Boolean)corridor.invoke(null,point(3208,3220,2),Collections.emptyList(),15),"Empty corridor rejected");
        TotalLevelScript script=new TotalLevelScript();
        Method zone=method("inExpectedZone",WorldPoint.class);
        set(script,"station",TotalLevelScript.BANK_ANCHOR);
        set(script,"state",State.WALKING_TO_TREES);
        check((Boolean)zone.invoke(script,point(3190,3245,0)),"Arrival handoff accepts trees without a live WalkJob");
        check((Boolean)zone.invoke(script,TotalLevelScript.BANK_ANCHOR),"Tree departure anchor accepted");
        set(script,"state",State.WALKING_TO_FISHING_SPOT);
        check((Boolean)zone.invoke(script,point(3243,3152,0)),"Arrival handoff accepts fishing destination");
        set(script,"station",point(3243,3152,0));set(script,"state",State.WALKING_TO_RANGE);
        check((Boolean)zone.invoke(script,point(3208,3214,0)),"Arrival handoff accepts range");
        set(script,"state",State.WALKING_TO_BANK);
        check((Boolean)zone.invoke(script,TotalLevelScript.BANK_ANCHOR),"Arrival handoff accepts upstairs bank");
        set(script,"state",State.CHOPPING);
        check((Boolean)zone.invoke(script,point(3235,3245,0)),"Work-area radius plus 15-tile corridor boundary");
        check(!(Boolean)zone.invoke(script,point(3236,3245,0)),"Outside work-area corridor rejected");
        set(script,"woodAnchor",point(3088,3230,0));
        check((Boolean)zone.invoke(script,point(3088,3230,0)),"Optional Draynor work-area authorized");
        check(!(Boolean)zone.invoke(script,point(3190,3245,0)),"Draynor mode does not authorize old trees");
        Class<?> walkType=Class.forName(TYPE.getName()+"$WalkJob");
        Constructor<?> walkConstructor=walkType.getDeclaredConstructor(TYPE,WorldPoint.class,WorldPoint.class,int.class);
        walkConstructor.setAccessible(true);
        Object job=walkConstructor.newInstance(script,TotalLevelScript.BANK_ANCHOR,point(3243,3152,0),5);
        set(job,"cancelled",true);
        set(job,"corridor",Arrays.asList(TotalLevelScript.BANK_ANCHOR,point(3212,3213,0),point(3243,3152,0)));
        set(script,"walkJob",job);set(script,"state",State.WALKING_TO_FISHING_SPOT);
        set(script,"station",TotalLevelScript.BANK_ANCHOR);
        Object mid=snapshot(1,1);set(mid,"tile",point(3212,3213,0));
        check(!(Boolean)zone.invoke(script,point(3212,3213,0)),"Suspended halfway route needs validated rebase");
        Method rebase=method("resumeCancelledRoute",SNAPSHOT);
        rebase.invoke(script,mid);
        check((Boolean)zone.invoke(script,point(3212,3213,0)),"Paused in-corridor route can resume without false evacuation");
        set(script,"station",TotalLevelScript.BANK_ANCHOR);set(mid,"tile",point(3300,3300,0));
        rebase.invoke(script,mid);
        check(field(TYPE,"station").get(script).equals(TotalLevelScript.BANK_ANCHOR),"Resume never blindly authorizes displacement");
        check(!(Boolean)zone.invoke(script,point(3300,3300,0)),"Off-corridor resume rejected");
        set(job,"cancelled",false);set(mid,"tile",point(3212,3213,0));rebase.invoke(script,mid);
        check(field(TYPE,"station").get(script).equals(TotalLevelScript.BANK_ANCHOR),"Active walk does not use suspension rebase");
        set(script,"walkJob",null);
        Method recipe=method("recipeMatches",Widget.class,int.class,int.class,String.class,Set.class);
        Widget shrimp=widget(ItemID.SHRIMP,"",null,false);
        Widget anchor=widget(ItemID.ANCHOVIES,"",null,false);
        check((Boolean)recipe.invoke(null,shrimp,ItemID.RAW_SHRIMP,ItemID.SHRIMP,"shrimps",Collections.newSetFromMap(new java.util.IdentityHashMap<Widget,Boolean>())),"Cooked model identifies shrimp recipe");
        check(!(Boolean)recipe.invoke(null,anchor,ItemID.RAW_SHRIMP,ItemID.SHRIMP,"shrimps",Collections.newSetFromMap(new java.util.IdentityHashMap<Widget,Boolean>())),"Wrong fish model rejected");
        Widget hidden=widget(ItemID.SHRIMP,"",null,true);
        check(!(Boolean)recipe.invoke(null,hidden,ItemID.RAW_SHRIMP,ItemID.SHRIMP,"shrimps",Collections.newSetFromMap(new java.util.IdentityHashMap<Widget,Boolean>())),"Hidden recipe rejected");
        Widget parent=widget(-1,"",new String[]{"Cook"},false,shrimp);
        check((Boolean)recipe.invoke(null,parent,ItemID.RAW_SHRIMP,ItemID.SHRIMP,"shrimps",Collections.newSetFromMap(new java.util.IdentityHashMap<Widget,Boolean>())),"Nested recipe model supported");
        Method action=method("hasAction",String[].class,String.class);
        check((Boolean)action.invoke(null,new String[]{null,"<col=fff>Cook</col>"},"Cook"),"Exact normalized live action");
        check(!(Boolean)action.invoke(null,new String[]{"Cook-5"},"Cook"),"Do not guess numeric cooking option");
        check(!(Boolean)action.invoke(null,null,"Cook"),"No actions rejected");
        Method transition=method("transition",State.class,String.class);
        set(script,"state",State.FAILED);
        transition.invoke(script,State.CHOPPING,"stale worker");
        check(script.getState()==State.FAILED,"Stopped worker cannot overwrite terminal state");
        check(Script.class.isAssignableFrom(TYPE)&&IScript.class.isAssignableFrom(TYPE),"Actual Script and IScript inheritance");
        check(TYPE.isAnnotationPresent(javax.inject.Singleton.class),"Shared script singleton");
        check(TotalLevelOverlay.class.isAnnotationPresent(javax.inject.Singleton.class),"Shared overlay singleton");
        check(TotalLevelScript.TARGET_TOTAL==100,"Immutable goal threshold 100");
        // Mock only the read-only event hook. No scheduler, bank, pathfinder or mouse is run.
        AtomicInteger total=new AtomicInteger(99); AtomicReference<String> name=new AtomicReference<>("Stage03 Offline Test");
        AtomicReference<GameState> game=new AtomicReference<>(GameState.LOGGED_IN);
        Player player=(Player)Proxy.newProxyInstance(Player.class.getClassLoader(),new Class<?>[]{Player.class},(proxy,m,a)->{
            if(m.getName().equals("getName")){return name.get();}
            return primitiveDefault(m.getReturnType());
        });
        Client client=(Client)Proxy.newProxyInstance(Client.class.getClassLoader(),new Class<?>[]{Client.class},(proxy,m,a)->{
            switch(m.getName()){
                case "getGameState":return game.get();case "getLocalPlayer":return player;case "getTotalLevel":return total.get();
                default:return primitiveDefault(m.getReturnType());
            }
        });
        Field clientField=field(Microbot.class,"client");Object old=clientField.get(null);boolean paused=Microbot.pauseAllScripts.get();
        try {
            clientField.set(null,client);set(script,"active",true);set(script,"state",State.ROTATION_BREAK);
            script.onGameTick();check(script.getState()==State.ROTATION_BREAK,"99 total does not certify success");
            total.set(100);Microbot.pauseAllScripts.set(true);
            script.onGameTick();check(script.getState()==State.COMPLETED,"100 total preempts bank break and global pause");
            check(script.getStatus().state.equals("COMPLETED")&&script.getStatus().total==100,"Overlay immediately publishes actual total-100 latch");
            check(script.getStatus().blockRemainingMillis==0,"Completed overlay has no active block deadline");
            transition.invoke(script,State.CHOPPING,"late transition");
            check(script.getState()==State.COMPLETED,"Late transition cannot overwrite goal latch");
            ((AtomicBoolean)field(TYPE,"goalObserved").get(script)).set(false);
            set(script,"state",State.MICRO_BREAK);total.set(101);script.onGameTick();
            check(script.getState()==State.COMPLETED,"Totals above 100 preempt micro-break");
            for(State candidate:State.values()) {
                if(candidate==State.COMPLETED||candidate==State.FAILED){continue;}
                ((AtomicBoolean)field(TYPE,"goalObserved").get(script)).set(false);
                set(script,"state",candidate);total.set(100);script.onGameTick();
                check(script.getState()==State.COMPLETED,"Goal preempts state "+candidate);
            }
            ((AtomicBoolean)field(TYPE,"goalObserved").get(script)).set(false);
            set(script,"state",State.CHOPPING);game.set(GameState.LOGIN_SCREEN);total.set(150);
            script.onGameTick();check(script.getState()==State.CHOPPING,"Logged-out stale totals cannot certify success");
            game.set(GameState.LOGGED_IN);total.set(100);script.onGameTick();
            name.set("Different Account");script.onGameTick();
            check(script.getState()==State.FAILED,"Changed account overrides stale goal latch");
            check(script.getStatus().detail.contains("Account changed"),"Account-change status is explicit");
            set(script,"active",false);
        } finally {clientField.set(null,old);Microbot.pauseAllScripts.set(paused);}
        System.out.println("PASS: "+assertions+" offline level-gate, fire-verification, timing, route-handoff, widget, DI and mocked total-level tick assertions.");
    }
}
