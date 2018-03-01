package millfork.assembly.opt

import millfork.assembly._
import millfork.assembly.Opcode._
import millfork.assembly.AddrMode._
import millfork.assembly.OpcodeClasses._
import millfork.env.{Constant, NormalFunction, NumericConstant}

/**
  * These optimizations help on their own, but may prevent other optimizations from triggering.
  *
  * @author Karol Stasiak
  */
//noinspection ZeroIndexToHead
object LaterOptimizations {

  private val LdxAddrModes = Set(Immediate, Absolute, ZeroPage, ZeroPageY, AbsoluteY)
  private val LdyAddrModes = Set(Immediate, Absolute, ZeroPage, ZeroPageX, AbsoluteX)
  private val StxAddrModes = Set(Absolute, ZeroPage, ZeroPageY)
  private val StyAddrModes = Set(Absolute, ZeroPage, ZeroPageX)
  private val StaAddrModes = Set(Absolute, ZeroPage, ZeroPageX, AbsoluteX, IndexedY, IndexedX, AbsoluteY)
  private val CpxyAddrModes = Set(Immediate, Absolute, ZeroPage)

  // This optimization tends to prevent later Variable To Register Optimization,
  // so run this only after it's pretty sure V2RO won't happen any more
  val DoubleLoadToDifferentRegisters = new RuleBasedAssemblyOptimization("Double load to different registers",
    needsFlowInfo = FlowInfoRequirement.NoRequirement,
    TwoDifferentLoadsWithNoFlagChangeInBetween(LDA, Not(ChangesA), LDX, TAX),
    TwoDifferentLoadsWithNoFlagChangeInBetween(LDA, Not(ChangesA), LDY, TAY),
    TwoDifferentLoadsWithNoFlagChangeInBetween(LDX, Not(ChangesX), LDA, TXA),
    TwoDifferentLoadsWithNoFlagChangeInBetween(LDY, Not(ChangesY), LDA, TYA),
    TwoDifferentLoadsWhoseFlagsWillNotBeChecked(LDA, Not(ChangesA), LDX, TAX),
    TwoDifferentLoadsWhoseFlagsWillNotBeChecked(LDA, Not(ChangesA), LDY, TAY),
    TwoDifferentLoadsWhoseFlagsWillNotBeChecked(LDX, Not(ChangesX), LDA, TXA),
    TwoDifferentLoadsWhoseFlagsWillNotBeChecked(LDY, Not(ChangesY), LDA, TYA),

    (HasOpcodeIn(Set(LDA, STA)) & MatchAddrMode(0) & MatchParameter(1)) ~
      (Linear & Not(ChangesA) & Not(HasOpcode(LDX)) & DoesntChangeIndexingInAddrMode(0) & DoesntChangeMemoryAt(0, 1)).* ~
      (Elidable & HasOpcode(LDX) & MatchAddrMode(0) & MatchParameter(1)) ~~> (code => code.init :+ AssemblyLine.implied(TAX)),

    (HasOpcodeIn(Set(LDA, STA)) & MatchAddrMode(0) & MatchParameter(1)) ~
      (Linear & Not(ChangesA) & Not(HasOpcode(LDY)) & DoesntChangeIndexingInAddrMode(0) & DoesntChangeMemoryAt(0, 1)).* ~
      (Elidable & HasOpcode(LDY) & MatchAddrMode(0) & MatchParameter(1)) ~~> (code => code.init :+ AssemblyLine.implied(TAY)),

    (HasOpcodeIn(Set(LDX, STX)) & MatchAddrMode(0) & MatchParameter(1)) ~
      (Linear & Not(ChangesX) & Not(HasOpcode(LDA)) & DoesntChangeIndexingInAddrMode(0) & DoesntChangeMemoryAt(0, 1)).* ~
      (Elidable & HasOpcode(LDA) & MatchAddrMode(0) & MatchParameter(1)) ~~> (code => code.init :+ AssemblyLine.implied(TXA)),

    (HasOpcodeIn(Set(LDY, STY)) & MatchAddrMode(0) & MatchParameter(1)) ~
      (Linear & Not(ChangesY) & Not(HasOpcode(LDA)) & DoesntChangeIndexingInAddrMode(0) & DoesntChangeMemoryAt(0, 1)).* ~
      (Elidable & HasOpcode(LDA) & MatchAddrMode(0) & MatchParameter(1)) ~~> (code => code.init :+ AssemblyLine.implied(TYA)),
  )

  private def a2x(line: AssemblyLine) = line.opcode match {
    case LDA => line.copy(opcode = LDX)
    case STA => line.copy(opcode = STX)
    case CMP => line.copy(opcode = CPX)
    case INC => line.copy(opcode = INX)
    case DEC => line.copy(opcode = DEX)
  }

