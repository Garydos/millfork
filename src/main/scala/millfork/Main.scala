package millfork

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.Locale

import millfork.assembly.m6809.opt.M6809OptimizationPresets
import millfork.assembly.mos.AssemblyLine
import millfork.assembly.mos.opt._
import millfork.assembly.z80.opt.Z80OptimizationPresets
import millfork.buildinfo.BuildInfo
import millfork.cli.{CliParser, CliStatus}
import millfork.compiler.LabelGenerator
import millfork.compiler.mos.MosCompiler
import millfork.env.Environment
import millfork.error.{ConsoleLogger, Logger}
import millfork.node.StandardCallGraph
import millfork.output._
import millfork.parser.{MSourceLoadingQueue, MosSourceLoadingQueue, ZSourceLoadingQueue}



object Main {


  def main(args: Array[String]): Unit = {
    val errorReporting = new ConsoleLogger
    implicit val __implicitLogger: Logger = errorReporting

    if (args.isEmpty) {
      errorReporting.info("For help, use --help")
    }

    val startTime = System.nanoTime()
    val (status, c0) = parser(errorReporting).parse(Context(errorReporting, Nil), args.toList)
    status match {
      case CliStatus.Quit => return
      case CliStatus.Failed =>
        errorReporting.fatalQuit("Invalid command line")
      case CliStatus.Ok => ()
    }
    errorReporting.assertNoErrors("Invalid command line")
    errorReporting.verbosity = c0.verbosity.getOrElse(0)
    if (c0.inputFileNames.isEmpty) {
      errorReporting.fatalQuit("No input files")
    }

    errorReporting.debug("millfork version " + BuildInfo.version)
    errorReporting.trace(s"Copyright (C) $copyrightYears  Karol Stasiak")
    errorReporting.trace("This program comes with ABSOLUTELY NO WARRANTY.")
    errorReporting.trace("This is free software, and you are welcome to redistribute it under certain conditions")
    errorReporting.trace("You should have received a copy of the GNU General Public License along with this program. If not, see https://www.gnu.org/licenses/")
    val c = fixMissingIncludePath(c0).filloutFlags()
    if (c.includePath.isEmpty) {
      errorReporting.warn("Failed to detect the default include directory, consider using the -I option")
    }

    val platform = Platform.lookupPlatformFile("." :: c.includePath, c.platform.getOrElse {
      errorReporting.info("No platform selected, defaulting to `c64`")
      "c64"
    })
    val options = CompilationOptions(platform, c.flags, c.outputFileName, c.zpRegisterSize.getOrElse(platform.zpRegisterSize), c.features, JobContext(errorReporting, new LabelGenerator))
    errorReporting.debug("Effective flags: ")
    options.flags.toSeq.sortBy(_._1).foreach{
      case (f, b) => errorReporting.debug(f"    $f%-30s : $b%s")
    }

    val output = c.outputFileName match {
      case Some(ofn) => ofn
      case None => c.inputFileNames match {
        case List(ifn) if ifn.endsWith(".mfk") =>
          new File(ifn.stripSuffix(".mfk")).getName
        case _ => "a"
      }
    }
    val assOutput = output + ".asm"
//    val prgOutputs = (platform.outputStyle match {
//      case OutputStyle.Single => List("default")
//      case OutputStyle.PerBank => platform.bankNumbers.keys.toList
//    }).map(bankName => bankName -> {
//      if (bankName == "default") {
//        if (output.endsWith(platform.fileExtension)) output else output + platform.fileExtension
//      } else {
//        s"${output.stripSuffix(platform.fileExtension)}.$bankName${platform.fileExtension}"
//      }
//    }).toMap

    val result: AssemblerOutput = CpuFamily.forType(platform.cpu) match {
      case CpuFamily.M6502 => assembleForMos(c, platform, options)
      case CpuFamily.I80 => assembleForI80(c, platform, options)
      case CpuFamily.I86 => assembleForI86(c, platform, options)
      case CpuFamily.M6809 => assembleForM6809(c, platform, options)
    }

    if (c.outputAssembly) {
      val path = Paths.get(assOutput)
      errorReporting.debug("Writing assembly to " + path.toAbsolutePath)
      Files.write(path, result.asm.mkString("\n").getBytes(StandardCharsets.UTF_8))
    }
    if (c.outputLabels) {
      def labelUnimportance(l: String): Int = {
        if (l.startsWith(".")) 8
        else if (l.startsWith("__")) 7
        else 0
      }
      val sortedLabels = result.labels.groupBy(_._2).values.map(_.minBy(a => labelUnimportance(a._1) -> a._1)).toSeq.sortBy(_._2)
      val format = c.outputLabelsFormatOverride.getOrElse(platform.outputLabelsFormat)
      val basename = if (format.addOutputExtension)  output + platform.fileExtension else output
      if (format.filePerBank) {
        sortedLabels.groupBy(_._2._1).foreach{ case (bank, labels) =>
          val labelOutput = basename + format.fileExtension(bank)
          val path = Paths.get(labelOutput)
          errorReporting.debug("Writing labels to " + path.toAbsolutePath)
          Files.write(path, labels.map(format).mkString("\n").getBytes(StandardCharsets.UTF_8))
        }
      } else {
        val labelOutput = basename + format.fileExtension(0)
        val path = Paths.get(labelOutput)
        errorReporting.debug("Writing labels to " + path.toAbsolutePath)
        Files.write(path, sortedLabels.map(format).mkString("\n").getBytes(StandardCharsets.UTF_8))
      }
    }
    val defaultPrgOutput = if (output.endsWith(platform.fileExtension)) output else output + platform.fileExtension
    result.code.foreach{
      case (bankName, code) =>
        val prgOutput = if (bankName == "default") {
          if (platform.generateGameBoyChecksums) {
            code(0x14d) = (0x0134 to 0x14c).map(code).map(_^0xff).sum.toByte
            val globalChecksum = code.map(_&0xff).sum
            code(0x14f) = globalChecksum.toByte
            code(0x14e) = globalChecksum.>>(8).toByte
          }
          defaultPrgOutput
        } else {
          s"${output.stripSuffix(platform.fileExtension)}.$bankName${platform.fileExtension}"
        }
        val path = Paths.get(prgOutput)
        errorReporting.debug("Writing output to " + path.toAbsolutePath)
        errorReporting.debug(s"Total output size: ${code.length} bytes")
        Files.write(path, code)
    }
    errorReporting.debug(s"Total time: ${Math.round((System.nanoTime() - startTime)/1e6)} ms")
    c.runFileName.foreach{ program =>
      val outputAbsolutePath = Paths.get(defaultPrgOutput).toAbsolutePath.toString
      val cmdline = program +: c.runParams :+ outputAbsolutePath
      errorReporting.debug(s"Running: ${cmdline.mkString(" ")}")
      new ProcessBuilder(cmdline.toArray: _*).directory(new File(program).getParentFile).start()
    }
    if (platform.generateBbcMicroInfFile) {
      val start = platform.codeAllocators("default").startAt
      val codeLength = result.code("default").length
      Files.write(Paths.get(defaultPrgOutput+".inf"),
        s"$defaultPrgOutput ${start.toHexString} ${start.toHexString} ${codeLength.toHexString}".getBytes(StandardCharsets.UTF_8))
    }
  }

