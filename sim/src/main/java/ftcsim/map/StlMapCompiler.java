package ftcsim.map;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Compiles the BIOBUZZ field STL (binary, inches) into a {@link FieldMap}. */
public final class StlMapCompiler {
    private static final double QUANT = 0.002;
    private static final double QUANT_OFFSET = 1000.0;
    private static final long MAX_TRIANGLES = 12_000_000L;
    private static final int READ_TRIS = 65536;

    private StlMapCompiler() {
    }

    interface TriVisitor {
        /** v holds the three vertices in the simulator frame (x, y, z each). */
        void visit(int tri, double[] v);
    }

    /** One welded connected component, bounds in the simulator frame. */
    static final class Comp {
        int tris;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;

        double sx() {
            return maxX - minX;
        }

        double sy() {
            return maxY - minY;
        }

        double sz() {
            return maxZ - minZ;
        }

        double cx() {
            return 0.5 * (minX + maxX);
        }

        double cy() {
            return 0.5 * (minY + maxY);
        }

        double cz() {
            return 0.5 * (minZ + maxZ);
        }
    }

    static final class Mesh {
        Path path;
        int nTri;
        int[] triComp;
        Comp[] comps;
    }

    static void readTriangles(Path path, int nTri, TriVisitor visitor) throws IOException {
        ByteBuffer buf = ByteBuffer.allocateDirect(50 * READ_TRIS).order(ByteOrder.LITTLE_ENDIAN);
        double[] s = new double[9];
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            ch.position(84);
            int done = 0;
            while (done < nTri) {
                int count = Math.min(READ_TRIS, nTri - done);
                buf.clear();
                buf.limit(count * 50);
                while (buf.hasRemaining()) {
                    if (ch.read(buf) < 0) {
                        throw new IOException("STL ended early at triangle " + (done + count - buf.remaining() / 50));
                    }
                }
                for (int i = 0; i < count; i++) {
                    int o = i * 50 + 12;
                    for (int k = 0; k < 3; k++) {
                        float x = buf.getFloat(o + 12 * k);
                        float y = buf.getFloat(o + 12 * k + 4);
                        float z = buf.getFloat(o + 12 * k + 8);
                        s[3 * k] = -y;
                        s[3 * k + 1] = x;
                        s[3 * k + 2] = z;
                    }
                    visitor.visit(done + i, s);
                }
                done += count;
            }
        }
    }

    private static int triangleCount(Path path) throws IOException {
        long size = java.nio.file.Files.size(path);
        if (size < 84) {
            throw new IllegalStateException("Not a binary STL (" + size + " bytes): " + path);
        }
        ByteBuffer head = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            while (head.hasRemaining()) {
                if (ch.read(head, 80 + head.position()) < 0) {
                    throw new IllegalStateException("Not a binary STL: truncated header in " + path);
                }
            }
        }
        long n = head.getInt(0) & 0xFFFFFFFFL;
        if (84 + 50 * n != size) {
            throw new IllegalStateException("Not a binary STL: header says " + n + " triangles but file size is "
                    + size + " bytes (expected " + (84 + 50 * n) + ")");
        }
        if (n == 0 || n > MAX_TRIANGLES) {
            throw new IllegalStateException("Unsupported STL triangle count " + n + " (expected 1.." + MAX_TRIANGLES + ")");
        }
        return (int) n;
    }

    private static long key(double x, double y, double z, int tri) {
        long qx = Math.round((x + QUANT_OFFSET) / QUANT);
        long qy = Math.round((y + QUANT_OFFSET) / QUANT);
        long qz = Math.round((z + QUANT_OFFSET) / QUANT);
        long lim = 1L << 20;
        if (qx < 0 || qy < 0 || qz < 0 || qx >= lim || qy >= lim || qz >= lim) {
            throw new IllegalStateException("STL vertex out of range at triangle " + tri + ": (" + x + ", " + y + ", " + z
                    + "); the file is not in inches at BIOBUZZ scale");
        }
        return (qx << 40) | (qy << 20) | qz;
    }

    private static int find(int[] parent, int a) {
        while (parent[a] != a) {
            parent[a] = parent[parent[a]];
            a = parent[a];
        }
        return a;
    }

    /** Welds vertices on a 0.002 in lattice and labels connected components. */
    static Mesh scan(Path path) throws IOException {
        int n = triangleCount(path);
        final long[] keys = new long[3 * n];
        readTriangles(path, n, (t, v) -> {
            for (int k = 0; k < 3; k++) {
                double x = v[3 * k], y = v[3 * k + 1], z = v[3 * k + 2];
                if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                    throw new IllegalStateException("STL has a non-finite vertex at triangle " + t);
                }
                keys[3 * t + k] = key(x, y, z, t);
            }
        });
        long[] sorted = keys.clone();
        Arrays.sort(sorted);
        int u = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || sorted[i] != sorted[i - 1]) {
                sorted[u++] = sorted[i];
            }
        }
        int[] parent = new int[u];
        for (int i = 0; i < u; i++) {
            parent[i] = i;
        }
        int[] ids = new int[3 * n];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = Arrays.binarySearch(sorted, 0, u, keys[i]);
        }
        sorted = null;
        for (int t = 0; t < n; t++) {
            int a = find(parent, ids[3 * t]);
            int b = find(parent, ids[3 * t + 1]);
            int c = find(parent, ids[3 * t + 2]);
            if (a != b) {
                parent[Math.max(a, b)] = Math.min(a, b);
            }
            a = find(parent, a);
            if (a != c) {
                parent[Math.max(a, c)] = Math.min(a, c);
            }
        }
        int[] rootToComp = new int[u];
        Arrays.fill(rootToComp, -1);
        int[] triComp = new int[n];
        int nComp = 0;
        for (int t = 0; t < n; t++) {
            int r = find(parent, ids[3 * t]);
            if (rootToComp[r] < 0) {
                rootToComp[r] = nComp++;
            }
            triComp[t] = rootToComp[r];
        }
        Comp[] comps = new Comp[nComp];
        for (int i = 0; i < nComp; i++) {
            comps[i] = new Comp();
        }
        readTriangles(path, n, (t, v) -> {
            Comp c = comps[triComp[t]];
            c.tris++;
            for (int k = 0; k < 3; k++) {
                double x = v[3 * k], y = v[3 * k + 1], z = v[3 * k + 2];
                c.minX = Math.min(c.minX, x);
                c.maxX = Math.max(c.maxX, x);
                c.minY = Math.min(c.minY, y);
                c.maxY = Math.max(c.maxY, y);
                c.minZ = Math.min(c.minZ, z);
                c.maxZ = Math.max(c.maxZ, z);
            }
        });
        Mesh m = new Mesh();
        m.path = path;
        m.nTri = n;
        m.triComp = triComp;
        m.comps = comps;
        return m;
    }

    // ---- recovery of the BIOBUZZ field model from the welded components ----

    private static final String[] WALLS = {"FAR", "BLUE", "AUDIENCE", "RED"};
    private static final String[] GROUPS = {"PRELOAD_RED", "PRELOAD_BLUE", "FLOWER_0", "FLOWER_1", "FLOWER_2",
            "FLOWER_3", "GARDEN_RED", "GARDEN_BLUE", "CELL_RED", "CELL_BLUE", "AREA_RED", "AREA_BLUE"};
    private static final double SOLID_TOP = 29.0;
    private static final double RASTER_CELL = 0.25;
    private static final double RASTER_HALF = 72.0;
    private static final int MAX_SOLIDS = 40;
    private static final int[] COARSEN = {1, 2, 3, 4, 6, 8, 12};

    private static final class Ball {
        boolean nectar;
        double x, y, z, r;
        String group = "";
    }

    public static FieldMap compile(File stl) throws IOException {
        FieldMap map = recover(scan(stl.toPath()));
        map.source = stl.getName();
        FieldMaps.validate(map);
        return map;
    }

    private static double r3(double v) {
        return Math.round(v * 1000.0) / 1000.0 + 0.0;
    }

    private static boolean within(Comp k, double lo, double hi) {
        return k.sx() >= lo && k.sx() <= hi && k.sy() >= lo && k.sy() <= hi && k.sz() >= lo && k.sz() <= hi;
    }

    private static FieldMap.Box box(String name, double x0, double y0, double z0, double x1, double y1, double z1) {
        return new FieldMap.Box(name, r3(x0), r3(y0), r3(z0), r3(x1), r3(y1), r3(z1));
    }

    private static FieldMap.Box union(String name, List<Comp> list, boolean clampFloor) {
        double x0 = Double.POSITIVE_INFINITY, y0 = x0, z0 = x0, x1 = Double.NEGATIVE_INFINITY, y1 = x1, z1 = x1;
        for (Comp k : list) {
            x0 = Math.min(x0, k.minX);
            y0 = Math.min(y0, k.minY);
            z0 = Math.min(z0, k.minZ);
            x1 = Math.max(x1, k.maxX);
            y1 = Math.max(y1, k.maxY);
            z1 = Math.max(z1, k.maxZ);
        }
        return box(name, x0, y0, clampFloor ? Math.max(0.0, z0) : z0, x1, y1, z1);
    }

    private static String wallOf(double x, double y) {
        if (Math.abs(x) > Math.abs(y)) {
            return x > 0 ? "AUDIENCE" : "FAR";
        }
        return y > 0 ? "BLUE" : "RED";
    }

    static FieldMap recover(Mesh mesh) throws IOException {
        Comp[] c = mesh.comps;
        int n = c.length;
        boolean[] used = new boolean[n];
        FieldMap map = new FieldMap();

        int floor = -1;
        for (int i = 0; i < n; i++) {
            if (c[i].sx() > 120 && c[i].sy() > 120 && c[i].sz() < 2) {
                if (floor >= 0) {
                    throw new IllegalStateException("More than one tile-floor component; this is not the BIOBUZZ field model");
                }
                floor = i;
            }
        }
        if (floor < 0) {
            throw new IllegalStateException("No tile floor (a flat component over 120 in square) found; this is not the BIOBUZZ field model");
        }
        Comp fl = c[floor];
        if (Math.abs(fl.cx()) > 1 || Math.abs(fl.cy()) > 1 || Math.abs(fl.sx() - fl.sy()) > 0.5) {
            throw new IllegalStateException("The tile floor is not a centred square; this is not the BIOBUZZ field model");
        }
        used[floor] = true;
        double floorSize = Math.min(fl.sx(), fl.sy());
        map.floorHalfIn = r3(0.5 * floorSize);
        map.tileIn = r3(floorSize / 6.0);

        // Perimeter walls: the full-height components that start at or below the tile surface (the opaque sheet
        // walls dip under the tiles, the transparent skins stop 0.09 in above them). The thin ones give the inner
        // face; the two bent-sheet panels that carry small bracket hardware are walls as well, not obstacles.
        double faceMin = Double.POSITIVE_INFINITY, faceMax = Double.NEGATIVE_INFINITY;
        int thin = 0;
        boolean[] side = new boolean[4];
        double wallTop = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            Comp k = c[i];
            if (used[i] || k.minZ > 0.5 || k.maxZ < 11 || Math.max(k.sx(), k.sy()) < 40) {
                continue;
            }
            used[i] = true;
            wallTop = Math.max(wallTop, k.maxZ);
            if (Math.min(k.sx(), k.sy()) >= 2) {
                continue;
            }
            double face;
            if (k.sx() < k.sy()) {
                face = Math.abs(k.cx()) - 0.5 * k.sx();
                side[k.cx() > 0 ? 0 : 1] = true;
            } else {
                face = Math.abs(k.cy()) - 0.5 * k.sy();
                side[k.cy() > 0 ? 2 : 3] = true;
            }
            faceMin = Math.min(faceMin, face);
            faceMax = Math.max(faceMax, face);
            thin++;
        }
        if (thin < 4 || !(side[0] && side[1] && side[2] && side[3]) || faceMax - faceMin > 0.2) {
            throw new IllegalStateException("Perimeter walls not recognised (" + thin + " thin wall components)");
        }
        map.wallInnerHalfIn = r3(faceMin);
        map.wallHeightIn = r3(wallTop);
        double cut = map.wallInnerHalfIn + 0.3;

        // Game pieces: spheres of the two ball sizes.
        List<Ball> balls = new ArrayList<>();
        int pollen = 0, nectar = 0;
        for (int i = 0; i < n; i++) {
            Comp k = c[i];
            if (used[i] || k.tris < 2000) {
                continue;
            }
            boolean p = within(k, 2.6, 3.0);
            boolean q = within(k, 3.4, 3.8);
            if (!p && !q) {
                continue;
            }
            used[i] = true;
            Ball b = new Ball();
            b.nectar = q;
            b.x = k.cx();
            b.y = k.cy();
            b.z = k.cz();
            b.r = r3(0.5 * Math.max(k.sx(), k.sy()));
            balls.add(b);
            if (q) {
                nectar++;
            } else {
                pollen++;
            }
        }
        if (pollen != 40 || nectar != 16) {
            throw new IllegalStateException("Expected 40 POLLEN and 16 NECTAR, found " + pollen + " and " + nectar
                    + "; this is not the BIOBUZZ field model");
        }
        List<Ball> stackBalls = new ArrayList<>();
        for (Ball b : balls) {
            if (b.nectar) {
                if (Math.abs(b.y) > 72) {
                    b.group = b.y < 0 ? "AREA_RED" : "AREA_BLUE";
                } else if (Math.abs(b.x) < 30 && Math.abs(b.y) < 30) {
                    b.group = b.y < 0 ? "CELL_RED" : "CELL_BLUE";
                } else {
                    throw new IllegalStateException("NECTAR at (" + r3(b.x) + ", " + r3(b.y) + ") is in no setup group");
                }
            } else if (Math.abs(b.y) > 72) {
                b.group = b.y < 0 ? "PRELOAD_RED" : "PRELOAD_BLUE";
            } else if (Math.abs(b.x) > 55 && Math.abs(b.y) > 55) {
                b.group = b.y < 0 ? "GARDEN_RED" : "GARDEN_BLUE";
            } else {
                stackBalls.add(b);
            }
        }
        List<List<Ball>> stacks = new ArrayList<>();
        for (Ball b : stackBalls) {
            List<Ball> home = null;
            for (List<Ball> s : stacks) {
                if (Math.hypot(s.get(0).x - b.x, s.get(0).y - b.y) < 1.0) {
                    home = s;
                    break;
                }
            }
            if (home == null) {
                home = new ArrayList<>();
                stacks.add(home);
            }
            home.add(b);
        }
        if (stacks.size() != 4) {
            throw new IllegalStateException("Expected 4 FLOWER stacks, found " + stacks.size());
        }

        // FLOWERs
        FieldMap.Flower[] flowers = new FieldMap.Flower[4];
        List<List<Comp>> assemblies = new ArrayList<>();
        int[] ringComp = new int[4];
        double[] axisX = new double[4], axisY = new double[4];
        for (List<Ball> s : stacks) {
            if (s.size() != 4) {
                throw new IllegalStateException("A FLOWER stack holds " + s.size() + " POLLEN, expected 4");
            }
            double ax = 0, ay = 0, bottom = Double.POSITIVE_INFINITY;
            for (Ball b : s) {
                ax += b.x / 4;
                ay += b.y / 4;
                bottom = Math.min(bottom, b.z - b.r);
            }
            String wall = wallOf(ax, ay);
            int idx = Arrays.asList(WALLS).indexOf(wall);
            if (flowers[idx] != null) {
                throw new IllegalStateException("Two FLOWER stacks on the " + wall + " wall");
            }
            for (Ball b : s) {
                b.group = "FLOWER_" + idx;
            }
            FieldMap.Flower f = new FieldMap.Flower();
            f.index = idx;
            f.wall = wall;
            f.x = r3(ax);
            f.y = r3(ay);
            f.floorZ = r3(bottom);
            flowers[idx] = f;
            axisX[idx] = ax;
            axisY[idx] = ay;
        }
        for (int fi = 0; fi < 4; fi++) {
            boolean xWall = flowers[fi].wall.equals("FAR") || flowers[fi].wall.equals("AUDIENCE");
            double axAlong = xWall ? axisY[fi] : axisX[fi];
            double axAcross = Math.abs(xWall ? axisX[fi] : axisY[fi]);
            double outward = Math.signum(xWall ? axisX[fi] : axisY[fi]);
            List<Comp> asm = new ArrayList<>();
            int ring = -1;
            double ringTop = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                Comp k = c[i];
                if (used[i]) {
                    continue;
                }
                double along = xWall ? k.cy() : k.cx();
                double across = outward * (xWall ? k.cx() : k.cy());
                if (Math.abs(along - axAlong) <= 3.5 && across <= cut && across >= axAcross - 4.5) {
                    used[i] = true;
                    asm.add(k);
                    boolean holds = k.minX <= axisX[fi] && k.maxX >= axisX[fi] && k.minY <= axisY[fi] && k.maxY >= axisY[fi];
                    if (holds && k.sz() < 2.0 && k.sx() > 3 && k.sy() > 3 && k.maxZ > ringTop) {
                        ringTop = k.maxZ;
                        ring = i;
                    }
                }
            }
            if (asm.isEmpty() || ring < 0 || ringTop < 15) {
                throw new IllegalStateException("FLOWER " + fi + " (" + flowers[fi].wall + ") has no top ring");
            }
            Comp rc = c[ring];
            Comp mid = null;
            for (Comp k : asm) {
                if (k.sz() < 0.6 && k.sx() * k.sy() >= 10 && k.maxZ > flowers[fi].floorZ + 8 && k.maxZ < rc.minZ - 3
                        && (mid == null || k.sx() * k.sy() > mid.sx() * mid.sy())) {
                    mid = k;
                }
            }
            if (mid == null) {
                throw new IllegalStateException("FLOWER " + fi + " (" + flowers[fi].wall + ") has no middle ring plate");
            }
            double top = Double.NEGATIVE_INFINITY;
            for (Comp k : asm) {
                top = Math.max(top, k.maxZ);
            }
            flowers[fi].topRingZ = r3(0.5 * (rc.minZ + rc.maxZ));
            flowers[fi].middleRingZ = r3(mid.maxZ);
            flowers[fi].topZ = r3(top);
            flowers[fi].footprint = union("FLOWER_" + fi, asm, true);
            ringComp[fi] = ring;
            assemblies.add(asm);
        }

        // HIVEs
        FieldMap.Hive[] hives = new FieldMap.Hive[2];
        int[] plateComp = new int[2];
        List<List<Comp>> frameComps = new ArrayList<>();
        List<List<Integer>> cellComps = new ArrayList<>();
        for (int h = 0; h < 2; h++) {
            double sg = h == 0 ? -1 : 1;
            String al = h == 0 ? "RED" : "BLUE";
            List<Integer> region = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Comp k = c[i];
                if (!used[i] && sg * k.cy() > 0 && Math.abs(k.cy()) < 30 && Math.abs(k.cx()) < 30) {
                    region.add(i);
                }
            }
            int pin = -1;
            int plate = -1;
            List<Comp> frame = new ArrayList<>();
            List<Integer> cells = new ArrayList<>();
            for (int i : region) {
                Comp k = c[i];
                if (k.sx() < 0.6 && k.sz() < 0.6 && k.sy() > 1.4 && k.sy() < 1.6 && Math.abs(k.cx()) < 0.5
                        && k.cz() > 35 && k.cz() < 52) {
                    if (pin >= 0) {
                        throw new IllegalStateException("Ambiguous pivot pin in the " + al + " HIVE");
                    }
                    pin = i;
                } else if (k.sy() < 3 && k.sx() > 30 && k.sz() > 15 && k.minZ > 25) {
                    if (plate >= 0) {
                        throw new IllegalStateException("Ambiguous rocker plate in the " + al + " HIVE");
                    }
                    plate = i;
                } else if (k.sy() >= 19 && k.sy() <= 22.5 && k.sx() >= 8 && k.sz() >= 10 && k.minZ > 25) {
                    cells.add(i);
                } else if (k.minZ < 0.7 && k.maxZ > 1.0) {
                    frame.add(k);
                }
            }
            if (pin < 0) {
                throw new IllegalStateException("No pivot pin found for the " + al + " HIVE; this is not the BIOBUZZ field model");
            }
            if (plate < 0) {
                throw new IllegalStateException("No rocker plate found for the " + al + " HIVE");
            }
            if (frame.isEmpty()) {
                throw new IllegalStateException("No frame found for the " + al + " HIVE");
            }
            FieldMap.Hive hv = new FieldMap.Hive();
            hv.alliance = al;
            hv.x = r3(c[pin].cx());
            hv.y = r3(c[pin].cy());
            hv.pivotZ = r3(c[pin].cz());
            hv.frame = union("HIVE_" + al + "_FRAME", frame, true);
            hives[h] = hv;
            plateComp[h] = plate;
            frameComps.add(frame);
            cellComps.add(cells);
            used[pin] = true;
            used[plate] = true;
            for (int i : cells) {
                used[i] = true;
            }
        }

        // Solid candidates: everything left that stands inside the walls and starts below the solid ceiling.
        boolean[] solid = new boolean[n];
        for (int i = 0; i < n; i++) {
            Comp k = c[i];
            if (!used[i] && k.minZ < SOLID_TOP && Math.abs(k.cx()) <= cut && Math.abs(k.cy()) <= cut) {
                solid[i] = true;
            }
        }
        // The perimeter panels are not solids: the Field clamps the robot at the wall's inner face.
        int[] plateOf = new int[n];
        int[] ringOf = new int[n];
        Arrays.fill(plateOf, -1);
        Arrays.fill(ringOf, -1);
        for (int h = 0; h < 2; h++) {
            plateOf[plateComp[h]] = h;
        }
        for (int f = 0; f < 4; f++) {
            ringOf[ringComp[f]] = f;
        }
        double[][] mom = new double[2][6];
        double[] ringMin2 = new double[4];
        Arrays.fill(ringMin2, Double.POSITIVE_INFINITY);
        Raster raster = new Raster();
        readTriangles(mesh.path, mesh.nTri, (t, v) -> {
            int ci = mesh.triComp[t];
            if (plateOf[ci] >= 0) {
                double ux = v[3] - v[0], uy = v[4] - v[1], uz = v[5] - v[2];
                double wx = v[6] - v[0], wy = v[7] - v[1], wz = v[8] - v[2];
                double cx = uy * wz - uz * wy, cy = uz * wx - ux * wz, cz = ux * wy - uy * wx;
                double area = 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
                double mx = (v[0] + v[3] + v[6]) / 3, mz = (v[2] + v[5] + v[8]) / 3;
                double[] m = mom[plateOf[ci]];
                m[0] += area;
                m[1] += area * mx;
                m[2] += area * mz;
                m[3] += area * mx * mx;
                m[4] += area * mx * mz;
                m[5] += area * mz * mz;
            } else if (ringOf[ci] >= 0) {
                int f = ringOf[ci];
                for (int q = 0; q < 3; q++) {
                    double dx = v[3 * q] - axisX[f], dy = v[3 * q + 1] - axisY[f];
                    ringMin2[f] = Math.min(ringMin2[f], dx * dx + dy * dy);
                }
            } else if (solid[ci]) {
                raster.add(v);
            }
        });

        double[] tilt = new double[2];
        for (int h = 0; h < 2; h++) {
            double[] m = mom[h];
            double mx = m[1] / m[0], mz = m[2] / m[0];
            double sxx = m[3] / m[0] - mx * mx, sxz = m[4] / m[0] - mx * mz, szz = m[5] / m[0] - mz * mz;
            tilt[h] = Math.abs(Math.toDegrees(0.5 * Math.atan2(2 * sxz, sxx - szz)));
            hives[h].tiltDeg = r3(tilt[h]);
        }
        for (int f = 0; f < 4; f++) {
            flowers[f].openingR = r3(Math.sqrt(ringMin2[f]));
        }

        for (int h = 0; h < 2; h++) {
            FieldMap.Hive hv = hives[h];
            List<Integer> cells = cellComps.get(h);
            List<Comp> aud = new ArrayList<>();
            List<Comp> far = new ArrayList<>();
            for (int i : cells) {
                (c[i].cx() > 0 ? aud : far).add(c[i]);
            }
            if (aud.size() < 2 || far.size() < 2) {
                throw new IllegalStateException("The " + hv.alliance + " HIVE CELLs were not recognised ("
                        + aud.size() + " audience-side and " + far.size() + " far-side parts)");
            }
            FieldMap.Box audBox = union("HIVE_" + hv.alliance + "_AUDIENCE_CELL", aud, false);
            FieldMap.Box farBox = union("HIVE_" + hv.alliance + "_FAR_CELL", far, false);
            if (audBox.centerZ() == farBox.centerZ()) {
                throw new IllegalStateException("The " + hv.alliance + " HIVE is level; no up CELL");
            }
            boolean audUp = audBox.centerZ() > farBox.centerZ();
            hv.halfWidthIn = r3(0.5 * (audBox.maxY - audBox.minY));
            double cos = Math.cos(Math.toRadians(tilt[h])), sin = Math.sin(Math.toRadians(tilt[h]));
            hv.cells.add(cell("AUDIENCE", audUp, audBox, 1, cos, sin));
            hv.cells.add(cell("FAR", !audUp, farBox, -1, cos, sin));
        }

        map.name = "BIOBUZZ";
        for (FieldMap.Flower f : flowers) {
            map.flowers.add(f);
        }
        map.hives.add(hives[0]);
        map.hives.add(hives[1]);
        for (Ball b : balls) {
            FieldMap.Piece p = new FieldMap.Piece();
            p.type = b.nectar ? "NECTAR" : "POLLEN";
            p.alliance = b.nectar ? (b.y < 0 ? "RED" : "BLUE") : "";
            p.group = b.group;
            p.x = r3(b.x);
            p.y = r3(b.y);
            p.z = r3(b.z);
            p.radius = b.r;
            map.pieces.add(p);
        }
        map.pieces.sort(Comparator.<FieldMap.Piece>comparingInt(p -> Arrays.asList(GROUPS).indexOf(p.group))
                .thenComparingDouble(p -> p.x).thenComparingDouble(p -> p.y).thenComparingDouble(p -> p.z));
        map.solids.addAll(raster.solids(map.wallInnerHalfIn));
        return map;
    }

    private static FieldMap.Cell cell(String side, boolean up, FieldMap.Box shell, int sideSign, double cos, double sin) {
        FieldMap.Cell cell = new FieldMap.Cell();
        cell.side = side;
        cell.up = up;
        cell.shell = shell;
        cell.mouthX = r3(sideSign * cos);
        cell.mouthZ = r3(up ? sin : -sin);
        return cell;
    }

    /** Top-down occupancy of the low geometry, reduced to a handful of axis-aligned boxes. */
    private static final class Raster {
        final int w = (int) Math.round(2 * RASTER_HALF / RASTER_CELL);
        final boolean[] occ = new boolean[w * w];
        final float[] zlo = new float[w * w];
        final float[] zhi = new float[w * w];

        Raster() {
            Arrays.fill(zlo, Float.POSITIVE_INFINITY);
            Arrays.fill(zhi, Float.NEGATIVE_INFINITY);
        }

        private static double dist(double[] v, int a, int b) {
            return Math.sqrt((v[3 * a] - v[3 * b]) * (v[3 * a] - v[3 * b])
                    + (v[3 * a + 1] - v[3 * b + 1]) * (v[3 * a + 1] - v[3 * b + 1])
                    + (v[3 * a + 2] - v[3 * b + 2]) * (v[3 * a + 2] - v[3 * b + 2]));
        }

        void add(double[] v) {
            double lo = Math.min(v[2], Math.min(v[5], v[8]));
            double hi = Math.max(v[2], Math.max(v[5], v[8]));
            if (hi < 0.2 || lo > SOLID_TOP) {
                return;
            }
            double e = Math.max(dist(v, 0, 1), Math.max(dist(v, 1, 2), dist(v, 2, 0)));
            int n = (int) Math.min(2000, Math.max(1, Math.ceil(e / (0.5 * RASTER_CELL))));
            for (int i = 0; i <= n; i++) {
                for (int j = 0; j <= n - i; j++) {
                    double u = (double) i / n, s = (double) j / n, a = 1 - u - s;
                    double z = a * v[2] + u * v[5] + s * v[8];
                    if (z < 0.2 || z > SOLID_TOP) {
                        continue;
                    }
                    double x = a * v[0] + u * v[3] + s * v[6];
                    double y = a * v[1] + u * v[4] + s * v[7];
                    int ix = (int) Math.floor((x + RASTER_HALF) / RASTER_CELL);
                    int iy = (int) Math.floor((y + RASTER_HALF) / RASTER_CELL);
                    if (ix < 0 || iy < 0 || ix >= w || iy >= w) {
                        continue;
                    }
                    int k = iy * w + ix;
                    occ[k] = true;
                    zlo[k] = Math.min(zlo[k], (float) z);
                    zhi[k] = Math.max(zhi[k], (float) z);
                }
            }
        }

        /** Greedy rectangle cover of the occupancy after OR-ing it into k x k blocks: {x0, y0, x1, y1, zlo, zhi}. */
        List<double[]> rects(int k) {
            int b = (w + k - 1) / k;
            boolean[] bo = new boolean[b * b];
            float[] lo = new float[b * b];
            float[] hi = new float[b * b];
            Arrays.fill(lo, Float.POSITIVE_INFINITY);
            Arrays.fill(hi, Float.NEGATIVE_INFINITY);
            for (int iy = 0; iy < w; iy++) {
                for (int ix = 0; ix < w; ix++) {
                    int s = iy * w + ix;
                    if (occ[s]) {
                        int d = (iy / k) * b + ix / k;
                        bo[d] = true;
                        lo[d] = Math.min(lo[d], zlo[s]);
                        hi[d] = Math.max(hi[d], zhi[s]);
                    }
                }
            }
            boolean[] done = new boolean[b * b];
            List<double[]> out = new ArrayList<>();
            for (int by = 0; by < b; by++) {
                for (int bx = 0; bx < b; bx++) {
                    int idx = by * b + bx;
                    if (!bo[idx] || done[idx]) {
                        continue;
                    }
                    int bw = 1;
                    while (bx + bw < b && bo[idx + bw] && !done[idx + bw]) {
                        bw++;
                    }
                    int bh = 1;
                    grow:
                    while (by + bh < b) {
                        for (int q = 0; q < bw; q++) {
                            int j = (by + bh) * b + bx + q;
                            if (!bo[j] || done[j]) {
                                break grow;
                            }
                        }
                        bh++;
                    }
                    float zl = Float.POSITIVE_INFINITY, zh = Float.NEGATIVE_INFINITY;
                    for (int dy = 0; dy < bh; dy++) {
                        for (int dx = 0; dx < bw; dx++) {
                            int j = (by + dy) * b + bx + dx;
                            done[j] = true;
                            zl = Math.min(zl, lo[j]);
                            zh = Math.max(zh, hi[j]);
                        }
                    }
                    double step = k * RASTER_CELL;
                    out.add(new double[] {-RASTER_HALF + bx * step, -RASTER_HALF + by * step,
                            -RASTER_HALF + (bx + bw) * step, -RASTER_HALF + (by + bh) * step, zl, zh});
                }
            }
            return out;
        }

        /**
         * Boxes from the finest block size whose cover fits the box budget once footprints under 1 sq in are dropped.
         * Blocks that lie wholly inside the band along the perimeter walls (wall brackets, corner posts, panel joints,
         * all within about 1.5 in of the inner face) are left out: the Field already clamps the robot at the wall.
         */
        List<FieldMap.Box> solids(double wallInner) {
            double band = wallInner - 1.75;
            for (int k : COARSEN) {
                List<double[]> kept = new ArrayList<>();
                for (double[] r : rects(k)) {
                    boolean inWall = r[0] >= band || r[2] <= -band || r[1] >= band || r[3] <= -band;
                    if (!inWall && (r[2] - r[0]) * (r[3] - r[1]) >= 1.0) {
                        kept.add(r);
                    }
                }
                if (kept.size() > MAX_SOLIDS) {
                    continue;
                }
                kept.sort(Comparator.<double[]>comparingDouble(r -> r[1]).thenComparingDouble(r -> r[0]));
                List<FieldMap.Box> out = new ArrayList<>();
                int hr = 0, hb = 0, other = 0;
                for (double[] r : kept) {
                    double cx = 0.5 * (r[0] + r[2]), cy = 0.5 * (r[1] + r[3]);
                    String name;
                    if (Math.abs(cx) < 30 && Math.abs(cy) < 30) {
                        name = cy < 0 ? "HIVE_RED_FRAME_" + (++hr) : "HIVE_BLUE_FRAME_" + (++hb);
                    } else {
                        name = "FIELD_" + (++other);
                    }
                    out.add(box(name, r[0], r[1], Math.max(0.0, r[4]), r[2], r[3], r[5]));
                }
                return out;
            }
            throw new IllegalStateException("The low field geometry does not reduce to " + MAX_SOLIDS + " boxes");
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: StlMapCompiler <field.stl> <out.json>");
            System.exit(2);
        }
        long t0 = System.nanoTime();
        FieldMap m = compile(new File(args[0]));
        Files.write(Path.of(args[1]), FieldMaps.toJson(m).getBytes(StandardCharsets.UTF_8));
        System.out.printf(Locale.ROOT, "compiled %s in %.1f s: tile %.3f floorHalf %.3f wallInner %.3f wallH %.3f, "
                        + "%d solids, %d hives, %d flowers, %d pieces%n",
                m.source, (System.nanoTime() - t0) / 1e9, m.tileIn, m.floorHalfIn, m.wallInnerHalfIn, m.wallHeightIn,
                m.solids.size(), m.hives.size(), m.flowers.size(), m.pieces.size());
        for (FieldMap.Hive h : m.hives) {
            System.out.printf(Locale.ROOT, "  hive %s (%.3f, %.3f) pivotZ %.3f tilt %.3f halfWidth %.3f%n",
                    h.alliance, h.x, h.y, h.pivotZ, h.tiltDeg, h.halfWidthIn);
        }
        for (FieldMap.Flower f : m.flowers) {
            System.out.printf(Locale.ROOT, "  flower %d %s (%.3f, %.3f) openingR %.3f ring %.3f mid %.3f top %.3f floor %.3f%n",
                    f.index, f.wall, f.x, f.y, f.openingR, f.topRingZ, f.middleRingZ, f.topZ, f.floorZ);
        }
    }
}
