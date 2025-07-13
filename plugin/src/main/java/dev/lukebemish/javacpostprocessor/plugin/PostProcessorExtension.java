package dev.lukebemish.javacpostprocessor.plugin;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.compile.JavaCompile;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

public abstract class PostProcessorExtension {
    public abstract ListProperty<String> getPlugins();

    @SuppressWarnings("UnstableApiUsage")
    @Inject
    public PostProcessorExtension(JavaCompile task) {
        task.getOptions().getCompilerArgumentProviders().add(() -> {
            var plugins = getPlugins().get();
            var list = new ArrayList<String>();
            if (!plugins.isEmpty()) {
                list.add("-Xplugin:dev.lukebemish.javac-post-processor "+String.join(" ", plugins));
            }
            return list;
        });
        task.getOptions().getForkOptions().getJvmArgumentProviders().add(() -> {
            var plugins = getPlugins().get();
            var list = new ArrayList<String>();
            if (!plugins.isEmpty()) {
                list.addAll(List.of(
                        "--add-exports=jdk.compiler/com.sun.tools.javac.api=dev.lukebemish.javacpostprocessor",
                        "--add-exports=jdk.compiler/com.sun.tools.javac.jvm=dev.lukebemish.javacpostprocessor",
                        "--add-exports=jdk.compiler/com.sun.tools.javac.util=dev.lukebemish.javacpostprocessor"
                ));
                // As gradle shoves everything onto the non-module annotation classpath, we need to expose this to ALL-UNNAMED too
                list.addAll(List.of(
                        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                        "--add-exports=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
                        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
                ));
            }
            return list;
        });
    }
}