  private def getDefaultIncludePath: Either[String, String] = {
    try {
      var where = getExecutablePath.getParentFile
      if ((where.getName == "scala-2.12" || where.getName == "scala-2.13") && where.getParentFile.getName == "target") {
        where = where.getParentFile.getParentFile
      }
      val dir = new File(where.getAbsolutePath + File.separatorChar + "include")
      if (dir.exists()) {
        Right(dir.getAbsolutePath)
      } else {
        Left(s"The ${dir.getAbsolutePath} directory doesn't exist")
      }
    } catch {
      case e: Exception => Left(e.toString)
    }
  }

  private def getExecutablePath: File = {
    try {
      new File(Class.forName("org.graalvm.nativeimage.ProcessProperties").getMethod("getExecutableName").invoke(null).asInstanceOf[String])
    } catch {
      case _: Exception => try {
        new File(getClass.getProtectionDomain.getCodeSource.getLocation.toURI)
      }
    }
  }

  private def getAllDefaultPlatforms: Seq[String] = {
    (getDefaultIncludePath match {
      case Left(_) => Seq(
        "c64", "c64_scpu", "c64_scpu16", "c64_crt8k", "c64_crt16k", "lunix",
        "vic20", "vic20_3k", "vic20_8k", "vic20_a000",
        "c16", "plus4", "pet", "c128",
        "a8", "bbcmicro", "apple2",
        "nes_mmc4", "nes_small", "atari_lynx", "vcs", "gb_small",
        "zxspectrum", "zxspectrum_8080", "pc88", "cpc464", "msx_crt",
        "cpm", "cpm_z80", "dos_com")
      case Right(path) =>
        Seq(new File(".").list(), new File(path).list())
          .filter(_ ne null)
          .flatMap(_.toSeq)
          .filter(_.endsWith(".ini"))
          .map(_.stripSuffix(".ini"))
    }).sorted
  }

  private def fixMissingIncludePath(c: Context)(implicit log: Logger): Context = {
    if (c.includePath.isEmpty) {
      getDefaultIncludePath match {
        case Left(err) =>
          log.debug(s"Failed to find the default include path: $err")
        case Right(path) =>
          log.debug(s"Automatically detected include path: $path")
          return c.copy(includePath = List(path) ++ c.extraIncludePath)
      }
    }
    c.copy(includePath = c.includePath ++ c.extraIncludePath)
  }

