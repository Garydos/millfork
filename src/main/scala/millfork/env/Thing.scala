package millfork.env

import millfork.assembly.BranchingOpcodeMapping
import millfork.{CompilationFlag, CompilationOptions, CpuFamily}
import millfork.node._
import millfork.output.{MemoryAlignment, NoAlignment}

sealed trait Thing {
  def name: String
}

case class Alias(name: String, target: String, deprecated: Boolean = false) extends Thing

sealed trait CallableThing extends Thing

sealed trait VariableLikeThing extends Thing

sealed trait IndexableThing extends Thing

sealed trait Type extends CallableThing {

  def size: Int

  def isSigned: Boolean

  def isSubtypeOf(other: Type): Boolean = this == other

  def isCompatible(other: Type): Boolean = this == other

  override def toString: String = name

  def isAssignableTo(targetType: Type): Boolean = isCompatible(targetType)

  def isArithmetic = false

  def isPointy = false

  def pointerTargetName: String = "byte"
}

sealed trait VariableType extends Type

case object VoidType extends Type {
  def size = 0

  def isSigned = false

  override def name = "void"
}

sealed trait PlainType extends VariableType {
  override def isCompatible(other: Type): Boolean = this == other || this.isSubtypeOf(other) || other.isSubtypeOf(this)

  override def isAssignableTo(targetType: Type): Boolean = isCompatible(targetType) || (targetType match {
    case BasicPlainType(_, size) => size > this.size // TODO
    case DerivedPlainType(_, parent, size, _) => isAssignableTo(parent)
    case _ => false
  })

  override def isArithmetic = true
}

case class BasicPlainType(name: String, size: Int) extends PlainType {
  def isSigned = false

  override def isSubtypeOf(other: Type): Boolean = this == other
}

case class DerivedPlainType(name: String, parent: PlainType, isSigned: Boolean, override val isPointy: Boolean) extends PlainType {
  def size: Int = parent.size

  override def isSubtypeOf(other: Type): Boolean = parent == other || parent.isSubtypeOf(other)
}

case class PointerType(name: String, targetName: String, var target: Option[Type]) extends VariableType {
  def size = 2

  override def isSigned: Boolean = false

  override def isPointy: Boolean = true

  override def pointerTargetName: String = targetName
}

case class FunctionPointerType(name: String, paramTypeName:String, returnTypeName: String, var paramType: Option[Type], var returnType: Option[Type]) extends VariableType {
  def size = 2

  override def isSigned: Boolean = false

  override def isPointy: Boolean = false
}

case object NullType extends VariableType {
  override def size: Int = 2

  override def isSigned: Boolean = false

  override def name: String = "null$"

  override def isPointy: Boolean = true

  override def isSubtypeOf(other: Type): Boolean = this == other || (other.isPointy || other.isInstanceOf[FunctionPointerType]) && other.size == 2

  override def isAssignableTo(targetType: Type): Boolean = this == targetType || (targetType.isPointy || targetType.isInstanceOf[FunctionPointerType]) && targetType.size == 2
}

case class EnumType(name: String, count: Option[Int]) extends VariableType {
  override def size: Int = 1

  override def isSigned: Boolean = false
}

sealed trait CompoundVariableType extends VariableType

case class StructType(name: String, fields: List[FieldDesc]) extends CompoundVariableType {
  override def size: Int = mutableSize
  var mutableSize: Int = -1
  var mutableFieldsWithTypes: List[(Type, String)] = Nil
  override def isSigned: Boolean = false
}

case class UnionType(name: String, fields: List[FieldDesc]) extends CompoundVariableType {
  override def size: Int = mutableSize
  var mutableSize: Int = -1
  var mutableFieldsWithTypes: List[(Type, String)] = Nil
  override def isSigned: Boolean = false
}

case object FatBooleanType extends VariableType {
  override def size: Int = 1

  override def isSigned: Boolean = false

  override def name: String = "bool"

  override def isPointy: Boolean = false

  override def isSubtypeOf(other: Type): Boolean = this == other

  override def isAssignableTo(targetType: Type): Boolean = this == targetType
}

sealed trait BooleanType extends Type {
  def size = 0

  def isSigned = false

  override def isAssignableTo(targetType: Type): Boolean = isCompatible(targetType) || targetType == FatBooleanType
}

