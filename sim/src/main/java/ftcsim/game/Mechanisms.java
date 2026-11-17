package ftcsim.game;

import ftcsim.config.RobotConfig;
import ftcsim.physics.MotorState;
import ftcsim.physics.ServoState;
import ftcsim.physics.World;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the intake, the flywheels and the feeder among the robot's motors and servos (by the names in {@code RobotConfig.game}, else by
 * keywords in the device names) and tells the game what they are doing right now. The sim does not model the shape of a team's
 * mechanism, only what it is commanded to do: an intake that runs pulls elements in at the front of the chassis, a feeder pulse fires
 * the oldest held element with the exit speed the spinning flywheels give it.
 */
final class Mechanisms {
    private static final String[] FEED_WORDS = {"feed", "kick", "index", "transfer", "gate", "push", "trigger"};
    private static final String[] INTAKE_WORDS = {"intake", "collect", "roller", "sweep", "ingest", "suck"};
    private static final String[] FLY_WORDS = {"shoot", "launch", "fly", "shooter", "flywheel"};

    private final World world;
    private final RobotConfig.Game cfg;

    private final List<MotorState> intakeMotors = new ArrayList<>();
    private final List<MotorState> flywheels = new ArrayList<>();
    private final List<MotorState> feederMotors = new ArrayList<>();
    private final List<ServoState> intakeServos = new ArrayList<>();
    private final List<ServoState> feederServos = new ArrayList<>();
    /** Positional feeder servos currently at or above the firing threshold (so only a rising edge fires). */
    private final Map<ServoState, Boolean> feederHigh = new IdentityHashMap<>();

    private long signature = Long.MIN_VALUE;
    private boolean pending;
    private double lastShot = -1e9;

    Mechanisms(World world, RobotConfig.Game cfg) {
        this.world = world;
        this.cfg = cfg != null ? cfg : new RobotConfig.Game();
    }

    private RobotConfig.Intake in() { return cfg.intake != null ? cfg.intake : new RobotConfig.Intake(); }

    private RobotConfig.Launcher la() { return cfg.launcher != null ? cfg.launcher : new RobotConfig.Launcher(); }

    /** Looks at the device lists again when they changed (devices are created while the OpMode initialises). */
    void refresh() {
        long sig = 0;
        for (MotorState m : world.motors) sig = sig * 31 + System.identityHashCode(m) + (m.isDriveWheel ? 7 : 0);
        for (ServoState s : world.servos) sig = sig * 37 + System.identityHashCode(s);
        if (sig == signature) return;
        signature = sig;
        intakeMotors.clear(); flywheels.clear(); feederMotors.clear();
        intakeServos.clear(); feederServos.clear(); feederHigh.clear();
        RobotConfig.Intake ic = in();
        RobotConfig.Launcher lc = la();
        for (MotorState m : world.motors) {
            if (m.isDriveWheel) continue;
            if (listed(ic.motors, m.name) && ic.enabled) intakeMotors.add(m);
            else if (listed(lc.feeders, m.name) && lc.enabled) feederMotors.add(m);
            else if (listed(lc.flywheels, m.name) && lc.enabled) flywheels.add(m);
            else byKeyword(m, ic.motors.isEmpty() && ic.enabled, lc.feeders.isEmpty() && lc.enabled, lc.flywheels.isEmpty() && lc.enabled);
        }
        for (ServoState s : world.servos) {
            if (listed(ic.servos, s.name) && ic.enabled) { intakeServos.add(s); continue; }
            if (listed(lc.feeders, s.name) && lc.enabled) { addFeederServo(s); continue; }
            String n = s.name == null ? "" : s.name.toLowerCase(Locale.ROOT);
            if (lc.enabled && lc.feeders.isEmpty() && has(n, FEED_WORDS)) addFeederServo(s);
            else if (ic.enabled && ic.servos.isEmpty() && s.continuous && has(n, INTAKE_WORDS)) intakeServos.add(s);
        }
    }

    private void byKeyword(MotorState m, boolean intakeOk, boolean feederOk, boolean flyOk) {
        String n = m.name == null ? "" : m.name.toLowerCase(Locale.ROOT);
        if (feederOk && has(n, FEED_WORDS)) feederMotors.add(m);
        else if (intakeOk && has(n, INTAKE_WORDS)) intakeMotors.add(m);
        else if (flyOk && has(n, FLY_WORDS)) flywheels.add(m);
    }

    private void addFeederServo(ServoState s) {
        feederServos.add(s);
        // a servo that already rests above the threshold must not fire the moment it is bound
        feederHigh.put(s, s.position() >= la().feederServoAbove);
    }