  private def a2y(line: AssemblyLine) = line.opcode match {
    case LDA => line.copy(opcode = LDY)
    case STA => line.copy(opcode = STY)
    case CMP => line.copy(opcode = CPY)
    case INC => line.copy(opcode = INY)
    case DEC => line.copy(opcode = DEY)
  }

  private def x2a(line: AssemblyLine) = line.opcode match {
    case LDX => line.copy(opcode = LDA)
    case STX => line.copy(opcode = STA)
    case CPX => line.copy(opcode = CMP)
    case INX => line.copy(opcode = INC)
    case DEX => line.copy(opcode = DEC)
  }

  private def y2a(line: AssemblyLine) = line.opcode match {
    case LDY => line.copy(opcode = LDA)
    case STY => line.copy(opcode = STA)
    case CPY => line.copy(opcode = CMP)
    case INY => line.copy(opcode = INC)
    case DEY => line.copy(opcode = DEC)
  }

  val DoubleLoadToTwoRegistersWhenOneWillBeTrashed = new RuleBasedAssemblyOptimization("Double load to two registers when one will be trashed",
    needsFlowInfo = FlowInfoRequirement.BackwardFlow,
    (Elidable & HasOpcode(LDA) & MatchAddrMode(0) & MatchParameter(1) & Not(ConcernsX)) ~
      (Elidable & HasOpcode(STA) & HasAddrModeIn(StxAddrModes) & DoesNotConcernMemoryAt(0, 1) & Not(ConcernsX)).+ ~
      (Elidable & (HasOpcode(TAX) | HasOpcode(LDA) & MatchAddrMode(0) & MatchParameter(1) & Not(ConcernsX)) & DoesntMatterWhatItDoesWith(State.A)) ~~> (_.init.map(a2x)),
    (Elidable & HasOpcode(LDA) & MatchAddrMode(0) & MatchParameter(1) & Not(ConcernsY)) ~
      (Elidable & HasOpcode(STA) & HasAddrModeIn(StyAddrModes) & DoesNotConcernMemoryAt(0, 1) & Not(ConcernsY)).+ ~
      (Elidable & (HasOpcode(TAY) | HasOpcode(LDA) & MatchAddrMode(0) & MatchParameter(1) & Not(ConcernsY)) & DoesntMatterWhatItDoesWith(State.A)) ~~> (_.init.map(a2y)),
    (Elidable & HasOpcode(LDX) & MatchAddrMode(0) & MatchParameter(1)) ~
      (Elidable & HasOpcode(STX) & HasAddrModeIn(StaAddrModes) & DoesNotConcernMemoryAt(0, 1)).+ ~
      (Elidable & (HasOpcode(TXA) | HasOpcode(LDX) & MatchAddrMode(0) & MatchParameter(1)) & DoesntMatterWhatItDoesWith(State.X)) ~~> (_.init.map(x2a)),
    (Elidable & HasOpcode(LDY) & MatchAddrMode(0) & MatchParameter(1)) ~
      (Elidable & HasOpcode(STY) & HasAddrModeIn(StaAddrModes) & DoesNotConcernMemoryAt(0, 1)).+ ~
      (Elidable & (HasOpcode(TYA) | HasOpcode(LDY) & MatchAddrMode(0) & MatchParameter(1)) & DoesntMatterWhatItDoesWith(State.Y)) ~~> (_.init.map(y2a)),
  )

  private def TwoDifferentLoadsWithNoFlagChangeInBetween(opcode1: Opcode.Value, middle: AssemblyLinePattern, opcode2: Opcode.Value, transferOpcode: Opcode.Value) = {
    (HasOpcode(opcode1) & MatchAddrMode(0) & MatchParameter(1)) ~
      (LinearOrLabel & Not(ChangesMemory) & middle & Not(HasOpcode(opcode2))).* ~
      (HasOpcode(opcode2) & Elidable & MatchAddrMode(0) & MatchParameter(1)) ~~> { c =>
      c.init :+ AssemblyLine.implied(transferOpcode)
    }
  }

  private def TwoDifferentLoadsWhoseFlagsWillNotBeChecked(opcode1: Opcode.Value, middle: AssemblyLinePattern, opcode2: Opcode.Value, transferOpcode: Opcode.Value) = {
    ((HasOpcode(opcode1) & MatchAddrMode(0) & MatchParameter(1)) ~
      (LinearOrLabel & Not(ChangesMemory) & middle & Not(HasOpcode(opcode2))).*).capture(2) ~
      (HasOpcode(opcode2) & Elidable & MatchAddrMode(0) & MatchParameter(1)) ~
      ((LinearOrLabel & Not(ReadsNOrZ) & Not(ChangesNAndZ)).* ~ ChangesNAndZ).capture(3) ~~> { (_, ctx) =>
      ctx.get[List[AssemblyLine]](2) ++ (AssemblyLine.implied(transferOpcode) :: ctx.get[List[AssemblyLine]](3))
    }
  }

