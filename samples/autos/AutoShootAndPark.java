import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

/**
 * BIOBUZZ AUTO, RED alliance: drive to the audience side of the RED HIVE, shoot the four preloaded POLLEN into its
 * upward CELL (2 pts each), then return and park in the RED LOADING ZONE.
 */
@Autonomous(name = "Auto Shoot Preload + Park", group = "BIOBUZZ", preselectTeleOp = "BIOBUZZ TeleOp")
public class AutoShootAndPark extends LinearOpMode {
    @Override
    public void runOpMode() {
        BioBot bot = new BioBot(this, 12, -61.5, 90);
        waitForStart();
        // stay on the audience side, well clear of the HIVE frame (x -19.5..19.5, y -25.5..-10.5)
        bot.goTo(44, -40, 180, 0.6, 5);
        bot.shoot(4, 0.9);
        bot.goTo(-30, -57, 90, 0.7, 10);
        while (opModeIsActive()) sleep(20);
    }
}