    private static boolean listed(List<String> names, String name) {
        if (names == null || name == null) return false;
        for (String n : names) if (n != null && n.equalsIgnoreCase(name)) return true;
        return false;
    }

    private static boolean has(String lower, String[] words) {
        for (String w : words) if (lower.contains(w)) return true;
        return false;
    }

    boolean hasIntake() { return !intakeMotors.isEmpty() || !intakeServos.isEmpty(); }

    boolean hasLauncher() { return !flywheels.isEmpty() || !feederMotors.isEmpty() || !feederServos.isEmpty(); }

    /** What was found, for the log and the UI. */
    String describe() {
        return "intake " + names(intakeMotors, intakeServos) + ", flywheels " + names(flywheels, List.of()) + ", feeder " + names(feederMotors, feederServos);
    }

    private static String names(List<MotorState> motors, List<ServoState> servos) {
        List<String> n = new ArrayList<>();
        for (MotorState m : motors) n.add(m.name);
        for (ServoState s : servos) n.add(s.name);
        return n.isEmpty() ? "(none)" : n.toString();
    }

    /** Commanded power of a mechanism motor as a signed fraction of full power. */
    private static double command(MotorState m) {
        if (!m.enabled) return 0;
        if (m.useVelocityTarget) {
            double max = m.configuredMaxTps > 1 ? m.configuredMaxTps : 2800;
            return Math.max(-1, Math.min(1, m.targetVelocityTps / max));
        }
        return m.power;
    }

    /** True while an intake motor or servo is driven hard enough, in the right direction and turning. */
    boolean intakeRunning() { return intakeLevel() > 0; }

    /** 0 when the intake is off, otherwise the command strength 0..1 (for the HUD). */
    double intakeLevel() {
        RobotConfig.Intake ic = in();
        double best = 0;
        for (MotorState m : intakeMotors) {
            double p = command(m);
            if (Math.abs(p) < ic.minPower || Math.abs(m.physicalOmega()) < 5) continue;
            if (ic.direction != 0 && Math.signum(p) != Math.signum(ic.direction)) continue;
            best = Math.max(best, Math.abs(p));
        }
        for (ServoState s : intakeServos) {
            if (!s.continuous) continue;
            double off = (s.commanded - 0.5) * 2;
            if (Math.abs(off) < Math.max(0.1, ic.minPower)) continue;
            if (ic.direction != 0 && Math.signum(off) != Math.signum(ic.direction)) continue;
            best = Math.max(best, Math.abs(off));
        }
        return best;
    }

    boolean flywheelsBound() { return !flywheels.isEmpty(); }

    /** Exit speed (in/s) the spinning flywheels give an element; 0 when there are no flywheels or none is turning. */
    double launchSpeed() {
        RobotConfig.Launcher lc = la();
        double sum = 0;
        int n = 0;
        for (MotorState m : flywheels) {
            double w = Math.abs(m.physicalOmega());
            if (w < 20) continue;
            sum += w * lc.wheelRadiusIn * lc.efficiency;
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    /** Flywheel speed relative to a typical shot, 0..1 (for the HUD). */
    double launcherLevel() { return Math.min(1, launchSpeed() / Math.max(1, la().manualSpeedInPerSec * 1.3)); }

    /**
     * True once per feeder stroke: a positional feeder servo rising through {@code feederServoAbove}, or a feeder motor or continuous
     * servo that runs (then at most one shot per cooldown).
     */
    boolean shotDue(double now) {
        RobotConfig.Launcher lc = la();
        double above = lc.feederServoAbove;
        for (ServoState s : feederServos) {
            if (s.continuous) {
                if (Math.abs(s.commanded - 0.5) > 0.1) pending = true;
                continue;
            }
            double p = s.position();
            boolean high = feederHigh.getOrDefault(s, false);
            if (!high && p >= above) { feederHigh.put(s, true); pending = true; }
            else if (high && p < above - 0.1) feederHigh.put(s, false);
        }
        for (MotorState m : feederMotors) {
            if (Math.abs(command(m)) >= lc.minPower && Math.abs(m.physicalOmega()) > 5) pending = true;
        }
        if (pending && now - lastShot >= lc.cooldownS) {
            pending = false;
            lastShot = now;
            return true;
        }
        return false;
    }

    /** Forgets a pending feeder request (the match was reset). */
    void clear() {
        pending = false;
        lastShot = -1e9;
        signature = Long.MIN_VALUE;
    }
}
