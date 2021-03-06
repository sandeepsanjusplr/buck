/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules;

import com.facebook.buck.config.BuckConfig;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.ExecutableFinder;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.skylark.SkylarkFilesystem;
import com.facebook.buck.json.HybridProjectBuildFileParser;
import com.facebook.buck.json.PythonDslProjectBuildFileParser;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.MissingBuildFileException;
import com.facebook.buck.parser.ParserConfig;
import com.facebook.buck.parser.api.ProjectBuildFileParser;
import com.facebook.buck.parser.api.Syntax;
import com.facebook.buck.parser.options.ProjectBuildFileParserOptions;
import com.facebook.buck.rules.coercer.TypeCoercerFactory;
import com.facebook.buck.skylark.parser.SkylarkProjectBuildFileParser;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultProcessExecutor;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.Threads;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Joiner;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.immutables.value.Value;

/**
 * Represents a single checkout of a code base. Two cells model the same code base if their
 * underlying {@link ProjectFilesystem}s are equal.
 *
 * <p>Should only be constructed by {@link CellProvider}.
 */
@Value.Immutable(prehash = true)
@BuckStyleTuple
abstract class AbstractCell {

  @Value.Auxiliary
  abstract ImmutableSet<Path> getKnownRoots();

  @Value.Auxiliary
  abstract Optional<String> getCanonicalName();

  abstract ProjectFilesystem getFilesystem();

  @Value.Auxiliary
  abstract Watchman getWatchman();

  abstract BuckConfig getBuckConfig();

  @Value.Auxiliary
  abstract KnownBuildRuleTypesFactory getKnownBuildRuleTypesFactory();

  @Value.Auxiliary
  abstract CellProvider getCellProvider();

  @Value.Auxiliary
  abstract SdkEnvironment getSdkEnvironment();

  @Value.Derived
  @Value.Auxiliary
  Supplier<KnownBuildRuleTypes> getKnownBuildRuleTypesSupplier() {
    // Stampede needs the Cell before it can materialize all the files required by
    // knownBuildRuleTypesFactory (specifically java/javac), and as such we need to load this
    // lazily when getKnownBuildRuleTypes() is called.
    return Suppliers.memoize(
            () -> {
              try {
                return getKnownBuildRuleTypesFactory().create(getBuckConfig(), getFilesystem());
              } catch (IOException e) {
                throw new RuntimeException(
                    String.format(
                        "Creation of KnownBuildRuleTypes failed for Cell rooted at [%s].",
                        getFilesystem().getRootPath()),
                    e);
              } catch (InterruptedException e) {
                Threads.interruptCurrentThread();
                throw new RuntimeException(
                    String.format(
                        "Creation of KnownBuildRuleTypes failed for Cell rooted at [%s].",
                        getFilesystem().getRootPath()),
                    e);
              }
            })
        ::get;
  }

  public Path getRoot() {
    return getFilesystem().getRootPath();
  }

  public KnownBuildRuleTypes getKnownBuildRuleTypes() {
    return getKnownBuildRuleTypesSupplier().get();
  }

  public boolean isCompatibleForCaching(Cell other) {
    return getFilesystem().equals(other.getFilesystem())
        && getBuckConfig().equalsForDaemonRestart(other.getBuckConfig())
        && Objects.equals(getSdkEnvironment(), other.getSdkEnvironment());
  }

  public String getBuildFileName() {
    return getBuckConfig().getView(ParserConfig.class).getBuildFileName();
  }

  /**
   * Whether the cell is enforcing buck package boundaries for the package at the passed path.
   *
   * @param path Path of package (or file in a package) relative to the cell root.
   */
  public boolean isEnforcingBuckPackageBoundaries(Path path) {
    ParserConfig configView = getBuckConfig().getView(ParserConfig.class);
    if (!configView.getEnforceBuckPackageBoundary()) {
      return false;
    }

    Path absolutePath = getFilesystem().resolve(path);

    ImmutableList<Path> exceptions = configView.getBuckPackageBoundaryExceptions();
    for (Path exception : exceptions) {
      if (absolutePath.startsWith(exception)) {
        return false;
      }
    }
    return true;
  }

  public Cell getCellIgnoringVisibilityCheck(Path cellPath) {
    return getCellProvider().getCellByPath(cellPath);
  }

  public Cell getCell(Path cellPath) {
    if (!getKnownRoots().contains(cellPath)) {
      throw new HumanReadableException(
          "Unable to find repository rooted at %s. Known roots are:\n  %s",
          cellPath, Joiner.on(",\n  ").join(getKnownRoots()));
    }
    return getCellIgnoringVisibilityCheck(cellPath);
  }

  public Cell getCell(BuildTarget target) {
    return getCell(target.getCellPath());
  }

  public Optional<Cell> getCellIfKnown(BuildTarget target) {
    if (getKnownRoots().contains(target.getCellPath())) {
      return Optional.of(getCell(target));
    }
    return Optional.empty();
  }

  /**
   * Returns a list of all cells, including this cell. If this cell is the root, getAllCells will
   * necessarily return all possible cells that this build may interact with, since the root cell is
   * required to declare a mapping for all cell names.
   */
  public ImmutableList<Cell> getAllCells() {
    return RichStream.from(getKnownRoots())
        .concat(RichStream.of(getRoot()))
        .distinct()
        .map(getCellProvider()::getCellByPath)
        .toImmutableList();
  }

  /** @return all loaded {@link Cell}s that are children of this {@link Cell}. */
  public ImmutableMap<Path, Cell> getLoadedCells() {
    return getCellProvider().getLoadedCells();
  }

  public Description<?> getDescription(BuildRuleType type) {
    return getKnownBuildRuleTypes().getDescription(type);
  }

