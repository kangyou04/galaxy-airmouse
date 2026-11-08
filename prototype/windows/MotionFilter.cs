namespace GalaxyMouse;

// Converts angular velocity samples to bounded relative movement. No OS input is sent here.
internal sealed class MotionFilter
{
    private double? previousSeconds;
    private double filteredX;
    private double filteredY;

    public (double X, double Y) Sample(double seconds, double xDegreesPerSecond, double yDegreesPerSecond)
    {
        if (!double.IsFinite(seconds) || !double.IsFinite(xDegreesPerSecond) || !double.IsFinite(yDegreesPerSecond))
        {
            Reset();
            return (0, 0);
        }
        if (previousSeconds is null) { previousSeconds = seconds; return (0, 0); }
        double elapsed = seconds - previousSeconds.Value;
        if (elapsed <= 0) return (0, 0);
        previousSeconds = seconds;
        if (elapsed > 0.25) { filteredX = filteredY = 0; return (0, 0); }
        double dt = Math.Min(elapsed, 0.05);
        const double deadZone = 0.8;
        double x = Math.Abs(xDegreesPerSecond) < deadZone ? 0 : Math.Clamp(xDegreesPerSecond, -360, 360);
        double y = Math.Abs(yDegreesPerSecond) < deadZone ? 0 : Math.Clamp(yDegreesPerSecond, -360, 360);
        double alpha = 1 - Math.Exp(-dt / 0.025);
        filteredX += alpha * (x - filteredX);
        filteredY += alpha * (y - filteredY);
        return (filteredX * dt, filteredY * dt);
    }

    public void Reset() { previousSeconds = null; filteredX = filteredY = 0; }
}
