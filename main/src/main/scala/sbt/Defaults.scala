/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt

import java.io.{ File, PrintWriter }
import java.net.{ URI, URL, URLClassLoader }
import java.util.Optional
import java.util.concurrent.TimeUnit

import lmcoursier.CoursierDependencyResolution
import lmcoursier.definitions.{ Configuration => CConfiguration }
import org.apache.ivy.core.module.descriptor.ModuleDescriptor
import org.apache.ivy.core.module.id.ModuleRevisionId
import sbt.Def.{ Initialize, ScopedKey, Setting, SettingsDefinition }
import sbt.Keys._
import sbt.Project.{
  inConfig,
  inScope,
  inTask,
  richInitialize,
  richInitializeTask,
  richTaskSessionVar
}
import sbt.Scope.{ GlobalScope, ThisScope, fillTaskAxis }
import sbt.coursierint._
import sbt.internal.CommandStrings.ExportStream
import sbt.internal._
import sbt.internal.classpath.AlternativeZincUtil
import sbt.internal.inc.JavaInterfaceUtil._
import sbt.internal.inc.classpath.ClasspathFilter
import sbt.internal.inc.{ ZincLmUtil, ZincUtil }
import sbt.internal.io.{ Source, WatchState }
import sbt.internal.librarymanagement.mavenint.{
  PomExtraDependencyAttributes,
  SbtPomExtraProperties
}
import sbt.internal.librarymanagement.{ CustomHttp => _, _ }
import sbt.internal.nio.{ CheckBuildSources, Globs }
import sbt.internal.server.{
  Definition,
  LanguageServerProtocol,
  LanguageServerReporter,
  ServerHandler
}
import sbt.nio.FileStamp.Formats.seqPathFileStampJsonFormatter
import sbt.internal.testing.TestLogger
import sbt.internal.util.Attributed.data
import sbt.internal.util.Types._
import sbt.internal.util._
import sbt.internal.util.complete._
import sbt.io.Path._
import sbt.io._
import sbt.io.syntax._
import sbt.librarymanagement.Artifact.{ DocClassifier, SourceClassifier }
import sbt.librarymanagement.Configurations.{
  Compile,
  CompilerPlugin,
  IntegrationTest,
  Provided,
  Runtime,
  Test,
  names
}
import sbt.librarymanagement.CrossVersion.{ binarySbtVersion, binaryScalaVersion, partialVersion }
import sbt.librarymanagement._
import sbt.librarymanagement.ivy._
import sbt.librarymanagement.syntax._
import sbt.nio.Keys._
import sbt.nio.file.syntax._
import sbt.nio.file.{ FileTreeView, Glob, RecursiveGlob }
import sbt.nio.{ FileChanges, Watch }
import sbt.std.TaskExtra._
import sbt.testing.{ AnnotatedFingerprint, Framework, Runner, SubclassFingerprint }
import sbt.util.CacheImplicits._
import sbt.util.InterfaceUtil.{ toJavaFunction => f1 }
import sbt.util._
import sjsonnew._
import sjsonnew.support.scalajson.unsafe.Converter
import xsbti.CrossValue
import xsbti.compile.{ AnalysisContents, IncOptions, IncToolOptionsUtil }

import scala.collection.immutable.ListMap
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal
import scala.xml.NodeSeq

// incremental compiler
import sbt.SlashSyntax0._
import sbt.internal.inc.{
  Analysis,
  AnalyzingCompiler,
  FileValueCache,
  Locate,
  ManagedLoggedReporter,
  MixedAnalyzingCompiler,
  ScalaInstance
}
import xsbti.compile.{
  ClassFileManagerType,
  ClasspathOptionsUtil,
  CompileAnalysis,
  CompileOptions,
  CompileOrder,
  CompileResult,
  CompilerCache,
  Compilers,
  DefinesClass,
  Inputs,
  MiniSetup,
  PerClasspathEntryLookup,
  PreviousResult,
  Setup,
  TransactionalManagerType
}

object Defaults extends BuildCommon {
  final val CacheDirectoryName = "cache"

  def configSrcSub(key: SettingKey[File]): Initialize[File] =
    Def.setting {
      (key in ThisScope.copy(config = Zero)).value / nameForSrc(configuration.value.name)
    }
  def nameForSrc(config: String) = if (config == Configurations.Compile.name) "main" else config
  def prefix(config: String) = if (config == Configurations.Compile.name) "" else config + "-"

  def lock(app: xsbti.AppConfiguration): xsbti.GlobalLock = LibraryManagement.lock(app)

  def extractAnalysis[T](a: Attributed[T]): (T, CompileAnalysis) =
    (a.data, a.metadata get Keys.analysis getOrElse Analysis.Empty)

  def analysisMap[T](cp: Seq[Attributed[T]]): T => Option[CompileAnalysis] = {
    val m = (for (a <- cp; an <- a.metadata get Keys.analysis) yield (a.data, an)).toMap
    m.get _
  }
  private[sbt] def globalDefaults(ss: Seq[Setting[_]]): Seq[Setting[_]] =
    Def.defaultSettings(inScope(GlobalScope)(ss))

  def buildCore: Seq[Setting[_]] = thisBuildCore ++ globalCore
  def thisBuildCore: Seq[Setting[_]] =
    inScope(GlobalScope.copy(project = Select(ThisBuild)))(
      Seq(
        managedDirectory := baseDirectory.value / "lib_managed"
      )
    )
  private[sbt] lazy val globalCore: Seq[Setting[_]] = globalDefaults(
    defaultTestTasks(test) ++ defaultTestTasks(testOnly) ++ defaultTestTasks(testQuick) ++ Seq(
      excludeFilter :== HiddenFileFilter,
      fileInputs :== Nil,
      fileInputIncludeFilter :== AllPassFilter.toNio,
      fileInputExcludeFilter :== DirectoryFilter.toNio || HiddenFileFilter,
      fileOutputIncludeFilter :== AllPassFilter.toNio,
      fileOutputExcludeFilter :== NothingFilter.toNio,
      inputFileStamper :== sbt.nio.FileStamper.Hash,
      outputFileStamper :== sbt.nio.FileStamper.LastModified,
      onChangedBuildSource :== sbt.nio.Keys.WarnOnSourceChanges,
      clean := { () },
      unmanagedFileStampCache :=
        state.value.get(persistentFileStampCache).getOrElse(new sbt.nio.FileStamp.Cache),
      managedFileStampCache := new sbt.nio.FileStamp.Cache,
    ) ++ globalIvyCore ++ globalJvmCore ++ Watch.defaults
  ) ++ globalSbtCore

  private[sbt] lazy val globalJvmCore: Seq[Setting[_]] =
    Seq(
      compilerCache := state.value get Keys.stateCompilerCache getOrElse CompilerCache.fresh,
      sourcesInBase :== true,
      autoAPIMappings := false,
      apiMappings := Map.empty,
      autoScalaLibrary :== true,
      managedScalaInstance :== true,
      classpathEntryDefinesClass :== FileValueCache(Locate.definesClass _).get,
      traceLevel in run :== 0,
      traceLevel in runMain :== 0,
      traceLevel in bgRun :== 0,
      traceLevel in fgRun :== 0,
      traceLevel in console :== Int.MaxValue,
      traceLevel in consoleProject :== Int.MaxValue,
      autoCompilerPlugins :== true,
      scalaHome :== None,
      apiURL := None,
      javaHome :== None,
      discoveredJavaHomes := CrossJava.discoverJavaHomes,
      javaHomes :== ListMap.empty,
      fullJavaHomes := CrossJava.expandJavaHomes(discoveredJavaHomes.value ++ javaHomes.value),
      testForkedParallel :== false,
      javaOptions :== Nil,
      sbtPlugin :== false,
      isMetaBuild :== false,
      reresolveSbtArtifacts :== false,
      crossPaths :== true,
      sourcePositionMappers :== Nil,
      artifactClassifier in packageSrc :== Some(SourceClassifier),
      artifactClassifier in packageDoc :== Some(DocClassifier),
      includeFilter :== NothingFilter,
      includeFilter in unmanagedSources :== ("*.java" | "*.scala"),
      includeFilter in unmanagedJars :== "*.jar" | "*.so" | "*.dll" | "*.jnilib" | "*.zip",
      includeFilter in unmanagedResources :== AllPassFilter,
      bgList := { bgJobService.value.jobs },
      ps := psTask.value,
      bgStop := bgStopTask.evaluated,
      bgWaitFor := bgWaitForTask.evaluated,
      bgCopyClasspath :== true,
    )

  private[sbt] lazy val globalIvyCore: Seq[Setting[_]] =
    Seq(
      internalConfigurationMap :== Configurations.internalMap _,
      credentials :== Nil,
      exportJars :== false,
      trackInternalDependencies :== TrackLevel.TrackAlways,
      exportToInternal :== TrackLevel.TrackAlways,
      useCoursier :== SysProp.defaultUseCoursier,
      retrieveManaged :== false,
      retrieveManagedSync :== false,
      configurationsToRetrieve :== None,
      scalaOrganization :== ScalaArtifacts.Organization,
      scalaArtifacts :== ScalaArtifacts.Artifacts,
      sbtResolver := {
        val v = sbtVersion.value
        if (v.endsWith("-SNAPSHOT") || v.contains("-bin-")) Classpaths.sbtMavenSnapshots
        else Resolver.DefaultMavenRepository
      },
      sbtResolvers := {
        // TODO: Remove Classpaths.typesafeReleases for sbt 2.x
        // We need to keep it around for sbt 1.x to cross build plugins with sbt 0.13 - https://github.com/sbt/sbt/issues/4698
        Vector(sbtResolver.value, Classpaths.sbtPluginReleases, Classpaths.typesafeReleases)
      },
      crossVersion :== Disabled(),
      buildDependencies := Classpaths.constructBuildDependencies.value,
      version :== "0.1.0-SNAPSHOT",
      classpathTypes :== Set("jar", "bundle", "maven-plugin", "test-jar") ++ CustomPomParser.JarPackagings,
      artifactClassifier :== None,
      checksums := Classpaths.bootChecksums(appConfiguration.value),
      conflictManager := ConflictManager.default,
      CustomHttp.okhttpClientBuilder :== CustomHttp.defaultHttpClientBuilder,
      CustomHttp.okhttpClient := CustomHttp.okhttpClientBuilder.value.build,
      pomExtra :== NodeSeq.Empty,
      pomPostProcess :== idFun,
      pomAllRepositories :== false,
      pomIncludeRepository :== Classpaths.defaultRepositoryFilter,
      updateOptions := UpdateOptions(),
      forceUpdatePeriod :== None,
      // coursier settings
      csrExtraCredentials :== Nil,
      csrLogger := LMCoursier.coursierLoggerTask.value,
      csrCacheDirectory :== LMCoursier.defaultCacheLocation,
      csrMavenProfiles :== Set.empty,
      csrReconciliations :== LMCoursier.relaxedForAllModules,
    )

