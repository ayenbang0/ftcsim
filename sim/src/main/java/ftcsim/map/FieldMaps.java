package ftcsim.map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loading, caching, serialising and validating {@link FieldMap}s. */
public final class FieldMaps {
    /** The map compiled from the BIOBUZZ CAD model, shipped as a resource so no STL is needed at run time. */
    public static final String BAKED_RESOURCE = "/map/biobuzz.json";
    /** Upper bound on FieldMap.solids, so collision stays cheap. */
    public static final int MAX_SOLIDS = 40;

    private static final String[] WALLS = {"FAR", "BLUE", "AUDIENCE", "RED"};
    private static final String[] GROUPS = {"PRELOAD_RED", "PRELOAD_BLUE", "FLOWER_0", "FLOWER_1", "FLOWER_2",
            "FLOWER_3", "GARDEN_RED", "GARDEN_BLUE", "CELL_RED", "CELL_BLUE", "AREA_RED", "AREA_BLUE"};
    private static final int[] GROUP_SIZE = {8, 8, 4, 4, 4, 4, 4, 4, 3, 3, 5, 5};

    /** Doubles are written to three decimals (0.001 in) which keeps the baked file small and diff-stable. */
    private static final TypeAdapter<Double> ROUNDED = new TypeAdapter<>() {
        @Override
        public void write(JsonWriter out, Double v) throws IOException {
            if (v == null) {
                out.nullValue();
            } else {
                out.value(Math.round(v * 1000.0) / 1000.0 + 0.0);
            }
        }

        @Override
        public Double read(JsonReader in) throws IOException {
            return in.nextDouble();
        }
    };

    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(double.class, ROUNDED)
            .registerTypeAdapter(Double.class, ROUNDED)
            .create();

    private FieldMaps() {
    }

    public static String toJson(FieldMap map) {
        return GSON.toJson(map);
    }

    /** The map as sent to the web UI: everything except the scattered game pieces (those travel in the game state). */
    public static com.google.gson.JsonElement renderTree(FieldMap map) {
        com.google.gson.JsonObject tree = GSON.toJsonTree(map).getAsJsonObject();
        tree.remove("pieces");
        return tree;
    }

    /** Parses and validates; throws IllegalArgumentException when the text is not a usable BIOBUZZ map. */
    public static FieldMap fromJson(String json) {
        FieldMap map;
        try {
            map = GSON.fromJson(json, FieldMap.class);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Field map is not valid JSON: " + e.getMessage(), e);
        }
        if (map == null) {
            throw new IllegalArgumentException("Field map is empty");
        }
        validate(map);
        return map;
    }

