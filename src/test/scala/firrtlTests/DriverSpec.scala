// See LICENSE for license details.

package firrtlTests

import java.io.{File, FileWriter}
import org.scalatest.{FreeSpec, Matchers}

import firrtl.passes.{InlineAnnotation, InlineInstances}
import firrtl.passes.memlib.{
  InferReadWrite,
  InferReadWriteAnnotation,
  ReplSeqMem,
  ReplSeqMemAnnotation
}
import firrtl.transforms.BlackBoxTargetDirAnno
import firrtl._
import firrtl.annotations._
import firrtl.util.BackendCompilationUtilities

class ExceptingTransform extends Transform {
  def inputForm = HighForm
  def outputForm = HighForm
  def execute(state: CircuitState): CircuitState = {
    throw new ExceptingTransform.CustomException("I ran!")
  }
}
object ExceptingTransform {
  case class CustomException(msg: String) extends Exception
}

//noinspection ScalaStyle
class DriverSpec extends FreeSpec with Matchers with BackendCompilationUtilities {
  "CommonOptions are some simple options available across the chisel3 ecosystem" - {
    "CommonOption provide an scopt implementation of an OptionParser" - {
      "Options can be set from an Array[String] as is passed into a main" - {
        "With no arguments default values come out" in {
          val optionsManager = new ExecutionOptionsManager("test")
          optionsManager.parse(Array.empty[String]) should be(true)

          val commonOptions = optionsManager.commonOptions
          commonOptions.topName should be("")
          commonOptions.targetDirName should be(".")
        }
        "top name and target can be set" in {
          val optionsManager = new ExecutionOptionsManager("test")
          optionsManager.parse(Array("--top-name", "dog", "--target-dir", "a/b/c")) should be(true)
          val commonOptions = optionsManager.commonOptions

          commonOptions.topName should be("dog")
          commonOptions.targetDirName should be("a/b/c")

          optionsManager.getBuildFileName(".fir") should be("a/b/c/dog.fir")
          optionsManager.getBuildFileName("fir") should be("a/b/c/dog.fir")
        }
      }
      "CommonOptions can create a directory" in {
        var dir = new java.io.File("a/b/c")
        if(dir.exists()) {
          dir.delete()
        }
        val optionsManager = new ExecutionOptionsManager("test")
        optionsManager.parse(Array("--top-name", "dog", "--target-dir", "a/b/c")) should be (true)
        val commonOptions = optionsManager.commonOptions

        commonOptions.topName should be ("dog")
        commonOptions.targetDirName should be ("a/b/c")

        optionsManager.makeTargetDir() should be (true)
        dir = new java.io.File("a/b/c")
        dir.exists() should be (true)
        FileUtils.deleteDirectoryHierarchy("a") should be (true)
      }
    }
    "options include by default a list of strings that are returned in commonOptions.programArgs" in {
      val optionsManager = new ExecutionOptionsManager("test")

      optionsManager.parse(Array("--top-name", "dog", "fox", "tardigrade", "stomatopod")) should be (true)
      println(s"programArgs ${optionsManager.commonOptions.programArgs}")
      optionsManager.commonOptions.programArgs.length should be (3)
      optionsManager.commonOptions.programArgs should be ("fox" :: "tardigrade" :: "stomatopod" :: Nil)

      optionsManager.commonOptions = CommonOptions()
      optionsManager.parse(
        Array("dog", "stomatopod")) should be (true)
      println(s"programArgs ${optionsManager.commonOptions.programArgs}")
      optionsManager.commonOptions.programArgs.length should be (2)
      optionsManager.commonOptions.programArgs should be ("dog" :: "stomatopod" :: Nil)

      optionsManager.commonOptions = CommonOptions()
      optionsManager.parse(
        Array("fox", "--top-name", "dog", "tardigrade", "stomatopod")) should be (true)
      println(s"programArgs ${optionsManager.commonOptions.programArgs}")
      optionsManager.commonOptions.programArgs.length should be (3)
      optionsManager.commonOptions.programArgs should be ("fox" :: "tardigrade" :: "stomatopod" :: Nil)

    }
  }
  "FirrtlOptions holds option information for the firrtl compiler" - {
    "It includes a CommonOptions" in {
      val optionsManager = new ExecutionOptionsManager("test")
      optionsManager.commonOptions.targetDirName should be (".")
    }
    "It provides input and output file names based on target" in {
      val optionsManager = new ExecutionOptionsManager("test") with HasFirrtlOptions

      optionsManager.parse(Array("--top-name", "cat")) should be (true)

      val firrtlOptions = optionsManager.firrtlOptions
      val inputFileName = optionsManager.getBuildFileName("fir", firrtlOptions.inputFileNameOverride)
      inputFileName should be ("./cat.fir")
      val outputFileName = firrtlOptions.getTargetFile(optionsManager)
      outputFileName should be ("./cat.v")
    }
    "input and output file names can be overridden, overrides do not use targetDir" in {
      val optionsManager = new ExecutionOptionsManager("test") with HasFirrtlOptions

      optionsManager.parse(
        Array("--top-name", "cat", "-i", "./bob.fir", "-o", "carol.v")
      ) should be (true)

      val firrtlOptions = optionsManager.firrtlOptions
      val inputFileName = optionsManager.getBuildFileName("fir", firrtlOptions.inputFileNameOverride)
      inputFileName should be ("./bob.fir")
      val outputFileName = firrtlOptions.getTargetFile(optionsManager)
      outputFileName should be ("carol.v")
    }
    "various annotations can be created from command line, currently:" - {
      "inline annotation" in {
        val optionsManager = new ExecutionOptionsManager("test") with HasFirrtlOptions

        optionsManager.parse(
          Array("--inline", "module,module.submodule,module.submodule.instance")
        ) should be (true)

        val firrtlOptions = optionsManager.firrtlOptions
        firrtlOptions.annotations.length should be (3)
        firrtlOptions.annotations.foreach(_ shouldBe an [InlineAnnotation])
      }
      "infer-rw annotation" in {
        val optionsManager = new ExecutionOptionsManager("test") with HasFirrtlOptions

        optionsManager.parse(
          Array("--infer-rw", "circuit")
        ) should be (true)

        val firrtlOptions = optionsManager.firrtlOptions
        firrtlOptions.annotations.length should be (1)
        firrtlOptions.annotations.head should be (InferReadWriteAnnotation)
      }
      "repl-seq-mem annotation" in {
        val optionsManager = new ExecutionOptionsManager("test") with HasFirrtlOptions

        optionsManager.parse(
          Array("--repl-seq-mem", "-c:circuit1:-i:infile1:-o:outfile1")
        ) should be (true)

        val firrtlOptions = optionsManager.firrtlOptions
        firrtlOptions.annotations.length should be (1)
        firrtlOptions.annotations.head should matchPattern {
          case ReplSeqMemAnnotation("infile1", "outfile1") =>
        }
      }
    }
  }

