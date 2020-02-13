/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.model;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Comparator.comparing;

import com.google.common.base.Functions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import com.google.devtools.intellij.model.ProjectData;
import com.google.devtools.intellij.model.ProjectData.LocalFileOrOutputArtifact;
import com.google.devtools.intellij.model.ProjectData.TargetSetsToArtifactsMap;
import com.google.idea.blaze.base.command.buildresult.OutputArtifact;
import com.google.idea.blaze.base.command.buildresult.RemoteOutputArtifact;
import com.google.idea.blaze.base.command.info.BlazeConfigurationHandler;
import com.google.idea.blaze.base.ideinfo.ArtifactLocation;
import com.google.idea.blaze.base.ideinfo.ProtoWrapper;
import com.google.idea.blaze.base.ideinfo.TargetIdeInfo;
import com.google.idea.blaze.base.ideinfo.TargetKey;
import com.google.idea.blaze.base.ideinfo.TargetMap;
import com.google.idea.blaze.base.model.primitives.TargetExpression;
import com.intellij.openapi.util.NotNullLazyValue;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import javax.annotation.Nullable;

/** A set of {@link OutputArtifact}s that we track against top level targets. */
public final class TrackedOutputArtifacts
    implements ProtoWrapper<ProjectData.TrackedOutputArtifacts> {
  private final ImmutableSetMultimap<ImmutableSet<String>, OutputArtifact> targetSetsToArtifacts;
  private final NotNullLazyValue<ImmutableSet<String>> configurationMnemonics;
  private final NotNullLazyValue<ImmutableMap<String, OutputArtifact>> pathToArtifacts;
  private final NotNullLazyValue<Boolean> hasRemoteOutputs;

  public static TrackedOutputArtifacts fromProjectData(@Nullable BlazeProjectData projectData) {
    return projectData == null ? EMPTY : projectData.getOutputArtifacts();
  }

  public static final TrackedOutputArtifacts EMPTY =
      new TrackedOutputArtifacts(ImmutableSetMultimap.of());

  public TrackedOutputArtifacts(
      ImmutableSetMultimap<ImmutableSet<String>, OutputArtifact> targetSetsToArtifacts) {
    this.targetSetsToArtifacts = targetSetsToArtifacts;
    this.hasRemoteOutputs =
        NotNullLazyValue.createValue(
            () ->
                targetSetsToArtifacts.values().stream()
                    .anyMatch(RemoteOutputArtifact.class::isInstance));
    this.pathToArtifacts =
        NotNullLazyValue.createValue(
            () ->
                targetSetsToArtifacts.values().stream()
                    .collect(
                        toImmutableMap(OutputArtifact::getRelativePath, Functions.identity())));
    this.configurationMnemonics =
        NotNullLazyValue.createValue(
            () ->
                targetSetsToArtifacts.values().stream()
                    .map(TrackedOutputArtifacts::parseConfigurationMnemonic)
                    .collect(toImmutableSet()));
  }

  @Override
  public ProjectData.TrackedOutputArtifacts toProto() {
    ProjectData.TrackedOutputArtifacts.Builder builder =
        ProjectData.TrackedOutputArtifacts.newBuilder();
    ImmutableList<String> targets =
        targetSetsToArtifacts.keySet().stream()
            .flatMap(Collection::stream)
            .sorted()
            .distinct()
            .collect(toImmutableList());
    ImmutableMap<String, Integer> targetIndices = indexMapOf(targets);
    ImmutableList<OutputArtifact> artifacts =
        ImmutableList.sortedCopyOf(
            comparing(OutputArtifact::getRelativePath), targetSetsToArtifacts.values());
    ImmutableMap<OutputArtifact, Integer> artifactIndices = indexMapOf(artifacts);
    builder.addAllTopLevelTargets(targets);
    builder.addAllOutputArtifacts(ProtoWrapper.mapToProtos(artifacts));
    builder.setTargetSetsToArtifactsMap(
        buildTargetSetsToArtifactsMap(targetIndices, artifactIndices, targetSetsToArtifacts));
    return builder.build();
  }

  public static TrackedOutputArtifacts fromProto(
      ProjectData.TrackedOutputArtifacts proto, File outputBase) {
    List<String> targets = proto.getTopLevelTargetsList();
    List<OutputArtifact> outputs =
        ProtoWrapper.map(
            proto.getOutputArtifactsList(), output -> OutputArtifact.fromProto(output, outputBase));
    ImmutableSetMultimap.Builder<ImmutableSet<String>, OutputArtifact> builder =
        ImmutableSetMultimap.builder();
    proto
        .getTargetSetsToArtifactsMap()
        .getEntriesList()
        .forEach(
            e ->
                builder.putAll(
                    e.getTargetIndicesList().stream().map(targets::get).collect(toImmutableSet()),
                    e.getArtifactIndicesList().stream()
                        .map(outputs::get)
                        .collect(toImmutableSet())));
    return new TrackedOutputArtifacts(builder.build());
  }

  /**
   * Temporary {@link TrackedOutputArtifacts} from serialized a{@link
   * ProjectData.RemoteOutputArtifacts}.
   *
   * <p>They'll be wiped out after the next sync since there's no tracking information from targets.
   */
  static TrackedOutputArtifacts fromRemoteOutputs(ProjectData.RemoteOutputArtifacts proto) {
    return new TrackedOutputArtifacts(
        ImmutableSetMultimap.<ImmutableSet<String>, OutputArtifact>builder()
            .putAll(
                ImmutableSet.of(),
                proto.getArtifactsList().stream()
                    .map(
                        artifact ->
                            LocalFileOrOutputArtifact.newBuilder().setArtifact(artifact).build())
                    .map(artifact -> OutputArtifact.fromProto(artifact, null))
                    .collect(toImmutableList()))
            .build());
  }

  private static TargetSetsToArtifactsMap.Builder buildTargetSetsToArtifactsMap(
      ImmutableMap<String, Integer> targets,
      ImmutableMap<OutputArtifact, Integer> artifacts,
      ImmutableMultimap<ImmutableSet<String>, OutputArtifact> map) {
    TargetSetsToArtifactsMap.Builder builder = TargetSetsToArtifactsMap.newBuilder();
    map.keySet()
        .forEach(
            targetSet ->
                builder.addEntries(
                    TargetSetsToArtifactsMap.Entry.newBuilder()
                        .addAllTargetIndices(
                            targetSet.stream()
                                .map(targets::get)
                                .sorted()
                                .collect(toImmutableList()))
                        .addAllArtifactIndices(
                            map.get(targetSet).stream()
                                .map(artifacts::get)
                                .sorted()
                                .collect(toImmutableList()))));
    return builder;
  }

  public OutputArtifact get(String relativePath) {
    return pathToArtifacts.getValue().get(relativePath);
  }

  public boolean hasRemoteOutputs() {
    return hasRemoteOutputs.getValue();
  }

  private static <T> ImmutableMap<T, Integer> indexMapOf(List<T> list) {
    return IntStream.range(0, list.size())
        .boxed()
        .collect(toImmutableMap(list::get, Functions.identity()));
  }

  public TrackedOutputArtifacts appendNewOutputs(
      ImmutableSetMultimap<ImmutableSet<String>, OutputArtifact> newOutputs) {
    return appendNewOutputs(new TrackedOutputArtifacts(newOutputs));
  }

  public TrackedOutputArtifacts appendNewOutputs(TrackedOutputArtifacts newOutputs) {
    ImmutableSet<String> newTargets =
        newOutputs.targetSetsToArtifacts.keySet().stream()
            .flatMap(Collection::stream)
            .collect(toImmutableSet());
    Multimap<OutputArtifact, String> inverted = HashMultimap.create();
    newOutputs.targetSetsToArtifacts.inverse().forEach(inverted::putAll);
    Multimaps.transformValues(
            targetSetsToArtifacts.inverse(), set -> Sets.difference(set, newTargets))
        .forEach(inverted::putAll);
    Multimap<OutputArtifact, ImmutableSet<String>> collatedInverse =
        Multimaps.transformValues(Multimaps.forMap(inverted.asMap()), ImmutableSet::copyOf);
    return new TrackedOutputArtifacts(ImmutableSetMultimap.copyOf(collatedInverse).inverse());
  }

  public TrackedOutputArtifacts removeUntrackedOutputs(TargetMap trackedTargets) {
    ImmutableSet<String> toKeep = targetsToKeep(trackedTargets);
    Multimap<OutputArtifact, String> inverted = HashMultimap.create();
    Multimaps.transformValues(
            targetSetsToArtifacts.inverse(), set -> Sets.intersection(set, toKeep))
        .forEach(inverted::putAll);
    Multimap<OutputArtifact, ImmutableSet<String>> collatedInverse =
        Multimaps.transformValues(Multimaps.forMap(inverted.asMap()), ImmutableSet::copyOf);
    return new TrackedOutputArtifacts(ImmutableSetMultimap.copyOf(collatedInverse).inverse());
  }

  private static ImmutableSet<String> targetsToKeep(TargetMap targetMap) {
    return targetMap.targets().stream()
        .map(TargetIdeInfo::getKey)
        .map(TargetKey::getLabel)
        .map(TargetExpression::toString)
        .collect(toImmutableSet());
  }

  /**
   * Looks for a {@link OutputArtifact} with a given genfiles-relative path, returning the first
   * such match, or null if none can be found.
   */
  @Nullable
  public OutputArtifact resolveGenfilesPath(String genfilesRelativePath) {
    return configurationMnemonics.getValue().stream()
        .map(m -> findOutputArtifact(String.format("%s/genfiles/%s", m, genfilesRelativePath)))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  @Nullable
  public OutputArtifact findOutputArtifact(ArtifactLocation location) {
    if (location.isSource()) {
      return null;
    }
    String execRootPath = location.getExecutionRootRelativePath();
    if (!execRootPath.startsWith("blaze-out/")) {
      return null;
    }
    return findOutputArtifact(execRootPath.substring("blaze-out/".length()));
  }

  @Nullable
  public OutputArtifact findOutputArtifact(String blazeOutRelativePath) {
    // first try the exact path (forwards compatibility with a future BEP format)
    OutputArtifact file = get(blazeOutRelativePath);
    if (file != null) {
      return file;
    }
    return findAlternatePathFormat(blazeOutRelativePath);
  }

  private static final ImmutableSet<String> POSSIBLY_MISSING_PATH_COMPONENTS =
      ImmutableSet.of("bin", "genfiles", "testlogs");

  @Nullable
  private OutputArtifact findAlternatePathFormat(String path) {
    // temporary code until we can get the full blaze-out-relative path from BEP
    int index = path.indexOf('/');
    int nextIndex = path.indexOf('/', index + 1);
    if (nextIndex == -1) {
      return null;
    }
    String secondPathComponent = path.substring(index + 1, nextIndex);
    if (!POSSIBLY_MISSING_PATH_COMPONENTS.contains(secondPathComponent)) {
      return null;
    }
    String alternatePath =
        String.format("%s%s", path.substring(0, index), path.substring(nextIndex));
    return get(alternatePath);
  }

  private static String parseConfigurationMnemonic(OutputArtifact output) {
    return BlazeConfigurationHandler.getConfigurationMnemonic(output.getRelativePath());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    return Objects.equals(
        targetSetsToArtifacts, ((TrackedOutputArtifacts) o).targetSetsToArtifacts);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(targetSetsToArtifacts);
  }
}