  private def assembleForMos(c: Context, platform: Platform, options: CompilationOptions): AssemblerOutput = {
    val optLevel = c.optimizationLevel.getOrElse(0)
    val unoptimized = new MosSourceLoadingQueue(
      initialFilenames = c.inputFileNames,
      includePath = c.includePath,
      options = options).run()

    val program = if (optLevel > 0) {
      OptimizationPresets.NodeOpt.foldLeft(unoptimized)((p, opt) => p.applyNodeOptimization(opt, options))
    } else {
      OptimizationPresets.NodeOpt0.foldLeft(unoptimized)((p, opt) => p.applyNodeOptimization(opt, options))
    }
    val callGraph = new StandardCallGraph(program, options.log)
    program.checkSegments(options.log, platform.codeAllocators.keySet)
    options.log.assertNoErrors("Build failed due to undefined segments")

    val env = new Environment(None, "", platform.cpuFamily, options)
    env.collectDeclarations(program, options)

    val assemblyOptimizations = optLevel match {
      case 0 => Nil
      case 1 => OptimizationPresets.QuickPreset
      case i if i >= 9 => List(SuperOptimizer)
      case _ =>
        val goodExtras = List(
          if (options.flag(CompilationFlag.EmitEmulation65816Opcodes)) SixteenOptimizations.AllForEmulation else Nil,
          if (options.flag(CompilationFlag.EmitNative65816Opcodes)) SixteenOptimizations.AllForNative else Nil,
          if (options.zpRegisterSize > 0) ZeropageRegisterOptimizations.All else Nil
        ).flatten
        val extras = List(
          if (options.flag(CompilationFlag.EmitIllegals)) UndocumentedOptimizations.All else Nil,
          if (options.flag(CompilationFlag.Emit65CE02Opcodes)) CE02Optimizations.All else Nil,
          if (options.flag(CompilationFlag.EmitCmosOpcodes)) CmosOptimizations.All else LaterOptimizations.Nmos,
          if (options.flag(CompilationFlag.EmitHudsonOpcodes)) HudsonOptimizations.All else Nil,
          if (options.flag(CompilationFlag.EmitEmulation65816Opcodes)) SixteenOptimizations.AllForEmulation else Nil,
          if (options.flag(CompilationFlag.EmitNative65816Opcodes)) SixteenOptimizations.AllForNative else Nil,
          if (options.flag(CompilationFlag.DangerousOptimizations)) DangerousOptimizations.All else Nil
        ).flatten
        val goodCycle = List.fill(optLevel - 2)(OptimizationPresets.Good ++ goodExtras).flatten
        val mainCycle = List.fill(optLevel - 1)(OptimizationPresets.AssOpt ++ extras).flatten
        goodCycle ++ mainCycle ++ goodCycle
    }

    // compile
    val assembler = new MosAssembler(program, env, platform)
    val result = assembler.assemble(callGraph, assemblyOptimizations, options)
    options.log.assertNoErrors("Codegen failed")
    options.log.debug(f"Unoptimized code size: ${assembler.unoptimizedCodeSize}%5d B")
    options.log.debug(f"Optimized code size:   ${assembler.optimizedCodeSize}%5d B")
    options.log.debug(f"Gain:                   ${(100L * (assembler.unoptimizedCodeSize - assembler.optimizedCodeSize) / assembler.unoptimizedCodeSize.toDouble).round}%5d%%")
    options.log.debug(f"Initialized variables: ${assembler.initializedVariablesSize}%5d B")
    result
  }

  private def assembleForI80(c: Context, platform: Platform, options: CompilationOptions): AssemblerOutput = {
    val optLevel = c.optimizationLevel.getOrElse(0)
    val unoptimized = new ZSourceLoadingQueue(
      initialFilenames = c.inputFileNames,
      includePath = c.includePath,
      options = options).run()

    val program = if (optLevel > 0) {
      OptimizationPresets.NodeOpt.foldLeft(unoptimized)((p, opt) => p.applyNodeOptimization(opt, options))
    } else {
      OptimizationPresets.NodeOpt0.foldLeft(unoptimized)((p, opt) => p.applyNodeOptimization(opt, options))
    }
    val callGraph = new StandardCallGraph(program, options.log)

    val env = new Environment(None, "", platform.cpuFamily, options)
    env.collectDeclarations(program, options)

    val assemblyOptimizations = optLevel match {
      case 0 => Nil
      case _ =>
        if (options.flag(CompilationFlag.EmitZ80Opcodes))
          Z80OptimizationPresets.GoodForZ80
        else if (options.flag(CompilationFlag.EmitIntel8080Opcodes))
          Z80OptimizationPresets.GoodForIntel8080
        else if (options.flag(CompilationFlag.EmitSharpOpcodes))
          Z80OptimizationPresets.GoodForSharp
        else Nil
    }

    // compile
    val assembler = new Z80Assembler(program, env, platform)
    val result = assembler.assemble(callGraph, assemblyOptimizations, options)
    options.log.assertNoErrors("Codegen failed")
    options.log.debug(f"Unoptimized code size: ${assembler.unoptimizedCodeSize}%5d B")
    options.log.debug(f"Optimized code size:   ${assembler.optimizedCodeSize}%5d B")
    options.log.debug(f"Gain:                   ${(100L * (assembler.unoptimizedCodeSize - assembler.optimizedCodeSize) / assembler.unoptimizedCodeSize.toDouble).round}%5d%%")
    options.log.debug(f"Initialized variables: ${assembler.initializedVariablesSize}%5d B")
    result
  }