  // Deprecated
  "Annotations can be read implicitly from the name of the circuit" in {
    val top = "foo"
    val optionsManager = new ExecutionOptionsManager("test") with HasFirrtlOptions {
      commonOptions = commonOptions.copy(topName = top)
    }
    val annoFile =  new File(optionsManager.commonOptions.targetDirName, top + ".anno")
    copyResourceToFile("/annotations/SampleAnnotations.anno", annoFile)
    optionsManager.firrtlOptions.annotations.length should be (0)
    val annos = Driver.getAnnotations(optionsManager)
    annos.length should be (12) // 9 from circuit plus 3 general purpose
    annos.count(_.isInstanceOf[InlineAnnotation]) should be (9)
    annoFile.delete()
  }

  // Deprecated
  "Annotations can be read using annotationFileNameOverride" in {
    val optionsManager = new ExecutionOptionsManager("test") with HasFirrtlOptions {
      commonOptions = commonOptions.copy(topName = "a.fir")
      firrtlOptions = firrtlOptions.copy(
        annotationFileNameOverride = "SampleAnnotations"
      )
    }
    val annotationsTestFile =  new File(optionsManager.commonOptions.targetDirName, optionsManager.firrtlOptions.annotationFileNameOverride + ".anno")
    copyResourceToFile("/annotations/SampleAnnotations.anno", annotationsTestFile)
    optionsManager.firrtlOptions.annotations.length should be (0)
    val annos = Driver.getAnnotations(optionsManager)
    annos.length should be (12) // 9 from circuit plus 3 general purpose
    annos.count(_.isInstanceOf[InlineAnnotation]) should be (9)
    annotationsTestFile.delete()
  }

