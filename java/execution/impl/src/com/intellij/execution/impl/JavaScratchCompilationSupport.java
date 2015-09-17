/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.execution.impl;

import com.intellij.compiler.options.CompileStepBeforeRun;
import com.intellij.execution.configurations.ModuleBasedConfiguration;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.*;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdk;
import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.JavaSdkVersion;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author Eugene Zhuravlev
 *         Date: 08-Sep-15
 */
public class JavaScratchCompilationSupport implements ProjectComponent, CompileTask{

  public JavaScratchCompilationSupport(Project project, CompilerManager compileManager) {
    compileManager.addAfterTask(this);
  }

  @Override
  public boolean execute(CompileContext context) {
    final Project project = context.getProject();

    final RunConfiguration configuration = CompileStepBeforeRun.getRunConfiguration(context);
    if (!(configuration instanceof ModuleBasedConfiguration)) {
      return true;
    }
    final String scratchUrl = JavaScratchRunConfigurationExtension.getScratchFileUrl(configuration);
    if (scratchUrl == null) {
      return true;
    }
    final Module configModule = ((ModuleBasedConfiguration)configuration).getConfigurationModule().getModule();
    if (configModule == null) {
      context.addMessage(CompilerMessageCategory.ERROR, "A module must be specified for the run configuration", scratchUrl, -1, -1);
      return true;
    }
    final ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(configModule);
    final Sdk targetSdk = moduleRootManager.getSdk();
    if (targetSdk == null) {
      context.addMessage(CompilerMessageCategory.ERROR, "Cannot find associated SDK for run configuration module \"" + configModule.getName() + "\".\nPlease check project settings.", scratchUrl, -1, -1);
      return true;
    }
    if (!(targetSdk.getSdkType() instanceof JavaSdkType)) {
      context.addMessage(CompilerMessageCategory.ERROR, "Expected Java SDK for run configuration module \"" + configModule.getName() + "\".\nPlease check project settings.", scratchUrl, -1, -1);
      return true;
    }

    final File outputDir = JavaScratchRunConfigurationExtension.getScratchOutputDirectory(project);
    if (outputDir == null) { // should not happen for normal projects
      return true;
    }
    FileUtil.delete(outputDir); // perform cleanup

    try {
      final File scratchFile = new File(VirtualFileManager.extractPath(scratchUrl));
      File srcFile = scratchFile;
      if (!StringUtil.endsWith(srcFile.getName(), ".java")) {

        final File srcDir = JavaScratchRunConfigurationExtension.getScratchTempDirectory(project);
        if (srcDir == null) { // should not happen for normal projects
          return true;
        }
        FileUtil.delete(srcDir); // perform cleanup

        final String srcFileName = ApplicationManager.getApplication().runReadAction(new Computable<String>() {
          @Override
          public String compute() {
            final VirtualFile vFile = VirtualFileManager.getInstance().findFileByUrl(scratchUrl);
            if (vFile != null) {
              final PsiFile psiFile = PsiManager.getInstance(project).findFile(vFile);
              if (psiFile instanceof PsiJavaFile) {
                String name = null;
                // take the name of the first found public top-level class, otherwise the name of any available top-level class
                for (PsiClass aClass : ((PsiJavaFile)psiFile).getClasses()) {
                  if (name == null) {
                    name = aClass.getName();
                    if (isPublic(aClass)) {
                      break;
                    }
                  }
                  else if (isPublic(aClass)){
                    name = aClass.getName();
                    break;
                  }
                }
                if (name != null) {
                  return name;
                }
              }
            }
            return FileUtil.getNameWithoutExtension(scratchFile);
          }
        });
        srcFile = new File(srcDir, srcFileName + ".java");
        FileUtil.copy(scratchFile, srcFile);
      }

      final Collection<File> files = Collections.singleton(srcFile);

      final Set<File> cp = new LinkedHashSet<File>();
      for (String s : moduleRootManager.orderEntries().compileOnly().recursively().exportedOnly().withoutSdk().getPathsList().getPathList()) {
        cp.add(new File(s));
      }
      final List<File> platformCp = new ArrayList<File>();
      for (String s : moduleRootManager.orderEntries().compileOnly().sdkOnly().getPathsList().getPathList()) {
        platformCp.add(new File(s));
      }

      final List<String> options = new ArrayList<String>();
      options.add("-g"); // always compile with debug info
      final JavaSdkVersion sdkVersion = JavaSdk.getInstance().getVersion(targetSdk);
      if (sdkVersion != null) {
        final String langLevel = "1." + Integer.valueOf(3 + sdkVersion.getMaxLanguageLevel().ordinal());
        options.add("-source");
        options.add(langLevel);
        options.add("-target");
        options.add(langLevel);
      }
      options.add("-proc:none"); // disable annotation processing

      final Collection<ClassObject> result = CompilerManager.getInstance(project).compileJavaCode(
        options, platformCp, cp, Collections.<File>emptyList(), files, outputDir
      );
      for (ClassObject classObject : result) {
        final byte[] bytes = classObject.getContent();
        if (bytes != null) {
          FileUtil.writeToFile(new File(classObject.getPath()), bytes);
        }
      }
    }
    catch (CompilationException e) {
      for (CompilationException.Message m : e.getMessages()) {
        context.addMessage(m.getCategory(), m.getText(), scratchUrl, m.getLine(), m.getColumn());
      }
    }
    catch (IOException e) {
      context.addMessage(CompilerMessageCategory.ERROR, e.getMessage(), scratchUrl, -1, -1);
    }
    return true;
  }

  private static boolean isPublic(PsiClass aClass) {
    final PsiModifierList modifiers = aClass.getModifierList();
    return modifiers != null && modifiers.hasModifierProperty(PsiModifier.PUBLIC);
  }

  @Override
  public void projectOpened() {
  }

  @Override
  public void projectClosed() {
  }

  @Override
  public void initComponent() {
  }

  @Override
  public void disposeComponent() {
  }

  @NotNull
  @Override
  public String getComponentName() {
    return "JavaScratchCompilationSupport";
  }
}
