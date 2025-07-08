package dev.lukebemish.javacpostprocessor;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import javax.lang.model.element.Element;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;

public class PostProcessorPlugin implements Plugin {
    @Override
    public String getName() {
        return "dev.lukebemish.javac-post-processor";
    }

    private final List<PostProcessor> processors;

    public PostProcessorPlugin() {
        var services = ServiceLoader.load(PostProcessor.class, PostProcessorPlugin.class.getClassLoader());
        processors = services.stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    @Override
    public void init(JavacTask task, String... args) {
        var enabledProcessors = new ArrayList<PostProcessor>();
        var enabledNames = new HashSet<>(Arrays.asList(args));
        for (var processor : processors) {
            if (enabledNames.contains(processor.name())) {
                enabledProcessors.add(processor);
                enabledNames.remove(processor.name());
            }
        }
        if (!enabledNames.isEmpty()) {
            throw new IllegalArgumentException("Post-processor(s) not present: " + String.join(", ", enabledNames));
        }

        for (var processor : enabledProcessors) {
            processor.init(task);
        }

        task.addTaskListener(new TaskListener() {
            @Override
            public void finished(TaskEvent e) {
                if (e.getKind() != TaskEvent.Kind.GENERATE) {
                    return;
                }

                Elements elements = task.getElements();
                var element = e.getTypeElement();
                if (element == null) {
                    return;
                }
                try {
                    var taskCtx = ((BasicJavacTask) task).getContext();
                    var fileManager = taskCtx.get(JavaFileManager.class);
                    var classWriter = com.sun.tools.javac.jvm.ClassWriter.instance(taskCtx);
                    JavaFileManager.Location fileLocation;
                    if (classWriter.multiModuleMode) {
                        Element moduleElement = element;
                        while (!(moduleElement instanceof ModuleElement)) {
                            if (moduleElement instanceof PackageElement packageElement) {
                                moduleElement = packageElement.getEnclosingElement();
                            } else if (moduleElement instanceof TypeElement typeElement) {
                                moduleElement = typeElement.getEnclosingElement();
                            }
                        }
                        fileLocation = fileManager.getLocationForModule(StandardLocation.CLASS_OUTPUT, ((ModuleElement) moduleElement).getQualifiedName().toString());
                    } else {
                        fileLocation = StandardLocation.CLASS_OUTPUT;
                    }
                    var outFile = fileManager.getJavaFileForOutput(
                            fileLocation,
                            elements.getBinaryName(element).toString(),
                            JavaFileObject.Kind.CLASS,
                            e.getSourceFile()
                    );

                    var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                    ClassVisitor visitor = writer;
                    for (var processor : enabledProcessors) {
                        visitor = processor.visit(visitor, elements.getBinaryName(element).toString(), fileManager, fileLocation);
                    }
                    if (visitor != writer) {
                        try (var is = outFile.openInputStream()) {
                            var reader = new ClassReader(is);
                            reader.accept(visitor, 0);
                            try (var os = outFile.openOutputStream()) {
                                os.write(writer.toByteArray());
                            }
                        }
                    }
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        });
    }
}
