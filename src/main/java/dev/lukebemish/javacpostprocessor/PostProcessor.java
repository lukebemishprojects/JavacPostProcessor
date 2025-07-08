package dev.lukebemish.javacpostprocessor;

import com.sun.source.util.JavacTask;
import org.objectweb.asm.ClassVisitor;

import javax.tools.JavaFileManager;

public interface PostProcessor {
    void init(JavacTask task);
    
    String name();
    
    ClassVisitor visit(ClassVisitor next, String binaryName, JavaFileManager fileManager, JavaFileManager.Location location);
}
