package ftcsim;

import ftcsim.teamcode.TeamRepo;

import java.io.File;
import java.io.IOException;

/** Command line entry point. */
public final class Main {
    private Main() {}

    /** Thrown by {@link #parse} for bad command lines; the message is user-facing. */
    public static final class UsageException extends Exception {
        public final boolean help;
        UsageException(String message, boolean help) { super(message); this.help = help; }
    }

    public static void main(String[] args) throws Exception {
        Simulator.Options o;
        try {
            o = parse(args);
        } catch (UsageException e) {
            if (e.help) { System.out.println(usage()); return; }
            System.err.println(e.getMessage());
            System.err.println();
            System.err.println(usage());
            System.exit(2);
            return;
        }
        if (o.batch) { install(); System.exit(ftcsim.game.BatchRunner.run(o)); }
        run(o);
    }

    /** Starts the simulator and blocks. */
    public static void run(Simulator.Options o) throws IOException, InterruptedException {
        install();
        Simulator sim = new Simulator(o);
        sim.start();
        Thread.currentThread().join();
    }

    /** SDK code resolves its user-visible strings through the Android Context; serve the AARs' English values. */
    public static void install() {
        android.content.res.Resources.setStringResolver(ftcsim.generated.ResourceNames::string);
    }

    /**
     * Parses the command line, the {@code ftcsim.*} system properties and the {@code FTCSIM_*} environment.
     * Arguments override properties, properties override the environment.
     */
    public static Simulator.Options parse(String[] args) throws UsageException {
        Simulator.Options o = new Simulator.Options();
        envOrProp("ftcsim.repo", "FTCSIM_REPO", v -> o.repo = new File(v));
        envOrProp("ftcsim.autos", "FTCSIM_AUTOS", v -> o.autos = new File(v));
        envOrProp("ftcsim.map", "FTCSIM_MAP", v -> o.map = new File(v));
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--repo": o.repo = new File(value(args, ++i, a)); break;
                case "--autos": case "--folder": o.autos = new File(value(args, ++i, a)); break;
                case "--map": o.map = new File(value(args, ++i, a)); break;
                case "--port": o.port = parsePort(value(args, ++i, a)); break;
                case "--config": o.config = new File(value(args, ++i, a)); break;
                case "--samples": o.includeSamples = true; break;
                case "--prebuilt": o.prebuilt = true; break;
                case "--no-browser": o.openBrowser = false; break;
                case "--batch": o.batch = true; o.openBrowser = false; break;
                case "--report": o.report = new File(value(args, ++i, a)); break;
                case "--data": o.dataDir = new File(value(args, ++i, a)); break;
                case "--web": o.webOverrideDir = new File(value(args, ++i, a)); break;
                case "--help": case "-h": throw new UsageException("", true);
                default:
                    if (a.startsWith("-")) throw new UsageException("Unknown option " + a, false);
                    // a bare folder: an FtcRobotController project, or else a folder of OpModes
                    File dir = new File(a);
                    if (!dir.isDirectory()) throw new UsageException("Folder not found: " + dir, false);
                    if (TeamRepo.isProject(dir)) o.repo = dir; else o.autos = dir;
            }
        }
        try {
            if (o.repo != null) o.repo = requireDir(o.repo, "Team repository").getCanonicalFile();
            if (o.autos != null) o.autos = requireDir(o.autos, "OpMode folder").getCanonicalFile();
            if (o.map != null) {
                if (!o.map.isFile()) throw new UsageException("Field map not found: " + o.map, false);
                o.map = o.map.getCanonicalFile();
            }
        } catch (IOException e) {
            throw new UsageException(e.getMessage(), false);
        }
        if (o.report != null && !o.batch) throw new UsageException("--report needs --batch", false);
        if (System.getenv("FTCSIM_NO_BROWSER") != null || java.awt.GraphicsEnvironment.isHeadless()) o.openBrowser = false;
        return o;
    }

    private static File requireDir(File f, String what) throws UsageException {
        if (!f.isDirectory()) throw new UsageException(what + " not found: " + f, false);
        return f;
    }

    private static String value(String[] args, int i, String option) throws UsageException {
        if (i >= args.length) throw new UsageException("Missing value for " + option, false);
        return args[i];
    }

    private static int parsePort(String s) throws UsageException {
        try {
            int p = Integer.parseInt(s.trim());
            if (p < 0 || p > 65535) throw new NumberFormatException();
            return p;
        } catch (NumberFormatException e) {
            throw new UsageException("Invalid port " + s, false);
        }
    }

    private static void envOrProp(String prop, String env, java.util.function.Consumer<String> set) {
        String v = System.getProperty(prop);
        if (v == null || v.isEmpty()) v = System.getenv(env);
        if (v != null && !v.isEmpty()) set.accept(v);
    }

    public static String usage() {
        return "FTCSim - desktop simulator for FTC robot code (BIOBUZZ 2026-27 field and rules)\n" +
            "usage: ftcsim [<folder>] [--repo <FtcRobotController project>] [--autos <folder of OpModes>]\n" +
            "              [--map <field.stl|field.json>] [--port 8000] [--config robot.json]\n" +
            "              [--samples] [--prebuilt] [--no-browser] [--data <dir>]\n" +
            "              [--batch [--report <file.json|file.md>]]\n" +
            "  <folder>    a bare folder is an FtcRobotController project when it has settings.gradle or TeamCode,\n" +
            "              otherwise a folder of OpMode sources (same as --autos)\n" +
            "  --autos     compile and run the Autonomous/TeleOp OpModes found in a plain folder (no project needed)\n" +
            "  --map       use another field CAD export (.stl) or a compiled map (.json) instead of the built-in BIOBUZZ map\n" +
            "  --batch     headless: run every Autonomous OpMode once on the field, print the scored result, exit\n" +
            "              (exit code 1 when an OpMode crashes or breaks a rule)\n" +
            "  --samples   also load the FtcRobotController sample OpModes\n" +
            "  --prebuilt  use classes compiled by Android Studio (TeamCode/build/...) instead of compiling sources\n" +
            "  Environment/system properties: FTCSIM_REPO/ftcsim.repo, FTCSIM_AUTOS/ftcsim.autos, FTCSIM_MAP/ftcsim.map\n";
    }
}
