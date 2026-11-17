package ftcsim.game;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.qualcomm.robotcore.eventloop.opmode.SimOpModeRunner;
import ftcsim.Simulator;
import ftcsim.log.StdoutCapture;
import ftcsim.runner.OpModeEntry;
import ftcsim.teamcode.TeamCode;
import ftcsim.teamcode.TeamCompiler;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Headless mode ({@code --batch}): runs every {@code @Autonomous} OpMode of the folder (or project) once on the BIOBUZZ field, in real
 * time (OpModes measure wall-clock time), through the 30 s AUTO period and the 8 s transition, and reports what the match referee would
 * score for it: LEAVE, AUTO PARK, HIVE TIPS and every violation. The exit code is 0 only when every OpMode ran without crashing and
 * without a foul.
 */
public final class BatchRunner {
    /** INIT and the settle time before START, and a little slack. */
    private static final long START_TIMEOUT_MS = 30_000;
    /** The AUTO period is 30 s of simulated time; give a stalled simulation some slack. */
    private static final long AUTO_TIMEOUT_MS = (long) (Rules.AUTO_S * 1000) + 30_000;
    private static final long TRANSITION_TIMEOUT_MS = (long) (Rules.TRANSITION_S * 1000) + 20_000;

    public static final int EXIT_OK = 0, EXIT_PROBLEMS = 1, EXIT_SETUP = 2, EXIT_NONE = 3;

    private BatchRunner() {}

    private static final class Result {
        String name;
        String alliance = "";
        boolean crashed;
        String error = "";
        double seconds;
        Map<String, Object> score = new LinkedHashMap<>();
        List<Map<String, Object>> violations = new ArrayList<>();
        List<Map<String, Object>> events = new ArrayList<>();

        int points(String key) {
            Object v = score.get(key);
            return v instanceof Number ? ((Number) v).intValue() : 0;
        }

        /** Anything that costs the alliance points or cards; VERBAL WARNINGs do not count. */
        int fouls() {
            int n = 0;
            for (Map<String, Object> v : violations) if (!"WARNING".equals(v.get("level"))) n++;
            return n;
        }

        String status() { return crashed ? "CRASHED" : fouls() > 0 ? "FOULS" : "OK"; }
    }

    /** @return the process exit code */
    public static int run(Simulator.Options o) {
        o.batch = true;
        o.openBrowser = false;
        PrintStream out = StdoutCapture.originalOut();
        Simulator sim = new Simulator(o);
        try {
            sim.start();
        } catch (IOException | RuntimeException e) {
            out.println("FTCSim batch: cannot start: " + e);
            return EXIT_SETUP;
        }
        TeamCode teamCode = sim.teamCode();
        if (teamCode == null) {
            out.println("FTCSim batch: no OpModes to run. Pass a folder of OpModes (--autos <folder>) or a project (--repo <path>).");
            return EXIT_SETUP;
        }
        TeamCode.Build build = teamCode.current();
        if (build == null || (build.compileResult != null && !build.compileResult.success)) {
            out.println("FTCSim batch: the OpModes do not compile:");
            if (build != null && build.compileResult != null) {
                for (TeamCompiler.Diagnostic d : build.compileResult.diagnostics) if ("ERROR".equals(d.kind)) out.println("  " + d);
            }
            return EXIT_SETUP;
        }
        List<OpModeEntry> autos = new ArrayList<>();
        for (OpModeEntry e : build.opModes) if (e.flavor == OpModeEntry.Flavor.AUTONOMOUS && !e.disabled) autos.add(e);
        if (autos.isEmpty()) {
            out.println("FTCSim batch: no @Autonomous OpModes found (" + build.opModes.size() + " OpMode(s) compiled).");
            return EXIT_NONE;
        }
        String source = o.autos != null ? o.autos.getPath() : o.repo != null ? o.repo.getPath() : "?";
        out.println("FTCSim batch: " + autos.size() + " autonomous OpMode(s) from " + source + " on the BIOBUZZ field"
            + (o.map != null ? " (map " + o.map.getName() + ")" : ""));
        List<Result> results = new ArrayList<>();
        int i = 0;
        for (OpModeEntry e : autos) {
            i++;
            Result r = runOne(sim, e);
            results.add(r);
            out.println(String.format("  [%d/%d] %-34s %-8s AUTO %2d pts (LEAVE %d, PARK %d, HIVE TIPS %d)  fouls %d  penalties %d  %.0f s%s",
                i, autos.size(), r.name, r.status(), r.points("auto"), r.points("leave"), r.points("park"), r.points("tips"),
                r.fouls(), r.points("penalty"), r.seconds, r.crashed ? "  " + firstLine(r.error) : ""));
            for (Map<String, Object> v : r.violations) {
                out.println(String.format("        %5.1f s  %s %s against %s: %s", ((Number) v.get("t")).doubleValue(), v.get("rule"), v.get("level"), v.get("against"), v.get("note")));
            }
        }
        sim.match().abort();
        boolean problems = false;
        for (Result r : results) problems |= r.crashed || r.fouls() > 0;
        if (o.report != null) {
            try {
                writeReport(o.report, source, results);
                out.println("Report written to " + o.report.getAbsolutePath());
            } catch (IOException e) {
                out.println("Cannot write the report: " + e);
                problems = true;
            }
        }
        out.println(problems ? "FTCSim batch: finished with problems." : "FTCSim batch: all OpModes ran cleanly.");
        return problems ? EXIT_PROBLEMS : EXIT_OK;
    }

