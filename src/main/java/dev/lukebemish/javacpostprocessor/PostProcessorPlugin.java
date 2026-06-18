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
import org.objectweb.asm.Type;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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

        var capturing = new CapturingListener(task);

        var context = new PostProcessor.Context() {
            @Override
            public JavacTask task() {
                return task;
            }

            @Override
            public BinaryBridge binaryBridge() {
                Elements elements = task.getElements();
                var taskCtx = ((BasicJavacTask) task).getContext();
                var compiler = JavaCompiler.instance(taskCtx);
                var types = task.getTypes();
                return new BinaryBridge() {
                    @Override
                    public @Nullable TypeElement elementByInternalName(String internalName) {
                        return getElementFromInternalName(internalName, compiler, capturing, elements);
                    }

                    @Override
                    public @Nullable TypeMirror typeByDescriptor(String descriptor) {
                        return switch (descriptor.charAt(0)) {
                            case 'Z' -> types.getPrimitiveType(TypeKind.BOOLEAN);
                            case 'I' -> types.getPrimitiveType(TypeKind.INT);
                            case 'B' -> types.getPrimitiveType(TypeKind.BYTE);
                            case 'S' -> types.getPrimitiveType(TypeKind.SHORT);
                            case 'C' -> types.getPrimitiveType(TypeKind.CHAR);
                            case 'J' -> types.getPrimitiveType(TypeKind.LONG);
                            case 'F' -> types.getPrimitiveType(TypeKind.FLOAT);
                            case 'D' -> types.getPrimitiveType(TypeKind.DOUBLE);
                            case 'V' -> types.getPrimitiveType(TypeKind.VOID);
                            case '[' -> {
                                var component = typeByDescriptor(descriptor.substring(1));
                                yield component == null ? null : types.getArrayType(component);
                            }
                            case 'L' -> {
                                var element = elementByInternalName(Type.getType(descriptor).getInternalName());
                                yield element == null ? null : element.asType();
                            }
                            default -> null;
                        };
                    }
                };
            }

            @Override
            public CommonSuperClassFinder commonSuperClassFinder() {
                Elements elements = task.getElements();
                var taskCtx = ((BasicJavacTask) task).getContext();
                var compiler = JavaCompiler.instance(taskCtx);
                var types = task.getTypes();
                return (class1, class2) -> attemptGetCommonSuperClass(types, elements, compiler, capturing, class1, class2);
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

        task.addTaskListener(capturing);
    }

    private static @Nullable TypeElement getElementFromInternalName(String internalName, JavaCompiler compiler, CapturingListener capturing, Elements elements) {
        // This approach _cannot_ find local/anonymous classes
        Element element = compiler.resolveBinaryNameOrIdent(internalName.replace('/', '.'));
        // However! Conveniently, such classes should be compiled in the same compilation as they are referenced!
        // So we can capture 'em and feed 'em around as needed here
        if (!(element instanceof TypeElement)) {
            element = capturing.NESTED_TYPE_ELEMENTS.get(internalName.replace('/', '.'));
        }
        if (element instanceof TypeElement typeElement && elements.getBinaryName(typeElement).contentEquals(internalName.replace('/', '.'))) {
            return typeElement;
        }
        return null;
    }

    private static @Nullable String attemptGetCommonSuperClass(Types types, Elements elements, JavaCompiler compiler, CapturingListener capturing, String type1, String type2) {
        Element element1 = getElementFromInternalName(type1, compiler, capturing, elements);
        Element element2 = getElementFromInternalName(type1, compiler, capturing, elements);
        if (element1 instanceof TypeElement typeElement1 && element2 instanceof TypeElement typeElement2) {
            if (types.isAssignable(typeElement1.asType(), typeElement2.asType())) {
                return type2;
            }
            if (types.isAssignable(typeElement2.asType(), typeElement1.asType())) {
                return type1;
            }
            if (typeElement1.getKind().isInterface() || typeElement2.getKind().isInterface()) {
                return Object.class.getName().replace('.', '/');
            }
            do {
                typeElement1 = (TypeElement) types.asElement(typeElement1.getSuperclass());
            } while (!types.isAssignable(typeElement2.asType(), typeElement1.asType()));
            return elements.getBinaryName(typeElement1).toString().replace('.', '/');
        }
        return null;
    }

    private static class CapturingListener implements TaskListener {
        private final Map<String, TypeElement> NESTED_TYPE_ELEMENTS;
        private final JavacTask task;

        CapturingListener(JavacTask task) {
            this.task = task;
            NESTED_TYPE_ELEMENTS = new HashMap<>();
        }

        @Override
        public void finished(TaskEvent e) {
            if (e.getKind() == TaskEvent.Kind.GENERATE && e.getTypeElement().getNestingKind() == NestingKind.TOP_LEVEL) {
                NESTED_TYPE_ELEMENTS.clear();
            } else if (e.getKind() != TaskEvent.Kind.ANALYZE && e.getKind() != TaskEvent.Kind.GENERATE) {
                return;
            }
            var typeElement = e.getTypeElement();
            if (typeElement.getNestingKind() == NestingKind.ANONYMOUS || typeElement.getNestingKind() == NestingKind.LOCAL) {
                String name = task.getElements().getBinaryName(typeElement).toString();
                NESTED_TYPE_ELEMENTS.put(name, typeElement);
            }
        }
    }
}
