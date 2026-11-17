package ftcsim.game;

import ftcsim.config.RobotConfig;
import ftcsim.map.FieldMap;
import ftcsim.physics.Chassis;
import ftcsim.physics.Field;
import ftcsim.physics.MotorState;
import ftcsim.physics.Pose2;
import ftcsim.physics.ServoState;
import ftcsim.physics.Units;
import ftcsim.physics.Vec2;
import ftcsim.physics.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleConsumer;

/**
 * The BIOBUZZ match: the period clock (30 s AUTO, 8 s transition, 2:00 TELEOP), the scoring elements (POLLEN and NECTAR) with their physics,
 * the rocking HIVEs, the FLOWERs, the robot's intake and launcher, the human player, the scoring of manual TU08 table 10-2 and the
 * violations of section 11. It runs on the physics thread as a step listener of the {@link World}; everything is in inches and seconds in the
 * field frame (+X audience, +Y blue wall, +Z up).
 */
public final class BiobuzzGame implements HiveModel.Listener {
    public enum Phase { PRE, AUTO, TRANSITION, TELEOP, ENDED }

    /** Bits of {@link #pollEvents()}: the clock moved on by itself. */
    public static final int AUTO_ENDED = 1, TELEOP_STARTED = 2, TELEOP_ENDED = 4;

    private static final double GRAVITY = 386.09;
    private static final double FLOOR_RESTITUTION = 0.35;
    private static final double ROLL_DECEL = 40;
    private static final double WALL_RESTITUTION = 0.4;
    private static final double SOLID_RESTITUTION = 0.4;
    private static final double ROBOT_RESTITUTION = 0.3;
    private static final double PAIR_RESTITUTION = 0.3;
    private static final double SLEEP_SPEED = 0.8;
    private static final double SLEEP_TIME = 0.2;
    private static final double ROBOT_CLEARANCE = 0.4;
    private static final double SETTLE_MAX_S = 6.0;
    private static final double PICKUP_GAP_S = 0.06;
    private static final double RETRIEVE_GAP_S = 0.5;
    private static final double HUMAN_GAP_S = 1.5;
    private static final double SLOW_DT = 0.02;
    private static final double QUIET_GRACE_S = 1.0;
    private static final double QUIET_END_S = 5.0;
    private static final int PRELOAD_COUNT = 4;
    private static final double MATCH_S = Rules.AUTO_S + Rules.TRANSITION_S + Rules.TELEOP_S;

    private static final class Event {
        final long seq;
        final double t;
        final String text;

        Event(long seq, double t, String text) {
            this.seq = seq;
            this.t = t;
            this.text = text;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            m.put("t", round2(t));
            m.put("text", text);
            return m;
        }
    }

    /** Points of one alliance, by line of table 10-2. */
    private static final class Score {
        int leave, autoPark, teleopPark, autoTips, teleopTips, cell, flower, bottom, garden, penalty;
        int rp;
        boolean dq, swarm, pollinator1, pollinator2;

        int auto() { return leave + autoPark + autoTips * Rules.TIP_POINTS; }

        int teleop() { return teleopPark + teleopTips * Rules.TIP_POINTS + cell + flower + bottom + garden; }

        int total() { return dq ? 0 : auto() + teleop() + penalty; }
    }

    private final World world;
    private final RobotConfig config;
    private final RobotConfig.Game fallbackGame = new RobotConfig.Game();
    private final Field field;
    private final FieldMap map;
    private final Mechanisms mech;
    private final Rules.Ledger ledger = new Rules.Ledger();
    private final Random rng = new Random(20260611L);
    private final double wall;
    private final double wallHeight;
    private final List<Element> elements = new ArrayList<>();
    private final List<String> groups = new ArrayList<>();
    private final List<HiveModel> hives = new ArrayList<>();
    private final List<FlowerModel> flowers = new ArrayList<>();
    private final List<FieldMap.Box> solids = new ArrayList<>();
    private final List<FieldMap.Box> hiveFrames = new ArrayList<>();
    private final List<Element> held = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private final double[][] loading = new double[2][];
    private final double[][] garden = new double[2][];
    private final boolean[] setupUp;
    private final DoubleConsumer stepper = this::step;
    private final Map<ServoState, Double> servoSeen = new IdentityHashMap<>();
    private final Score[] scores = {new Score(), new Score()};
    private final int[] autoTips = new int[2];
    private final int[] teleopTips = new int[2];
    private final int[] humanBudget = new int[2];
    private boolean attached;

    private Phase phase = Phase.PRE;
    private boolean practice, finalized, allianceLocked;
    private double now, autoStart, transitionStart, teleopStart, endedAt, slowAcc;
    private int pendingEvents;
    private long eventSeq;
    private Alliance robotAlliance = Alliance.RED;
    private String allianceMode = "AUTO";
    private boolean scriptedHuman = true;
    private boolean manualIntake, manualLaunch;
    private boolean leaveEarned, autoParkEarned, teleopPark;
    private double lastPickup, lastRetrieve, lastHumanDrop, lastG401, lastG407, lastG417;
    private double heldOverS, peak, motionS;
    private int motionLevel;

    // the robot as sampled for the current step
    private double rx, ry, rcos = 1, rsin, rL = 18, rW = 18, rH = 18, rvx, rvy, rom, rRadius = 13;
    // scratch of the element being stepped
    private boolean supported, touched;

    public BiobuzzGame(World world, RobotConfig config) {
        this.world = world;
        this.config = config;
        this.field = world.field;
        if (field == null || field.map == null) throw new IllegalArgumentException("the BIOBUZZ game needs a field built from a field map");
        this.map = field.map;
        this.wall = field.halfSizeIn;
        this.wallHeight = field.wallHeightIn;
        this.mech = new Mechanisms(world, game());
        for (FieldMap.Hive h : map.hives) hives.add(new HiveModel(hives.size(), h, this, rng));
        for (FieldMap.Flower f : map.flowers) flowers.add(new FlowerModel(flowers.size(), f));
        solids.addAll(map.solids);
        for (FieldMap.Flower f : map.flowers) if (f.footprint != null) solids.add(f.footprint);
        for (FieldMap.Box b : map.solids) if (b.name != null && b.name.startsWith("HIVE_")) hiveFrames.add(b);
        for (FieldMap.Piece p : map.pieces) {
            Element.Type t = "NECTAR".equalsIgnoreCase(p.type) ? Element.Type.NECTAR : Element.Type.POLLEN;
            Alliance a = t == Element.Type.NECTAR ? Alliance.parse(p.alliance, Alliance.RED) : null;
            elements.add(new Element(elements.size(), t, a, p.x, p.y, p.z, Element.State.FIELD, -1));
            groups.add(p.group == null ? "" : p.group);
        }
        double tile = field.tileIn;
        double h = wall;
        loading[0] = zone("Red LOADING ZONE", -2 * tile, -h, -tile, -h + Field.LOADING_ZONE_DEPTH_IN);
        loading[1] = zone("Blue LOADING ZONE", tile, h - Field.LOADING_ZONE_DEPTH_IN, 2 * tile, h);
        garden[0] = zone("Red GARDEN", h - Field.GARDEN_WIDTH_IN, -h, h, -h + tile);
        garden[1] = zone("Blue GARDEN", -h, h - tile, -h + Field.GARDEN_WIDTH_IN, h);
        double tipLoad = game().hiveTipLoad > 0 ? game().hiveTipLoad : 7.0;
        for (HiveModel hv : hives) hv.tipLoad = tipLoad;
        setupUp = new boolean[hives.size()];
        readFieldSetup();
        reset();
    }