  /** Core non-plugin settings for sbt builds.  These *must* be on every build or the sbt engine will fail to run at all. */
  private[sbt] lazy val globalSbtCore: Seq[Setting[_]] = globalDefaults(
    Seq(
      outputStrategy :== None, // TODO - This might belong elsewhere.
      buildStructure := Project.structure(state.value),
      settingsData := buildStructure.value.data,
      aggregate in checkBuildSources :== false,
      aggregate in checkBuildSources / changedInputFiles := false,
      checkBuildSources / Continuous.dynamicInputs := None,
      checkBuildSources / fileInputs := CheckBuildSources.buildSourceFileInputs.value,
      checkBuildSources := CheckBuildSources.needReloadImpl.value,
      fileCacheSize := "128M",
      trapExit :== true,
      connectInput :== false,
      cancelable :== true,
      taskCancelStrategy := { state: State =>
        if (cancelable.value) TaskCancellationStrategy.Signal
        else TaskCancellationStrategy.Null
      },
      envVars :== Map.empty,
      sbtVersion := appConfiguration.value.provider.id.version,
      sbtBinaryVersion := binarySbtVersion(sbtVersion.value),
      // `pluginCrossBuild` scoping is based on sbt-cross-building plugin.
      // The idea here is to be able to define a `sbtVersion in pluginCrossBuild`, which
      // directs the dependencies of the plugin to build to the specified sbt plugin version.
      sbtVersion in pluginCrossBuild := sbtVersion.value,
      onLoad := idFun[State],
      onUnload := idFun[State],
      onUnload := { s =>
        try onUnload.value(s)
        finally IO.delete(taskTemporaryDirectory.value)
      },
      extraLoggers :== { _ =>
        Nil
      },
      watchSources :== Nil, // Although this is deprecated, it can't be removed or it breaks += for legacy builds.
      skip :== false,
      taskTemporaryDirectory := { val dir = IO.createTemporaryDirectory; dir.deleteOnExit(); dir },
      onComplete := {
        val tempDirectory = taskTemporaryDirectory.value
        () => Clean.deleteContents(tempDirectory, _ => false)
      },
      turbo :== SysProp.turbo,
      useSuperShell := { if (insideCI.value) false else SysProp.supershell },
      progressReports := {
        val rs = EvaluateTask.taskTimingProgress.toVector ++ EvaluateTask.taskTraceEvent.toVector
        rs map { Keys.TaskProgress(_) }
      },
      progressState := {
        if ((ThisBuild / useSuperShell).value) Some(new ProgressState(SysProp.supershellBlankZone))
        else None
      },
      Previous.cache := new Previous(
        Def.streamsManagerKey.value,
        Previous.references.value.getReferences
      ),
      Previous.references :== new Previous.References,
      concurrentRestrictions := defaultRestrictions.value,
      parallelExecution :== true,
      fileTreeView :== FileTreeView.default,
      Continuous.dynamicInputs := Continuous.dynamicInputsImpl.value,
      logBuffered :== false,
      commands :== Nil,
      showSuccess :== true,
      showTiming :== true,
      timingFormat :== Aggregation.defaultFormat,
      aggregate :== true,
      maxErrors :== 100,
      fork :== false,
      initialize :== {},
      templateResolverInfos :== Nil,
      forcegc :== sys.props
        .get("sbt.task.forcegc")
        .map(java.lang.Boolean.parseBoolean)
        .getOrElse(GCUtil.defaultForceGarbageCollection),
      minForcegcInterval :== GCUtil.defaultMinForcegcInterval,
      interactionService :== CommandLineUIService,
      autoStartServer := true,
      serverHost := "127.0.0.1",
      serverPort := 5000 + (Hash
        .toHex(Hash(appConfiguration.value.baseDirectory.toString))
        .## % 1000),
      serverConnectionType := ConnectionType.Local,
      serverAuthentication := {
        if (serverConnectionType.value == ConnectionType.Tcp) Set(ServerAuthentication.Token)
        else Set()
      },
      serverHandlers :== Nil,
      fullServerHandlers := {
        (Vector(LanguageServerProtocol.handler)
          ++ serverHandlers.value
          ++ Vector(ServerHandler.fallback))
      },
      insideCI :== sys.env.contains("BUILD_NUMBER") ||
        sys.env.contains("CI") || SysProp.ci,
      // watch related settings
      pollInterval :== Watch.defaultPollInterval,
    )
  )

  def defaultTestTasks(key: Scoped): Seq[Setting[_]] =
    inTask(key)(
      Seq(
        tags := Seq(Tags.Test -> 1),
        logBuffered := true
      )
    )

  // TODO: This should be on the new default settings for a project.
  def projectCore: Seq[Setting[_]] = Seq(
    name := thisProject.value.id,
    logManager := LogManager.defaults(extraLoggers.value, StandardMain.console),
    onLoadMessage := (onLoadMessage or
      Def.setting {
        s"Set current project to ${name.value} (in build ${thisProjectRef.value.build})"
      }).value
  )

  def paths = Seq(
    baseDirectory := thisProject.value.base,
    target := baseDirectory.value / "target",
    historyPath := (historyPath or target(t => Option(t / ".history"))).value,
    sourceDirectory := baseDirectory.value / "src",
    sourceManaged := crossTarget.value / "src_managed",
    resourceManaged := crossTarget.value / "resource_managed"
  )

  lazy val configPaths = sourceConfigPaths ++ resourceConfigPaths ++ outputConfigPaths
  lazy val sourceConfigPaths = Seq(
    sourceDirectory := configSrcSub(sourceDirectory).value,
    sourceManaged := configSrcSub(sourceManaged).value,
    scalaSource := sourceDirectory.value / "scala",
    javaSource := sourceDirectory.value / "java",
    unmanagedSourceDirectories := {
      makeCrossSources(
        scalaSource.value,
        javaSource.value,
        scalaBinaryVersion.value,
        crossPaths.value
      ) ++
        makePluginCrossSources(
          sbtPlugin.value,
          scalaSource.value,
          (sbtBinaryVersion in pluginCrossBuild).value,
          crossPaths.value
        )
    },
    unmanagedSources / fileInputs := {
      val include = (includeFilter in unmanagedSources).value
      val filter = (excludeFilter in unmanagedSources).value match {
        // Hidden files are already filtered out by the FileStamps method
        case NothingFilter | HiddenFileFilter => include
        case exclude                          => include -- exclude
      }
      val baseSources =
        if (sourcesInBase.value) Globs(baseDirectory.value.toPath, recursive = false, filter) :: Nil
        else Nil
      unmanagedSourceDirectories.value
        .map(d => Globs(d.toPath, recursive = true, filter)) ++ baseSources
    },
    unmanagedSources := (unmanagedSources / inputFileStamps).value.map(_._1.toFile),
    managedSourceDirectories := Seq(sourceManaged.value),
    managedSources := {
      val stamper = inputFileStamper.value
      val cache = managedFileStampCache.value
      val res = generate(sourceGenerators).value
      res.foreach { f =>
        cache.putIfAbsent(f.toPath, stamper)
      }
      res
    },
    managedSourcePaths / outputFileStamper := sbt.nio.FileStamper.Hash,
    managedSourcePaths := managedSources.value.map(_.toPath),
    sourceGenerators :== Nil,
    sourceDirectories := Classpaths
      .concatSettings(unmanagedSourceDirectories, managedSourceDirectories)
      .value,
    sources := Classpaths.concatDistinct(unmanagedSources, managedSources).value
  )
  lazy val resourceConfigPaths = Seq(
    resourceDirectory := sourceDirectory.value / "resources",
    resourceManaged := configSrcSub(resourceManaged).value,
    unmanagedResourceDirectories := Seq(resourceDirectory.value),
    managedResourceDirectories := Seq(resourceManaged.value),
    resourceDirectories := Classpaths
      .concatSettings(unmanagedResourceDirectories, managedResourceDirectories)
      .value,
    unmanagedResources / fileInputs := {
      val include = (includeFilter in unmanagedResources).value
      val filter = (excludeFilter in unmanagedResources).value match {
        // Hidden files are already filtered out by the FileStamps method
        case NothingFilter | HiddenFileFilter => include
        case exclude                          => include -- exclude
      }
      unmanagedResourceDirectories.value.map(d => Globs(d.toPath, recursive = true, filter))
    },
    unmanagedResources := (unmanagedResources / inputFileStamps).value.map(_._1.toFile),
    resourceGenerators :== Nil,
    resourceGenerators += Def.task {
      PluginDiscovery.writeDescriptors(discoveredSbtPlugins.value, resourceManaged.value)
    },
    managedResources := generate(resourceGenerators).value,
    resources := Classpaths.concat(managedResources, unmanagedResources).value
  )
  // This exists for binary compatibility and probably never should have been public.
  def addBaseSources: Seq[Def.Setting[Task[Seq[File]]]] = Nil
  lazy val outputConfigPaths = Seq(
    classDirectory := crossTarget.value / (prefix(configuration.value.name) + "classes"),
    semanticdbTargetRoot := crossTarget.value / (prefix(configuration.value.name) + "meta"),
    target in doc := crossTarget.value / (prefix(configuration.value.name) + "api")
  )

  // This is included into JvmPlugin.projectSettings
  def compileBase = inTask(console)(compilersSetting :: Nil) ++ compileBaseGlobal ++ Seq(
    incOptions := incOptions.value
      .withClassfileManagerType(
        Option(
          TransactionalManagerType
            .of(crossTarget.value / "classes.bak", sbt.util.Logger.Null): ClassFileManagerType
        ).toOptional
      ),
    scalaInstance := scalaInstanceTask.value,
    crossVersion := (if (crossPaths.value) CrossVersion.binary else CrossVersion.disabled),
    sbtBinaryVersion in pluginCrossBuild := binarySbtVersion(
      (sbtVersion in pluginCrossBuild).value
    ),
    crossSbtVersions := Vector((sbtVersion in pluginCrossBuild).value),
    crossTarget := makeCrossTarget(
      target.value,
      scalaBinaryVersion.value,
      (sbtBinaryVersion in pluginCrossBuild).value,
      sbtPlugin.value,
      crossPaths.value
    ),
    clean := {
      val _ = clean.value
      IvyActions.cleanCachedResolutionCache(ivyModule.value, streams.value.log)
    },
    scalaCompilerBridgeBinaryJar := None,
    scalaCompilerBridgeSource := ZincLmUtil.getDefaultBridgeModule(scalaVersion.value),
  )
  // must be a val: duplication detected by object identity
  private[this] lazy val compileBaseGlobal: Seq[Setting[_]] = globalDefaults(
    Seq(
      incOptions := IncOptions.of(),
      classpathOptions :== ClasspathOptionsUtil.boot,
      classpathOptions in console :== ClasspathOptionsUtil.repl,
      compileOrder :== CompileOrder.Mixed,
      javacOptions :== Nil,
      scalacOptions :== Nil,
      scalaVersion := appConfiguration.value.provider.scalaProvider.version,
      derive(crossScalaVersions := Seq(scalaVersion.value)),
      derive(compilersSetting),
      derive(scalaBinaryVersion := binaryScalaVersion(scalaVersion.value))
    )
  )

  def makeCrossSources(
      scalaSrcDir: File,
      javaSrcDir: File,
      sv: String,
      cross: Boolean
  ): Seq[File] = {
    if (cross)
      Seq(scalaSrcDir.getParentFile / s"${scalaSrcDir.name}-$sv", scalaSrcDir, javaSrcDir)
    else
      Seq(scalaSrcDir, javaSrcDir)
  }

  def makePluginCrossSources(
      isPlugin: Boolean,
      scalaSrcDir: File,
      sbtBinaryV: String,
      cross: Boolean
  ): Seq[File] = {
    if (cross && isPlugin)
      Vector(scalaSrcDir.getParentFile / s"${scalaSrcDir.name}-sbt-$sbtBinaryV")
    else Vector()
  }

  def makeCrossTarget(t: File, sv: String, sbtv: String, plugin: Boolean, cross: Boolean): File = {
    val scalaBase = if (cross) t / ("scala-" + sv) else t
    if (plugin) scalaBase / ("sbt-" + sbtv) else scalaBase
  }

  def compilersSetting = {
    compilers := {
      val st = state.value
      val g = BuildPaths.getGlobalBase(st)
      val zincDir = BuildPaths.getZincDirectory(st, g)
      val app = appConfiguration.value
      val launcher = app.provider.scalaProvider.launcher
      val dr = scalaCompilerBridgeDependencyResolution.value
      val scalac =
        scalaCompilerBridgeBinaryJar.value match {
          case Some(jar) =>
            AlternativeZincUtil.scalaCompiler(
              scalaInstance = scalaInstance.value,
              classpathOptions = classpathOptions.value,
              compilerBridgeJar = jar,
              classLoaderCache = st.get(BasicKeys.classLoaderCache)
            )
          case _ =>
            ZincLmUtil.scalaCompiler(
              scalaInstance = scalaInstance.value,
              classpathOptions = classpathOptions.value,
              globalLock = launcher.globalLock,
              componentProvider = app.provider.components,
              secondaryCacheDir = Option(zincDir),
              dependencyResolution = dr,
              compilerBridgeSource = scalaCompilerBridgeSource.value,
              scalaJarsTarget = zincDir,
              classLoaderCache = st.get(BasicKeys.classLoaderCache),
              log = streams.value.log
            )
        }
      val compilers = ZincUtil.compilers(
        instance = scalaInstance.value,
        classpathOptions = classpathOptions.value,
        javaHome = javaHome.value,
        scalac
      )
      val classLoaderCache = state.value.classLoaderCache
      if (java.lang.Boolean.getBoolean("sbt.disable.interface.classloader.cache")) compilers
      else {
        compilers.withScalac(
          compilers.scalac match {
            case x: AnalyzingCompiler => x.withClassLoaderCache(classLoaderCache)
            case x                    => x
          }
        )
      }
    }
  }

  def defaultCompileSettings: Seq[Setting[_]] =
    globalDefaults(enableBinaryCompileAnalysis := true)

  lazy val configTasks: Seq[Setting[_]] = docTaskSettings(doc) ++ inTask(compile)(
    compileInputsSettings
  ) ++ configGlobal ++ defaultCompileSettings ++ compileAnalysisSettings ++ Seq(
    compileOutputs := {
      import scala.collection.JavaConverters._
      val classFiles =
        manipulateBytecode.value.analysis.readStamps.getAllProductStamps.keySet.asScala
      classFiles.toSeq.map(_.toPath) :+ compileAnalysisFileTask.value.toPath
    },
    compileOutputs := compileOutputs.triggeredBy(compile).value,
    clean := (compileOutputs / clean).value,
    compile := compileTask.value,
    internalDependencyConfigurations := InternalDependencies.configurations.value,
    manipulateBytecode := compileIncremental.value,
    compileIncremental := (compileIncrementalTask tag (Tags.Compile, Tags.CPU)).value,
    printWarnings := printWarningsTask.value,
    compileAnalysisFilename := {
      // Here, if the user wants cross-scala-versioning, we also append it
      // to the analysis cache, so we keep the scala versions separated.
      val binVersion = scalaBinaryVersion.value
      val extra =
        if (crossPaths.value) s"_$binVersion"
        else ""
      s"inc_compile$extra.zip"
    },
    externalHooks := {
      import sjsonnew.BasicJsonProtocol.mapFormat
      val currentInputs =
        (unmanagedSources / inputFileStamps).value ++ (managedSourcePaths / outputFileStamps).value
      val sv = scalaVersion.value
      val previousInputs = compileSourceFileInputs.previous.flatMap(_.get(sv))
      val inputChanges = previousInputs
        .map(sbt.nio.Settings.changedFiles(_, currentInputs))
        .getOrElse(FileChanges.noPrevious(currentInputs.map(_._1)))
      val currentOutputs = (dependencyClasspathFiles / outputFileStamps).value
      val previousOutputs = compileBinaryFileInputs.previous.flatMap(_.get(sv))
      val outputChanges = previousOutputs
        .map(sbt.nio.Settings.changedFiles(_, currentOutputs))
        .getOrElse(FileChanges.noPrevious(currentOutputs.map(_._1)))
      ExternalHooks.default.value(inputChanges, outputChanges, fileTreeView.value)
    },
    compileSourceFileInputs := {
      import sjsonnew.BasicJsonProtocol.mapFormat
      compile.value // ensures the inputFileStamps previous value is only set if compile succeeds.
      val version = scalaVersion.value
      val versions = crossScalaVersions.value.toSet + version
      val prev: Map[String, Seq[(java.nio.file.Path, sbt.nio.FileStamp)]] =
        compileSourceFileInputs.previous.map(_.filterKeys(versions)).getOrElse(Map.empty)
      prev + (version ->
        ((unmanagedSources / inputFileStamps).value ++ (managedSourcePaths / outputFileStamps).value))
    },
    compileSourceFileInputs := compileSourceFileInputs.triggeredBy(compile).value,
    compileBinaryFileInputs := {
      import sjsonnew.BasicJsonProtocol.mapFormat
      compile.value // ensures the inputFileStamps previous value is only set if compile succeeds.
      val version = scalaVersion.value
      val versions = crossScalaVersions.value.toSet + version
      val prev: Map[String, Seq[(java.nio.file.Path, sbt.nio.FileStamp)]] =
        compileBinaryFileInputs.previous.map(_.filterKeys(versions)).getOrElse(Map.empty)
      prev + (version -> (dependencyClasspathFiles / outputFileStamps).value)
    },
    compileBinaryFileInputs := compileBinaryFileInputs.triggeredBy(compile).value,
    incOptions := { incOptions.value.withExternalHooks(externalHooks.value) },
    compileIncSetup := compileIncSetupTask.value,
    console := consoleTask.value,
    collectAnalyses := Definition.collectAnalysesTask.map(_ => ()).value,
    consoleQuick := consoleQuickTask.value,
    discoveredMainClasses := (compile map discoverMainClasses storeAs discoveredMainClasses xtriggeredBy compile).value,
    discoveredSbtPlugins := discoverSbtPluginNames.value,
    // This fork options, scoped to the configuration is used for tests
    forkOptions := forkOptionsTask.value,
    selectMainClass := mainClass.value orElse askForMainClass(discoveredMainClasses.value),
    mainClass in run := (selectMainClass in run).value,
    mainClass := pickMainClassOrWarn(discoveredMainClasses.value, streams.value.log),
    runMain := foregroundRunMainTask.evaluated,
    run := foregroundRunTask.evaluated,
    fgRun := runTask(fullClasspath, mainClass in run, runner in run).evaluated,
    fgRunMain := runMainTask(fullClasspath, runner in run).evaluated,
    copyResources := copyResourcesTask.value,
    // note that we use the same runner and mainClass as plain run
    mainBgRunMainTaskForConfig(This),
    mainBgRunTaskForConfig(This)
  ) ++ inTask(run)(runnerSettings ++ newRunnerSettings)

  private[this] lazy val configGlobal = globalDefaults(
    Seq(
      initialCommands :== "",
      cleanupCommands :== "",
      asciiGraphWidth :== 40
    )
  )

  lazy val projectTasks: Seq[Setting[_]] = Seq(
    cleanFiles := cleanFilesTask.value,
    cleanKeepFiles := Vector.empty,
    cleanKeepGlobs := historyPath.value.map(_.toGlob).toSeq,
    clean := Def.taskDyn(Clean.task(resolvedScoped.value.scope, full = true)).value,
    consoleProject := consoleProjectTask.value,
    transitiveDynamicInputs := SettingsGraph.task.value,
  ) ++ sbt.internal.DeprecatedContinuous.taskDefinitions

  def generate(generators: SettingKey[Seq[Task[Seq[File]]]]): Initialize[Task[Seq[File]]] =
    generators { _.join.map(_.flatten) }

  @deprecated(
    "The watchTransitiveSourcesTask is used only for legacy builds and will be removed in a future version of sbt.",
    "1.3.0"
  )
  def watchTransitiveSourcesTask: Initialize[Task[Seq[Source]]] =
    watchTransitiveSourcesTaskImpl(watchSources)

  private def watchTransitiveSourcesTaskImpl(
      key: TaskKey[Seq[Source]]
  ): Initialize[Task[Seq[Source]]] = {
    import ScopeFilter.Make.{ inDependencies => inDeps, _ }
    val selectDeps = ScopeFilter(inAggregates(ThisProject) || inDeps(ThisProject))
    val allWatched = (key ?? Nil).all(selectDeps)
    Def.task { allWatched.value.flatten }
  }

  def transitiveUpdateTask: Initialize[Task[Seq[UpdateReport]]] = {
    import ScopeFilter.Make.{ inDependencies => inDeps, _ }
    val selectDeps = ScopeFilter(inDeps(ThisProject, includeRoot = false))
    val allUpdates = update.?.all(selectDeps)
    // If I am a "build" (a project inside project/) then I have a globalPluginUpdate.
    Def.task { allUpdates.value.flatten ++ globalPluginUpdate.?.value }
  }

  @deprecated("This is no longer used to implement continuous execution", "1.3.0")
  def watchSetting: Initialize[Watched] =
    Def.setting {
      val getService = watchService.value
      val interval = pollInterval.value
      val _antiEntropy = watchAntiEntropy.value
      val base = thisProjectRef.value
      val msg = watchingMessage.?.value.getOrElse(Watched.defaultWatchingMessage)
      val trigMsg = triggeredMessage.?.value.getOrElse(Watched.defaultTriggeredMessage)
      new Watched {
        val scoped = watchTransitiveSources in base
        val key = scoped.scopedKey
        override def antiEntropy: FiniteDuration = _antiEntropy
        override def pollInterval: FiniteDuration = interval
        override def watchingMessage(s: WatchState) = msg(s)
        override def triggeredMessage(s: WatchState) = trigMsg(s)
        override def watchService() = getService()
        override def watchSources(s: State) =
          EvaluateTask(Project structure s, key, s, base) match {
            case Some((_, Value(ps))) => ps
            case Some((_, Inc(i)))    => throw i
            case None                 => sys.error("key not found: " + Def.displayFull(key))
          }
      }
    }

  def scalaInstanceTask: Initialize[Task[ScalaInstance]] = Def.taskDyn {
    // if this logic changes, ensure that `unmanagedScalaInstanceOnly` and `update` are changed
    //  appropriately to avoid cycles
    scalaHome.value match {
      case Some(h) => scalaInstanceFromHome(h)
      case None =>
        val scalaProvider = appConfiguration.value.provider.scalaProvider
        val version = scalaVersion.value
        if (version == scalaProvider.version) // use the same class loader as the Scala classes used by sbt
          Def.task {
            val allJars = scalaProvider.jars
            val libraryJars = allJars.filter(_.getName == "scala-library.jar")
            allJars.filter(_.getName == "scala-compiler.jar") match {
              case Array(compilerJar) if libraryJars.nonEmpty =>
                val cache = state.value.classLoaderCache
                mkScalaInstance(version, allJars, libraryJars, compilerJar, cache)
              case _ => ScalaInstance(version, scalaProvider)
            }
          } else
          scalaInstanceFromUpdate
    }
  }

  // Returns the ScalaInstance only if it was not constructed via `update`
  //  This is necessary to prevent cycles between `update` and `scalaInstance`
  private[sbt] def unmanagedScalaInstanceOnly: Initialize[Task[Option[ScalaInstance]]] =
    Def.taskDyn {
      if (scalaHome.value.isDefined) Def.task(Some(scalaInstance.value)) else Def.task(None)
    }

  private[this] def noToolConfiguration(autoInstance: Boolean): String = {
    val pre = "Missing Scala tool configuration from the 'update' report.  "
    val post =
      if (autoInstance)
        "'scala-tool' is normally added automatically, so this may indicate a bug in sbt or you may be removing it from ivyConfigurations, for example."
      else
        "Explicitly define scalaInstance or scalaHome or include Scala dependencies in the 'scala-tool' configuration."
    pre + post
  }

  def scalaInstanceFromUpdate: Initialize[Task[ScalaInstance]] = Def.task {
    val toolReport = update.value.configuration(Configurations.ScalaTool) getOrElse
      sys.error(noToolConfiguration(managedScalaInstance.value))
    def files(id: String) =
      for {
        m <- toolReport.modules if m.module.name == id;
        (art, file) <- m.artifacts if art.`type` == Artifact.DefaultType
      } yield file
    def file(id: String) = files(id).headOption getOrElse sys.error(s"Missing ${id}.jar")
    val allJars = toolReport.modules.flatMap(_.artifacts.map(_._2))
    val libraryJar = file(ScalaArtifacts.LibraryID)
    val compilerJar = file(ScalaArtifacts.CompilerID)
    mkScalaInstance(
      scalaVersion.value,
      allJars,
      Array(libraryJar),
      compilerJar,
      state.value.classLoaderCache
    )
  }
  private[this] def mkScalaInstance(
      version: String,
      allJars: Seq[File],
      libraryJars: Array[File],
      compilerJar: File,
      classLoaderCache: sbt.internal.inc.classpath.ClassLoaderCache
  ): ScalaInstance = {
    val libraryLoader = classLoaderCache(libraryJars.toList)
    class ScalaLoader extends URLClassLoader(allJars.map(_.toURI.toURL).toArray, libraryLoader)
    val fullLoader = classLoaderCache.cachedCustomClassloader(
      allJars.toList,
      () => new ScalaLoader
    )
    new ScalaInstance(
      version,
      fullLoader,
      libraryLoader,
      libraryJars,
      compilerJar,
      allJars.toArray,
      Some(version)
    )
  }
  def scalaInstanceFromHome(dir: File): Initialize[Task[ScalaInstance]] = Def.task {
    val dummy = ScalaInstance(dir)(state.value.classLoaderCache.apply)
    Seq(dummy.loader, dummy.loaderLibraryOnly).foreach {
      case a: AutoCloseable => a.close()
      case cl               =>
    }
    mkScalaInstance(
      dummy.version,
      dummy.allJars,
      dummy.libraryJars,
      dummy.compilerJar,
      state.value.classLoaderCache
    )
  }

  private[this] def testDefaults =
    Defaults.globalDefaults(
      Seq(
        testFrameworks :== {
          import sbt.TestFrameworks._
          Seq(ScalaCheck, Specs2, Specs, ScalaTest, JUnit)
        },
        testListeners :== Nil,
        testOptions :== Nil,
        testResultLogger :== TestResultLogger.Default,
        testFilter in testOnly :== (selectedFilter _)
      )
    )
  lazy val testTasks
      : Seq[Setting[_]] = testTaskOptions(test) ++ testTaskOptions(testOnly) ++ testTaskOptions(
    testQuick
  ) ++ testDefaults ++ Seq(
    testLoader := ClassLoaders.testTask.value,
    loadedTestFrameworks := {
      val loader = testLoader.value
      val log = streams.value.log
      testFrameworks.value.flatMap(f => f.create(loader, log).map(x => (f, x)).toIterable).toMap
    },
    definedTests := detectTests.value,
    definedTestNames := (definedTests map (_.map(_.name).distinct) storeAs definedTestNames triggeredBy compile).value,
    testFilter in testQuick := testQuickFilter.value,
    executeTests := (
      Def.taskDyn {
        allTestGroupsTask(
          (streams in test).value,
          loadedTestFrameworks.value,
          testLoader.value,
          (testGrouping in test).value,
          (testExecution in test).value,
          (fullClasspath in test).value,
          testForkedParallel.value,
          (javaOptions in test).value,
          (classLoaderLayeringStrategy).value,
          projectId = s"${thisProject.value.id} / ",
        )
      }
    ).value,
    // ((streams in test, loadedTestFrameworks, testLoader, testGrouping in test, testExecution in test, fullClasspath in test, javaHome in test, testForkedParallel, javaOptions in test) flatMap allTestGroupsTask).value,
    testResultLogger in (Test, test) :== TestResultLogger.SilentWhenNoTests, // https://github.com/sbt/sbt/issues/1185
    test := {
      val trl = (testResultLogger in (Test, test)).value
      val taskName = Project.showContextKey(state.value).show(resolvedScoped.value)
      try trl.run(streams.value.log, executeTests.value, taskName)
      finally close(testLoader.value)
    },
    testOnly := {
      try inputTests(testOnly).evaluated
      finally close(testLoader.value)
    },
    testQuick := {
      try inputTests(testQuick).evaluated
      finally close(testLoader.value)
    }
  )
  private def close(sbtLoader: ClassLoader): Unit = sbtLoader match {
    case u: AutoCloseable   => u.close()
    case c: ClasspathFilter => c.close()
    case _                  =>
  }

  /**
   * A scope whose task axis is set to Zero.
   */
  lazy val TaskZero: Scope = ThisScope.copy(task = Zero)
  lazy val TaskGlobal: Scope = TaskZero

  /**
   * A scope whose configuration axis is set to Zero.
   */
  lazy val ConfigZero: Scope = ThisScope.copy(config = Zero)
  lazy val ConfigGlobal: Scope = ConfigZero
  def testTaskOptions(key: Scoped): Seq[Setting[_]] =
    inTask(key)(
      Seq(
        testListeners := {
          TestLogger.make(
            streams.value.log,
            closeableTestLogger(
              streamsManager.value,
              test in resolvedScoped.value.scope,
              logBuffered.value
            )
          ) +:
            new TestStatusReporter(succeededFile(streams.in(test).value.cacheDirectory)) +:
            testListeners.in(TaskZero).value
        },
        testOptions := Tests.Listeners(testListeners.value) +: (testOptions in TaskZero).value,
        testExecution := testExecutionTask(key).value
      )
    ) ++ inScope(GlobalScope)(
      Seq(
        derive(testGrouping := singleTestGroupDefault.value)
      )
    )

  private[this] def closeableTestLogger(manager: Streams, baseKey: Scoped, buffered: Boolean)(
      tdef: TestDefinition
  ): TestLogger.PerTest = {
    val scope = baseKey.scope
    val extra = scope.extra match { case Select(x) => x; case _ => AttributeMap.empty }
    val key = ScopedKey(scope.copy(extra = Select(testExtra(extra, tdef))), baseKey.key)
    val s = manager(key)
    new TestLogger.PerTest(s.log, () => s.close(), buffered)
  }

  def testExtra(extra: AttributeMap, tdef: TestDefinition): AttributeMap = {
    val mod = tdef.fingerprint match {
      case f: SubclassFingerprint  => f.isModule
      case f: AnnotatedFingerprint => f.isModule
      case _                       => false
    }
    extra.put(name.key, tdef.name).put(isModule, mod)
  }

  def singleTestGroup(key: Scoped): Initialize[Task[Seq[Tests.Group]]] =
    inTask(key, singleTestGroupDefault)
  def singleTestGroupDefault: Initialize[Task[Seq[Tests.Group]]] = Def.task {
    val tests = definedTests.value
    val fk = fork.value
    val opts = forkOptions.value
    Seq(new Tests.Group("<default>", tests, if (fk) Tests.SubProcess(opts) else Tests.InProcess))
  }
  def forkOptionsTask: Initialize[Task[ForkOptions]] =
    Def.task {
      ForkOptions(
        javaHome = javaHome.value,
        outputStrategy = outputStrategy.value,
        // bootJars is empty by default because only jars on the user's classpath should be on the boot classpath
        bootJars = Vector(),
        workingDirectory = Some(baseDirectory.value),
        runJVMOptions = javaOptions.value.toVector,
        connectInput = connectInput.value,
        envVars = envVars.value
      )
    }

  def testExecutionTask(task: Scoped): Initialize[Task[Tests.Execution]] =
    Def.task {
      new Tests.Execution(
        (testOptions in task).value,
        (parallelExecution in task).value,
        (tags in task).value
      )
    }

  def testQuickFilter: Initialize[Task[Seq[String] => Seq[String => Boolean]]] =
    Def.task {
      val cp = (fullClasspath in test).value
      val s = (streams in test).value
      val ans: Seq[Analysis] = cp.flatMap(_.metadata get Keys.analysis) map {
        case a0: Analysis => a0
      }
      val succeeded = TestStatus.read(succeededFile(s.cacheDirectory))
      val stamps = collection.mutable.Map.empty[String, Long]
      def stamp(dep: String): Long = {
        val stamps = for (a <- ans) yield intlStamp(dep, a, Set.empty)
        if (stamps.isEmpty) Long.MinValue
        else stamps.max
      }
      def intlStamp(c: String, analysis: Analysis, s: Set[String]): Long = {
        if (s contains c) Long.MinValue
        else
          stamps.getOrElse(
            c, {
              val x = {
                import analysis.{ apis, relations => rel }
                rel.internalClassDeps(c).map(intlStamp(_, analysis, s + c)) ++
                  rel.externalDeps(c).map(stamp) +
                  (apis.internal.get(c) match {
                    case Some(x) => x.compilationTimestamp
                    case _       => Long.MinValue
                  })
              }.max
              if (x != Long.MinValue) {
                stamps(c) = x
              }
              x
            }
          )
      }
      def noSuccessYet(test: String) = succeeded.get(test) match {
        case None     => true
        case Some(ts) => stamp(test) > ts
      }
      args =>
        for (filter <- selectedFilter(args))
          yield (test: String) => filter(test) && noSuccessYet(test)
    }
  def succeededFile(dir: File) = dir / "succeeded_tests"

  def inputTests(key: InputKey[_]): Initialize[InputTask[Unit]] =
    inputTests0.mapReferenced(Def.mapScope(_ in key.key))
  private[this] lazy val inputTests0: Initialize[InputTask[Unit]] = {
    val parser = loadForParser(definedTestNames)((s, i) => testOnlyParser(s, i getOrElse Nil))
    Def.inputTaskDyn {
      val (selected, frameworkOptions) = parser.parsed
      val s = streams.value
      val filter = testFilter.value
      val config = testExecution.value

      implicit val display = Project.showContextKey(state.value)
      val modifiedOpts = Tests.Filters(filter(selected)) +: Tests.Argument(frameworkOptions: _*) +: config.options
      val newConfig = config.copy(options = modifiedOpts)
      val output = allTestGroupsTask(
        s,
        loadedTestFrameworks.value,
        testLoader.value,
        testGrouping.value,
        newConfig,
        fullClasspath.value,
        testForkedParallel.value,
        javaOptions.value,
        classLoaderLayeringStrategy.value,
        projectId = s"${thisProject.value.id} / ",
      )
      val taskName = display.show(resolvedScoped.value)
      val trl = testResultLogger.value
      output.map(out => trl.run(s.log, out, taskName))
    }
  }

  def createTestRunners(
      frameworks: Map[TestFramework, Framework],
      loader: ClassLoader,
      config: Tests.Execution
  ): Map[TestFramework, Runner] = {
    import Tests.Argument
    val opts = config.options.toList
    frameworks.map {
      case (tf, f) =>
        val args = opts.flatMap {
          case Argument(None | Some(`tf`), args) => args
          case _                                 => Nil
        }
        val mainRunner = f.runner(args.toArray, Array.empty[String], loader)
        tf -> mainRunner
    }
  }

  private[sbt] def allTestGroupsTask(
      s: TaskStreams,
      frameworks: Map[TestFramework, Framework],
      loader: ClassLoader,
      groups: Seq[Tests.Group],
      config: Tests.Execution,
      cp: Classpath,
  ): Initialize[Task[Tests.Output]] = {
    allTestGroupsTask(
      s,
      frameworks,
      loader,
      groups,
      config,
      cp,
      forkedParallelExecution = false,
      javaOptions = Nil,
      strategy = ClassLoaderLayeringStrategy.ScalaLibrary,
      projectId = "",
    )
  }

  private[sbt] def allTestGroupsTask(
      s: TaskStreams,
      frameworks: Map[TestFramework, Framework],
      loader: ClassLoader,
      groups: Seq[Tests.Group],
      config: Tests.Execution,
      cp: Classpath,
      forkedParallelExecution: Boolean
  ): Initialize[Task[Tests.Output]] = {
    allTestGroupsTask(
      s,
      frameworks,
      loader,
      groups,
      config,
      cp,
      forkedParallelExecution,
      javaOptions = Nil,
      strategy = ClassLoaderLayeringStrategy.ScalaLibrary,
      projectId = "",
    )
  }

  private[sbt] def allTestGroupsTask(
      s: TaskStreams,
      frameworks: Map[TestFramework, Framework],
      loader: ClassLoader,
      groups: Seq[Tests.Group],
      config: Tests.Execution,
      cp: Classpath,
      forkedParallelExecution: Boolean,
      javaOptions: Seq[String],
      strategy: ClassLoaderLayeringStrategy,
      projectId: String
  ): Initialize[Task[Tests.Output]] = {
    val runners = createTestRunners(frameworks, loader, config)
    val groupTasks = groups map {
      case Tests.Group(_, tests, runPolicy) =>
        runPolicy match {
          case Tests.SubProcess(opts) =>
            s.log.debug(s"javaOptions: ${opts.runJVMOptions}")
            val forkedConfig = config.copy(parallel = config.parallel && forkedParallelExecution)
            s.log.debug(s"Forking tests - parallelism = ${forkedConfig.parallel}")
            ForkTests(
              runners,
              tests.toVector,
              forkedConfig,
              cp.files,
              opts,
              s.log,
              Tags.ForkedTestGroup
            )
          case Tests.InProcess =>
            if (javaOptions.nonEmpty) {
              s.log.warn("javaOptions will be ignored, fork is set to false")
            }
            Tests(frameworks, loader, runners, tests.toVector, config, s.log)
        }
    }
    val output = Tests.foldTasks(groupTasks, config.parallel)
    val result = output map { out =>
      out.events.foreach {
        case (suite, e) =>
          if (strategy != ClassLoaderLayeringStrategy.Flat ||
              strategy != ClassLoaderLayeringStrategy.ScalaLibrary) {
            (e.throwables ++ e.throwables.flatMap(t => Option(t.getCause)))
              .find { t =>
                t.isInstanceOf[NoClassDefFoundError] ||
                t.isInstanceOf[IllegalAccessError] ||
                t.isInstanceOf[ClassNotFoundException]
              }
              .foreach { t =>
                s.log.error(
                  s"Test suite $suite failed with $t.\nThis may be due to the "
                    + s"ClassLoaderLayeringStrategy ($strategy) used by your task.\n"
                    + "To improve performance and reduce memory, sbt attempts to cache the"
                    + " class loaders used to load the project dependencies.\n"
                    + "The project class files are loaded in a separate class loader that is"
                    + " created for each test run.\nThe test class loader accesses the project"
                    + " dependency classes using the cached project dependency classloader.\nWith"
                    + " this approach, class loading may fail under the following conditions:\n\n"
                    + " * Dependencies use reflection to access classes in your project's"
                    + " classpath.\n   Java serialization/deserialization may cause this.\n"
                    + " * An open package is accessed across layers. If the project's classes"
                    + " access or extend\n   jvm package private classes defined in a"
                    + " project dependency, it may cause an IllegalAccessError\n   because the"
                    + " jvm enforces package private at the classloader level.\n\n"
                    + "These issues, along with others that were not enumerated above, may be"
                    + " resolved by changing the class loader layering strategy.\n"
                    + "The Flat and ScalaLibrary strategies bundle the full project classpath in"
                    + " the same class loader.\nTo use one of these strategies, set the "
                    + " ClassLoaderLayeringStrategy key\nin your configuration, for example:\n\n"
                    + s"set ${projectId}Test / classLoaderLayeringStrategy :="
                    + " ClassLoaderLayeringStrategy.ScalaLibrary\n"
                    + s"set ${projectId}Test / classLoaderLayeringStrategy :="
                    + " ClassLoaderLayeringStrategy.Flat\n\n"
                    + "See ClassLoaderLayeringStrategy.scala for the full list of options."
                )
              }
          }
      }
      val summaries =
        runners map {
          case (tf, r) =>
            Tests.Summary(frameworks(tf).name, r.done())
        }
      out.copy(summaries = summaries)
    }
    Def.value { result }
  }

  def selectedFilter(args: Seq[String]): Seq[String => Boolean] = {
    def matches(nfs: Seq[NameFilter], s: String) = nfs.exists(_.accept(s))

    val (excludeArgs, includeArgs) = args.partition(_.startsWith("-"))

    val includeFilters = includeArgs map GlobFilter.apply
    val excludeFilters = excludeArgs.map(_.substring(1)).map(GlobFilter.apply)

    (includeFilters, excludeArgs) match {
      case (Nil, Nil) => Seq(const(true))
      case (Nil, _)   => Seq((s: String) => !matches(excludeFilters, s))
      case _ =>
        includeFilters.map(f => (s: String) => (f.accept(s) && !matches(excludeFilters, s)))
    }
  }
  def detectTests: Initialize[Task[Seq[TestDefinition]]] =
    Def.task {
      Tests.discover(loadedTestFrameworks.value.values.toList, compile.value, streams.value.log)._1
    }
  def defaultRestrictions: Initialize[Seq[Tags.Rule]] =
    Def.setting {
      val par = parallelExecution.value
      val max = EvaluateTask.SystemProcessors
      Tags.limitAll(if (par) max else 1) ::
        Tags.limit(Tags.ForkedTestGroup, 1) ::
        Tags.exclusiveGroup(Tags.Clean) ::
        Nil
    }

  lazy val packageBase: Seq[Setting[_]] = Seq(
    artifact := Artifact(moduleName.value)
  ) ++ Defaults.globalDefaults(
    Seq(
      packageOptions :== Nil,
      artifactName :== (Artifact.artifactName _)
    )
  )

  lazy val packageConfig: Seq[Setting[_]] =
    inTask(packageBin)(
      Seq(
        packageOptions := {
          val n = name.value
          val ver = version.value
          val org = organization.value
          val orgName = organizationName.value
          val main = mainClass.value
          val old = packageOptions.value
          Package.addSpecManifestAttributes(n, ver, orgName) +:
            Package.addImplManifestAttributes(n, ver, homepage.value, org, orgName) +:
            main.map(Package.MainClass.apply) ++: old
        }
      )
    ) ++
      inTask(packageSrc)(
        Seq(
          packageOptions := Package.addSpecManifestAttributes(
            name.value,
            version.value,
            organizationName.value
          ) +: packageOptions.value
        )
      ) ++
      packageTaskSettings(packageBin, packageBinMappings) ++
      packageTaskSettings(packageSrc, packageSrcMappings) ++
      packageTaskSettings(packageDoc, packageDocMappings) ++
      Seq(Keys.`package` := packageBin.value)

  def packageBinMappings = products map { _ flatMap Path.allSubpaths }
  def packageDocMappings = doc map { Path.allSubpaths(_).toSeq }
  def packageSrcMappings = concatMappings(resourceMappings, sourceMappings)

  private type Mappings = Initialize[Task[Seq[(File, String)]]]
  def concatMappings(as: Mappings, bs: Mappings) =
    (as zipWith bs)((a, b) => (a, b) map { case (a, b) => a ++ b })

  // drop base directories, since there are no valid mappings for these
  def sourceMappings: Initialize[Task[Seq[(File, String)]]] =
    Def.task {
      val sdirs = unmanagedSourceDirectories.value
      val base = baseDirectory.value
      val relative = (f: File) => relativeTo(sdirs)(f).orElse(relativeTo(base)(f)).orElse(flat(f))
      val exclude = Set(sdirs, base)
      unmanagedSources.value.flatMap {
        case s if !exclude(s) => relative(s).map(s -> _)
        case _                => None
      }
    }
  def resourceMappings = relativeMappings(unmanagedResources, unmanagedResourceDirectories)
  def relativeMappings(
      files: ScopedTaskable[Seq[File]],
      dirs: ScopedTaskable[Seq[File]]
  ): Initialize[Task[Seq[(File, String)]]] =
    Def.task {
      val rdirs = dirs.toTask.value.toSet
      val relative = (f: File) => relativeTo(rdirs)(f).orElse(flat(f))
      files.toTask.value.flatMap {
        case r if !rdirs(r) => relative(r).map(r -> _)
        case _              => None
      }
    }
  def collectFiles(
      dirs: ScopedTaskable[Seq[File]],
      filter: ScopedTaskable[FileFilter],
      excludes: ScopedTaskable[FileFilter]
  ): Initialize[Task[Seq[File]]] =
    Def.task {
      dirs.toTask.value.descendantsExcept(filter.toTask.value, excludes.toTask.value).get
    }
  def artifactPathSetting(art: SettingKey[Artifact]): Initialize[File] =
    Def.setting {
      val f = artifactName.value
      crossTarget.value / f(
        ScalaVersion(
          (scalaVersion in artifactName).value,
          (scalaBinaryVersion in artifactName).value
        ),
        projectID.value,
        art.value
      )
    }

  def artifactSetting: Initialize[Artifact] =
    Def.setting {
      val a = artifact.value
      val classifier = artifactClassifier.value
      val cOpt = configuration.?.value
      val cPart = cOpt flatMap {
        case Compile => None
        case Test    => Some(Artifact.TestsClassifier)
        case c       => Some(c.name)
      }
      val combined = cPart.toList ++ classifier.toList
      val configurations = cOpt.map(c => ConfigRef(c.name)).toVector
      if (combined.isEmpty) a.withClassifier(None).withConfigurations(configurations)
      else {
        val classifierString = combined mkString "-"
        a.withClassifier(Some(classifierString))
          .withType(Artifact.classifierType(classifierString))
          .withConfigurations(configurations)
      }
    }

  @deprecated("The configuration(s) should not be decided based on the classifier.", "1.0.0")
  def artifactConfigurations(
      base: Artifact,
      scope: Configuration,
      classifier: Option[String]
  ): Iterable[Configuration] =
    classifier match {
      case Some(c) => Artifact.classifierConf(c) :: Nil
      case None    => scope :: Nil
    }

  def packageTaskSettings(key: TaskKey[File], mappingsTask: Initialize[Task[Seq[(File, String)]]]) =
    inTask(key)(
      Seq(
        key in TaskZero := packageTask.value,
        packageConfiguration := packageConfigurationTask.value,
        mappings := mappingsTask.value,
        packagedArtifact := (artifact.value -> key.value),
        artifact := artifactSetting.value,
        artifactPath := artifactPathSetting(artifact).value
      )
    )

  def packageTask: Initialize[Task[File]] =
    Def.task {
      val config = packageConfiguration.value
      val s = streams.value
      Package(config, s.cacheStoreFactory, s.log)
      config.jar
    }

  def packageConfigurationTask: Initialize[Task[Package.Configuration]] =
    Def.task {
      new Package.Configuration(mappings.value, artifactPath.value, packageOptions.value)
    }

  def askForMainClass(classes: Seq[String]): Option[String] =
    sbt.SelectMainClass(Some(SimpleReader readLine _), classes)

  def pickMainClass(classes: Seq[String]): Option[String] =
    sbt.SelectMainClass(None, classes)

  private def pickMainClassOrWarn(classes: Seq[String], logger: Logger): Option[String] = {
    classes match {
      case multiple if multiple.size > 1 =>
        logger.warn(
          "Multiple main classes detected.  Run 'show discoveredMainClasses' to see the list"
        )
      case _ =>
    }
    pickMainClass(classes)
  }

  /** Implements `cleanFiles` task. */
  private[sbt] def cleanFilesTask: Initialize[Task[Vector[File]]] = Def.task { Vector.empty[File] }

  def bgRunMainTask(
      products: Initialize[Task[Classpath]],
      classpath: Initialize[Task[Classpath]],
      copyClasspath: Initialize[Boolean],
      scalaRun: Initialize[Task[ScalaRun]]
  ): Initialize[InputTask[JobHandle]] = {
    val parser = Defaults.loadForParser(discoveredMainClasses)(
      (s, names) => Defaults.runMainParser(s, names getOrElse Nil)
    )
    Def.inputTask {
      val service = bgJobService.value
      val (mainClass, args) = parser.parsed
      val hashClasspath = (bgHashClasspath in bgRunMain).value
      service.runInBackground(resolvedScoped.value, state.value) { (logger, workingDir) =>
        val cp =
          if (copyClasspath.value)
            service.copyClasspath(products.value, classpath.value, workingDir, hashClasspath)
          else classpath.value
        scalaRun.value.run(mainClass, data(cp), args, logger).get
      }
    }
  }

  def bgRunTask(
      products: Initialize[Task[Classpath]],
      classpath: Initialize[Task[Classpath]],
      mainClassTask: Initialize[Task[Option[String]]],
      copyClasspath: Initialize[Boolean],
      scalaRun: Initialize[Task[ScalaRun]]
  ): Initialize[InputTask[JobHandle]] = {
    import Def.parserToInput
    val parser = Def.spaceDelimited()
    Def.inputTask {
      val service = bgJobService.value
      val mainClass = mainClassTask.value getOrElse sys.error("No main class detected.")
      val hashClasspath = (bgHashClasspath in bgRun).value
      service.runInBackground(resolvedScoped.value, state.value) { (logger, workingDir) =>
        val cp =
          if (copyClasspath.value)
            service.copyClasspath(products.value, classpath.value, workingDir, hashClasspath)
          else classpath.value
        scalaRun.value.run(mainClass, data(cp), parser.parsed, logger).get
      }
    }
  }

  // runMain calls bgRunMain in the background and waits for the result.
  def foregroundRunMainTask: Initialize[InputTask[Unit]] =
    Def.inputTask {
      val handle = bgRunMain.evaluated
      val service = bgJobService.value
      service.waitForTry(handle).get
    }

  // run calls bgRun in the background and waits for the result.
  def foregroundRunTask: Initialize[InputTask[Unit]] =
    Def.inputTask {
      val handle = bgRun.evaluated
      val service = bgJobService.value
      service.waitForTry(handle).get
    }

  def runMainTask(
      classpath: Initialize[Task[Classpath]],
      scalaRun: Initialize[Task[ScalaRun]]
  ): Initialize[InputTask[Unit]] = {
    val parser =
      loadForParser(discoveredMainClasses)((s, names) => runMainParser(s, names getOrElse Nil))
    Def.inputTask {
      val (mainClass, args) = parser.parsed
      scalaRun.value.run(mainClass, data(classpath.value), args, streams.value.log).get
    }
  }

  def runTask(
      classpath: Initialize[Task[Classpath]],
      mainClassTask: Initialize[Task[Option[String]]],
      scalaRun: Initialize[Task[ScalaRun]]
  ): Initialize[InputTask[Unit]] = {
    import Def.parserToInput
    val parser = Def.spaceDelimited()
    Def.inputTask {
      val mainClass = mainClassTask.value getOrElse sys.error("No main class detected.")
      scalaRun.value.run(mainClass, data(classpath.value), parser.parsed, streams.value.log).get
    }
  }

  def runnerTask: Setting[Task[ScalaRun]] = runner := runnerInit.value

  def runnerInit: Initialize[Task[ScalaRun]] = Def.task {
    val tmp = taskTemporaryDirectory.value
    val resolvedScope = resolvedScoped.value.scope
    val si = scalaInstance.value
    val s = streams.value
    val opts = forkOptions.value
    val options = javaOptions.value
    val trap = trapExit.value
    if (fork.value) {
      s.log.debug(s"javaOptions: $options")
      new ForkRun(opts)
    } else {
      if (options.nonEmpty) {
        val mask = ScopeMask(project = false)
        val showJavaOptions = Scope.displayMasked(
          (javaOptions in resolvedScope).scopedKey.scope,
          (javaOptions in resolvedScope).key.label,
          mask
        )
        val showFork = Scope.displayMasked(
          (fork in resolvedScope).scopedKey.scope,
          (fork in resolvedScope).key.label,
          mask
        )
        s.log.warn(s"$showJavaOptions will be ignored, $showFork is set to false")
      }
      new Run(si, trap, tmp)
    }
  }

  private def foreachJobTask(
      f: (BackgroundJobService, JobHandle) => Unit
  ): Initialize[InputTask[Unit]] = {
    val parser: Initialize[State => Parser[Seq[JobHandle]]] = Def.setting { (s: State) =>
      val extracted = Project.extract(s)
      val service = extracted.get(bgJobService)
      // you might be tempted to use the jobList task here, but the problem
      // is that its result gets cached during execution and therefore stale
      BackgroundJobService.jobIdParser(s, service.jobs)
    }
    Def.inputTask {
      val handles = parser.parsed
      for (handle <- handles) {
        f(bgJobService.value, handle)
      }
    }
  }

  def psTask: Initialize[Task[Seq[JobHandle]]] =
    Def.task {
      val xs = bgList.value
      val s = streams.value
      xs foreach { x =>
        s.log.info(x.toString)
      }
      xs
    }

  def bgStopTask: Initialize[InputTask[Unit]] = foreachJobTask { (manager, handle) =>
    manager.stop(handle)
  }

  def bgWaitForTask: Initialize[InputTask[Unit]] = foreachJobTask { (manager, handle) =>
    manager.waitFor(handle)
  }

  def docTaskSettings(key: TaskKey[File] = doc): Seq[Setting[_]] =
    inTask(key)(
      Seq(
        apiMappings ++= {
          val dependencyCp = dependencyClasspath.value
          val log = streams.value.log
          if (autoAPIMappings.value) APIMappings.extract(dependencyCp, log).toMap
          else Map.empty[File, URL]
        },
        fileInputOptions := Seq("-doc-root-content", "-diagrams-dot-path"),
        key in TaskZero := {
          val s = streams.value
          val cs: Compilers = compilers.value
          val srcs = sources.value
          val out = target.value
          val sOpts = scalacOptions.value
          val xapis = apiMappings.value
          val hasScala = srcs.exists(_.name.endsWith(".scala"))
          val hasJava = srcs.exists(_.name.endsWith(".java"))
          val cp = data(dependencyClasspath.value).toList
          val label = nameForSrc(configuration.value.name)
          val fiOpts = fileInputOptions.value
          val reporter = (compilerReporter in compile).value
          (hasScala, hasJava) match {
            case (true, _) =>
              val options = sOpts ++ Opts.doc.externalAPI(xapis)
              val runDoc = Doc.scaladoc(label, s.cacheStoreFactory sub "scala", cs.scalac match {
                case ac: AnalyzingCompiler => ac.onArgs(exported(s, "scaladoc"))
              }, fiOpts)
              runDoc(srcs, cp, out, options, maxErrors.value, s.log)
            case (_, true) =>
              val javadoc =
                sbt.inc.Doc.cachedJavadoc(label, s.cacheStoreFactory sub "java", cs.javaTools)
              javadoc.run(
                srcs.toList,
                cp,
                out,
                javacOptions.value.toList,
                IncToolOptionsUtil.defaultIncToolOptions(),
                s.log,
                reporter
              )
            case _ => () // do nothing
          }
          out
        }
      )
    )

  def mainBgRunTask = mainBgRunTaskForConfig(Select(Runtime))
  def mainBgRunMainTask = mainBgRunMainTaskForConfig(Select(Runtime))

  private[this] def mainBgRunTaskForConfig(c: ScopeAxis[ConfigKey]) =
    bgRun := bgRunTask(
      exportedProductJars,
      fullClasspathAsJars in (This, c, This),
      mainClass in run,
      bgCopyClasspath in bgRun,
      runner in run
    ).evaluated

  private[this] def mainBgRunMainTaskForConfig(c: ScopeAxis[ConfigKey]) =
    bgRunMain := bgRunMainTask(
      exportedProductJars,
      fullClasspathAsJars in (This, c, This),
      bgCopyClasspath in bgRunMain,
      runner in run
    ).evaluated

  def discoverMainClasses(analysis: CompileAnalysis): Seq[String] = analysis match {
    case analysis: Analysis =>
      analysis.infos.allInfos.values.map(_.getMainClasses).flatten.toSeq.sorted
  }

  def consoleProjectTask =
    Def.task {
      ConsoleProject(state.value, (initialCommands in consoleProject).value)(streams.value.log)
      println()
    }

  def consoleTask: Initialize[Task[Unit]] = consoleTask(fullClasspath, console)
  def consoleQuickTask = consoleTask(externalDependencyClasspath, consoleQuick)
  def consoleTask(classpath: TaskKey[Classpath], task: TaskKey[_]): Initialize[Task[Unit]] =
    Def.task {
      val si = (scalaInstance in task).value
      val s = streams.value
      val cpFiles = data((classpath in task).value)
      val fullcp = (cpFiles ++ si.allJars).distinct
      val loader = sbt.internal.inc.classpath.ClasspathUtilities
        .makeLoader(fullcp, si, IO.createUniqueDirectory((taskTemporaryDirectory in task).value))
      val compiler =
        (compilers in task).value.scalac match {
          case ac: AnalyzingCompiler => ac.onArgs(exported(s, "scala"))
        }
      val sc = (scalacOptions in task).value
      val ic = (initialCommands in task).value
      val cc = (cleanupCommands in task).value
      (new Console(compiler))(cpFiles, sc, loader, ic, cc)()(s.log).get
      println()
    }

  private[this] def exported(w: PrintWriter, command: String): Seq[String] => Unit =
    args => w.println((command +: args).mkString(" "))

  private[this] def exported(s: TaskStreams, command: String): Seq[String] => Unit = {
    val w = s.text(ExportStream)
    try exported(w, command)
    finally w.close() // workaround for #937
  }

  def compileTask: Initialize[Task[CompileAnalysis]] = Def.task {
    val setup: Setup = compileIncSetup.value
    val useBinary: Boolean = enableBinaryCompileAnalysis.value
    // TODO - expose bytecode manipulation phase.
    val analysisResult: CompileResult = manipulateBytecode.value
    if (analysisResult.hasModified) {
      val store =
        MixedAnalyzingCompiler.staticCachedStore(setup.cacheFile, !useBinary)
      val contents = AnalysisContents.create(analysisResult.analysis(), analysisResult.setup())
      store.set(contents)
    }
    val map = managedFileStampCache.value
    val analysis = analysisResult.analysis
    import scala.collection.JavaConverters._
    analysis.readStamps.getAllProductStamps.asScala.foreach {
      case (f, s) =>
        map.put(f.toPath, sbt.nio.FileStamp.LastModified(s.getLastModified.orElse(-1L)))
    }
    analysis
  }
  def compileIncrementalTask = Def.task {
    // TODO - Should readAnalysis + saveAnalysis be scoped by the compile task too?
    compileIncrementalTaskImpl(streams.value, (compileInputs in compile).value)
  }
  private val incCompiler = ZincUtil.defaultIncrementalCompiler
  private[this] def compileIncrementalTaskImpl(s: TaskStreams, ci: Inputs): CompileResult = {
    lazy val x = s.text(ExportStream)
    def onArgs(cs: Compilers) = {
      cs.withScalac(
        cs.scalac match {
          case ac: AnalyzingCompiler => ac.onArgs(exported(x, "scalac"))
          case x                     => x
        }
      )
    }
    // .withJavac(
    //  cs.javac.onArgs(exported(x, "javac"))
    //)
    val compilers: Compilers = ci.compilers
    val i = ci.withCompilers(onArgs(compilers))
    try {
      val prev = i.previousResult
      prev.analysis.toOption map { analysis =>
        i.setup.reporter match {
          case r: LanguageServerReporter =>
            r.resetPrevious(analysis)
          case _ => ()
        }
      }
      incCompiler.compile(i, s.log)
    } finally x.close() // workaround for #937
  }
  private def compileAnalysisFileTask: Def.Initialize[Task[File]] =
    Def.task(streams.value.cacheDirectory / compileAnalysisFilename.value)
  def compileIncSetupTask = Def.task {
    val lookup = new PerClasspathEntryLookup {
      private val cachedAnalysisMap = analysisMap(dependencyClasspath.value)
      private val cachedPerEntryDefinesClassLookup = Keys.classpathEntryDefinesClass.value

      override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
        cachedAnalysisMap(classpathEntry).toOptional
      override def definesClass(classpathEntry: File): DefinesClass =
        cachedPerEntryDefinesClassLookup(classpathEntry)
    }
    Setup.of(
      lookup,
      (skip in compile).value,
      // TODO - this is kind of a bad way to grab the cache directory for streams...
      compileAnalysisFileTask.value,
      compilerCache.value,
      incOptions.value,
      (compilerReporter in compile).value,
      None.toOptional,
      // TODO - task / setting for extra,
      Array.empty
    )
  }
  def compileInputsSettings: Seq[Setting[_]] = {
    Seq(
      compileOptions := CompileOptions.of(
        (classDirectory.value +: data(dependencyClasspath.value)).toArray,
        sources.value.toArray,
        classDirectory.value,
        scalacOptions.value.toArray,
        javacOptions.value.toArray,
        maxErrors.value,
        f1(foldMappers(sourcePositionMappers.value)),
        compileOrder.value
      ),
      compilerReporter := {
        new LanguageServerReporter(
          maxErrors.value,
          streams.value.log,
          foldMappers(sourcePositionMappers.value)
        )
      },
      compileInputs := {
        val options = compileOptions.value
        val setup = compileIncSetup.value
        Inputs.of(
          compilers.value,
          options,
          setup,
          previousCompile.value
        )
      }
    )
  }

  private[sbt] def foldMappers[A](mappers: Seq[A => Option[A]]) =
    mappers.foldRight({ p: A =>
      p
    }) {
      (mapper, mappers) =>
        { p: A =>
          mapper(p).getOrElse(mappers(p))
        }
    }
  private[sbt] def none[A]: Option[A] = (None: Option[A])
  private[sbt] def jnone[A]: Optional[A] = none[A].toOptional
  def compileAnalysisSettings: Seq[Setting[_]] = Seq(
    previousCompile := {
      val setup = compileIncSetup.value
      val useBinary: Boolean = enableBinaryCompileAnalysis.value
      val store = MixedAnalyzingCompiler.staticCachedStore(setup.cacheFile, !useBinary)
      store.get().toOption match {
        case Some(contents) =>
          val analysis = Option(contents.getAnalysis).toOptional
          val setup = Option(contents.getMiniSetup).toOptional
          PreviousResult.of(analysis, setup)
        case None => PreviousResult.of(jnone[CompileAnalysis], jnone[MiniSetup])
      }
    }
  )

  def printWarningsTask: Initialize[Task[Unit]] =
    Def.task {
      val analysis = compile.value match { case a: Analysis => a }
      val max = maxErrors.value
      val spms = sourcePositionMappers.value
      val problems =
        analysis.infos.allInfos.values
          .flatMap(i => i.getReportedProblems ++ i.getUnreportedProblems)
      val reporter = new ManagedLoggedReporter(max, streams.value.log, foldMappers(spms))
      problems.foreach(p => reporter.log(p))
    }

  def sbtPluginExtra(m: ModuleID, sbtV: String, scalaV: String): ModuleID =
    m.extra(
        PomExtraDependencyAttributes.SbtVersionKey -> sbtV,
        PomExtraDependencyAttributes.ScalaVersionKey -> scalaV
      )
      .withCrossVersion(Disabled())

  def discoverSbtPluginNames: Initialize[Task[PluginDiscovery.DiscoveredNames]] = Def.taskDyn {
    if (sbtPlugin.value) Def.task(PluginDiscovery.discoverSourceAll(compile.value))
    else Def.task(PluginDiscovery.emptyDiscoveredNames)
  }

  def copyResourcesTask =
    Def.task {
      val t = classDirectory.value
      val dirs = resourceDirectories.value.toSet
      val s = streams.value
      val cacheStore = s.cacheStoreFactory make "copy-resources"
      val flt: File => Option[File] = flat(t)
      val transform: File => Option[File] = (f: File) => rebase(dirs, t)(f).orElse(flt(f))
      val mappings: Seq[(File, File)] = resources.value.flatMap {
        case r if !dirs(r) => transform(r).map(r -> _)
        case _             => None
      }
      s.log.debug("Copy resource mappings: " + mappings.mkString("\n\t", "\n\t", ""))
      Sync.sync(cacheStore)(mappings)
      mappings
    }

  def runMainParser: (State, Seq[String]) => Parser[(String, Seq[String])] = {
    import DefaultParsers._
    (state, mainClasses) =>
      Space ~> token(NotSpace examples mainClasses.toSet) ~ spaceDelimited("<arg>")
  }

  def testOnlyParser: (State, Seq[String]) => Parser[(Seq[String], Seq[String])] = {
    (state, tests) =>
      import DefaultParsers._
      val selectTests = distinctParser(tests.toSet, true)
      val options = (token(Space) ~> token("--") ~> spaceDelimited("<option>")) ?? Nil
      selectTests ~ options
  }

  private def distinctParser(exs: Set[String], raw: Boolean): Parser[Seq[String]] = {
    import DefaultParsers._
    import Parser.and
    val base = token(Space) ~> token(and(NotSpace, not("--", "Unexpected: ---")) examples exs)
    val recurse = base flatMap { ex =>
      val (matching, notMatching) = exs.partition(GlobFilter(ex).accept _)
      distinctParser(notMatching, raw) map { result =>
        if (raw) ex +: result else matching.toSeq ++ result
      }
    }
    recurse ?? Nil
  }

  val CompletionsID = "completions"

  def noAggregation: Seq[Scoped] =
    Seq(run, runMain, bgRun, bgRunMain, console, consoleQuick, consoleProject)
  lazy val disableAggregation = Defaults.globalDefaults(noAggregation map disableAggregate)
  def disableAggregate(k: Scoped) = aggregate in k :== false

  // 1. runnerSettings is added unscoped via JvmPlugin.
  // 2. In addition it's added scoped to run task.
  lazy val runnerSettings: Seq[Setting[_]] = Seq(runnerTask, forkOptions := forkOptionsTask.value)
  private[this] lazy val newRunnerSettings: Seq[Setting[_]] = {
    val unscoped: Seq[Def.Setting[_]] =
      Seq(runner := ClassLoaders.runner.value, forkOptions := forkOptionsTask.value)
    inConfig(Compile)(unscoped) ++ inConfig(Test)(unscoped)
  }

  lazy val baseTasks: Seq[Setting[_]] = projectTasks ++ packageBase

  lazy val configSettings: Seq[Setting[_]] =
    Classpaths.configSettings ++ configTasks ++ configPaths ++ packageConfig ++
      Classpaths.compilerPluginConfig ++ deprecationSettings

  lazy val compileSettings: Seq[Setting[_]] =
    configSettings ++ (mainBgRunMainTask +: mainBgRunTask) ++ Classpaths.addUnmanagedLibrary

  lazy val testSettings: Seq[Setting[_]] = configSettings ++ testTasks

  lazy val itSettings: Seq[Setting[_]] = inConfig(IntegrationTest) {
    testSettings
  }
  lazy val defaultConfigs: Seq[Setting[_]] = inConfig(Compile)(compileSettings) ++
    inConfig(Test)(testSettings) ++
    inConfig(Runtime)(Classpaths.configSettings)

  // These are project level settings that MUST be on every project.
  lazy val coreDefaultSettings: Seq[Setting[_]] =
    projectCore ++ disableAggregation ++ Seq(
      // Missing but core settings
      baseDirectory := thisProject.value.base,
      target := baseDirectory.value / "target",
      // Use (sbtVersion in pluginCrossBuild) to pick the sbt module to depend from the plugin.
      // Because `sbtVersion in pluginCrossBuild` can be scoped to project level,
      // this setting needs to be set here too.
      sbtDependency in pluginCrossBuild := {
        val app = appConfiguration.value
        val id = app.provider.id
        val sv = (sbtVersion in pluginCrossBuild).value
        val scalaV = (scalaVersion in pluginCrossBuild).value
        val binVersion = (scalaBinaryVersion in pluginCrossBuild).value
        val cross = id.crossVersionedValue match {
          case CrossValue.Disabled => Disabled()
          case CrossValue.Full     => CrossVersion.full
          case CrossValue.Binary   => CrossVersion.binary
        }
        val base = ModuleID(id.groupID, id.name, sv).withCrossVersion(cross)
        CrossVersion(scalaV, binVersion)(base).withCrossVersion(Disabled())
      },
      bgHashClasspath := !turbo.value,
      classLoaderLayeringStrategy := {
        if (turbo.value) ClassLoaderLayeringStrategy.AllLibraryJars
        else ClassLoaderLayeringStrategy.ScalaLibrary
      },
    )
  // build.sbt is treated a Scala source of metabuild, so to enable deprecation flag on build.sbt we set the option here.
  lazy val deprecationSettings: Seq[Setting[_]] =
    inConfig(Compile)(
      Seq(
        scalacOptions := {
          val old = scalacOptions.value
          val existing = old.toSet
          val d = "-deprecation"
          if (sbtPlugin.value && !existing(d)) d :: old.toList
          else old
        }
      )
    )

  def dependencyResolutionTask: Def.Initialize[Task[DependencyResolution]] =
    Def.taskDyn {
      if (useCoursier.value) {
        Def.task { CoursierDependencyResolution(csrConfiguration.value) }
      } else
        Def.task {
          IvyDependencyResolution(
            ivyConfiguration.value,
            CustomHttp.okhttpClient.value
          )
        }
    }
}
object Classpaths {
  import Defaults._
  import Keys._

