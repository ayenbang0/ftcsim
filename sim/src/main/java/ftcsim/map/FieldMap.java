package ftcsim.map;

import java.util.ArrayList;
import java.util.List;

/**
 * Game-field geometry for the BIOBUZZ arena, compiled from the CAD model of the field
 * ({@link StlMapCompiler}) or loaded from the compact JSON that compiler writes ({@link FieldMaps}).
 *
 * <p>Everything is in the simulator's field frame: inches, origin at the field centre, +X toward the
 * audience, +Y toward the blue ALLIANCE AREA wall (the red wall is at -Y), +Z up with Z = 0 on top of the
 * tiles. The CAD model is Z-up with the red/blue axis along its X axis, so a CAD point (cx, cy, cz) maps to
 * the field point (-cy, cx, cz).
 *
 * <p>The class is plain data so that Gson can read and write it without adapters.
 */
public final class FieldMap {
    public static final int FORMAT = 1;

    public int format = FORMAT;
    public String name = "BIOBUZZ";
    /** Provenance: the CAD file this was compiled from, or a note that it is a baked copy. */
    public String source = "";

    /** Pitch of the 6x6 floor tiles. */
    public double tileIn;
    /** Half size of the square tile floor. */
    public double floorHalfIn;
    /** |x| and |y| of the inner face of the perimeter walls. */
    public double wallInnerHalfIn;
    /** Height of the top of the perimeter walls above the tiles. */
    public double wallHeightIn;

    /** Robot-blocking static geometry between Z = 0 and Z = 29 in (HIVE frame legs, base rails, FLOWER bases). */
    public List<Box> solids = new ArrayList<>();
    public List<Hive> hives = new ArrayList<>();
    /** Index order matches {@code ftcsim.physics.Field}: far/red, blue/far, audience/blue, red/audience. */
    public List<Flower> flowers = new ArrayList<>();
    /** Every loose game piece at its CAD position (the as-built field setup). */
    public List<Piece> pieces = new ArrayList<>();

    public static final class Box {
        public String name = "";
        public double minX, minY, minZ, maxX, maxY, maxZ;

        public Box() {}

        public Box(String name, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.name = name;
            this.minX = minX; this.minY = minY; this.minZ = minZ;
            this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        }

        public double centerX() { return 0.5 * (minX + maxX); }
        public double centerY() { return 0.5 * (minY + maxY); }
        public double centerZ() { return 0.5 * (minZ + maxZ); }
    }

    /** One HIVE: a bi-stable rocker with a CELL at each end. The pivot axis runs parallel to the field Y axis. */
    public static final class Hive {
        /** RED (sits in the -Y half of the field) or BLUE (+Y half). */
        public String alliance = "";
        /** The pivot axis passes through (x, y, pivotZ) and runs along Y. */
        public double x, y, pivotZ;
        /** Angle of the rocker above/below level in the two stable states (the rocker swings twice this to tip). */
        public double tiltDeg;
        /** Half the length of a CELL along the pivot axis. */
        public double halfWidthIn;
        /** Overall frame extents. */
        public Box frame = new Box();
        /** Exactly two: index 0 = AUDIENCE side (+X of the pivot in the CAD state), index 1 = FAR side (-X). */
        public List<Cell> cells = new ArrayList<>();
    }

    public static final class Cell {
        /** AUDIENCE (+X of the pivot) or FAR (-X). */
        public String side = "";
        /** True when this CELL faces upward in the CAD model (the setup state of the field). */
        public boolean up;
        /** Bounding box of the CELL shell geometry in the CAD state. */
        public Box shell = new Box();
        /**
         * Unit vector in the field XZ plane pointing out of the CELL opening in the CAD state (the
         * direction a piece must travel against to enter).
         */
        public double mouthX, mouthZ;
    }

    /** One FLOWER: a tube on the perimeter wall with an entry ring on top. */
    public static final class Flower {
        public int index;
        /** FAR (-X wall), AUDIENCE (+X wall), RED (-Y wall) or BLUE (+Y wall). */
        public String wall = "";
        /** Vertical axis of the tube / centre of the top opening. */
        public double x, y;
        /** Radius of the top opening. */
        public double openingR;
        /** Z of the underside of the top ring, i.e. the top of the scoring volume. */
        public double topRingZ;
        /** Z of the top of the middle ring, i.e. the bottom of the scoring volume. */
        public double middleRingZ;
        /** Z of the top of the backstop (the highest point). */
        public double topZ;
        /** Z of the underside of the retrieval opening / resting height of the lowest piece. */
        public double floorZ;
        /** Footprint of the whole assembly (Z from the base to topZ). */
        public Box footprint = new Box();
    }

    public static final class Piece {
        /** POLLEN or NECTAR. */
        public String type = "";
        /** NECTAR colour (RED or BLUE); empty for POLLEN. */
        public String alliance = "";
        /**
         * Setup group: PRELOAD_RED, PRELOAD_BLUE (POLLEN staged for the robots), FLOWER_0..FLOWER_3 (POLLEN in the
         * FLOWER retrieval tube), GARDEN_RED, GARDEN_BLUE (POLLEN), CELL_RED, CELL_BLUE (NECTAR in the up CELL of
         * that alliance's HIVE), AREA_RED, AREA_BLUE (NECTAR staged in the ALLIANCE AREA).
         */
        public String group = "";
        public double x, y, z;
        public double radius;
    }

    public Hive hive(String alliance) {
        for (Hive h : hives) if (h.alliance.equalsIgnoreCase(alliance)) return h;
        return null;
    }

    public List<Piece> group(String group) {
        List<Piece> out = new ArrayList<>();
        for (Piece p : pieces) if (p.group.equals(group)) out.add(p);
        return out;
    }
}