    private RobotConfig.Game game() { return config != null && config.game != null ? config.game : fallbackGame; }

    private RobotConfig.Intake intake() { return game().intake != null ? game().intake : fallbackGame.intake; }

    private RobotConfig.Launcher launcher() { return game().launcher != null ? game().launcher : fallbackGame.launcher; }

    private int capacity() { return Math.max(1, game().capacity); }

    private double[] zone(String name, double minX, double minY, double maxX, double maxY) {
        for (Field.Zone z : field.zones) {
            if (name.equalsIgnoreCase(z.name)) return new double[] {z.minX, z.minY, z.maxX, z.maxY};
        }
        return new double[] {minX, minY, maxX, maxY};
    }

    private static String optionKey(HiveModel h) { return h.alliance == Alliance.RED ? "redHive" : "blueHive"; }

    private HiveModel hiveOf(Alliance a) {
        for (HiveModel h : hives) if (h.alliance == a) return h;
        return hives.get(0);
    }

    private FlowerModel flowerByIndex(int n) {
        for (FlowerModel f : flowers) if (f.flower.index == n) return f;
        return null;
    }

    /** Remembers which CELL of each HIVE the field is currently set up with (the user may flip them in the field options). */
    private void readFieldSetup() {
        for (int i = 0; i < hives.size(); i++) {
            HiveModel h = hives.get(i);
            String opt = field.option(optionKey(h));
            setupUp[i] = opt == null ? h.hive.cells.get(0).up : "audience".equals(opt);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // life cycle
    // ------------------------------------------------------------------------------------------------------------------------------

    public synchronized void attach() {
        if (!attached) {
            world.addStepListener(stepper);
            attached = true;
        }
    }

    public synchronized void detach() {
        if (attached) {
            world.removeStepListener(stepper);
            attached = false;
        }
    }

    /** The user changed the HIVE set-up in the field options: take it as the new starting position and start over. */
    public synchronized void adoptFieldSetup() {
        readFieldSetup();
        reset();
    }

    /** Back to the pre-match field: every element in place, HIVEs on their set-up side, scores and violations cleared. */
    public synchronized void reset() {
        phase = Phase.PRE;
        practice = false;
        finalized = false;
        allianceLocked = false;
        pendingEvents = 0;
        ledger.clear();
        events.clear();
        held.clear();
        Arrays.fill(autoTips, 0);
        Arrays.fill(teleopTips, 0);
        Arrays.fill(humanBudget, 0);
        leaveEarned = autoParkEarned = teleopPark = false;
        motionS = 0;
        motionLevel = 0;
        heldOverS = 0;
        peak = 0;
        manualIntake = false;
        manualLaunch = false;
        lastPickup = lastRetrieve = lastHumanDrop = lastG401 = lastG407 = lastG417 = -1e9;
        slowAcc = 0;
        mech.clear();
        for (int i = 0; i < hives.size(); i++) {
            HiveModel h = hives.get(i);
            h.reset(setupUp[i]);
            field.setOption(optionKey(h), setupUp[i] ? "audience" : "scoring");
        }
        for (FlowerModel f : flowers) f.reset();
        List<Element> stacked = new ArrayList<>();
        for (Element e : elements) {
            e.restore();
            String g = groups.get(e.id);
            if (g.startsWith("FLOWER_")) {
                stacked.add(e);
            } else if (g.startsWith("GARDEN_")) {
                e.x = e.px = clamp(e.x, -wall + e.r, wall - e.r);
                e.y = e.py = clamp(e.y, -wall + e.r, wall - e.r);
            } else if (g.startsWith("CELL_")) {
                HiveModel h = hiveOf(Alliance.parse(g.substring(5), Alliance.RED));
                h.add(e, h.upCell());
            } else {
                e.state = Element.State.HUMAN;
            }
        }
        stacked.sort(Comparator.comparingDouble(e -> e.z0));
        for (Element e : stacked) {
            FlowerModel f = flowerByIndex(number(groups.get(e.id)));
            if (f != null) f.add(e);
        }
        for (HiveModel h : hives) h.place();
        sampleRobot();
        robotAlliance = pickAlliance();
        assignPreloads();
        computeScores();
    }

    private static int number(String group) {
        int u = group.lastIndexOf('_');
        try {
            return Integer.parseInt(group.substring(u + 1));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private Alliance pickAlliance() {
        if ("RED".equals(allianceMode)) return Alliance.RED;
        if ("BLUE".equals(allianceMode)) return Alliance.BLUE;
        return Alliance.ofY(ry);
    }

    private void refreshAlliance() {
        Alliance a = pickAlliance();
        if (a != robotAlliance) {
            robotAlliance = a;
            assignPreloads();
        }
    }

    /** The robot starts with the four preloaded POLLEN of its alliance. */
    private void assignPreloads() {
        for (Element e : new ArrayList<>(held)) {
            if (!groups.get(e.id).startsWith("PRELOAD_")) continue;
            held.remove(e);
            stage(e);
        }
        String g = "PRELOAD_" + robotAlliance.name();
        int limit = Math.min(PRELOAD_COUNT, capacity());
        for (Element e : elements) {
            if (held.size() >= limit) break;
            if (e.state == Element.State.HUMAN && g.equals(groups.get(e.id))) hold(e);
        }
    }

    private void stage(Element e) {
        e.state = Element.State.HUMAN;
        e.vx = e.vy = e.vz = 0;
        e.asleep = true;
        e.launched = e.released = false;
    }

    /** AUTO starts when an autonomous OpMode is started. Starting it again (or after a finished match) puts the field back first. */
    public synchronized void beginAuto() {
        if (phase != Phase.PRE) reset();
        sampleRobot();
        refreshAlliance();
        allianceLocked = true;
        phase = Phase.AUTO;
        practice = false;
        autoStart = now;
        startChecks();
        addEvent("AUTO started - " + robotAlliance + " alliance, " + held.size() + " POLLEN preloaded");
    }

    /** TELEOP starts when a TeleOp OpMode is started: after AUTO normally, straight away when no AUTO was run (practice). */
    public synchronized void beginTeleop() {
        switch (phase) {
            case PRE:
                practice = true;
                sampleRobot();
                refreshAlliance();
                allianceLocked = true;
                startChecks();
                startTeleop(false);
                break;
            case AUTO:
                endAuto(false);
                startTeleop(false);
                break;
            case TRANSITION:
                startTeleop(false);
                break;
            case ENDED:
                reset();
                beginTeleop();
                break;
            default:
                break;
        }
    }

    /** Clock events since the last call (bit set of {@link #AUTO_ENDED}, {@link #TELEOP_STARTED}, {@link #TELEOP_ENDED}). */
    public synchronized int pollEvents() {
        int r = pendingEvents;
        pendingEvents = 0;
        return r;
    }

    public synchronized void settings(String mode, Boolean scripted, Double tipLoad) {
        if (mode != null) {
            String m = mode.trim().toUpperCase(Locale.ROOT);
            if (m.equals("RED") || m.equals("BLUE") || m.equals("AUTO")) allianceMode = m;
            if (phase == Phase.PRE) {
                sampleRobot();
                refreshAlliance();
            }
        }
        if (scripted != null) scriptedHuman = scripted;
        if (tipLoad != null && tipLoad >= 0.5) for (HiveModel h : hives) h.tipLoad = tipLoad;
    }

    /** The UI intake button / launch button for a robot whose code does not drive them. */
    public synchronized void manual(Boolean intakeOn, Boolean launch) {
        if (intakeOn != null) manualIntake = intakeOn;
        if (launch != null && launch) manualLaunch = true;
    }

    /** Input arrived from a driver station gamepad: forbidden during AUTO (G401). */
    public synchronized void driverInput() {
        if (phase != Phase.AUTO || now - lastG401 < 3.0) return;
        lastG401 = now;
        foul("G401", robotAlliance, "driver station input received during AUTO", false);
    }

    /** A human player of the robot's alliance enters one NECTAR through the LOADING ZONE. Returns a short message for the user. */
    public synchronized String humanNectar() {
        if (phase == Phase.ENDED) return "The match is over";
        Alliance a = robotAlliance;
        Element e = null;
        for (Element c : elements) {
            if (c.state == Element.State.HUMAN && c.type == Element.Type.NECTAR && c.alliance == a) {
                e = c;
                break;
            }
        }
        if (e == null) return "No NECTAR left in the " + a + " ALLIANCE AREA";
        boolean free = phase == Phase.PRE || matchRemaining() <= Rules.FLOWER_UNLOCK_REMAINING_S;
        int i = a.ordinal();
        if (!free) {
            if (humanBudget[i] <= 0) foul("G426", a, "NECTAR entered without a HIVE TIP and with more than 60 s left", false);
            else humanBudget[i]--;
        }
        dropNectar(e, a);
        return "NECTAR entered through the " + a + " LOADING ZONE";
    }

    private void dropNectar(Element e, Alliance a) {
        double[] z = loading[a.ordinal()];
        e.state = Element.State.FIELD;
        e.x = e.px = (z[0] + z[2]) / 2 + (rng.nextDouble() - 0.5) * 4;
        e.y = e.py = (z[1] + z[3]) / 2 + (rng.nextDouble() - 0.5) * 2;
        e.z = e.pz = 8;
        e.vx = e.vy = e.vz = 0;
        e.asleep = false;
        e.restTime = 0;
        e.launched = e.released = false;
        lastHumanDrop = now;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // clock
    // ------------------------------------------------------------------------------------------------------------------------------

    public synchronized Phase phase() { return phase; }

    public synchronized boolean practice() { return practice; }

    public synchronized boolean finalized() { return finalized; }

    public synchronized Alliance alliance() { return robotAlliance; }

    public synchronized double matchElapsed() {
        switch (phase) {
            case AUTO: return Math.min(Rules.AUTO_S, now - autoStart);
            case TRANSITION: return Rules.AUTO_S + Math.min(Rules.TRANSITION_S, now - transitionStart);
            case TELEOP: return Rules.AUTO_S + Rules.TRANSITION_S + Math.min(Rules.TELEOP_S, now - teleopStart);
            case ENDED: return MATCH_S;
            default: return 0;
        }
    }

    public synchronized double matchRemaining() { return MATCH_S - matchElapsed(); }

    private void advancePhase() {
        switch (phase) {
            case AUTO:
                if (now - autoStart >= Rules.AUTO_S) endAuto(true);
                break;
            case TRANSITION:
                if (now - transitionStart >= Rules.TRANSITION_S) startTeleop(true);
                break;
            case TELEOP:
                if (now - teleopStart >= Rules.TELEOP_S) endTeleop();
                break;
            default:
                break;
        }
    }

    /** LEAVE and AUTO PARK are assessed at the end of AUTO. */
    private void endAuto(boolean timed) {
        sampleRobot();
        leaveEarned = robotLeft();
        autoParkEarned = robotParked();
        phase = Phase.TRANSITION;
        transitionStart = now;
        motionS = 0;
        motionLevel = 0;
        if (timed) pendingEvents |= AUTO_ENDED;
        addEvent("AUTO ended - LEAVE " + (leaveEarned ? "earned" : "not earned") + ", AUTO PARK " + (autoParkEarned ? "earned" : "not earned"));
    }

    private void startTeleop(boolean timed) {
        phase = Phase.TELEOP;
        teleopStart = now;
        motionS = 0;
        motionLevel = 0;
        if (timed) pendingEvents |= TELEOP_STARTED;
        addEvent(practice ? "TELEOP started (practice, no AUTO)" : "TELEOP started");
    }

    private void endTeleop() {
        phase = Phase.ENDED;
        endedAt = now;
        motionS = 0;
        motionLevel = 0;
        pendingEvents |= TELEOP_ENDED;
        addEvent("TELEOP ended - waiting for the field to come to rest");
    }

    /** Everything at rest: nothing moving on the field and no HIVE swinging. */
    public synchronized boolean settled() {
        for (Element e : elements) {
            if ((e.state == Element.State.FIELD || e.state == Element.State.FLIGHT) && !e.asleep) return false;
        }
        for (HiveModel h : hives) if (h.tipping) return false;
        return true;
    }

    private void finalizeMatch() {
        sampleRobot();
        teleopPark = robotParked();
        computeScores();
        finalized = true;
        Score r = scores[Alliance.RED.ordinal()], b = scores[Alliance.BLUE.ordinal()];
        int rt = r.total(), bt = b.total();
        for (Score s : scores) {
            int i = s == r ? 0 : 1;
            int mine = s == r ? rt : bt, theirs = s == r ? bt : rt;
            s.swarm = !s.dq && s.leave + s.autoPark + s.teleopPark >= Rules.SWARM_THRESHOLD;
            int tips = autoTips[i] + teleopTips[i];
            s.pollinator1 = !s.dq && tips >= Rules.POLLINATOR_1_TIPS;
            s.pollinator2 = !s.dq && tips >= Rules.POLLINATOR_2_TIPS;
            s.rp = s.dq ? 0 : (mine > theirs ? 3 : mine == theirs ? 1 : 0) + (s.swarm ? 1 : 0) + (s.pollinator1 ? 1 : 0) + (s.pollinator2 ? 1 : 0);
        }
        addEvent(String.format(Locale.ROOT, "MATCH OVER - RED %d : %d BLUE", rt, bt));
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // the step
    // ------------------------------------------------------------------------------------------------------------------------------

    /** One physics step (1 ms); registered as a step listener of the world. */
    void step(double dt) {
        synchronized (this) {
            now += dt;
            advancePhase();
            sampleRobot();
            boolean intakeOn = intakeActive();
            for (int i = 0, n = elements.size(); i < n; i++) {
                Element e = elements.get(i);
                if (e.state == Element.State.FIELD || e.state == Element.State.FLIGHT) stepElement(e, dt, intakeOn);
            }
            for (HiveModel h : hives) h.step(dt);
            slowAcc += dt;
            if (slowAcc >= SLOW_DT) {
                double d = slowAcc;
                slowAcc = 0;
                slowTick(d);
            }
        }
    }

    private void sampleRobot() {
        Chassis c = world.chassis;
        Pose2 p = c.pose();
        rx = Units.mToIn(p.x);
        ry = Units.mToIn(p.y);
        rcos = Math.cos(p.heading);
        rsin = Math.sin(p.heading);
        rL = Units.mToIn(c.length);
        rW = Units.mToIn(c.width);
        rH = Units.mToIn(c.height);
        rRadius = Math.hypot(rL, rW) / 2;
        Vec2 v = c.velocityField();
        rvx = Units.mToIn(v.x);
        rvy = Units.mToIn(v.y);
        rom = c.omega();
    }

    private boolean intakeActive() { return manualIntake || mech.intakeRunning(); }

    private void slowTick(double dt) {
        mech.refresh();
        if (phase == Phase.PRE && !allianceLocked) refreshAlliance();
        if (manualLaunch) {
            manualLaunch = false;
            fire(manualSpeed());
        } else if (mech.shotDue(now)) {
            fire(mech.flywheelsBound() ? mech.launchSpeed() : launcher().manualSpeedInPerSec);
        }
        retrieveFromFlowers();
        robotRules(dt);
        humanScript();
        if (phase == Phase.AUTO) {
            leaveEarned = robotLeft();
            autoParkEarned = robotParked();
        } else if (phase == Phase.TELEOP || (phase == Phase.ENDED && !finalized)) {
            teleopPark = robotParked();
        }
        if (!finalized) computeScores();
        if (phase == Phase.ENDED && !finalized && (settled() || now - endedAt >= SETTLE_MAX_S)) finalizeMatch();
    }

    private double manualSpeed() {
        double s = mech.flywheelsBound() ? mech.launchSpeed() : 0;
        return s > 0 ? s : launcher().manualSpeedInPerSec;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // element physics
    // ------------------------------------------------------------------------------------------------------------------------------

    private void stepElement(Element e, double dt, boolean intakeOn) {
        if (e.asleep) {
            if (!nearRobot(e)) return;
            if (intakeOn && tryPickup(e)) return;
            if (robotCollide(e)) wake(e);
            return;
        }
        supported = false;
        touched = false;
        e.px = e.x;
        e.py = e.y;
        e.pz = e.z;
        e.vz -= GRAVITY * dt;
        e.x += e.vx * dt;
        e.y += e.vy * dt;
        e.z += e.vz * dt;

        for (FlowerModel f : flowers) {
            if (Math.abs(e.x - f.flower.x) > 8 || Math.abs(e.y - f.flower.y) > 8) continue;
            if (f.capture(e)) {
                flowerCaptured(e, f);
                return;
            }
        }
        if (e.launched) {
            for (HiveModel h : hives) {
                if (h.capture(e)) {
                    hiveCaptured(e, h);
                    return;
                }
            }
        }
        collideSolids(e);
        for (HiveModel h : hives) {
            if (h.collide(e)) {
                touched = true;
                supported = true;
            }
        }
        if (!bounds(e, dt)) return;
        if (intakeOn && tryPickup(e)) return;
        robotCollide(e);
        pairs(e);
        for (FlowerModel f : flowers) f.nudge(e, dt);

        if (touched) e.released = false;
        double sp2 = e.vx * e.vx + e.vy * e.vy + e.vz * e.vz;
        if (supported && sp2 < SLEEP_SPEED * SLEEP_SPEED) {
            e.restTime += dt;
            if (e.restTime >= SLEEP_TIME) {
                e.asleep = true;
                e.vx = e.vy = e.vz = 0;
                e.launched = false;
                e.released = false;
            }
        } else {
            e.restTime = 0;
        }
    }

    /** Wakes an element and whatever rests on it. */
    private void wake(Element o) {
        o.asleep = false;
        o.restTime = 0;
        for (Element p : elements) {
            if (!p.asleep || p.state != Element.State.FIELD || p.z <= o.z + 0.5) continue;
            double dx = p.x - o.x, dy = p.y - o.y;
            double lim = p.r + o.r + 0.3;
            if (dx * dx + dy * dy < lim * lim && p.z - o.z < lim) wake(p);
        }
    }

    private void collideSolids(Element e) {
        for (FieldMap.Box b : solids) {
            if (e.x + e.r < b.minX || e.x - e.r > b.maxX || e.y + e.r < b.minY || e.y - e.r > b.maxY
                    || e.z + e.r < b.minZ || e.z - e.r > b.maxZ) continue;
            double dx = e.x - clamp(e.x, b.minX, b.maxX);
            double dy = e.y - clamp(e.y, b.minY, b.maxY);
            double dz = e.z - clamp(e.z, b.minZ, b.maxZ);
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 >= e.r * e.r) continue;
            double nx = 0, ny = 0, nz = 0, pen;
            if (d2 > 1e-12) {
                double d = Math.sqrt(d2);
                nx = dx / d;
                ny = dy / d;
                nz = dz / d;
                pen = e.r - d;
            } else {
                // the centre is inside the box: leave through the nearest face
                double[] f = {e.x - b.minX, b.maxX - e.x, e.y - b.minY, b.maxY - e.y, e.z - b.minZ, b.maxZ - e.z};
                int k = 0;
                for (int i = 1; i < 6; i++) if (f[i] < f[k]) k = i;
                if (k == 0) nx = -1; else if (k == 1) nx = 1;
                else if (k == 2) ny = -1; else if (k == 3) ny = 1;
                else if (k == 4) nz = -1; else nz = 1;
                pen = f[k] + e.r;
            }
            e.x += nx * pen;
            e.y += ny * pen;
            e.z += nz * pen;
            double vn = e.vx * nx + e.vy * ny + e.vz * nz;
            if (vn < 0) {
                double k = 1 + (vn < -6 ? SOLID_RESTITUTION : 0);
                e.vx -= k * vn * nx;
                e.vy -= k * vn * ny;
                e.vz -= k * vn * nz;
            }
            touched = true;
            if (nz > 0.7) supported = true;
        }
    }

    /** Floor, rolling friction and perimeter walls. False when the element left the field over a wall. */
    private boolean bounds(Element e, double dt) {
        double lim = wall + e.r;
        if (Math.abs(e.x) > lim || Math.abs(e.y) > lim) {
            leaveField(e);
            return false;
        }
        if (e.z <= e.r) {
            e.z = e.r;
            if (e.vz < 0) e.vz = -e.vz > 5 ? -e.vz * FLOOR_RESTITUTION : 0;
            touched = true;
            if (e.vz < 1) {
                supported = true;
                double sp = Math.hypot(e.vx, e.vy);
                if (sp > 1e-9) {
                    double f = Math.max(0, sp - ROLL_DECEL * dt) / sp;
                    e.vx *= f;
                    e.vy *= f;
                }
            }
        }
        if (e.z - e.r < wallHeight) {
            if (e.x > wall - e.r) {
                e.x = wall - e.r;
                if (e.vx > 0) e.vx = -e.vx * WALL_RESTITUTION;
                touched = true;
            } else if (e.x < -wall + e.r) {
                e.x = -wall + e.r;
                if (e.vx < 0) e.vx = -e.vx * WALL_RESTITUTION;
                touched = true;
            }
            if (e.y > wall - e.r) {
                e.y = wall - e.r;
                if (e.vy > 0) e.vy = -e.vy * WALL_RESTITUTION;
                touched = true;
            } else if (e.y < -wall + e.r) {
                e.y = -wall + e.r;
                if (e.vy < 0) e.vy = -e.vy * WALL_RESTITUTION;
                touched = true;
            }
        }
        return true;
    }

    private void leaveField(Element e) {
        boolean shot = e.launched;
        e.vx = e.vy = e.vz = 0;
        e.asleep = true;
        e.launched = e.released = false;
        String what = e.type + (e.type == Element.Type.NECTAR ? " (" + e.alliance + ")" : "");
        if (e.type == Element.Type.NECTAR) {
            // NECTAR that leaves the field may be entered again through the LOADING ZONE at any time (G426)
            e.state = Element.State.HUMAN;
            humanBudget[e.alliance.ordinal()]++;
        } else {
            e.state = Element.State.OUT;
        }
        addEvent(what + " left the FIELD");
        if (shot) foul("G405", robotAlliance, what + " launched out of the FIELD", false);
    }

    private void pairs(Element e) {
        for (Element o : elements) {
            if (o == e || (o.state != Element.State.FIELD && o.state != Element.State.FLIGHT)) continue;
            if (!o.asleep && o.id < e.id) continue;
            double dx = e.x - o.x, dy = e.y - o.y, dz = e.z - o.z;
            double rr = e.r + o.r;
            if (Math.abs(dx) > rr || Math.abs(dy) > rr || Math.abs(dz) > rr) continue;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 >= rr * rr) continue;
            double d = Math.sqrt(d2);
            double nx = 0, ny = 0, nz = 1;
            if (d > 1e-6) {
                nx = dx / d;
                ny = dy / d;
                nz = dz / d;
            } else {
                d = 0;
            }
            double rvn = (e.vx - o.vx) * nx + (e.vy - o.vy) * ny + (e.vz - o.vz) * nz;
            double we = 1 / e.weight();
            double wo = o.asleep && rvn > -4 ? 0 : 1 / o.weight();
            double ws = we + wo;
            double pen = rr - d;
            e.x += nx * pen * we / ws;
            e.y += ny * pen * we / ws;
            e.z += nz * pen * we / ws;
            if (wo > 0) {
                o.x -= nx * pen * wo / ws;
                o.y -= ny * pen * wo / ws;
                o.z -= nz * pen * wo / ws;
            }
            if (rvn < 0) {
                double j = -(1 + (rvn < -3 ? PAIR_RESTITUTION : 0)) * rvn / ws;
                e.vx += j * we * nx;
                e.vy += j * we * ny;
                e.vz += j * we * nz;
                if (wo > 0) {
                    o.vx -= j * wo * nx;
                    o.vy -= j * wo * ny;
                    o.vz -= j * wo * nz;
                    if (o.asleep) wake(o);
                }
            }
            if (nz > 0.7 && o.asleep) supported = true;
            touched = true;
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // the robot
    // ------------------------------------------------------------------------------------------------------------------------------

    private boolean nearRobot(Element e) {
        double dx = e.x - rx, dy = e.y - ry;
        double reach = rRadius + e.r + 0.5;
        return dx * dx + dy * dy <= reach * reach;
    }

    /** Pushes the element out of the robot box and gives it the velocity of the robot at the contact. True on contact. */
    private boolean robotCollide(Element e) {
        if (now < e.ignoreRobotUntil || !nearRobot(e)) return false;
        double dx = e.x - rx, dy = e.y - ry;
        double a = dx * rcos + dy * rsin, b = -dx * rsin + dy * rcos;
        double ha = rL / 2, hb = rW / 2;
        double da = a - clamp(a, -ha, ha), db = b - clamp(b, -hb, hb), dz = e.z - clamp(e.z, ROBOT_CLEARANCE, rH);
        double d2 = da * da + db * db + dz * dz;
        if (d2 >= e.r * e.r) return false;
        double na, nb, nz, pen;
        if (d2 > 1e-12) {
            double d = Math.sqrt(d2);
            na = da / d;
            nb = db / d;
            nz = dz / d;
            pen = e.r - d;
        } else {
            // the centre is inside the box: out through the nearest side, or over the top when the element is high up
            double fa = ha - Math.abs(a), fb = hb - Math.abs(b), fz = Math.min(rH - e.z, e.z - ROBOT_CLEARANCE);
            if (e.z - ROBOT_CLEARANCE > (rH - ROBOT_CLEARANCE) / 2 && fz < Math.min(fa, fb)) {
                na = nb = 0;
                nz = 1;
                pen = rH - e.z + e.r;
            } else if (fa < fb) {
                na = a < 0 ? -1 : 1;
                nb = nz = 0;
                pen = fa + e.r;
            } else {
                nb = b < 0 ? -1 : 1;
                na = nz = 0;
                pen = fb + e.r;
            }
        }
        double nx = na * rcos - nb * rsin, ny = na * rsin + nb * rcos;
        e.x += nx * pen;
        e.y += ny * pen;
        e.z += nz * pen;
        double cx = e.x - rx, cy = e.y - ry;
        double vn = (e.vx - (rvx - rom * cy)) * nx + (e.vy - (rvy + rom * cx)) * ny + e.vz * nz;
        if (vn < 0) {
            double k = 1 + (vn < -8 ? ROBOT_RESTITUTION : 0);
            e.vx -= k * vn * nx;
            e.vy -= k * vn * ny;
            e.vz -= k * vn * nz;
        }
        touched = true;
        return true;
    }

    private boolean tryPickup(Element e) {
        if (e.state != Element.State.FIELD || e.launched || now < e.ignoreRobotUntil) return false;
        if (held.size() >= capacity() || now - lastPickup < PICKUP_GAP_S || e.z > 2 * e.r + 1) return false;
        if (!nearRobot(e)) return false;
        RobotConfig.Intake ic = intake();
        double dx = e.x - rx, dy = e.y - ry;
        double a = dx * rcos + dy * rsin, b = -dx * rsin + dy * rcos;
        double a0 = rL / 2 + ic.forwardIn;
        if (a < a0 || a > a0 + ic.depthIn) return false;
        if (Math.abs(b) > (ic.widthIn > 0 ? ic.widthIn : rW - 1) / 2) return false;
        pickupChecks(e);
        hold(e);
        return true;
    }

    /** What picking this element up means under the rules. */
    private void pickupChecks(Element e) {
        if (phase == Phase.AUTO && Math.abs(e.y) > 0.5 && Alliance.ofY(e.y) != robotAlliance) {
            foul("G402", robotAlliance, e.type + " taken from the opposing alliance's side of the FIELD during AUTO", false);
        }
        if (e.type == Element.Type.NECTAR && e.alliance != robotAlliance) {
            foul("G408", robotAlliance, "CONTROLLED opposing NECTAR", false);
        }
        if (e.released) foul("G409", robotAlliance, "caught an element released by a TIPPING HIVE", false);
    }

    private void hold(Element e) {
        e.state = Element.State.HELD;
        e.container = -1;
        e.vx = e.vy = e.vz = 0;
        e.asleep = true;
        e.launched = e.released = false;
        held.add(e);
        lastPickup = now;
    }

    /** The intake mouth in front of a FLOWER's retrieval opening takes POLLEN out of the bottom of the stack. */
    private void retrieveFromFlowers() {
        if (held.size() >= capacity() || now - lastRetrieve < RETRIEVE_GAP_S || !intakeActive()) return;
        RobotConfig.Intake ic = intake();
        double front = rL / 2 + ic.forwardIn + ic.depthIn / 2;
        double px = rx + rcos * front, py = ry + rsin * front;
        for (FlowerModel f : flowers) {
            if (Math.hypot(px - f.openingX(), py - f.openingY()) > 5) continue;
            if (-(rcos * f.inX + rsin * f.inY) < 0.6) continue;
            Element e = f.releaseBottom();
            if (e == null) continue;
            hold(e);
            lastRetrieve = now;
            addEvent("POLLEN taken out of FLOWER " + f.flower.index);
            return;
        }
    }

    /** Fires the oldest held element from the muzzle. Below the minimum speed it just drops out in front of the robot. */
    private void fire(double speed) {
        if (held.isEmpty()) return;
        RobotConfig.Launcher lc = launcher();
        Element e = held.remove(0);
        double heading = Math.atan2(rsin, rcos);
        e.x = e.px = rx + rcos * lc.muzzleForwardIn - rsin * lc.muzzleLeftIn;
        e.y = e.py = ry + rsin * lc.muzzleForwardIn + rcos * lc.muzzleLeftIn;
        e.z = e.pz = lc.muzzleHeightIn;
        e.state = Element.State.FIELD;
        e.asleep = false;
        e.restTime = 0;
        e.released = false;
        e.ignoreRobotUntil = now + 0.15;
        if (speed >= lc.minSpeedInPerSec) {
            double yaw = heading + Math.toRadians(lc.yawDeg + rng.nextGaussian() * lc.spreadDeg);
            double pitch = Math.toRadians(lc.pitchDeg + rng.nextGaussian() * lc.spreadDeg * 0.7);
            e.vx = speed * Math.cos(pitch) * Math.cos(yaw) + rvx;
            e.vy = speed * Math.cos(pitch) * Math.sin(yaw) + rvy;
            e.vz = speed * Math.sin(pitch);
            e.launched = true;
        } else {
            e.vx = 20 * rcos + rvx;
            e.vy = 20 * rsin + rvy;
            e.vz = 0;
            e.launched = false;
        }
    }

    private void flowerCaptured(Element e, FlowerModel f) {
        addEvent(e.type + (e.type == Element.Type.NECTAR ? " (" + e.alliance + ")" : "") + " entered FLOWER " + f.flower.index);
        if (e.type == Element.Type.NECTAR && matchRemaining() > Rules.FLOWER_UNLOCK_REMAINING_S) {
            foul("G410", robotAlliance, "NECTAR placed into a FLOWER with more than 60 s left", false);
        }
    }

    private void hiveCaptured(Element e, HiveModel h) {
        addEvent(e.type + (e.type == Element.Type.NECTAR ? " (" + e.alliance + ")" : "") + " LAUNCHED into the " + h.alliance + " HIVE ("
                + String.format(Locale.ROOT, "%.1f/%.1f", h.load(h.upCell()), h.tipLoad) + ")");
    }

    @Override
    public synchronized void tipStarted(HiveModel h) {
        field.setOption(optionKey(h), h.audienceUp ? "audience" : "scoring");
        for (Element e : elements) if (e.asleep && e.state == Element.State.FIELD && e.z - e.r > 0.5) wake(e);
    }

    @Override
    public synchronized void tipCompleted(HiveModel h) {
        if (phase == Phase.PRE) {
            addEvent(h.alliance + " HIVE tipped (before the match, not scored)");
            return;
        }
        int i = h.alliance.ordinal();
        boolean early = phase == Phase.AUTO || phase == Phase.TRANSITION;
        if (early) autoTips[i]++; else teleopTips[i]++;
        humanBudget[i]++;
        addEvent(h.alliance + " HIVE TIPPED (" + (early ? "AUTO" : "TELEOP") + ", tip " + (autoTips[i] + teleopTips[i]) + ")");
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // rules
    // ------------------------------------------------------------------------------------------------------------------------------

    private void foul(String rule, Alliance offender, String note, boolean strategic) {
        if (phase == Phase.PRE) return;
        List<Rules.Violation> added = ledger.violate(matchElapsed(), phase.name(), rule, offender, note, strategic);
        for (Rules.Violation v : added) {
            Rules.Rule r = Rules.rule(v.rule);
            addEvent(v.level + " " + v.rule + (r != null ? " " + r.title : "") + " against " + v.against + ": " + v.note);
        }
    }

    private void startChecks() {
        if (rL > 18.05 || rW > 18.05 || rH > 18.05) {
            foul("R102", robotAlliance, String.format(Locale.ROOT, "start configuration %.1f x %.1f x %.1f in exceeds 18 x 18 x 18 in", rL, rW, rH), false);
        }
        if (world.motors.size() > 8) foul("R503", robotAlliance, world.motors.size() + " motors configured (limit 8)", false);
        if (world.servos.size() > 8) foul("R503", robotAlliance, world.servos.size() + " servos configured (limit 8)", false);
        if (config != null && config.hubs != null && config.hubs.size() > 2) {
            foul("R701", robotAlliance, config.hubs.size() + " REV hubs configured (a Control Hub and at most one Expansion Hub)", false);
        }
    }

    private void robotRules(double dt) {
        double speed = Math.hypot(rvx, rvy);
        peak = Math.max(speed, peak - 600 * dt);
        boolean moving = motionDetected();
        boolean quiet = phase == Phase.TRANSITION && now - transitionStart > QUIET_GRACE_S
                || phase == Phase.ENDED && now - endedAt > QUIET_GRACE_S && now - endedAt < QUIET_END_S;
        if (quiet && moving) {
            motionS += dt;
            String rule = phase == Phase.TRANSITION ? "G403" : "G404";
            if (motionS >= 0.5 && motionLevel < 1) {
                motionLevel = 1;
                foul(rule, robotAlliance, "robot moved when it had to be motionless", false);
            }
            if (motionS >= 3.0 && motionLevel < 2) {
                motionLevel = 2;
                foul(rule, robotAlliance, "robot kept moving when it had to be motionless", true);
            }
        }
        if (held.size() > 4) {
            heldOverS += dt;
            if (heldOverS >= 3 && now - lastG407 >= 3) {
                lastG407 = now;
                foul("G407", robotAlliance, held.size() + " scoring elements CONTROLLED (limit 4)", held.size() >= 6);
            }
        } else {
            heldOverS = 0;
        }
        if ((phase == Phase.AUTO || phase == Phase.TELEOP) && peak >= 20 && now - lastG417 >= 2) {
            for (FieldMap.Box b : hiveFrames) {
                if (b.minZ >= rH || !robotOverlaps(b.minX - 0.3, b.minY - 0.3, b.maxX + 0.3, b.maxY + 0.3)) continue;
                lastG417 = now;
                foul("G417", robotAlliance, String.format(Locale.ROOT, "robot hit the HIVE frame at %.0f in/s", peak), peak >= 45);
                break;
            }
        }
    }

    /** The robot moves or a mechanism is powered (G403 / G404). */
    private boolean motionDetected() {
        boolean moving = Math.hypot(rvx, rvy) > 3 || Math.abs(rom) > 0.3;
        for (MotorState m : world.motors) {
            if (m.enabled && (Math.abs(m.power) > 0.05 || (m.useVelocityTarget && Math.abs(m.targetVelocityTps) > 20))) moving = true;
        }
        for (ServoState s : world.servos) {
            double p = s.position();
            Double old = servoSeen.put(s, p);
            if (old != null && Math.abs(old - p) > 0.02) moving = true;
        }
        return moving;
    }

    /** Scripted human player of the robot's alliance: enters NECTAR as G426 allows. */
    private void humanScript() {
        if (!scriptedHuman || phase != Phase.TELEOP || now - lastHumanDrop < HUMAN_GAP_S) return;
        Alliance a = robotAlliance;
        boolean free = matchRemaining() <= Rules.FLOWER_UNLOCK_REMAINING_S;
        if (!free && humanBudget[a.ordinal()] <= 0) return;
        double[] z = loading[a.ordinal()];
        for (Element e : elements) {
            if (e.type == Element.Type.NECTAR && e.alliance == a && e.state == Element.State.FIELD
                    && e.x >= z[0] - e.r && e.x <= z[2] + e.r && e.y >= z[1] - e.r && e.y <= z[3] + e.r) return;
        }
        for (Element e : elements) {
            if (e.state == Element.State.HUMAN && e.type == Element.Type.NECTAR && e.alliance == a) {
                if (!free) humanBudget[a.ordinal()]--;
                dropNectar(e, a);
                addEvent("Human player entered NECTAR (" + a + ")");
                return;
            }
        }
    }

    private void addEvent(String text) {
        events.add(new Event(++eventSeq, matchElapsed(), text));
        if (events.size() > 200) events.remove(0);
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // robot geometry, scoring
    // ------------------------------------------------------------------------------------------------------------------------------

    /** LEAVE: the robot no longer touches the perimeter wall. */
    private boolean robotLeft() {
        double ex = Math.abs(rcos) * rL / 2 + Math.abs(rsin) * rW / 2;
        double ey = Math.abs(rsin) * rL / 2 + Math.abs(rcos) * rW / 2;
        double lim = wall - 0.3;
        return rx + ex < lim && rx - ex > -lim && ry + ey < lim && ry - ey > -lim;
    }

    /** PARK: the robot is at least partially in its alliance's LOADING ZONE. */
    private boolean robotParked() {
        double[] z = loading[robotAlliance.ordinal()];
        return robotOverlaps(z[0], z[1], z[2], z[3]);
    }

    /** SAT test of the robot footprint against an axis-aligned rectangle. */
    private boolean robotOverlaps(double minX, double minY, double maxX, double maxY) {
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2, hx = (maxX - minX) / 2, hy = (maxY - minY) / 2;
        double dx = rx - cx, dy = ry - cy;
        double ha = rL / 2, hb = rW / 2;
        double ac = Math.abs(rcos), as = Math.abs(rsin);
        if (Math.abs(dx) > hx + ac * ha + as * hb) return false;
        if (Math.abs(dy) > hy + as * ha + ac * hb) return false;
        if (Math.abs(dx * rcos + dy * rsin) > ha + hx * ac + hy * as) return false;
        return Math.abs(-dx * rsin + dy * rcos) <= hb + hx * as + hy * ac;
    }

    private int groundedIn(double[] r) {
        int n = 0;
        for (Element e : elements) {
            if (e.state != Element.State.FIELD || !e.asleep && Math.hypot(e.vx, e.vy) > 5 || e.z > e.r + 0.5) continue;
            double dx = e.x - clamp(e.x, r[0], r[2]), dy = e.y - clamp(e.y, r[1], r[3]);
            if (dx * dx + dy * dy < e.r * e.r) n++;
        }
        return n;
    }

    private void computeScores() {
        boolean scoring = phase == Phase.TELEOP || phase == Phase.ENDED;
        for (Alliance a : Alliance.values()) {
            int i = a.ordinal();
            Score s = scores[i];
            boolean mine = a == robotAlliance;
            s.leave = mine && leaveEarned ? Rules.LEAVE_POINTS : 0;
            s.autoPark = mine && autoParkEarned ? Rules.PARK_POINTS : 0;
            s.teleopPark = mine && teleopPark ? Rules.PARK_POINTS : 0;
            s.autoTips = autoTips[i];
            s.teleopTips = teleopTips[i];
            s.cell = scoring ? hiveOf(a).upContents().size() * Rules.CELL_ELEMENT_POINTS : 0;
            s.flower = 0;
            s.bottom = 0;
            if (scoring) {
                for (FlowerModel f : flowers) {
                    if (f.owner() == a) s.flower += f.scoringCount() * Rules.FLOWER_ELEMENT_POINTS;
                    if (f.bottomNectarAlliance() == a) s.bottom += Rules.BOTTOM_NECTAR_POINTS;
                }
            }
            s.garden = scoring ? groundedIn(garden[i]) * Rules.GARDEN_ELEMENT_POINTS : 0;
            s.penalty = ledger.pointsFor(a);
            s.dq = ledger.disqualified(a);
        }
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // reporting
    // ------------------------------------------------------------------------------------------------------------------------------

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }

    private Map<String, Object> scoreMap(Score s) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean z = s.dq;
        m.put("auto", z ? 0 : s.auto());
        m.put("teleop", z ? 0 : s.teleop());
        m.put("penalty", z ? 0 : s.penalty);
        m.put("total", s.total());
        m.put("leave", z ? 0 : s.leave);
        m.put("park", z ? 0 : s.autoPark + s.teleopPark);
        m.put("hiveTips", z ? 0 : (s.autoTips + s.teleopTips) * Rules.TIP_POINTS);
        m.put("cell", z ? 0 : s.cell);
        m.put("flower", z ? 0 : s.flower);
        m.put("bottomNectar", z ? 0 : s.bottom);
        m.put("garden", z ? 0 : s.garden);
        m.put("rp", finalized ? s.rp : 0);
        m.put("tips", s.autoTips + s.teleopTips);
        m.put("swarm", finalized && s.swarm);
        m.put("pollinator1", finalized && s.pollinator1);
        m.put("pollinator2", finalized && s.pollinator2);
        m.put("dq", s.dq);
        return m;
    }

    public synchronized Map<String, Object> scoreboard() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("red", scoreMap(scores[Alliance.RED.ordinal()]));
        m.put("blue", scoreMap(scores[Alliance.BLUE.ordinal()]));
        return m;
    }

    public synchronized List<Map<String, Object>> violations() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Rules.Violation v : ledger.log()) out.add(v.toMap());
        return out;
    }

    public synchronized List<Map<String, Object>> eventLog() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Event e : events) out.add(e.toMap());
        return out;
    }

    public synchronized Map<String, Object> snapshot() {
        sampleRobot();
        if (phase == Phase.PRE && !allianceLocked) refreshAlliance();
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("name", "BIOBUZZ");
        g.put("phase", phase.name());
        g.put("practice", practice);
        g.put("finalized", finalized);
        g.put("clock", clockMap());
        boolean running = phase == Phase.AUTO || phase == Phase.TRANSITION || phase == Phase.TELEOP;
        g.put("flowerUnlockIn", running ? (Object) round2(Math.max(0, matchRemaining() - Rules.FLOWER_UNLOCK_REMAINING_S)) : null);
        g.put("alliance", robotAlliance.name());
        g.put("scoreboard", scoreboard());
        g.put("elements", elementList());
        List<Object> hl = new ArrayList<>();
        for (HiveModel h : hives) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("alliance", h.alliance.name());
            m.put("angle", round2(h.angle));
            m.put("up", h.upSide());
            m.put("tips", autoTips[h.alliance.ordinal()] + teleopTips[h.alliance.ordinal()]);
            m.put("load", round2(h.load(h.upCell())));
            m.put("tipLoad", round2(h.tipLoad));
            hl.add(m);
        }
        g.put("hives", hl);
        List<Object> fl = new ArrayList<>();
        for (FlowerModel f : flowers) {
            Alliance owner = f.owner();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", f.flower.index);
            m.put("owner", owner == null ? null : owner.name());
            m.put("count", f.size());
            m.put("scoring", owner != null);
            fl.add(m);
        }
        g.put("flowers", fl);
        Map<String, Object> robot = new LinkedHashMap<>();
        robot.put("held", held.size());
        robot.put("capacity", capacity());
        robot.put("leave", robotLeft());
        robot.put("park", robotParked());
        robot.put("intake", round2(manualIntake ? Math.max(1, mech.intakeLevel()) : mech.intakeLevel()));
        robot.put("launcherSpeed", round2(mech.launcherLevel()));
        g.put("robot", robot);
        Map<String, Object> human = new LinkedHashMap<>();
        int staged = 0;
        for (Element e : elements) {
            if (e.state == Element.State.HUMAN && e.type == Element.Type.NECTAR && e.alliance == robotAlliance) staged++;
        }
        human.put("nectar", staged);
        human.put("budget", humanBudget[robotAlliance.ordinal()]);
        human.put("free", phase == Phase.PRE || matchRemaining() <= Rules.FLOWER_UNLOCK_REMAINING_S);
        g.put("human", human);
        List<Object> vl = new ArrayList<>(violations());
        g.put("violations", vl);
        List<Object> el = new ArrayList<>();
        for (int i = Math.max(0, events.size() - 12); i < events.size(); i++) el.add(events.get(i).toMap());
        g.put("events", el);
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("allianceMode", allianceMode);
        st.put("scriptedHuman", scriptedHuman);
        st.put("tipLoad", round2(hives.isEmpty() ? 0 : hives.get(0).tipLoad));
        g.put("settings", st);
        g.put("mechanisms", mech.describe());
        return g;
    }

    private Map<String, Object> clockMap() {
        double total, elapsed;
        switch (phase) {
            case AUTO:
                total = Rules.AUTO_S;
                elapsed = now - autoStart;
                break;
            case TRANSITION:
                total = Rules.TRANSITION_S;
                elapsed = now - transitionStart;
                break;
            case TELEOP:
                total = Rules.TELEOP_S;
                elapsed = now - teleopStart;
                break;
            case ENDED:
                total = 0;
                elapsed = 0;
                break;
            default:
                return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("periodTotal", total);
        m.put("periodRemaining", round2(Math.max(0, total - elapsed)));
        m.put("matchElapsed", round2(matchElapsed()));
        m.put("matchRemaining", round2(matchRemaining()));
        return m;
    }

    private List<Object> elementList() {
        List<Object> out = new ArrayList<>(elements.size());
        for (Element e : elements) {
            double x = e.x, y = e.y, z = e.z;
            String s;
            switch (e.state) {
                case HUMAN:
                case OUT:
                    continue;
                case HELD: {
                    s = "HELD";
                    double a = (held.indexOf(e) - 1.5) * 3.3;
                    x = rx + rcos * a;
                    y = ry + rsin * a;
                    z = 6;
                    break;
                }
                case FLOWER:
                    s = "FLOWER";
                    break;
                case CELL:
                    s = "CELL";
                    break;
                default:
                    if (!e.asleep && e.z > e.r + 0.5 && (Math.abs(e.vz) > 1 || e.launched)) s = "FLIGHT";
                    else if (e.asleep && inGarden(e)) s = "GARDEN";
                    else s = "FIELD";
                    break;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id);
            m.put("t", e.type == Element.Type.POLLEN ? "P" : "N");
            m.put("c", e.type == Element.Type.POLLEN ? "Y" : e.alliance == Alliance.BLUE ? "B" : "R");
            m.put("x", round2(x));
            m.put("y", round2(y));
            m.put("z", round2(z));
            m.put("s", s);
            out.add(m);
        }
        return out;
    }

    private boolean inGarden(Element e) {
        for (double[] r : garden) {
            if (e.x >= r[0] - e.r && e.x <= r[2] + e.r && e.y >= r[1] - e.r && e.y <= r[3] + e.r) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // for tests
    // ------------------------------------------------------------------------------------------------------------------------------

    List<Element> elements() { return elements; }

    List<Element> heldElements() { return held; }

    HiveModel hiveModel(Alliance a) { return hiveOf(a); }

    FlowerModel flowerModel(int index) { return flowerByIndex(index); }

    String groupOf(Element e) { return groups.get(e.id); }
}