  def concatDistinct[T](
      a: ScopedTaskable[Seq[T]],
      b: ScopedTaskable[Seq[T]]
  ): Initialize[Task[Seq[T]]] = Def.task {
    (a.toTask.value ++ b.toTask.value).distinct
  }
  def concat[T](a: ScopedTaskable[Seq[T]], b: ScopedTaskable[Seq[T]]): Initialize[Task[Seq[T]]] =
    Def.task { a.toTask.value ++ b.toTask.value }
  def concatSettings[T](a: SettingKey[Seq[T]], b: SettingKey[Seq[T]]): Initialize[Seq[T]] =
    Def.setting { a.value ++ b.value }

  lazy val configSettings: Seq[Setting[_]] = classpaths ++ Seq(
    products := makeProducts.value,
    productDirectories := classDirectory.value :: Nil,
    classpathConfiguration := findClasspathConfig(
      internalConfigurationMap.value,
      configuration.value,
      classpathConfiguration.?.value,
      update.value
    )
  )
  private[this] def classpaths: Seq[Setting[_]] =
    Seq(
      externalDependencyClasspath := concat(unmanagedClasspath, managedClasspath).value,
      dependencyClasspath := concat(internalDependencyClasspath, externalDependencyClasspath).value,
      fullClasspath := concatDistinct(exportedProducts, dependencyClasspath).value,
      internalDependencyClasspath := internalDependencies.value,
      unmanagedClasspath := unmanagedDependencies.value,
      managedClasspath := {
        val isMeta = isMetaBuild.value
        val force = reresolveSbtArtifacts.value
        val csr = useCoursier.value
        val app = appConfiguration.value
        val sbtCp0 = app.provider.mainClasspath.toList
        val sbtCp = sbtCp0 map { Attributed.blank(_) }
        val mjars = managedJars(
          classpathConfiguration.value,
          classpathTypes.value,
          update.value
        )
        if (isMeta && !force && !csr) mjars ++ sbtCp
        else mjars
      },
      exportedProducts := trackedExportedProducts(TrackLevel.TrackAlways).value,
      exportedProductsIfMissing := trackedExportedProducts(TrackLevel.TrackIfMissing).value,
      exportedProductsNoTracking := trackedExportedProducts(TrackLevel.NoTracking).value,
      exportedProductJars := trackedExportedJarProducts(TrackLevel.TrackAlways).value,
      exportedProductJarsIfMissing := trackedExportedJarProducts(TrackLevel.TrackIfMissing).value,
      exportedProductJarsNoTracking := trackedExportedJarProducts(TrackLevel.NoTracking).value,
      internalDependencyAsJars := internalDependencyJarsTask.value,
      dependencyClasspathAsJars := concat(internalDependencyAsJars, externalDependencyClasspath).value,
      fullClasspathAsJars := concatDistinct(exportedProductJars, dependencyClasspathAsJars).value,
      unmanagedJars := findUnmanagedJars(
        configuration.value,
        unmanagedBase.value,
        includeFilter in unmanagedJars value,
        excludeFilter in unmanagedJars value
      )
    ).map(exportClasspath) ++ Seq(
      dependencyClasspathFiles := data(dependencyClasspath.value).map(_.toPath),
      dependencyClasspathFiles / outputFileStamps := {
        val cache = managedFileStampCache.value
        val stamper = outputFileStamper.value
        dependencyClasspathFiles.value.flatMap(p => cache.getOrElseUpdate(p, stamper).map(p -> _))
      }
    )