case class ConstantBooleanType(name: String, value: Boolean) extends BooleanType

case class FlagBooleanType(name: String, jumpIfTrue: BranchingOpcodeMapping, jumpIfFalse: BranchingOpcodeMapping) extends BooleanType

case object BuiltInBooleanType extends BooleanType {
  override def name = "bool$"
}

sealed trait TypedThing extends Thing {
  def typ: Type
}


sealed trait ThingInMemory extends Thing {
  def zeropage: Boolean

  def toAddress: Constant

  var farFlag: Option[Boolean] = None
  val declaredBank: Option[String]

  def isFar(compilationOptions: CompilationOptions): Boolean
  def bank(compilationOptions: CompilationOptions): String

  def isVolatile: Boolean
}

sealed trait PreallocableThing extends ThingInMemory {
  def shouldGenerate: Boolean

  def address: Option[Constant]

  def alignment: MemoryAlignment

  def toAddress: Constant = address.getOrElse(MemoryAddressConstant(this))
}

case class Label(name: String) extends ThingInMemory {
  override def toAddress: MemoryAddressConstant = MemoryAddressConstant(this)

  override def isFar(compilationOptions: CompilationOptions): Boolean =
    compilationOptions.flag(CompilationFlag.LargeCode) && farFlag.getOrElse(true)

  override def bank(compilationOptions: CompilationOptions): String =
    declaredBank.getOrElse(compilationOptions.platform.defaultCodeBank)

  override val declaredBank: Option[String] = None

  override def zeropage: Boolean = false

  override def isVolatile: Boolean = false
}

sealed trait Variable extends TypedThing with VariableLikeThing {
  def isVolatile: Boolean
}

sealed trait VariableInMemory extends Variable with ThingInMemory with IndexableThing {

  override def isFar(compilationOptions: CompilationOptions): Boolean =
    !zeropage && farFlag.getOrElse(false)

  override def bank(compilationOptions: CompilationOptions): String =
    declaredBank.getOrElse("default")
}

case class RegisterVariable(register: MosRegister.Value, typ: Type) extends Variable {
  def name: String = register.toString

  override def isVolatile: Boolean = false
}

case class ZRegisterVariable(register: ZRegister.Value, typ: Type) extends Variable {
  def name: String = register.toString
  override def isVolatile: Boolean = false
}

case class M6809RegisterVariable(register: M6809Register.Value, typ: Type) extends Variable {
  def name: String = register.toString
  override def isVolatile: Boolean = false
}

case class Placeholder(name: String, typ: Type) extends Variable {
  override def isVolatile: Boolean = false
}

sealed trait UninitializedMemory extends ThingInMemory {
  def sizeInBytes: Int

  def alloc: VariableAllocationMethod.Value

  def alignment: MemoryAlignment
}

object VariableAllocationMethod extends Enumeration {
  val Auto, Register, Static, Zeropage, None = Value
}

case class StackVariable(name: String, typ: Type, baseOffset: Int) extends Variable with IndexableThing {
  def sizeInBytes: Int = typ.size
  override def isVolatile: Boolean = false
}

object MemoryVariable {
  def unapply(v: MemoryVariable) = Some((v.name, v.typ, v.alloc))
}

abstract class MemoryVariable extends VariableInMemory {
  def alloc: VariableAllocationMethod.Value
}

case class UninitializedMemoryVariable(
                                        name: String,
                                        typ: Type,
                                        alloc:
                                        VariableAllocationMethod.Value,
                                        declaredBank: Option[String],
                                        override val alignment: MemoryAlignment,
                                        override val isVolatile: Boolean) extends MemoryVariable with UninitializedMemory {
  override def sizeInBytes: Int = typ.size

  override def zeropage: Boolean = alloc == VariableAllocationMethod.Zeropage

  override def toAddress: MemoryAddressConstant = MemoryAddressConstant(this)
}

case class InitializedMemoryVariable(
                                      name: String,
                                      address: Option[Constant],
                                      typ: Type,
                                      initialValue: Expression,
                                      declaredBank: Option[String],
                                      override val alignment: MemoryAlignment,
                                      override val isVolatile: Boolean) extends MemoryVariable with PreallocableThing {
  override def zeropage: Boolean = false

  override def toAddress: MemoryAddressConstant = MemoryAddressConstant(this)

  override def shouldGenerate: Boolean = true

  override def alloc: VariableAllocationMethod.Value = VariableAllocationMethod.Static
}

