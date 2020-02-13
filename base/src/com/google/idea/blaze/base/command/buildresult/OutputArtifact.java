/*
 * Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.base.command.buildresult;

import com.google.devtools.intellij.model.ProjectData.LocalFileOrOutputArtifact;
import com.google.idea.blaze.base.filecache.ArtifactState;
import com.google.idea.blaze.base.ideinfo.ProtoWrapper;
import com.intellij.openapi.extensions.ExtensionPointName;
import java.io.File;
import java.util.Arrays;
import java.util.Objects;
import javax.annotation.Nullable;

/** A blaze output artifact, generated during some build action. */
public abstract class OutputArtifact
    implements BlazeArtifact, ProtoWrapper<LocalFileOrOutputArtifact> {

  /** Returns the length of the underlying file in bytes, or 0 if this can't be determined. */
  public abstract long getLength();

  /** The path component related to the build configuration. */
  public abstract String getConfigurationMnemonic();

  /** The blaze-out-relative path. */
  public abstract String getRelativePath();

  /**
   * A key uniquely identifying an artifact between builds. Different versions of an artifact
   * produced from different blaze builds will have the same key.
   *
   * <p>TODO(brendandouglas): remove this in favor of ArtifactState#getKey
   */
  public String getKey() {
    return getRelativePath();
  }

  /**
   * Returns the {@link ArtifactState} for this output, used for serialization/diffing purposes. Can
   * require file system operations.
   */
  @Nullable
  public abstract ArtifactState toArtifactState();

  @Nullable
  public static OutputArtifact fromProto(
      LocalFileOrOutputArtifact proto, @Nullable File outputBase) {
    return Arrays.stream(OutputArtifact.Parser.EP_NAME.getExtensions())
        .map(p -> p.parseProto(proto, outputBase))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }

  /** Converts {@link LocalFileOrOutputArtifact} to {@link OutputArtifact}. */
  public interface Parser {
    ExtensionPointName<OutputArtifact.Parser> EP_NAME =
        ExtensionPointName.create("com.google.idea.blaze.OutputArtifactProtoParser");

    @Nullable
    OutputArtifact parseProto(LocalFileOrOutputArtifact proto, @Nullable File outputBase);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof OutputArtifact)) {
      return false;
    }
    return getRelativePath().equals(((OutputArtifact) obj).getRelativePath());
  }

  @Override
  public int hashCode() {
    return getRelativePath().hashCode();
  }

  @Override
  public String toString() {
    return getRelativePath();
  }
}