  private[this] def exportClasspath(s: Setting[Task[Classpath]]): Setting[Task[Classpath]] =
    s.mapInitialize(init => Def.task { exportClasspath(streams.value, init.value) })
  private[this] def exportClasspath(s: TaskStreams, cp: Classpath): Classpath = {
    val w = s.text(ExportStream)
    try w.println(Path.makeString(data(cp)))
    finally w.close() // workaround for #937
    cp
  }

  def defaultPackageKeys = Seq(packageBin, packageSrc, packageDoc)
  lazy val defaultPackages: Seq[TaskKey[File]] =
    for (task <- defaultPackageKeys; conf <- Seq(Compile, Test)) yield (task in conf)
  lazy val defaultArtifactTasks: Seq[TaskKey[File]] = makePom +: defaultPackages

  def findClasspathConfig(
      map: Configuration => Configuration,
      thisConfig: Configuration,
      delegated: Option[Configuration],
      report: UpdateReport
  ): Configuration = {
    val defined = report.allConfigurations.toSet
    val search = map(thisConfig) +: (delegated.toList ++ Seq(Compile, Configurations.Default))
    def notFound =
      sys.error(
        "Configuration to use for managed classpath must be explicitly defined when default configurations are not present."
      )
    search find { c =>
      defined contains ConfigRef(c.name)
    } getOrElse notFound
  }