trait MfArray extends ThingInMemory with IndexableThing {
  def indexType: VariableType
  def elementType: VariableType
  override def isVolatile: Boolean = false
  def sizeInBytes: Int
  def elementCount: Int
  def readOnly: Boolean
}

case class UninitializedArray(name: String, elementCount: Int, declaredBank: Option[String], indexType: VariableType, elementType: VariableType, override val readOnly: Boolean, override val alignment: MemoryAlignment) extends MfArray with UninitializedMemory {
  override def toAddress: MemoryAddressConstant = MemoryAddressConstant(this)

  override def alloc: VariableAllocationMethod.Value = VariableAllocationMethod.Static

  override def isFar(compilationOptions: CompilationOptions): Boolean = farFlag.getOrElse(false)

  override def bank(compilationOptions: CompilationOptions): String = declaredBank.getOrElse("default")

  override def zeropage: Boolean = false

  override def sizeInBytes: Int = elementCount * elementType.size
}

case class RelativeArray(name: String, address: Constant, elementCount: Int, declaredBank: Option[String], indexType: VariableType, elementType: VariableType, override val readOnly: Boolean) extends MfArray {
  override def toAddress: Constant = address

  override def isFar(compilationOptions: CompilationOptions): Boolean = farFlag.getOrElse(false)

  override def bank(compilationOptions: CompilationOptions): String = declaredBank.getOrElse("default")

  override def zeropage: Boolean = false

  override def sizeInBytes: Int = elementCount * elementType.size
}

case class InitializedArray(name: String, address: Option[Constant], contents: Seq[Expression], declaredBank: Option[String], indexType: VariableType, elementType: VariableType, override val readOnly: Boolean, override val alignment: MemoryAlignment) extends MfArray with PreallocableThing {
  override def shouldGenerate = true

  override def isFar(compilationOptions: CompilationOptions): Boolean = farFlag.getOrElse(false)

  override def bank(compilationOptions: CompilationOptions): String =
    declaredBank.getOrElse(if (readOnly) compilationOptions.platform.defaultCodeBank else "default")

  override def zeropage: Boolean = false

  override def elementCount: Int = contents.size

  override def sizeInBytes: Int = contents.size * elementType.size
}

case class RelativeVariable(name: String, address: Constant, typ: Type, zeropage: Boolean, declaredBank: Option[String], override val isVolatile: Boolean) extends VariableInMemory {
  override def toAddress: Constant = address
}

sealed trait MangledFunction extends CallableThing {
  def name: String

  def returnType: Type

  def params: ParamSignature

  def interrupt: Boolean

  def canBePointedTo: Boolean

  def requiresTrampoline(compilationOptions: CompilationOptions): Boolean = false
}

case class EmptyFunction(name: String,
                         returnType: Type,
                         paramType: Type) extends MangledFunction {
  override def params = EmptyFunctionParamSignature(paramType)

  override def interrupt = false

  override def canBePointedTo: Boolean = false
}

case class MacroFunction(name: String,
                         returnType: Type,
                         params: ParamSignature,
                         environment: Environment,
                         code: List[ExecutableStatement]) extends MangledFunction {
  override def interrupt = false

  override def canBePointedTo: Boolean = false
}

sealed trait FunctionInMemory extends MangledFunction with ThingInMemory {
  def environment: Environment

  override def isFar(compilationOptions: CompilationOptions): Boolean =
    compilationOptions.flag(CompilationFlag.LargeCode) && farFlag.getOrElse(true)

  override def bank(compilationOptions: CompilationOptions): String =
    declaredBank.getOrElse(compilationOptions.platform.defaultCodeBank)

  override def canBePointedTo: Boolean = !interrupt && returnType.size <= 2 && params.canBePointedTo && name !="call"

  override def requiresTrampoline(compilationOptions: CompilationOptions): Boolean = params.requireTrampoline(compilationOptions)
}

case class ExternFunction(name: String,
                          returnType: Type,
                          params: ParamSignature,
                          address: Constant,
                          environment: Environment,
                          declaredBank: Option[String]) extends FunctionInMemory {
  override def toAddress: Constant = address

  override def interrupt = false

  override def zeropage: Boolean = false

  override def isVolatile: Boolean = false
}

