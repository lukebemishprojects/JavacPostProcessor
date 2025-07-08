package dev.lukebemish.javacpostprocessor.plugin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.compile.JavaCompile;

public class PostProcessorGradlePlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getTasks().withType(JavaCompile.class).configureEach(javaCompile -> {
            javaCompile.getExtensions().add(PostProcessorExtension.class, "javacPostProcessor", project.getObjects().newInstance(PostProcessorExtension.class, javaCompile));
        });
    }
}