  def packaged(pkgTasks: Seq[TaskKey[File]]): Initialize[Task[Map[Artifact, File]]] =
    enabledOnly(packagedArtifact.toSettingKey, pkgTasks) apply (_.join.map(_.toMap))

  def artifactDefs(pkgTasks: Seq[TaskKey[File]]): Initialize[Seq[Artifact]] =
    enabledOnly(artifact, pkgTasks)

  def enabledOnly[T](key: SettingKey[T], pkgTasks: Seq[TaskKey[File]]): Initialize[Seq[T]] =
    (forallIn(key, pkgTasks) zipWith forallIn(publishArtifact, pkgTasks))(_ zip _ collect {
      case (a, true) => a
    })

  def forallIn[T](
      key: Scoped.ScopingSetting[SettingKey[T]], // should be just SettingKey[T] (mea culpa)
      pkgTasks: Seq[TaskKey[_]],
  ): Initialize[Seq[T]] =
    pkgTasks.map(pkg => key in pkg.scope in pkg).join

  private[this] def publishGlobalDefaults =
    Defaults.globalDefaults(
      Seq(
        publishMavenStyle :== true,
        publishArtifact :== true,
        publishArtifact in Test :== false
      )
    )

  val jvmPublishSettings: Seq[Setting[_]] = Seq(
    artifacts := artifactDefs(defaultArtifactTasks).value,
    packagedArtifacts := packaged(defaultArtifactTasks).value
  )

  val ivyPublishSettings: Seq[Setting[_]] = publishGlobalDefaults ++ Seq(
    artifacts :== Nil,
    packagedArtifacts :== Map.empty,
    crossTarget := target.value,
    makePom := {
      val config = makePomConfiguration.value
      val publisher = Keys.publisher.value
      publisher.makePomFile(ivyModule.value, config, streams.value.log)
      config.file.get
    },
    packagedArtifact in makePom := ((artifact in makePom).value -> makePom.value),
    deliver := deliverTask(makeIvyXmlConfiguration).value,
    deliverLocal := deliverTask(makeIvyXmlLocalConfiguration).value,
    makeIvyXml := deliverTask(makeIvyXmlConfiguration).value,
    publish := publishTask(publishConfiguration).value,
    publishLocal := publishTask(publishLocalConfiguration).value,
    publishM2 := publishTask(publishM2Configuration).value
  )

  private[this] def baseGlobalDefaults =
    Defaults.globalDefaults(
      Seq(
        conflictWarning :== ConflictWarning.default("global"),
        evictionWarningOptions := EvictionWarningOptions.default,
        compatibilityWarningOptions :== CompatibilityWarningOptions.default,
        homepage :== None,
        startYear :== None,
        licenses :== Nil,
        developers :== Nil,
        scmInfo :== None,
        offline :== SysProp.offline,
        defaultConfiguration :== Some(Configurations.Compile),
        dependencyOverrides :== Vector.empty,
        libraryDependencies :== Nil,
        excludeDependencies :== Nil,
        ivyLoggingLevel := (// This will suppress "Resolving..." logs on Jenkins and Travis.
        if (insideCI.value)
          UpdateLogging.Quiet
        else UpdateLogging.Default),
        ivyXML :== NodeSeq.Empty,
        ivyValidate :== false,
        moduleConfigurations :== Nil,
        publishTo :== None,
        resolvers :== Vector.empty,
        useJCenter :== false,
        retrievePattern :== Resolver.defaultRetrievePattern,
        transitiveClassifiers :== Seq(SourceClassifier, DocClassifier),
        sourceArtifactTypes :== Artifact.DefaultSourceTypes.toVector,
        docArtifactTypes :== Artifact.DefaultDocTypes.toVector,
        cleanKeepFiles :== Nil,
        cleanKeepGlobs :== Nil,
        fileOutputs :== Nil,
        sbtDependency := {
          val app = appConfiguration.value
          val id = app.provider.id
          val scalaVersion = app.provider.scalaProvider.version
          val binVersion = binaryScalaVersion(scalaVersion)
          val cross = id.crossVersionedValue match {
            case CrossValue.Disabled => Disabled()
            case CrossValue.Full     => CrossVersion.binary
            case CrossValue.Binary   => CrossVersion.full
          }
          val base = ModuleID(id.groupID, id.name, sbtVersion.value).withCrossVersion(cross)
          CrossVersion(scalaVersion, binVersion)(base).withCrossVersion(Disabled())
        },
        shellPrompt := shellPromptFromState,
        dynamicDependency := { (): Unit },
        transitiveClasspathDependency := { (): Unit },
        transitiveDynamicInputs :== Nil,
      )
    )

  val ivyBaseSettings: Seq[Setting[_]] = baseGlobalDefaults ++ sbtClassifiersTasks ++ Seq(
    conflictWarning := conflictWarning.value.copy(label = Reference.display(thisProjectRef.value)),
    unmanagedBase := baseDirectory.value / "lib",
    normalizedName := Project.normalizeModuleID(name.value),
    isSnapshot := (isSnapshot or version(_ endsWith "-SNAPSHOT")).value,
    description := (description or name).value,
    organization := (organization or normalizedName).value,
    organizationName := (organizationName or organization).value,
    organizationHomepage := (organizationHomepage or homepage).value,
    projectInfo := ModuleInfo(
      name.value,
      description.value,
      homepage.value,
      startYear.value,
      licenses.value.toVector,
      organizationName.value,
      organizationHomepage.value,
      scmInfo.value,
      developers.value.toVector
    ),
    overrideBuildResolvers := appConfiguration(isOverrideRepositories).value,
    externalResolvers := ((
      externalResolvers.?.value,
      resolvers.value,
      appResolvers.value,
      useJCenter.value
    ) match {
      case (Some(delegated), Seq(), _, _) => delegated
      case (_, rs, Some(ars), _)          => ars ++ rs
      case (_, rs, _, uj)                 => Resolver.combineDefaultResolvers(rs.toVector, uj, mavenCentral = true)
    }),
    appResolvers := {
      val ac = appConfiguration.value
      val uj = useJCenter.value
      appRepositories(ac) map { ars =>
        val useMavenCentral = ars contains Resolver.DefaultMavenRepository
        Resolver.reorganizeAppResolvers(ars, uj, useMavenCentral)
      }
    },
    bootResolvers := (appConfiguration map bootRepositories).value,
    fullResolvers :=
      (Def.task {
        val proj = projectResolver.value
        val rs = externalResolvers.value
        bootResolvers.value match {
          case Some(repos) if overrideBuildResolvers.value => proj +: repos
          case _ =>
            val base = if (sbtPlugin.value) sbtResolvers.value ++ rs else rs
            (proj +: base).distinct
        }
      }).value,
    moduleName := normalizedName.value,
    ivyPaths := IvyPaths(baseDirectory.value, bootIvyHome(appConfiguration.value)),
    csrCacheDirectory := {
      val old = csrCacheDirectory.value
      val ip = ivyPaths.value
      val defaultIvyCache = bootIvyHome(appConfiguration.value)
      if (old != LMCoursier.defaultCacheLocation) old
      else if (ip.ivyHome == defaultIvyCache) old
      else
        ip.ivyHome match {
          case Some(home) => home / "coursier-cache"
          case _          => old
        }
    },
    dependencyCacheDirectory := {
      val st = state.value
      BuildPaths.getDependencyDirectory(st, BuildPaths.getGlobalBase(st))
    },
    otherResolvers := Resolver.publishMavenLocal +: publishTo.value.toVector,
    projectResolver := projectResolverTask.value,
    projectDependencies := projectDependenciesTask.value,
    // TODO - Is this the appropriate split?  Ivy defines this simply as
    //        just project + library, while the JVM plugin will define it as
    //        having the additional sbtPlugin + autoScala magikz.
    allDependencies := {
      projectDependencies.value ++ libraryDependencies.value
    },
    allExcludeDependencies := excludeDependencies.value,
    scalaModuleInfo := (scalaModuleInfo or (
      Def.setting {
        Option(
          ScalaModuleInfo(
            (scalaVersion in update).value,
            (scalaBinaryVersion in update).value,
            Vector.empty,
            filterImplicit = false,
            checkExplicit = true,
            overrideScalaVersion = true
          ).withScalaOrganization(scalaOrganization.value)
            .withScalaArtifacts(scalaArtifacts.value.toVector)
        )
      }
    )).value,
    artifactPath in makePom := artifactPathSetting(artifact in makePom).value,
    publishArtifact in makePom := publishMavenStyle.value && publishArtifact.value,
    artifact in makePom := Artifact.pom(moduleName.value),
    projectID := defaultProjectID.value,
    projectID := pluginProjectID.value,
    projectDescriptors := depMap.value,
    updateConfiguration := {
      // Tell the UpdateConfiguration which artifact types are special (for sources and javadocs)
      val specialArtifactTypes = sourceArtifactTypes.value.toSet union docArtifactTypes.value.toSet
      // By default, to retrieve all types *but* these (it's assumed that everything else is binary/resource)
      UpdateConfiguration()
        .withRetrieveManaged(retrieveConfiguration.value)
        .withLogging(ivyLoggingLevel.value)
        .withArtifactFilter(ArtifactTypeFilter.forbid(specialArtifactTypes))
        .withOffline(offline.value)
    },
    retrieveConfiguration := {
      if (retrieveManaged.value)
        Some(
          RetrieveConfiguration()
            .withRetrieveDirectory(managedDirectory.value)
            .withOutputPattern(retrievePattern.value)
            .withSync(retrieveManagedSync.value)
            .withConfigurationsToRetrieve(configurationsToRetrieve.value map { _.toVector })
        )
      else None
    },
    dependencyResolution := dependencyResolutionTask.value,
    publisher := IvyPublisher(ivyConfiguration.value, CustomHttp.okhttpClient.value),
    ivyConfiguration := mkIvyConfiguration.value,
    ivyConfigurations := {
      val confs = thisProject.value.configurations
      (confs ++ confs.map(internalConfigurationMap.value) ++ (if (autoCompilerPlugins.value)
                                                                CompilerPlugin :: Nil
                                                              else Nil)).distinct
    },
    ivyConfigurations ++= Configurations.auxiliary,
    ivyConfigurations ++= {
      if (managedScalaInstance.value && scalaHome.value.isEmpty) Configurations.ScalaTool :: Nil
      else Nil
    },
    // Coursier needs these
    ivyConfigurations := {
      val confs = ivyConfigurations.value
      val names = confs.map(_.name).toSet
      val extraSources =
        if (names("sources"))
          None
        else
          Some(
            Configuration.of(
              id = "Sources",
              name = "sources",
              description = "",
              isPublic = true,
              extendsConfigs = Vector.empty,
              transitive = false
            )
          )

      val extraDocs =
        if (names("docs"))
          None
        else
          Some(
            Configuration.of(
              id = "Docs",
              name = "docs",
              description = "",
              isPublic = true,
              extendsConfigs = Vector.empty,
              transitive = false
            )
          )

      val use = useCoursier.value
      if (use) confs ++ extraSources.toSeq ++ extraDocs.toSeq
      else confs
    },
    moduleSettings := moduleSettings0.value,
    makePomConfiguration := MakePomConfiguration()
      .withFile((artifactPath in makePom).value)
      .withModuleInfo(projectInfo.value)
      .withExtra(pomExtra.value)
      .withProcess(pomPostProcess.value)
      .withFilterRepositories(pomIncludeRepository.value)
      .withAllRepositories(pomAllRepositories.value)
      .withConfigurations(Configurations.defaultMavenConfigurations),
    makeIvyXmlConfiguration := {
      makeIvyXmlConfig(
        publishMavenStyle.value,
        sbt.Classpaths.deliverPattern(crossTarget.value),
        if (isSnapshot.value) "integration" else "release",
        ivyConfigurations.value.map(c => ConfigRef(c.name)).toVector,
        checksums.in(publish).value.toVector,
        ivyLoggingLevel.value,
        isSnapshot.value
      )
    },
    publishConfiguration := {
      publishConfig(
        publishMavenStyle.value,
        deliverPattern(crossTarget.value),
        if (isSnapshot.value) "integration" else "release",
        ivyConfigurations.value.map(c => ConfigRef(c.name)).toVector,
        packagedArtifacts.in(publish).value.toVector,
        checksums.in(publish).value.toVector,
        getPublishTo(publishTo.value).name,
        ivyLoggingLevel.value,
        isSnapshot.value
      )
    },
    makeIvyXmlLocalConfiguration := {
      makeIvyXmlConfig(
        false, //publishMavenStyle.value,
        sbt.Classpaths.deliverPattern(crossTarget.value),
        if (isSnapshot.value) "integration" else "release",
        ivyConfigurations.value.map(c => ConfigRef(c.name)).toVector,
        checksums.in(publish).value.toVector,
        ivyLoggingLevel.value,
        isSnapshot.value,
        optResolverName = Some("local")
      )
    },
    publishLocalConfiguration := publishConfig(
      false, //publishMavenStyle.value,
      deliverPattern(crossTarget.value),
      if (isSnapshot.value) "integration" else "release",
      ivyConfigurations.value.map(c => ConfigRef(c.name)).toVector,
      packagedArtifacts.in(publishLocal).value.toVector,
      checksums.in(publishLocal).value.toVector,
      logging = ivyLoggingLevel.value,
      overwrite = isSnapshot.value
    ),
    publishM2Configuration := publishConfig(
      true,
      deliverPattern(crossTarget.value),
      if (isSnapshot.value) "integration" else "release",
      ivyConfigurations.value.map(c => ConfigRef(c.name)).toVector,
      packagedArtifacts.in(publishM2).value.toVector,
      checksums = checksums.in(publishM2).value.toVector,
      resolverName = Resolver.publishMavenLocal.name,
      logging = ivyLoggingLevel.value,
      overwrite = isSnapshot.value
    ),
    ivySbt := ivySbt0.value,
    ivyModule := { val is = ivySbt.value; new is.Module(moduleSettings.value) },
    allCredentials := LMCoursier.allCredentialsTask.value,
    transitiveUpdate := transitiveUpdateTask.value,
    updateCacheName := {
      val binVersion = scalaBinaryVersion.value
      val suffix = if (crossPaths.value) s"_$binVersion" else ""
      s"update_cache$suffix"
    },
    dependencyPositions := dependencyPositionsTask.value,
    unresolvedWarningConfiguration in update := UnresolvedWarningConfiguration(
      dependencyPositions.value
    ),
    updateFull := (updateTask tag (Tags.Update, Tags.Network)).value,
    update := (updateWithoutDetails("update") tag (Tags.Update, Tags.Network)).value,
    update := {
      val report = update.value
      val log = streams.value.log
      ConflictWarning(conflictWarning.value, report, log)
      report
    },
    evictionWarningOptions in update := evictionWarningOptions.value,
    evictionWarningOptions in evicted := EvictionWarningOptions.full,
    evicted := {
      import ShowLines._
      val report = (updateTask tag (Tags.Update, Tags.Network)).value
      val log = streams.value.log
      val ew =
        EvictionWarning(ivyModule.value, (evictionWarningOptions in evicted).value, report)
      ew.lines foreach { log.warn(_) }
      ew.infoAllTheThings foreach { log.info(_) }
      ew
    },
  ) ++
    inTask(updateClassifiers)(
      Seq(
        classifiersModule := {
          implicit val key = (m: ModuleID) => (m.organization, m.name, m.revision)
          val projectDeps = projectDependencies.value.iterator.map(key).toSet
          val externalModules = update.value.allModules.filterNot(m => projectDeps contains key(m))
          GetClassifiersModule(
            projectID.value,
            None,
            externalModules,
            ivyConfigurations.value.toVector,
            transitiveClassifiers.value.toVector
          )
        },
        dependencyResolution := dependencyResolutionTask.value,
        csrConfiguration := LMCoursier.updateClassifierConfigurationTask.value,
        updateClassifiers in TaskGlobal := LibraryManagement.updateClassifiersTask.value,
      )
    ) ++ Seq(
    csrProject := CoursierInputsTasks.coursierProjectTask.value,
    csrConfiguration := LMCoursier.coursierConfigurationTask.value,
    csrResolvers := CoursierRepositoriesTasks.coursierResolversTask.value,
    csrRecursiveResolvers := CoursierRepositoriesTasks.coursierRecursiveResolversTask.value,
    csrSbtResolvers := CoursierRepositoriesTasks.coursierSbtResolversTask.value,
    csrInterProjectDependencies := CoursierInputsTasks.coursierInterProjectDependenciesTask.value,
    csrExtraProjects := CoursierInputsTasks.coursierExtraProjectsTask.value,
    csrFallbackDependencies := CoursierInputsTasks.coursierFallbackDependenciesTask.value,
  ) ++
    IvyXml.generateIvyXmlSettings() ++
    LMCoursier.publicationsSetting(Seq(Compile, Test).map(c => c -> CConfiguration(c.name)))