  private def assembleForM6809(c: Context, platform: Platform, options: CompilationOptions): AssemblerOutput = {
    val optLevel = c.optimizationLevel.getOrElse(0)
    val unoptimized = new MSourceLoadingQueue(
      initialFilenames = c.inputFileNames,
      includePath = c.includePath,
      options = options).run()

    val program = if (optLevel > 0) {
      OptimizationPresets.NodeOpt.foldLeft(unoptimized)((p, opt) => p.applyNodeOptimization(opt, options))
    } else {
      OptimizationPresets.NodeOpt0.foldLeft(unoptimized)((p, opt) => p.applyNodeOptimization(opt, options))
    }
    val callGraph = new StandardCallGraph(program, options.log)

    val env = new Environment(None, "", platform.cpuFamily, options)
    env.collectDeclarations(program, options)

    val assemblyOptimizations = M6809OptimizationPresets.forLevel(optLevel)

    // compile
    val assembler = new M6809Assembler(program, env, platform)
    val result = assembler.assemble(callGraph, assemblyOptimizations, options)
    options.log.assertNoErrors("Codegen failed")
    options.log.debug(f"Unoptimized code size: ${assembler.unoptimizedCodeSize}%5d B")
    options.log.debug(f"Optimized code size:   ${assembler.optimizedCodeSize}%5d B")
    options.log.debug(f"Gain:                   ${(100L * (assembler.unoptimizedCodeSize - assembler.optimizedCodeSize) / assembler.unoptimizedCodeSize.toDouble).round}%5d%%")
    options.log.debug(f"Initialized variables: ${assembler.initializedVariablesSize}%5d B")
    result
  }

  private def assembleForI86(c: Context, platform: Platform, options: CompilationOptions): AssemblerOutput = {
    val optLevel = c.optimizationLevel.getOrElse(0)
    val unoptimized = new ZSourceLoadingQueue(
      initialFilenames = c.inputFileNames,
      includePath = c.includePath,
      options = options).run()

    val program = if (optLevel > 0) {
      OptimizationPresets.NodeOpt.foldLeft(unoptimized)((p, opt) => p.applyNodeOptimization(opt, options))
    } else {
      OptimizationPresets.NodeOpt0.foldLeft(unoptimized)((p, opt) => p.applyNodeOptimization(opt, options))
    }
    val callGraph = new StandardCallGraph(program, options.log)

    val env = new Environment(None, "", platform.cpuFamily, options)
    env.collectDeclarations(program, options)

    val assemblyOptimizations = optLevel match {
      case 0 => Nil
      case _ => Z80OptimizationPresets.GoodForIntel8080
    }

    // compile
    val assembler = new Z80ToX86Crossassembler(program, env, platform)
    val result = assembler.assemble(callGraph, assemblyOptimizations, options)
    options.log.assertNoErrors("Codegen failed")
    options.log.debug(f"Unoptimized code size: ${assembler.unoptimizedCodeSize}%5d B")
    options.log.debug(f"Optimized code size:   ${assembler.optimizedCodeSize}%5d B")
    options.log.debug(f"Gain:                   ${(100L * (assembler.unoptimizedCodeSize - assembler.optimizedCodeSize) / assembler.unoptimizedCodeSize.toDouble).round}%5d%%")
    options.log.debug(f"Initialized variables: ${assembler.initializedVariablesSize}%5d B")
    result
  }

