/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.android.rendering;

import com.google.common.collect.ImmutableMap;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.JavaPsiFacadeImpl;
import com.intellij.psi.search.GlobalSearchScope;

/** Unit tests for {@link BlazeRenderErrorContributor}. */
final class BlazeRenderErrorContributorTestCompat {
  private BlazeRenderErrorContributorTestCompat() {}

  static class MockJavaPsiFacade extends JavaPsiFacadeImpl {
    private ImmutableMap<String, PsiClass> classes;

    public MockJavaPsiFacade(
        Project project, PsiManager psiManager, ImmutableMap<String, PsiClass> classes) {
      super(project, psiManager, null, null);
      this.classes = classes;
    }

    @Override
    public PsiClass findClass(String qualifiedName, GlobalSearchScope scope) {
      return classes.get(qualifiedName);
    }
  }
}
