package ftcsim.teamcode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates the source roots of the team's OpModes. Three shapes are supported: a FtcRobotController-based
 * project ({@link #TeamRepo(File)}), a plain folder of OpMode sources ({@link #folder(File)}), or a project
 * with an extra folder of OpModes on top ({@link #of(File, File)}).
 */
public final class TeamRepo {
    /** The project directory, or the OpMode folder when there is no project. */
    public final File dir;
    /** Extra folder of OpMode sources, or null. */
    public final File folder;
    /** True when {@link #dir} is a FtcRobotController-style project (false: {@link #dir} is only a folder of sources). */
    public final boolean project;
    public final List<File> sourceRoots = new ArrayList<>();
    public final List<File> prebuiltClassDirs = new ArrayList<>();
    public final File samplesRoot;
    public boolean hasKotlin;
    public String sdkVersion = "unknown";

    public TeamRepo(File dir) { this(dir, null, true); }

    /** A plain folder of OpMode sources; there is no project, build file or module structure. */
    public static TeamRepo folder(File folder) { return new TeamRepo(folder, null, false); }

    /** A project (may be null) plus an extra folder of OpMode sources (may be null) compiled with it. */
    public static TeamRepo of(File project, File folder) {
        if (project == null) return folder(folder);
        return new TeamRepo(project, folder, true);
    }

    /** Whether a directory looks like a FtcRobotController project rather than a loose folder of sources. */
    public static boolean isProject(File dir) {
        return new File(dir, "settings.gradle").isFile() || new File(dir, "settings.gradle.kts").isFile()
            || new File(dir, "TeamCode/src").isDirectory();
    }

    private TeamRepo(File dir, File folder, boolean project) {
        this.dir = dir;
        this.project = project;
        this.folder = project ? folder : null;
        if (project) {
            List<String> modules = readModules();
            for (String m : modules) {
                if (m.equalsIgnoreCase("FtcRobotController")) continue;
                File java = new File(dir, m + "/src/main/java");
                File kotlin = new File(dir, m + "/src/main/kotlin");
                if (java.isDirectory()) sourceRoots.add(java);
                if (kotlin.isDirectory()) sourceRoots.add(kotlin);
                File javac = new File(dir, m + "/build/intermediates/javac/debug/classes");
                File kt = new File(dir, m + "/build/tmp/kotlin-classes/debug");
                if (javac.isDirectory()) prebuiltClassDirs.add(javac);
                if (kt.isDirectory()) prebuiltClassDirs.add(kt);
            }
            if (sourceRoots.isEmpty()) {
                File tc = new File(dir, "TeamCode/src/main/java");
                if (tc.isDirectory()) sourceRoots.add(tc);
            }
            samplesRoot = new File(dir, "FtcRobotController/src/main/java/org/firstinspires/ftc/robotcontroller/external/samples");
            if (folder != null) sourceRoots.add(folder);
            readSdkVersion();
        } else {
            sourceRoots.add(dir);
            samplesRoot = new File(dir, ".ftcsim-no-samples");
        }
        hasKotlin = false;
        for (File root : sourceRoots) if (containsExtension(root, ".kt")) hasKotlin = true;
    }

    private List<String> readModules() {
        List<String> modules = new ArrayList<>();
        for (String name : new String[] { "settings.gradle", "settings.gradle.kts" }) {
            File f = new File(dir, name);
            if (!f.exists()) continue;
            try {
                String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                Matcher m = Pattern.compile("include\\s*\\(?\\s*['\"]:?([A-Za-z0-9_\\-]+)['\"]").matcher(text);
                while (m.find()) modules.add(m.group(1));
            } catch (IOException ignored) {}
        }
        if (modules.isEmpty()) { modules.add("TeamCode"); modules.add("FtcRobotController"); }
        return modules;
    }

    private void readSdkVersion() {
        for (String name : new String[] { "build.dependencies.gradle", "TeamCode/build.gradle", "build.gradle" }) {
            File f = new File(dir, name);
            if (!f.exists()) continue;
            try {
                Matcher m = Pattern.compile("org\\.firstinspires\\.ftc:RobotCore:([0-9A-Za-z.\\-]+)").matcher(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                if (m.find()) { sdkVersion = m.group(1); return; }
            } catch (IOException ignored) {}
        }
    }

    private static boolean containsExtension(File root, String ext) {
        File[] children = root.listFiles();
        if (children == null) return false;
        for (File f : children) {
            if (f.isDirectory()) { if (containsExtension(f, ext)) return true; }
            else if (f.getName().endsWith(ext)) return true;
        }
        return false;
    }

    public static List<File> collect(File root, String ext) {
        List<File> out = new ArrayList<>();
        collect(root, ext, out);
        return out;
    }
    private static void collect(File root, String ext, List<File> out) {
        File[] children = root.listFiles();
        if (children == null) return;
        java.util.Arrays.sort(children);
        for (File f : children) {
            if (f.isDirectory()) collect(f, ext, out);
            else if (f.getName().endsWith(ext)) out.add(f);
        }
    }
}
