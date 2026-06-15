package dev.lukebemish.javacpostprocessor;

import com.google.auto.service.AutoService;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.main.JavaCompiler;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceLoader;

@AutoService(Plugin.class)
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

        var context = new PostProcessor.Context() {
            @Override
            public JavacTask task() {
                return task;
            }

            @Override
            public CommonSuperClassFinder commonSuperClassFinder() {
                Elements elements = task.getElements();
                var taskCtx = ((BasicJavacTask) task).getContext();
                var compiler = JavaCompiler.instance(taskCtx);
                var types = task.getTypes();
                return (class1, class2) -> attemptGetCommonSuperClass(types, elements, compiler, class1, class2);
            }
        };

        for (var processor : enabledProcessors) {
            processor.init(task);
            processor.context(context);
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
                    var commonSuperClassFinder = context.commonSuperClassFinder();
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
                    if (element.getKind() != ElementKind.MODULE && !elements.getBinaryName(element).toString().contains("module-info")) {
                        // We specifically avoid processing module-info
                        var outFile = fileManager.getJavaFileForOutput(
                            fileLocation,
                            elements.getBinaryName(element).toString(),
                            JavaFileObject.Kind.CLASS,
                            e.getSourceFile()
                        );

                        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
                            @Override
                            protected String getCommonSuperClass(String type1, String type2) {
                                var found = commonSuperClassFinder.findCommonSuperClass(type1, type2);
                                if (found != null) {
                                    return found;
                                }
                                return super.getCommonSuperClass(type1, type2);
                            }
                        };
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
                    }
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        });
    }

    private static @Nullable String attemptGetCommonSuperClass(Types types, Elements elements, JavaCompiler compiler, String type1, String type2) {
        Element element1 = compiler.resolveBinaryNameOrIdent(type1);
        Element element2 = compiler.resolveBinaryNameOrIdent(type2);
        if (element1 instanceof TypeElement typeElement1 && element2 instanceof TypeElement typeElement2) {
            if (types.isAssignable(typeElement1.asType(), typeElement2.asType())) {
                return type2;
            }
            if (types.isAssignable(typeElement2.asType(), typeElement1.asType())) {
                return type1;
            }
            if (typeElement1.getKind().isInterface() || typeElement2.getKind().isInterface()) {
                return Object.class.getName();
            }
            do {
                typeElement1 = (TypeElement) types.asElement(typeElement1.getSuperclass());
            } while (!types.isAssignable(typeElement2.asType(), typeElement1.asType()));
            return elements.getBinaryName(typeElement1).toString();
        }
        return null;
    }
}