  val jvmBaseSettings: Seq[Setting[_]] = Seq(
    libraryDependencies ++= autoLibraryDependency(
      autoScalaLibrary.value && scalaHome.value.isEmpty && managedScalaInstance.value,
      sbtPlugin.value,
      scalaOrganization.value,
      scalaVersion.value
    ),
    // Override the default to handle mixing in the sbtPlugin + scala dependencies.
    allDependencies := {
      val base = projectDependencies.value ++ libraryDependencies.value
      val isPlugin = sbtPlugin.value
      val sbtdeps =
        (sbtDependency in pluginCrossBuild).value.withConfigurations(Some(Provided.name))
      val pluginAdjust =
        if (isPlugin) sbtdeps +: base
        else base
      val sbtOrg = scalaOrganization.value
      val version = scalaVersion.value
      if (scalaHome.value.isDefined || scalaModuleInfo.value.isEmpty || !managedScalaInstance.value)
        pluginAdjust
      else {
        val isDotty = ScalaInstance.isDotty(version)
        ScalaArtifacts.toolDependencies(sbtOrg, version, isDotty) ++ pluginAdjust
      }
    },
    // in case of meta build, exclude all sbt modules from the dependency graph, so we can use the sbt resolved by the launcher
    allExcludeDependencies := {
      val sbtdeps = sbtDependency.value
      val isMeta = isMetaBuild.value
      val force = reresolveSbtArtifacts.value
      val excludes = excludeDependencies.value
      val csr = useCoursier.value
      val o = sbtdeps.organization
      val sbtModulesExcludes = Vector[ExclusionRule](
        o % "sbt",
        o %% "scripted-plugin",
        o %% "librarymanagement-core",
        o %% "librarymanagement-ivy",
        o %% "util-logging",
        o %% "util-position",
        o %% "io"
      )
      if (isMeta && !force && !csr) excludes.toVector ++ sbtModulesExcludes
      else excludes
    },
    dependencyOverrides ++= {
      val isPlugin = sbtPlugin.value
      val app = appConfiguration.value
      val id = app.provider.id
      val sv = (sbtVersion in pluginCrossBuild).value
      val base = ModuleID(id.groupID, "scripted-plugin", sv).withCrossVersion(CrossVersion.binary)
      if (isPlugin) Seq(base)
      else Seq()
    }
  )

  def warnResolversConflict(ress: Seq[Resolver], log: Logger): Unit = {
    val resset = ress.toSet
    for ((name, r) <- resset groupBy (_.name) if r.size > 1) {
      log.warn(
        "Multiple resolvers having different access mechanism configured with same name '" + name + "'. To avoid conflict, Remove duplicate project resolvers (`resolvers`) or rename publishing resolver (`publishTo`)."
      )
    }
  }

  private[sbt] def warnInsecureProtocol(ress: Seq[Resolver], log: Logger): Unit = {
    ress.foreach(_.validateProtocol(log))
  }
  // this warns about .from("http:/...") in ModuleID
  private[sbt] def warnInsecureProtocolInModules(mods: Seq[ModuleID], log: Logger): Unit = {
    mods.foreach(_.validateProtocol(log))
  }

  private[sbt] def defaultProjectID: Initialize[ModuleID] = Def.setting {
    val base = ModuleID(organization.value, moduleName.value, version.value)
      .cross(crossVersion in projectID value)
      .artifacts(artifacts.value: _*)
    apiURL.value match {
      case Some(u) => base.extra(SbtPomExtraProperties.POM_API_KEY -> u.toExternalForm)
      case _       => base
    }
  }
  def pluginProjectID: Initialize[ModuleID] =
    Def.setting {
      if (sbtPlugin.value)
        sbtPluginExtra(
          projectID.value,
          (sbtBinaryVersion in pluginCrossBuild).value,
          (scalaBinaryVersion in pluginCrossBuild).value
        )
      else projectID.value
    }
  private[sbt] def ivySbt0: Initialize[Task[IvySbt]] =
    Def.task {
      Credentials.register(credentials.value, streams.value.log)
      new IvySbt(ivyConfiguration.value, CustomHttp.okhttpClient.value)
    }
  def moduleSettings0: Initialize[Task[ModuleSettings]] = Def.task {
    val deps = allDependencies.value.toVector
    warnInsecureProtocolInModules(deps, streams.value.log)
    ModuleDescriptorConfiguration(projectID.value, projectInfo.value)
      .withValidate(ivyValidate.value)
      .withScalaModuleInfo(scalaModuleInfo.value)
      .withDependencies(deps)
      .withOverrides(dependencyOverrides.value.toVector)
      .withExcludes(allExcludeDependencies.value.toVector)
      .withIvyXML(ivyXML.value)
      .withConfigurations(ivyConfigurations.value.toVector)
      .withDefaultConfiguration(defaultConfiguration.value)
      .withConflictManager(conflictManager.value)
  }

  private[this] def sbtClassifiersGlobalDefaults =
    Defaults.globalDefaults(
      Seq(
        transitiveClassifiers in updateSbtClassifiers ~= (_.filter(_ != DocClassifier))
      )
    )
  def sbtClassifiersTasks =
    sbtClassifiersGlobalDefaults ++
      inTask(updateSbtClassifiers)(
        Seq(
          externalResolvers := {
            val boot = bootResolvers.value
            val explicit = buildStructure.value
              .units(thisProjectRef.value.build)
              .unit
              .plugins
              .pluginData
              .resolvers
            explicit orElse boot getOrElse externalResolvers.value
          },
          ivyConfiguration := InlineIvyConfiguration(
            lock = Option(lock(appConfiguration.value)),
            log = Option(streams.value.log),
            updateOptions = UpdateOptions(),
            paths = Option(ivyPaths.value),
            resolvers = externalResolvers.value.toVector,
            otherResolvers = Vector.empty,
            moduleConfigurations = Vector.empty,
            checksums = checksums.value.toVector,
            managedChecksums = false,
            resolutionCacheDir = Some(crossTarget.value / "resolution-cache"),
          ),
          ivySbt := ivySbt0.value,
          classifiersModule := classifiersModuleTask.value,
          // Redefine scalaVersion and scalaBinaryVersion specifically for the dependency graph used for updateSbtClassifiers task.
          // to fix https://github.com/sbt/sbt/issues/2686
          scalaVersion := appConfiguration.value.provider.scalaProvider.version,
          scalaBinaryVersion := binaryScalaVersion(scalaVersion.value),
          scalaOrganization := ScalaArtifacts.Organization,
          scalaModuleInfo := {
            Some(
              ScalaModuleInfo(
                scalaVersion.value,
                scalaBinaryVersion.value,
                Vector(),
                checkExplicit = false,
                filterImplicit = false,
                overrideScalaVersion = true
              ).withScalaOrganization(scalaOrganization.value)
            )
          },
          dependencyResolution := dependencyResolutionTask.value,
          csrConfiguration := LMCoursier.updateSbtClassifierConfigurationTask.value,
          updateSbtClassifiers in TaskGlobal := (Def.task {
            val lm = dependencyResolution.value
            val s = streams.value
            val is = ivySbt.value
            val mod = classifiersModule.value
            val updateConfig0 = updateConfiguration.value
            val updateConfig = updateConfig0
              .withMetadataDirectory(dependencyCacheDirectory.value)
              .withArtifactFilter(
                updateConfig0.artifactFilter.map(af => af.withInverted(!af.inverted))
              )
            val app = appConfiguration.value
            val srcTypes = sourceArtifactTypes.value
            val docTypes = docArtifactTypes.value
            val log = s.log
            val out = is.withIvy(log)(_.getSettings.getDefaultIvyUserDir)
            val uwConfig = (unresolvedWarningConfiguration in update).value
            withExcludes(out, mod.classifiers, lock(app)) { excludes =>
              // val noExplicitCheck = ivy.map(_.withCheckExplicit(false))
              LibraryManagement.transitiveScratch(
                lm,
                "sbt",
                GetClassifiersConfiguration(
                  mod,
                  excludes.toVector,
                  updateConfig,
                  srcTypes.toVector,
                  docTypes.toVector
                ),
                uwConfig,
                log
              ) match {
                case Left(_)   => ???
                case Right(ur) => ur
              }
            }
          } tag (Tags.Update, Tags.Network)).value
        )
      ) ++
      inTask(scalaCompilerBridgeScope)(
        Seq(
          dependencyResolution := dependencyResolutionTask.value,
          csrConfiguration := LMCoursier.scalaCompilerBridgeConfigurationTask.value,
          csrResolvers := CoursierRepositoriesTasks.coursierResolversTask.value,
          externalResolvers := scalaCompilerBridgeResolvers.value,
          ivyConfiguration := InlineIvyConfiguration(
            lock = Option(lock(appConfiguration.value)),
            log = Option(streams.value.log),
            updateOptions = UpdateOptions(),
            paths = Option(ivyPaths.value),
            resolvers = scalaCompilerBridgeResolvers.value.toVector,
            otherResolvers = Vector.empty,
            moduleConfigurations = Vector.empty,
            checksums = checksums.value.toVector,
            managedChecksums = false,
            resolutionCacheDir = Some(crossTarget.value / "bridge-resolution-cache"),
          )
        )
      ) ++ Seq(
      bootIvyConfiguration := (updateSbtClassifiers / ivyConfiguration).value,
      bootDependencyResolution := (updateSbtClassifiers / dependencyResolution).value,
      scalaCompilerBridgeResolvers := {
        val boot = bootResolvers.value
        val explicit = buildStructure.value
          .units(thisProjectRef.value.build)
          .unit
          .plugins
          .pluginData
          .resolvers
        val ext = externalResolvers.value.toVector
        // https://github.com/sbt/sbt/issues/4408
        val xs = (explicit, boot) match {
          case (Some(ex), Some(b)) => (ex.toVector ++ b.toVector).distinct
          case (Some(ex), None)    => ex.toVector
          case (None, Some(b))     => b.toVector
          case _                   => Vector()
        }
        (xs ++ ext).distinct
      },
      scalaCompilerBridgeDependencyResolution := (scalaCompilerBridgeScope / dependencyResolution).value
    )

  def classifiersModuleTask: Initialize[Task[GetClassifiersModule]] =
    Def.task {
      val classifiers = transitiveClassifiers.value
      val ref = thisProjectRef.value
      val pluginClasspath = loadedBuild.value.units(ref.build).unit.plugins.fullClasspath.toVector
      val pluginJars = pluginClasspath.filter(_.data.isFile) // exclude directories: an approximation to whether they've been published
      val pluginIDs: Vector[ModuleID] = pluginJars.flatMap(_ get moduleID.key)
      GetClassifiersModule(
        projectID.value,
        // TODO: Should it be sbt's scalaModuleInfo?
        scalaModuleInfo.value,
        sbtDependency.value +: pluginIDs,
        // sbt is now on Maven Central, so this has changed from sbt 0.13.
        Vector(Configurations.Default) ++ Configurations.default,
        classifiers.toVector
      )
    }

  def deliverTask(config: TaskKey[PublishConfiguration]): Initialize[Task[File]] =
    Def.task {
      val _ = update.value
      IvyActions.deliver(ivyModule.value, config.value, streams.value.log)
    }

  @deprecated("Use variant without delivery key", "1.1.1")
  def publishTask(
      config: TaskKey[PublishConfiguration],
      deliverKey: TaskKey[_],
  ): Initialize[Task[Unit]] =
    publishTask(config)

  def publishTask(config: TaskKey[PublishConfiguration]): Initialize[Task[Unit]] =
    Def.taskDyn {
      val s = streams.value
      val skp = (skip in publish).value
      val ref = thisProjectRef.value
      if (skp) Def.task { s.log.debug(s"Skipping publish* for ${ref.project}") } else
        Def.task { IvyActions.publish(ivyModule.value, config.value, s.log) }
    } tag (Tags.Publish, Tags.Network)

  val moduleIdJsonKeyFormat: sjsonnew.JsonKeyFormat[ModuleID] =
    new sjsonnew.JsonKeyFormat[ModuleID] {
      import LibraryManagementCodec._
      import sjsonnew.support.scalajson.unsafe._
      val moduleIdFormat: JsonFormat[ModuleID] = implicitly[JsonFormat[ModuleID]]
      def write(key: ModuleID): String =
        CompactPrinter(Converter.toJsonUnsafe(key)(moduleIdFormat))
      def read(key: String): ModuleID =
        Converter.fromJsonUnsafe[ModuleID](Parser.parseUnsafe(key))(moduleIdFormat)
    }

  def withExcludes(out: File, classifiers: Seq[String], lock: xsbti.GlobalLock)(
      f: Map[ModuleID, Vector[ConfigRef]] => UpdateReport
  ): UpdateReport = LibraryManagement.withExcludes(out, classifiers, lock)(f)

  /**
   * Substitute unmanaged jars for managed jars when the major.minor parts of
   * the version are the same for:
   *   1. The Scala version and the `scalaHome` (unmanaged) version are equal.
   *   2. The Scala version and the `declared` (managed) version are equal.
   *
   * Equality is weak, that is, no version qualifier is checked.
   */
  private def unmanagedJarsTask(scalaVersion: String, unmanagedVersion: String, jars: Seq[File]) = {
    (subVersion0: String) =>
      val scalaV = partialVersion(scalaVersion)
      val managedV = partialVersion(subVersion0)
      val unmanagedV = partialVersion(unmanagedVersion)
      (managedV, unmanagedV, scalaV) match {
        case (Some(mv), Some(uv), _) if mv == uv => jars
        case (Some(mv), _, Some(sv)) if mv == sv => jars
        case _                                   => Nil
      }
  }

  def updateTask: Initialize[Task[UpdateReport]] = updateTask0("updateFull", true, true)
  def updateWithoutDetails(label: String): Initialize[Task[UpdateReport]] =
    updateTask0(label, false, false)

  /**
   * cacheLabel - label to identify an update cache
   * includeCallers - include the caller information
   * includeDetails - include module reports for the evicted modules
   */
  private def updateTask0(
      cacheLabel: String,
      includeCallers: Boolean,
      includeDetails: Boolean
  ): Initialize[Task[UpdateReport]] = Def.task {
    val s = streams.value
    val cacheDirectory = crossTarget.value / cacheLabel / updateCacheName.value

    import CacheStoreFactory.jvalueIsoString
    val cacheStoreFactory: CacheStoreFactory = {
      val factory =
        state.value.get(Keys.cacheStoreFactoryFactory).getOrElse(InMemoryCacheStore.factory(0))
      factory(cacheDirectory.toPath, Converter)
    }

    val isRoot = executionRoots.value contains resolvedScoped.value
    val shouldForce = isRoot || {
      forceUpdatePeriod.value match {
        case None => false
        case Some(period) =>
          val fullUpdateOutput = cacheDirectory / "out"
          val now = System.currentTimeMillis
          val diff = now - IO.getModifiedTimeOrZero(fullUpdateOutput)
          val elapsedDuration = new FiniteDuration(diff, TimeUnit.MILLISECONDS)
          fullUpdateOutput.exists() && elapsedDuration > period
      }
    }

    val providedScalaJars: String => Seq[File] = {
      val scalaProvider = appConfiguration.value.provider.scalaProvider
      Defaults.unmanagedScalaInstanceOnly.value match {
        case Some(instance) =>
          unmanagedJarsTask(scalaVersion.value, instance.version, instance.allJars)
        case None =>
          (subVersion: String) =>
            if (scalaProvider.version == subVersion) scalaProvider.jars else Nil
      }
    }

    val state0 = state.value
    val updateConf = {
      // Log captures log messages at all levels, except ivy logs.
      // Use full level when debug is enabled so that ivy logs are shown.
      import UpdateLogging.{ Default, DownloadOnly, Full }
      val conf = updateConfiguration.value
      val maybeUpdateLevel = (logLevel in update).?.value
      val conf1 = maybeUpdateLevel.orElse(state0.get(logLevel.key)) match {
        case Some(Level.Debug) if conf.logging == Default => conf.withLogging(logging = Full)
        case Some(_) if conf.logging == Default           => conf.withLogging(logging = DownloadOnly)
        case _                                            => conf
      }

      // logical clock is folded into UpdateConfiguration
      conf1
        .withLogicalClock(LogicalClock(state0.hashCode))
        .withMetadataDirectory(dependencyCacheDirectory.value)
    }

    val evictionOptions = Def.taskDyn {
      if (executionRoots.value.exists(_.key == evicted.key))
        Def.task(EvictionWarningOptions.empty)
      else Def.task((evictionWarningOptions in update).value)
    }.value

    val extracted = (Project extract state0)
    val isPlugin = sbtPlugin.value
    val thisRef = thisProjectRef.value
    val label =
      if (isPlugin) Reference.display(thisRef)
      else Def.displayRelativeReference(extracted.currentRef, thisRef)

    LibraryManagement.cachedUpdate(
      // LM API
      lm = dependencyResolution.value,
      // Ivy-free ModuleDescriptor
      module = ivyModule.value,
      cacheStoreFactory = cacheStoreFactory,
      label = label,
      updateConf,
      substituteScalaFiles(scalaOrganization.value, _)(providedScalaJars),
      skip = (skip in update).value,
      force = shouldForce,
      depsUpdated = transitiveUpdate.value.exists(!_.stats.cached),
      uwConfig = (unresolvedWarningConfiguration in update).value,
      ewo = evictionOptions,
      mavenStyle = publishMavenStyle.value,
      compatWarning = compatibilityWarningOptions.value,
      includeCallers = includeCallers,
      includeDetails = includeDetails,
      log = s.log
    )
  }