  // Deprecated
  "Supported LegacyAnnotations will be converted automagically" in {
    val testDir = createTestDirectory("test")
    val annoFilename = "LegacyAnnotations.anno"
    val annotationsTestFile =  new File(testDir, annoFilename)
    val optionsManager = new ExecutionOptionsManager("test") with HasFirrtlOptions {
      commonOptions = commonOptions.copy(topName = "test", targetDirName = testDir.toString)
      firrtlOptions = firrtlOptions.copy(
        annotationFileNames = List(annotationsTestFile.toString)
      )
    }
    copyResourceToFile(s"/annotations/$annoFilename", annotationsTestFile)
    val annos = Driver.getAnnotations(optionsManager)

    val cname = CircuitName("foo")
    val mname = ModuleName("bar", cname)
    val compname = ComponentName("x", mname)
    import firrtl.passes.clocklist._
    import firrtl.passes.memlib._
    import firrtl.passes.wiring._
    import firrtl.transforms._
    val expected = List(
      InlineAnnotation(cname),
      ClockListAnnotation(mname, "output"),
      InferReadWriteAnnotation,
      ReplSeqMemAnnotation("input", "output"),
      NoDedupMemAnnotation(compname),
      NoDedupAnnotation(mname),
      SourceAnnotation(compname, "pin"),
      SinkAnnotation(compname, "pin"),
      BlackBoxResourceAnno(mname, "resource"),
      BlackBoxInlineAnno(mname, "name", "text"),
      BlackBoxTargetDirAnno("targetdir"),
      NoDCEAnnotation,
      DontTouchAnnotation(compname),
      OptimizableExtModuleAnnotation(mname)
    )
    for (e <- expected) {
      annos should contain (e)
    }
  }

  // Deprecated
  "UNsupported LegacyAnnotations should throw errors" in {
    val testDir = createTestDirectory("test")
    val annoFilename = "InvalidLegacyAnnotations.anno"
    val annotationsTestFile =  new File(testDir, annoFilename)
    copyResourceToFile(s"/annotations/$annoFilename", annotationsTestFile)

    import net.jcazevedo.moultingyaml._
    val text = io.Source.fromFile(annotationsTestFile).mkString
    val yamlAnnos = text.parseYaml match { case YamlArray(xs) => xs }

    // Since each one should error, emit each one to an anno file and try to read it
    for ((anno, i) <- yamlAnnos.zipWithIndex) {
      val annoFile = new File(testDir, s"anno_$i.anno")
      val fw = new FileWriter(annoFile)
      fw.write(YamlArray(anno).prettyPrint)
      fw.close()
      val optionsManager = new ExecutionOptionsManager("test") with HasFirrtlOptions {
        commonOptions = commonOptions.copy(topName = "test", targetDirName = testDir.toString)
        firrtlOptions = firrtlOptions.copy(
          annotationFileNames = List(annoFile.toString)
        )
      }
      (the [Exception] thrownBy {
        Driver.getAnnotations(optionsManager)
      }).getMessage should include ("Old-style annotations")
    }
  }

  "Annotations can be read from multiple files" in {
    val filename = "SampleAnnotations.anno.json"
    val optionsManager = new ExecutionOptionsManager("test") with HasFirrtlOptions {
      commonOptions = commonOptions.copy(topName = "a.fir")
      firrtlOptions = firrtlOptions.copy(
        annotationFileNames = List.fill(2)(filename) // just read the safe file twice
      )
    }
    val annotationsTestFile = new File(optionsManager.commonOptions.targetDirName, filename)
    copyResourceToFile(s"/annotations/$filename", annotationsTestFile)
    optionsManager.firrtlOptions.annotations.length should be (0)
    val annos = Driver.getAnnotations(optionsManager)
    annos.length should be (21) // 18 from files plus 3 general purpose
    annos.count(_.isInstanceOf[InlineAnnotation]) should be (18)
    annotationsTestFile.delete()
  }