    private static Result runOne(Simulator sim, OpModeEntry entry) {
        Result r = new Result();
        r.name = entry.name;
        BiobuzzGame game = sim.game();
        MatchControl match = sim.match();
        SimOpModeRunner runner = sim.runner();
        long t0 = System.currentTimeMillis();
        long before = match.runs();
        match.run(entry, null);
        boolean begun = waitFor(() -> match.runs() > before, START_TIMEOUT_MS);
        boolean started = begun && waitFor(() -> game.phase() != BiobuzzGame.Phase.PRE || runner.state() == SimOpModeRunner.State.ESTOP, START_TIMEOUT_MS);
        if (started && game.phase() == BiobuzzGame.Phase.AUTO) {
            waitFor(() -> game.phase() != BiobuzzGame.Phase.AUTO || runner.state() == SimOpModeRunner.State.ESTOP, AUTO_TIMEOUT_MS);
        }
        if (runner.state() == SimOpModeRunner.State.ESTOP) {
            r.crashed = true;
            r.error = runner.error() == null ? "emergency stop" : runner.error();
        } else if (!started || game.phase() == BiobuzzGame.Phase.PRE) {
            r.crashed = true;
            r.error = runner.error() != null ? runner.error() : "the OpMode did not start";
        } else if (game.phase() == BiobuzzGame.Phase.AUTO) {
            r.crashed = true;
            r.error = String.format("the AUTO period did not end (sim time %.1f s, %.0f steps/s, paused=%b)",
                    sim.world().simTime(), sim.world().stepRate(), sim.world().isPaused());
        } else {
            // the transition: elements still in the air count, the robot has to stand still (G403)
            waitFor(() -> game.phase() != BiobuzzGame.Phase.TRANSITION, TRANSITION_TIMEOUT_MS);
        }
        Alliance a = game.alliance();
        r.alliance = a == null ? "" : a.name();
        @SuppressWarnings("unchecked")
        Map<String, Object> side = a == null ? null : (Map<String, Object>) game.scoreboard().get(a.name().toLowerCase());
        if (side != null) r.score = side;
        r.violations = game.violations();
        r.events = game.eventLog();
        r.seconds = (System.currentTimeMillis() - t0) / 1000.0;
        return r;
    }

    private static boolean waitFor(BooleanSupplier condition, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) return true;
            try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return condition.getAsBoolean();
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int n = s.indexOf('\n');
        return n < 0 ? s : s.substring(0, n);
    }

    // ------------------------------------------------------------------ report
    private static void writeReport(File file, String source, List<Result> results) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) parent.mkdirs();
        String text = file.getName().toLowerCase().endsWith(".md") ? markdown(source, results) : json(source, results);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String json(String source, List<Result> results) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("field", "BIOBUZZ");
        root.put("source", source);
        root.put("date", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Result r : results) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("opmode", r.name);
            m.put("status", r.status());
            m.put("alliance", r.alliance);
            m.put("error", r.error);
            m.put("seconds", Math.round(r.seconds * 10) / 10.0);
            m.put("score", r.score);
            m.put("violations", r.violations);
            m.put("events", r.events);
            list.add(m);
        }
        root.put("results", list);
        Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        return gson.toJson(root) + "\n";
    }

    private static String markdown(String source, List<Result> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# FTCSim batch report - BIOBUZZ\n\n");
        sb.append("Source: `").append(source).append("`  \nDate: ").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n\n");
        sb.append("| OpMode | Alliance | Result | AUTO pts | LEAVE | PARK | HIVE TIPS | Fouls | Penalty pts |\n");
        sb.append("|---|---|---|---:|---:|---:|---:|---:|---:|\n");
        for (Result r : results) {
            sb.append("| ").append(r.name).append(" | ").append(r.alliance).append(" | ").append(r.status()).append(" | ").append(r.points("auto"))
              .append(" | ").append(r.points("leave")).append(" | ").append(r.points("park")).append(" | ").append(r.points("tips"))
              .append(" | ").append(r.fouls()).append(" | ").append(r.points("penalty")).append(" |\n");
        }
        for (Result r : results) {
            if (r.violations.isEmpty() && !r.crashed) continue;
            sb.append("\n## ").append(r.name).append("\n\n");
            if (r.crashed) sb.append("Crashed: ").append(firstLine(r.error)).append("\n\n");
            for (Map<String, Object> v : r.violations) {
                sb.append("- ").append(String.format("%.1f s", ((Number) v.get("t")).doubleValue())).append(" ").append(v.get("rule")).append(" ")
                  .append(v.get("level")).append(" against ").append(v.get("against")).append(": ").append(v.get("note")).append("\n");
            }
        }
        return sb.toString();
    }
}