  private def TwoIdenticalLoadsWithNoFlagChangeInBetween(opcode: Opcode.Value, middle: AssemblyLinePattern) = {
    (HasOpcode(opcode) & MatchAddrMode(0) & MatchParameter(1)) ~
      (LinearOrLabel & Not(ChangesMemory) & middle & Not(ChangesNAndZ)).* ~
      (HasOpcode(opcode) & Elidable & MatchAddrMode(0) & MatchParameter(1)) ~~> { c =>
      c.init
    }
  }

  private def TwoIdenticalImmediateLoadsWithNoFlagChangeInBetween(opcode: Opcode.Value, middle: AssemblyLinePattern) = {
    (HasOpcode(opcode) & HasAddrMode(Immediate) & MatchParameter(1)) ~
      (LinearOrLabel & middle & Not(ChangesNAndZ)).* ~
      (HasOpcode(opcode) & Elidable & HasAddrMode(Immediate) & MatchParameter(1)) ~~> { c =>
      c.init
    }
  }

  private def TwoIdenticalLoadsWhoseFlagsWillNotBeChecked(opcode: Opcode.Value, middle: AssemblyLinePattern) = {
    ((HasOpcode(opcode) & MatchAddrMode(0) & MatchParameter(1)) ~
      (LinearOrLabel & Not(ChangesMemory) & middle).*).capture(2) ~
      (HasOpcode(opcode) & Elidable & MatchAddrMode(0) & MatchParameter(1)) ~
      ((LinearOrLabel & Not(ReadsNOrZ) & Not(ChangesNAndZ)).* ~ ChangesNAndZ).capture(3) ~~> { (_, ctx) =>
      ctx.get[List[AssemblyLine]](2) ++ ctx.get[List[AssemblyLine]](3)
    }
  }

  //noinspection ZeroIndexToHead
  private def InterleavedLoads(load: Opcode.Value, store: Opcode.Value) = {
    (Elidable & HasOpcode(load) & MatchAddrMode(0) & MatchParameter(1)).capture(12) ~
      (Elidable & HasOpcode(store)).+.capture(10) ~
      (Elidable & HasOpcode(load) & MatchAddrMode(2) & MatchParameter(3) & DoesNotConcernMemoryAt(0, 1)).capture(13) ~
      (Elidable & HasOpcode(store) & DoesNotConcernMemoryAt(0, 1) & DoesNotConcernMemoryAt(2, 3)).+.capture(11) ~
      (Elidable & HasOpcode(load) & MatchAddrMode(0) & MatchParameter(1)) ~
      WhereNoMemoryAccessOverlapBetweenTwoLineLists(10, 11) ~~> { (_, ctx) =>
      List(ctx.get[List[AssemblyLine]](13),
        ctx.get[List[AssemblyLine]](11),
        ctx.get[List[AssemblyLine]](12),
        ctx.get[List[AssemblyLine]](10)).flatten
    }
  }

  val DoubleLoadToTheSameRegister = new RuleBasedAssemblyOptimization("Double load to the same register",
    needsFlowInfo = FlowInfoRequirement.NoRequirement,
    TwoIdenticalLoadsWithNoFlagChangeInBetween(LDA, Not(ChangesA)),
    TwoIdenticalLoadsWithNoFlagChangeInBetween(LDX, Not(ChangesX)),
    TwoIdenticalLoadsWithNoFlagChangeInBetween(LDY, Not(ChangesY)),
    TwoIdenticalLoadsWithNoFlagChangeInBetween(LAX, Not(ChangesA) & Not(ChangesX)),
    TwoIdenticalImmediateLoadsWithNoFlagChangeInBetween(LDA, Not(ChangesA)),
    TwoIdenticalImmediateLoadsWithNoFlagChangeInBetween(LDX, Not(ChangesX)),
    TwoIdenticalImmediateLoadsWithNoFlagChangeInBetween(LDY, Not(ChangesY)),
    TwoIdenticalLoadsWhoseFlagsWillNotBeChecked(LDA, Not(ChangesA)),
    TwoIdenticalLoadsWhoseFlagsWillNotBeChecked(LDX, Not(ChangesX)),
    TwoIdenticalLoadsWhoseFlagsWillNotBeChecked(LDY, Not(ChangesY)),
    TwoIdenticalLoadsWhoseFlagsWillNotBeChecked(LAX, Not(ChangesA) & Not(ChangesX)),
    InterleavedLoads(LDA, STA),
    InterleavedLoads(LDX, STX),
    InterleavedLoads(LDY, STY),
  )

