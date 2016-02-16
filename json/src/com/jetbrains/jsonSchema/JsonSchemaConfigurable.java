package com.jetbrains.jsonSchema;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.NamedConfigurable;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.CollectConsumer;
import com.jetbrains.jsonSchema.impl.JsonSchemaReader;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.io.IOException;

/**
 * @author Irina.Chernushina on 2/2/2016.
 */
public class JsonSchemaConfigurable extends NamedConfigurable<JsonSchemaMappingsConfigurationBase.SchemaInfo> {
  private final Project myProject;
  @NotNull private final VirtualFile myFile;
  @NotNull private final JsonSchemaMappingsConfigurationBase.SchemaInfo mySchema;
  @Nullable private final Runnable myTree;
  private JsonSchemaMappingsView myView;
  private String myDisplayName;

  public JsonSchemaConfigurable(Project project,
                                @NotNull VirtualFile schemaFile, @NotNull JsonSchemaMappingsConfigurationBase.SchemaInfo schema,
                                @Nullable Runnable updateTree) {
    super(true, updateTree);
    myProject = project;
    myFile = schemaFile;
    mySchema = schema;
    myTree = updateTree;
    myDisplayName = mySchema.getName();
  }

  @NotNull
  public JsonSchemaMappingsConfigurationBase.SchemaInfo getSchema() {
    return mySchema;
  }

  @Override
  public void setDisplayName(String name) {
    myDisplayName = name;
  }

  @Override
  public JsonSchemaMappingsConfigurationBase.SchemaInfo getEditableObject() {
    return mySchema;
  }

  @Override
  public String getBannerSlogan() {
    return mySchema.getName();
  }

  @Override
  public JComponent createOptionsPanel() {
    if (myView == null) {
      myView = new JsonSchemaMappingsView(myProject);
    }
    return myView.getComponent();
  }

  @Nls
  @Override
  public String getDisplayName() {
    return myDisplayName;
  }

  @Nullable
  @Override
  public String getHelpTopic() {
    return JsonSchemaMappingsConfigurable.SETTINGS_JSON_SCHEMA;
  }

  @Override
  public boolean isModified() {
    if (myView == null) return false;
    if (!FileUtil.toSystemDependentName(mySchema.getRelativePathToSchema()).equals(myView.getSchemaSubPath())) return false;
    return !Comparing.equal(myView.getData(), mySchema.getPatterns());
  }

  @Override
  public void apply() throws ConfigurationException {
    if (myView == null) return;
    doValidation();
    mySchema.setName(myDisplayName);
    mySchema.setPatterns(myView.getData());
    mySchema.setRelativePathToSchema(myView.getSchemaSubPath());
  }

  private void doValidation() throws ConfigurationException {
    if (StringUtil.isEmptyOrSpaces(myDisplayName)) throw new ConfigurationException("Schema name is empty");
    if (StringUtil.isEmptyOrSpaces(myView.getSchemaSubPath())) throw new ConfigurationException("Schema path is empty");
    if (!Comparing.equal(myView.getSchemaSubPath(), mySchema.getRelativePathToSchema())) {
      final CollectConsumer<String> collectConsumer = new CollectConsumer<>();
      final File file = new File(myProject.getBasePath(), myView.getSchemaSubPath());
      try {
        if (!JsonSchemaReader.isJsonSchema(FileUtil.loadFile(file), collectConsumer)) {
          final String message;
          if (collectConsumer.getResult().isEmpty()) message = "Can not read JSON schema from file (Unknown reason)";
          else message = "Can not read JSON schema from file: " + StringUtil.join(collectConsumer.getResult(), "; ");
          throw new ConfigurationException(message);
        }
      }
      catch (IOException e) {
        throw new ConfigurationException("Can not read JSON schema from file: " + e.getMessage());
      }
    }
  }

  @Override
  public void reset() {
    if (myView == null) return;
    myView.setItems(myFile, mySchema.getPatterns());
    setDisplayName(mySchema.getName());
  }

  public JsonSchemaMappingsConfigurationBase.SchemaInfo getUiSchema() {
    final JsonSchemaMappingsConfigurationBase.SchemaInfo info = new JsonSchemaMappingsConfigurationBase.SchemaInfo();
    info.setApplicationLevel(mySchema.isApplicationLevel());
    if (myView != null) {
      info.setName(getDisplayName());
      info.setPatterns(myView.getData());
      info.setRelativePathToSchema(myView.getSchemaSubPath());
    } else {
      info.setName(mySchema.getName());
      info.setPatterns(mySchema.getPatterns());
      info.setRelativePathToSchema(mySchema.getRelativePathToSchema());
    }
    return info;
  }

  @Override
  public void disposeUIResources() {

  }
}