  private[sbt] def dependencyPositionsTask: Initialize[Task[Map[ModuleID, SourcePosition]]] =
    Def.task {
      val projRef = thisProjectRef.value
      val st = state.value
      val s = streams.value
      val cacheStoreFactory = s.cacheStoreFactory sub updateCacheName.value
      import sbt.librarymanagement.LibraryManagementCodec._
      def modulePositions: Map[ModuleID, SourcePosition] =
        try {
          val extracted = (Project extract st)
          val sk = (libraryDependencies in (GlobalScope in projRef)).scopedKey
          val empty = extracted.structure.data set (sk.scope, sk.key, Nil)
          val settings = extracted.structure.settings filter { s: Setting[_] =>
            (s.key.key == libraryDependencies.key) &&
            (s.key.scope.project == Select(projRef))
          }
          Map(settings flatMap {
            case s: Setting[Seq[ModuleID]] @unchecked =>
              s.init.evaluate(empty) map { _ -> s.pos }
          }: _*)
        } catch {
          case NonFatal(_) => Map()
        }

      val outCacheStore = cacheStoreFactory make "output_dsp"
      val f = Tracked.inputChanged(cacheStoreFactory make "input_dsp") {
        (inChanged: Boolean, in: Seq[ModuleID]) =>
          implicit val NoPositionFormat: JsonFormat[NoPosition.type] = asSingleton(NoPosition)
          implicit val LinePositionFormat: IsoLList.Aux[LinePosition, String :*: Int :*: LNil] =
            LList.iso(
              { l: LinePosition =>
                ("path", l.path) :*: ("startLine", l.startLine) :*: LNil
              }, { in: String :*: Int :*: LNil =>
                LinePosition(in.head, in.tail.head)
              }
            )
          implicit val LineRangeFormat: IsoLList.Aux[LineRange, Int :*: Int :*: LNil] = LList.iso(
            { l: LineRange =>
              ("start", l.start) :*: ("end", l.end) :*: LNil
            }, { in: Int :*: Int :*: LNil =>
              LineRange(in.head, in.tail.head)
            }
          )
          implicit val RangePositionFormat
              : IsoLList.Aux[RangePosition, String :*: LineRange :*: LNil] = LList.iso(
            { r: RangePosition =>
              ("path", r.path) :*: ("range", r.range) :*: LNil
            }, { in: String :*: LineRange :*: LNil =>
              RangePosition(in.head, in.tail.head)
            }
          )
          implicit val SourcePositionFormat: JsonFormat[SourcePosition] =
            unionFormat3[SourcePosition, NoPosition.type, LinePosition, RangePosition]

          implicit val midJsonKeyFmt: sjsonnew.JsonKeyFormat[ModuleID] = moduleIdJsonKeyFormat
          val outCache =
            Tracked.lastOutput[Seq[ModuleID], Map[ModuleID, SourcePosition]](outCacheStore) {
              case (_, Some(out)) if !inChanged => out
              case _                            => modulePositions
            }
          outCache(in)
      }
      f(libraryDependencies.value)
    }

  /*
	// can't cache deliver/publish easily since files involved are hidden behind patterns.  publish will be difficult to verify target-side anyway
	def cachedPublish(cacheFile: File)(g: (IvySbt#Module, PublishConfiguration) => Unit, module: IvySbt#Module, config: PublishConfiguration) => Unit =
	{ case module :+: config :+: HNil =>
	/*	implicit val publishCache = publishIC
		val f = cached(cacheFile) { (conf: IvyConfiguration, settings: ModuleSettings, config: PublishConfiguration) =>*/
		    g(module, config)
		/*}
		f(module.owner.configuration :+: module.moduleSettings :+: config :+: HNil)*/
	}*/

  def defaultRepositoryFilter: MavenRepository => Boolean = repo => !repo.root.startsWith("file:")

  def getPublishTo(repo: Option[Resolver]): Resolver =
    repo getOrElse sys.error("Repository for publishing is not specified.")

  def publishConfig(
      publishMavenStyle: Boolean,
      deliverIvyPattern: String,
      status: String,
      configurations: Vector[ConfigRef],
      artifacts: Vector[(Artifact, File)],
      checksums: Vector[String],
      resolverName: String = "local",
      logging: UpdateLogging = UpdateLogging.DownloadOnly,
      overwrite: Boolean = false
  ) =
    PublishConfiguration(
      publishMavenStyle,
      deliverIvyPattern,
      status,
      configurations,
      resolverName,
      artifacts,
      checksums,
      logging,
      overwrite
    )

  def makeIvyXmlConfig(
      publishMavenStyle: Boolean,
      deliverIvyPattern: String,
      status: String,
      configurations: Vector[ConfigRef],
      checksums: Vector[String],
      logging: sbt.librarymanagement.UpdateLogging = UpdateLogging.DownloadOnly,
      overwrite: Boolean = false,
      optResolverName: Option[String] = None
  ) =
    PublishConfiguration(
      publishMavenStyle,
      Some(deliverIvyPattern),
      Some(status),
      Some(configurations),
      optResolverName,
      Vector.empty,
      checksums,
      Some(logging),
      overwrite
    )

  def deliverPattern(outputPath: File): String =
    (outputPath / "[artifact]-[revision](-[classifier]).[ext]").absolutePath

  def projectDependenciesTask: Initialize[Task[Seq[ModuleID]]] =
    Def.task {
      val ref = thisProjectRef.value
      val data = settingsData.value
      val deps = buildDependencies.value
      deps.classpath(ref) flatMap { dep =>
        (projectID in dep.project) get data map {
          _.withConfigurations(dep.configuration).withExplicitArtifacts(Vector.empty)
        }
      }
    }

  private[sbt] def depMap: Initialize[Task[Map[ModuleRevisionId, ModuleDescriptor]]] =
    Def.taskDyn {
      depMap(
        buildDependencies.value classpathTransitiveRefs thisProjectRef.value,
        settingsData.value,
        streams.value.log
      )
    }

  private[sbt] def depMap(
      projects: Seq[ProjectRef],
      data: Settings[Scope],
      log: Logger
  ): Initialize[Task[Map[ModuleRevisionId, ModuleDescriptor]]] =
    Def.value {
      projects.flatMap(ivyModule in _ get data).join.map { mod =>
        mod map { _.dependencyMapping(log) } toMap;
      }
    }

  def projectResolverTask: Initialize[Task[Resolver]] =
    projectDescriptors map { m =>
      val resolver = new ProjectResolver(ProjectResolver.InterProject, m)
      new RawRepository(resolver, resolver.getName)
    }

  def analyzed[T](data: T, analysis: CompileAnalysis) =
    Attributed.blank(data).put(Keys.analysis, analysis)
  def makeProducts: Initialize[Task[Seq[File]]] = Def.task {
    compile.value
    copyResources.value
    classDirectory.value :: Nil
  }
  private[sbt] def trackedExportedProducts(track: TrackLevel): Initialize[Task[Classpath]] =
    Def.task {
      val _ = (packageBin / dynamicDependency).value
      val art = (artifact in packageBin).value
      val module = projectID.value
      val config = configuration.value
      for { (f, analysis) <- trackedExportedProductsImplTask(track).value } yield APIMappings
        .store(analyzed(f, analysis), apiURL.value)
        .put(artifact.key, art)
        .put(moduleID.key, module)
        .put(configuration.key, config)
    }
  private[sbt] def trackedExportedJarProducts(track: TrackLevel): Initialize[Task[Classpath]] =
    Def.task {
      val _ = (packageBin / dynamicDependency).value
      val art = (artifact in packageBin).value
      val module = projectID.value
      val config = configuration.value
      for { (f, analysis) <- trackedJarProductsImplTask(track).value } yield APIMappings
        .store(analyzed(f, analysis), apiURL.value)
        .put(artifact.key, art)
        .put(moduleID.key, module)
        .put(configuration.key, config)
    }
  private[this] def trackedExportedProductsImplTask(
      track: TrackLevel
  ): Initialize[Task[Seq[(File, CompileAnalysis)]]] =
    Def.taskDyn {
      val _ = (packageBin / dynamicDependency).value
      val useJars = exportJars.value
      if (useJars) trackedJarProductsImplTask(track)
      else trackedNonJarProductsImplTask(track)
    }
  private[this] def trackedNonJarProductsImplTask(
      track: TrackLevel
  ): Initialize[Task[Seq[(File, CompileAnalysis)]]] =
    Def.taskDyn {
      val dirs = productDirectories.value
      val view = fileTreeView.value
      def containsClassFile(): Boolean =
        view.list(dirs.map(Glob(_, RecursiveGlob / "*.class"))).nonEmpty
      TrackLevel.intersection(track, exportToInternal.value) match {
        case TrackLevel.TrackAlways =>
          Def.task {
            products.value map { (_, compile.value) }
          }
        case TrackLevel.TrackIfMissing if !containsClassFile() =>
          Def.task {
            products.value map { (_, compile.value) }
          }
        case _ =>
          Def.task {
            val analysis = previousCompile.value.analysis.toOption.getOrElse(Analysis.empty)
            dirs.map(_ -> analysis)
          }
      }
    }
  private[this] def trackedJarProductsImplTask(
      track: TrackLevel
  ): Initialize[Task[Seq[(File, CompileAnalysis)]]] =
    Def.taskDyn {
      val jar = (artifactPath in packageBin).value
      TrackLevel.intersection(track, exportToInternal.value) match {
        case TrackLevel.TrackAlways =>
          Def.task {
            Seq((packageBin.value, compile.value))
          }
        case TrackLevel.TrackIfMissing if !jar.exists =>
          Def.task {
            Seq((packageBin.value, compile.value))
          }
        case _ =>
          Def.task {
            val analysisOpt = previousCompile.value.analysis.toOption
            Seq(jar) map { x =>
              (
                x,
                if (analysisOpt.isDefined) analysisOpt.get
                else Analysis.empty
              )
            }
          }
      }
    }

  def constructBuildDependencies: Initialize[BuildDependencies] =
    loadedBuild(lb => BuildUtil.dependencies(lb.units))

  def internalDependencies: Initialize[Task[Classpath]] =
    Def.taskDyn {
      val _ = (
        (exportedProductsNoTracking / transitiveClasspathDependency).value,
        (exportedProductsIfMissing / transitiveClasspathDependency).value,
        (exportedProducts / transitiveClasspathDependency).value,
        (exportedProductJarsNoTracking / transitiveClasspathDependency).value,
        (exportedProductJarsIfMissing / transitiveClasspathDependency).value,
        (exportedProductJars / transitiveClasspathDependency).value
      )
      internalDependenciesImplTask(
        thisProjectRef.value,
        classpathConfiguration.value,
        configuration.value,
        settingsData.value,
        buildDependencies.value,
        trackInternalDependencies.value
      )
    }
  def internalDependencyJarsTask: Initialize[Task[Classpath]] =
    Def.taskDyn {
      internalDependencyJarsImplTask(
        thisProjectRef.value,
        classpathConfiguration.value,
        configuration.value,
        settingsData.value,
        buildDependencies.value,
        trackInternalDependencies.value
      )
    }
  def unmanagedDependencies: Initialize[Task[Classpath]] =
    Def.taskDyn {
      unmanagedDependencies0(
        thisProjectRef.value,
        configuration.value,
        settingsData.value,
        buildDependencies.value
      )
    }
  def mkIvyConfiguration: Initialize[Task[IvyConfiguration]] =
    Def.task {
      val (rs, other) = (fullResolvers.value.toVector, otherResolvers.value.toVector)
      val s = streams.value
      warnResolversConflict(rs ++: other, s.log)
      warnInsecureProtocol(rs ++: other, s.log)
      InlineIvyConfiguration()
        .withPaths(ivyPaths.value)
        .withResolvers(rs)
        .withOtherResolvers(other)
        .withModuleConfigurations(moduleConfigurations.value.toVector)
        .withLock(lock(appConfiguration.value))
        .withChecksums((checksums in update).value.toVector)
        .withResolutionCacheDir(crossTarget.value / "resolution-cache")
        .withUpdateOptions(updateOptions.value)
        .withLog(s.log)
    }

  import java.util.LinkedHashSet

  import collection.JavaConverters._
  def interSort(
      projectRef: ProjectRef,
      conf: Configuration,
      data: Settings[Scope],
      deps: BuildDependencies
  ): Seq[(ProjectRef, String)] = {
    val visited = (new LinkedHashSet[(ProjectRef, String)]).asScala
    def visit(p: ProjectRef, c: Configuration): Unit = {
      val applicableConfigs = allConfigs(c)
      for (ac <- applicableConfigs) // add all configurations in this project
        visited add (p -> ac.name)
      val masterConfs = names(getConfigurations(projectRef, data).toVector)

      for (ResolvedClasspathDependency(dep, confMapping) <- deps.classpath(p)) {
        val configurations = getConfigurations(dep, data)
        val mapping =
          mapped(confMapping, masterConfs, names(configurations.toVector), "compile", "*->compile")
        // map master configuration 'c' and all extended configurations to the appropriate dependency configuration
        for (ac <- applicableConfigs; depConfName <- mapping(ac.name)) {
          for (depConf <- confOpt(configurations, depConfName))
            if (!visited((dep, depConfName)))
              visit(dep, depConf)
        }
      }
    }
    visit(projectRef, conf)
    visited.toSeq
  }

  def interSortConfigurations(
      projectRef: ProjectRef,
      conf: Configuration,
      data: Settings[Scope],
      deps: BuildDependencies
  ): Seq[(ProjectRef, ConfigRef)] =
    interSort(projectRef, conf, data, deps).map {
      case (projectRef, configName) => (projectRef, ConfigRef(configName))
    }

  private[sbt] def unmanagedDependencies0(
      projectRef: ProjectRef,
      conf: Configuration,
      data: Settings[Scope],
      deps: BuildDependencies
  ): Initialize[Task[Classpath]] =
    Def.value {
      interDependencies(
        projectRef,
        deps,
        conf,
        conf,
        data,
        TrackLevel.TrackAlways,
        true,
        (dep, conf, data, _) => unmanagedLibs(dep, conf, data),
      )
    }
  private[sbt] def internalDependenciesImplTask(
      projectRef: ProjectRef,
      conf: Configuration,
      self: Configuration,
      data: Settings[Scope],
      deps: BuildDependencies,
      track: TrackLevel
  ): Initialize[Task[Classpath]] =
    Def.value { interDependencies(projectRef, deps, conf, self, data, track, false, productsTask) }
  private[sbt] def internalDependencyJarsImplTask(
      projectRef: ProjectRef,
      conf: Configuration,
      self: Configuration,
      data: Settings[Scope],
      deps: BuildDependencies,
      track: TrackLevel
  ): Initialize[Task[Classpath]] =
    Def.value {
      interDependencies(projectRef, deps, conf, self, data, track, false, jarProductsTask)
    }
  private[sbt] def interDependencies(
      projectRef: ProjectRef,
      deps: BuildDependencies,
      conf: Configuration,
      self: Configuration,
      data: Settings[Scope],
      track: TrackLevel,
      includeSelf: Boolean,
      f: (ProjectRef, String, Settings[Scope], TrackLevel) => Task[Classpath]
  ): Task[Classpath] = {
    val visited = interSort(projectRef, conf, data, deps)
    val tasks = (new LinkedHashSet[Task[Classpath]]).asScala
    for ((dep, c) <- visited)
      if (includeSelf || (dep != projectRef) || (conf.name != c && self.name != c))
        tasks += f(dep, c, data, track)

    (tasks.toSeq.join).map(_.flatten.distinct)
  }

  def mapped(
      confString: Option[String],
      masterConfs: Seq[String],
      depConfs: Seq[String],
      default: String,
      defaultMapping: String
  ): String => Seq[String] = {
    lazy val defaultMap = parseMapping(defaultMapping, masterConfs, depConfs, _ :: Nil)
    parseMapping(confString getOrElse default, masterConfs, depConfs, defaultMap)
  }
  def parseMapping(
      confString: String,
      masterConfs: Seq[String],
      depConfs: Seq[String],
      default: String => Seq[String]
  ): String => Seq[String] =
    union(confString.split(";") map parseSingleMapping(masterConfs, depConfs, default))
  def parseSingleMapping(
      masterConfs: Seq[String],
      depConfs: Seq[String],
      default: String => Seq[String]
  )(confString: String): String => Seq[String] = {
    val ms: Seq[(String, Seq[String])] =
      trim(confString.split("->", 2)) match {
        case x :: Nil => for (a <- parseList(x, masterConfs)) yield (a, default(a))
        case x :: y :: Nil =>
          val target = parseList(y, depConfs);
          for (a <- parseList(x, masterConfs)) yield (a, target)
        case _ => sys.error("Invalid configuration '" + confString + "'") // shouldn't get here
      }
    val m = ms.toMap
    s => m.getOrElse(s, Nil)
  }

  def union[A, B](maps: Seq[A => Seq[B]]): A => Seq[B] =
    a => maps.foldLeft(Seq[B]()) { _ ++ _(a) } distinct;

  def parseList(s: String, allConfs: Seq[String]): Seq[String] =
    (trim(s split ",") flatMap replaceWildcard(allConfs)).distinct
  def replaceWildcard(allConfs: Seq[String])(conf: String): Seq[String] = conf match {
    case ""  => Nil
    case "*" => allConfs
    case _   => conf :: Nil
  }

  private def trim(a: Array[String]): List[String] = a.toList.map(_.trim)
  def missingConfiguration(in: String, conf: String) =
    sys.error("Configuration '" + conf + "' not defined in '" + in + "'")
  def allConfigs(conf: Configuration): Seq[Configuration] =
    Dag.topologicalSort(conf)(_.extendsConfigs)

  def getConfigurations(p: ResolvedReference, data: Settings[Scope]): Seq[Configuration] =
    ivyConfigurations in p get data getOrElse Nil
  def confOpt(configurations: Seq[Configuration], conf: String): Option[Configuration] =
    configurations.find(_.name == conf)
  private[sbt] def productsTask(
      dep: ResolvedReference,
      conf: String,
      data: Settings[Scope],
      track: TrackLevel
  ): Task[Classpath] =
    track match {
      case TrackLevel.NoTracking     => getClasspath(exportedProductsNoTracking, dep, conf, data)
      case TrackLevel.TrackIfMissing => getClasspath(exportedProductsIfMissing, dep, conf, data)
      case TrackLevel.TrackAlways    => getClasspath(exportedProducts, dep, conf, data)
    }
  private[sbt] def jarProductsTask(
      dep: ResolvedReference,
      conf: String,
      data: Settings[Scope],
      track: TrackLevel
  ): Task[Classpath] =
    track match {
      case TrackLevel.NoTracking     => getClasspath(exportedProductJarsNoTracking, dep, conf, data)
      case TrackLevel.TrackIfMissing => getClasspath(exportedProductJarsIfMissing, dep, conf, data)
      case TrackLevel.TrackAlways    => getClasspath(exportedProductJars, dep, conf, data)
    }

  def unmanagedLibs(dep: ResolvedReference, conf: String, data: Settings[Scope]): Task[Classpath] =
    getClasspath(unmanagedJars, dep, conf, data)

  def getClasspath(
      key: TaskKey[Classpath],
      dep: ResolvedReference,
      conf: String,
      data: Settings[Scope]
  ): Task[Classpath] =
    (key in (dep, ConfigKey(conf))) get data getOrElse constant(Nil)

  def defaultConfigurationTask(p: ResolvedReference, data: Settings[Scope]): Configuration =
    flatten(defaultConfiguration in p get data) getOrElse Configurations.Default

  def flatten[T](o: Option[Option[T]]): Option[T] = o flatMap idFun

  val sbtIvySnapshots: URLRepository = Resolver.sbtIvyRepo("snapshots")
  val typesafeReleases: URLRepository =
    Resolver.typesafeIvyRepo("releases").withName("typesafe-alt-ivy-releases")
  val sbtPluginReleases: URLRepository = Resolver.sbtPluginRepo("releases")
  val sbtMavenSnapshots: MavenRepository =
    MavenRepository("sbt-maven-snapshot", Resolver.SbtRepositoryRoot + "/" + "maven-snapshots/")

  def modifyForPlugin(plugin: Boolean, dep: ModuleID): ModuleID =
    if (plugin) dep.withConfigurations(Some(Provided.name)) else dep

  def autoLibraryDependency(
      auto: Boolean,
      plugin: Boolean,
      org: String,
      version: String
  ): Seq[ModuleID] =
    if (auto)
      modifyForPlugin(plugin, ModuleID(org, ScalaArtifacts.LibraryID, version)) :: Nil
    else
      Nil

  def addUnmanagedLibrary: Seq[Setting[_]] =
    Seq(unmanagedJars in Compile ++= unmanagedScalaLibrary.value)

  def unmanagedScalaLibrary: Initialize[Task[Seq[File]]] = Def.taskDyn {
    if (autoScalaLibrary.value && scalaHome.value.isDefined)
      Def.task { scalaInstance.value.libraryJars } else
      Def.task { Nil }
  }

  import DependencyFilter._
  def managedJars(config: Configuration, jarTypes: Set[String], up: UpdateReport): Classpath =
    up.filter(configurationFilter(config.name) && artifactFilter(`type` = jarTypes))
      .toSeq
      .map {
        case (_, module, art, file) =>
          Attributed(file)(
            AttributeMap.empty
              .put(artifact.key, art)
              .put(moduleID.key, module)
              .put(configuration.key, config)
          )
      }
      .distinct