  private def pointlessLoadAfterStore(store: Opcode.Value, load: Opcode.Value, addrMode: AddrMode.Value, meantime: AssemblyLinePattern = Anything) = {
    ((HasOpcode(store) & HasAddrMode(addrMode) & MatchParameter(1)) ~
      (LinearOrBranch & Not(ChangesA) & Not(ChangesMemory) & meantime).*).capture(2) ~
      (HasOpcode(load) & Elidable & HasAddrMode(addrMode) & MatchParameter(1)) ~
      ((LinearOrLabel & Not(ReadsNOrZ) & Not(ChangesNAndZ)).* ~ ChangesNAndZ).capture(3) ~~> { (_, ctx) =>
      ctx.get[List[AssemblyLine]](2) ++ ctx.get[List[AssemblyLine]](3)
    }
  }

  val PointlessLoadAfterStore = new RuleBasedAssemblyOptimization("Pointless load after store",
    needsFlowInfo = FlowInfoRequirement.NoRequirement,
    pointlessLoadAfterStore(STA, LDA, Absolute),
    pointlessLoadAfterStore(STA, LDA, AbsoluteX, Not(ChangesX)),
    pointlessLoadAfterStore(STA, LDA, AbsoluteY, Not(ChangesY)),
    pointlessLoadAfterStore(STX, LDX, Absolute),
    pointlessLoadAfterStore(STY, LDY, Absolute),
  )


  private val ShiftAddrModes = Set(ZeroPage, ZeroPageX, Absolute, AbsoluteX)
  private val ShiftOpcodes = Set(ASL, ROL, ROR, LSR)

  // LDA-SHIFT-STA is slower than just SHIFT
  // LDA-SHIFT-SHIFT-STA is equally fast as SHIFT-SHIFT, but the latter doesn't use the accumulator
  val PointessLoadingForShifting = new RuleBasedAssemblyOptimization("Pointless loading for shifting",
    needsFlowInfo = FlowInfoRequirement.NoRequirement,
    (Elidable & HasOpcode(LDA) & HasAddrModeIn(ShiftAddrModes) & MatchAddrMode(0) & MatchParameter(1)) ~
      (Elidable & HasOpcodeIn(ShiftOpcodes) & HasAddrMode(Implied) & MatchOpcode(2)) ~
      (Elidable & HasOpcode(STA) & HasAddrModeIn(ShiftAddrModes) & MatchAddrMode(0) & MatchParameter(1)) ~
      (Not(ReadsA) & Not(OverwritesA)).* ~ OverwritesA ~~> { (code, ctx) =>
      AssemblyLine(ctx.get[Opcode.Value](2), ctx.get[AddrMode.Value](0), ctx.get[Constant](1)) :: code.drop(3)
    },
    (Elidable & HasOpcode(LDA) & HasAddrModeIn(ShiftAddrModes) & MatchAddrMode(0) & MatchParameter(1)) ~
      (Elidable & HasOpcodeIn(ShiftOpcodes) & HasAddrMode(Implied) & MatchOpcode(2)) ~
      (Elidable & HasOpcodeIn(ShiftOpcodes) & HasAddrMode(Implied) & MatchOpcode(2)) ~
      (Elidable & HasOpcode(STA) & HasAddrModeIn(ShiftAddrModes) & MatchAddrMode(0) & MatchParameter(1)) ~
      (Not(ReadsA) & Not(OverwritesA)).* ~ OverwritesA ~~> { (code, ctx) =>
      val shift = AssemblyLine(ctx.get[Opcode.Value](2), ctx.get[AddrMode.Value](0), ctx.get[Constant](1))
      shift :: shift :: code.drop(4)
    }
  )

  // SHIFT-LDA is equally fast as LDA-SHIFT-STA, but can enable further optimizations doesn't use the accumulator
  // LDA-SHIFT-SHIFT-STA is equally fast as SHIFT-SHIFT, but the latter doesn't use the accumulator
  val LoadingAfterShifting = new RuleBasedAssemblyOptimization("Loading after shifting",
    needsFlowInfo = FlowInfoRequirement.NoRequirement,
    (Elidable & HasOpcodeIn(ShiftOpcodes) & MatchAddrMode(0) & MatchParameter(1)) ~
      (Elidable & HasOpcode(LDA) & MatchAddrMode(0) & MatchParameter(1)) ~~> { (code, ctx) =>
      AssemblyLine(LDA, ctx.get[AddrMode.Value](0), ctx.get[Constant](1)) ::
        AssemblyLine.implied(code.head.opcode) ::
        AssemblyLine(STA, ctx.get[AddrMode.Value](0), ctx.get[Constant](1)) ::
        code.drop(2)
    }
  )

  val UseZeropageAddressingMode = new RuleBasedAssemblyOptimization("Using zeropage addressing mode",
    needsFlowInfo = FlowInfoRequirement.NoRequirement,
    (Elidable & HasAddrMode(Absolute) & MatchParameter(0)) ~ Where(ctx => ctx.get[Constant](0).quickSimplify match {
      case NumericConstant(x, _) => (x & 0xff00) == 0
      case _ => false
    }) ~~> (code => code.head.copy(addrMode = ZeroPage) :: Nil)
  )

