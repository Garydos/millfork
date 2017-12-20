package millfork.test.emu

import millfork.{Cpu, OptimizationPresets}

/**
  * @author Karol Stasiak
  */
object EmuOptimizedInlinedRun extends EmuRun(
  Cpu.StrictMos,
  OptimizationPresets.NodeOpt,
  OptimizationPresets.AssOpt ++ OptimizationPresets.Good ++ OptimizationPresets.Good ++ OptimizationPresets.Good,
  false)