  "Annotations can be created from the command line and read from a file at the same time" in {
    val optionsManager = new ExecutionOptionsManager("test") with HasFirrtlOptions
    val targetDir = new File(optionsManager.commonOptions.targetDirName)
    val annoFile = new File(targetDir, "annotations.anno")

    optionsManager.parse(
      Array("--infer-rw", "circuit", "-faf", annoFile.toString)
    ) should be (true)

    copyResourceToFile("/annotations/SampleAnnotations.anno.json", annoFile)

    val firrtlOptions = optionsManager.firrtlOptions
    firrtlOptions.annotations.length should be (1) // infer-rw

    val anns = Driver.getAnnotations(optionsManager)
    anns should contain (BlackBoxTargetDirAnno(".")) // built in to getAnnotations
    anns should contain (InferReadWriteAnnotation)  // --infer-rw
    anns.collect { case a: InlineAnnotation => a }.length should be (9)  // annotations file

    annoFile.delete()
  }

  "Circuits are emitted on properly" - {
    val input =
      """|circuit Top :
         |  module Top :
         |    output foo : UInt<32>
         |    inst c of Child
         |    inst e of External
         |    foo <= tail(add(c.foo, e.foo), 1)
         |  module Child :
         |    output foo : UInt<32>
         |    inst e of External
         |    foo <= e.foo
         |  extmodule External :
         |    output foo : UInt<32>
      """.stripMargin

    "To a single file with file extension depending on the compiler by default" in {
      Seq(
        "low" -> "./Top.lo.fir",
        "high" -> "./Top.hi.fir",
        "middle" -> "./Top.mid.fir",
        "verilog" -> "./Top.v"
      ).foreach { case (compilerName, expectedOutputFileName) =>
        val manager = new ExecutionOptionsManager("test") with HasFirrtlOptions {
          commonOptions = CommonOptions(topName = "Top")
          firrtlOptions = FirrtlExecutionOptions(firrtlSource = Some(input), compilerName = compilerName)
        }

        firrtl.Driver.execute(manager)

        val file = new File(expectedOutputFileName)
        file.exists() should be (true)
        file.delete()
      }
    }
    "To a single file per module if OneFilePerModule is specified" in {
      Seq(
        "low" -> Seq("./Top.lo.fir", "./Child.lo.fir"),
        "high" -> Seq("./Top.hi.fir", "./Child.hi.fir"),
        "middle" -> Seq("./Top.mid.fir", "./Child.mid.fir"),
        "verilog" -> Seq("./Top.v", "./Child.v")
      ).foreach { case (compilerName, expectedOutputFileNames) =>
        println(s"$compilerName -> $expectedOutputFileNames")
        val manager = new ExecutionOptionsManager("test") with HasFirrtlOptions {
          commonOptions = CommonOptions(topName = "Top")
          firrtlOptions = FirrtlExecutionOptions(firrtlSource = Some(input),
                                                 compilerName = compilerName,
                                                 emitOneFilePerModule = true)
        }

        firrtl.Driver.execute(manager) match {
          case success: FirrtlExecutionSuccess =>
            success.circuitState.annotations.length should be > (0)
          case _ =>

        }

        for (name <- expectedOutputFileNames) {
          val file = new File(name)
          file.exists() should be (true)
          file.delete()
        }
      }
    }
  }


  "Directory deleter is handy for cleaning up after tests" - {
    "for example making a directory tree, and deleting it looks like" in {
      FileUtils.makeDirectory("dog/fox/wolf")
      val dir = new File("dog/fox/wolf")
      dir.exists() should be (true)
      dir.isDirectory should be (true)

      FileUtils.deleteDirectoryHierarchy("wolf") should be (false)
      FileUtils.deleteDirectoryHierarchy("dog") should be (true)
      dir.exists() should be (false)
    }
  }
}