  def findUnmanagedJars(
      config: Configuration,
      base: File,
      filter: FileFilter,
      excl: FileFilter
  ): Classpath = {
    (base * (filter -- excl) +++ (base / config.name).descendantsExcept(filter, excl)).classpath
  }
  @deprecated(
    "The method only works for Scala 2, use the overloaded version to support both Scala 2 and Scala 3",
    "1.1.5"
  )
  def autoPlugins(report: UpdateReport, internalPluginClasspath: Seq[File]): Seq[String] =
    autoPlugins(report, internalPluginClasspath, isDotty = false)

  def autoPlugins(
      report: UpdateReport,
      internalPluginClasspath: Seq[File],
      isDotty: Boolean
  ): Seq[String] = {
    val pluginClasspath = report.matching(configurationFilter(CompilerPlugin.name)) ++ internalPluginClasspath
    val plugins =
      sbt.internal.inc.classpath.ClasspathUtilities.compilerPlugins(pluginClasspath, isDotty)
    plugins.map("-Xplugin:" + _.getAbsolutePath).toSeq
  }

  private[this] lazy val internalCompilerPluginClasspath: Initialize[Task[Classpath]] =
    Def.taskDyn {
      val ref = thisProjectRef.value
      val data = settingsData.value
      val deps = buildDependencies.value
      internalDependenciesImplTask(
        ref,
        CompilerPlugin,
        CompilerPlugin,
        data,
        deps,
        TrackLevel.TrackAlways
      )
    }

  lazy val compilerPluginConfig = Seq(
    scalacOptions := {
      val options = scalacOptions.value
      val newPlugins = autoPlugins(
        update.value,
        internalCompilerPluginClasspath.value.files,
        ScalaInstance.isDotty(scalaVersion.value)
      )
      val existing = options.toSet
      if (autoCompilerPlugins.value) options ++ newPlugins.filterNot(existing) else options
    }
  )

  def substituteScalaFiles(scalaOrg: String, report: UpdateReport)(
      scalaJars: String => Seq[File]
  ): UpdateReport =
    report.substitute { (configuration, module, arts) =>
      if (module.organization == scalaOrg) {
        val jarName = module.name + ".jar"
        val replaceWith = scalaJars(module.revision).toVector
          .filter(_.getName == jarName)
          .map(f => (Artifact(f.getName.stripSuffix(".jar")), f))
        if (replaceWith.isEmpty) arts else replaceWith
      } else
        arts
    }

  // try/catch for supporting earlier launchers
  def bootIvyHome(app: xsbti.AppConfiguration): Option[File] =
    try {
      Option(app.provider.scalaProvider.launcher.ivyHome)
    } catch {
      case _: NoSuchMethodError => None
    }

  def bootChecksums(app: xsbti.AppConfiguration): Vector[String] =
    try {
      app.provider.scalaProvider.launcher.checksums.toVector
    } catch {
      case _: NoSuchMethodError => IvySbt.DefaultChecksums
    }

  def isOverrideRepositories(app: xsbti.AppConfiguration): Boolean =
    try app.provider.scalaProvider.launcher.isOverrideRepositories
    catch { case _: NoSuchMethodError => false }

  /** Loads the `appRepositories` configured for this launcher, if supported. */
  def appRepositories(app: xsbti.AppConfiguration): Option[Vector[Resolver]] =
    try {
      Some(app.provider.scalaProvider.launcher.appRepositories.toVector map bootRepository)
    } catch {
      case _: NoSuchMethodError => None
    }

  def bootRepositories(app: xsbti.AppConfiguration): Option[Vector[Resolver]] =
    try {
      Some(app.provider.scalaProvider.launcher.ivyRepositories.toVector map bootRepository)
    } catch {
      case _: NoSuchMethodError => None
    }

  private[this] def mavenCompatible(ivyRepo: xsbti.IvyRepository): Boolean =
    try {
      ivyRepo.mavenCompatible
    } catch { case _: NoSuchMethodError => false }

  private[this] def skipConsistencyCheck(ivyRepo: xsbti.IvyRepository): Boolean =
    try {
      ivyRepo.skipConsistencyCheck
    } catch { case _: NoSuchMethodError => false }

  private[this] def descriptorOptional(ivyRepo: xsbti.IvyRepository): Boolean =
    try {
      ivyRepo.descriptorOptional
    } catch { case _: NoSuchMethodError => false }

  private[this] def bootRepository(repo: xsbti.Repository): Resolver = {
    import xsbti.Predefined
    repo match {
      case m: xsbti.MavenRepository => MavenRepository(m.id, m.url.toString)
      case i: xsbti.IvyRepository =>
        val patterns = Patterns(
          Vector(i.ivyPattern),
          Vector(i.artifactPattern),
          mavenCompatible(i),
          descriptorOptional(i),
          skipConsistencyCheck(i)
        )
        i.url.getProtocol match {
          case "file" =>
            // This hackery is to deal suitably with UNC paths on Windows. Once we can assume Java7, Paths should save us from this.
            val file = IO.toFile(i.url)
            Resolver.file(i.id, file)(patterns)
          case _ => Resolver.url(i.id, i.url)(patterns)
        }
      case p: xsbti.PredefinedRepository =>
        p.id match {
          case Predefined.Local                => Resolver.defaultLocal
          case Predefined.MavenLocal           => Resolver.mavenLocal
          case Predefined.MavenCentral         => Resolver.DefaultMavenRepository
          case Predefined.ScalaToolsReleases   => Resolver.ScalaToolsReleases
          case Predefined.ScalaToolsSnapshots  => Resolver.ScalaToolsSnapshots
          case Predefined.SonatypeOSSReleases  => Resolver.sonatypeRepo("releases")
          case Predefined.SonatypeOSSSnapshots => Resolver.sonatypeRepo("snapshots")
          case unknown =>
            sys.error(
              "Unknown predefined resolver '" + unknown + "'.  This resolver may only be supported in newer sbt versions."
            )
        }
    }
  }

  def shellPromptFromState: State => String = { s: State =>
    val extracted = Project.extract(s)
    (name in extracted.currentRef).get(extracted.structure.data) match {
      case Some(name) => s"sbt:$name" + Def.withColor("> ", Option(scala.Console.CYAN))
      case _          => "> "
    }
  }
}

private[sbt] object Build0 extends BuildExtra

trait BuildExtra extends BuildCommon with DefExtra {
  import Defaults._

  /**
   * Defines an alias given by `name` that expands to `value`.
   * This alias is defined globally after projects are loaded.
   * The alias is undefined when projects are unloaded.
   * Names are restricted to be either alphanumeric or completely symbolic.
   * As an exception, '-' and '_' are allowed within an alphanumeric name.
   */
  def addCommandAlias(name: String, value: String): Seq[Setting[State => State]] = {
    val add = (s: State) => BasicCommands.addAlias(s, name, value)
    val remove = (s: State) => BasicCommands.removeAlias(s, name)
    def compose(setting: SettingKey[State => State], f: State => State) =
      setting in GlobalScope ~= (_ compose f)
    Seq(compose(onLoad, add), compose(onUnload, remove))
  }

  /**
   * Adds Maven resolver plugin.
   */
  def addMavenResolverPlugin: Setting[Seq[ModuleID]] =
    libraryDependencies += sbtPluginExtra(
      ModuleID("org.scala-sbt", "sbt-maven-resolver", sbtVersion.value),
      sbtBinaryVersion.value,
      scalaBinaryVersion.value
    )

  /**
   * Adds `dependency` as an sbt plugin for the specific sbt version `sbtVersion` and Scala version `scalaVersion`.
   * Typically, use the default values for these versions instead of specifying them explicitly.
   */
  def addSbtPlugin(
      dependency: ModuleID,
      sbtVersion: String,
      scalaVersion: String
  ): Setting[Seq[ModuleID]] =
    libraryDependencies += sbtPluginExtra(dependency, sbtVersion, scalaVersion)

  /**
   * Adds `dependency` as an sbt plugin for the specific sbt version `sbtVersion`.
   * Typically, use the default value for this version instead of specifying it explicitly.
   */
  def addSbtPlugin(dependency: ModuleID, sbtVersion: String): Setting[Seq[ModuleID]] =
    libraryDependencies += {
      val scalaV = (scalaBinaryVersion in update).value
      sbtPluginExtra(dependency, sbtVersion, scalaV)
    }

  /**
   * Adds `dependency` as an sbt plugin for the sbt and Scala versions configured by
   * `sbtBinaryVersion` and `scalaBinaryVersion` scoped to `update`.
   */
  def addSbtPlugin(dependency: ModuleID): Setting[Seq[ModuleID]] =
    libraryDependencies += {
      val sbtV = (sbtBinaryVersion in pluginCrossBuild).value
      val scalaV = (scalaBinaryVersion in update).value
      sbtPluginExtra(dependency, sbtV, scalaV)
    }

  /** Transforms `dependency` to be in the auto-compiler plugin configuration. */
  def compilerPlugin(dependency: ModuleID): ModuleID =
    dependency.withConfigurations(Some("plugin->default(compile)"))

  /** Adds `dependency` to `libraryDependencies` in the auto-compiler plugin configuration. */
  def addCompilerPlugin(dependency: ModuleID): Setting[Seq[ModuleID]] =
    libraryDependencies += compilerPlugin(dependency)

  /** Constructs a setting that declares a new artifact `a` that is generated by `taskDef`. */
  def addArtifact(a: Artifact, taskDef: TaskKey[File]): SettingsDefinition = {
    val pkgd = packagedArtifacts := packagedArtifacts.value updated (a, taskDef.value)
    Seq(artifacts += a, pkgd)
  }

  /** Constructs a setting that declares a new artifact `artifact` that is generated by `taskDef`. */
  def addArtifact(
      artifact: Initialize[Artifact],
      taskDef: Initialize[Task[File]]
  ): SettingsDefinition = {
    val artLocal = SettingKey.local[Artifact]
    val taskLocal = TaskKey.local[File]
    val art = artifacts := artLocal.value +: artifacts.value
    val pkgd = packagedArtifacts := packagedArtifacts.value updated (artLocal.value, taskLocal.value)
    Seq(artLocal := artifact.value, taskLocal := taskDef.value, art, pkgd)
  }

  def externalIvySettings(
      file: Initialize[File] = inBase("ivysettings.xml"),
      addMultiResolver: Boolean = true
  ): Setting[Task[IvyConfiguration]] =
    externalIvySettingsURI(file(_.toURI), addMultiResolver)

  def externalIvySettingsURL(
      url: URL,
      addMultiResolver: Boolean = true
  ): Setting[Task[IvyConfiguration]] =
    externalIvySettingsURI(Def.value(url.toURI), addMultiResolver)

  def externalIvySettingsURI(
      uri: Initialize[URI],
      addMultiResolver: Boolean = true
  ): Setting[Task[IvyConfiguration]] = {
    val other = Def.task {
      (
        baseDirectory.value,
        appConfiguration.value,
        projectResolver.value,
        updateOptions.value,
        streams.value
      )
    }
    ivyConfiguration := ((uri zipWith other) {
      case (u, otherTask) =>
        otherTask map {
          case (base, app, pr, uo, s) =>
            val extraResolvers = if (addMultiResolver) Vector(pr) else Vector.empty
            ExternalIvyConfiguration()
              .withLock(lock(app))
              .withBaseDirectory(base)
              .withLog(s.log)
              .withUpdateOptions(uo)
              .withUri(u)
              .withExtraResolvers(extraResolvers)
        }
    }).value
  }

  private[this] def inBase(name: String): Initialize[File] = Def.setting {
    baseDirectory.value / name
  }

  def externalIvyFile(
      file: Initialize[File] = inBase("ivy.xml"),
      iScala: Initialize[Option[ScalaModuleInfo]] = scalaModuleInfo
  ): Setting[Task[ModuleSettings]] =
    moduleSettings := IvyFileConfiguration(
      ivyValidate.value,
      iScala.value,
      file.value,
      managedScalaInstance.value
    )

  def externalPom(
      file: Initialize[File] = inBase("pom.xml"),
      iScala: Initialize[Option[ScalaModuleInfo]] = scalaModuleInfo,
  ): Setting[Task[ModuleSettings]] =
    moduleSettings := PomConfiguration(
      ivyValidate.value,
      iScala.value,
      file.value,
      managedScalaInstance.value,
    )

  def runInputTask(
      config: Configuration,
      mainClass: String,
      baseArguments: String*
  ): Initialize[InputTask[Unit]] =
    Def.inputTask {
      import Def._
      val r = (runner in (config, run)).value
      val cp = (fullClasspath in config).value
      val args = spaceDelimited().parsed
      r.run(mainClass, data(cp), baseArguments ++ args, streams.value.log).get
    }

  def runTask(
      config: Configuration,
      mainClass: String,
      arguments: String*
  ): Initialize[Task[Unit]] =
    Def.task {
      val cp = (fullClasspath in config).value
      val r = (runner in (config, run)).value
      val s = streams.value
      r.run(mainClass, data(cp), arguments, s.log).get
    }

  // public API
  /** Returns a vector of settings that create custom run input task. */
  def fullRunInputTask(
      scoped: InputKey[Unit],
      config: Configuration,
      mainClass: String,
      baseArguments: String*
  ): Vector[Setting[_]] = {
    // TODO: Re-write to avoid InputTask.apply which is deprecated
    // I tried "Def.spaceDelimited().parsed" (after importing Def.parserToInput)
    // but it broke actions/run-task
    // Maybe it needs to be defined inside a Def.inputTask?
    def inputTask[T](f: TaskKey[Seq[String]] => Initialize[Task[T]]): Initialize[InputTask[T]] =
      InputTask.apply(Def.value((s: State) => Def.spaceDelimited()))(f)

    Vector(
      scoped := inputTask { result =>
        initScoped(
          scoped.scopedKey,
          ClassLoaders.runner mapReferenced Project.mapScope(s => s.in(config))
        ).zipWith(Def.task { ((fullClasspath in config).value, streams.value, result.value) }) {
          (rTask, t) =>
            (t, rTask) map {
              case ((cp, s, args), r) =>
                r.run(mainClass, data(cp), baseArguments ++ args, s.log).get
            }
        }
      }.evaluated
    ) ++ inTask(scoped)(forkOptions in config := forkOptionsTask.value)
  }

  // public API
  /** Returns a vector of settings that create custom run task. */
  def fullRunTask(
      scoped: TaskKey[Unit],
      config: Configuration,
      mainClass: String,
      arguments: String*
  ): Vector[Setting[_]] =
    Vector(
      scoped := initScoped(
        scoped.scopedKey,
        ClassLoaders.runner mapReferenced Project.mapScope(s => s.in(config))
      ).zipWith(Def.task { ((fullClasspath in config).value, streams.value) }) {
          case (rTask, t) =>
            (t, rTask) map {
              case ((cp, s), r) =>
                r.run(mainClass, data(cp), arguments, s.log).get
            }
        }
        .value
    ) ++ inTask(scoped)(forkOptions in config := forkOptionsTask.value)

  def initScoped[T](sk: ScopedKey[_], i: Initialize[T]): Initialize[T] =
    initScope(fillTaskAxis(sk.scope, sk.key), i)
  def initScope[T](s: Scope, i: Initialize[T]): Initialize[T] =
    i mapReferenced Project.mapScope(Scope.replaceThis(s))

  /**
   * Disables post-compilation hook for determining tests for tab-completion (such as for 'test-only').
   * This is useful for reducing test:compile time when not running test.
   */
  def noTestCompletion(config: Configuration = Test): Setting[_] =
    inConfig(config)(Seq(definedTests := detectTests.value)).head

  def filterKeys(ss: Seq[Setting[_]], transitive: Boolean = false)(
      f: ScopedKey[_] => Boolean
  ): Seq[Setting[_]] =
    ss filter (s => f(s.key) && (!transitive || s.dependencies.forall(f)))
}

trait DefExtra {
  private[this] val ts: TaskSequential = new TaskSequential {}
  implicit def toTaskSequential(@deprecated("unused", "") d: Def.type): TaskSequential = ts
}

trait BuildCommon {

  /**
   * Allows a String to be used where a `NameFilter` is expected.
   * Asterisks (`*`) in the string are interpreted as wildcards.
   * All other characters must match exactly.  See [[sbt.io.GlobFilter]].
   */
  implicit def globFilter(expression: String): NameFilter = GlobFilter(expression)

  implicit def richAttributed(s: Seq[Attributed[File]]): RichAttributed = new RichAttributed(s)
  implicit def richFiles(s: Seq[File]): RichFiles = new RichFiles(s)
  implicit def richPathFinder(s: PathFinder): RichPathFinder = new RichPathFinder(s)
  final class RichPathFinder private[sbt] (s: PathFinder) {

    /** Converts the `PathFinder` to a `Classpath`, which is an alias for `Seq[Attributed[File]]`. */
    def classpath: Classpath = Attributed blankSeq s.get
  }
  final class RichAttributed private[sbt] (s: Seq[Attributed[File]]) {

    /** Extracts the plain `Seq[File]` from a Classpath (which is a `Seq[Attributed[File]]`).*/
    def files: Seq[File] = Attributed.data(s)
  }
  final class RichFiles private[sbt] (s: Seq[File]) {

    /** Converts the `Seq[File]` to a Classpath, which is an alias for `Seq[Attributed[File]]`. */
    def classpath: Classpath = Attributed blankSeq s
  }

  def overrideConfigs(cs: Configuration*)(
      configurations: Seq[Configuration]
  ): Seq[Configuration] = {
    val existingName = configurations.map(_.name).toSet
    val newByName = cs.map(c => (c.name, c)).toMap
    val overridden = configurations map { conf =>
      newByName.getOrElse(conf.name, conf)
    }
    val newConfigs = cs filter { c =>
      !existingName(c.name)
    }
    overridden ++ newConfigs
  }

  // these are intended for use in in put tasks for creating parsers
  def getFromContext[T](task: TaskKey[T], context: ScopedKey[_], s: State): Option[T] =
    SessionVar.get(SessionVar.resolveContext(task.scopedKey, context.scope, s), s)

  def loadFromContext[T](task: TaskKey[T], context: ScopedKey[_], s: State)(
      implicit f: JsonFormat[T]
  ): Option[T] =
    SessionVar.load(SessionVar.resolveContext(task.scopedKey, context.scope, s), s)

  // intended for use in constructing InputTasks
  def loadForParser[P, T](task: TaskKey[T])(
      f: (State, Option[T]) => Parser[P]
  )(implicit format: JsonFormat[T]): Initialize[State => Parser[P]] =
    loadForParserI(task)(Def value f)(format)
  def loadForParserI[P, T](task: TaskKey[T])(
      init: Initialize[(State, Option[T]) => Parser[P]]
  )(implicit format: JsonFormat[T]): Initialize[State => Parser[P]] =
    Def.setting { (s: State) =>
      init.value(s, loadFromContext(task, resolvedScoped.value, s)(format))
    }

  def getForParser[P, T](
      task: TaskKey[T]
  )(init: (State, Option[T]) => Parser[P]): Initialize[State => Parser[P]] =
    getForParserI(task)(Def value init)
  def getForParserI[P, T](
      task: TaskKey[T]
  )(init: Initialize[(State, Option[T]) => Parser[P]]): Initialize[State => Parser[P]] =
    Def.setting { (s: State) =>
      init.value(s, getFromContext(task, resolvedScoped.value, s))
    }

  // these are for use for constructing Tasks
  def loadPrevious[T](task: TaskKey[T])(implicit f: JsonFormat[T]): Initialize[Task[Option[T]]] =
    Def.task { loadFromContext(task, resolvedScoped.value, state.value)(f) }
  def getPrevious[T](task: TaskKey[T]): Initialize[Task[Option[T]]] =
    Def.task { getFromContext(task, resolvedScoped.value, state.value) }

  private[sbt] def derive[T](s: Setting[T]): Setting[T] =
    Def.derive(s, allowDynamic = true, trigger = _ != streams.key, default = true)
}