  public BuildRuleType getBuildRuleType(String rawType) {
    return getKnownBuildRuleTypes().getBuildRuleType(rawType);
  }

  public ImmutableSet<Description<?>> getAllDescriptions() {
    return getKnownBuildRuleTypes().getAllDescriptions();
  }

  /**
   * For use in performance-sensitive code or if you don't care if the build file actually exists,
   * otherwise prefer {@link #getAbsolutePathToBuildFile(BuildTarget)}.
   *
   * @param target target to look up
   * @return path which may or may not exist.
   */
  public Path getAbsolutePathToBuildFileUnsafe(BuildTarget target) {
    Cell targetCell = getCell(target);
    ProjectFilesystem targetFilesystem = targetCell.getFilesystem();
    return targetFilesystem.resolve(target.getBasePath()).resolve(targetCell.getBuildFileName());
  }

  public Path getAbsolutePathToBuildFile(BuildTarget target) throws MissingBuildFileException {
    Path buildFile = getAbsolutePathToBuildFileUnsafe(target);
    Cell cell = getCell(target);
    if (!cell.getFilesystem().isFile(buildFile)) {

      throw new MissingBuildFileException(
          target.getFullyQualifiedName(),
          target
              .getBasePath()
              .resolve(cell.getBuckConfig().getView(ParserConfig.class).getBuildFileName()));
    }
    return buildFile;
  }

  /**
   * Callers are responsible for managing the life-cycle of the created {@link
   * ProjectBuildFileParser}.
   */
  public ProjectBuildFileParser createBuildFileParser(
      TypeCoercerFactory typeCoercerFactory, Console console, BuckEventBus eventBus) {
    return createBuildFileParser(
        typeCoercerFactory, console, eventBus, /* enableProfiling */ false);
  }

  /**
   * Same as @{{@link #createBuildFileParser(TypeCoercerFactory, Console, BuckEventBus)}} but
   * provides a way to configure whether parse profiling should be enabled
   */
  public ProjectBuildFileParser createBuildFileParser(
      TypeCoercerFactory typeCoercerFactory,
      Console console,
      BuckEventBus eventBus,
      boolean enableProfiling) {

    ParserConfig parserConfig = getBuckConfig().getView(ParserConfig.class);

    boolean useWatchmanGlob =
        parserConfig.getGlobHandler() == ParserConfig.GlobHandler.WATCHMAN
            && getWatchman().hasWildmatchGlob();
    boolean watchmanGlobStatResults =
        parserConfig.getWatchmanGlobSanityCheck() == ParserConfig.WatchmanGlobSanityCheck.STAT;
    boolean watchmanUseGlobGenerator =
        getWatchman().getCapabilities().contains(Watchman.Capability.GLOB_GENERATOR);
    boolean useMercurialGlob = parserConfig.getGlobHandler() == ParserConfig.GlobHandler.MERCURIAL;
    String pythonInterpreter = parserConfig.getPythonInterpreter(new ExecutableFinder());
    Optional<String> pythonModuleSearchPath = parserConfig.getPythonModuleSearchPath();

    ProjectBuildFileParserOptions buildFileParserOptions =
        ProjectBuildFileParserOptions.builder()
            .setEnableProfiling(enableProfiling)
            .setProjectRoot(getFilesystem().getRootPath())
            .setCellRoots(getCellPathResolver().getCellPaths())
            .setCellName(getCanonicalName().orElse(""))
            .setFreezeGlobals(parserConfig.getFreezeGlobals())
            .setPythonInterpreter(pythonInterpreter)
            .setPythonModuleSearchPath(pythonModuleSearchPath)
            .setAllowEmptyGlobs(parserConfig.getAllowEmptyGlobs())
            .setIgnorePaths(getFilesystem().getIgnorePaths())
            .setBuildFileName(getBuildFileName())
            .setDefaultIncludes(parserConfig.getDefaultIncludes())
            .setDescriptions(getAllDescriptions())
            .setUseWatchmanGlob(useWatchmanGlob)
            .setWatchmanGlobStatResults(watchmanGlobStatResults)
            .setWatchmanUseGlobGenerator(watchmanUseGlobGenerator)
            .setWatchman(getWatchman())
            .setWatchmanQueryTimeoutMs(parserConfig.getWatchmanQueryTimeoutMs())
            .setUseMercurialGlob(useMercurialGlob)
            .setRawConfig(getBuckConfig().getRawConfigForParser())
            .setBuildFileImportWhitelist(parserConfig.getBuildFileImportWhitelist())
            .build();
    PythonDslProjectBuildFileParser pythonDslProjectBuildFileParser =
        new PythonDslProjectBuildFileParser(
            buildFileParserOptions,
            typeCoercerFactory,
            getBuckConfig().getEnvironment(),
            eventBus,
            new DefaultProcessExecutor(console));
    if (parserConfig.isPolyglotParsingEnabled()) {
      return HybridProjectBuildFileParser.using(
          ImmutableMap.of(
              Syntax.PYTHON_DSL,
              pythonDslProjectBuildFileParser,
              Syntax.SKYLARK,
              SkylarkProjectBuildFileParser.using(
                  buildFileParserOptions,
                  eventBus,
                  SkylarkFilesystem.using(getFilesystem()),
                  typeCoercerFactory)),
          parserConfig.getDefaultBuildFileSyntax());
    }
    return pythonDslProjectBuildFileParser;
  }

  public CellPathResolver getCellPathResolver() {
    return getBuckConfig().getCellPathResolver();
  }

  public void ensureConcreteFilesExist(BuckEventBus eventBus) {
    getFilesystem().ensureConcreteFilesExist(eventBus);
  }
}
