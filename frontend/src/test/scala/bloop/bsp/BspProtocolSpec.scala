package bloop.bsp

import java.nio.file.Files

import bloop.cli.validation.Validate
import bloop.cli.{BspProtocol, CliOptions, Commands}
import bloop.engine.{BuildLoader, Run}
import bloop.io.AbsolutePath
import bloop.tasks.TestUtil
import bloop.logging.{BspClientLogger, RecordingLogger}
import org.junit.Test
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.{ScalaBuildTarget, endpoints}
import junit.framework.Assert
import monix.eval.Task

import scala.meta.jsonrpc.{LanguageClient, Response, Services}
import scala.util.control.NonFatal

class BspProtocolSpec {
  private final val configDir = AbsolutePath(TestUtil.getBloopConfigDir("utest"))
  private final val cwd = AbsolutePath(TestUtil.getBaseFromConfigDir(configDir.underlying))
  private final val tempDir = Files.createTempDirectory("temp-sockets")
  tempDir.toFile.deleteOnExit()

  def validateBsp(bspCommand: Commands.Bsp): Commands.ValidatedBsp = {
    Validate.bsp(bspCommand, BspServer.isWindows) match {
      case Run(bsp: Commands.ValidatedBsp, _) => BspClientTest.setupBspCommand(bsp, cwd, configDir)
      case failed => sys.error(s"Command validation failed: ${failed}")
    }
  }

  def createLocalBspCommand(configDir: AbsolutePath): Commands.ValidatedBsp = {
    val uniqueId = java.util.UUID.randomUUID().toString.take(4)
    val socketFile = tempDir.resolve(s"test-$uniqueId.socket")
    validateBsp(
      Commands.Bsp(
        protocol = BspProtocol.Local,
        socket = Some(socketFile),
        pipeName = Some(s"\\\\.\\pipe\\test-$uniqueId")
      )
    )
  }

  def createTcpBspCommand(
      configDir: AbsolutePath,
      verbose: Boolean = false
  ): Commands.ValidatedBsp = {
    val opts = if (verbose) CliOptions.default.copy(verbose = true) else CliOptions.default
    validateBsp(Commands.Bsp(protocol = BspProtocol.Tcp, cliOptions = opts))
  }

  def reportIfError(logger: BspClientLogger[RecordingLogger])(thunk: => Unit): Unit = {
    try thunk
    catch {
      case NonFatal(t) =>
        val logs = logger.underlying.getMessages().map(t => s"${t._1}: ${t._2}")
        val errorMsg = s"BSP test failed with the following logs:\n${logs.mkString("\n")}"
        System.err.println(errorMsg)
        throw t
    }
  }

  def testInitialization(cmd: Commands.ValidatedBsp): Unit = {
    val logger = new BspClientLogger(new RecordingLogger)
    // We test the initialization several times to make sure the scheduler doesn't get blocked.
    def test(counter: Int): Unit = {
      if (counter == 0) ()
      else {
        BspClientTest.runTest(cmd, configDir, logger)(c => monix.eval.Task.eval(Right(())))
        test(counter - 1)
      }
    }

    reportIfError(logger) {
      test(10)
      val CompleteHandshake = "BSP initialization handshake complete."
      val BuildInitialize = "\"method\" : \"build/initialize\""
      val BuildInitialized = "\"method\" : \"build/initialized\""
      val msgs = logger.underlying.getMessages.map(_._2)
      Assert.assertEquals(msgs.count(_.contains(BuildInitialize)), 10)
      Assert.assertEquals(msgs.count(_.contains(BuildInitialized)), 10)
      Assert.assertEquals(msgs.count(_.contains(CompleteHandshake)), 10)
    }
  }

