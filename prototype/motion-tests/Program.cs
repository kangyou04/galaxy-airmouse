using GalaxyMouse;

static void Check(bool condition, string name) { if (!condition) throw new Exception(name); Console.WriteLine("PASS " + name); }
var filter = new MotionFilter();
Check(filter.Sample(1, 100, 100) == (0d, 0d), "first sample establishes time");
Check(filter.Sample(1.01, 0.1, -0.1) == (0d, 0d), "small stationary noise is ignored");
var move = filter.Sample(1.02, 90, -90);
Check(move.X > 0 && move.Y < 0, "motion preserves direction");
Check(filter.Sample(1.01, 100, 100) == (0d, 0d), "out-of-order sample ignored");
Check(filter.Sample(2, 100, 100) == (0d, 0d), "long gap does not jump");
Check(filter.Sample(double.NaN, 1, 1) == (0d, 0d), "non-finite input rejected");
Check(filter.Sample(3, 1, 1) == (0d, 0d), "invalid input resets clock");
var bounded = filter.Sample(3.05, 1000000, -1000000);
Check(Math.Abs(bounded.X) <= 18 && Math.Abs(bounded.Y) <= 18, "extreme velocity is bounded");
