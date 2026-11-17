import com.qualcomm.robotcore.eventloop.opmode.Autonomous;
import com.qualcomm.robotcore.eventloop.opmode.LinearOpMode;

/**
 * BIOBUZZ AUTO, RED alliance: step off the RED wall (LEAVE, 3 pts) and finish inside the RED LOADING ZONE
 * (AUTO PARK, 5 pts). 8 points, the whole zone is reachable from the start pose in a couple of seconds.
 */
@Autonomous(name = "Auto Leave + Park", group = "BIOBUZZ", preselectTeleOp = "BIOBUZZ TeleOp")
public class AutoLeavePark extends LinearOpMode {
    @Override
    public void runOpMode() {
        BioBot bot = new BioBot(this, 12, -61.5, 90);
        waitForStart();
        // the LOADING ZONE spans x = -47..-23.5 and is 11 in deep; keep the robot overlapping it after leaving the wall
        bot.goTo(-30, -57, 90, 0.6, 8);
        telemetry.addData("pose", "%.1f %.1f %.1f", bot.x(), bot.y(), bot.heading());
        telemetry.update();
        while (opModeIsActive()) sleep(20);
    }
}