  def testBuildTargets(bspCmd: Commands.ValidatedBsp): Unit = {
    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).map {
        case Right(workspaceTargets) =>
          workspaceTargets.targets.map { t =>
            Assert.assertEquals(t.languageIds.sorted, List("java", "scala"))
            t.data.foreach { json =>
              ScalaBuildTarget.decodeScalaBuildTarget(json.hcursor) match {
                case Right(target) =>
                  Assert.assertTrue(
                    s"Scala bin version ${target.scalaBinaryVersion} == Scala version ${target.scalaVersion}",
                    target.scalaBinaryVersion != target.scalaVersion
                  )
                case Left(failure) =>
                  sys.error(s"Decoding `${json}` as a scala build target failed: ${failure}")
              }
            }
          }
          Right(Assert.assertEquals(workspaceTargets.targets.size, 8))
        case Left(error) => Left(error)
      }
    }

    BspClientTest.runTest(bspCmd, configDir, logger)(c => clientWork(c))

    reportIfError(logger) {
      val BuildInitialize = "\"method\" : \"build/initialize\""
      val BuildInitialized = "\"method\" : \"build/initialized\""
      val BuildTargets = "\"method\" : \"workspace/buildTargets\""
      val msgs = logger.underlying.getMessages.map(_._2)
      Assert.assertEquals(msgs.count(_.contains(BuildInitialize)), 1)
      Assert.assertEquals(msgs.count(_.contains(BuildInitialized)), 1)
      Assert.assertEquals(msgs.count(_.contains(BuildTargets)), 1)
    }
  }

  def testDependencySources(bspCmd: Commands.ValidatedBsp): Unit = {
    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap {
        case Left(error) => Task.now(Left(error))
        case Right(workspaceTargets) =>
          val btis = workspaceTargets.targets.map(_.id)
          endpoints.BuildTarget.dependencySources.request(bsp.DependencySourcesParams(btis)).map {
            case Left(error) => Left(error)
            case Right(sources) =>
              val fetchedSources = sources.items.flatMap(i => i.sources.map(_.value))
              val expectedSources = BuildLoader
                .loadSynchronously(configDir, logger.underlying)
                .flatMap(_.sources.map(s => bsp.Uri(s.underlying.toUri).value))
              val msg = s"Expected != Fetched, $expectedSources != $fetchedSources"
              val same = expectedSources.sorted.sameElements(fetchedSources.sorted)
              Right(Assert.assertTrue(msg, same))
          }
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger)(c => clientWork(c))
      ()
    }
  }

  def testScalacOptions(bspCmd: Commands.ValidatedBsp): Unit = {
    def stringify(xs: Seq[String]) = xs.sorted.mkString(";")
    def stringifyOptions(
        scalacOptions0: Seq[String],
        classpath0: Seq[bsp.Uri],
        classesDir: bsp.Uri
    ): String = {
      val scalacOptions = stringify(scalacOptions0)
      val classpath = stringify(classpath0.map(_.value))
      s"""StringifiedScalacOption($scalacOptions, $classpath, ${classesDir.value})"""
    }

    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap {
        case Left(error) => Task.now(Left(error))
        case Right(workspaceTargets) =>
          val btis = workspaceTargets.targets.map(_.id)
          endpoints.BuildTarget.scalacOptions.request(bsp.ScalacOptionsParams(btis)).map {
            case Left(error) => Left(error)
            case Right(options) =>
              val uriOptions = options.items.map(i => (i.target.uri.value, i)).sortBy(_._1)
              val expectedUriOptions = BuildLoader
                .loadSynchronously(configDir, logger.underlying)
                .map(p => (p.bspUri.value, p))
                .sortBy(_._1)

              Assert
                .assertEquals("Size of options differ", uriOptions.size, expectedUriOptions.size)
              uriOptions.zip(expectedUriOptions).foreach {
                case ((obtainedUri, opts), (expectedUri, p)) =>
                  Assert.assertEquals(obtainedUri, expectedUri)
                  val obtainedOptions =
                    stringifyOptions(opts.options, opts.classpath, opts.classDirectory)
                  val classpath = p.classpath.iterator.map(i => bsp.Uri(i.toBspUri)).toList
                  val classesDir = bsp.Uri(p.classesDir.toBspUri)
                  val expectedOptions =
                    stringifyOptions(p.scalacOptions.toList, classpath, classesDir)
                  Assert.assertEquals(obtainedOptions, expectedOptions)
              }

              Right(uriOptions)
          }
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger)(c => clientWork(c))
      ()
    }
  }

  def testCompile(bspCmd: Commands.ValidatedBsp): Unit = {
    var tested: Boolean = false
    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap { ts =>
        ts match {
          case Right(workspaceTargets) =>
            workspaceTargets.targets.map(_.id).find(_.uri.value.endsWith("utestJVM")) match {
              case Some(id) =>
                endpoints.BuildTarget.compile.request(bsp.CompileParams(List(id), None, Nil)).map {
                  case Left(e) => Left(e)
                  case Right(report) =>
                    if (tested) Right(report)
                    else Left(Response.internalError("The test didn't receive any compile report."))
                }
              case None => Task.now(Left(Response.internalError("Missing 'utestJVM'")))
            }
          case Left(error) =>
            Task.now(Left(Response.internalError(s"Target request failed with $error.")))
        }
      }
    }

    val addServicesTest = { (s: Services) =>
      s.notification(endpoints.BuildTarget.compileReport) { report =>
        if (tested) throw new AssertionError("Bloop compiled more than one target")
        if (report.target.uri.value.endsWith("utestJVM")) {
          tested = true
          Assert.assertEquals("Warnings in utestJVM != 4", 4, report.warnings)
          Assert.assertEquals("Errors in utestJVM != 0", 0, report.errors)
          //Assert.assertTrue("Duration in utestJVM == 0", report.time != 0)
        }
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger, addServicesTest)(c => clientWork(c))
      // Make sure that the compilation is logged back to the client via logs in stdout
      val msgs = logger.underlying.getMessages.iterator.filter(_._1 == "info").map(_._2).toList
      Assert.assertTrue("End of compilation is not reported.", msgs.contains("Done compiling."))
    }
  }

  def testTest(bspCmd: Commands.ValidatedBsp): Unit = {
    var checkCompiledUtest: Boolean = false
    var checkCompiledUtestTest: Boolean = false
    var checkTestedTargets: Boolean = false
    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap { ts =>
        ts match {
          case Right(workspaceTargets) =>
            workspaceTargets.targets.map(_.id).find(_.uri.value.endsWith("utestJVM-test")) match {
              case Some(id) =>
                endpoints.BuildTarget.test.request(bsp.TestParams(List(id), None, Nil)).map {
                  case Left(e) => Left(e)
                  case Right(report) =>
                    val valid = checkCompiledUtest && checkCompiledUtestTest && checkTestedTargets
                    if (valid) Right(report)
                    else Left(Response.internalError("Didn't receive all compile or test reports."))
                }
              case None => Task.now(Left(Response.internalError("Missing 'utestJVM-test'")))
            }
          case Left(error) =>
            Task.now(Left(Response.internalError(s"Target request failed testing with $error.")))
        }
      }
    }

    val addServicesTest = { (s: Services) =>
      s.notification(endpoints.BuildTarget.compileReport) { report =>
          if (checkCompiledUtest && checkCompiledUtestTest)
            throw new AssertionError(s"Bloop compiled unexpected target: ${report}")
          val uri = report.target.uri.value
          if (uri.endsWith("utestJVM")) {
            checkCompiledUtest = true
            Assert.assertEquals("Warnings in utestJVM != 4", 4, report.warnings)
            Assert.assertEquals("Errors in utestJVM != 0", 0, report.errors)
          } else if (uri.endsWith("utestJVM-test")) {
            checkCompiledUtestTest = true
            Assert.assertEquals("Warnings in utestJVM != 5", 5, report.warnings)
            Assert.assertEquals("Errors in utestJVM-test != 0", 0, report.errors)
          } else ()
        }
        .notification(endpoints.BuildTarget.testReport) { report =>
          if (checkTestedTargets)
            throw new AssertionError(s"Bloop unexpected only one test report, received: ${report}")
          val uri = report.target.uri.value
          if (uri.endsWith("utestJVM-test")) {
            checkTestedTargets = true
            Assert.assertEquals("Successful tests != 115", 115, report.passed)
            Assert.assertEquals(s"Failed tests ${report.failed}", 0, report.failed)
          }
        }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger, addServicesTest)(c => clientWork(c))
      // Make sure that the compilation is logged back to the client via logs in stdout
      val msgs = logger.underlying.getMessages.iterator.filter(_._1 == "info").map(_._2).toList
      Assert.assertTrue(
        "Test execution did not compile utestJVM and utestJVM-test.",
        msgs.filter(_.contains("Done compiling.")).size == 2
      )
    }
  }

  def testRun(bspCmd: Commands.ValidatedBsp): Unit = {
    val project = "utestJVM-test"
    var checkCompiledUtest: Boolean = false
    var checkCompiledUtestTest: Boolean = false
    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap { ts =>
        ts match {
          case Right(workspaceTargets) =>
            workspaceTargets.targets.map(_.id).find(_.uri.value.endsWith(project)) match {
              case Some(id) =>
                endpoints.BuildTarget.run.request(bsp.RunParams(id, None, Nil)).map {
                  case Left(e) => Left(e)
                  case Right(result) =>
                    if (checkCompiledUtest && checkCompiledUtestTest) {
                      result.statusCode match {
                        case bsp.StatusCode.Ok => Right(result)
                        case bsp.StatusCode.Error =>
                          Left(Response.internalError("Status code of run is an error!"))
                        case bsp.StatusCode.Cancelled =>
                          Left(Response.internalError("Status code of cancelled is an error!"))
                      }
                    } else {
                      Left(Response.internalError("The test didn't receive any compile report."))
                    }
                }
              case None => Task.now(Left(Response.internalError(s"Missing '$project'")))
            }
          case Left(error) =>
            Task.now(Left(Response.internalError(s"Target request failed testing with $error.")))
        }
      }
    }

    val addServicesTest = { (s: Services) =>
      s.notification(endpoints.BuildTarget.compileReport) { report =>
        if (checkCompiledUtest && checkCompiledUtestTest)
          throw new AssertionError(s"Bloop compiled unexpected target: ${report}")
        val uri = report.target.uri.value
        if (uri.endsWith("utestJVM")) {
          checkCompiledUtest = true
          Assert.assertEquals("Warnings in utestJVM != 4", 4, report.warnings)
          Assert.assertEquals("Errors in utestJVM != 0", 0, report.errors)
        } else if (uri.endsWith("utestJVM-test")) {
          checkCompiledUtestTest = true
          Assert.assertEquals("Warnings in utestJVM != 5", 5, report.warnings)
          Assert.assertEquals("Errors in utestJVM-test != 0", 0, report.errors)
        } else ()
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger, addServicesTest)(c => clientWork(c))
      // Make sure that the compilation is logged back to the client via logs in stdout
      val msgs = logger.underlying.getMessages.iterator.filter(_._1 == "info").map(_._2).toList
      Assert.assertTrue(
        s"Run execution did not compile $project.",
        msgs.filter(_.contains("Done compiling.")).size == 2
      )
    }
  }

  type BspResponse[T] = Task[Either[Response.Error, T]]
  def testFailedCompile(bspCmd: Commands.ValidatedBsp): Unit = {
    val logger = new BspClientLogger(new RecordingLogger)
    def expectError(request: BspResponse[bsp.CompileResult], expected: String, failMsg: String) = {
      request.flatMap {
        case Right(report) =>
          Task.now(Left(Response.parseError(s"Expecting failed compilation, received $report.")))
        case Left(e) =>
          val validError = e.error.message.contains(expected)
          if (validError) Task.now(Right(()))
          else Task.now(Left(Response.parseError(failMsg)))
      }
    }

    def clientWork(implicit client: LanguageClient) = {
      def compileParams(xs: List[bsp.BuildTargetIdentifier]): bsp.CompileParams =
        bsp.CompileParams(xs, None, Nil)
      val expected1 = "URI 'file://this-doesnt-exist' has invalid format."
      val fail1 = "The invalid format error was missed in 'this-doesnt-exist'"
      val f = new java.net.URI("file://thisdoesntexist")
      val params1 = compileParams(List(bsp.BuildTargetIdentifier(bsp.Uri(f))))

      val expected3 = "Empty build targets. Expected at least one build target identifier."
      val fail3 = "No error was thrown on empty build targets."
      val params3 = compileParams(List())

      val extraErrors = List((expected3, fail3, params3))
      val init = expectError(endpoints.BuildTarget.compile.request(params1), expected1, fail1)
      extraErrors.foldLeft(init) {
        case (acc, (expected, fail, params)) =>
          acc.flatMap {
            case Left(l) => Task.now(Left(l))
            case Right(_) =>
              expectError(endpoints.BuildTarget.compile.request(params), expected, fail)
          }
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger, allowError = true)(c => clientWork(c))
    }
  }

  @Test def TestInitializationViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testInitialization(createLocalBspCommand(configDir))
  }

  @Test def TestInitializationViaTcp(): Unit = {
    testInitialization(createTcpBspCommand(configDir))
  }

  @Test def TestBuildTargetsViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testBuildTargets(createLocalBspCommand(configDir))
  }

  @Test def TestBuildTargetsViaTcp(): Unit = {
    testBuildTargets(createTcpBspCommand(configDir))
  }

  @Test def TestDependencySourcesViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testDependencySources(createLocalBspCommand(configDir))
  }

  @Test def TestDependencySourcesViaTcp(): Unit = {
    testDependencySources(createTcpBspCommand(configDir))
  }

  @Test def TestScalacOptionsViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testScalacOptions(createLocalBspCommand(configDir))
  }

  @Test def TestScalacOptionsViaTcp(): Unit = {
    testScalacOptions(createTcpBspCommand(configDir))
  }

  @Test def TestCompileViaLocal(): Unit = {
    if (!BspServer.isWindows) testCompile(createLocalBspCommand(configDir))
  }

  @Test def TestCompileViaTcp(): Unit = {
    testCompile(createTcpBspCommand(configDir, verbose = true))
  }

  @Test def TestTestViaLocal(): Unit = {
    if (!BspServer.isWindows) testTest(createLocalBspCommand(configDir))
  }

  @Test def TestTestViaTcp(): Unit = {
    testTest(createTcpBspCommand(configDir, verbose = true))
  }

  @Test def TestRunViaLocal(): Unit = {
    if (!BspServer.isWindows) testRun(createLocalBspCommand(configDir))
  }

  @Test def TestRunViaTcp(): Unit = {
    testRun(createTcpBspCommand(configDir, verbose = true))
  }

  @Test def TestFailedCompileViaLocal(): Unit = {
    if (!BspServer.isWindows) testFailedCompile(createLocalBspCommand(configDir))
  }

  @Test def TestFailedCompileViaTcp(): Unit = {
    testFailedCompile(createTcpBspCommand(configDir))
  }
}
