# BIOBUZZ in FTCSim

The BIOBUZZ game model follows the *BIOBUZZ Competition Manual TU08*. The field comes from the competition CAD
(`am-5850 BIOBUZZ.stl`), the rules from the manual.

## Field (STL)

* `ftcsim.map.StlMapCompiler` turns the 232 MB binary STL (inches, 4.6 M triangles, 747 components) into a compact
  `FieldMap` (collision boxes, HIVEs with their rocker CELLs, FLOWERs, the 56 starting game pieces). The result is
  baked into `sim/src/main/resources/map/biobuzz.json` and is what the simulator uses by default.
* Use another or a newer STL with `--map <file.stl>` (compiled once, cached in the data directory) or rebake with
  `gradlew bakeMap -Pstl=<file.stl>`.
* STL to simulator frame: `sim(X, Y, Z) = (-stl_y, stl_x, stl_z)`. RED alliance wall = sim -Y, BLUE = +Y, the audience
  is +X, the origin is the field centre. Perimeter walls are not solids: the robot is clamped to the inner wall.
* The CAD carries no tape. LOADING ZONEs (one tile wide, 11 in deep against the alliance wall) and GARDENs
  (2 in wide, one tile long, in the side corners) are derived from the tile grid described in manual section 9.3.

## Match

`--autos <folder>` / the UI "Folder" panel loads a plain folder of `@Autonomous` / `@TeleOp` sources.
"Run match" plays AUTO (30 s), the 8 s transition and TELEOP (120 s) on the simulator clock; the AUTO OpMode is stopped
when AUTO ends and the selected TeleOp is started when TELEOP begins. `--batch` runs every `@Autonomous` for its 30 s and
prints the score and violations (exit code 0 = all fine, 1 = problems/fouls, 2 = setup error, 3 = nothing to run).

## Scoring (manual 10.5)

| Item | Points | When |
|---|---|---|
| LEAVE (robot off the wall) | 3 | end of AUTO |
| PARK in own LOADING ZONE | 5 AUTO + 5 TELEOP | end of AUTO / end of MATCH |
| HIVE TIP | 20 | assessed throughout (before TELEOP counts as AUTO) |
| POLLEN/NECTAR in an upward CELL | 2 | end of MATCH |
| POLLEN/NECTAR in an owned FLOWER | 2, +5 bottom NECTAR bonus | after 1:00 remaining, final at end |
| Element in GARDEN | 1 | end of TELEOP at rest |
| Penalties | 5 minor / 20 major to the opponent | when assessed |

Ranking points: POLLINATOR 1 (4 TIPS), POLLINATOR 2 (7 TIPS), WIN 3 / TIE 1. SWARM needs two robots' worth of LEAVE+PARK
(>= 16), so it is unreachable with one simulated robot (max 13 points of LEAVE+PARK).

The manual does not publish the load that tips a HIVE. `game.hiveTipLoad` in the robot config (default 7.0;
POLLEN = 1.0, NECTAR = 1.3) is an assumption.

## Rules enforced

G401 (own-side starting/AUTO restrictions), G402 (alliance sides), G403/G404, G405 (advisory), G407, G408, G409, G410
(FLOWER scoring locked until 1:00), G417 (HIVE meddling), G418, G426 (NECTAR re-entry through the LOADING ZONE),
G427, G428. Violations carry the manual's levels: VERBAL WARNING, MINOR, MAJOR, YELLOW CARD (a second is RED CARD),
RED CARD = DISQUALIFIED. STRATEGIC/REPEATED repeats are approximated: the third occurrence is penalised, the first two
are verbal warnings.

Not simulated (no referees, opposing robots, drive team, or the physical robot inspection): the human-player and
drive-team conduct rules, the opposing alliance (it is passive), pre-match robot inspection (R-rules on sizes such as
R102 18x18x18 start / R105 18x24x29 limits are only checked from `lengthIn`/`widthIn`/`heightIn` in the config), and
FTC Dashboard/Panels prohibition (R704).

## Robot mechanisms

OpModes drive ordinary SDK hardware. The robot picks up elements with an intake and shoots with a launcher; both are
found from the robot config's `game` block, or by motor/servo name (`intake`, `shooter`, `feeder`, ...). In the UI the
manual buttons (Intake / Fire) and keyboard gamepad let you play TeleOp without writing code.
