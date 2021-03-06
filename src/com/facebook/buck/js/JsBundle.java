/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.UserFlavor;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.WorkerTool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class JsBundle extends AbstractBuildRuleWithDeclaredAndExtraDeps implements JsBundleOutputs {

  @AddToRuleKey private final String bundleName;

  @AddToRuleKey private final ImmutableSet<String> entryPoints;

  @AddToRuleKey private final ImmutableSortedSet<SourcePath> libraries;

  @AddToRuleKey private final ImmutableList<ImmutableSet<SourcePath>> libraryPathGroups;

  @AddToRuleKey private final WorkerTool worker;

  private static final ImmutableMap<UserFlavor, String> PLATFORM_STRINGS =
      ImmutableMap.of(
          JsFlavors.ANDROID, "android",
          JsFlavors.IOS, "ios");

  private static final ImmutableMap<UserFlavor, String> RAM_BUNDLE_STRINGS =
      ImmutableMap.of(
          JsFlavors.RAM_BUNDLE_INDEXED, "indexed",
          JsFlavors.RAM_BUNDLE_FILES, "files");

  protected JsBundle(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ImmutableSortedSet<SourcePath> libraries,
      ImmutableSet<String> entryPoints,
      ImmutableList<ImmutableSet<SourcePath>> libraryPathGroups,
      String bundleName,
      WorkerTool worker) {
    super(buildTarget, projectFilesystem, params);
    this.bundleName = bundleName;
    this.entryPoints = entryPoints;
    this.libraryPathGroups = libraryPathGroups;
    this.libraries = libraries;
    this.worker = worker;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    final SourcePathResolver sourcePathResolver = context.getSourcePathResolver();
    final SourcePath jsOutputDir = getSourcePathToOutput();
    final SourcePath sourceMapFile = getSourcePathToSourceMap();
    final SourcePath resourcesDir = getSourcePathToResources();

    String jobArgs;
    try {
      jobArgs = getJobArgs(sourcePathResolver, jsOutputDir, sourceMapFile, resourcesDir);
    } catch (IOException ex) {
      throw getArgsException(ex);
    }

    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(jsOutputDir));
    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(sourceMapFile));
    buildableContext.recordArtifact(sourcePathResolver.getRelativePath(resourcesDir));

    return ImmutableList.<Step>builder()
        .addAll(
            MakeCleanDirectoryStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolver.getRelativePath(
                        JsUtil.relativeToOutputRoot(
                            getBuildTarget(), getProjectFilesystem(), "")))))
        .add(
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolver.getRelativePath(jsOutputDir))),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolver.getRelativePath(sourceMapFile).getParent())),
            MkdirStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(),
                    getProjectFilesystem(),
                    sourcePathResolver.getRelativePath(resourcesDir))),
            JsUtil.workerShellStep(
                worker, jobArgs, getBuildTarget(), sourcePathResolver, getProjectFilesystem()))
        .build();
  }

  private String getJobArgs(
      SourcePathResolver sourcePathResolver,
      SourcePath jsOutputDir,
      SourcePath sourceMapFile,
      SourcePath resourcesDir)
      throws IOException {
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    JsonGenerator generator = ObjectMappers.createGenerator(stream);

    ImmutableSortedSet<Flavor> flavors = getBuildTarget().getFlavors();

    generator.writeStartObject();

    generator.writeFieldName("assetsDirPath");
    generator.writeString(sourcePathResolver.getAbsolutePath(resourcesDir).toString());

    generator.writeFieldName("bundlePath");
    generator.writeString(
        String.format("%s/%s", sourcePathResolver.getAbsolutePath(jsOutputDir), bundleName));

    generator.writeStringField("command", "bundle");

    generator.writeFieldName("entryPoints");
    generator.writeStartArray();
    for (String entryPoint : entryPoints) {
      generator.writeString(entryPoint);
    }
    generator.writeEndArray();

    generator.writeFieldName("libraryGroups");
    generator.writeStartArray();
    for (ImmutableSet<SourcePath> libGroup : libraryPathGroups) {
      generator.writeStartArray();
      for (SourcePath lib : libGroup) {
        generator.writeString(sourcePathResolver.getAbsolutePath(lib).toString());
      }
      generator.writeEndArray();
    }
    generator.writeEndArray();

    generator.writeFieldName("libraries");
    generator.writeStartArray();
    for (SourcePath lib : libraries) {
      generator.writeString(sourcePathResolver.getAbsolutePath(lib).toString());
    }
    generator.writeEndArray();

    JsFlavors.PLATFORM_DOMAIN
        .getFlavor(flavors)
        .ifPresent(
            platform -> {
              try {
                generator.writeFieldName("platform");
                generator.writeString(getEnforce(PLATFORM_STRINGS, platform));
              } catch (IOException ex) {
                throw getArgsException(ex);
              }
            });

    JsFlavors.RAM_BUNDLE_DOMAIN
        .getFlavor(flavors)
        .ifPresent(
            ramBundleMode -> {
              try {
                generator.writeFieldName("ramBundle");
                generator.writeString(getEnforce(RAM_BUNDLE_STRINGS, ramBundleMode));
              } catch (IOException ex) {
                throw getArgsException(ex);
              }
            });

    generator.writeFieldName("release");
    generator.writeBoolean(flavors.contains(JsFlavors.RELEASE));

    generator.writeFieldName("rootPath");
    generator.writeString(getProjectFilesystem().getRootPath().toString());

    generator.writeFieldName("sourceMapPath");
    generator.writeString(sourcePathResolver.getAbsolutePath(sourceMapFile).toString());

    generator.writeEndObject();

    generator.close();
    return stream.toString(StandardCharsets.UTF_8.name());
  }

  private String getEnforce(ImmutableMap<UserFlavor, String> map, Flavor flavor) {
    return Preconditions.checkNotNull(map.get(flavor), "no string representation of the flavor");
  }

  private HumanReadableException getArgsException(Throwable ex) {
    return new HumanReadableException(
        ex, "Failed to build args for the JsBundle: " + getBuildTarget().getFullyQualifiedName());
  }

  @Override
  public String getBundleName() {
    return bundleName;
  }
}