  val UseXInsteadOfStack = new RuleBasedAssemblyOptimization("Using X instead of stack",
    needsFlowInfo = FlowInfoRequirement.BackwardFlow,
    (Elidable & HasOpcode(PHA) & DoesntMatterWhatItDoesWith(State.X)) ~
      (Not(ConcernsStack) & Not(ConcernsX)).capture(1) ~
      Where(_.isExternallyLinearBlock(1)) ~
      (Elidable & HasOpcode(PLA)) ~~> (c =>
      AssemblyLine.implied(TAX) :: (c.tail.init :+ AssemblyLine.implied(TXA))
      )
  )

  val UseYInsteadOfStack = new RuleBasedAssemblyOptimization("Using Y instead of stack",
    needsFlowInfo = FlowInfoRequirement.BackwardFlow,
    (Elidable & HasOpcode(PHA) & DoesntMatterWhatItDoesWith(State.Y)) ~
      (Not(ConcernsStack) & Not(ConcernsY)).capture(1) ~
      Where(_.isExternallyLinearBlock(1)) ~
      (Elidable & HasOpcode(PLA)) ~~> (c =>
      AssemblyLine.implied(TAY) :: (c.tail.init :+ AssemblyLine.implied(TYA))
      )
  )

  // TODO: make it more generic
  val IndexSwitchingOptimization = new RuleBasedAssemblyOptimization("Index switching optimization",
    needsFlowInfo = FlowInfoRequirement.BackwardFlow,
    (Elidable & HasOpcode(LDY) & MatchAddrMode(2) & Not(ReadsX) & MatchParameter(0) & HasAddrModeIn(LdxAddrModes)) ~
      (Elidable & Linear & Not(ChangesY) & HasAddrMode(AbsoluteY) & SupportsAbsoluteX & Not(ConcernsX)) ~
      (HasOpcode(LDY) & Not(ConcernsX)) ~
      (Linear & Not(ChangesY) & Not(ConcernsX) & HasAddrModeIn(Set(AbsoluteY, IndexedY, ZeroPageY))) ~
      (Elidable & HasOpcode(LDY) & MatchAddrMode(2) & Not(ReadsX) & MatchParameter(0)) ~
      (Elidable & Linear & Not(ChangesY) & HasAddrMode(AbsoluteY) & SupportsAbsoluteX & Not(ConcernsX) & DoesntMatterWhatItDoesWith(State.X, State.N, State.Z)) ~~> { (code, ctx) =>
      List(
        code(0).copy(opcode = LDX),
        code(1).copy(addrMode = AbsoluteX),
        code(2),
        code(3),
        code(5).copy(addrMode = AbsoluteX))
    },
    (Elidable & HasOpcode(LDX) & MatchAddrMode(2) & Not(ReadsY) & MatchParameter(0) & HasAddrModeIn(LdyAddrModes)) ~
      (Elidable & Linear & Not(ChangesX) & HasAddrMode(AbsoluteX) & SupportsAbsoluteY & Not(ConcernsY)) ~
      (HasOpcode(LDX) & Not(ConcernsY)) ~
      (Linear & Not(ChangesX) & Not(ConcernsY) & HasAddrModeIn(Set(AbsoluteX, IndexedX, ZeroPageX, AbsoluteIndexedX))) ~
      (Elidable & HasOpcode(LDX) & MatchAddrMode(2) & Not(ReadsY) & MatchParameter(0)) ~
      (Elidable & Linear & Not(ChangesX) & HasAddrMode(AbsoluteX) & SupportsAbsoluteY & Not(ConcernsY) & DoesntMatterWhatItDoesWith(State.Y, State.N, State.Z)) ~~> { (code, ctx) =>
      List(
        code(0).copy(opcode = LDY),
        code(1).copy(addrMode = AbsoluteY),
        code(2),
        code(3),
        code(5).copy(addrMode = AbsoluteY))
    },

  )

