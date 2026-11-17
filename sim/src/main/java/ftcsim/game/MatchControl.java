package ftcsim.game;

import com.qualcomm.robotcore.eventloop.opmode.SimOpModeRunner;
import com.qualcomm.robotcore.util.RobotLog;
import ftcsim.runner.OpModeEntry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The Driver Station side of a match. The game clock (see {@link BiobuzzGame}) decides when AUTO, the transition and TELEOP begin
 * and end; this class turns that into the actions the field staff and drivers perform: stop the Autonomous OpMode when AUTO is over,
 * INIT the TeleOp OpMode during the transition and start it when TELEOP begins, stop it when the MATCH ends.
 * It can also run a whole match on its own ({@link #run}).
 *
 * <p>Every OpMode init/start/stop issued here happens on this class's own thread, never on the physics thread.
 */
public final class MatchControl {
    private static final String TAG = "Match";
    /** Pause between INIT and START of an OpMode started by {@link #run}: time for a LinearOpMode to reach waitForStart(). */
    private static final long INIT_SETTLE_MS = 1200;

    public enum Step { IDLE, AUTO, TRANSITION, TELEOP, DONE }

    private final SimOpModeRunner runner;
    private final BiobuzzGame game;
    private final Runnable resetRobot;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();
    private volatile OpModeEntry autoEntry;
    private volatile OpModeEntry teleopEntry;
    private volatile boolean active;
    private volatile long runs;
    private Thread thread;

    /** @param resetRobot puts the robot back on its starting pose (and stops its mechanisms) */
    public MatchControl(SimOpModeRunner runner, BiobuzzGame game, Runnable resetRobot) {
        this.runner = runner;
        this.game = game;
        this.resetRobot = resetRobot;
    }

    public synchronized void start() {
        if (thread != null) return;
        thread = new Thread(this::loop, "ftcsim-match");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Resets the field and the robot, INITs and STARTs {@code auto} (when not null) and lets the clock take it from there; {@code teleop}
     * (when not null) is started by this class after the transition. With only a TeleOp OpMode the match is a practice run of TELEOP.
     */
    public void run(OpModeEntry auto, OpModeEntry teleop) {
        if (auto == null && teleop == null) throw new IllegalArgumentException("choose an Autonomous and/or a TeleOp OpMode to run");
        commands.add(() -> begin(auto, teleop));
    }

    /** Stops the running OpMode and cancels the plan; the field is left as it is. */
    public void abort() { commands.add(this::abortNow); }

    /** Stops everything and puts the field and the robot back to the pre-match state. */
    public void reset() {
        commands.add(() -> {
            abortNow();
            game.reset();
            resetRobot.run();
        });
    }

    /** A match started with {@link #run} is in progress. */
    public boolean active() { return active; }

    /** Number of matches begun with {@link #run} (counted once the field has been put back, before the OpMode is INITed). */
    public long runs() { return runs; }

    public Step step() {
        switch (game.phase()) {
            case AUTO: return Step.AUTO;
            case TRANSITION: return Step.TRANSITION;
            case TELEOP: return Step.TELEOP;
            case ENDED: return Step.DONE;
            default: return Step.IDLE;
        }
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", active);
        m.put("step", step().name());
        OpModeEntry a = autoEntry, t = teleopEntry;
        m.put("auto", a == null ? null : a.name);
        m.put("teleop", t == null ? null : t.name);
        return m;
    }

    // ------------------------------------------------------------------ thread
    private void loop() {
        while (true) {
            try {
                for (Runnable c; (c = commands.poll()) != null; ) c.run();
                tick();
            } catch (Throwable t) {
                RobotLog.ee(TAG, t, "match control failed");
            }
            try { Thread.sleep(20); } catch (InterruptedException e) { return; }
        }
    }

    private void tick() {
        int events = game.pollEvents();
        if (events == 0) return;
        if ((events & BiobuzzGame.AUTO_ENDED) != 0) {
            OpModeEntry cur = runner.entry();
            if (runner.state() == SimOpModeRunner.State.RUNNING && cur != null && cur.flavor == OpModeEntry.Flavor.AUTONOMOUS) {
                RobotLog.ii(TAG, "AUTO is over: stopping %s", cur.name);
                runner.stop();
            }
            OpModeEntry t = teleopEntry;
            if (active && t != null) initTeleop(t);
        }
        if ((events & BiobuzzGame.TELEOP_STARTED) != 0 && active) {
            OpModeEntry t = teleopEntry;
            if (t != null) {
                initTeleop(t);
                if (runner.state() == SimOpModeRunner.State.INIT && runner.entry() == t) runner.start();
            }
        }
        if ((events & BiobuzzGame.TELEOP_ENDED) != 0) {
            if (runner.state() == SimOpModeRunner.State.RUNNING) {
                RobotLog.ii(TAG, "The MATCH is over: stopping %s", runner.entry() == null ? "the OpMode" : runner.entry().name);
                runner.stop();
            }
            active = false;
        }
    }

    /** The drivers INIT the TeleOp OpMode during the transition. */
    private void initTeleop(OpModeEntry t) {
        if (runner.state() == SimOpModeRunner.State.INIT && runner.entry() == t) return;
        if (runner.state() == SimOpModeRunner.State.RUNNING) runner.stop();
        runner.init(t);
    }

    private void begin(OpModeEntry auto, OpModeEntry teleop) {
        abortNow();
        game.reset();
        resetRobot.run();
        runs++;
        autoEntry = auto;
        teleopEntry = teleop;
        active = true;
        OpModeEntry first = auto != null ? auto : teleop;
        RobotLog.ii(TAG, "Running a match: AUTO %s, TELEOP %s", auto == null ? "(none)" : auto.name, teleop == null ? "(none)" : teleop.name);
        runner.init(first);
        if (runner.state() != SimOpModeRunner.State.INIT) {
            RobotLog.ww(TAG, "%s did not initialise (%s): the match was not started", first.name, runner.state());
            active = false;
            return;
        }
        try { Thread.sleep(INIT_SETTLE_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        if (!active || runner.state() != SimOpModeRunner.State.INIT || runner.entry() != first) return;
        runner.start(); // the runner's state listener starts the game clock
    }

    private void abortNow() {
        active = false;
        autoEntry = null;
        teleopEntry = null;
        SimOpModeRunner.State s = runner.state();
        if (s == SimOpModeRunner.State.RUNNING || s == SimOpModeRunner.State.INIT) runner.stop();
    }
}