    /** The map shipped inside the application. */
    public static FieldMap baked() {
        try (InputStream in = FieldMaps.class.getResourceAsStream(BAKED_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + BAKED_RESOURCE);
            }
            return fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + BAKED_RESOURCE + ": " + e.getMessage(), e);
        }
    }

    /**
     * Loads a map from a compiled {@code .json} file or compiles it from a CAD {@code .stl}. STL results are
     * cached in {@code cacheDir} keyed on the file name, size and modification time, so the multi-second
     * compile happens once per CAD file; a null cacheDir disables caching.
     */
    public static FieldMap load(File file, File cacheDir) throws IOException {
        String name = file.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        }
        if (!lower.endsWith(".stl")) {
            throw new IllegalArgumentException("Unsupported field file (expected .stl or .json): " + name);
        }
        if (!file.isFile()) {
            throw new IOException("No such file: " + file);
        }
        Path cache = null;
        if (cacheDir != null) {
            String base = name.substring(0, name.length() - 4).replaceAll("[^A-Za-z0-9._-]", "_");
            cache = cacheDir.toPath().resolve(base + "-" + file.length() + "-" + file.lastModified() + "-v"
                    + FieldMap.FORMAT + ".json");
            if (Files.isRegularFile(cache)) {
                try {
                    return fromJson(Files.readString(cache, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException | IOException stale) {
                    // fall through and rebuild a damaged cache entry
                }
            }
        }
        FieldMap map = StlMapCompiler.compile(file);
        if (cache != null) {
            Files.createDirectories(cache.getParent());
            Path tmp = Files.createTempFile(cache.getParent(), "map-", ".tmp");
            try {
                Files.writeString(tmp, toJson(map), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, cache, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, cache, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        return map;
    }

    /** Throws IllegalArgumentException describing the first structural problem found. */
    public static void validate(FieldMap m) {
        require(m.format == FieldMap.FORMAT, "Unsupported field map format " + m.format + " (expected " + FieldMap.FORMAT + ")");
        require("BIOBUZZ".equals(m.name), "Field map is for '" + m.name + "', not BIOBUZZ");
        finite("tileIn", m.tileIn);
        finite("floorHalfIn", m.floorHalfIn);
        finite("wallInnerHalfIn", m.wallInnerHalfIn);
        finite("wallHeightIn", m.wallHeightIn);
        require(m.tileIn > 0 && m.floorHalfIn > 0 && m.wallInnerHalfIn > 0 && m.wallHeightIn > 0,
                "Field dimensions must be positive");
        require(m.solids != null && m.hives != null && m.flowers != null && m.pieces != null, "Field map lists are missing");
        require(m.solids.size() <= MAX_SOLIDS, "Too many solids: " + m.solids.size() + " (limit " + MAX_SOLIDS + ")");
        for (FieldMap.Box b : m.solids) {
            box("solid", b);
        }

        require(m.hives.size() == 2, "Expected 2 hives, found " + m.hives.size());
        for (int i = 0; i < 2; i++) {
            FieldMap.Hive h = m.hives.get(i);
            String al = i == 0 ? "RED" : "BLUE";
            require(al.equals(h.alliance), "Hive " + i + " must be " + al + ", is '" + h.alliance + "'");
            finite("hive " + al, h.x, h.y, h.pivotZ, h.tiltDeg, h.halfWidthIn);
            require(i == 0 ? h.y < 0 : h.y > 0, al + " hive must sit on the " + (i == 0 ? "-Y" : "+Y") + " side");
            require(h.tiltDeg > 0 && h.tiltDeg < 90, al + " hive tilt out of range: " + h.tiltDeg);
            require(h.halfWidthIn > 0, al + " hive half width must be positive");
            box(al + " hive frame", h.frame);
            require(h.cells != null && h.cells.size() == 2, al + " hive needs exactly 2 cells");
            FieldMap.Cell a = h.cells.get(0);
            FieldMap.Cell f = h.cells.get(1);
            require("AUDIENCE".equals(a.side) && "FAR".equals(f.side), al + " hive cells must be ordered AUDIENCE, FAR");
            require(a.up != f.up, al + " hive must have exactly one up cell");
            for (FieldMap.Cell c : h.cells) {
                finite(al + " " + c.side + " cell", c.mouthX, c.mouthZ);
                box(al + " " + c.side + " cell", c.shell);
                require(Math.abs(Math.hypot(c.mouthX, c.mouthZ) - 1.0) < 0.01,
                        al + " " + c.side + " cell mouth is not a unit vector");
                require(c.up ? c.mouthZ > 0 : c.mouthZ < 0, al + " " + c.side + " cell mouth must point " + (c.up ? "up" : "down"));
                require("AUDIENCE".equals(c.side) ? c.mouthX > 0 : c.mouthX < 0,
                        al + " " + c.side + " cell mouth must point away from the pivot");
            }
        }

        require(m.flowers.size() == 4, "Expected 4 flowers, found " + m.flowers.size());
        for (int i = 0; i < 4; i++) {
            FieldMap.Flower f = m.flowers.get(i);
            require(f.index == i && WALLS[i].equals(f.wall), "Flower " + i + " must be on the " + WALLS[i] + " wall");
            finite("flower " + i, f.x, f.y, f.openingR, f.topRingZ, f.middleRingZ, f.topZ, f.floorZ);
            require(f.openingR > 0, "Flower " + i + " opening radius must be positive");
            require(f.floorZ < f.middleRingZ && f.middleRingZ < f.topRingZ && f.topRingZ < f.topZ,
                    "Flower " + i + " heights must increase floor < middle ring < top ring < top");
            box("flower " + i + " footprint", f.footprint);
        }

        Map<String, Integer> counts = new HashMap<>();
        for (FieldMap.Piece p : m.pieces) {
            finite("piece", p.x, p.y, p.z, p.radius);
            require(p.radius > 0, "Piece radius must be positive");
            require("POLLEN".equals(p.type) || "NECTAR".equals(p.type), "Unknown piece type '" + p.type + "'");
            require("NECTAR".equals(p.type) ? ("RED".equals(p.alliance) || "BLUE".equals(p.alliance)) : p.alliance.isEmpty(),
                    p.type + " piece has alliance '" + p.alliance + "'");
            counts.merge(p.group, 1, Integer::sum);
        }
        require(m.pieces.size() == 56, "Expected 56 pieces, found " + m.pieces.size());
        for (int i = 0; i < GROUPS.length; i++) {
            int have = counts.getOrDefault(GROUPS[i], 0);
            require(have == GROUP_SIZE[i], "Group " + GROUPS[i] + " must hold " + GROUP_SIZE[i] + " pieces, holds " + have);
        }
    }

    private static void box(String what, FieldMap.Box b) {
        require(b != null, what + " box is missing");
        finite(what, b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ);
        require(b.minX <= b.maxX && b.minY <= b.maxY && b.minZ <= b.maxZ, what + " box is inverted");
    }

    private static void finite(String what, double... values) {
        for (double v : values) {
            require(Double.isFinite(v), what + " has a non-finite number");
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new IllegalArgumentException(message);
        }
    }
}