  private def incDecThroughIndexRegister(amount: Int, dec: Boolean, carrySet: Boolean, useX: Boolean) = {
    val ldAddrModes = if (useX) LdxAddrModes else LdyAddrModes
    val stAddrModes = if (useX) StxAddrModes else StyAddrModes
    val ldOp = if (useX) LDX else LDY
    val stOp = if (useX) STX else STY
    val changeOp = if (dec) if (useX) DEX else DEY else if (useX) INX else INY
    val addOp = if (dec) SBC else ADC
    val addParam = if (dec ^ carrySet) amount + 1 else amount
    val indexState = if (useX) State.X else State.Y
    val cState = if (carrySet) HasSet(State.C) else HasClear(State.C)
    val carryOp = if (carrySet) SEC else CLC

    (Elidable & HasOpcode(LDA) & HasAddrModeIn(ldAddrModes)).capture(11) ~
      (Elidable & HasOpcode(carryOp)).? ~
      (Elidable & HasOpcode(addOp) & HasImmediate(addParam) & cState & HasClear(State.D)) ~
      (Elidable & HasOpcode(STA) & HasAddrModeIn(stAddrModes) & DoesntMatterWhatItDoesWith(State.A, State.C, State.V, indexState)).capture(12) ~~> { (_, ctx) =>
      ctx.get[List[AssemblyLine]](11).head.copy(opcode = ldOp) ::
        (List.fill(amount)(AssemblyLine.implied(changeOp)) :+
          ctx.get[List[AssemblyLine]](12).head.copy(opcode = stOp))
    }
  }

  val IncrementThroughIndexRegisters = new RuleBasedAssemblyOptimization("Increment through index registers",
    needsFlowInfo = FlowInfoRequirement.BothFlows,
    incDecThroughIndexRegister(1, dec = false, carrySet = false, useX = true),
    incDecThroughIndexRegister(1, dec = false, carrySet = false, useX = false),
    incDecThroughIndexRegister(1, dec = false, carrySet = true, useX = true),
    incDecThroughIndexRegister(1, dec = false, carrySet = true, useX = false),
    incDecThroughIndexRegister(1, dec = true, carrySet = true, useX = true),
    incDecThroughIndexRegister(1, dec = true, carrySet = true, useX = false),
    incDecThroughIndexRegister(2, dec = false, carrySet = false, useX = true),
    incDecThroughIndexRegister(2, dec = false, carrySet = false, useX = false),
    incDecThroughIndexRegister(2, dec = false, carrySet = true, useX = true),
    incDecThroughIndexRegister(2, dec = false, carrySet = true, useX = false),
    incDecThroughIndexRegister(2, dec = true, carrySet = true, useX = true),
    incDecThroughIndexRegister(2, dec = true, carrySet = true, useX = false),
  )