case class NormalFunction(name: String,
                          returnType: Type,
                          params: ParamSignature,
                          environment: Environment,
                          stackVariablesSize: Int,
                          address: Option[Constant],
                          code: List[ExecutableStatement],
                          hasElidedReturnVariable: Boolean,
                          interrupt: Boolean,
                          kernalInterrupt: Boolean,
                          reentrant: Boolean,
                          position: Option[Position],
                          declaredBank: Option[String],
                          override val alignment: MemoryAlignment) extends FunctionInMemory with PreallocableThing {
  override def shouldGenerate = true

  override def zeropage: Boolean = false

  override def isVolatile: Boolean = false
}

case class ConstantThing(name: String, value: Constant, typ: Type) extends TypedThing with VariableLikeThing with IndexableThing {
  def map(f: Constant => Constant) = ConstantThing("", f(value), typ)
}

case class StackOffsetThing(name: String, offset: Int, typ: Type, subbyte: Option[Int]) extends TypedThing with VariableLikeThing {

}

trait ParamSignature {
  def types: List[Type]

  def length: Int

  def canBePointedTo: Boolean

  def requireTrampoline(compilationOptions: CompilationOptions): Boolean

  def paramThingNames: Set[String]
}

case class NormalParamSignature(params: List[VariableInMemory]) extends ParamSignature {
  override def length: Int = params.length

  override def types: List[Type] = params.map(_.typ)

  def canBePointedTo: Boolean = params.size <= 1 && params.forall(_.typ.size.<=(2))

  def requireTrampoline(compilationOptions: CompilationOptions): Boolean = compilationOptions.platform.cpuFamily match {
    case CpuFamily.M6502 => params.exists(_.typ.size.>=(2))
    case _ => false
  }

  def paramThingNames: Set[String] = params.map(_.name).toSet

}

sealed trait ParamPassingConvention {
  def inInlinedOnly: Boolean

  def inNonInlinedOnly: Boolean
}

case class ByMosRegister(register: MosRegister.Value) extends ParamPassingConvention {
  override def inInlinedOnly = false

  override def inNonInlinedOnly = false
}

case class ByZRegister(register: ZRegister.Value) extends ParamPassingConvention {
  override def inInlinedOnly = false

  override def inNonInlinedOnly = false
}

case class ByM6809Register(register: M6809Register.Value) extends ParamPassingConvention {
  override def inInlinedOnly = false

  override def inNonInlinedOnly = false
}

case class ByVariable(name: String) extends ParamPassingConvention {
  override def inInlinedOnly = false

  override def inNonInlinedOnly = true
}

case class ByConstant(name: String) extends ParamPassingConvention {
  override def inInlinedOnly = true

  override def inNonInlinedOnly = false
}

case class ByReference(name: String) extends ParamPassingConvention {
  override def inInlinedOnly = true

  override def inNonInlinedOnly = false
}

object AssemblyParameterPassingBehaviour extends Enumeration {
  val Copy, ByReference, ByConstant = Value
}

case class AssemblyParam(typ: Type, variable: TypedThing, behaviour: AssemblyParameterPassingBehaviour.Value) {
  def canBePointedTo: Boolean = behaviour == AssemblyParameterPassingBehaviour.Copy && (variable match {
    case RegisterVariable(MosRegister.A | MosRegister.AX, _) => true
    case ZRegisterVariable(ZRegister.A | ZRegister.HL, _) => true
    case _ => false
  })
}


case class AssemblyParamSignature(params: List[AssemblyParam]) extends ParamSignature {
  override def length: Int = params.length

  override def types: List[Type] = params.map(_.typ)

  def canBePointedTo: Boolean = params.size <= 1 && params.forall(_.canBePointedTo)

  override def requireTrampoline(compilationOptions: CompilationOptions): Boolean =
    false // all pointable functions with this kind of signature by definition use the pure register-cased parameter passing convention

  def paramThingNames: Set[String] = params.map(_.variable.name).toSet
}

case class EmptyFunctionParamSignature(paramType: Type) extends ParamSignature {
  override def length: Int = 1

  override def types: List[Type] = List(paramType)

  def canBePointedTo: Boolean = false

  override def requireTrampoline(compilationOptions: CompilationOptions): Boolean = false

  def paramThingNames: Set[String] = Set.empty
}
