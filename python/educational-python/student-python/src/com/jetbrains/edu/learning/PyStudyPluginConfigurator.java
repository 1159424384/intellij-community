package com.jetbrains.edu.learning;

import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.jetbrains.edu.courseFormat.Course;
import com.jetbrains.edu.learning.actions.PyTwitterAction;
import com.jetbrains.edu.learning.actions.StudyAfterCheckAction;
import com.jetbrains.edu.learning.settings.ModifiableSettingsPanel;
import com.jetbrains.edu.learning.settings.PySettingsPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PyStudyPluginConfigurator extends StudyBasePluginConfigurator {
  
  @NotNull
  @Override
  public DefaultActionGroup getActionGroup(Project project) {
    final DefaultActionGroup baseGroup = super.getActionGroup(project);
    final DefaultActionGroup group = new DefaultActionGroup();
    group.add(new PyStudyCheckAction());
    group.addAll(baseGroup);
    return group;
  }

  @NotNull
  @Override
  public String getDefaultHighlightingMode() {
    return "python";
  }

  @NotNull
  @Override
  public String getLanguageScriptUrl() {
    return getClass().getResource("/python.js").toExternalForm();
  }

  @Nullable
  @Override
  public StudyAfterCheckAction[] getAfterCheckActions() {
    return new StudyAfterCheckAction[]{new PyTwitterAction()};
  }

  @Override
  public boolean accept(@NotNull Project project) {
    StudyTaskManager taskManager = StudyTaskManager.getInstance(project);
    if (taskManager == null) return false;
    Course course = taskManager.getCourse();
    return course != null && "Python".equals(course.getLanguage()) && "PyCharm".equals(course.getCourseType());
  }

  @Nullable
  @Override
  public ModifiableSettingsPanel getSettingsPanel() {
    return new PySettingsPanel();
  }
}
