package ftcsim.desktop;

import ftcsim.Main;
import ftcsim.Simulator;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileSystemView;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.awt.GraphicsEnvironment;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Entry point of the packaged Windows application ({@code FTCSim.exe} / {@code FTCSim-cli.exe}).
 *
 * <p>Starts the simulator server on a loopback port and shows the UI in a Chromium based browser opened in
 * <em>app mode</em> (Edge ships with Windows 10/11): a native-looking window without tabs or address bar. The
 * browser gets its own profile directory, so it is a separate process that lives exactly as long as the
 * window. When the window closes the simulator exits.
 *
 * <p>Extra options on top of {@link Main#usage()}: {@code --no-window} (server only), {@code --self-test},
 * {@code --console} (log to the console instead of the log file).
 *
 * <p>System properties / environment: {@code ftcsim.home} (set by the launcher to the app-image root),
 * {@code ftcsim.console=true} (console launcher), {@code FTCSIM_BROWSER} (path of the browser executable to
 * use), {@code FTCSIM_BROWSER_ARGS} (extra browser arguments, whitespace separated; diagnostics).
 */
public final class DesktopMain {
    private DesktopMain() {}

    private static final String TITLE = "FTCSim";
    /** The browser process is considered handed off to an existing instance when it exits sooner than this. */
    private static final long HANDOFF_MS = 3000;
    /** The simulator exits when the UI was connected once and then no client stayed for this long. */
    private static final long CLIENT_GONE_MS = 15000;
    /** Fallback (no tracked browser process): give up when the UI never connects within this time. */
    private static final long NEVER_CONNECTED_MS = 10 * 60_000L;

    private static boolean console;
    private static PrintStream logStream;
    private static Simulator sim;
    private static Process browser;
    private static File temporaryDir; // secondary instance data dir, deleted on exit
    private static FileChannel lockChannel; // keeps the primary-instance lock alive
    private static final AtomicBoolean stopping = new AtomicBoolean();
    private static volatile long neverConnectedLimitMs;

    public static void main(String[] argv) {
        List<String> args = new ArrayList<>(Arrays.asList(argv));
        boolean selfTest = args.remove("--self-test");
        boolean noWindow = args.remove("--no-window");
        boolean consoleFlag = args.remove("--console");
        console = consoleFlag || Boolean.getBoolean("ftcsim.console") || System.console() != null;

        File bundle = bundleHome();
        System.setProperty("ftcsim.home", bundle.getAbsolutePath());

        if (selfTest) { System.exit(selfTest()); return; }

        boolean help = args.contains("--help") || args.contains("-h");
        boolean batch = args.contains("--batch");
        if (help) {
            if (console) {
                try { Main.main(toArray(args)); } catch (Exception e) { System.err.println(e.getMessage()); System.exit(2); }
                return;
            }
            showText("FTCSim - command line options", Main.usage() + extraUsage());
            return;
        }

        try {
            File local = localAppData();
            if (batch) {
                // headless scoring run: same defaults for data/home as the desktop app, then the regular CLI
                runBatch(args, bundle, local);
                return;
            }
            start(args, bundle, local, noWindow);
        } catch (Main.UsageException e) {
            fatal(e.getMessage(), 2, false);
        } catch (Throwable t) {
            t.printStackTrace();
            fatal("FTCSim could not start:\n" + describe(t) + "\n\nDetails: " + new File(localAppData(), "ftcsim.log"), 1, true);
        }
    }

    // ------------------------------------------------------------------ batch
    private static void runBatch(List<String> args, File bundle, File local) throws Exception {
        if (!args.contains("--data")) { args.add("--data"); args.add(new File(local, "data").getAbsolutePath()); }
        File home = writableHome(bundle, local);
        System.setProperty("ftcsim.home", home.getAbsolutePath());
        boolean hasSource = args.contains("--repo") || args.contains("--autos") || args.contains("--folder")
            || System.getenv("FTCSIM_AUTOS") != null || System.getenv("FTCSIM_REPO") != null
            || args.stream().anyMatch(a -> !a.startsWith("-") && new File(a).isDirectory());
        if (!hasSource) {
            File autos = defaultAutos(bundle);
            if (autos != null) { args.add("--autos"); args.add(autos.getAbsolutePath()); }
        }
        Main.main(toArray(args));
    }

    // ------------------------------------------------------------------ desktop run
    private static void start(List<String> args, File bundle, File local, boolean noWindow) throws Exception {
        Simulator.Options o = Main.parse(toArray(args));
        o.desktop = true;
        o.openBrowser = false;

        // Data directory, instance lock, log file.
        boolean explicitData = o.dataDir != null;
        File data = explicitData ? o.dataDir.getAbsoluteFile() : new File(local, "data");
        if (!data.isDirectory() && !data.mkdirs()) throw new IOException("Cannot create the data folder " + data);
        boolean primary = acquireInstanceLock(data);
        File profile;
        if (primary) {
            profile = new File(data, "browser-profile");
        } else {
            // A second FTCSim is running: keep its data and browser profile separate and delete them on exit.
            temporaryDir = new File(data, "secondary-" + ProcessHandle.current().pid());
            if (!temporaryDir.isDirectory() && !temporaryDir.mkdirs()) throw new IOException("Cannot create " + temporaryDir);
            data = temporaryDir;
            profile = new File(temporaryDir, "browser-profile");
        }
        o.dataDir = data;
        if (!console) redirectOutput(local);
        log("FTCSim desktop starting: java " + System.getProperty("java.version") + ", bundle " + bundle + ", data " + data
            + (primary ? "" : " (secondary instance)"));

        o.home = writableHome(bundle, local);
        if (o.repo == null && o.autos == null) o.autos = defaultAutos(bundle);
        log("Home " + o.home + ", OpMode folder " + (o.repo != null ? o.repo + " (project)" : o.autos));

        // Port: the requested one (default 8000) when free, otherwise any free port.
        if (o.port != 0 && !portFree(o.port)) {
            int busy = o.port;
            o.port = 0;
            log("Port " + busy + " is in use; choosing a free port");
        }

        Main.install();
        sim = new Simulator(o);
        try {
            sim.start();
        } catch (IOException e) {
            throw new IOException("Could not start the FTCSim server on port " + o.port + ": " + e.getMessage(), e);
        }
        int port = sim.web().port();
        String url = "http://127.0.0.1:" + port + "/";
        log("FTCSim UI: " + url);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { if (sim != null) sim.web().stop(); } catch (Throwable ignored) {}
        }, "ftcsim-shutdown"));

        if (noWindow) {
            System.out.println("FTCSim server only (--no-window): " + url);
            System.out.flush();
        } else {
            openWindow(url, profile);
            Thread watcher = new Thread(DesktopMain::watchClients, "ftcsim-client-watcher");
            watcher.setDaemon(true);
            watcher.start();
        }
        new CountDownLatch(1).await(); // the process ends through shutdown()
    }

    // ------------------------------------------------------------------ window
    private static void openWindow(String url, File profile) {
        File exe = findBrowser();
        if (exe != null) {
            try {
                //noinspection ResultOfMethodCallIgnored
                profile.mkdirs();
                List<String> cmd = new ArrayList<>();
                cmd.add(exe.getAbsolutePath());
                cmd.add("--app=" + url);
                cmd.add("--user-data-dir=" + profile.getAbsolutePath());
                cmd.add("--window-size=1500,950");
                cmd.add("--no-first-run");
                cmd.add("--no-default-browser-check");
                cmd.add("--disable-features=Translate");
                cmd.add("--disable-background-mode");
                String extra = System.getenv("FTCSIM_BROWSER_ARGS");
                if (extra != null && !extra.isBlank()) cmd.addAll(Arrays.asList(extra.trim().split("\\s+")));
                log("Opening app window: " + cmd);
                long t0 = System.currentTimeMillis();
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                browser = pb.start();
                Process p = browser;
                Thread waiter = new Thread(() -> {
                    try { p.waitFor(); } catch (InterruptedException e) { return; }
                    long lived = System.currentTimeMillis() - t0;
                    if (lived < HANDOFF_MS) {
                        log("The browser process exited after " + lived + " ms (profile in use?); relying on the connection watcher");
                        neverConnectedLimitMs = NEVER_CONNECTED_MS;
                    } else {
                        shutdown("the application window was closed");
                    }
                }, "ftcsim-window-waiter");
                waiter.setDaemon(true);
                waiter.start();
                return;
            } catch (Exception e) {
                log("Could not start " + exe + ": " + e);
            }
        } else {
            log("No Edge/Chrome/Brave/Chromium found; using the default browser");
        }
        neverConnectedLimitMs = NEVER_CONNECTED_MS;
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Throwable t) {
            log("Desktop.browse failed: " + t);
        }
        showError("FTCSim is running, but no browser could be opened.\nOpen " + url + " in a web browser.");
    }

    /** Stops the simulator once the UI was connected and has been gone for CLIENT_GONE_MS. */
    private static void watchClients() {
        long start = System.currentTimeMillis();
        long zeroSince = -1;
        boolean seen = false;
        while (!stopping.get()) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            long now = System.currentTimeMillis();
            int open = 0;
            try { for (var c : sim.web().clients()) if (c.isOpen()) open++; } catch (RuntimeException ignored) {}
            if (open > 0) { seen = true; zeroSince = -1; continue; }
            if (seen) {
                if (zeroSince < 0) zeroSince = now;
                else if (now - zeroSince >= CLIENT_GONE_MS) { shutdown("the UI disconnected"); return; }
            } else if (neverConnectedLimitMs > 0 && now - start > neverConnectedLimitMs) {
                shutdown("the UI never connected");
                return;
            }
        }
    }

    static void shutdown(String why) {
        if (!stopping.compareAndSet(false, true)) return;
        log("Shutting down: " + why);
        try { if (sim != null) sim.web().stop(); } catch (Throwable ignored) {}
        if (temporaryDir != null) deleteTree(temporaryDir);
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) {}
        if (logStream != null) logStream.flush();
        Runtime.getRuntime().halt(0);
    }

    // ------------------------------------------------------------------ browser discovery
    private static File findBrowser() {
        String forced = firstNonEmpty(System.getProperty("ftcsim.browser"), System.getenv("FTCSIM_BROWSER"));
        if (forced != null) {
            File f = new File(forced);
            if (f.isFile()) return f;
            log("FTCSIM_BROWSER does not exist: " + forced);
        }
        String pf86 = System.getenv("ProgramFiles(x86)"), pf = System.getenv("ProgramFiles"), local = System.getenv("LOCALAPPDATA");
        List<File> candidates = new ArrayList<>();
        // Microsoft Edge: ships with Windows 10/11
        addCandidate(candidates, pf86, "Microsoft\\Edge\\Application\\msedge.exe");
        addCandidate(candidates, pf, "Microsoft\\Edge\\Application\\msedge.exe");
        addCandidate(candidates, registryApp("msedge.exe"));
        // Google Chrome
        addCandidate(candidates, pf, "Google\\Chrome\\Application\\chrome.exe");
        addCandidate(candidates, pf86, "Google\\Chrome\\Application\\chrome.exe");
        addCandidate(candidates, local, "Google\\Chrome\\Application\\chrome.exe");
        addCandidate(candidates, registryApp("chrome.exe"));
        // Brave, Chromium
        addCandidate(candidates, pf, "BraveSoftware\\Brave-Browser\\Application\\brave.exe");
        addCandidate(candidates, pf86, "BraveSoftware\\Brave-Browser\\Application\\brave.exe");
        addCandidate(candidates, local, "BraveSoftware\\Brave-Browser\\Application\\brave.exe");
        addCandidate(candidates, local, "Chromium\\Application\\chrome.exe");
        for (File f : candidates) if (f.isFile()) return f;
        return null;
    }

    private static void addCandidate(List<File> out, String base, String rel) {
        if (base != null && !base.isEmpty()) out.add(new File(base, rel));
    }

    private static void addCandidate(List<File> out, File f) {
        if (f != null) out.add(f);
    }

    /** Looks the executable up in the registry's App Paths (HKLM, then HKCU). */
    private static File registryApp(String exeName) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return null;
        for (String hive : new String[] {"HKLM", "HKCU"}) {
            try {
                Process p = new ProcessBuilder("reg", "query",
                    hive + "\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\" + exeName, "/ve")
                    .redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); continue; }
                Matcher m = Pattern.compile("REG_SZ\\s+(.+?)\\s*$", Pattern.MULTILINE).matcher(out);
                if (m.find()) {
                    File f = new File(m.group(1).replace("\"", ""));
                    if (f.isFile()) return f;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // ------------------------------------------------------------------ folders
    /** The app-image root (ftcsim.home set by the launcher), else derived from the location of the jar. */
    private static File bundleHome() {
        String p = System.getProperty("ftcsim.home");
        if (p != null && !p.isEmpty() && !p.equals(".") && p.indexOf('$') < 0) return new File(p).getAbsoluteFile();
        try {
            File jar = new File(DesktopMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File dir = jar.getParentFile();
            if (jar.isFile() && dir != null && "app".equalsIgnoreCase(dir.getName()) && dir.getParentFile() != null) return dir.getParentFile();
        } catch (Exception ignored) {}
        return new File(".").getAbsoluteFile();
    }

    private static File localAppData() {
        String l = System.getenv("LOCALAPPDATA");
        File base = l != null && !l.isEmpty() ? new File(l)
            : new File(System.getProperty("user.home", "."), "AppData" + File.separator + "Local");
        File dir = new File(base, "FTCSim");
        if (!dir.isDirectory() && !dir.mkdirs()) dir = new File(System.getProperty("java.io.tmpdir"), "FTCSim");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** The bundle folder when it is writable (config saves go to {@code <home>/configs}); else a seeded per-user copy. */
    private static File writableHome(File bundle, File local) {
        File configs = new File(bundle, "configs");
        if (isWritableDir(configs.isDirectory() ? configs : bundle)) return bundle;
        File home = new File(local, "home");
        try {
            copyTree(configs.toPath(), new File(home, "configs").toPath());
        } catch (IOException e) {
            log("Could not seed " + home + ": " + e);
        }
        return home;
    }

    private static boolean isWritableDir(File dir) {
        try {
            File t = File.createTempFile(".ftcsim-write", ".tmp", dir);
            return t.delete();
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** {@code <Documents>\FTCSim\autos}, seeded from the bundled samples on first use; never overwrites user files. */
    private static File defaultAutos(File bundle) {
        File docs;
        try {
            docs = FileSystemView.getFileSystemView().getDefaultDirectory();
        } catch (Throwable t) {
            docs = null;
        }
        if (docs == null || !docs.isDirectory()) docs = new File(System.getProperty("user.home", "."), "Documents");
        File autos = new File(docs, "FTCSim" + File.separator + "autos");
        try {
            Files.createDirectories(autos.toPath());
        } catch (IOException e) {
            log("Cannot create " + autos + ": " + e);
            return null;
        }
        File samples = new File(bundle, "samples" + File.separator + "autos");
        if (samples.isDirectory()) {
            try { copyTree(samples.toPath(), autos.toPath()); } catch (IOException e) { log("Could not seed " + autos + ": " + e); }
        }
        return autos;
    }

    /** Copies files that do not exist yet; existing files are left alone. */
    private static void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path src : (Iterable<Path>) walk::iterator) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) Files.createDirectories(dst);
                else if (!Files.exists(dst)) {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static boolean acquireInstanceLock(File data) {
        try {
            lockChannel = FileChannel.open(new File(data, ".ftcsim-instance.lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock l = lockChannel.tryLock();
            if (l != null) return true;
            lockChannel.close();
        } catch (IOException | RuntimeException ignored) {}
        lockChannel = null;
        return false;
    }

    private static boolean portFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ logging
    /** Sends System.out/err to %LOCALAPPDATA%\FTCSim\ftcsim.log (append; restarted when larger than 5 MB). */
    private static void redirectOutput(File local) {
        try {
            File f = new File(local, "ftcsim.log");
            boolean truncate = f.length() > 5L * 1024 * 1024;
            logStream = new PrintStream(new FileOutputStream(f, !truncate), true, StandardCharsets.UTF_8);
            System.setOut(logStream);
            System.setErr(logStream);
            logStream.println();
            logStream.println("==== FTCSim " + java.time.LocalDateTime.now() + " ====");
        } catch (IOException e) {
            logStream = null;
        }
    }

    private static void log(String msg) {
        PrintStream out = System.out;
        out.println("[desktop] " + msg);
        out.flush();
    }

    // ------------------------------------------------------------------ dialogs
    private static void fatal(String message, int exitCode, boolean log) {
        if (log) log(message.replace('\n', ' '));
        showError(message);
        if (logStream != null) logStream.flush();
        System.exit(exitCode);
    }

    private static void showError(String message) {
        if (console || GraphicsEnvironment.isHeadless()) {
            System.err.println(message);
            return;
        }
        try {
            onEdt(() -> JOptionPane.showMessageDialog(null, message, TITLE, JOptionPane.ERROR_MESSAGE));
        } catch (Throwable t) {
            System.err.println(message);
        }
    }

    private static void showText(String title, String text) {
        if (GraphicsEnvironment.isHeadless()) { System.out.println(text); return; }
        try {
            onEdt(() -> {
                JTextArea area = new JTextArea(text, 20, 90);
                area.setEditable(false);
                area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
                JOptionPane.showMessageDialog(null, new JScrollPane(area), title, JOptionPane.INFORMATION_MESSAGE);
            });
        } catch (Throwable t) {
            System.out.println(text);
        }
    }

    private static void onEdt(Runnable r) throws Exception {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeAndWait(r);
    }

    private static String extraUsage() {
        return "Desktop launcher options:\n"
            + "  --no-window  start the server only (no application window); keeps running until killed\n"
            + "  --self-test  check the bundled runtime (in-process compiler, UI resources) and exit\n"
            + "  --console    write the log to the console instead of %LOCALAPPDATA%\\FTCSim\\ftcsim.log\n";
    }

    // ------------------------------------------------------------------ self test
    private static int selfTest() {
        try {
            System.out.println("java.version=" + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            System.out.println("java.home=" + System.getProperty("java.home"));
            System.out.println("ftcsim.home=" + System.getProperty("ftcsim.home"));
            System.out.println("headless=" + GraphicsEnvironment.isHeadless());
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) return fail("no in-process Java compiler (the runtime lacks jdk.compiler)");
            Path tmp = Files.createTempDirectory("ftcsim-selftest");
            try {
                Path src = tmp.resolve("Hello.java");
                Files.writeString(src, "class Hello {}\n");
                Path out = Files.createDirectories(tmp.resolve("out"));
                String cp = System.getProperty("java.class.path");
                int rc = compile(compiler, out, cp, src);
                if (rc != 0 || !Files.isRegularFile(out.resolve("Hello.class"))) return fail("compiling class Hello failed (rc=" + rc + ")");
                System.out.println("compiled Hello.class in-process");
                // a source that needs the application classpath (java.class.path of the launcher)
                Path src2 = tmp.resolve("Uses.java");
                Files.writeString(src2, "class Uses { static String u() { return ftcsim.Main.usage(); } }\n");
                rc = compile(compiler, out, cp, src2);
                if (rc != 0 || !Files.isRegularFile(out.resolve("Uses.class"))) return fail("compiling against the application classpath failed (rc=" + rc + ")");
                System.out.println("compiled against java.class.path (" + cp.split(File.pathSeparator).length + " entries)");
            } finally {
                deleteTree(tmp.toFile());
            }
            try (InputStream in = DesktopMain.class.getResourceAsStream("/web/index.html")) {
                if (in == null) return fail("classpath resource /web/index.html is missing");
                System.out.println("/web/index.html: " + in.readAllBytes().length + " bytes");
            }
            System.out.println("SELFTEST OK");
            return 0;
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            return fail(describe(t));
        }
    }

    private static int compile(JavaCompiler compiler, Path outDir, String classpath, Path source) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = compiler.run(null, null, err, "-d", outDir.toString(), "-classpath", classpath, "-proc:none", source.toString());
        if (rc != 0) System.out.println(err.toString(StandardCharsets.UTF_8));
        return rc;
    }

    private static int fail(String why) {
        System.out.println("SELFTEST FAILED: " + why);
        return 1;
    }

    // ------------------------------------------------------------------ helpers
    private static String[] toArray(List<String> l) { return l.toArray(new String[0]); }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : b != null && !b.isEmpty() ? b : null;
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.toString() : m;
    }
}