  val LoadingBranchesOptimization = new RuleBasedAssemblyOptimization("Loading branches optimization",
    needsFlowInfo = FlowInfoRequirement.BackwardFlow,
    (Elidable & HasOpcode(LDA) & HasAddrModeIn(LdxAddrModes) & DoesntMatterWhatItDoesWith(State.X)) ~
      (Linear & Not(ConcernsX) & Not(ChangesA) & Not(HasOpcode(CMP)) & (Not(ReadsA) | Elidable & HasOpcode(STA) & HasAddrModeIn(StxAddrModes))).*.capture(39) ~
      (Elidable & HasOpcode(CMP) & HasAddrModeIn(CpxyAddrModes)).?.capture(40) ~
      (Elidable & HasOpcodeIn(OpcodeClasses.ShortConditionalBranching) & MatchParameter(22)).capture(41) ~
      (Elidable & HasOpcode(LDA)).capture(31) ~
      (Elidable & HasOpcodeIn(Set(JMP, BRA)) & MatchParameter(21)) ~
      (Elidable & HasOpcode(LABEL) & MatchParameter(22)).capture(42) ~
      (Elidable & HasOpcode(LDA)).capture(32) ~
      (Elidable & HasOpcode(LABEL) & MatchParameter(21) & HasCallerCount(1) & DoesntMatterWhatItDoesWith(State.A, State.X, State.N, State.Z)) ~~> { (code, ctx) =>
      val ldx = List(code.head.copy(opcode = LDX))
      val stx = ctx.get[List[AssemblyLine]](39).map(l => if (l.opcode == STA) l.copy(opcode = STX) else l)
      val cpx = ctx.get[List[AssemblyLine]](40).map(_.copy(opcode = CPX))
      val branch = ctx.get[List[AssemblyLine]](41)
      val label = ctx.get[List[AssemblyLine]](42)
      val loadIfJumped = ctx.get[List[AssemblyLine]](32)
      val loadIfNotJumped = ctx.get[List[AssemblyLine]](31)
      List(loadIfJumped, ldx, stx, cpx, branch, loadIfNotJumped, label).flatten
    },
    (Elidable & HasOpcode(LDA) & HasAddrModeIn(LdyAddrModes) & DoesntMatterWhatItDoesWith(State.Y)) ~
      (Linear & Not(ConcernsY) & Not(ChangesA) & Not(HasOpcode(CMP)) & (Not(ReadsA) | Elidable & HasOpcode(STA) & HasAddrModeIn(StyAddrModes))).*.capture(39) ~
      (Elidable & HasOpcode(CMP) & HasAddrModeIn(CpxyAddrModes)).?.capture(40) ~
      (Elidable & HasOpcodeIn(OpcodeClasses.ShortConditionalBranching) & MatchParameter(22)).capture(41) ~
      (Elidable & HasOpcode(LDA)).capture(31) ~
      (Elidable & HasOpcodeIn(Set(JMP, BRA)) & MatchParameter(21)) ~
      (Elidable & HasOpcode(LABEL) & MatchParameter(22)).capture(42) ~
      (Elidable & HasOpcode(LDA)).capture(32) ~
      (Elidable & HasOpcode(LABEL) & MatchParameter(21) & HasCallerCount(1) & DoesntMatterWhatItDoesWith(State.A, State.Y, State.N, State.Z)) ~~> { (code, ctx) =>
      val ldy = List(code.head.copy(opcode = LDY))
      val sty = ctx.get[List[AssemblyLine]](39).map(l => if (l.opcode == STA) l.copy(opcode = STY) else l)
      val cpy = ctx.get[List[AssemblyLine]](40).map(_.copy(opcode = CPY))
      val branch = ctx.get[List[AssemblyLine]](41)
      val label = ctx.get[List[AssemblyLine]](42)
      val loadIfJumped = ctx.get[List[AssemblyLine]](32)
      val loadIfNotJumped = ctx.get[List[AssemblyLine]](31)
      List(loadIfJumped, ldy, sty, cpy, branch, loadIfNotJumped, label).flatten
    },
    (HasOpcode(LDA) & Not(ConcernsX) & DoesntMatterWhatItDoesWith(State.X)) ~
      (Linear & Not(ConcernsX)).*.capture(39) ~
      (Elidable & HasOpcodeIn(OpcodeClasses.ShortConditionalBranching) & MatchParameter(22)).capture(41) ~
      (Elidable & HasOpcode(LDA) & HasAddrModeIn(LdxAddrModes)).capture(31) ~
      (Elidable & HasOpcodeIn(Set(JMP, BRA)) & MatchParameter(21)) ~
      (Elidable & HasOpcode(LABEL) & MatchParameter(22)).capture(42) ~
      (Elidable & HasOpcode(LDA) & HasAddrModeIn(LdxAddrModes)).capture(32) ~
      (Elidable & HasOpcode(LABEL) & MatchParameter(21) & HasCallerCount(1)) ~
      (Elidable & HasOpcode(STA) & HasAddrModeIn(StxAddrModes) & DoesntMatterWhatItDoesWith(State.A, State.X, State.N, State.Z)).capture(33) ~~> { (code, ctx) =>
      val lda = List(code.head)
      val cmp = ctx.get[List[AssemblyLine]](39)
      val branch = ctx.get[List[AssemblyLine]](41)
      val label = ctx.get[List[AssemblyLine]](42)
      val loadIfJumped = ctx.get[List[AssemblyLine]](32).map(_.copy(opcode = LDX))
      val loadIfNotJumped = ctx.get[List[AssemblyLine]](31).map(_.copy(opcode = LDX))
      val stx = ctx.get[List[AssemblyLine]](33).map(_.copy(opcode = STX))
      List(loadIfJumped, lda, cmp, branch, loadIfNotJumped, label, stx).flatten
    },
    (HasOpcode(LDA) & Not(ConcernsY) & DoesntMatterWhatItDoesWith(State.Y)) ~
      (Linear & Not(ConcernsY)).*.capture(39) ~
      (Elidable & HasOpcodeIn(OpcodeClasses.ShortConditionalBranching) & MatchParameter(22)).capture(41) ~
      (Elidable & HasOpcode(LDA) & HasAddrModeIn(LdyAddrModes)).capture(31) ~
      (Elidable & HasOpcodeIn(Set(JMP, BRA)) & MatchParameter(21)) ~
      (Elidable & HasOpcode(LABEL) & MatchParameter(22)).capture(42) ~
      (Elidable & HasOpcode(LDA) & HasAddrModeIn(LdyAddrModes)).capture(32) ~
      (Elidable & HasOpcode(LABEL) & MatchParameter(21) & HasCallerCount(1)) ~
      (Elidable & HasOpcode(STA) & HasAddrModeIn(StyAddrModes) & DoesntMatterWhatItDoesWith(State.A, State.Y, State.N, State.Z)).capture(33) ~~> { (code, ctx) =>
      val lda = List(code.head)
      val cmp = ctx.get[List[AssemblyLine]](39)
      val branch = ctx.get[List[AssemblyLine]](41)
      val label = ctx.get[List[AssemblyLine]](42)
      val loadIfJumped = ctx.get[List[AssemblyLine]](32).map(_.copy(opcode = LDY))
      val loadIfNotJumped = ctx.get[List[AssemblyLine]](31).map(_.copy(opcode = LDY))
      val sty = ctx.get[List[AssemblyLine]](33).map(_.copy(opcode = STY))
      List(loadIfJumped, lda, cmp, branch, loadIfNotJumped, label, sty).flatten
    },
  )