  //noinspection NameBooleanParameters
  private def parser(errorReporting: Logger): CliParser[Context] = new CliParser[Context] {

    fluff("Main options:", "")

    parameter("-o", "--out").placeholder("<file>").action { (p, c) =>
      assertNone(c.outputFileName, "Output already defined")
      c.copy(outputFileName = Some(p))
    }.description("The output file name, without extension.")

    flag("-s").action { c =>
      c.copy(outputAssembly = true)
    }.description("Generate also the assembly output.")

    flag("-g").action { c =>
      c.copy(outputLabels = true)
    }.description("Generate also the label file in the default format.")

    parameter("-G").placeholder("<format>").action { (p, c) =>
      val f = DebugOutputFormat.map.getOrElse(
        p.toLowerCase(Locale.ROOT),
        errorReporting.fatal("Invalid label file format: " + p))
      c.copy(outputLabels = true, outputLabelsFormatOverride = Some(f))
    }.description("Generate also the label file in the given format. Available options: vice, nesasm, sym.")

    parameter("-t", "--target").placeholder("<platform>").action { (p, c) =>
      assertNone(c.platform, "Platform already defined")
      c.copy(platform = Some(p))
    }.description(s"Target platform, any of:\n${getAllDefaultPlatforms.grouped(10).map(_.mkString(", ")).mkString(",\n")}.")

    parameter("-I", "--include-dir").repeatable().placeholder("<dir>;<dir>;...").action { (paths, c) =>
      val n = paths.split(";")
      c.copy(includePath = c.includePath ++ n)
    }.description("Include paths for modules. If not given, the default path is used: " + getDefaultIncludePath.fold(identity, identity))

    parameter("-i", "--add-include-dir").repeatable().placeholder("<dir>").action { (path, c) =>
      c.copy(extraIncludePath = c.extraIncludePath :+ path)
    }.description("Add a directory to include paths.")

    parameter("-r", "--run").placeholder("<program>").action { (p, c) =>
      assertNone(c.runFileName, "Run program already defined")
      c.copy(runFileName = Some(p))
    }.description("Program to run after successful compilation.")

    parameter("-R", "--run-param").placeholder("<param>").action { (p, c) =>
      c.copy(runParams = c.runParams :+ p)
    }.description("Adds a commandline parameter to the program launched with -r")

    parameter("-D", "--define").placeholder("<feature>=<value>").action { (p, c) =>
      val tokens = p.split('=')
      if (tokens.length == 2) {
        import java.lang.Long.parseLong
        assertNone(c.features.get(tokens(0)), "Feature already defined")
        try {
          val parsed = if (tokens(1).startsWith("$")) {
            parseLong(tokens(1).tail, 16)
          } else if (tokens(1).startsWith("%")) {
            parseLong(tokens(1).tail, 2)
          } else if (tokens(1).endsWith("h") || tokens(1).endsWith("H")) {
            parseLong(tokens(1).init, 16)
          } else if (tokens(1).startsWith("0B") || tokens(1).startsWith("0b")) {
            parseLong(tokens(1).drop(2), 2)
          } else if (tokens(1).startsWith("0X") || tokens(1).startsWith("0x")) {
            parseLong(tokens(1).drop(2), 16)
          } else if (tokens(1).startsWith("0Q") || tokens(1).startsWith("0q")) {
            parseLong(tokens(1).drop(2), 4)
          } else if (tokens(1).startsWith("0O") || tokens(1).startsWith("0o")) {
            parseLong(tokens(1).drop(2), 8)
          } else {
            tokens(1).toLong
          }
          c.copy(features = c.features + (tokens(0) -> parsed))
        } catch {
          case _:java.lang.NumberFormatException =>
            errorReporting.fatal(s"Invalid number used in -D option: ${tokens(1)}")
        }
      } else {
        errorReporting.fatal("Invalid syntax for -D option")
      }
    }.description("Define a feature value for the preprocessor.")

    boolean("-finput_intel_syntax", "-finput_zilog_syntax").action((c,v) =>
      c.changeFlag(CompilationFlag.UseIntelSyntaxForInput, v)
    ).description("Select syntax for assembly source input.")

    boolean("-foutput_intel_syntax", "-foutput_zilog_syntax").action((c,v) =>
      c.changeFlag(CompilationFlag.UseIntelSyntaxForOutput, v)
    ).description("Select syntax for assembly output.")

    boolean("--syntax=intel", "--syntax=zilog").action((c,v) =>
      c.changeFlag(CompilationFlag.UseIntelSyntaxForInput, v).changeFlag(CompilationFlag.UseIntelSyntaxForOutput, v)
    ).description("Select syntax for assembly input and output.")

    boolean("-fline-numbers", "-fno-line-numbers").action((c,v) =>
      c.changeFlag(CompilationFlag.LineNumbersInAssembly, v)
    ).description("Show source line numbers in assembly.")

    boolean("-fsource-in-asm", "-fno-source-in-asm").action((c,v) =>
      if (v) {
        c.changeFlag(CompilationFlag.SourceInAssembly, true).changeFlag(CompilationFlag.LineNumbersInAssembly, true)
      } else {
        c.changeFlag(CompilationFlag.LineNumbersInAssembly, false)
      }
    ).description("Show source in assembly.")

    endOfFlags("--").description("Marks the end of options.")

    fluff("", "Verbosity options:", "")

    flag("-q", "--quiet").action { c =>
      assertNone(c.verbosity, "Cannot use -v and -q together")
      c.copy(verbosity = Some(-1))
    }.description("Supress all messages except for errors.")

    private val verbose = flag("-v", "--verbose").maxCount(3).action { c =>
      if (c.verbosity.exists(_ < 0)) errorReporting.error("Cannot use -v and -q together", None)
      c.copy(verbosity = Some(1 + c.verbosity.getOrElse(0)))
    }.description("Increase verbosity.")
    flag("-vv").repeatable().action(c => verbose.encounter(verbose.encounter(verbose.encounter(c)))).description("Increase verbosity even more.")
    flag("-vvv").repeatable().action(c => verbose.encounter(verbose.encounter(c))).description("Increase verbosity even more.")

    fluff("", "Code generation options:", "")

    boolean("-fcmos-ops", "-fno-cmos-ops").action { (c, v) =>
      c.changeFlag(CompilationFlag.EmitCmosOpcodes, v)
    }.description("Whether should emit CMOS opcodes.")
    boolean("-f65ce02-ops", "-fno-65ce02-ops").action { (c, v) =>
      c.changeFlag(CompilationFlag.Emit65CE02Opcodes, v)
    }.description("Whether should emit 65CE02 opcodes.")
    boolean("-fhuc6280-ops", "-fno-huc6280-ops").action { (c, v) =>
      c.changeFlag(CompilationFlag.EmitHudsonOpcodes, v)
    }.description("Whether should emit HuC6280 opcodes.")
    flag("-fno-65816-ops").action { c =>
      c.changeFlag(CompilationFlag.EmitEmulation65816Opcodes, b = false)
      c.changeFlag(CompilationFlag.EmitNative65816Opcodes, b = false)
      c.changeFlag(CompilationFlag.ReturnWordsViaAccumulator, b = false)
    }.description("Don't emit 65816 opcodes.")
    flag("-femulation-65816-ops").action { c =>
      c.changeFlag(CompilationFlag.EmitEmulation65816Opcodes, b = true)
      c.changeFlag(CompilationFlag.EmitNative65816Opcodes, b = false)
      c.changeFlag(CompilationFlag.ReturnWordsViaAccumulator, b = false)
    }.description("Emit 65816 opcodes in emulation mode (experimental).")
    flag("-fnative-65816-ops").action { c =>
      c.changeFlag(CompilationFlag.EmitEmulation65816Opcodes, b = true)
      c.changeFlag(CompilationFlag.EmitNative65816Opcodes, b = true)
    }.description("Emit 65816 opcodes in native mode (very experimental and buggy).")
    boolean("-flarge-code", "-fsmall-code").action { (c, v) =>
      c.changeFlag(CompilationFlag.LargeCode, v)
    }.description("Whether should use 24-bit or 16-bit jumps to subroutines (not yet implemented).").hidden()

    boolean("-f8085-ops", "-fno-8085-ops").action { (c, v) =>
      c.changeFlag(CompilationFlag.EmitIntel8085Opcodes, v)
    }.description("Whether should emit Intel 8085 opcodes.")
    boolean("-fz80-ops", "-fno-z80-ops").action { (c, v) =>
      c.changeFlag(CompilationFlag.EmitZ80Opcodes, v).changeFlag(CompilationFlag.EmitExtended80Opcodes, v)
    }.description("Whether should emit Z80 opcodes.")

    boolean("-fillegals", "-fno-illegals").action { (c, v) =>
      c.changeFlag(CompilationFlag.EmitIllegals, v)
    }.description("Whether should emit illegal (undocumented) opcodes. On 6502, requires -O2 or higher to have an effect.")
    flag("-fzp-register=[0-15]").description("Set the size of the zeropage pseudoregister (6502 only).").dummy()
    (0 to 15).foreach(i =>
      flag("-fzp-register="+i).action(c => c.copy(zpRegisterSize = Some(i))).hidden()
    )
    flag("-fzp-register").action { c =>
      c.copy(zpRegisterSize = Some(4))
    }.description("Alias for -fzp-register=4.")
    flag("-fno-zp-register").action { c =>
      c.copy(zpRegisterSize = Some(0))
    }.description("Alias for -fzp-register=0.")
    boolean("-fjmp-fix", "-fno-jmp-fix").action { (c, v) =>
      c.changeFlag(CompilationFlag.PreventJmpIndirectBug, v)
    }.description("Whether should prevent indirect JMP bug on page boundary (6502 only).")
    boolean("-fdecimal-mode", "-fno-decimal-mode").action { (c, v) =>
      c.changeFlag(CompilationFlag.DecimalMode, v)
    }.description("Whether hardware decimal mode should be used (6502 only).")
    boolean("-fvariable-overlap", "-fno-variable-overlap").action { (c, v) =>
      c.changeFlag(CompilationFlag.VariableOverlap, v)
    }.description("Whether variables should overlap if their scopes do not intersect. Enabled by default.")
    boolean("-fcompact-dispatch-params", "-fno-compact-dispatch-params").action { (c, v) =>
      c.changeFlag(CompilationFlag.CompactReturnDispatchParams, v)
    }.description("Whether parameter values in return dispatch statements may overlap other objects. Enabled by default.")
    boolean("-fbounds-checking", "-fno-bounds-checking").action { (c, v) =>
      c.changeFlag(CompilationFlag.VariableOverlap, v)
    }.description("Whether should insert bounds checking on array access.")
    boolean("-flenient-encoding", "-fno-lenient-encoding").action { (c, v) =>
      c.changeFlag(CompilationFlag.LenientTextEncoding, v)
    }.description("Whether the compiler should replace invalid characters in string literals that use the default encodings.")
    boolean("-fshadow-irq", "-fno-shadow-irq").action { (c, v) =>
      c.changeFlag(CompilationFlag.UseShadowRegistersForInterrupts, v)
    }.description("Whether shadow registers should be used in interrupt routines (Z80 only)")
    flag("-fuse-ix-for-stack").action { c =>
      c.changeFlag(CompilationFlag.UseIxForStack, true).changeFlag(CompilationFlag.UseIyForStack, false)
    }.description("Use IX as base pointer for stack variables (Z80 only)")
    flag("-fuse-iy-for-stack").action { c =>
      c.changeFlag(CompilationFlag.UseIyForStack, true).changeFlag(CompilationFlag.UseIxForStack, false)
    }.description("Use IY as base pointer for stack variables (Z80 only)")
    flag("-fuse-u-for-stack").action { c =>
      c.changeFlag(CompilationFlag.UseIxForStack, true).changeFlag(CompilationFlag.UseUForStack, false)
    }.description("Use U as base pointer for stack variables (6809 only)").hidden()
    flag("-fuse-y-for-stack").action { c =>
      c.changeFlag(CompilationFlag.UseIyForStack, true).changeFlag(CompilationFlag.UseYForStack, false)
    }.description("Use Y as base pointer for stack variables (6809 only)").hidden()
    boolean("-fuse-ix-for-scratch", "-fno-use-ix-for-scratch").action { (c, v) =>
      if (v) {
        c.changeFlag(CompilationFlag.UseIxForScratch, true).changeFlag(CompilationFlag.UseIxForStack, false)
      } else {
        c.changeFlag(CompilationFlag.UseIxForScratch, false)
      }
    }.description("Use IX as base pointer for stack variables (Z80 only)")
    boolean("-fuse-iy-for-scratch", "-fno-use-iy-for-scratch").action { (c, v) =>
      if (v) {
        c.changeFlag(CompilationFlag.UseIyForScratch, true).changeFlag(CompilationFlag.UseIyForStack, false)
      } else {
        c.changeFlag(CompilationFlag.UseIyForScratch, false)
      }
    }.description("Use IY as base pointer for stack variables (Z80 only)")
    flag("-fno-use-index-for-stack").action { c =>
      c.changeFlag(CompilationFlag.UseIyForStack, false).changeFlag(CompilationFlag.UseIxForStack, false)
    }.description("Don't use either IX or IY as base pointer for stack variables (Z80 only)")
    flag("-fno-use-uy-for-stack").action { c =>
      c.changeFlag(CompilationFlag.UseUForStack, false).changeFlag(CompilationFlag.UseYForStack, false)
    }.description("Don't use either U or Y as base pointer for stack variables (6809 only)").hidden()
    boolean("-fsoftware-stack", "-fno-software-stack").action { (c, v) =>
      c.changeFlag(CompilationFlag.SoftwareStack, v)
    }.description("Use software stack for stack variables (6502 only)")

    fluff("", "Optimization options:", "")


    flag("-O0").action { c =>
      assertNone(c.optimizationLevel, "Optimization level already defined")
      c.copy(optimizationLevel = Some(0))
    }.description("Disable all optimizations.")
    flag("-O").action { c =>
      assertNone(c.optimizationLevel, "Optimization level already defined")
      c.copy(optimizationLevel = Some(1))
    }.description("Optimize code.")
    for (i <- 1 to 9) {
      val f = flag("-O" + i).action { c =>
        assertNone(c.optimizationLevel, "Optimization level already defined")
        c.copy(optimizationLevel = Some(i))
      }.description("Optimize code even more.")
      if (i == 1 || i > 4) f.hidden()
    }
    flag("--inline").action { c =>
      c.changeFlag(CompilationFlag.InlineFunctions, true)
    }.description("Inline functions automatically.").hidden()
    boolean("-finline", "-fno-inline").action { (c, v) =>
      c.changeFlag(CompilationFlag.InlineFunctions, v)
    }.description("Inline functions automatically.")
    flag("--ipo").action { c =>
      c.changeFlag(CompilationFlag.InterproceduralOptimization, true)
    }.description("Interprocedural optimization.").hidden()
    boolean("--fipo", "--fno-ipo").action { (c, v) =>
      c.changeFlag(CompilationFlag.InterproceduralOptimization, v)
    }.description("Interprocedural optimization.").hidden()
    boolean("-fipo", "-fno-ipo").action { (c, v) =>
      c.changeFlag(CompilationFlag.InterproceduralOptimization, v)
    }.description("Interprocedural optimization.")
    boolean("-foptimize-stdlib", "-fno-optimize-stdlib").action { (c, v) =>
      c.changeFlag(CompilationFlag.OptimizeStdlib, v)
    }.description("Optimize standard library calls.")
    boolean("-fsubroutine-extraction", "-fno-subroutine-extraction").action { (c, v) =>
      c.changeFlag(CompilationFlag.SubroutineExtraction, v)
    }.description("Extract identical code fragments into subroutines.")
    boolean("-ffunction-fallthrough", "-fno-function-fallthrough").action { (c, v) =>
      c.changeFlag(CompilationFlag.FunctionFallthrough, v)
    }.description("Replace tail calls by simply putting one function after another. Enabled by default.")
    boolean("-ffunction-deduplication", "-fno-function-deduplication").action { (c, v) =>
      c.changeFlag(CompilationFlag.FunctionDeduplication, v)
    }.description("Merge identical functions into one function. Enabled by default.")
    boolean("-fregister-variables", "-fno-register-variables").action { (c, v) =>
      c.changeFlag(CompilationFlag.RegisterVariables, v)
    }.description("Allow moving local variables into CPU registers. Enabled by default.")
    flag("-Os", "--size").action { c =>
      c.changeFlag(CompilationFlag.OptimizeForSize, true).
        changeFlag(CompilationFlag.OptimizeForSpeed, false).
        changeFlag(CompilationFlag.OptimizeForSonicSpeed, false)
    }.description("Prefer smaller code even if it is slightly slower (experimental). Implies -fsubroutine-extraction.")
    flag("-Of", "--fast").action { c =>
      c.changeFlag(CompilationFlag.OptimizeForSize, false).
        changeFlag(CompilationFlag.OptimizeForSpeed, true).
        changeFlag(CompilationFlag.OptimizeForSonicSpeed, false)
    }.description("Prefer faster code even if it is slightly bigger (experimental). Implies -finline.")
    flag("-Ob", "--blast-processing").action { c =>
      c.changeFlag(CompilationFlag.OptimizeForSize, false).
        changeFlag(CompilationFlag.OptimizeForSpeed, true).
        changeFlag(CompilationFlag.OptimizeForSonicSpeed, true)
    }.description("Prefer faster code even if it is much bigger (experimental). Implies -finline.")
    flag("--dangerous-optimizations").action { c =>
      c.changeFlag(CompilationFlag.DangerousOptimizations, true)
    }.description("Use dangerous optimizations (experimental).").hidden()
    boolean("-fdangerous-optimizations", "-fno-dangerous-optimizations").action { (c, v) =>
      c.changeFlag(CompilationFlag.DangerousOptimizations, v)
    }.description("Use dangerous optimizations (experimental). Implies -fipo and -foptimize-stdlib.")
    flag("-Og", "--optimize-debugging").action { c =>
      c.changeFlag(CompilationFlag.OptimizeForDebugging, true)
    }.description("Disable optimizations that make debugging harder (experimental).")

    fluff("", "Warning options:", "")

    flag("-Wall", "--Wall").action { c =>
      CompilationFlag.allWarnings.foldLeft(c) { (c, f) => c.changeFlag(f, true) }
    }.description("Enable extra warnings.")

    flag("-Wfatal", "--Wfatal").action { c =>
      c.changeFlag(CompilationFlag.FatalWarnings, true)
    }.description("Treat warnings as errors.")

    fluff("", "Other options:", "")

    flag("--single-threaded").action(c =>
      c.changeFlag(CompilationFlag.SingleThreaded, true)
    ).description("Run the compiler in a single thread.")

    flag("--help").action(c => {
      println("millfork version " + BuildInfo.version)
      println(s"Copyright (C) $copyrightYears  Karol Stasiak")
      println("This program comes with ABSOLUTELY NO WARRANTY.")
      println("This is free software, and you are welcome to redistribute it under certain conditions")
      println("You should have received a copy of the GNU General Public License along with this program. If not, see https://www.gnu.org/licenses/")
      println()
      printHelp(20).foreach(println(_))
      assumeStatus(CliStatus.Quit)
      c
    }).description("Display this message.")

    flag("--version").action(c => {
      println("millfork version " + BuildInfo.version)
      assumeStatus(CliStatus.Quit)
      System.exit(0)
      c
    }).description("Print the version and quit.")


    default.action { (p, c) =>
      if (p.startsWith("-")) {
        errorReporting.error(s"Invalid option `$p`", None)
        c
      } else {
        c.copy(inputFileNames = c.inputFileNames :+ p)
      }
    }

    def assertNone[T](value: Option[T], msg: String): Unit = {
      if (value.isDefined) {
        errorReporting.error(msg, None)
      }
    }
  }
}
