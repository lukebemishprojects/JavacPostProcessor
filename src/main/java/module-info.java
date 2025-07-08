import com.sun.source.util.Plugin;
import dev.lukebemish.javacpostprocessor.PostProcessor;
import dev.lukebemish.javacpostprocessor.PostProcessorPlugin;

module dev.lukebemish.javacpostprocessor {
    requires jdk.compiler;
    requires org.objectweb.asm;
    
    provides Plugin with PostProcessorPlugin;
    uses PostProcessor;
}