  val IncreaseWithLimit = new RuleBasedAssemblyOptimization("Increase with a limit",
    needsFlowInfo = FlowInfoRequirement.BackwardFlow,
    (Elidable & HasOpcode(INC) & HasAddrModeIn(Set(Absolute, ZeroPage)) & MatchParameter(0)) ~
      (Elidable & HasOpcode(LDA) & HasAddrModeIn(Set(Absolute, ZeroPage)) & MatchParameter(0)) ~
      (Elidable & HasOpcode(CMP) & HasAddrModeIn(CpxyAddrModes)) ~
      (HasOpcode(BNE) & MatchParameter(14831)) ~
      (Elidable & HasOpcode(LDA) & HasAddrModeIn(LdyAddrModes)) ~
      (Elidable & HasOpcode(STA) & HasAddrModeIn(Set(Absolute, ZeroPage)) & MatchParameter(0) & DoesntMatterWhatItDoesWith(State.A, State.Y)) ~
      (HasOpcode(LABEL) & MatchParameter(14831)) ~~> { code =>
      List(
        code(1).copy(opcode = LDY),
        AssemblyLine.implied(INY),
        code(2).copy(opcode = CPY),
        code(3),
        code(4).copy(opcode = LDY),
        code(6),
        code(5).copy(opcode = STY))
    },
    (Elidable & HasOpcode(INC) & HasAddrModeIn(Set(Absolute, ZeroPage)) & MatchParameter(0)) ~
      (Elidable & HasOpcode(LDA) & HasAddrModeIn(Set(Absolute, ZeroPage)) & MatchParameter(0)) ~
      (Elidable & HasOpcode(CMP) & HasAddrModeIn(CpxyAddrModes)) ~
      (HasOpcode(BNE) & MatchParameter(14831)) ~
      (Elidable & HasOpcode(LDA) & HasAddrModeIn(LdxAddrModes)) ~
      (Elidable & HasOpcode(STA) & HasAddrModeIn(Set(Absolute, ZeroPage)) & MatchParameter(0) & DoesntMatterWhatItDoesWith(State.A, State.X)) ~
      (HasOpcode(LABEL) & MatchParameter(14831)) ~~> { code =>
      List(
        code(1).copy(opcode = LDX),
        AssemblyLine.implied(INX),
        code(2).copy(opcode = CPX),
        code(3),
        code(4).copy(opcode = LDX),
        code(6),
        code(5).copy(opcode = STX))
    },
  )

  val UseIndexedX = new RuleBasedAssemblyOptimization("Using indexed-indirect addressing mode",
    needsFlowInfo = FlowInfoRequirement.BothFlows,

    (Elidable & HasOpcode(LDY) & HasImmediate(0) & DoesntMatterWhatItDoesWith(State.Z, State.N)) ~
      (Linear & Not(ConcernsY)).* ~
      (Elidable & HasAddrMode(IndexedY) & HasX(0) & DoesntMatterWhatItDoesWith(State.Y)) ~~> { code =>
      code.tail.init :+ code.last.copy(addrMode = IndexedX)
    },

    (Elidable & HasOpcode(LDY) & HasImmediate(0) & DoesntMatterWhatItDoesWith(State.Z, State.N)) ~
      (Linear & Not(ConcernsY)).* ~
      (Elidable & HasOpcodeIn(Set(ISC, DCP, SLO, SRE, RRA, RLA)) & HasAddrMode(IndexedY) & HasX(0xff) & DoesntMatterWhatItDoesWith(State.Y, State.X)) ~~> { code =>
      code.tail.init ++ List(AssemblyLine.implied(INX), code.last.copy(addrMode = IndexedX))
    },

    (Elidable & HasOpcode(LDY) & HasImmediate(0) & DoesntMatterWhatItDoesWith(State.Z, State.N)) ~
      (Linear & Not(ConcernsY)).* ~
      (Elidable & HasOpcodeIn(Set(ISC, DCP, SLO, SRE, RRA, RLA)) & HasAddrMode(IndexedY) & HasX(1) & DoesntMatterWhatItDoesWith(State.Y, State.X)) ~~> { code =>
      code.tail.init ++ List(AssemblyLine.implied(DEX), code.last.copy(addrMode = IndexedX))
    },


  )

  val All = List(
    DoubleLoadToDifferentRegisters,
    DoubleLoadToTheSameRegister,
    IndexSwitchingOptimization,
    LoadingBranchesOptimization,
    PointlessLoadAfterStore,
    PointessLoadingForShifting,
    LoadingAfterShifting,
    UseXInsteadOfStack,
    UseYInsteadOfStack,
    UseZeropageAddressingMode)

  val Nmos = List(
    IncrementThroughIndexRegisters,
    UseIndexedX
  )
}

