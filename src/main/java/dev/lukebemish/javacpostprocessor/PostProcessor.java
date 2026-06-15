package dev.lukebemish.javacpostprocessor;

import com.sun.source.util.JavacTask;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassVisitor;

import javax.tools.JavaFileManager;

public interface PostProcessor {
    /**
     * @deprecated prefer {@link #context(Context)}
     */
    @Deprecated
    default void init(JavacTask task) {}
    default void context(Context context) {}

    String name();

    ClassVisitor visit(ClassVisitor next, String binaryName, JavaFileManager fileManager, JavaFileManager.Location location);

    interface Context {
        JavacTask task();
        CommonSuperClassFinder commonSuperClassFinder();

        /**
         * Attempts to locate a common superclass from two type internal names.
         */
        interface CommonSuperClassFinder {
            @Nullable String findCommonSuperClass(String class1, String class2);
        }
    }
}
